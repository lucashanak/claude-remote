package com.clauderemote.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the Czech Vosk acoustic model (~44 MB) on demand.
 *
 * Model: vosk-model-small-cs-0.4-rhasspy from alphacephei.com. Stored under
 * `filesDir/vosk-cs/<model-dir>`. The model directory contains am/, conf/,
 * graph/, ivector/ which the Vosk Java binding loads via [org.vosk.Model].
 *
 * The download is intentionally simple: HTTPS GET + streaming unzip. No
 * resume, no checksum — the model is small and we re-download on failure.
 */
internal object VoskModelManager {

    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-cs-0.4-rhasspy"

    fun modelDir(context: Context): File =
        File(context.filesDir, "vosk-cs/$MODEL_DIR_NAME")

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        // The Vosk runtime expects am/ + conf/ at minimum. Check both before
        // declaring the model "ready" so a half-extracted zip doesn't get
        // mistaken for a valid install.
        return dir.isDirectory &&
            File(dir, "am").isDirectory &&
            File(dir, "conf").isDirectory
    }

    /**
     * Download + extract. Reports progress 0..1f via [onProgress]; returns
     * true on success. Safe to call on a coroutine; runs on Dispatchers.IO.
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "vosk-cs").apply { mkdirs() }
        // Clear out any partial install before starting.
        modelDir(context).deleteRecursively()

        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext false
            val total = conn.contentLengthLong.coerceAtLeast(1L)
            var written = 0L
            ZipInputStream(conn.inputStream).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(64 * 1024)
                while (entry != null) {
                    val outFile = File(root, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                                onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            isModelReady(context)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
