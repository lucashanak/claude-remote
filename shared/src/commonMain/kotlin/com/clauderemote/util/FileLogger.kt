package com.clauderemote.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(logDir: File) {
        logDir.mkdirs()
        val fileName = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        logFile = File(logDir, "claude-remote-$fileName.log")
    }

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp [$tag] $message\n"
        try {
            logFile?.appendText(line)
        } catch (_: Exception) {}
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(tag, "ERROR: $message${throwable?.let { " | ${it.message}" } ?: ""}")
    }
}
