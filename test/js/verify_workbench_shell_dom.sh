#!/usr/bin/env bash
# Build and run the workbench drawer-layer DOM regression.
#
# E2E03 found the details panel's close button covered by the drawer backdrop
# in the wide layout.  This harness mounts the real shell chrome in jsdom and
# proves:
#   - wide navigation/details columns never install the fixed backdrop;
#   - the details close control transacts even while the wide column is open;
#   - the <=1023px drawer layout owns the backdrop;
#   - opening moves focus to the drawer close control and closing returns it
#     to the control that opened the drawer.
#
# This is a focused Node harness; the production build is shadow-cljs :app.
# All foreign libs live under test/js.  Never point one at node_modules:
# Closure compiles the package's package.json as JavaScript into the output
# tree, and Node then fails with ERR_INVALID_PACKAGE_CONFIG.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Keep concurrent harness runs independent.  The trap removes only this run's
# directory; it never deletes a shared output path.
OUT="$(mktemp -d -p . "out-workbench-shell-dom.XXXXXX")"
# Keep the compiler's generated bootstrap paths relative to the repository
# root; an absolute or "./"-prefixed output dir changes their form.
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'workbench shell DOM output kept for inspection: %s\n' "$OUT" >&2
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
 :main hc.client.workbench.shell-dom-test/-main
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
   :module-type :commonjs}
  {:file "test/js/f7_lite_shim.js"
   :provides ["framework7/lite"]
   :module-type :commonjs}]}
EDN
)" \
  -c hc.client.workbench.shell-dom-test

node test/js/run_workbench_shell_dom_tests.cjs "$OUT"
