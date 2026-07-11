package com.clauderemote.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Wire model for the phone -> watch session snapshot (path "/sessions").
 * This module has no Gradle dependency on `androidApp` (kept dependency-free
 * — see build.gradle.kts), so it declares its own copy matching
 * androidApp's `WearSync.kt`. Keep the two in sync when the shape changes.
 */
@Serializable
data class WearSessionInfo(
    val id: String,
    val title: String,
    val status: String,
    val activity: String,
    val lastMessage: String?,
    val lastMessageAt: Long = 0,
)

@Serializable
data class WearSessionsPayload(val sessions: List<WearSessionInfo>)

/**
 * Process-wide holder for the latest synced session list. Written by
 * [WearDataListenerService] (system-invoked per data change, not a
 * long-lived component you can hold a reference to) and by MainActivity's
 * one-shot initial fetch on launch; read by the SessionList UI via
 * `collectAsState()`.
 */
object SessionRepository {
    private val _sessions = MutableStateFlow<List<WearSessionInfo>>(emptyList())
    val sessions: StateFlow<List<WearSessionInfo>> = _sessions

    fun update(payload: List<WearSessionInfo>) {
        _sessions.value = payload
    }
}
