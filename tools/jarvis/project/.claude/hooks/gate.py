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

Extending (F2): add the command and its subcommands to ALLOWED, e.g. "cr-mail": {"reply"}; a command that takes
free text must take it on stdin, never argv (navrh §5 I6, point 19), so the argument rules below stay as they are.
F1 is read-only: only the read subcommands of cr-sessions and cr-digest.
"""
import json
import re
import shlex
import sys

# Tools the model may call at all (f1-spike: with the deny list the model has AskUserQuestion, Bash, Read, ToolSearch
# and the server-side advisor). Everything else, MCP included, exits 2.
TOOLS = frozenset({"Bash", "Read", "ToolSearch", "AskUserQuestion"})
MODE = "dontAsk"

ALLOWED = {
    "cr-sessions": frozenset({"list", "show", "tail"}),
    "cr-digest": frozenset({"delta", "brief", "waiting", "usage"}),
}
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


def check(cmd):
    """None when the command may run, else the reason it may not."""
    if not isinstance(cmd, str):
        return "the command is not a string"
    # only trailing newlines: str.strip() would also drop \x0c, \x1c-\x1f and NBSP, and then the gate would judge a
    # different line than the one bash runs
    line = cmd.rstrip("\n")
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
    need = REQUIRED.get((name, args[0]), frozenset()) - set(args[1:])
    if need:
        return f"{name} {args[0]} needs {' '.join(sorted(need))}"
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
