Of course. Building upon the high-level design, here is a more detailed design proposal that elaborates on the specific
components, protocols, and data flows required to implement the multi-modal data collection platform.

### Detailed System Design

This document outlines the detailed design of the multi-modal physiological data acquisition platform, expanding on the
Hub-and-Spoke architecture. It covers the internal structure of the PC Controller and Android Sensor Node applications,
the communication protocol, and the end-to-end data management pipeline.

#### 1\. System Architecture

The system maintains the **Hub-and-Spoke architecture**, with a central PC (Hub) and multiple Android devices (Spokes).
This design provides centralized control and scalability while isolating recording tasks to dedicated mobile
nodes.[1, 2]

* **The Hub (PC Controller):** A Python-based application with a PyQt6 GUI.[2] It manages the entire experiment, from
  device discovery and session control to data aggregation and post-processing. For performance-critical tasks, it
  leverages a C++ backend integrated via PyBind11.[2]
* **The Spoke (Android Sensor Node):** A modular Android application written in Kotlin. It handles all hardware
  interfacing, real-time data capture, and local storage on the mobile device.[2]

#### 2\. PC Controller (Hub) Detailed Design

The PC Controller is the master application that orchestrates the entire data collection process.

**2.1. Core Software Modules**

* **`GUIManager`:** Built with PyQt6, this module provides a tabbed interface for the researcher.[2]
    * **Dashboard Tab:** Displays a dynamic grid of connected devices. For each Android Spoke, it shows a live video
      preview (via a `QLabel`) and status indicators. For the Shimmer sensor, it displays a real-time plot of GSR data
      using `PyQtGraph`.[2]
    * **Logs Tab:** A text area that displays real-time status messages, warnings, and errors from all system components
      for debugging.
    * **Playback & Annotation Tab:** A post-session analysis tool that loads recorded video and sensor data,
      synchronizes them on a timeline, and allows the researcher to add timestamped annotations.[2]
* **`NetworkController`:** Manages all TCP/IP communication.[3]
    * It runs a main server thread that listens for incoming connections from Android Spokes.
    * For each connected device, it spawns a dedicated `WorkerThread` (`QThread` subclass) to handle all message
      passing, preventing the main UI thread from blocking.[2]
    * It uses **Zeroconf (mDNS)** for automatic device discovery, browsing for services of type `_gsr-controller._tcp`
      on the local network.[2]
* **`SessionManager`:** Controls the lifecycle of a recording session.
    * On `start_session`, it creates a unique timestamped directory on the local filesystem and generates a
      `session_metadata.json` file.
    * On `stop_session`, it updates the metadata file with the session duration and final status.
* **`TimeSyncService`:** Implements the NTP-like protocol to align clocks across all devices.[4, 5, 6] It calculates and
  stores the clock offset for each connected Spoke.
* **`SensorManager`:** Manages sensors connected directly to the PC.
    * **`NativeShimmer` Module (C++ Backend):** This is a key component for high-integrity data capture. It connects to
      a docked Shimmer sensor via a specified serial port (e.g., "COM3") and runs in a dedicated C++ thread, independent
      of Python's Global Interpreter Lock.[2] It reads the 128 Hz data stream, parses the bytes, and pushes timestamped
      samples into a thread-safe, lock-free queue for the Python GUI to consume with minimal latency.[2]
    * **`NativeWebcam` Module (C++ Backend):** Similar to the Shimmer module, this component uses OpenCV in C++ to
      capture frames from a local webcam. It leverages zero-copy memory sharing to expose frames to Python as NumPy
      arrays, ensuring high performance.[2]

#### 3\. Android Sensor Node (Spoke) Detailed Design

The Android application is a modular system designed for robust, real-time data capture.

**3.1. Core Components**

* **`RecordingController`:** The central orchestrator of the app. It initializes, starts, and stops all active
  `SensorRecorder` modules in response to commands from the PC Hub.[2]
* **`NetworkClient`:** Manages the TCP/IP socket connection to the Hub, handling the sending and receiving of JSON
  messages.
* **`FileTransferManager`:** After a session ends, this module compresses the entire session data directory into a
  single ZIP archive and manages its transfer to the Hub's `DataAggregator`.[2]

**3.2. Sensor Integration Modules (`SensorRecorder` Implementations)**

* **`RgbCameraRecorder`:**
    * Uses the **CameraX** library for efficient camera control.[2]
    * Configures two simultaneous output streams: a `VideoCapture` use case to record H.264 video to an MP4 file, and an
      `ImageCapture` use case to save full-resolution JPEG images at approximately 30 FPS to a separate directory.[2]
      This provides both a continuous video and high-quality stills for analysis.
* **`ThermalCameraRecorder`:**
    * Integrates the Topdon TC001 camera using the open-source **`UVCCamera` library**.[2]
    * It listens for USB device attachment using `USBMonitor.OnDeviceConnectListener`.
    * Once connected, it registers a frame callback that receives each thermal frame as a `ByteBuffer`. The data is
      parsed into an array of 49,152 temperature values and written as a new row in a CSV file, prefixed with a
      nanosecond-precision timestamp.[2]
* **`ShimmerRecorder`:**
    * Manages the wireless connection to the Shimmer3 GSR+ sensor using the **Nordic BLE library** for robust
      communication.[2]
    * It sends specific byte commands over a UART-over-BLE characteristic to control the sensor: `0x07` to start
      streaming and `0x20` to stop.[2]
    * A notification callback receives 8-byte data packets at 128 Hz. The module parses these packets to reconstruct the
      16-bit raw values for GSR and PPG.[2]
    * It then applies the correct conversion formula, accounting for the sensor's 12-bit ADC resolution and multiple
      gain ranges, to calculate the final skin conductance value in microsiemens (μS).[7, 2]
    * Each converted sample is timestamped and written to a local CSV file.

#### 4\. Communication and Synchronization Detailed Design

**4.1. Protocol Specification**

Communication is handled via a custom JSON-based protocol over TCP/IP.[8]

* **Command Structure (PC to Android):**
  ```json
  {"id": 123, "command": "start_recording", "params": {"session_id": "20250815_103000"}}
  ```
* **Response Structure (Android to PC):**
  ```json
  {"ack_id": 123, "status": "ok", "message": "Recording started"}
  ```
* **Asynchronous Data (Android to PC):** For live previews, the Spoke sends messages with a `type` field.
  ```json
  {"type": "preview_frame", "device_id": "Pixel_7", "frame": "<base64_jpeg_string>"}
  ```
* **Security:** All communication channels are secured using **TLS 1.2+** to ensure data encryption and integrity.

**4.2. Time Synchronization Mechanism**

The system uses an NTP-inspired algorithm to align clocks.[4, 5, 6]

1. Upon initial connection, the PC Hub and Android Spoke exchange a series of timestamped packets.
2. The Hub calculates the round-trip time and estimates the clock offset between itself and the Spoke.
3. This offset is stored by the Hub for that specific device.
4. During data collection, all data points on the Spoke are timestamped using the device's local, high-precision
   monotonic clock.
5. In post-processing, the Hub applies the stored offset to all timestamps from that Spoke, translating them into a
   single, unified timeline with sub-millisecond accuracy.

#### 5\. Data Management and Pipeline

**5.1. On-Device Storage Structure**

For each session, the Android app creates a unique directory on its local storage. Within this directory, data is
organized by modality to prevent conflicts.[2]

```
/sessions/
└── 20250815_103000/
    ├── RGB_20250815_103000.mp4
    ├── raw_rgb/
    │   ├── frame_1660562000123456789.jpg
    │   └──...
    ├── thermal_data_20250815_103000.csv
    ├── GSR_20250815_103000.csv
    └── session_metadata.json
```

**5.2. Data Aggregation and Post-Processing**

1. **Transfer:** After the `stop_recording` command, the Android app's `FileTransferManager` compresses the entire
   session directory into a single ZIP file. This archive is then transferred over the network to the PC Hub.[2]
2. **Aggregation:** The Hub's `DataAggregator` receives the ZIP file, unpacks it, and stores the contents in the main
   session folder on the PC, organized by device.
3. **Export:** The researcher can use the Playback & Annotation tool to review the synchronized data. The system also
   provides an export function that uses libraries like **`pandas`** and **`h5py`** to process the raw CSV and video
   files and compile them into a single, analysis-ready HDF5 file, which is ideal for machine learning workflows.[2]