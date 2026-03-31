package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual class MoshManager {
    private var process: Process? = null
    private var readJob: Job? = null

    actual val isConnected: Boolean get() = process != null

    actual suspend fun connect(
        server: SshServer,
        startupCommand: String,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Step 1: SSH to get MOSH_CONNECT info
            val jsch = JSch()
            if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
                jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
            }
            val sshSession = jsch.getSession(server.username, server.host, server.port)
            if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
                sshSession.setPassword(server.password)
            }
            sshSession.setConfig("StrictHostKeyChecking", "no")
            sshSession.connect(15000)

            val channel = sshSession.openChannel("exec") as ChannelExec
            channel.setCommand("mosh-server new 2>&1")
            channel.inputStream = null
            val input = channel.inputStream
            channel.connect(10000)
            val moshOutput = input.bufferedReader().readText()
            channel.disconnect()
            sshSession.disconnect()

            // Parse MOSH CONNECT <port> <key>
            val connectLine = moshOutput.lines().find { it.startsWith("MOSH CONNECT") }
                ?: return@withContext false
            val parts = connectLine.split(" ")
            if (parts.size < 4) return@withContext false
            val moshPort = parts[2]
            val moshKey = parts[3]

            // Step 2: Launch mosh-client binary
            val moshBinary = moshBinaryPath ?: "mosh-client"
            val pb = ProcessBuilder(moshBinary, server.host, moshPort)
            pb.environment()["MOSH_KEY"] = moshKey
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc

            // Read loop in separate scope
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            readJob = scope.launch {
                val buffer = ByteArray(8192)
                try {
                    while (isActive) {
                        val len = proc.inputStream.read(buffer)
                        if (len < 0) break
                        onOutput(String(buffer, 0, len, Charsets.UTF_8))
                    }
                } catch (_: Exception) {}
                onDisconnect()
            }

            // Send startup command
            proc.outputStream.write("$startupCommand\n".toByteArray())
            proc.outputStream.flush()

            true
        } catch (e: Exception) {
            false
        }
    }

    actual fun sendInput(data: String) {
        try {
            process?.outputStream?.write(data.toByteArray(Charsets.UTF_8))
            process?.outputStream?.flush()
        } catch (_: Exception) {}
    }

    actual fun sendBytes(data: ByteArray) {
        try {
            process?.outputStream?.write(data)
            process?.outputStream?.flush()
        } catch (_: Exception) {}
    }

    actual fun resize(cols: Int, rows: Int) {
        // Mosh handles resize via its own protocol
    }

    actual suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        process?.destroy()
        process = null
    }

    companion object {
        var moshBinaryPath: String? = null
    }
}
