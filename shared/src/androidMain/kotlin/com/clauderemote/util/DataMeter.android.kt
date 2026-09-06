package com.clauderemote.util

import android.net.TrafficStats
import android.os.Process

actual fun platformNetBytes(): NetBytes? {
    val uid = Process.myUid()
    val rx = TrafficStats.getUidRxBytes(uid)
    val tx = TrafficStats.getUidTxBytes(uid)
    return if (rx == TrafficStats.UNSUPPORTED.toLong()) null else NetBytes(rx, tx, appScoped = true)
}
