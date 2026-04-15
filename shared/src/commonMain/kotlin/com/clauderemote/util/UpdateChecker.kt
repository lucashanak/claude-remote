package com.clauderemote.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class PatchStep(
    val url: String,
    val size: Long,
    val from: String,
    val to: String
)

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    val patchChain: List<PatchStep>,
    val apkSha256: String?,
    val dmgUrl: String = "",
    val dmgSize: Long = 0
) {
    val totalPatchSize: Long get() = patchChain.sumOf { it.size }
    val hasPatch: Boolean get() = patchChain.isNotEmpty()
}

object UpdateChecker {

    private const val REPO = "lucashanak/claude-remote"
    private const val MAX_PATCH_CHAIN = 3

    /**
     * Check for updates. Returns null if current version is latest.
     */
    suspend fun checkUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=5")
                .openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "ClaudeRemote/$currentVersion")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10000
                readTimeout = 10000
            }

            val releases = JSONArray(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            if (releases.length() == 0) return@withContext null

            val latest = releases.getJSONObject(0)
            val latestVer = latest.optString("tag_name", "").trimStart('v')

            if (latestVer.isBlank() || latestVer == currentVersion) return@withContext null
            if (!isNewer(latestVer, currentVersion)) return@withContext null

            // Find assets in latest release
            var apkUrl = ""
            var apkSize = 0L
            var dmgUrl = ""
            var dmgSize = 0L
            val latestAssets = latest.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until latestAssets.length()) {
                val a = latestAssets.getJSONObject(i)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                val size = a.optLong("size", 0)
                when {
                    name.endsWith(".apk") -> { apkUrl = url; apkSize = size }
                    name.endsWith(".dmg") -> { dmgUrl = url; dmgSize = size }
                }
            }
            if (apkUrl.isBlank() && dmgUrl.isBlank()) return@withContext null

            // Extract SHA-256 from release body
            val body = latest.optString("body", "")
            // Use last SHA — CI appends new hash when overwriting APK
            val sha256 = Regex("sha256:([a-f0-9]{64})").findAll(body).lastOrNull()?.groupValues?.get(1)

            // Build patch chain across releases
            val patchMap = mutableMapOf<String, PatchStep>()
            for (r in 0 until releases.length()) {
                val rel = releases.getJSONObject(r)
                val assets = rel.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (!name.endsWith(".bspatch")) continue
                    val match = Regex("patch-(.*)-to-(.*)\\.bspatch").find(name) ?: continue
                    val from = match.groupValues[1]
                    val to = match.groupValues[2]
                    patchMap[from] = PatchStep(
                        url = a.optString("browser_download_url", ""),
                        size = a.optLong("size", 0),
                        from = from,
                        to = to
                    )
                }
            }

            // Walk chain: current → ... → latest (max MAX_PATCH_CHAIN steps)
            val chain = mutableListOf<PatchStep>()
            var ver = currentVersion
            for (step in 0 until MAX_PATCH_CHAIN) {
                val patch = patchMap[ver] ?: break
                chain.add(patch)
                ver = patch.to
                if (ver == latestVer) break
            }
            val validChain = if (ver == latestVer) chain else emptyList()

            UpdateInfo(latestVer, apkUrl, apkSize, validChain, sha256, dmgUrl, dmgSize)
        } catch (e: Exception) {
            FileLogger.error("UpdateChecker", "Check failed", e)
            null
        }
    }

    /**
     * Download a file with progress callback.
     * Returns the downloaded bytes.
     */
    suspend fun downloadFile(
        url: String,
        onProgress: (progress: Int, downloaded: Long, total: Long) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "ClaudeRemote")
        conn.instanceFollowRedirects = true
        conn.connect()

        // Handle redirects (GitHub → S3)
        val actualConn = if (conn.responseCode in listOf(301, 302)) {
            val redirectUrl = conn.getHeaderField("Location")
            conn.disconnect()
            val rc = URL(redirectUrl).openConnection() as HttpURLConnection
            rc.setRequestProperty("User-Agent", "ClaudeRemote")
            rc.connect()
            rc
        } else {
            conn
        }

        val totalSize = actualConn.contentLength.toLong()
        var downloaded = 0L
        val output = java.io.ByteArrayOutputStream()

        actualConn.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                downloaded += n
                if (totalSize > 0) {
                    onProgress(((downloaded * 100) / totalSize).toInt(), downloaded, totalSize)
                } else {
                    onProgress(-1, downloaded, 0)
                }
            }
        }
        actualConn.disconnect()
        output.toByteArray()
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    fun sha256(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Apply a bsdiff patch to produce a new file.
     */
    fun applyPatch(oldBytes: ByteArray, patchBytes: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        io.sigpipe.jbsdiff.Patch.patch(oldBytes, patchBytes, output)
        return output.toByteArray()
    }
}
