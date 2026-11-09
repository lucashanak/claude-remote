package com.clauderemote.voice.conversation

import com.clauderemote.session.SessionOrchestrator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The conversation mode's [JarvisVoiceLink] over whichever orchestrator is current: Android replaces it when
 * MainActivity is recreated (and shuts the old one down), so a link that held one orchestrator would report the
 * route lost mid-conversation. Every call reads [current]; Jarvis' turns follow it ([flatMapLatest]). No orchestrator
 * for a moment = not reachable (the route's 5 s grace covers a recreation) and a send is refused, never queued.
 */
class OrchestratorTalkLink(private val current: StateFlow<SessionOrchestrator?>) : JarvisVoiceLink {
    override suspend fun send(prompt: String, stillWanted: () -> Boolean): TalkSendResult =
        current.value?.sendJarvisVoice(prompt, stillWanted) ?: TalkSendResult.Refused(NOT_READY)

    override fun reachable(): Boolean = current.value?.jarvisVoiceReachable() == true

    @OptIn(ExperimentalCoroutinesApi::class)
    override val answers: Flow<JarvisTurn> = current.flatMapLatest { it?.jarvisTurns() ?: emptyFlow() }

    companion object {
        const val NOT_READY = "Aplikace se zrovna znovu spouští, neposláno."
    }
}
