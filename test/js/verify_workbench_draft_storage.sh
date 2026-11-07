#!/usr/bin/env bash
# Build and run the workbench draft-storage consumer regression.
#
# E2E01's "quit and reopen" check is browser-local: the workbench stores
# drafts in localStorage, not in the server session record.  This harness
# installs an in-memory localStorage, drives the real `workbench.storage`
# save/load path, and proves:
#   - a same-profile reload restores each session's own text, cursor,
#     selection, attachments, quotes, and version;
#   - a new browser profile is a successful empty store, not a corrupt or
#     failed one;
#   - JSON keywordized session keys are normalized to runtime string IDs;
#   - a write failure is reported as `:storage-unavailable`, never saved.
#
# This is a focused Node harness; the production build is shadow-cljs :app.
# Never point a foreign-lib at node_modules: Closure compiles the package's
# package.json as JavaScript into the output tree, and Node then fails with
# ERR_INVALID_PACKAGE_CONFIG.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Keep concurrent harness runs independent.  The trap removes only this run's
# directory; it never deletes a shared output path.
OUT="$(mktemp -d -p . "out-workbench-draft-storage.XXXXXX")"
# Keep the compiler's generated bootstrap paths relative to the repository
# root; an absolute or "./"-prefixed output dir changes their form.
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'workbench draft storage output kept for inspection: %s\n' "$OUT" >&2
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
 :main hc.client.workbench.draft-storage-test/-main
 :language-in :ecmascript-2017
 :rewrite-polyfills false
 :npm-deps false}
EDN
)" \
  -c hc.client.workbench.draft-storage-test

# The repository package.json declares "type": "module".  cljs.main emits a
# CommonJS bootstrap that uses require(), so the temporary output directory
# needs its own CommonJS package scope.
printf '{"type":"commonjs"}\n' > "$OUT/package.json"
node "$OUT/test.js"
