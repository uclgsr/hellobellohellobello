# Shimmer C-API Integration Directory

This directory is intended to contain the Shimmer C-API source code from:
https://github.com/ShimmerEngineering/Shimmer-C-API

## Integration Setup

To complete the Shimmer C-API integration:

1. Clone or extract the Shimmer C-API source into this directory
2. Build the Shimmer C-API libraries for your platform
3. Update the CMakeLists.txt USE_SHIMMER_CAPI option to ON
4. The native_backend will automatically use the real Shimmer hardware instead of simulation

## Expected Structure

```
shimmer_c_api/
├── include/
│   ├── Shimmer.h
│   ├── ShimmerBluetooth.h
│   ├── ShimmerSerial.h
│   └── ...
├── lib/
│   ├── libshimmer.a (or .lib on Windows)
│   └── ...
└── README.md (from Shimmer Engineering)
```

## Build Instructions

The integration supports both Windows and Linux builds. The CMake configuration will automatically detect and link the Shimmer C-API when USE_SHIMMER_CAPI=ON is set.

For detailed build instructions, see the parent README.md file.