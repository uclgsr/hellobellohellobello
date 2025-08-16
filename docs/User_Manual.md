# Multi-Modal Physiological Sensing Platform — User Manual

This manual explains how to install, run, and validate the platform for research use.

## 1. Components
- PC Controller (Hub): PyQt6 application controlling sessions, aggregating data, exporting HDF5.
- Android Sensor Node (Spoke): Records RGB/Thermal/GSR and responds to commands from the Hub.

## 2. Installation
### 2.1. Windows PC (Hub)
1. Install Python 3.11+ and Git.
2. Clone the repository.
3. Build prerequisites (first time only):
   - From repo root: `./gradlew.bat :pc_controller:installRequirements`
4. Launch (dev mode): `python pc_controller\src\main.py`

### 2.2. Android (Spoke)
1. Open the project in Android Studio (Hedgehog or newer).
2. Connect a device with USB debugging enabled.
3. Build and run the `app` module.

## 3. Running a Pilot Session
1. Start the PC Controller.
2. Ensure Android device(s) are on the same secure network.
3. Use Dashboard to discover devices; press Start Recording.
4. Use "Flash Sync" to trigger synchronized white-screen flash across nodes for temporal check.
5. Press Stop Recording; the Spoke will transfer session files automatically to the Hub.
6. Use Playback & Annotation tab to inspect streams and Export to HDF5 if needed.

## 4. Camera Calibration (FR9)
Use a checkerboard target and capture several images at different orientations.
- Use the calibration utility (Tools) to compute intrinsics and save parameters as JSON.
- Parameters are used for undistortion and improved pose accuracy.

## 5. Validating Time Synchronization
After file transfer completes, open the Flash Sync Validation tool (or follow docs/Flash_Sync_Validation.md). Ensure timestamps across streams align within ±5 ms.

## 6. Packaging
- PC Controller (Windows): `./gradlew.bat :pc_controller:assemblePcController` produces an EXE under `pc_controller/build/dist/`.
- Android APK: `./gradlew.bat :android_sensor_node:app:assembleRelease`.

## 7. Safety and Data Protection
- Participant IDs only; no PII stored with sensor data.
- Face blurring can be enabled in video post-processing pipeline.
- All communication is TLS 1.2+; data at rest should be encrypted on Android using Keystore-backed AES256-GCM.

## 8. Troubleshooting
- If no devices appear: check firewall, TLS certificates, and that both PC and phone are on the same network.
- If HDF5 export fails: validate CSV paths and ensure `h5py` is installed.
- If calibration cannot find corners: verify checkerboard size matches settings and lighting is adequate.
