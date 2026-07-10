package com.clauderemote.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub Releases for a newer wearApp build and installs it via
 * PackageInstaller — self-update, so after the one-time ADB sideload no
 * phone/ADB is needed for future updates, just a tap to confirm on the
 * watch. The watch reaches GitHub directly over whatever connection Wear OS
 * gives it (own Wi-Fi, or transparently relayed through the paired phone's
 * connection on watches with no LTE/Wi-Fi of their own).
 */
object WearUpdater {
    private const val TAG = "WearUpdater"
    private const val RELEASES_URL = "https://api.github.com/repos/lucashanak/claude-remote/releases/latest"
    private const val ASSET_NAME = "ClaudeRemoteWear.apk"
    private val executor = Executors.newSingleThreadExecutor()

    data class UpdateInfo(val version: String, val downloadUrl: String)

    /** Runs on a background thread; callback fires on that same background thread — caller hops back to main. */
    fun checkLatest(context: Context, onResult: (UpdateInfo?) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            try {
                val body = httpGetString(RELEASES_URL)
                val obj = Json.parseToJsonElement(body).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("no tag_name in response")
                val assets = obj["assets"]?.jsonArray ?: throw IllegalStateException("no assets in response")
                val asset = assets.map { it.jsonObject }
                    .firstOrNull { it["name"]?.jsonPrimitive?.content == ASSET_NAME }
                    ?: throw IllegalStateException("no $ASSET_NAME in latest release")
                val url = asset["browser_download_url"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("asset has no browser_download_url")
                onResult(UpdateInfo(version = tag.removePrefix("v"), downloadUrl = url))
            } catch (e: Exception) {
                WearLog.w(context, TAG, "checkLatest failed: ${e.message}")
                onError(e.message ?: "unknown error")
            }
        }
    }

    fun downloadAndInstall(context: Context, url: String, onProgress: (String) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            try {
                onProgress("Stahuji…")
                val bytes = httpGetBytes(url)
                onProgress("Instaluji…")
                installApk(context, bytes)
                onProgress("Potvrďte instalaci na hodinkách")
            } catch (e: Exception) {
                WearLog.w(context, TAG, "downloadAndInstall failed: ${e.message}")
                onError(e.message ?: "unknown error")
            }
        }
    }

    private fun httpGetString(url: String): String {
        val conn = newConnection(url)
        // Must be set BEFORE connect() — HttpURLConnection throws
        // "Cannot set request property after connection is made" otherwise.
        // (Confirmed on a real device: newConnection() used to connect()
        // internally, before the caller had a chance to set headers.)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** GitHub release asset downloads redirect (typically to S3) — followed
     *  manually since cross-host redirects aren't always reliable via
     *  HttpURLConnection's own instanceFollowRedirects on every Android version. */
    private fun httpGetBytes(url: String): ByteArray {
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
        return BufferedInputStream(conn.inputStream).use { it.readBytes() }
    }

    /** Builds an unconnected HttpURLConnection — callers set headers, THEN call connect(). */
    private fun newConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        return conn
    }

    private fun installApk(context: Context, apkBytes: ByteArray) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.openWrite("wear_update", 0, apkBytes.size.toLong()).use { out ->
            out.write(apkBytes)
            session.fsync(out)
        }
        val intent = Intent(context, InstallResultReceiver::class.java)
        // MUTABLE is required — the system fills in EXTRA_STATUS/EXTRA_INTENT
        // on this intent when firing it; an immutable PendingIntent silently
        // can't receive those extras.
        val pendingIntent = PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        session.commit(pendingIntent.intentSender)
        session.close()
    }
}
