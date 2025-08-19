package com.yourcompany.sensorspoke.utils

import java.util.concurrent.CopyOnWriteArrayList

/**
 * PreviewBus is a simple in-process event bus to distribute downsampled JPEG
 * preview frames from the camera recorder to interested subscribers (e.g.,
 * the RecordingService which forwards frames to the PC Hub).
 */
object PreviewBus {
    private val listeners = CopyOnWriteArrayList<(bytes: ByteArray, timestampNs: Long) -> Unit>()

    fun subscribe(listener: (ByteArray, Long) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (ByteArray, Long) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(
        bytes: ByteArray,
        timestampNs: Long,
    ) {
        for (l in listeners) {
            runCatching { l(bytes, timestampNs) }
        }
    }
}
