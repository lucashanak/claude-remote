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
