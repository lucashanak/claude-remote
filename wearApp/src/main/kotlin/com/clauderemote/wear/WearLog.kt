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
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull() ?: return@addOnSuccessListener
                Wearable.getMessageClient(ctx).sendMessage(node.id, "/log", line.toByteArray(Charsets.UTF_8))
            }
        }
    }
}
