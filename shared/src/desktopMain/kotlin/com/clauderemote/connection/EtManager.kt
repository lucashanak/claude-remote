package com.clauderemote.connection

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop ET runner. Uses the patched `et` client bundled with the app (built
 * by build-et-desktop.sh, packaged as a classpath resource under bin/), NOT a
 * system `et` — stock distro builds lack the --idpasskey / --pty patches. The
 * binary is extracted to a cache dir and marked executable on first use.
 */
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
            val etBinary = resolveBinary()
            if (etBinary == null) {
                FileLogger.error("EtManager", "no bundled et client for this platform — skipping ET")
                return@withContext false
            }
            val pb = ProcessBuilder(
                etBinary,
                "--pty",
                "--pty-cols", cols.coerceAtLeast(1).toString(),
                "--pty-rows", rows.coerceAtLeast(1).toString(),
                "--logdir", logDir(),
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
        // In-band resize sentinel understood by the --pty wrapper (see
        // patches/et-client.patch): ESC _ C R <cols>;<rows> BEL → TIOCSWINSZ.
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
        process?.destroyForcibly()
        process = null
    }

    companion object {
        @Volatile private var extractedPath: String? = null

        /** The bundled et resource name for this OS/arch, or null if unsupported. */
        private fun resourceName(): String? {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            return when {
                os.contains("linux") -> "bin/et-linux-x64"
                os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm")) -> "bin/et-macos-arm64"
                os.contains("mac") -> "bin/et-macos-x64"
                else -> null
            }
        }

        /** Extract the bundled et client to a stable cache dir + chmod +x. */
        private fun resolveBinary(): String? {
            extractedPath?.let { if (File(it).canExecute()) return it }
            val res = resourceName() ?: return null
            val stream = EtManager::class.java.classLoader.getResourceAsStream(res) ?: run {
                FileLogger.error("EtManager", "bundled et resource missing: $res")
                return null
            }
            val dir = File(System.getProperty("user.home"), ".cache/claude-remote/et").apply { mkdirs() }
            val out = File(dir, res.substringAfterLast('/'))
            try {
                stream.use { input -> out.outputStream().use { input.copyTo(it) } }
                out.setExecutable(true, false)
            } catch (e: Exception) {
                FileLogger.error("EtManager", "failed to extract et client", e)
                return null
            }
            extractedPath = out.absolutePath
            FileLogger.log("EtManager", "et client ready at ${out.absolutePath}")
            return extractedPath
        }

        private fun logDir(): String =
            File(System.getProperty("java.io.tmpdir"), "claude-remote-et").apply { mkdirs() }.absolutePath
    }
}
