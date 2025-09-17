package com.yourcompany.sensorspoke.sensors.gsr

import android.util.Log
import com.shimmerresearch.driver.ObjectCluster

/**
 * ShimmerDataProcessor handles conversion of ObjectCluster data to usable sensor samples.
 * Extracted from ShimmerRecorder to improve modularity and testability.
 * 
 * This utility class is responsible for:
 * - Converting ObjectCluster data to standardized sensor samples
 * - Handling GSR calibration and unit conversion
 * - Processing PPG data from Shimmer sensors
 * - Data validation and integrity checking
 */
class ShimmerDataProcessor {
    companion object {
        private const val TAG = "ShimmerDataProcessor"
        
        // Shimmer sensor channel names
        private const val GSR_CHANNEL = "GSR"
        private const val PPG_CHANNEL = "PPG_A13"
        private const val TIMESTAMP_CHANNEL = "Timestamp"
        
        // Data format types
        private const val RAW_FORMAT = "RAW"
        private const val CAL_FORMAT = "CAL"
        
        // GSR conversion constants (12-bit ADC as per requirements)
        private const val GSR_ADC_MAX = 4095.0 // 12-bit ADC maximum
        private const val GSR_UNCAL_TO_KOHMS_FACTOR = 1000.0 // Convert to kΩ
    }

    /**
     * Standardized sensor sample data class
     */
    data class SensorSample(
        val timestampNs: Long,
        val timestampMs: Long,
        val gsrKohms: Double,
        val gsrRaw12bit: Int,
        val ppgRaw: Int,
        val connectionStatus: String,
        val dataIntegrity: String
    )

    /**
     * Convert ObjectCluster to standardized SensorSample
     * This implements the core data processing logic previously in ShimmerRecorder
     */
    fun convertObjectClusterToSensorSample(objectCluster: ObjectCluster): SensorSample? {
        return try {
            // Extract timestamp
            val timestampNs = System.nanoTime()
            val timestampMs = System.currentTimeMillis()
            
            // Extract GSR data - prioritize calibrated data if available
            val gsrData = extractGsrData(objectCluster)
            val ppgData = extractPpgData(objectCluster)
            
            // Determine connection status based on ObjectCluster state
            val connectionStatus = when (objectCluster.mState) {
                com.shimmerresearch.bluetooth.ShimmerBluetooth.BtState.CONNECTED -> "CONNECTED"
                com.shimmerresearch.bluetooth.ShimmerBluetooth.BtState.CONNECTING -> "CONNECTING"
                com.shimmerresearch.bluetooth.ShimmerBluetooth.BtState.DISCONNECTED -> "DISCONNECTED"
                else -> "UNKNOWN"
            }
            
            // Validate data integrity
            val dataIntegrity = validateDataIntegrity(gsrData, ppgData)
            
            SensorSample(
                timestampNs = timestampNs,
                timestampMs = timestampMs,
                gsrKohms = gsrData.first,
                gsrRaw12bit = gsrData.second,
                ppgRaw = ppgData,
                connectionStatus = connectionStatus,
                dataIntegrity = dataIntegrity
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ObjectCluster to SensorSample: ${e.message}", e)
            null
        }
    }

    /**
     * Extract and calibrate GSR data from ObjectCluster
     * Returns Pair<calibratedValue, rawValue>
     */
    private fun extractGsrData(objectCluster: ObjectCluster): Pair<Double, Int> {
        return try {
            // Try to get calibrated GSR data first
            val calibratedGsr = objectCluster.getFormatCluster(GSR_CHANNEL, CAL_FORMAT)?.mData
            val rawGsr = objectCluster.getFormatCluster(GSR_CHANNEL, RAW_FORMAT)?.mData
            
            when {
                calibratedGsr != null && rawGsr != null -> {
                    // Use calibrated value and raw value
                    val rawInt = rawGsr.toInt().coerceIn(0, GSR_ADC_MAX.toInt())
                    Pair(calibratedGsr, rawInt)
                }
                rawGsr != null -> {
                    // Only raw data available - apply our own calibration
                    val rawInt = rawGsr.toInt().coerceIn(0, GSR_ADC_MAX.toInt())
                    val calibratedValue = convertRawGsrToKohms(rawInt)
                    Pair(calibratedValue, rawInt)
                }
                else -> {
                    // No GSR data available - return defaults
                    Log.w(TAG, "No GSR data found in ObjectCluster")
                    Pair(0.0, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting GSR data: ${e.message}", e)
            Pair(0.0, 0)
        }
    }

    /**
     * Extract PPG data from ObjectCluster
     */
    private fun extractPpgData(objectCluster: ObjectCluster): Int {
        return try {
            val ppgValue = objectCluster.getFormatCluster(PPG_CHANNEL, RAW_FORMAT)?.mData
                ?: objectCluster.getFormatCluster(PPG_CHANNEL, CAL_FORMAT)?.mData
            
            ppgValue?.toInt()?.coerceIn(0, 65535) ?: 0 // 16-bit range for PPG
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting PPG data: ${e.message}", e)
            0
        }
    }

    /**
     * Convert raw GSR value to kΩ using 12-bit ADC range
     * This implements the critical 12-bit conversion requirement from the problem statement
     */
    private fun convertRawGsrToKohms(rawValue: Int): Double {
        // Ensure we're working with 12-bit range (0-4095)
        val clampedRaw = rawValue.coerceIn(0, GSR_ADC_MAX.toInt())
        
        // Convert to resistance in kΩ
        // This is a simplified conversion - in practice, Shimmer provides calibration constants
        return (clampedRaw.toDouble() / GSR_ADC_MAX) * GSR_UNCAL_TO_KOHMS_FACTOR
    }

    /**
     * Validate data integrity
     */
    private fun validateDataIntegrity(gsrData: Pair<Double, Int>, ppgData: Int): String {
        return when {
            gsrData.first <= 0.0 && gsrData.second <= 0 -> "NO_GSR_DATA"
            gsrData.first > 10000.0 -> "GSR_OUT_OF_RANGE" // Very high resistance might indicate poor contact
            ppgData <= 0 -> "NO_PPG_DATA"
            else -> "OK"
        }
    }

    /**
     * Format sensor sample for CSV output
     */
    fun formatSampleForCsv(sample: SensorSample, sampleNumber: Int): String {
        return "${sample.timestampNs},${sample.timestampMs},$sampleNumber,${"%.3f".format(sample.gsrKohms)},${sample.gsrRaw12bit},${sample.ppgRaw},${sample.connectionStatus},${sample.dataIntegrity}"
    }

    /**
     * Get CSV header for sensor data
     */
    fun getCsvHeader(): String {
        return "timestamp_ns,timestamp_ms,sample_number,gsr_kohms,gsr_raw_12bit,ppg_raw,connection_status,data_integrity"
    }
}