package com.clauderemote.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable

/**
 * Thin wrapper around android.util.Log that ALSO best-effort forwards the
 * line to the paired phone via MessageClient at "/log". PhoneWearService
 * feeds it into FileLogger there, which the phone already ships to the
 * server (LogShipper, one file per install id) — reusing that entire
 * pipeline instead of building a second one just for the watch. Fire-and-
 * forget: shipping never throws, never blocks, and local Log.i/w always
 * happens regardless of whether the phone is reachable.
 */
object WearLog {
    // Resolving connectedNodes is itself a Play Services IPC round-trip;
    // doing it on every single log line (a burst of session transitions logs
    // dozens within milliseconds) was needless radio/CPU churn on top of the
    // sendMessage itself. The paired phone doesn't change often, so a short
    // cache is enough — falls back to a fresh lookup if empty/stale or if a
    // send ever fails (covers a mid-cache disconnect/repair).
    private const val CACHE_TTL_MS = 60_000L
    @Volatile private var cachedNodeId: String? = null
    @Volatile private var cachedAt: Long = 0

    fun i(context: Context, tag: String, message: String) {
        Log.i(tag, message)
        ship(context, "$tag: $message")
    }

    fun w(context: Context, tag: String, message: String) {
        Log.w(tag, message)
        ship(context, "$tag: $message")
    }

    private fun ship(context: Context, line: String) {
        runCatching {
            val ctx = context.applicationContext
            val node = cachedNodeId
            if (node != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                Wearable.getMessageClient(ctx).sendMessage(node, "/log", line.toByteArray(Charsets.UTF_8))
                    .addOnFailureListener { cachedNodeId = null }
            } else {
                Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
                    val n = nodes.firstOrNull() ?: return@addOnSuccessListener
                    cachedNodeId = n.id
                    cachedAt = System.currentTimeMillis()
                    Wearable.getMessageClient(ctx).sendMessage(n.id, "/log", line.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
}
