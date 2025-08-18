# Comprehensive Testing Guide - Multi-Modal Physiological Sensing Platform

This guide consolidates all testing strategies, procedures, and best practices for validating the multi-modal physiological sensing platform. It covers unit testing, integration testing, system testing, and hardware testing workflows.

## Quick Reference

- **Unit Testing**: Test individual components in isolation
- **Integration Testing**: Test component interactions and data flows  
- **System Testing**: Test complete end-to-end functionality
- **Hardware Testing**: Test with real sensors and devices

## Table of Contents

1. [Unit Testing](#unit-testing)
2. [Integration Testing](#integration-testing)  
3. [System Testing](#system-testing)
4. [Hardware Testing Workflow](#hardware-testing-workflow)
5. [Quick Commands](#quick-commands)
6. [Troubleshooting](#troubleshooting)

---

## Unit Testing

Unit testing validates individual components in isolation. The platform uses framework-appropriate testing tools for each codebase.

### Python Unit Testing (PC Controller)

**Frameworks**: `unittest` (standard library) and `pytest` (preferred)

#### Using pytest
```bash
# Run all tests
pytest -q

# Run without GUI tests (when live GUI is running)
pytest -q -k "not gui"

# Run with verbose output
pytest -v
```

#### Using unittest
```bash
# Run tests in a specific module
python -m unittest test_module

# Run with higher verbosity
python -m unittest -v test_module
```

**Key Features**:
- Plain assert statements with detailed failure reports
- Automatic test discovery (`test_*.py` or `*_test.py`)
- Powerful fixture system for setup/teardown

### Android Unit Testing (Sensor Spoke)

**Frameworks**: `JUnit` for local JVM tests, `Robolectric` for Android framework dependencies

#### Running Android Tests
```bash
# Run all unit tests
.\gradlew.bat --no-daemon :android_sensor_node:app:testDebugUnitTest

# View results
android_sensor_node\app\build\reports\tests\testDebugUnitTest\index.html
```

**Test Types**:
- **Local Unit Tests**: Fast JVM tests in `app/src/test/` for pure Kotlin logic
- **Robolectric Tests**: Android framework simulation for Activities/Views

```kotlin
@RunWith(AndroidJUnit4::class)
class MyActivityTest {
    @Test
    fun clickingButton_shouldChangeMessage() {
        val controller = Robolectric.buildActivity(MyActivity::class.java)
        controller.setup()
        val activity = controller.get()

        activity.findViewById<Button>(R.id.button).performClick()
        val textView = activity.findViewById<TextView>(R.id.text)
        assertEquals("Expected Text", textView.text.toString())
    }
}
```

---

## Integration Testing

Integration testing validates interactions between system components and end-to-end data flows.

### Test Environment Setup
- Host PC running `pc_controller` application
- 2+ Android smartphones with `android_sensor_node` app
- Shared Wi-Fi network
- Shimmer3 GSR+ sensor and Topdon TC001 thermal camera

### Key Integration Test Cases

#### Test 1: Device Discovery and Connection
**Objective**: Verify PC Hub can discover and connect to Android Spokes

**Procedure**:
1. Launch PC controller application
2. Launch Android sensor app on same Wi-Fi network
3. Observe PC GUI device list
4. Initiate connection from PC

**Expected**: Android device appears in list, connection established, capabilities displayed

#### Test 2: Synchronized Multi-Device Recording
**Objective**: Verify synchronized start/stop across devices

**Procedure**:
1. Connect 2+ Android Spokes to PC Hub
2. Click "Start Session" on PC GUI
3. Record for ~30 seconds
4. Click "Stop Session"

**Expected**: All devices start/stop simultaneously, ~30-second data files created

#### Test 3: Temporal Synchronization ("Flash Sync")
**Objective**: Validate <5ms synchronization accuracy

**Procedure**:
1. Start synchronized recording with 2+ Spokes
2. Click "Flash Sync" button 3+ times at random intervals
3. Stop session and analyze data

**Expected**: 
- White flashes visible in all recorded videos
- `flash_sync_events.csv` logs precise timestamps
- Aligned timestamps differ by <5ms after NTP adjustment

#### Test 4: End-to-End Data Pipeline
**Objective**: Verify complete data workflow

**Procedure**:
1. Run complete recording session
2. Monitor PC GUI and filesystem

**Expected**:
- Android app compresses session data to ZIP
- Automatic transfer to PC Hub via network
- PC unpacks archive to correct session directory
- Data files complete and uncorrupted

#### Test 5: Fault Tolerance and Recovery
**Objective**: Test resilience to network interruptions

**Procedure**:
1. Start recording with Android Spoke
2. Disconnect Android device from Wi-Fi
3. Observe PC GUI for 15+ seconds
4. Reconnect Android device
5. Stop session

**Expected**:
- PC GUI shows "Offline" status during disconnection
- Android continues recording locally
- PC GUI shows "Online" upon reconnection
- Complete data file transferred after session

---

## System Testing

System testing evaluates the complete platform under production-like conditions.

### Test Environment
- Production PC controller application
- 4-8 Android devices with release APK
- Standard Wi-Fi network (not test network)
- Full hardware sensor complement

### Key System Test Cases

#### Test 1: Endurance and Load Test
**Objective**: Validate stability over extended periods

**Procedure**:
1. Connect maximum Android Spokes (8 devices)
2. Start synchronized recording with all sensors
3. Run continuously for 2+ hours
4. Monitor CPU/memory usage
5. Stop and transfer all data

**Expected**:
- System remains stable throughout test
- Resource usage stable (no memory leaks)
- All 2-hour data successfully recorded/transferred

#### Test 2: Usability Test
**Objective**: Evaluate end-user experience

**Procedure**:
1. Provide test subject with `User_Manual.md`
2. Ask them to complete full workflow using documentation
3. Observe interactions and note difficulties

**Expected**:
- User completes workflow successfully
- GUI intuitive with clear status indicators
- Documentation sufficient for task completion

#### Test 3: Chaos Testing (Fault Tolerance)
**Objective**: Verify resilience to real-world failures

**Procedure**:
1. Start recording with 3+ Spokes
2. Simulate failures during session:
   - Network disconnection (30s)
   - App crash and relaunch
   - Complete device power-off
3. Continue recording 1+ minute after each failure
4. Stop session and analyze results

**Expected**:
- Unaffected devices continue normal operation
- PC GUI correctly updates device status
- Recovered devices rejoin session successfully
- No data loss from unaffected devices

#### Test 4: Security Validation
**Objective**: Verify encrypted communication

**Procedure**:
1. Use Wireshark to monitor network traffic
2. Start session and observe packet content

**Expected**: TCP packets encrypted (no plaintext JSON visible)

---

## Hardware Testing Workflow

This section covers running tests while hardware devices are connected.

### Prerequisites
- Windows 10/11 with PowerShell
- Python 3.11+ and Android SDK
- Local Wi-Fi network
- Hardware: Android phones, Shimmer3 GSR+, Topdon TC001

### Two-Terminal Setup

**Terminal A - Python Tests**:
```bash
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r pc_controller\requirements.txt

# Run tests (avoid GUI contention)
pytest -q -k "not gui"
```

**Terminal B - Android Tests**:
```bash
.\gradlew.bat --no-daemon :android_sensor_node:app:testDebugUnitTest
```

### Hardware Connection Process

#### Android Devices
1. Enable Developer Options and USB debugging
2. Install debug app: `.\gradlew.bat :android_sensor_node:app:installDebug`
3. Launch app and grant Camera permissions
4. Ensure same Wi-Fi network as PC

#### Shimmer3 GSR+ (BLE Mode)
1. Power on Shimmer (ensure advertising)
2. Keep device close for Android BLE pairing
3. App logs to `flash_sync_events.csv` and GSR CSV during recording

#### Topdon TC001 (Thermal)
1. Connect via USB-OTG to Android
2. ThermalCameraRecorder writes CSV headers
3. Expected file: `thermal.csv`

### Manual Integration Session

1. **Launch Hub**: `python pc_controller\src\main.py`
2. **Start Session**: Creates `pc_controller_data\<session_id>`
3. **Flash Sync**: Generate white flashes for timing validation
4. **Stop Session**: Triggers file transfer on port 9001
5. **Validate**: `python scripts\validate_sync.py --session-id <SESSION_ID>`

### Safety Guidelines

**Resource Management**:
- Use `pytest -k "not gui"` when live GUI is running
- Android unit tests safe to run with app open on devices
- FileReceiver uses port 9001 only during session stop

**Environment**:
- Disable Gradle daemon: `--no-daemon`
- Set `org.gradle.debug=false` in gradle.properties
- Allow Python/Java through Windows firewall

---

## Quick Commands

### Test Execution
```bash
# Python unit tests
pytest -q

# Python tests (no GUI)  
pytest -q -k "not gui"

# Android unit tests
.\gradlew.bat --no-daemon :android_sensor_node:app:testDebugUnitTest

# Install Android debug app
.\gradlew.bat :android_sensor_node:app:installDebug
```

### Session Management
```bash
# Launch PC Hub GUI
python pc_controller\src\main.py

# Validate synchronization
python scripts\validate_sync.py --session-id <SESSION_ID>
```

### Build and Reports
```bash
# Android test results
android_sensor_node\app\build\reports\tests\testDebugUnitTest\index.html
```

---

## Troubleshooting

### Common Issues

**PyQt6 not installed**
- Solution: `pip install -r pc_controller\requirements.txt`
- Effect: GUI tests skipped

**Android test timeouts**
- Solution: Use `--no-daemon` flag with Gradle
- Ensure `org.gradle.debug=false`

**Discovery/Network issues**  
- Solution: Allow Python/Java through Windows firewall (private networks)
- Verify devices on same subnet
- Disable VPNs blocking mDNS/zeroconf

**No file transfers**
- Solution: Ensure "Stop Session" clicked on Hub to trigger transfer
- Check port 9001 not blocked by firewall

**Resource contention**
- Solution: Don't run GUI tests while live GUI active
- Use separate terminals for different test types

### Test Environment Issues

**Memory leaks during endurance testing**
- Monitor: Windows Task Manager, Android Studio Profiler
- Expected: Stable resource usage over time

**Synchronization failures**
- Check: NTP service configuration
- Validate: Flash sync timestamps within 5ms tolerance
- Tool: `validate_sync.py` analysis script

**Network packet analysis**
- Tool: Wireshark for security validation
- Expected: Encrypted TCP packets (no plaintext JSON)

---

For additional details on specific test categories, see the original detailed guides:
- Unit Testing: `docs/markdown/guide_unit_testing.md`
- Integration Testing: `docs/markdown/guide_integration_testing.md`  
- System Testing: `docs/markdown/guide_system_testing.md`
- Hardware Testing: `docs/markdown/guide_running_tests_with_hardware.md`