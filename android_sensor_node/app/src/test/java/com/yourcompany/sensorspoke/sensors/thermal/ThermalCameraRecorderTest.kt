package com.yourcompany.sensorspoke.sensors.thermal

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ThermalCameraRecorderTest {

    @Test
    fun start_createsCsvWithHeader_and_stop_closes() = runBlocking {
        val tmp = createTempDirectory("thermal_test_").toFile()
        try {
            val recorder = ThermalCameraRecorder()
            recorder.start(tmp)
            val csv = File(tmp, "thermal.csv")
            assertThat(csv.exists()).isTrue()
            val firstLine = csv.bufferedReader().use { it.readLine() }
            assertThat(firstLine).isNotNull()
            assertThat(firstLine!!.startsWith("timestamp_nanos")).isTrue()
            assertThat(firstLine.contains(",p0")).isTrue()
            assertThat(firstLine.contains(",p49151")).isTrue()
            recorder.stop()
        } finally {
            tmp.deleteRecursively()
        }
    }
}
