package com.clauderemote.connection

import com.clauderemote.model.TmuxSession
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TmuxManager {

    /**
     * Check if tmux is installed on the remote server.
     */
    suspend fun isInstalled(session: Session): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = execCommand(session, "which tmux")
            output.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * List existing tmux sessions on the remote server.
     */
    suspend fun listSessions(session: Session): List<TmuxSession> = withContext(Dispatchers.IO) {
        try {
            // `-u`: exec channels carry no locale, and a non-UTF-8 tmux client gets
            // every non-ASCII character in a session name replaced with '_'. See the
            // long note in SessionOrchestrator.serverHasOtherLiveSession.
            val output = execCommand(session, "tmux -u list-sessions -F '#{session_name}|#{session_windows}|#{session_attached}|#{session_created}|#{session_activity}' 2>/dev/null")
            if (output.isBlank()) return@withContext emptyList()

            output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split("|")
                // Still `>= 4`: the activity field is additive, so a server
                // whose tmux doesn't emit it (or a line from an older format)
                // yields a session with lastActivity = 0 instead of vanishing
                // from the list entirely.
                if (parts.size >= 4) {
                    TmuxSession(
                        name = parts[0],
                        windows = parts[1].toIntOrNull() ?: 0,
                        attached = parts[2] == "1",
                        created = parts[3],
                        lastActivity = parts.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L,
                    )
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * What a FRESH launch must not be named (V15): the server's tmux sessions and the names in its restore manifest
     * (`sessions.json`: a session another device runs that is not back yet after a reboot, R2). Unlike [listSessions]
     * a failure THROWS — an exec that did not run to its end never reads as "no sessions" (M2). No tmux server is an
     * answer (no sessions); an unreadable manifest only drops the manifest's names. The read is interruptible, so the
     * caller's timeout really ends it (K4-2); the channel's OPEN is not (K5-1, see [listForFreshName]). The server ends
     * the listing itself within [LISTING_SERVER_LIMIT_S] s (K5-2), so a hung tmux or file read never keeps sshd's session
     * once the client has given its slot back; a listing the server cut short THROWS too.
     */
    data class FreshNameListing(val sessions: List<TmuxSession>, val manifestNames: Set<String>) {
        val names: Set<String> get() = sessions.map { it.name }.toSet() + manifestNames
    }

    internal const val LISTING_END = "__CR_LISTED__"
    /** Last line of the listing: the exit status of the server-side limit around it (124 = the server cut it short). */
    internal const val LISTING_RC = "__CR_LISTING_RC__"
    /**
     * K5-2: the server's own limit on the whole listing, shorter than the client's (quick connect 3 s, Connect screen
     * 6 s). `timeout` signals its process group (the tmux CLIENT and `cat`), never the tmux server.
     */
    internal const val LISTING_SERVER_LIMIT_S = 2

    internal fun freshNameListingCommand(): String =
        "timeout $LISTING_SERVER_LIMIT_S sh -c \"tmux -u list-sessions -F '$SESSION_FORMAT' 2>/dev/null; echo \\\"$LISTING_END \\\$?\\\"; " +
            "cat \\\"\\\$HOME/.claude-remote/sessions.json\\\" 2>/dev/null; true\"; echo \"$LISTING_RC \$?\""

    /**
     * Parses [freshNameListingCommand]'s output; throws when the end-of-listing line or the final status line is missing
     * (the exec was cut short) or the server's limit cut the listing short (status 124).
     */
    internal fun parseFreshNameListing(output: String): FreshNameListing {
        val lines = output.lines()
        val rcAt = lines.indexOfLast { it.startsWith("$LISTING_RC ") }
        if (rcAt < 0) throw java.io.IOException("tmux listing cut short (no status line)")
        val rc = lines[rcAt].removePrefix("$LISTING_RC ").trim().toIntOrNull()
        if (rc != 0) throw java.io.IOException("tmux listing ended by the server's limit or failed (status ${rc ?: "unknown"})")
        val end = lines.indexOfFirst { it.startsWith("$LISTING_END ") }
        if (end < 0 || end > rcAt) throw java.io.IOException("tmux listing cut short (no end line)")
        val sessions = parseSessionLines(lines.take(end))
        val manifest = lines.subList(end + 1, rcAt).joinToString("\n").trim()
        val manifestNames = if (manifest.isEmpty()) emptySet() else try {
            kotlinx.serialization.json.Json.parseToJsonElement(manifest).let { it as? kotlinx.serialization.json.JsonArray }
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("tmuxSessionName")?.let { n -> (n as? kotlinx.serialization.json.JsonPrimitive)?.content } }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        return FreshNameListing(sessions, manifestNames)
    }

    suspend fun listForFreshName(session: Session): FreshNameListing = withContext(Dispatchers.IO) {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(freshNameListingCommand())
        channel.inputStream = null
        val input = channel.inputStream
        try {
            // K5-1: the OPEN is never interrupted. jsch gives up a channel interrupted before the server confirmed it
            // without sending CLOSE (it has no recipient yet), so a late confirmation left sshd a session nobody ever
            // closed: one MaxSessions slot gone for the life of the transport. Opened, the channel is closed below.
            withContext(kotlinx.coroutines.NonCancellable) { channel.connect(5000) }
            parseFreshNameListing(kotlinx.coroutines.runInterruptible { input.bufferedReader().readText() })
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
        }
    }

    private const val SESSION_FORMAT = "#{session_name}|#{session_windows}|#{session_attached}|#{session_created}|#{session_activity}"

    private fun parseSessionLines(lines: List<String>): List<TmuxSession> =
        lines.filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 4) {
                TmuxSession(
                    name = parts[0],
                    windows = parts[1].toIntOrNull() ?: 0,
                    attached = parts[2] == "1",
                    created = parts[3],
                    lastActivity = parts.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L,
                )
            } else null
        }

    /**
     * Build the tmux attach/create command.
     */
    fun buildAttachCommand(sessionName: String): String {
        // set-clipboard on + terminal-features clipboard → tmux sends the selected
        // text back to the terminal via OSC 52 after a mouse-drag selection, which
        // both the desktop app (custom interceptor) and Termux on Android honor.
        val escapedName = sessionName.replace("'", "'\\''")
        return "tmux new-session -A -s '$escapedName' \\; " +
            "set-option -g mouse on \\; " +
            "set-option -g set-clipboard on \\; " +
            "set-option -g history-limit 100000 \\; " +
            "set-option -ga terminal-features ',*:clipboard'"
    }

    /**
     * Rename a tmux session (blocking, call from IO thread).
     */
    fun renameSession(session: Session, oldName: String, newName: String) {
        // `=` forces an EXACT target match. Plain `-t name` prefix-matches, so
        // renaming `x` while only `x-2` existed renamed the WRONG session — and
        // the app then lost track of both. Measured on tmux 3.5a.
        execCommand(session, "tmux rename-session -t '=${oldName.replace("'", "'\\''")}' '${newName.replace("'", "'\\''")}'")
    }

    /**
     * Kill a tmux session.
     *
     * Records a tombstone FIRST, so no caller can kill a pane without the
     * close being visible to the other devices. The pane's death is what every
     * other device reacts to — its attach drops and auto-reconnect fires within
     * a second — and a peer that finds no tombstone rebuilds the pane with
     * `claude --resume`, which is how a killed session came back a moment later.
     */
    suspend fun killSession(session: Session, sessionName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            try {
                execCommand(
                    session,
                    com.clauderemote.session.service.ManifestCommands.tombstone(sessionName, durable = false),
                )
            } catch (e: Exception) {
                // Best-effort: a missing tombstone only costs us the race guard,
                // it must never block the kill the user asked for.
                com.clauderemote.util.FileLogger.error(
                    "TmuxManager", "tombstone before kill failed for $sessionName: ${e.message}", e
                )
            }
            // `-t '=name'`: plain `-t` prefix-matches, so killing "proj--cashy"
            // would also hit a live "proj--cashy-2" (measured on tmux 3.5a).
            // `=` forces an exact-name match for a target-session.
            execCommand(session, "tmux kill-session -t '=${sessionName.replace("'", "'\\''")}'")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * List folders in a remote directory.
     */
    suspend fun listFolders(session: Session, path: String = "~"): List<String> = withContext(Dispatchers.IO) {
        try {
            val expandedPath = if (path == "~") "\$HOME" else path
            val output = execCommand(session, "ls -1d $expandedPath/*/ 2>/dev/null | head -50")
            output.lines().filter { it.isNotBlank() }.map { it.trimEnd('/') }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Check if a remote folder exists, create if requested.
     */
    suspend fun ensureFolder(session: Session, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execCommand(session, "mkdir -p '${path.replace("'", "'\\''")}' && echo OK")
            result.trim() == "OK"
        } catch (e: Exception) {
            false
        }
    }

    private fun execCommand(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.inputStream = null
        val input = channel.inputStream
        try {
            channel.connect(5000)
            return input.bufferedReader().readText()
        } finally {
            // also when the read is interrupted (listForFreshName's timeout) or throws: never leak the channel
            try { channel.disconnect() } catch (_: Exception) {}
        }
    }
}
