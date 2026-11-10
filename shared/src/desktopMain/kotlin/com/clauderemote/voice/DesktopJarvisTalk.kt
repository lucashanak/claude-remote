package com.clauderemote.voice

import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.util.FileLogger
import com.clauderemote.voice.conversation.JarvisTalk
import com.clauderemote.voice.conversation.JarvisTalkDriver
import com.clauderemote.voice.conversation.JarvisTalkHost
import com.clauderemote.voice.conversation.OrchestratorTalkLink
import com.clauderemote.voice.conversation.TalkEars
import com.clauderemote.voice.conversation.TalkMouth
import com.clauderemote.voice.conversation.TalkTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Jarvis' conversation mode on the desktop (F5a kolo 2): the same driver as on the phone, with the desktop Soniox
 * dictation as the ears and [DesktopReadAloud] (Soniox, `say`/spd-say without a key) as the mouth. No media keys on
 * the desktop, so the buttons are the app's ("Mluvit s Jarvisem", Odeslat / Přerušit / Ukončit, Ctrl+Shift+P).
 */
object DesktopJarvisTalk {
    @Volatile private var driver: JarvisTalkDriver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install(orchestrators: StateFlow<SessionOrchestrator?>) {
        val link = OrchestratorTalkLink(orchestrators)
        JarvisTalk.host = object : JarvisTalkHost {
            override fun start() {
                if (desktopSonioxApiKey().isBlank()) {
                    JarvisTalk.report("Chybí Soniox API klíč (Nastavení → Voice).")
                    return
                }
                synchronized(this@DesktopJarvisTalk) {
                    if (driver != null) return
                    val d = JarvisTalkDriver(scope, DesktopTalkEars(), DesktopTalkMouth(), link, onState = { JarvisTalk.publish(it) })
                    driver = d
                    FileLogger.log(TAG, "conversation started")
                    d.start()
                    scope.launch {
                        d.join()
                        synchronized(this@DesktopJarvisTalk) { if (driver === d) driver = null }
                        FileLogger.log(TAG, "conversation ended")
                    }
                }
            }

            override fun stop() { driver?.stop() }

            override fun button() { driver?.button() }

            override fun report(text: String): Boolean {
                val d = driver ?: return false
                d.report(text)
                return true
            }
        }
    }

    private const val TAG = "JarvisTalk"
}

/** One utterance at a time through the desktop Soniox dictation; the end of speech after 1.2 s of silence. */
internal class DesktopTalkEars : TalkEars {
    private var current: DesktopDictation? = null
    /** A partial with words arrived in the utterance now open (F5c: Jarvis' report then waits for it). */
    @Volatile private var words = false

    override fun hearing(): Boolean = words

    @Synchronized override fun listen(onHeard: (String) -> Unit, onError: (String) -> Unit) {
        current?.stop()
        words = false
        val d = DesktopDictation(
            apiKey = desktopSonioxApiKey(),
            silenceMs = END_OF_SPEECH_MS,
            onPartial = { p -> if (p.isNotBlank()) words = true },
            onFinal = onHeard,
            onError = onError,
        )
        current = d
        d.start()
    }

    @Synchronized override fun finish() { current?.stop() }

    @Synchronized override fun cancel() { current?.stop(); current = null; words = false }

    private companion object {
        const val END_OF_SPEECH_MS = 1_200
    }
}

/**
 * [DesktopReadAloud]: Soniox when there is a key, the system synthesiser otherwise; its onDone comes once. Stop and
 * speak run in order on one thread of their own: a stop may fork `spd-say -C` (blocking, must not hold the driver's
 * loop), and an asynchronous stop that overtook the next speak would cut that one off.
 */
internal class DesktopTalkMouth : TalkMouth {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "cr-jarvis-talk-mouth").apply { isDaemon = true }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        worker.execute {
            DesktopReadAloud.speak(text) { err ->
                if (err != null) FileLogger.log("JarvisTalk", "read-aloud ended with: $err")
                onDone()
            }
        }
    }

    override fun stop() { worker.execute { DesktopReadAloud.stop() } }

    override fun tone(tone: TalkTone) {
        // no tone engine on the desktop; the state line says the same (a system beep would be the error sound)
    }
}
