package com.clauderemote.model

/** Active Claude `/login` OAuth flow detected on a session's screen. */
data class LoginFlowState(val sessionId: String, val url: String)

/**
 * Claude's "Your login expires in N days · run /login to renew" banner, seen on
 * a session's screen.
 *
 * [days] is null when the banner is there but its count didn't parse — the
 * warning is still worth showing, just without a number.
 */
data class LoginExpiryWarning(val sessionId: String, val days: Int?)
