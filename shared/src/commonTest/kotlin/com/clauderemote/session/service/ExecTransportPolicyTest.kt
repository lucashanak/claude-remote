package com.clauderemote.session.service

import com.clauderemote.connection.RouteKind
import com.clauderemote.model.SshServer
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure rules of the exec transport: backoff, which deaths strike Tailscale, which route a connection took. */
class ExecTransportPolicyTest {
    private val server = SshServer(id = "s", name = "box", host = "ssh.example.org", username = "u", tailscaleHost = "100.64.0.7")

    @Test fun foregroundBackoffIsZeroOneTwoFourEightSixteenThirtyThenStays() {
        val seq = (0..9).map { ExecTransportPolicy.backoffMs(it, background = false) / 1000 }
        assertEquals(listOf(0L, 1, 2, 4, 8, 16, 30, 30, 30, 30), seq)
    }

    @Test fun backgroundBackoffIsSlowerWithAFloorAndACap() {
        val seq = (0..9).map { ExecTransportPolicy.backoffMs(it, background = true) / 1000 }
        assertEquals(listOf(5L, 5, 5, 8, 16, 32, 60, 60, 60, 60), seq)
        assertTrue(ExecTransportPolicy.downNetworkRetryMs(true) > ExecTransportPolicy.downNetworkRetryMs(false))
    }

    @Test fun aNegativeAttemptIsTreatedAsTheFirst() {
        assertEquals(0L, ExecTransportPolicy.backoffMs(-3, background = false))
    }

    @Test fun onlyAYoungTailscaleDeathStrikes() {
        assertTrue(ExecTransportPolicy.strikesTailscale("TS", 0))
        assertTrue(ExecTransportPolicy.strikesTailscale("TS", 29_999))
        assertFalse(ExecTransportPolicy.strikesTailscale("TS", 30_000), "30 s is already a normal life")
        assertFalse(ExecTransportPolicy.strikesTailscale("CF", 100), "Cloudflare is the last resort and never strikes")
        assertFalse(ExecTransportPolicy.strikesTailscale("DIRECT", 100))
    }

    @Test fun aStableTransportResetsTheBackoffAndAnUnstableOneEscalatesIt() {
        assertEquals(0, ExecTransportPolicy.nextAttempt(5, 60_000))
        assertEquals(6, ExecTransportPolicy.nextAttempt(5, 59_999))
        assertEquals(1, ExecTransportPolicy.nextAttempt(0, 0))
    }

    @Test fun viaIsDerivedFromTheResolvedEndpoint() {
        assertEquals("CF", ExecTransportPolicy.viaOf(server, server.copy(useCloudflareProxy = true)))
        assertEquals("TS", ExecTransportPolicy.viaOf(server, server.copy(host = "100.64.0.7")))
        assertEquals("DIRECT", ExecTransportPolicy.viaOf(server, server))
        assertEquals("DIRECT", ExecTransportPolicy.viaOf(server.copy(tailscaleHost = ""), server.copy(tailscaleHost = "")))
    }

    // ---- RouteKind: LAN vs tunnel vs Cloudflare (the 126 baseline "TS deaths" went over the LAN) ----

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun cloudflareWinsWhateverTheSocketSays() {
        assertEquals("cf", RouteKind.classify(ip("192.168.1.20"), ip("192.168.1.7"), viaProxy = true))
        assertEquals("cf", RouteKind.classify(null, null, viaProxy = true))
    }

    @Test fun aTailscaleEndpointReachedOverTheLanIsLanNotTunnel() {
        // phone on home Wi-Fi, Tailscale off, the resolver said "TAILSCALE -> 192.168.1.7"
        assertEquals("lan", RouteKind.classify(ip("192.168.1.20"), ip("192.168.1.7"), viaProxy = false, ifaceName = "wlan0"))
    }

    @Test fun aSocketOnATailscaleAddressOrToOneIsATunnel() {
        assertEquals("tunnel", RouteKind.classify(ip("100.101.102.103"), ip("192.168.1.7"), false), "local address in 100.64/10 (subnet route)")
        assertEquals("tunnel", RouteKind.classify(ip("192.168.1.20"), ip("100.64.0.7"), false), "remote address in 100.64/10")
        assertEquals("tunnel", RouteKind.classify(ip("fd7a:115c:a1e0::1"), ip("fd7a:115c:a1e0::2"), false))
        assertEquals("tunnel", RouteKind.classify(ip("10.0.0.5"), ip("10.0.0.9"), false, ifaceName = "tailscale0"))
        assertEquals("tunnel", RouteKind.classify(ip("10.0.0.5"), ip("10.0.0.9"), false, ifaceName = "tun0"))
    }

    @Test fun theCgnatRangeEndsWhereTailscaleEnds() {
        assertTrue(RouteKind.isTailscaleAddress(ip("100.64.0.1")))
        assertTrue(RouteKind.isTailscaleAddress(ip("100.127.255.254")))
        assertFalse(RouteKind.isTailscaleAddress(ip("100.63.255.255")))
        assertFalse(RouteKind.isTailscaleAddress(ip("100.128.0.1")))
        assertFalse(RouteKind.isTailscaleAddress(ip("192.168.1.7")))
    }

    @Test fun anUnreadableLocalAddressIsUnknownNeverGuessedAsLan() {
        assertEquals("unknown", RouteKind.classify(null, ip("192.168.1.7"), false))
    }
}
