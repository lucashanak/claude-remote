#!/usr/bin/env bash
# Local test for tests-gate.sh with a `gh` shim on PATH (no network, except the optional LIVE case).
# usage: test-tests-gate.sh [GATE_SCRIPT] [WORKFLOW_DIR]
#   GATE_SCRIPT   default: sibling tests-gate.sh (mutation runs pass a mutant)
#   WORKFLOW_DIR  default: <repo>/.github/workflows (mutation runs pass a mutated copy)
#   LIVE=1        also read the real API once against the commit below (read-only, needs `gh auth`)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="${1:-$HERE/tests-gate.sh}"
WFDIR="${2:-$HERE/../../.github/workflows}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
# Shim: logs every call's arguments to $TMP/args. `commits/` queries print $TMP/head, everything else
# prints $TMP/resp.json (what `gh api` returns for the runs list). $TMP/mode: ok | fail | hang.
cat > "$TMP/bin/gh" <<SHIM
#!/usr/bin/env bash
echo "\$*" >> "$TMP/args"
case "\$(cat "$TMP/mode")" in
  fail) echo "gh: HTTP 403: rate limited" >&2; exit 1 ;;
  hang) sleep 300 ;;
esac
case "\$*" in
  *commits/*) cat "$TMP/head" ;;
  *) cat "$TMP/resp.json" ;;
esac
SHIM
chmod +x "$TMP/bin/gh"

fail=0
SHA=abcdef0123456789abcdef0123456789abcdef01
REPO=owner/repo
# gate [env...] -- deadline : runs the gate, sets $out and $rc
gate() {
  local deadline="$1"; shift
  : > "$TMP/args"
  out=$(env PATH="$TMP/bin:$PATH" TESTS_GATE_POLL_SECONDS=1 "$@" timeout 30 bash "$GATE" "$SHA" "$REPO" ${deadline:+"$deadline"} 2>&1); rc=$?
}
check() { # name cond-result detail
  if [ "$2" = 0 ]; then echo "ok   $1"; else echo "FAIL $1: $3"; printf '    %s\n' "${out//$'\n'/$'\n    '}"; fail=1; fi
}
# run NAME EXPECTED_EXIT DEADLINE JSON [OUTPUT_REGEX] [env...]
run() {
  local name="$1" want="$2" deadline="$3" json="$4" rx="${5:-}"; shift 5 2>/dev/null || shift $#
  printf '%s' "$json" > "$TMP/resp.json"; echo ok > "$TMP/mode"; echo "$SHA" > "$TMP/head"
  gate "$deadline" "$@"
  local bad=0
  [ "$rc" -eq "$want" ] || bad=1
  [ -z "$rx" ] || grep -Eq "$rx" <<<"$out" || bad=1
  check "$name" "$bad" "exit $rc (want $want) rx='$rx'"
}
runs() { printf '{"workflow_runs":[%s]}' "$1"; }
R() { printf '{"event":"%s","status":"%s","conclusion":%s,"html_url":"u"}' "$1" "$2" "$3"; }
calls() { wc -l < "$TMP/args"; }

run success        0 5 "$(runs "$(R push completed '"success"')")" 'Tests passed'
run failure        1 5 "$(runs "$(R push completed '"failure"')")" 'refusing to release'
run cancelled      1 5 "$(runs "$(R push completed '"cancelled"')")" 'were cancelled'
run skipped        1 5 "$(runs "$(R push completed '"skipped"')")" 'were skipped'
run null_conclusion 1 5 "$(runs "$(R push completed null)")" 'Tests none for this commit'
run in_progress_then_timeout 1 2 "$(runs "$(R push in_progress null)")" 'Timed out'
run no_run_timeout 1 2 "$(runs "")" 'Timed out'
run non_push_ignored 1 2 "$(runs "$(R workflow_dispatch completed '"success"')")" 'Timed out'
run first_run_wins 1 5 "$(runs "$(R push completed '"failure"'),$(R push completed '"success"')")" 'refusing'

# Deadline is wall-clock.
s=$(date +%s); run deadline_bounded 1 2 "$(runs "")" 'Timed out'; e=$(date +%s)
bad=0; [ $((e - s)) -le 6 ] || bad=1; check deadline_took_le_6s "$bad" "took $((e - s))s"

# The query: the right workflow file and the FULL sha, one request per poll.
run query_url 0 5 "$(runs "$(R push completed '"success"')")" 'Tests passed'
bad=0; grep -qF "repos/$REPO/actions/workflows/tests.yml/runs?head_sha=$SHA" "$TMP/args" || bad=1
check query_url_exact "$bad" "args: $(cat "$TMP/args")"
bad=0; grep -q 'commits/' "$TMP/args" && bad=1
check no_head_query_without_env "$bad" "head queried although TESTS_GATE_HEAD_BRANCH unset"

# Pause between polls: 3 s deadline with 1 s pause allows a handful of calls, not hundreds.
run poll_pause 1 3 "$(runs "")" 'Timed out'
n=$(calls); bad=0; [ "$n" -ge 2 ] && [ "$n" -le 6 ] || bad=1
check poll_pause_bounded "$bad" "$n gh calls in a 3 s deadline"

# Default deadline is 1800 s (the script's own message names it when the deadline is not passed).
bad=0
# shellcheck disable=SC2016
grep -q 'DEADLINE_S="${3:-1800}"' "$GATE" || bad=1
check default_deadline_1800 "$bad" "default deadline not 1800"

# API error: printed, then keep waiting and fail closed with a timeout (not an immediate crash).
printf '%s' "$(runs "")" > "$TMP/resp.json"; echo fail > "$TMP/mode"; echo "$SHA" > "$TMP/head"
gate 2
bad=0; [ "$rc" -eq 1 ] && grep -q 'Timed out' <<<"$out" && grep -q 'API error.*HTTP 403' <<<"$out" || bad=1
check api_error_fail_closed "$bad" "rc=$rc"
n=$(calls); bad=0; [ "$n" -ge 2 ] || bad=1
check api_error_keeps_polling "$bad" "$n calls"

# Garbage (not JSON) with exit 0: also fail closed, with a diagnostic.
echo ok > "$TMP/mode"; printf '<html>502</html>' > "$TMP/resp.json"
gate 2
bad=0; [ "$rc" -eq 1 ] && grep -q 'Timed out' <<<"$out" && grep -q 'API error' <<<"$out" || bad=1
check garbage_response_fail_closed "$bad" "rc=$rc"

# Hung gh: bounded by the per-call timeout, so the gate ends within deadline + call timeout (+ slack).
echo hang > "$TMP/mode"; s=$(date +%s)
gate 2 TESTS_GATE_CALL_TIMEOUT=2
e=$(date +%s); bad=0
{ [ "$rc" -eq 1 ] && grep -q 'Timed out' <<<"$out" && [ $((e - s)) -le 12 ]; } || bad=1
check hung_gh_bounded "$bad" "rc=$rc took $((e - s))s"
bad=0; grep -q 'TESTS_GATE_CALL_TIMEOUT:-60' "$GATE" || bad=1
check default_call_timeout_60 "$bad" "default call timeout not 60"

# Head-of-main check (CI sets TESTS_GATE_HEAD_BRANCH=main on refs/heads/main).
run head_ok 0 5 "$(runs "$(R push completed '"success"')")" 'Tests passed' TESTS_GATE_HEAD_BRANCH=main
bad=0; grep -qF "repos/$REPO/commits/main" "$TMP/args" || bad=1
check head_query_url "$bad" "args: $(cat "$TMP/args")"
printf '%s' "$(runs "$(R push completed '"success"')")" > "$TMP/resp.json"; echo ok > "$TMP/mode"; echo 1111111111111111111111111111111111111111 > "$TMP/head"
gate 5 TESTS_GATE_HEAD_BRANCH=main
bad=0; [ "$rc" -eq 1 ] && grep -q 'not the head of main' <<<"$out" || bad=1
check stale_sha_refused "$bad" "rc=$rc"
# Head query failing counts as stale (fail closed).
printf '%s' "$(runs "$(R push completed '"success"')")" > "$TMP/resp.json"
# (shim variant that fails only the commits/ query)
cat > "$TMP/bin/gh" <<SHIM
#!/usr/bin/env bash
case "\$*" in *commits/*) exit 1 ;; *) cat "$TMP/resp.json" ;; esac
SHIM
gate 5 TESTS_GATE_HEAD_BRANCH=main
bad=0; [ "$rc" -eq 1 ] && grep -q 'not the head of main' <<<"$out" || bad=1
check head_query_error_refused "$bad" "rc=$rc"
# Head query hangs (only commits/ hangs): bounded by the per-call timeout, then refused.
cat > "$TMP/bin/gh" <<SHIM
#!/usr/bin/env bash
case "\$*" in *commits/*) sleep 300 ;; *) cat "$TMP/resp.json" ;; esac
SHIM
s=$(date +%s)
gate 5 TESTS_GATE_HEAD_BRANCH=main TESTS_GATE_CALL_TIMEOUT=2
e=$(date +%s); bad=0
{ [ "$rc" -eq 1 ] && grep -q 'not the head of main' <<<"$out" && [ $((e - s)) -le 12 ]; } || bad=1
check hung_head_query_bounded "$bad" "rc=$rc took $((e - s))s"

# in_progress that turns into success on the second poll.
cat > "$TMP/bin/gh" <<SHIM
#!/usr/bin/env bash
echo x >> "$TMP/args"
if [ "\$(wc -l < "$TMP/args")" -ge 2 ]; then cat "$TMP/done.json"; else cat "$TMP/resp.json"; fi
SHIM
printf '%s' "$(runs "$(R push in_progress null)")" > "$TMP/resp.json"
printf '%s' "$(runs "$(R push completed '"success"')")" > "$TMP/done.json"
gate 20
bad=0; { [ "$rc" -eq 0 ] && grep -q 'Tests passed' <<<"$out"; } || bad=1
check in_progress_then_success "$bad" "rc=$rc"

# Workflow file invariants (a mutation of the YAML must fail here).
# (run from the temp dir: python3 puts the cwd on sys.path; -I would also hide a user-site PyYAML)
wf=$(cd "$TMP" && python3 - "$(cd "$WFDIR" && pwd)/build-and-release.yml" <<'PY'
import sys, yaml
d = yaml.safe_load(open(sys.argv[1]))
errs = []
g = d["concurrency"]["group"]
if "github.ref" not in g: errs.append("concurrency group is not per ref: %r" % g)
if d["concurrency"].get("cancel-in-progress") is not True: errs.append("cancel-in-progress changed")
j = d["jobs"]
if j["release"].get("if") != "github.ref == 'refs/heads/main'": errs.append("release if")
if "tests-passed" not in j["release"]["needs"]: errs.append("release does not need tests-passed")
if j["tests-passed"].get("timeout-minutes") != 35: errs.append("tests-passed timeout-minutes")
steps = " ".join(s.get("run", "") for s in j["tests-passed"]["steps"])
if "scripts/ci/tests-gate.sh" not in steps or "1800" not in steps: errs.append("gate call / 1800")
env = {}
for s in j["tests-passed"]["steps"]: env.update(s.get("env", {}))
want = "${{ github.ref == 'refs/heads/main' && 'main' || '' }}"
if env.get("TESTS_GATE_HEAD_BRANCH") != want: errs.append("TESTS_GATE_HEAD_BRANCH is %r, want %r" % (env.get("TESTS_GATE_HEAD_BRANCH"), want))
print("OK" if not errs else "; ".join(errs))
PY
)
bad=0; [ "$wf" = OK ] || bad=1; out="$wf"; check workflow_invariants "$bad" "$wf"
# tests.yml is already per ref; keep it that way (verified, not changed).
t=$(cd "$TMP" && python3 -c 'import sys,yaml;print(yaml.safe_load(open(sys.argv[1]))["concurrency"]["group"])' "$(cd "$WFDIR" && pwd)/tests.yml")
bad=0; case "$t" in *github.ref*) ;; *) bad=1 ;; esac; out="$t"; check tests_yml_group_per_ref "$bad" "$t"

if [ "${LIVE:-0}" = 1 ]; then
  LSHA=18faa58d38ab6afc09d86cc7d6af911ac3fd962f
  # Real run 42333770710 is the Tests run of this SHA; read-only API call, 60 s deadline.
  out=$(TESTS_GATE_POLL_SECONDS=2 timeout 90 bash "$GATE" "$LSHA" "$(cd "$HERE" && git config --get remote.origin.url | sed -E 's#.*github.com[:/]##; s#\.git$##')" 60 2>&1); rc=$?
  echo "live: exit $rc: $out"
  [ "$rc" -eq 0 ] || fail=1
fi
exit "$fail"
