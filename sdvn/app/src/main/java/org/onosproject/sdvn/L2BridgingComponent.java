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

package org.onosproject.sdvn;

import java.util.Set;
import java.util.stream.Collectors;

import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupService;
import org.onosproject.net.host.HostEvent;
import org.onosproject.net.host.HostListener;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiActionParamId;
import org.onosproject.net.pi.model.PiMatchFieldId;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import static org.onosproject.sdvn.AppConstants.INITIAL_SETUP_DELAY;
import org.onosproject.sdvn.common.Utils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App component that configures devices to provide L2 bridging capabilities.
 */
@Component(
        immediate = true,
        enabled = true
)
public class L2BridgingComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final int DEFAULT_BROADCAST_GROUP_ID = 255;

    private final DeviceListener deviceListener = new InternalDeviceListener();
    private final HostListener hostListener = new InternalHostListener();

    private ApplicationId appId;

    //--------------------------------------------------------------------------
    // ONOS CORE SERVICE BINDING
    //
    // These variables are set by the Karaf runtime environment before calling
    // the activate() method.
    //--------------------------------------------------------------------------

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private GroupService groupService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MainComponent mainComponent;

    //--------------------------------------------------------------------------
    // COMPONENT ACTIVATION.
    //
    // When loading/unloading the app the Karaf runtime environment will call
    // activate()/deactivate().
    //--------------------------------------------------------------------------

    @Activate
    protected void activate() {
        appId = mainComponent.getAppId();

        // Register listeners to be informed about device and host events.
        deviceService.addListener(deviceListener);
        hostService.addListener(hostListener);
        // Schedule set up of existing devices. Needed when reloading the app.
        mainComponent.scheduleTask(this::setUpAllDevices, INITIAL_SETUP_DELAY);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        deviceService.removeListener(deviceListener);
        hostService.removeListener(hostListener);

        log.info("Stopped");
    }

    /**
     * Sets up everything necessary to support L2 broadcast on the given device.
     *
     * @param deviceId the device to set up
     */
    private void setUpDevice(DeviceId deviceId) {
        insertBroadcastGroup(deviceId);
        insertBroadcastFlowRules(deviceId);
        insertDefaultFlowRule(deviceId);
    }

    /**
     * Inserts an ALL group in the ONOS core to replicate packets on all ports.
     * <p>
     * ALL groups in ONOS are equivalent to P4Runtime packet replication engine
     * (PRE) Multicast groups.
     *
     * @param deviceId the device where to install the group
     */

    private void insertBroadcastGroup(DeviceId deviceId) {

        Set<PortNumber> ports = getPorts(deviceId);

        if (ports.isEmpty()) {
            // Stop here.
            log.warn("Device {} has 0 ports", deviceId);
            return;
        }

        log.info("Adding L2 multicast group with \"{}\" on {}...",
                ports, deviceId);

        // Forge group object.
        final GroupDescription multicastGroup = Utils.buildMulticastGroup(
                appId, deviceId, DEFAULT_BROADCAST_GROUP_ID, ports);

        // Insert.
        groupService.addGroup(multicastGroup);
    }


    /*
     * Insert flow rule that matches all unmatched ethernet traffic. This
     * will implement the traditional briding behavior that floods all
     * unmatched traffic.
     * <p>
     * This method will be called at component activation for each device
     * (switch) known by ONOS, and every time a new device-added event is
     * captured by the InternalDeviceListener defined below.
     * This method will also send the switch id value to the switch.
     *
     * @param deviceId device ID where to install the rules
     */

    @SuppressWarnings("unused")
    private void insertDefaultFlowRule(DeviceId deviceId) {

        log.info("Adding default rule on {}...", deviceId);

        // Action: set multicast group id
        final PiAction setDefaultAction = PiAction.builder()
                .withId(PiActionId.of("IngressPipeImpl.add_switch_id"))
                .withParameter(new PiActionParam(
                        PiActionParamId.of("port_num"),
                        1))
                .withParameter(new PiActionParam(
                        PiActionParamId.of("switch_id_value"),
                        Utils.getUniqueSessionId(deviceId)))
                .build();

        //  Build flow rule.
        final String tableId = "IngressPipeImpl.l2_exact_table";

        /* Chaged the method used here because of an error "Invalid representation of 'don't care' ternary match"
        In this github issue https://github.com/opennetworkinglab/ngsdn-tutorial/issues/93 the solution was to add this
        Also hid the PiCriterion acordingly */

        final FlowRule rule = Utils.buildFlowRuleDefaultAction(
                deviceId, appId, tableId,
                setDefaultAction);

        // Insert rules.
        flowRuleService.applyFlowRules(rule);
    }

    /**
     * Insert flow rules matching ethernet destination broadcast
     * address. Such packets should be processed by the multicast
     * group created before.
     * <p>
     * This method will be called at component activation for each device
     * (switch) known by ONOS, and every time a new device-added event is
     * captured by the InternalDeviceListener defined below.
     * This method will also send the switch id value to the switch.
     *
     * @param deviceId device ID where to install the rules
     */
    private void insertBroadcastFlowRules(DeviceId deviceId) {

        log.info("Adding L2 broadcast rules on {}...", deviceId);

        // Match exactly FF:FF:FF:FF:FF:FF
        final PiCriterion macBroadcastCriterion = PiCriterion.builder()
                .matchExact(
                        PiMatchFieldId.of("hdr.ethernet.dst_addr"),
                        MacAddress.valueOf("FF:FF:FF:FF:FF:FF").toBytes())
                .build();

        // Action: set multicast group id
        final PiAction setMcastGroupAction = PiAction.builder()
                .withId(PiActionId.of("IngressPipeImpl.set_multicast_group"))
                .withParameter(new PiActionParam(
                        PiActionParamId.of("gid"),
                        DEFAULT_BROADCAST_GROUP_ID))
                .withParameter(new PiActionParam(
                        PiActionParamId.of("switch_id_value"),
                        Utils.getUniqueSessionId(deviceId)))
                .build();

        final String tableId = "IngressPipeImpl.l2_exact_table";

        final FlowRule rule1 = Utils.buildFlowRule(
                deviceId, appId, tableId,
                macBroadcastCriterion, setMcastGroupAction);


        // Insert rule
        flowRuleService.applyFlowRules(rule1);
    }

    /**
     * Insert flow rules to forward packets to a given host located at the given
     * device and port.
     * <p>
     * This method will be called at component activation for each host known by
     * ONOS, and every time a new host-added event is captured by the
     * InternalHostListener defined below.
     *
     * @param host     host instance
     * @param deviceId device where the host is located
     * @param port     port where the host is attached to
     */
    private void learnHost(Host host, DeviceId deviceId, PortNumber port) {

        final String tableId = "IngressPipeImpl.l2_exact_table";
        // Match exactly on the host MAC address.
        final MacAddress hostMac = host.mac();

        log.info("Adding L2 unicast rule on {} for host {}:{} (port {})...",
                deviceId, host.id(), hostMac, port);

        final PiCriterion hostMacCriterion = PiCriterion.builder()
                .matchExact(PiMatchFieldId.of("hdr.ethernet.dst_addr"),
                        hostMac.toBytes())
                .build();

        // Action: set output port
        final PiAction l2UnicastAction = PiAction.builder()
                .withId(PiActionId.of("IngressPipeImpl.set_egress_port"))
                .withParameter(new PiActionParam(
                        PiActionParamId.of("port_num"),
                        port.toLong()))
                .build();

        // Forge flow rule.
        final FlowRule rule = Utils.buildFlowRule(
                deviceId, appId, tableId, hostMacCriterion, l2UnicastAction);

        // Insert.
        flowRuleService.applyFlowRules(rule);
    }

    //--------------------------------------------------------------------------
    // EVENT LISTENERS
    //
    // Events are processed only if isRelevant() returns true.
    //--------------------------------------------------------------------------

    /**
     * Listener of device events.
     */
    public class InternalDeviceListener implements DeviceListener {

        @Override
        public boolean isRelevant(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_ADDED:
                case DEVICE_AVAILABILITY_CHANGED:
                    break;
                default:
                    // Ignore other events.
                    return false;
            }
            // Process only if this controller instance is the master.
            final DeviceId deviceId = event.subject().id();
            return mastershipService.isLocalMaster(deviceId);
        }

        @Override
        public void event(DeviceEvent event) {
            final DeviceId deviceId = event.subject().id();
            if (deviceService.isAvailable(deviceId)) {
                // A P4Runtime device is considered available in ONOS when there
                // is a StreamChannel session open and the pipeline
                // configuration has been set.

                // Events are processed using a thread pool defined in the
                // MainComponent.
                mainComponent.getExecutorService().execute(() -> {
                    log.info("{} event! deviceId={}", event.type(), deviceId);

                    setUpDevice(deviceId);
                });
            }
        }
    }

    /**
     * Listener of host events.
     */
    public class InternalHostListener implements HostListener {

        @Override
        public boolean isRelevant(HostEvent event) {
            switch (event.type()) {
                case HOST_ADDED:
                    // Host added events will be generated by the
                    // HostLocationProvider by intercepting ARP/NDP packets.
                    break;
                case HOST_REMOVED:
                case HOST_UPDATED:
                case HOST_MOVED:
                default:
                    // Ignore other events.
                    return false;
            }
            // Process host event only if this controller instance is the master
            // for the device where this host is attached to.
            final Host host = event.subject();
            final DeviceId deviceId = host.location().deviceId();
            return mastershipService.isLocalMaster(deviceId);
        }

        @Override
        public void event(HostEvent event) {
            final Host host = event.subject();
            // Device and port where the host is located.
            final DeviceId deviceId = host.location().deviceId();
            final PortNumber port = host.location().port();

            mainComponent.getExecutorService().execute(() -> {
                log.info("{} event! host={}, deviceId={}, port={}",
                        event.type(), host.id(), deviceId, port);

                learnHost(host, deviceId, port);
            });
        }
    }

    //--------------------------------------------------------------------------
    // UTILITY METHODS
    //--------------------------------------------------------------------------

    /**
     * Returns a set of ports for the given device that are used to connect
     * hosts to the fabric.
     *
     * @param deviceId device ID
     * @return set of host facing ports
     */

    private Set<PortNumber> getPorts(DeviceId deviceId) {
        // Get all interfaces configured via netcfg for the given device ID and
        // return the corresponding device port number. Interface configuration
        // in the netcfg.json looks like this:
        // "device:host1/3": {
        //   "interfaces": [
        //     {
        //       "name": "host1-3",
        //       (...)
        //     }
        //   ]
        // }
        return interfaceService.getInterfaces().stream()
                .map(Interface::connectPoint)
                .filter(cp -> cp.deviceId().equals(deviceId))
                .map(ConnectPoint::port)
                .collect(Collectors.toSet());
    }

    /**
     * Sets up L2 bridging on all devices known by ONOS and for which this ONOS
     * node instance is currently master.
     * <p>
     * This method is called at component activation.
     */
    private void setUpAllDevices() {
        deviceService.getAvailableDevices().forEach(device -> {
            if (mastershipService.isLocalMaster(device.id())) {
                log.info("*** L2 BRIDGING - Starting initial set up for {}...", device.id());
                setUpDevice(device.id());
                // For all hosts connected to this device...
                hostService.getConnectedHosts(device.id()).forEach(
                        host -> learnHost(host, host.location().deviceId(),
                                host.location().port()));
            }
        });
    }
}
