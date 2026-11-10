package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.JarvisSays
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.voice.conversation.TalkSendResult
import com.clauderemote.voice.conversation.TalkWords
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F5c: "Ano" of Jarvis' question (phone notification, watch) and "Odpovědět" go only into Jarvis' pane, only over the
 * conversation mode's checked path (ReplyGuard.voiceCheck + the empty-input check on the server), and Ano only for a
 * jask that is still the newest thing he said. Through the real orchestrator with a fake exec gate (nothing reaches a
 * network), plus a read of the sources for the parts a unit cannot reach without a live pane.
 */
class JarvisAnswerPathTest {
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val servers = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    private class RecordingGate : ExecChannels {
        val kinds = CopyOnWriteArrayList<ExecKind>()
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T {
            kinds += kind
            throw UnsupportedOperationException()
        }
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T {
            kinds += kind
            throw UnsupportedOperationException()
        }
    }

    private val gate = RecordingGate()

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        servers.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        SshSessionHelper.execChannelsProvider = savedExecChannels
        CommFlags.resetForTest()
    }

    private fun orch(): SessionOrchestrator {
        val o = SessionOrchestrator(servers, TabManager(), SessionStorage(FakeKeyValueStore())).also { orchestrator = it }
        SshSessionHelper.execChannelsProvider = { gate }
        return o
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    private fun SessionOrchestrator.hear(id: String, kind: String, agoMs: Long) {
        jarvisAlerts.connected(server.id)
        jarvisAlerts.connected(server.id) // warm: a fresh line is raised
        jarvisAlerts.onLine(server.id, "claude-remote-jarvis $id $kind ${System.currentTimeMillis() - agoMs} ${b64("Mám pustit citaci dál? Ano?")} -", 0)
    }

    @Test fun anoWithoutThatQuestionTouchesNothing() = runBlocking {
        val o = orch()
        assertEquals(TalkSendResult.Refused(JarvisSays.STALE), o.jarvisYes("j-1"), "no question known")
        assertEquals(TalkSendResult.Refused(JarvisSays.NOT_A_QUESTION), o.jarvisYes(null))
        o.hear("j-1", "jsay", 1_000)
        assertEquals(TalkSendResult.Refused(JarvisSays.NOT_A_QUESTION), o.jarvisYes("j-1"), "a jsay is no question")
        o.hear("j-2", "jask", 500)
        o.hear("j-3", "jsay", 100)
        assertEquals(TalkSendResult.Refused(JarvisSays.STALE), o.jarvisYes("j-2"), "he said something after it")
        assertTrue(gate.kinds.isEmpty(), "a refused Ano reaches no server")
    }

    @Test fun anoOfTheQuestionGoesTheJarvisPaneWayAndNowhereElse() = runBlocking {
        val o = orch()
        o.hear("j-9", "jask", 1_000)
        // the question passes; with no Jarvis tab on this device the checked path refuses before any exec
        assertEquals(TalkSendResult.Refused(TalkWords.NO_TAB), o.jarvisYes("j-9"))
        assertEquals(TalkSendResult.Refused(TalkWords.NO_TAB), o.jarvisReply("j-9", "Ne, počkej do zítřka."))
        assertTrue(o.jarvisAlerts.state.value.answered.isEmpty(), "a refused answer leaves the question open")
        assertEquals("j-9", JarvisSays.ask(o.jarvisAlerts.state.value, System.currentTimeMillis())?.id)
        assertEquals(TalkSendResult.Refused(JarvisSays.STALE), o.jarvisReply("j-0", "jiná otázka"), "a reply obeys the same rule")
        assertEquals(TalkSendResult.Refused(TalkWords.NO_TAB), o.sendJarvisAnswer("Ne, počkej do zítřka."))
        assertEquals(TalkSendResult.Refused(TalkWords.EMPTY), o.sendJarvisAnswer(" \u0007\n "))
        assertTrue(gate.kinds.isEmpty())
    }

    private val orchSrc by lazy { java.io.File("src/commonMain/kotlin/com/clauderemote/session/SessionOrchestrator.kt").readText() }

    private fun body(src: String, signature: String): String {
        val at = src.indexOf(signature)
        assertTrue(at >= 0, "missing: $signature")
        val next = src.indexOf("\n    suspend fun ", at + signature.length).let { if (it < 0) src.length else it }
        val nextFun = src.indexOf("\n    fun ", at + signature.length).let { if (it < 0) src.length else it }
        val nextPriv = src.indexOf("\n    private ", at + signature.length).let { if (it < 0) src.length else it }
        return src.substring(at, minOf(next, nextFun, nextPriv))
    }

    @Test fun theAnswerPathIsTheCheckedOneOfTheConversationMode() {
        val checked = body(orchSrc, "private suspend fun sendJarvisChecked(")
        assertTrue("jarvisTabIdForWatch()" in checked, "Jarvis' own tab only")
        assertTrue("ReplyGuard.voiceCheck(" in checked, "idle at his prompt, no dialog")
        assertTrue("emptyInput = true" in checked, "his input box checked empty on the server")
        assertTrue("approve = false" in checked, "never an approval of a dialog")
        assertTrue("jarvisAnswerLock.withLock { sendJarvisAnswerLocked(text) }" in body(orchSrc, "suspend fun sendJarvisAnswer("))
        assertTrue("sendJarvisChecked(" in body(orchSrc, "private suspend fun sendJarvisAnswerLocked("))
        // a spoken prompt takes the same lock (kolo 4)
        assertTrue("jarvisAnswerLock.withLock { sendJarvisChecked(prompt, stillWanted" in body(orchSrc, "suspend fun sendJarvisVoice("))
        val yes = body(orchSrc, "suspend fun jarvisYes(")
        assertTrue("answerJarvisQuestion(alertId, com.clauderemote.session.service.JarvisSays.YES" in yes)
        assertTrue("answerJarvisQuestion(alertId, text" in body(orchSrc, "suspend fun jarvisReply("))
        assertEquals("ano", JarvisSays.YES)
        // H2: one answer at a time, the question checked again INSIDE the lock; H1: marked for Sent and Maybe
        val q = body(orchSrc, "private suspend fun answerJarvisQuestion(")
        val lock = q.indexOf("jarvisAnswerLock.withLock")
        assertTrue(lock >= 0, "the answers share one lock")
        assertTrue(q.indexOf("JarvisSays.yesRefusal(") > lock, "checked inside the lock, not before it")
        assertTrue(q.indexOf("sendJarvisAnswerLocked(text)") > lock)
        assertTrue("r !is com.clauderemote.voice.conversation.TalkSendResult.Refused) jarvisAlerts.markAnswered(alertId)" in q)
    }

    @Test fun theNotificationAndTheWatchUseOnlyThatPath() {
        val receiver = java.io.File("../androidApp/src/main/kotlin/com/clauderemote/JarvisAskReceiver.kt").readText()
        assertTrue("jarvisYes(" in receiver && "jarvisReply(" in receiver)
        // L1: the answer is never wrapped in a timeout (a late "sent" would read as "nevím")
        val call = receiver.indexOf("orchestrator.jarvisYes(")
        val job = receiver.indexOf("val job = SCOPE.launch {")
        assertTrue(job in 0 until call, "the answer runs in its own job")
        assertTrue("withTimeoutOrNull" !in receiver.substring(job, call), "no timeout around the answer")
        assertTrue("withTimeoutOrNull(LIMIT_MS) { job.join() }" in receiver)
        // L4: Ano and Odpovědět need the phone unlocked
        val notifier = java.io.File("../androidApp/src/main/kotlin/com/clauderemote/JarvisNotifier.kt").readText()
        assertTrue("setAuthenticationRequired(true)" in notifier)
        for (other in listOf("sendReply", "sendCheckedReply", "sendJarvisVoice", "sendJarvisAnswer", "writeInput", "sendKeys")) {
            assertTrue(other !in receiver, "the notification's answer must not take $other")
        }
        val wear = java.io.File("../androidApp/src/main/kotlin/com/clauderemote/PhoneWearService.kt").readText()
        val yes = wear.substring(wear.indexOf("private fun handleJarvisYes("), wear.indexOf("private fun handleHistoryRequest("))
        assertTrue(".jarvisYes(req.id)" in yes)
        assertTrue("sendReply" !in yes && "answer(" !in yes.replace("answerOutcome(", ""), "the wrist's Ano is not the per-session reply")
    }

    // ── kolo 4: two answers at once, against a fake exec ──

    private fun concurrentYes(result: TalkSendResult): Pair<List<String>, List<TalkSendResult>> = runBlocking {
        val o = orch()
        o.hear("q", "jask", 1_000)
        val typed = CopyOnWriteArrayList<String>()
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        o.jarvisCheckedSendForTest = { prompt ->
            assertEquals(1, inside.incrementAndGet(), "never two answers in the exec at once")
            typed += prompt
            gate.await() // the first exec is still running while the second tap comes
            inside.decrementAndGet()
            result
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val a = scope.async { o.jarvisYes("q") }
        val b = scope.async { o.jarvisYes("q") } // the second tap (or the watch) meanwhile
        kotlinx.coroutines.delay(200)
        assertEquals(listOf("ano"), typed.toList(), "the second one waits for the first")
        gate.complete(Unit)
        val results = listOf(a.await(), b.await())
        typed.toList() to results
    }

    @Test fun twoConcurrentYesTypeOneAnoAndTheOtherIsAnswered() {
        val (typed, results) = concurrentYes(TalkSendResult.Sent(1_000))
        assertEquals(listOf("ano"), typed)
        assertEquals(1, results.count { it is TalkSendResult.Sent })
        assertEquals(listOf(TalkSendResult.Refused(JarvisSays.ANSWERED)), results.filterIsInstance<TalkSendResult.Refused>())
        assertTrue("q" in orchestrator!!.jarvisAlerts.state.value.answered)
        assertEquals(null, JarvisSays.ask(orchestrator!!.jarvisAlerts.state.value, System.currentTimeMillis()))
    }

    @Test fun aMaybeLocksTheQuestionToo() {
        val (typed, results) = concurrentYes(TalkSendResult.Maybe("?"))
        assertEquals(listOf("ano"), typed, "it may have gone in: no second ano")
        assertEquals(1, results.count { it is TalkSendResult.Maybe })
        assertEquals(listOf(TalkSendResult.Refused(JarvisSays.ANSWERED)), results.filterIsInstance<TalkSendResult.Refused>())
    }

    @Test fun aRefusedAnswerLeavesItOpenForTheNextOne() {
        val (typed, results) = concurrentYes(TalkSendResult.Refused("Jarvis teď nemůže přijmout"))
        assertEquals(listOf("ano", "ano"), typed, "nothing went in: the second tap may try")
        assertTrue(results.all { it is TalkSendResult.Refused && it.sentence.startsWith("Jarvis teď") })
        assertTrue(orchestrator!!.jarvisAlerts.state.value.answered.isEmpty())
    }

    @Test fun aVoicePromptWaitsForAnAnswerInFlight() = runBlocking {
        val o = orch()
        o.hear("q", "jask", 1_000)
        val order = CopyOnWriteArrayList<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        o.jarvisCheckedSendForTest = { prompt ->
            order += "start $prompt"
            if (prompt == "ano") gate.await()
            order += "end $prompt"
            TalkSendResult.Sent()
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val yes = scope.async { o.jarvisYes("q") }
        kotlinx.coroutines.delay(100)
        val voice = scope.async { o.sendJarvisVoice("[hlas] co je nového") }
        kotlinx.coroutines.delay(200)
        assertEquals(listOf("start ano"), order.toList(), "the spoken prompt waits for the answer's exec")
        gate.complete(Unit)
        yes.await(); voice.await()
        assertEquals(listOf("start ano", "end ano", "start [hlas] co je nového", "end [hlas] co je nového"), order.toList())
    }
}
