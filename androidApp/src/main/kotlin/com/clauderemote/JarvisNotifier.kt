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
import com.clauderemote.session.service.JarvisSays
import com.clauderemote.util.FileLogger

/**
 * Jarvis F3 on the phone: ONE merged notification "Jarvis · N" for all his alerts (navrh §3 bod 4: one interruption,
 * one line per item), and the quiet "Jarvis neodpovídá" notice when his lease lapses while alerts are routed through
 * him. Loud and quiet are two channels under the same id: a re-post moves the notification between them, so an info
 * item updates the list without a sound while a block, a done task (or reminder, fired watch) or an incident alerts.
 *
 * F5c (2026-11-10): Jarvis' own sentences (jsay/jblock/jask) replace the merged list under the same id with ONE
 * notification titled "Jarvis" and his text ([postSays]): a jblock on the loud channel, the rest on "Jarvis" (normal
 * importance), and a jask with "Ano" and "Odpovědět" ([JarvisAskReceiver]). A later alert of any kind replaces it, and
 * with it the Ano: an "ano" typed then could answer something else than what was shown.
 */
object JarvisNotifier {
    private const val CHANNEL_NOW = "jarvis_now"
    private const val CHANNEL_QUIET = "jarvis_quiet"
    private const val CHANNEL_SAYS = "jarvis_says"
    // Outside both KeepAliveService's id (1) and AlertNotifier's space (bit 30 set).
    private const val MERGED_ID = 0x2A11_0001
    private const val FAIL_OPEN_ID = 0x2A11_0002
    private const val UNDO_RESULT_ID = 0x2A11_0003
    private const val ANSWER_RESULT_ID = 0x2A11_0005
    private const val ASK_YES_REQ = 0x2A13_0001
    private const val ASK_REPLY_REQ = 0x2A13_0002
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SAYS, "Jarvis", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Co Jarvis hlásí vlastními slovy (co doběhlo, co udělal, na co se ptá)"
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
        askShown = null
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

    /**
     * Jarvis' own sentence [alert] (F5c) under the merged id: title "Jarvis", his text whole, [loudness] picks the
     * channel. [ask] = this jask is the question Ano may answer ([JarvisSays.ask]): it gets "Ano" and "Odpovědět".
     */
    fun postSays(context: Context, alert: JarvisAlert, feed: JarvisFeed, loudness: JarvisSays.Loudness, ask: Boolean) {
        runCatching {
            ensureChannels(context)
            val channel = when (loudness) {
                JarvisSays.Loudness.HIGH -> CHANNEL_NOW
                JarvisSays.Loudness.NORMAL -> CHANNEL_SAYS
                JarvisSays.Loudness.QUIET -> CHANNEL_QUIET
            }
            val b = Notification.Builder(context, channel)
                .setContentTitle(JarvisSays.TITLE)
                .setContentText(alert.text)
                .setStyle(Notification.BigTextStyle().bigText(alert.text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(loudness == JarvisSays.Loudness.QUIET)
                .setWhen(alert.atMs)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
            if (feed.pending.size > 1) b.setNumber(feed.pending.size)
            if (ask) {
                // L4: not from the lock screen — an approval needs the phone unlocked (API 31+)
                fun locked(a: Notification.Action.Builder) =
                    a.also { if (android.os.Build.VERSION.SDK_INT >= 31) it.setAuthenticationRequired(true) }.build()
                b.addAction(locked(Notification.Action.Builder(null, "Ano", askIntent(context, alert.id, mutable = false))))
                val input = android.app.RemoteInput.Builder(JarvisAskReceiver.KEY_REPLY).setLabel("Odpověď Jarvisovi…").build()
                b.addAction(
                    locked(Notification.Action.Builder(null, "Odpovědět", askIntent(context, alert.id, mutable = true)).addRemoteInput(input)),
                )
            }
            JarvisUndo.undoable(feed.pending).forEach { b.addAction(undoAction(context, it)) }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(MERGED_ID, b.build())
            lastSays = alert
            askShown = if (ask) alert.id else null
            FileLogger.log(TAG, "says posted: ${alert.id} (${alert.kind.wire}) $loudness ask=$ask")
        }.onFailure { FileLogger.log(TAG, "says post FAILED: ${it.message}") }
    }

    /** The sentence the merged id shows now, and the question it offers Ano for (null = none). */
    @Volatile private var lastSays: JarvisAlert? = null
    @Volatile var askShown: String? = null
        private set

    /**
     * rev-f5c-app M2: the notification offers Ano only while [JarvisSays.ask] still names that question. A later alert
     * that was not posted here (routing per session, notifications off), an answer from the watch or a lapse takes it
     * away: the same sentence is posted again, quietly, without Ano/Odpovědět.
     */
    fun dropAskIfGone(context: Context, feed: JarvisFeed, now: Long) {
        val shown = askShown ?: return
        if (JarvisSays.ask(feed, now)?.id == shown) return
        val a = lastSays?.takeIf { it.id == shown }
        askShown = null
        if (a == null) return
        FileLogger.log(TAG, "question $shown no longer on offer: Ano taken off the notification")
        postSays(context, a, feed, JarvisSays.Loudness.QUIET, ask = false)
    }

    /** Ano (no input) or Odpovědět (RemoteInput: FLAG_MUTABLE is required for it on Android 12+). */
    private fun askIntent(context: Context, alertId: String, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, if (mutable) ASK_REPLY_REQ else ASK_YES_REQ,
        android.content.Intent(context, JarvisAskReceiver::class.java)
            .setAction(if (mutable) JarvisAskReceiver.ACTION_REPLY else JarvisAskReceiver.ACTION_YES)
            .putExtra(JarvisAskReceiver.EXTRA_ALERT_ID, alertId),
        (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * What Ano/Odpovědět did: [ok] = it went in (the question's notification is spent and goes), else his sentence
     * replaces it with the reason and no actions (re-posting also ends the RemoteInput spinner).
     */
    fun postAnswerResult(context: Context, ok: Boolean, text: String) {
        askShown = null
        runCatching {
            ensureChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (ok) nm.cancel(MERGED_ID)
            val n = Notification.Builder(context, CHANNEL_QUIET)
                .setContentTitle(if (ok) "Jarvis · odesláno" else "Jarvis · neodesláno")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent(context))
                .setAutoCancel(true)
                .setTimeoutAfter(if (ok) 15_000L else 10 * 60_000L)
                .build()
            nm.notify(if (ok) ANSWER_RESULT_ID else MERGED_ID, n)
        }.onFailure { FileLogger.log(TAG, "answer result post FAILED: ${it.message}") }
    }

    fun cancel(context: Context) {
        askShown = null
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
