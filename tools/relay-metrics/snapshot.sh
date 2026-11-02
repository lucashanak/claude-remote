#!/usr/bin/env bash
# Hourly snapshot of the shipped client logs (plan S0.2).  Meant for the user crontab
# (see K0-M-rozhodnuti.md for the line); no systemd involved.
#
#   snapshot.sh [ROOT]
#
# ROOT defaults to /home/lucas/claude-remote/.omc/research/relay-design/metrics.
# Environment (for tests; defaults are the live paths):
#   RELAY_LOGS_DIR   directory with <device>.log         (default ~/.claude-remote/logs)
#   RELAY_SERVER_DIR directory with drift.log/forget.log (default ~/.claude-remote)
#
# Effects, all under ROOT only:
#   raw/<device>/<YYYY-MM-DD>.log   deduplicated, REDACTED flush batches (key = flush header + batch hash);
#                                   files older than 60 days are deleted
#   .gitignore                      '*' (created once)
#   canaries/server.log             one line per run: UTC time, tmux server pid, drift/forget counters
# The only thing touched outside ROOT is a read-only 'tmux display -p' on the default socket.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="${1:-/home/lucas/claude-remote/.omc/research/relay-design/metrics}"
logs="${RELAY_LOGS_DIR:-$HOME/.claude-remote/logs}"
srv="${RELAY_SERVER_DIR:-$HOME/.claude-remote}"

mkdir -p "$root/raw" "$root/canaries" || exit 1
exec 9>"$root/.snapshot.lock" || { echo "snapshot: cannot open the lock file" >&2; exit 1; }
flock -n 9 || { echo "snapshot: another run holds the lock" >&2; exit 0; }
# raw/ holds (redacted) log copies: keep git away from everything under ROOT
[ -e "$root/.gitignore" ] || printf '*\n' > "$root/.gitignore"

# Server canaries first, so a failing snapshot below still leaves its canary line.
# awk, not grep: grep returned nothing on large files on this box.
pid="$(env -u TMUX -u TMUX_PANE timeout 5 tmux display -p '#{pid}' 2>/dev/null)" || pid=""
[ -n "$pid" ] || pid="unavailable"
count() {
  if [ -r "$srv/$1" ]; then awk -v re="$2" '$0 ~ re {n++} END {print n+0}' "$srv/$1"; else echo "n/a"; fi
}
printf '%s tmux_pid=%s drift_refusing=%s drift_selfheal=%s forget_lines=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$pid" \
  "$(count drift.log 'drift: REFUSING')" "$(count drift.log 'drift: self-heal')" \
  "$(count forget.log '\\] forget: ')" >> "$root/canaries/server.log"

python3 -I "$here/extract_metrics.py" --snapshot "$root" --logs-dir "$logs" || exit 1
