#!/usr/bin/env bash
# Build and run the speech input recording-handler DOM tests.
#
# Regression harness for the render-time side effect where the mic button's
# onClick was an IIFE: entering :recording called stop-recording! during
# render, stopped the MediaRecorder before any chunk, and produced the
# "没有录到声音" empty-blob failure.  The test mounts the real
# speech.ui/input-element in jsdom with fake media devices.
#
# This is a focused Node harness; the production build is shadow-cljs :app.
# All foreign libs live under test/js.  Never point one at node_modules:
# Closure compiles the package's package.json as JavaScript into the output
# tree, and Node then fails with ERR_INVALID_PACKAGE_CONFIG.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Keep concurrent harness runs independent.  The trap removes only this run's
# directory; it never deletes a shared output path.
OUT="$(mktemp -d -p . "out-speech-ui.XXXXXX")"
# Keep the compiler's generated bootstrap paths relative to the repository
# root; an absolute or "./"-prefixed output dir changes their form.
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'speech UI output kept for inspection: %s\n' "$OUT" >&2
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
 :main hc.client.speech.ui-test/-main
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
  -c hc.client.speech.ui-test

node test/js/run_speech_ui_tests.cjs "$OUT"
