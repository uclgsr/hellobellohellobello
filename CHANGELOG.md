# Changelog
All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Conventional Commits.

## [Unreleased]
### Added
- Phase 1 scaffolding for Multi-Modal Physiological Sensing Platform.
  - PC Controller (Hub) Python project under `pc_controller/` with PyQt6 GUI scaffold and core NetworkController using Zeroconf discovery and TCP client worker.
  - Protocol specification `PROTOCOL.md` describing the initial `query_capabilities` handshake and mDNS service type.
  - Android Sensor Node scaffold (to be introduced under `android_sensor_node/`) including NSD (Zeroconf) advertisement plan and ForegroundService with ServerSocket.
  - Repository maintenance: `.gitignore` initial rules.

### Changed
- Repository structure prepared for a multi-module layout (PC Controller + Android Sensor Node).

### Security
- Planned use of TLS sockets in future phases (not enabled in Phase 1 prototype).
