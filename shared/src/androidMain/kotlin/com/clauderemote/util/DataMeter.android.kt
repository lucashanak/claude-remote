package com.clauderemote.util

import android.net.TrafficStats
import android.os.Process

actual fun platformNetBytes(): Pair<Long, Long>? {
    val uid = Process.myUid()
    val rx = TrafficStats.getUidRxBytes(uid)
    val tx = TrafficStats.getUidTxBytes(uid)
    return if (rx == TrafficStats.UNSUPPORTED.toLong()) null else rx to tx
}
