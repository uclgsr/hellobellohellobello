package com.yourcompany.sensorspoke.sensors.gsr

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ShimmerRecorderTest {

    @Test
    fun start_createsCsvHeader_and_stop_closes() = runBlocking {
        val tmp = createTempDirectory("shimmer_test_").toFile()
        try {
            val recorder = ShimmerRecorder()
            recorder.start(tmp)
            val csv = File(tmp, "gsr.csv")
            assertThat(csv.exists()).isTrue()
            val first = csv.bufferedReader().use { it.readLine() }
            assertThat(first).isEqualTo("timestamp_nanos,gsr_uS,ppg_raw")
            recorder.stop()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun convertGsrToMicroSiemens_clamps12bit_and_scales() {
        val r = ShimmerRecorder()
        // Below 0 clamps to 0
        assertThat(r.convertGsrToMicroSiemens(-10, vRef = 3.0, rangeScale = 2.0)).isEqualTo(0.0)
        // Above 4095 clamps to 4095 -> voltage ~ vRef -> microSiemens ~ vRef*scale
        val top = r.convertGsrToMicroSiemens(50000, vRef = 3.0, rangeScale = 2.0)
        assertThat(top).isWithin(1e-9).of(6.0)
        // Mid-scale 2048 -> ~ 2048/4095 * vRef * scale
        val mid = r.convertGsrToMicroSiemens(2048, vRef = 3.3, rangeScale = 1.5)
        val expected = (2048.0 / 4095.0) * 3.3 * 1.5
        assertThat(mid).isWithin(1e-9).of(expected)
    }
}
