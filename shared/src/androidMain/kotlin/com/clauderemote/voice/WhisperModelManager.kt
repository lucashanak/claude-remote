package com.clauderemote.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the sherpa-onnx Whisper "small" (int8) Czech-capable model plus
 * the Silero VAD model used to chop audio into utterances.
 *
 * Unlike the Vosk model (a single zip), the sherpa-onnx Whisper bundle is a
 * handful of individual files, so we just stream each one to disk. Total is
 * ~375 MB — a deliberate quality tier the user opts into; it lives in
 * filesDir, not the APK.
 *
 * Files land in `filesDir/whisper-cs/`:
 *   small-encoder.int8.onnx, small-decoder.int8.onnx, small-tokens.txt,
 *   silero_vad.onnx
 */
internal object WhisperModelManager {

    private const val HF_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main"
    private const val VAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    const val ENCODER = "small-encoder.int8.onnx"
    const val DECODER = "small-decoder.int8.onnx"
    const val TOKENS = "small-tokens.txt"
    const val VAD = "silero_vad.onnx"

    private val FILES = listOf(
        ENCODER to "$HF_BASE/small-encoder.int8.onnx",
        DECODER to "$HF_BASE/small-decoder.int8.onnx",
        TOKENS to "$HF_BASE/small-tokens.txt",
        VAD to VAD_URL,
    )

    fun modelDir(context: Context): File = File(context.filesDir, "whisper-cs")

    fun path(context: Context, file: String): String =
        File(modelDir(context), file).absolutePath

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return false
        // Encoder/decoder are large; treat a model as ready only if every
        // expected file exists and is non-trivially sized (guards against a
        // half-finished download being mistaken for a complete one).
        return FILES.all { (name, _) ->
            val f = File(dir, name)
            f.isFile && f.length() > 1024
        }
    }

    /**
     * Download all model files. Reports overall progress 0..1f. Returns true
     * on success. Runs on IO. Re-downloads everything (no resume) — simple
     * and the user only does this once.
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = modelDir(context).apply { mkdirs() }

        // First pass: HEAD requests to learn total bytes for a real progress
        // bar. Fall back to per-file weighting if a server omits the length.
        val sizes = LongArray(FILES.size)
        var grandTotal = 0L
        for (i in FILES.indices) {
            sizes[i] = contentLength(FILES[i].second).coerceAtLeast(1L)
            grandTotal += sizes[i]
        }
        grandTotal = grandTotal.coerceAtLeast(1L)

        var written = 0L
        for ((name, url) in FILES) {
            val ok = downloadFile(url, File(dir, name)) { chunk ->
                written += chunk
                onProgress((written.toFloat() / grandTotal).coerceIn(0f, 1f))
            }
            if (!ok) return@withContext false
        }
        isModelReady(context)
    }

    private fun contentLength(url: String): Long = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        try {
            conn.connect()
            conn.contentLengthLong
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(-1L)

    private fun downloadFile(
        url: String,
        dest: File,
        onChunk: (Long) -> Unit,
    ): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode !in 200..299) return false
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        onChunk(n.toLong())
                    }
                }
            }
            true
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(false)
}
