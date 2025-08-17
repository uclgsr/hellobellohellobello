Of course. Breaking down a large project into specific, actionable prompts is the perfect way to leverage an AI code generation tool like JetBrains Junie.

Since the GitHub repository was initially inaccessible, these prompts are designed to build the project from a clean slate, ensuring every requirement from your thesis is met.[1] They are structured to be fed to Junie one by one, building the application logically from the ground up.

Here is the series of prompts, broken down by phase and platform, to complete your repository.

### **Phase 1: Foundational Infrastructure (PC & Android)**

This phase sets up the core structure and communication protocols. These prompts should be run first as everything else depends on them.

#### **Prompt 1.1 (PC): Project Scaffolding & Configuration**

"Create the project structure for a Python application that will act as the central controller for a data acquisition system. The root directory should be `asd_pc`.

1.  **Create the following directory structure:**
    * `asd_pc/core/`
    * `asd_pc/network/`
    * `asd_pc/sensors/`
    * `asd_pc/gui/`
    * `tests/`
2.  **Create a `config.json` file** in the `asd_pc` root. This file will externalize configuration parameters to meet requirement **NFR8**.[2] Populate it with initial values:
    ```json
    {
      "server_ip": "0.0.0.0",
      "command_port": 8080,
      "timesync_port": 8081,
      "file_transfer_port": 8082,
      "shimmer_sampling_rate": 128,
      "video_resolution": ,
      "video_fps": 30,
      "use_tls": false
    }
    ```
3.  **Create a `requirements.txt` file** with the following core dependencies: `PyQt5`, `asyncio`, `bleak`, `pytest`."

-----

#### **Prompt 1.2 (PC): Core Communication Protocol Definition**

"In the `asd_pc/network/` directory, create a Python file named `protocol.py`. This file will define the JSON-based communication protocol used between the PC and Android clients, as required by **FR7**.[2]

Define a class or a set of functions that can create the JSON strings for the following commands. Each command should have a clear structure.

* `REGISTER_DEVICE`
* `DEVICE_ACCEPTED`
* `SESSION_CREATE`
* `SESSION_START`
* `SESSION_STOP`
* `HEARTBEAT`
* `GSR_DATA`
* `SYNC_FLASH`
* `INITIATE_FILE_TRANSFER`
* `REJOIN_SESSION`"

-----

#### **Prompt 1.3 (PC): Time Synchronization Server**

"In the `asd_pc/network/` directory, create a Python file `time_server.py`. Implement a `TimeSyncServer` class that runs an asynchronous UDP server to fulfill requirement **FR3**.[2]

1.  The server should be built using Python's `asyncio` library.
2.  It should listen on the `timesync_port` defined in `config.json`.
3.  When it receives any UDP packet, it must immediately capture the current high-resolution system time (`time.monotonic_ns`) and send this timestamp back to the client's source address and port.
4.  This class should be designed to run as a standalone task within the main application."

-----

#### **Prompt 1.4 (Android): Project Scaffolding & Permissions**

"Set up a new Android Studio project for the client application.

1.  **Use Kotlin** as the primary language.
2.  **Define the package structure:** `com.buccancs.asd.network`, `com.buccancs.asd.recording`, `com.buccancs.asd.sensors`, `com.buccancs.asd.ui`.
3.  **Add necessary permissions** to the `AndroidManifest.xml`: `CAMERA`, `BLUETOOTH`, `INTERNET`, `FOREGROUND_SERVICE`, and permissions for USB device access.
4.  **Add dependencies** to `build.gradle`: AndroidX libraries, JUnit, Espresso, and any necessary libraries for USB serial communication if required for the thermal camera."

-----

#### **Prompt 1.5 (Android): Time Synchronization Client**

"In the `com.buccancs.asd.network` package, create a `TimeSyncClient` class in Kotlin. This class will synchronize the Android device's clock with the PC server to meet requirements **FR3** and **NFR2**.[2]

1.  The client should run on a background thread.
2.  It should send a UDP packet to the PC's `timesync_port`.
3.  It must record the local time before sending (`T1`) and after receiving the response (`T2`).
4.  It will receive the server's timestamp (`T_server`) in the response.
5.  Implement the logic to calculate the clock offset: `offset = (T_server + (T2 - T1) / 2) - T2`.
6.  Provide a public method `getSyncedTimestamp()` that returns the current system time plus the calculated offset. This method will be used by all other parts of the app for timestamping."

-----

### **Phase 2: PC Controller Implementation**

Now, build out the core logic of the main PC application.

#### **Prompt 2.1 (PC): Session Manager**

"In `asd_pc/core/`, create `session_manager.py`. Implement the `SessionManager` class to handle the session lifecycle (**FR4** [2]).

1.  It should have methods: `create_session(name)`, `start_recording()`, `stop_recording()`.
2.  `create_session` must create a uniquely named directory and a `metadata.json` file within it. It must enforce the 'one active session' rule.
3.  `start_recording` and `stop_recording` should manage the session state and log start/end times to the metadata file.
4.  This class should be the central state machine for the application."

-----

#### **Prompt 2.2 (PC): Shimmer Sensor Manager with Simulation**

"In `asd_pc/sensors/`, create `shimmer_manager.py`. Implement a `ShimmerManager` class and a `SimulatedShimmer` class to meet requirement **FR1**.[2]

1.  **`ShimmerManager`:** Use the `bleak` library to discover, connect to, and stream data from a real Shimmer3 GSR+ sensor. It must log incoming data at 128Hz to a CSV file within the active session directory in real-time (**FR5**, **NFR3** [2]).
2.  **`SimulatedShimmer`:** Create this class with the *exact same public methods* as `ShimmerManager` (`connect()`, `start_streaming()`, `stop_streaming()`, etc.). When streaming, it should generate a continuous stream of dummy GSR data (e.g., a sine wave with noise) at 128Hz.
3.  The main application should be able to use either class interchangeably."

-----

#### **Prompt 2.3 (PC): Network Server & Device Manager**

"In `asd_pc/network/`, create `network_server.py`. Implement a `NetworkServer` class using `asyncio` to manage TCP connections from multiple Android clients.

1.  The server should listen on the `command_port` from `config.json`.
2.  For each client, it should handle incoming JSON messages based on the protocol defined in `protocol.py`.
3.  **Integrate a `DeviceManager` class** (in `asd_pc/core/device_manager.py`). This class will maintain a list of connected devices.
4.  Implement the **heartbeat mechanism** (**FR8** [2]): The `DeviceManager` should track the last heartbeat time for each device and mark a device as 'Offline' if a heartbeat is not received within a set timeout (e.g., 10 seconds)."

-----

### **Phase 3: Android Client Implementation**

Build the Android application's core recording and communication functionalities.

#### **Prompt 3.1 (Android): Foreground Service & Recording Controller**

"In the `com.buccancs.asd` root package, create a `RecordingService` class that extends `ForegroundService` to ensure reliability (**NFR3** [2]).

1.  This service must start with a persistent notification.
2.  It will host the `NetworkClient` instance and a new `RecordingController` class.
3.  Create the **`RecordingController`** (in `com.buccancs.asd.recording`). This class will receive commands from the `NetworkClient` (e.g., `SESSION_START`, `SESSION_STOP`) and delegate these actions to the specific recorder modules (which will be created next)."

-----

#### **Prompt 3.2 (Android): RGB & Thermal Camera Recorders**

"In the `com.buccancs.asd.recording` package, create two classes: `RgbRecorder` and `ThermalRecorder` to fulfill **FR5**.[2]

1.  **`RgbRecorder`:** Use the `Camera2` API to record video. It must be configurable to record at \>= 1920x1080 and 30 FPS. Each frame's presentation timestamp must be captured. The output should be a standard `.mp4` file saved to a session-specific directory.
2.  **`ThermalRecorder`:** Integrate the `InfiSense IRUVC SDK` to control the Topdon TC001 camera. This class must handle USB permissions. It needs to capture and save the **radiometric data** (the per-pixel temperature matrix) for each frame to a custom binary file.
3.  Both classes must have `start(sessionId)` and `stop()` methods, controlled by the `RecordingController`."

-----

### **Phase 4: Integration & Advanced Features**

Connect the two applications and implement the more complex, interactive features.

#### **Prompt 4.1 (PC & Android): Fault Tolerance & Rejoin Logic**

"Implement the full fault tolerance and recovery feature (**FR8** [2]). This requires code on both the PC and Android.

1.  **Android:** In the `NetworkClient`, implement an automatic reconnection loop that activates when the TCP connection is lost. When reconnected, it must send a `REJOIN_SESSION` command to the server.
2.  **PC:** In the `NetworkServer`, handle the `REJOIN_SESSION` command. If the session is still active, allow the device to continue. If the session ended while the device was offline, respond with an `INITIATE_FILE_TRANSFER` command.
3.  **Android:** The `RecordingController` must be designed to **continue recording locally** even if the network connection is lost."

-----

#### **Prompt 4.2 (PC & Android): Automatic Data Transfer**

"Implement the automatic data transfer mechanism (**FR10** [2]).

1.  **PC:** In `asd_pc/network/`, create a `FileTransferServer` class. It should listen on the `file_transfer_port` from `config.json`. It will receive files in chunks and save them to the correct session directory. After each successful transfer, it should update the session's `metadata.json` to log the file's details (name, size, checksum) to ensure data integrity (**NFR4** [2]).
2.  **Android:** In `com.buccancs.asd.network`, create a `FileTransferManager`. When triggered by the `INITIATE_FILE_TRANSFER` command, it should connect to the `FileTransferServer`, find all local files for that session, and stream them one by one."

-----

#### **Prompt 4.3 (PC & Android): Calibration Utility**

"Implement the camera calibration utility (**FR9** [2]).

1.  **Android:** Create a new `CalibrationActivity`. This UI should display live feeds from both the RGB and thermal cameras. A button should allow the user to capture a synchronized pair of still images. The activity should collect multiple pairs and package them to be sent to the PC.
2.  **PC:** Create a standalone Python script in a `tools/` directory named `calibrate.py`. This script will use OpenCV to take the image pairs from the Android device, detect a checkerboard pattern, and compute the camera intrinsic and extrinsic parameters. The results should be saved to a file that can be associated with a specific Android device ID."