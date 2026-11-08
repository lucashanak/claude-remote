package com.clauderemote.session

import com.clauderemote.session.service.ClosedRecord
import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisAlertKind
import com.clauderemote.session.service.RestoreResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Jarvis F4: "Vrátit" of a close Jarvis announced — which alert offers it, which record it restores, what it says. */
class JarvisUndoTest {
    private val sid = "11111111-1111-1111-1111-111111111111"
    private val name = "claude-server-kontexta-yolo--soniox"
    private val now = 1_800_000_000L

    private fun iso(epochS: Long) = java.time.Instant.ofEpochSecond(epochS).toString()

    private fun rec(
        tmux: String = name,
        at: Long? = now - 600,
        by: String? = "jarvis",
        sessionId: String? = sid,
        cwd: String? = "/home/u/kontexta",
        reason: String? = null,
    ): ClosedRecord = ClosedRecord(
        v = 1, at = at?.let(::iso), tmuxName = tmux, sessionId = sessionId, launchSessionId = null, transcript = null,
        cwd = cwd, folder = cwd, account = null, model = null, mode = null, flags = emptyList(), effort = null,
        claudeVersion = null, title = null, pid = null, by = by, durable = true, reason = reason,
    )

    private fun alert(
        id: String = "a1",
        kind: JarvisAlertKind = JarvisAlertKind.DONE,
        text: String = "Zavřel jsem soniox.",
        tmux: String? = name,
        server: String = "srv",
    ) = JarvisAlert(id, kind, 1, 1, text, tmux, server)

    // ---- which alert offers "Vrátit" --------------------------------------------------------------------------

    @Test fun onlyADoneAlertSayingZavrelJsemWithATmuxNameIsAClose() {
        assertTrue(JarvisUndo.isCloseAlert(alert()))
        assertFalse(JarvisUndo.isCloseAlert(alert(kind = JarvisAlertKind.INFO)), "the contract's kind is done")
        assertFalse(JarvisUndo.isCloseAlert(alert(kind = JarvisAlertKind.BLOCK)))
        assertFalse(JarvisUndo.isCloseAlert(alert(tmux = null)), "no name, nothing to restore")
        assertFalse(JarvisUndo.isCloseAlert(alert(tmux = " ")))
        assertFalse(JarvisUndo.isCloseAlert(alert(text = "soniox doběhla.")), "a finished task is not a close")
        assertFalse(JarvisUndo.isCloseAlert(alert(text = "Nezavřel jsem soniox.")))
    }

    @Test fun theNotificationOffersTheNewestClosesOncePerSessionAtMostTwo() {
        val pending = listOf( // newest first, as the feed holds them
            alert("a5", tmux = "claude-server-x--c"),
            alert("a4", text = "soniox doběhla.", tmux = "claude-server-x--z"),
            alert("a3", tmux = "claude-server-x--b"),
            alert("a2", tmux = "claude-server-x--c"),
            alert("a1", tmux = "claude-server-x--a"),
        )
        assertEquals(listOf("a5", "a3"), JarvisUndo.undoable(pending).map { it.id })
        assertEquals(listOf("a5", "a3", "a1"), JarvisUndo.undoable(pending, max = 9).map { it.id })
        // the same name on two servers is two sessions
        val two = listOf(alert("b2", server = "s2"), alert("b1", server = "s1"))
        assertEquals(2, JarvisUndo.undoable(two).size)
        assertEquals("soniox", JarvisUndo.alias(name))
        assertEquals("plain", JarvisUndo.alias("plain"))
    }

    // ---- which record it restores ------------------------------------------------------------------------------

    @Test fun picksTheNewestRecordOfTheNameWhenJarvisClosedItWithinADay() {
        val mine = rec(at = now - 3600)
        val records = listOf(rec(tmux = "claude-server-other--x", at = now - 10), mine, rec(at = now - 90_000))
        val p = assertIs<JarvisUndo.Pick.Found>(JarvisUndo.pick(records, name, now))
        assertEquals(mine, p.record)
        assertFalse(p.fresh)
    }

    @Test fun aLaterCloseBySomebodyElseEndsTheUndo() {
        // restored and closed again on the phone since: "Vrátit" must not undo Lukáš's own close
        val records = listOf(rec(at = now - 60, by = "app:phone-1"), rec(at = now - 3600, by = "jarvis"))
        val p = assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(records, name, now))
        assertTrue("někdo jiný" in p.why)
    }

    @Test fun theWindowIsTwentyFourHours() {
        assertIs<JarvisUndo.Pick.Found>(JarvisUndo.pick(listOf(rec(at = now - JarvisUndo.WINDOW_S)), name, now))
        val old = assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(listOf(rec(at = now - JarvisUndo.WINDOW_S - 1)), name, now))
        assertTrue("24 h" in old.why)
        val noTime = assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(listOf(rec(at = null)), name, now))
        assertTrue("kdy" in noTime.why)
    }

    @Test fun noRecordNoFolderOrNoConversation() {
        assertTrue("nenašel" in assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(emptyList(), name, now)).why)
        assertTrue("složku" in assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(listOf(rec(cwd = null, sessionId = null)), name, now)).why)
        // a folder without a conversation to resume: a new conversation there, as the row's tap does
        assertTrue(assertIs<JarvisUndo.Pick.Found>(JarvisUndo.pick(listOf(rec(sessionId = null)), name, now)).fresh)
    }

    // ---- the headless undo calls the restore with that record ------------------------------------------------

    private class FakeRestore(private val result: RestoreResult) {
        val calls = mutableListOf<Pair<ClosedRecord, String?>>()
        suspend fun restore(r: ClosedRecord, fresh: String?): RestoreResult { calls += r to fresh; return result }
    }

    @Test fun vratitRestoresThePickedRecordUnderItsOwnName() = runBlocking {
        val jarvisClose = rec(at = now - 300)
        val fake = FakeRestore(RestoreResult.Restored(1, sid, true, sid))
        val text = JarvisUndo.undo(
            name, "box", now,
            read = { ClosedRead.Ok(listOf(rec(tmux = "claude-server-x--other", at = now - 5), jarvisClose)) },
            restore = fake::restore, newId = { "new-id" },
        )
        assertEquals(listOf<Pair<ClosedRecord, String?>>(jarvisClose to null), fake.calls)
        assertEquals("Vrátil jsem „soniox“.", text)
    }

    @Test fun aRecordWithoutAConversationGetsANewOne() = runBlocking {
        val fake = FakeRestore(RestoreResult.Restored(1, null, true, null))
        JarvisUndo.undo(name, "box", now, read = { ClosedRead.Ok(listOf(rec(sessionId = null))) }, restore = fake::restore, newId = { "new-id" })
        assertEquals("new-id", fake.calls.single().second)
    }

    @Test fun nothingIsRestoredWhenItCannotBeUndone() = runBlocking {
        val fake = FakeRestore(RestoreResult.Restored(1, sid, true, sid))
        val other = JarvisUndo.undo(name, "box", now, read = { ClosedRead.Ok(listOf(rec(by = "app:x"))) }, restore = fake::restore, newId = { "n" })
        val unread = JarvisUndo.undo(name, "box", now, read = { ClosedRead.Unknown }, restore = fake::restore, newId = { "n" })
        val skipped = JarvisUndo.undo(name, "box", now, read = { ClosedRead.Skipped }, restore = fake::restore, newId = { "n" })
        assertTrue(fake.calls.isEmpty())
        assertTrue("někdo jiný" in other)
        assertTrue("nejde načíst" in unread && "box" in unread)
        assertTrue("nejde načíst" in skipped)
    }

    @Test fun choicesAreLeftToTheApp() = runBlocking {
        fun say(r: RestoreResult) = runBlocking {
            JarvisUndo.undo(name, "box", now, read = { ClosedRead.Ok(listOf(rec())) }, restore = { _, _ -> r }, newId = { "n" })
        }
        assertTrue("v aplikaci" in say(RestoreResult.Exists("claude-server-kontexta-yolo--soniox-2")))
        assertTrue("v aplikaci" in say(RestoreResult.NoTranscript("33333333-3333-3333-3333-333333333333")))
        assertEquals("Konverzace „soniox“ už běží v „jinde“.", say(RestoreResult.RunningElsewhere("claude-server-x--jinde")))
        assertEquals("Konverzace „soniox“ už běží.", say(RestoreResult.RunningElsewhere(null)))
        assertTrue("přihlášení" in say(RestoreResult.LoginRequired))
        assertTrue("nedorazila" in say(RestoreResult.Unknown), "an unknown result is said as such, never retried")
    }

    // ---- rev-f4-app fixes ---------------------------------------------------------------------------------------

    @Test fun theNewestRecordIsTheNewestByTimeNotByPlace() {
        // LOW-1: a list that arrives oldest first must not undo an older Jarvis close past a newer close by the app
        val oldestFirst = listOf(rec(at = now - 7200, by = "jarvis"), rec(at = now - 60, by = "app:phone-1"))
        assertTrue("někdo jiný" in assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(oldestFirst, name, now)).why)
        val newer = rec(at = now - 60)
        assertEquals(newer, assertIs<JarvisUndo.Pick.Found>(JarvisUndo.pick(listOf(rec(at = now - 7200), newer), name, now)).record)
        // a record without a time may be the newest: nothing is undone past it
        assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(listOf(rec(at = now - 60), rec(at = null, by = "app:phone-1")), name, now))
        val untimed = listOf(rec(at = now - 60), rec(at = null, by = "jarvis"))
        assertTrue("kdy" in assertIs<JarvisUndo.Pick.No>(JarvisUndo.pick(untimed, name, now)).why)
    }

    @Test fun oneInFlightSetHoldsAnUndoUntilItEnds() = runBlocking {
        // MED-1: a second "Vrátit" of the same session while the first runs does nothing
        val f = JarvisUndoInFlight()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var runs = 0
        val first = async(kotlinx.coroutines.Dispatchers.Default) {
            f.run("srv", name) { runs++; started.complete(Unit); release.await(); "first" }
        }
        started.await()
        assertTrue(f.isRunning("srv", name))
        assertEquals(null, f.run("srv", name) { runs++; "second" })
        assertEquals("other", f.run("srv", "claude-server-x--other") { "other" }, "another session is not blocked")
        assertEquals("other", f.run("srv2", name) { "other" }, "nor the same name on another server")
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals(1, runs)
        assertTrue(f.state.value.isEmpty())
        // a failing undo lets go too
        runCatching { f.run("srv", name) { error("boom") } }
        assertTrue(f.tryEnter("srv", name))
    }

    @Test fun theVelinButtonIsOffWhileItsUndoRuns() {
        val a = alert(server = "srv")
        assertEquals(true, JarvisUndo.undoButtonEnabled(a, emptySet()))
        assertEquals(false, JarvisUndo.undoButtonEnabled(a, setOf("srv" to name)))
        assertEquals(true, JarvisUndo.undoButtonEnabled(a, setOf("other" to name)))
        assertEquals(null, JarvisUndo.undoButtonEnabled(alert(text = "soniox doběhla."), emptySet()), "no button at all")
    }

    @Test fun onlyTheUndoIntentActionCarriesARequest() {
        // LOW: extras on some other intent are not "Vrátit"
        assertEquals(JarvisUndoRequest("s", name), JarvisUndo.requestFrom(JarvisUndo.INTENT_ACTION, "s", name))
        assertEquals(null, JarvisUndo.requestFrom("android.intent.action.MAIN", "s", name))
        assertEquals(null, JarvisUndo.requestFrom(null, "s", name))
        assertEquals(null, JarvisUndo.requestFrom(JarvisUndo.INTENT_ACTION, "", name))
        assertEquals(null, JarvisUndo.requestFrom(JarvisUndo.INTENT_ACTION, "s", null))
    }

    @Test fun aHeadlessUndoThatDoesNotAnswerIsSaidSoInTime() = runBlocking {
        // LOW: the watch's undo blocks the phone's listener thread; it must end even when the restore never answers
        val text = kotlinx.coroutines.withTimeout(5_000) {
            JarvisUndo.undo(
                name, "box", now, read = { ClosedRead.Ok(listOf(rec())) },
                restore = { _, _ -> kotlinx.coroutines.awaitCancellation() }, newId = { "n" }, timeoutMs = 200,
            )
        }
        assertTrue("neodpovědělo včas" in text && "možná" in text, text)
    }
}
