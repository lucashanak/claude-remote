package com.clauderemote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.clauderemote.session.QuickAccess

/**
 * Trampoline of the static launcher shortcuts "Jarvis" and "Velín" (res/xml/shortcuts.xml).
 *
 * A static shortcut is started with FLAG_ACTIVITY_CLEAR_TASK on its target's task. Pointed at MainActivity that
 * would destroy it — and with it the orchestrator and every live SSH tab — on each tap. This activity has a task of
 * its own (empty taskAffinity, no history, no UI), so the clear hits only that task; it hands the request to
 * MainActivity the way a notification does (NEW_TASK + SINGLE_TOP: the running instance gets onNewIntent, a cold
 * start gets it in onCreate) and finishes before it is ever drawn.
 */
class QuickAccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = QuickAccess.targetOf(intent?.action, intent?.getStringExtra(QuickAccess.EXTRA_TARGET))
        if (target != null) startActivity(MainActivity.quickAccessIntent(this, target))
        finish()
    }
}
