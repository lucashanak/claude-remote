package com.clauderemote.connection

import com.clauderemote.util.FileLogger
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
        cols: Int,
        rows: Int,
        startupCommand: String,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val etBinary = etBinaryPath
            if (etBinary == null) {
                FileLogger.error("EtManager", "et binary not resolved (libet.so missing from nativeLibraryDir — extractNativeLibs?)")
                return@withContext false
            }
            // Patched client (patches/et-client.patch):
            //  --idpasskey skips the ssh bootstrap (the app already ran
            //    etterminal over its SSH-over-CF channel);
            //  --pty makes the client forkpty itself so it runs with a real
            //    controlling TTY under this plain pipe-based Process — ET needs
            //    a PTY for input forwarding + a valid window size, else the
            //    remote shell renders into a 0x0 terminal and loops redrawing.
            //  --silent avoids the GetTempDirectory() log file (unwritable path
            //    on Android).
            val logDir = etLogDir ?: run {
                FileLogger.error("EtManager", "no writable et log dir — skipping ET")
                return@withContext false
            }
            val pb = ProcessBuilder(
                etBinary,
                "--pty",
                "--pty-cols", cols.coerceAtLeast(1).toString(),
                "--pty-rows", rows.coerceAtLeast(1).toString(),
                "--logdir", logDir,
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
            FileLogger.log("EtManager", "et client started (pty ${cols}x${rows}, port $port)")
            true
        } catch (e: Exception) {
            FileLogger.error("EtManager", "et client failed to start", e)
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
        // In-band resize sentinel understood by the --pty wrapper: it strips
        // ESC _ C R <cols>;<rows> BEL from the input stream and applies
        // TIOCSWINSZ to the PTY master (which ET then forwards to the server).
        if (cols < 1 || rows < 1) return
        try {
            process?.outputStream?.apply {
                write("_CR$cols;$rows".toByteArray(Charsets.US_ASCII))
                flush()
            }
        } catch (_: Exception) {}
    }

    actual suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        process?.destroy()
        process = null
    }

    companion object {
        var etBinaryPath: String? = null
        // App-private, always-writable dir for et's log file. Without --logdir
        // et defaults to /data/local/tmp (the _PATH_TMP shim), which an app
        // cannot write → the client FATAL-aborts with EACCES before connecting.
        var etLogDir: String? = null

        /** Initialize from Android Context — finds the bundled et client. */
        fun init(context: android.content.Context) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binary = java.io.File(nativeLibDir, "libet.so")
            etBinaryPath = if (binary.exists() && binary.canExecute()) binary.absolutePath else null
            etLogDir = java.io.File(context.cacheDir, "et").apply {
                // Clear stale (empty, --silent) per-launch log files from earlier runs.
                listFiles()?.forEach { it.delete() }
                mkdirs()
            }.absolutePath
            FileLogger.log("EtManager", "init: libet.so exists=${binary.exists()} canExec=${binary.canExecute()} dir=$nativeLibDir logdir=$etLogDir")
        }
    }
}
