package com.clauderemote.model

/**
 * Remote directory browsing model — pure Kotlin, no SSH and no Compose, so the
 * command construction, the parsing and the ranking are unit-testable without a
 * server.
 *
 * The folder picker used to run one `ls` of a single directory per click, each
 * through `SshSessionHelper.withSession`. That helper only reuses a POOLED
 * transport, and the Connect screen runs before any session for that server
 * exists — so every navigation step, including every `..`, paid a full SSH
 * handshake. Over the Cloudflare tunnel on Starlink that is seconds per click,
 * and nothing was cached, so walking back up refetched a listing we just saw.
 *
 * Instead we take ONE round trip that returns the whole subtree with the two
 * facts the ranking needs (mtime, and whether the directory is a project), then
 * navigate it in memory. Deeper levels are fetched lazily with the same command
 * rooted lower down and merged into the cached tree.
 */

/** Kind of a scanned directory, used for both ranking and the picker's sections. */
enum class RemoteDirKind { RECENT, PROJECT, FOLDER }

data class RemoteDirEntry(
    /** Full path in DISPLAY form — i.e. still `~`-prefixed when the scan root was. */
    val path: String,
    /** Last path segment. */
    val name: String,
    /** Directory holds a `.git` or `.claude` — i.e. somewhere you'd launch Claude. */
    val isProject: Boolean,
    /** Unix mtime in whole seconds; 0 when the server's `find` gave us nothing. */
    val mtimeSeconds: Long,
    /** True for dot-directories, which the picker hides unless asked. */
    val isHidden: Boolean = name.startsWith("."),
)

/**
 * An immutable snapshot of a scanned subtree, indexed by parent so the picker
 * can list any directory it has already seen without another round trip.
 *
 * [listedParents] is deliberately separate from the key set of [childrenByParent]:
 * a directory we scanned and found EMPTY must render as "no subfolders" rather
 * than as "not loaded yet", and those two states are indistinguishable from an
 * absent map entry alone.
 */
class RemoteDirTree(
    val root: String,
    private val childrenByParent: Map<String, List<RemoteDirEntry>>,
    private val listedParents: Set<String>,
) {
    companion object {
        fun empty(root: String = "~") = RemoteDirTree(root, emptyMap(), emptySet())
    }

    val isEmpty: Boolean get() = childrenByParent.isEmpty()

    /** Children of [path], already sorted (projects first, then by recency). */
    fun children(path: String): List<RemoteDirEntry> =
        childrenByParent[RemotePath.normalize(path)].orEmpty()

    /** True when [path] was actually scanned — an empty directory still counts. */
    fun hasListing(path: String): Boolean = RemotePath.normalize(path) in listedParents

    /** Every entry in the tree, for whole-tree fuzzy completion. */
    fun allEntries(): List<RemoteDirEntry> = childrenByParent.values.flatten()

    /** True when a directory with exactly this path was seen by a scan. */
    fun contains(path: String): Boolean {
        val norm = RemotePath.normalize(path)
        if (norm in listedParents) return true
        val parent = RemotePath.parent(norm) ?: return false
        return childrenByParent[parent].orEmpty().any { it.path == norm }
    }

    /**
     * Overlay [other] on top of this tree — used when a lazy deepen scan brings
     * back a subtree. The newer listing wins for any parent both cover, since it
     * is the more recent read of that directory.
     */
    fun merge(other: RemoteDirTree): RemoteDirTree = RemoteDirTree(
        root = root,
        childrenByParent = childrenByParent + other.childrenByParent,
        listedParents = listedParents + other.listedParents,
    )
}

/** One clickable breadcrumb segment: the label to draw and the path to jump to. */
data class PathCrumb(val label: String, val path: String)

/**
 * Remote path arithmetic. Deliberately string-based and platform-free: these
 * are POSIX paths on the far side of an SSH connection, never local files, so
 * `okio`/`java.io.File` semantics (Windows separators, local existence) would
 * be wrong here.
 */
object RemotePath {
    /** Collapse `//`, drop a trailing slash, and treat blank as `~`. */
    fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return "~"
        val collapsed = buildString {
            var lastWasSlash = false
            for (ch in trimmed) {
                if (ch == '/' && lastWasSlash) continue
                append(ch)
                lastWasSlash = ch == '/'
            }
        }
        val stripped = collapsed.trimEnd('/')
        return stripped.ifBlank { "/" }
    }

    /** True for the two paths that have no parent to walk up to. */
    fun isRoot(path: String): Boolean = normalize(path).let { it == "~" || it == "/" }

    /** Parent directory, or null at `~` / `/`. */
    fun parent(path: String): String? {
        val norm = normalize(path)
        if (isRoot(norm)) return null
        val cut = norm.lastIndexOf('/')
        // "~/foo" → "~", "/foo" → "/", "foo" (relative, no slash) → no parent.
        return when {
            cut < 0 -> null
            cut == 0 -> "/"
            else -> norm.substring(0, cut)
        }
    }

    /** Append a single segment. */
    fun join(parent: String, name: String): String {
        val base = normalize(parent)
        return if (base == "/") "/$name" else "$base/$name"
    }

    /** Last segment, falling back to the whole path for `~` and `/`. */
    fun name(path: String): String {
        val norm = normalize(path)
        val cut = norm.lastIndexOf('/')
        return if (cut < 0 || cut == norm.length - 1) norm else norm.substring(cut + 1)
    }

    /**
     * Breadcrumb trail from the root down to [path], each crumb carrying the
     * path to jump straight to it — the old picker could only walk up one level
     * per click, and each of those was a round trip.
     */
    fun crumbs(path: String): List<PathCrumb> {
        val norm = normalize(path)
        if (norm == "~" || norm == "/") return listOf(PathCrumb(norm, norm))
        val rootLabel = if (norm.startsWith("~")) "~" else "/"
        val rest = norm.removePrefix("~").removePrefix("/").split('/').filter { it.isNotBlank() }
        val out = mutableListOf(PathCrumb(rootLabel, rootLabel))
        var acc = rootLabel
        for (segment in rest) {
            acc = join(acc, segment)
            out += PathCrumb(segment, acc)
        }
        return out
    }

    /**
     * Render [path] for a POSIX shell. `~` must stay UNQUOTED to expand, so a
     * home-relative path becomes `"$HOME"` plus a single-quoted remainder rather
     * than one quoted blob — quoting the tilde would look up a literal `~`
     * directory and silently return nothing.
     *
     * Everything else is single-quoted with `'` escaped as `'\''`, which makes
     * the result exactly one shell word: `~/x'; rm -rf ~; echo '` comes out as
     * `"$HOME"'/x'\''; rm -rf ~; echo '\'''`, and `$(…)`, backticks, `;`, `|`,
     * newlines and backslashes are all literal. The `"$HOME"` prefix is a fixed
     * literal we emit, and a value substituted into double quotes is not
     * re-tokenized, so a hostile `$HOME` on the server cannot break out either.
     *
     * ONE DELIBERATE LIMITATION: only a LEADING `~` or `~/` is special-cased.
     * `~someuser/x` is single-quoted and therefore looks up a literal
     * `~someuser` directory, which yields an empty listing rather than that
     * user's home. Do NOT "fix" that by emitting the leading segment unquoted —
     * the segment is user input, so `~; rm -rf /` would then be injected
     * verbatim. If it ever needs supporting, validate the user part against
     * `[A-Za-z0-9_-]+` FIRST and only then leave it unquoted.
     */
    fun toShellArg(path: String): String {
        val norm = normalize(path)
        return when {
            norm == "~" -> "\"$HOME_VAR\""
            norm.startsWith("~/") -> "\"$HOME_VAR\"" + singleQuote(norm.substring(1))
            else -> singleQuote(norm)
        }
    }

    private const val HOME_VAR = "\$HOME"

    private fun singleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/**
 * Builds and parses the one-shot directory scan.
 *
 * The command emits three marker-delimited sections in a single exec so the
 * whole browse costs one round trip: the expanded root (so absolute paths from
 * `find` can be mapped back to the `~`-form the UI shows), the directory list
 * with mtimes, and the set of directories holding a project marker.
 */
object RemoteDirScan {
    const val ROOT_MARKER = "<<<CR-ROOT>>>"
    const val DIRS_MARKER = "<<<CR-DIRS>>>"
    const val PROJ_MARKER = "<<<CR-PROJ>>>"

    /** Default subtree depth: enough for `~/work/client/repo` from one scan. */
    const val DEFAULT_DEPTH = 3

    /** Cap the payload — a pathological tree must not stall the picker. */
    const val MAX_DIRS = 3000
    const val MAX_PROJECTS = 500

    /**
     * Client-side ceiling on the scan's output. [MAX_DIRS] is enforced by `head`
     * INSIDE the remote command, so it binds only a cooperative server; this is
     * what binds an uncooperative one. Roughly 1M chars covers the 3000 paths
     * the command can legitimately emit many times over.
     */
    const val MAX_OUTPUT_CHARS = 1_000_000

    /**
     * Directories never worth descending into for a "where do I launch Claude"
     * picker, and expensive to walk. Pruning them is what keeps a depth-3 scan
     * of a home directory cheap.
     */
    private val PRUNED = listOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "Pods",
        "__pycache__", "venv", ".venv", ".gradle", ".cache", ".npm",
        ".m2", ".cargo", ".next", ".idea", ".terraform",
    )

    /** Markers that make a directory a "project" — where a Claude session belongs. */
    private val PROJECT_MARKERS = listOf(".git", ".claude")

    /**
     * One exec covering the whole browse.
     *
     * Dot-directories are listed (so `~/.claude` is reachable — the old picker
     * filtered them out entirely and could never navigate there) but the heavy
     * ones above are pruned, which is where the cost actually was.
     */
    fun command(root: String, depth: Int = DEFAULT_DEPTH): String {
        val r = RemotePath.toShellArg(root)
        // The dirs pass also prunes `.git`; the projects pass must NOT, since a
        // `.git` directory is exactly what it is looking for.
        val prune = (PRUNED + ".git").joinToString(" -o ") { "-name ${shellName(it)}" }
        val prunePlain = PRUNED.joinToString(" -o ") { "-name ${shellName(it)}" }
        val markers = PROJECT_MARKERS.joinToString(" -o ") { "-name ${shellName(it)}" }
        val printDir = "-printf '%T@ %p\\n'"
        return buildString {
            // The markers MUST be quoted. Bare `<<<` is a here-string operator
            // and `>>>` a redirect, so an unquoted `echo <<<CR-ROOT>>>` never
            // prints the marker at all and the whole scan parses as empty.
            append("echo '$ROOT_MARKER'; ")
            // `cd` + `pwd` expands `~` and resolves symlinks the same way the
            // launched session's `cd` will, so the picker cannot hand back a
            // path that then fails at launch.
            //
            // `--` matters: quoting does not stop `cd` parsing a leading dash as
            // an OPTION, so a path typed as `-P` or `--` used to make `cd`
            // succeed into $HOME and `find` fall back to `.`, quietly listing
            // the home directory under bogus `./…` paths. Failing cleanly (which
            // `|| exit 0` turns into an empty listing) beats looking like it
            // worked.
            append("cd -- $r 2>/dev/null && pwd || exit 0; ")
            append("echo '$DIRS_MARKER'; ")
            // Dot-directories are PRINTED but not DESCENDED into, so `~/.claude`
            // stays reachable (the old picker filtered dot entries out entirely
            // and could never navigate there) while their contents stay out of
            // the walk. That is what makes this cheap: measured against a real
            // home directory, descending into them took 2.56s AND exhausted the
            // MAX_DIRS cap inside `.config` subtrees, truncating away the very
            // projects the user wanted. Pruning them: 1242 entries in 0.03s.
            append(
                "find $r -mindepth 1 -maxdepth $depth \\( $prune \\) -prune " +
                    "-o \\( -type d -name '.*' $printDir -prune \\) " +
                    "-o \\( -type d $printDir \\) 2>/dev/null | head -$MAX_DIRS; "
            )
            append("echo '$PROJ_MARKER'; ")
            append(
                "find $r -mindepth 1 -maxdepth ${depth + 1} \\( $prunePlain \\) -prune " +
                    "-o \\( $markers \\) -prune -printf '%h\\n' 2>/dev/null | head -$MAX_PROJECTS"
            )
        }
    }

    /**
     * `find -printf` is GNU-only. When the server ships BSD find (macOS) the
     * dirs section comes back empty, and the caller retries with this — one
     * level, no metadata, but the picker still works.
     */
    fun fallbackCommand(root: String): String {
        val r = RemotePath.toShellArg(root)
        // It echoes the expanded root for the SAME reason the main scan does:
        // `ls -1d` prints ABSOLUTE paths, and without the root to strip, every
        // entry lands under `/home/<user>` while the UI asks for `~` — the
        // picker then shows "No subfolders" forever on any BSD/macOS server.
        return "echo '$ROOT_MARKER'; cd $r 2>/dev/null && pwd || exit 0; " +
            "echo '$DIRS_MARKER'; ls -1d $r/*/ 2>/dev/null | head -$MAX_DIRS"
    }

    private fun shellName(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Parse the three-section output into a tree.
     *
     * [displayRoot] is what the UI calls the scan root (usually `~/…`); the
     * absolute prefix echoed by the command is rewritten back to it so the
     * field, the breadcrumb and `server.recentFolders` all speak one form.
     */
    fun parse(displayRoot: String, output: String, depth: Int = DEFAULT_DEPTH): RemoteDirTree {
        val root = RemotePath.normalize(displayRoot)
        val lines = output.lines()

        val rootIdx = lines.indexOfFirst { it.trim() == ROOT_MARKER }
        val dirsIdx = lines.indexOfFirst { it.trim() == DIRS_MARKER }
        val projIdx = lines.indexOfFirst { it.trim() == PROJ_MARKER }
        if (dirsIdx < 0) return RemoteDirTree.empty(root)

        val absoluteRoot = if (rootIdx >= 0 && rootIdx + 1 < dirsIdx) {
            lines.subList(rootIdx + 1, dirsIdx).firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('/')
        } else null

        val dirLines = lines.subList(
            (dirsIdx + 1).coerceAtMost(lines.size),
            (if (projIdx >= 0) projIdx else lines.size).coerceAtLeast(dirsIdx + 1),
        )
        val projLines = if (projIdx >= 0) lines.subList((projIdx + 1).coerceAtMost(lines.size), lines.size)
        else emptyList()

        val projects = projLines.mapNotNull { toDisplayPath(root, absoluteRoot, it) }.toSet()

        // A directory is a project either because it holds a marker, or because
        // the marker directory itself was listed under it — `find` prunes at the
        // marker, so `~/repo/.git` never appears in the dirs section, but a
        // parent listed in the projects section still identifies `~/repo`.
        val parsed = mutableListOf<RemoteDirEntry>()
        for (line in dirLines) {
            if (line.isBlank()) continue
            val sep = line.indexOf(' ')
            if (sep <= 0) continue
            val mtime = line.substring(0, sep).substringBefore('.').toLongOrNull() ?: 0L
            val path = toDisplayPath(root, absoluteRoot, line.substring(sep + 1)) ?: continue
            if (path == root) continue
            parsed += RemoteDirEntry(
                path = path,
                name = RemotePath.name(path),
                isProject = path in projects,
                mtimeSeconds = mtime,
            )
        }
        return buildTree(root, parsed, depth)
    }

    /**
     * Rewrite an absolute path from `find`/`ls` into the display form the UI
     * speaks, so the field, the breadcrumb and `recentFolders` all agree. The
     * trailing slash in the prefix test matters: without it an absolute root of
     * `/home/luc` would also swallow `/home/lucas/x`.
     */
    private fun toDisplayPath(root: String, absoluteRoot: String?, raw: String): String? {
        val p = raw.trim().trimEnd('/')
        if (p.isBlank()) return null
        val base = absoluteRoot?.takeIf { it.isNotBlank() } ?: return RemotePath.normalize(p)
        return when {
            p == base -> root
            p.startsWith("$base/") -> RemotePath.normalize(root + p.removePrefix(base))
            // A `find` rooted at `base` cannot legitimately print a path outside
            // it, so anything else is a forgery and is dropped rather than
            // passed through. Directory names may contain newlines, and `find`
            // prints them raw, so a name like "x\n1700000000 /etc/foo\ny" arrives
            // as an extra LINE that parses as a perfectly well-formed entry. It
            // used to be kept verbatim, filed under parent "/", which then
            // suppressed the "no such folder" hint and got offered by
            // completion — i.e. it could nudge the user into launching a session
            // somewhere an attacker chose. Planting such a name needs only a
            // hostile `git clone` or an unpacked archive, not a compromised
            // server. (The residue is that a forged line can still impersonate a
            // SECTION MARKER and truncate the listing; that costs the user
            // folders they cannot see, never a path they can act on.)
            else -> null
        }
    }

    /** Parse the BSD fallback: one level, no mtimes, no project markers. */
    fun parseFallback(displayRoot: String, output: String): RemoteDirTree {
        val root = RemotePath.normalize(displayRoot)
        val lines = output.lines()
        val rootIdx = lines.indexOfFirst { it.trim() == ROOT_MARKER }
        val dirsIdx = lines.indexOfFirst { it.trim() == DIRS_MARKER }
        val absoluteRoot = if (rootIdx >= 0 && rootIdx + 1 < dirsIdx) {
            lines.subList(rootIdx + 1, dirsIdx).firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('/')
        } else null
        // Marker-less output (a bare `ls` from somewhere else) still parses,
        // just without the rewrite.
        val body = if (dirsIdx >= 0) lines.subList(dirsIdx + 1, lines.size) else lines
        val entries = body.mapNotNull { line ->
            val path = toDisplayPath(root, absoluteRoot, line) ?: return@mapNotNull null
            if (path == root) return@mapNotNull null
            RemoteDirEntry(path, RemotePath.name(path), isProject = false, mtimeSeconds = 0L)
        }
        return buildTree(root, entries, depth = 1)
    }

    /**
     * Group entries under their parent and sort each listing.
     *
     * The scan root is always marked as listed even when it has no children, so
     * an empty directory shows "no subfolders" instead of a stuck spinner.
     */
    private fun buildTree(
        root: String,
        entries: List<RemoteDirEntry>,
        depth: Int,
    ): RemoteDirTree {
        val byParent = mutableMapOf<String, MutableList<RemoteDirEntry>>()
        for (entry in entries) {
            val parent = RemotePath.parent(entry.path) ?: continue
            byParent.getOrPut(parent) { mutableListOf() }.add(entry)
        }

        // A directory's listing is known only if the walk actually DESCENDED
        // into it — not merely if we saw its name.
        //
        // Marking every seen directory as listed is a trap: the picker asks for
        // a listing only when it has none, so a directory wrongly marked listed
        // shows "No subfolders" forever and never asks. That silently broke the
        // three cases at the edge of every scan — everything below `maxdepth`,
        // every dot-directory (printed but pruned, so `~/.claude` was reachable
        // to select but not to browse), and, worst, EVERY folder on a BSD/macOS
        // server, since the `ls` fallback only ever sees one level. It also made
        // ConnectScreen's "no such folder" hint fire on paths that merely lay
        // deeper than the scan reached.
        val listed = mutableSetOf(root)
        // Any parent whose children we saw was necessarily descended into.
        listed += byParent.keys
        for (entry in entries) {
            // Dot-directories are printed but pruned, so their listing is never
            // known from this scan even though they sit above the depth limit.
            if (entry.isHidden) continue
            // Below the limit and not pruned: the walk went in, so an absence
            // from byParent genuinely means "empty", not "not fetched".
            if (relativeDepth(root, entry.path) < depth) listed += entry.path
        }

        val sorted = byParent.mapValues { (_, kids) -> kids.sortedWith(ENTRY_ORDER) }
        return RemoteDirTree(root, sorted, listed)
    }

    /** Path segments of [path] beneath [root]; 0 when they are the same directory. */
    private fun relativeDepth(root: String, path: String): Int {
        if (path == root) return 0
        val rest = path.removePrefix(root).trim('/')
        if (rest.isBlank()) return 0
        return rest.count { it == '/' } + 1
    }

    /**
     * Projects first, then most-recently-touched, then alphabetical. This is the
     * ranking that stops `android-sdk` and `Downloads` outranking the repo you
     * actually want; a purely alphabetical listing buried it.
     */
    private val ENTRY_ORDER: Comparator<RemoteDirEntry> =
        compareByDescending<RemoteDirEntry> { it.isProject }
            .thenBy { it.isHidden }
            .thenByDescending { it.mtimeSeconds }
            .thenBy { it.name.lowercase() }
}

/** One typeahead result: the path, why it ranked, and where the query matched. */
data class PathSuggestion(
    val path: String,
    val kind: RemoteDirKind,
    /** Indices in [path] that matched the query, for highlighting. */
    val matchedIndices: List<Int> = emptyList(),
)

/**
 * Shell-like completion for the path field.
 *
 * The field — not the picker — is the fast path on a desktop with a keyboard:
 * typing `~/cl` should offer `~/claude-remote` and Tab should complete it, the
 * way the terminal these users live in already behaves. It also gives the field
 * the validation it never had; a typo used to surface only as a failed launch.
 */
object PathCompletion {
    const val DEFAULT_LIMIT = 8

    /**
     * Rank candidates for [input].
     *
     * When the input names a directory and ends in `/`, this lists that
     * directory's children — the same "descend" affordance a shell gives you —
     * otherwise it matches the last segment against siblings, then falls back to
     * a whole-tree fuzzy match so `bookse` finds `~/BookSeeker` from anywhere.
     */
    fun suggest(
        input: String,
        tree: RemoteDirTree,
        recents: List<String> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
    ): List<PathSuggestion> {
        val raw = input.trim()
        val recentSet = recents.map { RemotePath.normalize(it) }.toSet()

        fun kindOf(entry: RemoteDirEntry) = when {
            entry.path in recentSet -> RemoteDirKind.RECENT
            entry.isProject -> RemoteDirKind.PROJECT
            else -> RemoteDirKind.FOLDER
        }

        // Empty field: offer recents, then the best-ranked top-level entries.
        if (raw.isBlank()) {
            val fromRecents = recents.map { PathSuggestion(RemotePath.normalize(it), RemoteDirKind.RECENT) }
            val fromTree = tree.children(tree.root).map { PathSuggestion(it.path, kindOf(it)) }
            return (fromRecents + fromTree).distinctBy { it.path }.take(limit)
        }

        // Trailing slash means "inside this directory", not "matching this name".
        if (raw.endsWith("/")) {
            val dir = RemotePath.normalize(raw)
            return tree.children(dir).map { PathSuggestion(it.path, kindOf(it)) }.take(limit)
        }

        val parent = RemotePath.parent(raw)
        val fragment = RemotePath.name(raw)
        val siblings = if (parent != null) tree.children(parent) else emptyList()

        val scored = mutableListOf<Pair<Int, PathSuggestion>>()
        val seen = mutableSetOf<String>()

        // Matching happens on the NAME, but the suggestion shows the whole path,
        // so highlight indices shift by the length of the parent prefix.
        fun consider(entry: RemoteDirEntry, bonus: Int) {
            if (!seen.add(entry.path)) return
            val match = fuzzyMatch(entry.name, fragment) ?: return
            val kind = kindOf(entry)
            val kindBonus = when (kind) {
                RemoteDirKind.RECENT -> 400
                RemoteDirKind.PROJECT -> 200
                RemoteDirKind.FOLDER -> 0
            }
            val offset = entry.path.length - entry.name.length
            scored += (match.score + bonus + kindBonus) to PathSuggestion(
                path = entry.path,
                kind = kind,
                matchedIndices = match.indices.map { it + offset },
            )
        }

        // Siblings of what's typed rank above anything found elsewhere: the user
        // has already committed to a directory by typing its prefix.
        siblings.forEach { consider(it, bonus = 1000) }
        tree.allEntries().forEach { consider(it, bonus = 0) }

        return scored.sortedWith(
            compareByDescending<Pair<Int, PathSuggestion>> { it.first }.thenBy { it.second.path.length }
        ).map { it.second }.take(limit)
    }

    /**
     * What Tab should insert: the longest path prefix shared by every candidate.
     * With a single candidate this completes it outright; with several it
     * advances to the point they diverge, exactly like shell completion.
     */
    fun commonPrefix(candidates: List<String>): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates.first()
        val first = candidates.first()
        var len = first.length
        for (other in candidates.drop(1)) {
            len = minOf(len, other.length)
            var i = 0
            while (i < len && first[i] == other[i]) i++
            len = i
            if (len == 0) return ""
        }
        return first.substring(0, len)
    }

    /** A fuzzy hit: higher [score] is better, [indices] are positions in the target. */
    data class Match(val score: Int, val indices: List<Int>)

    /**
     * Case-insensitive scoring, strongest signal first: an exact name, then a
     * prefix, then a substring, then a subsequence (`bsk` → `BookSeeker`).
     * Shorter targets win ties so `~/ai` beats `~/ai-experiments` for "ai".
     */
    fun fuzzyMatch(target: String, query: String): Match? {
        if (query.isEmpty()) return Match(1, emptyList())
        val t = target.lowercase()
        val q = query.lowercase()

        if (t == q) return Match(1000 - target.length, target.indices.toList())
        if (t.startsWith(q)) return Match(800 - target.length, (0 until q.length).toList())
        val sub = t.indexOf(q)
        if (sub >= 0) return Match(600 - target.length - sub, (sub until sub + q.length).toList())

        // Subsequence: every query char in order, rewarding tight runs.
        val indices = mutableListOf<Int>()
        var ti = 0
        var gaps = 0
        for (qc in q) {
            var found = -1
            while (ti < t.length) {
                if (t[ti] == qc) { found = ti; ti++; break }
                ti++
                gaps++
            }
            if (found < 0) return null
            indices += found
        }
        return Match(300 - target.length - gaps, indices)
    }
}
