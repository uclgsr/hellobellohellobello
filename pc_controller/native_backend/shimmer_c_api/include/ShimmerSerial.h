#pragma once

// Shimmer Serial API  
// This header provides Serial-specific functions for Shimmer C-API integration

#include "Shimmer.h"

#ifdef __cplusplus
extern "C" {
#endif

// Serial-specific connection functions
void* ShimmerSerial_connect(const char* port);
int ShimmerSerial_scan(char** port_list, int max_ports);
int ShimmerSerial_disconnect(void* handle);

#ifdef __cplusplus
}
#endif