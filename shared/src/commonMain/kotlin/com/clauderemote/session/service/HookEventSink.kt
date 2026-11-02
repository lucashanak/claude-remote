package com.clauderemote.session.service

/**
 * Seam: callbacks the hook-event reducer calls and the orchestrator wiring
 * implements. Every method has a no-op default; [NoOp] is the production stub
 * until then.
 */
internal interface HookEventSink {
    fun onActivity(serverId: String, tmuxName: String, atMs: Long) {}
    fun onClaudeWorking(serverId: String, tmuxName: String, working: Boolean) {}
    fun onCheckUuid(serverId: String, tmuxName: String) {}
    fun onCheckClosed(serverId: String, tmuxName: String, delayMs: Long) {}
    fun onNotify(serverId: String, tmuxName: String, kind: String, text: String?) {}

    object NoOp : HookEventSink
}
