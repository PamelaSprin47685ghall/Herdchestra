#!/usr/bin/env bash
# Aggregate runner for the Node/jsdom ClojureScript verification scripts
# under test/js/verify_*.sh.
#
# By default this runs every Node/jsdom script (everything except
# verify_conversation_selection_browser.sh, which requires a live Chromium
# binary).  Pass --with-browser to include the browser script as well, or
# pass specific script names/paths to run a subset.
set -euo pipefail

cd "$(dirname "$0")/../.."

# The individual scripts call `clojure` directly.  This repository supplies it
# through Pixi, so put the managed environment first on PATH.  Without this the
# aggregate runner only works when the caller already activated the
# environment, and a bare `npm run verify:js` fails with "clojure: command not
# found" (exit 127).
if [ -d ".pixi/envs/default/bin" ]; then
  PATH="$PWD/.pixi/envs/default/bin:$PATH"
  export PATH
fi

WITH_BROWSER=0
TARGETS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --with-browser) WITH_BROWSER=1; shift ;;
    *) TARGETS+=("$1"); shift ;;
  esac
done

if [ "${#TARGETS[@]}" -eq 0 ]; then
  for script in test/js/verify_*.sh; do
    base="$(basename "$script")"
    if [ "$base" = "verify_all.sh" ]; then
      continue
    fi
    if [ "$base" = "verify_conversation_selection_browser.sh" ] && [ "$WITH_BROWSER" -ne 1 ]; then
      continue
    fi
    TARGETS+=("$script")
  done
fi

FAILED=0
PASSED=0
for script in "${TARGETS[@]}"; do
  if [ ! -f "$script" ] && [ -f "test/js/$script" ]; then
    script="test/js/$script"
  fi
  echo "==> $script"
  if bash "$script"; then
    PASSED=$((PASSED + 1))
  else
    echo "FAIL: $script" >&2
    FAILED=$((FAILED + 1))
  fi
done

echo "verify_all: passed=$PASSED failed=$FAILED"
[ "$FAILED" -eq 0 ]
