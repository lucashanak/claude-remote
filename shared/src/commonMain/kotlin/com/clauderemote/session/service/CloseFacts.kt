package com.clauderemote.session.service

/**
 * Should closing a session wait (Jarvis F0, §15.6, R6 = 6d)? The app closes at once and runs [CloseFactsCommands]
 * alongside: a [CloseLevel.BLOCK] brings the tab back ("Neuzavřeno: <důvod> · Zavřít i tak"), a [CloseLevel.WARN] is
 * only added to the snackbar, and facts that do not arrive in time ([CloseLevel.UNKNOWN]) let the close go ahead.
 * `cr-session facts` prints the same lines; `cr-session close --token` refuses unless the facts still hash the same.
 */
internal enum class CloseLevel { OK, WARN, BLOCK, UNKNOWN }

/** One reason, with the short Czech text the snackbar shows. */
internal data class CloseReason(val code: Code, val level: CloseLevel, val text: String) {
    enum class Code { BUSY, WAITING, BACKGROUND, DRAFT, VIEWER, VIEWER_MAYBE, PROCESSES, UNCOMMITTED, UNPUSHED, STASH, RECENT }
}

internal data class CloseFacts(
    /** The worst level of [reasons]; [CloseLevel.UNKNOWN] when the output was cut off or unreadable. */
    val level: CloseLevel,
    val reasons: List<CloseReason>,
    /** `<epoch>.<sha256>` of the facts that matter (not their timestamps); what `cr-session close --token` checks. */
    val token: String?,
    /** The tmux session exists; null = unknown. */
    val alive: Boolean?,
    /** A transcript of the session's conversation exists, so it can be restored; null = unknown. */
    val transcript: Boolean?,
) {
    /** The BLOCK reasons, comma-joined ("běží tah, rozepsaný vstup"), or null. */
    val blockText: String? get() = reasons.filter { it.level == CloseLevel.BLOCK }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.text }
    /** The WARN reasons, comma-joined, or null. */
    val warnText: String? get() = reasons.filter { it.level == CloseLevel.WARN }.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.text }

    companion object {
        val UNKNOWN = CloseFacts(CloseLevel.UNKNOWN, emptyList(), null, null, null)
    }
}

internal object CloseFactsCommands {

    /** Last line of the facts output; without it the output was cut off and the facts are [CloseLevel.UNKNOWN]. */
    const val END = "__CR_FACTS_END__"

    /** A client counts as watching when its last input is younger than this. */
    const val VIEWER_ACTIVE_S = 300

    /** A turn the events file says is running counts only this long without a registry to confirm it (a lost `stop`). */
    const val EVENT_BUSY_S = 1800

    /** Activity younger than this is worth a warning. */
    const val RECENT_S = 600

    /** A foreign process of the pane younger than this is not counted (a statusline or hook run, gone in a moment). */
    const val PROC_MIN_AGE_S = 3

    /**
     * Read-only facts of the session `$N`. `$SELF` (may be empty) names this device's client marker
     * (`~/.claude-remote/clients/<SELF>`, written by `TmuxProbes.singleClientPreamble` on every attach): the client
     * whose tty it holds is the closing device's own and is not "someone else watching". Every external call has
     * `timeout 3`; git runs with `GIT_OPTIONAL_LOCKS=0` so it never takes `index.lock` from a session at work.
     *
     * Prints `FACT <name> <values…>` lines (see [parse]), then `TOKEN <epoch>.<sha256>`, then [END].
     */
    fun closeFactsBody(): String = FACTS.replace("@@JSON@@", ManifestCommands.JSON_SH)

    /** [closeFactsBody] for [tmuxName]; [selfMarker] = [selfMarkerFor] of this device, or null (no own client). */
    fun closeFactsCommand(tmuxName: String, selfMarker: String? = null): String =
        "N=${ManifestCommands.sq(tmuxName)}; SELF=${ManifestCommands.sq(selfMarker.orEmpty())}\n" + closeFactsBody()

    /** The marker file name `TmuxProbes.singleClientPreamble` writes for ([deviceKey], [tmuxName]). */
    fun selfMarkerFor(tmuxName: String, deviceKey: String): String = TmuxProbes.clientMarkerKey(tmuxName, deviceKey)

    private fun sh(s: String) = s.replace('§', '$')

    private val FACTS = sh(
        """
        ( set +eu
        @@JSON@@
        NOW=§(date +%s)
        if timeout 3 tmux has-session -t "=§N" 2>/dev/null; then ALIVE=1; else ALIVE=0; fi
        echo "FACT alive §ALIVE"
        PP=""; CP=""; CFG=""; RJ=""; TREE=""
        if [ "§ALIVE" = 1 ]; then
          PP=§(timeout 3 tmux -u list-panes -t "=§N:" -F '#{pane_pid}' 2>/dev/null | head -n 1)
          PC=§(timeout 3 tmux -u display-message -p -t "=§N:" '#{pane_current_path}' 2>/dev/null)
        fi
        cfg_of() { tr '\0' '\n' < "/proc/§1/environ" 2>/dev/null | sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -n 1; }
        if [ -n "§PP" ]; then
          PS=§(ps -eo pid=,ppid=,etimes=,comm= 2>/dev/null)
          TREE=§(printf '%s\n' "§PS" | awk -v root="§PP" '{ k[§2]=k[§2]" "§1; c[§1]=§4 } END { h=0;t=0;q[t++]=root; while(h<t){p=q[h++];print p, c[p];m=split(k[p],a," ");for(i=1;i<=m;i++)if(a[i]!="")q[t++]=a[i]} }')
          for p in §(printf '%s\n' "§TREE" | cut -d' ' -f1); do
            c=§(cfg_of "§p")
            if [ -f "§{c:-§HOME/.claude}/sessions/§p.json" ]; then CP=§p; CFG=§c; break; fi
          done
          if [ -z "§CP" ]; then
            CP=§(printf '%s\n' "§TREE" | awk '§2=="claude" {print §1; exit}')
            [ -n "§CP" ] && CFG=§(cfg_of "§CP")
          fi
        fi
        C="§{CFG:-§HOME/.claude}"
        [ -n "§CP" ] && [ -f "§C/sessions/§CP.json" ] && RJ=§(head -c 65536 "§C/sessions/§CP.json" 2>/dev/null | tr '\n' ' ')
        ST=§(jget status "§RJ"); WF=§(jget waitingFor "§RJ"); CWD=§(jget cwd "§RJ"); RSID=§(jget sessionId "§RJ")
        echo "FACT pid §{CP:--}"
        echo "FACT status §{ST:--} §(if [ -n "§WF" ]; then printf '%s' "§WF" | base64 -w0; else echo -; fi)"
        # the last hook event of this session (hook-ev.sh: claude-remote-ev <EV> <name_b64> <ms> <pid> <extra_b64>)
        NB=§(printf '%s' "§N" | base64 -w0)
        EVL=§(tail -n 4000 "§HOME/.cache/claude-remote/events" 2>/dev/null | awk -v nb="§NB" '§1=="claude-remote-ev" && §3==nb && §4 ~ /^[0-9]+§/ { e=§2; m=§4; x=§6 } END { if (e!="") print e, m, x }')
        if [ -n "§EVL" ]; then
          set -- §EVL
          AGE=§(( NOW - §2 / 1000 )); BG=0; [ "§1" = stop ] && [ "§(printf '%s' "§{3:-}" | base64 -d 2>/dev/null)" = bg ] && BG=1
          echo "FACT event §1 §AGE §BG"
        else
          echo "FACT event - - 0"
        fi
        # the input box: the row right below a rule that starts with ❯ + NBSP. Placeholder suggestions ("Try …") and
        # the cursor are drawn dim / reversed, typed text is not, so what is left once those are cut is the draft.
        DR=-
        if [ "§ALIVE" = 1 ]; then
          DR=§(timeout 3 tmux capture-pane -p -e -t "=§N:" 2>/dev/null | LC_ALL=C awk -v esc="§(printf '\033')" '
            function plain(s) { gsub(esc "\\[[0-9;]*m", "", s); return s }
            { p = plain(§0); if (rule && index(p, "\342\235\257\302\240") == 1) cand = §0; rule = (index(p, "\342\224\200") == 1) }
            END {
              if (cand == "") { print "-"; exit }
              s = cand; sub(/^[^\342]*\342\235\257\302\240/, "", s)
              gsub(esc "\\[(0;)?2m[^" esc "]*", "", s); gsub(esc "\\[7m[^" esc "]*", "", s)
              s = plain(s); gsub(/\302\240/, "", s); gsub(/[ \t]/, "", s)
              print (s == "" ? 0 : 1)
            }')
          [ -n "§DR" ] || DR=-
        fi
        echo "FACT draft §DR"
        # clients: someone watching = a client active in the last @@VIEW@@ s that is not this device's own
        SELFT=""; [ -n "§{SELF:-}" ] && SELFT=§(cat "§HOME/.claude-remote/clients/§SELF" 2>/dev/null)
        OTHERS=0; SELFON=-; OTT=""
        [ -n "§{SELF:-}" ] && SELFON=0
        if [ "§ALIVE" = 1 ]; then
          CL=§(timeout 3 tmux list-clients -t "=§N" -F '#{client_tty} #{client_activity}' 2>/dev/null)
          while read -r tty act; do
            [ -n "§tty" ] || continue
            if [ -n "§SELFT" ] && [ "§tty" = "§SELFT" ]; then SELFON=1; continue; fi
            case "§act" in ''|*[!0-9]*) continue ;; esac
            if [ §(( NOW - act )) -lt @@VIEW@@ ]; then OTHERS=§((OTHERS+1)); OTT="§OTT §tty"; fi
          done <<CR_CL
        §CL
        CR_CL
        fi
        echo "FACT clients §OTHERS §SELFON"
        # processes in the pane that are not claude, not its MCP servers and not a plain shell: what a shell (the
        # pane's own, or one claude spawned for a tool) runs — a dev server, a build, a background task
        # (a process younger than @@YOUNG@@ s is not counted: claude's statusline, `sh -c node …`, and hooks come and go)
        PR=""
        if [ -n "§PP" ]; then
          PR=§(printf '%s\n' "§PS" | awk -v root="§PP" -v cl="§{CP:-0}" -v young=@@YOUNG@@ '
            { k[§2]=k[§2]" "§1; e[§1]=§3; c[§1]=§4 }
            function shell(n) { return n ~ /^-?(ba|da|z|k|fi|tc|c)?sh§/ || n == "login" }
            END {
              h=0; t=0; q[t]=root; z[t]=0; t++
              while (h<t) { p=q[h]; zz=z[h]; h++
                if (p == cl) zc = 1; else if (zz == 1) zc = (shell(c[p]) ? 3 : 2); else zc = zz
                if (((zc == 0 && !shell(c[p]) && p != cl) || (zz == 3)) && e[p] + 0 >= young) { n++; if (!(c[p] in seen)) { seen[c[p]]=1; names = names (names==""?"":",") c[p] } }
                m=split(k[p],a," "); for(i=1;i<=m;i++) if (a[i]!="") { q[t]=a[i]; z[t]=zc; t++ } }
              print (n+0), (names==""?"-":names) }')
        fi
        echo "FACT procs §{PR:-0 -}"
        # git (WARN only): uncommitted files, commits ahead of upstream, stashes
        W="§{CWD:-§{PC:-}}"; GD=-; GA=-; GS=-
        if [ -n "§W" ] && GIT_OPTIONAL_LOCKS=0 timeout 3 git -C "§W" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
          GD=§(GIT_OPTIONAL_LOCKS=0 timeout 3 git -C "§W" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
          GA=§(GIT_OPTIONAL_LOCKS=0 timeout 3 git -C "§W" rev-list --count '@{u}..HEAD' 2>/dev/null); [ -n "§GA" ] || GA=-
          GS=§(GIT_OPTIONAL_LOCKS=0 timeout 3 git -C "§W" stash list 2>/dev/null | wc -l | tr -d ' ')
        fi
        echo "FACT git §GD §GA §GS"
        TRX=-
        if [ -n "§CP" ]; then
          TRX=0
          for s in "§RSID" §(tr '\0' '\n' < "/proc/§CP/cmdline" 2>/dev/null | awk 'p { print; p=0 } /^--(resume|session-id)§/ { p=1 }'); do
            case "§s" in ''|*[!0-9a-f-]*) continue ;; esac
            [ -n "§(ls "§HOME"/.claude/projects/*/"§s".jsonl "§C"/projects/*/"§s".jsonl 2>/dev/null | head -n 1)" ] && { TRX=1; break; }
          done
        fi
        echo "FACT transcript §TRX"
        # the token: what makes closing safe or not, without timestamps (a client's activity moves every keystroke)
        EV1=§(printf '%s' "§EVL" | cut -d' ' -f1)
        SIG="§N|§{CP:-}|§{ST:-}|§{WF:-}|§EV1|§DR|§OTT|§PR|§GD|§GA|§GS"
        H=§(printf '%s' "§SIG" | sha256sum 2>/dev/null | cut -d' ' -f1); [ -n "§H" ] || H=§(printf '%s' "§SIG" | cksum | tr ' ' '-')
        echo "TOKEN §NOW.§H"
        echo "@@END@@"
        )
        """.trimIndent(),
    ).replace("@@VIEW@@", VIEWER_ACTIVE_S.toString()).replace("@@END@@", END).replace("@@YOUNG@@", PROC_MIN_AGE_S.toString())

    /**
     * `cr_blocks`: reads a facts output on stdin and prints the codes of its BLOCK reasons, comma-joined (empty = none),
     * the shell twin of the BLOCK rules in [parse] (a test runs both over the same outputs). `cr-session close` refuses
     * with it; the app needs no twin, it parses.
     */
    internal val BLOCKS_SH: String = sh(
        """
        cr_blocks() {
          awk -v busy=@@BUSY@@ '
            function add(c) { if (!(c in seen)) { seen[c] = 1; out = out (out == "" ? "" : ",") c } }
            §1 == "FACT" { a[§2] = §3; b[§2] = §4; c[§2] = §5 }
            END {
              if (a["alive"] == "0") { print ""; exit }
              st = a["status"]; ev = a["event"]; age = b["event"]; bg = c["event"]
              young = (age ~ /^[0-9]+§/ && age + 0 < busy)
              if (st == "busy") add("BUSY"); if (st == "waiting") add("WAITING")
              if ((st == "" || st == "-") && young) { if (ev == "work") add("BUSY"); if (ev == "ask" || ev == "perm") add("WAITING") }
              if (ev == "stop" && bg == "1" && young) add("BACKGROUND")
              if (a["draft"] == "1") add("DRAFT")
              if (a["clients"] + 0 > 0 && b["clients"] != "0") add("VIEWER")
              if (a["procs"] + 0 > 0) add("PROCESSES")
              print out
            }'
        }
        """.trimIndent(),
    ).replace("@@BUSY@@", EVENT_BUSY_S.toString())

    /** The value of `TOKEN` in [out], or null. */
    fun token(out: String): String? =
        out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("TOKEN ") }?.removePrefix("TOKEN ")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [CloseFacts] of [out] (the output of [closeFactsCommand] or `cr-session facts`). An active client other than the
     * closing device's own is a BLOCK when the command carried no marker (`cr-session`: no own client) or when the
     * device's own client was found among the attached ones. When a marker was given but its tty is not attached,
     * the other clients are only a WARN ("možná"): an attach that wrote no marker would look like someone else.
     */
    fun parse(out: String): CloseFacts {
        val lines = out.lines().map { it.trim() }
        if (END !in lines) return CloseFacts.UNKNOWN
        val f = HashMap<String, List<String>>()
        for (l in lines) if (l.startsWith("FACT ")) {
            val t = l.removePrefix("FACT ").split(Regex("\\s+"))
            if (t.isNotEmpty()) f[t[0]] = t.drop(1)
        }
        fun num(s: String?): Int? = s?.toIntOrNull()
        val alive = when (f["alive"]?.firstOrNull()) { "1" -> true; "0" -> false; else -> null }
        val transcript = when (f["transcript"]?.firstOrNull()) { "1" -> true; "0" -> false; else -> null }
        val token = token(out)
        if (alive == false) return CloseFacts(CloseLevel.OK, emptyList(), token, false, transcript)

        val reasons = ArrayList<CloseReason>()
        fun block(code: CloseReason.Code, text: String) { if (reasons.none { it.code == code }) reasons += CloseReason(code, CloseLevel.BLOCK, text) }
        fun warn(code: CloseReason.Code, text: String) { if (reasons.none { it.code == code }) reasons += CloseReason(code, CloseLevel.WARN, text) }

        val status = f["status"]?.getOrNull(0)?.takeUnless { it == "-" }
        val waitingFor = f["status"]?.getOrNull(1)?.takeUnless { it == "-" }?.let { decodeB64(it) }
        val ev = f["event"]
        val evName = ev?.getOrNull(0)?.takeUnless { it == "-" }
        val evAge = num(ev?.getOrNull(1))
        when (status) {
            "busy" -> block(CloseReason.Code.BUSY, "běží tah")
            "waiting" -> block(CloseReason.Code.WAITING, waitingText(waitingFor))
        }
        // without a registry (older claude, or none found) the events file is the only witness of a running turn
        if (status == null && evName != null && evAge != null && evAge < EVENT_BUSY_S) when (evName) {
            "work" -> block(CloseReason.Code.BUSY, "běží tah")
            "ask", "perm" -> block(CloseReason.Code.WAITING, "čeká na odpověď")
        }
        // a `stop` that left background tasks running: a BLOCK only while it is recent — a later event would have
        // replaced it, and tasks still running then show up as processes of the pane anyway
        if (evName == "stop" && ev?.getOrNull(2) == "1" && evAge != null && evAge < EVENT_BUSY_S) {
            block(CloseReason.Code.BACKGROUND, "běží úlohy na pozadí")
        }
        if (f["draft"]?.firstOrNull() == "1") block(CloseReason.Code.DRAFT, "rozepsaný vstup")
        val others = num(f["clients"]?.getOrNull(0)) ?: 0
        val selfOn = f["clients"]?.getOrNull(1)
        if (others > 0) {
            if (selfOn == "0") warn(CloseReason.Code.VIEWER_MAYBE, "možná se dívá jiné zařízení")
            else block(CloseReason.Code.VIEWER, "dívá se jiné zařízení")
        }
        val procs = num(f["procs"]?.getOrNull(0)) ?: 0
        if (procs > 0) {
            val names = f["procs"]?.getOrNull(1)?.takeUnless { it == "-" }?.split(',')?.take(3)?.joinToString(", ")
            block(CloseReason.Code.PROCESSES, if (names != null) "běží $names" else "běží další procesy")
        }
        val git = f["git"]
        num(git?.getOrNull(0))?.takeIf { it > 0 }?.let { warn(CloseReason.Code.UNCOMMITTED, czCount(it, "necommitnutý soubor", "necommitnuté soubory", "necommitnutých souborů")) }
        num(git?.getOrNull(1))?.takeIf { it > 0 }?.let { warn(CloseReason.Code.UNPUSHED, czCount(it, "nepushnutý commit", "nepushnuté commity", "nepushnutých commitů")) }
        num(git?.getOrNull(2))?.takeIf { it > 0 }?.let { warn(CloseReason.Code.STASH, czCount(it, "uložený stash", "uložené stashe", "uložených stashů")) }
        if (evAge != null && evAge < RECENT_S && reasons.none { it.level == CloseLevel.BLOCK }) {
            val min = evAge / 60
            warn(CloseReason.Code.RECENT, if (min == 0) "aktivní právě teď" else "aktivní před $min min")
        }
        val level = when {
            reasons.any { it.level == CloseLevel.BLOCK } -> CloseLevel.BLOCK
            reasons.any { it.level == CloseLevel.WARN } -> CloseLevel.WARN
            else -> CloseLevel.OK
        }
        return CloseFacts(level, reasons, token, alive, transcript)
    }

    private fun waitingText(waitingFor: String?): String = when {
        waitingFor == null -> "čeká na schválení"
        waitingFor.contains("dialog", ignoreCase = true) -> "otevřený dialog"
        waitingFor.contains("input", ignoreCase = true) -> "čeká na odpověď"
        else -> "čeká na schválení"
    }

    /** "1 soubor", "3 soubory", "5 souborů". */
    internal fun czCount(n: Int, one: String, few: String, many: String): String = when {
        n == 1 -> "1 $one"
        n in 2..4 -> "$n $few"
        else -> "$n $many"
    }

    private fun decodeB64(s: String): String? = try {
        java.util.Base64.getDecoder().decode(s).decodeToString()
    } catch (_: IllegalArgumentException) {
        null
    }
}
