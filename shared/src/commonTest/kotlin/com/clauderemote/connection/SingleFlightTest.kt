package com.clauderemote.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The generic single-flight the pool leases through: dedupe, capacity, failure fan-out, no leaks. */
class SingleFlightTest {
    private class Res(val id: Int)

    private class Harness(capacity: Int = 8) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutex = Mutex()
        val published = CopyOnWriteArrayList<Pair<Res, Int>>()
        val disposed = CopyOnWriteArrayList<Res>()
        val connects = AtomicInteger()
        /** Claimants that have joined a flight (the join is done, the wait is next). */
        val joined = AtomicInteger()
        val flight = SingleFlight<String, Res>(
            scope, mutex,
            publish = { f, r -> published += r to f.claims },
            dispose = { disposed += it },
        )
        val cap = capacity

        /** Claim a slot and wait for the value; [gate] holds the connect open. */
        suspend fun lease(key: String, gate: CompletableDeferred<Unit>? = null, fail: Throwable? = null): Res {
            val f = mutex.withLock {
                flight.join(key, cap) {
                    connects.incrementAndGet()
                    gate?.await()
                    if (fail != null) throw fail
                    Res(connects.get())
                }
            }
            joined.incrementAndGet()
            return try {
                f.result.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // what the pool does for a claimant that gives up
                withContext(NonCancellable) { mutex.withLock { flight.abandon(f) } }
                throw e
            }
        }
    }

    private fun bounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) { runBlocking { withTimeout(20_000) { block() } } }

    @Test fun twentyConcurrentLeasesWithRoomForAllShareOneConnect() = bounded {
        val h = Harness(capacity = 20)
        val gate = CompletableDeferred<Unit>()
        val all = (1..20).map { async { h.lease("k", gate) } }
        while (h.joined.get() < 20) kotlinx.coroutines.yield()
        gate.complete(Unit)
        val got = all.awaitAll()
        assertEquals(1, h.connects.get(), "20 claimants, capacity 20 => ONE connect")
        assertTrue(got.all { it === got[0] })
        assertEquals(listOf(20), h.published.map { it.second }, "published once, with the 20 claims promised")
    }

    @Test fun capacityBoundsClaimantsPerConnectSoTwentyLeasesAtCapEightStartThree() = bounded {
        val h = Harness(capacity = 8)
        val gate = CompletableDeferred<Unit>()
        val all = (1..20).map { async { h.lease("k", gate) } }
        while (h.joined.get() < 20) kotlinx.coroutines.yield()
        gate.complete(Unit)
        val got = all.awaitAll()
        assertEquals(3, h.connects.get(), "ceil(20/8) = 3 connects, never more")
        assertEquals(3, got.distinct().size)
        assertEquals(listOf(4, 8, 8), h.published.map { it.second }.sorted(), "claims per connect: 8 + 8 + 4")
        assertEquals(3, h.mutex.withLock { h.flight.peakInflight }, "they ran in parallel, not one after another")
    }

    @Test fun aFailureReachesEveryWaiterAndClearsTheEntry() = bounded {
        val h = Harness(capacity = 8)
        val gate = CompletableDeferred<Unit>()
        val boom = IllegalStateException("handshake failed")
        val all = (1..5).map { async { runCatching { h.lease("k", gate, fail = boom) } } }
        while (h.joined.get() < 5) kotlinx.coroutines.yield()
        gate.complete(Unit)
        val results = all.awaitAll()
        assertTrue(results.all { it.exceptionOrNull().let { e -> e is IllegalStateException && e.message == boom.message } }, "every waiter gets the failure: $results")
        assertEquals(0, h.mutex.withLock { h.flight.inflight }, "the failed flight is forgotten")
        // and the next claimant starts a FRESH connect instead of inheriting the failure
        val again = h.lease("k")
        assertEquals(2, h.connects.get())
        assertTrue(again.id > 0)
        assertEquals(emptyList(), h.disposed.toList())
    }

    @Test fun aSlowKeyNeverBlocksAnotherKey() = bounded {
        val h = Harness(capacity = 8)
        val slow = CompletableDeferred<Unit>()
        val a = async { h.lease("A", slow) }
        while (h.joined.get() < 1) kotlinx.coroutines.yield()
        val b = h.lease("B")      // returns while A is still connecting
        assertTrue(!a.isCompleted, "A is still connecting")
        slow.complete(Unit)
        assertNotSame(a.await(), b)
    }

    @Test fun cancellingTheInitiatorDoesNotCancelTheConnectForTheOthers() = bounded {
        val h = Harness(capacity = 8)
        val gate = CompletableDeferred<Unit>()
        val initiator = launch { h.lease("k", gate) }
        while (h.joined.get() < 1) kotlinx.coroutines.yield()
        val other = async { h.lease("k", gate) }
        while (h.joined.get() < 2) kotlinx.coroutines.yield()
        initiator.cancelAndJoin()
        gate.complete(Unit)
        val r = other.await()
        assertEquals(1, h.connects.get(), "one connect served both")
        assertEquals(listOf(r to 1), h.published.map { it.first to it.second }, "published for the one claimant left")
        assertEquals(emptyList(), h.disposed.toList())
    }

    @Test fun aConnectNobodyWaitsForAnymoreIsDisposedNotLeaked() = bounded {
        val h = Harness(capacity = 8)
        val gate = CompletableDeferred<Unit>()
        val f = h.mutex.withLock { h.flight.join("k", 8) { h.connects.incrementAndGet(); gate.await(); Res(7) } }
        // the only claimant gives up while the handshake is still running
        h.mutex.withLock { h.flight.abandon(f) }
        gate.complete(Unit)
        val r = f.result.await()
        assertEquals(emptyList(), h.published.toList(), "nobody wants it: not published")
        assertEquals(listOf(r), h.disposed.toList(), "...but disposed, so the session cannot leak")
    }

    @Test fun aFinishedFlightIsNotJoinedAgain() = bounded {
        val h = Harness(capacity = 8)
        val first = h.lease("k")
        val second = h.lease("k")
        assertNotSame(first, second, "the pool, not the flight, owns a finished value; a new claim connects again")
        assertEquals(2, h.connects.get())
        assertSame(first, h.published.first().first)
    }
    @Test fun abandoningAFinishedFlightLeavesItsClaimsAlone() = bounded {
        val mutex = Mutex()
        val flight = SingleFlight<String, Res>(
            CoroutineScope(SupervisorJob() + Dispatchers.Default), mutex,
            publish = { _, _ -> },
            dispose = { },
        )
        val f = mutex.withLock { flight.join("k", 8) { Res(1) } }
        f.result.await()
        assertEquals(1, f.claims)
        // the claimant gave up only after the value was published: the owner returns the slot itself
        mutex.withLock { flight.abandon(f) }
        assertEquals(1, f.claims, "a finished flight keeps the claims it was published with")
    }

    @Test fun aFailingPublishFailsTheWaitersInsteadOfLeavingThemHanging() = bounded {
        val mutex = Mutex()
        val flight = SingleFlight<String, Res>(
            CoroutineScope(SupervisorJob() + Dispatchers.Default), mutex,
            publish = { _, _ -> throw IllegalStateException("publish broke") },
            dispose = { },
        )
        val f = mutex.withLock { flight.join("k", 8) { Res(1) } }
        val e = assertFailsWith<IllegalStateException> { f.result.await() }
        assertEquals("publish broke", e.message)
    }

    @Test fun aFailingDisposeStillCompletesTheFlight() = bounded {
        val mutex = Mutex()
        val flight = SingleFlight<String, Res>(
            CoroutineScope(SupervisorJob() + Dispatchers.Default), mutex,
            publish = { _, _ -> },
            dispose = { throw IllegalStateException("dispose broke") },
        )
        val gate = CompletableDeferred<Unit>()
        val f = mutex.withLock { flight.join("k", 8) { gate.await(); Res(1) } }
        mutex.withLock { flight.abandon(f) }   // nobody left: the value is disposed
        gate.complete(Unit)
        assertFailsWith<IllegalStateException> { f.result.await() }
    }

    // ---- the bound on connects running at once ----------------------------------------------------

    @Test fun aBoundOnParallelConnectsRefusesTheFourthFlightAndAdmitsItAfterOneEnds() = bounded {
        val mutex = Mutex()
        val flight = SingleFlight<String, Res>(CoroutineScope(SupervisorJob() + Dispatchers.Default), mutex, publish = { _, _ -> }, dispose = { })
        val gates = List(4) { CompletableDeferred<Unit>() }
        val joined = mutex.withLock {
            (0 until 3).map { i -> flight.tryJoin("k", capacity = 1, maxParallel = 3) { gates[i].await(); Res(i) } }
        }
        assertTrue(joined.all { it != null })
        assertEquals(3, mutex.withLock { flight.inflight })
        // every flight is full (capacity 1) and three already run: nothing new may start
        assertEquals(null, mutex.withLock { flight.tryJoin("k", capacity = 1, maxParallel = 3) { gates[3].await(); Res(3) } })
        assertEquals(3, mutex.withLock { flight.started })
        val oldest = mutex.withLock { flight.pending("k") }!!
        assertSame(joined[0], oldest, "a refused claimant waits for the oldest connect")
        gates[0].complete(Unit)
        oldest.result.await()
        val again = mutex.withLock { flight.tryJoin("k", capacity = 1, maxParallel = 3) { gates[3].await(); Res(3) } }
        assertTrue(again != null, "a slot is free again")
        assertEquals(4, mutex.withLock { flight.started })
        assertEquals(3, mutex.withLock { flight.peakInflight }, "never more than the bound ran at once")
        gates.forEach { it.complete(Unit) }
    }

    @Test fun aClaimantStillJoinsAPendingFlightWithRoomWhenTheBoundIsReached() = bounded {
        val mutex = Mutex()
        val flight = SingleFlight<String, Res>(CoroutineScope(SupervisorJob() + Dispatchers.Default), mutex, publish = { _, _ -> }, dispose = { })
        val gate = CompletableDeferred<Unit>()
        mutex.withLock {
            val a = flight.tryJoin("k", capacity = 4, maxParallel = 1) { gate.await(); Res(1) }!!
            val b = flight.tryJoin("k", capacity = 4, maxParallel = 1) { gate.await(); Res(2) }
            assertSame(a, b, "room left in the one running connect")
        }
        gate.complete(Unit)
    }
}
