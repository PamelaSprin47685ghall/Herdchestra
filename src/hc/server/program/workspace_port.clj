(ns hc.server.program.workspace-port
  "Reference `:workspace` port over `hc.core.workspace-gate`.

  Production composition can supply its own map with the same keys; this
  adapter exists so the program runtime can be exercised against the real
  protected-file contract without duplicating it.  It does not own the STM
  board: pass a `:board` adapter that publishes the real action."
  (:require [hc.core.workspace-gate :as wg]))

(defn workspace-gate-port
  "Build the port map described in the program package AGENTS.md.

  Options:
  - `:root` workspace root
  - `:gate` optional shared write gate
  - `:workspace-gate` optional already-composed gate (wins over root/gate)
  - `:board` optional board adapter.  When omitted, this reference port uses
    the explicit boardless test seam; pass a real adapter for eligibility.
  - `:limits` optional batch limits"
  [opts]
  (let [opts (if (contains? opts :workspace-root)
               (assoc opts :root (:workspace-root opts))
               opts)
        board-supplied? (contains? opts :board)
        wg (or (:workspace-gate opts)
               (wg/workspace-gate
                (cond-> (select-keys opts [:root :gate :board :limits
                                           :allow-boardless?])
                  (not board-supplied?)
                  (merge {:board nil :allow-boardless? true}))))
        root (:root wg)]
    {:open-session (fn [_context] (wg/session root))
     :cat (fn [session path] (wg/cat session path))
     :stat (fn [session path] (wg/stat session path))
     :ls (fn [session path] (wg/ls session path))
     :write (fn [session path content] (wg/write session path content))
     :mkdir (fn [session path] (wg/mkdir session path))
     :rm (fn [session path] (wg/rm session path))
     :mv (fn [session source target opts]
           (wg/mv session source target (or opts {})))
     :facts (fn [session]
              (select-keys (wg/snapshot session) [:ops :read-set :checks]))
     :settle!
     (fn [session request]
       ;; Forward the whole documented settle request: the board adapter
       ;; needs `:task-id` (and the application may need `:program-name`,
       ;; `:role`, `:phase`, `:intent-bytes`).  Dropping fields here made a
       ;; board-backed reference port publish an action without a task.
       (let [result (wg/commit! wg (assoc request :batch (wg/snapshot session)))]
         (if (= :APPLIED (:status result))
           {:status :APPLIED
            :session (wg/session root)
            :result result}
           {:status (:status result)
            :session session
            :result result})))}))
