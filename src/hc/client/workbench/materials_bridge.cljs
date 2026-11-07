(ns hc.client.workbench.materials-bridge
  "Bridge between the confirmed materials snapshot and the materials UI.

  The bridge projects the Fulcro server copy into `:ui/materials`, submits
  queued materials commands through the Sente transport, and feeds server
  receipts back to the pure materials model.  An accepted write triggers one
  `:materials/snapshot` command because materials writes do not yet schedule
  a gateway publish."
  (:require [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.materials :as materials]
            [hc.client.materials.model :as model]
            [hc.client.workbench.model :as wb-model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce app-ref
  (atom nil))

(defonce fetched-session
  (atom nil))

;; When the last `:materials/snapshot` request was sent.
;;
;; A new instance can answer the first snapshot before the materials library
;; is ready.  Without a retry the panel stays on `材料库未就绪` forever, so a
;; not-ready projection is re-requested on a short throttle until the server
;; reports a revision.
(defonce fetched-at (atom nil))

;; Diagnostic copy of the last projected materials state.  Never read this to
;; decide what is true: the Fulcro database's `:ui/materials` is the single
;; authority, so a state the database rejected as stale cannot be revived by a
;; later receipt.
(defonce last-state
  (atom nil))

;; Throttle for the pending-command receipt sweep.  Materials commands live
;; in `:ui/materials :commands`, outside the `:ui/command` recovery sweep,
;; so a receipt lost to a stale subscription or a dropped event would hold
;; its pending lock forever.  Re-querying receipts on a short throttle lets
;; the gateway answer from the durable gate without re-sending the command.
(defonce receipt-sweep-at (atom nil))

;; Interval that drives the sweep independently of sync events.  A command
;; whose send was acknowledged but whose receipt was lost would otherwise
;; wait forever: sync only runs on server-revision/connection changes, and
;; the pending lock refuses every later command on the same key.
(defonce receipt-sweep-timer (atom nil))

(def ^:private receipt-sweep-ms 5000)

(defn pending-receipt-requests
  "The `[:hc/receipt ...]` payloads for commands still pending in STATE.

  Pending means `:queued` or `:submitting`: a command whose transport ack
  arrived but whose domain receipt never did stays `:submitting` forever
  without this query.  The sweep is the only path that lets the gateway's
  durable gate answer `:not-found` for a command it never accepted.
  `:dispatching`/`:outcome-unknown` are receivable placeholders the model
  writes for receipts still in flight; sweeping them recovers a lost push
  instead of leaving the command non-terminal forever."
  [state]
  (->> (:commands state)
       vals
       (filter #(contains? (conj model/pending-statuses
                                  :dispatching :outcome-unknown)
                           (:status %)))
       (take 8)
       (mapv (fn [command]
               {:command-id (:command-id command)
                :session-id (get-in command [:payload :session-id])}))))

(defn recover-pending-materials-commands!
  "Ask the gateway for the receipts of commands still pending in
  `:ui/materials`.  The server answers a receipt query with one CUSTOM
  `hc/command-receipt` event (or `:not-found`), which `handle-event!`
  applies through the normal receipt path.  This never re-sends a command.

  `transport/receipt!` returns false only while the channel is closed;
  that is not fatal — the next sweep tick retries.  The query omits the
  remembered subscription id so the gateway answers on the uid's current
  subscription even when the remembered generation went stale."
  ([state now-ms]
   (recover-pending-materials-commands! state now-ms transport/receipt!))
  ([state now-ms send-receipt!]
   (let [pending (pending-receipt-requests state)]
     (when (and (seq pending)
                (or (nil? @receipt-sweep-at)
                    (> (- now-ms @receipt-sweep-at) 2000)))
       (reset! receipt-sweep-at now-ms)
       (js/setTimeout
        (fn []
          (let [failed (count
                        (filter
                         (fn [{:keys [command-id session-id]}]
                           (not (send-receipt! {:command-id command-id
                                               :session-id session-id})))
                         pending))]
            (when (pos? failed)
              (log/warn "Materials receipt sweep could not send queries"
                        {:pending (count pending) :failed failed}))))
        0)))))

(declare state-from-db)
(declare current-with-local)
(declare accepted-send-command clear-turn-after-send! clear-turn-selection-for
         matching-accepted-sends)

(defn- sweep-pending-materials-commands!
  "One sweep tick: read the current `:ui/materials` and query receipts for
  its pending commands.  Runs on the interval even when no sync event
  arrives, so a lost receipt cannot hold the pending lock forever."
  []
  (when-let [app @app-ref]
    (try
      (let [db (application/current-state app)]
        (recover-pending-materials-commands!
         (current-with-local db)
         (js/Date.now)))
      (catch :default error
        (log/warn "Materials receipt sweep failed" error)))))

(defn set-app!
  [app]
  (reset! app-ref app))

(defn- state-from-db
  [db]
  (or (:ui/materials db) (materials/initial-state)))

(defn- current-with-local
  "DB state with the local keys of the last committed write merged in.

  `transact-state!` commits `:ui/materials` through a queued Fulcro
  transaction, so a `state-from-db` read can run before that commit lands
  and hand back a projection missing the local delta the write carried —
  a receipt's `:last-move`, command statuses, drafts or selections.  When
  the last write belongs to the same session as the database projection,
  merge its local fields back in so the next transaction cannot lose them
  (D-28).  A session change still discards them through `begin-session`.
  "
  [db]
  (let [current (state-from-db db)
        session-id (:selected-session-id current)]
    (if (and session-id
             (= session-id (:selected-session-id @last-state)))
      (model/merge-local-state current @last-state)
      current)))

(defn- transact-state!
  [state]
  (let [db (when-let [app @app-ref] (application/current-state app))
        current (:ui/materials db)
        same-session? (= (:selected-session-id state)
                         (:selected-session-id current))
        stale? (and same-session?
                    (integer? (:revision state))
                    (integer? (:revision current))
                    (< (:revision state) (:revision current)))
        ;; A stale write is stale only in its server-derived revision: the
        ;; database already holds a newer snapshot.  Dropping the whole
        ;; state would also drop the local delta the write carried — a
        ;; receipt's `:last-move`, a command status, drafts and selections.
        ;; Merge those local fields onto the current projection instead.
        next-state (if stale?
                     (model/merge-local-state current state)
                     state)]
    (reset! last-state next-state)
    (when-let [app @app-ref]
      ;; Write the local `:ui/materials` slice through its guarded mutation
      ;; so the revision check has one owner.  A direct atom write plus a
      ;; scheduled render remains the fallback if the transaction throws.
      (try
        (comp/transact! app [(mutations/set-materials-state
                              {:new-state next-state})])
        (catch :default error
          (log/warn "Materials state transaction failed; writing directly"
                    error)
          (swap! (:com.fulcrologic.fulcro.application/state-atom app)
                 assoc :ui/materials next-state)
          (application/schedule-render! app))))))

(defn- notify!
  [tone text]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/notify
                      {:notice {:id (str "materials-" (random-uuid))
                                :tone tone
                                :text text
                                :at (str (js/Date.))}})])))

(defn- submit!
  [envelope]
  (let [sent? (try
                (transport/command! envelope)
                (catch :default error
                  (log/warn "Materials command transport failed" error)
                  false))]
    (when-not sent?
      (notify! :warn "材料命令未发送：实时通道未打开或已中断。"))
    (boolean sent?)))

(defn dispatch!
  "Apply one materials action and submit every queued envelope."
  [action]
  (let [db (when-let [app @app-ref] (application/current-state app))
        current (current-with-local db)
        {:keys [state]} (materials/reduce-action current action)
        state (materials/flush-outbox! state submit!)]
    (transact-state! state)
    ;; A queued command may already be pending from an earlier lost receipt;
    ;; re-query receipts here so a new action also unblocks the sweep path
    ;; without waiting for the interval or a sync event.
    (recover-pending-materials-commands! state (js/Date.now))
    state))

(defn- receipt-event?
  [event]
  (let [type (or (:type event) (:event event))
        name (or (:name event) (:event event))]
    (or (contains? #{:hc/receipt :hc/command-receipt
                     "hc/receipt" "hc/command-receipt"}
                   type)
        (contains? #{:hc/receipt :hc/command-receipt
                     "hc/receipt" "hc/command-receipt"}
                   name))))

(defn- apply-receipt-event!
  [event]
  (let [receipt (or (:value event) (:receipt event) event)
        command-id (model/receipt-command-id receipt)]
    ;; The receipt is applied inside the deferred task, not here: a
    ;; snapshot publish can land between this callback and the flush, and
    ;; writing the state captured above would be dropped by transact-state!
    ;; as stale, leaving the command :submitting forever and blocking the
    ;; next write on the same key.  Re-reading the database keeps the
    ;; receipt on the freshest revision; the pending-status check keeps a
    ;; duplicate receipt from queueing a second snapshot command.
    (js/setTimeout
     (fn []
       (let [db (when-let [app @app-ref]
                  (application/current-state app))
             current (current-with-local db)
             command (get-in current [:commands command-id])
             ;; A receipt sweep can answer while the server still holds
             ;; the :dispatching/:outcome-unknown placeholder; applying it
             ;; keeps the command moving toward its terminal receipt.
             receivable? #{:queued :submitting :dispatching
                           :outcome-unknown}
             ;; A receipt can beat the transaction that records its
             ;; command.  `apply-receipt` parks receipts for unknown
             ;; commands in `:orphan-receipts`; `drain-orphan-receipts`
             ;; replays them once the command lands.  Dropping the event
             ;; here instead would lose the receipt forever and hold the
             ;; pending lock.  A command that already reached a terminal
             ;; status is left alone so a duplicate receipt cannot queue a
             ;; second snapshot command — except a `:refused` one: the
             ;; sweep's `:not-found` is provisional, and a later accepted
             ;; receipt must still land its `:last-move` instead of being
             ;; dropped.
             state (cond
                     (and command
                          (contains? receivable? (:status command)))
                     (materials/apply-receipt current receipt)

                     (and command
                          (= :refused (:status command))
                          (contains? #{:accepted :applied :replayed}
                                     (model/receipt-status receipt)))
                     (materials/apply-receipt current receipt)

                     (and (nil? command) (some? command-id))
                     (materials/apply-receipt current receipt)

                     :else current)
             ;; The accepted write queues one authoritative snapshot
             ;; command.  Send it outside Sente's receive callback so the
             ;; transport is not asked to re-enter itself while delivering
             ;; a receipt.
             state (materials/flush-outbox! state submit!)]
         (transact-state! state)
         ;; Clear the per-turn card staging in the same deferred task, on
         ;; the state the receipt write just committed.  A separate
         ;; setTimeout raced this write: whichever landed last won, so the
         ;; staged card could survive into the next send (D-27) or be
         ;; cleared before its own receipt applied.  The send-command
         ;; lookup runs inside this task too: a receipt can arrive while
         ;; the conversation transaction that records the command is still
         ;; uncommitted, and reading it earlier would skip the clear.
         (when-let [send-command (when-let [app @app-ref]
                                   (accepted-send-command
                                    (application/current-state app)
                                    event))]
           (clear-turn-after-send! send-command))))
     0)))
(defn- accepted-send-command
  [db event]
  (let [receipt (or (:value event) (:receipt event) event)
        command-id (model/receipt-command-id receipt)
        status (model/receipt-status receipt)
        ;; Only the session's latest accepted send may clear staging; an
        ;; older send's receipt must not wipe a newer selection.
        command (some #(when (= command-id (:command-id %)) %)
                      (vals (matching-accepted-sends db)))]
    (when (and command
               (contains? #{:accepted :applied :replayed} status))
      command)))

(defn- submitted-turn-selection
  "The per-turn selection captured by the accepted send command.

  The draft snapshot carries the version attached by
  `materials/attach-turn-selection`; the payload fallback keeps older
  commands and direct `send-message!` callers comparable."
  [command]
  (let [snapshot (:snapshot command)
        payload (:payload command)]
    {:turn-card-ids (vec (or (:turn-card-ids snapshot)
                             (:turn-card-ids payload)
                             []))
     :instruction-values (or (:instruction-values snapshot)
                             (:instruction-values payload)
                             {})
     :temporary-read? (boolean (or (:temporary-read? snapshot)
                                   (:temporary-read? payload)))
     :turn-selection-version (or (:turn-selection-version snapshot)
                                 (:turn-selection-version payload))}))

(defn- clear-turn-after-send!
  [command]
  (let [db (when-let [app @app-ref] (application/current-state app))
        current (current-with-local db)
        next (clear-turn-selection-for current command)]
    (when (not= next current)
      (transact-state! next))))

(defn- matching-accepted-sends
  "The latest accepted `:send-message` command per session in `:ui/command`.

  Only the newest accepted send may clear staging: an older send's
  selection was superseded by the newer one, and its temporary-read
  bypass must not wipe cards staged after it.  Commands without a
  session-id are app-level and never own turn staging."
  [db]
  (->> (:ui/command db)
       vals
       (filter #(and (= :send-message (:kind %))
                     (contains? #{:accepted :applied :replayed}
                                (:status %))
                     (model/non-blank-string? (:session-id %))))
       (reduce (fn [by-session command]
                 (let [sid (:session-id command)
                       prev (get by-session sid)]
                   (if (or (nil? prev)
                           (>= (compare (or (:created-at command) "")
                                        (or (:created-at prev) ""))
                               0))
                     (assoc by-session sid command)
                     by-session)))
               {})))

(defn- clear-turn-selection-for
  "Return STATE with the per-turn staging cleared when COMMAND is an
  accepted send whose submitted selection still matches what is staged.

  Pure so `sync-from-db!` can fold it over every accepted send command —
  including receipts replayed from `:orphan-receipts`, which never pass
  through `apply-receipt-event!` — and commit once."
  [state command]
  (let [session-id (get-in command [:payload :session-id])
        current-session (or (:selected-session-id state)
                            (get-in state [:session :session-id]))
        staged? (or (seq (:turn-card-ids state))
                    (true? (:temporary-read? state)))
        submitted (submitted-turn-selection command)
        submitted-version (:turn-selection-version submitted)
        current-version (long (or (:turn-selection-version state) 0))
        ;; The selection being cleared must not be newer than the one the
        ;; send submitted: cards staged after the send belong to a later
        ;; turn.  The temporary-read bypass additionally requires the
        ;; current selection to be no newer than the submission, so a
        ;; later temporary read is never wiped by an older send.
        version-ok? (or (nil? submitted-version)
                        (<= (long submitted-version) current-version))
        temporary-ok? (and (true? (:temporary-read? submitted))
                           (or (nil? submitted-version)
                               (<= current-version
                                   (long submitted-version))))]
    (if (and staged?
             version-ok?
             (= session-id current-session)
             (or temporary-ok?
                 (materials/turn-selection-matches? state submitted)))
      (materials/clear-turn-selection state)
      state)))

(defn- sweep-accepted-send-clears!
  "Clear per-turn staging for the latest accepted `:send-message` command
  of each session in `:ui/command`.

  Receipt events clear on arrival, but a receipt that arrived before its
  command was mirrored, or one replayed from `:orphan-receipts`, never
  reaches `apply-receipt-event!`'s clear path.  This sweep is idempotent —
  the version guard inside `clear-turn-selection-for` refuses to clear a
  selection the operator changed after the send."
  [state db]
  (reduce clear-turn-selection-for
          state
          (vals (matching-accepted-sends db))))
(defn handle-event!
  "Apply a materials command receipt.  Unrelated receipts are ignored.

  The receipt is handled on a later task because Fulcro may still be
  committing the transaction that recorded the command; reading the database
  inside Sente's receive callback can otherwise miss the command id."
  [event]
  (when (receipt-event? event)
    (js/setTimeout #(apply-receipt-event! event) 0)))

(defn sync-from-db!
  "Project the latest server display copy into `:ui/materials`.

  The projection is revision-guarded inside the model, so a delayed server
  snapshot cannot undo a newer command snapshot."
  [db]
  (let [db (or db
               (when-let [app @app-ref]
                 (application/current-state app))
               {})
        ;; The Fulcro database is the authority for server-derived tables,
        ;; but a queued transaction can leave it one write behind; merge
        ;; the local keys of the last write so a snapshot sync cannot lose
        ;; a receipt's `:last-move`, pending commands or drafts (D-28).
        current (current-with-local db)
        ;; `:ui/connection` reaches the database through a queued
        ;; `install-sync` transaction, so a sync triggered by that very
        ;; commit can read the previous connection view and stamp a stale
        ;; `:sync` phase onto `:ui/materials` — the materials command gate
        ;; then refuses writes with "连接尚未完成同步" until the next sync
        ;; (E2E11).  The adapter atom is the synchronous authority that
        ;; `install-sync` projects; read it directly instead.
        connection (bridge/connection)
        snapshot (materials/snapshot-from-db db)
        selected-id (or (wb-model/selected-session-id db)
                        (get-in snapshot [:materials/session :session-id]))
        session-changed? (and selected-id
                              (not= selected-id (:selected-session-id current)))
        current (if session-changed?
                  (materials/begin-session current selected-id)
                  (assoc current :selected-session-id selected-id))
        state (materials/apply-snapshot current snapshot)
        session-id (or (:selected-session-id state)
                       (get-in state [:session :session-id]))
        ;; Read the selected session's Owner epoch from the same confirmed
        ;; client DB that supplied the materials snapshot.  The adapter
        ;; lookup is only a timing fallback; the global app epoch is not a
        ;; session-command authority.
        command-epoch (or (wb-model/session-command-epoch db session-id)
                          (bridge/command-epoch-for-session session-id))
        state (assoc state
                     :sync {:phase (:phase connection)
                            :command-epoch command-epoch}
                     :transport-available? (boolean (:command-allowed? connection)))
        ;; Receipts can land before the transaction that records their
        ;; command; replay the parked orphans once the database has caught
        ;; up, then flush the snapshot commands they may queue.
        state (materials/drain-orphan-receipts state)
        ;; Orphan-replayed and otherwise missed receipts never reach the
        ;; turn-clear path in `apply-receipt-event!`; fold every accepted
        ;; send command's clear into this projection so staging cannot
        ;; leak into the next turn (D-27).
        state (sweep-accepted-send-clears! state db)
        now-ms (js/Date.now)
        retry? (and (not (:ready? state))
                    (or (nil? @fetched-at)
                        (> (- now-ms @fetched-at) 1500)))
        fetch? (and (:command-allowed? connection)
                    (some? command-epoch)
                    (some? session-id)
                    (or (not= session-id @fetched-session)
                        retry?))
        _ (when-not (and (:command-allowed? connection)
                         (some? command-epoch))
            (reset! fetched-session nil)
            (reset! fetched-at nil))
        _ (when fetch?
            (reset! fetched-session session-id)
            (reset! fetched-at now-ms))]
    (transact-state! (materials/flush-outbox! state submit!))
    (recover-pending-materials-commands! state now-ms)
    (when fetch?
      ;; The initial subscription can carry the gateway's startup baseline.
      ;; Ask for one authoritative materials snapshot after the sync
      ;; transaction, outside the Sente receive callback.
      (js/setTimeout
       (fn []
         (let [db (when-let [app @app-ref] (application/current-state app))]
           (if (bridge/command-allowed?)
             (let [current (current-with-local db)
                   {:keys [state]} (materials/reduce-action
                                    current {:type :materials/refresh})]
               (transact-state! (materials/flush-outbox! state submit!)))
             ;; A connection that dropped before this timer fires retries on
             ;; the next successful sync instead of reporting a false error.
             (reset! fetched-session nil))))
       750))))

(defn install!
  [app]
  (reset! fetched-session nil)
  (reset! last-state nil)
  (set-app! app)
  ;; `install!` runs again on hot reload and after a transport restart; the
  ;; listener must stay unique or one receipt is reduced twice.
  (bridge/ensure-event-listener! ::receipt handle-event!)
  ;; The receipt sweep must not depend on sync events: a command whose
  ;; transport ack arrived but whose receipt was lost would hold its pending
  ;; lock forever otherwise.  One interval per app lifetime is enough;
  ;; repeated `install!` calls keep the existing timer.
  (when (nil? @receipt-sweep-timer)
    (reset! receipt-sweep-timer
            (js/setInterval sweep-pending-materials-commands!
                            receipt-sweep-ms))))
