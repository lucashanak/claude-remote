package com.jcraft.jsch

/**
 * An exec channel that runs its command in a LOCAL `sh -c` with [env] added to the environment, for unit tests of a
 * command's exit semantics without sshd. Stdout is the channel's input stream; stderr is dropped, as an exec read
 * (`execReadWithWatchdog`) drops the extended stream. [rewrite] may replace the command (e.g. answer a side command
 * with a fixed `echo`). Lives in jsch's package only because the constructor is package-private.
 */
class LocalExecChannel(
    private val env: Map<String, String>,
    private val rewrite: (String) -> String = { it },
) : ChannelExec() {
    @Volatile var command: String? = null
        private set
    private var process: Process? = null

    override fun setCommand(command: String) { this.command = command }
    override fun setInputStream(`in`: java.io.InputStream?) {}
    override fun getInputStream(): java.io.InputStream = launch().inputStream
    override fun connect(connectTimeout: Int) { launch() }
    override fun isConnected(): Boolean = process?.isAlive == true
    override fun disconnect() { process?.destroy() }

    @Synchronized private fun launch(): Process = process ?: ProcessBuilder("sh", "-c", rewrite(command ?: error("no command")))
        .apply { environment().putAll(env); redirectError(ProcessBuilder.Redirect.DISCARD) }
        .start().also { process = it }
}
