package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.session.ClaudeConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One line of `~/.claude-remote/closed.jsonl` ([ManifestCommands.closeRecordBody], §15.12). Every field but
 * [tmuxName] may be missing: the record is written from whatever the dying pane still showed.
 */
internal data class ClosedRecord(
    val v: Int?,
    /** ISO-8601 UTC, e.g. `2026-11-06T17:20:03Z`. */
    val at: String?,
    val tmuxName: String,
    /** The newest session id that has a transcript (the registry's after a `--resume` fork, else the launch's). */
    val sessionId: String?,
    /** The id the pane was launched with (`--resume` / `--session-id`). */
    val launchSessionId: String?,
    val transcript: String?,
    val cwd: String?,
    val folder: String?,
    /** Account slug; null = the default login. */
    val account: String?,
    /** [ClaudeModel] name, null when unknown. */
    val model: String?,
    /** [ClaudeMode] name, null when unknown. */
    val mode: String?,
    val flags: List<String>,
    val effort: String?,
    val claudeVersion: String?,
    val title: String?,
    val pid: Int?,
    /** `app:<installId>`, `prune`, `jarvis`. */
    val by: String?,
    val durable: Boolean?,
    val reason: String?,
) {
    val claudeModel: ClaudeModel get() = model?.let { m -> ClaudeModel.entries.firstOrNull { it.name == m } } ?: ClaudeModel.DEFAULT

    val claudeMode: ClaudeMode
        get() = mode?.let { m -> ClaudeMode.entries.firstOrNull { it.name == m } }
            ?: if (ClaudeConfig.modeArgs(ClaudeMode.YOLO).last() in flags) ClaudeMode.YOLO else ClaudeMode.NORMAL

    /** Closed by the app's prune (a guess that the pane was gone), not by a person. */
    val closedByPrune: Boolean get() = by == "prune"

    /** Where to start the restored pane: the directory claude ran in (its transcript is keyed by it). */
    val restoreFolder: String? get() = cwd ?: folder

    /** [at] as epoch seconds, or null. */
    val atEpochSeconds: Long?
        get() = at?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }
}

internal object ClosedRecords {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One record, or null for a line that is not one (blank, cut off, no `tmuxName`). Unknown fields are ignored. */
    fun parseLine(line: String): ClosedRecord? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        val o = try { json.parseToJsonElement(t) as? JsonObject } catch (_: Exception) { null } ?: return null
        fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.takeIf { it.isNotEmpty() }
        val name = str("tmuxName") ?: return null
        return ClosedRecord(
            v = (o["v"] as? JsonPrimitive)?.intOrNull,
            at = str("at"),
            tmuxName = name,
            sessionId = str("sessionId"),
            launchSessionId = str("launchSessionId"),
            transcript = str("transcript"),
            cwd = str("cwd"),
            folder = str("folder"),
            account = str("account"),
            model = str("model"),
            mode = str("mode"),
            flags = (o["flags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
            effort = str("effort"),
            claudeVersion = str("claudeVersion"),
            title = str("title"),
            pid = (o["pid"] as? JsonPrimitive)?.intOrNull,
            by = str("by"),
            durable = (o["durable"] as? JsonPrimitive)?.booleanOrNull,
            reason = str("reason"),
        )
    }

    /**
     * The output of [ManifestCommands.readClosed], NEWEST FIRST. Null when the end marker is missing (the read was
     * cut off: unknown, not "nothing closed"); lines that are not records are skipped.
     */
    fun parseRead(out: String): List<ClosedRecord>? {
        val lines = out.lines()
        val end = lines.indexOfLast { it.trim() == ManifestCommands.CLOSED_END }
        if (end < 0) return null
        return lines.subList(0, end).mapNotNull { parseLine(it) }.asReversed()
    }
}

/** The outcome of a restore ([SessionRestore.restoreCommand], `cr-session restore`). */
internal sealed interface RestoreResult {
    /** Started; [registry] = claude reported itself ([pid], [sessionId] — a new id after the `--resume` fork). */
    data class Restored(val pid: Int?, val sessionId: String?, val registry: Boolean) : RestoreResult
    /** The name is taken (a live session): nothing was touched. [suggestedName] = the first free `…-2`, `…-3`. */
    data class Exists(val suggestedName: String?) : RestoreResult
    /** The manifest still holds the name untombstoned: drift's self-heal will start it within a minute. */
    data object PendingSelfHeal : RestoreResult
    /** The conversation runs in another pane already ([tmuxName], when its registry says). */
    data class RunningElsewhere(val tmuxName: String?) : RestoreResult
    /** Neither id has a transcript. [candidateSessionId] = the newest transcript of the folder ±10 min around the close (a guess, for the user to confirm). */
    data class NoTranscript(val candidateSessionId: String?) : RestoreResult
    /** The account has no `.credentials.json` (only its presence is checked). */
    data class LoggedOut(val account: String) : RestoreResult
    /** Started (tombstone already lifted), but claude did not come up and the pane asks for a login. The session is left running. */
    data object LoginRequired : RestoreResult
    /** Not attempted: an invalid input, or the tmux socket guard refused. */
    data class Refused(val why: String) : RestoreResult
    data class Failed(val detail: String) : RestoreResult
    /** No result line (cut off, timed out). */
    data object Unknown : RestoreResult
}

/**
 * Restore a closed session under its name, account and conversation (§15.13, §15.14), shared by the app (inline over
 * exec) and `cr-session restore`. The steps, in this order, each refusing with its own [RestoreResult]:
 *  1. the name is free (`has-session`; taken → [RestoreResult.Exists] with `…--<alias>-2`, rising — the builder would
 *     otherwise kill the session there) and no manifest entry of it waits for self-heal;
 *  2. the conversation runs nowhere (a live registry file of either id);
 *  3. its transcript exists (the record's id, else the launch's: claude forks on `--resume` and writes the new
 *     transcript lazily);
 *  4. the account is logged in (`test -s .credentials.json`, never read);
 *  5. the tombstones are lifted BEFORE the start (§15.7, rozpor 15);
 *  6. the pane is created detached from the builder's own pieces — never `kill-session` (§15.15);
 *  7. `opened.d/<name>` with `by:"restore"`, so devices that forgot the tab adopt it;
 *  8. wait up to `$WAIT` s for claude's registry entry.
 */
internal object SessionRestore {

    const val RESULT_PREFIX = "CR_RESTORE"

    /** Seconds the app's inline restore waits for claude's registry (its exec limit must exceed it). */
    const val APP_WAIT_S = 8

    private fun sh(s: String) = s.replace('§', '$')

    /** Breadth-first pids of the pane subtree rooted at `$PP`, one per line (same walk as drift and [ManifestCommands.uuidProbe]). */
    private const val SUBTREE_AWK =
        "awk -v root=\"§PP\" '{ k[§2]=k[§2]\" \"§1 } END { h=0;t=0;q[t++]=root; while(h<t){p=q[h++];print p;m=split(k[p],a,\" \");for(i=1;i<=m;i++)if(a[i]!=\"\")q[t++]=a[i]} }'"

    /**
     * Wait up to `$WAIT` s for the claude of pane `$N` to write `sessions/<pid>.json` under its own config dir. Sets
     * `RSTATE` (`ok` | `login` | `none`), `RPID`, `RSID`, `RPANE` (the pane id, `%12`). Needs [ManifestCommands.JSON_SH].
     */
    internal val WAIT_REGISTRY: String = sh(
        """
        RPID=""; RSID=""; RPANE=""; RSTATE=none; k=0
        while [ §k -lt §(( §{WAIT:-8} * 2 )) ]; do
          PL=§(timeout 3 tmux -u list-panes -t "=§N:" -F '#{pane_pid} #{pane_id}' 2>/dev/null | head -n 1)
          PP=§{PL%% *}; [ -n "§PL" ] && RPANE=§{PL#* }
          if [ -n "§PP" ]; then
            for p in §(ps -eo pid=,ppid= 2>/dev/null | $SUBTREE_AWK); do
              c=§(tr '\0' '\n' < "/proc/§p/environ" 2>/dev/null | sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -n 1)
              f="§{c:-§HOME/.claude}/sessions/§p.json"
              if [ -f "§f" ]; then RPID=§p; RSID=§(jget sessionId "§(head -c 65536 "§f" 2>/dev/null | tr '\n' ' ')"); RSTATE=ok; break 2; fi
            done
          fi
          sleep 0.5; k=§((k+1))
        done
        if [ "§RSTATE" != ok ] && timeout 3 tmux capture-pane -p -t "=§N:" 2>/dev/null | grep -qiE '/login|login method|not logged in'; then RSTATE=login; fi
        """.trimIndent(),
    )

    /**
     * Inputs: `$N` (the name to restore under), `$S` (session id), `$S2` (fallback id, may be empty), `$F` (folder, for
     * the marker and the candidate search), `$A` (account slug, empty = default), `$CMD` / `$CMD2` (the pane's launch
     * command resuming `$S` / `$S2`), `$FRESH` (1 = a new conversation: no transcript checks), `$AT_EPOCH` (close
     * time, may be empty), `$WAIT`. Prints exactly one `CR_RESTORE <RESULT> [args]` line ([parse]).
     */
    fun restoreBody(): String = RESTORE
        .replace("@@JSON@@", ManifestCommands.JSON_SH)
        .replace("@@MANIFEST@@", ManifestCommands.MANIFEST_SH)
        .replace("@@CLEAR@@", ManifestCommands.clearTombstoneBody())
        .replace("@@SPAWN@@", ClaudeConfig.detachedCreateBody())
        .replace("@@MARKER@@", ManifestCommands.openedMarkerBody())
        .replace("@@WAIT@@", WAIT_REGISTRY)

    /**
     * The app's restore of [record] under [tmuxName] (default: its own name). [sessionId] overrides the id to resume
     * (a confirmed [RestoreResult.NoTranscript] candidate); [freshSessionId] starts a NEW conversation of that id in
     * the same folder and account instead (§15.14 "transcript chybí"). Mode and model come from the record.
     */
    fun restoreCommand(
        record: ClosedRecord,
        tmuxName: String = record.tmuxName,
        sessionId: String? = null,
        freshSessionId: String? = null,
        waitS: Int = APP_WAIT_S,
    ): String {
        val folder = requireNotNull(record.restoreFolder) { "the record has no folder" }
        val uuid = Regex("^[0-9a-f-]{8,64}$")
        val s = freshSessionId ?: sessionId ?: record.sessionId ?: record.launchSessionId
        require(s != null && uuid.matches(s)) { "no usable session id" }
        val s2 = if (freshSessionId == null && sessionId == null) record.launchSessionId?.takeIf { it != s && uuid.matches(it) } else null
        fun launch(id: String, resume: Boolean) =
            ClaudeConfig.buildLaunchCommand(folder, record.claudeMode, record.claudeModel, id, resume, record.account)
        val sq = ManifestCommands::sq
        return "N=${sq(tmuxName)}; S=${sq(s)}; S2=${sq(s2.orEmpty())}; F=${sq(folder)}; A=${sq(record.account.orEmpty())}; " +
            "CMD=${sq(launch(s, resume = freshSessionId == null))}; CMD2=${sq(s2?.let { launch(it, true) }.orEmpty())}; " +
            "FRESH=${if (freshSessionId != null) 1 else 0}; AT_EPOCH=${record.atEpochSeconds ?: ""}; WAIT=$waitS\n" +
            restoreBody()
    }

    /** The [RestoreResult] of a restore's output (the last `CR_RESTORE` line). */
    fun parse(out: String): RestoreResult {
        val line = out.lines().map { it.trim() }.lastOrNull { it.startsWith("$RESULT_PREFIX ") } ?: return RestoreResult.Unknown
        val t = line.removePrefix("$RESULT_PREFIX ").split(Regex("\\s+"), limit = 3)
        fun arg(i: Int): String? = t.getOrNull(i)?.trim()?.takeUnless { it.isEmpty() || it == "-" }
        return when (t[0]) {
            "RESTORED" -> RestoreResult.Restored(arg(1)?.toIntOrNull(), arg(2), registry = true)
            "RESTORED_NO_REGISTRY" -> RestoreResult.Restored(null, null, registry = false)
            "EXISTS" -> RestoreResult.Exists(arg(1))
            "PENDING_SELF_HEAL" -> RestoreResult.PendingSelfHeal
            "RUNNING_ELSEWHERE" -> RestoreResult.RunningElsewhere(arg(1))
            "NO_TRANSCRIPT" -> RestoreResult.NoTranscript(arg(1))
            "LOGGED_OUT" -> RestoreResult.LoggedOut(arg(1) ?: "default")
            "OPENED_LOGIN_REQUIRED" -> RestoreResult.LoginRequired
            "INVALID" -> RestoreResult.Refused("invalid ${arg(1).orEmpty()}".trim())
            "REFUSED_GUARD" -> RestoreResult.Refused("socket guard ${arg(1).orEmpty()}".trim())
            "FAILED" -> RestoreResult.Failed(arg(1).orEmpty())
            else -> RestoreResult.Unknown
        }
    }

    private val RESTORE = sh(
        """
        ( set +eu
        @@JSON@@
        D="§HOME/.claude-remote"
        out() { echo "$RESULT_PREFIX §*"; }
        case "§N" in ''|*[./:]*|*'
        '*) out INVALID name; exit 0 ;; esac
        case "§{A:-}" in ''|default) ACFG="§HOME/.claude" ;; */*|.*) out INVALID account; exit 0 ;; *) ACFG="§D/accounts/§A" ;; esac
        case "§{S:-}" in ''|*[!0-9a-f-]*) out INVALID session; exit 0 ;; esac
        case "§{S2:-}" in *[!0-9a-f-]*) S2="" ;; esac
        tr_of() { case "§1" in ''|*[!0-9a-f-]*) return 0 ;; esac; ls "§HOME"/.claude/projects/*/"§1".jsonl "§ACFG"/projects/*/"§1".jsonl 2>/dev/null | head -n 1; }
        @@MANIFEST@@
        has() { timeout 3 tmux has-session -t "=§1" 2>/dev/null; }
        suggest() {
          case "§N" in *--*) B="§N-" ;; *) B="§N--" ;; esac
          i=2; while [ §i -lt 100 ]; do
            has "§B§i" || { in_manifest "§B§i" && ! tombstoned "§B§i"; } || break
            i=§((i+1))
          done
          printf '%s' "§B§i"
        }
        # 1. the name is free, and no manifest entry of it waits for drift's self-heal
        has "§N"; R=§?
        [ §R -eq 124 ] && { out FAILED tmux-timeout; exit 0; }
        [ §R -eq 0 ] && { out EXISTS "§(suggest)"; exit 0; }
        if in_manifest "§N" && ! tombstoned "§N"; then out PENDING_SELF_HEAL; exit 0; fi
        if [ "§{FRESH:-0}" != 1 ]; then
          # 2. the conversation runs nowhere (a registry file of a live process, the same process: procStart)
          for f in "§HOME"/.claude/sessions/*.json "§D"/accounts/*/sessions/*.json; do
            [ -f "§f" ] || continue
            J=§(head -c 65536 "§f" 2>/dev/null | tr '\n' ' '); v=§(jget sessionId "§J")
            [ -n "§v" ] && { [ "§v" = "§S" ] || [ "§v" = "§{S2:-}" ]; } || continue
            p=§{f##*/}; p=§{p%.json}; [ -d "/proc/§p" ] || continue
            ps0=§(jget procStart "§J"); ps1=§(sed 's/^.*) //' "/proc/§p/stat" 2>/dev/null | cut -d' ' -f20)
            [ -n "§ps0" ] && [ "§ps0" != "§ps1" ] && continue
            t=§(jget tmux "§J"); t=§{t%%:*}; out RUNNING_ELSEWHERE "§{t:--}"; exit 0
          done
          # 3. a transcript exists: the record's id, else the launch's (claude forks on --resume, the new one is lazy)
          if [ -z "§(tr_of "§S")" ]; then
            if [ -n "§{S2:-}" ] && [ -n "§(tr_of "§S2")" ]; then S=§S2; CMD=§CMD2
            else
              PD="§HOME/.claude/projects/§(printf '%s' "§F" | sed 's/[^A-Za-z0-9]/-/g')"; CAND=""
              case "§{AT_EPOCH:-}" in ''|*[!0-9]*) ;; *)
                [ -d "§PD" ] && CAND=§(find "§PD" -maxdepth 1 -name '*.jsonl' -newermt "@§((AT_EPOCH-600))" ! -newermt "@§((AT_EPOCH+600))" -printf '%T@ %f\n' 2>/dev/null | sort -n | tail -n 1 | sed 's/^[^ ]* //; s/\.jsonl§//') ;;
              esac
              out NO_TRANSCRIPT "§{CAND:--}"; exit 0
            fi
          fi
        fi
        # 4. the account is logged in: only whether its credentials file is there, its content is never read
        [ -s "§ACFG/.credentials.json" ] || { out LOGGED_OUT "§{A:-default}"; exit 0; }
        # 5. tombstones off BEFORE the start: drift would subtract the name, peers would forget the tab (§15.7)
        ( @@CLEAR@@ ) >/dev/null 2>&1
        # 6. the builder's own pieces, detached, killing nothing: a name taken meanwhile fails new-session
        NAME=§N
        @@SPAWN@@
        RC=§?
        if [ §RC -ne 0 ]; then
          if [ "§{CR_SG:-0}" -ne 0 ] && [ "§{CR_SG:-0}" -ne 3 ]; then out REFUSED_GUARD "§CR_SG"; exit 0; fi
          has "§N" && { out EXISTS "§(suggest)"; exit 0; }
          out FAILED "rc=§RC"; exit 0
        fi
        # 7. a marker, so devices that forgot the tab adopt it
        ( BY=restore; TASK=""
        @@MARKER@@
        )
        # 8. claude's registry entry
        @@WAIT@@
        case "§RSTATE" in
          ok) out RESTORED "§{RPID:--}" "§{RSID:--}" ;;
          login) out OPENED_LOGIN_REQUIRED ;;
          *) out RESTORED_NO_REGISTRY ;;
        esac
        )
        """.trimIndent(),
    )
}
