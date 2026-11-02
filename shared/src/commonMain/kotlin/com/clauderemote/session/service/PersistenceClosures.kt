package com.clauderemote.session.service

/**
 * Seam: facts that `SessionPersistenceService.forgetSession` records and the
 * hook-event reducer needs ("this device did not just close it"). The stub knows
 * nothing, so it never claims a local close.
 */
internal interface PersistenceClosures {
    /** True if THIS device closed tmux session [name] on [serverId] within the last [withinMs]. Names are only unique per server. */
    fun recentlyClosedLocally(serverId: String, name: String, withinMs: Long): Boolean

    object None : PersistenceClosures {
        override fun recentlyClosedLocally(serverId: String, name: String, withinMs: Long): Boolean = false
    }
}
