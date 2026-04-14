package com.termux.terminal;

/**
 * Stubbed out in claude-remote — we bypass subprocess creation and feed bytes
 * directly into TerminalEmulator from the SSH stream. No native library needed.
 */
final class JNI {

    public static int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns) {
        throw new UnsupportedOperationException("createSubprocess stubbed: SshTerminalSession should be used instead");
    }

    public static void setPtyWindowSize(int fd, int rows, int cols) {
        // No-op: SshTerminalSession.updateSize resizes the emulator directly and forwards SIGWINCH over SSH.
    }

    public static int waitFor(int processId) {
        throw new UnsupportedOperationException("waitFor stubbed");
    }

    public static void close(int fileDescriptor) {
        // No-op.
    }
}
