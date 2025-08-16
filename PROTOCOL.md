# Protocol Specification (Phase 1–4)

This document defines the JSON, line-delimited messages and mDNS service required to establish connectivity and control
between the PC Hub (PC Controller) and Android Spoke (Sensor Node).

## Zeroconf (mDNS) Service

- Service Type: `_gsr-controller._tcp.local.`
- Service Name (example): `SensorSpoke - Pixel_7`
- Port: TCP port the Android Spoke listens on for incoming PC connections.

## Messages

All control messages are line-delimited JSON (each message ends with a single `\n`). Unknown fields must be ignored by
receivers (forward/backward compatibility).

### PC → Android: Query Capabilities

```json
{"id": 1, "command": "query_capabilities"}
```

### Android → PC: Capabilities Response

```json
{
  "ack_id": 1,
  "status": "ok",
  "capabilities": {
    "device_model": "<model>",
    "has_thermal": true,
    "cameras": [
      {"id": "0", "facing": "back"},
      {"id": "1", "facing": "front"}
    ]
  }
}
```

### Time Sync Handshake (NTP-like)

- PC → Android (request):

```json
{"id": 100, "command": "time_sync", "t0": 1712345678901234567}
```

- Android → PC (response):

```json
{"ack_id": 100, "status": "ok", "t1": 1712345678902234567, "t2": 1712345678902235567}
```

PC records `t3` on receipt; offset and delay are computed as defined in docs/4_4_phase.md.

### Start Recording

- PC → Android:

```json
{"id": 200, "command": "start_recording", "session_id": "20250816_024400_<uuid>"}
```

- Android → PC (ack):

```json
{"ack_id": 200, "status": "ok"}
```

### Stop Recording

- PC → Android:

```json
{"id": 201, "command": "stop_recording"}
```

- Android → PC (ack):

```json
{"ack_id": 201, "status": "ok"}
```

### Flash Sync Trigger

- PC → Android:

```json
{"id": 300, "command": "flash_sync"}
```

- Android behavior: briefly turn the screen white and log a high-precision timestamp.
- Android → PC (ack):

```json
{"ack_id": 300, "status": "ok", "ts": 1712345678903333000}
```

### Preview Frames (Phase 4 streaming)

- Android → PC (asynchronous):

```json
{"type": "preview_frame", "device_id": "<id>", "jpeg_base64": "...", "ts": 1712345678904444000}
```

PC decodes and displays in the corresponding DeviceWidget.
