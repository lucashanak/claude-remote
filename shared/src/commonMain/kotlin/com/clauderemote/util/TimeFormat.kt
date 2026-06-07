package com.clauderemote.util

/**
 * Convert an ISO-8601 UTC timestamp (Claude Code transcript format,
 * `2026-05-15T15:24:02.384Z`) to the device's LOCAL time as `HH:mm:ss`.
 * Returns null when the input doesn't parse — callers fall back to the raw
 * UTC clock-time substring.
 */
expect fun isoToLocalTime(iso: String): String?
