package com.clauderemote.voice

import com.clauderemote.util.FileLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Read-aloud on desktop is the OS speech synthesiser driven as a child
 * process — macOS `say`, Linux `spd-say` (speech-dispatcher) with
 * `espeak-ng` / `espeak` behind it. No JVM speech API exists and the project
 * takes no new dependencies, so a process is the whole engine: spawn it,
 * wait for it, kill it to stop.
 *
 * Everything that decides *what* to run is a pure function here so it can be
 * unit-tested without a synthesiser installed; only [DesktopSpeech] touches
 * the OS.
 */

/**
 * How a synthesiser is invoked: the program, its argv tail, and whether the
 * text rides in on stdin. stdin is preferred wherever the program supports it
 * (`say`, `espeak`) — an assistant message can be tens of kilobytes, which is
 * close enough to the argv limit to be worth not spending. `spd-say` has no
 * stdin mode, so there the text is the final argument, placed after `--` so a
 * message that happens to begin with `-` isn't parsed as an option.
 */
internal data class SpeechCommand(
    val program: String,
    val args: List<String>,
    val textOnStdin: Boolean,
) {
    /** Full argv, ready for ProcessBuilder. */
    fun argv(): List<String> = listOf(program) + args
}

internal fun isMacOs(osName: String): Boolean = osName.lowercase().startsWith("mac")

/**
 * Synthesisers to try, best first. macOS always has `say`; on Linux
 * `spd-say` is preferred over raw espeak because it goes through
 * speech-dispatcher, which is what the desktop's own accessibility stack
 * uses (so it respects the user's configured voice).
 */
internal fun speechCandidates(osName: String): List<String> =
    if (isMacOs(osName)) listOf("say", "espeak-ng", "espeak")
    else listOf("spd-say", "espeak-ng", "espeak")

/** Baseline speaking rate of both `say` and espeak, in words per minute. */
private const val BASE_WPM = 175

/** The app's rate setting is a percentage (100 = normal), clamped as in settings. */
internal fun wordsPerMinute(ratePct: Int): Int = BASE_WPM * ratePct.coerceIn(25, 400) / 100

/**
 * `spd-say -r` is a relative −100..100 scale, not words per minute, so the
 * percentage is mapped onto it piecewise: 100 % is dead centre, and the two
 * halves are stretched separately because the setting's range is lopsided
 * (25..100 below, 100..400 above).
 */
internal fun spdSayRate(ratePct: Int): Int {
    val pct = ratePct.coerceIn(25, 400)
    val r = if (pct <= 100) (pct - 100) * 100 / 75 else (pct - 100) * 100 / 300
    return r.coerceIn(-100, 100)
}

/** Builds the invocation for an already-chosen [program]. */
internal fun buildSpeechCommand(program: String, ratePct: Int, text: String): SpeechCommand =
    when (program) {
        // `say` with no string argument and no -f reads the text from stdin.
        "say" -> SpeechCommand(program, listOf("-r", wordsPerMinute(ratePct).toString()), textOnStdin = true)
        // -w blocks until the message has actually been spoken. Without it
        // spd-say returns immediately, the button would reset while audio is
        // still playing, and there would be no process left to kill on stop.
        "spd-say" -> SpeechCommand(
            program,
            listOf("-w", "-r", spdSayRate(ratePct).toString(), "--", text),
            textOnStdin = false,
        )
        else -> SpeechCommand(program, listOf("-s", wordsPerMinute(ratePct).toString(), "--stdin"), textOnStdin = true)
    }

/** True when [program] resolves to an executable in [pathEnv]. */
internal fun isOnPath(program: String, pathEnv: String?, exists: (String) -> Boolean): Boolean =
    pathEnv.orEmpty()
        .split(File.pathSeparatorChar)
        .any { dir -> dir.isNotBlank() && exists(dir + File.separatorChar + program) }

/**
 * Picks the first installed synthesiser and builds its invocation, or null
 * when the machine has none — the caller must treat that as "say nothing",
 * never as an error worth throwing.
 */
internal fun resolveSpeechCommand(
    osName: String,
    ratePct: Int,
    text: String,
    onPath: (String) -> Boolean,
): SpeechCommand? = speechCandidates(osName)
    .firstOrNull(onPath)
    ?.let { buildSpeechCommand(it, ratePct, text) }

private fun executableExists(path: String): Boolean =
    runCatching { File(path).let { it.isFile && it.canExecute() } }.getOrDefault(false)

/** What this machine will actually run — the default for [DesktopSpeech.speakBlocking]. */
internal fun systemSpeechCommand(ratePct: Int, text: String): SpeechCommand? =
    resolveSpeechCommand(
        osName = System.getProperty("os.name").orEmpty(),
        ratePct = ratePct,
        text = text,
        onPath = { isOnPath(it, System.getenv("PATH"), ::executableExists) },
    )

/**
 * The one live read-aloud in the process. Speaking is app-wide singular (as on
 * Android): starting a new one kills the previous, so a second speaker button
 * never talks over the first, and the losing caller's [speakBlocking] returns
 * so its button resets itself.
 */
internal object DesktopSpeech {
    private const val TAG = "DesktopTts"

    private class Playback(val process: Process, val program: String)

    private val current = AtomicReference<Playback?>(null)
    private val warnedNoEngine = AtomicBoolean(false)
    // Bumped by every stop, including one that finds nothing running: spawning
    // takes a few milliseconds, and a stop landing inside that window has no
    // process to kill yet. Without the counter it would be lost and the
    // utterance would start after the user asked for silence.
    private val stopSeq = AtomicLong(0)

    /** True while an utterance is running. Exists for the lifecycle tests. */
    internal fun isSpeaking(): Boolean = current.get() != null

    /**
     * Speaks [text] and returns null when it finished (or was stopped), or a
     * human-readable message when it could not be spoken at all. BLOCKS for
     * the whole utterance — call it off the UI thread.
     *
     * [resolve] is the seam the tests use to drive the process machinery with
     * an ordinary command, so kill / failure / spawn-error behaviour is
     * checkable on a machine with no synthesiser installed.
     */
    fun speakBlocking(
        text: String,
        ratePct: Int,
        resolve: (Int, String) -> SpeechCommand? = ::systemSpeechCommand,
    ): String? {
        stop()
        val seqAtStart = stopSeq.get()
        val cmd = resolve(ratePct, text)
        if (cmd == null) {
            // Once per run: a machine without a synthesiser will hit this on
            // every single message, and read-aloud is not important enough to
            // fill the log with it.
            if (warnedNoEngine.compareAndSet(false, true)) {
                FileLogger.log(TAG, "no speech synthesiser on PATH (tried say/spd-say/espeak-ng/espeak)")
            }
            return "No speech synthesiser installed (say / spd-say / espeak-ng)."
        }
        val process = runCatching {
            ProcessBuilder(cmd.argv())
                // Merge and discard: an unread pipe fills up and would wedge
                // the synthesiser mid-sentence.
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse {
            FileLogger.log(TAG, "spawn ${cmd.program} failed: ${it.message}")
            return "Could not start ${cmd.program}: ${it.message ?: "unknown error"}"
        }
        val playback = Playback(process, cmd.program)
        // CLAIM, don't clobber. Two cards tapped within the spawn window (a
        // PATH scan plus a fork) can interleave so that the slower caller's
        // `set` overwrites the faster one's handle — after which stop() can
        // never find the playback that is actually talking, its button stays
        // stuck "speaking", and the next utterance talks over it. Losing the
        // claim means someone newer owns the speaker, so this caller kills its
        // own process and returns quietly, exactly as if it had been
        // superseded a moment later.
        if (!current.compareAndSet(null, playback)) {
            runCatching { process.destroy() }
            return null
        }
        // Claim the stop that arrived while we were spawning.
        if (stopSeq.get() != seqAtStart) stop()
        FileLogger.log(TAG, "speak via ${cmd.program} (${text.length} chars, rate ${ratePct}%)")

        runCatching {
            process.outputStream.use { out ->
                if (cmd.textOnStdin) out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
        val exit = runCatching { process.waitFor() }.getOrElse { -1 }
        // Losing the CAS means someone else already took over (stop, or another
        // button starting) — their kill is not this caller's error.
        val superseded = !current.compareAndSet(playback, null)
        if (superseded || exit == 0) return null
        FileLogger.log(TAG, "${cmd.program} exited with $exit")
        return "${cmd.program} exited with $exit"
    }

    /** Kills the live utterance, if any. Safe to call when nothing is speaking. */
    fun stop() {
        stopSeq.incrementAndGet()
        val playback = current.getAndSet(null) ?: return
        runCatching { playback.process.destroy() }
        // spd-say is only a client: the words are queued inside the
        // speech-dispatcher daemon and keep playing after the client dies, so
        // stopping means telling the daemon to cancel too.
        if (playback.program == "spd-say") {
            runCatching {
                ProcessBuilder("spd-say", "-C")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
        }
    }
}
