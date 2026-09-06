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
    // DRAIN FIRST. Waiting before reading deadlocks as soon as netstat writes
    // more than the pipe buffer holds — a Mac with a handful of utun/awdl/
    // bridge interfaces, each printing a row per address family, gets there
    // easily. The wait would then time out on every single poll and the meter
    // would report nothing, forever, while killing a child each minute. The
    // watchdog keeps the timeout guarantee that reading-first gives up.
    val killed = java.util.concurrent.atomic.AtomicBoolean(false)
    val watchdog = Thread {
        try {
            Thread.sleep(2_000)
        } catch (_: InterruptedException) {
            return@Thread
        }
        if (p.isAlive) {
            killed.set(true)
            p.destroyForcibly()
        }
    }.apply { isDaemon = true; start() }
    return try {
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(1, TimeUnit.SECONDS)
        // A killed netstat leaves a TRUNCATED table, which parses cleanly into
        // a silently under-counted sum. No number is better than a wrong one
        // the reader cannot distinguish from a real drop in traffic.
        if (killed.get()) null else out.ifBlank { null }
    } catch (_: Exception) {
        null
    } finally {
        watchdog.interrupt()
        if (p.isAlive) p.destroyForcibly()
    }
}

// Prefixes of virtual interfaces (Docker bridges/veth pairs, libvirt bridges)
// that carry the SAME traffic as the physical NIC it's routed through/from —
// counting them alongside the physical interface double- or triple-counts
// that traffic on a box running containers/VMs (this dev box does). Real
// physical/Wi-Fi interfaces (eth*, en*, wlan*, wl*) are never named this way.
// Every entry here must be traffic that ALSO crosses a physical interface and
// would otherwise be counted twice: the tunnels (utun* is what Tailscale and
// every macOS VPN present), the software bridges, and a link aggregate over
// its members. NOT listed: llw0/ap1 — like awdl0 they are separate Apple
// peer-to-peer radio paths carrying their own traffic to another device, not
// an encapsulation of bytes leaving via en0, so excluding them would drop real
// traffic from a total whose whole point is to be machine-wide (and would
// contradict counting awdl0, which DataMeterTest pins).
private val MAC_VIRTUAL_IFACE_PREFIXES =
    listOf("utun", "ipsec", "gif", "stf", "bridge", "vmenet", "bond")

private val VIRTUAL_IFACE_PREFIXES = listOf(
    // Container/VM plumbing: a veth pair and the bridge it hangs off both see
    // the bytes that also cross the physical NIC.
    "veth", "docker", "br-", "virbr", "vnet", "vmbr",
    // TUNNELS, and these are the ones that matter here: this app routes its
    // sessions over Tailscale or a Cloudflare tunnel, so the encapsulated
    // bytes appear once on tailscale0/tun0/wg0 and again on the physical
    // interface underneath. Counting both doubles exactly the traffic the
    // meter exists to investigate.
    "tailscale", "tun", "tap", "wg",
    // A bond's members carry the same frames as the bond device.
    "bond",
)

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
    val ibytesCol = header.indexOf("Ibytes")
    val obytesCol = header.indexOf("Obytes")
    if (ibytesCol < 0 || obytesCol < 0) return null
    val ibytesFromEnd = header.size - ibytesCol
    val obytesFromEnd = header.size - obytesCol
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
        // Same double-count as Linux, different names: utun* is what Tailscale
        // and every macOS VPN present, and the encapsulated bytes cross en0
        // underneath. ipsec*/gif*/bridge*/vmenet* are the other tunnels and
        // bridges that carry already-counted traffic.
        if (MAC_VIRTUAL_IFACE_PREFIXES.any { iface.startsWith(it) }) continue
        if (iface.isEmpty() || iface == "lo0" || !seen.add(iface)) continue
        val rxBytes = fields[ibytesIdx].toLongOrNull() ?: continue
        val txBytes = fields[obytesIdx].toLongOrNull() ?: continue
        rx += rxBytes
        tx += txBytes
        any = true
    }
    return if (any) rx to tx else null
}
