package com.clauderemote.session.service

import com.clauderemote.model.ServerTransport
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The server-level strike feed the exec transport uses (`noteServerEarlyDeath`), observed through the resolver's cooldown. */
class TransportResolverServerDeathTest {
    private val base = SshServer(
        id = "s", name = "box", host = "ssh.example.org", username = "u",
        useCloudflareProxy = true, tailscaleHost = "100.64.0.7", transport = ServerTransport.TAILSCALE,
    )
    private val ts = base.forTransport(ServerTransport.TAILSCALE)

    private fun resolver() = TransportResolver(CoroutineScope(SupervisorJob() + Dispatchers.Default), ConnectionRegistry(ServerStorage(FakeKeyValueStore()), TabManager()))

    /** "cooling" = the pinned Tailscale server is resolved to its Cloudflare fallback. */
    private fun cooling(r: TransportResolver) = runBlocking { r.resolveTransport(base) }.useCloudflareProxy

    @Test fun twoYoungTailscaleDeathsArmTheCooldownThatTabsUse() {
        val r = resolver()
        assertEquals(false, cooling(r))
        r.noteServerEarlyDeath(base, ts, 1_000)
        assertEquals(false, cooling(r), "one strike is tolerated")
        r.noteServerEarlyDeath(base, ts, 2_000)
        assertEquals(true, cooling(r), "two strikes => Cloudflare for the cooldown window")
    }

    @Test fun aTransportThatLivedThirtySecondsIsNotAStrike() {
        val r = resolver()
        repeat(5) { r.noteServerEarlyDeath(base, ts, 30_000) }
        assertEquals(false, cooling(r))
    }

    @Test fun aCloudflareEndpointNeverStrikes() {
        val r = resolver()
        repeat(5) { r.noteServerEarlyDeath(base, base, 100) }   // eff = the Cloudflare config
        assertEquals(false, cooling(r))
    }

    @Test fun deathsRightAfterOurOwnNetworkTeardownAreNotStrikes() {
        val r = resolver()
        r.markNetworkTeardown()
        repeat(5) { r.noteServerEarlyDeath(base, ts, 100) }
        assertEquals(false, cooling(r), "we closed the transport ourselves")
    }
    @Test fun aTransportThatStayedUpClearsTheStrikeStreak() {
        val r = resolver()
        r.noteServerEarlyDeath(base, ts, 1_000)
        r.noteServerTransportStable(base, ts)
        r.noteServerEarlyDeath(base, ts, 1_000)
        assertEquals(false, cooling(r), "the strike before the proven-stable transport no longer counts")
    }

    @Test fun aStableCloudflareTransportLeavesTheTailscaleStreakAlone() {
        val r = resolver()
        r.noteServerEarlyDeath(base, ts, 1_000)
        r.noteServerTransportStable(base, base)   // eff = the Cloudflare config
        r.noteServerEarlyDeath(base, ts, 1_000)
        assertEquals(true, cooling(r))
    }
}
