#!/bin/sh
# Jarvis SessionStart hook, for every source: startup, resume, clear and compact.
# 1. Writes <state>/self = the tmux session name of Jarvis's own pane, so jarvis-d and cr-digest leave Jarvis's own
#    events out (I8: Jarvis must not wake itself).
# 2. Prints `cr-digest brief`; the stdout of SessionStart lands in the model's context. After /compact this is the
#    re-brief (R9). --max-chars 8000: Claude Code moves hook output over 10 000 characters to a file and the model sees
#    only a ~2 000-character preview (f1-spike 2c).
#
# Never fails: a SessionStart hook that errors must not keep Jarvis from starting, so every step degrades to a note.
cat >/dev/null 2>&1 || true

state="${JARVIS_STATE_DIR:-$HOME/.claude-remote/jarvis}"

write_self() {
  # Only our own pane, named explicitly: without -t, tmux would answer for whichever client was active last.
  case "${TMUX_PANE:-}" in
    %*) ;;
    *) return 0 ;;
  esac
  case "${TMUX_PANE#%}" in
    '' | *[!0-9]*) return 0 ;;
  esac
  name="$(timeout -k 1 2 tmux display-message -p -t "$TMUX_PANE" '#S' 2>/dev/null)" || return 0
  # one non-empty line, sane length; anything else is not a session name we want the daemon to trust
  [ -n "$name" ] || return 0
  [ "$(printf '%s' "$name" | wc -l)" -eq 0 ] || return 0
  [ "${#name}" -le 200 ] || return 0
  mkdir -p "$state" && chmod 700 "$state" || return 0
  # mktemp creates the file 0600; tmp + rename in the same directory: a reader sees the old name or the new one
  tmp="$(mktemp "$state/.self.XXXXXX")" || return 0
  if printf '%s\n' "$name" >"$tmp" && mv -f "$tmp" "$state/self"; then
    return 0
  fi
  rm -f "$tmp"
}

write_self || true

out="$("@BIN@/cr-digest" brief --max-chars 8000 2>/dev/null)"
rc=$?
if [ "$rc" -eq 0 ] && [ -n "$out" ]; then
  printf '%s\n' "$out"
else
  echo "[jarvis brief nedostupný: cr-digest skončil s kódem $rc. Stav zjisti přes cr-sessions list.]"
fi
exit 0
