# Chapter 5 — Evaluation and Testing: Master Test and Validation Plan

This chapter defines the complete Test and Validation strategy for the Multi-Modal Physiological Sensing Platform. It
specifies objectives, methods, procedures, and acceptance criteria spanning Unit, Integration, and System testing, with
a definitive hardware validation toolkit focused on temporal accuracy.

Sources of truth and alignment:

- .junie/guidelines.md — Project-level instructions and QA expectations
- docs/latex/1.tex, 2.tex, 3.tex, 4.tex — Introduction, Background, Requirements (FR/NFR), and Design
- PROTOCOL.md — JSON/TLS command protocol and time sync handshake
- CHANGELOG.md — Implemented scope covered by this plan
- docs/Hardware_Validation_Protocol.md — Hardware-in-the-loop SOP for Flash Sync validation

Goals:

- Rigorously validate the platform against all Functional Requirements (FR) and Non-Functional Requirements (NFR) as
  specified in the docs/latex suite and .junie/guidelines.md.
- Provide reproducible procedures and automated tooling to verify temporal synchronization to <5 ms (NFR2) using the "
  Flash Sync" feature (FR7).

## 5.1 Methodology: Multi-Layered Assurance for a Distributed Platform

A distributed, research-grade data acquisition system demands a layered approach to confidence:

- Unit Tests — Validate deterministic logic in isolation (builders, parsers, data converters, file structure).
- Integration Tests — Validate interactions between Hub and Spokes over the secured protocol (TLS 1.2+), including
  command/ack flows, time sync, preview streaming, and automated data transfer.
- System Tests — Validate the complete platform under realistic scenarios for reliability, endurance, usability,
  security, and recovery.
- Hardware Validation — Definitive, human-verifiable SOP with automated analysis of real recordings to prove temporal
  alignment.

Each layer feeds the next: unit tests guard core logic, integration tests verify component cooperation, and system tests
and SOP validate the production workflows and scientific claims.

## 5.2 Unit Testing Strategy

Objectives:

- Catch regressions early with fast, deterministic checks.
- Provide ≥90% coverage on the most critical modules wherever practical.
- Ensure all public functions have at least one meaningful assertion.

### 5.2.1 PC Controller (pytest)

Scope and existing tests (see pc_controller/tests):

- Protocol JSON Builders and Utilities
    - Module: pc_controller/src/network/protocol.py
    - Tests: pc_controller/tests/test_protocol.py
    - Validates: query_capabilities, start_recording, stop_recording, flash_sync, time_sync, transfer_files;
      line-delimited JSON format; NTP-like offset math helpers.
- HDF5 Export
    - Module: pc_controller/src/data/hdf5_exporter.py
    - Tests: pc_controller/tests/test_hdf5_exporter.py
    - Verifies: group structure per device/modality, dataset dtypes, root attributes, timestamp handling. Skips
      gracefully if pandas/h5py missing.
- Session Data Loading
    - Module: pc_controller/src/data/data_loader.py
    - Tests: pc_controller/tests/test_data_loader.py
    - Verifies: CSV parsing for Android outputs; correct index normalization to integer timestamps (ns) across variants.
- Validation Core (Flash Sync analytics)
    - Module: pc_controller/src/tools/validate_sync_core.py
    - Tests: pc_controller/tests/test_validate_sync_core.py
    - Verifies: flash peak detection, video-origin estimation, per-event spread, and PASS/FAIL evaluation given a
      tolerance.

How to run (Windows PowerShell from repository root):

- python -m venv .venv; .\.venv\Scripts\Activate.ps1
- pip install -r pc_controller\requirements.txt
- pytest -q

### 5.2.2 Android Sensor Node (JUnit + Robolectric)

Scope and existing tests (see android_sensor_node/app/src/test):

- Shimmer 12-bit ADC to microsiemens (μS)
    - Module: sensors/gsr/ShimmerRecorder.kt
    - Tests: sensors/gsr/ShimmerRecorderTest.kt
    - Validates: 12-bit range (0–4095) clamps, correct scaling to μS, and representative edge cases.
- Session Directory Creation and State
    - Module: controller/RecordingController.kt
    - Tests: controller/RecordingControllerTest.kt
    - Ensures: per-session folder creation, per-recorder subfolders, start/stop transitions and idempotence.
- File Transfer Zipping & Streaming
    - Module: network/FileTransferManager.kt
    - Tests: network/FileTransferManagerTest.kt (Robolectric)
    - Verifies: zip archive contents and integrity; JSON transfer header; round-trip consistency.

How to run:

- gradlew.bat :android_sensor_node:test

Documentation Note: These unit tests validate core correctness for message format, data serialization, parsing, and
conversion logic — the foundations for reliable end-to-end operation.

## 5.3 Integration Test Cases (Hub–Spoke)

Approach: Human-guided end-to-end runs with real devices, guided by docs/guide_integration_testing.md. Logs, GUI state,
and session files are the system of record; automated post-hoc analysis via scripts/validate_sync.py.

Test Cases (procedure → expected outcome):

1) Device Discovery, Connection, and Capabilities Exchange

- Procedure: Launch Hub and one Spoke; verify discovery and connection; confirm handshake capabilities in GUI.
- Expected: Device listed; connection established; capabilities correctly shown.

2) Synchronized Multi-Device Recording (FR2)

- Procedure: Connect ≥2 Spokes; Hub: Start Session, record ~30 s; Stop Session.
- Expected: All devices start/stop in sync; files present; durations approximately match control time.

3) Temporal Synchronization — Flash Sync (FR3, FR7, NFR2)

- Procedure: Trigger Flash Sync ≥3 times during a short recording; stop; run scripts/validate_sync.py.
- Expected: flash_sync_events.csv exists for each device; after applying offsets, per-event spreads <5 ms.

4) Automated Data Transfer Pipeline (FR10)

- Procedure: Stop Session; observe automatic ZIP transfer and unpacking on Hub.
- Expected: Files received under pc_controller_data/<session_id>/<device_id>/; contents intact.

5) Fault Tolerance and Reconnection (FR8, NFR3)

- Procedure: Interrupt Wi‑Fi mid-session; then restore.
- Expected: GUI shows Offline→Online; Spokes keep local recording; final transfer yields continuous data.

Artifacts: Logs, GUI screenshots (optional), and session directory under pc_controller_data/<session_id>/.

## 5.4 System Test Cases

Objective: Validate the assembled system against FR and NFR under realistic conditions (docs/guide_system_testing.md).

1) Endurance and Load (NFR1, NFR7)

- Procedure: Run ≥2 hours with multiple Spokes; monitor CPU, memory, storage.
- Expected: No crashes or critical leaks; stable performance; all data transfers complete successfully.

2) Usability (FR6, NFR6)

- Procedure: Give User_Manual.md to a new user; ask them to run a 5-minute session and playback.
- Expected: Task completed without developer assistance; collect qualitative feedback.

3) Recovery and Fault Tolerance (Chaos) (FR8, NFR3)

- Procedure: Simulate network drop, app crash, and device power off.
- Expected: Remaining devices unaffected; upon recovery, system resumes expected state; data integrity preserved.

4) Security Validation (NFR5)

- Procedure: Use Wireshark to inspect traffic while connected over TLS 1.2+.
- Expected: Encrypted payloads; no plaintext JSON visible; certificates validated per setup.

Acceptance: All scenarios must meet expected results without critical defects. Deviations are logged with severity and
reproduction steps.

## 5.5 Definitive Validation Toolkit for Temporal Accuracy (NFR2)

This section is the authoritative procedure and tooling for validating the <5 ms synchronization claim.

- SOP Document: docs/Hardware_Validation_Protocol.md — Step-by-step Standard Operating Procedure for a real Flash Sync
  session. References required equipment, setup, execution, and acceptance criteria.
- Automated Analysis Script: scripts/validate_sync.py — CLI to analyze the session and produce a PASS/FAIL verdict based
  on per-event spreads.

Usage:

- python scripts\validate_sync.py --session-id <SESSION_ID> [--base-dir <PATH>] [--tolerance-ms 5.0]

Behavior:

- Loads session_metadata.json to read per-device NTP-like offsets.
- Reads flash_sync_events.csv and videos per device; detects flash frames; aligns times; computes per-event spread and
  overall maximum; prints a clear PASS/FAIL verdict (default tolerance 5.0 ms).

Outputs:

- Console report with device summaries, detected streams, per-event spreads, overall maximum spread, and PASS/FAIL
  verdict.

## 5.6 Traceability to Requirements and CHANGELOG

- FR2 (Synchronized start/stop): Integration Test 2; RecordingControllerTest; protocol builder unit tests.
- FR3/FR7/NFR2 (Temporal synchronization): SOP + validate_sync.py + validation core unit tests; acceptance <5 ms.
- FR8/NFR3 (Fault tolerance): Integration Test 5; System Chaos Tests.
- FR9 (Calibration): camera_calibration utility and tests (if in scope of release).
- FR10 (Automated transfer): FileTransferManager tests; Integration Test 4.
- NFR5 (Security): System Security Validation test; TLS traffic encrypted.
- All implemented features mentioned in CHANGELOG.md are covered by at least one unit/integration test and/or SOP with
  automated validator.

## 5.7 Execution, Reporting, and Roles

Execution Order: Unit → Integration → System → Hardware Validation.

Reporting:

- Store outputs under /test_artifacts/<date> with links to session directories.
- Archive console output of validate_sync.py and note PASS/FAIL.

Roles:

- QA Lead: Owns this chapter, triage, and sign-off.
- Developers: Keep tests green; extend coverage when adding features; maintain docs.

## 5.8 Maintenance

- Keep this chapter and CHANGELOG.md updated when test scope changes.
- Ensure CI executes pytest and Gradle unit tests on each change.
- Maintain dependency lists in pc_controller/requirements.txt and Gradle files; avoid unused libraries.

## 5.9 Quick Commands

- Python unit tests: pytest -q
- Android unit tests: gradlew.bat :android_sensor_node:test
- Validation: python scripts\validate_sync.py --session-id <SESSION_ID>
