# Multi-Modal Physiological Sensing Platform

This repository contains a hub-and-spoke research platform for synchronous, multi-modal data collection.

- Hub (PC Controller, Python + PyQt6): Central controller for session management, device control, data aggregation, playback/annotation, and export to HDF5. Performance-critical components are bridged via a C++ native backend (PyBind11).
- Spoke (Android Sensor Node, Kotlin/Android): Mobile client that interfaces with hardware (RGB, thermal, Shimmer GSR), records locally, and communicates with the Hub over a secured TCP/IP socket.

See docs/1_high_level_design.md and docs/2_detail_design.md for design overviews, and docs/3_phase_by_phase.md with the phase-specific implementation notes.

## Project Structure

- pc_controller/ — Python sources, native backend (C++), tests, and Gradle tasks for Python workflows
- android_sensor_node/ — Android application (MVVM), with CameraX RGB, Topdon TC001 thermal integration, and Shimmer GSR
- docs/ — User and developer documentation, protocol, validation guides, and LaTeX sources
- scripts/ — Utility scripts (e.g., backup_script.py)

## Prerequisites

- Windows 10/11 for the packaged PC Controller EXE (PyInstaller). Python 3.11+ for development.
- Android Studio (Giraffe/Koala or newer), Android SDK/NDK as required by your environment.
- Git and Java 17+ for Gradle builds.

## Building and Running

### PC Controller (Hub)

- Create a virtual environment and install requirements:
  - From repository root: `gradlew.bat :pc_controller:installRequirements` (creates pc_controller/.venv and installs deps)
- Run tests: `gradlew.bat :pc_controller:pyTest` or simply `pytest` from the repo root (pytest.ini is configured)
- Package EXE: `gradlew.bat :pc_controller:assemblePcController`
  - Output is placed under pc_controller/build/dist/pc_controller.exe

To run from sources (development):
- Activate the venv: `pc_controller\.venv\Scripts\activate`
- Launch: `python pc_controller\src\main.py`

### Android Sensor Node (Spoke)

- Open the project in Android Studio at the repository root (multi-project Gradle). The Android module is at android_sensor_node/app.
- Build debug: `gradlew.bat :android_sensor_node:app:assembleDebug`
- Unit tests: `gradlew.bat :android_sensor_node:app:testDebugUnitTest`
- Release APK: `gradlew.bat :android_sensor_node:app:assembleRelease`

> Note: Building the Android module requires a properly configured Android SDK/NDK in your environment.

### Aggregate Tasks

- Run both Python and Android unit tests: `gradlew.bat checkAll`
- Package PC EXE and Android APK: `gradlew.bat packageAll`

## Communication and Time Sync

- TLS 1.2+ TCP/IP socket with JSON control messages. See PROTOCOL.md for full message formats.
- NTP-like handshake upon connection to compute per-device clock offsets. All data is timestamped using device-local monotonic clocks; alignment is performed in post-processing.
- Flash Sync: The Hub can broadcast a `flash_sync` command to trigger simultaneous on-screen flashes across devices for temporal validation (docs/Flash_Sync_Validation.md).

## Data Handling

- Android records locally per session (RGB MP4 + FR5 JPEGs, thermal CSV, GSR CSV). On stop, the device can transfer a ZIP of the session to the Hub over TCP.
- On PC, DataLoader and HDF5 exporter utilities aggregate and export into a research-ready HDF5 file.
- Backup strategy and automation: see BACKUP_STRATEGY.md and scripts/backup_script.py.

## Native Backend

- pc_controller/native_backend contains the C++ code intended for PyBind11 integration for high-performance capture (Shimmer via Shimmer C-API and local webcam via OpenCV). Consult pc_controller/native_backend/README.md for build guidance.

## Security & Ethics

- Android local storage is encrypted using Android Keystore (AES256-GCM) for sensitive artifacts. The PC Controller requires authentication (see GUI docs) and supports anonymized participant IDs.
- Face blurring is available in video streams to protect privacy (see GUI playback tools in docs).

## Testing

- Python unit tests use pytest (see pytest.ini; tests located under pc_controller/tests). Run via `pytest` or Gradle task `:pc_controller:pyTest`.
- Android unit tests use JUnit; invoke via `:android_sensor_node:app:testDebugUnitTest`.
- System-level validation for temporal accuracy is described in docs/Flash_Sync_Validation.md.

## Contributing

- Follow Conventional Commits. Update CHANGELOG.md (Keep a Changelog) under the Unreleased section for every change.
- Branching model: main (stable), develop (integration), feat/... for features.

## License

This repository is intended for research use. See headers in source files and institutional policies for licensing details.
