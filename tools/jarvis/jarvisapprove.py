"""Jarvis F5e: Lukáš approves through Jarvis (Lukáš 2026-11-11: "Neumí nic potvrdit ani schválit. K čemu mi pak je?").

Decision 14e changes: Jarvis reads what a session asks (a tool permission, an AskUserQuestion) and PROPOSES it; on
Lukáš's "ano" (voice, the Ano of the `jask` notification, the watch, typed) the CODE answers the dialog. Jarvis himself
never approves anything, and what gets approved is decided by the code from the dialog, never by Jarvis's words.

- `read_dialog`: the open hook event of the session (`perm` / `ask`, its ms) + one read-only `capture-pane` of the
  pane I1 resolves. The screen is parsed into the question, the options (number, label, description, Recommended,
  where the pointer ❯ is), the title / tool and its content, and the shape (tabs of several questions, multiSelect).
- The fingerprint: sha256 of the hook event (`<ev> <ms>`, the app's PromptFingerprint key) and of the dialog's text
  with the pointer and the colours taken out: a redraw or moving the pointer never changes it, another dialog
  (another event, another text) always does.
- Keys (Claude 2.1.322, its own "Confirmation" bindings read from the binary: enter = confirm the FOCUSED option,
  escape = no, up / down = move; there is no binding for `y` or `n`):
    approve a permission  Enter, ONLY while a fresh capture shows `❯ 1. Yes` (no navigation; anything else is stale:
                          never "Yes, and always allow …", never "Yes, and switch to auto mode");
    choose option N       the pointer is moved onto option N, each step checked, then Enter; only a single question
                          with single choice and a real option (never "Type something", never "Chat about this");
    deny                  Escape.
  Right before the first key: the same fingerprint, the same open event as the session's newest, nobody's hands on the
  pane (I5: copy-mode, a client's input in the last 20 s, a hold), mail on, I1 (the same claude). After the key the
  dialog must be gone from the screen (or the session reported work) within a few seconds.
- Never: I7 sessions (PROD / DB- / deploy / Proxmox: "Tohle schval v aplikaci."), the folder-trust dialog, /login or
  any other system dialog (they have no `perm` / `ask` hook event, so they are never read as one).
"""
import hashlib
import json
import os
import re
import time

import jarvislib as J

ANSI = re.compile(r"\x1b\[[0-9;:?]*[A-Za-z]|\x1b\][^\x07]*\x07")
RULE_RE = re.compile(r"^\s*─{20,}\s*$")
OPTION_RE = re.compile(r"^\s*(❯)?\s*(\d{1,2})\.\s+(.*?)\s*$")
FOOTER_RE = re.compile(r"Esc to cancel")
PERM_QUESTION_RE = re.compile(r"^\s*Do you want to\b.*\?\s*$")
CHIP_RE = re.compile(r"[☐☒✔]")
NOT_AN_ANSWER = ("Type something", "Type something.", "Chat about this")
# verified live by the lead (2026-11-11, an isolated `tmux -L`, Claude 2.1.322): `n`+Enter RAN the command, `y`+Enter
# ran it, Escape denied it. "Ano" = Enter ONLY while the screen shows the focus on "1. Yes" (the app's regex), else
# stale; "Ne" = Escape. Never an option with "always" or "auto mode" (a persistent approval).
APPROVE_RE = re.compile(r"❯ *1\. Yes[ \t]*$", re.M)
NEVER_RE = re.compile(r"always|auto mode|don't ask again", re.IGNORECASE)
CONTENT_MAX = 200
MAX_STEPS = 12
CONFIRM_S = 5.0
POLL_S = 0.25
STEP_S = 0.15


def plain(raw):
    return ANSI.sub("", raw or "").replace(" ", " ")


def parse_screen(raw):
    """The dialog on a captured screen, or None. {kind: perm|ask, title, content, question, options: [{n, label,
    desc, recommended}], pointer, tabs, multi, region}. `region` is the dialog's text without the pointer, for the
    fingerprint."""
    lines = [l.rstrip() for l in plain(raw).splitlines()]
    foot = next((i for i in range(len(lines) - 1, -1, -1) if FOOTER_RE.search(lines[i])), None)
    if foot is None:
        return None
    first = None
    for i in range(foot - 1, -1, -1):
        m = OPTION_RE.match(lines[i])
        if m and m.group(2) == "1":
            first = i
            break
    if first is None:
        return None
    start = next((i for i in range(first - 1, -1, -1) if RULE_RE.match(lines[i])), None)
    if start is None:
        return None
    options, pointer, cur = [], None, None
    for i in range(first, foot):
        l = lines[i]
        if RULE_RE.match(l) or not l.strip():
            continue
        m = OPTION_RE.match(l)
        if m and int(m.group(2)) == len(options) + 1:
            cur = {"n": int(m.group(2)), "label": m.group(3), "desc": "",
                   "recommended": "(Recommended)" in m.group(3)}
            options.append(cur)
            if m.group(1):
                pointer = cur["n"]
        elif cur is not None and not FOOTER_RE.search(l):
            cur["desc"] = (cur["desc"] + " " + l.strip()).strip()
    head = [l for l in lines[start + 1:first] if l.strip() and not RULE_RE.match(l)
            and not re.match(r"^\s*╌{10,}\s*$", l)]
    perm = bool(head) and bool(PERM_QUESTION_RE.match(head[-1]))
    chips = head[0] if head and CHIP_RE.search(head[0]) else ""
    if perm:
        question = head[-1].strip()
        title = head[0].strip()
        # the command / file and the warnings are the `│` lines; the subtitle ("Run shell command") is not content.
        # A line Claude cut in the middle of a word fills the box to its last column: it joins the next WITHOUT a
        # blank (the live `…36f14f6a035` + `2/scratchpad`), a line wrapped at a blank joins with one
        width = len(lines[start].rstrip())
        content, cut = "", False
        for l in head[1:-1]:
            if not re.match(r"^\s*│", l):
                continue
            piece = re.sub(r"^\s*│\s?", "", l).strip()
            if piece:
                content = content + piece if content and cut else (content + " " + piece).strip()
            cut = len(l.rstrip()) >= width - 1
    else:
        title = re.sub(r"[☐☒✔←→]", "", chips).strip() if chips else ""
        question = " ".join(re.sub(r"^\s*│\s?", "", l).strip() for l in head[1 if chips else 0:])
        content = ""
    region = "\n".join(re.sub(r"\s+", " ", l.replace("❯", " ")).strip() for l in lines[start + 1:foot + 1])
    # rev-f5e LOW: the "❯ 1. Yes" check reads the dialog's own lines only (a "❯ 1. Yes" quoted above it is no focus)
    shown = "\n".join(lines[start + 1:foot + 1])
    return {"kind": "perm" if perm else "ask", "title": title, "content": content, "question": question,
            "options": options, "pointer": pointer,
            "tabs": len(CHIP_RE.findall(chips)) > 1 or "Submit" in chips,
            "multi": any(o["label"] == "Type something" or o["label"].startswith(("[ ]", "[✔]", "[x]", "☐", "☒"))
                         for o in options),
            "region": region, "shown": shown}


def fingerprint(ev, ms, dialog):
    return hashlib.sha256(f"{ev} {ms}\n{dialog['region']}".encode("utf-8", "replace")).hexdigest()[:16]


class Dialog:
    """What one session asks right now, read for a proposal or right before the keys."""

    def __init__(self, name, target, ev, ms, extra, parsed):
        self.name, self.target, self.ev, self.ms, self.extra, self.p = name, target, ev, ms, extra, parsed
        self.fp = fingerprint(ev, ms, parsed)

    def answers(self):
        """[(action, n, label)] the code may send for this dialog: approve / option N / deny."""
        out = [("deny", None, "Ne")]
        if self.p["kind"] == "perm":
            yes = [o for o in self.p["options"] if o["label"] == "Yes"]
            if yes:
                out.insert(0, ("approve", yes[0]["n"], "Yes"))
        elif not (self.p["tabs"] or self.p["multi"]):
            out[:0] = [("option", o["n"], o["label"]) for o in self.p["options"] if answerable(o)]
        return out


def answerable(opt):
    """A real answer of a QUESTION (never "Type something" / "Chat about this"). A permission is answered only with
    its option labelled exactly "Yes" (Dialog.answers, _move_to): every persistent choice ("Yes, and don't ask again",
    "Yes, allow all edits during this session") has a longer label."""
    return opt["label"] not in NOT_AN_ANSWER and not NEVER_RE.search(opt["label"])


def read_dialog(mail, name, fold=None, now=None):
    """(Dialog, None) or (None, why in Czech)."""
    if mail.check_high_impact(name):
        return None, "Tohle schval v aplikaci (PROD, DB-, deploy, Proxmox)."
    t = mail.resolve_target(name)
    if isinstance(t, str):
        return None, f"{J.short_name(name)} teď nemá živého claude ({t})"
    fs = mail.fresh_events(name, fold)
    op, last = fs.get("open"), fs.get("last") or {}
    if not op or op.get("ev") not in ("perm", "ask"):
        return None, f"{J.short_name(name)} nečeká na povolení ani na odpověď na otázku"
    if last.get("ev") != op["ev"] or last.get("ms") != op["ms"]:
        return None, f"{J.short_name(name)} se mezitím pohnula"
    rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", t.pane])
    p = parse_screen(raw) if rc == 0 else None
    if not p:
        return None, f"dialog {J.short_name(name)} na obrazovce nepoznám; schval to v aplikaci"
    if p["kind"] != ("perm" if op["ev"] == "perm" else "ask"):
        return None, f"obrazovka {J.short_name(name)} neodpovídá hlášenému dialogu; schval to v aplikaci"
    if p["kind"] == "perm" and len(p["content"]) > CONTENT_MAX:
        return None, LONG  # rev-f5e MED: what Lukáš would approve must fit the sentence he hears, whole
    return Dialog(name, t, op["ev"], op["ms"], op.get("extra") or "", p), None


def describe(d):
    """Structured, escaped lines for `cr-approve show` (texts are data)."""
    p = d.p
    out = [f"session: {J.short_name(d.name)} · {'povolení' if p['kind'] == 'perm' else 'otázka'} · otisk {d.fp}"]
    if p["kind"] == "perm":
        out.append(f"nástroj: «{J.safe_text(p['title'], 120)}»")
        if p["content"]:
            out.append(f"co dělá: «{J.safe_text(p['content'], CONTENT_MAX)}»")
        out.append(f"dotaz: «{J.safe_text(p['question'], 120)}»")
    else:
        if p["title"]:
            out.append(f"téma: «{J.safe_text(p['title'], 80)}»")
        out.append(f"otázka: «{J.safe_text(p['question'], CONTENT_MAX)}»")
    for o in p["options"]:
        tag = []
        if o["recommended"]:
            tag.append("doporučeno")
        if not answerable(o):
            tag.append("tudy ne")
        out.append(f"  {o['n']}. «{J.safe_text(o['label'], 100)}»" + (f" ({', '.join(tag)})" if tag else "")
                   + (f" · «{J.safe_text(o['desc'], 120)}»" if o["desc"] else ""))
    if p["tabs"]:
        out.append("víc otázek naráz (záložky): přes Jarvise nejde, zvolit v aplikaci")
    if p["multi"]:
        out.append("výběr víc možností: přes Jarvise nejde, zvolit v aplikaci")
    return out


def sentence(d, action, n=None, said=None):
    """The Czech sentence the CODE composes for the proposal (Jarvis reads it verbatim; the `jask` text). `said`: the
    option numbers Lukáš's own prompt named; an option outside them is said so in the sentence (rev-f5e HIGH)."""
    short = J.short_name(d.name)
    p = d.p
    if action == "deny":
        return f"{short} {'chce povolení' if p['kind'] == 'perm' else 'se ptá'}: {_what(d)}. Zamítnout?"
    if action == "approve":
        return f"{short} chce povolení: {_what(d)}. Schválit?"
    o = next(o for o in p["options"] if o["n"] == n)
    rec = "doporučená " if o["recommended"] else ""
    label = J.safe_text(o["label"].replace("(Recommended)", "").strip(), 80)
    note = (f" (ve tvé zprávě: {', '.join(str(x) for x in said)})" if said and n not in said else "")
    return f"{short} se ptá: {J.safe_text(p['question'], 120)}; {rec}možnost {n}: {label}{note}. Zvolit ji?"


def _what(d):
    p = d.p
    if p["kind"] == "perm":
        tool = re.split(r"\s+·\s+| command\b", p["title"])[0].strip() or "nástroj"
        # never cut (kolo 5): Lukáš approves what he hears, so the WHOLE command; read_dialog sent anything over
        # CONTENT_MAX to the app already
        return J.safe_text(f"{tool} {p['content']}".strip(), CONTENT_MAX + 200)
    return J.safe_text(p["question"], 140)


def done_sentence(d, action, n=None):
    short = J.short_name(d.name)
    if action == "deny":
        return f"Zamítnuto: {short} to neudělá."
    if action == "approve":
        return f"Schváleno: {short} může {_what(d)}."
    o = next((o for o in d.p["options"] if o["n"] == n), {"label": "?"})
    return f"Zvoleno: {short}, možnost {n}: {J.safe_text(o['label'].replace('(Recommended)', '').strip(), 80)}."


CHANGED = "Dialog se mezitím změnil, nic jsem neschválil."
LONG = "Dlouhý příkaz, schval ho v aplikaci."


def execute(mail, name, args, fold=None, sleep=time.sleep):
    """(ok, message): answer the dialog exactly as approved, or nothing. `args`: {ev, ms, fp, action, n}."""
    if mail.off():
        return False, "Jarvis je vypnutý (off), nic jsem neschválil."
    d, why = read_dialog(mail, name, fold)
    if not d or d.ev != args.get("ev") or d.ms != args.get("ms") or d.fp != args.get("fp"):
        return False, CHANGED
    action, n = args.get("action"), args.get("n")
    allowed = {(a, x) for a, x, _ in d.answers()}
    if (action, n if action != "deny" else None) not in allowed:
        return False, "Tuhle volbu přes Jarvise neudělám; zvol to v aplikaci."
    why = mail.check_hands(d.target, mail.env.now_ms())
    if why:
        return False, f"Do {J.short_name(name)} teď někdo píše ({why}), nic jsem neschválil."
    since = mail.env.now_ms()
    pane = d.target.pane
    if action == "deny":
        mail.tmux(["send-keys", "-t", pane, "Escape"])
    elif action == "approve":
        # no navigation for a permission: Enter only over exactly "❯ 1. Yes" on a fresh capture (same fingerprint)
        rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", pane])
        p = parse_screen(raw) if rc == 0 else None
        if not p or fingerprint(d.ev, d.ms, p) != d.fp or not APPROVE_RE.search(p["shown"]):
            return False, CHANGED
        mail.tmux(["send-keys", "-t", pane, "Enter"])
    else:
        ok = _move_to(mail, d, n, sleep)
        if not ok:
            return False, CHANGED
        mail.tmux(["send-keys", "-t", pane, "Enter"])
    if _gone(mail, d, since, sleep):
        return True, done_sentence(d, action, n)
    return False, f"Nevím, jestli to v {J.short_name(name)} prošlo; zkontroluj to v aplikaci."


def _move_to(mail, d, n, sleep):
    """Moves the pointer onto option n with Up/Down, checking the screen (same fingerprint) after every step."""
    for _ in range(MAX_STEPS):
        rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", d.target.pane])
        p = parse_screen(raw) if rc == 0 else None
        if not p or fingerprint(d.ev, d.ms, p) != d.fp or p["pointer"] is None:
            return False
        if p["pointer"] == n:
            o = next(o for o in p["options"] if o["n"] == n)
            return answerable(o) and d.p["kind"] == "ask"
        # rev-f5e LOW: nobody's hands right before EVERY arrow (an arrow into a prompt that came back would pull the
        # previous prompt into its box)
        if mail.check_hands(d.target, mail.env.now_ms()):
            return False
        mail.tmux(["send-keys", "-t", d.target.pane, "Down" if p["pointer"] < n else "Up"])
        sleep(STEP_S)
    return False


def _gone(mail, d, since, sleep):
    deadline = time.monotonic() + CONFIRM_S
    while True:
        if mail.worked_since(d.name, since) or _askdone_since(mail, d.name, since):
            return True
        rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", d.target.pane])
        p = parse_screen(raw) if rc == 0 else None
        if rc == 0 and (p is None or fingerprint(d.ev, d.ms, p) != d.fp):
            return True
        if time.monotonic() >= deadline:
            return False
        sleep(POLL_S)


def _askdone_since(mail, name, since):
    for line in reversed(J.read_tail(mail.env.events_path, 262144)):
        e = J.parse_event(line)
        if e and e.name == name and e.ev in ("askdone", "stop") and e.ms >= since - 50:
            return True
    return False


def args_json(d, action, n):
    return {"name": d.name, "ev": d.ev, "ms": d.ms, "fp": d.fp, "action": action, "n": n}


def dump(d):
    return json.dumps({"name": d.name, "ev": d.ev, "ms": d.ms, "fp": d.fp, "dialog": {
        k: d.p[k] for k in ("kind", "title", "content", "question", "options", "pointer", "tabs", "multi")},
        "answers": [{"action": a, "n": n, "label": l} for a, n, l in d.answers()]}, ensure_ascii=False, indent=1)


# ---------------------------------------------------------------------------------------------------------- trust
# Lukáš 2026-11-12: the folder-trust dialog ("Yes, I trust this folder") may be approved through Jarvis too, for a
# folder under his $HOME (realpath, $HOME itself included), never for a PROD / DB- / deploy / Proxmox session or path.
# There is no hook event for it: the screen is read (jarvislib.trust_dialog, its four marks), and the fingerprint is
# the folder, the pane, the claude below the pane (pid + procStart) and the dialog's text. The focus starts on
# "No, exit" (checked live by the lead): Down, then the focus must stand on "Yes, I trust this folder", then Enter;
# before every key the fingerprint again and nobody's hands on the pane (I5).

OSC = re.compile(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")
TRUST_MAX = 10
TRUST_YES = re.compile(r"^\s*❯\s*(?:\d\.\s*)?Yes, I trust this folder\s*$", re.M)
TRUST_NO = re.compile(r"^\s*❯\s*(?:\d\.\s*)?No, exit\s*$", re.M)


class Pane:
    """What check_hands needs (name, pane) plus the claude below the pane."""

    def __init__(self, name, pane, pane_pid, pid, start):
        self.name, self.pane, self.pane_pid, self.pid, self.start = name, pane, pane_pid, pid, start


class Trust:
    def __init__(self, name, pane, path, text):
        self.name, self.target, self.path, self.text = name, pane, path, text
        self.fp = hashlib.sha256("\n".join([path, pane.pane, str(pane.pid), str(pane.start), text]).encode(
            "utf-8", "replace")).hexdigest()[:16]

    def item(self):
        t = self.target
        return {"name": self.name, "pane": t.pane, "pid": t.pid, "start": t.start, "path": self.path, "fp": self.fp}


def trust_plain(raw):
    return plain(OSC.sub("", raw or ""))


def trust_screen(raw):
    """(folder, dialog text without the pointer) of a trust dialog on the screen, or None."""
    if not J.trust_dialog(raw):
        return None
    lines = [l.rstrip() for l in trust_plain(raw).splitlines()]
    i = next((k for k, l in enumerate(lines) if l.strip() == "Accessing workspace:"), None)
    if i is None:
        return None
    nxt = next((l.strip() for l in lines[i + 1:] if l.strip()), "")
    if not nxt.startswith("/"):
        return None
    end = next((k for k in range(i, len(lines)) if "Enter to confirm" in lines[k]), len(lines) - 1)
    text = "\n".join(x for x in (re.sub(r"\s+", " ", l.replace("❯", " ")).strip() for l in lines[i:end + 1]) if x)
    return nxt, text


def trust_pane(mail, name):
    """Pane, or why not: the first live pane of the session and the claude that runs below it."""
    rc, out = mail.tmux(["list-panes", "-s", "-t", "=" + name, "-F",
                         "#{pane_id}\t#{pane_pid}\t#{session_name}\t#{pane_dead}\t#{pane_in_mode}"])
    f = out.splitlines()[0].split("\t") if rc == 0 and out.strip() else []
    if len(f) < 4 or f[3] == "1" or not f[0].startswith("%"):
        return "pane"
    try:
        pane_pid = int(f[1])
    except ValueError:
        return "pane"
    env = mail.env
    pid = J._claude_under(J._children(env.proc_table()), pane_pid)
    if not pid:
        return "no-claude"
    return Pane(name, f[0], pane_pid, pid, env.proc_start(pid))


def under_home(env, path):
    home = os.path.realpath(env.home)
    real = os.path.realpath(path)
    return real == home or real.startswith(home.rstrip("/") + "/")


class NotTrust(str):
    """A refusal that says the session shows no trust dialog at all (the caller may look for another dialog)."""


def read_trust(mail, name):
    """(Trust, None) or (None, why in Czech); `why` is a NotTrust when no trust dialog is on the screen."""
    short = J.short_name(name)
    t = trust_pane(mail, name)
    if isinstance(t, str):
        return None, NotTrust(f"{short} teď nemá živého claude ({t})")
    rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", t.pane])
    got = trust_screen(raw) if rc == 0 else None
    if not got:
        return None, NotTrust(f"{short} nečeká na důvěru ke složce")
    path, text = got
    if mail.check_high_impact(name) or mail.check_high_impact(path):
        return None, "Tohle schval v aplikaci (PROD, DB-, deploy, Proxmox)."
    if not under_home(mail.env, path):
        return None, f"složka {path} je mimo tvůj domov; tu potvrď v aplikaci"
    # rev-stuck LOW: the folder on the screen must be the claude's own working directory (a wrapped or cut path on
    # the screen never passes for another folder)
    cwd = mail.env.proc_cwd(t.pid)
    if not cwd or os.path.realpath(path) != os.path.realpath(cwd):
        return None, f"složka v dialogu {short} nesedí se složkou, ve které claude běží; potvrď to v aplikaci"
    return Trust(name, t, path, text), None


def trust_sentence(trusts):
    if len(trusts) == 1:
        return f"{J.short_name(trusts[0].name)} čeká na důvěru ke složce {trusts[0].path}. Potvrdit?"
    n = len(trusts)
    return (f"{n} {J.plural(n, 'session', 'sessions', 'sessions')} čeká na důvěru ke složce {trusts[0].path}: "
            + ", ".join(J.short_name(t.name) for t in trusts) + ". Potvrdit všem?")


def _trust_check(mail, item):
    """(Trust, None) when the dialog is still exactly the approved one and nobody's hands are on the pane."""
    tr, why = read_trust(mail, item["name"])
    # the fingerprint holds the folder, the pane, the claude's pid and procStart and the dialog's text
    if not tr or tr.fp != item.get("fp"):
        return None, "dialog se změnil"
    if mail.check_hands(tr.target, mail.env.now_ms()):
        return None, "někdo do ní píše"
    return tr, None


def execute_trust(mail, items, sleep=time.sleep):
    """(ok, message): each session checked on its own; what does not fit is skipped and named in the result."""
    if mail.off():
        return False, "Jarvis je vypnutý (off), nic jsem nepotvrdil."
    done, skipped = [], []
    for item in items[:TRUST_MAX]:
        short = J.short_name(item.get("name") or "?")
        tr, why = _trust_check(mail, item)
        if not tr:
            skipped.append(f"{short} ({why})")
            continue
        pane = tr.target.pane
        rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", pane])
        scr = trust_plain(raw) if rc == 0 else ""
        if TRUST_NO.search(scr):
            mail.tmux(["send-keys", "-t", pane, "Down"])
            sleep(STEP_S)
        tr, why = _trust_check(mail, item)  # again right before Enter
        rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", pane])
        if not tr or rc != 0 or not TRUST_YES.search(trust_plain(raw)):
            skipped.append(f"{short} ({why or 'fokus není na Yes, I trust this folder'})")
            continue
        mail.tmux(["send-keys", "-t", pane, "Enter"])
        if _trust_gone(mail, pane, sleep):
            done.append(short)
        else:
            skipped.append(f"{short} (nevím, jestli to prošlo)")
    path = items[0].get("path") if items else "?"
    msg = (f"Potvrzena důvěra ke složce {path}: {', '.join(done)}." if done else "Nic jsem nepotvrdil.")
    if skipped:
        msg += " Přeskočeno: " + ", ".join(skipped) + "."
    return bool(done), msg


def _trust_gone(mail, pane, sleep):
    deadline = time.monotonic() + CONFIRM_S
    while True:
        rc, raw = mail.tmux(["capture-pane", "-p", "-e", "-t", pane])
        if rc == 0 and not J.trust_dialog(raw):
            return True
        if time.monotonic() >= deadline:
            return False
        sleep(POLL_S)
