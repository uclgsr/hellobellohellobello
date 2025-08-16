Native Backend (C++ via PyBind11) - Phase 3

This directory contains a PyBind11 native extension exposing two classes:
- NativeShimmer: High-integrity wired Shimmer3 GSR+ capture (simulated in this phase).
- NativeWebcam: Local webcam capture with a zero-copy BGR frame buffer (OpenCV optional).

In Phase 3 we provide a fully working, simulated implementation suitable for
local integration and GUI development. In later phases, NativeShimmer will be
swapped to use the official Shimmer C-API and serial I/O.

Build Instructions (Windows / PowerShell):
1. Ensure you have Python 3.11+ and a C++ toolchain (Visual Studio Build Tools).
2. Install dependencies into your venv:
   pip install pybind11
   # Optional (for real camera capture):
   # pip install opencv-python
3. Configure and build with CMake:
   cd pc_controller\native_backend
   cmake -S . -B build -DPYBIND11_FINDPYTHON=ON -DUSE_OPENCV=OFF
   cmake --build build --config Release
4. Copy the compiled artifact into this directory (if not already placed here by your generator):
   - build\Release\native_backend.pyd  -> pc_controller\native_backend\

Import from Python:
from pc_controller.native_backend import NativeShimmer, NativeWebcam

Troubleshooting:
- If import fails, ensure native_backend.pyd is located next to __init__.py.
- To enable real webcam via OpenCV, configure CMake with -DUSE_OPENCV=ON and ensure OpenCV is locatable by CMake.
- If you do not build the extension, the Python GUI will fall back to simulated devices.
