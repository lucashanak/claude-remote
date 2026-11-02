package com.clauderemote.session.service

/**
 * CAP contract shared by the reader (reconcile) and the writer (hooks install).
 * Executable reference semantics; the shell side must agree with these functions.
 *
 * **Marker file, per config dir:** `<CLAUDE_CONFIG_DIR>/claude-remote-hookev.installed`
 * ([MARKER_FILE_NAME]; `~/.claude` when the variable is unset) = `<epoch_s>\n`,
 * ten digits ([parseInstalled]). One marker per config dir, because a box has several
 * (`~/.claude` and one per account) and an install into one must not make claudes of
 * another look capable.
 *
 * **Write order (writer):** write the settings.json entries first and `os.replace`
 * them into place, and only THEN write the marker. A crash in between leaves the new
 * hooks without a (new) marker, which reads as "not capable" (the safe answer), never
 * the other way round.
 *
 * CAP(pane) = 1 iff ALL of:
 *  1. start time of C  >  the installed epoch of C's config dir (strictly greater);
 *  2. the settings.json of C's config dir (`CLAUDE_CONFIG_DIR` from `/proc/C/environ`,
 *     default `~/.claude`) carries the marker [HOOKEV_MARKER];
 *  3. neither that `settings.json` nor the `settings.local.json` next to it sets
 *     `disableAllHooks` ([combineDisableAllHooks]).
 * where C is the SHALLOWEST process with comm exactly `claude` in the process subtree
 * of the pane's `pane_pid`, the pane's own process included ([shallowestClaude]).
 * 0 = known not capable, null = unknown (unknown must never be treated as 0 by
 * anything that forgets or parks).
 *
 * Limits: project-level `.claude/settings*.json` in the working directory and managed
 * settings (`/etc/claude-code/`, which does not exist today) are not examined; a
 * `disableAllHooks` set there is not seen. Start times come from `/proc` in whole
 * seconds, so a claude started in the same second as the install is judged "not
 * capable" — the strict `>` errs on the safe side by up to one second.
 * Hot-reload branch ("CAP=1 whenever hooks are installed") is [HOT_RELOAD_BRANCH]
 * = off; enable it only if hot reload of settings is proven.
 */
object HookCapContract {
    const val MARKER_FILE_NAME = "claude-remote-hookev.installed"
    const val HOOKEV_MARKER = "claude-remote-ev v"
    const val HOT_RELOAD_BRANCH = false

    sealed interface Installed {
        /** File does not exist: hooks never installed ⇒ CAP 0. */
        data object Absent : Installed
        /** Exists but is not exactly ten digits (+ one newline) ⇒ unknown. */
        data object Corrupt : Installed
        data class Epoch(val seconds: Long) : Installed
    }

    /**
     * [text] = file content, null = file absent. Accepts exactly ten digits (an epoch
     * in seconds, 2001-09 to 2286-11) with a non-zero first digit, plus at most one
     * trailing newline. `0`, milliseconds (13 digits), empty, signs, spaces and
     * `\r\n` are [Installed.Corrupt] — unknown, never a verdict.
     */
    fun parseInstalled(text: String?): Installed {
        if (text == null) return Installed.Absent
        val body = text.removeSuffix("\n")
        if (body.length != 10 || body[0] == '0' || !body.all { it in '0'..'9' }) return Installed.Corrupt
        return Installed.Epoch(body.toLong())
    }

    data class Proc(val pid: Int, val ppid: Int, val comm: String)

    /**
     * Pid of the shallowest `claude` in the subtree of [panePid]: the pane's own
     * process counts as depth 0 (a pane that `exec`s claude), so `bash -l → bash -lc →
     * claude` yields the claude and so does a bare `claude` pane. comm must equal
     * `claude` exactly (a helper called `claude-…` is not selected). Null when there is
     * none, or when several share the minimum depth (ambiguous).
     */
    fun shallowestClaude(panePid: Int, procs: List<Proc>): Int? {
        val byPid = procs.associateBy { it.pid }
        if (byPid[panePid]?.comm == CLAUDE_COMM) return panePid
        val children = procs.groupBy { it.ppid }
        var level = listOf(panePid)
        val seen = mutableSetOf(panePid)
        while (level.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for (pid in level) for (c in children[pid].orEmpty()) if (seen.add(c.pid)) next += c.pid
            val claudes = next.filter { byPid.getValue(it).comm == CLAUDE_COMM }
            if (claudes.isNotEmpty()) return claudes.singleOrNull()
            level = next
        }
        return null
    }

    private const val CLAUDE_COMM = "claude"

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

    /** Null inputs mean "could not determine". Any definite failure yields false even if another input is unknown. */
    fun verdict(
        claudeStartEpochS: Long?,
        installed: Installed,
        settingsHasMarker: Boolean?,
        disableAllHooks: Boolean?,
    ): Boolean? {
        if (installed is Installed.Absent) return false
        if (settingsHasMarker == false || disableAllHooks == true) return false
        val inst = (installed as? Installed.Epoch)?.seconds
        if (inst != null && claudeStartEpochS != null && claudeStartEpochS <= inst) return false
        if (inst == null || claudeStartEpochS == null || settingsHasMarker == null || disableAllHooks == null) return null
        return true
    }
}
