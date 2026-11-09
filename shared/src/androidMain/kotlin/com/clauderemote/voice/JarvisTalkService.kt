package com.clauderemote.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.clauderemote.util.FileLogger
import com.clauderemote.voice.conversation.JarvisTalk
import com.clauderemote.voice.conversation.JarvisTalkDriver
import com.clauderemote.voice.conversation.JarvisTalkHost
import com.clauderemote.voice.conversation.JarvisVoiceLink
import com.clauderemote.voice.conversation.TalkEars
import com.clauderemote.voice.conversation.TalkMouth
import com.clauderemote.voice.conversation.TalkPhase
import com.clauderemote.voice.conversation.TalkState
import com.clauderemote.voice.conversation.TalkTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android side of Jarvis' conversation mode (F5a): [install] once from MainActivity with the orchestrator's link; the
 * shared UI then starts and stops it through [JarvisTalk].
 */
object AndroidJarvisTalk {
    @Volatile internal var link: JarvisVoiceLink? = null

    /**
     * [orchestrators]: the current orchestrator as it changes (MainActivity replaces it on a recreation); the link
     * follows it on every call (review MED, [OrchestratorTalkLink]).
     */
    fun install(context: Context, orchestrators: kotlinx.coroutines.flow.StateFlow<com.clauderemote.session.SessionOrchestrator?>) {
        if (link == null) link = com.clauderemote.voice.conversation.OrchestratorTalkLink(orchestrators)
        val app = context.applicationContext
        JarvisTalk.host = object : JarvisTalkHost {
            override fun start() {
                // Checked here, in the foreground tap, so the reason is said where he looks (the service has no UI).
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    JarvisTalk.report("Chybí oprávnění mikrofonu – povol ho diktováním v aplikaci.")
                    return
                }
                if (sonioxConfig(app).apiKey.isBlank()) {
                    JarvisTalk.report("Chybí Soniox API klíč (Nastavení → Voice).")
                    return
                }
                if (JarvisTalkService.driver != null) return
                // A microphone FGS may open the mic later only when it was started while the app is in front: this
                // runs from a tap in the app (or the launcher shortcut that just brought it up).
                runCatching {
                    ContextCompat.startForegroundService(app, Intent(app, JarvisTalkService::class.java).setAction(JarvisTalkService.ACTION_START))
                }.onFailure {
                    FileLogger.log(JarvisTalkService.TAG, "start refused: ${it.javaClass.simpleName}: ${it.message}")
                    JarvisTalk.report("Konverzaci nejde spustit (${it.javaClass.simpleName}).")
                }
            }

            override fun stop() { JarvisTalkService.driver?.stop() }

            override fun button() { JarvisTalkService.driver?.button() }
        }
    }
}

/**
 * The running conversation: a `microphone` foreground service (the mic stays usable with the screen off), its
 * notification with "Mluvit"/"Ukončit", a wake lock, and a MediaSession whose play/pause — the headset's button — is
 * the mode's button while it runs (interrupt the reading / finish the utterance / talk while Jarvis thinks). Ends with
 * the driver.
 */
class JarvisTalkService : Service() {
    companion object {
        internal const val TAG = "JarvisTalk"
        const val ACTION_START = "com.clauderemote.jarvistalk.START"
        const val ACTION_STOP = "com.clauderemote.jarvistalk.STOP"
        const val ACTION_BUTTON = "com.clauderemote.jarvistalk.BUTTON"
        private const val CHANNEL = "jarvis_talk"
        private const val NOTIFICATION_ID = 7400
        private const val WAKE_MAX_MS = 30 * 60_000L
        private const val WAKE_RENEW_MS = 10 * 60_000L

        @Volatile internal var driver: JarvisTalkDriver? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var media: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastPressMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> driver?.stop() ?: stopSelf()
            ACTION_BUTTON -> driver?.button()
            ACTION_START -> begin()
            else -> if (driver == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (driver != null) return
        val n = notification(TalkState(phase = TalkPhase.LISTENING))
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }
        if (started.isFailure) {
            val e = started.exceptionOrNull()
            FileLogger.log(TAG, "startForeground refused: ${e?.javaClass?.simpleName}: ${e?.message}")
            JarvisTalk.report("Konverzaci nejde spustit na pozadí – spusť ji z aplikace.")
            // started with startForegroundService: stopping without ANY startForeground crashes the app ("did not
            // call startForeground"). The service also declares specialUse, which needs no while-in-use access.
            runCatching {
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION_ID, n)
            }.onFailure { FileLogger.log(TAG, "fallback startForeground failed too: ${it.javaClass.simpleName}") }
            stopForegroundCompat()
            stopSelf()
            return
        }
        val link = AndroidJarvisTalk.link
        if (link == null) {
            JarvisTalk.report("Aplikace ještě není připravená – zkus to znovu.")
            stopForegroundCompat()
            stopSelf()
            return
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claude-remote:jarvis-talk")
            .apply { setReferenceCounted(false); runCatching { acquire(WAKE_MAX_MS) } }
        setUpMedia()
        // While Soniox reads, the headset's button lands on ITS media session: the hook takes the press for the
        // mode (the reading is left alone, the driver stops it). A focus loss (navigation, a call) never calls the
        // hook, so the reading just waits and the microphone does not open (review MED).
        SonioxTtsPlayer.onMediaButton = { if (driver != null) { press(); true } else false }
        val d = JarvisTalkDriver(
            scope = scope,
            ears = SonioxTalkEars(applicationContext),
            mouth = AndroidTalkMouth(applicationContext),
            link = link,
            onState = { s -> JarvisTalk.publish(s); updateNotification(s) },
        )
        driver = d
        // renewed on a clock, not on state changes: a quiet stretch (Jarvis thinking, a paused reading) must not
        // let it lapse while the mode runs; it ends with the mode
        scope.launch {
            while (driver === d) {
                kotlinx.coroutines.delay(WAKE_RENEW_MS)
                if (driver === d) renewWake()
            }
        }
        FileLogger.log(TAG, "conversation started")
        d.start()
        scope.launch {
            d.join()
            Handler(Looper.getMainLooper()).post { finishService() }
        }
    }

    /** The wake lock lasts [WAKE_MAX_MS] from its last renewal (every [WAKE_RENEW_MS] while the mode runs). */
    private fun renewWake() {
        wake?.let { w -> runCatching { w.acquire(WAKE_MAX_MS) } }
    }

    private fun finishService() {
        SonioxTtsPlayer.onMediaButton = null
        driver = null
        FileLogger.log(TAG, "conversation ended")
        stopForegroundCompat()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    /** The headset's button, debounced: a key that also reaches onPlay/onPause must count once. */
    private fun press() {
        val now = System.currentTimeMillis()
        if (now - lastPressMs < 400) return
        lastPressMs = now
        driver?.button()
    }

    private fun setUpMedia() {
        media = MediaSession(this, "JarvisTalk").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ke = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ke != null && ke.keyCode in BUTTON_KEYS) {
                        if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) press()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() = press()
                override fun onPause() = press()
            })
            // "Playing" so the system routes the media buttons here while the mode runs (our app played audio last).
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            isActive = true
        }
    }

    private val BUTTON_KEYS = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
    )

    private fun notification(s: TalkState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Konverzace s Jarvisem", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Běžící hlasová konverzace s Jarvisem"
                    setShowBadge(false)
                },
            )
        }
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, JarvisTalkService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0x4A54, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis – konverzace")
            .setContentText(JarvisTalk.label(s) + (s.heard?.let { " · „$it“" } ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Mluvit", action(ACTION_BUTTON, 1)).build())
            .addAction(Notification.Action.Builder(null, "Ukončit", action(ACTION_STOP, 2)).build())
        open?.let { b.setContentIntent(it) }
        return b.build()
    }

    private var lastShown: String? = null

    private fun updateNotification(s: TalkState) {
        if (s.phase == TalkPhase.OFF) return
        val key = "${s.phase}|${s.heard}"
        if (key == lastShown) return
        lastShown = key
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(s)) }
    }

    override fun onDestroy() {
        SonioxTtsPlayer.onMediaButton = null
        driver?.stop()
        driver = null
        media?.let { runCatching { it.isActive = false; it.release() } }
        media = null
        wake?.let { w -> runCatching { if (w.isHeld) w.release() } }
        wake = null
        // the driver's own cleanup (mic, TTS) runs in its loop's finally; give it a moment before the scope goes
        Handler(Looper.getMainLooper()).postDelayed({ scope.cancel() }, 1_000)
        super.onDestroy()
    }
}

/** One utterance at a time through Soniox real-time STT; the end of speech after [END_OF_SPEECH_MS] of silence. */
internal class SonioxTalkEars(private val context: Context) : TalkEars {
    @Volatile private var current: SonioxDictation? = null

    override fun listen(onHeard: (String) -> Unit, onError: (String) -> Unit) {
        val key = sonioxConfig(context).apiKey
        postOnMain {
            current?.stop()
            // single-shot: Mic.open takes the Bluetooth headset's mic when one is connected (SCO), else the phone's
            val d = SonioxDictation(context, key, continuous = false, onFinal = onHeard, onError = onError, silenceMs = END_OF_SPEECH_MS)
            current = d
            d.start()
        }
    }

    override fun finish() { postOnMain { current?.stop() } }

    override fun cancel() { postOnMain { current?.stop(); current = null } }

    companion object {
        /** A conversation turn ends sooner than a dictation (the dictation default is 4 s). */
        const val END_OF_SPEECH_MS = 1_200
    }
}

/**
 * Reads through Soniox ([SonioxTts], its own player, MediaSession and audio focus), the on-device TTS when Soniox
 * fails before it got going. The headset's button during a reading reaches the mode through
 * [SonioxTtsPlayer.onMediaButton] (set by the service), never through a pause: a pause from a focus loss just waits.
 */
internal class AndroidTalkMouth(private val context: Context) : TalkMouth {
    private val main = Handler(Looper.getMainLooper())

    override fun speak(text: String, onDone: () -> Unit) {
        val once = AtomicBoolean(false)
        val fellBack = AtomicBoolean(false)
        fun done() { if (once.compareAndSet(false, true)) onDone() }
        val cfg = sonioxConfig(context)
        val rate = ttsSpeechRate(context)
        val startedAt = System.currentTimeMillis()
        postOnMain {
            if (cfg.apiKey.isBlank()) {
                TtsHolder.speak(context, text, { done() }, null)
                return@postOnMain
            }
            SonioxTts.speak(
                context, cfg.apiKey, cfg.ttsVoice, text, rate,
                onFinish = { if (!fellBack.get()) done() },
                onError = { msg ->
                    FileLogger.log(JarvisTalkService.TAG, "Soniox TTS failed: $msg")
                    // nothing has been read yet: say it with the phone's voice instead of nothing
                    if (System.currentTimeMillis() - startedAt < FALLBACK_WINDOW_MS && fellBack.compareAndSet(false, true)) {
                        TtsHolder.speak(context, text, { done() }, null)
                    }
                },
            )
        }
    }

    override fun stop() {
        postOnMain { SonioxTts.stop(); TtsHolder.stop() }
    }

    override fun tone(tone: TalkTone) {
        runCatching {
            val g = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            g.startTone(if (tone == TalkTone.LISTEN) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_PROMPT, 150)
            main.postDelayed({ runCatching { g.release() } }, 400)
        }
    }

    private companion object {
        const val FALLBACK_WINDOW_MS = 4_000L
    }
}
