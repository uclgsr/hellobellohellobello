cmake_minimum_required(VERSION 3.15)
project(native_backend LANGUAGES CXX)

# Options
option(USE_OPENCV "Build with OpenCV for NativeWebcam" OFF)
option(USE_SHIMMER_CAPI "Build with Shimmer C-API for real hardware support" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find pybind11
find_package(pybind11 QUIET)
if (NOT pybind11_FOUND)
    # Try to find pybind11 via Python
    execute_process(
        COMMAND python3 -c "import pybind11; print(pybind11.get_cmake_dir())"
        OUTPUT_VARIABLE PYBIND11_CMAKE_DIR
        OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE PYBIND11_PYTHON_RESULT
    )
    if (PYBIND11_PYTHON_RESULT EQUAL 0 AND EXISTS ${PYBIND11_CMAKE_DIR})
        list(APPEND CMAKE_PREFIX_PATH ${PYBIND11_CMAKE_DIR})
        find_package(pybind11 REQUIRED CONFIG)
        message(STATUS "Found pybind11 via Python at ${PYBIND11_CMAKE_DIR}")
    else()
        message(FATAL_ERROR "pybind11 not found. Install with: pip install pybind11")
    endif()
endif()

if (USE_OPENCV)
    find_package(OpenCV REQUIRED)
endif()

# Shimmer C-API integration
if (USE_SHIMMER_CAPI)
    # Look for Shimmer C-API in the shimmer_c_api subdirectory
    set(SHIMMER_CAPI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shimmer_c_api)
    
    # Check if Shimmer C-API is available
    if (EXISTS ${SHIMMER_CAPI_DIR}/include)
        message(STATUS "Found Shimmer C-API at ${SHIMMER_CAPI_DIR}")
        set(SHIMMER_CAPI_FOUND TRUE)
    else()
        message(WARNING "Shimmer C-API not found at ${SHIMMER_CAPI_DIR}/include")
        message(STATUS "Download from: https://github.com/ShimmerEngineering/Shimmer-C-API")
        set(SHIMMER_CAPI_FOUND FALSE)
    endif()
endif()

pybind11_add_module(native_backend native_backend.cpp)

if (USE_OPENCV)
    target_compile_definitions(native_backend PRIVATE USE_OPENCV)
    target_link_libraries(native_backend PRIVATE ${OpenCV_LIBS})
endif()

# Link Shimmer C-API if available
if (USE_SHIMMER_CAPI AND SHIMMER_CAPI_FOUND)
    target_compile_definitions(native_backend PRIVATE USE_SHIMMER_CAPI)
    target_include_directories(native_backend PRIVATE ${SHIMMER_CAPI_DIR}/include)
    
    # Look for library files
    if (WIN32)
        # Windows: Look for .lib files
        file(GLOB SHIMMER_LIBS ${SHIMMER_CAPI_DIR}/lib/*.lib)
    else()
        # Linux/Unix: Look for .a files
        file(GLOB SHIMMER_LIBS ${SHIMMER_CAPI_DIR}/lib/*.a)
    endif()
    
    if (SHIMMER_LIBS)
        target_link_libraries(native_backend PRIVATE ${SHIMMER_LIBS})
        message(STATUS "Linked Shimmer C-API libraries: ${SHIMMER_LIBS}")
    else()
        # Use stub implementation if real libraries not found
        message(STATUS "Using Shimmer C-API stub implementation for development")
        target_sources(native_backend PRIVATE ${SHIMMER_CAPI_DIR}/lib/shimmer_stub.cpp)
    endif()
elseif (USE_SHIMMER_CAPI)
    message(WARNING "USE_SHIMMER_CAPI enabled but Shimmer C-API not found")
    message(STATUS "Using stub implementation. Download real API from:")
    message(STATUS "https://github.com/ShimmerEngineering/Shimmer-C-API")
    
    # Use stub implementation
    target_compile_definitions(native_backend PRIVATE USE_SHIMMER_CAPI)
    target_include_directories(native_backend PRIVATE ${SHIMMER_CAPI_DIR}/include)
    target_sources(native_backend PRIVATE ${SHIMMER_CAPI_DIR}/lib/shimmer_stub.cpp)
endif()

# On Windows, produce .pyd in this directory
set_target_properties(native_backend PROPERTIES
    OUTPUT_NAME native_backend
)
