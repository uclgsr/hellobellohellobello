package com.yourcompany.sensorspoke.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeManagerTest {

    @Test
    fun nowNanos_isMonotonicAndPositive() {
        val t1 = TimeManager.nowNanos()
        val t2 = TimeManager.nowNanos()
        assertThat(t1).isGreaterThan(0L)
        assertThat(t2).isAtLeast(t1)
    }
}
