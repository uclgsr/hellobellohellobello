package com.yourcompany.sensorspoke.network

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * FileTransferManager (Phase 5):
 * - Compress a session directory into a ZIP stream
 * - Transfer it to the PC Hub's FileReceiver over TCP
 */
class FileTransferManager(private val context: Context) {

    private fun sessionRoot(): File {
        val base: File? = context.getExternalFilesDir(null)
        return File(base ?: context.filesDir, "sessions")
    }

    private fun sessionDir(sessionId: String): File = File(sessionRoot(), sessionId)

    /**
     * Transfer the given sessionId directory to the host:port receiver.
     * Streams a ZIP over the socket without creating a temp file.
     */
    suspend fun transferSession(sessionId: String, host: String, port: Int) = withContext(Dispatchers.IO) {
        val dir = sessionDir(sessionId)
        if (!dir.exists() || !dir.isDirectory) throw IllegalArgumentException("Session not found: ${dir.absolutePath}")

        val deviceId = (Build.MODEL ?: "device").replace(" ", "_")
        Socket(host, port).use { socket ->
            val out = BufferedOutputStream(socket.getOutputStream())
            // Send header JSON line first
            val header = JSONObject()
                .put("session_id", sessionId)
                .put("device_id", deviceId)
                .put("filename", "${deviceId}_data.zip")
            out.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            // Now stream ZIP content
            ZipOutputStream(out).use { zos ->
                zipDirectoryContents(dir, dir, zos)
                // Also include flash_sync_events.csv if present at app files root
                try {
                    val root = context.getExternalFilesDir(null) ?: context.filesDir
                    val flash = File(root, "flash_sync_events.csv")
                    if (flash.exists() && flash.isFile) {
                        val entry = ZipEntry("flash_sync_events.csv")
                        entry.time = flash.lastModified()
                        zos.putNextEntry(entry)
                        FileInputStream(flash).use { fis ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = fis.read(buf)
                                if (n <= 0) break
                                zos.write(buf, 0, n)
                            }
                        }
                        zos.closeEntry()
                    }
                } catch (_: Exception) { /* best-effort inclusion */ }
                zos.finish()
                zos.flush()
            }
            out.flush()
        }
    }

    private fun zipDirectoryContents(root: File, src: File, zos: ZipOutputStream) {
        val files = src.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                zipDirectoryContents(root, f, zos)
            } else {
                val relPath = root.toURI().relativize(f.toURI()).path
                val entry = ZipEntry(relPath)
                entry.time = f.lastModified()
                zos.putNextEntry(entry)
                FileInputStream(f).use { fis ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        zos.write(buf, 0, n)
                    }
                }
                zos.closeEntry()
            }
        }
    }
}
