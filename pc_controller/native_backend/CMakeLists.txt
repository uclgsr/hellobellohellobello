cmake_minimum_required(VERSION 3.15)
project(native_backend LANGUAGES CXX)

# Options
option(USE_OPENCV "Build with OpenCV for NativeWebcam" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find pybind11
find_package(pybind11 CONFIG QUIET)
if (NOT pybind11_FOUND)
    message(STATUS "pybind11 Config not found, trying module mode")
    find_package(pybind11 REQUIRED)
endif()

if (USE_OPENCV)
    find_package(OpenCV REQUIRED)
endif()

pybind11_add_module(native_backend native_backend.cpp)

if (USE_OPENCV)
    target_compile_definitions(native_backend PRIVATE USE_OPENCV)
    target_link_libraries(native_backend PRIVATE ${OpenCV_LIBS})
endif()

# On Windows, produce .pyd in this directory
set_target_properties(native_backend PROPERTIES
    OUTPUT_NAME native_backend
)
