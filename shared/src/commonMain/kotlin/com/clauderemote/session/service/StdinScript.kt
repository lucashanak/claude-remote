package com.clauderemote.session.service

/**
 * D3: a script that goes on the exec channel's stdin behind a short exec request ([EXEC], ~240 B) instead of being the
 * request itself — the repo's rule "nothing over 4 KB as an exec argument" (`ExecArgumentSizeTest`). It is the shape
 * of the restore installer's C0 launcher ([SessionPersistenceService.INSTALL_RESTORE_EXEC], dávka C) for any body,
 * with the body's exit status added as a marker line, and it is sent the C0 way: paced stdin
 * (`execRun(…, stdin = …, paceStdin = Pace())`, or `PooledRemoteExec(UngatedExecChannels { session })` on a session
 * of its own).
 *
 * Fail-closed: [EXEC] reads the whole stdin (`s=$(cat)`) and runs it only when its last line is [END]; a stdin cut
 * short anywhere (a transport lost midway closes it early) runs NOTHING and prints [TRUNCATED] (exit 97). The body runs
 * in a subshell with `</dev/null` (an `exit` inside it still reaches the marker; nothing reads the rest of the stream),
 * then [MARK]`<status>` is printed on a line of its own and the exec exits with the body's status. The body is the
 * builder's text unchanged — variables on its first line as the builders write them, no positional arguments — run by
 * bash (`eval`), as before on a server whose login shell is bash.
 *
 * Output without the marker — cut off, [TRUNCATED], a shell that never got that far — is [Outcome.Incomplete], never a
 * success; each caller decides what that means for it (a close falls back, the close check reads it as unknown, a
 * restore as Unknown, the `cr-session` install as failed).
 *
 * Never for a command that reads its own stdin (`ManifestCommands.pushMerge` reads the manifest from it): the body's
 * stdin is `/dev/null`. Its sends are interactive, never `transfer = true` (they may take the reserve slot).
 */
internal object StdinScript {
    /** The stdin's last line: the body runs only when it arrived. A comment, so it is harmless inside the `eval`. */
    const val END = "# claude-remote-stdin-end"

    /** Printed on its own line after the body ran, followed by the body's exit status. */
    const val MARK = "__CR_END__"

    /** Printed (exit 97) when the stdin did not arrive whole: nothing of it ran. */
    const val TRUNCATED = "__CR_TRUNCATED__"

    /** The exec request. Quoted for any login shell (bash, dash, zsh): one single-quoted argument of `bash -c`. */
    const val EXEC =
        "bash -c 's=\$(cat); case \"\$s\" in *\"$END\") ( eval \"\$s\" ) </dev/null; r=\$?; echo; echo $MARK\$r; exit \$r;; " +
            "*) echo $TRUNCATED; exit 97;; esac'"

    /** The stdin of [EXEC]: [body], then [END]. */
    fun stdin(body: String): ByteArray = (body + "\n" + END + "\n").toByteArray(Charsets.UTF_8)

    sealed interface Outcome {
        /** The body ran to its end: its exit [status] and its own output [out] (the marker line and the newline before it removed). */
        data class Completed(val status: Int, val out: String) : Outcome

        /** No marker: the body did not run (cut short, [TRUNCATED]) or its end was not seen. Never a success. */
        data object Incomplete : Outcome
    }

    /**
     * Reads [stdout] of [EXEC]: [Outcome.Completed] only when its last line is [MARK]`<status>` (a login banner before
     * the body's output is kept in [Outcome.Completed.out], as it was before).
     */
    fun parse(stdout: String): Outcome {
        val at = stdout.lastIndexOf("\n$MARK")
        if (at < 0) return Outcome.Incomplete
        val status = stdout.substring(at + 1 + MARK.length).trim().toIntOrNull() ?: return Outcome.Incomplete
        return Outcome.Completed(status, stdout.substring(0, at))
    }
}
