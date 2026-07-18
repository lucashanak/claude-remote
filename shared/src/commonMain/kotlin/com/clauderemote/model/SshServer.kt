package com.clauderemote.model

import kotlinx.serialization.Serializable

@Serializable
data class PortForward(
    val type: String = "L", // "L" local, "R" remote
    val localPort: Int,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int
) {
    fun toSshArg(): String = "-$type $localPort:$remoteHost:$remotePort"
}

/**
 * Which network path to reach the server on.
 *  - CLOUDFLARE: the configured host over the Cloudflare WebSocket tunnel
 *    (or plain SSH if useCloudflareProxy is false) — the existing behavior.
 *  - TAILSCALE: plain SSH to [SshServer.tailscaleHost] (the 100.x / MagicDNS
 *    address), routed by the system Tailscale VPN. WireGuard roaming survives
 *    the egress-IP changes that kill the CF tunnel on Starlink.
 *  - AUTO: prefer TAILSCALE when tailscaleHost is set AND reachable, else fall
 *    back to CLOUDFLARE (probed per connect).
 */
@Serializable
enum class ServerTransport { CLOUDFLARE, TAILSCALE, AUTO }

@Serializable
data class SshServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String? = null,
    val privateKey: String? = null,
    val preferMosh: Boolean = false,
    // Opt-in: reach the session through the Eternal Terminal client, which
    // resumes over TCP so a Starlink egress-IP change (CF WebSocket drop +
    // rebuild) becomes a seamless replay instead of a full tmux redraw. Rides
    // the same CF/Tailscale transport; requires etserver on the server. Off by
    // default — no behavior change until explicitly enabled per server.
    val preferEternal: Boolean = false,
    val defaultFolder: String = "~",
    val recentFolders: List<String> = emptyList(),
    val defaultClaudeMode: ClaudeMode = ClaudeMode.NORMAL,
    val defaultClaudeModel: ClaudeModel = ClaudeModel.DEFAULT,
    val portForwards: List<PortForward> = emptyList(),
    val favorite: Boolean = false,
    val startupCommand: String = "",
    val snippets: List<String> = emptyList(),
    val useCloudflareProxy: Boolean = false,
    val cloudflareToken: String = "",
    // Tailscale (100.x / MagicDNS) address of the SAME server, reached by plain
    // SSH over the system Tailscale VPN. Empty = not configured.
    val tailscaleHost: String = "",
    val transport: ServerTransport = ServerTransport.CLOUDFLARE
) {
    val displayAddress: String get() = "$username@$host${if (port != 22) ":$port" else ""}"

    /** True when a Tailscale path is configured and selectable. */
    val hasTailscale: Boolean get() = tailscaleHost.isNotBlank()

    /**
     * Return a copy reconfigured for [t] — the EFFECTIVE server handed to the
     * connection layer. TAILSCALE swaps in [tailscaleHost] over plain SSH (no CF
     * proxy); CLOUDFLARE keeps the server as configured. AUTO is resolved to a
     * concrete transport by the orchestrator (reachability probe) before calling
     * this, so it's treated as CLOUDFLARE here as a safe fallback.
     */
    fun forTransport(t: ServerTransport): SshServer = when (t) {
        ServerTransport.TAILSCALE ->
            if (hasTailscale) copy(host = tailscaleHost, useCloudflareProxy = false, cloudflareToken = "")
            else this
        else -> this
    }

    fun withRecentFolder(folder: String): SshServer {
        val updated = (listOf(folder) + recentFolders.filter { it != folder }).take(10)
        return copy(recentFolders = updated)
    }
}
