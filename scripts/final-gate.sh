#!/usr/bin/env bash
# Herdchestra final-gate orchestrator.
#
# One reproducible sequence for the release evidence: preflight, identity,
# optional client rebuild, four JVM shards, the CLJS gate, the three
# benchmarks, the disk/IME/recovery E2E specs, and the optional release gate.
#
# Every stage writes <out>/<stage>.log and a row in <out>/summary.tsv.  The
# client bundle SHA-256 is recorded before and after every stage; a stage that
# changes it (except rebuild/gate, which are expected to rebuild) fails with
# BUNDLE_DRIFT.
#
# This script runs for a long time.  It refuses to start while another
# Herdchestra app/shard/Playwright/hc-chrome process is alive.  It never
# starts a browser or a server for preflight except the app's --check probe.
#
# Exit codes:
#   0  every selected stage passed (or was explicitly skipped)
#   1  at least one selected stage failed (including BUNDLE_DRIFT)
#   2  usage or setup error
#
# Worktree dirtiness is recorded by the identity stage but is not a
# preflight failure: the gate is also useful before the final commit.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

OUT=""
ONLY=()
REBUILD=0
SKIP_E2E=0
KEEP_GOING=0
FAILED=0
ALL_PASS=1

ALL_STAGES=(preflight identity rebuild shard cljs bench1 bench2 bench3
            e2e18 e2e19 e2e22 gate)
CANONICAL_STAGES=(preflight identity rebuild shard cljs bench1 bench2 bench3
                  e2e18 e2e19 e2e22 gate)

usage() {
  cat <<'USAGE'
Usage: scripts/final-gate.sh [options]

Runs the final P-BENCH gate sequence, writes one log per stage and appends a
row to <out>/summary.tsv.  This command is intentionally long-running; do not
run it concurrently with another Herdchestra app, shard, Playwright, or
hc-chrome process.

Options:
  --rebuild              Rebuild the client bundle first:
                         flock /tmp/hc-build.lock bash -lc 'npm run release'
  --only STAGE           Run only STAGE (repeatable).  Values:
                         preflight identity rebuild shard cljs bench1 bench2
                         bench3 e2e18 e2e19 e2e22 gate
  --skip-e2e             Omit e2e18/e2e19/e2e22.
  --out DIR              Evidence directory.
                         Default: plans/evidence/final-gate-<UTC timestamp>/
  --keep-going           Continue after a failed stage; the final exit is still
                         nonzero.  Without it the script stops at the first
                         failure.
  -h, --help             This help.

Default stages (no --only): preflight identity shard cljs bench1 bench2 bench3
e2e18 e2e19 e2e22.  "gate" is opt-in because scripts/release-gate.sh reruns the
shards and release build; it runs only after all selected stages are green.

summary.tsv columns:
  stage exit seconds bundle_sha256 artifact verdict note tests assertions
  failures errors

Exit codes: 0 all selected stages passed; 1 a stage failed; 2 usage/setup.
USAGE
}

die() {
  echo "final-gate: $*" >&2
  exit 2
}

known_stage() {
  local candidate="$1" stage
  for stage in "${ALL_STAGES[@]}"; do
    [ "$candidate" = "$stage" ] && return 0
  done
  return 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --rebuild) REBUILD=1; shift ;;
    --only)
      [ "$#" -ge 2 ] || die "--only needs a stage"
      known_stage "$2" || die "unknown stage: $2"
      ONLY+=("$2")
      shift 2
      ;;
    --skip-e2e) SKIP_E2E=1; shift ;;
    --out)
      [ "$#" -ge 2 ] || die "--out needs a directory"
      OUT="$2"
      shift 2
      ;;
    --keep-going) KEEP_GOING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

parse_shard_summary() {
  local summary="$1"
  local expected_header
  expected_header="$(printf 'shard\tnamespaces\tseconds\texit\tpeak_rss_kb\trss_procs\ttests\tassertions\tfailures\terrors\tlast_ns')"
  local header
  header="$(head -1 "$summary" 2>/dev/null | tr -d '\r')"
  if [ "$header" != "$expected_header" ]; then
    echo "SHARD_SUMMARY rows=0 tests=0 assertions=0 failures=0 errors=0 max_peak_kb=0 hash=missing verdict=FAIL reasons=header"
    return 1
  fi
  local stats
  stats="$(awk -F '\t' '
    NR == 1 { for (i = 1; i <= NF; i++) c[$i] = i; next }
    $0 ~ /^[[:space:]]*$/ { next }
    $1 == "HASH_MATCH" { hash = $2; next }
    {
      rows++
      if (!($1 in seen)) { seen[$1] = 1 } else { bad_shards++ }
      if ($1 !~ /^[ABCD]$/) { bad_shards++ }
      if (($c["exit"] + 0) != 0) { bad_exit++ }
      if (($c["failures"] + 0) != 0) { bad_failures++ }
      if (($c["errors"] + 0) != 0) { bad_errors++ }
      if (($c["tests"] + 0) <= 0) { bad_tests++ }
      if (($c["assertions"] + 0) <= 0) { bad_assertions++ }
      peak = $c["peak_rss_kb"] + 0
      if (peak > max_peak) { max_peak = peak }
      if (peak > 2097152) { bad_peak++ }
      tests += $c["tests"] + 0
      assertions += $c["assertions"] + 0
      failures += $c["failures"] + 0
      errors += $c["errors"] + 0
    }
    END {
      if (hash == "") hash = "missing"
      printf "%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%d\n",
        rows + 0, tests + 0, assertions + 0, failures + 0, errors + 0,
        bad_shards + 0, bad_exit + 0, bad_failures + 0, bad_errors + 0,
        bad_tests + 0, bad_assertions + 0, hash, max_peak + 0
    }
  ' "$summary")"
  SHARD_ROWS=0
  SHARD_TESTS=0
  SHARD_ASSERTIONS=0
  SHARD_FAILURES=0
  SHARD_ERRORS=0
  SHARD_BAD_SHARDS=0
  SHARD_BAD_EXIT=0
  SHARD_BAD_FAILURES=0
  SHARD_BAD_ERRORS=0
  SHARD_BAD_TESTS=0
  SHARD_BAD_ASSERTIONS=0
  SHARD_HASH=missing
  SHARD_PEAK_KB=0
  IFS=$'\t' read -r SHARD_ROWS SHARD_TESTS SHARD_ASSERTIONS \
    SHARD_FAILURES SHARD_ERRORS SHARD_BAD_SHARDS SHARD_BAD_EXIT \
    SHARD_BAD_FAILURES SHARD_BAD_ERRORS SHARD_BAD_TESTS \
    SHARD_BAD_ASSERTIONS SHARD_HASH SHARD_PEAK_KB <<< "$stats"
  local reasons=()
  [ "$SHARD_ROWS" -eq 4 ] || reasons+=("shards")
  [ "$SHARD_BAD_SHARDS" -eq 0 ] || reasons+=("shard-names")
  [ "$SHARD_BAD_EXIT" -eq 0 ] || reasons+=("exit")
  [ "$SHARD_BAD_FAILURES" -eq 0 ] || reasons+=("failures")
  [ "$SHARD_BAD_ERRORS" -eq 0 ] || reasons+=("errors")
  [ "$SHARD_BAD_TESTS" -eq 0 ] || reasons+=("empty-tests")
  [ "$SHARD_BAD_ASSERTIONS" -eq 0 ] || reasons+=("empty-assertions")
  [ "$SHARD_TESTS" -gt 4000 ] || reasons+=("tests<=4000")
  [ "$SHARD_PEAK_KB" -le 2097152 ] || reasons+=("peak>2GiB")
  [ "$SHARD_HASH" = "true" ] || reasons+=("hash")
  SHARD_VERDICT=PASS
  SHARD_REASONS=""
  if [ "${#reasons[@]}" -gt 0 ]; then
    SHARD_VERDICT=FAIL
    SHARD_REASONS="$(printf '%s,' "${reasons[@]}")"
    SHARD_REASONS="${SHARD_REASONS%,}"
  fi
  printf 'SHARD_SUMMARY rows=%s tests=%s assertions=%s failures=%s errors=%s max_peak_kb=%s hash=%s verdict=%s reasons=%s\n' \
    "$SHARD_ROWS" "$SHARD_TESTS" "$SHARD_ASSERTIONS" "$SHARD_FAILURES" \
    "$SHARD_ERRORS" "$SHARD_PEAK_KB" "$SHARD_HASH" "$SHARD_VERDICT" \
    "${SHARD_REASONS:-none}"
  [ "$SHARD_VERDICT" = "PASS" ]
}

if [ -n "${FINAL_GATE_PARSE_SHARD_SUMMARY:-}" ]; then
  # Parser self-test hook: no JVM, no server, no Playwright.
  parse_shard_summary "$FINAL_GATE_PARSE_SHARD_SUMMARY"
  exit $?
fi

if [ -z "$OUT" ]; then
  OUT="$REPO_ROOT/plans/evidence/final-gate-$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "$OUT"
SUMMARY="$OUT/summary.tsv"
printf 'stage\texit\tseconds\tbundle_sha256\tartifact\tverdict\tnote\ttests\tassertions\tfailures\terrors\n' > "$SUMMARY"

bundle_sha() {
  local file="$REPO_ROOT/resources/public/js/main.js"
  if [ -f "$file" ]; then
    sha256sum "$file" | awk '{print $1}'
  else
    echo "MISSING"
  fi
}

run_clojure() {
  if [ -x "$REPO_ROOT/.pixi/envs/default/bin/clojure" ]; then
    "$REPO_ROOT/.pixi/envs/default/bin/clojure" "$@"
  else
    clojure "$@"
  fi
}

static_snapshot() {
  local files=(
    "$REPO_ROOT/src/hc/server/app/system.clj"
    "$REPO_ROOT/resources/public/js/main.js"
    "$REPO_ROOT/scripts/test-shards.edn"
  )
  local file
  for file in "${files[@]}"; do
    if [ ! -f "$file" ]; then
      printf 'MISSING %s\n' "$file"
    else
      sha256sum "$file"
    fi
  done
}

stage_limit() {
  case "$1" in
    preflight) echo 300 ;;
    identity) echo 300 ;;
    rebuild) echo 2400 ;;
    shard) echo 9000 ;;
    cljs) echo 7200 ;;
    bench1|bench2|bench3) echo 900 ;;
    e2e18|e2e19|e2e22) echo 1800 ;;
    gate) echo 15000 ;;
    *) echo 1800 ;;
  esac
}

STAGE=""
STAGE_START=0
STAGE_SHA=""

stage_begin() {
  STAGE="$1"
  STAGE_START="$(date +%s)"
  STAGE_SHA="$(bundle_sha)"
  echo "[final-gate] $STAGE start (bundle $STAGE_SHA)"
}

record_row() {
  local stage="$1" code="$2" seconds="$3" sha="$4" artifact="$5"
  local verdict="$6" note="$7" tests="${8:-}" assertions="${9:-}"
  local failures="${10:-}" errors="${11:-}"
  note="${note//$'\t'/ }"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$stage" "$code" "$seconds" "$sha" "$artifact" "$verdict" "$note" \
    "$tests" "$assertions" "$failures" "$errors" >> "$SUMMARY"
  printf '[final-gate] %-9s exit=%s %ss %-12s bundle=%s %s\n' \
    "$stage" "$code" "$seconds" "$verdict" "$sha" "$note"
}

stage_end() {
  local code="$1" verdict="$2" note="${3:-}"
  local tests="${4:-}" assertions="${5:-}" failures="${6:-}" errors="${7:-}"
  local end seconds sha
  end="$(date +%s)"
  seconds=$((end - STAGE_START))
  sha="$(bundle_sha)"
  if [ "$STAGE" != "rebuild" ] && [ "$STAGE" != "gate" ] \
     && [ "$sha" != "$STAGE_SHA" ]; then
    verdict="BUNDLE_DRIFT"
    note="${note:+$note; }bundle changed during stage"
    code=1
  fi
  record_row "$STAGE" "$code" "$seconds" "$sha" "$OUT/$STAGE.log" \
    "$verdict" "$note" "$tests" "$assertions" "$failures" "$errors"
  if [ "$verdict" != "PASS" ] && [ "$verdict" != "SKIP" ]; then
    FAILED=1
    ALL_PASS=0
    if [ "$KEEP_GOING" -eq 0 ]; then
      echo
      echo "FINAL GATE FAILED at $STAGE"
      echo "log: $OUT/$STAGE.log"
      echo "evidence: $OUT"
      exit 1
    fi
  fi
}

run_logged() {
  local stage="$1"
  shift
  local log="$OUT/$stage.log"
  local limit
  limit="$(stage_limit "$stage")"
  set +e
  timeout -k 30 "$limit" bash -c '"$@"' _ "$@" >"$log" 2>&1
  RUN_CODE=$?
  set -e
  return 0
}

stage_preflight() {
  stage_begin preflight
  local log="$OUT/preflight.log"
  : > "$log"
  local stray
  set +e
  stray="$(pgrep -af 'hc[.]server[.]app|hc-shard-|playwright|hc-chrome' || true)"
  set -e
  if [ -n "$stray" ]; then
    printf '%s\n' "$stray" >> "$log"
    stage_end 1 FAIL "stray-process"
    return
  fi
  echo "NO_STRAY" >> "$log"

  local before after
  before="$(static_snapshot)"
  sleep 60
  after="$(static_snapshot)"
  printf '%s\n' "$before" >> "$log"
  printf '%s\n' "$after" >> "$log"
  if [ "$before" != "$after" ]; then
    stage_end 1 FAIL "static-drift-60s"
    return
  fi
  echo "STATIC_STABLE_60S" >> "$log"

  local code
  set +e
  run_clojure -M:server -m hc.server.app --check --port 0 >> "$log" 2>&1
  code=$?
  set -e
  if [ "$code" -ne 0 ]; then
    stage_end "$code" FAIL "app-check-exit-$code"
    return
  fi
  if ! grep -Fq ':store {:open? true' "$log"; then
    stage_end 1 FAIL "app-check-store-not-open"
    return
  fi
  echo ":SYSTEM-OK" >> "$log"
  stage_end 0 PASS "SYSTEM-OK;--check--port-0-exit-0"
}

stage_identity() {
  stage_begin identity
  local log="$OUT/identity.log"
  : > "$log"
  local head dirty_total bundle shards
  head="$(git rev-parse HEAD)" || { stage_end 1 FAIL "git-head-failed"; return; }
  dirty_total="$(git status --porcelain -uall | wc -l | tr -d ' ')"
  local product_paths=(
    src test scripts resources deps.edn package.json package-lock.json
    pixi.toml pixi.lock shadow-cljs.edn AGENTS.md HACK.md DEBT.md RUNNING.md
  )
  local product_dirty untracked_evidence
  product_dirty="$(git status --porcelain -uall -- "${product_paths[@]}" \
    'plans/0.0.3-*.md' | wc -l | tr -d ' ')"
  untracked_evidence="$(git status --porcelain -uall -- plans/evidence \
    | wc -l | tr -d ' ')"
  bundle="$(bundle_sha)"
  shards="$(sha256sum scripts/test-shards.edn | awk '{print $1}')"
  local version_clj version_pixi version_package version_lock_root
  local version_lock_package version_bin version_health check
  version_clj="$(sed -n 's/.*(def version "\([^"]*\)".*/\1/p' src/hc/version.cljc | head -1)"
  version_pixi="$(sed -n 's/^version = "\([^"]*\)".*/\1/p' pixi.toml | head -1)"
  version_package="$(sed -n 's/.*"version": "\([^"]*\)".*/\1/p' package.json | head -1)"
  local lock_pair
  lock_pair="$(node -e '
const lock = require(process.argv[1]);
const root = lock.packages && lock.packages[""];
if (!root) process.exit(1);
process.stdout.write(String(lock.version) + "\t" + String(root.version));
' "$REPO_ROOT/package-lock.json" 2>/dev/null || true)"
  IFS=$'\t' read -r version_lock_root version_lock_package <<< "$lock_pair"
  set +e
  version_bin="$(run_clojure -M:server -m hc.server.app --version 2>&1 \
    | grep -o 'Herdchestra [^ ]*' | head -1 | awk '{print $2}')"
  local version_code=$?
  set -e
  if [ "$version_code" -ne 0 ] || [ -z "${version_bin:-}" ]; then
    version_bin="unknown"
  fi
  check="skipped"
  version_health="skipped"
  if [ -f "$OUT/preflight.log" ]; then
    version_health="$(grep -o ':version "[^"]*"' "$OUT/preflight.log" 2>/dev/null \
      | head -1 | sed 's/:version "//;s/"$//' || true)"
    if [ -n "$version_health" ]; then
      if [ "$version_health" = "$version_clj" ]; then
        check="ok"
      else
        check="mismatch"
      fi
    else
      version_health="skipped"
    fi
  fi

  local six_way_equal=true value
  for value in "$version_pixi" "$version_package" "$version_lock_root" \
               "$version_lock_package" "$version_bin"; do
    if [ -z "$value" ] || [ "$value" = "unknown" ] || [ "$value" != "$version_clj" ]; then
      six_way_equal=false
    fi
  done
  if [ -z "$version_clj" ]; then
    six_way_equal=false
  fi

  {
    echo "head=$head"
    echo "dirty_total=$dirty_total"
    echo "product_dirty=$product_dirty"
    echo "untracked_evidence=$untracked_evidence"
    echo "bundle_sha256=$bundle"
    echo "test_shards_sha256=$shards"
    echo "version.cljc=$version_clj"
    echo "pixi.toml=$version_pixi"
    echo "package.json=$version_package"
    echo "package-lock.json=$version_lock_root"
    echo "package-lock.packages[\"\"]=$version_lock_package"
    echo "--version=$version_bin"
    echo "health=$version_health"
    echo "check=$check"
    echo "six_way_equal=$six_way_equal"
  } > "$log"

  if [ "$six_way_equal" != "true" ] || [ "$check" = "mismatch" ]; then
    stage_end 1 FAIL "six_way_equal=$six_way_equal;check=$check;product_dirty=$product_dirty;untracked_evidence=$untracked_evidence" \
      "" "" 1 0
  else
    stage_end 0 PASS "six_way_equal=true;check=$check;product_dirty=$product_dirty;untracked_evidence=$untracked_evidence"
  fi
}

stage_rebuild() {
  stage_begin rebuild
  local code
  run_logged rebuild flock /tmp/hc-build.lock bash -lc 'npm run release'
  code=$RUN_CODE
  if [ "$code" -ne 0 ]; then
    stage_end "$code" FAIL "client-release-exit-$code"
  else
    stage_end 0 PASS "client-release;new-bundle-$(bundle_sha)"
  fi
}

stage_shard() {
  stage_begin shard
  local code log="$OUT/shard.log"
  run_logged shard env HC_SHARD_TIMEOUT_SECONDS=1800 \
    bash scripts/test-sharded.sh --out "$OUT/shards"
  code=$RUN_CODE
  local summary="$OUT/shards/summary.tsv"
  if [ "$code" -ne 0 ] || [ ! -f "$summary" ]; then
    stage_end "$code" FAIL "shard-run-exit-$code"
    return
  fi
  local parse_code parse_out
  set +e
  parse_shard_summary "$summary" > "$OUT/shard-parse.txt" 2>&1
  parse_code=$?
  set -e
  parse_out="$(cat "$OUT/shard-parse.txt")"
  echo "$parse_out" >> "$log"
  if [ "$code" -ne 0 ] || [ "$parse_code" -ne 0 ]; then
    stage_end 1 FAIL "$parse_out" "$SHARD_TESTS" "$SHARD_ASSERTIONS" \
      "$SHARD_FAILURES" "$SHARD_ERRORS"
  else
    stage_end 0 PASS "HASH_MATCH=true;$parse_out" \
      "$SHARD_TESTS" "$SHARD_ASSERTIONS" "$SHARD_FAILURES" "$SHARD_ERRORS"
  fi
}

stage_cljs() {
  stage_begin cljs
  local code log="$OUT/cljs.log"
  run_logged cljs bash scripts/test-cljs-gate.sh
  code=$RUN_CODE
  local line selected passed failed
  line="$(grep 'CLJS gate: selected=' "$log" | tail -1 || true)"
  selected="$(printf '%s\n' "$line" | sed -n 's/.*selected=\([0-9]*\).*/\1/p')"
  passed="$(printf '%s\n' "$line" | sed -n 's/.*passed=\([0-9]*\).*/\1/p')"
  failed="$(printf '%s\n' "$line" | sed -n 's/.*failed=\([0-9]*\).*/\1/p')"
  if [ "$code" -ne 0 ] || [ "${selected:-0}" -ne 26 ] \
     || [ "${passed:-0}" -ne 26 ] || [ "${failed:-1}" -ne 0 ]; then
    stage_end 1 FAIL "cljs-exit=$code selected=${selected:-?} passed=${passed:-?} failed=${failed:-?}" \
      "${selected:-}" "" "${failed:-1}" 0
  else
    stage_end 0 PASS "cljs-26/26" "${selected:-}" "" 0 0
  fi
}

run_bench_stage() {
  local stage="$1" script="$2" json="$OUT/$1.json"
  stage_begin "$stage"
  local code verdict stable
  run_logged "$stage" node "$script" --json "$json"
  code=$RUN_CODE
  if [ ! -f "$json" ]; then
    stage_end "$code" FAIL "missing-json"
    return
  fi
  verdict="$(node -e 'const r=require(process.argv[1]); process.stdout.write(String(r.verdict||""))' "$json")"
  stable="$(node -e 'const r=require(process.argv[1]); process.stdout.write(String(r.bundleStable===true))' "$json")"
  if [ "$code" -ne 0 ] || [ "$verdict" != "PASS" ] || [ "$stable" != "true" ]; then
    stage_end 1 FAIL "verdict=${verdict:-?};bundleStable=${stable:-?};exit=$code" "" "" 1 0
  else
    stage_end 0 PASS "verdict=PASS;bundleStable=true" "" "" 0 0
  fi
}

stage_bench1() { run_bench_stage bench1 scripts/bench-1000-dom.mjs; }
stage_bench2() { run_bench_stage bench2 scripts/bench-2000-activity.mjs; }
stage_bench3() { run_bench_stage bench3 scripts/bench-20k-draft.mjs; }

run_e2e_stage() {
  local stage="$1" spec="$2"
  stage_begin "$stage"
  local code
  if [ -x "$REPO_ROOT/node_modules/.bin/playwright" ]; then
    run_logged "$stage" "$REPO_ROOT/node_modules/.bin/playwright" test \
      --config test/e2e/playwright.config.mjs "$spec"
  else
    run_logged "$stage" npx playwright test \
      --config test/e2e/playwright.config.mjs "$spec"
  fi
  code=$RUN_CODE
  local raw="$REPO_ROOT/plans/evidence/e2e-auto/playwright-results.json"
  local copied="$OUT/$stage-results.json"
  if [ -f "$raw" ]; then cp "$raw" "$copied"; fi
  local ok=0
  if [ -f "$copied" ]; then
    ok="$(node -e '
const r = require(process.argv[1]);
const s = r.stats || {};
process.stdout.write(String((s.unexpected || 0) === 0 && (s.failed || 0) === 0));
' "$copied" 2>/dev/null || echo 0)"
  fi
  if [ "$code" -ne 0 ] || [ "$ok" != "true" ]; then
    stage_end 1 FAIL "playwright-exit=$code;stats-ok=$ok" "" "" 1 0
  else
    stage_end 0 PASS "playwright-results-ok" "" "" 0 0
  fi
}

stage_e2e18() { run_e2e_stage e2e18 test/e2e/e2e-18-disk-full.spec.mjs; }
stage_e2e19() { run_e2e_stage e2e19 test/e2e/e2e-19-ime-a11y.spec.mjs; }
stage_e2e22() { run_e2e_stage e2e22 test/e2e/e2e-22.spec.mjs; }

stage_gate() {
  if [ "$ALL_PASS" -ne 1 ]; then
    stage_begin gate
    stage_end 1 SKIP "blocked-by-earlier-failure"
    return
  fi
  stage_begin gate
  local code
  run_logged gate bash scripts/release-gate.sh
  code=$RUN_CODE
  if [ "$code" -ne 0 ]; then
    stage_end "$code" FAIL "release-gate-exit-$code"
  else
    stage_end 0 PASS "release-gate"
  fi
}

selected_stages() {
  local stage
  if [ "${#ONLY[@]}" -eq 0 ]; then
    for stage in "${CANONICAL_STAGES[@]}"; do
      case "$stage" in
        rebuild) [ "$REBUILD" -eq 1 ] && echo "$stage" ;;
        e2e18|e2e19|e2e22) [ "$SKIP_E2E" -eq 0 ] && echo "$stage" ;;
        gate) : ;;
        *) echo "$stage" ;;
      esac
    done
  else
    for stage in "${CANONICAL_STAGES[@]}"; do
      local wanted=0 item
      for item in "${ONLY[@]}"; do
        [ "$item" = "$stage" ] && wanted=1
      done
      [ "$stage" = "rebuild" ] && [ "$REBUILD" -eq 1 ] && wanted=1
      [ "$wanted" -eq 1 ] || continue
      case "$stage" in
        e2e18|e2e19|e2e22) [ "$SKIP_E2E" -eq 1 ] && continue ;;
      esac
      echo "$stage"
    done
  fi
}

STAGES=()
while IFS= read -r stage; do
  [ -n "$stage" ] && STAGES+=("$stage")
done < <(selected_stages)

if [ "${#STAGES[@]}" -eq 0 ]; then
  die "no stages selected"
fi

echo "Herdchestra final gate"
echo "repo: $REPO_ROOT"
echo "evidence: $OUT"
echo "stages: ${STAGES[*]}"
echo "bundle: $(bundle_sha)"
echo

GATE_START="$(date +%s)"
for stage in "${STAGES[@]}"; do
  case "$stage" in
    preflight) stage_preflight ;;
    identity) stage_identity ;;
    rebuild) stage_rebuild ;;
    shard) stage_shard ;;
    cljs) stage_cljs ;;
    bench1) stage_bench1 ;;
    bench2) stage_bench2 ;;
    bench3) stage_bench3 ;;
    e2e18) stage_e2e18 ;;
    e2e19) stage_e2e19 ;;
    e2e22) stage_e2e22 ;;
    gate) stage_gate ;;
  esac
done

GATE_END="$(date +%s)"
echo
echo "== final-gate summary =="
cat "$SUMMARY"
echo
echo "elapsed_seconds=$((GATE_END - GATE_START))"
echo "evidence=$OUT"
if [ "$FAILED" -ne 0 ]; then
  echo "FINAL GATE: FAIL"
  exit 1
fi
echo "FINAL GATE: PASS"
exit 0
