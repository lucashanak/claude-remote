package com.clauderemote.util

// Desktop has no cheap app-wide byte counter; rely on the per-stream meters.
actual fun platformNetBytes(): Pair<Long, Long>? = null
