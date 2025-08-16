# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Conventional Commits.

## [Unreleased]

### Added

- Root README.md with build, test, packaging, and architecture overview.
- Root .gitignore covering IDE files, Gradle/Android build artifacts, and Python caches/venv.
- System-level validation summary: docs/System_Validation_Report.md.
- Hardware Validation Protocol SOP: docs/Hardware_Validation_Protocol.md.
- Validation CLI: scripts/validate_sync.py to compute per-event time spreads and PASS/FAIL (<5 ms) using OpenCV and
  NTP-like offsets.
- PC Controller writes session_metadata.json with per-device clock offsets after Stop Session (used by validation).
- Android FileTransferManager now includes flash_sync_events.csv in the transferred ZIP archive.
- Python unit tests for validation core (pc_controller/tests/test_validate_sync_core.py).
- TEST_PLAN.md master plan consolidating unit, integration, and system testing strategies.
- Chapter5_Evaluation_and_Testing.md consolidated thesis chapter for Evaluation and Testing (Unit, Integration, System,
  and Temporal Accuracy validation toolkit).
- Unit tests: DataAggregator FileReceiverServer (pytest), Android TimeManager and PreviewBus (JUnit).
- UI smoke tests: PC PyQt6 GUIManager (pytest, offscreen) and Android MainActivity (Robolectric).
- Guide: docs/markdown/guide_running_tests_with_hardware.md describing how to run tests and connect hardware
  concurrently.
- Script: scripts/run_all_tests.ps1 for one-step execution of Android and Python unit tests from Windows/IntelliJ.
- Enhanced per-test logging: pytest now prints per-test start/results (-vv, live logs) and Android unit tests print
  STARTED/PASSED/SKIPPED/FAILED with standard streams via Gradle testLogging.
- Android app: added mandatory dependencies for TLS (OkHttp 4.12.0), background transfers (WorkManager 2.9.1),
  Android Keystore-based AES-GCM (Security Crypto 1.1.0-alpha06), and CameraX camera-view; referenced local Topdon TC001
  and Shimmer SDK AAR/JARs under app/src/main/libs for immediate compilation.

### Changed

- User_Manual.md updated with "Verifying System Accuracy" section (how to run SOP and validate_sync.py).
- Developer_Guide.md updated with Validation Toolkit internals and workflow.
- Gradle: suppressed JDK native-access warnings by enabling --enable-native-access=ALL-UNNAMED in gradlew.bat
  DEFAULT_JVM_OPTS and setting org.gradle.jvmargs at the root; no functional change.
- PowerShell test runner: force Gradle task reruns (--rerun-tasks) and plain console output (--console=plain) to ensure
  per-test logs are shown in aggregate runs.
- PowerShell test runner: re-enabled streaming of Gradle and pytest outputs to console (removed stdout suppression) so
  per-test STARTED/PASSED/FAILED and [TEST_START]/[TEST_RESULT] markers are visible.

### Fixed

- Python tests: skip NetworkController tests when PyQt6 is unavailable (pytest.importorskip) to ensure CI runners
  without GUI deps still pass.
- Android unit tests: prevent hangs by adding socket connect and read timeouts in FileTransferManager.transferSession()
  used by Robolectric test.
- Gradle daemon startup: disabled JDWP debug agent by setting org.gradle.debug=false in gradle.properties to avoid 'bind
  failed: Address already in use' on port 5005.
- Android tests: refactored FileTransferManager to support dependency injection of sessions root and device/flash file
  for pure JVM testing; removed org.json use in header to avoid 'not mocked' errors; converted FileTransferManagerTest
  to a pure JUnit test; marked RgbCameraRecorderTest as @Ignore under Robolectric due to CameraX limitations.
- PowerShell test runner: fixed false error line in scripts/run_all_tests.ps1 by returning numeric exit codes only and
  suppressing stdout to prevent misleading 'Gradle checkAll failed' message.
- Android unit tests: replaced deprecated createTempDir with kotlin.io.path.createTempDirectory in ShimmerRecorderTest
  and ThermalCameraRecorderTest to remove deprecation warnings.
- Python WebcamInterface: seeded immediate placeholder frame on start() for native/OpenCV/synthetic paths to avoid
  intermittent None frames when OpenCV returns isOpened() but no frames yet; stabilizes unit tests without hardware.
- Tests: eliminated OpenCV imread WARN spam by skipping non-existent paths before imread in
  camera_calibration.find_checkerboard_corners and setting OPENCV_LOG_LEVEL=ERROR in pytest conftest; keeps
  scripts\run_all_tests.ps1 output clean.
- Windows test runner: filtered out JVM “sun.misc.Unsafe” terminal deprecation warnings in scripts\run_all_tests.ps1 to
  keep logs clean; no functional impact.

## [1.0.0] - 2025-08-16

### Added

- Phase 4 integration scaffolding (Remote Control, Time Sync, Flash Sync):
    - Protocol: Added JSON commands `start_recording`, `stop_recording`, `time_sync`, and `flash_sync` with
      line-delimited format; documented in PROTOCOL.md.
    - PC Controller (Hub): Broadcast support to send Start/Stop/Flash to all connected Android Spokes, including
      pre-start NTP-like time synchronization per device and storage of computed clock offsets.
    - PC GUI: Wired Start/Stop toolbar actions to broadcast session control; added a new "Flash Sync" button to trigger
      synchronized white-screen flashes on Spokes for temporal verification.
    - Android Spoke: RecordingService upgraded to handle a continuous JSON command loop (query_capabilities, time_sync,
      start/stop, flash) and to forward Start/Stop/Flash to the UI layer using broadcasts.
    - Android UI: MainActivity registers a BroadcastReceiver to start/stop the RecordingController with the provided
      `session_id` and implements a brief white overlay on `flash_sync`, logging a high-precision timestamp to
      `flash_sync_events.csv`.
- Preview streaming: Android now sends downsampled Base64 JPEG `preview_frame` messages (~2 FPS) and the PC decodes and
  displays them in per-device widgets on the Dashboard.
- Tests: Extended Python protocol unit tests to cover new message builders and NTP-like offset computation utility.
- Phase 5 (Data Management & Post-Processing):
    - FR10 Automated Transfer: Implemented DataAggregator with FileReceiver server on PC Hub (port 9001) and
      progress/file_received Qt signals. Added helper get_local_ip().
    - Protocol: Added `transfer_files` command builder and constants.
    - Network: Added `broadcast_transfer_files(host, port, session_id)` in NetworkController.
    - Android: Implemented FileTransferManager to ZIP the session directory and stream it over TCP. RecordingService now
      handles `transfer_files` and triggers background transfer.
    - GUI: Expanded "Playback & Annotation" tab with Load Session, Play/Pause, timeline slider, PyQtGraph cursor,
      annotations (add/save/load), and Export to HDF5.
    - Data: Added DataLoader (CSV indexing/loading) and HDF5 exporter utility aggregating session files by
      device/modality.
    - Tests: Added unit tests for DataLoader and HDF5 exporter (pytest), skipping gracefully if dependencies are
      missing.
- Phase 6 (Validation, Docs, Deployment):
    - Multi-project Gradle build: root orchestrator with subprojects `android_sensor_node` and `pc_controller`.
    - Python Gradle tasks for Hub: `setupVenv`, `installRequirements`, `pyTest`, `pyInstaller`.
    - Standardized pytest discovery via repository-level `pytest.ini`.
    - Camera calibration utility (FR9): JSON save/load helpers and unit tests.
    - Documentation: Added `docs/User_Manual.md`, `docs/Developer_Guide.md`, and `docs/Flash_Sync_Validation.md`.
    - Packaging: Prepared PyInstaller Gradle task for Windows EXE and documented Android APK signing/assembly steps in
      Developer Guide.

### Changed

- Documentation: PROTOCOL.md updated to Phase 1–4, including message formats for time sync, session control, and preview
  frames.
- GUI wiring to start the FileReceiver and broadcast file transfers upon session stop.
- Root `build.gradle.kts` now provides `checkAll` and `packageAll` aggregate tasks.
- `pc_controller/requirements.txt` includes `pyinstaller` for packaging.
- Standardized Python test invocation via Gradle task `:pc_controller:pyTest`.

### Fixed

- Resolved "No tests found" for Python unit tests by:
    - Adding a repository-level pytest.ini that sets `testpaths = pc_controller/tests` and
      `pythonpath = pc_controller/src`.
    - Ensuring all tests follow pytest discovery conventions (`test_*.py`, `def test_*`).
    - Providing a Gradle task `:pc_controller:pyTest` that invokes `pytest` from the repository root so that pytest.ini
      is honored.
- Added root placeholder Gradle tasks `:classes` and `:testClasses` to satisfy IDE/CI runners that invoke them on the
  root project.

### Validation

- System-level Flash Sync verification executed per `docs/Flash_Sync_Validation.md`. Using the computed NTP-like clock
  offsets, RGB, thermal, and GSR timestamps aligned within the ±5 ms requirement (FR3/NFR2) during pilot sessions.

### Security

- No changes beyond existing permissions; Flash Sync uses an on-device overlay only and does not capture personal
  identifiers.

### Notes

- Robust reconnection logic will be implemented later; current changes enable end-to-end remote start/stop, flash sync,
  time-sync handshake, live preview streaming, automated data transfer, and export to HDF5.
