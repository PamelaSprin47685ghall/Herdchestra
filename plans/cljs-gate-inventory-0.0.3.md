# CLJS gate inventory — 0.0.3

The 26 `.cljs` test namespaces under `test/hc/client/` are **not** part of
`scripts/test-shards.edn`. That file is consumed by a JVM `clojure.test`
runner, which neither compiles `.cljs` nor loads the Node/browser shims these
tests need. They are run by individual `test/js/verify_*.sh` harnesses and,
from 0.0.3, by the serial gate:

```bash
bash scripts/test-cljs-gate.sh
# CLJS_GATE_ONLY=swarm bash scripts/test-cljs-gate.sh
```

The gate runs the 26 wired harnesses, each bounded by `timeout -k 10 600`,
and keeps logs under `target/cljs-gate/<timestamp>/`. The real-browser
selection harness runs last.

## Coverage table (26 tests)

| # | `.cljs` test | Namespace | Harness | Status |
|---:|---|---|---|---|
| 1 | `artifacts/ui_test.cljs` | `hc.client.artifacts.ui-test` | `test/js/verify_artifacts_ui.sh` | covered |
| 2 | `backup/password_dom_test.cljs` | `hc.client.backup.password-dom-test` | `test/js/verify_backup_password_dom.sh` | covered |
| 3 | `conversation/render_dom_test.cljs` | `hc.client.conversation.render-dom-test` | `test/js/verify_render_dom.sh` | covered |
| 4 | `conversation/scroll_restore_test.cljs` | `hc.client.conversation.scroll-restore-test` | `test/js/verify_scroll_restore.sh` | covered |
| 5 | `conversation/selection_browser_test.cljs` | `hc.client.conversation.selection-browser-test` | `test/js/verify_conversation_selection_browser.sh` (`shadow-cljs :conversation-selection-test`) | covered |
| 6 | `conversation/selection_dom_test.cljs` | `hc.client.conversation.selection-dom-test` | `test/js/verify_conversation_selection_dom.sh` | covered |
| 7 | `diagnostics/bridge_test.cljs` | `hc.client.diagnostics.bridge-test` | `test/js/verify_diagnostics_bridge.sh` | covered |
| 8 | `diagnostics/ui_test.cljs` | `hc.client.diagnostics.ui-test` | `test/js/verify_diagnostics_ui.sh` | covered |
| 9 | `generated_card/dom_test.cljs` | `hc.client.generated-card.dom-test` | `test/js/verify_generated_card_dom.sh` | covered |
| 10 | `generated_card/ui_test.cljs` | `hc.client.generated-card.ui-test` | `test/js/verify_generated_card_ui.sh` | covered |
| 11 | `miniapps/api_test.cljs` | `hc.client.miniapps.api-test` | `test/js/verify_miniapps_api.sh` | covered |
| 12 | `schedule/time_test.cljs` | `hc.client.schedule.time-test` | `test/js/verify_schedule_time.sh` | covered |
| 13 | `schedule/ui_test.cljs` | `hc.client.schedule.ui-test` | `test/js/verify_schedule_ui.sh` | covered |
| 14 | `sessions/controls/ui_test.cljs` | `hc.client.sessions.controls.ui-test` | `test/js/verify_session_controls_dom.sh` | covered |
| 15 | `speech/playback_test.cljs` | `hc.client.speech.playback-test` | `test/js/verify_speech_playback.sh` | covered |
| 16 | `speech/ui_test.cljs` | `hc.client.speech.ui-test` | `test/js/verify_speech_ui.sh` | covered |
| 17 | `swarm/ui_test.cljs` | `hc.client.swarm.ui-test` | `test/js/verify_swarm_ui.sh` | covered |
| 18 | `workbench/draft_storage_test.cljs` | `hc.client.workbench.draft-storage-test` | `test/js/verify_workbench_draft_storage.sh` | covered |
| 19 | `workbench/extensions_bridge_test.cljs` | `hc.client.workbench.extensions-bridge-test` | `test/js/verify_extensions_bridge.sh` | covered |
| 20 | `workbench/materials_bridge_test.cljs` | `hc.client.workbench.materials-bridge-test` | `test/js/verify_materials_bridge.sh` | covered |
| 21 | `workbench/presence_test.cljs` | `hc.client.workbench.presence-test` | `test/js/verify_presence.sh` | covered |
| 22 | `workbench/scale_dom_test.cljs` | `hc.client.workbench.scale-dom-test` | `test/js/verify_workbench_scale_dom.sh` | covered |
| 23 | `workbench/session_command_test.cljs` | `hc.client.workbench.session-command-test` | `test/js/verify_workbench_session_commands.sh` | covered |
| 24 | `workbench/settings_bridge_test.cljs` | `hc.client.workbench.settings-bridge-test` | `test/js/verify_settings_bridge.sh` | covered |
| 25 | `workbench/shell_dom_test.cljs` | `hc.client.workbench.shell-dom-test` | `test/js/verify_workbench_shell_dom.sh` | covered |
| 26 | `workbench/transport_test.cljs` | `hc.client.workbench.transport-test` | `test/js/verify_materials_bridge.sh` (invoked by `materials_bridge_test.cljs` `-main` via `run-tests`) | covered |

`test/js/verify_files_ui.sh` runs `hc.client.files.ui-smoke`; it is a smoke
harness, not one of the `_test.cljs` namespaces in this table.

## Former gaps now wired

All seven now have repository harnesses and are in the gate. None is
inherently manual; `manualResidual` count is **0**. The command drafts below
remain as implementation notes.

### 1. `hc.client.conversation.scroll-restore-test` — difficulty: low/medium
Has `-main`, uses jsdom and React client renderer. Copy the
`verify_render_dom.sh` compile block, change `:main` to
`hc.client.conversation.scroll-restore-test/-main`, keep the same foreign
libs, and run `test/js/run_session_controls_dom_tests.cjs "$OUT"`.

```bash
OUT="$(mktemp -d -p . out-scroll-restore.XXXXXX)"; OUT="${OUT#./}"
clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client -m cljs.main \
  -co "{:target :nodejs :output-to \"$OUT/test.js\" :output-dir \"$OUT\"
        :optimizations :none
        :main hc.client.conversation.scroll-restore-test/-main
        :language-in :ecmascript-2017 :rewrite-polyfills false :npm-deps false
        :foreign-libs [{:file \"test/js/react_shim.js\" :provides [\"react\"] :module-type :commonjs}
                       {:file \"test/js/react_dom_shim.js\" :provides [\"react-dom\"] :module-type :commonjs}
                       {:file \"test/js/react_dom_client_shim.js\" :provides [\"react-dom/client\"] :module-type :commonjs}
                       {:file \"test/js/f7_global_shim.js\" :provides [\"framework7-react\"] :module-type :commonjs}]}" \
  -c hc.client.conversation.scroll-restore-test
node test/js/run_session_controls_dom_tests.cjs "$OUT"
```

### 2. `hc.client.diagnostics.bridge-test` — difficulty: low
Has `-main`; no DOM setup. Compile a Node target and run it directly.

```bash
OUT=target/diagnostics-bridge-test; rm -rf "$OUT"
clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client -m cljs.main \
  -co "{:target :nodejs :output-to \"$OUT/main.js\" :output-dir \"$OUT\"
        :optimizations :none :main hc.client.diagnostics.bridge-test/-main
        :language-in :ecmascript-2017 :rewrite-polyfills false :npm-deps false}" \
  -c hc.client.diagnostics.bridge-test
printf '{"type":"commonjs"}\n' > "$OUT/package.json"
node "$OUT/main.js"
```

### 3. `hc.client.diagnostics.ui-test` — difficulty: low
Exact command already exists in `src/hc/client/diagnostics/AGENTS.md` §8; it
only needs wiring into a `test/js/verify_diagnostics_ui.sh`:

```bash
rm -rf target/diag-ui
clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co '{:target :nodejs
        :output-to "target/diag-ui/main.cjs"
        :output-dir "target/diag-ui"
        :main hc.client.diagnostics.ui-test/-main
        :node-modules-dirs ["node_modules"]}' \
  -c hc.client.diagnostics.ui-test
printf '{"type":"commonjs"}\n' > target/diag-ui/package.json
node target/diag-ui/main.cjs
```

### 4. `hc.client.miniapps.api-test` — difficulty: low
Has `-main`; pure Node fetch stub, no React/DOM.

```bash
OUT=target/miniapps-api-test; rm -rf "$OUT"
clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client -m cljs.main \
  -co "{:target :nodejs :output-to \"$OUT/main.js\" :output-dir \"$OUT\"
        :optimizations :none :main hc.client.miniapps.api-test/-main
        :language-in :ecmascript-2017 :rewrite-polyfills false :npm-deps false}" \
  -c hc.client.miniapps.api-test
printf '{"type":"commonjs"}\n' > "$OUT/package.json"
node "$OUT/main.js"
```

### 5. `hc.client.schedule.time-test` — difficulty: low
Exact command already exists in `src/hc/client/schedule/AGENTS.md` §5; it
needs a `test/js/verify_schedule_time.sh` wrapper:

```bash
clojure -M:dev:cljs:client -co '{:target :nodejs
  :output-to "target/schedule-time-test/main.js"
  :output-dir "target/schedule-time-test"
  :optimizations :none
  :main hc.client.schedule.time-test/-main
  :language-in :ecmascript-2017
  :rewrite-polyfills false
  :npm-deps false}' -c hc.client.schedule.time-test
printf '{"type":"commonjs"}\n' > target/schedule-time-test/package.json
node target/schedule-time-test/main.js
```

### 6. `hc.client.speech.playback-test` — difficulty: low
Has `-main`; pure Node fake audio/URL injection.

```bash
OUT=target/speech-playback-test; rm -rf "$OUT"
clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.12.145"}}}' \
  -M:dev:client -m cljs.main \
  -co "{:target :nodejs :output-to \"$OUT/main.js\" :output-dir \"$OUT\"
        :optimizations :none :main hc.client.speech.playback-test/-main
        :language-in :ecmascript-2017 :rewrite-polyfills false :npm-deps false}" \
  -c hc.client.speech.playback-test
printf '{"type":"commonjs"}\n' > "$OUT/package.json"
node "$OUT/main.js"
```

### 7. `hc.client.workbench.presence-test` — difficulty: medium
No `-main` and no `run-tests` entry point. Add a tiny wrapper namespace (new
file, e.g. `test/hc/client/workbench/presence_gate.cljs`):

```clojure
(ns hc.client.workbench.presence-gate
  (:require [cljs.test :refer-macros [run-tests]]
            [hc.client.workbench.presence-test]))

(defn -main [& _]
  (run-tests 'hc.client.workbench.presence-test))

(set! *main-cli-fn* -main)
```

Then compile it as a Node target (same template as the other pure-Node
tests) and run `node "$OUT/main.js"`.

## manualResidual

- Of the 26 `_test.cljs` files: **0**. All can be automated with harnesses.
- Separate `*_smoke.cljs` files (backup/diagnostics/settings/search/files/
  swarm browser or UI smoke, `extensions/sente_smoke.cljs`,
  `diagnostics/probe_logs.cljs`) remain outside this inventory; those are the
  intended manual/E2E residual and are not counted in the 26.

## Presence test status

The former `fake-storage` arity defect is fixed: the helper now has a
one-argument form (initial content, writes allowed). The test reports
**6 tests / 19 assertions / 0 failures / 0 errors, exit 0**. The harness also
keeps its log-summary check, so a future silent failure still exits nonzero
even if the test loses its `:end-run-tests` override.
