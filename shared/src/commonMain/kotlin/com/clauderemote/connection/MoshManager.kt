package com.clauderemote.connection

import com.clauderemote.model.SshServer

/**
 * Platform-specific Mosh implementation.
 * Android: uses NDK-compiled mosh-client binary via PtyProcess.
 * Desktop: uses system mosh-client binary via ProcessBuilder.
 */
expect class MoshManager() {

    /**
     * Connect via Mosh: first SSH to get MOSH_CONNECT, then launch mosh-client.
     */
    suspend fun connect(
        server: SshServer,
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
