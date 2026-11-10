package com.clauderemote.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.clauderemote.util.FileLogger
import com.clauderemote.voice.conversation.JarvisAloudQueue
import com.clauderemote.voice.conversation.JarvisHeadsetSetting
import com.clauderemote.voice.conversation.JarvisAloud
import com.clauderemote.voice.conversation.JarvisTalk
import com.clauderemote.voice.conversation.TalkPhase

/**
 * Jarvis' own sentences read in the headset when no conversation runs (F5c, 2026-11-10): Soniox TTS (the phone's
 * voice without a key), no microphone, a short ducking audio focus (music only gets quieter). Only while a headset is
 * connected — Bluetooth A2DP/headset or wired; never the loudspeaker. One at a time and never over another reading
 * ([JarvisAloudQueue]). After a jask the headset's button starts the conversation mode for
 * [JarvisAloudQueue.ASK_WINDOW_MS], so Lukáš can answer by voice.
 *
 * Everything here runs on the main thread (offer posts to it).
 */
object JarvisHeadsetVoice {
    private const val TAG = "JarvisAloud"
    private const val BUSY_RETRY_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private val queue = JarvisAloudQueue()
    private var reading = false
    private var retryPosted = false
    private var askSession: MediaSession? = null
    private var askEnd: Runnable? = null
    /** The reading now under way; a late done of a reading stopped by a lost headset matches no more. */
    private var readGen = 0
    private var lossWatch: HeadsetLossWatch? = null
    @Volatile private var appContext: Context? = null

    /** Once from MainActivity: the setting for the Velín's switch, and the context the reader speaks with. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        checkConstants()
        val settings = com.clauderemote.storage.AppSettings(com.clauderemote.storage.PlatformPreferences(app))
        JarvisAloud.headset = object : JarvisHeadsetSetting {
            override var enabled: Boolean
                get() = settings.readJarvisInHeadset
                set(value) { settings.readJarvisInHeadset = value }
        }
    }

    /** "Číst Jarvise ve sluchátkách" (Velín → Jarvis). */
    fun enabled(): Boolean = constantsOk && (JarvisAloud.headset?.enabled ?: false)

    private val ttsAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * A headset is where Jarvis' reading would play right now (rev-f5c-app M1): on Android 13+ the route of a media
     * speech stream itself, below it a headset among the outputs. An HFP-only headset (SCO) or a BLE speaker is not
     * one ([com.clauderemote.voice.conversation.HeadsetRoute]).
     */
    fun connected(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val routed = if (android.os.Build.VERSION.SDK_INT >= 33) am.getAudioDevicesForAttributes(ttsAttributes).map { it.type } else null
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        com.clauderemote.voice.conversation.HeadsetRoute.headsetNow(routed, outputs)
    }.getOrDefault(false)

    /** A headset device is still an output at all (the loss watch: a route switch to SCO is no loss). */
    fun present(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        com.clauderemote.voice.conversation.HeadsetRoute.present(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    }.getOrDefault(false)

    /** The pure rule mirrors Android's constants by value: a mismatch would read into the loudspeaker. Logged once. */
    private fun checkConstants() {
        val r = com.clauderemote.voice.conversation.HeadsetRoute
        val ok = r.WIRED_HEADSET == AudioDeviceInfo.TYPE_WIRED_HEADSET && r.WIRED_HEADPHONES == AudioDeviceInfo.TYPE_WIRED_HEADPHONES &&
            r.BLUETOOTH_SCO == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && r.BLUETOOTH_A2DP == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
            r.USB_HEADSET == AudioDeviceInfo.TYPE_USB_HEADSET &&
            (android.os.Build.VERSION.SDK_INT < 31 ||
                (r.BLE_HEADSET == AudioDeviceInfo.TYPE_BLE_HEADSET && r.BLE_SPEAKER == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        if (!ok) {
            FileLogger.log(TAG, "AudioDeviceInfo constants differ from HeadsetRoute: headset reading switched off")
            constantsOk = false
        }
    }

    @Volatile private var constantsOk = true

    /** Read [text] in the headset when its turn comes; [ask] = a jask (the button may start the conversation after it). */
    fun offer(text: String, ask: Boolean) {
        main.post {
            queue.offer(text, ask, System.currentTimeMillis())
            pump()
        }
    }

    /** Another reading or a conversation holds the audio: wait for it, never cut it off. */
    private fun busy(): Boolean = TtsPlayback.controller != null || JarvisTalk.state.value.phase != TalkPhase.OFF

    private fun pump() {
        val ctx = appContext ?: return
        if (reading) return
        if (busy()) {
            // a conversation started meanwhile takes what waits (read there, behind Lukáš's words)
            if (JarvisTalk.state.value.phase != TalkPhase.OFF) {
                generateSequence { queue.take(System.currentTimeMillis()) }.forEach { JarvisTalk.report(it.text) }
                return
            }
            if (!retryPosted && queue.size > 0) {
                retryPosted = true
                main.postDelayed({ retryPosted = false; pump() }, BUSY_RETRY_MS)
            }
            return
        }
        val item = queue.take(System.currentTimeMillis()) ?: return
        // checked again at the moment of reading: taken off meanwhile = not read (never the loudspeaker)
        if (!enabled() || !connected(ctx)) {
            FileLogger.log(TAG, "skipped: ${if (!enabled()) "switched off" else "no headset any more"}")
            queue.clear()
            return
        }
        reading = true
        closeAskWindow()
        val gen = ++readGen
        // the headset leaves mid-sentence: stop now, drop what waits, never finish on the loudspeaker
        val stopNow = {
            if (gen == readGen) {
                readGen++
                endWatch()
                queue.clear()
                reading = false
                SonioxTts.stop()
                TtsHolder.stop()
            }
        }
        lossWatch = HeadsetLossWatch(ctx, onStopReading = stopNow, onLost = stopNow).also { it.start() }
        speak(ctx, item.text) {
            main.post {
                if (gen != readGen) return@post
                endWatch()
                reading = false
                if (item.ask && queue.size == 0) openAskWindow(ctx)
                pump()
            }
        }
    }

    private fun endWatch() {
        lossWatch?.stop()
        lossWatch = null
    }

    private fun speak(ctx: Context, text: String, onDone: () -> Unit) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        fun done() { if (once.compareAndSet(false, true)) onDone() }
        val cfg = sonioxConfig(ctx)
        FileLogger.log(TAG, "reading ${text.length} chars in the headset (${if (cfg.apiKey.isBlank()) "system TTS" else "Soniox"})")
        if (cfg.apiKey.isBlank()) {
            TtsHolder.speak(ctx, text, { done() }, null)
            return
        }
        SonioxTts.speak(
            ctx, cfg.apiKey, cfg.ttsVoice, text, ttsSpeechRate(ctx),
            onFinish = { done() },
            onError = { msg -> FileLogger.log(TAG, "Soniox TTS failed: $msg"); done() },
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
    }

    /**
     * After a jask: a media session in "playing" for [JarvisAloudQueue.ASK_WINDOW_MS], so the headset's button lands
     * here (this app played audio last) and starts the conversation mode. Android allows a microphone service from the
     * background only in some states: when the start is refused, the reason is read into the headset.
     */
    private fun openAskWindow(ctx: Context) {
        closeAskWindow()
        val ms = MediaSession(ctx, "JarvisAsk")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ke = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (ke != null && ke.keyCode in BUTTON_KEYS) {
                    if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) pressed(ctx)
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() = pressed(ctx)
            override fun onPause() = pressed(ctx)
        })
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        ms.isActive = true
        askSession = ms
        val end = Runnable { closeAskWindow() }
        askEnd = end
        main.postDelayed(end, JarvisAloudQueue.ASK_WINDOW_MS)
        FileLogger.log(TAG, "ask window open: the headset button starts the conversation for ${JarvisAloudQueue.ASK_WINDOW_MS / 1000} s")
    }

    private fun pressed(ctx: Context) {
        if (askSession == null) return
        closeAskWindow()
        FileLogger.log(TAG, "headset button after a question: starting the conversation")
        JarvisTalk.start()
        // the start is checked in steps (permission, key, the microphone service); a refusal leaves a sentence
        main.postDelayed({
            val problem = JarvisTalk.problem.value
            if (problem != null && JarvisTalk.state.value.phase == TalkPhase.OFF && connected(ctx)) {
                FileLogger.log(TAG, "conversation did not start: $problem")
                queue.offer(problem, false, System.currentTimeMillis())
                pump()
            }
        }, 2_000L)
    }

    private fun closeAskWindow() {
        askEnd?.let { main.removeCallbacks(it) }
        askEnd = null
        askSession?.let { runCatching { it.isActive = false; it.release() } }
        askSession = null
    }

    private val BUTTON_KEYS = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
    )
}

/**
 * F5c: watches for the headset going away while Jarvis is read into it — an [AudioDeviceCallback] (the device left
 * the output list) and [AudioManager.ACTION_AUDIO_BECOMING_NOISY] (the system's own "audio is about to come out of the
 * speaker", which arrives before the list catches up). [onLost] runs once, on the main thread, and must stop the
 * reading at once: the rest would otherwise come out of the phone's loudspeaker. Armed only once a headset is an
 * output ([HeadsetLoss]); a conversation on the phone's own speaker is never stopped by it.
 */
internal class HeadsetLossWatch(
    private val context: Context,
    /** Stop the reading now (BECOMING_NOISY or the headset gone). May come more than once. */
    private val onStopReading: () -> Unit,
    /** The headset is no output any more (once). */
    private val onLost: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)
    @Volatile private var armed = false
    @Volatile private var fired = false
    private var receiver: android.content.BroadcastReceiver? = null

    private val callback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (JarvisHeadsetVoice.present(context)) armed = true
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = check(noisy = false)
    }

    private fun check(noisy: Boolean) {
        if (fired) return
        val now = JarvisHeadsetVoice.present(context)
        if (!com.clauderemote.voice.conversation.HeadsetLoss.stopReading(armed, noisy, now)) return
        onStopReading()
        if (com.clauderemote.voice.conversation.HeadsetLoss.lost(armed, now)) {
            fired = true
            FileLogger.log("JarvisAloud", "headset gone (${if (noisy) "becoming noisy" else "device removed"}): reading stopped")
            onLost()
        } else if (noisy) {
            FileLogger.log("JarvisAloud", "becoming noisy: reading stopped, the device list is read again")
            // the list may lag behind the broadcast: a real loss shows up in it a moment later
            main.postDelayed({ check(noisy = false) }, com.clauderemote.voice.conversation.HeadsetLoss.RECHECK_MS)
        }
    }

    /** True when it is armed (a headset is an output now). */
    fun start(): Boolean {
        armed = JarvisHeadsetVoice.present(context)
        runCatching { am?.registerAudioDeviceCallback(callback, main) }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) main.post { check(noisy = true) }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, r, android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = r
        }
        return armed
    }

    fun stop() {
        runCatching { am?.unregisterAudioDeviceCallback(callback) }
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null
    }
}
