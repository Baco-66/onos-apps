/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <core.p4>
#include <v1model.p4>

// CPU_PORT specifies the P4 port number associated to controller packet-in and
// packet-out. All packets forwarded via this port will be delivered to the
// controller as P4Runtime PacketIn messages. Similarly, PacketOut messages from
// the controller will be seen by the P4 pipeline as coming from the CPU_PORT.
#define CPU_PORT 200 

#define MAX_HOPS 4

typedef bit<32>  session_id_t;
typedef bit<9>   port_num_t;
typedef bit<48>  mac_addr_t;
typedef bit<16>  mcast_group_id_t;
typedef bit<8>   switch_id_t;

const bit<8> CLONE_TO_CONTROLLER = 1;
const bit<16> TYPE_BROADCAST = 0x9001;

//------------------------------------------------------------------------------
// HEADER DEFINITIONS
//------------------------------------------------------------------------------

header ethernet_t {
    mac_addr_t  dst_addr;
    mac_addr_t  src_addr;
    bit<16>     ether_type;
}

// Marquer header. This is added to packets sent out port 1, which always 
// corresponds to the wireless antenna. The marker stops the switch from 
// repeatedly trasmiting the same packet. 
header marquer_t {
    bit<8>      switch_id;
    mac_addr_t  dst_addr;
    bit<16>     ether_type;
}

// Packet-in header. Prepended to packets sent to the CPU_PORT and used by the
// P4Runtime server (Stratum) to populate the PacketIn message metadata fields.
// Here we use it to carry the original ingress port where the packet was
// received.
@controller_header("packet_in")
header cpu_in_header_t {
    port_num_t  ingress_port;
    bit<7>      _pad;
}

// Packet-out header. Prepended to packets received from the CPU_PORT. Fields of
// this header are populated by the P4Runtime server based on the P4Runtime
// PacketOut metadata fields. Here we use it to inform the P4 pipeline on which
// port this packet-out should be transmitted.
@controller_header("packet_out")
header cpu_out_header_t {
    port_num_t  egress_port;
    bit<7>      _pad;
}

struct parsed_headers_t {
    cpu_out_header_t     cpu_out;
    cpu_in_header_t      cpu_in;
    ethernet_t           ethernet;
    marquer_t[MAX_HOPS]  marquer;
}

struct local_metadata_t {
    switch_id_t          switch_id;
    bool                 is_multicast;
    @field_list(CLONE_TO_CONTROLLER)
    port_num_t           host_port;
}


//------------------------------------------------------------------------------
// INGRESS PIPELINE
//------------------------------------------------------------------------------

parser ParserImpl (packet_in packet,
                   out parsed_headers_t hdr,
                   inout local_metadata_t local_metadata,
                   inout standard_metadata_t standard_metadata) {

    state start {
        transition select(standard_metadata.ingress_port) {
            CPU_PORT: parse_packet_out;
            default: parse_ethernet;
        }
    }

    state parse_packet_out {
        packet.extract(hdr.cpu_out);
        transition parse_ethernet;
    }

    state parse_ethernet {
        packet.extract(hdr.ethernet);
        transition select(hdr.ethernet.ether_type){
            TYPE_BROADCAST: parse_marquer;
            default: accept;
        }
    }

    state parse_marquer{
        packet.extract(hdr.marquer.next);
        transition select(hdr.marquer.last.ether_type){
            TYPE_BROADCAST: parse_marquer;
            default: accept;
        }
    }

}


control VerifyChecksumImpl(inout parsed_headers_t hdr, inout local_metadata_t meta) {
    // Not used here. We assume all packets have valid checksum, if not, we let the end hosts detect errors.
    apply { /* EMPTY */ }
}


control IngressPipeImpl (inout parsed_headers_t    hdr,
                         inout local_metadata_t    local_metadata,
                         inout standard_metadata_t standard_metadata) {

    // Drop action shared by many tables.
    action drop() {
        mark_to_drop(standard_metadata);
    }

    // *** L2 BRIDGING
    //
    // This pipeline forwards packets based only on their Ethernet destination
    // address. There are three types of L2 entries that we support:
    // 
    // 1. Unicast entries: these which will be filled in by the control plane when 
    //    the location (port) of new hosts is learned.
    // 2. Broadcast entries: used when the destination address is FF:FF:FF:FF:FF:FF
    // 3. Default entrie: sends the packet out port 1, which always corresponds to 
    // the wireless antena

    // --- l2_exact_table (for unicast entries) --------------------------------

    action set_egress_port(port_num_t port_num) {
        standard_metadata.egress_spec = port_num;
    }

    action add_switch_id(port_num_t port_num, switch_id_t switch_id_value) {
        set_egress_port(port_num);
        local_metadata.switch_id = switch_id_value;
    }

    action set_multicast_group(mcast_group_id_t gid, switch_id_t switch_id_value) {
        // gid will be used by the Packet Replication Engine (PRE) in the
        // Traffic Manager--located right after the ingress pipeline, to
        // replicate a packet to multiple egress ports, specified by the control
        // plane by means of P4Runtime MulticastGroupEntry messages.
        standard_metadata.mcast_grp = gid;
        local_metadata.is_multicast = true;
        local_metadata.switch_id = switch_id_value;
    }


    table l2_exact_table {
        key = {
            hdr.ethernet.dst_addr: exact;
        }
        actions = {
            set_egress_port;
            set_multicast_group;
            @defaultonly add_switch_id;
        }
        // The @name annotation is used here to provide a name to this table
        // counter, as it will be needed by the compiler to generate the
        // corresponding P4Info entity.
        @name("l2_exact_table_counter")
        counters = direct_counter(CounterType.packets_and_bytes);
    }

    // *** ACL
    //
    // Provides ways to override a previous forwarding decision, for example
    // requiring that a packet is cloned/sent to the CPU, or dropped.
    //
    // We use this table to clone all ARP packets to the control plane, so to
    // enable host discovery. When the location of a new host is discovered, the
    // controller is expected to update the L2 tables with the correspionding
    // entries.

    action send_to_cpu() {
        standard_metadata.egress_spec = CPU_PORT;
    }

    action clone_to_cpu(session_id_t session_id) {
        // Cloning is achieved by using the clone3 primitive. Here we set the 
        // type of clone operation (ingress-to-egress pipeline), the clone 
        // session ID (defined by the controller), and the tag of the fields we 
        // want to preserve for the cloned packet replica, which in this case 
        // correspond to the ingress_port of the packet.
        local_metadata.host_port = standard_metadata.ingress_port;
	    clone_preserving_field_list(CloneType.I2E, session_id, CLONE_TO_CONTROLLER);
    }

    table acl_table {
        key = {
            standard_metadata.ingress_port: ternary;
            hdr.ethernet.dst_addr:          ternary;
            hdr.ethernet.src_addr:          ternary;
            hdr.ethernet.ether_type:        ternary;
        }
        actions = {
            send_to_cpu;
            clone_to_cpu;
            drop;
        }
        @name("acl_table_counter")
        counters = direct_counter(CounterType.packets_and_bytes);
    }

    // DEBUG TABLE

    table debug {
        key = {
            local_metadata.host_port     : exact;
            //local_metadata.switch_id   : exact;
            //hdr.marquer[0].switch_id   : exact;
            //hdr.marquer[1].switch_id   : exact;
            //hdr.marquer[2].switch_id   : exact;
            //hdr.marquer[3].switch_id   : exact;
        }
        actions = {
            NoAction;
        }
        default_action = NoAction;
    }

    apply {
        
        if (hdr.cpu_out.isValid()) {

            // Set the packet egress port to that found in the cpu_out header
            standard_metadata.egress_spec = hdr.cpu_out.egress_port;

            // Remove (set invalid) the cpu_out header
            hdr.cpu_out.setInvalid();

            // Exit the pipeline here (no need to go through other tables)
            exit;
        }

        // This needs to go to a better place, because right here it will always insert a new rule.
        // It need to be called when the packet does not come from the broadcast port
        // This conditional can be better.
        if (standard_metadata.ingress_port == 1) {
            if (hdr.marquer[0].isValid()){
                hdr.ethernet.dst_addr = hdr.marquer[0].dst_addr;
            } else {
                mark_to_drop(standard_metadata);
                exit;
            }
        } else {
            acl_table.apply();
        }

        l2_exact_table.apply();

        /*
        if (standard_metadata.ingress_port == 1){
            l2_exact_table.apply();
        } else if (l2_exact_table.apply().hit){
            if (local_metadata.is_multicast == true){
                if (){
                    acl_table.apply();
                }
            } else {
                // nothing
            }
        } else {
            acl_table.apply();
        }
        */


    }
}

//------------------------------------------------------------------------------
// EGRESS PIPELINE
//------------------------------------------------------------------------------


control EgressPipeImpl (inout parsed_headers_t hdr,
                        inout local_metadata_t local_metadata,
                        inout standard_metadata_t standard_metadata) {

    // DEBUG TABLE

    table debug {
        key = {
            local_metadata.host_port        : exact;
            standard_metadata.ingress_port  : exact;
            hdr.cpu_in.ingress_port         : exact;
        }
        actions = {
            NoAction;
        }
        default_action = NoAction;
    }


    apply {

        if (standard_metadata.egress_port == CPU_PORT) { 
            // If the packet is to be forwarded to the CPU port e.g., if in 
            // ingress we matched on the ACL table with action send/clone_to_cpu...

            // Set cpu_in header as valid
            hdr.cpu_in.setValid(); 
            // Set the cpu_in.ingress_port field to the original 
            // packet's ingress port (standard_metadata.ingress_port 
            // stored in local_metadata.host_port).
            hdr.cpu_in.ingress_port = local_metadata.host_port; 

            exit;
        }



        if (standard_metadata.egress_port == 1) {

            // Check if any marquer id matches the switch_id from metadata, or if the max size has been reached
            if ((hdr.marquer[0].isValid() && hdr.marquer[0].switch_id == local_metadata.switch_id) ||
                (hdr.marquer[1].isValid() && hdr.marquer[1].switch_id == local_metadata.switch_id) ||
                (hdr.marquer[2].isValid() && hdr.marquer[2].switch_id == local_metadata.switch_id) ||
                hdr.marquer[3].isValid()) {
                mark_to_drop(standard_metadata);
            } else {
                // Add the switch id marquer in the right place
                if (hdr.ethernet.ether_type != TYPE_BROADCAST) {
                    hdr.marquer[0].setValid();
                    hdr.marquer[0].switch_id = local_metadata.switch_id;
                    hdr.marquer[0].ether_type = hdr.ethernet.ether_type;
                    hdr.ethernet.ether_type = TYPE_BROADCAST;
                    hdr.marquer[0].dst_addr = hdr.ethernet.dst_addr;
                }
                else if (hdr.marquer[0].ether_type != TYPE_BROADCAST) {
                    hdr.marquer[1].setValid();
                    hdr.marquer[1].switch_id = local_metadata.switch_id;
                    hdr.marquer[1].ether_type = hdr.marquer[0].ether_type;
                    hdr.marquer[0].ether_type = TYPE_BROADCAST;
                }
                else if (hdr.marquer[1].ether_type != TYPE_BROADCAST) {
                    hdr.marquer[2].setValid();
                    hdr.marquer[2].switch_id = local_metadata.switch_id;
                    hdr.marquer[2].ether_type = hdr.marquer[1].ether_type;
                    hdr.marquer[1].ether_type = TYPE_BROADCAST;
                }
                else if (hdr.marquer[2].ether_type != TYPE_BROADCAST) {
                    hdr.marquer[3].setValid();
                    hdr.marquer[3].switch_id = local_metadata.switch_id;
                    hdr.marquer[3].ether_type = hdr.marquer[2].ether_type;
                    hdr.marquer[2].ether_type = TYPE_BROADCAST;
                }
                // Set the destination MAC to the broadcast address so the antenas 
                // recieve the packets
                hdr.ethernet.dst_addr = 0xFFFFFFFFFFFF;
            }
        

        } else {

            // If this is a multicast packet make sure we are not replicating 
            // the packet on the same port where it was received
            if (local_metadata.is_multicast == true && standard_metadata.ingress_port == standard_metadata.egress_port) {
                mark_to_drop(standard_metadata);
                exit;
            }

            // If the destination has been reached, put the ether_type in the 
            // correct place and discard all marquers
            if (hdr.ethernet.ether_type != TYPE_BROADCAST) {
            }
            else if (hdr.marquer[0].ether_type != TYPE_BROADCAST) {
                hdr.ethernet.ether_type = hdr.marquer[0].ether_type;
            }
            else if (hdr.marquer[1].ether_type != TYPE_BROADCAST) {
                hdr.ethernet.ether_type = hdr.marquer[1].ether_type;
            }
            else if (hdr.marquer[2].ether_type != TYPE_BROADCAST) {
                hdr.ethernet.ether_type = hdr.marquer[2].ether_type;
            }
            else if (hdr.marquer[3].ether_type != TYPE_BROADCAST) {
                hdr.ethernet.ether_type = hdr.marquer[3].ether_type;
            }
            hdr.marquer[0].setInvalid();
            hdr.marquer[1].setInvalid();
            hdr.marquer[2].setInvalid();
            hdr.marquer[3].setInvalid();
        }


    }
}


control ComputeChecksumImpl(inout parsed_headers_t hdr, inout local_metadata_t local_metadata) {
    apply { /* EMPTY */ }
}


control DeparserImpl(packet_out packet, in parsed_headers_t hdr) {
    apply {
        packet.emit(hdr.cpu_in);
        packet.emit(hdr.ethernet);
        packet.emit(hdr.marquer);
    }
}


V1Switch(
    ParserImpl(),
    VerifyChecksumImpl(),
    IngressPipeImpl(),
    EgressPipeImpl(),
    ComputeChecksumImpl(),
    DeparserImpl()
) main;
