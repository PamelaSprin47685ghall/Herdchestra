#!/usr/bin/env bash
# Node harness for hc.client.diagnostics.ui-test.
#
# This is the real React element-tree render test documented in
# src/hc/client/diagnostics/AGENTS.md section 8.
#
# Fulcro's `components.cljc` does `goog.require` on the `react` namespace at
# compile time, so the compiler must be handed the `:foreign-libs` shims that
# provide it.  `:node-modules-dirs` alone is not enough: it feeds the runtime
# require path, not the compiler's namespace resolution, and the build dies
# with "goog.require could not find: ...react_shim".
#
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
       :language-in :ecmascript-2017
       :rewrite-polyfills false
       :npm-deps false
       :foreign-libs
       [{:file \"test/js/react_shim.js\"
         :provides [\"react\"]
         :module-type :commonjs}
        {:file \"test/js/react_dom_shim.js\"
         :provides [\"react-dom\"]
         :module-type :commonjs}
        {:file \"test/js/react_dom_server_shim.js\"
         :provides [\"react-dom/server\"]
         :module-type :commonjs}
        {:file \"test/js/react_dom_client_shim.js\"
         :provides [\"react-dom/client\"]
         :module-type :commonjs}
        {:file \"test/js/f7_global_shim.js\"
         :provides [\"framework7-react\"]
         :module-type :commonjs}]}" \
  -c hc.client.diagnostics.ui-test
node test/js/run_diagnostics_ui_tests.cjs "$OUT"
