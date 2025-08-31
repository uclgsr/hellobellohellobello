package com.yourcompany.sensorspoke.ui.fragments

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Test for the RGB preview fragment
 */
class RgbPreviewFragmentTest {
    @Test
    fun testFragmentCreation() {
        val fragment = RgbPreviewFragment.newInstance()
        assertNotNull(fragment)
    }
}
