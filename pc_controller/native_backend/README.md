Native Backend (C++ via PyBind11)

This directory contains a PyBind11 native extension exposing two classes:

- NativeShimmer: High-integrity wired Shimmer3 GSR+ capture (simulated in this phase).
- NativeWebcam: Local webcam capture with a zero-copy BGR frame buffer (OpenCV optional).

Phase status: A fully working, simulated implementation is provided for local
integration and GUI development. In production, NativeShimmer should be built
against the official Shimmer C-API for wired/docked capture.

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

2) Configure the build
   cd pc_controller\native_backend
   # Base (no OpenCV), relies on synthetic frames in NativeWebcam
   cmake -S . -B build -DPYBIND11_FINDPYTHON=ON -DUSE_OPENCV=OFF -DCMAKE_BUILD_TYPE=Release

   # With OpenCV via vcpkg (example)
   # Assuming vcpkg is installed at C:\tools\vcpkg and integrated with CMake
   #   .\vcpkg integrate install
   # Install OpenCV (x64-windows)
   #   .\vcpkg install opencv:x64-windows
   # Configure with toolchain
   # cmake -S . -B build -DPYBIND11_FINDPYTHON=ON -DUSE_OPENCV=ON ^
   #   -DCMAKE_TOOLCHAIN_FILE=C:\tools\vcpkg\scripts\buildsystems\vcpkg.cmake ^
   #   -DCMAKE_BUILD_TYPE=Release

3) Build
   cmake --build build --config Release

4) Place the artifact for import
   - build\Release\native_backend.pyd -> pc_controller\native_backend\
   If your generator already outputs the .pyd into this directory, this step can be skipped.

Linking the Shimmer C-API (for production)
- Repository: https://github.com/ShimmerEngineering/Shimmer-C-API
- Steps (recommended outline):
  1. Clone and build the Shimmer C-API for Windows x64 (Release).
  2. Add include directories and library paths to this project:
     - In CMakeLists.txt, use target_include_directories(native_backend PRIVATE <path-to-shimmer-include>)
       and target_link_libraries(native_backend PRIVATE <shimmer_libs>), guarded by an option like USE_SHIMMER_CAPI.
  3. Add a compile definition (e.g., USE_SHIMMER_CAPI) and replace the simulated read loop in NativeShimmer::run_loop()
     with actual serial/dock reading using the C-API, pushing (timestamp, gsr_uS) to the SPSC queue.
  4. Ensure timestamps come from a high-resolution monotonic clock and GSR is converted to microsiemens using the
     correct sensor conversion.

Troubleshooting
- If import fails, ensure native_backend.pyd is located next to __init__.py.
- To enable real webcam via OpenCV, configure with -DUSE_OPENCV=ON and ensure OpenCV is locatable by CMake
  (e.g., via vcpkg toolchain file).
- If you do not build the extension, the Python GUI falls back to simulated devices.

Python Import
from pc_controller.native_backend import NativeShimmer, NativeWebcam
