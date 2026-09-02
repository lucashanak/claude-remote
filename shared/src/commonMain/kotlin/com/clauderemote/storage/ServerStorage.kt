package com.clauderemote.storage

import com.clauderemote.model.SshServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServerStorage(private val prefs: KeyValueStore) {

    // coerceInputValues: ignoreUnknownKeys only covers unknown KEYS. An unknown
    // ENUM VALUE (e.g. a `transport` written by a newer app build) would still
    // make decodeFromString throw for the WHOLE list, wiping every saved
    // server. With this on, an unrecognized enum constant (or `null` for a
    // non-nullable field) falls back to that property's declared default
    // instead of aborting the decode.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = false }

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

    /**
     * Merge servers from an exported JSON list; returns how many were added.
     * Decoding uses the same lenient [json] as everything else here, so an
     * export from a different app build survives an enum value this one
     * doesn't know. Throws on text that isn't a server list at all, so the
     * platform importers can tell the user instead of failing silently.
     */
    fun importServers(text: String): Int {
        val imported = json.decodeFromString<List<SshServer>>(text)
        imported.forEach { addServer(it) }
        return imported.size
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
