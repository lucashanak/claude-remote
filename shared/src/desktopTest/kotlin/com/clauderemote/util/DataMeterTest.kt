package com.clauderemote.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Feeds the parsers captured sample text rather than reading the live
 * system — a test that depends on live counters is untestable in CI.
 */
class DataMeterTest {

    // Real `cat /proc/net/dev` capture from a Linux dev box: lo plus a mix of
    // a physical NIC, bridges and veth pairs (docker). Header line 2 declares
    // 8 receive fields, so transmit `bytes` is field index 8.
    private val procNetDevSample = """
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
            lo: 20506938849 30023766    0    0    0     0          0         0 20506938849 30023766    0    0    0     0       0          0
          eth0: 77030956018 280088566    0 1008128    0     0          0         0 132232976588 201304034    0  591    0     0       0          0
        br-92369b181541: 1556138   14834    0    0    0     0          0         0  4946533   12771    0    0    0     0       0          0
        docker0: 478430901  790070    0    0    0     0          0         0 9469912660 1999251    0    0    0     0       0          0
        veth363da6e: 322151703  704378    0    0    0     0          0         0 226543206  365952    0    0    0     0       0          0
    """.trimIndent()

    @Test
    fun `parseProcNetDev sums only the physical interface, excluding lo and virtual ifaces`() {
        val (rx, tx) = parseProcNetDev(procNetDevSample)!!
        // eth0 only: lo is loopback, br-92369b181541/docker0/veth363da6e all
        // carry traffic that already crossed (or will cross) eth0 — counting
        // them too would double/triple-count the same bytes.
        assertEquals(77030956018L, rx)
        assertEquals(132232976588L, tx)
    }

    @Test
    fun `parseProcNetDev with only virtual interfaces returns null`() {
        val virtualOnly = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 20506938849 30023766    0    0    0     0          0         0 20506938849 30023766    0    0    0     0       0          0
            docker0: 478430901  790070    0    0    0     0          0         0 9469912660 1999251    0    0    0     0       0          0
          veth363da6e: 322151703  704378    0    0    0     0          0         0 226543206  365952    0    0    0     0       0          0
        """.trimIndent()
        assertNull(parseProcNetDev(virtualOnly))
    }

    /**
     * The case this project actually hits: sessions ride a Tailscale or
     * Cloudflare tunnel, so the same bytes appear on tailscale0/tun0 AND on the
     * physical NIC underneath. Counting both doubles exactly the traffic the
     * meter exists to investigate.
     */
    @Test
    fun `parseProcNetDev excludes tunnel interfaces so tunnelled bytes are counted once`() {
        val withTunnels = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0
              eth0: 5000 50 0 0 0 0 0 0 7000 70 0 0 0 0 0 0
        tailscale0: 4000 40 0 0 0 0 0 0 6000 60 0 0 0 0 0 0
              tun0: 3000 30 0 0 0 0 0 0 2000 20 0 0 0 0 0 0
               wg0: 1500 15 0 0 0 0 0 0 1200 12 0 0 0 0 0 0
        """.trimIndent()
        val (rx, tx) = parseProcNetDev(withTunnels)!!
        assertEquals(5000L, rx, "only the physical interface should count")
        assertEquals(7000L, tx, "only the physical interface should count")
    }

    @Test
    fun `parseProcNetDev with only loopback returns null`() {
        val loOnly = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 20506938849 30023766    0    0    0     0          0         0 20506938849 30023766    0    0    0     0       0          0
        """.trimIndent()
        assertNull(parseProcNetDev(loOnly))
    }

    @Test
    fun `parseProcNetDev handles malformed input gracefully`() {
        assertNull(parseProcNetDev(""))
        assertNull(parseProcNetDev("garbage\nmore garbage"))
    }

    // Representative `netstat -ibn` output on macOS: en0 with a link row plus
    // duplicated inet/inet6 rows (same byte counts repeated per address
    // family), lo0 with its own link+inet+inet6 rows.
    private val netstatIbnSample = """
        Name    Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll
        lo0     16384 <Link#1>                         50000     0    5000000    50000     0    5000000     0
        lo0     16384 127           127.0.0.1          50000     0    5000000    50000     0    5000000     0
        lo0     16384 ::1/128       ::1                50000     0    5000000    50000     0    5000000     0
        en0     1500  <Link#4>    aa:bb:cc:dd:ee:ff   900000     0  800000000   700000     0  600000000     0
        en0     1500  192.168.1     192.168.1.5        900000     0  800000000   700000     0  600000000     0
        en0     1500  fe80::1%en0   fe80::1            900000     0  800000000   700000     0  600000000     0
        awdl0   1500  <Link#8>                            10     0       1000       10     0       1000     0
    """.trimIndent()

    @Test
    fun `parseNetstatIbn dedupes per-interface rows and excludes lo0`() {
        val (rx, tx) = parseNetstatIbn(netstatIbnSample)!!
        // First row seen per interface only: en0 (link row) + awdl0, lo0 excluded.
        assertEquals(800000000L + 1000L, rx)
        assertEquals(600000000L + 1000L, tx)
    }

    @Test
    fun `parseNetstatIbn with only loopback returns null`() {
        val loOnly = """
            Name    Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll
            lo0     16384 <Link#1>                         50000     0    5000000    50000     0    5000000     0
        """.trimIndent()
        assertNull(parseNetstatIbn(loOnly))
    }

    @Test
    fun `parseNetstatIbn handles malformed input gracefully`() {
        assertNull(parseNetstatIbn(""))
        assertNull(parseNetstatIbn("garbage header line"))
    }
}
