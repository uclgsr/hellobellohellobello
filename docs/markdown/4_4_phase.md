### Detailed Implementation Plan: Phase 4

**Objective:** To merge the PC Hub and Android Spoke applications into a single, cohesive system capable of
synchronized, multi-device data recording. This phase brings together the components developed in Phases 1, 2, and 3 to
realize the core functionality of the platform.

-----

#### **Task 4.1: Remote Control and Session Management Integration**

* **Description:** Connect the PC Controller's GUI to the network layer, enabling it to send commands that remotely
  control the recording lifecycle on the Android Spokes.
* **Technology:** Python (PyQt6 signals/slots), Kotlin, JSON-based protocol.[1]
* **PC Controller (Hub) Actions:**
    1. **Command Dispatch:**
        * Connect the "Start Recording" button's `clicked` signal to a slot in the `SessionManager`.
        * This slot will create a new session directory and a unique `session_id`.
        * The `SessionManager` will then call a method on the `NetworkController` (e.g., `broadcast_command`) with the
          command `start_recording` and the `session_id` as a parameter.
    2. **Worker Thread Logic:**
        * The `NetworkController` will delegate the command to the `WorkerThread` for each connected Android device.
        * Each `WorkerThread` will construct the final JSON message, send it over its TCP socket, and wait for an
          acknowledgment response (FR2).[1]
* **Android Sensor Node (Spoke) Actions:**
    1. **Command Reception:**
        * The `NetworkClient` service, listening in a background thread, will parse incoming JSON messages.
    2. **Action Orchestration:**
        * Upon receiving a `start_recording` command, the `NetworkClient` will extract the `session_id` and invoke
          `RecordingController.startSession(sessionId)`.
        * Upon receiving a `stop_recording` command, it will invoke `RecordingController.stopSession()`.
    3. **Acknowledgment:**
        * After successfully starting or stopping the recording, the `RecordingController` will notify the
          `NetworkClient`, which will then send an acknowledgment JSON message back to the PC Hub.

-----

#### **Task 4.2: Time Synchronization Implementation and Verification**

* **Description:** Implement and validate the NTP-like protocol to ensure all data streams can be aligned with high
  temporal accuracy.
* **Technology:** NTP-inspired timestamp exchange algorithm.[2]
* **Action:**
    1. **Handshake Implementation:**
        * Immediately after a new TCP connection is established, the PC's `WorkerThread` will initiate the time
          synchronization handshake.
        * It will send a packet containing its current high-precision timestamp (`t0`). The Android Spoke will record
          its arrival time (`t1`), and immediately reply with a packet containing both `t1` and its own departure time (
          `t2`). The PC will record the response arrival time (`t3`).
    2. **Offset Calculation:**
        * Using the four timestamps (`t0`, `t1`, `t2`, `t3`), the PC's `WorkerThread` will calculate the round-trip
          delay and the clock offset for that specific Android device.
        * This offset will be stored in a dictionary within the `NetworkController`, mapping device IDs to their clock
          offsets.
    3. **Verification via "Flash Sync" (FR7):**
        * Add a "Flash Sync" button to the PC GUI.
        * When clicked, the PC will broadcast a `flash_sync` command to all connected Spokes.
        * On each Android device, the `RecordingController` will execute the command by briefly turning the screen
          bright white and immediately logging the event with its local high-precision timestamp.
        * During post-session analysis, the recorded videos will be inspected. The frame number of the flash will be
          correlated with the logged timestamp. By applying the calculated clock offsets, the timestamps from all
          devices for this common event should align to within the required tolerance of <5 ms (NFR2).[1]

-----

#### **Task 4.3: Live Preview Streaming**

* **Description:** Implement the functionality for the Android app to stream downsampled, low-framerate video to the PC
  for real-time monitoring.
* **Technology:** Android CameraX `ImageAnalysis` use case, Base64 encoding, PyQt6 `QImage`.
* **Action:**
    1. **Android Frame Production:**
        * In the `RgbCameraRecorder`, add a CameraX `ImageAnalysis` use case to the camera configuration.
        * Set up an analyzer on a background thread that receives frames from this use case.
        * The analyzer will throttle the frame rate (e.g., to 2 FPS), downscale the image (e.g., to 320x240), compress
          it to a low-quality JPEG, and Base64 encode the byte array.
        * The resulting string will be sent to the `NetworkClient` to be wrapped in a `preview_frame` JSON message and
          sent to the PC.
    2. **PC Frame Consumption:**
        * The PC's `WorkerThread` will listen for these asynchronous `preview_frame` messages.
        * Upon receipt, it will decode the Base64 string back into image bytes and construct a `QImage`.
        * It will then emit a Qt signal (e.g., `newPreviewFrame(deviceId, qImage)`).
    3. **GUI Update:**
        * The `GUIManager` will have a slot connected to the `newPreviewFrame` signal. This slot will identify the
          correct `DeviceWidget` using the `deviceId` and update its `QLabel` with the new `QImage`, providing a live
          preview.

-----

#### **Task 4.4: Fault Tolerance and Reconnection Logic**

* **Description:** Implement robust error handling to manage network interruptions without losing data.
* **Technology:** Exception handling, state management.
* **Action:**
    1. **Disconnection Detection:**
        * **PC Hub:** The `WorkerThread` will use a `try-except` block around its socket operations to catch
          `socket.error`. It will also implement a heartbeat mechanism (expecting a status message every few seconds)
          and time out if no message is received.
        * **Android Spoke:** The `NetworkClient` will similarly detect a broken socket.
    2. **State Management:**
        * When a disconnect occurs, the PC's `GUIManager` will be notified to visually flag the device as "Offline" (
          e.g., by changing its border color to red) (FR8).[1]
        * Crucially, the Android `RecordingController` will *not* stop recording. It will continue to save data locally
          to the device's storage.
    3. **Reconnection Protocol:**
        * The Android `NetworkClient` will enter a reconnect loop, periodically attempting to re-establish a connection
          with the PC Hub.
        * Once reconnected, it will send a special `reconnect` message containing its device ID and the current
          `session_id`.
        * The PC's `NetworkController` will recognize this message, associate the new connection with the previously
          disconnected device, and restore it to an "Online" state in the GUI. It will then re-send any critical
          commands that might have been missed during the outage (NFR3).[1]

-----

#### **Phase 4 Deliverables and Verification**

* **Software:**
    * A fully integrated system where the PC Controller can remotely and synchronously start and stop recordings on
      multiple Android devices.
    * The PC GUI will display live, low-framerate video previews from all connected Android Spokes.
* **Verification Criteria:**
    * **Synchronized Recording:** A test session with at least two Android devices and the PC's local sensors, triggered
      by a single "Start" command, produces data files whose timestamps are verified to be aligned within 5 ms using
      the "Flash Sync" method.
    * **Live Monitoring:** The PC dashboard correctly displays live video feeds from all connected Android devices.
    * **Fault Tolerance:** During a recording session, temporarily disconnecting an Android device from the network does
      not stop the recording on that device or any other device. Upon reconnection, the device's status is updated on
      the PC, and it correctly stops recording when the final "Stop" command is issued.