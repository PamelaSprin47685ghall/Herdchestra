(ns hc.server.shell
  "Public facade for managed shell sessions.

  The shell path shares the workspace write gate and the board adapter with
  the protected file contract, but it does not call the file preparer: an
  arbitrary command is not a dry-runnable file batch.  See
  `src/hc/server/shell/AGENTS.md` and root `AGENTS.md` §4.4.

  Typical use:

      (def gate (workspace-gate/write-gate))
      (def r (shell/runner {:root workspace-root
                            :gate gate
                            :board (shell.board/swarm-board kernel)
                            :artifact-dir artifact-root}))
      (def session (shell/start! r request))
      (shell/await session)          ; or (shell/run! r request)
      (shell/request-stop! r \"req-1\")

  A completed process is `:COMPLETED` with `:exit-code` facts, not a success
  boolean.  A started process whose end cannot be confirmed is `:UNCERTAIN`
  with `:file-changed? :unknown`; the session keeps W and a watchdog releases
  it only after the process and its known children end."
  (:refer-clojure :exclude [await run!])
  (:require [hc.server.shell.board :as board]
            [hc.server.shell.error :as error]
            [hc.server.shell.output :as output]
            [hc.server.shell.process :as process]
            [hc.server.shell.session :as session]))

;; Construction and lifecycle
(defn runner
  [opts]
  (session/runner opts))

(defn read-only-runner
  "Plan dedicated-worker shell runner: no board, token or claim, shared W,
  best-effort read-only command policy."
  [opts]
  (session/read-only-runner opts))

(defn start!
  [runner request]
  (session/start! runner request))

(defn await
  ([session]
   (session/await session))
  ([session timeout-ms]
   (session/await session timeout-ms)))

(defn run!
  [runner request]
  (session/run! runner request))

(defn call!
  "Alias for `run!`, matching the root specification's `call` entry name."
  [runner request]
  (session/run! runner request))

(defn request-stop!
  ([runner target]
   (session/request-stop! runner target))
  ([runner target opts]
   (session/request-stop! runner target opts)))

(defn action
  [runner request-id]
  (session/action runner request-id))

(defn retry-publish!
  "Retry only a shell action's pending board publication.

  Never reruns the process and never reapplies a file operation.  Returns
  `:published? true` after the action reaches its terminal board phase, or a
  still-pending result with `:retry :publish-only`."
  [runner request-id]
  (session/retry-publish! runner request-id))

(defn status
  [session]
  (session/status session))

(defn result
  [session]
  (session/result session))

(defn subscribe!
  [session listener]
  (session/subscribe! session listener))

(defn unsubscribe!
  [session listener]
  (session/unsubscribe! session listener))

;; Board adapters
(defn memory-board
  []
  (board/memory-board))

(defn swarm-board
  [kernel-or-provider]
  (board/swarm-board kernel-or-provider))

(defn owner-board
  [execute-board-fn board-snapshot-fn]
  (board/owner-board execute-board-fn board-snapshot-fn))

(defn set-actor!
  [board actor-id alive?]
  (board/set-actor! board actor-id alive?))

(defn set-token!
  [board holder-id term-id]
  (board/set-token! board holder-id term-id))

(defn set-claim!
  [board claim]
  (board/set-claim! board claim))

(defn set-task!
  [board task-id state & [extra]]
  (board/set-task! board task-id state extra))

(defn fail-publishes!
  [board n]
  (board/fail-publishes! board n))

(defn board-action
  [board request-id]
  (board/board-action board request-id))

(defn board-state
  [board]
  (board/board-state board))

;; Limits and classification
(def default-inline-limit output/default-inline-limit)
(def default-artifact-limit output/default-artifact-limit)

(defn expected-error?
  [t]
  (error/expected? t))

(defn describe
  [result]
  (error/describe result))

(defn process-info
  [process]
  (process/process-info process))
