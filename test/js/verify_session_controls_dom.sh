#!/usr/bin/env bash
# Build and run the session metadata controls DOM interaction tests.
#
# This is a focused Node harness; the production build is shadow-cljs :app.
# It installs jsdom before React/ReactDOM load, then mounts the real
# SessionControls component and clicks rename, pin, archive, restore, delete,
# and purge.  It proves the action keyword crosses the UI boundary, delete and
# purge require a second explicit click, a pending receipt blocks an already
# open form or confirmation, and the purge command carries no workspace target.
#
# All foreign libs live under test/js.  Never point one at node_modules:
# Closure compiles the package's package.json as JavaScript into the output
# tree, and Node then fails with ERR_INVALID_PACKAGE_CONFIG.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Keep concurrent harness runs independent.  The trap removes only this run's
# directory; it never deletes a shared output path.
OUT="$(mktemp -d -p . "out-session-controls-dom.XXXXXX")"
# Keep the compiler's generated bootstrap paths relative to the repository
# root; an absolute or "./"-prefixed output dir changes their form.
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'session controls DOM output kept for inspection: %s\n' "$OUT" >&2
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
 :main hc.client.sessions.controls.ui-test/-main
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
  -c hc.client.sessions.controls.ui-test

node test/js/run_session_controls_dom_tests.cjs "$OUT"
