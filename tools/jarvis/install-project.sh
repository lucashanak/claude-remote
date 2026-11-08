#!/usr/bin/env bash
# Install Jarvis's project (tools/jarvis/project/) into ~/jarvis: CLAUDE.md, pravidla.md, .claude/settings.json and the
# hooks. Idempotent; safe to run again after a `git pull`.
#
# The template keeps Jarvis's instructions as project/CLAUDE.jarvis.md and installs them as CLAUDE.md: a file named
# CLAUDE.md inside the repo would be loaded lazily (nested memory) into every working session that reads a file under
# tools/jarvis/project/, and they would start taking Jarvis's rules as their own.
#
#   tools/jarvis/install-project.sh [--target DIR] [--bin DIR] [--python PATH] [--force] [--dry-run]
#
#   --target  where the project goes                      (default ~/jarvis)
#   --bin     where cr-digest / cr-sessions are installed  (default ~/.local/bin, see install.sh)
#   --python  python3 for the gate, absolute               (default /usr/bin/python3, else the one on PATH)
#   --force   overwrite files Lukáš changed (the old one is kept as <file>.bak.<stamp>)
#
# Templates hold @HOME@, @JARVIS@, @BIN@ and @PY@; they are replaced with absolute paths here, so every hook command
# points at the installed file and nothing depends on PATH or on the cwd Claude Code happens to run hooks in.
#
# What counts as "changed by Lukáš": <target>/.claude/jarvis-installed records the sha256 of every file this script
# wrote. A file that still matches that record is ours and is updated to the new template; a file that does not
# match (or has no record) was edited by hand: it is left alone and the diff to the new template is printed.
# pravidla.md is Lukáš's from the start: created when missing, never compared, never touched again.
#
# Modes: hooks 755, everything else 644. Nothing outside --target is written: no ~/.claude*, no tmux, no trust dialog.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$here/project"
target="$HOME/jarvis"
bindir="$HOME/.local/bin"
py=""
force=0
dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --target) target="$2"; shift 2 ;;
    --bin) bindir="$2"; shift 2 ;;
    --python) py="$2"; shift 2 ;;
    --force) force=1; shift ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "install-project.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

if [ -z "$py" ]; then
  if [ -x /usr/bin/python3 ]; then py=/usr/bin/python3; else py="$(command -v python3 || true)"; fi
fi

# Paths go into JSON strings and into "…"-quoted shell words: refuse anything that would need escaping there.
for v in "$HOME" "$target" "$bindir" "$py"; do
  case "$v" in
    /*) ;;
    *) echo "install-project.sh: path must be absolute: '$v'" >&2; exit 2 ;;
  esac
  case "$v" in
    *[\"\\\$\`]* | *$'\n'*) echo "install-project.sh: path has a character I will not quote: '$v'" >&2; exit 2 ;;
  esac
done
[ -x "$py" ] || { echo "install-project.sh: python3 not executable: $py" >&2; exit 1; }
for c in cr-digest cr-sessions cr-mail cr-task cr-rules cr-jarvis; do
  [ -x "$bindir/$c" ] || echo "install-project.sh: warning: $bindir/$c missing; run tools/jarvis/install.sh first" >&2
done

# Never write through a symlink: a linked ~/jarvis/.claude/hooks would put the gate wherever the link points, and a
# link left by anyone with this uid could redirect our writes. The real directories or nothing.
for d in "$target" "$target/.claude" "$target/.claude/hooks"; do
  if [ -L "$d" ]; then echo "install-project.sh: $d is a symlink, refusing to install through it" >&2; exit 2; fi
done

# installed path -> template path (only CLAUDE.md differs, see the header)
srcof() { if [ "$1" = CLAUDE.md ]; then echo CLAUDE.jarvis.md; else echo "$1"; fi; }

# relative path -> mode; pravidla.md handled apart
FILES=(CLAUDE.md .claude/settings.json .claude/hooks/gate.py .claude/hooks/session-start.sh .claude/hooks/prompt-delta.sh)
declare -A MODE=([CLAUDE.md]=644 [.claude/settings.json]=644 [.claude/hooks/gate.py]=755
                 [.claude/hooks/session-start.sh]=755 [.claude/hooks/prompt-delta.sh]=755 [pravidla.md]=644)
record="$target/.claude/jarvis-installed"
# checked before anything is written, so a refusal never leaves a half-installed project
for rel in "${FILES[@]}" pravidla.md .claude/jarvis-installed; do
  if [ -L "$target/$rel" ]; then
    echo "install-project.sh: $target/$rel is a symlink, refusing to write through it" >&2; exit 2
  fi
done

esc() { printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'; }
render() {
  sed -e "s|@HOME@|$(esc "$HOME")|g" -e "s|@JARVIS@|$(esc "$target")|g" \
      -e "s|@BIN@|$(esc "$bindir")|g" -e "s|@PY@|$(esc "$py")|g" "$1"
}
sha() { sha256sum "$1" | cut -d' ' -f1; }
recorded() { [ -f "$record" ] && awk -v p="$2" '$2 == p { print $1 }' "$1" | tail -n 1; }

tmpd="$(mktemp -d)"
trap 'rm -rf "$tmpd"' EXIT

# put <rendered> at <rel> atomically (tmp + rename in the destination directory)
put() {
  local new=$1 rel=$2 dst="$target/$2" t
  mkdir -p "$(dirname "$dst")"
  t="$(mktemp "$(dirname "$dst")/.jarvis-tmp.XXXXXX")"
  cat "$new" >"$t"
  chmod "${MODE[$rel]}" "$t"
  mv -f "$t" "$dst"
}

# record <rel> <sha>: set this file's line in the record right after the file is written, before anything is
# printed. A run that dies halfway (Ctrl-C, or SIGPIPE when its output goes to `| head`) then never leaves a file
# that we wrote but did not record, which the next run would wrongly report as changed by hand.
rec() {
  [ "$dry" = 1 ] && return 0
  local t
  mkdir -p "$(dirname "$record")"
  t="$(mktemp "$(dirname "$record")/.jarvis-tmp.XXXXXX")"
  { [ -f "$record" ] && awk -v p="$1" '$2 != p' "$record"; printf '%s  %s\n' "$2" "$1"; } >"$t"
  chmod 644 "$t"
  mv -f "$t" "$record"
}

kept=0
for rel in "${FILES[@]}" pravidla.md; do
  new="$tmpd/$(echo "$rel" | tr / _)"
  render "$src/$(srcof "$rel")" >"$new"
  dst="$target/$rel"
  if [ ! -e "$dst" ]; then
    if [ "$dry" = 1 ]; then echo "would create $dst"; else
      put "$new" "$rel"
      [ "$rel" = pravidla.md ] || rec "$rel" "$(sha "$new")"
      echo "created   $dst"
    fi
    continue
  fi
  if [ "$rel" = pravidla.md ]; then
    echo "kept      $dst (Lukáš's file, never updated)"
    continue
  fi
  if cmp -s "$new" "$dst"; then
    if [ "$dry" = 0 ] && [ "$(stat -c %a "$dst")" != "${MODE[$rel]}" ]; then chmod "${MODE[$rel]}" "$dst"; fi
    rec "$rel" "$(sha "$new")"
    echo "unchanged $dst"
    continue
  fi
  was="$(recorded "$record" "$rel" || true)"
  if [ -n "$was" ] && [ "$was" = "$(sha "$dst")" ]; then
    if [ "$dry" = 1 ]; then echo "would update $dst"; else
      put "$new" "$rel"
      rec "$rel" "$(sha "$new")"
      echo "updated   $dst"
    fi
  elif [ "$force" = 1 ]; then
    if [ "$dry" = 1 ]; then echo "would replace $dst (changed by hand)"; else
      bak="$dst.bak.$(date -u +%Y%m%dT%H%M%SZ)"
      cp -p "$dst" "$bak"
      put "$new" "$rel"
      rec "$rel" "$(sha "$new")"
      echo "replaced  $dst (changed by hand, old one in $bak)"
    fi
  else
    kept=$((kept + 1))
    echo "KEPT      $dst: changed by hand, not overwritten. Difference to the new template:"
    diff -u --label "$dst (installed)" --label "$rel (template)" "$dst" "$new" || true
    # the record keeps its old line: the file stays "changed by hand" until it is merged or --force is used
  fi
done

# settings.local.json is merged over settings.json by Claude Code: one allow rule there widens Jarvis past dontAsk
local_settings="$target/.claude/settings.local.json"
if [ -e "$local_settings" ] || [ -L "$local_settings" ]; then
  echo
  echo "WARNING: $local_settings exists. Claude Code merges it over settings.json, so its allow rules,"
  echo "         hooks or defaultMode weaken Jarvis's permissions. Remove it unless you mean it."
fi

if [ "$kept" -gt 0 ]; then
  echo
  echo "$kept file(s) kept as Lukáš left them. Merge by hand, or run again with --force (keeps a .bak)."
fi
cat <<EOF

One-time steps that this script does not do:
- start Claude in $target once and confirm the trust dialog: before that no project hook runs and the session hangs
  on the dialog (f1-spike 1c, 2d);
- the session name must not end in -yolo (restore would start it with --dangerously-skip-permissions);
- its mode in the session manifest must be NORMAL: restore.sh adds --permission-mode auto / acceptEdits for AUTO and
  AUTO_ACCEPT, a CLI flag beats the project's dontAsk, and the tool gate then blocks every tool (safe, but useless);
- start Claude directly in Jarvis's own tmux session (as the app does), never from a shell that inherited TMUX or
  TMUX_PANE from another pane: the SessionStart hook writes the name of the session TMUX_PANE points at into
  ~/.claude-remote/jarvis/self, and a stale pane would make the digest hide that other session instead of Jarvis.
EOF
