package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SlashCommand(
    val command: String,
    val description: String = "",
    val category: String = "Commands"
)

/**
 * Fetches available Claude Code slash commands from the remote server.
 * Falls back to a comprehensive built-in list if fetch fails.
 */
object CommandFetcher {

    private var cachedCommands: List<SlashCommand>? = null

    suspend fun fetchCommands(sshManager: SshManager): List<SlashCommand> {
        cachedCommands?.let { return it }

        val session = sshManager.getSession() ?: return FALLBACK_COMMANDS
        val commands = try {
            withContext(Dispatchers.IO) {
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(SCAN_SCRIPT)
                channel.inputStream = null
                val input = channel.inputStream
                channel.connect(5000)
                val output = input.bufferedReader().readText()
                channel.disconnect()

                parseCommands(output)
            }
        } catch (e: Exception) {
            FileLogger.error("CommandFetcher", "Failed to fetch commands", e)
            null
        }

        val result = if (commands != null && commands.size > 10) commands else FALLBACK_COMMANDS
        cachedCommands = result
        FileLogger.log("CommandFetcher", "Loaded ${result.size} commands (${if (commands != null) "remote" else "fallback"})")
        return result
    }

    fun getCachedOrFallback(): List<SlashCommand> = cachedCommands ?: FALLBACK_COMMANDS

    fun clearCache() { cachedCommands = null }

    private val SCAN_SCRIPT = """
        if [ -f ~/.claude/cache/changelog.md ]; then
            grep -oP '`/[a-z][-a-z]*`' ~/.claude/cache/changelog.md 2>/dev/null | sort -u | sed 's/`//g' | while read c; do
                printf '%s\t\tCommands\n' "${'$'}c"
            done
        fi
        for dir in ~/.claude/commands; do
            [ -d "${'$'}dir" ] || continue
            find "${'$'}dir" -maxdepth 2 -name '*.md' 2>/dev/null | while read f; do
                name=${'$'}(basename "${'$'}f" .md)
                printf '/%s\t\tUser\n' "${'$'}name"
            done
        done
        if [ -d ~/.claude/plugins/cache ]; then
            find ~/.claude/plugins/cache -maxdepth 6 -name SKILL.md 2>/dev/null | while read f; do
                name=${'$'}(awk '/^name:/ {sub(/^name:[ ]*/, ""); print; exit}' "${'$'}f" 2>/dev/null)
                desc=${'$'}(awk '/^description:/ {sub(/^description:[ ]*/, ""); print; exit}' "${'$'}f" 2>/dev/null)
                [ -n "${'$'}name" ] && printf '/%s\t%s\tPlugin Skills\n' "${'$'}name" "${'$'}desc"
            done
        fi
    """.trimIndent()

    private fun parseCommands(output: String): List<SlashCommand>? {
        val seen = mutableSetOf<String>()
        val commands = output.lines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                val cmd = parts.getOrNull(0)?.trim() ?: return@mapNotNull null
                if (!cmd.startsWith("/") || cmd.length <= 1) return@mapNotNull null
                if (!seen.add(cmd)) return@mapNotNull null
                val desc = parts.getOrNull(1)?.trim().orEmpty().ifBlank { DESCRIPTIONS[cmd].orEmpty() }
                val category = parts.getOrNull(2)?.trim().orEmpty().ifBlank { "Commands" }
                SlashCommand(cmd, desc, category)
            }
            .sortedWith(compareBy({ categoryOrder(it.category) }, { it.command }))

        return if (commands.size > 5) commands else null
    }

    private fun categoryOrder(category: String): Int = when (category) {
        "Commands" -> 0
        "User" -> 1
        "Plugin Skills" -> 2
        else -> 3
    }

    private val DESCRIPTIONS = mapOf(
        "/add-dir" to "Add directory to workspace",
        "/batch" to "Batch mode operations",
        "/branch" to "Create/switch git branch",
        "/btw" to "Background note to Claude",
        "/buddy" to "Pair programming mode",
        "/chrome" to "Chrome browser control",
        "/claude-api" to "Claude API tools",
        "/clear" to "Clear conversation context",
        "/color" to "Change color scheme",
        "/commit-push-pr" to "Commit, push, and create PR",
        "/compact" to "Compact context manually",
        "/config" to "Open settings",
        "/context" to "View context info",
        "/copy" to "Copy last response",
        "/cost" to "Show session cost",
        "/debug" to "Debug information",
        "/doctor" to "Health check",
        "/effort" to "Set effort level",
        "/env" to "Environment variables",
        "/exit" to "Exit Claude Code",
        "/export" to "Export conversation",
        "/extra-usage" to "Extra usage info",
        "/feedback" to "Send feedback",
        "/fork" to "Fork current session",
        "/heapdump" to "Generate heap dump",
        "/help" to "Show help",
        "/hooks" to "Manage hooks",
        "/ide" to "Connect to IDE",
        "/keybindings" to "Customize keyboard shortcuts",
        "/login" to "Authentication",
        "/loop" to "Run command on interval",
        "/mcp" to "Manage MCP servers",
        "/memory" to "Edit memory files",
        "/mobile" to "Mobile mode",
        "/model" to "Switch model (interactive)",
        "/output-style" to "Change output style",
        "/permissions" to "Manage permissions",
        "/plan" to "Enter plan mode",
        "/plugin" to "Manage plugins",
        "/plugins" to "List plugins",
        "/poll" to "Poll for status",
        "/powerup" to "Interactive lessons",
        "/pr-comments" to "View PR comments",
        "/reload-plugins" to "Reload plugins",
        "/remote-control" to "Remote control mode",
        "/remote-env" to "Remote environment",
        "/rename" to "Rename session",
        "/resume" to "Resume conversation",
        "/rewind" to "Undo code changes",
        "/sandbox" to "Sandbox mode",
        "/security-review" to "Security review",
        "/settings" to "Open settings",
        "/simplify" to "Simplify code",
        "/skills" to "List available skills",
        "/stats" to "Usage statistics",
        "/status" to "View status",
        "/tag" to "Tag session",
        "/tasks" to "Manage tasks",
        "/teleport" to "Teleport mode",
        "/terminal-setup" to "Configure terminal",
        "/theme" to "Change theme",
        "/thinking" to "Toggle thinking mode",
        "/todos" to "View todo list",
        "/upgrade" to "Upgrade Claude Code",
        "/usage" to "Check plan limits",
        "/voice" to "Voice mode",
    )

    val FALLBACK_COMMANDS = DESCRIPTIONS.map { (cmd, desc) -> SlashCommand(cmd, desc) }
        .sortedBy { it.command }
}
