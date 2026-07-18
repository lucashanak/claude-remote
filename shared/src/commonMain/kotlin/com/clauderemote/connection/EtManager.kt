package com.clauderemote.connection

/**
 * Platform-specific Eternal Terminal (ET) client runner.
 *   Android: the NDK-cross-compiled `et` client bundled as libet.so (built by
 *            build-et.sh, packaged in jniLibs, resolved via [init]).
 *   Desktop: the system-installed `et` binary.
 *
 * Unlike [MoshManager], ET does NOT do its own SSH bootstrap here: the app runs
 * `etterminal` over its existing in-process SSH-over-Cloudflare channel, parses
 * the IDPASSKEY, and forwards a local port to etserver:2022 over that same
 * tunnel. This runner just launches the (patched) client with `--idpasskey`
 * pointed at the local forward endpoint. ET then resumes the session over TCP
 * across a transport drop+rebuild — so a Starlink egress-IP change that kills
 * the CF WebSocket becomes a seamless replay instead of a full tmux redraw.
 *
 * The bootstrap + port-forward live in the orchestrator (they need the live
 * JSch session); this class is intentionally a thin process wrapper, mirroring
 * [MoshManager] so the terminal I/O plumbing is identical.
 */
expect class EtManager() {

    /**
     * Launch the ET client against an already-bootstrapped session.
     *
     * @param idpasskey the `<id>/<passkey>` parsed from etterminal's IDPASSKEY
     * @param host      the local forward host (normally 127.0.0.1)
     * @param port      the local forwarded port that tunnels to etserver:2022
     * @param startupCommand fed to the ET shell once attached (e.g. tmux attach)
     */
    suspend fun connect(
        idpasskey: String,
        host: String,
        port: Int,
        cols: Int,
        rows: Int,
        startupCommand: String,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Boolean

    fun sendInput(data: String)
    fun sendBytes(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    suspend fun disconnect()
    val isConnected: Boolean
}
