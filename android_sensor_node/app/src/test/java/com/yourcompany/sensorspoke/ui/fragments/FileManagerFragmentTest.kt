package com.yourcompany.sensorspoke.ui.fragments

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Test for the file manager fragment
 */
class FileManagerFragmentTest {
    @Test
    fun testFragmentCreation() {
        val fragment = FileManagerFragment.newInstance()
        assertNotNull(fragment)
    }
}
