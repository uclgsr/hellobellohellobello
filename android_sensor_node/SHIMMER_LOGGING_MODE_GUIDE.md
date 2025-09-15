# Shimmer3 GSR Logging-Only Mode Implementation Guide

## Overview

This guide documents the implementation of logging-only mode for Shimmer3 GSR+ sensors, where data is stored on the Shimmer's internal SD card with device timestamps, and the Android app acts as a remote controller.

## Architecture

### Two Recording Modes

The `ShimmerRecorder` now supports two operational modes:

1. **REAL_TIME_STREAMING**: Live BLE data streaming with local CSV logging (existing functionality)
2. **LOGGING_ONLY**: Remote control mode where Shimmer logs to SD card (new functionality)

### Key Components

#### 1. ShimmerRecordingMode Enum
```kotlin
enum class ShimmerRecordingMode {
    REAL_TIME_STREAMING,  // Live BLE data streaming to app
    LOGGING_ONLY         // Log to Shimmer SD card, app acts as controller
}
```

#### 2. ShimmerLoggingConfig Data Class
```kotlin
data class ShimmerLoggingConfig(
    val samplingRate: Double = 128.0,           // Hz
    val gsrRange: Int = 0,                      // GSR range setting
    val enablePPG: Boolean = true,              // Enable PPG sensors
    val enableAccel: Boolean = false,           // Enable accelerometer
    val sessionDurationMinutes: Int = 60        // Maximum session duration
)
```

#### 3. LoggingOnlyShimmerManager Class
Handles Shimmer devices in logging-only mode with the following responsibilities:
- BLE connection management for remote control
- Sensor configuration for logging mode
- Start/stop logging commands (0x07/0x20)
- Session metadata tracking
- Status monitoring

## Implementation Details

### Creating ShimmerRecorder Instances

#### Real-Time Streaming Mode (Default)
```kotlin
val shimmerRecorder = ShimmerRecorder.forRealTimeStreaming(context)
```

#### Logging-Only Mode
```kotlin
val config = ShimmerLoggingConfig(
    samplingRate = 128.0,
    gsrRange = 0,
    enablePPG = true,
    enableAccel = false,
    sessionDurationMinutes = 60
)

val shimmerRecorder = ShimmerRecorder.forLoggingOnly(context, config)
```

### Session Flow for Logging-Only Mode

#### 1. Connection Phase
```kotlin
// Start the recorder (initializes logging manager)
shimmerRecorder.start(sessionDir)

// Connect to specific Shimmer device
val success = shimmerRecorder.connectToDevice(deviceAddress, deviceName)
```

#### 2. Logging Phase
```kotlin
// Logging automatically starts upon successful connection
// Session metadata is created with timing information
// App monitors connection status and logging state
```

#### 3. Stop Phase
```kotlin
// Stop logging and disconnect
shimmerRecorder.stop()

// Session metadata is finalized with completion information
```

### Session Data Structure (Logging Mode)

```
/sessions/{session_id}/
├── shimmer_logging_metadata.json     # Logging session metadata
└── (No local CSV files - data on Shimmer SD card)
```

### Session Metadata Example (Logging Mode)
```json
{
    "session_id": "20241213_143022_123_Galaxy_S22_ab12cd34",
    "recording_mode": "SHIMMER_LOGGING_ONLY",
    "start_timestamp_ms": 1702474222123,
    "start_timestamp_ns": 1234567890123456789,
    "device_info": {
        "device_name": "Shimmer3-A1B2",
        "device_address": "00:06:66:12:34:56",
        "sampling_rate_hz": 128.0,
        "gsr_range": 0
    },
    "data_location": "shimmer_sd_card",
    "timestamp_source": "shimmer_internal",
    "end_timestamp_ms": 1702474282456,
    "duration_ms": 60333,
    "status": "completed",
    "notes": "Data logged to Shimmer SD card with internal timestamps. Use Consensys software to download data from device."
}
```

## Callback Interfaces

### ShimmerLoggingCallback
```kotlin
interface ShimmerLoggingCallback {
    fun onLoggingStarted(sessionId: String, startTime: Long)
    fun onLoggingStopped(durationMs: Long)
    fun onConnectionStateChanged(connected: Boolean, message: String)
    fun onLoggingStateChanged(logging: Boolean, message: String)
    fun onDeviceInitialized(message: String)
    fun onError(error: String)
}
```

## Status Monitoring

### Recording Statistics (Mode-Aware)
```kotlin
val stats = shimmerRecorder.getRecordingStats()

when (stats.recordingMode) {
    ShimmerRecordingMode.REAL_TIME_STREAMING -> {
        println("Streaming mode: ${stats.totalSamples} samples recorded")
        println("Output file: ${stats.outputFile}")
    }
    ShimmerRecordingMode.LOGGING_ONLY -> {
        println("Logging mode: ${stats.loggingStatus?.loggingDuration}ms duration")
        println("Metadata file: ${stats.outputFile}")
    }
}
```

## Benefits of Logging-Only Mode

### 1. Data Integrity
- **No packet loss**: Data written directly to Shimmer SD card
- **Internal timestamps**: Shimmer device handles timing internally
- **Continuous logging**: No BLE interruptions affect data collection

### 2. Battery Efficiency
- **Reduced radio usage**: Minimal BLE communication after start
- **Lower power consumption**: App doesn't process continuous data stream
- **Extended recording**: Suitable for longer sessions

### 3. Simplified Synchronization
- **Device timestamps**: Shimmer handles internal timing
- **Post-processing**: Data alignment done offline
- **Reliable timing**: No network-induced timing variations

## Data Retrieval

After a logging session, data must be retrieved from the Shimmer device:

1. **Connect Shimmer to PC**: Use USB dock or Bluetooth
2. **Use Consensys Software**: Official Shimmer software for data download
3. **Extract CSV files**: Retrieve timestamped sensor data
4. **Post-processing**: Align with other sensor modalities using session metadata

## Permissions and Requirements

### Required Permissions (Same as Streaming Mode)
- `BLUETOOTH_CONNECT` (API 31+)
- `BLUETOOTH_SCAN` (API 31+)
- `ACCESS_FINE_LOCATION`
- `BLUETOOTH` and `BLUETOOTH_ADMIN` (API < 31)

### Hardware Requirements
- Shimmer3 GSR+ device with SD card
- BLE-capable Android device
- Sufficient SD card space on Shimmer device

## Integration with Existing System

The logging-only mode integrates seamlessly with the existing sensor ecosystem:

- **Same SensorRecorder interface**: No changes to RecordingController
- **Consistent session management**: Uses same session directory structure
- **Permission system**: Leverages existing PermissionManager
- **Error handling**: Graceful fallbacks and comprehensive logging

## Testing and Validation

### Mode Selection Testing
```kotlin
@Test
fun testModeSelection() {
    val streamingRecorder = ShimmerRecorder.forRealTimeStreaming(context)
    assertEquals(ShimmerRecordingMode.REAL_TIME_STREAMING, streamingRecorder.getRecordingMode())
    
    val loggingRecorder = ShimmerRecorder.forLoggingOnly(context)
    assertEquals(ShimmerRecordingMode.LOGGING_ONLY, loggingRecorder.getRecordingMode())
}
```

### Session Metadata Validation
```kotlin
@Test
fun testLoggingSessionMetadata() {
    val sessionDir = File(tempDir, "test_session")
    val recorder = ShimmerRecorder.forLoggingOnly(context)
    
    recorder.start(sessionDir)
    // Verify metadata file created with correct structure
    
    val metadataFile = File(sessionDir, "shimmer_logging_metadata.json")
    assertTrue(metadataFile.exists())
    
    val metadata = JSONObject(metadataFile.readText())
    assertEquals("SHIMMER_LOGGING_ONLY", metadata.getString("recording_mode"))
}
```

This implementation provides a robust, production-ready logging-only mode that complements the existing real-time streaming functionality while maintaining consistency with the overall system architecture.