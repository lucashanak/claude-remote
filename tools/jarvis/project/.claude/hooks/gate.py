#!/usr/bin/env python3
"""Jarvis PreToolUse gate for Bash: one allowlisted command, nothing else.

Installed as  "<python3> -I ~/jarvis/.claude/hooks/gate.py"  (matcher "Bash").

Why it exists next to `defaultMode: dontAsk` + `allow`: it is the second, independent layer. dontAsk already denies
any Bash outside the allow rules, but a rule set is one typo away from `Bash(cr-:*)`; this gate checks the whole
command string again with its own rules.

Why fail-closed by hand: Claude Code 2.1.319 treats ONLY exit 2 as "deny". Exit 1, a traceback, or a missing
interpreter (127) lets the call through (f1-spike 4f). So every path that is not an explicit "allow" ends in exit 2,
including our own bugs. The interpreter is called by absolute path with the script as an argument: if this file
goes missing, python3 itself exits 2 ("can't open file"), which blocks as well.

Extending (F2): add the command and its subcommands to ALLOWED, e.g. "cr-mail": {"reply"}; a command that takes
free text must take it on stdin, never argv (navrh §5 I6, point 19), so the argument rules below stay as they are.
F1 is read-only: only the read subcommands of cr-sessions and cr-digest.
"""
import json
import re
import shlex
import sys

ALLOWED = {
    "cr-sessions": frozenset({"list", "show", "tail"}),
    "cr-digest": frozenset({"delta", "brief", "waiting", "usage"}),
}
HELP = frozenset({"-h", "--help"})

# The shape of the whole line: the command name, then one space and arguments without any shell metacharacter that
# could chain, substitute or redirect (; & | $ ` < > ( ) \ and every control character, newline included).
LINE = re.compile(r"(?:%s)(?: [^;&|$`<>()\\\x00-\x1f\x7f]*)?" % "|".join(re.escape(c) for c in ALLOWED))
# Each argument after shell quoting is removed: session names, numbers and options. No glob, tilde or brace characters,
# so bash cannot turn one argument into something else.
ARG = re.compile(r"[\w .,:@%+=/-]+")


def check(cmd):
    """None when the command may run, else the reason it may not."""
    if not isinstance(cmd, str):
        return "the command is not a string"
    line = cmd.strip()
    if not LINE.fullmatch(line):
        return "only one cr-sessions or cr-digest command, without ; & | $ ` < > ( ) \\ or a line break"
    words = shlex.split(line)  # unbalanced quotes raise -> exit 2
    name, args = words[0], words[1:]
    if name not in ALLOWED:
        return f"{name} is not allowed"
    if not args or (args[0] not in ALLOWED[name] and args[0] not in HELP):
        return f"{name} takes one of: {', '.join(sorted(ALLOWED[name]))}"
    for a in args:
        if not ARG.fullmatch(a):
            return f"argument {a[:40]!r} has characters outside letters, digits and . , : @ % + = / -"
    return None


def main():
    data = json.load(sys.stdin)
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
        code = main()
    except BaseException as e:  # noqa: BLE001 -- anything unexpected must block, never fall through
        print(f"jarvis gate: internal error, blocking ({type(e).__name__})", file=sys.stderr)
        code = 2
    sys.exit(code)
