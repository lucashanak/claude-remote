package com.clauderemote.connection

import com.clauderemote.storage.PlatformPreferences
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages SSH key pairs: generation, storage, listing, import/export.
 * Keys are stored in PlatformPreferences as JSON.
 */
class SshKeyManager(private val prefs: PlatformPreferences) {

    @Serializable
    data class ManagedKey(
        val id: String,
        val name: String,
        val type: String, // "rsa", "ed25519"
        val privateKey: String, // PEM format
        val publicKey: String,
        val fingerprint: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun loadKeys(): List<ManagedKey> {
        val raw = prefs.getString("managed_ssh_keys", "")
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<ManagedKey>>(raw)
        } catch (e: Exception) {
            FileLogger.error(TAG, "Failed to load keys", e)
            emptyList()
        }
    }

    private fun saveKeys(keys: List<ManagedKey>) {
        prefs.putString("managed_ssh_keys", json.encodeToString(keys))
    }

    /**
     * Generate a new SSH key pair.
     * @param name Display name for the key
     * @param type "rsa" or "ed25519"
     * @param bits Key size (only for RSA, typically 4096)
     */
    fun generateKey(name: String, type: String = "ed25519", bits: Int = 4096): ManagedKey {
        val jsch = JSch()
        val keyType = when (type.lowercase()) {
            "rsa" -> KeyPair.RSA
            "ed25519" -> KeyPair.ED25519
            "ecdsa" -> KeyPair.ECDSA256
            else -> KeyPair.ED25519
        }
        val kpair = KeyPair.genKeyPair(jsch, keyType, bits)

        val privStream = java.io.ByteArrayOutputStream()
        kpair.writePrivateKey(privStream)
        val privKey = privStream.toString("UTF-8")

        val pubStream = java.io.ByteArrayOutputStream()
        kpair.writePublicKey(pubStream, name)
        val pubKey = pubStream.toString("UTF-8")

        val fingerprint = kpair.fingerPrint
        kpair.dispose()

        val id = java.util.UUID.randomUUID().toString()
        val key = ManagedKey(
            id = id,
            name = name,
            type = type.lowercase(),
            privateKey = privKey,
            publicKey = pubKey,
            fingerprint = fingerprint
        )

        val keys = loadKeys() + key
        saveKeys(keys)
        FileLogger.log(TAG, "Generated $type key: $name ($fingerprint)")
        return key
    }

    fun importKey(name: String, privateKeyPem: String): ManagedKey? {
        return try {
            val jsch = JSch()
            jsch.addIdentity("import", privateKeyPem.toByteArray(), null, null)

            val id = java.util.UUID.randomUUID().toString()
            val key = ManagedKey(
                id = id,
                name = name,
                type = "imported",
                privateKey = privateKeyPem,
                publicKey = "(imported)",
                fingerprint = "(imported)"
            )

            val keys = loadKeys() + key
            saveKeys(keys)
            FileLogger.log(TAG, "Imported key: $name")
            key
        } catch (e: Exception) {
            FileLogger.error(TAG, "Import failed", e)
            null
        }
    }

    fun deleteKey(keyId: String) {
        val keys = loadKeys().filter { it.id != keyId }
        saveKeys(keys)
    }

    fun getKey(keyId: String): ManagedKey? = loadKeys().find { it.id == keyId }

    /**
     * Deploy public key to a remote server (append to authorized_keys).
     */
    suspend fun deployKeyToServer(
        key: ManagedKey,
        serverHost: String,
        port: Int,
        username: String,
        password: String
    ): Boolean {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val jsch = JSch()
                val sess = jsch.getSession(username, serverHost, port)
                sess.setPassword(password)
                sess.setConfig("StrictHostKeyChecking", "no")
                sess.timeout = 10000
                sess.connect(10000)

                val pubKey = key.publicKey.trim()
                val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                        "echo '${pubKey.replace("'", "\\'")}' >> ~/.ssh/authorized_keys && " +
                        "chmod 600 ~/.ssh/authorized_keys"

                val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(command)
                ch.inputStream = null
                ch.connect(5000)
                ch.inputStream.bufferedReader().readText()
                ch.disconnect()
                sess.disconnect()
                FileLogger.log(TAG, "Key deployed to $username@$serverHost")
                true
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "Key deploy failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "SshKeyManager"
    }
}
