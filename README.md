# SDVN Application for ONOS

This repository contains an ONOS (Open Network Operating System) application designed for use in Software-Defined Vehicular Network (SDVN) scenarios. The application manages connectivity with devices through P4 programmable switches and utilizes ONOS as a network controller.

## Repository Structure

- **`sdvn/app/src/main/resources`** - Contains the P4 program (`main.p4`), defining the data plane behavior.
- **`sdvn/app/src/main/java/org/onosproject/sdvn`** - Holds the Java application code for ONOS integration.
- **`sdvn/config`** - Stores configuration files needed by ONOS to establish connections with devices.

## Prerequisites

Before running this application, ensure the following dependencies are installed and configured:

- **ONOS**: Make sure ONOS is installed and accessible from your terminal.
- **P4 Compiler** (`p4c-bm2-ss`): Required to compile the P4 code to run on a BMv2 software switch.
- **Maven**: For building the Java application.

## Steps to Run the Application

1. **Compile the P4 Code**  
   Navigate to the P4 code directory and compile `main.p4`:

   ```bash
   cd sdvn/app/src/main/resources/
   p4c-bm2-ss --arch v1model -o bmv2.json --p4runtime-files p4info.txt --std p4-16 main.p4
   cd ../../../../..

2. **Compile the Java Application with Maven**  
   Build the application package:

   ```bash
   cd sdvn/app
   mvn clean install
   cd ../..

3. **Start ONOS**  
   Open a new terminal and start the ONOS server. The command may vary depending on your setup. If `ok` is not recognized, refer to the ONOS documentation for alternative startup commands.

   ```bash
   ok
  
4. **Activate ONOS Dependencies**  
   Enable required ONOS components for BMv2 and P4Runtime support. Use the ONOS GUI (`onos-gui localhost`) or activate the applications via the terminal. SSH into the ONOS environment if the default `onos` command doesn’t work:

   ```bash
   app activate org.onosproject.drivers.bmv2 \
                org.onosproject.pipelines.basic \
                org.onosproject.protocols.p4runtime \
                org.onosproject.p4runtime \
                org.onosproject.drivers.p4runtime

5. **Install the SDVN Application on ONOS**  
   Deploy the compiled `.oar` file to ONOS:

   ```bash
   onos-app localhost install! sdvn/app/target/sdvn-1.0.0-SNAPSHOT.oar

6. **Load the Device Configuration**  
   Install the configuration file so ONOS can connect to the devices:

   ```bash
   onos-netcfg localhost sdvn/config/<desired_config_file>

## Expected Outcome

After completing these steps, ONOS should detect and manage the devices defined in the configuration file. Three registered `pipeconfigs` should be visible, indicating that ONOS is correctly interfacing with the devices via P4Runtime.

---

### Additional Notes

- **Troubleshooting**: If ONOS fails to recognize the devices, ensure that the P4 code compiled without errors and that the configuration file is accurate and properly formatted.
- **Compatibility**: This application was tested with ONOS version X.Y.Z (replace with actual version), and might require adjustments for other versions.