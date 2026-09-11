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
        /**
         * Everything the filter should match, lowercased by the caller: alias,
         * the full folder path, the server name, the mode, the tmux name. It
         * lives here because the drawer used to pre-filter on all of those and
         * then this model filtered again on folder+alias alone — a query for a
         * server name passed the first filter, matched nothing in the second,
         * and produced an empty list with no "no matches" message.
         */
        val searchText: String,
        /** Claude is waiting for input, or the session broke. */
        val needsAttention: Boolean,
        /** The session currently on screen. */
        val isActive: Boolean,
    )

    sealed interface Row {
        /** "Needs you" — only emitted when something is actually waiting. */
        data class AttentionHeader(val count: Int) : Row

        /** Heads the loose sessions whose folder has only one of them. */
        data class OtherHeader(val count: Int) : Row

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

    /**
     * Stable across restarts: the folder is derived from the tmux name.
     *
     * Nothing prunes these keys. An earlier version did, from inside each
     * caller's per-server loop, so collapsing a folder on one server deleted
     * every other server's collapse state — and any server that was merely
     * offline lost its state to a toggle anywhere else. The set only grows when
     * the user deliberately collapses something, so a stale key costs a few
     * bytes, while deleting the wrong one reads as "it randomly forgets".
     */
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
        /**
         * Force the flat view without filtering here — for a caller that has
         * ALREADY filtered [entries] itself. The drawer does: it filters on
         * server name, mode and tmux name as well, and having this model filter
         * a second time on a narrower field set meant a query for a server name
         * passed the first filter, matched nothing here, and rendered an empty
         * panel with no "no matches" message.
         */
        flat: Boolean = false,
    ): List<Row> {
        val q = query.trim().lowercase()
        val matching = if (q.isEmpty()) entries else entries.filter { it.searchText.contains(q) }
        if (matching.isEmpty()) return emptyList()

        val ordered = matching.sortedWith(compareBy({ it.folderKey }, { it.alias.lowercase() }))
        if (q.isNotEmpty() || flat) return ordered.map { Row.Item(it, inGroup = false) }

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
        // Groups first, then the loose ones under their own header.
        //
        // Singletons used to sit inline in one alphabetical sequence, which
        // read better but broke the sticky header: a pinned "backendV2" stayed
        // on screen above an unrelated single "iam-notbroke" row, saying the
        // row belonged to a folder it does not. A sticky header that lies is
        // worse than a lost sort order, and every section here now has a
        // header of its own so whatever is pinned is always true.
        val singles = rest.filter { (totalByGroup[groupKey(it.serverId, it.folderKey)] ?: 0) < MIN_GROUP_SIZE }
        val grouped = rest - singles.toSet()
        val seen = mutableSetOf<String>()
        for (entry in grouped) {
            val key = groupKey(entry.serverId, entry.folderKey)
            if (!seen.add(key)) continue
            val total = totalByGroup[key] ?: 0
            val members = grouped.filter { groupKey(it.serverId, it.folderKey) == key }
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
        if (singles.isNotEmpty()) {
            // The header earns its row exactly when something above could
            // otherwise claim these rows. `rows` only ever holds a header
            // followed by its items, so "not empty" IS that question — asking
            // instead whether any FOLDER header exists missed the attention
            // section: on a server whose folders all hold one session, with one
            // of them waiting for approval, the loose ones rendered inside
            // "Needs attention" with nothing between, since the section simply
            // never ended. Empty means all-loose-and-nothing-waiting, which is
            // the case this guard was added for.
            if (rows.isNotEmpty()) rows += Row.OtherHeader(singles.size)
            singles.forEach { rows += Row.Item(it, inGroup = false) }
        }
        return rows
    }

}
