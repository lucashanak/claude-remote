package com.clauderemote.session.service

import com.clauderemote.connection.SshManager
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "SessionOrchestrator"

/**
 * Trailing-edge window for coalescing a burst of kickRedraw calls into one
 * server-side resize/refresh. A tab switch alone fires several kicks inside the
 * same second (replay kick + the Android view poke at 300/800ms + the resize
 * callback), and each used to issue its own `resize-window`.
 */
private const val REDRAW_COALESCE_MS = 150L

/**
 * Low-level tmux/shell probe + terminal-redraw primitives. Originally extracted
 * verbatim from SessionOrchestrator (same timings — the shrink-then-restore
 * delay(80), the 1500ms exec timeouts, waitForShellPrompt's poll loop — and the
 * same fail-open/fail-closed semantics). Since then kickRedraw coalesces bursts
 * and skips no-op resizes; see REDRAW_COALESCE_MS and singleClientPreamble for
 * why (tmux server SIGSEGVs under resize churn).
 */
internal class TmuxProbes(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val terminalIO: TerminalIOService,
) {
    /**
     * Nudge tmux to redraw a session's pane at [cols]x[rows] without disturbing
     * the input line. Preferred path: `tmux refresh-client` over an exec channel,
     * which repaints every attached client to the pane's current server-side
     * geometry. The SIGWINCH toggle is kept only as a fallback for when the
     * exec channel fails (connection mid-reconnect, tmux probe error).
     *
     * Called by the platform after it has laid out the terminal for the new
     * session at the current dimensions. Safe to call from the UI thread —
     * the SSH round-trip runs on [scope].
     */
    fun kickRedraw(sessionId: String, cols: Int, rows: Int) {
        val conn = registry.ssh(sessionId) ?: return
        if (cols <= 1 || rows <= 0) return
        FileLogger.log("TermGeom", "kickRedraw $sessionId requested ${cols}x${rows}")
        // Sync pty geometry to the current view first (no-op if unchanged) —
        // each session's pty keeps the size from the last time *it* was
        // active, which may be stale after switching between sessions.
        conn.resize(cols, rows)
        val tmuxName = tabManager.getTab(sessionId)?.tmuxSessionName
        // COALESCE the burst. Every kick used to run `resize-window` server
        // side, and continuous resize churn is the leading suspect for the
        // tmux 3.3a layout SIGSEGVs that kill the whole server (and with it
        // every session). Trailing edge only: the last kick of a burst carries
        // the final geometry, so replace any pending one with this.
        val kick = scope.launch {
            kotlinx.coroutines.delay(REDRAW_COALESCE_MS)
            // CURRENT-DEVICE-WINS sizing via tmux's native `window-size latest`
            // (the default; we never set `manual`). The `conn.resize(cols,rows)`
            // above is a channel SIGWINCH → tmux MSG_RESIZE, which promotes THIS
            // (active) client to the window's `latest` and sizes the window to
            // its pty — so the device the user is actually on wins, with NO
            // server-side `resize-window`. We deliberately dropped that call: it
            // implicitly flips the window to `window-size manual` (documented
            // tmux side-effect) and PINS it to one device, which is exactly why
            // a phone kept seeing a stale desktop width. A zombie/stale client
            // can only steal the size at the instant the active client drops
            // (tmux promotes a survivor), and the next attach's SIGWINCH self-
            // heals it — no manual pinning needed.
            val refreshed = tmuxName != null && refreshTmuxClient(conn, tmuxName)
            if (!refreshed) {
                // Fallback: SIGWINCH toggle. Shrink COLS, not ROWS — row
                // shrink pushes the tmux status line up a cell during the
                // kick and its bytes leak into scrollback as a stray
                // status-line artifact mid-history. Restore in `finally`: a
                // kick superseded mid-toggle (see the coalescing above) must
                // not leave the pty one column short.
                conn.resize(cols - 1, rows)
                try {
                    kotlinx.coroutines.delay(80)
                } finally {
                    conn.resize(cols, rows)
                }
            }
        }
        // Supersede: the pending kick (if any) is now stale — drop it.
        redrawJobs.put(sessionId, kick)?.cancel()
    }

    // Pending (debounced) redraw per session — see REDRAW_COALESCE_MS.
    private val redrawJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Session teardown: cancel a pending redraw. */
    fun dispose(sessionId: String, @Suppress("UNUSED_PARAMETER") tmuxName: String?) {
        redrawJobs.remove(sessionId)?.cancel()
    }

    /**
     * Shell preamble that keeps this DEVICE to at most ONE tmux client per
     * session, run immediately before `tmux attach-session`.
     *
     * Why: a dropped transport does NOT always take the server-side shell with
     * it — a half-open SSH-over-CF socket lives until sshd's ClientAlive gives
     * up, and Eternal Terminal deliberately keeps its shells alive across
     * drops. The old `tmux attach` in that shell therefore stays a live,
     * "focused" client while the reconnect attaches a second one, and the tmux
     * server ends up with two clients of DIFFERENT sizes on one session, each
     * fighting over its layout (measured on the live box: /dev/pts/8 and
     * /dev/pts/34 both attached and focused on the same session). That is the
     * resize churn we think crashes tmux 3.3a.
     *
     * How: every attach records its own tty in
     * `~/.claude-remote/clients/<deviceKey>-<session>`; the next attach reads
     * that file and detaches THAT ONE client (`detach-client -t <tty>`, never
     * `-a`) if it is still attached to this session. Keyed by [deviceKey]
     * (AppSettings.installId — stable across app restarts), so another device's
     * client is never touched: intentional multi-device attach keeps working,
     * one client each.
     *
     * Note `-t '=name'`: plain `-t` prefix-matches, which would list/detach
     * clients of a differently-named session that merely starts with ours.
     */
    fun singleClientPreamble(tmuxName: String, deviceKey: String): String {
        val escaped = tmuxName.replace("'", "'\\''")
        // Marker path component: keep it filesystem-safe and bounded, but still
        // 1:1 with (device, tmux session) so two tabs can't share a marker.
        // Truncate each part separately so a long tmux name can never eat the
        // device part (which is what keeps devices from sharing a marker).
        val key = (deviceKey.take(40) + "-" + tmuxName.takeLast(120))
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "CRD=\"\$HOME/.claude-remote/clients\"; mkdir -p \"\$CRD\" 2>/dev/null; " +
            "CRF=\"\$CRD/$key\"; CRT=\$(cat \"\$CRF\" 2>/dev/null); " +
            "[ -n \"\$CRT\" ] && tmux list-clients -t '=$escaped' -F '#{client_tty}' 2>/dev/null " +
            "| grep -qxF -- \"\$CRT\" && tmux detach-client -t \"\$CRT\" 2>/dev/null; " +
            // Record OUR tty for the next attach. `case /dev/*`: a shell with no
            // controlling terminal makes `tty` print "not a tty" on stdout and
            // exit 1, which would otherwise be stored as the marker.
            "CRT=\$(tty 2>/dev/null); case \"\$CRT\" in /dev/*) printf '%s' \"\$CRT\" > \"\$CRF\" 2>/dev/null;; esac; "
    }

    /**
     * Run `tmux refresh-client` for every client attached to [tmuxName] via a
     * short-lived exec channel. Returns true only if at least one client was
     * actually refreshed (the command echoes OK per successful refresh).
     */
    private suspend fun refreshTmuxClient(conn: SshManager, tmuxName: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sshSession = conn.getSession() ?: return@withContext false
                val escaped = tmuxName.replace("'", "'\\''")
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(
                    "tmux list-clients -t '=$escaped' -F '#{client_name}' 2>/dev/null" +
                        " | while IFS= read -r c; do tmux refresh-client -t \"\$c\" 2>/dev/null && echo OK; done"
                )
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(1500) // keep snappy on cell links; fallback toggle covers failure
                val out = input.bufferedReader().readText()
                ch.disconnect()
                out.contains("OK")
            } catch (e: Exception) {
                FileLogger.log(TAG, "refresh-client failed for $tmuxName: ${e.message}")
                false
            }
        }

    /**
     * Read-only geometry probe (TermGeom): logs tmux's actual pane geometry and
     * the window-size option for [tmuxName] against the [cols]x[rows] the client
     * requested, and returns the window's CURRENT width x height (null if the
     * probe or the parse fails). kickRedraw uses
     * the return value to skip a `resize-window` that would be a no-op — the
     * window is what we'd pin, so comparing it to the request is exact. Changes
     * no tmux state.
     */
    /**
     * Synchronous tmux session existence probe via SSH exec channel.
     * Returns true if `tmux has-session -t=<name>` exits 0. Returns true on
     * exec failure too (fail-open: fall through to attach which will create
     * via -A if needed — old behavior).
     *
     * EXACT target (`-t '=name'`): plain `-t` falls back to prefix matching, so
     * `has-session` answered YES for a DEAD session whenever some other session
     * merely started with the same name (`…--cashy` vs `…--cashy-2`) — and the
     * attach that followed landed the user in the wrong conversation instead of
     * rebuilding theirs.
     */
    fun probeTmuxSession(sshManager: SshManager, sessionName: String): Boolean {
        return try {
            val sshSession = sshManager.getSession() ?: return true
            val escaped = sessionName.replace("'", "'\\''")
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand("tmux has-session -t '=$escaped' 2>/dev/null && echo YES || echo NO")
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500) // fail-open probe — keep snappy on cell links
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            out.endsWith("YES")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Tmux probe failed for $sessionName: ${e.message}", e)
            true // fail-open
        }
    }

    /**
     * Probe whether a Claude Code transcript file exists for the given UUID
     * in the encoded form of [folder]. Used to decide whether `--resume <uuid>`
     * will succeed or whether we need to launch fresh with `--session-id <uuid>`.
     *
     * Encoding: `~` is expanded to `$HOME`, relative folders are anchored at
     * `$HOME`, then every `/` becomes `-` (matches Claude Code's on-disk layout
     * under `~/.claude/projects/`).
     *
     * Fail-closed (returns false on probe error) so we don't try a `--resume`
     * that we can't verify — it's safer to start fresh than to crash with
     * "No conversation found".
     */
    fun probeTranscriptExists(sshManager: SshManager, folder: String, uuid: String): Boolean {
        return try {
            val sshSession = sshManager.getSession() ?: return false
            val escapedFolder = folder.replace("'", "'\\''")
            val cmd = """
                F='$escapedFolder'
                E="${'$'}{F/#~/${'$'}HOME}"
                case "${'$'}E" in /*) ;; *) E="${'$'}HOME/${'$'}E";; esac
                # UUID is globally unique — a transcript matching it anywhere means
                # it exists, immune to the lossy cwd->dir encoding. Fall back to the
                # corrected encoding (every non-alphanumeric -> '-') for a not-yet-
                # globbable path.
                ENC=${'$'}(echo "${'$'}E" | sed 's|[^a-zA-Z0-9]|-|g')
                if ls "${'$'}HOME/.claude/projects/"*/"$uuid.jsonl" >/dev/null 2>&1 || [ -f "${'$'}HOME/.claude/projects/${'$'}ENC/$uuid.jsonl" ]; then echo YES; else echo NO; fi
            """.trimIndent()
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand(cmd)
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500)
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            out.endsWith("YES")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Transcript probe failed for $uuid in $folder: ${e.message}", e)
            false
        }
    }

    /**
     * Wait for shell prompt by watching output buffer for prompt chars ($ # > %).
     * Returns as soon as prompt detected or after maxWait ms.
     */
    suspend fun waitForShellPrompt(sessionId: String, maxWait: Long) {
        val start = System.currentTimeMillis()
        val promptChars = setOf('$', '#', '>', '%')
        while (System.currentTimeMillis() - start < maxWait) {
            val lastLine = terminalIO.lastLine(sessionId)
            if (lastLine.isNotEmpty() && lastLine.any { it in promptChars }) return
            kotlinx.coroutines.delay(50)
        }
    }
}
