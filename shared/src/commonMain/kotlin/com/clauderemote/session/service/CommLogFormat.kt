package com.clauderemote.session.service

/**
 * Log line formats of the communication redesign, fixed here so the metrics
 * extractor (`tools/relay-metrics`, parses `key=value` tolerantly) and the code
 * that emits them agree. Emitters: `data/60s` (SessionOrchestrator), `Attach`
 * (attach path).
 */
object CommLogFormat {
    /** Appended to the END of the `data/60s:` line; the existing prefix is untouched. */
    fun dataTail(attached: Int, warm: Int, parked: Int, exec: Int): String =
        " | attached=$attached warm=$warm parked=$parked exec=$exec"

    /** Attach phases in the canonical order. */
    val ATTACH_PHASES = listOf("shell", "prompt", "decide", "attach", "firstByte")

    /**
     * `Attach <sid> kind=cold|warm ms=<n> phase=shell=<ms>,prompt=<ms>,…`. The phase
     * value is a comma-separated list of `name=<ms>` items (key=value inside the
     * value, which the extractor's tolerant parser reads). [phasesMs] may hold any
     * subset of [ATTACH_PHASES] (warm attaches skip some); they are emitted in
     * canonical order. With no phases, `phase=-`.
     */
    fun attach(sessionId: String, cold: Boolean, totalMs: Long, phasesMs: Map<String, Long> = emptyMap()): String {
        val phase = ATTACH_PHASES.filter { it in phasesMs }.joinToString(",") { "$it=${phasesMs.getValue(it)}" }
            .ifEmpty { "-" }
        return "Attach $sessionId kind=${if (cold) "cold" else "warm"} ms=$totalMs phase=$phase"
    }

    /**
     * The whole `data/60s:` line: today's [prefix] (everything that was logged before
     * the redesign, byte for byte) followed by [dataTail]. `warm`, `parked` and `exec`
     * are 0 until the features that count them exist. Pure apart from reading the
     * registry, so the invariant "prefix unchanged, tail last" is testable without
     * running the orchestrator's 60 s loop.
     */
    internal fun data60sLine(prefix: String, registry: ConnectionRegistry): String =
        prefix + dataTail(attached = registry.allSsh().count { it.isConnected }, warm = 0, parked = 0, exec = 0)
}
