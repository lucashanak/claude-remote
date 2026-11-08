#!/usr/bin/env bash
# Release gate: wait for the Tests workflow run of one commit and succeed only if it succeeded.
#
# usage: tests-gate.sh SHA REPO [DEADLINE_SECONDS=1800]
#   TESTS_GATE_POLL_SECONDS   pause between polls (default 10)
#   TESTS_GATE_CALL_TIMEOUT   limit of one `gh api` call in seconds (default 60)
#   TESTS_GATE_HEAD_BRANCH    if set (CI sets `main` on refs/heads/main): SHA must still be the head
#                             of that branch, else the gate fails ("not the head of main")
#
# Exit 0: the push-triggered Tests run for SHA concluded `success` (and SHA is the branch head, if asked).
# Exit 1: it concluded anything else (cancelled/skipped included: a cancelled run is not a verified
#         commit), the SHA is stale, or no completed run appeared before the wall-clock deadline.
# The deadline is real time, and every `gh` call is bounded, so neither slow nor hung calls stretch it
# past DEADLINE + CALL_TIMEOUT. API errors are printed and treated as "no result yet" (fail closed).
set -euo pipefail

SHA="${1:?usage: tests-gate.sh SHA REPO [DEADLINE_SECONDS]}"
REPO="${2:?usage: tests-gate.sh SHA REPO [DEADLINE_SECONDS]}"
DEADLINE_S="${3:-1800}"
POLL_S="${TESTS_GATE_POLL_SECONDS:-10}"
CALL_S="${TESTS_GATE_CALL_TIMEOUT:-60}"
HEAD_BRANCH="${TESTS_GATE_HEAD_BRANCH:-}"

API="repos/${REPO}/actions/workflows/tests.yml/runs?head_sha=${SHA}"
END=$(( $(date +%s) + DEADLINE_S ))
ERR=$(mktemp)
trap 'rm -f "$ERR"' EXIT
n=0

# Prints the first line of what went wrong with a gh/jq call.
report_api_error() {
  echo "::warning::API error (poll $n): $(head -n 1 "$ERR" 2>/dev/null || true) [$1]"
}

while :; do
  n=$((n + 1))
  RUN=""
  if RAW=$(timeout "$CALL_S" gh api "$API" 2>"$ERR"); then
    if ! RUN=$(jq -c '[.workflow_runs[] | select(.event=="push")] | first // empty' <<<"$RAW" 2>"$ERR"); then
      RUN=""; report_api_error "unparsable response"
    fi
  else
    report_api_error "gh exit $?"
  fi
  if [ -n "$RUN" ]; then
    STATUS=$(jq -r '.status' <<<"$RUN")
    CONCL=$(jq -r '.conclusion // "none"' <<<"$RUN")
    URL=$(jq -r '.html_url' <<<"$RUN")
    if [ "$STATUS" = "completed" ]; then
      case "$CONCL" in
        success)
          if [ -n "$HEAD_BRANCH" ]; then
            HEAD=$(timeout "$CALL_S" gh api "repos/${REPO}/commits/${HEAD_BRANCH}" --jq .sha 2>"$ERR" || true)
            if [ "$HEAD" != "$SHA" ]; then
              echo "::error::${SHA} is not the head of ${HEAD_BRANCH} (head: ${HEAD:-unknown}) - refusing to release stale code. $URL"
              exit 1
            fi
          fi
          echo "Tests passed for ${SHA} - $URL"
          exit 0 ;;
        cancelled|skipped)
          # A newer push superseded this commit; that run's own concurrency group cancels this
          # release too. Never publish off an unverified commit.
          echo "::error::Tests were $CONCL for this commit - $URL"
          exit 1 ;;
        *)
          echo "::error::Tests $CONCL for this commit - refusing to release. $URL"
          exit 1 ;;
      esac
    fi
    echo "Tests $STATUS (poll $n) - $URL"
  else
    echo "waiting for the Tests run for ${SHA} to appear (poll $n)"
  fi
  if [ "$(date +%s)" -ge "$END" ]; then
    echo "::error::Timed out after ${DEADLINE_S}s waiting for the Tests workflow on ${SHA}."
    exit 1
  fi
  sleep "$POLL_S"
done
