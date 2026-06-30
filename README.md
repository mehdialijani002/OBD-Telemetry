# Real-Time Automotive Telemetry Dashboard (OBD-II HMI)

An end-to-end Internet of Things (IoT) automotive project that extracts real-time vehicle data from an OBD-II hardware simulator and visualizes it on an Android-based Human-Machine Interface (HMI) using the MQTT protocol.

## 📌 Project Overview
This system is designed to simulate a modern connected-car architecture. It bridges physical automotive hardware with a cloud-based application. The pipeline consists of a Python-based IoT Gateway that communicates with an ELM327 Bluetooth dongle via Serial Port Profile (SPP), processes the hexadecimal OBD-II responses, and publishes the telemetry data (Speed and Fuel Level) to an MQTT broker. The Android application subscribes to these topics to provide a real-time dashboard with auditory safety alerts.

## 🏗️ System Architecture
The project is divided into three main operational layers:
1. **Hardware Layer:** OBD-II Simulator Board + ELM327 Bluetooth Dongle (v2.1).
2. **Gateway Layer (Python):** Handles SPP Bluetooth connection, AT command initialization, PID polling (010D for Speed, 010C for RPM mapped to Fuel), and MQTT publishing.
3. **Application Layer (Android):** Subscribes to MQTT topics, parses incoming payloads, updates the UI asynchronously, and triggers alerts.

---

## 🛠️ Hardware Gateway (Python)

    (obd_gateway.py):
   
      import serial
      import time
      import paho.mqtt.client as mqtt
      
      MQTT_BROKER = "broker.hivemq.com"
      TOPIC_SPEED = "automotive/obd/speed"
      TOPIC_FUEL = "automotive/obd/fuel"
      COM_PORT = "COM11"
      
      client = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2)
      client.connect(MQTT_BROKER, 1883, 60)
      client.loop_start()
      
      try:
      print(f"Connecting to {COM_PORT} at 38400...")
      ser = serial.Serial(COM_PORT, 38400, timeout=1, write_timeout=1)

       ser.reset_input_buffer()
       ser.reset_output_buffer()
       time.sleep(2)
       print("Port connected! Initializing ELM327 v2.1...")
   
       ser.write(b"\r")
       time.sleep(0.5)
       ser.read_all()
       
       ser.write(b"ATZ\r")
       time.sleep(1)
       ser.read_all()
       
       ser.write(b"ATE0\r")
       time.sleep(0.5)
       ser.read_all()
   
       print("Sending ATSP0 (Auto Protocol)...")
       ser.write(b"ATSP0\r")
       time.sleep(1)
       ser.read_all()
   
       print("\n✅ Initialization Successful! Streaming Speed & Fuel (Mapped from RPM)...\n")
   
       while True:
           ser.write(b"010D1\r")
           raw_speed = ser.read_until(b'>').decode('utf-8', errors='ignore').strip()
           clean_speed = raw_speed.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")
           
           if "410D" in clean_speed:
               idx = clean_speed.find("410D")
               try:
                   speed_kph = int(clean_speed[idx+4:idx+6], 16)
                   client.publish(TOPIC_SPEED, str(speed_kph))
                   print(f"⚡ Speed Sent: {speed_kph} km/h")
               except ValueError:
                   pass
   
           time.sleep(0.1) 
   
           ser.write(b"010C1\r")
           raw_rpm = ser.read_until(b'>').decode('utf-8', errors='ignore').strip()
           clean_rpm = raw_rpm.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")
           
           if "410C" in clean_rpm:
               idx = clean_rpm.find("410C")
               try:
                   A = int(clean_rpm[idx+4:idx+6], 16)
                   B = int(clean_rpm[idx+6:idx+8], 16)
                   
                   rpm_value = ((A * 256) + B) / 4
                   
                   fuel_percent = int((rpm_value / 8000) * 100)
                   
                   if fuel_percent > 100: 
                       fuel_percent = 100 
                   
                   client.publish(TOPIC_FUEL, str(fuel_percent))
                   print(f"⛽ Fake Fuel (from RPM) Sent: {fuel_percent}%")
               except ValueError:
                   pass
   
           time.sleep(0.1)

      except Exception as e:
      print(f"\n❌ Critical Error: {e}")
      finally:
      client.loop_stop()
      client.disconnect()
      if 'ser' in locals() and ser.is_open:
      ser.close()

---




## 🚀 Getting Started & Usage

### Prerequisites
* Android Studio (API level 35 recommended)
* Python 3.x installed on the host machine
* ELM327 Bluetooth Dongle paired to the host PC

### 1. Setting Up the Python Gateway
1. Install the required Python libraries:
   pip install pyserial paho-mqtt
2. Pair your ELM327 dongle with your PC and identify the Outgoing COM Port (e.g., COM11).
3. Update the COM_PORT variable in the obd_gateway.py script.
4. Run the script to initialize the hardware and start streaming to HiveMQ:
   python obd_gateway.py

### 2. Setting Up the Android Application
1. Open the project in Android Studio.
2. Ensure the AndroidManifest.xml includes the permissions and cleartext configurations mentioned above.
3. Verify that the Eclipse Paho MQTT library implementation is included in your build.gradle.kts file:
   implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
4. Sync the Gradle files, then run the application on an Android Emulator or a physical device.

---

## ⚙️ How It Works (Data Flow)
1. The Python script sends standard OBD-II PIDs (010D for speed, 010C for RPM) to the ELM327 chip.
2. The chip queries the simulator's ECU and returns Hexadecimal values.
3. Python decodes the Hex string into decimal integers and calculates the accurate values.
4. Data is published to automotive/obd/speed and automotive/obd/fuel.
5. The Android app receives the payload via the messageArrived callback and updates the UI on the main thread.

---

## 👨‍💻 Author
**Mehdi Alijanibaei** - *AI & Automotive Software Engineering*
