### Detailed Implementation Plan: Phase 5

**Objective:** To implement the end-to-end data management pipeline, including automated file transfer from Android Spokes to the PC Hub, and to provide tools for post-session data review, annotation, and export.

-----

#### **Task 5.1: Automated Data Transfer and Aggregation (FR10)**

*   **Description:** Implement the mechanism for automatically and reliably transferring all recorded data files from the Android Spokes to the PC Hub after a session concludes.
*   **Technology:** Python (sockets, threading), Kotlin (sockets, `ZipOutputStream`).
*   **PC Controller (Hub) Actions:**
    1.  **`DataAggregator` Module:**
        *   Create a dedicated `FileReceiver` class that runs a `ServerSocket` on a separate thread and a specific, high-numbered port (e.g., 9001). This keeps the bulk data transfer channel separate from the primary command and control channel.
        *   When the `SessionManager` stops a session, it will signal the `NetworkController` to send a `transfer_files` command to each Android Spoke. This command will include the IP and port for the `FileReceiver`.
    2.  **File Reception and Unpacking:**
        *   The `FileReceiver` will listen for incoming connections. When a Spoke connects, it will read the incoming byte stream and write it directly to a temporary ZIP file in the corresponding session directory (e.g., `/sessions/20250815_103000/Pixel_7_data.zip`).
        *   Implement a progress update mechanism using Qt signals to show the transfer progress for each device in the GUI.
        *   After the transfer is complete and the socket is closed, automatically unpack the ZIP archive into the session folder and then delete the temporary archive.
*   **Android Sensor Node (Spoke) Actions:**
    1.  **`FileTransferManager` Module:**
        *   Implement a `zipSession(sessionId)` method that uses Android's `ZipOutputStream` to efficiently compress the entire session directory into a single `.zip` file.[1]
        *   Implement a `sendFile(file, host, port)` method that runs within a `ForegroundService` to handle the network transfer robustly, even if the app is backgrounded.
        *   When the `NetworkClient` receives the `transfer_files` command, it will trigger the `FileTransferManager` to first zip the session data and then initiate the transfer to the specified IP and port.

-----

#### **Task 5.2: Playback and Annotation Tool**

*   **Description:** Develop the user interface and backend logic for reviewing and annotating completed recording sessions, allowing researchers to inspect the synchronized data.
*   **Technology:** PyQt6, PyQtGraph, OpenCV-Python, pandas.[1]
*   **PC Controller (Hub) Actions:**
    1.  **GUI Implementation:**
        *   Fully implement the "Playback & Annotation" tab scaffolded in Phase 3.[1]
        *   Add a file dialog (`QFileDialog`) to allow the user to select a `session_metadata.json` file, which will serve as the entry point to load a session.
        *   The UI will include a main video display widget (`QLabel`), a timeline slider (`QSlider`), standard playback controls (Play, Pause, Seek), and a multi-layered data plot widget (`PyQtGraph`).
    2.  **Data Loading and Synchronization:**
        *   Implement a `DataLoader` class that parses the `session_metadata.json` and loads all associated data files (videos, CSVs) into memory or prepares them for streaming from disk.
        *   Use the `pandas` library to load all sensor CSV data (GSR, thermal, etc.) into time-indexed DataFrames.[1]
        *   Use OpenCV (`cv2.VideoCapture`) to open the video files for frame-by-frame access.[1]
    3.  **Playback Logic:**
        *   Link the timeline slider's position to the master timestamps. As the user moves the slider or as the video plays, the application will:
            *   Seek to the corresponding frame in the video file and display it in the `QLabel`.
            *   Update a vertical line cursor on the `PyQtGraph` plot to indicate the current time.
            *   Display the interpolated sensor values for that specific timestamp in a status panel.
    4.  **Annotation Feature:**
        *   Add a text box and an "Add Annotation" button. When clicked, the system will record the current timestamp and the text, saving it to an `annotations.json` file within the session directory. These annotations will be displayed as markers on the timeline.

-----

#### **Task 5.3: Data Export Functionality**

*   **Description:** Create a utility to consolidate all raw data from a session into a single, structured, and analysis-friendly file format, suitable for machine learning workflows.
*   **Technology:** Python, pandas, h5py.[1]
*   **PC Controller (Hub) Actions:**
    1.  **Export UI:**
        *   Add an "Export to HDF5" button to the "Playback & Annotation" tab.
    2.  **Export Logic:**
        *   When the button is clicked, a script will be triggered that:
            *   Loads all CSV data into pandas DataFrames.
            *   Creates a new HDF5 file using the `h5py` library.[1]
            *   Creates groups within the HDF5 file for each device and each sensor modality (e.g., `/Pixel_7/GSR/`, `/PC/Webcam/`).
            *   Saves the data from each DataFrame as datasets within the appropriate group (e.g., a `timestamps` dataset and a `values` dataset).
            *   Stores the session metadata and any annotations as attributes on the root HDF5 group.

-----

#### **Phase 5 Deliverables and Verification**

*   **Software:**
    *   A fully integrated system where data is automatically and reliably transferred from Android devices to the PC after a recording session is completed.
    *   A functional playback tool within the PC application that allows for the synchronized review and annotation of multi-modal data.
*   **Data Output:**
    *   A single, structured HDF5 file containing all data from a multi-device test session, ready for analysis in external tools like Python (with pandas/h5py) or MATLAB.
*   **Verification Criteria:**
    *   **Automated Transfer:** After a session is stopped, all data files from the Android Spoke(s) appear correctly in the designated session folder on the PC without any manual intervention. The process should be verified to handle large files (e.g., >1 GB) without crashing.
    *   **Synchronized Playback:** In the playback tool, visual events in the video (such as the "Flash Sync" event from Phase 4) must align perfectly with the corresponding timestamps on the GSR and other sensor data plots.
    *   **Data Integrity:** The data contained within the exported HDF5 file must be bit-for-bit identical to the data in the original raw files. The structure of the HDF5 file should be logical and correctly labeled.
    *   **Annotation Persistence:** Annotations created and saved in the playback tool must be correctly reloaded when the session is opened again.