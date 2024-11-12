This repository contains a ONOS application for use in SDVN senarious. 

The repository contains:
sdvn/app/src/main/resources - Contains the P4 code
sdvn/app/src/main/java/org/onosproject/sdvn - Contains the java code that will be used by ONOS
sdvn/config - Contains the configuration that needs to be given to ONOS so the controller can connect to the devices correctly

Steps to run:
  1. Compile the P4 code: cd sdvn/app/src/main/resources/ && p4c-bm2-ss --arch v1model -o bmv2.json --p4runtime-files p4info.txt --std p4-16 main.p4 && cd ../../../../..
  2. Compile the aplication using maven: cd sdvn/app && mvn clean install && cd ../..
  3. Turn on ONOS in a diferent terminal: ok (or whatever comand your version of ONOS uses)
  4. Turn on the necessary ONOS dependencies, this can be done with the GUI (onos-gui localhost) or in the terminal. For me, to open the terminal I had to ssh into it because the default comand did not work: $ app activate org.onosproject.drivers.bmv2 org.onosproject.pipelines.basic org.onosproject.protocols.p4runtime org.onosproject.p4runtime org.onosproject.drivers.p4runtime
  5. Install the sdvn aplication to ONOS: onos-app localhost install! sdvn/app/target/sdvn-1.0.0-SNAPSHOT.oar
  6. Install the configuration to ONOS: onos-netcfg localhost sdvn/config"the_desired_config_file"
     
After running this, ONOS should be aware of the devices added, there should be three registered pipeconfigs


