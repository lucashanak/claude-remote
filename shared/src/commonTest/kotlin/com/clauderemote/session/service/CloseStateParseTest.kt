package com.clauderemote.session.service

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SessionPersistenceService.parseCloseState] — the reading half of the
 * closed-elsewhere probe. It decides whether a reconnect attaches, rebuilds, or
 * DELETES the tab, so every ambiguous input must fail open (never "closed").
 */
class CloseStateParseTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val name = "claude-server-abc"

    private fun parse(raw: String) = SessionPersistenceService.parseCloseState(raw, name, json)

    private fun manifest(vararg names: String) =
        names.joinToString(",", "[", "]") {
            """{"id":"i-$it","serverId":"srv","folder":"/w","tmuxSessionName":"$it"}"""
        }

    @Test
    fun tombstonedSessionIsReported() {
        val state = parse("TOMBSTONED:1\n---\n${manifest(name)}\n")
        assertEquals(RemoteCloseState(tracked = true, tombstoned = true), state)
    }

    @Test
    fun trackedAndUntrackedAreDistinguished() {
        assertEquals(
            RemoteCloseState(tracked = true, tombstoned = false),
            parse("TOMBSTONED:0\n---\n${manifest(name, "claude-server-other")}\n"),
        )
        assertEquals(
            RemoteCloseState(tracked = false, tombstoned = false),
            parse("TOMBSTONED:0\n---\n${manifest("claude-server-other")}\n"),
        )
    }

    /** Right after a close the manifest is legitimately empty — that IS untracked. */
    @Test
    fun emptyManifestReadsAsUntracked() {
        assertEquals(RemoteCloseState(tracked = false, tombstoned = false), parse("TOMBSTONED:0\n---\n"))
        assertEquals(RemoteCloseState(tracked = false, tombstoned = false), parse("TOMBSTONED:0\n---\n[]\n"))
    }

    /**
     * No verdict line ⇒ the probe never ran (transport truncation, no shell, no
     * flock). Null, so the caller falls open instead of reading silence as a
     * close.
     */
    @Test
    fun missingVerdictLineIsUnknown() {
        assertNull(parse(""))
        assertNull(parse("bash: flock: command not found\n"))
        assertNull(parse(manifest(name)))
    }

    /**
     * A corrupt manifest must read as STILL TRACKED. Truncated/garbled
     * sessions.json is a documented state on this server (ENOSPC, unclean
     * shutdown), and reading it as "untracked" is what deletes live tabs.
     */
    @Test
    fun corruptManifestFailsOpenToTracked() {
        val state = parse("TOMBSTONED:0\n---\n[{\"id\":\"s1\",\n")
        assertEquals(RemoteCloseState(tracked = true, tombstoned = false), state)
    }

    /** A tombstone still parses when the manifest half is garbage. */
    @Test
    fun tombstoneSurvivesACorruptManifest() {
        assertTrue(parse("TOMBSTONED:1\n---\nnot json at all")?.tombstoned == true)
    }

    /** Login banners / MOTD noise before the verdict must not hide it. */
    @Test
    fun leadingNoiseIsTolerated() {
        val state = parse("Welcome to the server\nTOMBSTONED:1\n---\n[]\n")
        assertEquals(RemoteCloseState(tracked = false, tombstoned = true), state)
    }
}
