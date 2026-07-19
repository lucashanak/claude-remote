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

actual fun isoToEpochMillis(iso: String): Long? =
    try {
        // OffsetDateTime handles both a `+00:00` offset and a bare `Z`; the
        // Instant fallback covers any bare-`Z` edge OffsetDateTime rejects.
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
