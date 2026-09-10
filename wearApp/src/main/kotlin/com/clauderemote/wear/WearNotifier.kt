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
     * Tělo notifikace pro session. Preference (drží se kontraktu s
     * telefonem): `summary` (jednořádkové LLM shrnutí) → `notifyBody` (tělo,
     * které telefon vyřešil pro právě dokončený tah) → useknutá
     * `lastMessage`. Surová `lastMessage` bývá klidně 7000+ znaků a na
     * hodinkách je k ničemu; navíc je to jen snapshot v okamžiku pushe, takže
     * je ze všech tří nejnáchylnější na zastarání.
     *
     * Public, protože [WearDataListenerService] podle hashe tohohle textu
     * pozná, že dorazilo OPRAVENÉ tělo pro tentýž tah, a notifikaci přepíše.
     */
    fun bodyFor(session: WearSessionInfo): String {
        session.summary?.takeIf { it.isNotBlank() }?.let { return it }
        session.notifyBody?.takeIf { it.isNotBlank() }?.let { return truncate(it) }
        val last = session.lastMessage?.takeIf { it.isNotBlank() } ?: return ""
        return truncate(last)
    }

    private fun truncate(text: String): String =
        if (text.length > 100) text.take(100) + "…" else text

    /**
     * Stabilní id notifikace pro session. Vlastní bázový offset, disjunktní
     * od telefonního `0x4000_0000 or (hash and 0x3FFF_FFFF)` v AlertNotifier:
     * oba moduly mají stejný applicationId, takže při zapnutém bridgingu by
     * si holý `sessionId.hashCode()` a telefonní id mohly kolidovat a jedna
     * notifikace by přepsala druhou. Hodinky drží 0x2000_0000..0x3FFF_FFFF,
     * telefon 0x4000_0000..0x7FFF_FFFF.
     *
     * Šířka masky (2^29 kbelíků místo dřívějších 32768) není kosmetika: při
     * 32768 kbelících má už ~21 souběžných session přes půl procenta šanci
     * na kolizi, a kolize znamená, že jedna session přepíše notifikaci druhé.
     */
    private fun notifIdFor(sessionId: String): Int = 0x2000_0000 or (sessionId.hashCode() and 0x1FFF_FFFF)

    /**
     * Visí notifikace pro tuhle session ještě na zápěstí? `getActiveNotifications`
     * vrací jen notifikace téhle appky a přežije i restart procesu (drží je
     * systém), takže je to spolehlivější než cokoliv vlastního.
     *
     * Při chybě vrací false = "nevisí", takže se tichá oprava radši zahodí,
     * než aby zabzučela za něco vyřízeného: `setOnlyAlertOnce` totiž ztiší
     * jen AKTUALIZACI už zobrazené notifikace, na znovuvyvěšení té smetené
     * nemá vliv.
     */
    fun isShowing(context: Context, sessionId: String): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val id = notifIdFor(sessionId)
        return runCatching { nm.activeNotifications.any { it.id == id } }
            .onFailure { e -> WearLog.w(context, TAG, "activeNotifications failed: ${e.message}") }
            .getOrDefault(false)
    }

    /**
     * Posts (or replaces — stable id keyed on the session) the notification
     * for a session that's waiting on the user. Shape depends on the kind of
     * wait: APPROVAL_NEEDED gets Y/N action buttons + a full-screen wake,
     * WAITING_FOR_INPUT gets an inline text reply.
     *
     * [silentUpdate] mapuje na `setOnlyAlertOnce` — na už vyvěšenou
     * notifikaci se jen přepíše text, bez druhého bzučení (oprava
     * zastaralého těla). Na PRVNÍM vyvěšení nemá flag žádný efekt, takže jím
     * nelze omylem ztišit celý první alert.
     * [bodyOverride] používá chybová cesta z [WearActionReceiver] —
     * notifikace se vrátí i s napsaným textem, aby šlo odeslání zopakovat.
     * [replacesActivity] je aktivita, ve které notifikace pro tuhle session
     * právě visí (null = nevisí / nevíme) — viz kanálový trik níž.
     */
    fun notifySession(
        context: Context,
        session: WearSessionInfo,
        silentUpdate: Boolean = false,
        bodyOverride: String? = null,
        replacesActivity: String? = null,
    ) {
        ensureChannels(context)
        // notify() is a silent no-op without POST_NOTIFICATIONS on API 33+;
        // don't crash on the missing permission, just log and bail (the app
        // requests it on launch — see MainActivity.requestNotificationPermission).
        if (!canPost(context)) {
            WearLog.w(context, TAG, "notifySession skipped for ${session.id}: POST_NOTIFICATIONS not granted")
            return
        }

        val notifId = notifIdFor(session.id)
        val contentPending = PendingIntent.getActivity(
            context,
            requestCode(session.id, "content"),
            deepLinkIntent(context, session.id),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )

        val body = bodyOverride ?: bodyFor(session)

        val channel = channelFor(session.activity)
        val builder = NotificationCompat.Builder(context, channel)
            // A mipmap (color launcher icon) as the status-bar small icon
            // isn't the textbook monochrome silhouette, but it's the only
            // in-repo icon and matches the app's identity; InstallResultReceiver
            // uses android.R.drawable.ic_dialog_info instead — kept ours on the
            // app icon deliberately so these read as "from Claude Remote".
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(session.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silentUpdate)

        when (session.activity) {
            "APPROVAL_NEEDED" -> buildApproval(context, builder, session)
            "WAITING_FOR_INPUT" -> buildReply(context, builder, session)
        }

        // Kanál už vyvěšené notifikace NELZE změnit tím, že se na stejné id
        // vyvěsí znovu — tenhle projekt to má draze zaplacené na telefonu
        // (viz AlertNotifier kdoc: alert zůstal tiše viset na LOW kanálu a
        // nikdy nezazvonil). Přesně to potká upgrade WAITING -> APPROVAL:
        // tlačítka Ano/Ne i full-screen intent by naskočily, ale eskalace na
        // IMPORTANCE_HIGH ne. Když se kanál mění, notifikaci proto nejdřív
        // zruš, ať vznikne opravdu nový záznam.
        // ...jenže zrušení + vyvěšení je z pohledu systému NOVÁ notifikace,
        // takže setOnlyAlertOnce už nic neztiší. Ruš proto jen při
        // eskalaci, kde o zabzučení stojíme (WAITING -> APPROVAL). Opačný
        // směr (APPROVAL -> WAITING při přepnutí tabu na telefonu) nechej
        // viset na původním kanálu a jen tiše přepiš obsah — tam by nový
        // záznam znamenal bzučení za degradaci požadavku.
        val channelChanged = replacesActivity != null && channelFor(replacesActivity) != channel
        if (channelChanged && !silentUpdate) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
        NotificationManagerCompat.from(context).notify(notifId, builder.build())
        WearLog.i(
            context, TAG,
            "Posted notification for ${session.id} (${session.activity}) " +
                "silentUpdate=$silentUpdate channelChanged=$channelChanged",
        )
    }

    /**
     * Odeslání na telefon selhalo — vrať notifikaci zpět. Dřív se rušila
     * bezpodmínečně, takže po "No phone connected" zůstalo zápěstí prázdné a
     * nadiktovaná odpověď byla nenávratně pryč. Když session pořád známe,
     * vyvěsíme ji v plné podobě včetně akce "Odpovědět", takže jde rovnou
     * zkusit znovu; napsaný text jde do těla, aby ho šlo přečíst. Když ji
     * neznáme (proces mezitím přišel o repository), aspoň holé oznámení, že
     * se to neodeslalo.
     *
     * [unconfirmed] rozlišuje "opravdu to selhalo" od "vypršel timeout, ale
     * odeslání může doběhnout" — v druhém případě se hlásí "Nepotvrzeno",
     * aby uživatel neposílal totéž podruhé.
     */
    fun notifySendFailure(
        context: Context,
        sessionId: String,
        pendingText: String?,
        error: String,
        unconfirmed: Boolean = false,
    ) {
        val body = buildString {
            // "Nepotvrzeno" ≠ "Neodesláno": po vypršení timeoutu může
            // round-trip přes Play Services doběhnout a odpověď DORAZIT.
            // Tvrdit v tu chvíli "neodesláno" svádí uživatele odeslat to
            // znovu, takže by Claude dostal totéž dvakrát.
            append(if (unconfirmed) "⚠ Nepotvrzeno: " else "⚠ Neodesláno: ")
            append(error)
            if (!pendingText.isNullOrBlank()) {
                append("\n")
                append(pendingText)
            }
        }
        val session = SessionRepository.sessions.value.firstOrNull { it.id == sessionId }
        if (session != null) {
            notifySession(context, session, silentUpdate = false, bodyOverride = body)
            return
        }
        ensureChannels(context)
        if (!canPost(context)) {
            WearLog.w(context, TAG, "notifySendFailure skipped for $sessionId: POST_NOTIFICATIONS not granted")
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_WAITING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Claude Remote")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    requestCode(sessionId, "content"),
                    deepLinkIntent(context, sessionId),
                    PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
                )
            )
            .setAutoCancel(true)
        NotificationManagerCompat.from(context).notify(notifIdFor(sessionId), builder.build())
        WearLog.i(context, TAG, "Posted send-failure notification for $sessionId: $error")
    }

    fun cancelSession(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notifIdFor(sessionId))
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
        // canUseFullScreenIntent() existuje až od API 34 — pod ním se
        // omezení USE_FULL_SCREEN_INTENT na volací/budíkové appky netýká,
        // takže to ber jako povolené. Stejný gate (a stejný warning) jako
        // InstallResultReceiver: bez něj se schválení tiše degraduje na
        // heads-up, spící hodinky se neprobudí a v logu o tom není ani řádka.
        val nm = context.getSystemService(NotificationManager::class.java)
        val canUseFsi = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (nm != null && nm.canUseFullScreenIntent())
        if (canUseFsi) {
            builder.setFullScreenIntent(fullScreenPending, true)
        } else {
            WearLog.w(
                context, TAG,
                "canUseFullScreenIntent() false — approval for ${session.id} falls back to heads-up only",
            )
        }

        // I approval jde diktovat: Soniox nejde spustit z notifikace (chce
        // appku v popředí + mic), tak deep-linkni do detailu a rovnou nastartuj.
        addDictateAction(context, builder, session)
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
        // Soniox streaming diktát jako doplňková volba vedle systémového
        // RemoteInputu ("Odpovědět" = Google hlas/klávesnice).
        addDictateAction(context, builder, session)
    }

    /**
     * "🎤 Diktovat" — na rozdíl od ostatních akcí getActivity (ne broadcast):
     * chceme appku v POPŘEDÍ kvůli mic streamu. Deep-linkne do detailu session
     * s [EXTRA_START_DICTATION], MainActivity pak přes [NavRequest] řekne UI, ať
     * po otevření spustí Soniox diktování (viz SessionDetailScreen).
     */
    private fun addDictateAction(context: Context, builder: NotificationCompat.Builder, session: WearSessionInfo) {
        val dictateIntent = deepLinkIntent(context, session.id).putExtra(EXTRA_START_DICTATION, true)
        val dictatePending = PendingIntent.getActivity(
            context,
            requestCode(session.id, "dictate"),
            dictateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        builder.addAction(
            NotificationCompat.Action.Builder(0, "🎤 Diktovat", dictatePending).build()
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

    // notify() je bez POST_NOTIFICATIONS na API 33+ tichý no-op; nepadat na
    // chybějícím oprávnění, jen zalogovat a nevyvěsit (MainActivity o něj
    // žádá při startu a odmítnutí ukazuje v seznamu session).
    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // S+ requires callers to declare mutability explicitly; below that the
    // flag doesn't exist, so pass 0.
    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    const val EXTRA_SESSION_ID = "session_id"
    // Set by the "🎤 Diktovat" action so MainActivity knows to auto-start
    // Soniox dictation once it routes to the session (a plain tap on the body
    // just opens the session without it).
    const val EXTRA_START_DICTATION = "start_dictation"
    const val EXTRA_ANSWER = "answer"
    const val KEY_REPLY_TEXT = "reply_text"

    private const val TAG = "WearNotifier"
    private const val CHANNEL_APPROVAL = "claude_approval"
    private const val CHANNEL_WAITING = "claude_waiting"
}
