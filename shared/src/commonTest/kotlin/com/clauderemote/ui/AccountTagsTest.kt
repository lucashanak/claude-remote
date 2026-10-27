package com.clauderemote.ui

import androidx.compose.ui.graphics.Color
import com.clauderemote.model.ClaudeAccount
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which account chip each session row in the drawer and side panel gets. */
class AccountTagsTest {

    private fun session(id: String, slug: String?) = ClaudeSession(
        id = id,
        server = SshServer(id = "srv", name = "srv", host = "example.com", username = "user"),
        folder = "~/x",
        mode = ClaudeMode.NORMAL,
        model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-srv-x--$id",
        connectionType = ConnectionType.SSH,
        accountSlug = slug,
    )

    private val palette = listOf(Color.Red, Color.Green, Color.Blue)
    private val colorsInOrder: (List<String>) -> Map<String, Color> =
        { slugs -> slugs.withIndex().associate { (i, s) -> s to palette[i % palette.size] } }

    private val default = ClaudeAccount(slug = "default", email = "lukashanak@nekrachni.cz", isDefault = true)
    private val kontexta = ClaudeAccount(slug = "lukas-kontexta-cz", email = "lukas@kontexta.cz")

    @Test
    fun singleAccountShowsNoChips() {
        // Every row would carry the same chip — noise, not information.
        val tags = buildAccountTags(listOf(default), listOf(session("a", null)), colorsInOrder)
        assertTrue(tags.isEmpty())
    }

    @Test
    fun nullSlugIsTheDefaultAccount() {
        val tags = buildAccountTags(
            listOf(default, kontexta),
            listOf(session("a", null), session("b", "lukas-kontexta-cz")),
            colorsInOrder,
        )
        assertEquals("LN", tags[session("a", null).accountKey]?.label)
        assertEquals("LK", tags[session("b", "lukas-kontexta-cz").accountKey]?.label)
    }

    @Test
    fun coloursFollowTheAccountListLikeTheTopBarChip() {
        // The top bar assigns over the account list alone; a session-only slug
        // appended after it must not shift the colours of the listed accounts.
        val tags = buildAccountTags(
            listOf(default, kontexta),
            listOf(session("x", "other-server-acct"), session("a", null)),
            colorsInOrder,
        )
        assertEquals(Color.Red, tags["default"]?.color)
        assertEquals(Color.Green, tags["lukas-kontexta-cz"]?.color)
        assertEquals(Color.Blue, tags["other-server-acct"]?.color)
    }

    @Test
    fun unprobedSlugFallsBackToSlugInitials() {
        // Before the account probe returns, sessions on two accounts still differ.
        val tags = buildAccountTags(
            emptyList(),
            listOf(session("a", null), session("b", "lukas-kontexta-cz")),
            colorsInOrder,
        )
        assertEquals("DE", tags["default"]?.label)
        assertEquals("LU", tags["lukas-kontexta-cz"]?.label)
    }
}
