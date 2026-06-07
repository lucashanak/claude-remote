package com.clauderemote.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val localTimeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun isoToLocalTime(iso: String): String? = try {
    localTimeFmt.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
} catch (_: Throwable) {
    null
}
