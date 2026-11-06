package com.jcraft.jsch

/**
 * A shell channel that reports itself connected without any session behind it, so a test can hand the registry an
 * `SshManager` that counts as a connected tab. Lives in jsch's package only because the constructor is package-private.
 */
class FakeShellChannel : ChannelShell() {
    override fun isConnected(): Boolean = true
    override fun disconnect() {}
    override fun setPtySize(col: Int, row: Int, wp: Int, hp: Int) {}
}
