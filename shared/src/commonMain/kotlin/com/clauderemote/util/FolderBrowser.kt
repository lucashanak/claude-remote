package com.clauderemote.util

import com.clauderemote.connection.TmuxManager
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Browse remote folders via SSH exec channel.
 */
object FolderBrowser {

    suspend fun listFolders(session: Session, path: String = "~"): List<FolderEntry> {
        return TmuxManager.listFolders(session, path).map { fullPath ->
            FolderEntry(
                name = fullPath.substringAfterLast('/'),
                fullPath = fullPath,
                isDirectory = true
            )
        }
    }

    suspend fun getHomeDirectory(session: Session): String = withContext(Dispatchers.IO) {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand("echo \$HOME")
        channel.inputStream = null
        val input = channel.inputStream
        channel.connect(5000)
        val home = input.bufferedReader().readText().trim()
        channel.disconnect()
        home.ifBlank { "~" }
    }
}

data class FolderEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean
)
