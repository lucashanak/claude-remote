#!/usr/bin/env python3
"""Jarvis PreToolUse gates: which tools at all, and for Bash one allowlisted command, nothing else.

Installed twice:
  "<python3> -I ~/jarvis/.claude/hooks/gate.py --tools"   matcher "*"     tool allowlist + permission mode
  "<python3> -I ~/jarvis/.claude/hooks/gate.py"           matcher "Bash"  the command line

Why the tool gate: the deny list names the tools we know of, and a new Claude Code release can add one that dontAsk
lets through (spike 4i found five). An allowlist of tool names does not age that way. It also requires
permission_mode == "dontAsk": a CLI --permission-mode (restore.sh adds one for the AUTO and AUTO_ACCEPT manifest
modes) or a plan approval would otherwise take Jarvis out of dontAsk, and then every tool blocks instead.

Why it exists next to `defaultMode: dontAsk` + `allow`: it is the second, independent layer. dontAsk already denies
any Bash outside the allow rules, but a rule set is one typo away from `Bash(cr-:*)`; this gate checks the whole
command string again with its own rules.

Why fail-closed by hand: Claude Code 2.1.319 treats ONLY exit 2 as "deny". Exit 1, a traceback, or a missing
interpreter (127) lets the call through (f1-spike 4f). So every path that is not an explicit "allow" ends in exit 2,
including our own bugs. The interpreter is called by absolute path with the script as an argument: if this file
goes missing, python3 itself exits 2 ("can't open file"), which blocks as well.

F2a adds cr-mail and cr-task. Their free text (a message, a task title) goes as ONE argument in quotes, because the
gate lets no heredoc, pipe or `$` through: a token wrapped whole in '…' or "…" may hold spaces, Czech and punctuation
(TEXT), every other token stays an ARG. Bash takes a quoted word literally (no glob, tilde or brace expansion; the
line shape already removed `$`, backtick and backslash, the only things double quotes still expand), so the gate
judges the same words bash will pass. Not allowed to Jarvis: `cr-mail reply` (the addressee's), `cr-mail on`
(Lukáš's switch) and `cr-mail prompt-seen` (the hook's: it would mint Jarvis a fresh "Lukáš's prompt").

F2b adds `cr-mail audit` and `cr-task watch|watches|unwatch|log` (all read or notify-only), and forbids
`cr-task done --force` (Lukáš's override of the evidence).

F3 adds `cr-rules add|list|rm` (the CLI itself requires a fresh prompt of Lukáš's asking for silence), `cr-jarvis mode`
(a fresh prompt of his), `cr-task remind|reminders|unremind` and `cr-digest briefing`. Not allowed to Jarvis:
`cr-mail approve|reject`, the app's yes/no on a proposal (the CLI refuses Jarvis's pane as well).

F4 adds `cr-sessions open|check-close|close|closed|reopen|stale|pick-account` (the CLI decides between acting at once
and a proposal; `--task` and `--reason` are quoted free text, so cr-sessions joins TEXT_CMDS) and `cr-jarvis proposals`
(read only). Not allowed to Jarvis: `cr-jarvis approve|reject` (Lukáš's card) and `cr-jarvis run` (an approved
action, started by the prompt hook), and `--force` anywhere on `cr-sessions close` (the CLI has none; `cr-session`
itself is not on the list at all).
rev-f4 L5: FORBIDDEN looks at unquoted words only, so a quoted "--force" (bash passes it as --force) or a prefix like
`--forc` gets past this layer. The guarantee is the CLI: `cr-sessions close` has no such option and argparse refuses
the call before any action (test_jarvisops ReviewFixTest.test_l5_…); this gate stays a second, cheaper layer.
"""
import json
import re
import shlex
import sys
import unicodedata

# Tools the model may call at all (f1-spike: with the deny list the model has AskUserQuestion, Bash, Read, ToolSearch
# and the server-side advisor). Everything else, MCP included, exits 2.
TOOLS = frozenset({"Bash", "Read", "ToolSearch", "AskUserQuestion"})
MODE = "dontAsk"

ALLOWED = {
    "cr-sessions": frozenset({"list", "show", "tail", "open", "check-close", "close", "closed", "reopen", "stale",
                              "pick-account"}),
    "cr-digest": frozenset({"delta", "brief", "waiting", "usage", "briefing"}),
    "cr-mail": frozenset({"send", "status", "inbox", "cancel", "off", "audit"}),
    "cr-task": frozenset({"add", "list", "show", "done", "fail", "watch", "watches", "unwatch", "log", "remind",
                          "reminders", "unremind"}),
    "cr-rules": frozenset({"add", "list", "rm"}),
    "cr-jarvis": frozenset({"mode", "proposals"}),
}
# Options Jarvis may never pass. `cr-task done --force` closes a delegated task without its evidence; the CLI refuses
# it to anyone but Lukáš's own shell already, this is the second layer.
FORBIDDEN = {("cr-task", "done"): frozenset({"--force"}), ("cr-sessions", "close"): frozenset({"--force"})}
# commands whose arguments may be quoted free text
TEXT_CMDS = frozenset({"cr-mail", "cr-task", "cr-rules", "cr-sessions"})
HELP = frozenset({"-h", "--help"})
# Options a subcommand cannot run without. `cr-digest delta` without --peek moves the cursor the UserPromptSubmit hook
# reads from, so what changed (a perm, an ask) would never reach Lukáš's next delta.
REQUIRED = {("cr-digest", "delta"): frozenset({"--peek"})}

# The shape of the whole line: the command name, then one space and arguments without any shell metacharacter that
# could chain, substitute or redirect (; & | $ ` < > ( ) \ and every control character, newline included).
LINE = re.compile(r"(?:%s)(?: [^;&|$`<>()\\\x00-\x1f\x7f]*)?" % "|".join(re.escape(c) for c in ALLOWED))
# Each argument after shell quoting is removed: session names, numbers and options. No glob, tilde or brace characters,
# so bash cannot turn one argument into something else.
ARG = re.compile(r"[\w .,:@%+=/-]+")
# The inside of one quoted token. LINE has already removed ; & | $ ` < > ( ) \ and control characters.
TEXT = re.compile(r"[^\x00-\x1f\x7f]{1,2000}")


def split_quoted(line):
    """[(word, quoted)] split on spaces, or None. A quoted token is wrapped WHOLE in one pair of ' or "; a quote
    anywhere else (`a"b"`, `"a"b`, unbalanced) is refused, so no token is half literal and half expanded."""
    out, i, n = [], 0, len(line)
    while i < n:
        if line[i] == " ":
            i += 1
            continue
        if line[i] in "'\"":
            q = line[i]
            j = line.find(q, i + 1)
            if j < 0 or (j + 1 < n and line[j + 1] != " "):
                return None
            out.append((line[i + 1:j], True))
            i = j + 1
        else:
            j = line.find(" ", i)
            j = n if j < 0 else j
            w = line[i:j]
            if "'" in w or '"' in w:
                return None
            out.append((w, False))
            i = j
    return out


def check(cmd):
    """None when the command may run, else the reason it may not."""
    if not isinstance(cmd, str):
        return "the command is not a string"
    # only trailing newlines: str.strip() would also drop \x0c, \x1c-\x1f and NBSP, and then the gate would judge a
    # different line than the one bash runs
    line = cmd.rstrip("\n")
    if not LINE.fullmatch(line):
        return ("only one cr-sessions, cr-digest, cr-mail, cr-task, cr-rules or cr-jarvis command, without "
                "; & | $ ` < > ( ) \\ or a line break")
    if line.split(" ", 1)[0] in TEXT_CMDS:
        return check_text_cmd(line)
    words = shlex.split(line)  # unbalanced quotes raise -> exit 2
    name, args = words[0], words[1:]
    if name not in ALLOWED:
        return f"{name} is not allowed"
    if not args or (args[0] not in ALLOWED[name] and args[0] not in HELP):
        return f"{name} takes one of: {', '.join(sorted(ALLOWED[name]))}"
    for a in args:
        if not ARG.fullmatch(a):
            return f"argument {a[:40]!r} has characters outside letters, digits and . , : @ % + = / -"
    need = REQUIRED.get((name, args[0]), frozenset()) - set(args[1:])
    if need:
        return f"{name} {args[0]} needs {' '.join(sorted(need))}"
    return None


def check_text_cmd(line):
    toks = split_quoted(line)
    if not toks or toks[0][1]:
        return "unbalanced or misplaced quotes; put free text in one pair of quotes"
    if len(toks) != len(shlex.split(line)):
        return "the quoting is ambiguous"
    name, args = toks[0][0], toks[1:]
    if not args or args[0][1] or (args[0][0] not in ALLOWED[name] and args[0][0] not in HELP):
        return f"{name} takes one of: {', '.join(sorted(ALLOWED[name]))}"
    bad = FORBIDDEN.get((name, args[0][0]), frozenset()) & {w for w, q in args[1:] if not q}
    if bad:
        return f"{name} {args[0][0]} {' '.join(sorted(bad))} is Lukáš's, not Jarvis's"
    for w, quoted in args:
        if quoted:
            if not TEXT.fullmatch(w) or any(unicodedata.category(c) in ("Cc", "Cf", "Cs", "Co", "Zl", "Zp") for c in w):
                return "quoted text must be one line without control or format characters"
        elif not ARG.fullmatch(w):
            return f"argument {w[:40]!r} has characters outside letters, digits and . , : @ % + = / - (quote free text)"
    return None


def check_tool(data):
    """None when this tool may run in this permission mode, else the reason it may not."""
    tool, mode = data["tool_name"], data["permission_mode"]
    if tool not in TOOLS:
        return f"tool {str(tool)[:60]} is not on Jarvis's tool list ({', '.join(sorted(TOOLS))})"
    if mode != MODE:
        return (f"permission mode is {str(mode)[:30]!r}, Jarvis runs only in {MODE}; restart it without "
                f"--permission-mode (Shift+Tab never cycles back to {MODE})")
    return None


def main(argv):
    data = json.load(sys.stdin)
    if argv == ["--tools"]:
        why = check_tool(data)
        if why is None:
            return 0
        print(f"jarvis gate: {why}.", file=sys.stderr)
        return 2
    if argv:
        raise ValueError("unknown arguments")
    if data.get("tool_name") != "Bash":
        return 0  # the matcher only sends Bash; other tools are governed by the deny list and dontAsk
    why = check(data["tool_input"]["command"])
    if why is None:
        return 0
    print(f"jarvis gate: {why}. Allowed: "
          + "; ".join(f"{c} {'|'.join(sorted(s))} [args]" for c, s in ALLOWED.items()), file=sys.stderr)
    return 2


if __name__ == "__main__":
    try:
        code = main(sys.argv[1:])
    except BaseException as e:  # noqa: BLE001 -- anything unexpected must block, never fall through
        print(f"jarvis gate: internal error, blocking ({type(e).__name__})", file=sys.stderr)
        code = 2
    sys.exit(code)
