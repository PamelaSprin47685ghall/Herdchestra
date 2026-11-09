#!/usr/bin/env bash
# Node harness for hc.client.diagnostics.ui-test.
#
# This is the real React element-tree render test documented in
# src/hc/client/diagnostics/AGENTS.md section 8.  It uses the npm React
# packages through :node-modules-dirs rather than the foreign-lib shims.
# This harness never writes resources/public/js.
set -euo pipefail

if [ "${HC_CLJS_HARNESS_TIMEOUT:-0}" != "1" ]; then
  HC_CLJS_HARNESS_TIMEOUT=1 exec timeout -k 10 600 bash "$0" "$@"
fi

cd "$(dirname "$0")/../.."

mkdir -p target
OUT="$(mktemp -d -p target "cljs-gate-diagnostics-ui.XXXXXX")"
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'diagnostics UI output kept for inspection: %s\n' "$OUT" >&2
  fi
  exit "$status"
}
trap cleanup EXIT

clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co "{:target :nodejs
       :output-to \"$OUT/main.cjs\"
       :output-dir \"$OUT\"
       :main hc.client.diagnostics.ui-test/-main
       :node-modules-dirs [\"node_modules\"]}" \
  -c hc.client.diagnostics.ui-test
printf '{"type":"commonjs"}\n' > "$OUT/package.json"
node "$OUT/main.cjs"
