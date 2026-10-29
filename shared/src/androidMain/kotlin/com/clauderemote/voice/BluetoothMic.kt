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
 * So the route takes the communication mode, asks for that link, then points the
 * recorder at the headset's input. While it is open the earphones drop from music
 * quality to call quality; releasing it hands them back.
 *
 * PROCESS-WIDE and reference-counted: every dictation shares one AudioManager,
 * so a second route would replace the first and either one's release would
 * drop both — one dictation finishing its tail while the next starts would
 * leave the new one deaf again. The link goes down when the LAST holder lets go.
 *
 * BLOCKING (waits for the link): call from the capture thread, never main.
 */
internal object BluetoothRoute {
    private const val TAG = "BluetoothMic"
    private const val LINK_TIMEOUT_MS = 2500L

    private val HEADSET_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private class Active(
        val am: AudioManager,
        val legacySco: Boolean,
        val previousMode: Int,
        val input: AudioDeviceInfo?,
    )

    private var active: Active? = null
    private var holders = 0

    /** One dictation's hold on the route. */
    class Lease internal constructor(val input: AudioDeviceInfo?) {
        private var released = false
        val label: String get() = input?.let { "bluetooth(${it.productName})" } ?: "bluetooth"
        fun release() {
            if (released) return
            released = true
            drop()
        }
    }

    /** Holds the headset's call link, or null when no Bluetooth headset mic is there. */
    @Synchronized
    fun acquire(context: Context): Lease? {
        active?.let { holders++; return Lease(it.input) }
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val opened = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) openModern(am) else openLegacy(context, am)
        } catch (e: InterruptedException) {
            // Cancelled while waiting for the link — the open* path already took
            // it down; keep the interrupt for the capture loop to see.
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            FileLogger.error(TAG, "could not route to the Bluetooth mic, using the phone's", e)
            null
        } ?: return null
        active = opened
        holders = 1
        return Lease(opened.input)
    }

    @Synchronized
    private fun drop() {
        if (holders == 0) return
        holders--
        if (holders > 0) return
        active?.let { close(it) }
        active = null
    }

    private fun close(a: Active) {
        runCatching {
            if (a.legacySco) {
                @Suppress("DEPRECATION")
                a.am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                a.am.stopBluetoothSco()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                a.am.clearCommunicationDevice()
            }
        }
        runCatching { a.am.mode = a.previousMode }
    }

    @SuppressLint("NewApi")
    private fun openModern(am: AudioManager): Active? {
        val device = am.availableCommunicationDevices.firstOrNull { it.type in HEADSET_TYPES } ?: return null
        val previousMode = am.mode
        // Owning the communication MODE is what makes the route apply now: in
        // MODE_NORMAL, Android 14+ only moves to the headset once something is
        // already recording for communication, so the first seconds were silent.
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!am.setCommunicationDevice(device)) {
            FileLogger.log(TAG, "setCommunicationDevice refused ${device.productName}")
            runCatching { am.mode = previousMode }
            return null
        }
        val a = Active(am, legacySco = false, previousMode = previousMode,
            input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == device.type })
        try {
            val started = System.currentTimeMillis()
            while (am.communicationDevice?.id != device.id && System.currentTimeMillis() - started < LINK_TIMEOUT_MS) {
                Thread.sleep(50)
            }
            val waited = System.currentTimeMillis() - started
            // The selection is reported a little before the audio really flows.
            Thread.sleep(300)
            FileLogger.log(TAG, "routed to ${device.productName} (type ${device.type}, input=${a.input != null}, " +
                "rates=${a.input?.sampleRates?.joinToString("/") ?: "?"}, " +
                if (am.communicationDevice?.id == device.id) "selected after ${waited} ms)" else "NOT selected after ${waited} ms)")
        } catch (e: InterruptedException) {
            close(a)
            throw e
        }
        return a
    }

    @Suppress("DEPRECATION")
    private fun openLegacy(context: Context, am: AudioManager): Active? {
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
        val a = Active(am, legacySco = true, previousMode = am.mode, input = input)
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            if (!connected.await(LINK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                FileLogger.log(TAG, "SCO did not connect in $LINK_TIMEOUT_MS ms, using the phone's mic")
                close(a)
                return null
            }
        } catch (e: InterruptedException) {
            close(a)
            throw e
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        FileLogger.log(TAG, "routed to ${input.productName} over SCO")
        return a
    }
}

/** A recorder plus the Bluetooth route it listens through (null = phone mic). */
internal class Mic(val recorder: AudioRecord, private val lease: BluetoothRoute.Lease?) {
    val label: String get() = lease?.label ?: "phone"

    fun release() {
        runCatching { recorder.release() }
        lease?.release()
    }

    companion object {
        /**
         * The dictation recorder, listening through a connected Bluetooth headset
         * when there is one and [useBluetooth]. Throws like the AudioRecord
         * constructor; check [AudioRecord.getState] as before. BLOCKING — see
         * [BluetoothRoute].
         *
         * [useBluetooth] false for an always-on listener (the wake word): it would
         * otherwise hold the earphones at call quality for as long as it runs.
         */
        @SuppressLint("MissingPermission")
        fun open(context: Context, sampleRate: Int, bufferBytes: Int, useBluetooth: Boolean = true): Mic {
            val lease = if (useBluetooth) BluetoothRoute.acquire(context) else null
            val recorder = try {
                AudioRecord(
                    // The call link carries the mic for COMMUNICATION capture;
                    // VOICE_RECOGNITION stays on the phone mic on some phones.
                    if (lease != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: Throwable) {
                lease?.release()
                throw e
            }
            lease?.input?.let { runCatching { recorder.setPreferredDevice(it) } }
            return Mic(recorder, lease)
        }
    }
}
