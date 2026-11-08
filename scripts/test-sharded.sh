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
# The PRE=POST tree hash gate: src/, test/ and resources/ are hashed once
# before shard 1 and once after shard D; all four shards share that gate.
# A mismatch means the tree changed while the suite ran.
#
# Usage: scripts/test-sharded.sh [--only A|B|C|D ...] [--out DIR]
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

SHARDS_EDN="$REPO/scripts/test-shards.edn"
[ -f "$SHARDS_EDN" ] || { echo "missing $SHARDS_EDN" >&2; exit 2; }

mkdir -p "$OUT_DIR"
SUMMARY="$OUT_DIR/summary.tsv"
: > "$SUMMARY"
printf 'shard\tnamespaces\tseconds\texit\tpeak_rss_kb\ttests\tassertions\tfailures\terrors\n' >> "$SUMMARY"

SHARD_ORDER="A B C D"

# Read one shard's namespace list as an EDN vector of symbols.
shard_nses() {
  "$CLOJURE_BIN" -M -e \
    "(pr (get (read-string (slurp \"$SHARDS_EDN\")) (keyword \"$1\")))"
}

hash_tree() {
  (cd "$REPO" && find src test resources -type f | sort | xargs sha256sum | sha256sum | cut -d' ' -f1)
}

# VmHWM of every JVM whose command line carries this shard's marker, kB.
peak_rss_kb() {
  local total=0 pid rss
  for pid in $(pgrep -f 'hc-shard-[A-D]' 2>/dev/null || true); do
    rss=$(awk '/VmHWM/ {print $2}' "/proc/$pid/status" 2>/dev/null || echo 0)
    total=$((total + rss))
  done
  echo "$total"
}

run_shard() {
  local shard="$1"
  local nses
  nses="$(shard_nses "$shard")"
  local log="$OUT_DIR/shard-$shard.log"
  local marker="hc-shard-$shard"
  echo "[shard $shard] nses: $(echo "$nses" | tr -d '[]' | wc -w) namespaces"
  local start end code peak
  start="$(date +%s)"
  # -J-Dhc.shard marks this shard's JVM command line so peak_rss_kb can
  # find it (the clojure launcher and the JVM share the marker).
  set +e
  "$CLOJURE_BIN" -J-Dhc.shard="$marker" \
    -X:test:compose:server:client :nses "$nses" > "$log" 2>&1 &
  local runner_pid=$!
  peak=0
  while kill -0 "$runner_pid" 2>/dev/null; do
    local cur
    cur="$(peak_rss_kb)"
    [ "$cur" -gt "$peak" ] && peak="$cur"
    sleep 2
  done
  wait "$runner_pid"
  code=$?
  set -e
  end="$(date +%s)"
  local tests assertions failures errors
  tests="$(grep -oE 'Ran [0-9]+ tests' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  assertions="$(grep -oE '[0-9]+ assertions' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  failures="$(grep -oE '[0-9]+ failures' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  errors="$(grep -oE '[0-9]+ errors' "$log" | tail -1 | grep -oE '[0-9]+' || echo '?')"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$shard" "$(echo "$nses" | tr -d '[]' | wc -w)" \
    "$((end - start))" "$code" "$peak" \
    "$tests" "$assertions" "$failures" "$errors" >> "$SUMMARY"
  echo "[shard $shard] exit=$code seconds=$((end - start)) peak_rss_kb=$peak log=$log"
  return "$code"
}

# Guard: no other Herdchestra app/test JVM may be running; shards and E2E
# apps share /tmp and store locks.
if pgrep -f 'hc\.server\.app|hc-shard-' >/dev/null 2>&1; then
  echo "refusing to run: another Herdchestra JVM is already running" >&2
  pgrep -af 'hc\.server\.app|hc-shard-' >&2 || true
  exit 3
fi

PRE_HASH="$(hash_tree)"
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

POST_HASH="$(hash_tree)"
echo "[gate] POST tree hash: $POST_HASH"
if [ "$PRE_HASH" != "$POST_HASH" ]; then
  echo "[gate] FAIL: tree hash changed during the run (PRE=$PRE_HASH POST=$POST_HASH)" >&2
  failed=1
else
  echo "[gate] OK: PRE == POST ($PRE_HASH)"
fi

echo "summary: $SUMMARY"
exit "$failed"
