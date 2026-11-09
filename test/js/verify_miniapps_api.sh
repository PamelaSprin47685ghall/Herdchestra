#!/usr/bin/env bash
# Node harness for hc.client.miniapps.api-test.
#
# The test replaces global fetch and exercises the real generation client.
# This harness never writes resources/public/js.
set -euo pipefail

if [ "${HC_CLJS_HARNESS_TIMEOUT:-0}" != "1" ]; then
  HC_CLJS_HARNESS_TIMEOUT=1 exec timeout -k 10 600 bash "$0" "$@"
fi

cd "$(dirname "$0")/../.."

mkdir -p target
OUT="$(mktemp -d -p target "cljs-gate-miniapps-api.XXXXXX")"
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'miniapps api output kept for inspection: %s\n' "$OUT" >&2
  fi
  exit "$status"
}
trap cleanup EXIT

clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client \
  -m cljs.main \
  -co "{:target :nodejs
       :output-to \"$OUT/main.js\"
       :output-dir \"$OUT\"
       :optimizations :none
       :main hc.client.miniapps.api-test/-main
       :language-in :ecmascript-2017
       :rewrite-polyfills false
       :npm-deps false}" \
  -c hc.client.miniapps.api-test
printf '{"type":"commonjs"}\n' > "$OUT/package.json"
node "$OUT/main.js"
