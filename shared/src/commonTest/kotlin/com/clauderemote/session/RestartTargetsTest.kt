package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which sessions a fleet-wide restart touches.
 *
 * Worth pinning even though the rule is three lines: this is the one place
 * where "restart everything" decides what NOT to kill, and the failure mode is
 * silent — a session restarted without a conversation to resume comes back
 * empty, and the user finds out by reading a blank pane, not by an error.
 */
class RestartTargetsTest {

    private fun session(
        id: String,
        status: SessionStatus = SessionStatus.ACTIVE,
        claudeSessionId: String? = "uuid-$id",
    ) = ClaudeSession(
        id = id,
        server = SshServer(id = "srv", name = "srv", host = "example.com", username = "user"),
        folder = "~/x",
        mode = ClaudeMode.NORMAL,
        model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-srv-x--$id",
        connectionType = ConnectionType.SSH,
        status = status,
        claudeSessionId = claudeSessionId,
    )

    @Test
    fun takesConnectedSessionsThatHaveAConversation() {
        val tabs = listOf(session("a"), session("b"))
        assertEquals(listOf("a", "b"), RestartTargets.eligible(tabs))
    }

    @Test
    fun skipsSessionsWithNoLiveConnection() {
        // A restart there is a no-op that still costs an SSH round trip, and on
        // a forty-session pass those stack up into a stall.
        val tabs = listOf(
            session("ok"),
            session("down", status = SessionStatus.DISCONNECTED),
            session("broken", status = SessionStatus.ERROR),
            session("starting", status = SessionStatus.CONNECTING),
        )
        assertEquals(listOf("ok"), RestartTargets.eligible(tabs))
    }

    @Test
    fun skipsSessionsWithNothingToResume() {
        // `--resume` on a session nobody has spoken to exits with "No
        // conversation found" — the orchestrator refuses it for that reason, so
        // the fleet pass must not spend a round trip discovering it again.
        val tabs = listOf(session("fresh", claudeSessionId = null), session("blank", claudeSessionId = "  "), session("real"))
        assertEquals(listOf("real"), RestartTargets.eligible(tabs))
    }

    @Test
    fun keepsTabOrderSoTheUserCanPredictWhichPaneGoesNext() {
        val tabs = listOf(session("z"), session("m"), session("a"))
        assertEquals(listOf("z", "m", "a"), RestartTargets.eligible(tabs))
    }

    @Test
    fun emptyFleetSelectsNothing() {
        assertEquals(emptyList(), RestartTargets.eligible(emptyList()))
    }
}
