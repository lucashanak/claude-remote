package com.clauderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.clauderemote.session.JarvisUndo
import com.clauderemote.session.QuickTarget
import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisFeed
import com.clauderemote.session.service.JarvisMerged
import com.clauderemote.util.FileLogger

/**
 * Jarvis F3 on the phone: ONE merged notification "Jarvis · N" for all his alerts (navrh §3 bod 4: one interruption,
 * one line per item), and the quiet "Jarvis neodpovídá" notice when his lease lapses while alerts are routed through
 * him. Loud and quiet are two channels under the same id: a re-post moves the notification between them, so an info
 * item updates the list without a sound while a block, a done task (or reminder, fired watch) or an incident alerts.
 */
object JarvisNotifier {
    private const val CHANNEL_NOW = "jarvis_now"
    private const val CHANNEL_QUIET = "jarvis_quiet"
    // Outside both KeepAliveService's id (1) and AlertNotifier's space (bit 30 set).
    private const val MERGED_ID = 0x2A11_0001
    private const val FAIL_OPEN_ID = 0x2A11_0002
    private const val UNDO_RESULT_ID = 0x2A11_0003
    private const val UNDO_REQ_BASE = 0x2A12_0000
    private const val TAG = "JarvisNotifier"

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NOW, "Jarvis – hned", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Co blokuje, hotové úkoly a připomínky, hromadný incident"
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Jarvis – tiše", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Informace, které počkají; Jarvis neodpovídá"
            },
        )
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, MERGED_ID,
        MainActivity.quickAccessIntent(context, QuickTarget.JARVIS_NEWS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * "Vrátit <alias>" of a close Jarvis announced (F4): opens the app, which checks closed.jsonl and restores the way
     * "Nedávno zavřené" does. The request code is per (server, name), so two actions never share one PendingIntent.
     */
    private fun undoAction(context: Context, a: JarvisAlert): Notification.Action {
        val tmux = a.tmux!!
        val pi = PendingIntent.getActivity(
            context, UNDO_REQ_BASE + ((a.serverId + "\u0000" + tmux).hashCode() and 0xFFFF),
            MainActivity.jarvisUndoIntent(context, a.serverId, tmux),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, "Vrátit ${JarvisUndo.alias(tmux)}", pi).build()
    }

    /** Post or replace the merged notification with [feed]'s pending alerts; [buzz] picks the loud channel. */
    fun post(context: Context, feed: JarvisFeed, buzz: Boolean) {
        if (feed.pending.isEmpty()) return cancel(context)
        runCatching {
            ensureChannels(context)
            val lines = JarvisMerged.lines(feed)
            val b = Notification.Builder(context, if (buzz) CHANNEL_NOW else CHANNEL_QUIET)
                .setContentTitle(JarvisMerged.title(feed))
                .setContentText(lines.first())
                .setStyle(Notification.InboxStyle().also { s -> lines.forEach { s.addLine(it) } })
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(!buzz)
                .setNumber(feed.pending.size)
                .setCategory(Notification.CATEGORY_MESSAGE)
            JarvisUndo.undoable(feed.pending).forEach { b.addAction(undoAction(context, it)) }
            val n = b.build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(MERGED_ID, n)
            FileLogger.log(TAG, "merged posted: ${feed.pending.size} pending, buzz=$buzz")
        }.onFailure { FileLogger.log(TAG, "merged post FAILED: ${it.message}") }
    }

    fun cancel(context: Context) {
        runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(MERGED_ID) }
    }

    /** The lease lapsed while routed through Jarvis: said once, quietly; per-session alerts are back already. */
    fun postFailOpen(context: Context) {
        runCatching {
            ensureChannels(context)
            val n = Notification.Builder(context, CHANNEL_QUIET)
                .setContentTitle("Jarvis")
                .setContentText(JarvisMerged.FAIL_OPEN_TEXT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(FAIL_OPEN_ID, n)
        }.onFailure { FileLogger.log(TAG, "fail-open post FAILED: ${it.message}") }
    }

    /** What "Vrátit" from the watch did (the phone ran it without a screen): one quiet line, a tap opens the app. */
    fun postUndoResult(context: Context, text: String) {
        runCatching {
            ensureChannels(context)
            val n = Notification.Builder(context, CHANNEL_QUIET)
                .setContentTitle("Jarvis · vrácení")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent(context))
                .setAutoCancel(true)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(UNDO_RESULT_ID, n)
        }.onFailure { FileLogger.log(TAG, "undo result post FAILED: ${it.message}") }
    }

    fun cancelFailOpen(context: Context) {
        runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(FAIL_OPEN_ID) }
    }
}
