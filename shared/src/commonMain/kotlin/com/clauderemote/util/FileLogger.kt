package com.clauderemote.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple file logger that works on both Android and Desktop.
 * On Android, also logs to Logcat via platform-specific init.
 */
object FileLogger {
    private const val FILE_NAME = "app_debug.log"
    private const val MAX_SIZE = 512 * 1024 // 512KB

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Platform-specific logcat bridge (set by Android Application class)
    var platformLog: ((level: String, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun init(filesDir: File, appVersion: String = "") {
        logFile = File(filesDir, FILE_NAME)
        log("FileLogger", "=== App started${if (appVersion.isNotBlank()) ", version=$appVersion" else ""} ===")
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        platformLog?.invoke("D", tag, message, throwable)
        writeToFile("D", tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        platformLog?.invoke("E", tag, message, throwable)
        writeToFile("E", tag, message, throwable)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        platformLog?.invoke("W", tag, message, throwable)
        writeToFile("W", tag, message, throwable)
    }

    fun readLog(): String {
        val file = logFile ?: return "(no log file)"
        return if (file.exists()) file.readText() else "(empty log)"
    }

    fun clearLog() {
        logFile?.let { if (it.exists()) it.delete() }
        log("FileLogger", "=== Log cleared ===")
    }

    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable?) {
        val file = logFile ?: return
        try {
            val time = dateFormat.format(Date())
            val line = buildString {
                append("$time $level/$tag: $message")
                if (throwable != null) {
                    append("\n  ${throwable::class.java.simpleName}: ${throwable.message}")
                    for (frame in throwable.stackTrace.take(8)) {
                        append("\n    at $frame")
                    }
                }
                append("\n")
            }

            // Truncate if too large
            if (file.exists() && file.length() > MAX_SIZE) {
                val tail = file.readText().takeLast(MAX_SIZE / 2)
                file.writeText("--- truncated ---\n$tail")
            }

            file.appendText(line)
        } catch (_: Exception) {}
    }
}
