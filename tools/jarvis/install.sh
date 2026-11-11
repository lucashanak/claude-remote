#!/usr/bin/env bash
# Install Jarvis's tools (cr-sessions, cr-digest, jarvis-d; F2a: cr-mail, cr-task; F3: cr-rules, cr-jarvis) for the
# current user.
#
#   tools/jarvis/install.sh [--prefix DIR] [--no-test] [--dry-run]
#
# PREFIX defaults to ~/.local. Layout:
#   PREFIX/lib/jarvis/<UTC stamp>-<sha>/   a COPY of the runtime files (never the test files)
#   PREFIX/lib/jarvis/current              -> that release, swapped atomically (ln + mv -T)
#   PREFIX/bin/{cr-sessions,cr-digest,jarvis-d,cr-mail,cr-task,cr-rules,cr-jarvis} -> PREFIX/lib/jarvis/current/<script>.py
#   PREFIX/lib/jarvis/<release>/SHA256SUMS  what was installed; jarvis-d and `cr-digest brief` report a file that no
#                                           longer matches ("Jarvisovy nástroje se změnily mimo instalaci", F2a
#                                           decision 5: every session has this uid, so this is a tripwire, not a wall)
#
# Why a copy and not links into the checkout: ~50 sessions work on `main` in /home/lucas/claude-remote. A link would
# make every checkout, rebase or half-edited file there the live code of Jarvis's hooks and of a cron job that runs
# every minute; one syntax error would silence Jarvis without a trace. A copy changes only when someone runs this
# script on purpose. Each script finds jarvislib.py next to its own realpath, so one release never mixes with another.
#
# The crontab line is only PRINTED, never written: installing the daemon is a decision for whoever runs this.
# One file outside PREFIX: the mail allowlist <state>/mail-allow (state = $JARVIS_STATE_DIR or ~/.claude-remote/jarvis)
# is created when MISSING, holding only the test session, so a fresh install cannot mail any real session. An
# existing allowlist is Lukáš's and is never touched. Nothing else: no ~/.claude*, no tmux, no systemd.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
prefix="${HOME}/.local"
run_tests=1
dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) prefix="$2"; shift 2 ;;
    --no-test) run_tests=0; shift ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "install.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

FILES=(jarvislib.py jarvismail.py jarviswatch.py jarvisalert.py jarvisops.py jarvisacct.py cr_sessions.py cr_digest.py jarvis_d.py cr_mail.py
       cr_task.py cr_rules.py cr_jarvis.py)
declare -A BIN=([cr-sessions]=cr_sessions.py [cr-digest]=cr_digest.py [jarvis-d]=jarvis_d.py [cr-mail]=cr_mail.py
                [cr-task]=cr_task.py [cr-rules]=cr_rules.py [cr-jarvis]=cr_jarvis.py)
state="${JARVIS_STATE_DIR:-$HOME/.claude-remote/jarvis}"
ALLOW_DEFAULT="claude-server-jarvis-test--jarvis-test"

command -v python3 >/dev/null || { echo "install.sh: python3 not found" >&2; exit 1; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' || { echo "install.sh: python3 >= 3.9 needed" >&2; exit 1; }

if [ "$run_tests" = 1 ]; then
  # hermetic: temp HOME, fake tmux and liveness; TMUX/TMUX_PANE dropped all the same (two F0 incidents came from them)
  env -u TMUX -u TMUX_PANE -u CLAUDE_CONFIG_DIR JARVIS_INSTALL_RUNNING=1 python3 -m unittest discover -s "$here" >/dev/null 2>&1 \
    || { echo "install.sh: tests failed, nothing installed (run: python3 -m unittest discover -s $here -v)" >&2; exit 1; }
fi

# --dirty: a release built from uncommitted files must say so in its name
sha="$(git -C "$here" describe --always --dirty 2>/dev/null || echo nogit)"
# JARVIS_INSTALL_STAMP: tests only (two installs within one second would otherwise share a name)
stamp="${JARVIS_INSTALL_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)-${sha}}"
lib="$prefix/lib/jarvis"
rel="$lib/$stamp"
bin="$prefix/bin"

if [ "$dry" = 1 ]; then
  echo "would copy ${FILES[*]} -> $rel"
  echo "would point $lib/current -> $stamp"
  for b in "${!BIN[@]}"; do echo "would link $bin/$b -> $lib/current/${BIN[$b]}"; done
  [ -e "$state/mail-allow" ] || echo "would create $state/mail-allow with $ALLOW_DEFAULT"
else
  # a release is never rewritten in place: `current` may point at it and a running jarvis-d imports from it
  if [ -e "$rel" ]; then echo "install.sh: $rel already exists, nothing installed" >&2; exit 1; fi
  mkdir -p "$lib" "$bin"
  mkdir "$rel"
  for f in "${FILES[@]}"; do install -m 0644 "$here/$f" "$rel/$f"; done
  for b in "${!BIN[@]}"; do chmod 0755 "$rel/${BIN[$b]}"; done
  (cd "$rel" && sha256sum "${FILES[@]}" >SHA256SUMS && chmod 0444 SHA256SUMS)
  ln -sfn "$stamp" "$lib/current.tmp.$$"
  mv -T "$lib/current.tmp.$$" "$lib/current"
  for b in "${!BIN[@]}"; do
    t="$bin/$b"
    # never replace something that is not ours: only a missing name, or a link that points into $lib/
    if [ -e "$t" ] || [ -L "$t" ]; then
      cur="$(readlink "$t" 2>/dev/null || true)"
      case "$cur" in
        "$lib"/*) ;;
        *) echo "install.sh: $t exists and is not our link, left alone" >&2; continue ;;
      esac
    fi
    ln -sfn "$lib/current/${BIN[$b]}" "$t.tmp.$$" && mv -T "$t.tmp.$$" "$t"
  done
  if [ ! -e "$state/mail-allow" ] && [ ! -L "$state/mail-allow" ]; then
    mkdir -p "$state" && chmod 700 "$state"
    t="$(mktemp "$state/.mail-allow.XXXXXX")"
    printf '# Sessions Jarvis may mail (cr-mail send), one tmux name per line. Written by Lukáš.\n%s\n' "$ALLOW_DEFAULT" >"$t"
    mv -f "$t" "$state/mail-allow"
    echo "created $state/mail-allow (only $ALLOW_DEFAULT)"
  fi
  echo "installed $stamp into $lib (current), commands in $bin"
fi

cat <<EOF

jarvis-d is NOT scheduled by this script. To run it every minute, add this line with 'crontab -e':

* * * * * $bin/jarvis-d >/dev/null 2>&1

(jarvis-d takes its own lock, loops ~55 s with a 5 s tick and writes only under ~/.claude-remote/jarvis/.)
EOF
