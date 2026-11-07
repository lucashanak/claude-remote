#!/bin/sh
# Jarvis UserPromptSubmit hook: prints `cr-digest delta` (what changed since the last prompt) into the context of the
# prompt Lukáš just sent, and moves the cursor.
# --max-chars 8000: over 10 000 characters Claude Code moves hook output to a file and the model sees only a
# ~2 000-character preview (f1-spike 2c); cr-digest puts what blocks Lukáš first and counts what did not fit.
#
# Always exit 0: exit 2 from UserPromptSubmit BLOCKS the prompt and erases it, so a broken digest must never cost
# Lukáš his message. A failure becomes one line the model can pass on.
cat >/dev/null 2>&1 || true

out="$("@BIN@/cr-digest" delta --max-chars 8000 2>/dev/null)"
rc=$?
if [ "$rc" -eq 0 ] && [ -n "$out" ]; then
  printf '%s\n' "$out"
else
  echo "[jarvis delta nedostupná: cr-digest skončil s kódem $rc. Stav zjisti přes cr-digest brief.]"
fi
exit 0
