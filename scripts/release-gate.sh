#!/usr/bin/env bash
# Herdchestra 0.0.3 release-gate runner.
#
# Runs the release gates in order, writes every step's log and summary to a
# fresh plans/evidence/release-gate-<timestamp>/ directory, and stops at the
# first failing step.  It never writes Git state and never creates a tag.
#
# The expected version is read from src/hc/version.cljc, never hard-coded, so
# the next version bump cannot silently pass a stale gate.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_ROOT="$REPO_ROOT/plans/evidence"
PIXI="${PIXI:-/tmp/pixi}"
SMOKE_PIDS=()

VERSION_FILE="$REPO_ROOT/src/hc/version.cljc"
EXPECTED_VERSION="$(sed -n 's/.*(def version "\([^"]*\)".*/\1/p' "$VERSION_FILE" | head -1)"
if [ -z "$EXPECTED_VERSION" ]; then
  echo "cannot read (def version ...) from $VERSION_FILE" >&2
  exit 2
fi

usage() {
  cat <<'USAGE'
Usage: scripts/release-gate.sh [--help]

Runs the Herdchestra release gates in order and writes evidence to a fresh
plans/evidence/release-gate-<timestamp>/ directory.  The expected version is
parsed from src/hc/version.cljc.

Environment:
  PIXI=/path/to/pixi     Pixi binary (default: /tmp/pixi)
  HC_GATE_E2E=1          Also run `npm run e2e` (22 specs) and the three
                         benchmarks.  Default is skip: E2E is long and needs
                         a browser.
  HC_CLOJURE=/path/clj   Clojure binary for scripts/test-sharded.sh
  HC_GATE_STEP_TIMEOUT_SECONDS=1800    default wall clock per step
  HC_GATE_SHARD_TIMEOUT_SECONDS=15000  whole four-shard step (must exceed
                                       4 * HC_SHARD_TIMEOUT_SECONDS)
  HC_GATE_E2E_TIMEOUT_SECONDS=5400     e2e step
  HC_GATE_BENCH_TIMEOUT_SECONDS=3600   each benchmark step
  HC_GATE_CLJS_TIMEOUT_SECONDS=7200    CLJS harness step

Gates:
  1. pixi install --locked
  2. client-install + client-release + artifact hashes
  3. app-check health: :ok true, expected :version, real :git-hash,
     present :git-dirty?
  4. real start smoke, SIGTERM, same-data-dir restart
  5. full test: scripts/test-sharded.sh (four shards, exact summary header,
     per-shard tests/assertions > 0, tests total >= 400, failures/errors 0,
     each shard peak VmHWM <= 2 GiB, PRE=POST over src/test/resources plus
     the root manifests and shard scripts) or `pixi run test` fallback
  6. CLJS DOM gate: `bash scripts/test-cljs-gate.sh` (soft skip when the
     script is not present; this is a default-path gate, not an E2E one)
  7. E2E and benchmarks (only when HC_GATE_E2E=1)
  8. version consistency: version.cljc = pixi.toml = package.json =
     package-lock.json (both root fields) = --version = health
  9. release notes for the expected version must exist and be non-empty
  10. worktree inventory; product source/test/scripts/resources, the root
     build manifests, versioned plans, AGENTS.md, HACK.md, DEBT.md and
     RUNNING.md must be clean.  plans/evidence/** is excluded because this
     gate writes fresh evidence there on every run.

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
  local limit start end code
  limit="$(step_timeout_seconds "$name")"
  case "$limit" in
    ''|*[!0-9]*|0)
      echo "invalid timeout for step $name: '$limit' (want a positive integer)" >&2
      exit 2
      ;;
  esac
  start="$(date +%s)"
  # bash -c keeps exported shell helpers (run_node / run_npm) callable under
  # timeout; timeout signals the whole process group, so a hung child does
  # not survive its step.
  if timeout -k 30 "$limit" bash -c '"$@"' _ "$@" >"$log" 2>&1; then
    code=0
  else
    code=$?
  fi
  end="$(date +%s)"
  printf '%s\t%s\t%s\t%s\n' "$name" "$code" "$((end - start))" "$log" >> "$SUMMARY"
  echo "[gate] $name exit=$code elapsed=$((end - start))s timeout=${limit}s"
  if [ "$code" -ne 0 ]; then
    if [ "$code" -eq 124 ] || [ "$code" -eq 137 ]; then
      echo "[gate] $name TIMEOUT after ${limit}s; last output: $log" >&2
      fail "$name (TIMEOUT ${limit}s)" "$log"
    fi
    fail "$name" "$log"
  fi
}

run_node() {
  if command -v node >/dev/null 2>&1; then
    node "$@"
  else
    "$PIXI" run node "$@"
  fi
}

run_npm() {
  if command -v npm >/dev/null 2>&1; then
    npm "$@"
  else
    "$PIXI" run npm "$@"
  fi
}
# The helpers run inside `bash -c` under timeout, so PIXI must be in the
# child environment too.
export PIXI
export -f run_node run_npm

step_timeout_seconds() {
  case "$1" in
    test-sharded|test)
      printf '%s' "${HC_GATE_SHARD_TIMEOUT_SECONDS:-15000}"
      ;;
    e2e)
      printf '%s' "${HC_GATE_E2E_TIMEOUT_SECONDS:-5400}"
      ;;
    bench-*)
      printf '%s' "${HC_GATE_BENCH_TIMEOUT_SECONDS:-3600}"
      ;;
    cljs-gate)
      printf '%s' "${HC_GATE_CLJS_TIMEOUT_SECONDS:-7200}"
      ;;
    *)
      printf '%s' "${HC_GATE_STEP_TIMEOUT_SECONDS:-1800}"
      ;;
  esac
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
echo "expected version: $EXPECTED_VERSION"
echo "pixi: $PIXI"
echo "evidence: $EVIDENCE"
{
  echo "started_at=$(date -Is)"
  echo "repo=$REPO_ROOT"
  echo "pixi=$PIXI"
  echo "pixi_version=$("$PIXI" --version 2>&1 | head -1)"
  echo "expected_version=$EXPECTED_VERSION"
  echo "hc_gate_e2e=${HC_GATE_E2E:-0}"
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

# 3. app-check: health must name the expected version and a real tree.
run_step app-check "$PIXI" run app-check
grep -oE ':ok (true|false)|:version "[^"]*"|:git-hash "[^"]*"|:git-dirty\? (true|false)|:store \{[^}]*\}|:gateway \{[^}]*\}|:models \{[^}]*\}' "$LAST_LOG" \
  > "$EVIDENCE/app-check-health.txt" || true
if ! grep -q ':ok true' "$EVIDENCE/app-check-health.txt"; then
  fail "app-check-health" "$LAST_LOG"
fi
if ! grep -q ":version \"$EXPECTED_VERSION\"" "$EVIDENCE/app-check-health.txt"; then
  fail "app-check-version" "$LAST_LOG"
fi
if ! grep -q ':git-hash "' "$EVIDENCE/app-check-health.txt"; then
  fail "app-check-git-hash-missing" "$LAST_LOG"
fi
if grep -q ':git-hash "unknown"' "$EVIDENCE/app-check-health.txt"; then
  fail "app-check-git-hash-unknown" "$LAST_LOG"
fi
if ! grep -q ':git-dirty? ' "$EVIDENCE/app-check-health.txt"; then
  fail "app-check-git-dirty-missing" "$LAST_LOG"
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
grep -q ":version \"$EXPECTED_VERSION\"" "$SMOKE/health1.edn" || fail "real-start-health1-version" "$SMOKE/start1.log"
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

# 5. Full test.  Prefer the four-shard runner: it keeps one JVM per shard,
# records each shard's peak VmHWM, and enforces the PRE=POST tree hash gate.
#
# The summary header is validated verbatim, then every value is read by
# column name.  A `?`, a zero, or a timeout is a failure here: it means the
# shard did not produce a complete report and must never look green.
TEST_MODE=""
TEST_SUMMARY_LINE=""
if [ -x scripts/test-sharded.sh ]; then
  TEST_MODE="sharded"
  run_step test-sharded bash scripts/test-sharded.sh --out "$EVIDENCE/shards"
  SHARD_SUMMARY="$EVIDENCE/shards/summary.tsv"
  if [ ! -s "$SHARD_SUMMARY" ]; then
    fail "test-sharded-summary" "$LAST_LOG"
  fi
  SHARD_HEADER_EXPECTED="$(printf 'shard\tnamespaces\tseconds\texit\tpeak_rss_kb\trss_procs\ttests\tassertions\tfailures\terrors\tlast_ns')"
  SHARD_HEADER_ACTUAL="$(sed -n '1p' "$SHARD_SUMMARY")"
  if [ "$SHARD_HEADER_ACTUAL" != "$SHARD_HEADER_EXPECTED" ]; then
    {
      printf 'expected: %s\n' "$SHARD_HEADER_EXPECTED"
      printf 'actual:   %s\n' "$SHARD_HEADER_ACTUAL"
    } > "$EVIDENCE/shards/header-mismatch.txt"
    fail "test-sharded-header" "$EVIDENCE/shards/header-mismatch.txt"
  fi
  IFS=$'\t' read -r -a SHARD_COLS <<< "$SHARD_HEADER_ACTUAL"
  shard_col() {
    local want="$1" i
    for i in "${!SHARD_COLS[@]}"; do
      if [ "${SHARD_COLS[$i]}" = "$want" ]; then
        printf '%s' "$((i + 1))"
        return 0
      fi
    done
    return 1
  }
  C_NS="$(shard_col namespaces)" || fail "test-sharded-header-column:namespaces" "$SHARD_SUMMARY"
  C_SECONDS="$(shard_col seconds)" || fail "test-sharded-header-column:seconds" "$SHARD_SUMMARY"
  C_EXIT="$(shard_col exit)" || fail "test-sharded-header-column:exit" "$SHARD_SUMMARY"
  C_PEAK="$(shard_col peak_rss_kb)" || fail "test-sharded-header-column:peak_rss_kb" "$SHARD_SUMMARY"
  C_TESTS="$(shard_col tests)" || fail "test-sharded-header-column:tests" "$SHARD_SUMMARY"
  C_ASSERTIONS="$(shard_col assertions)" || fail "test-sharded-header-column:assertions" "$SHARD_SUMMARY"
  C_FAILURES="$(shard_col failures)" || fail "test-sharded-header-column:failures" "$SHARD_SUMMARY"
  C_ERRORS="$(shard_col errors)" || fail "test-sharded-header-column:errors" "$SHARD_SUMMARY"
  C_LAST_NS="$(shard_col last_ns)" || fail "test-sharded-header-column:last_ns" "$SHARD_SUMMARY"

  PARSE_ERRORS="$EVIDENCE/shards/parse-errors.txt"
  : > "$PARSE_ERRORS"
  row_bad() {
    local reason="$1"
    {
      printf 'reason: %s\n' "$reason"
      printf 'header: %s\n' "$SHARD_HEADER_ACTUAL"
      printf 'row:    %s\n' "$(printf '%s\t' "${ROW[@]}")"
    } > "$PARSE_ERRORS"
    fail "test-sharded-row:${ROW[0]:-<empty>}" "$PARSE_ERRORS"
  }
  field() {
    printf '%s' "${ROW[$(( $1 - 1 ))]:-}"
  }
  is_uint() {
    case "$1" in
      ''|*[!0-9]*) return 1 ;;
      *) return 0 ;;
    esac
  }

  SHARD_COUNT=0
  SEEN_SHARDS=" "
  TESTS_TOTAL=0
  ASSERTIONS_TOTAL=0
  PEAK_MAX=0
  while IFS=$'\t' read -r -a ROW; do
    shard="${ROW[0]:-}"
    case "$shard" in
      A|B|C|D) ;;
      *) continue ;;
    esac
    case "$SEEN_SHARDS" in
      *" $shard "*) row_bad "duplicate shard row" ;;
    esac
    SEEN_SHARDS="$SEEN_SHARDS$shard "
    nses="$(field "$C_NS")"
    secs="$(field "$C_SECONDS")"
    code="$(field "$C_EXIT")"
    peak="$(field "$C_PEAK")"
    tests="$(field "$C_TESTS")"
    assertions="$(field "$C_ASSERTIONS")"
    failures="$(field "$C_FAILURES")"
    errors="$(field "$C_ERRORS")"
    last_ns="$(field "$C_LAST_NS")"

    is_uint "$nses" || row_bad "namespaces is not a plain number"
    [ "$nses" -gt 0 ] || row_bad "namespaces is zero"
    is_uint "$secs" || row_bad "seconds is not a plain number"
    is_uint "$code" || row_bad "exit is not a plain number"
    if [ "$code" -eq 124 ] || [ "$code" -eq 137 ]; then
      row_bad "shard timed out (exit $code, last_ns=${last_ns:-<none>})"
    fi
    [ "$code" -eq 0 ] || row_bad "shard exit $code"
    is_uint "$peak" || row_bad "peak_rss_kb is not a plain number"
    [ "$peak" -le 2097152 ] || row_bad "peak_rss_kb $peak > 2097152"
    is_uint "$tests" || row_bad "tests is not a plain number (last_ns=${last_ns:-<none>})"
    [ "$tests" -gt 0 ] || row_bad "tests is zero"
    is_uint "$assertions" || row_bad "assertions is not a plain number (last_ns=${last_ns:-<none>})"
    [ "$assertions" -gt 0 ] || row_bad "assertions is zero"
    is_uint "$failures" || row_bad "failures is not a plain number (last_ns=${last_ns:-<none>})"
    [ "$failures" -eq 0 ] || row_bad "failures is $failures"
    is_uint "$errors" || row_bad "errors is not a plain number (last_ns=${last_ns:-<none>})"
    [ "$errors" -eq 0 ] || row_bad "errors is $errors"

    SHARD_COUNT=$((SHARD_COUNT + 1))
    TESTS_TOTAL=$((TESTS_TOTAL + tests))
    ASSERTIONS_TOTAL=$((ASSERTIONS_TOTAL + assertions))
    if [ "$peak" -gt "$PEAK_MAX" ]; then
      PEAK_MAX="$peak"
    fi
  done < "$SHARD_SUMMARY"

  if [ "$SHARD_COUNT" -ne 4 ]; then
    fail "test-sharded-shard-count:$SHARD_COUNT" "$SHARD_SUMMARY"
  fi
  if [ "$SEEN_SHARDS" != " A B C D " ]; then
    fail "test-sharded-shard-set:$SEEN_SHARDS" "$SHARD_SUMMARY"
  fi
  if [ "$TESTS_TOTAL" -lt 400 ]; then
    fail "test-sharded-tests-total:$TESTS_TOTAL" "$SHARD_SUMMARY"
  fi
  if [ "$ASSERTIONS_TOTAL" -lt 1 ]; then
    fail "test-sharded-assertions-total:$ASSERTIONS_TOTAL" "$SHARD_SUMMARY"
  fi

  PRE_HASH="$(awk -F'\t' '$1 == "PRE_HASH" {print $2; exit}' "$SHARD_SUMMARY")"
  POST_HASH="$(awk -F'\t' '$1 == "POST_HASH" {print $2; exit}' "$SHARD_SUMMARY")"
  HASH_MATCH="$(awk -F'\t' '$1 == "HASH_MATCH" {print $2; exit}' "$SHARD_SUMMARY")"
  for pair in "PRE:$PRE_HASH" "POST:$POST_HASH"; do
    label="${pair%%:*}"
    value="${pair#*:}"
    if ! printf '%s' "$value" | grep -Eq '^[0-9a-f]{64}$'; then
      fail "test-sharded-tree-hash:$label" "$SHARD_SUMMARY"
    fi
  done
  if [ "$PRE_HASH" != "$POST_HASH" ]; then
    fail "test-sharded-tree-hash-mismatch" "$SHARD_SUMMARY"
  fi
  if [ "$HASH_MATCH" != "true" ]; then
    fail "test-sharded-tree-hash-unconfirmed:$HASH_MATCH" "$SHARD_SUMMARY"
  fi
  {
    echo "mode=sharded"
    echo "shards=$SHARD_COUNT"
    echo "peak_max_kb=$PEAK_MAX"
    echo "peak_limit_kb=2097152"
    echo "tests_total=$TESTS_TOTAL"
    echo "assertions_total=$ASSERTIONS_TOTAL"
    echo "pre_hash=$PRE_HASH"
    echo "post_hash=$POST_HASH"
    echo "hash_match=$HASH_MATCH"
    cat "$SHARD_SUMMARY"
  } > "$EVIDENCE/test-summary.txt"
  TEST_SUMMARY_LINE="$(grep -E '^(tests_total|assertions_total|peak_max_kb|pre_hash|post_hash)=' "$EVIDENCE/test-summary.txt" | tr '\n' ' ')"
  TEST_LOG="$SHARD_SUMMARY"
else
  TEST_MODE="single"
  run_step test "$PIXI" run test
  TEST_LOG="$LAST_LOG"
  RAN_LINE="$(grep -oE 'Ran [0-9]+ tests containing [0-9]+ assertions' "$TEST_LOG" | tail -1 || true)"
  if [ -z "$RAN_LINE" ]; then
    fail "test-summary-line-missing" "$TEST_LOG"
  fi
  printf '%s\n' "$RAN_LINE" > "$EVIDENCE/test-summary.txt"
  grep -q '^0 failures, 0 errors' "$TEST_LOG" || fail "test-failures" "$TEST_LOG"
  TEST_SUMMARY_LINE="$RAN_LINE"
fi

# 6. ClojureScript DOM gate.  Default path, not gated behind HC_GATE_E2E:
# these harnesses are not in test-shards.edn (that runner is JVM-only).
# Soft skip when the script is not present yet so this gate can land before
# its owner creates it; a present script must exit 0.
CLJS_GATE_STATUS=""
if [ -f scripts/test-cljs-gate.sh ]; then
  run_step cljs-gate bash scripts/test-cljs-gate.sh
  CLJS_SUMMARY="$(grep -E '^CLJS gate: ' "$LAST_LOG" | tail -1 || true)"
  CLJS_GATE_STATUS="ran"
  if [ -n "$CLJS_SUMMARY" ]; then
    CLJS_GATE_STATUS="ran: $CLJS_SUMMARY"
  fi
  {
    echo "status=ran"
    echo "log=$LAST_LOG"
    printf '%s\n' "$CLJS_SUMMARY"
  } > "$EVIDENCE/cljs-gate.txt"
else
  printf 'cljs-gate\t0\t0\t%s\n' "SKIP: cljs gate not found" >> "$SUMMARY"
  CLJS_GATE_STATUS="SKIP: scripts/test-cljs-gate.sh not found"
  echo "[gate] SKIP: cljs gate not found (scripts/test-cljs-gate.sh)"
  {
    echo "status=SKIP"
    echo "reason=scripts/test-cljs-gate.sh not present"
  } > "$EVIDENCE/cljs-gate.txt"
fi

# 7. E2E and benchmarks.  Default skip: these need a browser and several
# minutes; a release gate run for the final tree sets HC_GATE_E2E=1.
if [ "${HC_GATE_E2E:-0}" = "1" ]; then
  run_step e2e run_npm run e2e
  run_step bench-1000 run_node scripts/bench-1000-dom.mjs \
    --json "$EVIDENCE/bench-1000.json" --evidence "$EVIDENCE/bench-1000"
  run_step bench-2000 run_node scripts/bench-2000-activity.mjs \
    --json "$EVIDENCE/bench-2000-activity.json" --evidence "$EVIDENCE/bench-2000"
  run_step bench-20k run_node scripts/bench-20k-draft.mjs \
    --json "$EVIDENCE/bench-20k-draft.json"
  E2E_STATUS="ran"
else
  printf 'e2e\t0\t0\t%s\n' "skipped: set HC_GATE_E2E=1 to run" >> "$SUMMARY"
  E2E_STATUS="skipped"
  echo "[gate] e2e skipped (set HC_GATE_E2E=1 to run npm run e2e and benchmarks)"
fi

# 8. Version consistency: every manifest and runtime surface must equal the
# version.cljc value.  package-lock.json carries the version twice (the lock
# root and packages[""]), so a stale lock cannot hide behind package.json.
VERSION_START="$(date +%s)"
VERSION_CLJ="$EXPECTED_VERSION"
VERSION_PIXI="$(sed -n 's/^version = "\([^"]*\)".*/\1/p' pixi.toml | head -1)"
VERSION_PACKAGE="$(sed -n 's/.*"version": "\([^"]*\)".*/\1/p' package.json | head -1)"
VERSION_LOCK_ERR="$EVIDENCE/package-lock-version.err"
if ! VERSION_LOCK="$(PKG_LOCK="$REPO_ROOT/package-lock.json" run_node -e '
const lock = require(process.env.PKG_LOCK);
const root = lock.packages && lock.packages[""];
if (!root || lock.version !== root.version) {
  console.error("package-lock.json: lock.version and packages[\"\"].version differ");
  process.exit(1);
}
console.log(lock.version);
' 2>"$VERSION_LOCK_ERR")"; then
  fail "version-consistency:package-lock.json" "$VERSION_LOCK_ERR"
fi
run_step version "$PIXI" run clojure -M:server -m hc.server.app --version
VERSION_BINARY="$(grep -o 'Herdchestra [^ ]*' "$LAST_LOG" | head -1 | awk '{print $2}')"
VERSION_HEALTH="$(grep -o ':version "[^"]*"' "$EVIDENCE/app-check-health.txt" | head -1 | sed 's/:version "//;s/"$//')"
{
  echo "expected=$EXPECTED_VERSION"
  echo "version.cljc=$VERSION_CLJ"
  echo "pixi.toml=$VERSION_PIXI"
  echo "package.json=$VERSION_PACKAGE"
  echo "package-lock.json=$VERSION_LOCK"
  echo "--version=$VERSION_BINARY"
  echo "health=$VERSION_HEALTH"
} > "$EVIDENCE/versions.txt"
for pair in \
  "version.cljc:$VERSION_CLJ" \
  "pixi.toml:$VERSION_PIXI" \
  "package.json:$VERSION_PACKAGE" \
  "package-lock.json:$VERSION_LOCK" \
  "--version:$VERSION_BINARY" \
  "health:$VERSION_HEALTH"; do
  value="${pair#*:}"
  if [ -z "$value" ] || [ "$value" != "$EXPECTED_VERSION" ]; then
    fail "version-consistency:${pair%%:*}" "$EVIDENCE/versions.txt"
  fi
done
VERSION_END="$(date +%s)"
printf 'version-consistency\t0\t%s\t%s\n' "$((VERSION_END - VERSION_START))" "$EVIDENCE/versions.txt" >> "$SUMMARY"

# 9. Release notes.  Hard fail, not a warning: this gate certifies a
# releasable tree, and the notes are part of that deliverable.
NOTES="$REPO_ROOT/plans/$EXPECTED_VERSION-release-notes.md"
if [ ! -s "$NOTES" ]; then
  {
    echo "missing or empty: $NOTES"
    echo "reason: a passing release gate certifies a releasable tree; the release notes are part of the release deliverable."
  } > "$EVIDENCE/release-notes.txt"
  fail "release-notes-missing" "$EVIDENCE/release-notes.txt"
fi
printf 'release-notes\t0\t0\t%s\n' "$NOTES" >> "$SUMMARY"

# 10. Worktree inventory.  Product files and release documentation must be
# clean; ignored build outputs (out-*, target/, .pixi/, node_modules/,
# hs_err_pid*.log) are not counted by porcelain and never fail this step.
# plans/evidence/** is deliberately excluded: this gate writes a fresh
# evidence directory on every run, so including it would fail against its
# own output.
WT_START="$(date +%s)"
git status --porcelain -uall > "$EVIDENCE/git-status-porcelain.txt"
git status --porcelain -uall -- src test scripts resources \
  deps.edn package.json package-lock.json pixi.toml pixi.lock shadow-cljs.edn \
  "plans/$EXPECTED_VERSION-*.md" "plans/*-$EXPECTED_VERSION.md" \
  AGENTS.md HACK.md DEBT.md RUNNING.md \
  > "$EVIDENCE/git-status-product.txt"
if [ -s "$EVIDENCE/git-status-product.txt" ]; then
  fail "worktree-product-dirty" "$EVIDENCE/git-status-product.txt"
fi
TRACKED="$(git ls-files | wc -l)"
UNTRACKED="$(git status --porcelain -uall | wc -l)"
TOPLEVEL="$(git status --porcelain | wc -l)"
{
  echo "top_level_entries=$TOPLEVEL"
  echo "untracked_files=$UNTRACKED"
  echo "tracked_files=$TRACKED"
  echo "product_dirty_files=0"
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
  echo "version=$EXPECTED_VERSION"
  echo "test_mode=$TEST_MODE"
  echo "tests=$TEST_SUMMARY_LINE"
  echo "cljs_gate=$CLJS_GATE_STATUS"
  echo "e2e=$E2E_STATUS"
  echo "worktree_untracked=$UNTRACKED"
  echo "worktree_tracked=$TRACKED"
} > "$EVIDENCE/PASS.txt"

echo
echo "PASS: all release gates completed"
echo "Evidence: $EVIDENCE"
echo "Summary:"
cat "$SUMMARY"
echo "Version: $EXPECTED_VERSION"
echo "Tests: $TEST_SUMMARY_LINE"
echo "CLJS gate: $CLJS_GATE_STATUS"
echo "E2E: $E2E_STATUS"
echo "Worktree: tracked=$TRACKED untracked=$UNTRACKED top_level=$TOPLEVEL"
if [ "$UNTRACKED" -gt 0 ]; then
  echo "WARNING: worktree still has $UNTRACKED untracked entries outside product paths."
fi
exit 0
