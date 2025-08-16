### Detailed Implementation Plan: Phase 1

**Objective:** To establish the foundational project structures and implement the core network communication layer that
allows the PC Hub and an Android Spoke to discover, connect, and exchange basic information with each other.

-----

#### **Task 1.1: Project Scaffolding and Version Control**

* **Description:** Create the initial project structures for both the PC and Android applications and place them under
  version control.
* **PC Controller (Hub):**
    * **Technology:** Python 3, PyQt6 for the GUI framework.[1]
    * **Action:**
        1. Initialize a new Git repository.
        2. Set up a Python virtual environment.
        3. Create a `requirements.txt` file and add initial dependencies (`PyQt6`, `zeroconf`).
        4. Create the initial directory structure:
           ```
           /pc_controller/
           ├── src/
           │   ├── main.py           # Main application entry point
           │   ├── gui/              # GUI-related modules
           │   ├── network/          # Network communication modules
           │   └── core/             # Core logic (e.g., session management)
           └── tests/                # Unit tests
           ```
* **Android Sensor Node (Spoke):**
    * **Technology:** Kotlin, Android Studio.
    * **Action:**
        1. Initialize a new Git repository or a new directory within a monorepo.
        2. Create a new Android Studio project targeting a modern Android API level.
        3. Add necessary permissions to `AndroidManifest.xml` (`INTERNET`, `ACCESS_WIFI_STATE`,
           `CHANGE_WIFI_MULTICAST_STATE`).
        4. Create the initial package structure:
           ```
           /com.example.sensorspoke/
           ├── ui/                   # Activities and Fragments
           ├── network/              # Network client and service discovery
           └── service/              # Background services for recording
           ```

-----

#### **Task 1.2: Communication Protocol Definition (Version 1.0)**

* **Description:** Formally define the initial set of JSON messages that will be used for device discovery, connection,
  and basic control. This ensures both development teams are working from a common specification.
* **Technology:** JSON for message payloads.[1]
* **Action:** Create a `PROTOCOL.md` document in the repository that specifies the following message formats:
    * **Device Advertisement (via Zeroconf):**
        * Service Type: `_gsr-controller._tcp.local.`
        * Service Name: e.g., "GSR Spoke - Pixel 7"
        * Port: The port number the Android Spoke is listening on.
    * **PC-to-Android Commands:**
        * **Query Capabilities:**
          ```json
          {"id": 1, "command": "query_capabilities"}
          ```
    * **Android-to-PC Responses:**
        * **Acknowledge Connection:**
          ```json
          {"ack_id": 0, "status": "connected", "device_id": "Pixel_7"}
          ```
        * **Capabilities Data:**
          ```json
          {"ack_id": 1, "status": "ok", "capabilities": {"has_thermal": true, "cameras": [...]}}
          ```

-----

#### **Task 1.3: Network Implementation**

* **Description:** Implement the software modules responsible for network discovery and communication on both the Hub
  and the Spoke.
* **PC Controller (Hub):**
    * **`NetworkController` Module:**
        1. Implement a `ZeroconfServiceBrowser` class that uses the `zeroconf` library to listen for the defined service
           type.
        2. When a service is discovered, add the device's information (name, IP, port) to a list that will be displayed
           in the GUI.
        3. Implement a `TcpServer` class that runs in a separate `QThread` to listen for incoming connections.[1]
        4. When a user initiates a connection, create a `WorkerThread` for that specific device to handle all subsequent
           message sending and receiving, ensuring the GUI remains responsive.[1]
* **Android Sensor Node (Spoke):**
    * **`NetworkClient` Module:**
        1. Use Android's `NsdManager` to register and advertise the Zeroconf service when the app starts.[1]
        2. Implement a `ForegroundService` to host a `ServerSocket` that listens for an incoming connection from the PC
           Hub. This ensures the connection remains active even if the app is in the background.
        3. Upon connection, the service will manage the `Socket`'s input and output streams, running the read loop in a
           background thread (e.g., using Kotlin Coroutines).

-----

#### **Task 1.4: Initial Handshake and Capabilities Exchange**

* **Description:** Implement the first logical interaction between the two applications to verify the end-to-end
  communication channel.
* **Action:**
    1. **On the PC Hub:** Once a TCP connection is successfully established, the corresponding `WorkerThread` will
       immediately send the `query_capabilities` JSON command.
    2. **On the Android Spoke:** The `NetworkClient` service will receive and parse the command. It will then gather
       basic device information (e.g., model name, available cameras using `CameraManager`) and construct the JSON
       response containing these capabilities.
    3. **On the PC Hub:** The `WorkerThread` will receive the capabilities response, parse it, and use a Qt signal to
       pass the information back to the main thread. The `GUIManager` will then update the UI to reflect the status and
       capabilities of the newly connected device.

-----

#### **Phase 1 Deliverables and Verification**

* **Software:**
    * A runnable PC application that discovers and lists available Android devices on the network.
    * A runnable Android application that advertises its presence and can accept a connection from the PC.
* **Documentation:**
    * A `PROTOCOL.md` file defining the initial JSON message formats.
    * Initialized Git repositories with the project scaffolding.
* **Verification Criteria:**
    * **Successful Discovery:** The PC application's UI correctly displays the names of all Android devices running the
      Spoke app on the same local network.
    * **Successful Connection:** The user can select a device from the list on the PC, and a stable TCP/IP connection is
      established.
    * **Successful Handshake:** Logs on both the PC and Android device confirm that the `query_capabilities` command and
      its corresponding response are sent and received correctly. The PC UI updates to show the connected status of the
      device.