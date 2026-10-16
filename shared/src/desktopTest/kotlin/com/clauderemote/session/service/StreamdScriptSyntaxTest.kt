package com.clauderemote.session.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression guard for [TranscriptService.STREAMD_SCRIPT] — the python daemon
 * embedded as a Kotlin raw string and heredoc'd onto every server. A syntax
 * error in it is INVISIBLE to the Kotlin compiler and breaks transcript
 * streaming for every session on that server, so it has to be parsed here.
 *
 * Also pins the client↔daemon wire contract: the two halves of the delta
 * protocol live in two different languages in two different files, and S1's
 * `f` (chunk start offset) is only load-bearing if BOTH of them use it.
 *
 * Lives in `desktopTest` (not `commonTest`) because it shells out to python3
 * and writes temp files — an Android unit test has neither.
 */
class StreamdScriptSyntaxTest {

    private val script = TranscriptService.STREAMD_SCRIPT
    private val marker = TranscriptService.STREAMD_MARKER
    private val installer = TranscriptService.ENSURE_STREAMD_COMMAND

    private fun python3Available(): Boolean = try {
        ProcessBuilder("python3", "-c", "pass").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    /**
     * The whole point of this file: `python3 -c "import ast; ast.parse(...)"`
     * over the real embedded script. The size guard runs first and is
     * load-bearing — `ast.parse("")` succeeds, so without it a future
     * refactor that empties the string would keep "passing".
     */
    @Test
    fun streamdScript_parsesAsPython() {
        val nonBlank = script.lines().count { it.isNotBlank() }
        assertTrue(nonBlank > 60, "streamd script has only $nonBlank non-blank lines — is it still the daemon?")
        if (!python3Available()) {
            println("python3 not on PATH — skipping streamdScript_parsesAsPython")
            return
        }
        val tmp = File.createTempFile("streamd-syntax", ".py")
        tmp.deleteOnExit()
        try {
            tmp.writeText(script)
            val proc = ProcessBuilder(
                "python3", "-c",
                "import ast,sys; ast.parse(open(sys.argv[1], encoding='utf-8').read())",
                tmp.absolutePath,
            ).start()
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) fail("streamd.py failed to parse (exit=$exit):\n$stderr")
        } finally {
            tmp.delete()
        }
    }

    /**
     * S1's wire contract. The client reads `f` from each delta event and drops
     * a batch that starts past its own offset; if the daemon stopped emitting
     * `f`, the client would silently fall back to the pre-v4 fast-forward and
     * lose deltas again with no test failing. Both halves are asserted here
     * because they cannot see each other.
     */
    @Test
    fun deltaEvents_carryTheChunkStartOffset() {
        assertTrue(
            Regex("""'f':\s*start""").containsMatchIn(script),
            "streamd no longer emits the chunk START offset as 'f' — TranscriptStream.pushLines " +
                "needs it to tell a contiguous delta from one that begins past what it has read",
        )
        // Both emit branches (gzip and raw base64) must carry it.
        assertEquals(
            2, Regex("""'f':\s*start""").findAll(script).count(),
            "only one of the two emit branches (z=1 / z=0) reports 'f'",
        )
        // The partial-first-line drop must move `start` with the chunk,
        // otherwise `f` points at a byte that is NOT the first byte sent.
        assertTrue(
            Regex("""start = off \+ first \+ 1""").containsMatchIn(script),
            "the rotation/first-sight branch trims a partial line off the chunk but does not " +
                "advance `start` past it — 'f' would then overstate what was sent",
        )
        val client = File("src/commonMain/kotlin/com/clauderemote/session/transcript/TranscriptStream.kt")
        assertTrue(client.isFile, "expected TranscriptStream.kt at ${client.absolutePath}")
        assertTrue(
            client.readText().contains("fromOffset"),
            "TranscriptStream.pushLines no longer takes the chunk start offset",
        )
    }

    /**
     * The daemon half of S1, proven rather than pinned: run the real script
     * against a throwaway $HOME, append lines in two rounds, and check that
     * every delta's `f` is the previous delta's `o` and that `o - f` is exactly
     * the chunk it sent. That identity is what lets the client treat `f > its
     * offset` as a genuine hole instead of fast-forwarding over it.
     *
     * Reads the daemon's stdout on a separate thread: a 200 KB base64 delta
     * exceeds the pipe buffer, and a single-threaded harness would deadlock.
     */
    @Test
    fun streamdDeltas_reportContiguousFromAndToOffsets() {
        if (!python3Available()) {
            println("python3 not on PATH — skipping streamdDeltas_reportContiguousFromAndToOffsets")
            return
        }
        val home = File.createTempFile("streamd-home", "").let { it.delete(); it.mkdirs(); it }
        val scriptFile = File(home, "streamd.py").apply { writeText(script) }
        val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val jsonl = File(home, ".claude/projects/-tmp-x/$uuid.jsonl")
        jsonl.parentFile.mkdirs()
        jsonl.writeText("")
        val proc = ProcessBuilder("python3", scriptFile.absolutePath)
            .apply { environment()["HOME"] = home.absolutePath }
            .start()
        val events = java.util.Collections.synchronizedList(mutableListOf<Triple<Long, Long, Int>>())
        val pump = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (!line.contains("\"t\":\"d\"")) return@forEachLine
                    val from = Regex(""""f":(\d+)""").find(line)!!.groupValues[1].toLong()
                    val to = Regex(""""o":(\d+)""").find(line)!!.groupValues[1].toLong()
                    val b64 = Regex(""""b":"([^"]*)"""").find(line)!!.groupValues[1]
                    events += Triple(from, to, java.util.Base64.getDecoder().decode(b64).size)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        var finalSize = -1L
        try {
            val stdin = proc.outputStream.bufferedWriter()
            // z=0: raw base64, so the chunk's byte length is checkable directly.
            stdin.write("""{"op":"watch","id":"s1","cwd":"/tmp/x","uuid":"$uuid","off":0,"z":0}""" + "\n")
            stdin.flush()
            repeat(2) { round ->
                jsonl.appendText((0 until 3).joinToString("") { """{"type":"user","r":$round,"i":$it}""" + "\n" })
                // streamd ticks once a second; wait for this round's delta.
                val deadline = System.currentTimeMillis() + 15_000
                while (events.size <= round && System.currentTimeMillis() < deadline) Thread.sleep(100)
            }
            finalSize = jsonl.length()
            stdin.close() // stdin EOF ⇒ the daemon exits
            proc.waitFor()
            pump.join(5_000)
        } finally {
            proc.destroy()
            home.deleteRecursively()
        }
        val seen = events.toList()
        assertTrue(seen.size >= 2, "expected a delta per append round, got ${seen.size}: $seen")
        assertEquals(0L, seen.first().first, "the first delta of a watch from off=0 must start at byte 0")
        seen.forEach { (from, to, bytes) ->
            assertEquals(bytes.toLong(), to - from, "delta [$from,$to) does not match its $bytes payload bytes")
        }
        seen.zipWithNext().forEach { (a, b) ->
            assertEquals(a.second, b.first, "delta $b does not start where $a ended — the client would see a hole")
        }
        assertEquals(finalSize, seen.last().second, "the last delta must end at EOF")
    }

    /**
     * The install gate is `head -c 200 streamd.py | grep -q '<marker>'`, so the
     * marker must actually sit in the first 200 BYTES of the file (UTF-8 — the
     * header has an em dash), or every connection reinstalls the script.
     */
    @Test
    fun marker_isWithinTheFirst200BytesTheInstallerGrepsFor() {
        assertTrue(
            installer.contains("head -c 200"),
            "installer no longer greps the first 200 bytes — update this test to match",
        )
        val head = String(script.toByteArray(Charsets.UTF_8).copyOfRange(0, 200), Charsets.UTF_8)
        assertTrue(
            head.contains(marker),
            "marker '$marker' is not inside the first 200 bytes of streamd.py, so the installer's " +
                "gate never matches and the script is rewritten on every connect. Head was:\n$head",
        )
    }

    /**
     * Version skew in the OTHER direction: an app still running an older build
     * greps for ITS OWN marker. Unless this script lists that version too, the
     * old client finds no match and overwrites v4 with its own vN — two clients
     * on one server then rewrite the file past each other indefinitely. Every
     * version from the oldest compatible one up to the current marker must be
     * listed, and the whole line must fit in the grepped head.
     */
    @Test
    fun markerCompatList_coversEveryCompatibleVersion_andFitsInTheGreppedHead() {
        val current = Regex("""claude-remote-streamd v(\d+)""").find(marker)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: fail("STREAMD_MARKER is not of the form 'claude-remote-streamd vN': $marker")
        val compatLine = script.lines().firstOrNull { it.contains("marker-compat") }
            ?: fail("no 'marker-compat' line in streamd.py — an older client would overwrite this script")
        val versions = Regex("""claude-remote-streamd v(\d+)""")
            .findAll(compatLine).map { it.groupValues[1].toInt() }.toList()
        assertEquals(
            (versions.first()..current).toList(), versions,
            "marker-compat list is not the monotonic/complete range up to the current marker (v$current): $compatLine",
        )
        assertTrue(
            current in versions,
            "current marker v$current is missing from the compat list, so even this build's own gate " +
                "would not match: $compatLine",
        )
        // The gate only reads 200 bytes, so the compat line is useless past them.
        val lineEnd = script.indexOf(compatLine) + compatLine.length
        val bytesToLineEnd = script.substring(0, lineEnd).toByteArray(Charsets.UTF_8).size
        assertTrue(
            bytesToLineEnd <= 200,
            "the marker-compat line ends at byte $bytesToLineEnd, past the `head -c 200` the installer " +
                "greps — move it closer to the top or shorten the header",
        )
    }
}
