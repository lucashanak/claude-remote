package com.clauderemote.session.service

/**
 * Seam: per-server bootstrap (to be implemented with: single-flight ensure,
 * memoization, `client-flags.json` force-off decided on the server, 6 h re-ensure).
 */
internal interface ServerBootstrap {
    /** Idempotent, memoized on success. */
    suspend fun ensure(serverId: String)
    /** Forget the memo (e.g. streamd EOF without `hello`). */
    fun invalidate(serverId: String)
    /** Re-read `client-flags.json` (foreground, every 10 min). */
    suspend fun refreshFlags(serverId: String)
}

/** Stub: today's behaviour is the per-tab ensure in NotificationService/TranscriptService, so nothing to do here. */
internal object NoopServerBootstrap : ServerBootstrap {
    override suspend fun ensure(serverId: String) {}
    override fun invalidate(serverId: String) {}
    override suspend fun refreshFlags(serverId: String) {}
}
