package com.clauderemote.session

import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.FakeShellChannel
import com.jcraft.jsch.Session

/**
 * Builds the channel-budget situations of the exec layer without a network: a shell transport of the pool that
 * carries [shells] tabs (sshd's `MaxSessions` is 10, the pool keeps 2 of them free and lends one of those to a short
 * channel: 7 shells leave room for exactly one LONG counted channel and 8 for none, 8 shells for exactly one SHORT
 * one), and tab managers that count as connected.
 *
 * The pool fills its transport list only through a real handshake, so the transport is put there by reflection. The
 * constructor is matched by parameter TYPE, not position, so an added field does not break the fixture silently: an
 * unknown parameter type fails loudly.
 */
object BudgetFixture {
    fun injectTransport(pool: ServerTransportPool, session: Session, shells: Int, host: String = "127.0.0.1", port: Int = 1) {
        val tCls = Class.forName("com.clauderemote.connection.ServerTransportPool\$Transport")
        val roleCls = Class.forName("com.clauderemote.connection.TransportRole")
        val shellRole = roleCls.enumConstants.first { (it as Enum<*>).name == "SHELL" }
        val ctor = tCls.declaredConstructors.first { it.parameterTypes.firstOrNull() == Session::class.java }
        ctor.isAccessible = true
        var strings = 0
        val args = ctor.parameterTypes.map { p ->
            when (p) {
                Session::class.java -> session
                String::class.java -> if (strings++ == 0) host else "lan"
                Int::class.javaPrimitiveType -> port
                Boolean::class.javaPrimitiveType -> false
                Long::class.javaPrimitiveType -> System.currentTimeMillis()
                roleCls -> shellRole
                com.clauderemote.connection.TransportEnd::class.java -> com.clauderemote.connection.TransportEnd("fixture")
                else -> error("BudgetFixture: unknown Transport parameter type $p — update the fixture")
            }
        }
        val t = ctor.newInstance(*args.toTypedArray())
        @Suppress("UNCHECKED_CAST")
        val lessees = tCls.getMethod("getLessees").also { it.isAccessible = true }.invoke(t) as MutableSet<Any>
        repeat(shells) { lessees.add(Any()) }
        val f = ServerTransportPool::class.java.getDeclaredField("transports").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(pool) as MutableList<Any>).add(t)
    }

    /** An [SshManager] whose session is [session] and whose shell reads connected: what a connected tab looks like to the registry. */
    fun connectedManager(storage: ServerStorage, session: Session, pool: ServerTransportPool? = null): SshManager {
        val m = SshManager(storage, transportPool = pool)
        SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(m, session)
        SshManager::class.java.getDeclaredField("channel").also { it.isAccessible = true }.set(m, FakeShellChannel())
        return m
    }
}
