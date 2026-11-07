package com.clauderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.clauderemote.util.FileLogger

/**
 * Posts the "Claude needs input" alert as its OWN notification — separate
 * from the KeepAliveService foreground notification. This fixes three
 * platform-level bugs the previous same-id channel-switching design hit:
 *
 *  1. A posted notification's channel CANNOT be changed by re-posting the
 *     same id — the alert silently stayed on the LOW `keepalive` channel,
 *     so it never made a sound or heads-up.
 *  2. A foreground-service notification id is always already visible, so
 *     re-posts don't re-alert.
 *  3. Ongoing/FGS notifications are NOT bridged to Wear OS — the watch
 *     never saw the alert (or its RemoteInput reply action).
 *
 * This notification is non-ongoing + autoCancel on the HIGH channel, posted
 * via NotificationManager directly — so it sounds, heads-ups, bridges to the
 * watch, and works even when KeepAliveService isn't running.
 */
object AlertNotifier {
    private const val ALERT_CHANNEL_ID = "claude_alerts"
    // Keep alert ids far away from KeepAliveService.NOTIFICATION_ID (1). The
    // id space is the full low 30 bits of the session-id hash OR-ed into a
    // high bit: 2^30 buckets instead of the old 32768, so two live sessions
    // colliding (one alert silently replacing another, and clear() cancelling
    // the wrong one) stops being a realistic risk. The OR — rather than a
    // plain sum — also guarantees every id lands in [0x40000000, 0x7FFFFFFF],
    // which can never be the foreground-service id.
    private const val ALERT_ID_BASE = 0x4000_0000
    private const val TAG = "AlertNotifier"
    // A whole assistant message travels to PlayReceiver through a
    // PendingIntent extra, which is a binder transaction against a 1 MB
    // buffer shared with everything else in flight. Bodies are normally a few
    // KB, but a tool dump is not, so the TTS text is capped — read-aloud of a
    // 4000-character answer is already minutes long.
    private const val TTS_EXTRA_MAX = 4000

    private fun alertId(sessionId: String) = ALERT_ID_BASE or (sessionId.hashCode() and 0x3FFF_FFFF)

    /** Idempotent; safe to call on every post (and when the service never ran). */
    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Claude Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when Claude needs your attention"
            enableVibration(true)
            enableLights(true)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Post (or replace) the session's alert.
     *
     * [displayText] is what the notification shows — the LLM summary when one
     * is available. [speakText] is what the "Přehrát" action reads aloud, and
     * defaults to the displayed text; the caller passes the FULL assistant
     * message here so read-aloud gives the real answer rather than the
     * one-sentence summary of it.
     *
     * Bridging to the watch is deliberately left at the platform default: the
     * watch app suppresses this bridged copy from its own side via the
     * NO_BRIDGING meta-data in the wearApp manifest, so nothing is needed here.
     *
     * [prompt] names the prompt this alert is for. The answer actions carry it back so
     * [ReplyReceiver] types nothing once the session shows a different prompt; null makes every
     * reply to this alert fail closed. A permission gets Ano/Ne instead of the inline reply.
     */
    fun post(
        context: Context,
        sessionId: String,
        sessionTitle: String,
        displayText: String,
        speakText: String = displayText,
        prompt: com.clauderemote.session.service.PromptFingerprint?,
    ) {
        // POST_NOTIFICATIONS denied (33+) does NOT throw from notify() — it is
        // a silent no-op, which used to make the log claim "Alert posted" while
        // the user saw nothing. Check and log the real state instead.
        val enabled = runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)
        if (!enabled) {
            FileLogger.log(TAG, "Alert NOT posted for $sessionId — notifications are blocked in system settings")
            return
        }
        runCatching {
            ensureChannel(context)
            val openIntent = PendingIntent.getActivity(
                context, sessionId.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("switch_to_session", sessionId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // The extras name the prompt of THIS alert, and the request code does too: with one
            // code per session, a later alert for the same session rewrote the extras of the
            // PendingIntent a reply already in progress would fire, and the text written for A
            // went out carrying B's identity.
            fun answerIntent(tag: String, mutable: Boolean, extra: Intent.() -> Unit) = PendingIntent.getBroadcast(
                context, "$sessionId#${prompt?.key}#$tag".hashCode(),
                Intent(context, ReplyReceiver::class.java).apply {
                    putExtra(ReplyReceiver.EXTRA_SESSION_ID, sessionId)
                    putExtra(ReplyReceiver.EXTRA_TITLE, sessionTitle)
                    putExtra(ReplyReceiver.EXTRA_PROMPT, prompt?.encode())
                    extra()
                },
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val answerActions = if (prompt?.kind == com.clauderemote.session.service.PromptKind.PERMISSION) {
                // A permission is answered with Ano/Ne only: typed text + Enter would pick the
                // selector's default, "Yes".
                listOf("Ano" to "y", "Ne" to "n").map { (label, answer) ->
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                        label,
                        answerIntent("answer_$answer", mutable = false) { putExtra(ReplyReceiver.EXTRA_ANSWER, answer) },
                    ).build()
                }
            } else {
                // Inline reply (RemoteInput). Wear OS bridges this action to the
                // watch automatically and offers its built-in voice input, so the
                // user can answer Claude from the wrist without any watch app.
                // FLAG_MUTABLE is required for RemoteInput on Android 12+.
                val remoteInput = android.app.RemoteInput.Builder(ReplyReceiver.KEY_REPLY)
                    .setLabel("Odpověď pro Claude…")
                    .build()
                listOf(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                        "Odpovědět",
                        answerIntent("reply", mutable = true) {},
                    ).addRemoteInput(remoteInput).build()
                )
            }

            // "Přehrát" — read the message aloud on demand via the chosen TTS
            // engine. Distinct request code from reply so the PendingIntents
            // don't collide.
            val playIntent = PendingIntent.getBroadcast(
                context, sessionId.hashCode() xor 0x5AFE,
                Intent(context, PlayReceiver::class.java).apply {
                    putExtra(PlayReceiver.EXTRA_TEXT, speakText.take(TTS_EXTRA_MAX))
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val playAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    context, android.R.drawable.ic_lock_silent_mode_off
                ),
                "Přehrát",
                playIntent,
            ).build()

            // `displayText` is the summary, the last assistant message, or a
            // generic fallback. Show a one-line preview when collapsed and the
            // full text when expanded.
            val collapsed = displayText.replace(Regex("\\s+"), " ").trim().take(140)
            val notification = Notification.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle(sessionTitle)
                .setContentText(collapsed)
                .setStyle(Notification.BigTextStyle().bigText(displayText))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .apply { answerActions.forEach { addAction(it) } }
                .addAction(playAction)
                .build()

            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(alertId(sessionId), notification)
            FileLogger.log(TAG, "Alert posted for $sessionId: $sessionTitle — $displayText")
        }.onFailure {
            FileLogger.log(TAG, "Alert post FAILED for $sessionId: ${it.message}")
        }
    }

    /**
     * Replace the session's alert after a reply was refused, saying why ([message]: a
     * RefuseReason text). No answer actions: whatever the session shows now has to be seen in
     * the app before it is answered. Re-posting the same id also ends the RemoteInput spinner.
     */
    fun postRefused(context: Context, sessionId: String, sessionTitle: String, message: String) {
        runCatching {
            ensureChannel(context)
            val openIntent = PendingIntent.getActivity(
                context, sessionId.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("switch_to_session", sessionId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle(sessionTitle)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(alertId(sessionId), notification)
        }.onFailure {
            FileLogger.log(TAG, "Refusal post FAILED for $sessionId: ${it.message}")
        }
    }

    /** Whether the user has notifications for this app switched on at OS level. */
    fun notificationsBlocked(context: Context): Boolean = runCatching {
        !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

    /** Cancel the session's alert (also clears the watch-side reply spinner). */
    fun clear(context: Context, sessionId: String) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(alertId(sessionId))
        }
    }
}
