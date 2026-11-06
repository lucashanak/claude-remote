package com.clauderemote.session

import com.clauderemote.util.FileLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The platform's default-network callback, ONE per process, always delivered to the CURRENT orchestrator.
 *
 * Android registered a callback in every `MainActivity.onCreate` and never unregistered it, so after a recreated
 * activity the callback of the old one still drove the OLD orchestrator: a second teardown and a second reconnect
 * sweep over the old tabs on every network change. Unregistering in `onDestroy` instead would stop the reaction to
 * network changes while the activity is gone and the process (KeepAliveService) lives on. So the registration is made
 * once per process, and each event asks [register]'s `current` for the orchestrator to drive.
 */
object NetworkCallbackHub {
    /** What a network event drives: the live orchestrator. */
    interface Target {
        fun onNetworkAvailable()
        fun onNetworkLost()
        fun reconnectDeadTabs(reason: String)
    }

    /** The two events the platform callback forwards. */
    interface Listener {
        fun onAvailable()
        fun onLost()
    }

    private const val TAG = "Network"
    private val registered = AtomicBoolean(false)
    @Volatile private var current: () -> Target? = { null }

    private val listener = object : Listener {
        override fun onAvailable() {
            FileLogger.log(TAG, "Default network available, checking for dead sessions")
            val t = current() ?: return
            // exec transports before the tabs reconnect (they keep the shell cap and the forwards)
            t.onNetworkAvailable()
            t.reconnectDeadTabs("onAvailable")
        }

        override fun onLost() {
            // Always about the default network (the contract of the default-network callback): kill the pooled transports
            // now instead of waiting ~20 s for keepalive, turning a handover freeze into a ~1 s blip.
            FileLogger.log(TAG, "Default network lost — tearing down transports")
            current()?.onNetworkLost()
        }
    }

    /**
     * Point the events at [current] (called by every activity instance) and register the platform callback through
     * [platformRegister] the first time only. A registration that threw is not counted, so the next call tries again.
     */
    fun register(current: () -> Target?, platformRegister: (Listener) -> Unit) {
        this.current = current
        if (!registered.compareAndSet(false, true)) return
        try {
            platformRegister(listener)
        } catch (e: Exception) {
            registered.set(false)
            FileLogger.error(TAG, "Failed to register network callback", e)
        }
    }

    internal fun resetForTest() {
        registered.set(false)
        current = { null }
    }
}
