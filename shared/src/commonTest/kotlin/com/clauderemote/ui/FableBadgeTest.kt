package com.clauderemote.ui

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.latestAssistantModel
import com.clauderemote.session.transcript.TranscriptEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which sessions get the Fable badge in the session lists. */
class FableBadgeTest {

    private fun session(model: ClaudeModel) = ClaudeSession(
        id = "s",
        server = SshServer(id = "srv", name = "srv", host = "example.com", username = "user"),
        folder = "~/x",
        mode = ClaudeMode.NORMAL,
        model = model,
        tmuxSessionName = "claude-srv-x--s",
        connectionType = ConnectionType.SSH,
    )

    private fun answer(id: String, model: String?) =
        TranscriptEntry.AssistantText(id = id, timestamp = null, text = "ok", model = model)

    @Test
    fun liveModelBeatsLaunchModel() {
        // Launched on Opus, switched with /model fable — the transcript knows.
        assertTrue(runsFable(session(ClaudeModel.OPUS), "claude-fable-5-1"))
        // And the reverse: launched on Fable, switched away.
        assertFalse(runsFable(session(ClaudeModel.FABLE), "claude-opus-5-5"))
    }

    @Test
    fun launchModelCoversASessionThatHasNotAnsweredYet() {
        assertTrue(runsFable(session(ClaudeModel.FABLE), null))
        assertFalse(runsFable(session(ClaudeModel.DEFAULT), null))
    }

    @Test
    fun latestModelSkipsSyntheticErrorTurns() {
        // A rate-limit error logged as model `<synthetic>` is not a model switch.
        val entries = listOf(
            answer("a", "claude-fable-5-1"),
            TranscriptEntry.UserPrompt(id = "u", timestamp = null, text = "hi"),
            answer("b", "<synthetic>"),
        )
        assertEquals("claude-fable-5-1", latestAssistantModel(entries))
    }

    @Test
    fun latestModelIsTheNewestAnswer() {
        val entries = listOf(answer("a", "claude-fable-5-1"), answer("b", "claude-opus-5-5"))
        assertEquals("claude-opus-5-5", latestAssistantModel(entries))
        assertNull(latestAssistantModel(emptyList()))
    }
}
