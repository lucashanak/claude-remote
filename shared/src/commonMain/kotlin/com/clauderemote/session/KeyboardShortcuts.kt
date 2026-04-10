package com.clauderemote.session

import com.clauderemote.storage.AppSettings

/**
 * Registry of keyboard shortcuts mapped to action IDs.
 * Shortcut format: "ctrl+k", "ctrl+shift+p", "ctrl+tab", "ctrl+w"
 */
object KeyboardShortcuts {

    data class Shortcut(
        val key: String,          // e.g., "k", "tab", "w", "n"
        val ctrl: Boolean = false,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false
    ) {
        val displayString: String get() = buildString {
            if (ctrl) append("Ctrl+")
            if (shift) append("Shift+")
            if (alt) append("Alt+")
            if (meta) append("Cmd+")
            append(key.replaceFirstChar { it.uppercase() })
        }
    }

    data class Binding(
        val shortcut: Shortcut,
        val actionId: String,
        val label: String
    )

    val DEFAULT_BINDINGS = listOf(
        Binding(Shortcut("k", ctrl = true), "command_palette", "Command Palette"),
        Binding(Shortcut("tab", ctrl = true), "next_tab", "Next Tab"),
        Binding(Shortcut("tab", ctrl = true, shift = true), "prev_tab", "Previous Tab"),
        Binding(Shortcut("w", ctrl = true), "close_tab", "Close Tab"),
        Binding(Shortcut("n", ctrl = true), "new_session", "New Session"),
        Binding(Shortcut("d", ctrl = true), "usage_dashboard", "Usage Dashboard"),
        Binding(Shortcut("comma", ctrl = true), "settings", "Settings"),
    )

    fun parseShortcutString(s: String): Shortcut {
        val parts = s.lowercase().split("+").map { it.trim() }
        return Shortcut(
            key = parts.last(),
            ctrl = "ctrl" in parts,
            shift = "shift" in parts,
            alt = "alt" in parts,
            meta = "cmd" in parts || "meta" in parts
        )
    }

    fun shortcutToString(s: Shortcut): String = buildString {
        if (s.ctrl) append("ctrl+")
        if (s.shift) append("shift+")
        if (s.alt) append("alt+")
        if (s.meta) append("meta+")
        append(s.key)
    }

    fun loadBindings(settings: AppSettings): List<Binding> {
        val customJson = settings.customShortcuts
        if (customJson.isBlank()) return DEFAULT_BINDINGS
        return try {
            val pairs = customJson.split(";").filter { it.contains("=") }
            val custom = pairs.associate { pair ->
                val (key, action) = pair.split("=", limit = 2)
                key.trim() to action.trim()
            }
            DEFAULT_BINDINGS.map { binding ->
                val customKey = custom[binding.actionId]
                if (customKey != null) {
                    binding.copy(shortcut = parseShortcutString(customKey))
                } else binding
            }
        } catch (_: Exception) {
            DEFAULT_BINDINGS
        }
    }

    fun saveBindings(settings: AppSettings, bindings: List<Binding>) {
        val str = bindings.joinToString(";") { "${it.actionId}=${shortcutToString(it.shortcut)}" }
        settings.customShortcuts = str
    }
}
