"""Jarvis F4: Jarvis opens, closes and restores sessions, and the general proposals (mail + actions).

Shared by `cr_sessions.py` (open / check-close / close / closed / reopen / stale / pick-account), `cr_jarvis.py`
(proposals / approve / reject / run), `jarvismail.py` (Lukáš's next prompt decides the proposals), `jarvis_d.py` (the
sweep) and `cr_digest.py` (the delta, the weekly "staré sessions"). Design: `20-architektura.md` §15, `f4/00-brief.md`.

The server path is `~/.claude-remote/bin/cr-session` (F0, generated from the app's own builders, I9): this module never
runs tmux or touches a manifest or a tombstone itself. It only decides WHETHER and WHEN, and calls cr-session:
- the version must be the one this wrapper knows, else it refuses ("connect the app so it reinstalls cr-session");
- close always with the token of `cr-session facts`, never `--force` (that is Lukáš's own, from his shell);
- PROD/DB-/deploy/Proxmox names (I7) and Jarvis himself are never closed;
- at most 5 closes on one confirmation and 20 a day (counted in the ops ledger, by Jarvis only).

When does it act at once (Lukáš 2026-11-08, the same rule as the mail): he NAMED the session (close, reopen) or the
project (open) in his fresh prompt himself, the facts report no BLOK, and the account is the folder's default by the
policy. Anything else (Jarvis chose, BLOK, another account, a bulk close, outside the policy) is a PROPOSAL `x-…`,
approved by his next short "ano" (`prompt_seen`) or by the app's card (`cr-jarvis approve`), dropped otherwise, gone
after 10 minutes.

Files (under `<state>/ops/`, mode 600 / dirs 700):
  proposals.json   every action record (proposed -> approved -> running -> done | failed; dropped, rejected, expired)
  ledger.jsonl     append-only: proposals, decisions, results, refusals (the delta's and the alert's source)
  ops.lock         flock for both
  stale-told       when the morning briefing last offered to close the stale sessions (once a week)
"""
import contextlib
import datetime
import fcntl
import json
import os
import re
import secrets
import subprocess
import sys
import time

import jarvislib as J

ID_RE = re.compile(r"x-\d{8}T\d{6}-[0-9a-f]{4}")
KINDS = ("open", "close", "restore", "approve")
STATES = ("proposed", "approved", "running", "done", "failed", "dropped", "rejected", "expired")
FINAL = ("done", "failed", "dropped", "rejected", "expired")

CR_SESSION_VERSION = "cr-session v2"
PROPOSAL_MS = 10 * 60 * 1000
PROMPT_FRESH_MS = 10 * 60 * 1000
CLOSE_PER_CONFIRM = 5
CLOSE_PER_DAY = 20
# actions (open / close / reopen commands, a bulk close counts once) on one prompt of Lukáš's: a loop guard
PER_PROMPT = 3
# an account is unsuitable for a new session from this much on (Lukáš 2026-11-08)
FIVE_HOUR_MAX = 85
SEVEN_DAY_MAX = 95
USAGE_STALE_S = 15 * 60
# cr-session facts: an event this young still counts (cr_blocks' `busy=1800`)
EVENT_YOUNG_S = 1800
RECENT_ACTIVITY_S = 10 * 60
CLOSED_UNDO_MS = 24 * 3600 * 1000
OPEN_WAIT_S = 15
# an approved action runs in the app's exec or in a process the prompt hook started: a shorter wait for the registry
APPROVED_WAIT_S = 8
RUN_STALE_MS = 30 * 1000
# a run whose process died (the app's exec cut at its 35 s timeout, a kill) never ends its record itself
RUNNING_DEAD_MS = 5 * 60 * 1000
LEDGER_MAX = 2 * 1024 * 1024
KEEP_FINAL_MS = 7 * 86400 * 1000
STALE_DAYS = 7
STALE_TELL_MS = int(6.5 * 86400 * 1000)
SERVER = "server"
ALIAS_RE = re.compile(r"[\w][\w -]{0,39}")
MODELS = ("DEFAULT", "OPUS", "FABLE", "SONNET", "HAIKU")

# rev-f4 H1: naming a session or a project is not asking to act on it ("kolik tokenů žere rag?" closed RAG). An
# action runs at once only when the prompt both names its target AND asks for that act; the hook keeps the flags only.
INTENT = {
    "closeIntent": re.compile(r"(?<!\w)(?:zavři(?:te)?|zavřít|zavřeš|ukonči(?:te)?|ukončit|close|kill)(?!\w)",
                              re.IGNORECASE),
    "openIntent": re.compile(r"(?<!\w)(?:otevři(?:te)?|otevřít|spusť(?:te)?|spustit|založ(?:te)?|založit|open)(?!\w)"
                             r"|(?<!\w)nov(?:ou|á)\s+session", re.IGNORECASE),
    "reopenIntent": re.compile(r"(?<!\w)(?:vrať(?:te)?|vrátit|obnov(?:te)?|obnovit)(?!\w)", re.IGNORECASE),
}
# F5e: the option numbers Lukáš's prompt names ("zvol dvojku", "dvě", "2"): only to SAY a mismatch in the code's
# sentence, never to approve anything
_NUM_WORDS = {"jedna": 1, "jedničk": 1, "první": 1, "dvě": 2, "dva": 2, "dvojk": 2, "druh": 2, "tři": 3, "trojk": 3,
              "třetí": 3, "čtyři": 4, "čtyřk": 4, "čtvrt": 4, "pět": 5, "pětk": 5, "pát": 5}


def option_numbers(prompt):
    if not isinstance(prompt, str):
        return []
    out = {int(x) for x in re.findall(r"(?<!\d)([1-9])(?!\d)", prompt)}
    for w in re.findall(r"\w+", prompt.lower()):
        for stem, n in _NUM_WORDS.items():
            if w.startswith(stem):
                out.add(n)
    return sorted(out)[:5]
# rev-f4 M1: the targets of every close proposed or run on one prompt of Lukáš's, together
CLOSE_TARGETS_PER_PROMPT = 5
# rev-f4 L4: a runner or cr-session must not run as Jarvis's pane / claude / account
SCRUB_ENV = ("TMUX", "TMUX_PANE", "CLAUDECODE", "CLAUDE_CONFIG_DIR")

# cr-session's REFUSED_BLOCKED codes (cr_blocks) and ours (a task or mail of Jarvis's waits on the session)
BLOCK_CZ = {"BUSY": "pracuje (běží tah)", "WAITING": "čeká na odpověď nebo povolení",
            "BACKGROUND": "běží jí úloha na pozadí", "DRAFT": "má rozepsaný vstup",
            "VIEWER": "někdo se na ni právě dívá", "PROCESSES": "běží v ní další procesy",
            "TASK": "má otevřený úkol od Jarvise", "MAIL": "čeká na ni zpráva od Jarvise"}
CR_BLOCKS = ("BUSY", "WAITING", "BACKGROUND", "DRAFT", "VIEWER", "PROCESSES")


class Refused(Exception):
    pass


def _norm(s):
    return " ".join(s.split()) if isinstance(s, str) else ""


def last_turn_text(path, current=None, max_bytes=2 * 1024 * 1024):
    """Jarvis's texts of the turn right BEFORE Lukáš's current prompt (kolo 5: only what he said after the prompt
    before it; a turn without any text is "", never an older answer). The current prompt may already stand in the
    transcript when the hook runs: a last prompt equal to `current` (the app's `[hlas] ` cut) is skipped."""
    if not isinstance(path, str) or not path:
        return None
    import jarvismail as M
    turns = [[None, []]]  # [prompt text, assistant texts]
    for line in J.read_tail(path, max_bytes):
        if not line.startswith("{"):
            continue
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if not isinstance(d, dict) or d.get("isSidechain"):
            continue
        if d.get("type") == "user" and J._is_prompt(d):
            c = (d.get("message") or {}).get("content")
            text = c if isinstance(c, str) else " ".join(b.get("text", "") for b in c or [] if isinstance(b, dict))
            turns.append([text, []])
        elif d.get("type") == "assistant":
            t = J._assistant_text(d)
            if t:
                turns[-1][1].append(t)
    if current is not None and len(turns) > 1 and not turns[-1][1] and \
            _norm(M.strip_voice(turns[-1][0])) == _norm(M.strip_voice(current)):
        turns.pop()  # the "ano" itself is already written
    return " ".join(turns[-1][1])


def said_verbatim(sentence, transcript, current=None):
    """F5e: did Jarvis's last turn before this prompt hold the proposal's sentence word for word (only blanks
    normalised)?"""
    s = _norm(sentence)
    return bool(s) and s in _norm(last_turn_text(transcript, current) or "")


def intents(prompt):
    """{closeIntent, openIntent, reopenIntent}: booleans for the hook's last-prompt record (never the text)."""
    d = {k: bool(r.search(prompt)) for k, r in INTENT.items()} if isinstance(prompt, str) else \
        {k: False for k in INTENT}
    d["optionNumbers"] = option_numbers(prompt)
    return d


def scrubbed_env(environ):
    e = {k: v for k, v in environ.items() if k not in SCRUB_ENV}
    e.setdefault("PATH", os.environ.get("PATH", "/usr/bin:/bin"))
    return e


def new_id(ms):
    t = datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y%m%dT%H%M%S")
    return f"x-{t}-{secrets.token_hex(2)}"


def plural_cz(n, one, few, many):
    return J.plural(n, one, few, many)


# ---------------------------------------------------------------------------------------------------------- names

def sanitize(name):
    """TmuxNameParser.sanitize: tmux rewrites `.` and `:` itself, so the name we keep is the one tmux will hold."""
    return name.replace(".", "_").replace(":", "_")


def build_name(folder, alias="", yolo=True, server=SERVER):
    """TmuxNameParser.build: claude-<server>-<folder basename>[-yolo][--<alias>], only the folder part is ever cut."""
    part = folder.rstrip("/").rsplit("/", 1)[-1] or folder
    suffix = ("-yolo" if yolo else "") + (("--" + alias.replace(" ", "-")) if alias.strip() else "")
    prefix = f"claude-{server}-"
    budget = 64 - len(prefix) - len(suffix)
    if 1 <= budget < len(part):
        part = part[:budget]
    return sanitize(prefix + part + suffix)


def fresh_name(folder, alias, taken, yolo=True, server=SERVER):
    """ClaudeConfig.freshTmuxSessionName: the usual name, else the first free `-2`, `-3`, … before the tail, so the
    app's parser still reads the mode and the alias back."""
    base = build_name(folder, alias, yolo, server)
    if base not in taken:
        return base
    tail = build_name("x", alias, yolo, server)[len(build_name("x", "", False, server)):]
    stem = base[:-len(tail)] if tail and base.endswith(tail) else base
    i = 2
    while f"{stem}-{i}{tail}" in taken:
        i += 1
    return f"{stem}-{i}{tail}"


def high_impact(name):
    import jarvismail as M
    return bool(M.HIGH_IMPACT.search(name or ""))


# ---------------------------------------------------------------------------------------------------------- policy

def normalize(p):
    """RemotePath.normalize: trim, collapse `//`, drop a trailing `/` (blank = `~`)."""
    t = (p or "").strip()
    if not t:
        return "~"
    t = re.sub(r"/{2,}", "/", t).rstrip("/")
    return t or "/"


def canonical_folder(folder, home):
    """canonicalPolicyFolder: `~` or `~/rel` under home, an absolute path otherwise, relative = home-relative."""
    p = normalize(folder)
    while p.startswith("./"):
        p = p[2:]
    if p == ".":
        return "~"
    h = normalize(home) if home else None
    h = h if h and h.startswith("/") and h != "/" else None
    if p.startswith("~"):
        return p
    if h and p == h:
        return "~"
    if h and p.startswith(h + "/"):
        return "~" + p[len(h):]
    if p.startswith("/"):
        return p
    return "~/" + p


def covers(key, folder):
    """policyCoversFolder: the folder itself and everything under it, never a sibling with the same prefix; a rule on
    `~` or `/` covers only that folder."""
    return key == folder or (key not in ("~", "/") and folder.startswith(key + "/"))


def policy_for(env, folder):
    """(stored key, {defaultAccountSlug, allowedAccountSlugs}) of the most specific rule covering `folder` (an absolute
    path), or (None, {}). FolderPolicyStorage.get with ROW_ORDER: longest canonical key, the canonical spelling first,
    then the stored text."""
    d = J.read_json(os.path.join(env.cr, "folder-policies.json"), {}) or {}
    if not isinstance(d, dict):
        return None, {}
    target = canonical_folder(folder, env.home)
    rows = []
    for key, pol in d.items():
        if not isinstance(key, str) or not isinstance(pol, dict):
            continue
        # the server file is keyed by folder alone (the app writes this server's rules); an app-side `<id>::` prefix
        # is dropped the same way
        k = key.split("::", 1)[1] if "::" in key else key
        can = canonical_folder(k, env.home)
        if covers(can, target):
            rows.append((k, can, pol))
    if not rows:
        return None, {}
    rows.sort(key=lambda r: (-len(r[1]), 0 if r[0] == r[1] else 1, r[0]))
    k, _, pol = rows[0]
    allowed = [a for a in pol.get("allowedAccountSlugs") or [] if isinstance(a, str)]
    dflt = pol.get("defaultAccountSlug") if isinstance(pol.get("defaultAccountSlug"), str) else None
    return k, {"defaultAccountSlug": dflt, "allowedAccountSlugs": allowed}


def accounts(env):
    """Every account, in the app's order: `default` (~/.claude) first, then ~/.claude-remote/accounts/*."""
    out = ["default"]
    try:
        out += sorted(n for n in os.listdir(os.path.join(env.cr, "accounts"))
                      if not n.startswith(".") and os.path.isdir(os.path.join(env.cr, "accounts", n)))
    except OSError:
        pass
    return out


def account_dir(env, slug):
    return os.path.join(env.home, ".claude") if slug in (None, "", "default") else os.path.join(env.cr, "accounts", slug)


def logged_in(env, slug):
    """Whether the account has a credentials file. Only its size is looked at (`test -s`), never its content."""
    try:
        return os.path.getsize(os.path.join(account_dir(env, slug), ".credentials.json")) > 0
    except OSError:
        return False


def usage_of(env, slug, now, usage=None):
    """{fiveHour, sevenDay, known, ok, why}: unknown (no cache or older than 15 min) counts as usable, said aloud."""
    u = (usage if usage is not None else J.read_usage(env, now)).get(slug or "default") or {}
    age = u.get("ageS")
    known = bool(u) and age is not None and age <= USAGE_STALE_S
    five, seven = u.get("fiveHour"), u.get("sevenDay")
    why = None
    if known:
        if isinstance(seven, (int, float)) and seven >= SEVEN_DAY_MAX:
            why = f"má vyčerpaný týden ({round(seven)} %)"
        elif isinstance(five, (int, float)) and five >= FIVE_HOUR_MAX:
            why = f"má vyčerpaných 5 hodin ({round(five)} %)"
    return {"fiveHour": five if known else None, "sevenDay": seven if known else None, "known": known,
            "ok": why is None, "why": why}


def usage_cz(u):
    if not u["known"]:
        return "čerpání neznámé"
    f = "?" if u["fiveHour"] is None else f"{round(u['fiveHour'])} %"
    s = "?" if u["sevenDay"] is None else f"{round(u['sevenDay'])} %"
    return f"5 h {f}, týden {s}"


def pick_account(env, folder, now, usage=None):
    """The account a new session in `folder` starts on (ClaudeAccount: allowedAccountsFor / defaultAccountFor):
    {account, policyDefault, allowed, policyKey, usage, ok, alternative, why}.
    `account` is the candidate (the policy's default when offerable, else the first offerable); `ok` = it is logged in
    and under the limits; else `alternative` = the allowed one with the lowest load (week weight 2, 5 h weight 1), or
    None: then Lukáš is asked, the policy is never crossed silently."""
    usage = usage if usage is not None else J.read_usage(env, now)
    every = accounts(env)
    key, pol = policy_for(env, folder)
    allow = [a for a in every if a in pol.get("allowedAccountSlugs", [])]
    offer = allow or every
    dflt = pol.get("defaultAccountSlug")
    cand = dflt if dflt in offer else offer[0]

    def suit(a):
        if not logged_in(env, a):
            return {"ok": False, "why": "není přihlášený", "known": False, "fiveHour": None, "sevenDay": None}
        return usage_of(env, a, now, usage)
    cu = suit(cand)
    alt = None
    if not cu["ok"]:
        best = None
        for a in offer:
            if a == cand:
                continue
            u = suit(a)
            if not u["ok"]:
                continue
            load = 2 * (u["sevenDay"] or 0) + (u["fiveHour"] or 0)
            if best is None or load < best[0]:
                best = (load, a)
        alt = best[1] if best else None
    return {"account": cand, "policyDefault": dflt, "allowed": allow, "policyKey": key, "usage": cu,
            "ok": cu["ok"], "alternative": alt, "why": cu.get("why")}


# ---------------------------------------------------------------------------------------------------------- projects

def _folders_in_json_list(path):
    d = J.read_json(path, [])
    out = []
    if isinstance(d, list):
        for x in d:
            if isinstance(x, dict) and isinstance(x.get("folder"), str):
                out.append(x["folder"])
    return out


def _expand(env, f):
    f = normalize(f)
    if f == "~":
        return env.home
    if f.startswith("~/"):
        return os.path.join(env.home, f[2:])
    if f.startswith("/"):
        return f
    return os.path.join(env.home, f)


def known_projects(env):
    """{absolute folder: name} where a session has been (R5 = 5b): the manifest, closed.jsonl and the folder
    policies. Only existing directories strictly under $HOME, never Jarvis's own folder; $HOME itself (named `~`, as
    the app names it: `claude-server-~-yolo…`) only when a session ran exactly there by the manifest or closed.jsonl
    (lead 2026-11-08), never by a policy key alone."""
    sess = _folders_in_json_list(os.path.join(env.cr, "sessions.json"))
    sess += _folders_in_json_list(os.path.join(env.cr, "sessions.restore.json"))
    for line in J.read_tail(os.path.join(env.cr, "closed.jsonl"), 256 * 1024):
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if isinstance(d, dict):
            for k in ("cwd", "folder"):
                if isinstance(d.get(k), str):
                    sess.append(d[k])
    pol = J.read_json(os.path.join(env.cr, "folder-policies.json"), {}) or {}
    raw = [(f, True) for f in sess]
    if isinstance(pol, dict):
        raw += [(k.split("::", 1)[1] if "::" in k else k, False) for k in pol if isinstance(k, str)]
    home = os.path.realpath(env.home)
    banned = {os.path.join(home, "jarvis")}
    out = {}
    for f, from_session in raw:
        p = _expand(env, f)
        try:
            rp = os.path.realpath(p)
        except (OSError, ValueError):
            continue
        if rp == home and from_session:
            out[rp] = "~"
            continue
        if not rp.startswith(home + "/") or banned_folder(rp, banned) or not os.path.isdir(rp):
            continue
        out[rp] = os.path.basename(rp)
    return out


def banned_folder(rp, banned):
    """rev-f4 L6: Jarvis's own project and everything under it (a session there would load his CLAUDE.md and gate)."""
    return any(rp == b or rp.startswith(b + "/") for b in banned)


def project_label(env, folder):
    """`~` for $HOME, else the folder's name: what the app puts in the tmux name and what Jarvis says."""
    return "~" if os.path.realpath(folder) == os.path.realpath(env.home) else os.path.basename(folder)


def resolve_project(env, q):
    """(absolute folder, None) or (None, reason). A path (with `/` or `~`) must be a known project exactly; a word
    matches a known project's folder name (any case, `.` = `_`)."""
    known = known_projects(env)
    q = (q or "").strip()
    if not q:
        return None, "chybí projekt"
    if "/" in q or q.startswith("~"):
        rp = os.path.realpath(_expand(env, q))
        if rp in known:
            return rp, None
        return None, f"„{J.safe_name(q, 80)}“ není známý projekt (otevírám jen složky, kde už nějaká session byla)"
    if q == "~":
        rp = os.path.realpath(env.home)
        return (rp, None) if rp in known else (None, "domovská složka ~ není známý projekt (žádná session tam neběžela)")
    want = sanitize(q).lower()
    hits = sorted(f for f, b in known.items() if sanitize(b).lower() == want)
    if len(hits) == 1:
        return hits[0], None
    if len(hits) > 1:
        return None, f"„{J.safe_name(q)}“ je víc složek: " + ", ".join("~/" + os.path.relpath(h, os.path.realpath(env.home)) for h in hits) + "; řekni cestu"
    near = sorted({b for b in known.values() if want in b.lower()})[:8]
    return None, (f"„{J.safe_name(q)}“ není známý projekt" + (f" (myslíš: {', '.join(near)}?)" if near else "")
                  + "; otevírám jen složky, kde už nějaká session byla")


def named_projects(env, prompt):
    """Known projects whose folder name the prompt names as a whole word (any case). Folders, never the text."""
    low = prompt.lower()
    out = []
    for f, b in known_projects(env).items():
        if len(b) >= 3 and re.search(r"(?<![\w-])" + re.escape(b.lower()) + r"(?![\w-])", low):
            out.append(f)
    return sorted(out)[:20]


def named_closed(env, prompt):
    """Names in closed.jsonl (the last 50) the prompt names by their exact name, alias or short name."""
    low = prompt.lower()
    out = set()
    for c in J.read_closed(env, 50):
        n = c["name"]
        _, alias, _ = J.parse_name(n)
        for key in {n, alias, J.short_name(n)}:
            if key and len(key) >= 3 and re.search(r"(?<![\w-])" + re.escape(key.lower()) + r"(?![\w-])", low):
                out.add(n)
                break
    return sorted(out)[:20]


# ---------------------------------------------------------------------------------------------------------- facts

def parse_facts(out):
    """`cr-session facts` -> ({key: [values]}, token, complete)."""
    facts, token, end = {}, None, False
    for line in (out or "").splitlines():
        p = line.split()
        if not p:
            continue
        if p[0] == "FACT" and len(p) >= 3:
            facts[p[1]] = p[2:]
        elif p[0] == "TOKEN" and len(p) == 2:
            token = p[1]
        elif p[0] == "__CR_FACTS_END__":
            end = True
    return facts, token, end


def _f(facts, key, i, default="-"):
    v = facts.get(key) or []
    return v[i] if len(v) > i else default


def cr_blocks(facts):
    """The same verdict as cr-session's own `cr_blocks` awk: what `close` refuses without --force."""
    if _f(facts, "alive", 0, "0") == "0":
        return []
    out = []
    st, ev, age, bg = _f(facts, "status", 0), _f(facts, "event", 0), _f(facts, "event", 1), _f(facts, "event", 2, "0")
    young = age.isdigit() and int(age) < EVENT_YOUNG_S

    def add(c):
        if c not in out:
            out.append(c)
    if st == "busy":
        add("BUSY")
    if st == "waiting":
        add("WAITING")
    if st in ("", "-") and young:
        if ev == "work":
            add("BUSY")
        if ev in ("ask", "perm"):
            add("WAITING")
    if ev == "stop" and bg == "1" and young:
        add("BACKGROUND")
    if _f(facts, "draft", 0) == "1":
        add("DRAFT")
    c0 = _f(facts, "clients", 0, "0")
    if c0.isdigit() and int(c0) > 0 and _f(facts, "clients", 1) != "0":
        add("VIEWER")
    p0 = _f(facts, "procs", 0, "0")
    if p0.isdigit() and int(p0) > 0:
        add("PROCESSES")
    return out


def _n(v):
    return int(v) if isinstance(v, str) and v.isdigit() else None


def facts_report(facts, extra_blocks=()):
    """{alive, blocks: [code], block: [cz], warn: [cz], info: [cz], restorable, dirty, sentence_bits} in Czech."""
    alive = _f(facts, "alive", 0, "0") == "1"
    blocks = cr_blocks(facts) + [b for b in extra_blocks if b]
    block_cz = []
    for b in blocks:
        t = BLOCK_CZ.get(b, b)
        if b == "PROCESSES":
            names = _f(facts, "procs", 1)
            if names not in ("-", ""):
                t += f" ({J.safe_name(names, 60)})"
        block_cz.append(t)
    warn, info = [], []
    dirty, ahead, stash = _n(_f(facts, "git", 0)), _n(_f(facts, "git", 1)), _n(_f(facts, "git", 2))
    if dirty:
        warn.append(f"{dirty} {plural_cz(dirty, 'necommitnutý soubor', 'necommitnuté soubory', 'necommitnutých souborů')}")
    if ahead:
        warn.append(f"{ahead} {plural_cz(ahead, 'nepushnutý commit', 'nepushnuté commity', 'nepushnutých commitů')}")
    if stash:
        warn.append(f"{stash} {plural_cz(stash, 'stash', 'stashe', 'stashů')}")
    age = _n(_f(facts, "event", 1))
    if age is not None and age < RECENT_ACTIVITY_S:
        warn.append(f"naposledy aktivní před {J.minutes_cz(age * 1000)}")
    tr = _f(facts, "transcript", 0)
    if tr == "1":
        info.append("konverzace se neztratí, jde vrátit")
    elif tr == "0":
        warn.append("konverzaci nenacházím, nepůjde vrátit")
    elif alive:
        info.append("claude v ní neběží")
    return {"alive": alive, "blocks": blocks, "block": block_cz, "warn": warn, "info": info,
            "restorable": tr == "1", "dirty": dirty}


def close_question(short, rep):
    """The one sentence Jarvis asks with (§15.6): „Zavřít pdf-sloupce? Má 3 necommitnuté soubory, konverzaci si
    pamatuju.“"""
    bits = []
    if rep["block"]:
        bits.append("pozor, " + " a ".join(rep["block"]))
    if rep["warn"]:
        bits.append("má " + ", ".join(rep["warn"]) if not rep["warn"][0].startswith(("naposledy", "konverzaci"))
                    else ", ".join(rep["warn"]))
    bits.append("konverzaci si pamatuju" if rep["restorable"] else "konverzaci si nepamatuju")
    s = ", ".join(bits)
    return f"Zavřít {short}? {s[0].upper() + s[1:]}."


# ---------------------------------------------------------------------------------------------------------- cr-session

class CrSession:
    """The one door to the server path. `run` is a seam for tests (an atrapa script in a temp HOME is the default
    there too: it is the real subprocess path, only the script is fake)."""

    def __init__(self, env, run=None):
        self.env = env
        self.path = env.environ.get("JARVIS_CR_SESSION") or os.path.join(env.cr, "bin", "cr-session")
        self._run = run

    def call(self, *args, timeout=60):
        if self._run is not None:
            return self._run(list(args))
        e = scrubbed_env(self.env.environ)
        e.setdefault("HOME", self.env.home)
        try:
            p = subprocess.run([self.path, *args], capture_output=True, text=True, timeout=timeout, env=e,
                               stdin=subprocess.DEVNULL)
        except subprocess.TimeoutExpired:
            return 124, ""
        except OSError as ex:
            return 127, str(ex)
        return p.returncode, p.stdout

    def check(self):
        """None, or why cr-session cannot be used (missing, or a version this wrapper does not know)."""
        if self._run is None and not os.access(self.path, os.X_OK):
            return "chybí ~/.claude-remote/bin/cr-session: připoj aplikaci k serveru, ať ho doinstaluje"
        rc, out = self.call("version", timeout=5)
        v = (out or "").strip().splitlines()[0].strip() if (out or "").strip() else ""
        if rc != 0 or v != CR_SESSION_VERSION:
            return (f"cr-session má jinou verzi ({J.safe_name(v, 30) or 'neznámou'}), znám jen {CR_SESSION_VERSION}: "
                    f"aktualizuj aplikaci a připoj ji k serveru")
        return None

    def facts(self, name):
        rc, out = self.call("facts", name, timeout=12)
        f, tok, end = parse_facts(out)
        return f, tok, end


# ---------------------------------------------------------------------------------------------------------- store

class Ops:
    def __init__(self, env, mail=None, cr=None, spawn=None):
        import jarvismail as M
        self.env = env
        self.M = M
        self.mail = mail or M.Mail(env)
        self.cr = cr or CrSession(env)
        self.spawn = spawn or self._spawn
        self.dir = env.path("ops")

    # ------------------------------------------------------------------ files

    def ensure(self):
        os.makedirs(self.dir, mode=0o700, exist_ok=True)

    @contextlib.contextmanager
    def lock(self):
        self.ensure()
        with open(os.path.join(self.dir, "ops.lock"), "a+") as f:
            fcntl.flock(f.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(f.fileno(), fcntl.LOCK_UN)

    def all(self):
        d = J.read_json(os.path.join(self.dir, "proposals.json"), [])
        return [r for r in d if isinstance(r, dict) and ID_RE.fullmatch(str(r.get("id"))) and r.get("kind") in KINDS
                and r.get("state") in STATES] if isinstance(d, list) else []

    def get(self, xid):
        return next((r for r in self.all() if r["id"] == xid), None)

    def _save(self, recs, now):
        keep = [r for r in recs if r["state"] not in FINAL or now - J._int(r.get("updatedAt") or 0) < KEEP_FINAL_MS]
        J.atomic_write(os.path.join(self.dir, "proposals.json"), json.dumps(keep, ensure_ascii=False))

    def put(self, rec):
        """Under lock()."""
        recs = [r for r in self.all() if r["id"] != rec["id"]] + [rec]
        self._save(recs, J._int(rec.get("updatedAt")) or self.env.now_ms())

    def log(self, ev, **kw):
        """Under lock()."""
        p = os.path.join(self.dir, "ledger.jsonl")
        try:
            if os.path.getsize(p) > LEDGER_MAX:
                keep = J.read_tail(p, LEDGER_MAX // 2)
                J.atomic_write(p, "".join(l + "\n" for l in keep))
        except OSError:
            pass
        d = dict({"at": self.env.now_ms(), "ev": ev}, **{k: v for k, v in kw.items() if v is not None})
        fd = os.open(p, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        try:
            os.write(fd, (json.dumps(d, ensure_ascii=False) + "\n").encode("utf-8"))
        finally:
            os.close(fd)

    def ledger(self, since_ms=0, max_bytes=1024 * 1024):
        out = []
        for line in J.read_tail(os.path.join(self.dir, "ledger.jsonl"), max_bytes):
            try:
                d = json.loads(line)
            except ValueError:
                continue
            if isinstance(d, dict) and J._int(d.get("at")) and d["at"] > since_ms:
                out.append(d)
        return out

    def transition(self, rec, state, ev=None, **kw):
        """Under lock()."""
        rec.update(state=state, updatedAt=self.env.now_ms(), **{k: v for k, v in kw.items() if v is not None})
        self.put(rec)
        self.log(ev or state, id=rec["id"], kind=rec["kind"], target=rec.get("target"), **kw)

    # ------------------------------------------------------------------ who

    def caller(self, now):
        """'jarvis' (his own pane, a fresh prompt of Lukáš's), 'lukas' (his shell: no Claude session), else Refused."""
        me = J.read_self(self.env)
        name, why = self.mail.caller_session()
        if not why and me and name == me:
            lp = self.mail.last_prompt() or {}
            at = J._int(lp.get("at"))
            if not lp.get("id") or not at or not (-60000 <= now - at <= PROMPT_FRESH_MS):
                # F5c: after a wake Jarvis may PROPOSE (the wake record has no named project or session and no intent,
                # so cr-sessions turns everything into a proposal that waits for Lukáš's "ano")
                if not self.mail.wake_prompt(now):
                    raise Refused("chybí čerstvý Lukášův pokyn (do 10 minut); zeptej se ho")
            return "jarvis"
        if not self.env.environ.get("CLAUDECODE"):
            return "lukas"
        raise Refused("otevírat a zavírat sessions smí jen Jarvis na Lukášův pokyn, nebo Lukáš ze svého shellu")

    def off(self):
        return os.path.exists(self.env.path("off"))

    def precheck(self):
        if self.off():
            raise Refused("Jarvis je vypnutý (~/.claude-remote/jarvis/off); zapne ho Lukáš: cr-mail on")
        why = self.cr.check()
        if why:
            raise Refused(why)

    def per_prompt(self, prompt_id, now):
        if not prompt_id:
            return
        if self.M.WAKE_RE.fullmatch(prompt_id):
            if self.mail.wake_proposals(prompt_id, now) >= self.M.WAKE_PROPOSALS:
                raise Refused("po probuzení nejvýš jeden návrh; další až po Lukášově odpovědi")
            return
        n = len({e.get("id") for e in self.ledger(now - 2 * PROMPT_FRESH_MS) if e.get("prompt") == prompt_id
                 and e.get("ev") in ("proposed", "approved")})
        if n >= PER_PROMPT:
            raise Refused(f"na jeden Lukášův pokyn nejvýš {PER_PROMPT} akce se sessions")

    def close_targets(self, prompt_id):
        """How many sessions the closes of this prompt already cover (proposed, approved, run): M1."""
        if not prompt_id:
            return 0
        return sum(len(r.get("targets") or []) for r in self.all()
                   if r["kind"] == "close" and r.get("prompt") == prompt_id and r["state"] not in ("dropped", "rejected", "expired"))

    def closes_today(self, now):
        lt = time.localtime(now / 1000)
        midnight = int(time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, 0, 0, 0, 0, 0, -1)) * 1000)
        # a reservation (`closing`, written under the lock before cr-session runs) counts as a close: an attempt that
        # failed afterwards still used its slot (conservative), and two runners never both see 19
        return len({(e.get("id"), e.get("name")) for e in self.ledger(midnight, 4 * 1024 * 1024)
                    if e.get("ev") in ("closing", "closed") and e.get("by") == "jarvis"})

    # ------------------------------------------------------------------ sessions

    def sessions(self, now):
        import jarviswatch as JW
        return JW.sessions_now(self.env, now)

    def resolve(self, q, sessions):
        """(name, None) or (None, reason): an exact name, alias or short name; close misses are offered, never chosen."""
        hits = [q] if q in sessions else []
        for key in ("alias", "short"):
            if not hits:
                hits = [n for n, s in sessions.items() if isinstance(s, dict) and s.get(key) == q]
        if not hits:
            near = sorted(n for n in sessions if q and q in n)[:6]
            return None, f"žádná session přesně „{J.safe_name(q)}“" + (f" (myslíš: {', '.join(near)}?)" if near else "")
        if len(hits) > 1:
            return None, f"„{J.safe_name(q)}“ je nejednoznačné: " + ", ".join(sorted(hits)[:6])
        return hits[0], None

    def extra_blocks(self, name):
        """Our own BLOKs (§15.6): an open task of Jarvis's for the session, or mail to it without a reply."""
        out = []
        try:
            import cr_task
            if any(t["state"] == "open" and t.get("to") == name for t in cr_task.fold(cr_task.read_events(self.env)).values()):
                out.append("TASK")
        except Exception:  # noqa: BLE001  unreadable = unknown, and the cr-session facts still decide the rest
            pass
        if any(r.get("to") == name and r["state"] in ("queued", "delivering", "delivered", "uncertain")
               for r in self.mail.all()):
            out.append("MAIL")
        return out

    def check_close(self, name):
        """(report dict, facts, token). Raises Refused for names Jarvis never closes."""
        if not name.startswith(J.SERVER_PREFIX):
            raise Refused(f"{J.safe_name(name, 80)} není claude-server-* session")
        if name == J.read_self(self.env):
            raise Refused("sám sebe nezavírám")
        if high_impact(name):
            raise Refused(f"{J.short_name(name)} je PROD/DB-/deploy/Proxmox session, tu nikdy nezavírám")
        facts, token, end = self.cr.facts(name)
        if not end or not token:
            raise Refused("cr-session facts neodpověděl celý; zkus to za chvíli znovu")
        rep = facts_report(facts, self.extra_blocks(name))
        if not rep["alive"]:
            raise Refused(f"{J.short_name(name)} v tmux neběží")
        rep["name"], rep["short"] = name, J.short_name(name)
        rep["hash"] = token.split(".", 1)[1] if "." in token else token
        rep["question"] = close_question(rep["short"], rep)
        return rep, facts, token

    # ------------------------------------------------------------------ proposals

    def propose(self, kind, target, title, detail, args, prompt_id, now, targets=None):
        rec = {"v": 1, "id": new_id(now), "kind": kind, "target": target, "targets": targets or [target],
               "title": title, "detail": detail, "created": now, "expires": now + PROPOSAL_MS, "state": "proposed",
               "prompt": prompt_id, "args": args, "updatedAt": now, "result": None}
        if prompt_id and self.M.WAKE_RE.fullmatch(prompt_id) and not self.mail.claim_wake_proposal(prompt_id):
            raise Refused("po probuzení nejvýš jeden návrh; další až po Lukášově odpovědi")  # rev-f5c L4
        with self.lock():
            self.put(rec)
            self.log("proposed", id=rec["id"], kind=kind, target=target, prompt=prompt_id)
        return rec

    def proposals(self, now):
        """Pending proposals, mail and actions, in the contract shape (oldest first)."""
        out = []
        for r in self.mail.all():
            created = J._int(r.get("created")) or 0
            if r["state"] == "proposed" and 0 <= now - created <= self.M.PROMPT_FRESH_MS:
                text = self.M.data_text(self.mail.text_of(r["id"]), self.M.TEXT_MAX)
                out.append({"id": r["id"], "kind": "mail", "target": r["to"],
                            "title": f"Poslat zprávu do {J.short_name(r['to'])}.", "detail": text or "",
                            "created": created, "expires": created + self.M.PROMPT_FRESH_MS})
        for r in self.all():
            if r["state"] == "proposed" and now < J._int(r.get("expires")):
                out.append({"id": r["id"], "kind": r["kind"], "target": r["target"], "targets": r.get("targets"),
                            "title": r["title"], "detail": r["detail"], "created": r["created"],
                            "expires": r["expires"]})
        out.sort(key=lambda p: p["created"])
        return out

    def decide(self, xid, ok, by, run=True):
        """Lukáš's yes or no (the app's card, or his next prompt). Returns (record, message) or raises Refused."""
        now = self.env.now_ms()
        with self.lock():
            rec = self.get(xid)
            if not rec:
                raise Refused(f"návrh {xid} neexistuje")
            if rec["state"] != "proposed":
                raise Refused(f"návrh {xid} je {rec['state']}, rozhodnout jde jen o čekající")
            if now >= J._int(rec.get("expires")):
                self.transition(rec, "expired")
                raise Refused(f"návrh {xid} je starší než 10 minut, propadl")
            if not ok:
                self.transition(rec, "rejected", decidedBy=by)
                return rec, f"{xid} zahozen"
            self.transition(rec, "approved", decidedBy=by)
        if not run:
            return rec, f"{xid} schválen"
        return self.run(xid)

    def decide_on_prompt(self, prompt, prev_id, now, single=False, cur_id=None, transcript=None):
        """Lukáš's next prompt decides every action proposal (the mail's MED4 rule): approved only when it was made
        during the PREVIOUS prompt, within 10 minutes, and this prompt is a short yes or names its id; anything else
        drops it. Approved ones run in a detached process, so the hook stays fast."""
        # M1: a bare yes (the whole prompt) approves only when exactly one proposal, mail or action, waits
        short_yes = single and isinstance(prompt, str) and bool(self.M.APPROVE.match(prompt))
        go = []
        if not os.path.exists(os.path.join(self.dir, "proposals.json")):
            return go  # nothing was ever proposed: every prompt of Lukáš's must not create files here
        with self.lock():
            props = [r for r in self.all() if r["state"] == "proposed"]
            named = {r["id"] for r in props if isinstance(prompt, str) and r["id"] in prompt}
            for rec in props:
                ok = (isinstance(prompt, str) and prev_id is not None and rec.get("prompt") == prev_id
                      and 0 <= now - J._int(rec.get("created")) <= PROPOSAL_MS
                      and (rec["id"] in named if named else short_yes))
                if ok and rec["kind"] == "approve" and not said_verbatim(rec.get("title"), transcript, prompt):
                    # F5e (lead): Lukáš answered what Jarvis SAID; an approval goes only when Jarvis's last answer
                    # held the code's sentence word for word. Otherwise it waits (re-keyed to this prompt) and the
                    # delta tells Jarvis to read it again
                    rec["prompt"] = cur_id
                    first = not rec.get("unreadAt")
                    rec["unreadAt"] = rec.get("unreadAt") or now
                    # the first refusal is also told to Lukáš (jarvis-d, once per proposal); later ones only to Jarvis
                    self.transition(rec, "proposed", ev="unread" if first else "unread-again")
                elif ok:
                    self.transition(rec, "approved", decidedBy="prompt")
                    go.append(rec["id"])
                else:
                    self.transition(rec, "dropped", reason="no-yes")
        for xid in go:
            try:
                self.spawn(xid)
            except Exception as e:  # noqa: BLE001  jarvis-d's sweep runs it when the spawn failed
                with self.lock():
                    self.log("spawn-failed", id=xid, reason=f"{type(e).__name__}"[:80])
        return go

    def _spawn(self, xid):
        me = os.path.join(os.path.dirname(os.path.realpath(__file__)), "cr_jarvis.py")
        # the runner sees the same HOME / state dir as this process (env.environ, not os.environ: a test's temp HOME
        # must never send it to the live state)
        e = scrubbed_env(self.env.environ)
        self._popen([sys.executable, "-I", me, "run", xid], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True, close_fds=True, env=e)

    @staticmethod
    def _popen(*a, **kw):
        subprocess.Popen(*a, **kw)

    def sweep(self, now):
        """jarvis-d: proposals past their 10 minutes expire; an approved one nobody started for 30 s gets the detached
        runner (rev-f4 M2: never run in the daemon's tick, an open waits seconds and the alerts and mail would wait
        with it), at most once per 30 s."""
        late = []
        if not os.path.exists(os.path.join(self.dir, "proposals.json")):
            return late  # nothing was ever proposed: no ops/ directory from the daemon's tick
        with self.lock():
            for rec in self.all():
                if rec["state"] == "proposed" and now >= J._int(rec.get("expires")):
                    self.transition(rec, "expired")
                elif rec["state"] == "approved" and now - J._int(rec.get("updatedAt")) > RUN_STALE_MS \
                        and now - J._int(rec.get("spawnedAt") or 0) > RUN_STALE_MS:
                    rec["spawnedAt"] = now
                    self.put(rec)
                    late.append(rec["id"])
                elif rec["state"] == "running" and now - J._int(rec.get("updatedAt")) > RUNNING_DEAD_MS:
                    self.transition(rec, "failed", message="přerušeno: proces, který akci prováděl, skončil; "
                                                           "ověř stav přes cr-sessions show nebo closed")
        for xid in late:
            try:
                self.spawn(xid)
            except Exception as e:  # noqa: BLE001
                with self.lock():
                    self.log("spawn-failed", id=xid, reason=f"{type(e).__name__}"[:80])
        return late

    # ------------------------------------------------------------------ running

    def run(self, xid, wait=APPROVED_WAIT_S):
        """Execute an APPROVED action once (approved -> running under the lock). Returns (record, message)."""
        with self.lock():
            rec = self.get(xid)
            if not rec:
                raise Refused(f"návrh {xid} neexistuje")
            if rec["state"] != "approved":
                raise Refused(f"návrh {xid} je {rec['state']}, provést jde jen schválený")
            self.transition(rec, "running", ev="started", prompt=rec.get("prompt"))
        try:
            if self.off():
                raise Refused("Jarvis je vypnutý (off)")
            why = None if rec["kind"] == "approve" else self.cr.check()  # F5e answers a dialog, no cr-session
            if why:
                raise Refused(why)
            ok, msg, result = {"open": self._do_open, "close": self._do_close_all, "restore": self._do_restore,
                               "approve": self._do_approve}[rec["kind"]](rec, wait, approved=True)
        except Refused as e:
            ok, msg, result = False, str(e), None
        except Exception as e:  # noqa: BLE001  a crash must end the record, never leave it running
            ok, msg, result = False, f"vnitřní chyba ({type(e).__name__})", None
        with self.lock():
            r = self.get(xid) or rec
            self.transition(r, "done" if ok else "failed", ev="done" if ok else "failed", result=result,
                            message=msg[:400])
        return r, msg

    def start_now(self, kind, target, title, detail, args, prompt_id, by, wait):
        """An action that needs no confirmation: recorded like an approved proposal and run at once."""
        now = self.env.now_ms()
        rec = {"v": 1, "id": new_id(now), "kind": kind, "target": target, "targets": args.get("names") or [target],
               "title": title, "detail": detail, "created": now, "expires": now, "state": "approved", "prompt": prompt_id,
               "args": args, "updatedAt": now, "decidedBy": by, "result": None}
        with self.lock():
            self.put(rec)
            self.log("approved", id=rec["id"], kind=kind, target=target, decidedBy=by, prompt=prompt_id)
        return self.run(rec["id"], wait=wait)

    # ---- approve (F5e)

    def _do_approve(self, rec, wait, approved):
        """The approved answer of a session's dialog, by the code (jarvisapprove.execute: the same fingerprint, the
        exact keys, never a persistent choice). Runs in the detached runner, never in Jarvis's turn."""
        import jarvisapprove as JAP
        ok, msg = JAP.execute(self.mail, rec["args"]["name"], rec["args"])
        return ok, msg, {"status": "answered" if ok else "not-answered"}

    # ---- open

    def _do_open(self, rec, wait, approved):
        a = rec["args"]
        args = ["open", "--name", a["name"], "--folder", a["folder"], "--account", a["account"], "--model",
                a.get("model") or "DEFAULT", "--mode", "yolo", "--by", "jarvis", "--wait", str(wait)]
        rc, out = self.cr.call(*args, timeout=wait + 12)
        first = (out or "").strip().splitlines()[0] if (out or "").strip() else ""
        word = first.split(" ", 1)[0]
        short = J.short_name(a["name"])
        if word not in ("OPENED", "OPENED_NO_REGISTRY", "OPENED_LOGIN_REQUIRED"):
            return False, open_failure(word, first, short), {"status": word or f"rc={rc}"}
        with self.lock():
            self.log("opened", id=rec["id"], name=a["name"], folder=a["folder"], account=a["account"], by="jarvis")
        msg = f"Otevřel jsem {short} v {project_label(self.env, a['folder'])} na účtu {J.account_short(None if a['account'] == 'default' else a['account'])}."
        if word == "OPENED_LOGIN_REQUIRED":
            msg += " Účet chce přihlásit (v aplikaci, Účty)."
        elif word == "OPENED_NO_REGISTRY":
            msg += " Claude ještě nenaběhl, nejspíš čeká na potvrzení důvěry ke složce."
        result = {"status": word, "name": a["name"]}
        if a.get("task"):
            mrec, why = self.mail.send_approved(a["name"], a["task"], by=f"op:{rec['id']}")
            if why:
                msg += f" Zadání se poslat nepodařilo: {why}."
            else:
                result["mail"] = mrec["id"]
                msg += (f" Zadání ({mrec['id']}) čeká ve frontě, doručí se, až session naběhne"
                        + (" a potvrdí se důvěra ke složce." if word != "OPENED" else "."))
        return True, msg, result

    # ---- close

    def _do_close_all(self, rec, wait, approved):
        names = rec["args"].get("names") or [rec["target"]]
        done, bad = [], []
        for n in names:
            ok, msg = self._do_close(n, rec, approved)
            (done if ok else bad).append((n, msg))
        msg = " ".join(m for _, m in done + bad)
        return (not bad), msg, {"closed": [n for n, _ in done], "failed": [n for n, _ in bad]}

    def _do_close(self, name, rec, approved):
        short = J.short_name(name)
        try:
            rep, facts, token = self.check_close(name)
        except Refused as e:
            return False, f"{short}: {e}."
        cr_now = [b for b in rep["blocks"] if b in CR_BLOCKS]
        at_ask = (rec["args"].get("facts") or {}).get(name) or {}
        if cr_now:
            # never --force: that one is Lukáš's own, from his shell
            return False, (f"{short} nezavřu, {' a '.join(BLOCK_CZ[b] for b in cr_now)}. Až to skončí, řekni znovu, "
                           f"nebo ji zavři sám: cr-session close {name} --token … --force")
        # our own BLOKs (a task, mail): Lukáš's yes covers the ones he was told about, never a new one
        new = [b for b in rep["blocks"] if b not in (at_ask.get("blocks") or [])]
        if new:
            return False, f"{short} nezavřu: {', '.join(BLOCK_CZ[b] for b in new)}."
        if not self.same_facts(at_ask, rep):
            # §15.6 (rev-f4 L2: always): something changed between the question and the yes (it started, someone
            # opened it, git moved), or the question's facts are unknown
            return False, f"{short} se od otázky změnila ({'; '.join(rep['warn'] + rep['info']) or 'stav'}), zeptej se znovu."
        # rev-f4 L3: the day's count and its reservation under one lock, so parallel runners never pass 20 together
        with self.lock():
            if self.closes_today(self.env.now_ms()) >= CLOSE_PER_DAY:
                return False, f"{short}: dnes už {CLOSE_PER_DAY} zavření, víc ne."
            self.log("closing", id=rec["id"], name=name, by="jarvis" if rec.get("decidedBy") != "lukas" else "lukas-shell")
        by = "jarvis" if rec.get("decidedBy") != "lukas" else "lukas-shell"
        rc, out = self.cr.call("close", name, "--token", token, "--by", by,
                               "--reason", f"{rec['id']} {rec.get('decidedBy') or ''}".strip()[:120], timeout=15)
        first = (out or "").strip().splitlines()[0] if (out or "").strip() else ""
        if first != "CLOSED":
            return False, f"{short}: {close_failure(first)}."
        with self.lock():
            self.log("closed", id=rec["id"], name=name, by=by, restorable=rep["restorable"])
        return True, f"Zavřel jsem {short}." + ("" if rep["restorable"] else " Konverzaci jsem nenašel, vrátit nepůjde.")

    # ---- restore

    @staticmethod
    def same_facts(at_ask, rep):
        """The facts Lukáš said yes to are the facts now (the token's hash, no timestamps). Always checked (L2)."""
        return bool(at_ask.get("hash")) and at_ask["hash"] == rep["hash"]

    def _do_restore(self, rec, wait, approved):
        a = rec["args"]
        args = ["restore", a["key"], "--by", "jarvis", "--wait", str(wait)]
        if a.get("newName"):
            args[2:2] = ["--name", a["newName"]]
        rc, out = self.cr.call(*args, timeout=wait + 12)
        line = next((l for l in (out or "").splitlines() if l.startswith("CR_RESTORE ")), (out or "").strip())
        parts = line.split()
        if parts and parts[0] == "CR_RESTORE":
            parts = parts[1:]
        word = parts[0] if parts else ""
        name = a.get("newName") or a["name"]
        short = J.short_name(name)
        if word in ("RESTORED", "RESTORED_NO_REGISTRY", "OPENED_LOGIN_REQUIRED"):
            with self.lock():
                self.log("restored", id=rec["id"], name=name, key=a["key"], by="jarvis")
            msg = f"Vrátil jsem {short}."
            if word == "OPENED_LOGIN_REQUIRED":
                msg += " Účet chce přihlásit (v aplikaci, Účty)."
            elif word == "RESTORED_NO_REGISTRY":
                msg += " Claude ještě nenaběhl."
            return True, msg, {"status": word, "name": name}
        if word == "EXISTS" and len(parts) > 1 and not a.get("newName"):
            sug = parts[1]
            p = self.propose("restore", sug, f"Vrátit {J.short_name(a['name'])} jako {J.short_name(sug)}.",
                             f"Jméno {J.short_name(a['name'])} už nese jiná běžící session. "
                             + (a.get("detail") or ""), dict(a, newName=sug),
                             # rev-f4 L1: Lukáš has not seen it yet, so no "ano" may approve it; only its id or the card
                             None, self.env.now_ms())
            return False, (f"Jméno {J.short_name(a['name'])} je obsazené. Navrhuju {p['id']}: vrátit ji jako "
                           f"{J.short_name(sug)}. Zeptej se Lukáše: „Vrátit ji jako {J.short_name(sug)}? Ano?“"), \
                {"status": "EXISTS", "proposal": p["id"]}
        return False, restore_failure(word, parts, short), {"status": word or f"rc={rc}"}


def open_failure(word, line, short):
    return {
        "EXISTS": f"{short} už existuje, zkus to znovu (vyberu jiné jméno)",
        "PENDING_SELF_HEAL": f"jméno {short} čeká na obnovu driftem, zkus to za minutu",
        "RUNNING_ELSEWHERE": "ta konverzace už běží jinde",
        "REFUSED_OFF": "Jarvis je vypnutý (off)",
        "REFUSED_GUARD": "tmux socket guard odmítl start (viz ~/.claude-remote/sockheal.log)",
        "INVALID": f"cr-session odmítl zadání ({J.safe_text(line, 80)})",
        "FAILED": f"start se nepovedl ({J.safe_text(line, 80)})",
    }.get(word, f"cr-session odpověděl nečekaně ({J.safe_text(line, 80) or 'nic'})") + "."


def close_failure(line):
    word = line.split(" ", 1)[0]
    if word == "REFUSED_BLOCKED":
        codes = line.split(" ", 1)[1].split(",") if " " in line else []
        return "nezavřu, " + " a ".join(BLOCK_CZ.get(c, c) for c in codes)
    return {"REFUSED_STALE": "stav se mezitím změnil, zeptej se znovu", "REFUSED_SELF": "sám sebe nezavírám",
            "REFUSED_NAME": "tohle není claude-server-* session", "REFUSED_OFF": "Jarvis je vypnutý (off)"}.get(
        word, f"cr-session odpověděl nečekaně ({J.safe_text(line, 80) or 'nic'})")


def restore_failure(word, parts, short):
    arg = parts[1] if len(parts) > 1 else ""
    if word == "RUNNING_ELSEWHERE":
        where = J.short_name(arg) if arg.startswith(J.SERVER_PREFIX) else None
        return f"Ta konverzace už běží" + (f" v {where}." if where else " (mimo claude-server sessions).")
    return {
        "EXISTS": f"Jméno {short} je obsazené.",
        "PENDING_SELF_HEAL": f"{short} čeká na obnovu driftem, zkus to za minutu.",
        "NO_TRANSCRIPT": f"Konverzaci {short} nenacházím, vrátit ji nejde.",
        "LOGGED_OUT": f"Účet {J.account_short(None if arg in ('', 'default') else arg)} není přihlášený; přihlas ho v aplikaci (Účty).",
        "NOT_FOUND": "Takový záznam v closed.jsonl není.",
        "REFUSED_OFF": "Jarvis je vypnutý (off).",
        "REFUSED_GUARD": "tmux socket guard odmítl start.",
    }.get(word, f"Obnova se nepovedla ({J.safe_text(' '.join(parts), 80) or 'nic'}).")


# ---------------------------------------------------------------------------------------------------------- delta

OPS_LINE = {
    "done": "⚙ {message}",
    "failed": "⚠ {id} se nepovedlo: {message}",
    "dropped": "⚙ návrh {id} ({title}) bez Lukášova ano, zahozen",
    "rejected": "⚙ návrh {id} ({title}) Lukáš v aplikaci zamítl",
    "expired": "⚙ návrh {id} ({title}) propadl",
    "approved": "⚙ návrh {id} ({title}): Lukáš řekl ano, provádím",
    # F5e: a bare "ano" to an approval whose sentence Jarvis did not say word for word
    "unread": "⚠ návrh {id}: Lukášovo ano jsem nepřijal. Přečti Lukášovi větu návrhu doslova a zeptej se znovu: "
              "«{title}»",
    "unread-again": "⚠ návrh {id}: Lukášovo ano jsem znovu nepřijal. Přečti Lukášovi větu návrhu doslova: «{title}»",
}


def digest_items(env, since_ms, now):
    """Action news since `since_ms` for cr-digest: results of approved actions (the hook ran them, Jarvis did not see
    them happen), drops and expiries. (items, max_at)."""
    try:
        ops = Ops(env)
        evs = ops.ledger(since_ms)
        recs = {r["id"]: r for r in ops.all()}
    except Exception:  # noqa: BLE001  the delta must never fail on this
        return [], since_ms
    items, max_at = [], since_ms
    for e in evs:
        max_at = max(max_at, e["at"])
        ev = e.get("ev")
        if ev not in OPS_LINE:
            continue
        r = recs.get(e.get("id") or "") or {}
        if ev == "approved" and e.get("decidedBy") not in ("prompt",) and not str(e.get("decidedBy") or "").startswith("app:"):
            continue  # an immediate action: Jarvis ran it himself and saw its result
        if ev in ("done", "failed") and r.get("decidedBy") not in ("prompt",) and not str(r.get("decidedBy") or "").startswith("app:"):
            continue
        items.append({"kind": "ops-" + ev, "id": e.get("id"), "title": J.safe_text(r.get("title"), 120) or r.get("kind") or "?",
                      "message": J.safe_text(e.get("message") or r.get("message"), 300) or "", "at": e["at"]})
    return items, max_at


def ops_line(it):
    return OPS_LINE[it["kind"][4:]].format(id=it["id"], title=it["title"], message=it["message"])


def ops_say(it):
    k = it["kind"][4:]
    if k == "done":
        return it["message"] or "Hotovo."
    if k == "failed":
        return "Nepovedlo se: " + (it["message"] or "nevím proč")
    if k == "approved":
        return "Provádím, co jste schválil."
    if k in ("unread", "unread-again"):
        return "Lukášovo ano jsem nepřijal: přečti mu větu návrhu doslova a zeptej se znovu."
    return "Návrh jsem zahodil." if k in ("dropped", "expired") else "Návrh jste zamítl."


# ---------------------------------------------------------------------------------------------------------- stale

def stale(env, now, days=STALE_DAYS, sessions=None, repo=None):
    """Sessions idle at least `days`, with no uncommitted changes (git status read, never locked), no open task and no
    unanswered mail of Jarvis's; never Jarvis himself or an I7 name. Oldest first. Unknown git = not stale (only
    what can surely go)."""
    import jarviswatch as JW
    sessions = sessions if sessions is not None else JW.sessions_now(env, now)
    repo = repo or JW.Repo(env)
    try:
        import cr_task
        busy_task = {t.get("to") for t in cr_task.fold(cr_task.read_events(env)).values() if t["state"] == "open"}
    except Exception:  # noqa: BLE001
        busy_task = None
    try:
        import jarvismail as M
        mailed = {r.get("to") for r in M.Mail(env).all() if r["state"] in M.UNANSWERED}
    except Exception:  # noqa: BLE001
        mailed = None
    if busy_task is None or mailed is None:
        return []
    out = []
    for name, s in sessions.items():
        if not isinstance(s, dict) or s.get("self") or s.get("state") != "idle" or high_impact(name):
            continue
        last = max(J._int(s.get(k)) or 0 for k in ("stateSince", "lastPromptMs", "lastAssistantMs"))
        if not last or now - last < days * 86400 * 1000:
            continue
        if name in busy_task or name in mailed:
            continue
        cwd = JW.target_cwd(env, name) or s.get("cwd")
        if not cwd:
            continue
        st = repo.git(cwd, "status", "--porcelain")
        if st is None:
            # not a repo at all is fine; a repo git could not read is not
            if repo.git(cwd, "rev-parse", "--is-inside-work-tree") is not None:
                continue
        elif st.strip():
            continue
        out.append({"name": name, "short": J.short_name(name), "idleMs": now - last, "folder": cwd,
                    "title": s.get("title")})
    out.sort(key=lambda r: -r["idleMs"])
    return out


def stale_due(env, now):
    """True when the morning briefing may offer the stale sessions again (once a week)."""
    d = J.read_json(os.path.join(env.path("ops"), "stale-told"))
    return not isinstance(d, dict) or now - (J._int(d.get("at")) or 0) >= STALE_TELL_MS


def stale_told(env, now, n):
    J.atomic_write(os.path.join(env.path("ops"), "stale-told"), json.dumps({"at": now, "n": n}))
