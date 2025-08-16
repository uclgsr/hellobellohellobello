package com.yourcompany.sensorspoke.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.yourcompany.sensorspoke.service.RecordingService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI for Phase 1; immediately start the foreground service
        lifecycleScope.launch {
            val intent = Intent(this@MainActivity, RecordingService::class.java)
            startForegroundService(intent)
        }
    }
}
