#!/usr/bin/env bash
# Node harness for hc.client.schedule.time-test.
#
# The `.cljs` test namespaces are not part of scripts/test-shards.edn; that
# file is consumed by a JVM clojure.test runner.  This harness compiles the
# namespace to a Node target and runs it.  It never writes resources/public/js.
set -euo pipefail

if [ "${HC_CLJS_HARNESS_TIMEOUT:-0}" != "1" ]; then
  HC_CLJS_HARNESS_TIMEOUT=1 exec timeout -k 10 600 bash "$0" "$@"
fi

cd "$(dirname "$0")/../.."

mkdir -p target
OUT="$(mktemp -d -p target "cljs-gate-schedule-time.XXXXXX")"
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'schedule time output kept for inspection: %s\n' "$OUT" >&2
  fi
  exit "$status"
}
trap cleanup EXIT

clojure -M:dev:cljs:client -co "{:target :nodejs
  :output-to \"$OUT/main.js\"
  :output-dir \"$OUT\"
  :optimizations :none
  :main hc.client.schedule.time-test/-main
  :language-in :ecmascript-2017
  :rewrite-polyfills false
  :npm-deps false}" -c hc.client.schedule.time-test
printf '{"type":"commonjs"}\n' > "$OUT/package.json"
node "$OUT/main.js"
