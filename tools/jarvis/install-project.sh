#!/usr/bin/env bash
# Install Jarvis's project (tools/jarvis/project/) into ~/jarvis: CLAUDE.md, pravidla.md, .claude/settings.json and the
# hooks. Idempotent; safe to run again after a `git pull`.
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
for c in cr-digest cr-sessions; do
  [ -x "$bindir/$c" ] || echo "install-project.sh: warning: $bindir/$c missing; run tools/jarvis/install.sh first" >&2
done

# relative path -> mode; pravidla.md handled apart
FILES=(CLAUDE.md .claude/settings.json .claude/hooks/gate.py .claude/hooks/session-start.sh .claude/hooks/prompt-delta.sh)
declare -A MODE=([CLAUDE.md]=644 [.claude/settings.json]=644 [.claude/hooks/gate.py]=755
                 [.claude/hooks/session-start.sh]=755 [.claude/hooks/prompt-delta.sh]=755 [pravidla.md]=644)
record="$target/.claude/jarvis-installed"

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

kept=0
declare -a newrec=()
for rel in "${FILES[@]}" pravidla.md; do
  new="$tmpd/$(echo "$rel" | tr / _)"
  render "$src/$rel" >"$new"
  dst="$target/$rel"
  if [ ! -e "$dst" ]; then
    if [ "$dry" = 1 ]; then echo "would create $dst"; else put "$new" "$rel"; echo "created   $dst"; fi
    [ "$rel" = pravidla.md ] || newrec+=("$(sha "$new")  $rel")
    continue
  fi
  if [ "$rel" = pravidla.md ]; then
    echo "kept      $dst (Lukáš's file, never updated)"
    continue
  fi
  if cmp -s "$new" "$dst"; then
    if [ "$dry" = 0 ] && [ "$(stat -c %a "$dst")" != "${MODE[$rel]}" ]; then chmod "${MODE[$rel]}" "$dst"; fi
    echo "unchanged $dst"
    newrec+=("$(sha "$new")  $rel")
    continue
  fi
  was="$(recorded "$record" "$rel" || true)"
  if [ -n "$was" ] && [ "$was" = "$(sha "$dst")" ]; then
    if [ "$dry" = 1 ]; then echo "would update $dst"; else put "$new" "$rel"; echo "updated   $dst"; fi
    newrec+=("$(sha "$new")  $rel")
  elif [ "$force" = 1 ]; then
    if [ "$dry" = 1 ]; then echo "would replace $dst (changed by hand)"; else
      bak="$dst.bak.$(date -u +%Y%m%dT%H%M%SZ)"
      cp -p "$dst" "$bak"
      put "$new" "$rel"
      echo "replaced  $dst (changed by hand, old one in $bak)"
    fi
    newrec+=("$(sha "$new")  $rel")
  else
    kept=$((kept + 1))
    echo "KEPT      $dst: changed by hand, not overwritten. Difference to the new template:"
    diff -u --label "$dst (installed)" --label "$rel (template)" "$dst" "$new" || true
    # keep the old record: the file stays "changed by hand" until it is merged or --force is used
    if [ -n "$was" ]; then newrec+=("$was  $rel"); fi
  fi
done

if [ "$dry" = 0 ]; then
  rt="$(mktemp "$target/.claude/.jarvis-tmp.XXXXXX")"
  printf '%s\n' "${newrec[@]}" >"$rt"
  chmod 644 "$rt"
  mv -f "$rt" "$record"
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
- start it without --permission-mode; the project's dontAsk applies.
EOF
