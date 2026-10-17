package com.clauderemote.integration

import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.session.transcript.TranscriptParser
import com.clauderemote.ui.RenderItem
import com.clauderemote.ui.buildRenderList
import com.clauderemote.ui.computeTurnMeta
import com.clauderemote.ui.itemKey
import com.clauderemote.ui.shouldShowEntry
import com.clauderemote.util.isoToEpochMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * The whole chat pipeline — real [TranscriptParser] into the real chat render
 * model — run over THIS MACHINE's actual Claude Code transcripts.
 *
 * Why it exists: every chat defect found in the 2026-10 review was invisible to
 * the fixture tests and obvious the moment the production parser met real data
 * (386 queued prompts that never rendered, 83 fake user bubbles from
 * compactions, 3 732 tool cards summarised as bare parameter names, 17
 * duplicate entry ids that would hard-crash a LazyColumn on Android). Fixtures
 * prove a rule holds for the shapes we thought of; this proves it holds for the
 * shapes Claude Code actually writes — including record types that did not
 * exist when the parser was written.
 *
 * It belongs in the integration lane for the same reason the sshd/tmux tests
 * do: it asserts against the ENVIRONMENT (~/.claude/projects, ~1 GiB of JSONL
 * here, ~10 s to walk) rather than against declared inputs, so it must never be
 * served from the build cache and must never slow the pure-logic lane.
 *
 * SKIPS — it does not fail — where there are no transcripts, which is every CI
 * runner and every machine but the developer's own.
 *
 * Run it:  gradle :shared:integrationTest --tests '*RealTranscriptHarness*'
 *
 * PRIVACY: real transcripts are private conversations. Nothing here — neither
 * an assertion message nor the report nor an exception detail — may print
 * message text. Ids, counts, record types, tool names and file names only.
 */
class RealTranscriptHarnessTest {

    private val root = File(
        System.getenv("TRANSCRIPT_HARNESS_ROOT")
            ?: (System.getProperty("user.home") + "/.claude/projects")
    )

    /**
     * Guard against a single pathological file exhausting the test JVM; the
     * `integrationTest` task sizes its heap to clear this with room to spare.
     * A bad value must not take the two data-independent tests down with it,
     * hence `toLongOrNull`.
     */
    private val maxBytesPerFile =
        System.getenv("TRANSCRIPT_HARNESS_MAX_BYTES")?.toLongOrNull() ?: 268_435_456L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * MAIN session transcripts: `<encoded-cwd>/<uuid>.jsonl`, the exact shape
     * TranscriptStream tails. The 3 392 `<uuid>/subagents/agent-*.jsonl` files
     * sit one directory deeper and the app never opens them, so neither does
     * this — listing only the top level excludes them structurally.
     */
    private fun mainTranscripts(): List<File> =
        (root.listFiles { f: File -> f.isDirectory } ?: emptyArray())
            .sortedBy { it.name }
            .flatMap { dir ->
                (dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jsonl") }
                    ?: emptyArray()).sortedBy { it.name }
            }

    /** A violated invariant, with a bounded number of examples kept for the report. */
    private class Breach(val what: String) {
        var count = 0
        val examples = mutableListOf<String>()
        fun add(example: String) {
            count++
            if (examples.size < 12) examples += example
        }
    }

    private val breaches = linkedMapOf<String, Breach>()

    private fun breach(what: String, example: String) {
        breaches.getOrPut(what) { Breach(what) }.add(example)
    }

    // ---------------------------------------------------------------- render

    /**
     * The display states a real chat can be in. Keys must stay unique in every
     * one of them: a duplicate key is not a cosmetic bug, it throws out of
     * LazyColumn and takes the Android app down.
     */
    private class RenderConfig(
        val label: String,
        val showSystem: Boolean,
        val showThinking: Boolean,
        val liveTurnDone: Boolean,
        val historyTruncated: Boolean,
        /** Exactly the lambda TranscriptView hands to buildRenderList. */
        val isExpanded: (String) -> Boolean,
        /** Only when nothing can be hidden is "every entry reached a card" a fair ask. */
        val fullyExpanded: Boolean = false,
    )

    private val renderConfigs = listOf(
        RenderConfig(
            "default: steps collapsed, live turn still running",
            showSystem = false, showThinking = false, liveTurnDone = false,
            historyTruncated = false, isExpanded = { false },
        ),
        RenderConfig(
            "everything shown, every turn expanded, live turn done",
            showSystem = true, showThinking = true, liveTurnDone = true,
            historyTruncated = false, isExpanded = { true }, fullyExpanded = true,
        ),
        RenderConfig(
            "truncated backlog, system notes shown",
            showSystem = true, showThinking = false, liveTurnDone = true,
            historyTruncated = true, isExpanded = { false },
        ),
        RenderConfig(
            // The realistic one: the two toggles move independently, and a reader
            // expands some turns and not others, so collapsed TurnSteps rows and
            // expanded bodies coexist in one list.
            "thinking shown, system hidden, turns expanded piecemeal",
            showSystem = false, showThinking = true, liveTurnDone = true,
            historyTruncated = false, isExpanded = { key -> key.hashCode() % 2 == 0 },
        ),
    )

    private fun renderedEntryIds(items: List<RenderItem>): Set<String> {
        val out = HashSet<String>()
        for (item in items) when (item) {
            is RenderItem.Single -> out += item.entry.id
            is RenderItem.ToolGroup -> item.calls.forEach { out += it.id }
            is RenderItem.TurnSteps, is RenderItem.TimeGap -> Unit
        }
        return out
    }

    private fun entryText(e: TranscriptEntry): String? = when (e) {
        is TranscriptEntry.UserPrompt -> e.text
        is TranscriptEntry.AssistantText -> e.text
        is TranscriptEntry.AssistantThinking -> e.text
        is TranscriptEntry.SlashCommand -> e.name
        is TranscriptEntry.SystemNote -> e.text
        // A tool result legitimately carries an empty body (a silent command),
        // and a tool call is summarised, not texted — neither is a blank card.
        is TranscriptEntry.ToolCall, is TranscriptEntry.ToolResult -> null
    }

    // ----------------------------------------------------------------- stats

    private val entryTypeCounts = linkedMapOf<String, Long>()
    private val toolCallsByName = linkedMapOf<String, Long>()
    private val bareKeyListByTool = linkedMapOf<String, Long>()
    private var filesScanned = 0
    private var filesSkippedTooBig = 0
    private var bytesScanned = 0L
    private var linesIn = 0L
    private var entriesOut = 0L
    private var queuedSeen = 0L
    private var queuedRendered = 0L
    private var queuedSuppressedAsDuplicate = 0L
    private var queuedSuppressedAsSynthetic = 0L
    private var errorMessages = 0L
    private var compactSummaries = 0L
    private var noArgumentTools = 0L
    private var bareKeyListSummaries = 0L

    private fun bump(m: MutableMap<String, Long>, k: String) {
        m[k] = (m[k] ?: 0L) + 1L
    }

    // ------------------------------------------------------------------ test

    @Test
    fun everyRealTranscriptParsesAndRendersWithoutLoss() {
        assumeTrue(
            "no transcripts at ${root.path} — nothing to check on this machine",
            root.isDirectory
        )
        val files = mainTranscripts()
        assumeTrue("${root.path} holds no main-session transcripts", files.isNotEmpty())

        for (file in files) {
            val size = file.length()
            if (size > maxBytesPerFile) {
                filesSkippedTooBig++
                continue
            }
            filesScanned++
            bytesScanned += size

            // ONE read. These transcripts are live — the session running this
            // build is appending to one of them — so a second pass over the
            // same file would see records the parse never had, and every one of
            // them would look like a prompt the chat had lost.
            val queuedLines = ArrayList<String>()
            val entries = try {
                file.useLines { lines ->
                    TranscriptParser.parseLines(
                        lines.onEach { line ->
                            linesIn++
                            // Cheap pre-filter: a handful of lines per file.
                            if (line.contains("\"queued_command\"")) queuedLines += line
                        }
                    )
                }
            } catch (e: Exception) {
                // The parser swallows per-line failures by design; anything that
                // still escapes means a real record shape kills the whole chat.
                // Only the exception TYPE is safe to print — a serialization
                // message quotes the offending line, i.e. the conversation.
                breach("parseLines threw on a real transcript", "${file.name}: ${e::class.simpleName}")
                continue
            }
            entriesOut += entries.size

            checkEntries(file, entries)
            checkRendering(file, entries)
            checkQueuedCommands(file, entries, queuedLines)
        }

        val report = writeReport()
        if (breaches.isNotEmpty()) {
            val detail = breaches.values.joinToString("\n") { b ->
                "  - ${b.what}: ${b.count}\n" + b.examples.joinToString("\n") { "      $it" }
            }
            fail(
                "the chat pipeline broke on real transcripts " +
                    "($filesScanned files, $linesIn lines, $entriesOut entries):\n$detail\n" +
                    "full report: ${report.path}"
            )
        }
        // A pass with nothing scanned is a wiring failure, not a pass — and the
        // queued-command path is the one most easily wired to nothing, since it
        // keys off a string that only Claude Code decides to write.
        if (entriesOut == 0L) {
            fail("scanned $filesScanned files but parsed 0 entries — harness is not wired to real data")
        }
        if (queuedSeen == 0L) {
            fail(
                "scanned $filesScanned files and found no queued_command records at all — " +
                    "either the record shape changed or the pre-filter no longer matches it"
            )
        }
    }

    // ------------------------------------------------------------ invariants

    private fun checkEntries(file: File, entries: List<TranscriptEntry>) {
        val seen = HashSet<String>(entries.size * 2)
        for (e in entries) {
            bump(entryTypeCounts, e::class.simpleName ?: "?")

            // A duplicate id is a hard Android crash (LazyColumn item keys).
            // ParseState.emit already drops one, so on real data this can only
            // go red if that guard is removed — which is the point. The teeth
            // are in theDetectorsFireOnAKnowinglyBrokenChat, not here.
            if (!seen.add(e.id)) {
                breach("duplicate entry id in one transcript", "${file.name}: ${e.id} (${e::class.simpleName})")
            }

            // Every rendered card must have something in it. A blank card is
            // the defect the user reported as "something is missing".
            entryText(e)?.let { text ->
                if (text.isBlank()) {
                    breach("entry rendered with blank text", "${file.name}: ${e.id} (${e::class.simpleName})")
                }
            }

            when (e) {
                is TranscriptEntry.ToolCall -> {
                    bump(toolCallsByName, e.name)
                    if (e.inputSummary.isBlank()) {
                        breach("tool call with a blank summary", "${file.name}: ${e.id} (${e.name})")
                    }
                    if (e.inputSummary == "(no arguments)") noArgumentTools++
                    if (isBareKeyList(e)) {
                        bareKeyListSummaries++
                        bump(bareKeyListByTool, e.name)
                    }
                }
                is TranscriptEntry.AssistantText -> if (e.isError) errorMessages++
                is TranscriptEntry.SystemNote -> if (e.subtype == "compact_summary") compactSummaries++
                else -> Unit
            }
        }
    }

    /**
     * The original "keys only" defect: a card whose whole summary is the names
     * of the arguments rather than any of them ("prompt, schema, label").
     * Deliberately counted, not asserted — `fallbackSummary` legitimately ends
     * there when a tool's every value is a long string or a nested object. The
     * census is what makes a regression that doubled it visible.
     */
    private fun isBareKeyList(call: TranscriptEntry.ToolCall): Boolean {
        val obj = runCatching { json.parseToJsonElement(call.fullInput).jsonObject }.getOrNull()
            ?: return false
        if (obj.isEmpty()) return false
        return call.inputSummary == obj.keys.joinToString(", ")
    }

    private fun checkRendering(file: File, entries: List<TranscriptEntry>) {
        // Same pairing the view does: a result shown inside its call's card is
        // filtered out of the top-level list.
        val resultsByToolId = entries.filterIsInstance<TranscriptEntry.ToolResult>()
            .mapNotNull { r -> r.toolUseId?.let { it to r } }
            .toMap()
        val pairedResultIds = entries.filterIsInstance<TranscriptEntry.ToolCall>()
            .mapNotNull { resultsByToolId[it.toolUseId]?.id }
            .toSet()
        val meta = computeTurnMeta(entries)

        for (cfg in renderConfigs) {
            val filtered = entries.filter {
                shouldShowEntry(it, cfg.showSystem, cfg.showThinking, pairedResultIds)
            }
            val items = buildRenderList(
                filtered, meta, cfg.liveTurnDone, cfg.historyTruncated, cfg.isExpanded
            )

            val keys = HashSet<String>(items.size * 2)
            for (item in items) {
                val key = itemKey(item)
                if (!keys.add(key)) {
                    breach(
                        "duplicate LazyColumn key (crashes Android)",
                        "${file.name}: $key [${cfg.label}]"
                    )
                }
            }

            // With every turn expanded nothing may be hidden: an entry that
            // survived the display filter and then never reached a render item
            // is an entry the user simply cannot see.
            if (cfg.fullyExpanded) {
                val rendered = renderedEntryIds(items)
                for (e in filtered) {
                    if (e.id !in rendered) {
                        breach(
                            "entry dropped between filter and render",
                            "${file.name}: ${e.id} (${e::class.simpleName}) [${cfg.label}]"
                        )
                    }
                }
            }
        }
    }

    /**
     * A `queued_command` attachment is a prompt the user typed while Claude was
     * busy. Each one must either reach the chat or have a reason not to that
     * this harness can verify independently: blank, a system injection, or a
     * twin of a prompt that is already on screen (Claude Code writes the same
     * text twice — once queued, once as a `user` record — and the parser keeps
     * one). Anything else is a prompt that silently vanished, which is the bug
     * the user opened this review with.
     *
     * [queuedLines] comes from the same single read as [entries]; see the file
     * loop for why re-reading would manufacture failures.
     */
    private fun checkQueuedCommands(
        file: File,
        entries: List<TranscriptEntry>,
        queuedLines: List<String>,
    ) {
        val renderedIds = entries.filterIsInstance<TranscriptEntry.UserPrompt>()
            .map { it.id }.toHashSet()
        val promptTimesByText = HashMap<String, MutableList<Long>>()
        for (e in entries) {
            if (e !is TranscriptEntry.UserPrompt) continue
            val at = e.timestamp?.let { isoToEpochMillis(it) } ?: continue
            promptTimesByText.getOrPut(e.text.trim()) { mutableListOf() } += at
        }

        for (line in queuedLines) {
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val attachment = runCatching { obj["attachment"]?.jsonObject }.getOrNull() ?: continue
            if (attachment["type"]?.jsonPrimitive?.contentOrNull != "queued_command") continue
            val origin = runCatching {
                attachment["origin"]?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            // `peer` and `task-notification` queue entries are agent plumbing,
            // never a human prompt. A MISSING origin is deliberately NOT treated
            // as plumbing: 984 of 1 654 records here carry no origin object at
            // all (the field appeared mid-history), and skipping those in
            // lockstep with the parser is how this check would go green on the
            // day Claude Code stops writing the field and every real prompt
            // vanishes from the chat. They fall through to the ladder below,
            // where today all of them are recognised as synthetic injections.
            if (origin != null && origin != "human") continue

            queuedSeen++
            val uuid = runCatching { obj["uuid"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val text = runCatching { attachment["prompt"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (text == null) {
                // The likeliest future shape change: `prompt` becoming a content
                // block array once a queued prompt can carry a pasted image.
                // The parser drops those silently, so count them loudly.
                breach(
                    "queued prompt whose text is not a plain string",
                    "${file.name}: ${uuid ?: "(no uuid)"}"
                )
                continue
            }
            if (uuid != null && uuid in renderedIds) {
                queuedRendered++
                continue
            }
            if (text.isBlank() || TranscriptParser.isSyntheticUserText(text)) {
                queuedSuppressedAsSynthetic++
                continue
            }
            if (uuid == null) {
                breach("queued prompt has no uuid to key an entry on", file.name)
                continue
            }
            val at = (obj["timestamp"]?.jsonPrimitive?.contentOrNull
                ?: attachment["timestamp"]?.jsonPrimitive?.contentOrNull)
                ?.let { isoToEpochMillis(it) }
            val hasVisibleTwin = at != null && promptTimesByText[text.trim()]
                ?.any { abs(it - at) <= TranscriptParser.DEDUP_WINDOW_MS } == true
            if (hasVisibleTwin) {
                queuedSuppressedAsDuplicate++
            } else {
                breach(
                    "queued prompt missing from the chat with no twin on screen",
                    "${file.name}: $uuid"
                )
            }
        }
    }

    // ---------------------------------------------------------------- report

    /**
     * A content-free census of what the chat made of the real data. It is the
     * half of the harness that no assertion can replace: the counts are what
     * made the original defects visible, and a diff of two runs is what shows
     * a parser change gained or lost entries.
     */
    private fun writeReport(): File {
        val dir = File(
            System.getProperty("clauderemote.integration.logDir")
                ?: "build/integration-logs"
        )
        dir.mkdirs()
        val out = File(dir, "real-transcript-harness.md")
        val sb = StringBuilder()
        sb.appendLine("# Chat pipeline vs. the real transcripts in ${root.path}")
        sb.appendLine()
        sb.appendLine("- main-session files scanned: $filesScanned (skipped as oversized: $filesSkippedTooBig)")
        sb.appendLine("- bytes: ${bytesScanned / (1024 * 1024)} MiB, lines in: $linesIn, entries out: $entriesOut")
        sb.appendLine("- queued prompts: $queuedSeen seen, $queuedRendered rendered, " +
            "$queuedSuppressedAsDuplicate suppressed as a twin, $queuedSuppressedAsSynthetic synthetic/blank")
        sb.appendLine("- API error messages recognised: $errorMessages")
        sb.appendLine("- compaction summaries recognised: $compactSummaries")
        sb.appendLine("- tool calls summarised as `(no arguments)`: $noArgumentTools")
        sb.appendLine("- tool calls summarised as a bare key list: $bareKeyListSummaries")
        sb.appendLine()
        appendCensus(sb, "Entries by type", "type", "count", entryTypeCounts)
        appendCensus(sb, "Tool calls by name", "tool", "calls", toolCallsByName)
        appendCensus(sb, "Bare key-list summaries by tool", "tool", "calls", bareKeyListByTool)
        if (breaches.isNotEmpty()) {
            sb.appendLine("## Broken invariants")
            sb.appendLine()
            for (b in breaches.values) {
                sb.appendLine("### ${b.what} — ${b.count}")
                sb.appendLine()
                b.examples.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
        }
        out.writeText(sb.toString())
        println("[real-transcript-harness] $filesScanned files, $linesIn lines, $entriesOut entries -> ${out.path}")
        return out
    }

    private fun appendCensus(
        sb: StringBuilder,
        title: String,
        keyHeader: String,
        valueHeader: String,
        counts: Map<String, Long>,
    ) {
        sb.appendLine("## $title")
        sb.appendLine()
        if (counts.isEmpty()) {
            sb.appendLine("_none_")
            sb.appendLine()
            return
        }
        sb.appendLine("| $keyHeader | $valueHeader |")
        sb.appendLine("|---|---:|")
        counts.entries.sortedByDescending { it.value }
            .forEach { sb.appendLine("| `${it.key}` | ${it.value} |") }
        sb.appendLine()
    }

    // ----------------------------------------------------- harness self-check

    /**
     * A green harness is worth nothing unless it can go red. These three run
     * everywhere, real transcripts or not: they feed the SAME detectors a chat
     * that is knowingly broken and assert each one fires. Without them a
     * refactor could quietly turn the checks above into no-ops and every run
     * would still pass.
     *
     * Covered: duplicate entry id, blank card, blank tool summary, duplicate
     * LazyColumn key, queued prompt with no uuid, queued prompt missing from
     * the chat. NOT covered: `parseLines threw` and `entry dropped between
     * filter and render`, neither of which can be produced without breaking
     * production code — they are guards against a future regression, and only
     * the real-data run exercises them.
     */
    @Test
    fun theDetectorsFireOnAKnowinglyBrokenChat() {
        val f = File("synthetic.jsonl")
        val ts = "2026-10-17T10:00:00.000Z"
        val broken = listOf(
            TranscriptEntry.UserPrompt(id = "p1", timestamp = ts, text = "ask"),
            TranscriptEntry.ToolCall(
                id = "t1", timestamp = ts, toolUseId = "u1",
                name = "Bash", inputSummary = "  ", fullInput = "{}"
            ),
            TranscriptEntry.AssistantText(id = "a1", timestamp = ts, text = "   ", model = "m"),
            // Same id again: the Android crash, and a blank card twice over.
            TranscriptEntry.AssistantText(id = "a1", timestamp = ts, text = "", model = "m"),
        )
        checkEntries(f, broken)
        checkRendering(f, broken)

        assertEquals(1, breaches["duplicate entry id in one transcript"]?.count)
        assertEquals(2, breaches["entry rendered with blank text"]?.count)
        assertEquals(1, breaches["tool call with a blank summary"]?.count)
        assertNotNull(breaches["duplicate LazyColumn key (crashes Android)"])

        // ...and stays silent on a sound one.
        breaches.clear()
        val sound = listOf(
            TranscriptEntry.UserPrompt(id = "p2", timestamp = ts, text = "ask"),
            TranscriptEntry.ToolCall(
                id = "t2", timestamp = ts, toolUseId = "u2",
                name = "Bash", inputSummary = "ls", fullInput = """{"command":"ls"}"""
            ),
            TranscriptEntry.AssistantText(id = "a2", timestamp = ts, text = "answer", model = "m"),
        )
        checkEntries(f, sound)
        checkRendering(f, sound)
        assertEquals(emptyList(), breaches.keys.toList())
    }

    @Test
    fun theQueuedPromptDetectorFiresOnAPromptThatCannotReachTheChat() {
        val file = File("synthetic.jsonl")
        // Identical records but for the uuid — and without one the parser has
        // nothing to key an entry on, so the prompt cannot be shown at all.
        val lines = listOf(
            """{"type":"attachment","uuid":"q1","timestamp":"2026-10-17T10:00:00.000Z","attachment":{"type":"queued_command","origin":{"kind":"human"},"prompt":"first queued prompt"}}""",
            """{"type":"attachment","timestamp":"2026-10-17T10:05:00.000Z","attachment":{"type":"queued_command","origin":{"kind":"human"},"prompt":"second queued prompt"}}""",
        )
        val entries = TranscriptParser.parseLines(lines.asSequence())
        checkQueuedCommands(file, entries, lines)

        assertEquals(2L, queuedSeen)
        assertEquals(1L, queuedRendered)
        assertEquals(1, breaches["queued prompt has no uuid to key an entry on"]?.count)
    }

    @Test
    fun theQueuedPromptDetectorFiresWhenAPromptNeverReachesTheChat() {
        // A parser that loses a queued prompt outright is a code defect, not a
        // record shape, so it cannot be provoked from data. Feed the detector
        // the chat such a defect would produce instead: the record is on disk,
        // nothing is on screen, and no twin can excuse it.
        val file = File("synthetic.jsonl")
        val lines = listOf(
            """{"type":"attachment","uuid":"q9","timestamp":"2026-10-17T10:00:00.000Z","attachment":{"type":"queued_command","origin":{"kind":"human"},"prompt":"a prompt the chat lost"}}""",
        )
        checkQueuedCommands(file, entries = emptyList(), queuedLines = lines)

        assertEquals(1L, queuedSeen)
        assertEquals(0L, queuedRendered)
        assertEquals(
            1,
            breaches["queued prompt missing from the chat with no twin on screen"]?.count
        )
    }
}
