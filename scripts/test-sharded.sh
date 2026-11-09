#!/usr/bin/env bash
# Run the full clojure.test suite as four independent JVM shards.
#
# Each shard is a separate JVM running cognitect.test-runner through
# hc.test-runner/test, so report-leaked-stores! still runs at the end of
# every shard.  The namespace lists live in scripts/test-shards.edn (one
# key per shard, generated from every test/*_test.clj file); the runner is
# invoked with -X:test exec-arg :nses, which loads exactly those
# namespaces — helper namespaces (support/fake_port/smoke mains) are never
# required, so a half-edited helper cannot break a shard.
#
# Every shard gets the standard caps -Xmx1g -XX:MaxDirectMemorySize=512m
# (from the :test/:server alias jvm-opts, plus -XX:+ExitOnOutOfMemoryError);
# a single shard JVM peaks around 1.9-2.0 GiB instead of the historical
# 2.28 GiB parent+children overlap.
#
# The PRE=POST tree hash gate: src/, test/, scripts/ and resources/ plus the
# root build manifests are hashed once before shard 1 and once after shard D;
# all four shards share that gate.  A mismatch means the tree changed while
# the suite ran.  The summary records both hashes and the match verdict, so
# the caller can assert them independently of this script's exit code.
#
# Usage: scripts/test-sharded.sh [--only A|B|C|D ...] [--out DIR]
#
# Environment:
#   HC_SHARD_TIMEOUT_SECONDS=3600  per-shard wall clock limit enforced with
#                                  `timeout -k 30`; a kill records TIMEOUT
#                                  and exit 124/137.
#   HC_CLOJURE=/path/clojure       clojure binary (default: .pixi env, then
#                                  `clojure` on PATH).
#   HC_SHARD_OUT=/tmp/hc-shards    default output directory (--out wins).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"

CLOJURE_BIN="${HC_CLOJURE:-}"
if [ -z "$CLOJURE_BIN" ]; then
  if [ -x "$REPO/.pixi/envs/default/bin/clojure" ]; then
    CLOJURE_BIN="$REPO/.pixi/envs/default/bin/clojure"
  else
    CLOJURE_BIN="clojure"
  fi
fi

OUT_DIR="${HC_SHARD_OUT:-/tmp/hc-shards}"
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

SHARD_TIMEOUT_SECONDS="${HC_SHARD_TIMEOUT_SECONDS:-3600}"
case "$SHARD_TIMEOUT_SECONDS" in
  ''|*[!0-9]*|0)
    echo "invalid HC_SHARD_TIMEOUT_SECONDS: '$SHARD_TIMEOUT_SECONDS' (want a positive integer)" >&2
    exit 2
    ;;
esac

SHARDS_EDN="$REPO/scripts/test-shards.edn"
[ -f "$SHARDS_EDN" ] || { echo "missing $SHARDS_EDN" >&2; exit 2; }

mkdir -p "$OUT_DIR"
SUMMARY="$OUT_DIR/summary.tsv"
: > "$SUMMARY"
printf 'shard\tnamespaces\tseconds\texit\tpeak_rss_kb\trss_procs\ttests\tassertions\tfailures\terrors\tlast_ns\n' >> "$SUMMARY"

SHARD_ORDER="A B C D"

# Read one shard's namespace list as an EDN vector of symbols.  A missing
# key must stop the run here: cognitect's runner falls back to `.*-test$`
# over the whole test tree when :nses is nil, which looks like a green run.
shard_nses() {
  local shard="$1" out
  if ! out="$("$CLOJURE_BIN" -M -e \
    "(pr (get (read-string (slurp \"$SHARDS_EDN\")) (keyword \"$shard\")))" \
    2>/dev/null)"; then
    echo "cannot read shard '$shard' from $SHARDS_EDN (clojure failed)" >&2
    return 2
  fi
  case "$out" in
    nil|'')
      echo "missing shard '$shard' in $SHARDS_EDN; refusing the all-tests fallback" >&2
      return 2
      ;;
    \[*) ;;
    *)
      echo "invalid namespace vector for shard '$shard': $out" >&2
      return 2
      ;;
  esac
  printf '%s' "$out"
}

declare -A SHARD_NSES=()

validate_shard() {
  local shard="$1" nses count
  nses="$(shard_nses "$shard")" || exit 2
  count="$(printf '%s' "$nses" | tr -d '[]' | wc -w)"
  if [ "$count" -le 0 ]; then
    echo "shard '$shard' in $SHARDS_EDN has no namespaces; refusing to run" >&2
    exit 2
  fi
  SHARD_NSES["$shard"]="$nses"
}

HASH_DIRS="src test resources"
HASH_REQUIRED="scripts/test-shards.edn scripts/test-sharded.sh deps.edn pixi.toml package.json package-lock.json shadow-cljs.edn"
HASH_OPTIONAL="pixi.lock"

hash_tree() {
  (
    cd "$REPO" || exit 1
    local dir f
    for dir in $HASH_DIRS; do
      if [ ! -d "$dir" ]; then
        echo "hash_tree: required directory missing: $dir" >&2
        exit 1
      fi
    done
    for f in $HASH_REQUIRED; do
      if [ ! -f "$f" ]; then
        echo "hash_tree: required file missing: $f" >&2
        exit 1
      fi
    done
    {
      find $HASH_DIRS -type f -print0 \
        | LC_ALL=C sort -z \
        | xargs -0 -r sha256sum
      sha256sum $HASH_REQUIRED
      if [ -f "$HASH_OPTIONAL" ]; then
        sha256sum "$HASH_OPTIONAL"
      fi
    } | sha256sum | cut -d' ' -f1
  )
}

# JVM pids whose command line carries this shard's marker.  The clojure
# launcher shares the marker but is not a JVM, so filter by
# /proc/<pid>/comm == java; the launcher's own RSS must not inflate the peak.
jvm_pids() {
  local marker="$1" pid comm
  for pid in $(pgrep -f "hc.shard=$marker" 2>/dev/null || true); do
    comm="$(cat "/proc/$pid/comm" 2>/dev/null || true)"
    [ "$comm" = "java" ] && printf '%s\n' "$pid"
  done
}

# Peak JVM VmHWM (kB) and the number of JVMs sampled at that peak.  The
# `rss_procs` summary column records the sample size, so a reader can tell
# that peak_rss_kb measured the JVM(s) for this shard only.
rss_sample() {
  local marker="$1" total=0 count=0 pid rss
  for pid in $(jvm_pids "$marker"); do
    rss="$(awk '/VmHWM/ {print $2}' "/proc/$pid/status" 2>/dev/null || true)"
    [ -n "$rss" ] || continue
    total=$((total + rss))
    count=$((count + 1))
  done
  printf '%s %s\n' "$total" "$count"
}

# The runner treats :nses as a filter over namespaces found under test/.
# A namespace listed in the EDN but absent from disk is therefore skipped
# silently and the shard can exit 0 with fewer tests.  Compare the expected
# list with the `Testing <ns>` lines actually printed.
check_ghost_nses() {
  local shard="$1" nses="$2" log="$3" missing
  missing="$(comm -23 \
    <(printf '%s' "$nses" | tr -d '[]' | tr ' ' '\n' | sed '/^$/d' \
        | LC_ALL=C sort -u) \
    <(grep -oE '^Testing [^ ]+' "$log" | awk '{print $2}' \
        | LC_ALL=C sort -u))"
  if [ -n "$missing" ]; then
    {
      echo "[shard $shard] FAIL: namespaces listed in EDN but never tested:"
      printf '  %s\n' $missing
    } | tee -a "$log" >&2
    return 1
  fi
  return 0
}

run_shard() {
  local shard="$1"
  local nses="${SHARD_NSES[$shard]:-}"
  if [ -z "$nses" ]; then
    echo "[shard $shard] missing validated namespace list" >&2
    exit 2
  fi
  local ns_count
  ns_count="$(printf '%s' "$nses" | tr -d '[]' | wc -w)"
  local log="$OUT_DIR/shard-$shard.log"
  local marker="hc-shard-$shard"
  echo "[shard $shard] nses: $ns_count namespaces"
  local start end code peak peak_procs
  start="$(date +%s)"
  # -J-Dhc.shard marks this shard's JVM command line so rss_sample can
  # find it.  timeout is the only bound on a hung JVM; -k 30 escalates to
  # SIGKILL after the grace period.
  set +e
  timeout -k 30 "$SHARD_TIMEOUT_SECONDS" \
    "$CLOJURE_BIN" -J-Dhc.shard="$marker" \
    -X:test:compose:server:client :nses "$nses" > "$log" 2>&1 &
  local runner_pid=$!
  peak=0
  peak_procs=0
  while kill -0 "$runner_pid" 2>/dev/null; do
    local cur cur_procs
    read -r cur cur_procs < <(rss_sample "$marker")
    if [ "$cur" -gt "$peak" ]; then
      peak="$cur"
      peak_procs="$cur_procs"
    fi
    sleep 2
  done
  wait "$runner_pid"
  code=$?
  set -e
  end="$(date +%s)"
  local tests assertions failures errors last_ns timed_out=0
  tests="$(grep -oE 'Ran [0-9]+ tests' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  assertions="$(grep -oE '[0-9]+ assertions' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  failures="$(grep -oE '[0-9]+ failures' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  errors="$(grep -oE '[0-9]+ errors' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  last_ns="$(grep -oE '^Testing [^ ]+' "$log" | awk '{print $2}' | tail -1 || true)"
  if [ "$code" -eq 124 ] || [ "$code" -eq 137 ]; then
    timed_out=1
    # A killed shard has no trustworthy counts; never let a partial log look
    # like a completed run.
    tests='?'
    assertions='?'
    failures='?'
    errors='?'
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$shard" "$ns_count" "$((end - start))" "$code" "$peak" "$peak_procs" \
    "$tests" "$assertions" "$failures" "$errors" "${last_ns:-}" >> "$SUMMARY"
  echo "[shard $shard] exit=$code seconds=$((end - start)) peak_rss_kb=$peak rss_procs=$peak_procs log=$log"
  if [ "$timed_out" -eq 1 ]; then
    printf 'TIMEOUT\t%s\t%s\n' "$shard" "$SHARD_TIMEOUT_SECONDS" >> "$SUMMARY"
    echo "[shard $shard] TIMEOUT after ${SHARD_TIMEOUT_SECONDS}s (timeout -k 30)" >> "$log"
  fi
  echo "[shard $shard] summary: tests=$tests assertions=$assertions failures=$failures errors=$errors last_ns=${last_ns:-<none>}" >> "$log"
  local ghost=0
  if [ "$timed_out" -eq 0 ]; then
    check_ghost_nses "$shard" "$nses" "$log" || ghost=1
  fi
  if [ "$timed_out" -eq 1 ]; then
    return 124
  fi
  if [ "$code" -ne 0 ]; then
    return "$code"
  fi
  [ "$ghost" -eq 0 ] || return 1
  return 0
}

# Fail before starting any JVM when the shard selection itself is wrong.
if [ -n "$ONLY" ]; then
  for shard in $ONLY; do
    case " $SHARD_ORDER " in
      *" $shard "*) ;;
      *)
        echo "unknown shard '$shard' in --only (known: $SHARD_ORDER)" >&2
        exit 2
        ;;
    esac
  done
fi
for shard in $SHARD_ORDER; do
  if [ -n "$ONLY" ]; then
    case " $ONLY " in
      *" $shard "*) validate_shard "$shard" ;;
      *) continue ;;
    esac
  else
    validate_shard "$shard"
  fi
done

# Guard: no other Herdchestra app/test JVM may be running; shards and E2E
# apps share /tmp and store locks.  exit 3 is a residual JVM, never a test
# failure.
if pgrep -f 'hc\.server\.app|hc-shard-' >/dev/null 2>&1; then
  echo "refusing to run: another Herdchestra JVM is already running." >&2
  echo "exit 3 means a residual JVM was found; it is not a test failure." >&2
  pgrep -af 'hc\.server\.app|hc-shard-' >&2 || true
  exit 3
fi

if ! PRE_HASH="$(hash_tree)"; then
  echo "[gate] FAIL: cannot hash the tree (required path missing, see above)" >&2
  exit 4
fi
case "$PRE_HASH" in
  *[!0-9a-f]*|'')
    echo "[gate] FAIL: invalid PRE tree hash '$PRE_HASH'" >&2
    exit 4
    ;;
esac
if [ "${#PRE_HASH}" -ne 64 ]; then
  echo "[gate] FAIL: PRE tree hash is not a sha256: '$PRE_HASH'" >&2
  exit 4
fi
printf 'PRE_HASH\t%s\n' "$PRE_HASH" >> "$SUMMARY"
echo "[gate] PRE tree hash: $PRE_HASH"

failed=0
for shard in $SHARD_ORDER; do
  case " $ONLY " in
    *" $shard "*) ;;
    *) [ -n "$ONLY" ] && continue ;;
  esac
  if ! run_shard "$shard"; then
    failed=1
    echo "[shard $shard] FAILED; continuing remaining shards" >&2
  fi
done

if ! POST_HASH="$(hash_tree)"; then
  echo "[gate] FAIL: cannot hash the tree after the run (required path missing, see above)" >&2
  exit 4
fi
case "$POST_HASH" in
  *[!0-9a-f]*|'')
    echo "[gate] FAIL: invalid POST tree hash '$POST_HASH'" >&2
    exit 4
    ;;
esac
if [ "${#POST_HASH}" -ne 64 ]; then
  echo "[gate] FAIL: POST tree hash is not a sha256: '$POST_HASH'" >&2
  exit 4
fi
printf 'POST_HASH\t%s\n' "$POST_HASH" >> "$SUMMARY"
echo "[gate] POST tree hash: $POST_HASH"
if [ "$PRE_HASH" != "$POST_HASH" ]; then
  printf 'HASH_MATCH\tfalse\n' >> "$SUMMARY"
  echo "[gate] FAIL: tree hash changed during the run (PRE=$PRE_HASH POST=$POST_HASH)" >&2
  failed=1
else
  printf 'HASH_MATCH\ttrue\n' >> "$SUMMARY"
  echo "[gate] OK: PRE == POST ($PRE_HASH)"
fi

echo "summary: $SUMMARY"
exit "$failed"
