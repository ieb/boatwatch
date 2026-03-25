# Boat Watch

Watch Apps for a boat or rather wearOS apps. 

# Apps

The apps should be standalone apps, targeting a Galaxy Watch 6 classic running wearOS 5.0. 

# Battery Montor

An app to display the voltage and current of a 4 cell LiFePO4 battery pack including

* Voltage
* Current
* State of charge
* Status or state of health
* Cell Voltages (4)
* Temperature sensors (3)

The intended use of the app is as a quick check on the state of the battery rather than constant monitoring.

## References


The BMS is a JBD BMS, read by a custom device and exposing the information over Wifi. Some references

* https://github.com/ieb/N2KNMEA0183Wifi/tree/main custom firmware
* https://github.com/ieb/N2KNMEA0183Wifi/tree/main/lib/jdbbms interface to the BMS over serial
* the above repo is checked out at ../N2KNMEA0183Wifi
* https://github.com/ieb/N2KLifePo4 example web ui that uses the firmware over http

# Raymarine Autopilot control

An app to control a Raymarine Autopilot sending messages over http to the embeded firmware which then forwards them to the NMEA2000 bus as if the watch was an official Raymarine remote control for the Autopilot. 

It should:

* Have an Auto and Standby button to enable and disable the autopilot.
* Pressing auto, puts the auto pilot into streer to compas
* Once in Auto pressing auto again will cycle through steer to AWA, to TWA and back to compass.
* In compass mode the screen will display the target compass headding
* In AWA mode, the target AWA
* In TWA mode, the target TWA
* It will have +1, +10, -1, -10 buttons or controls to allow heading addjustments
* Optionally on a Galaxy Watch 6 classic, the rotaiting bezel may be used as a knob to adjust the target heading.

The intended use is to have the app running to control the autopilot. It should not be necessaty to have the app running all the time although when it is running it may recieve status updates if available from the autopilot.

## References

* in ../autopilot/watch you will find a watch app that was written for the above communicating over BLE. Some of the functions it has are not supported by a Raymarine Autopilot but it uses the same NMEA2000 protocol. That hardware with BLE support is in ../autopilot/firmware/esp32_autopilot
* The custom firmware this app needs to work in conjunction with is here https://github.com/ieb/N2KNMEA0183Wifi/tree/main  checked out at ../N2KNMEA0183Wifi