#pragma once

// Shimmer Bluetooth API
// This header provides Bluetooth-specific functions for Shimmer C-API integration

#include "Shimmer.h"

#ifdef __cplusplus
extern "C" {
#endif

// Bluetooth-specific connection functions
void* ShimmerBluetooth_connect(const char* mac_address);
int ShimmerBluetooth_scan(char** device_list, int max_devices);
int ShimmerBluetooth_disconnect(void* handle);

#ifdef __cplusplus
}
#endif