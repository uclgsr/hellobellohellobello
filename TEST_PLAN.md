# Test and Validation Plan — Multi-Modal Physiological Sensing Platform

This master plan defines the complete testing strategy for the platform across Unit, Integration, and System levels. It consolidates procedures, responsibilities, and acceptance criteria for verifying functionality, performance, reliability, and data integrity.

References (source of truth):
- docs/latex/1.tex (Introduction and Objectives)
- docs/latex/2.tex (Background and Literature Review)
- docs/latex/3.tex (Requirements and Analysis)
- docs/latex/4.tex (Design and Implementation Details)
- CHANGELOG.md (scope of implemented features covered by this plan)
- docs/guide_unit_testing.md, docs/guide_integration_testing.md, docs/guide_system_testing.md (how-to references)
- docs/Hardware_Validation_Protocol.md (SOP for hardware-in-the-loop validation)

Scope:
- Hub (PC Controller, Python/PyQt6 + native C++ backend for perf-critical code)
- Spoke (Android Sensor Node, Kotlin, MVVM)
- Hub–Spoke network protocol, time sync, file transfer, and validation toolkit


## 1. Unit Testing Strategy

Objectives:
- Validate self-contained logic deterministically and quickly.
- Provide high-confidence guards for protocol formatting, data parsing, and conversions.

Coverage Targets:
- New and critical modules: ≥90% lines and branches where practical.
- All public functions/utilities have at least one meaningful assertion.

### 1.1 PC Controller (pytest)

Focus Areas and Existing Tests:
- Network Protocol builders and math
  - Module: pc_controller/src/network/protocol.py
  - Tests: pc_controller/tests/test_protocol.py
  - Validates JSON line format for: query_capabilities, start_recording, stop_recording, flash_sync, time_sync, transfer_files; and NTP-like offset computation.
- CSV Aggregation to HDF5
  - Module: pc_controller/src/data/hdf5_exporter.py
  - Tests: pc_controller/tests/test_hdf5_exporter.py (skips if pandas/h5py unavailable)
  - Verifies device/modality groups, timestamp handling, dataset existence, and root attributes.
- Session Data Loading
  - Module: pc_controller/src/data/data_loader.py
  - Tests: pc_controller/tests/test_data_loader.py (skips if pandas unavailable)
  - Verifies recursive indexing and timestamp_ns/ts_ns/timestamp/time_ns as int index.
- Validation Core (Flash Sync analytics)
  - Module: pc_controller/src/tools/validate_sync_core.py
  - Tests: pc_controller/tests/test_validate_sync_core.py
  - Verifies peak detection, video time-origin estimation, and PASS/FAIL computation.
- File Receiver and Data Aggregation (FileReceiverServer)
  - Module: pc_controller/src/data/data_aggregator.py
  - Tests: pc_controller/tests/test_data_aggregator.py (skips if PyQt6 unavailable)
  - Verifies JSON header parsing and end-to-end receive/unpack of ZIP into session/device directory via a live TCP connection.

How to Run (from repository root):
- Windows PowerShell
  - python -m venv .venv; .\.venv\Scripts\Activate.ps1
  - pip install -r pc_controller\requirements.txt
  - pytest -q

### 1.2 Android Sensor Node (JUnit + Robolectric)

Focus Areas and Existing Tests:
- Shimmer 12-bit ADC Conversion to microsiemens (μS)
  - Module: android_sensor_node/app/src/main/java/.../sensors/gsr/ShimmerRecorder.kt
  - Tests: android_sensor_node/app/src/test/java/.../sensors/gsr/ShimmerRecorderTest.kt
  - Validates 12-bit range (0–4095) clamping and conversion scaling.
- Session Directory Creation and State
  - Module: android_sensor_node/app/src/main/java/.../controller/RecordingController.kt
  - Tests: android_sensor_node/app/src/test/java/.../controller/RecordingControllerTest.kt
  - Ensures per-session folder and per-recorder subfolders are created; start/stop transitions are correct.
- File Transfer Zipping & Streaming
  - Module: android_sensor_node/app/src/main/java/.../network/FileTransferManager.kt
  - Tests: android_sensor_node/app/src/test/java/.../network/FileTransferManagerTest.kt (Robolectric)
  - Verifies that a JSON header and a ZIP stream are sent and entries match source files.

How to Run (from repository root):
- gradlew.bat :android_sensor_node:test
- Results are reported under android_sensor_node/app/build/test-results/testDebugUnitTest.


## 2. Integration Testing Strategy (Hub–Spoke)

Approach:
- Human-guided end-to-end sessions with real devices per docs/guide_integration_testing.md. Logs, GUI state, and final files are the system-of-record. Automated analysis via scripts/validate_sync.py.

Key Test Cases (detailed procedures and expected outcomes):
1) Device Discovery, Connection, and Capabilities Exchange
- Procedure: Launch Hub and one Spoke; verify discovery and connect; confirm initial JSON handshake capability info on GUI.
- Expected: Device listed; connection established; capabilities shown (e.g., cameras) per handshake.

2) Synchronized Multi-Device Recording (FR2)
- Procedure: Connect ≥2 Spokes; Hub: Start Session, record ~30 s, Stop Session.
- Expected: All devices start/stop in sync; files appear on devices; durations ~30 s.

3) Temporal Synchronization Verification — Flash Sync (FR3, FR7, NFR2)
- Procedure: During recording, trigger Flash Sync ≥3 times; stop; transfer; run automated validation.
- Expected: Videos show visible flashes; flash_sync_events.csv exists per device; after NTP offset alignment, aligned timestamps differ <5 ms.

4) End-to-End Data Pipeline Integrity (FR10)
- Procedure: Run a complete short session; observe automatic ZIP transfer; Hub unpacks into session directory.
- Expected: Files received under correct session/device folders; content uncorrupted and identical to sources.

5) Fault Tolerance and Network Reconnection (FR8, NFR3)
- Procedure: Disconnect Wi‑Fi mid-session; observe GUI; reconnect; stop.
- Expected: GUI shows Offline then Online; Spoke continues local recording; final transfer includes continuous data.

Artifacts:
- Logs, session directory under pc_controller_data\<session_id>\, and validation report.


## 3. System Testing Strategy

Purpose: Validate the fully assembled system against functional/non-functional requirements as in docs/guide_system_testing.md.

Key Scenarios:
1) Endurance and Load (NFR1, NFR7)
- Run ~2 hours with many Spokes; monitor CPU/memory; ensure no leaks/crashes; full data transfer and aggregation.

2) Usability (FR6, NFR6)
- Provide User_Manual.md to a new user; they run a 5-minute session and playback; collect feedback.

3) Recovery and Fault Tolerance (Chaos) (FR8, NFR3)
- Simulate network interruption, app crash, and device power off; verify unaffected devices continue and recovery behavior is correct.

4) Security Validation (NFR5)
- Use Wireshark to confirm TLS encryption: packets unreadable; plaintext JSON must not be visible.

Acceptance: All scenarios meet expected outcomes with no critical defects; deviations logged as issues with reproduction steps and severity.


## 4. System Validation Toolkit

SOP: docs/Hardware_Validation_Protocol.md — step-by-step instructions to execute a Flash Sync validation session.

Automated Analysis:
- Script: scripts/validate_sync.py
- Usage:
  - python scripts\validate_sync.py --session-id <SESSION_ID> [--base-dir <PATH>] [--tolerance-ms 5.0]
- Behavior:
  - Loads session_metadata.json for per-device offsets.
  - Reads each device’s flash_sync_events.csv and video; detects flash frames.
  - Computes per-event spreads across streams and prints a report with a clear PASS/FAIL verdict based on required <5 ms tolerance by default.

Outputs: Console report including per-event spread (ms), overall max spread (ms), and PASS/FAIL verdict.


## 5. Traceability to Requirements and CHANGELOG

- FR2 (Synchronized start/stop): Covered by Integration Test Case 2 and relevant unit tests (protocol builders) and RecordingControllerTest.
- FR3/FR7/NFR2 (Temporal sync): Flash Sync SOP + validate_sync.py + validation core unit tests; PASS criterion <5 ms.
- FR8/NFR3 (Fault tolerance): Integration and System recovery tests.
- FR9 (Calibration): pc_controller/src/tools/camera_calibration.py and its tests (if applicable to release) — sanity checks.
- FR10 (Automated transfer): FileTransferManager tests; end-to-end Integration Case 4.
- Security NFR5: System Security Validation test case.
- All implemented features listed in CHANGELOG.md are covered by at least one unit or integration test and/or the SOP plus automated validator.


## 6. Execution, Reporting, and Roles

- Execution Order: Unit → Integration → System.
- Reporting: Store artifacts under /test_artifacts/<date> with links to session folders. For automated validation, save console output.
- Roles:
  - QA Lead: Owns plan, triage, and sign-off.
  - Developers: Keep tests green; expand coverage when adding features.


## 7. Maintenance

- Update this TEST_PLAN.md and CHANGELOG.md when adding features or modifying test scope.
- Keep dependencies minimal and documented in pc_controller/requirements.txt and Android Gradle files.
- Ensure CI runs pytest and Gradle unit tests on every change.


## 8. Quick Commands

- Python unit tests: pytest -q
- Android unit tests: gradlew.bat :android_sensor_node:test
- Validation: python scripts\validate_sync.py --session-id <SESSION_ID>


### 1.3 UI Tests (Smoke)

- PC Controller (PyQt6): pc_controller/tests/test_gui_manager_ui_smoke.py verifies tab titles (Dashboard, Logs, Playback & Annotation) and toolbar actions exist using offscreen Qt; auto-skips if PyQt6 is unavailable on CI.
- Android (Robolectric): android_sensor_node/app/src/test/java/com/yourcompany/sensorspoke/ui/MainActivityTest.kt launches MainActivity and asserts presence of Start/Stop buttons; MainActivity conditionally skips starting services when running under tests to keep the test fast and isolated.
