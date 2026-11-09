package com.clauderemote.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import com.clauderemote.util.FileLogger

/**
 * Read-aloud through Soniox's real-time TTS, as a small media player.
 *
 * Why it is shaped like this (each one is a failure from the car, 2026-11-05):
 *  - One request per reply failed on long replies: Soniox caps a stream at
 *    5000 bytes of text (400) and 2 minutes of audio (413). The text is now cut
 *    into paragraph-sized segments ([ttsSegments]), each its own stream on one
 *    shared WebSocket (`stream_id`, up to 2 in flight — the account allows 3).
 *  - A dropped signal ended the reading and the rest was lost. Audio is now
 *    downloaded into memory ahead of playback (μ-law, 8 kB/s, so a 10-minute
 *    reply is ~5 MB) and played from there: a dropout plays on from what is
 *    already downloaded while the socket reconnects with backoff, and a
 *    segment cut mid-download is fetched again and joined ~3 s before the
 *    point the old copy ran out. Giving up (after 5 min) leaves the player
 *    paused at that segment, so ▶ continues instead of starting over.
 *  - No way to pause or skip. Pause/resume, previous/next paragraph and −10 s
 *    work from the in-app bar ([TtsPlayback]), the media notification, the lock
 *    screen and the car's / headset's media buttons ([MediaSession]). Audio
 *    focus is transient: music pauses while Claude reads; a phone call pauses
 *    the reading and it resumes afterwards.
 *
 * The protocol, buffers and player state machine are the shared
 * [SonioxTtsSession] (commonMain, also used by desktop); this file is only the
 * Android side of it: [AudioTrackSink] and [AndroidTtsHost] (focus, network,
 * MediaSession, notification).
 *
 * Audio is G.711 μ-law at 8000 Hz (the only rate Soniox allows for μ-law),
 * expanded to 16-bit PCM by table lookup — no MediaCodec, which used to stall
 * the stream. Reading speed is applied by AudioTrack's pitch-preserving
 * playbackParams, because Soniox's own `speed` rejects anything outside
 * 0.7–1.3.
 */
internal object SonioxTts {
    private const val TAG = "SonioxTts"

    fun speak(
        context: Context,
        apiKey: String,
        voice: String,
        text: String,
        rate: Float,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)?,
    ) {
        stop() // supersede anything in flight
        if (apiKey.isBlank()) {
            onError?.let { postOnMain { it("Chybí Soniox API klíč") } }
            postOnMain(onFinish)
            return
        }
        val segments = ttsSegments(text)
        if (segments.isEmpty()) {
            postOnMain(onFinish)
            return
        }
        FileLogger.log(TAG, "speak: ${text.length} chars → ${segments.size} segments " +
            segments.joinToString(",", "[", "]") { it.length.toString() })
        val ctx = context.applicationContext
        val host = AndroidTtsHost(ctx, rate)
        val session = SonioxTtsSession(
            segments = segments,
            voice = voice,
            sink = AudioTrackSink(rate),
            sockets = sonioxOkHttpSockets(apiKey),
            host = host,
            onFinish = onFinish,
            onError = onError,
            tag = TAG,
        )
        // Bound before start(): start asks for focus, and a focus change in
        // that window must already reach the session.
        host.session = session
        SonioxTtsPlayer.start(session)
    }

    fun stop() = SonioxTtsPlayer.stop()
}

private val ttsAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** AudioTrack in MODE_STREAM: a blocking write returns short when paused, as [TtsAudioSink] requires. */
internal class AudioTrackSink(private val rate: Float) : TtsAudioSink {
    @Volatile private var track: AudioTrack? = null

    override fun open(sampleRate: Int): Int {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(ttsAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (rate != 1.0f) runCatching {
            t.playbackParams = t.playbackParams.setSpeed(rate.coerceIn(0.5f, 2.5f))
        }
        track = t
        return t.bufferSizeInFrames * 2
    }

    override fun write(pcm: ByteArray, off: Int, len: Int): Int =
        track?.write(pcm, off, len) ?: -1

    override fun play() { track?.play() }
    override fun pause() { track?.pause() }
    override fun flush() { track?.flush() }

    override fun halt() {
        track?.let { t -> runCatching { t.pause(); t.flush(); t.stop() } }
    }

    override fun release() {
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    override fun describe(): String =
        "AudioTrack speed=${track?.let { t -> runCatching { t.playbackParams.speed }.getOrNull() } ?: rate} (asked $rate)"
}

/** Focus, network, MediaSession and the media notification for one session. */
private class AndroidTtsHost(private val ctx: Context, private val rate: Float) : SonioxTtsHost {
    private val tag = "SonioxTts"
    @Volatile var session: SonioxTtsSession? = null
    @Volatile private var ended = false
    private var media: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private val audioManager = ctx.getSystemService(AudioManager::class.java)

    override fun post(action: () -> Unit) = postOnMain(action)

    override fun onStart(session: SonioxTtsSession) {
        this.session = session
        postOnMain { setUpMediaControls(session) }
        watchNetwork(session)
    }

    override fun onState(state: TtsPlayerState) {
        postOnMain { updateMediaControls(state) }
    }

    override fun onEnd(finish: () -> Unit) {
        ended = true
        focusRequest?.let { req -> runCatching { audioManager?.abandonAudioFocusRequest(req) } }
        focusRequest = null
        netCallback?.let { cb ->
            runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        postOnMain {
            receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
            receiver = null
            media?.let { runCatching { it.isActive = false; it.release() } }
            media = null
            // A newer session may already own the notification id; it attaches
            // before posting, so checking here on main is race-free.
            if (TtsPlayback.controller == null) runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
            finish()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val s = session ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> s.pause()
            // A call or a navigation prompt: pause, and come back when it ends.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> s.transientPause()
            AudioManager.AUDIOFOCUS_GAIN -> s.transientResume()
        }
    }

    /** @return false when the system refused focus (e.g. during a call). */
    override fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ttsAttributes)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = req
        val res = runCatching { audioManager?.requestAudioFocus(req) }.getOrNull()
        if (res == AudioManager.AUDIOFOCUS_REQUEST_FAILED) FileLogger.log(tag, "audio focus refused")
        return res != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun watchNetwork(s: SonioxTtsSession) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Signal is back: skip the rest of the backoff.
            override fun onAvailable(network: Network) = s.networkAvailable()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun setUpMediaControls(s: SonioxTtsSession) {
        if (ended) return
        val ms = MediaSession(ctx, "ClaudeRemoteReadAloud")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = s.play()
            override fun onPause() = s.pause()
            override fun onSkipToNext() = s.next()
            override fun onSkipToPrevious() = s.previous()
            override fun onRewind() = s.rewind(10)
            override fun onFastForward() = s.next()
            override fun onStop() = s.stop()
            // Hardware play/pause only goes to the hook when one is set; with
            // none, super keeps today's handling (onPlay/onPause above). The
            // lock screen and notification call onPlay/onPause directly, so
            // they never reach the hook.
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                if (SonioxTtsPlayer.onMediaButton != null) {
                    @Suppress("DEPRECATION")
                    val ev = mediaButtonIntent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev != null && ev.keyCode in PLAY_PAUSE_KEYS) {
                        if (ev.action == android.view.KeyEvent.ACTION_DOWN && ev.repeatCount == 0) s.mediaButton()
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                when (action) {
                    CUSTOM_REWIND -> s.rewind(10)
                    CUSTOM_STOP -> s.stop()
                }
            }
        })
        ms.isActive = true
        media = ms

        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_PREV -> s.previous()
                    ACTION_REWIND -> s.rewind(10)
                    ACTION_TOGGLE -> s.toggle()
                    ACTION_NEXT -> s.next()
                    ACTION_STOP -> s.stop()
                }
            }
        }
        val filter = IntentFilter().apply {
            listOf(ACTION_PREV, ACTION_REWIND, ACTION_TOGGLE, ACTION_NEXT, ACTION_STOP).forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(ctx, rcv, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = rcv
        s.republish()
    }

    private fun updateMediaControls(s: TtsPlayerState) {
        val ms = media ?: return
        if (ended) return
        val pbState = when (s.phase) {
            TtsPhase.PLAYING -> PlaybackState.STATE_PLAYING
            TtsPhase.BUFFERING, TtsPhase.WAITING_NETWORK -> PlaybackState.STATE_BUFFERING
            TtsPhase.PAUSED -> PlaybackState.STATE_PAUSED
            TtsPhase.INTERRUPTED -> PlaybackState.STATE_PAUSED
        }
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_STOP,
                )
                .setState(pbState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, rate)
                // Android 13+ builds its media controls from these, not from
                // the notification's buttons — so −10 s and Konec go here too.
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_REWIND, "Zpět o 10 s", android.R.drawable.ic_media_rew).build(),
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Konec", android.R.drawable.ic_menu_close_clear_cancel).build(),
                )
                .build(),
        )
        val title = "Claude · odstavec ${s.segment + 1}/${s.segmentCount}"
        ms.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.preview)
                .build(),
        )
        postNotification(ms, s, title)
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is requested by the app; without it notify() is a no-op
    private fun postNotification(ms: MediaSession, s: TtsPlayerState, title: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Předčítání", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ovládání předčítání zpráv (pauza, odstavce)"
                    setShowBadge(false)
                },
            )
        }
        fun pi(action: String, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(action).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playing = s.phase == TtsPhase.PLAYING || s.phase == TtsPhase.BUFFERING ||
            s.phase == TtsPhase.WAITING_NETWORK
        val status = when (s.phase) {
            TtsPhase.WAITING_NETWORK -> "Čekám na signál… · "
            TtsPhase.INTERRUPTED -> "${s.note ?: "Přerušeno"} · "
            else -> ""
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 7310, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        @Suppress("DEPRECATION")
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title)
            .setContentText(status + s.preview)
            .setContentIntent(open)
            .setDeleteIntent(pi(ACTION_STOP, 7315))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Předchozí", pi(ACTION_PREV, 7311)).build())
            .addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pauza" else "Přehrát",
                    pi(ACTION_TOGGLE, 7312),
                ).build(),
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Další", pi(ACTION_NEXT, 7313)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Konec", pi(ACTION_STOP, 7314)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(ms.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private companion object {
        const val CHANNEL = "tts_player"
        const val NOTIFICATION_ID = 7300
        const val ACTION_PREV = "com.clauderemote.tts.PREV"
        const val ACTION_REWIND = "com.clauderemote.tts.REWIND"
        const val ACTION_TOGGLE = "com.clauderemote.tts.TOGGLE"
        const val ACTION_NEXT = "com.clauderemote.tts.NEXT"
        const val ACTION_STOP = "com.clauderemote.tts.STOP"
        const val CUSTOM_REWIND = "rewind10"
        const val CUSTOM_STOP = "stop"
        val PLAY_PAUSE_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
