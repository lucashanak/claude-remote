package com.clauderemote.storage

import com.clauderemote.model.SshServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServerStorage(private val prefs: PlatformPreferences) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun loadServers(): List<SshServer> {
        val raw = prefs.getString(KEY_SERVERS, "[]")
        return try {
            json.decodeFromString<List<SshServer>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveServers(servers: List<SshServer>) {
        prefs.putString(KEY_SERVERS, json.encodeToString(servers))
    }

    fun addServer(server: SshServer) {
        val servers = loadServers().toMutableList()
        servers.add(server)
        saveServers(servers)
    }

    fun updateServer(server: SshServer) {
        val servers = loadServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            servers[index] = server
            saveServers(servers)
        }
    }

    fun deleteServer(id: String) {
        val servers = loadServers().filter { it.id != id }
        saveServers(servers)
    }

    fun getServer(id: String): SshServer? = loadServers().find { it.id == id }

    // Known hosts (TOFU)
    fun loadKnownHosts(): Map<String, String> {
        val raw = prefs.getString(KEY_KNOWN_HOSTS, "{}")
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveKnownHost(host: String, fingerprint: String) {
        val hosts = loadKnownHosts().toMutableMap()
        hosts[host] = fingerprint
        prefs.putString(KEY_KNOWN_HOSTS, json.encodeToString(hosts))
    }

    companion object {
        private const val KEY_SERVERS = "ssh_servers"
        private const val KEY_KNOWN_HOSTS = "known_hosts"
    }
}
