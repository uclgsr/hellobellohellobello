#pragma once

// Shimmer C-API Compatibility Layer
// This header provides the expected interface for Shimmer C-API integration
// When the actual Shimmer C-API is integrated, this file should be replaced
// with the official headers from https://github.com/ShimmerEngineering/Shimmer-C-API

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Return codes
#define SHIMMER_OK 0
#define SHIMMER_ERROR -1
#define SHIMMER_TIMEOUT 1

// Sensor types
#define SHIMMER_SENSOR_GSR 0x01
#define SHIMMER_SENSOR_PPG 0x02

// GSR range settings
#define SHIMMER_GSR_RANGE_AUTO 0

// Data packet structure
typedef struct {
    uint64_t timestamp_ms;
    bool has_gsr;
    bool has_ppg;
    uint16_t gsr_raw;      // 12-bit ADC value (0-4095)
    uint16_t ppg_raw;      // PPG raw value
} ShimmerDataPacket;

// Function prototypes (these are placeholders for the actual C-API)
// The actual function signatures may differ in the real Shimmer C-API

// Connection functions
void* ShimmerSerial_connect(const char* port);
void* ShimmerBluetooth_connect(const char* mac_address);
int Shimmer_disconnect(void* handle);

// Configuration functions
int Shimmer_enableSensor(void* handle, int sensor_type);
int Shimmer_setSamplingRate(void* handle, double rate_hz);
int Shimmer_setGSRRange(void* handle, int range);

// Streaming functions
int Shimmer_startStreaming(void* handle);
int Shimmer_stopStreaming(void* handle);
int Shimmer_getNextDataPacket(void* handle, ShimmerDataPacket* packet, int timeout_ms);

// Device information functions
int Shimmer_getDeviceName(void* handle, char* name_buffer, int buffer_size);
int Shimmer_getFirmwareVersion(void* handle, char* version_buffer, int buffer_size);

#ifdef __cplusplus
}
#endif