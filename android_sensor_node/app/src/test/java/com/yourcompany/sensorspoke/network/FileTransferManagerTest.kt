package com.yourcompany.sensorspoke.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileTransferManagerTest {

    @Test
    fun transferSession_streamsHeaderAndZipEntries() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val sessionsRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val sessionId = "session_abc"
        val sessionDir = java.io.File(sessionsRoot, "sessions/${'$'}sessionId").apply { mkdirs() }
        // Create nested files
        val sub1 = java.io.File(sessionDir, "rgb").apply { mkdirs() }
        val sub2 = java.io.File(sessionDir, "gsr").apply { mkdirs() }
        val f1 = java.io.File(sub1, "file1.txt").apply { writeText("hello") }
        val f2 = java.io.File(sub2, "file2.txt").apply { writeText("world") }

        // Start server
        val server = ServerSocket(0)
        val port = server.localPort
        val pool = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val headerHolder = arrayOfNulls<String>(1)
        val entries = mutableListOf<String>()

        pool.submit {
            server.use { ss ->
                ss.accept().use { socket ->
                    readSession(socket.getInputStream(), headerHolder, entries)
                    latch.countDown()
                }
            }
        }

        // Act: transfer
        val ftm = FileTransferManager(context)
        ftm.transferSession(sessionId, "127.0.0.1", port)

        // Assert
        latch.await(5, TimeUnit.SECONDS)
        val header = headerHolder[0]
        require(header != null) { "Header was null" }
        val obj = JSONObject(header)
        assertThat(obj.getString("session_id")).isEqualTo(sessionId)
        assertThat(obj.getString("device_id")).isNotEmpty()
        assertThat(obj.getString("filename")).contains(".zip")

        // Zip contained both files relative paths
        // Depending on OS zip uses forward slashes
        assertThat(entries.any { it.endsWith("rgb/file1.txt") }).isTrue()
        assertThat(entries.any { it.endsWith("gsr/file2.txt") }).isTrue()

        pool.shutdownNow()
    }

    private fun readSession(`in`: InputStream, headerOut: Array<String?>, entries: MutableList<String>) {
        val bis = BufferedInputStream(`in`)
        val headerSb = StringBuilder()
        while (true) {
            val ch = bis.read()
            if (ch == -1) throw IllegalStateException("Stream ended before header newline")
            if (ch.toChar() == '\n') break
            headerSb.append(ch.toChar())
        }
        headerOut[0] = headerSb.toString()
        ZipInputStream(bis).use { zis ->
            while (true) {
                val e: ZipEntry = zis.nextEntry ?: break
                entries.add(e.name)
                val buffer = ByteArray(4096)
                while (true) {
                    val n = zis.read(buffer)
                    if (n <= 0) break
                }
                zis.closeEntry()
            }
        }
    }
}
