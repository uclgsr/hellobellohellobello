# How to Run Tests While Connecting Hardware (Windows)

This guide shows how to run all unit tests and prepare hardware at the same time. It consolidates quick commands and
best practices so you can validate software behavior while devices are connected and available for integration/system
sessions.

Sources of truth:

- TEST_PLAN.md — Master testing plan and quick commands
- docs/markdown/guide_unit_testing.md — Unit testing details
- docs/markdown/guide_integration_testing.md — End-to-end runs
- docs/markdown/guide_system_testing.md — System scenarios
- docs/markdown/Hardware_Validation_Protocol.md — Flash Sync SOP

Prerequisites:

- Windows 10/11
- Python 3.11+ and PowerShell
- Android Studio + Android SDK; USB drivers installed for your device
- OpenCV, NumPy (installed via pc_controller\requirements.txt)
- Local Wi‑Fi network shared by the PC Hub and Android devices

Hardware checklist:

- Android phone(s) for the Sensor Spoke app
- Optional: Topdon TC001 thermal camera via USB‑OTG
- Optional: Shimmer3 GSR+ sensor (Android BLE mode)
- Optional: Shimmer dock for PC (serial) and PC webcam for Hub-side previews

Network and ports:

- Hub auto-starts a FileReceiver on TCP 9001 during Stop Session for automated transfer (FR10)
- Preview/TCP streams use per-device sockets from the Android app
- Ensure your Windows firewall allows private network access for Python and your desktop app

---

## 1) Prepare Two Terminals

Open two PowerShell windows from the repository root.

- Terminal A: Python (Hub) tests
    - python -m venv .venv
    - .\.venv\Scripts\Activate.ps1
    - pip install -r pc_controller\requirements.txt
    - Optional: run only non-GUI tests while GUI is active: pytest -q -k "not gui"
    - Default: run all tests: pytest -q

- Terminal B: Android (Spoke) tests
    - .\gradlew.bat --no-daemon :android_sensor_node:app:testDebugUnitTest
    - Results: android_sensor_node\app\build\reports\tests\testDebugUnitTest\index.html

Notes:

- The Android tests are pure JVM/Robolectric unit tests and do not require a connected device.
- The PC tests use offscreen Qt and will auto-skip UI tests if PyQt6 isn’t available.
- If you plan to run the live Hub GUI simultaneously, prefer pytest -k "not gui" to avoid contention with Qt.

---

## 2) Connect Hardware Concurrently

You can prepare and connect hardware while tests are running in a separate terminal.

Android Spoke device(s):

- Enable Developer Options and USB debugging (optional, for install/adb)
- Install and launch the app (Debug):
    - .\gradlew.bat :android_sensor_node:app:installDebug
    - On the phone, open the app; grant Camera permission when prompted
- Ensure the phone and the PC are on the same Wi‑Fi network

Shimmer3 GSR+ (Android BLE mode):

- Turn on Shimmer and ensure it’s advertising
- Pairing is handled by the ShimmerAndroidAPI at runtime; keep the device close
- In a recording session, the app will log flash_sync_events.csv and GSR CSV in its session directory

Topdon TC001 (thermal):

- Connect via USB‑OTG
- For this phase, the ThermalCameraRecorder writes the CSV header structure; full SDK streaming is integrated later. The
  expected file (thermal.csv) confirms connectivity path

PC Hub sensors (optional):

- PC webcam: ensure available and not used by other apps
- Shimmer dock (COM port): note the assigned COMx in Windows Device Manager for future native backend integration

Network prep:

- Disable VPNs or captive portals that block mDNS/zeroconf
- Ensure the firewall allows inbound connections for Python on private networks

---

## 3) Run an Integration Session (Manual)

Perform a quick end-to-end session while tests can still run in background in the other terminal.

1. Launch Hub GUI:
    - python pc_controller\src\main.py
2. In the Dashboard:
    - Click Start Session (creates pc_controller_data\\<session_id>)
    - On Android, you should observe Start; sensors record files into the session folder
3. Click Flash Sync several times to generate visible white flashes on each Android screen
4. Click Stop Session
    - Hub starts FileReceiver (port 9001) and broadcasts transfer request
    - On success, files appear under pc_controller_data\\<session_id>\\<device_id>

Automated sync validation afterward:

- python scripts\validate_sync.py --session-id <SESSION_ID>
- PASS if overall max spread < 5 ms by default

---

## 4) Running Tests and Sessions Together Safely

- Resource contention:
    - Avoid running GUI smoke tests in pytest while the live GUI is open: use -k "not gui"
    - Android unit tests do not start services (MainActivityTest is @Ignore by default); they are safe to run while the
      app is open on devices
- Ports:
    - FileReceiver uses 9001 only when Stop Session is triggered; unit tests do not bind to that port unless explicitly
      tested
- Gradle daemon:
    - We disabled Gradle debug transport by default; if you see JDWP binding errors, ensure gradle.properties has
      org.gradle.debug=false
- Environment variables:
    - Offscreen Qt is set in tests that need it; for manual runs you don’t need to set QT_QPA_PLATFORM

---

## 5) Troubleshooting

- PyQt6 not installed: GUI tests will be skipped; install via pc_controller\requirements.txt
- Android test timeouts: pass --no-daemon to Gradle and ensure org.gradle.debug=false
- Firewall/Discovery issues: allow Python and Java through the Windows firewall on private networks; verify devices are
  on the same subnet
- No files transferred: confirm Stop Session was pressed on the Hub to trigger transfer; check that port 9001 isn’t
  blocked

---

## 6) Quick Commands Recap

- Python tests: pytest -q
- Android tests: .\gradlew.bat --no-daemon :android_sensor_node:app:testDebugUnitTest
- Live session (manual): python pc_controller\src\main.py
- Sync validation: python scripts\validate_sync.py --session-id <SESSION_ID>

For a full test plan (unit→integration→system), see TEST_PLAN.md.
