package com.clauderemote.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [TmuxNameParser] — the tmux session naming convention.
 *
 * The name is the join key between a tab, the live tmux session, the restore
 * manifest and every `-t '=name'` probe, so a name the client believes in but
 * tmux never created is not a cosmetic problem: it splits one session into two
 * tabs and makes every command for it miss.
 */
class TmuxNameParserTest {

    @Test
    fun build_substitutesCharactersTmuxWouldRename() {
        // tmux turns `new-session -s claude-server-nekrachni.plus-yolo` into
        // claude-server-nekrachni_plus-yolo, so the client must ask for the name
        // it is going to get.
        assertEquals(
            "claude-server-nekrachni_plus-yolo",
            TmuxNameParser.build("server", "/home/lucas/nekrachni.plus", isYolo = true)
        )
    }

    @Test
    fun build_substitutesInEverySegment() {
        assertEquals(
            "claude-dev_box-app_v2-yolo--rel_1_2",
            TmuxNameParser.build("dev.box", "/srv/app.v2", isYolo = true, alias = "rel:1.2")
        )
    }

    @Test
    fun build_leavesOrdinaryNamesUntouched() {
        assertEquals(
            "claude-server-claude-remote-yolo--wear",
            TmuxNameParser.build("server", "/home/lucas/claude-remote", isYolo = true, alias = "wear")
        )
    }

    @Test
    fun build_isIdempotent() {
        val once = TmuxNameParser.build("server", "/home/lucas/nekrachni.plus", isYolo = true)
        assertEquals(once, TmuxNameParser.sanitize(once))
    }

    @Test
    fun parse_roundTripsASanitizedName() {
        val name = TmuxNameParser.build("server", "/home/lucas/nekrachni.plus", isYolo = true, alias = "plus")
        val parsed = TmuxNameParser.parse(name, "server")
        // The dot is gone for good — the folder survives on the tab, not in the
        // name — but yolo/alias must still round-trip.
        assertEquals("nekrachni_plus", parsed.folder)
        assertEquals(true, parsed.isYolo)
        assertEquals("plus", parsed.alias)
    }
}
