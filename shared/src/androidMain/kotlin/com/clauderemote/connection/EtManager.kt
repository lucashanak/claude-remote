package com.clauderemote.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val etBinary = etBinaryPath ?: return@withContext false
            // Patched client: --idpasskey skips ssh bootstrap (the app already
            // ran etterminal over its SSH-over-CF channel). --silent avoids the
            // GetTempDirectory() log file, which may be unwritable on Android.
            //
            // CAVEAT (validate on-device): the ET client manipulates the local
            // terminal (tcgetattr/raw mode, SIGWINCH). Under a plain pipe-based
            // Process it has no controlling TTY; mosh-client tolerates this, but
            // ET may need a PTY. If so, launch it through a PTY wrapper and drive
            // resize() via TIOCSWINSZ instead of the no-op below.
            val pb = ProcessBuilder(
                etBinary,
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
        // ET tracks the terminal size via SIGWINCH on its controlling TTY.
        // Without a PTY there is no winsize to set; see the CAVEAT in connect().
    }

    actual suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        process?.destroy()
        process = null
    }

    companion object {
        var etBinaryPath: String? = null

        /** Initialize from Android Context — finds the bundled et client. */
        fun init(context: android.content.Context) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binary = java.io.File(nativeLibDir, "libet.so")
            if (binary.exists() && binary.canExecute()) {
                etBinaryPath = binary.absolutePath
            }
        }
    }
}
