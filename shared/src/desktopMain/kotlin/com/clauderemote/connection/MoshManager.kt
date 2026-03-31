package com.clauderemote.connection

import com.clauderemote.model.SshServer
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

    actual val isConnected: Boolean get() = process?.isAlive == true

    actual suspend fun connect(
        server: SshServer,
        startupCommand: String,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // On desktop, use system mosh command directly
            val cmd = mutableListOf("mosh", "${server.username}@${server.host}")
            if (server.port != 22) {
                cmd.addAll(listOf("--ssh=ssh -p ${server.port}"))
            }
            // Pass startup command via mosh's -- separator
            cmd.addAll(listOf("--", startupCommand))

            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc

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
        // Desktop mosh handles resize through the terminal
    }

    actual suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        process?.destroyForcibly()
        process = null
    }
}
