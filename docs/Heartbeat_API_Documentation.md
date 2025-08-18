# Heartbeat API Documentation

## Overview

The Multi-Modal Sensor Platform implements a comprehensive fault tolerance and device health monitoring system through the Heartbeat API. This system provides real-time connection status monitoring, automatic reconnection capabilities, and device health assessment for distributed sensor networks.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Core Components](#core-components)
3. [Protocol Specification](#protocol-specification)
4. [Python Hub API Reference](#python-hub-api-reference)
5. [Android Client API Reference](#android-client-api-reference)
6. [Usage Examples](#usage-examples)
7. [Integration Patterns](#integration-patterns)
8. [Configuration Management](#configuration-management)
9. [Performance Considerations](#performance-considerations)
10. [Troubleshooting](#troubleshooting)

## Architecture Overview

The Heartbeat system implements a hub-and-spoke monitoring architecture with automatic fault detection and recovery:

```
┌─────────────────┐         ┌─────────────────┐
│   PC Hub        │         │  Android Spoke  │
│                 │         │                 │
│ HeartbeatManager│◄────────┤ HeartbeatManager│
│  - Monitor      │  JSON   │  - Send         │
│  - Track Status │  over   │  - Device Info  │
│  - Callbacks    │  TCP    │  - Auto Retry   │
│  - Auto Reconnect│        │  - State Persist│
└─────────────────┘         └─────────────────┘
```

### Key Design Principles

- **Proactive Monitoring**: Continuous device health assessment
- **Automatic Recovery**: Transparent reconnection with exponential backoff
- **Real-time Status**: Immediate notification of connection state changes
- **Cross-platform**: Unified implementation across Python and Kotlin/Android
- **Configurable**: Flexible timing and threshold configuration
- **Production Ready**: Comprehensive error handling and logging

## Core Components

### HeartbeatManager (Python Hub)

**Location**: `pc_controller/src/network/heartbeat_manager.py`

**Purpose**: 
- Monitor connection health of registered Android devices
- Detect connection losses and classify device status
- Trigger automatic reconnection attempts
- Provide status callbacks for UI updates

**Key Features**:
- Async monitoring loop with configurable intervals
- Device registration and lifecycle management
- Exponential backoff reconnection strategy
- Comprehensive status tracking and reporting
- Thread-safe operation with proper cleanup

### HeartbeatManager (Android Client)

**Location**: `android_sensor_node/app/src/main/java/com/example/sensornode/network/HeartbeatManager.kt`

**Purpose**:
- Send periodic heartbeat messages to PC Hub
- Provide real device status information
- Handle connection loss gracefully
- Attempt automatic reconnection

**Key Features**:
- Periodic heartbeat transmission
- Real device metadata collection
- Connection state persistence
- Background operation support
- Integration with Android lifecycle

### HeartbeatStatus (Data Model)

**Location**: `pc_controller/src/network/heartbeat_manager.py`

**Purpose**: 
- Encapsulate device connection status information
- Provide standardized status classification
- Track timing and connectivity metrics

**Status Classifications**:
- `ONLINE`: Device actively sending heartbeats
- `OFFLINE`: No heartbeats received within timeout
- `UNKNOWN`: Device registered but no heartbeats yet
- `RECONNECTING`: Attempting to reestablish connection

## Protocol Specification

### Heartbeat Message Format

The heartbeat protocol uses JSON messages sent over TCP connections with version 1 framing:

```json
{
  "v": 1,
  "type": "heartbeat",
  "timestamp_ns": 1692350400000000000,
  "device_id": "android_device_1",
  "device_info": {
    "battery_level": 85,
    "available_storage_mb": 2048,
    "is_recording": true,
    "last_recording_session": "session_2023_08_18_14_30",
    "uptime_ms": 3600000,
    "network_type": "wifi",
    "signal_strength": -45
  }
}
```

### Message Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `v` | integer | Yes | Protocol version (always 1) |
| `type` | string | Yes | Message type (always "heartbeat") |
| `timestamp_ns` | integer | Yes | Timestamp in nanoseconds since epoch |
| `device_id` | string | Yes | Unique device identifier |
| `device_info` | object | Yes | Device status information |

### Device Info Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `battery_level` | integer | Yes | Battery percentage (0-100) |
| `available_storage_mb` | integer | Yes | Available storage in MB |
| `is_recording` | boolean | Yes | Whether device is currently recording |
| `last_recording_session` | string | No | ID of most recent session |
| `uptime_ms` | integer | Yes | Device uptime in milliseconds |
| `network_type` | string | Yes | Network connection type |
| `signal_strength` | integer | No | Signal strength in dBm |

### Response Format

The hub responds with acknowledgment messages:

```json
{
  "v": 1,
  "type": "heartbeat_ack",
  "timestamp_ns": 1692350400500000000,
  "device_id": "android_device_1",
  "status": "ok"
}
```

This comprehensive Heartbeat API documentation provides complete coverage of the fault tolerance system, from basic usage patterns to advanced integration scenarios and troubleshooting procedures.
