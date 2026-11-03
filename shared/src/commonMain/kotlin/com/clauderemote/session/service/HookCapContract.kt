package com.clauderemote.session.service

/**
 * CAP contract shared by the reader (reconcile) and the writer (hooks install).
 * Executable reference semantics; the shell side must agree with these functions.
 *
 * Claude Code reloads `settings.json` while it runs (an added or removed hook is live in
 * every running session within about a second), so capability does not depend on when a
 * process started.
 *
 * CAP(pane) = 1 iff BOTH:
 *  1. the `settings.json` of C's config dir (`CLAUDE_CONFIG_DIR` from `/proc/C/environ`,
 *     default `~/.claude`) carries the marker [HOOKEV_MARKER];
 *  2. neither that `settings.json` nor the `settings.local.json` next to it sets
 *     `disableAllHooks` ([combineDisableAllHooks]);
 *  3. the file `~/.claude-remote/hookev.off` does not exist (the owner's switch that makes
 *     the hook script write nothing).
 * CAP is a fact about the host. A consumer that trusts the hook stream must also multiply
 * it by `CommFlags.effectiveFor(serverId).hooks`: with hooks off for the server the watcher
 * does not read the events, whatever CAP says.
 * where C is the SHALLOWEST claude process in the process subtree of the pane's
 * `pane_pid`, the pane's own process included ([shallowestClaude]). A claude process has
 * comm exactly `claude`, or a bare version number (`2.1.316`, the name of the versioned
 * binary) whose `/proc/<pid>/exe` lies under `…/claude/versions/` ([Proc.isClaude]) — the
 * bundled `rg` carries the same comm but another exe. 0 = known not capable, null =
 * unknown (unknown must never be treated as 0 by anything that forgets or parks).
 *
 * **Marker file (audit only):** `<CLAUDE_CONFIG_DIR>/claude-remote-hookev.installed`
 * ([MARKER_FILE_NAME]) = `<epoch_s>\n`, ten digits ([parseInstalled]), written by the
 * installer AFTER the settings.json `os.replace`. It records when the hooks were
 * installed; it is NOT a condition of CAP (a claude started before the install is capable
 * as soon as settings.json carries the entries).
 *
 * Limits: project-level `.claude/settings*.json` in the working directory and managed
 * settings (`/etc/claude-code/`, which does not exist today) are not examined; a
 * `disableAllHooks` set there is not seen.
 */
object HookCapContract {
    const val MARKER_FILE_NAME = "claude-remote-hookev.installed"
    const val HOOKEV_MARKER = "claude-remote-ev v"

    sealed interface Installed {
        /** File does not exist: hooks never installed through this client. */
        data object Absent : Installed
        /** Exists but is not exactly ten digits (+ one newline) ⇒ unknown. */
        data object Corrupt : Installed
        data class Epoch(val seconds: Long) : Installed
    }

    /**
     * [text] = file content, null = file absent. Accepts exactly ten digits (an epoch
     * in seconds, 2001-09 to 2286-11) with a non-zero first digit, plus at most one
     * trailing newline. `0`, milliseconds (13 digits), empty, signs, spaces and
     * `\r\n` are [Installed.Corrupt]. Audit only, see the class doc.
     */
    fun parseInstalled(text: String?): Installed {
        if (text == null) return Installed.Absent
        val body = text.removeSuffix("\n")
        if (body.length != 10 || body[0] == '0' || !body.all { it in '0'..'9' }) return Installed.Corrupt
        return Installed.Epoch(body.toLong())
    }

    /** [exe] = target of `/proc/<pid>/exe`, null when unknown / not read. */
    data class Proc(val pid: Int, val ppid: Int, val comm: String, val exe: String? = null) {
        val isClaude: Boolean
            get() = comm == CLAUDE_COMM ||
                (VERSION_COMM.matches(comm) && exe != null && "/claude/versions/" in exe)
    }

    /**
     * Pid of the shallowest claude in the subtree of [panePid]: the pane's own
     * process counts as depth 0 (a pane that `exec`s claude), so `bash -l → bash -lc →
     * claude` yields the claude and so does a bare `claude` pane. See [Proc.isClaude]
     * (a helper called `claude-…` is not selected). Null when there is none, or when
     * several share the minimum depth (ambiguous).
     */
    fun shallowestClaude(panePid: Int, procs: List<Proc>): Int? {
        val byPid = procs.associateBy { it.pid }
        if (byPid[panePid]?.isClaude == true) return panePid
        val children = procs.groupBy { it.ppid }
        var level = listOf(panePid)
        val seen = mutableSetOf(panePid)
        while (level.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for (pid in level) for (c in children[pid].orEmpty()) if (seen.add(c.pid)) next += c.pid
            val claudes = next.filter { byPid.getValue(it).isClaude }
            if (claudes.isNotEmpty()) return claudes.singleOrNull()
            level = next
        }
        return null
    }

    private const val CLAUDE_COMM = "claude"
    private val VERSION_COMM = Regex("^[0-9]+(\\.[0-9]+)+$")

    /**
     * `disableAllHooks` of a config dir from its two settings files (each: true/false,
     * null = could not be read; a file that does not exist is false). Any true wins;
     * otherwise any unknown makes it unknown.
     */
    fun combineDisableAllHooks(settingsJson: Boolean?, settingsLocalJson: Boolean?): Boolean? = when {
        settingsJson == true || settingsLocalJson == true -> true
        settingsJson == null || settingsLocalJson == null -> null
        else -> false
    }

    /** Null inputs mean "could not determine". A definite failure yields false even if the other input is unknown. */
    fun verdict(settingsHasMarker: Boolean?, disableAllHooks: Boolean?, hooksOff: Boolean? = false): Boolean? {
        if (settingsHasMarker == false || disableAllHooks == true || hooksOff == true) return false
        if (settingsHasMarker == null || disableAllHooks == null || hooksOff == null) return null
        return true
    }
}
