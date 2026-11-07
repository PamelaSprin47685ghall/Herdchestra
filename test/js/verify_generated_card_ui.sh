#!/usr/bin/env bash
# Build and run the generated form/chart card React render tests.
#
# This is a focused Node harness; the production build is shadow-cljs :app.
set -euo pipefail

cd "$(dirname "$0")/../.."
OUT=out-generated-card-ui
rm -rf "$OUT"

clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client \
  -m cljs.main \
  -co "$(cat <<'EDN'
{:target :nodejs
 :output-to "out-generated-card-ui/test.js"
 :output-dir "out-generated-card-ui"
 :optimizations :none
 :main hc.client.generated-card.ui-test/-main
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
)" \
  -c hc.client.generated-card.ui-test

node test/js/run_swarm_ui_tests.cjs "$OUT"
