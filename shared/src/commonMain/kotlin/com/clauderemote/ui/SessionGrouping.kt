package com.clauderemote.ui

/**
 * Turns a server's sessions into the rows a list actually renders: a
 * "needs you" section, folder groups with headers, and loose sessions.
 *
 * Why this exists as a pure function: the same (folder, alias) extraction and
 * ordering was written three times — SessionDrawer, TerminalSidePanel and
 * LauncherScreen — each with its own subtly different handling of remote panes
 * and of the alias-vs-label question. One flat list of 52 sessions is what that
 * grew into. Everything here is decided without Compose so the rules below can
 * be tested, which matters because most of them exist to stop a session from
 * quietly disappearing.
 */
internal object SessionGrouping {

    /**
     * A folder needs at least this many sessions to earn a header. With ~15
     * distinct folders, giving every singleton its own header would add more
     * rows than the grouping removes — the exact opposite of the point.
     */
    const val MIN_GROUP_SIZE = 2

    /** One session, already reduced to what grouping and rendering need. */
    data class Entry(
        val id: String,
        val serverId: String,
        /** Folder leaf, lowercased — the grouping key and the sort key. */
        val folderKey: String,
        /** Folder leaf as the user wrote it, for the header. */
        val folderLabel: String,
        val alias: String,
        /** Claude is waiting for input, or the session broke. */
        val needsAttention: Boolean,
        /** The session currently on screen. */
        val isActive: Boolean,
    )

    sealed interface Row {
        /** "Needs you" — only emitted when something is actually waiting. */
        data class AttentionHeader(val count: Int) : Row

        data class FolderHeader(
            val key: String,
            val label: String,
            /** Every session in this folder, INCLUDING any hoisted above. */
            val total: Int,
            /** How many of [total] are currently in the "needs you" section. */
            val hoisted: Int,
            val collapsed: Boolean,
        ) : Row

        /**
         * [inGroup] rows are drawn under a folder header, so they show the
         * alias alone — repeating the folder on every row is what truncated
         * the part the user actually reads ("backendV2 · analyza_b…").
         */
        data class Item(val entry: Entry, val inGroup: Boolean) : Row
    }

    /** Stable across restarts: the folder is derived from the tmux name. */
    fun groupKey(serverId: String, folderKey: String): String = "$serverId|$folderKey"

    /**
     * [collapsed] holds [groupKey] values. [query] filters on folder and alias.
     *
     * Rules, each of which exists to stop something vanishing:
     *  - A **query** switches the list to a plain filtered view: no hoisting,
     *    no collapsing. When you are searching, "it matched but I hid it" is
     *    never the answer you want.
     *  - Sessions needing attention are **hoisted** to the top section and
     *    removed from their folder, so a collapsed folder can never swallow the
     *    one session that is waiting for you. The folder header still counts
     *    them ([Row.FolderHeader.total]) so the number never lies.
     *  - The **active** session is always rendered, even inside a collapsed
     *    folder — the list must never hide what is on screen right now.
     *  - Folders are never collapsed automatically; [collapsed] only ever grows
     *    by an explicit tap. A redesign that hides everything on first launch
     *    reads as data loss.
     */
    fun build(
        entries: List<Entry>,
        collapsed: Set<String>,
        query: String = "",
    ): List<Row> {
        val q = query.trim().lowercase()
        val matching = if (q.isEmpty()) entries else entries.filter {
            it.folderKey.contains(q) || it.alias.lowercase().contains(q)
        }
        if (matching.isEmpty()) return emptyList()

        val ordered = matching.sortedWith(compareBy({ it.folderKey }, { it.alias.lowercase() }))
        if (q.isNotEmpty()) return ordered.map { Row.Item(it, inGroup = false) }

        val attention = ordered.filter { it.needsAttention }
        val rest = ordered.filterNot { it.needsAttention }

        val rows = mutableListOf<Row>()
        if (attention.isNotEmpty()) {
            rows += Row.AttentionHeader(attention.size)
            attention.forEach { rows += Row.Item(it, inGroup = false) }
        }

        // Totals come from the FULL list, not `rest`, so a folder whose only
        // session was hoisted still appears with an honest count instead of
        // silently disappearing from the list.
        //
        // Keyed by (server, folder), never by folder alone: several server
        // entries can point at the SAME machine, so identical folder names
        // across them are normal. Keying on the name would merge two servers'
        // sessions into one group and let one collapse hide the other's.
        val totalByGroup = ordered.groupingBy { groupKey(it.serverId, it.folderKey) }.eachCount()
        // Emitted in folder order with singletons inline, which keeps the
        // alphabetical reading order the list has always had.
        val seen = mutableSetOf<String>()
        for (entry in rest) {
            val key = groupKey(entry.serverId, entry.folderKey)
            if (!seen.add(key)) continue
            val total = totalByGroup[key] ?: 0
            val members = rest.filter { groupKey(it.serverId, it.folderKey) == key }
            if (total < MIN_GROUP_SIZE) {
                members.forEach { rows += Row.Item(it, inGroup = false) }
                continue
            }
            val isCollapsed = key in collapsed
            rows += Row.FolderHeader(
                key = key,
                label = entry.folderLabel,
                total = total,
                hoisted = total - members.size,
                collapsed = isCollapsed,
            )
            if (isCollapsed) {
                members.filter { it.isActive }.forEach { rows += Row.Item(it, inGroup = true) }
            } else {
                members.forEach { rows += Row.Item(it, inGroup = true) }
            }
        }
        return rows
    }

    /**
     * Drops keys for folders that no longer exist, so the stored set cannot
     * grow forever as folders come and go. Called on write, where the live
     * folder list is known — never on read, where a momentarily empty session
     * list would wipe the user's collapse state.
     */
    fun prune(collapsed: Set<String>, live: List<Entry>): Set<String> {
        if (collapsed.isEmpty()) return collapsed
        val liveKeys = live.map { groupKey(it.serverId, it.folderKey) }.toSet()
        return collapsed.intersect(liveKeys)
    }
}
