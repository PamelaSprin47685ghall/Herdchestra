(ns hc.compose.fleet.router
  "Pure command routing for fleet operations.

  The application router owns transport, epochs, and receipts.  This
  namespace only maps one already-authorized fleet command to the fleet
  service call and returns a uniform result.  It never touches the session
  Owner, a global W, or a model.

  Command shapes:

    {:kind :fleet/fork :slices [...]}
    {:kind :fleet/join :timeout-ms n?}
    {:kind :fleet/complete :swarm-id id :report string :board-version n}
    {:kind :fleet/abandon :swarm-id id :reason any}
    {:kind :fleet/fail :swarm-id id :error any}
    {:kind :fleet/gc}
    {:kind :fleet/state}

  SERVICE may be the concrete fleet service or a host/test map exposing the
  same operations as `:fork`/`:join!`/... functions.  `:fleet/fork` returns
  `{:ok? true :result [{:swarm-id :slice-id :workspace} ...]}` (a resource
  shortage is `{:ok? false :error {:code :INSUFFICIENT_WORKTREES}}`).
  `:fleet/join` returns `{:ok? true :result <joined-result|:none|:no-results>}`
  or `{:ok? false :error ...}`.  `:fleet/complete`/`:fleet/abandon`/
  `:fleet/fail` return only the control receipt and persistence evidence;
  `:fleet/gc` returns only the sweep summary.  No command returns the full
  control state.  `:fleet/state` returns a bounded summary (fleet row fields
  plus the full report for joined rows) and status, never a board, task,
  action, or agent detail."
  (:require [hc.compose.fleet.service :as service]))

(def command-kinds
  #{:fleet/fork :fleet/join :fleet/complete :fleet/abandon
    :fleet/fail :fleet/gc :fleet/state})

(defn- fail
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- invoke
  "Call an injected service function when present, otherwise the namespace
  implementation.  This keeps the router usable with a concrete fleet
  service and with a test/host adapter that exposes the same operations."
  [service key fallback & args]
  (let [bang (keyword (str (name key) "!"))
        f (or (get service key)
              (get service bang)
              fallback)]
    (apply f service args)))

(defn- command-receipt
  "Keep only the command receipt, never the complete control state."
  [result]
  (cond
    (not (map? result))
    {:ok? false
     :error {:code :FLEET_COMMAND_FAILED
             :message "the fleet service did not return a result map"}}

    (not (:ok? result))
    {:ok? false :error (:error result)}

    :else
    (cond-> {:ok? true}
      (contains? result :receipt) (assoc :receipt (:receipt result))
      (contains? result :persisted?) (assoc :persisted? (:persisted? result))
      (contains? result :persist-error) (assoc :persist-error (:persist-error result)))))

(defn- summary-observation
  "Project a raw observation to the bounded Steward row shape."
  [observation]
  (-> observation
      (update :swarms
              (fn [rows]
                (mapv (fn [row]
                        (cond-> (select-keys row service/fleet-row-fields)
                          (= :joined (:status row))
                          (assoc :report (:report row))))
                      rows)))
      (update :events
              (fn [events]
                (vec (take-last service/max-observation-events events))))))

(defn- compact-fork
  [service slices]
  (try
    (let [result (invoke service :fork service/fork slices)]
      (if (= :INSUFFICIENT_WORKTREES result)
        {:ok? false
         :error {:code :INSUFFICIENT_WORKTREES
                 :message "the runtime cannot allocate enough worktrees"}}
        {:ok? true :result result}))
    (catch clojure.lang.ExceptionInfo e
      {:ok? false
       :error (or (:fleet/error (ex-data e))
                  {:code :FLEET_FORK_FAILED
                   :message (or (.getMessage e) (str e))})})
    (catch Throwable t
      {:ok? false
       :error {:code :FLEET_FORK_FAILED
               :message (or (.getMessage t) (str t))}})))

(defn handle
  "Apply one fleet command to SERVICE.

  The caller must have authorized the command.  `:fleet/join` intentionally
  blocks exactly like `service/join!`; call it outside the Owner mailbox."
  [service {:keys [kind] :as request}]
  (case kind
    :fleet/fork
    (compact-fork service (:slices request))

    :fleet/join
    (let [result (invoke service :join! service/join!
                         (select-keys request [:timeout-ms]))]
      (if (and (map? result) (:fleet/error result))
        {:ok? false :error (:fleet/error result)}
        {:ok? true :result result}))

    :fleet/complete
    (command-receipt
     (invoke service :complete! service/complete!
             (:swarm-id request) (:report request)
             (select-keys request [:board-version])))

    :fleet/abandon
    (command-receipt
     (invoke service :abandon! service/abandon!
             (:swarm-id request) (:reason request)))

    :fleet/fail
    (command-receipt
     (invoke service :fail! service/fail!
             (:swarm-id request) (:error request)))

    :fleet/gc
    (let [result (invoke service :gc! service/gc!)]
      (if (map? result)
        (select-keys result [:ok? :removed :failed :interrupted? :error])
        {:ok? false
         :error {:code :FLEET_COMMAND_FAILED
                 :message "the fleet GC did not return a result map"}}))

    :fleet/state
    (let [observation ((or (:observation service) service/observation) service)
          summary (if-let [f (:observation-summary service)]
                    (f service)
                    (summary-observation observation))]
      {:ok? true
       :observation summary
       :status ((or (:status service) service/status) service)})

    (fail :UNKNOWN_FLEET_COMMAND
          "unknown fleet command"
          {:kind kind :known (vec (sort-by str command-kinds))})))
