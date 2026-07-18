package com.clauderemote.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop ET runner — uses the system `et` binary. NOTE: --idpasskey is a
 * claude-remote patch (see patches/et-idpasskey.patch), so this needs the
 * patched client on PATH; a stock distro `et` will reject the flag. Bundling a
 * patched desktop `et` is a follow-up (desktop is lower priority than mobile,
 * where the Starlink drops actually hurt).
 */
actual class EtManager {
    private var process: Process? = null
    private var readJob: Job? = null

    actual val isConnected: Boolean get() = process?.isAlive == true

    actual suspend fun connect(
        idpasskey: String,
        host: String,
        port: Int,
        startupCommand: String,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder(
                "et",
                "--idpasskey", idpasskey,
                "--host", host,
                "--port", port.toString(),
                "--silent"
            )
            pb.environment()["TERM"] = "xterm-256color"
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

            if (startupCommand.isNotEmpty()) {
                proc.outputStream.write("$startupCommand\n".toByteArray())
                proc.outputStream.flush()
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
        // System et manages its own SIGWINCH via the controlling TTY.
    }

    actual suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        process?.destroyForcibly()
        process = null
    }
}
