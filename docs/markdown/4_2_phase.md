### Detailed Implementation Plan: Phase 2

**Objective:** To implement the data capture and local storage logic for each sensor modality within the Android application. The goal of this phase is to create a fully functional, standalone data collection app on the Android device that can be tested and validated locally before integration with the PC Controller.

-----

#### **Task 2.1: Implement the Central `RecordingController`**

*   **Description:** Develop the core orchestrator class within the Android app. This controller will manage the state of the recording session and coordinate all individual sensor modules.
*   **Technology:** Kotlin, Android Architecture Components (ViewModel, LiveData).
*   **Action:**
    1.  Create a `RecordingController` class, likely managed by a ViewModel to survive configuration changes.
    2.  Define the recording states (e.g., `IDLE`, `PREPARING`, `RECORDING`, `STOPPING`).
    3.  Implement the main methods:
        *   `startSession(sessionId: String)`: Creates a session-specific directory on the device's external storage. It then iterates through all registered sensor modules and calls their `startRecording` methods, passing the appropriate file paths.
        *   `stopSession()`: Calls the `stopRecording` method on all active sensor modules and finalizes the session.
    4.  Create a simple UI in the main `Activity` with "Start Recording" and "Stop Recording" buttons that trigger these methods for local testing.

-----

#### **Task 2.2: RGB Camera Module (`RgbCameraRecorder`)**

*   **Description:** Implement the module for capturing both standard video and high-resolution still images from the device's camera.
*   **Technology:** CameraX Jetpack library.[1]
*   **Action:**
    1.  Create a `RgbCameraRecorder` class that encapsulates all CameraX logic.
    2.  In the `startRecording` method, configure and bind two simultaneous use cases to the camera lifecycle:
        *   **`VideoCapture`:** Configure this to record video at the required resolution (e.g., 1080p) and frame rate (30 FPS).[1] The output will be an MP4 file saved directly to the session directory.[1]
        *   **`ImageCapture`:** Configure this to capture high-resolution still images. Implement a loop on a background thread that calls `takePicture` at approximately 30 FPS. Each captured image should be saved as a JPEG file with a high-precision nanosecond timestamp in its filename (e.g., `frame_1660562000123456789.jpg`).[1]
    3.  The `stopRecording` method will stop both the video recording and the image capture loop.

-----

#### **Task 2.3: Thermal Camera Module (`ThermalCameraRecorder`)**

*   **Description:** Integrate the Topdon TC001 thermal camera and implement the logic to capture and save its radiometric data.
*   **Technology:** Open-source `UVCCamera` library for USB Video Class device support.[1]
*   **Action:**
    1.  Create a `ThermalCameraRecorder` class.
    2.  Use Android's `UsbManager` to detect when the Topdon camera is connected via USB-C and request the necessary permissions from the user.[1]
    3.  Integrate the `UVCCamera` library to open the device and start the video stream at its native 256x192 resolution.[1]
    4.  Implement the `IFrameCallback` interface. Within the callback:
        *   Retrieve the raw frame data from the provided `ByteBuffer`.
        *   Parse the buffer to get the temperature value for each of the 49,152 pixels.
        *   Construct a CSV row consisting of the current nanosecond timestamp followed by all pixel values.
        *   Append this row to the session's thermal data CSV file.
    5.  Ensure all file I/O operations happen on a background thread to prevent blocking the camera stream or the main UI thread.[1]

-----

#### **Task 2.4: GSR Sensor Module (`ShimmerRecorder`)**

*   **Description:** Implement the wireless connection and data streaming from the Shimmer3 GSR+ sensor.
*   **Technology:** Nordic Semiconductor's Android BLE Library for robust Bluetooth communication.[1]
*   **Action:**
    1.  Create a `ShimmerRecorder` class that handles BLE scanning, connection, and data parsing.
    2.  Implement the logic to send the specific byte commands to the Shimmer's UART-over-BLE characteristic to start (`0x07`) and stop (`0x20`) the 128 Hz data stream.[1]
    3.  Set up a notification callback for the RX characteristic to receive the incoming 8-byte data packets.[1]
    4.  In the callback, parse the byte payload to reconstruct the raw 16-bit values for GSR and PPG.[1]
    5.  Implement the crucial conversion formula to transform the raw GSR value into microsiemens (μS). This logic must correctly account for the sensor's **12-bit ADC resolution** and its multiple gain ranges, as specified in the user guide.[2, 1]
    6.  For each sample, capture a nanosecond timestamp and write the timestamp, the converted GSR value, and the raw PPG value as a new row in the session's GSR data CSV file.[1]

-----

#### **Phase 2 Deliverables and Verification**

*   **Software:**
    *   A functional Android application that can operate in a standalone mode. The UI will provide basic controls to initiate and stop a recording session for all connected sensors.
*   **Data Output:**
    *   Upon completing a local test recording, the device's storage will contain a uniquely named session folder. This folder will house the following data files, all correctly formatted:
        *   An MP4 video file from the RGB camera.
        *   A subdirectory containing a sequence of timestamped JPEG images.
        *   A CSV file containing timestamped thermal data.
        *   A CSV file containing timestamped GSR and PPG data.
*   **Verification Criteria:**
    *   **Functionality:** The app can reliably start and stop recordings from all three sensor modalities simultaneously using the local UI controls.
    *   **Performance:** The application remains stable and responsive during recording, with no crashes or significant UI lag.
    *   **Data Integrity:** The output files are correctly formatted and can be opened and inspected on a computer. The data within them should be plausible (e.g., video plays, CSVs contain the expected number of columns, GSR values are within a reasonable range).
    *   **Timestamping:** All data files use high-precision nanosecond timestamps, providing the basis for the formal synchronization that will be verified in Phase 4.