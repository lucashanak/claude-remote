package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SlashCommand(val command: String, val description: String = "")

/**
 * Fetches available Claude Code slash commands from the remote server.
 * Falls back to a comprehensive built-in list if fetch fails.
 */
object CommandFetcher {

    private var cachedCommands: List<SlashCommand>? = null

    /**
     * Fetch commands from the remote server by grepping Claude's changelog/help.
     * Uses SSH exec channel (not the shell channel) to avoid interfering with active session.
     */
    suspend fun fetchCommands(sshManager: SshManager): List<SlashCommand> {
        cachedCommands?.let { return it }

        val session = sshManager.getSession() ?: return FALLBACK_COMMANDS
        val commands = try {
            withContext(Dispatchers.IO) {
                // Try to get commands from claude's changelog or help
                val cmd = """
                    if command -v claude >/dev/null 2>&1; then
                        claude --help 2>&1 | head -60
                    fi
                    # Also extract slash commands from changelog if available
                    if [ -f ~/.claude/cache/changelog.md ]; then
                        echo "---SLASH_COMMANDS---"
                        grep -oP '`/[a-z][-a-z]*`' ~/.claude/cache/changelog.md 2>/dev/null | sort -u | sed 's/`//g'
                    fi
                """.trimIndent()

                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(cmd)
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

    private fun parseCommands(output: String): List<SlashCommand>? {
        val slashSection = output.substringAfter("---SLASH_COMMANDS---", "")
        if (slashSection.isBlank()) return null

        val commands = slashSection.lines()
            .map { it.trim() }
            .filter { it.startsWith("/") && it.length > 1 }
            .distinct()
            .sorted()
            .map { cmd ->
                val desc = DESCRIPTIONS[cmd] ?: ""
                SlashCommand(cmd, desc)
            }

        return if (commands.size > 5) commands else null
    }

    // Descriptions for known commands (enhances the raw list from changelog)
    private val DESCRIPTIONS = mapOf(
        "/add-dir" to "Add directory to workspace",
        "/batch" to "Batch mode operations",
        "/branch" to "Create/switch git branch",
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
        "/exit" to "Exit Claude Code",
        "/export" to "Export conversation",
        "/feedback" to "Send feedback",
        "/fork" to "Fork current session",
        "/help" to "Show help",
        "/hooks" to "Manage hooks",
        "/ide" to "Connect to IDE",
        "/keybindings" to "Customize keyboard shortcuts",
        "/login" to "Authentication",
        "/loop" to "Run command on interval",
        "/mcp" to "Manage MCP servers",
        "/memory" to "Edit memory files",
        "/model" to "Switch model (interactive)",
        "/output-style" to "Change output style",
        "/permissions" to "Manage permissions",
        "/plan" to "Enter plan mode",
        "/plugin" to "Manage plugins",
        "/plugins" to "List plugins",
        "/pr-comments" to "View PR comments",
        "/reload-plugins" to "Reload plugins",
        "/remote-control" to "Remote control mode",
        "/rename" to "Rename session",
        "/resume" to "Resume conversation",
        "/rewind" to "Undo code changes",
        "/security-review" to "Security review",
        "/settings" to "Open settings",
        "/simplify" to "Simplify code",
        "/skills" to "List available skills",
        "/stats" to "Usage statistics",
        "/status" to "View status",
        "/tasks" to "Manage tasks",
        "/theme" to "Change theme",
        "/todos" to "View todo list",
        "/upgrade" to "Upgrade Claude Code",
        "/usage" to "Check plan limits",
        "/voice" to "Voice mode",
    )

    // Comprehensive fallback list
    val FALLBACK_COMMANDS = listOf(
        SlashCommand("/plan", "Enter plan mode"),
        SlashCommand("/model", "Switch model (interactive)"),
        SlashCommand("/clear", "Clear conversation context"),
        SlashCommand("/compact", "Compact context manually"),
        SlashCommand("/config", "Open settings"),
        SlashCommand("/help", "Show help"),
        SlashCommand("/rewind", "Undo code changes"),
        SlashCommand("/resume", "Resume conversation"),
        SlashCommand("/status", "View status"),
        SlashCommand("/usage", "Check plan limits"),
        SlashCommand("/stats", "Usage statistics"),
        SlashCommand("/context", "View context info"),
        SlashCommand("/add-dir", "Add directory to workspace"),
        SlashCommand("/thinking", "Toggle thinking mode"),
        SlashCommand("/effort", "Set effort level"),
        SlashCommand("/memory", "Edit memory files"),
        SlashCommand("/copy", "Copy last response"),
        SlashCommand("/debug", "Debug information"),
        SlashCommand("/cost", "Show session cost"),
        SlashCommand("/permissions", "Manage permissions"),
        SlashCommand("/hooks", "Manage hooks"),
        SlashCommand("/mcp", "Manage MCP servers"),
        SlashCommand("/plugin", "Manage plugins"),
        SlashCommand("/skills", "List available skills"),
        SlashCommand("/theme", "Change theme"),
        SlashCommand("/keybindings", "Customize keyboard shortcuts"),
        SlashCommand("/export", "Export conversation"),
        SlashCommand("/fork", "Fork current session"),
        SlashCommand("/rename", "Rename session"),
        SlashCommand("/tasks", "Manage tasks"),
        SlashCommand("/todos", "View todo list"),
        SlashCommand("/doctor", "Health check"),
        SlashCommand("/upgrade", "Upgrade Claude Code"),
        SlashCommand("/login", "Authentication"),
        SlashCommand("/feedback", "Send feedback"),
        SlashCommand("/commit-push-pr", "Commit, push, and create PR"),
        SlashCommand("/pr-comments", "View PR comments"),
        SlashCommand("/security-review", "Security review"),
        SlashCommand("/simplify", "Simplify code"),
        SlashCommand("/branch", "Create/switch git branch"),
        SlashCommand("/loop", "Run command on interval"),
        SlashCommand("/ide", "Connect to IDE"),
        SlashCommand("/voice", "Voice mode"),
        SlashCommand("/output-style", "Change output style"),
    )
}
