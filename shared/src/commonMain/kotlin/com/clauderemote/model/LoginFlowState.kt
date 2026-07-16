package com.clauderemote.model

/** Active Claude `/login` OAuth flow detected on a session's screen. */
data class LoginFlowState(val sessionId: String, val url: String)
