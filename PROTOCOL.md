# Protocol Specification (Phase 1)

This document defines the initial JSON messages and mDNS service required to establish connectivity between the PC Hub (PC Controller) and Android Spoke (Sensor Node).

## Zeroconf (mDNS) Service
- Service Type: `_gsr-controller._tcp.local.`
- Service Name (example): `SensorSpoke - Pixel_7`
- Port: TCP port the Android Spoke listens on for incoming PC connections.

## Messages

All control messages are line-delimited JSON (each message ends with a single `\n`).

### PC → Android: Query Capabilities
```json
{"id": 1, "command": "query_capabilities"}
```

### Android → PC: Acknowledge Connection
```json
{"ack_id": 0, "status": "connected", "device_id": "<device_model>"}
```

### Android → PC: Capabilities Response
```json
{
  "ack_id": 1,
  "status": "ok",
  "capabilities": {
    "has_thermal": true,
    "cameras": [
      {"id": "0", "facing": "back"},
      {"id": "1", "facing": "front"}
    ]
  }
}
```

Notes:
- `ack_id` mirrors the `id` of the PC command being acknowledged.
- Additional fields may be added in later protocol versions; parsers should ignore unknown fields.
