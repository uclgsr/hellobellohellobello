# Protocol Specification (Phase 1–5)

This document defines the JSON, line-delimited messages and mDNS service required to establish connectivity and control
between the PC Hub (PC Controller) and Android Spoke (Sensor Node).

## Zeroconf (mDNS) Service

- Service Type: `_gsr-controller._tcp.local.`
- Service Name (example): `SensorSpoke - Pixel_7`
- Port: TCP port the Android Spoke listens on for incoming PC connections.

## Messages

All control messages use robust length-prefix framing by default and support legacy newline-delimited JSON for backward compatibility.

Framing (preferred): "${length}\n{json}"
- The first line is the ASCII decimal length of the JSON payload in bytes, followed by a single newline.
- The next {length} bytes are the UTF-8 JSON document.

Framing (legacy fallback): line-delimited JSON (each message ends with a single "\n"). Receivers should accept either framing;
new senders should use length-prefix.

Versioned schema (v=1): All control/event messages should include a top-level field "v":1 and a "type" discriminator
(e.g., "cmd", "ack", "event"). Unknown fields must be ignored by receivers (forward/backward compatibility).

### PC → Android: Query Capabilities

v=1 (preferred)

```json
{"v":1, "id": 1, "type":"cmd", "command": "query_capabilities"}
```

Legacy (accepted)

```json
{"id": 1, "command": "query_capabilities"}
```

### Android → PC: Capabilities Response

v=1 ack

```json
{"v":1, "ack_id": 1, "type":"ack", "status": "ok", "capabilities": {"device_model": "<model>", "has_thermal": true}}
```

Legacy (accepted)

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

- PC → Android (request), v=1 (preferred):

```json
{"v":1, "id": 100, "type":"cmd", "command": "time_sync", "seq": 1, "t0": 1712345678901234567}
```

- Android → PC (response), v=1 ack:

```json
{"v":1, "ack_id": 100, "type":"ack", "status": "ok", "t1": 1712345678902234567, "t2": 1712345678902235567}
```

Legacy (accepted) retains the Phase 1–4 shapes.

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
