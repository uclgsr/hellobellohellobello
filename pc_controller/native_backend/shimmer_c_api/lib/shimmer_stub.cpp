// Shimmer C-API Stub Implementation
// This provides a stub implementation for testing and development
// When the actual Shimmer C-API is available, replace this with the real library

#include "Shimmer.h"
#include "ShimmerBluetooth.h"
#include "ShimmerSerial.h"

#include <cstring>
#include <cstdlib>
#include <chrono>
#include <thread>

// Simple stub implementation for development/testing
// This will be replaced by the actual Shimmer C-API library

extern "C" {

// Connection functions
void* ShimmerSerial_connect(const char* port) {
    // Return a dummy handle for stub implementation
    return reinterpret_cast<void*>(0x12345678);
}

void* ShimmerBluetooth_connect(const char* mac_address) {
    // Return a dummy handle for stub implementation  
    return reinterpret_cast<void*>(0x87654321);
}

int Shimmer_disconnect(void* handle) {
    return SHIMMER_OK;
}

// Configuration functions
int Shimmer_enableSensor(void* handle, int sensor_type) {
    return SHIMMER_OK;
}

int Shimmer_setSamplingRate(void* handle, double rate_hz) {
    return SHIMMER_OK;
}

int Shimmer_setGSRRange(void* handle, int range) {
    return SHIMMER_OK;
}

// Streaming functions
int Shimmer_startStreaming(void* handle) {
    return SHIMMER_OK;
}

int Shimmer_stopStreaming(void* handle) {
    return SHIMMER_OK;
}

int Shimmer_getNextDataPacket(void* handle, ShimmerDataPacket* packet, int timeout_ms) {
    // Simulate timeout occasionally
    static int call_count = 0;
    call_count++;
    
    if (call_count % 10 == 0) {
        return SHIMMER_TIMEOUT;
    }
    
    // Simulate realistic packet data
    auto now = std::chrono::steady_clock::now();
    auto timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count();
    
    packet->timestamp_ms = static_cast<uint64_t>(timestamp_ms);
    packet->has_gsr = true;
    packet->has_ppg = true;
    
    // Simulate 12-bit ADC GSR value (0-4095)
    // Simulate a GSR value corresponding to ~10 µS
    packet->gsr_raw = 2000 + (rand() % 500); // Some variation around midpoint
    
    packet->ppg_raw = 1500 + (rand() % 1000);
    
    // Simulate realistic sampling timing
    std::this_thread::sleep_for(std::chrono::milliseconds(8)); // ~128Hz
    
    return SHIMMER_OK;
}

// Device information functions
int Shimmer_getDeviceName(void* handle, char* name_buffer, int buffer_size) {
    const char* name = "Shimmer3 GSR+ Stub";
    strncpy(name_buffer, name, buffer_size - 1);
    name_buffer[buffer_size - 1] = '\0';
    return SHIMMER_OK;
}

int Shimmer_getFirmwareVersion(void* handle, char* version_buffer, int buffer_size) {
    const char* version = "0.1.0-stub";
    strncpy(version_buffer, version, buffer_size - 1);
    version_buffer[buffer_size - 1] = '\0';
    return SHIMMER_OK;
}

// Bluetooth-specific functions
int ShimmerBluetooth_scan(char** device_list, int max_devices) {
    return 0; // No devices found in stub mode
}

int ShimmerBluetooth_disconnect(void* handle) {
    return SHIMMER_OK;
}

// Serial-specific functions  
int ShimmerSerial_scan(char** port_list, int max_ports) {
    return 0; // No ports found in stub mode
}

int ShimmerSerial_disconnect(void* handle) {
    return SHIMMER_OK;
}

} // extern "C"