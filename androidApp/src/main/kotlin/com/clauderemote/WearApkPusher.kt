package com.clauderemote

import android.content.Context
import com.clauderemote.util.FileLogger
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pushes the watch companion app's own update APK to it directly over the
 * Wearable Data Layer, instead of having the watch download it itself over
 * HTTP relayed through the phone's Bluetooth connection. That path proved
 * unreliable on a real device: slow/timing-out downloads, and the watch's
 * screen going to sleep mid-download loses the install-confirmation prompt
 * with no way back to it. The phone has a real internet connection and stays
 * awake for as long as the user is looking at Settings, so it downloads the
 * APK itself and streams the bytes to [com.clauderemote.wear.WearDataListenerService]
 * via [ChannelClient] — the Data Layer client meant for payloads too large
 * for MessageClient's ~100 KB limit. The watch still installs via
 * PackageInstaller and the user still needs one confirmation tap; only the
 * download step moves off the watch.
 */
object WearApkPusher {
    private const val TAG = "WearApkPusher"
    private const val REPO = "lucashanak/claude-remote"
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ASSET_NAME = "ClaudeRemoteWear.apk"
    private const val CHANNEL_PATH = "/apk_push"
    private const val WRITE_TIMEOUT_SEC = 60L
    private val executor = Executors.newSingleThreadExecutor()

    fun checkAndPush(context: Context, onProgress: (String) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            try {
                onProgress("Kontroluji verzi…")
                val (version, url) = latestRelease()
                onProgress("Stahuji v$version…")
                val bytes = download(url)
                onProgress("Odesílám na hodinky (${bytes.size / 1024} KB)…")
                push(context, bytes)
                onProgress("Odesláno. Potvrďte instalaci na hodinkách.")
            } catch (e: Exception) {
                FileLogger.log(TAG, "checkAndPush failed: ${e.message}")
                onError(e.message ?: "unknown error")
            }
        }
    }

    private fun latestRelease(): Pair<String, String> {
        val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        val tag = obj.optString("tag_name").removePrefix("v")
        val assets = obj.optJSONArray("assets") ?: throw IllegalStateException("no assets in latest release")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == ASSET_NAME) {
                val downloadUrl = a.optString("browser_download_url")
                if (downloadUrl.isBlank()) throw IllegalStateException("asset has no browser_download_url")
                return tag to downloadUrl
            }
        }
        throw IllegalStateException("no $ASSET_NAME in latest release")
    }

    private fun download(url: String): ByteArray {
        var conn = newConnection(url)
        conn.connect()
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = newConnection(next)
            conn.connect()
            redirects++
        }
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes() }
    }

    private fun newConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 10_000
        readTimeout = 30_000
    }

    private fun push(context: Context, apkBytes: ByteArray) {
        val ctx = context.applicationContext
        val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 10, TimeUnit.SECONDS)
        val node = nodes.firstOrNull() ?: throw IllegalStateException("no connected watch")
        val channelClient = Wearable.getChannelClient(ctx)
        val channel = Tasks.await(channelClient.openChannel(node.id, CHANNEL_PATH), 10, TimeUnit.SECONDS)
        try {
            val output = Tasks.await(channelClient.getOutputStream(channel), 10, TimeUnit.SECONDS)
            // Force-close the stream if the write stalls (e.g. Bluetooth
            // drops) — plain write() has no timeout of its own and would
            // otherwise wedge this object's single-thread executor forever,
            // queuing behind it any later push attempt.
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            val abort = watchdog.schedule({ runCatching { output.close() } }, WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            try {
                output.use {
                    // 8-byte length prefix — lets the watch detect a
                    // truncated transfer explicitly instead of silently
                    // installing a partial APK.
                    it.write(ByteBuffer.allocate(8).putLong(apkBytes.size.toLong()).array())
                    it.write(apkBytes)
                }
            } finally {
                abort.cancel(false)
                watchdog.shutdown()
            }
            FileLogger.log(TAG, "Pushed ${apkBytes.size} bytes to watch")
        } finally {
            Tasks.await(channelClient.close(channel), 10, TimeUnit.SECONDS)
        }
    }
}
