#!/usr/bin/env bash
# Herdchestra 0.0.1 release-gate runner.
#
# Runs the eight RUNNING.md gates in order, writes every step's log and summary
# to a fresh plans/evidence/release-gate-<timestamp>/ directory, and stops at the
# first failing step.  It never writes Git state and never creates a tag.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_ROOT="$REPO_ROOT/plans/evidence"
PIXI="${PIXI:-/tmp/pixi}"
SMOKE_PIDS=()

usage() {
  cat <<'USAGE'
Usage: scripts/release-gate.sh [--help]

Runs the 0.0.1 release gates in order and writes evidence to a fresh
plans/evidence/release-gate-<timestamp>/ directory.

Environment:
  PIXI=/path/to/pixi     Pixi binary (default: /tmp/pixi)

Gates:
  1. pixi install --locked
  2. client-install + client-release + artifact hashes
  3. app-check health
  4. real start smoke, SIGTERM, same-data-dir restart
  5. full test (pixi run test)
  6. version consistency (five places)
  7. worktree inventory (recorded, not a command failure)
  8. release notes are not executed; inspect manually

This script performs no Git writes and no tag creation.
USAGE
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  usage
  exit 0
fi
if [ "$#" -gt 0 ]; then
  echo "unknown argument: $1" >&2
  usage >&2
  exit 2
fi
if [ ! -x "$PIXI" ]; then
  if command -v pixi >/dev/null 2>&1; then
    PIXI="$(command -v pixi)"
  else
    echo "pixi not found: set PIXI=/path/to/pixi or install pixi" >&2
    exit 2
  fi
fi

TS="$(date +%Y%m%d-%H%M%S)"
EVIDENCE="$EVIDENCE_ROOT/release-gate-$TS"
suffix=1
while [ -e "$EVIDENCE" ]; do
  EVIDENCE="$EVIDENCE_ROOT/release-gate-$TS-$suffix"
  suffix=$((suffix + 1))
done
mkdir -p "$EVIDENCE"
SUMMARY="$EVIDENCE/summary.tsv"
printf 'step\texit\telapsed_seconds\tlog\n' > "$SUMMARY"
STEP_NO=0
LAST_LOG=""
GATE_START="$(date +%s)"

cleanup() {
  local pid
  for pid in "${SMOKE_PIDS[@]:-}"; do
    if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT

fail() {
  local name="$1"
  local log="${2:-}"
  echo
  echo "GATE FAILED at step: $name"
  echo "Evidence directory: $EVIDENCE"
  [ -n "$log" ] && echo "Step log: $log"
  if [ -n "$log" ] && [ -f "$log" ]; then
    echo "--- last 40 lines ---"
    tail -40 "$log" || true
  fi
  exit 1
}

run_step() {
  local name="$1"
  shift
  STEP_NO=$((STEP_NO + 1))
  local log
  log="$(printf '%s/%02d-%s.log' "$EVIDENCE" "$STEP_NO" "$name")"
  LAST_LOG="$log"
  echo "[gate] $name"
  local start end code
  start="$(date +%s)"
  if "$@" >"$log" 2>&1; then
    code=0
  else
    code=$?
  fi
  end="$(date +%s)"
  printf '%s\t%s\t%s\t%s\n' "$name" "$code" "$((end - start))" "$log" >> "$SUMMARY"
  echo "[gate] $name exit=$code elapsed=$((end - start))s"
  if [ "$code" -ne 0 ]; then
    fail "$name" "$log"
  fi
}

start_app() {
  local log="$1"
  nohup "$PIXI" run clojure -M:server -m hc.server.app \
    --host 127.0.0.1 --port 0 \
    --data-dir "$SMOKE/data" \
    --workspace-root "$SMOKE/work" \
    --static-root resources/public >"$log" 2>&1 &
  echo $!
}

wait_url() {
  local log="$1" url=""
  local i
  for i in $(seq 1 120); do
    url="$(grep -o 'http://127.0.0.1:[0-9]*' "$log" 2>/dev/null | head -1 || true)"
    if [ -n "$url" ]; then
      echo "$url"
      return 0
    fi
    sleep 1
  done
  return 1
}

stop_app() {
  local pid="$1" url="$2"
  kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  local i
  for i in $(seq 1 30); do
    if ! curl -fsS --max-time 1 "${url%/}/healthz" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 0
}

echo "Herdchestra release gate"
echo "repo: $REPO_ROOT"
echo "pixi: $PIXI"
echo "evidence: $EVIDENCE"
{
  echo "started_at=$(date -Is)"
  echo "repo=$REPO_ROOT"
  echo "pixi=$PIXI"
  echo "pixi_version=$("$PIXI" --version 2>&1 | head -1)"
  echo "head=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || true)"
  echo "branch=$(git -C "$REPO_ROOT" branch --show-current 2>/dev/null || true)"
} > "$EVIDENCE/env.txt"

cd "$REPO_ROOT"

# 1. Locked install
run_step install "$PIXI" install --locked

# 2. Client dependencies and release build
run_step client-install "$PIXI" run client-install
run_step client-release "$PIXI" run client-release
MAIN="$REPO_ROOT/resources/public/js/main.js"
CSS="$REPO_ROOT/resources/public/css/framework7-bundle.css"
if [ ! -s "$MAIN" ] || [ ! -s "$CSS" ]; then
  fail "client-release-artifacts" "$LAST_LOG"
fi
{
  stat -c '%n size=%s mtime=%y' "$MAIN" "$CSS"
  sha256sum "$MAIN" "$CSS"
} > "$EVIDENCE/artifacts.txt"

# 3. app-check
run_step app-check "$PIXI" run app-check
grep -oE ':ok (true|false)|:version "[^"]*"|:store \{[^}]*\}|:gateway \{[^}]*\}|:models \{[^}]*\}' "$LAST_LOG" \
  > "$EVIDENCE/app-check-health.txt" || true
if ! grep -q ':ok true' "$EVIDENCE/app-check-health.txt"; then
  fail "app-check-health" "$LAST_LOG"
fi

# 4. Real start smoke, SIGTERM, same-data-dir restart
SMOKE_START="$(date +%s)"
SMOKE="$EVIDENCE/smoke"
mkdir -p "$SMOKE/data" "$SMOKE/work"
PID1="$(start_app "$SMOKE/start1.log")"
SMOKE_PIDS+=("$PID1")
URL1="$(wait_url "$SMOKE/start1.log")" || fail "real-start-first" "$SMOKE/start1.log"
BASE1="${URL1%/}"
echo "$URL1" > "$SMOKE/url1.txt"
curl -fsS --max-time 10 "$BASE1/healthz" > "$SMOKE/health1.edn" || fail "real-start-health1" "$SMOKE/start1.log"
curl -fsS --max-time 10 "$BASE1/" > "$SMOKE/root1.html" || fail "real-start-root1" "$SMOKE/start1.log"
grep -q '<title>Herdchestra' "$SMOKE/root1.html" || fail "real-start-root1-title" "$SMOKE/start1.log"
grep -q ':store {:open? true' "$SMOKE/health1.edn" || fail "real-start-health1-store" "$SMOKE/start1.log"
grep -q ':gateway' "$SMOKE/health1.edn" || fail "real-start-health1-gateway" "$SMOKE/start1.log"
stop_app "$PID1" "$URL1"

PID2="$(start_app "$SMOKE/start2.log")"
SMOKE_PIDS+=("$PID2")
URL2="$(wait_url "$SMOKE/start2.log")" || fail "real-start-second" "$SMOKE/start2.log"
BASE2="${URL2%/}"
echo "$URL2" > "$SMOKE/url2.txt"
curl -fsS --max-time 10 "$BASE2/healthz" > "$SMOKE/health2.edn" || fail "real-start-health2" "$SMOKE/start2.log"
curl -fsS --max-time 10 "$BASE2/" > "$SMOKE/root2.html" || fail "real-start-root2" "$SMOKE/start2.log"
grep -q ':store {:open? true' "$SMOKE/health2.edn" || fail "real-start-health2-store" "$SMOKE/start2.log"
grep -q ':sessions {:count 1}' "$SMOKE/health2.edn" || fail "real-start-health2-sessions" "$SMOKE/start2.log"
stop_app "$PID2" "$URL2"
SMOKE_END="$(date +%s)"
printf 'real-start\t0\t%s\t%s\n' "$((SMOKE_END - SMOKE_START))" "$SMOKE/start1.log" >> "$SUMMARY"

# 5. Full test
run_step test "$PIXI" run test
TEST_LOG="$LAST_LOG"
grep -E 'Ran [0-9]+ tests containing [0-9]+ assertions|^[0-9]+ failures, [0-9]+ errors' "$TEST_LOG" \
  > "$EVIDENCE/test-summary.txt" || true
grep -q '^0 failures, 0 errors' "$TEST_LOG" || fail "test-failures" "$TEST_LOG"

# 6. Version consistency
VERSION_START="$(date +%s)"
VERSION_CLJ="$(sed -n 's/.*def version "\([^"]*\)".*/\1/p' src/hc/version.cljc | head -1)"
VERSION_PIXI="$(sed -n 's/^version = "\([^"]*\)".*/\1/p' pixi.toml | head -1)"
VERSION_PACKAGE="$(sed -n 's/.*"version": "\([^"]*\)".*/\1/p' package.json | head -1)"
run_step help "$PIXI" run clojure -M:server -m hc.server.app --help
VERSION_HELP="$(grep -o 'Herdchestra [^ ]* server' "$LAST_LOG" | head -1 | awk '{print $2}')"
VERSION_HEALTH="$(grep -o ':version "[^"]*"' "$EVIDENCE/app-check-health.txt" | head -1 | sed 's/:version "//;s/"$//')"
{
  echo "version.cljc=$VERSION_CLJ"
  echo "pixi.toml=$VERSION_PIXI"
  echo "package.json=$VERSION_PACKAGE"
  echo "--help=$VERSION_HELP"
  echo "health=$VERSION_HEALTH"
} > "$EVIDENCE/versions.txt"
for pair in \
  "version.cljc:$VERSION_CLJ" \
  "pixi.toml:$VERSION_PIXI" \
  "package.json:$VERSION_PACKAGE" \
  "--help:$VERSION_HELP" \
  "health:$VERSION_HEALTH"; do
  value="${pair#*:}"
  if [ "$value" != "0.0.1" ]; then
    fail "version-consistency:${pair%%:*}" "$EVIDENCE/versions.txt"
  fi
done
VERSION_END="$(date +%s)"
printf 'version-consistency\t0\t%s\t%s\n' "$((VERSION_END - VERSION_START))" "$EVIDENCE/versions.txt" >> "$SUMMARY"

# 7. Worktree inventory (recorded, not a failure by itself)
WT_START="$(date +%s)"
git status --porcelain -uall > "$EVIDENCE/git-status-porcelain.txt"
TRACKED="$(git ls-files | wc -l)"
UNTRACKED="$(git status --porcelain -uall | wc -l)"
TOPLEVEL="$(git status --porcelain | wc -l)"
{
  echo "top_level_entries=$TOPLEVEL"
  echo "untracked_files=$UNTRACKED"
  echo "tracked_files=$TRACKED"
  (git status --porcelain --ignored | grep -c '^!!' || true) | sed 's/^/ignored_entries=/'
} > "$EVIDENCE/worktree.txt"
WT_END="$(date +%s)"
printf 'worktree\t0\t%s\t%s\n' "$((WT_END - WT_START))" "$EVIDENCE/worktree.txt" >> "$SUMMARY"

# Summary
GATE_END="$(date +%s)"
TOTAL="$((GATE_END - GATE_START))"
{
  echo "PASS"
  echo "evidence=$EVIDENCE"
  echo "elapsed_seconds=$TOTAL"
  echo "tests=$(grep -oE 'Ran [0-9]+ tests containing [0-9]+ assertions' "$TEST_LOG" | tail -1)"
  echo "worktree_untracked=$UNTRACKED"
  echo "worktree_tracked=$TRACKED"
} > "$EVIDENCE/PASS.txt"

echo
echo "PASS: all release gates completed"
echo "Evidence: $EVIDENCE"
echo "Summary:"
cat "$SUMMARY"
echo "Tests: $(grep -oE 'Ran [0-9]+ tests containing [0-9]+ assertions' "$TEST_LOG" | tail -1)"
echo "Worktree: tracked=$TRACKED untracked=$UNTRACKED top_level=$TOPLEVEL"
if [ "$UNTRACKED" -gt 0 ]; then
  echo "WARNING: worktree still has $UNTRACKED untracked files; gate 6 is not clean."
fi
exit 0
