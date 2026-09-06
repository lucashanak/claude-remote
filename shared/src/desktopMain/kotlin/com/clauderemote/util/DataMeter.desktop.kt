package com.clauderemote.util

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Desktop has no per-process byte counter without native APIs (no OSHI, no
 * netlink) — the best we get with plain JVM APIs is a system-wide cumulative
 * counter across all interfaces, matching the (rx, tx) since-boot semantics
 * Android's TrafficStats gives us (see DataMeter.android.kt), just scoped to
 * the whole machine instead of our UID — hence `appScoped = false`. Callers
 * must not compute a "residual/overhead" figure against this number the way
 * they can for Android's per-app count, since it also includes every other
 * process on the box.
 */
actual fun platformNetBytes(): NetBytes? = try {
    val os = System.getProperty("os.name")?.lowercase() ?: ""
    when {
        os.contains("linux") -> parseProcNetDev(File("/proc/net/dev").readText())
            ?.let { NetBytes(it.first, it.second, appScoped = false) }
        os.contains("mac") -> runNetstatIbn()
            ?.let { parseNetstatIbn(it) }
            ?.let { NetBytes(it.first, it.second, appScoped = false) }
        else -> null // Windows: not a supported target here.
    }
} catch (_: Exception) {
    null
}

/** Runs with a short timeout since this is polled from the UI; a hung
 *  `netstat` must not block the poller. Returns null on timeout/failure. */
private fun runNetstatIbn(): String? {
    val p = ProcessBuilder("netstat", "-ibn").redirectErrorStream(true).start()
    return try {
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else {
            p.inputStream.bufferedReader().readText()
        }
    } finally {
        if (p.isAlive) p.destroyForcibly()
    }
}

// Prefixes of virtual interfaces (Docker bridges/veth pairs, libvirt bridges)
// that carry the SAME traffic as the physical NIC it's routed through/from —
// counting them alongside the physical interface double- or triple-counts
// that traffic on a box running containers/VMs (this dev box does). Real
// physical/Wi-Fi interfaces (eth*, en*, wlan*, wl*) are never named this way.
private val VIRTUAL_IFACE_PREFIXES = listOf("veth", "docker", "br-", "virbr")

/**
 * Parses `/proc/net/dev`. Format (stable since Linux 2.6):
 *   line 1: "Inter-|   Receive        |  Transmit"
 *   line 2: " face |bytes packets ... |bytes packets ..."
 *   data:   "  eth0: <8 receive fields> <8 transmit fields>"
 * `bytes` is always the first field on each side, but we key the transmit
 * offset off the header's receive-column count rather than hardcoding 8, in
 * case a future kernel adds/removes a column.
 */
internal fun parseProcNetDev(text: String): Pair<Long, Long>? {
    val lines = text.lines()
    if (lines.size < 3) return null
    val header = lines[1] // " face |bytes packets errs ... |bytes packets ..."
    val sides = header.split("|")
    if (sides.size < 3) return null
    val receiveFieldCount = sides[1].trim().split(Regex("\\s+")).size
    var rx = 0L
    var tx = 0L
    var any = false
    for (line in lines.drop(2)) {
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val iface = line.substring(0, colon).trim()
        if (iface.isEmpty() || iface == "lo") continue
        if (VIRTUAL_IFACE_PREFIXES.any { iface.startsWith(it) }) continue
        val fields = line.substring(colon + 1).trim().split(Regex("\\s+"))
        if (fields.size <= receiveFieldCount) continue
        val rxBytes = fields[0].toLongOrNull() ?: continue
        val txBytes = fields.getOrNull(receiveFieldCount)?.toLongOrNull() ?: continue
        rx += rxBytes
        tx += txBytes
        any = true
    }
    return if (any) rx to tx else null
}

/**
 * Parses `netstat -ibn` output on macOS. Columns:
 *   Name Mtu Network Address Ipkts Ierrs Ibytes Opkts Oerrs Obytes Coll
 * `netstat -ib` prints one row per address family per interface (link, inet,
 * inet6, ...) — only the link-layer row (Network column is `<Link#N>`)
 * carries the real cumulative byte counters, so we take the FIRST row seen
 * per interface name and skip the rest to avoid double/triple counting.
 * `lo0` is excluded as loopback.
 *
 * The `Address` column is blank for interfaces with no MAC (lo0, some
 * virtual interfaces) — since the row is whitespace-split, a blank field
 * just vanishes rather than parsing as empty, shifting every later column
 * left by one. Name is always the first field, but everything else is
 * indexed from the END of the row (Coll, Obytes, ... are never blank), so
 * a missing Address doesn't misalign the counters we actually read.
 */
internal fun parseNetstatIbn(text: String): Pair<Long, Long>? {
    val lines = text.lines()
    if (lines.isEmpty()) return null
    val header = lines[0].trim().split(Regex("\\s+"))
    val ibytesFromEnd = header.size - header.indexOf("Ibytes")
    val obytesFromEnd = header.size - header.indexOf("Obytes")
    if (header.indexOf("Ibytes") < 0 || header.indexOf("Obytes") < 0) return null
    var rx = 0L
    var tx = 0L
    val seen = HashSet<String>()
    var any = false
    for (line in lines.drop(1)) {
        val fields = line.trim().split(Regex("\\s+"))
        val ibytesIdx = fields.size - ibytesFromEnd
        val obytesIdx = fields.size - obytesFromEnd
        if (fields.isEmpty() || ibytesIdx < 1 || obytesIdx < 1) continue
        val iface = fields[0]
        if (iface.isEmpty() || iface == "lo0" || !seen.add(iface)) continue
        val rxBytes = fields[ibytesIdx].toLongOrNull() ?: continue
        val txBytes = fields[obytesIdx].toLongOrNull() ?: continue
        rx += rxBytes
        tx += txBytes
        any = true
    }
    return if (any) rx to tx else null
}
