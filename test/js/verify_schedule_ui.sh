#!/usr/bin/env bash
# Build and run the CLJS schedule workspace render tests.
#
# The production browser build is shadow-cljs :app.  This focused harness uses
# cljs.main with global npm shims so the real React element tree can be
# server-rendered under Node without a browser.
set -euo pipefail

cd "$(dirname "$0")/../.."
OUT=out-schedule-ui
rm -rf "$OUT"

clojure -M:dev:cljs:client -co "$(cat <<'EDN'
{:target :nodejs
 :output-to "out-schedule-ui/test.js"
 :output-dir "out-schedule-ui"
 :optimizations :none
 :main hc.client.schedule.ui-test/-main
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
   :module-type :commonjs}]}
EDN
)" -c hc.client.schedule.ui-test

node test/js/run_schedule_ui_tests.cjs "$OUT"
