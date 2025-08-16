# Changelog
All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Conventional Commits.

## [Unreleased]
### Added
- Android Sensor Node: Phase 2 local recording functionality (standalone mode)
  - RecordingController manages per-session directories (unique ID) and orchestrates sensor recorders using coroutines and StateFlow.
  - RGB: CameraX-based RgbCameraRecorder records 1080p MP4 and saves continuous high-res JPEG frames named by nanosecond timestamp.
  - Thermal: ThermalCameraRecorder scaffold creates `thermal.csv` with header (Topdon SDK integration point prepared).
  - GSR: ShimmerRecorder scaffold creates `gsr.csv` with header and includes 12-bit ADC conversion helper for accurate μS calculation (ShimmerAndroidAPI integration point prepared).
  - Simple Start/Stop UI in MainActivity to trigger recording sessions and request CAMERA permission.
- Dependencies: Added CameraX libraries for video and image capture.
- Tests: Added minimal JVM unit test for RecordingController session directory creation and state transitions (using a mock SensorRecorder).
- PC Controller (Hub): Phase 3 GUI with Dashboard and Logs tabs using PyQt6 and PyQtGraph for live GSR plotting; dynamic grid with video and GSR widgets.
- PC Controller (Hub): Local device interfaces (ShimmerInterface, WebcamInterface) with native-backend integration and robust Python fallbacks.
- PC Controller (Hub): Native C++ backend scaffolding via PyBind11 (NativeShimmer + NativeWebcam) with simulated data sources and zero-copy NumPy frames.
- PC Controller (Hub): Unit tests for local interfaces (fallback behavior) to keep GUI responsive without hardware.

### Changed
- Refactored SensorRecorder interface to accept a session directory parameter for consistent file output handling.

### Security
- Manifest updated with required permissions for CAMERA and placeholders for BLE/USB to support upcoming integrations.

### Notes
- Integration with Topdon TC001 (topdon-sdk) and ShimmerAndroidAPI will be completed in a subsequent step; current scaffolds keep builds green while establishing file formats and session management.
