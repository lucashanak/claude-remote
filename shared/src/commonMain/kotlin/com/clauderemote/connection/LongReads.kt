package com.clauderemote.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatcher for reads that block for the whole life of a channel or process: a tab's shell read loop, the
 * streamd and notify-watcher line readers, the ET and mosh client output.
 *
 * Why not [Dispatchers.IO]: it is capped at 64 threads, and each of these loops parks one of them in a blocking
 * read for as long as its channel lives. The desktop reconnects every restored tab at launch. With 62 tabs the
 * 62 shell read loops plus streamd and the notify watcher held all 64, and the attach step of the remaining
 * tabs (`withContext(Dispatchers.IO)`, the very thing that would make those reads return) never got a thread:
 * Mac v1.0.569 on 2026-11-09, 14 of 62 tabs attached, the rest CONNECTING for hours, every IO poller and the
 * log shipper silent after the first 25 s. A thread dump showed 64 `DefaultDispatcher-worker` threads in
 * `PipedInputStream.read`, 62 of them at the `SshManager` read loop.
 *
 * A view of [Dispatchers.IO] made by `limitedParallelism` is not bounded by IO's 64 (elasticity, kotlinx.coroutines
 * ≥ 1.7): its threads come on demand from the same pool and IO keeps its own 64 for short work. The bound here is
 * only a ceiling against a runaway leak, far above any real tab count.
 */
val LongReads: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(LONG_READS_MAX)

/** Ceiling of [LongReads]: one thread per live tab or watcher, with a lot of room above the ~60 tabs seen. */
const val LONG_READS_MAX = 1024
