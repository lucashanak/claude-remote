package com.clauderemote.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/**
 * Turns a session's flip to WAITING_FOR_INPUT/APPROVAL_NEEDED into an
 * actionable watch notification — the "notifikace-first" flow: the user
 * answers Claude straight from the notification (Y/N buttons for approval,
 * an inline RemoteInput reply for input) WITHOUT opening the app. This is
 * the tier above the read-aloud TTS in [WearDataListenerService]: TTS tells
 * you something needs you, the notification lets you act on it in place.
 *
 * All the actual sending is delegated to [WearActionReceiver] via broadcast
 * PendingIntents — a BroadcastReceiver is a documented background-activity-
 * start / background-work exemption when triggered by a notification action,
 * unlike calling startActivity() ourselves (see InstallResultReceiver's
 * kdoc for that whole saga).
 */
object WearNotifier {
    /**
     * Two channels so the OS can treat them differently: approval is the
     * high-urgency "Claude is blocked on your yes/no" case (heads-up + full-
     * screen wake), waiting-for-input is the softer "type a reply when you
     * get a chance". Idempotent — safe to call on every push.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_APPROVAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_APPROVAL, "Schválení", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 60, 80, 120)
                    enableLights(true)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_WAITING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WAITING, "Čeká na vstup", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 40)
                }
            )
        }
    }

    /**
     * Posts (or replaces — stable id keyed on the session) the notification
     * for a session that's waiting on the user. Shape depends on the kind of
     * wait: APPROVAL_NEEDED gets Y/N action buttons + a full-screen wake,
     * WAITING_FOR_INPUT gets an inline text reply.
     */
    fun notifySession(context: Context, session: WearSessionInfo) {
        ensureChannels(context)
        // notify() is a silent no-op without POST_NOTIFICATIONS on API 33+;
        // don't crash on the missing permission, just log and bail (the app
        // requests it on launch — see MainActivity.requestNotificationPermission).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            WearLog.w(context, TAG, "notifySession skipped for ${session.id}: POST_NOTIFICATIONS not granted")
            return
        }

        val notifId = session.id.hashCode()
        val contentPending = PendingIntent.getActivity(
            context,
            requestCode(session.id, "content"),
            deepLinkIntent(context, session.id),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )

        val builder = NotificationCompat.Builder(context, channelFor(session.activity))
            // A mipmap (color launcher icon) as the status-bar small icon
            // isn't the textbook monochrome silhouette, but it's the only
            // in-repo icon and matches the app's identity; InstallResultReceiver
            // uses android.R.drawable.ic_dialog_info instead — kept ours on the
            // app icon deliberately so these read as "from Claude Remote".
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(session.title)
            .setContentText(session.lastMessage ?: "")
            .setContentIntent(contentPending)
            .setAutoCancel(true)

        when (session.activity) {
            "APPROVAL_NEEDED" -> buildApproval(context, builder, session)
            "WAITING_FOR_INPUT" -> buildReply(context, builder, session)
        }

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
        WearLog.i(context, TAG, "Posted notification for ${session.id} (${session.activity})")
    }

    fun cancelSession(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(sessionId.hashCode())
    }

    /**
     * Deep-link back into the running app on a specific session. singleTop +
     * FLAG_ACTIVITY_CLEAR_TOP so tapping this reuses the existing task rather
     * than stacking Activities; MainActivity reads "session_id" in onCreate/
     * onNewIntent and routes to it via [NavRequest].
     */
    fun deepLinkIntent(context: Context, sessionId: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun buildApproval(context: Context, builder: NotificationCompat.Builder, session: WearSessionInfo) {
        builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        // Text labels (not just ✓/✗) so TalkBack announces them meaningfully.
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Ano", approvePending(context, session.id, "y")).build()
        )
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Ne", approvePending(context, session.id, "n")).build()
        )

        // Full-screen intent → WakeAndConfirmActivity trampoline wakes an
        // asleep/ambient screen (system fires it immediately when off/locked,
        // degrades to a heads-up when the screen's already on). It hands off
        // to the app's deep-link so the user lands on the session if they'd
        // rather read the full context before answering.
        val wakeIntent = Intent(context, WakeAndConfirmActivity::class.java).apply {
            putExtra(WakeAndConfirmActivity.EXTRA_CONFIRM_INTENT, deepLinkIntent(context, session.id))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            requestCode(session.id, "fullscreen"),
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        builder.setFullScreenIntent(fullScreenPending, true)
    }

    private fun buildReply(context: Context, builder: NotificationCompat.Builder, session: WearSessionInfo) {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Odpověď pro Claude")
            .build()

        val replyIntent = Intent(context, WearActionReceiver::class.java).apply {
            action = WearActionReceiver.ACTION_REPLY
            putExtra(EXTRA_SESSION_ID, session.id)
        }
        // RemoteInput requires a MUTABLE PendingIntent — the system fills the
        // typed text into the intent before delivering it to the receiver.
        val replyPending = PendingIntent.getBroadcast(
            context,
            requestCode(session.id, "reply"),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Odpovědět", replyPending)
                .addRemoteInput(remoteInput)
                .build()
        )
    }

    private fun approvePending(context: Context, sessionId: String, answer: String): PendingIntent {
        val intent = Intent(context, WearActionReceiver::class.java).apply {
            action = WearActionReceiver.ACTION_APPROVE
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_ANSWER, answer)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(sessionId, "approve_$answer"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
    }

    private fun channelFor(activity: String): String =
        if (activity == "APPROVAL_NEEDED") CHANNEL_APPROVAL else CHANNEL_WAITING

    // Distinct request codes per (session, action) — a shared code would let
    // one session's PendingIntent overwrite another's extras on FLAG_UPDATE_CURRENT.
    private fun requestCode(sessionId: String, action: String): Int = (sessionId + ":" + action).hashCode()

    // S+ requires callers to declare mutability explicitly; below that the
    // flag doesn't exist, so pass 0.
    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_ANSWER = "answer"
    const val KEY_REPLY_TEXT = "reply_text"

    private const val TAG = "WearNotifier"
    private const val CHANNEL_APPROVAL = "claude_approval"
    private const val CHANNEL_WAITING = "claude_waiting"
}
