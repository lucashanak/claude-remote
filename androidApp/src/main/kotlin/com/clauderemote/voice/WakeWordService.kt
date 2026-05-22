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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clauderemote.MainActivity
import com.clauderemote.R
import com.clauderemote.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that continuously listens for the Czech wake phrase
 * "Hej Claude" using an offline Vosk model. On detection it emits via
 * [WakeEvents] and launches MainActivity, which surfaces voice mode.
 *
 * Lifecycle is driven from settings: enabling the toggle starts the
 * service, disabling stops it. The service self-stops if the Vosk model is
 * not yet downloaded or RECORD_AUDIO is not granted — the settings UI
 * is responsible for those preconditions.
 *
 * The service intentionally does NOT keep listening while voice mode is
 * on screen: it would compete with VoiceMode's own SpeechRecognizer for
 * the mic. The Compose layer stops this service when voice mode opens and
 * restarts it on close (see TerminalScreen wiring).
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioJob: Job? = null
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!preconditionsOk()) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (audioJob == null) {
            audioJob = scope.launch { runListenLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        audioJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun preconditionsOk(): Boolean {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return hasMic && VoskModelManager.isModelReady(this)
    }

    private fun stopSelfSafely() {
        stopped = true
        audioJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runListenLoop() {
        val engine = WakeWordEngine.create(this) ?: run {
            stopSelfSafely()
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            engine.close()
            stopSelfSafely()
            return
        }
        // Vosk does best with ~250 ms chunks; pick a buffer at least that
        // big but no smaller than the platform minimum.
        val bufferSize = maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
        val recorder = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: SecurityException) {
            engine.close()
            stopSelfSafely()
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            engine.close()
            stopSelfSafely()
            return
        }

        var wakeDetected = false
        val buf = ByteArray(FRAME_SAMPLES * 2) // 16-bit samples
        try {
            recorder.startRecording()
            WakeWordController.reportServiceRunning(true, this)
            while (!stopped && scope.isActive) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (engine.process(buf, n)) {
                    wakeDetected = true
                    // Break out immediately — voice mode is about to take
                    // the microphone over. The finally block releases
                    // AudioRecord synchronously *before* we fire the wake,
                    // so VoiceMode's SpeechRecognizer doesn't race the
                    // service for the mic.
                    break
                }
            }
        } catch (_: Throwable) {
            // Fall through to cleanup.
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            engine.close()
            WakeWordController.reportServiceRunning(false, this)
        }
        if (wakeDetected && !stopped) {
            fireWake()
        }
        // The service self-stops after a wake so the WakeWordHost lifecycle
        // logic in TerminalScreen is the single source of truth for when
        // to start listening again.
        stopSelfSafely()
    }

    private fun fireWake() {
        WakeEvents.fire()
        // Bring the app to the foreground so voice mode is visible.
        // FLAG_ACTIVITY_SINGLE_TOP avoids a duplicate activity instance
        // when MainActivity is already at the top of the back stack.
        val launch = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_VOICE_MODE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(launch) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Voice wake-word",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent notification while \"Hej Claude\" wake-word is enabled."
                    setShowBadge(false)
                }
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Poslouchám \"Hej Claude\"")
            .setContentText("Hands-free hlasový režim je aktivní.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Vypnout", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_OPEN_VOICE_MODE = "${BuildConfig.APPLICATION_ID}.OPEN_VOICE_MODE"
        const val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.STOP_WAKE"

        private const val CHANNEL_ID = "voice-wake-word"
        private const val NOTIFICATION_ID = 7732
        private const val SAMPLE_RATE_HZ = 16000
        // ~250 ms chunks (16k samples/sec * 0.25 s = 4000 samples).
        private const val FRAME_SAMPLES = 4000

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
