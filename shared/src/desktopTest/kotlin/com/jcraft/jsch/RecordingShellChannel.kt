package com.jcraft.jsch

import java.util.concurrent.CopyOnWriteArrayList

/**
 * C5: a connected shell channel (no session behind it) that records each window-change it is asked for and the thread
 * that asked; [onPty] runs inside the call (a test can hold it there). Lives in jsch's package only because the
 * constructor is package-private.
 */
class RecordingShellChannel(private val onPty: (Int, Int) -> Unit = { _, _ -> }) : ChannelShell() {
    data class Pty(val cols: Int, val rows: Int, val thread: String)

    val ptys = CopyOnWriteArrayList<Pty>()
    val disconnectThreads = CopyOnWriteArrayList<String>()

    override fun isConnected(): Boolean = true
    override fun disconnect() { disconnectThreads += Thread.currentThread().name }
    override fun setPtySize(col: Int, row: Int, wp: Int, hp: Int) {
        ptys += Pty(col, row, Thread.currentThread().name)
        onPty(col, row)
    }
}
