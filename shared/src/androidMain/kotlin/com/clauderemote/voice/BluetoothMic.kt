package com.clauderemote.voice

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.clauderemote.util.FileLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where dictation listens: the Bluetooth headset's microphone when one is
 * connected, the phone's own otherwise.
 *
 * Without this, dictation with Bluetooth earphones in heard silence — Soniox got
 * 6-8 s of audio and returned 0 characters (2026-10-28). A headset mic is not an
 * ordinary input: it only carries audio over the headset's CALL link (SCO, or LE
 * audio), which Android opens for "communication", not for a plain recording.
 * So the route asks for that link first, then points the recorder at the
 * headset's input. While it is open the earphones drop from music quality to
 * call quality; [release] hands them back.
 *
 * BLOCKING (waits for the link): call from the capture thread, never main.
 */
internal class MicRoute private constructor(
    private val am: AudioManager,
    private val legacySco: Boolean,
    val input: AudioDeviceInfo?,
) {
    /** For the log: which mic a dictation actually used. */
    val label: String get() = input?.let { "bluetooth(${it.productName})" } ?: "bluetooth"

    fun release() {
        runCatching {
            if (legacySco) {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothMic"
        private const val LINK_TIMEOUT_MS = 2500L

        private val HEADSET_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        /** Opens the headset's call link, or null when no Bluetooth headset mic is there. */
        fun acquire(context: Context): MicRoute? {
            val am = context.getSystemService(AudioManager::class.java) ?: return null
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) acquireModern(am) else acquireLegacy(context, am)
            } catch (e: Exception) {
                FileLogger.error(TAG, "could not route to the Bluetooth mic, using the phone's", e)
                null
            }
        }

        @SuppressLint("NewApi")
        private fun acquireModern(am: AudioManager): MicRoute? {
            val device = am.availableCommunicationDevices.firstOrNull { it.type in HEADSET_TYPES } ?: return null
            if (!am.setCommunicationDevice(device)) {
                FileLogger.log(TAG, "setCommunicationDevice refused ${device.productName}")
                return null
            }
            // The call is accepted at once but the link takes a moment to come up;
            // audio read before it does is the silence this class exists to avoid.
            val deadline = System.currentTimeMillis() + LINK_TIMEOUT_MS
            while (am.communicationDevice?.id != device.id && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            val input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == device.type }
            FileLogger.log(TAG, "routed to ${device.productName} (type ${device.type}, input=${input != null})")
            return MicRoute(am, legacySco = false, input = input)
        }

        @Suppress("DEPRECATION")
        private fun acquireLegacy(context: Context, am: AudioManager): MicRoute? {
            val input = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return null
            if (!am.isBluetoothScoAvailableOffCall) return null
            val connected = CountDownLatch(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown()
                }
            }
            context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            try {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                if (!connected.await(LINK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    FileLogger.log(TAG, "SCO did not connect in ${LINK_TIMEOUT_MS} ms, using the phone's mic")
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                    return null
                }
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
            FileLogger.log(TAG, "routed to ${input.productName} over SCO")
            return MicRoute(am, legacySco = true, input = input)
        }
    }
}

/** A recorder plus the Bluetooth route it listens through (null = phone mic). */
internal class Mic(val recorder: AudioRecord, val route: MicRoute?) {
    val label: String get() = route?.label ?: "phone"

    fun release() {
        runCatching { recorder.release() }
        route?.release()
    }

    companion object {
        /**
         * The dictation recorder, listening through a connected Bluetooth
         * headset when there is one. Throws like the AudioRecord constructor;
         * check [AudioRecord.getState] as before. BLOCKING — see [MicRoute].
         */
        @SuppressLint("MissingPermission")
        fun open(context: Context, sampleRate: Int, bufferBytes: Int): Mic {
            val route = MicRoute.acquire(context)
            val recorder = try {
                AudioRecord(
                    // The call link carries the mic for COMMUNICATION capture;
                    // VOICE_RECOGNITION stays on the phone mic on some phones.
                    if (route != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: Throwable) {
                route?.release()
                throw e
            }
            route?.input?.let { runCatching { recorder.setPreferredDevice(it) } }
            return Mic(recorder, route)
        }
    }
}
