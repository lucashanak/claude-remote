package com.clauderemote.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keyed, capacity-bounded "single flight" for expensive connects.
 *
 * Callers that need a resource for key `K` [join] a pending connect instead of
 * starting their own. One connect serves at most `capacity` claimants (the resource
 * can only hold that many), so N concurrent claimants start `ceil(N / capacity)`
 * connects — in parallel, and a slow key never delays another.
 *
 * Ownership rules that make it leak-free:
 *  - the connect runs in [scope] (pool-owned), NOT in the first claimant's coroutine,
 *    so cancelling the initiator does not cancel the connect for the others;
 *  - when the connect finishes with no claimant left, the value is [dispose]d; with
 *    claimants it is handed to [publish] while the lock is held, then delivered through
 *    [Flight.result] — the value is published or disposed, never dropped;
 *  - a failure completes [Flight.result] exceptionally for every claimant and the
 *    flight is forgotten, so the next claimant starts a fresh connect.
 *
 * Locking: [join] and [abandon] must be called with [mutex] held (the owner's own
 * mutex, so the flight table and the owner's structures change atomically).
 * [publish] and [dispose] run with it held, so they must not suspend.
 */
internal class SingleFlight<K : Any, T : Any>(
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val publish: (Flight<K, T>, T) -> Unit,
    private val dispose: (T) -> Unit,
) {
    class Flight<K : Any, T : Any> internal constructor(val key: K, val capacity: Int) {
        /** Completes with the connected value, or exceptionally with the connect's failure. */
        val result: CompletableDeferred<T> = CompletableDeferred()
        /** Claimants that have not given up. Frozen once [finished]. */
        var claims: Int = 0
            internal set
        var finished: Boolean = false
            internal set
        /** The connected value once [finished] successfully (also when it was disposed). */
        var value: T? = null
            internal set
    }

    private val flights = HashMap<K, MutableList<Flight<K, T>>>()

    /** Connects currently running. Read under the lock. */
    val inflight: Int get() = flights.values.sumOf { it.size }

    /** Highest [inflight] ever seen (diagnostics and tests). */
    var peakInflight: Int = 0
        private set

    /** Connects started so far (diagnostics and tests). */
    var started: Int = 0
        private set

    /**
     * Claim one slot of a pending connect for [key], starting [connect] when every
     * pending one is full. [capacity] is how many claimants one connect may serve.
     */
    fun join(key: K, capacity: Int, connect: suspend () -> T): Flight<K, T> =
        tryJoin(key, capacity, Int.MAX_VALUE, connect)!!

    /**
     * [join] with a bound on the connects of one [key] running at the same time: when every pending
     * connect is full and [maxParallel] of them already run, nothing is started and the result is null.
     * The caller then waits for one of them ([pending]) and asks again. This is what keeps a reconnect
     * wave from opening ceil(N / capacity) handshakes at once against a server whose sshd throttles new
     * connections (`MaxStartups`), whatever gate stands in front of the pool.
     */
    fun tryJoin(key: K, capacity: Int, maxParallel: Int, connect: suspend () -> T): Flight<K, T>? {
        check(mutex.isLocked) { "SingleFlight.join needs the owner's mutex" }
        require(capacity >= 1)
        require(maxParallel >= 1)
        val list = flights.getOrPut(key) { mutableListOf() }
        val existing = list.firstOrNull { it.claims < minOf(it.capacity, capacity) }
        if (existing == null && list.size >= maxParallel) return null
        val flight = existing ?: Flight<K, T>(key, capacity).also { f ->
            list += f
            started++
            peakInflight = maxOf(peakInflight, inflight)
            scope.launch { run(f, connect) }
        }
        flight.claims++
        return flight
    }

    /** The oldest connect of [key] still running (what a claimant refused by [tryJoin] waits for), or null. Under the lock. */
    fun pending(key: K): Flight<K, T>? {
        check(mutex.isLocked) { "SingleFlight.pending needs the owner's mutex" }
        return flights[key]?.firstOrNull()
    }

    /** A claimant gave up before the connect finished. A no-op once it has finished. */
    fun abandon(flight: Flight<K, T>) {
        check(mutex.isLocked) { "SingleFlight.abandon needs the owner's mutex" }
        if (!flight.finished && flight.claims > 0) flight.claims--
    }

    private suspend fun run(f: Flight<K, T>, connect: suspend () -> T) {
        val outcome = try {
            Result.success(connect())
        } catch (e: Throwable) {
            Result.failure(e)
        }
        // NonCancellable: a cancelled scope must still publish-or-dispose the value
        withContext(NonCancellable) {
            mutex.withLock {
                f.finished = true
                flights[f.key]?.let { list ->
                    list.remove(f)
                    if (list.isEmpty()) flights.remove(f.key)
                }
                val failure = outcome.exceptionOrNull()
                if (failure != null) {
                    f.result.completeExceptionally(failure)
                } else {
                    val v = outcome.getOrThrow()
                    f.value = v
                    try {
                        if (f.claims > 0) publish(f, v) else dispose(v)
                        f.result.complete(v)
                    } catch (e: Throwable) {
                        // a failing publish/dispose must not leave the waiters hanging
                        f.result.completeExceptionally(e)
                    }
                }
            }
        }
    }
}
