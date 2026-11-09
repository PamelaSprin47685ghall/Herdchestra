#!/usr/bin/env bash
# Node harness for hc.client.workbench.presence-test.
#
# The test installs fake browser storage globals and exercises the Sente
# handshake identity contract.  The shared client modules pull React/Fulcro,
# so this harness uses the standard foreign-lib shims and jsdom runner.
# It never writes resources/public/js.
set -euo pipefail

if [ "${HC_CLJS_HARNESS_TIMEOUT:-0}" != "1" ]; then
  HC_CLJS_HARNESS_TIMEOUT=1 exec timeout -k 10 600 bash "$0" "$@"
fi

cd "$(dirname "$0")/../.."

mkdir -p target
OUT="$(mktemp -d -p target "cljs-gate-presence.XXXXXX")"
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'presence output kept for inspection: %s\n' "$OUT" >&2
  fi
  exit "$status"
}
trap cleanup EXIT

clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client \
  -m cljs.main \
  -co "$(cat <<EDN
{:target :nodejs
 :output-to "$OUT/test.js"
 :output-dir "$OUT"
 :optimizations :none
 :main hc.client.workbench.presence-test/-main
 :language-in :ecmascript-2017
 :rewrite-polyfills false
 :npm-deps false
 :foreign-libs
 [{:file "test/js/react_shim.js"
   :provides ["react"]
   :module-type :commonjs}
  {:file "test/js/react_dom_shim.js"
   :provides ["react-dom"]
   :module-type :commonjs}
  {:file "test/js/react_dom_server_shim.js"
   :provides ["react-dom/server"]
   :module-type :commonjs}
  {:file "test/js/react_dom_client_shim.js"
   :provides ["react-dom/client"]
   :module-type :commonjs}
  {:file "test/js/f7_global_shim.js"
   :provides ["framework7-react"]
   :module-type :commonjs}]}
EDN
)" \
  -c hc.client.workbench.presence-test
node test/js/run_session_controls_dom_tests.cjs "$OUT" 2>&1 | tee "$OUT/run.log"

# presence_test.cljs predates the standard :end-run-tests exit-code override.
# Keep the harness honest even if the test runner exits zero after reporting
# failures or errors.
if grep -Eq '([1-9][0-9]* (failures|errors))' "$OUT/run.log"; then
  echo "presence test reported failures/errors" >&2
  exit 1
fi
