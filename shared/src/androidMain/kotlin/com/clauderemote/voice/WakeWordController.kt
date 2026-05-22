package com.clauderemote.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level entry point used by the UI to control wake-word listening.
 *
 * Responsibilities:
 *  - Reports model + permission readiness so the settings screen can
 *    decide what to show (toggle vs. download button vs. permission ask).
 *  - Downloads the Vosk Czech model on demand, surfacing progress.
 *  - Starts and stops the [com.clauderemote.voice.WakeWordService] without
 *    leaking knowledge of the service class to call sites in commonMain.
 *
 * State is a process-wide singleton so the settings screen and the voice
 * mode lifecycle (which pauses the service while voice mode is on screen)
 * observe the same source of truth.
 */
object WakeWordController {

    enum class Status { Idle, Downloading, Ready, Listening }

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    fun isModelReady(context: Context): Boolean = VoskModelManager.isModelReady(context)

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Download the Czech wake-word model. Suspending; surfaces progress
     * via [downloadProgress]. Returns true on success.
     */
    suspend fun downloadModel(context: Context): Boolean {
        _status.value = Status.Downloading
        _downloadProgress.value = 0f
        val ok = VoskModelManager.download(context) { _downloadProgress.value = it }
        _status.value = if (ok) Status.Ready else Status.Idle
        return ok
    }

    /** Start the foreground listener. No-op if preconditions aren't met. */
    fun startListening(context: Context) {
        if (!isModelReady(context) || !hasMicPermission(context)) return
        val intent = Intent().setComponent(serviceComponent(context))
        ContextCompat.startForegroundService(context, intent)
        // Don't flip to Listening yet — the service will call
        // [reportServiceRunning] once AudioRecord is actually open. Until
        // then, we stay at Ready so the UI doesn't lie if the service
        // self-stops due to permission/model issues.
    }

    /** Stop the foreground listener if running. */
    fun stopListening(context: Context) {
        val intent = Intent().setComponent(serviceComponent(context))
            .setAction(STOP_ACTION)
        runCatching { context.startService(intent) }
        // Same pattern: actual flip happens via [reportServiceRunning] when
        // the service's audio loop exits. This is just a request to stop.
    }

    /**
     * Called by [com.clauderemote.voice.WakeWordService] when its audio
     * loop opens / releases the microphone, so the controller status
     * reflects ground truth instead of the call-site's hope. Public
     * because the service lives in the androidApp module across the
     * Kotlin `internal` boundary.
     */
    fun reportServiceRunning(running: Boolean, context: Context) {
        _status.value = when {
            running -> Status.Listening
            isModelReady(context) -> Status.Ready
            else -> Status.Idle
        }
    }

    private fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, SERVICE_CLASS)

    // The service lives in the androidApp module so commonMain can't
    // reference it by class literal; resolve by name at runtime.
    private const val SERVICE_CLASS = "com.clauderemote.voice.WakeWordService"
    private const val STOP_ACTION = "STOP_WAKE"
}
