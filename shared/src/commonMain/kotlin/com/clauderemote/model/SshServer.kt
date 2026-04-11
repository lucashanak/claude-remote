package com.clauderemote.model

import kotlinx.serialization.Serializable

@Serializable
data class PortForward(
    val type: String = "L", // "L" local, "R" remote
    val localPort: Int,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int
) {
    fun toSshArg(): String = "-$type $localPort:$remoteHost:$remotePort"
}

@Serializable
data class SshServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String? = null,
    val privateKey: String? = null,
    val preferMosh: Boolean = false,
    val defaultFolder: String = "~",
    val recentFolders: List<String> = emptyList(),
    val defaultClaudeMode: ClaudeMode = ClaudeMode.NORMAL,
    val defaultClaudeModel: ClaudeModel = ClaudeModel.DEFAULT,
    val portForwards: List<PortForward> = emptyList(),
    val favorite: Boolean = false,
    val startupCommand: String = "",
    val snippets: List<String> = emptyList(),
    val useCloudflareProxy: Boolean = false,
    val cloudflareToken: String = ""
) {
    val displayAddress: String get() = "$username@$host${if (port != 22) ":$port" else ""}"

    fun withRecentFolder(folder: String): SshServer {
        val updated = (listOf(folder) + recentFolders.filter { it != folder }).take(10)
        return copy(recentFolders = updated)
    }
}
