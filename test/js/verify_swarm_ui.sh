#!/usr/bin/env bash
# Build and run the CLJS swarm dashboard render tests.
#
# The production browser build is shadow-cljs :app.  This focused harness uses
# cljs.main with global npm shims so the real React/Framework7 element tree can
# be server-rendered under Node without a browser.
set -euo pipefail

cd "$(dirname "$0")/../.."
OUT=out-swarm-ui
rm -rf "$OUT"

clojure -M:dev:cljs:client -co "$(cat <<'EDN'
{:target :nodejs
 :output-to "out-swarm-ui/test.js"
 :output-dir "out-swarm-ui"
 :optimizations :none
 :main hc.client.swarm.ui-test/-main
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
  {:file "test/js/f7_global_shim.js"
   :provides ["framework7-react"]
   :module-type :commonjs}]}
EDN
)" -c hc.client.swarm.ui-test

node test/js/run_swarm_ui_tests.cjs "$OUT"
