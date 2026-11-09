#!/usr/bin/env bash
# Herdchestra CLJS test gate.
#
# These are the browser/DOM/Node `.cljs` harnesses.  They are deliberately
# NOT in scripts/test-shards.edn: that file is consumed by a JVM
# clojure.test runner, which neither compiles `.cljs` nor loads the Node
# shims these tests need.  This gate is their serial entry point.
#
# No flock is taken: each harness compiles into its own `out-*` or
# `target/*` directory and never writes `resources/public/js`.  Runs are
# serial by design because several harnesses use cljs.main and shared npm
# shims.  Every harness is bounded by `timeout -k 10 600`.
#
# Usage:
#   bash scripts/test-cljs-gate.sh
#   CLJS_GATE_ONLY=swarm bash scripts/test-cljs-gate.sh
#
# CLJS_GATE_ONLY matches a substring of either the harness path or the test
# namespace.  Logs are kept under target/cljs-gate/<timestamp>/.
set -u -o pipefail

cd "$(dirname "$0")/.."

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_ROOT="${CLJS_GATE_LOG_ROOT:-target/cljs-gate/$STAMP}"
mkdir -p "$LOG_ROOT"

# Harness | test namespace.  Keep the browser harness last: it drives a real
# Chromium page and is the slowest, most environment-sensitive item.
HARNESSES="$(cat <<'EOF'
test/js/verify_artifacts_ui.sh|hc.client.artifacts.ui-test
test/js/verify_backup_password_dom.sh|hc.client.backup.password-dom-test
test/js/verify_conversation_selection_dom.sh|hc.client.conversation.selection-dom-test
test/js/verify_render_dom.sh|hc.client.conversation.render-dom-test
test/js/verify_extensions_bridge.sh|hc.client.workbench.extensions-bridge-test
test/js/verify_files_ui.sh|hc.client.files.ui-smoke
test/js/verify_generated_card_dom.sh|hc.client.generated-card.dom-test
test/js/verify_generated_card_ui.sh|hc.client.generated-card.ui-test
test/js/verify_materials_bridge.sh|hc.client.workbench.materials-bridge-test
test/js/verify_schedule_ui.sh|hc.client.schedule.ui-test
test/js/verify_session_controls_dom.sh|hc.client.sessions.controls.ui-test
test/js/verify_settings_bridge.sh|hc.client.workbench.settings-bridge-test
test/js/verify_speech_ui.sh|hc.client.speech.ui-test
test/js/verify_swarm_ui.sh|hc.client.swarm.ui-test
test/js/verify_workbench_draft_storage.sh|hc.client.workbench.draft-storage-test
test/js/verify_workbench_scale_dom.sh|hc.client.workbench.scale-dom-test
test/js/verify_workbench_session_commands.sh|hc.client.workbench.session-command-test
test/js/verify_workbench_shell_dom.sh|hc.client.workbench.shell-dom-test
test/js/verify_schedule_time.sh|hc.client.schedule.time-test
test/js/verify_diagnostics_bridge.sh|hc.client.diagnostics.bridge-test
test/js/verify_miniapps_api.sh|hc.client.miniapps.api-test
test/js/verify_speech_playback.sh|hc.client.speech.playback-test
test/js/verify_presence.sh|hc.client.workbench.presence-test
test/js/verify_diagnostics_ui.sh|hc.client.diagnostics.ui-test
test/js/verify_scroll_restore.sh|hc.client.conversation.scroll-restore-test
test/js/verify_conversation_selection_browser.sh|hc.client.conversation.selection-browser-test
EOF
)"

ONLY="${CLJS_GATE_ONLY:-}"
selected=0
failed=0
declare -a FAILED_ITEMS=()

printf '%-52s | %-46s | %4s | %7s | %s\n' \
  "harness" "ns" "exit" "seconds" "PASS/FAIL"
printf '%.0s-' {1..132}
printf '\n'

while IFS='|' read -r script ns; do
  [ -n "${script:-}" ] || continue
  if [ -n "$ONLY" ]; then
    case "$script $ns" in
      *"$ONLY"*) ;;
      *) continue ;;
    esac
  fi
  selected=$((selected + 1))
  safe_name="$(printf '%s' "$script" | sed 's#[^A-Za-z0-9_.-]#_#g')"
  log="$LOG_ROOT/$safe_name.log"
  start="$(date +%s)"
  timeout -k 10 600 bash "$script" >"$log" 2>&1
  code=$?
  end="$(date +%s)"
  elapsed=$((end - start))
  if [ "$code" -eq 0 ]; then
    status="PASS"
  else
    status="FAIL"
    failed=$((failed + 1))
    FAILED_ITEMS+=("$script | $ns | exit=$code | ${elapsed}s | $log")
  fi
  printf '%-52s | %-46s | %4s | %7ss | %s\n' \
    "$script" "$ns" "$code" "$elapsed" "$status"
done <<< "$HARNESSES"

printf '\n'
if [ "$selected" -eq 0 ]; then
  echo "CLJS gate: no harness matched CLJS_GATE_ONLY=$ONLY" >&2
  exit 2
fi

echo "CLJS gate: selected=$selected passed=$((selected - failed)) failed=$failed"
echo "logs: $LOG_ROOT"
if [ "$failed" -ne 0 ]; then
  echo "failed harnesses:"
  printf '  %s\n' "${FAILED_ITEMS[@]}"
  exit 1
fi
exit 0
