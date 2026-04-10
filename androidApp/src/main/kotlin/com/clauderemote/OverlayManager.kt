package com.clauderemote

import android.annotation.SuppressLint
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager

/**
 * Manages the overlay keyboard WebView and routes input to active terminal session.
 * Simplified from vscode_android: no GeckoView/cursor injection, only terminal input.
 */
class OverlayManager(
    private val overlayWebView: WebView,
    private val tabManager: TabManager,
    private val sessionOrchestrator: SessionOrchestrator,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "OverlayManager"
    }

    var isVisible = false
        private set

    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        overlayWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        overlayWebView.setBackgroundColor(0xFF1E1E1E.toInt())
        overlayWebView.addJavascriptInterface(JSInterface(), "Android")
        overlayWebView.loadUrl("file:///android_asset/overlay-ui/overlay.html")
        registerClipboardListener()
    }

    /** Replace JS setInterval clipboard polling with a proper Android listener.
     *  Fires only on actual clipboard changes — zero battery cost at idle. */
    private fun registerClipboardListener() {
        val cm = overlayWebView.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            if (!isVisible) return@OnPrimaryClipChangedListener
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
            val json = org.json.JSONObject.quote(text)
            overlayWebView.post {
                overlayWebView.evaluateJavascript("onNativeClipboardChange($json)", null)
            }
        }
        cm.addPrimaryClipChangedListener(listener)
        clipboardListener = listener
    }

    fun destroy() {
        clipboardListener?.let {
            val cm = overlayWebView.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.removePrimaryClipChangedListener(it)
            clipboardListener = null
        }
    }

    fun show() {
        if (isVisible) return
        isVisible = true
        overlayWebView.visibility = View.VISIBLE
        onVisibilityChanged(true)
    }

    fun hide() {
        if (!isVisible) return
        isVisible = false
        overlayWebView.visibility = View.GONE
        onVisibilityChanged(false)
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    private fun sendToActiveSession(data: String) {
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.sendInput(id, data)
        }
    }

    // Map overlay key names to terminal escape sequences
    private fun keyToTerminalSequence(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String? {
        val base = when (key) {
            "Enter" -> "\r"
            "Bksp" -> "\u007F"
            "Tab" -> "\t"
            "Esc" -> "\u001B"
            "Space" -> " "
            "Up" -> "\u001B[A"
            "Down" -> "\u001B[B"
            "Right" -> "\u001B[C"
            "Left" -> "\u001B[D"
            "Home" -> "\u001B[H"
            "End" -> "\u001B[F"
            "PgUp" -> "\u001B[5~"
            "PgDn" -> "\u001B[6~"
            "Del" -> "\u001B[3~"
            "Ins" -> "\u001B[2~"
            "F1" -> "\u001BOP"
            "F2" -> "\u001BOQ"
            "F3" -> "\u001BOR"
            "F4" -> "\u001BOS"
            "F5" -> "\u001B[15~"
            "F6" -> "\u001B[17~"
            "F7" -> "\u001B[18~"
            "F8" -> "\u001B[19~"
            "F9" -> "\u001B[20~"
            "F10" -> "\u001B[21~"
            "F11" -> "\u001B[23~"
            "F12" -> "\u001B[24~"
            else -> {
                if (key.length == 1) key else return null
            }
        }

        // Ctrl modifier: convert letter to control character
        if (ctrl && base.length == 1) {
            val ch = base[0]
            if (ch in 'a'..'z') return (ch - 'a' + 1).toChar().toString()
            if (ch in 'A'..'Z') return (ch - 'A' + 1).toChar().toString()
        }

        // Alt modifier: prepend ESC
        if (alt && base.length == 1) {
            return "\u001B$base"
        }

        return base
    }

    @Suppress("unused")
    inner class JSInterface {
        @JavascriptInterface
        fun sendChar(ch: String) {
            sendToActiveSession(ch)
        }

        @JavascriptInterface
        fun sendKey(json: String) {
            try {
                val obj = org.json.JSONObject(json)
                val seq = keyToTerminalSequence(
                    obj.getString("key"),
                    obj.optBoolean("ctrl"),
                    obj.optBoolean("alt"),
                    obj.optBoolean("shift")
                )
                if (seq != null) sendToActiveSession(seq)
            } catch (_: Exception) {}
        }

        // Touchpad not needed for terminal-only app, but keep stubs
        @JavascriptInterface
        fun pointerMove(dx: Float, dy: Float) {}

        @JavascriptInterface
        fun mouseDown(button: Int) {}

        @JavascriptInterface
        fun mouseUp(button: Int) {}

        @JavascriptInterface
        fun click(button: Int) {}

        @JavascriptInterface
        fun doubleClick() {}

        @JavascriptInterface
        fun scroll(deltaY: Float) {}

        @JavascriptInterface
        fun hideOverlay() {
            overlayWebView.post { hide() }
        }

        @JavascriptInterface
        fun backToMenu() {
            overlayWebView.post { hide() }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val cm = overlayWebView.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        }

        @JavascriptInterface
        fun haptic() {
            val ctx = overlayWebView.context
            val prefs = ctx.getSharedPreferences("claude_remote", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("haptic_feedback", false)) return

            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(5, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(5)
            }
        }
    }
}
