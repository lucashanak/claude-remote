package com.clauderemote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clauderemote.voice.playTtsOnce

/**
 * Handles the "🔊 Přehrát" action on the Claude-needs-input notification —
 * reads the last assistant message aloud through the user's selected TTS
 * engine (server / Google Cloud / on-device). On-demand, so background
 * notifications never auto-play audio.
 */
class PlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isNotEmpty()) playTtsOnce(context, text)
    }

    companion object {
        const val EXTRA_TEXT = "tts_text"
    }
}
