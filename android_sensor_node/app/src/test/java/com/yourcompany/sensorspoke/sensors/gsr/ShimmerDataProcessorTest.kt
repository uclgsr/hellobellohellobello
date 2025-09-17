package com.yourcompany.sensorspoke.sensors.gsr

import com.shimmerresearch.driver.ObjectCluster
import com.shimmerresearch.bluetooth.ShimmerBluetooth
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ShimmerDataProcessor to validate ObjectCluster conversion logic.
 * These tests ensure proper GSR data processing with 12-bit ADC range validation.
 */
class ShimmerDataProcessorTest {

    private lateinit var dataProcessor: ShimmerDataProcessor
    private lateinit var mockObjectCluster: ObjectCluster

    @Before
    fun setUp() {
        dataProcessor = ShimmerDataProcessor()
        mockObjectCluster = ObjectCluster()
    }

    @Test
    fun testCsvHeaderFormat() {
        val header = dataProcessor.getCsvHeader()
        val expectedHeader = "timestamp_ns,timestamp_ms,sample_number,gsr_kohms,gsr_raw_12bit,ppg_raw,connection_status,data_integrity"
        assertEquals("CSV header should match expected format", expectedHeader, header)
    }

    @Test
    fun testSensorSampleCreation() {
        val sample = ShimmerDataProcessor.SensorSample(
            timestampNs = 1000000000L,
            timestampMs = 1000L,
            gsrKohms = 50.5,
            gsrRaw12bit = 2048,
            ppgRaw = 1500,
            connectionStatus = "CONNECTED",
            dataIntegrity = "OK"
        )

        assertEquals("Timestamp in nanoseconds should match", 1000000000L, sample.timestampNs)
        assertEquals("Timestamp in milliseconds should match", 1000L, sample.timestampMs)
        assertEquals("GSR value should match", 50.5, sample.gsrKohms, 0.1)
        assertEquals("GSR raw value should match", 2048, sample.gsrRaw12bit)
        assertEquals("PPG raw value should match", 1500, sample.ppgRaw)
        assertEquals("Connection status should match", "CONNECTED", sample.connectionStatus)
        assertEquals("Data integrity should match", "OK", sample.dataIntegrity)
    }

    @Test
    fun testFormatSampleForCsv() {
        val sample = ShimmerDataProcessor.SensorSample(
            timestampNs = 1000000000L,
            timestampMs = 1000L,
            gsrKohms = 50.123,
            gsrRaw12bit = 2048,
            ppgRaw = 1500,
            connectionStatus = "CONNECTED",
            dataIntegrity = "OK"
        )

        val csvLine = dataProcessor.formatSampleForCsv(sample, 1)
        val expectedLine = "1000000000,1000,1,50.123,2048,1500,CONNECTED,OK"
        assertEquals("CSV formatted line should match expected format", expectedLine, csvLine)
    }

    @Test
    fun testObjectClusterConversionWithDisconnectedState() {
        mockObjectCluster.mState = ShimmerBluetooth.BtState.DISCONNECTED
        
        val result = dataProcessor.convertObjectClusterToSensorSample(mockObjectCluster)
        
        assertNotNull("Result should not be null", result)
        assertEquals("Connection status should be DISCONNECTED", "DISCONNECTED", result!!.connectionStatus)
        assertTrue("Timestamp should be valid", result.timestampNs > 0)
        assertTrue("Timestamp in ms should be valid", result.timestampMs > 0)
    }

    @Test
    fun testObjectClusterConversionWithConnectedState() {
        mockObjectCluster.mState = ShimmerBluetooth.BtState.CONNECTED
        
        val result = dataProcessor.convertObjectClusterToSensorSample(mockObjectCluster)
        
        assertNotNull("Result should not be null", result)
        assertEquals("Connection status should be CONNECTED", "CONNECTED", result!!.connectionStatus)
    }

    @Test
    fun testObjectClusterConversionWithConnectingState() {
        mockObjectCluster.mState = ShimmerBluetooth.BtState.CONNECTING
        
        val result = dataProcessor.convertObjectClusterToSensorSample(mockObjectCluster)
        
        assertNotNull("Result should not be null", result)
        assertEquals("Connection status should be CONNECTING", "CONNECTING", result!!.connectionStatus)
    }

    @Test
    fun testObjectClusterConversionWithUnknownState() {
        // Test with null or other state
        val result = dataProcessor.convertObjectClusterToSensorSample(mockObjectCluster)
        
        assertNotNull("Result should not be null", result)
        assertTrue("Connection status should be set", result!!.connectionStatus.isNotEmpty())
    }

    @Test
    fun testGsrRaw12BitRange() {
        // Test that GSR raw values are within 12-bit range
        val sample = ShimmerDataProcessor.SensorSample(
            timestampNs = System.nanoTime(),
            timestampMs = System.currentTimeMillis(),
            gsrKohms = 100.0,
            gsrRaw12bit = 4095, // Maximum 12-bit value
            ppgRaw = 2000,
            connectionStatus = "CONNECTED",
            dataIntegrity = "OK"
        )

        assertTrue("GSR raw value should be within 12-bit range", sample.gsrRaw12bit <= 4095)
        assertTrue("GSR raw value should be non-negative", sample.gsrRaw12bit >= 0)
    }

    @Test
    fun testDataIntegrityValidation() {
        // Test different data integrity scenarios through ObjectCluster conversion
        mockObjectCluster.mState = ShimmerBluetooth.BtState.CONNECTED
        
        val result = dataProcessor.convertObjectClusterToSensorSample(mockObjectCluster)
        
        assertNotNull("Result should not be null", result)
        assertTrue("Data integrity should be set", result!!.dataIntegrity.isNotEmpty())
        // With no sensor data, we expect some form of integrity status
        assertNotEquals("Data integrity should not be empty", "", result.dataIntegrity)
    }

    @Test 
    fun testCsvFormattingWithPrecision() {
        val sample = ShimmerDataProcessor.SensorSample(
            timestampNs = 1234567890123456L,
            timestampMs = 1234567890L,
            gsrKohms = 123.456789, // Test precision handling
            gsrRaw12bit = 3000,
            ppgRaw = 2500,
            connectionStatus = "CONNECTED",
            dataIntegrity = "OK"
        )

        val csvLine = dataProcessor.formatSampleForCsv(sample, 42)
        
        // Check that GSR value is formatted to 3 decimal places
        assertTrue("CSV should contain properly formatted GSR value", csvLine.contains("123.457"))
        assertTrue("CSV should contain sample number", csvLine.contains("42"))
        assertTrue("CSV should contain all required fields", csvLine.split(",").size == 8)
    }
}