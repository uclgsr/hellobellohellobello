Native Backend (C++ via PyBind11)

This directory contains a PyBind11 native extension exposing two classes:

- **NativeShimmer**: High-integrity wired Shimmer3 GSR+ capture with official Shimmer C-API integration
- **NativeWebcam**: Local webcam capture with a zero-copy BGR frame buffer (OpenCV optional)

## Shimmer C-API Integration Status

✅ **PRODUCTION READY**: Full Shimmer C-API integration implemented  
✅ **Hardware Support**: Real Shimmer3 GSR+ devices via serial/Bluetooth  
✅ **12-bit ADC**: Correct GSR conversion (0-4095 range) as per specification  
✅ **Development Mode**: Stub implementation for testing without hardware  

The system automatically detects available hardware and falls back to simulation when needed.

Windows Build Instructions (PowerShell)

Prerequisites
- Python 3.11+ (64-bit) and a virtual environment activated
- Visual Studio Build Tools 2019/2022 with C++ workload
- CMake 3.20+
- PyBind11 (Python side)
- Optional: OpenCV (for real webcam capture), recommended via vcpkg
- Optional: Shimmer C-API (for wired Shimmer capture)

1) Install Python dependencies in your venv
   pip install --upgrade pip
   pip install pybind11
   # Optional (for testing OpenCV from Python):
   # pip install opencv-python

## Build Options

### Option 1: Production Mode with Shimmer C-API (Recommended)
```bash
cmake -S . -B build -DPYBIND11_FINDPYTHON=ON -DUSE_SHIMMER_CAPI=ON -DCMAKE_BUILD_TYPE=Release
```

### Option 2: Development Mode (Simulation Only)
```bash
cmake -S . -B build -DPYBIND11_FINDPYTHON=ON -DUSE_SHIMMER_CAPI=OFF -DCMAKE_BUILD_TYPE=Release
```

### Option 3: Full Featured (Shimmer C-API + OpenCV)
```bash
# With vcpkg OpenCV
cmake -S . -B build -DPYBIND11_FINDPYTHON=ON -DUSE_SHIMMER_CAPI=ON -DUSE_OPENCV=ON \
  -DCMAKE_TOOLCHAIN_FILE=C:\tools\vcpkg\scripts\buildsystems\vcpkg.cmake \
  -DCMAKE_BUILD_TYPE=Release
```

3) Build
   cmake --build build --config Release

4) Place the artifact for import
   - build\Release\native_backend.pyd -> pc_controller\native_backend\
   If your generator already outputs the .pyd into this directory, this step can be skipped.

## Shimmer C-API Integration Guide

### Automatic Setup (Recommended)
The system includes automatic Shimmer C-API integration:
- **Stub Mode**: Works without hardware for development and testing
- **Hardware Mode**: Automatically activates when real Shimmer C-API is available

### Manual Shimmer C-API Installation
For production deployment with real hardware:

1. **Download Shimmer C-API**:
   ```bash
   cd pc_controller/native_backend/shimmer_c_api
   git clone https://github.com/ShimmerEngineering/Shimmer-C-API .
   ```

2. **Build Shimmer C-API** (follow Shimmer's build instructions):
   - Windows: Build with Visual Studio to generate .lib files
   - Linux: Use make/cmake to generate .a files

3. **Place Libraries**:
   - Copy header files to `shimmer_c_api/include/`
   - Copy library files to `shimmer_c_api/lib/`

4. **Build with Hardware Support**:
   ```bash
   cmake -S . -B build -DUSE_SHIMMER_CAPI=ON
   cmake --build build --config Release
   ```

### Features Available

#### With Real Hardware (USE_SHIMMER_CAPI=ON + Real SDK)
- ✅ Actual Shimmer3 GSR+ device communication
- ✅ Serial and Bluetooth connection support  
- ✅ Real-time 128Hz GSR data streaming
- ✅ 12-bit ADC precision (0-4095 range)
- ✅ Device information and status reporting

#### With Stub Implementation (Development Mode)
- ✅ Complete API compatibility for testing
- ✅ Realistic GSR simulation with noise and patterns
- ✅ Same interface as real hardware
- ✅ No external dependencies

#### Fallback Simulation (USE_SHIMMER_CAPI=OFF)
- ✅ Pure simulation mode
- ✅ No external hardware requirements
- ✅ Ideal for GUI development and testing

Troubleshooting
- If import fails, ensure native_backend.pyd is located next to __init__.py.
- To enable real webcam via OpenCV, configure with -DUSE_OPENCV=ON and ensure OpenCV is locatable by CMake
  (e.g., via vcpkg toolchain file).
- If you do not build the extension, the Python GUI falls back to simulated devices.

Python Import
from pc_controller.native_backend import NativeShimmer, NativeWebcam
