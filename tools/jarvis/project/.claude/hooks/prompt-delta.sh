#!/bin/sh
# Jarvis UserPromptSubmit hook: prints `cr-digest delta` (what changed since the last prompt) into the context of the
# prompt Lukáš just sent, and moves the cursor.
# --max-chars 8000: over 10 000 characters Claude Code moves hook output to a file and the model sees only a
# ~2 000-character preview (f1-spike 2c); cr-digest puts what blocks Lukáš first and counts what did not fit.
#
# Always exit 0: exit 2 from UserPromptSubmit BLOCKS the prompt and erases it, so a broken digest must never cost
# Lukáš his message. A failure becomes one line the model can pass on.
#
# F2a: first the payload goes to `cr-mail prompt-seen`, which records this prompt's id and time in <state>/last-prompt.
# `cr-mail send` needs a prompt of Lukáš's younger than 10 minutes (I6); a prompt that starts with `[jarvis` is not
# his and cancels the previous one. Before the delta, so a send in this very turn sees it.
# The previous prompt goes away FIRST and without cr-mail (rev-f2a MED5): if cr-mail is missing, broken or killed, no
# old prompt can authorise a send any more. last-prompt.prev is what the proposals of the previous turn wait for; when
# the move fails it is deleted, so a stale one can never approve anything (fail closed).
state="${JARVIS_STATE_DIR:-$HOME/.claude-remote/jarvis}"
mv -f "${state:?}/last-prompt" "${state:?}/last-prompt.prev" 2>/dev/null || rm -f "${state:?}/last-prompt.prev" 2>/dev/null
rm -f "${state:?}/last-prompt" 2>/dev/null
payload="$(head -c 1048576 2>/dev/null)" || payload=""
printf '%s' "$payload" | "@BIN@/cr-mail" prompt-seen >/dev/null 2>&1 || true

out="$("@BIN@/cr-digest" delta --max-chars 8000 2>/dev/null)"
rc=$?
if [ "$rc" -eq 0 ] && [ -n "$out" ]; then
  printf '%s\n' "$out"
else
  echo "[jarvis delta nedostupná: cr-digest skončil s kódem $rc. Stav zjisti přes cr-digest brief.]"
fi
exit 0
