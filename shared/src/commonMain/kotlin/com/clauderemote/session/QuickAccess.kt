package com.clauderemote.session

import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SessionStatus

/** Where a one-tap "quick access" goes: Jarvis' tab, the Velín, or the Velín's Jarvis tab (his merged notification). */
enum class QuickTarget { JARVIS, VELIN, JARVIS_NEWS, JARVIS_TALK }

/** What a tap on Jarvis does, given what this device knows ([QuickAccess.route]). */
sealed interface JarvisRoute {
    /** Jarvis has a tab here: show it. [reclaim] = the tab's close is still in its undo window, undo it first. */
    data class SwitchTab(val tabId: String, val reclaim: Boolean) : JarvisRoute

    /** Jarvis runs on [remote]'s server but no tab shows it: attach to it the way a detached remote row is opened. */
    data class Attach(val remote: RemoteSession) : JarvisRoute

    /** No tab and no server scan has answered yet: scan first, then route again. */
    data object Unknown : JarvisRoute

    /** No tab, and every server that answered the scan lacks the session: "Jarvis neběží" and the Velín. */
    data object NotRunning : JarvisRoute
}

/**
 * Quick access to Jarvis and the Velín from anywhere: the top-bar button, Ctrl+Shift+J / Ctrl+Shift+D, the palette,
 * the Android launcher shortcuts and the quick-settings tile all end here.
 *
 * Never a new session: Jarvis is found among this device's tabs or attached to where the last scan saw it live
 * (`launchSession(isNewTmuxSession = false)`, the remote-row path, which switches to an existing tab of the name).
 */
object QuickAccess {
    /** Jarvis' tmux name; the server keeps the same in `~/.claude-remote/jarvis/self`. */
    const val JARVIS_TMUX_NAME = "claude-server-jarvis--jarvis"

    /** Intent actions of the launcher shortcuts and the tile (Android). */
    const val ACTION_JARVIS = "com.clauderemote.action.JARVIS"
    const val ACTION_VELIN = "com.clauderemote.action.VELIN"
    /** Jarvis' conversation mode (F5a): opens his tab and starts talking. */
    const val ACTION_JARVIS_TALK = "com.clauderemote.action.JARVIS_TALK"

    /** The extra form of the same request ("jarvis" / "velin"), for intents that keep the MAIN action. */
    const val EXTRA_TARGET = "quick_access"

    fun extraValue(target: QuickTarget): String = when (target) {
        QuickTarget.JARVIS_NEWS -> "jarvis-news"
        QuickTarget.JARVIS_TALK -> "jarvis-talk"
        else -> target.name.lowercase()
    }

    /** The in-process token that lets an intent start the conversation mode ([authorize]). */
    const val EXTRA_TALK_TOKEN = "quick_access_talk"

    private val talkLock = Any()
    private var talkToken: String? = null

    /**
     * A one-time token for an intent this process builds itself (QuickAccessActivity, which is not exported). Only one
     * is live: a newer one replaces it.
     */
    fun issueTalkToken(): String = java.util.UUID.randomUUID().toString().also { synchronized(talkLock) { talkToken = it } }

    /**
     * MainActivity is exported, so anyone can send it `jarvis-talk`; opening the microphone (and then sending what it
     * hears to Jarvis, who acts on a spoken "ano") must take a token only this process could have issued. Without it
     * the request is downgraded to opening Jarvis. The token is used up either way.
     */
    fun authorize(target: QuickTarget, token: String?): QuickTarget {
        if (target != QuickTarget.JARVIS_TALK) return target
        val ok = synchronized(talkLock) {
            val live = talkToken
            talkToken = null
            live != null && token != null && live == token
        }
        return if (ok) QuickTarget.JARVIS_TALK else QuickTarget.JARVIS
    }

    /** The target an intent asks for, or null when it is not a quick-access intent. The action wins over the extra. */
    fun targetOf(action: String?, extra: String?): QuickTarget? = when (action) {
        ACTION_JARVIS -> QuickTarget.JARVIS
        ACTION_VELIN -> QuickTarget.VELIN
        ACTION_JARVIS_TALK -> QuickTarget.JARVIS_TALK
        else -> when (extra?.trim()?.lowercase()) {
            "jarvis" -> QuickTarget.JARVIS
            "velin", "velín" -> QuickTarget.VELIN
            "jarvis-news" -> QuickTarget.JARVIS_NEWS
            "jarvis-talk" -> QuickTarget.JARVIS_TALK
            else -> null
        }
    }

    /**
     * Where a tap on Jarvis goes.
     *
     * [tabs] = this device's tabs (hidden ones included), [hiddenIds] = tabs whose close waits out its undo window,
     * [remote] = the last scan's tmux sessions over all servers, [scannedServerIds] = the servers whose last scan
     * answered (a server that did not answer proves nothing about its sessions).
     *
     * 1. A tab of the name is shown — unless its server answered the scan without the session and the tab is not
     *    connected (a dead tab is "not running", the stale-tab prune forgets it later). Of several tabs (more servers),
     *    a connected one first, then one the scan saw live, then the first.
     * 2. No usable tab: the first remote session of the name (scan order = server order) is attached.
     * 3. Nothing: [JarvisRoute.Unknown] until some server answered a scan, then [JarvisRoute.NotRunning].
     */
    fun route(
        tabs: List<ClaudeSession>,
        hiddenIds: Set<String>,
        remote: List<RemoteSession>,
        scannedServerIds: Set<String>,
        name: String = JARVIS_TMUX_NAME,
    ): JarvisRoute {
        val liveOn = remote.filter { it.tmuxSession.name == name }.mapTo(HashSet()) { it.server.id }
        val usable = tabs.filter { t ->
            t.tmuxSessionName == name &&
                !(t.status != SessionStatus.ACTIVE && t.server.id in scannedServerIds && t.server.id !in liveOn)
        }
        val tab = usable.firstOrNull { it.status == SessionStatus.ACTIVE }
            ?: usable.firstOrNull { it.server.id in liveOn }
            ?: usable.firstOrNull()
        if (tab != null) return JarvisRoute.SwitchTab(tab.id, reclaim = tab.id in hiddenIds)
        remote.firstOrNull { it.tmuxSession.name == name }?.let { return JarvisRoute.Attach(it) }
        return if (scannedServerIds.isEmpty()) JarvisRoute.Unknown else JarvisRoute.NotRunning
    }

    /**
     * A tab that is connected or connecting (a cold start's restore) is shown at once; anything else — a dead tab, an
     * attach, nothing at all — waits for a fresh scan, so the attach goes where Jarvis runs now and "not running" is
     * said only by servers that answered.
     */
    fun needsFreshScan(route: JarvisRoute, tabs: List<ClaudeSession>): Boolean =
        !(route is JarvisRoute.SwitchTab && tabs.any { it.id == route.tabId && it.status in SHOWN_AT_ONCE })

    private val SHOWN_AT_ONCE = setOf(SessionStatus.ACTIVE, SessionStatus.CONNECTING)

    /** The route after a fresh scan: no answer from any server still counts as "not running" (the user gets the Velín). */
    fun settle(route: JarvisRoute): JarvisRoute = if (route is JarvisRoute.Unknown) JarvisRoute.NotRunning else route

    /** The short notice before the Velín opens: no server answered is said apart from "it does not run". */
    fun notRunningNotice(anyServerAnswered: Boolean): String =
        if (anyServerAnswered) "Jarvis neběží" else "Jarvis neběží (server neodpověděl)"
}
