### Detailed Implementation Plan: Phase 3

**Objective:** To build the main user interface of the PC Controller and integrate sensors that connect directly to the PC, establishing the Hub as a standalone data collection station. This phase ensures the core PC application is robust and performant before adding the complexity of remote device management.

-----

#### **Task 3.1: GUI Development (`GUIManager`)**

*   **Description:** Implement the main graphical user interface for the PC Controller application, focusing on session control and live data monitoring.
*   **Technology:** Python, PyQt6 for the GUI framework, PyQtGraph for plotting.[1]
*   **Action:**
    1.  **Main Window and Layout:**
        *   Create the main application window (`QMainWindow`) with a central tabbed widget (`QTabWidget`).
        *   Implement the "Dashboard" and "Logs" tabs. The "Playback & Annotation" tab will be scaffolded but its functionality deferred to Phase 5.[1]
    2.  **Dashboard Implementation:**
        *   Design a dynamic grid layout that can accommodate widgets for multiple data streams (both local and remote).[1]
        *   Create a reusable `DeviceWidget` class that will contain the visualization elements for a single data source.
    3.  **Live Data Visualization:**
        *   For video streams (from the local webcam in this phase), the `DeviceWidget` will contain a `QLabel` that is updated with new frames (`QImage`/`QPixmap`).[1]
        *   For GSR data, the `DeviceWidget` will contain a `PlotWidget` from the PyQtGraph library. Implement a data buffer that the plot can read from to display a scrolling, real-time waveform of the GSR signal.[1]
    4.  **Session Controls:**
        *   Add "Start Session," "Stop Session," and "Connect Device" buttons to the main toolbar.
        *   Connect these buttons to placeholder functions in the `SessionManager` and `NetworkController` that will be fully implemented in later phases.

-----

#### **Task 3.2: High-Integrity Shimmer Integration (`NativeShimmer`)**

*   **Description:** Develop the high-performance C++ backend for connecting to a docked Shimmer3 GSR+ sensor via a wired USB connection. This provides the most reliable, low-latency option for ground-truth data collection (FR1).[1]
*   **Technology:** C++, PyBind11 for Python bindings.[1]
*   **Action:**
    1.  **C++ Module (`NativeShimmer`):**
        *   Create a C++ class that handles the low-level serial port communication (e.g., using the Win32 API on Windows or `termios` on Linux/macOS) to connect to the Shimmer Dock.[1]
        *   Implement the logic to run the serial read loop in a dedicated C++ background thread, ensuring it operates independently of Python's Global Interpreter Lock (GIL).[1]
        *   This thread will continuously read the 128 Hz data stream from the sensor, parse the incoming bytes, and convert the raw data into meaningful values (microsiemens).[1]
    2.  **Data Marshalling:**
        *   Implement a thread-safe, lock-free queue to pass the parsed and timestamped GSR samples from the C++ thread to the Python main thread.[1] This decouples the high-frequency data capture from the GUI updates, preventing performance issues.
    3.  **PyBind11 Integration:**
        *   Use PyBind11 to create a Python wrapper for the `NativeShimmer` class. This will expose methods like `connect(port)`, `start_streaming()`, `stop_streaming()`, and `get_latest_samples()` to the Python application.[1]

-----

#### **Task 3.3: Local Webcam Integration (`NativeWebcam`)**

*   **Description:** Implement a similar high-performance C++ module to capture video from a webcam connected directly to the PC.
*   **Technology:** C++, OpenCV, PyBind11, NumPy.[1]
*   **Action:**
    1.  **C++ Module (`NativeWebcam`):**
        *   Create a C++ class that uses OpenCV's `VideoCapture` to open and read frames from a local webcam.[1]
        *   Run the frame capture loop in its own C++ thread to ensure a stable frame rate.
    2.  **Zero-Copy Data Sharing:**
        *   To pass video frames from C++ to Python with maximum efficiency, implement a zero-copy mechanism. The C++ module will expose the raw frame data buffer directly to Python, which can then be wrapped in a NumPy array without needing to copy the underlying memory.[1]
    3.  **PyBind11 Integration:**
        *   Expose methods like `start_capture(device_id)` and `get_latest_frame()` to the Python application. The `get_latest_frame()` method will return a NumPy array that can be easily converted to a `QImage` for display in the PyQt6 GUI.[1]

-----

#### **Phase 3 Deliverables and Verification**

*   **Software:**
    *   A functional PC application that can connect to, display, and record live data streams from a locally docked Shimmer GSR+ sensor and a local webcam simultaneously.
*   **Code:**
    *   A stable and well-documented C++ extension module (`native_backend`) that can be compiled and imported into the Python application.
*   **Verification Criteria:**
    *   **GUI Responsiveness:** The user interface must remain fluid and responsive while both the Shimmer sensor and webcam are streaming data at their full rates.
    *   **Data Integrity:** The live plot of the GSR data must accurately reflect the 128 Hz signal from the sensor. The live video feed must be smooth and display at the camera's native frame rate.
    *   **Performance:** The C++ backend must demonstrate minimal latency and timing jitter, as verified by logging the timestamps at both the C++ capture point and the Python consumption point.
    *   **Recording:** The application must be able to save the data from both local sensors to correctly formatted files on disk when a local recording is started and stopped.