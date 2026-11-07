#!/usr/bin/env bash
# Build and run the real-browser conversation selection regression.
#
# The focused shadow-cljs build mounts only the conversation surface with a
# scripted stream.  Chromium selects a streamed paragraph, receives more
# chunks, and proves the live DOM Range survives without stopping the stream.
#
# Playwright is an external test dependency (the same one used by
# verify_browser_ui.cjs).  Set PLAYWRIGHT_PATH when it is not resolvable from
# the repository, for example:
#   PLAYWRIGHT_PATH=/path/to/node_modules/playwright ./test/js/verify_conversation_selection_browser.sh
#
# The production build is shadow-cljs :app; this harness only writes under
# target/conversation-selection-test.
set -euo pipefail

cd "$(dirname "$0")/../.."

npx shadow-cljs compile :conversation-selection-test
node test/js/run_conversation_selection_browser.cjs
