(ns hc.core.swarm-stm.commands
  "Pure command handlers for the swarm board.

  A command is a map with :op and, for mutations, an optional :request-id.
  Dispatch validates the complete precondition, applies every record change to
  one immutable board value, and returns events plus a result.  finalize then
  recomputes indexes, applies holder retirement, handles wake-all and bumps
  the board version in the same commit.  No handler performs I/O, waits, runs a
  model or touches a file."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.core.swarm-stm.board :as board]))

;; ---------------------------------------------------------------------------
;; small helpers

(defn- fail
  [code & [detail]]
  (cond-> {:ok? false :error code}
    detail (assoc :detail detail)))

(defn- ok
  [board events result]
  {:ok? true :board board :events (vec events) :result result})

(defn- nonblank?
  [s]
  (and (string? s) (not (str/blank? s))))

(defn- valid-input-attachment?
  [attachment]
  (and (map? attachment)
       (or (nonblank? (:attachment-id attachment))
           (nonblank? (:id attachment))
           (nonblank? (:blob-id attachment))
           (nonblank? (:send-blob-id attachment)))
       (or (nil? (:attachment-id attachment))
           (nonblank? (:attachment-id attachment)))
       (or (nil? (:id attachment)) (nonblank? (:id attachment)))
       (or (nil? (:blob-id attachment)) (nonblank? (:blob-id attachment)))
       (or (nil? (:send-blob-id attachment))
           (nonblank? (:send-blob-id attachment)))
       (or (nil? (:name attachment)) (nonblank? (:name attachment)))
       (or (nil? (:filename attachment)) (nonblank? (:filename attachment)))
       (or (nil? (:mime attachment)) (nonblank? (:mime attachment)))
       (or (nil? (:size attachment))
           (and (integer? (:size attachment)) (not (neg? (:size attachment)))))
       (or (nil? (:local-copy? attachment))
           (boolean? (:local-copy? attachment)))
       (or (nil? (:send-route attachment))
           (keyword? (:send-route attachment))
           (nonblank? (:send-route attachment)))
       (or (nil? (:url attachment)) (nonblank? (:url attachment)))))

(defn- normalize-input-attachment
  [attachment]
  (let [attachment-id (or (:attachment-id attachment) (:id attachment))
        normalized (select-keys attachment
                                [:blob-id :send-blob-id :name :filename :mime
                                 :size :local-copy? :send-route :url])]
    (cond-> normalized
      (nonblank? attachment-id) (assoc :attachment-id attachment-id))))

(defn- normalize-input-attachments
  [attachments]
  (mapv normalize-input-attachment attachments))

(defn- require-actor
  [b cmd]
  (let [id (:actor-id cmd)]
    (cond
      (nil? id) (fail :ACTOR_REQUIRED)
      (not (contains? (:actors b) id)) (fail :UNKNOWN_ACTOR {:agent-id id})
      (not (board/alive? b id))
      (fail :ACTOR_INACTIVE
            {:agent-id id
             :life-state (get-in b [:actors id :life-state])})
      :else nil)))

(defn- expected-mismatch
  [b task cmd]
  (let [expected (or (:expected-cond cmd) (:expected cmd))]
    (when expected
      (let [actual {:rev (:rev task)
                    :state (:state task)
                    :owner-id (:owner-id task)
                    :needs-token (:needs-token task)
                    :board-version (:board-version b)}
            mismatch (into {} (filter (fn [[k v]] (not= v (get actual k))) expected))]
        (when (seq mismatch)
          {:expected expected
           :actual (select-keys actual (keys expected))
           :mismatch mismatch
           :current (select-keys task
                                [:task-id :state :rev :owner-id :needs-token
                                 :wait-for :child-ids :remaining-work])})))))

(defn- action-for-claim
  [b claim-id]
  (some #(when (and (= claim-id (:claim-id %))
                    (not= :finished (:phase %)))
           %)
        (vals (:actions b))))

(defn- active-actions-for-task
  [b task-id]
  (filter #(and (= task-id (:task-id %))
                (contains? board/active-action-phases (:phase %)))
          (vals (:actions b))))

(defn- action-in-flight?
  [b task-id]
  (boolean (seq (active-actions-for-task b task-id))))

(defn- actions-for-actor
  [b agent-id]
  (filter #(= agent-id (:agent-id %)) (vals (:actions b))))

(defn- remove-actions
  [b action-ids]
  (let [action-ids (set action-ids)]
    (reduce (fn [b aid]
              (let [a (get-in b [:actions aid])
                    actor-id (:agent-id a)]
                (cond-> b
                  true (update :actions dissoc aid)
                  (and actor-id (= aid (get-in b [:actors actor-id :active-action-id])))
                  (assoc-in [:actors actor-id :active-action-id] nil))))
            b
            action-ids)))

(defn- remove-pre-side-effect-actions
  "Drop actions that have not entered a side-effect phase.  Callers must have
  rejected :applying/:running/:uncertain first."
  [b task-id]
  (let [ids (for [a (vals (:actions b))
                  :when (and (= task-id (:task-id a))
                             (contains? #{:claimed :preparing :waiting-gate}
                                        (:phase a)))]
              (:action-id a))]
    (remove-actions b ids)))

(defn- release-claim
  "Remove one claim from the task and claim index.  Does not bump task rev;
  callers own that decision."
  [b claim-id]
  (let [c (get-in b [:claims claim-id])
        task-id (:task-id c)]
    (cond-> b
      true (update :claims dissoc claim-id)
      (and task-id (= claim-id (get-in b [:tasks task-id :claim-id])))
      (assoc-in [:tasks task-id :claim-id] nil))))

(defn- publishable?
  [x]
  (or (true? x)
      (and (map? x) (true? (:ok? x)))))

;; ---------------------------------------------------------------------------
;; actors

(defn- auto-slot
  [b]
  (loop [n (inc (get-in b [:counters :actor] 0))]
    (let [slot (keyword (str "slot-" n))]
      (if (some #(and (= slot (:slot-id %))
                      (= :alive (:life-state %)))
                (vals (:actors b)))
        (recur (inc n))
        slot))))

(defn- dispatch-register-actor
  [b cmd]
  (let [slot (or (:slot-id cmd) (auto-slot b))]
    (if (some #(and (= slot (:slot-id %))
                    (= :alive (:life-state %)))
              (vals (:actors b)))
      (fail :SLOT_OCCUPIED {:slot-id slot})
      (let [[b id] (board/alloc-id b :actor :agent)
            generation (inc (count (filter #(= slot (:slot-id %))
                                           (vals (:actors b)))))
            actor {:agent-id id
                   :slot-id slot
                   :role :normal
                   :life-state :alive
                   :active-action-id nil
                   :created-version (inc (:board-version b))
                   :dead-at-version nil
                   :generation generation}]
        (ok (assoc-in b [:actors id] actor)
            [{:type :actor-registered :agent-id id :slot-id slot
              :generation generation}]
            {:op :register-actor :agent-id id :slot-id slot
             :generation generation})))))

;; ---------------------------------------------------------------------------
;; task creation

(defn- dispatch-create-task
  [b cmd]
  (or (require-actor b cmd)
      (let [goal (:goal cmd)
            remaining (:remaining-work cmd)
            done-when (:done-when cmd)
            owner (:owner-id cmd)
            needs-token (boolean (:needs-token cmd))
            wait-for (set (:wait-for cmd #{}))
            ledger-key (:ledger-key cmd)]
        (cond
          (not (nonblank? goal))
          (fail :INVALID_TASK {:field :goal :reason :missing})

          (not (nonblank? remaining))
          (fail :INVALID_TASK {:field :remaining-work :reason :missing})

          (not (nonblank? done-when))
          (fail :INVALID_TASK {:field :done-when :reason :missing})

          (and owner (not (board/alive? b owner)))
          (fail :UNKNOWN_ACTOR {:agent-id owner})

          (and ledger-key (contains? (:ledger b) ledger-key))
          (fail :LEDGER_KEY_IN_USE {:ledger-key ledger-key})

          (and ledger-key
               (some #(= ledger-key (:ledger-key %)) (vals (:tasks b))))
          (fail :LEDGER_KEY_IN_USE
                {:ledger-key ledger-key
                 :reason :historical-root-task})

          (some #(not (contains? (:tasks b) %)) wait-for)
          (fail :UNKNOWN_WAIT_TASK
                {:wait-for (set (remove #(contains? (:tasks b) %)) wait-for)})

          (some #(= :done (get-in b [:tasks % :state])) wait-for)
          (fail :WAIT_TASK_DONE
                {:wait-for (set (filter #(= :done (get-in b [:tasks % :state]))
                                        wait-for))})

          :else
          (let [[b id] (board/alloc-id b :task :task)
                state (if (seq wait-for) :waiting :ready)
                task {:task-id id
                      :ledger-key ledger-key
                      :goal goal
                      :remaining-work remaining
                      :done-when done-when
                      :state state
                      :wait-for wait-for
                      :needs-token needs-token
                      :owner-id owner
                      :claim-id nil
                      :parent-id nil
                      :child-ids []
                      :result-refs []
                      :handoff-note (:handoff-note cmd)
                      :rev 0
                      :created-version (inc (:board-version b))
                      :done-at-version nil}
                b (assoc-in b [:tasks id] task)
                b (if ledger-key
                    (assoc-in b [:ledger ledger-key]
                              {:task-id id :state :open
                               :created-version (inc (:board-version b))})
                    b)]
            (ok b
                [{:type :task-created :task-id id :state state
                  :ledger-key ledger-key}]
                {:op :create-task :task-id id :state state
                 :ledger-key ledger-key}))))))

;; ---------------------------------------------------------------------------
;; split

(defn- validate-child-spec
  [b parent parent-id actor-id spec]
  (let [goal (:goal spec)
        remaining (:remaining-work spec)
        done-when (:done-when spec)
        owner (:owner-id spec)
        needs-token (boolean (:needs-token spec))
        wait-for (set (:wait-for spec #{}))]
    (cond
      (not (map? spec))
      (fail :INVALID_CHILD {:reason :not-a-map})

      (not (nonblank? goal))
      (fail :INVALID_CHILD {:field :goal :reason :missing})

      (not (nonblank? remaining))
      (fail :INVALID_CHILD {:field :remaining-work :reason :missing})

      (not (nonblank? done-when))
      (fail :INVALID_CHILD {:field :done-when :reason :missing})

      (and owner (not= owner actor-id))
      (fail :INVALID_CHILD
            {:field :owner-id :reason :cannot-assign
             :owner-id owner :actor-id actor-id})

      (and owner
           (or (:needs-token parent)
               (not= actor-id (:owner-id parent))))
      (fail :INVALID_CHILD
            {:field :owner-id :reason :splitter-does-not-own-parent
             :owner-id owner :parent-owner-id (:owner-id parent)
             :parent-needs-token (:needs-token parent)})

      (and owner (not (board/alive? b owner)))
      (fail :INVALID_CHILD {:field :owner-id :reason :unknown-actor
                            :owner-id owner})

      (some #(not (contains? (:tasks b) %)) wait-for)
      (fail :INVALID_CHILD
            {:field :wait-for
             :unknown (set (remove #(contains? (:tasks b) %)) wait-for)})

      (some #(= :done (get-in b [:tasks % :state])) wait-for)
      (fail :INVALID_CHILD
            {:field :wait-for :reason :done-task
             :done (set (filter #(= :done (get-in b [:tasks % :state]))
                                wait-for))})

      (or (contains? wait-for parent-id)
          (some #(board/reaches? b % parent-id) wait-for))
      (fail :DEPENDENCY_CYCLE
            {:task-id parent-id :child-goal goal
             :reason :child-waits-on-ancestor})

      :else nil)))

(defn- dispatch-split
  [b cmd]
  (or (require-actor b cmd)
      (let [pid (:task-id cmd)
            parent (get-in b [:tasks pid])
            child-specs (:children cmd)
            requested-wait (set (:wait-for cmd #{}))
            actor-id (:actor-id cmd)
            ;; Concurrent splits must not overwrite the winner's structure.
            ;; Without an explicit condition, the parent's current rev is the
            ;; minimal read condition.
            cmd (assoc cmd :expected-cond
                       (or (:expected-cond cmd) (:expected cmd)
                           {:rev (:rev parent)}))]
        (cond
          (nil? parent)
          (fail :UNKNOWN_TASK {:task-id pid})

          (= :done (:state parent))
          (fail :TASK_DONE {:task-id pid})

          (not (contains? #{:ready :waiting} (:state parent)))
          (fail :TASK_NOT_SPLITTABLE {:task-id pid :state (:state parent)})

          (not (nonblank? (:remaining-work cmd)))
          (fail :INVALID_TASK {:field :remaining-work :reason :missing})

          (not (and (sequential? child-specs) (seq child-specs)))
          (fail :NO_CHILDREN {:task-id pid})

          (expected-mismatch b parent cmd)
          (fail :EXPECTED_CONDITION_FAILED
                (expected-mismatch b parent cmd))

          (action-in-flight? b pid)
          (fail :ACTION_IN_FLIGHT
                {:task-id pid
                 :action-ids (mapv :action-id (active-actions-for-task b pid))})

          (some #(not (contains? (:tasks b) %)) requested-wait)
          (fail :UNKNOWN_WAIT_TASK
                {:wait-for (set (remove #(contains? (:tasks b) %) requested-wait))})

          (some #(= :done (get-in b [:tasks % :state])) requested-wait)
          (fail :WAIT_TASK_DONE
                {:wait-for (set (filter #(= :done (get-in b [:tasks % :state]))
                                        requested-wait))})

          (some #(not (contains? (set (:child-ids parent)) %)) requested-wait)
          (fail :WAIT_NOT_CHILD
                {:task-id pid
                 :not-children (set (remove #(contains? (set (:child-ids parent)) %)
                                            requested-wait))})

          (some #(board/reaches? b % pid) requested-wait)
          (fail :DEPENDENCY_CYCLE {:task-id pid
                                   :wait-for requested-wait})

          :else
          (if-let [err (some #(validate-child-spec b parent pid actor-id %)
                             child-specs)]
            err
            (let [claim-id (:claim-id parent)
                  claim (get-in b [:claims claim-id])]
              (if (and claim (not= actor-id (:agent-id claim)))
                (fail :ALREADY_CLAIMED
                      {:task-id pid :claim-id claim-id
                       :agent-id (:agent-id claim)})
                (let [;; The claimant may accept a new structure before a
                      ;; side effect; release the stale execution claim.
                      b (if claim (release-claim b claim-id) b)
                      b (remove-pre-side-effect-actions b pid)
                      existing-wait (set (filter #(not= :done
                                                       (get-in b [:tasks % :state]))
                                                 (:wait-for parent)))
                      [b new-ids]
                      (reduce (fn [[b ids] spec]
                                (let [[b id] (board/alloc-id b :task :task)
                                      needs-token (boolean (:needs-token spec))
                                      owner (when-not needs-token (:owner-id spec))
                                      wait-for (set (:wait-for spec #{}))
                                      state (if (seq wait-for) :waiting :ready)
                                      task {:task-id id
                                            :ledger-key nil
                                            :goal (:goal spec)
                                            :remaining-work (:remaining-work spec)
                                            :done-when (:done-when spec)
                                            :state state
                                            :wait-for wait-for
                                            :needs-token needs-token
                                            :owner-id owner
                                            :claim-id nil
                                            :parent-id pid
                                            :child-ids []
                                            :result-refs []
                                            :handoff-note (:handoff-note spec)
                                            :rev 0
                                            :created-version (inc (:board-version b))
                                            :done-at-version nil}]
                                  [(assoc-in b [:tasks id] task)
                                   (conj ids id)]))
                              [b []]
                              child-specs)
                      effective-wait (into (set existing-wait)
                                           (concat new-ids requested-wait))
                      b (-> b
                            (assoc-in [:tasks pid :state] :waiting)
                            (assoc-in [:tasks pid :wait-for] effective-wait)
                            (update-in [:tasks pid :child-ids] into new-ids)
                            (update-in [:tasks pid :remaining-work]
                                       (constantly (:remaining-work cmd)))
                            (update-in [:tasks pid :rev] inc))]
                  (ok b
                      (cond-> [{:type :task-split
                                :task-id pid
                                :child-ids new-ids
                                :wait-for effective-wait
                                :released-claim-id claim-id}]
                        claim-id (conj {:type :claim-released
                                        :task-id pid
                                        :claim-id claim-id
                                        :reason :structural-change}))
                      {:op :split
                       :task-id pid
                       :child-ids new-ids
                       :wait-for effective-wait
                       :state :waiting
                       :released-claim-id claim-id})))))))))

;; ---------------------------------------------------------------------------
;; claim / release / finish

(defn- dispatch-claim
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            actor-id (:actor-id cmd)
            reason (:atomicity-reason cmd)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (= :done (:state t)) (fail :TASK_DONE {:task-id tid})
          (= :waiting (:state t))
          (fail :TASK_NOT_READY {:task-id tid :state :waiting
                                 :wait-for (:wait-for t)})
          (:claim-id t)
          (fail :ALREADY_CLAIMED
                {:task-id tid :claim-id (:claim-id t)
                 :agent-id (get-in b [:claims (:claim-id t) :agent-id])})
          (expected-mismatch b t cmd)
          (fail :EXPECTED_CONDITION_FAILED (expected-mismatch b t cmd))
          (:needs-token t)
          (cond
            (nil? (get-in b [:token-slot :holder-id]))
            (fail :TOKEN_REQUIRED {:task-id tid :reason :no-holder})
            (not (board/holder? b actor-id))
            (fail :TOKEN_REQUIRED
                  {:task-id tid
                   :holder-id (get-in b [:token-slot :holder-id])})
            :else
            (if-not (nonblank? reason)
              (fail :ATOMICITY_REASON_REQUIRED {:task-id tid})
              (let [[b cid] (board/alloc-id b :claim :claim)
                    claim {:claim-id cid
                           :task-id tid
                           :agent-id actor-id
                           :atomicity-reason reason
                           :phase :claimed
                           :created-version (inc (:board-version b))}]
                (ok (-> b
                        (assoc-in [:claims cid] claim)
                        (assoc-in [:tasks tid :claim-id] cid)
                        (update-in [:tasks tid :rev] inc))
                    [{:type :task-claimed :task-id tid :claim-id cid
                      :agent-id actor-id}]
                    {:op :claim :task-id tid :claim-id cid
                     :agent-id actor-id}))))
          (and (:owner-id t) (not= actor-id (:owner-id t)))
          (fail :NOT_OWNER {:task-id tid :owner-id (:owner-id t)})
          (not (nonblank? reason))
          (fail :ATOMICITY_REASON_REQUIRED {:task-id tid})
          :else
          (let [[b cid] (board/alloc-id b :claim :claim)
                claim {:claim-id cid
                       :task-id tid
                       :agent-id actor-id
                       :atomicity-reason reason
                       :phase :claimed
                       :created-version (inc (:board-version b))}]
            (ok (-> b
                    (assoc-in [:claims cid] claim)
                    (assoc-in [:tasks tid :claim-id] cid)
                    (cond-> (nil? (:owner-id t))
                      (assoc-in [:tasks tid :owner-id] actor-id))
                    (update-in [:tasks tid :rev] inc))
                [{:type :task-claimed :task-id tid :claim-id cid
                  :agent-id actor-id}]
                {:op :claim :task-id tid :claim-id cid
                 :agent-id actor-id}))))))

(defn- dispatch-release
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            cid (:claim-id cmd)
            actor-id (:actor-id cmd)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (= :done (:state t)) (fail :TASK_DONE {:task-id tid})
          (not= cid (:claim-id t))
          (fail :CLAIM_CHANGED {:task-id tid :claim-id cid
                                :current-claim-id (:claim-id t)})
          (not= actor-id (get-in b [:claims cid :agent-id]))
          (fail :CLAIM_CHANGED {:task-id tid :claim-id cid
                                :claim-agent-id (get-in b [:claims cid :agent-id])})
          (action-in-flight? b tid)
          (fail :ACTION_IN_FLIGHT
                {:task-id tid
                 :action-ids (mapv :action-id (active-actions-for-task b tid))})
          :else
          (let [b (-> b
                      (remove-pre-side-effect-actions tid)
                      (release-claim cid)
                      (update-in [:tasks tid :rev] inc))]
            (ok b
                [{:type :claim-released :task-id tid :claim-id cid
                  :agent-id actor-id}]
                {:op :release :task-id tid :claim-id cid}))))))

(defn- parent-recovery
  "Remove TASK-ID from every waiter's wait-for.  Only a waiter whose wait-for
  becomes empty moves from :waiting to :ready; no parent is ever auto-done."
  [b task-id]
  (reduce (fn [[b events recoveries] [pid parent]]
            (if (and (not= :done (:state parent))
                     (contains? (:wait-for parent) task-id))
              (let [new-wait (disj (:wait-for parent) task-id)
                    parent? (contains? (set (:child-ids parent)) task-id)
                    new-state (if (and (= :waiting (:state parent))
                                       (empty? new-wait))
                                :ready
                                (:state parent))
                    parent' (-> parent
                                (assoc :wait-for new-wait)
                                (assoc :state new-state)
                                (update :rev inc))
                    recovered? (not= (:state parent) new-state)]
                [(assoc-in b [:tasks pid] parent')
                 (conj events
                       (cond-> {:type (if (and parent? recovered?)
                                        :parent-recovered
                                        :dependency-satisfied)
                                :task-id pid
                                :dependency-id task-id
                                :state new-state}
                         recovered? (assoc :previous-state :waiting)))
                 (cond-> recoveries recovered? (assoc pid new-state))])
              [b events recoveries]))
          [b [] {}]
          (:tasks b)))

(defn- dispatch-finish
  [b cmd env]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            actor-id (:actor-id cmd)
            cid (:claim-id cmd)
            active-claim (:claim-id t)
            claim (get-in b [:claims active-claim])
            read-set (vec (:read-set cmd []))
            verified-read-set (vec (:verified-read-set cmd []))
            verifier (:verify-read-set env)
            summary (:result-summary cmd)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (= :done (:state t)) (fail :TASK_DONE {:task-id tid})
          (and active-claim (not= active-claim cid))
          (fail :CLAIM_CHANGED {:task-id tid :claim-id cid
                                :current-claim-id active-claim})
          (and (nil? active-claim) cid)
          (fail :CLAIM_CHANGED {:task-id tid :claim-id cid
                                :current-claim-id nil})
          (and claim (not= actor-id (:agent-id claim)))
          (fail :CLAIM_CHANGED {:task-id tid :claim-id active-claim
                                :claim-agent-id (:agent-id claim)})
          (and (nil? active-claim)
               (not (board/responsible-for-task? b actor-id t)))
          (fail :NOT_RESPONSIBLE {:task-id tid :owner-id (:owner-id t)
                                  :needs-token (:needs-token t)
                                  :holder-id (get-in b [:token-slot :holder-id])})
          (action-in-flight? b tid)
          (fail :ACTION_IN_FLIGHT
                {:task-id tid
                 :action-ids (mapv :action-id (active-actions-for-task b tid))})
          (expected-mismatch b t cmd)
          (fail :EXPECTED_CONDITION_FAILED (expected-mismatch b t cmd))
          (not (nonblank? summary))
          (fail :RESULT_SUMMARY_REQUIRED {:task-id tid})
          (and (seq read-set) (seq verified-read-set))
          (fail :INVALID_READ_SET
                {:task-id tid
                 :reason "provide either read-set or verified-read-set, not both"})
          (and (seq verified-read-set)
               (not (:under-write-gate? env)))
          (fail :WRITE_GATE_REQUIRED
                {:task-id tid
                 :verified-read-set verified-read-set
                 :reason "verified-read-set metadata requires an under-W caller"})
          (and (seq read-set) (not (:under-write-gate? env)))
          (fail :WRITE_GATE_REQUIRED
                {:task-id tid
                 :read-set read-set
                 :reason "a non-empty read-set must be verified and committed under W"})
          (and (seq read-set) (nil? verifier))
          (fail :READ_SET_VERIFICATION_REQUIRED
                {:task-id tid
                 :read-set read-set
                 :reason "a non-empty read-set needs an explicit workspace verifier"})
          (and (seq read-set) verifier (not (ifn? verifier)))
          (fail :INVALID_VERIFIER
                {:task-id tid
                 :reason "verify-read-set must be a function"})
          :else
          (let [verdict (try
                          (if (seq read-set)
                            (verifier read-set)
                            {:ok? true})
                          (catch Throwable e
                            {:ok? false
                             :reason :read-set-check-failed
                             :message (.getMessage e)}))]
            (if-not (publishable? verdict)
              (fail :FILE_CONFLICT
                    (merge {:task-id tid :read-set read-set}
                           (when (map? verdict) verdict)))
              (let [stored-read-set (if (seq read-set)
                                      read-set
                                      verified-read-set)
                    version (inc (:board-version b))
                    removed-action-ids
                    (vec (sort (map :action-id
                                    (filter #(and (= tid (:task-id %))
                                                  (contains? #{:claimed :preparing
                                                               :waiting-gate}
                                                             (:phase %)))
                                            (vals (:actions b))))))
                    result-ref (cond-> {:summary summary
                                        :read-set stored-read-set
                                        :at-version version}
                                 (seq verified-read-set)
                                 (assoc :verified-read-set verified-read-set))
                    b (-> b
                          (remove-pre-side-effect-actions tid)
                          (update-in [:tasks tid]
                                     (fn [t]
                                       (-> t
                                           (assoc :state :done)
                                           (assoc :claim-id nil)
                                           (assoc :wait-for #{})
                                           (assoc :done-at-version version)
                                           (update :result-refs conj result-ref)
                                           (update :rev inc))))
                          (cond-> active-claim (release-claim active-claim)))
                    [b recovery-events recoveries] (parent-recovery b tid)
                    ledger-key (:ledger-key t)
                    b (if ledger-key
                        (update b :ledger dissoc ledger-key)
                        b)
                    finished-event {:type :task-finished
                                    :task-id tid
                                    :agent-id actor-id
                                    :claim-id cid
                                    :removed-action-ids removed-action-ids
                                    :parent-ids (keys recoveries)
                                    :parent-states recoveries}
                    events (cond-> (into [finished-event] recovery-events)
                             ledger-key (conj {:type :ledger-released
                                               :ledger-key ledger-key
                                               :task-id tid}))]
                (ok b events
                    {:op :finish
                     :task-id tid
                     :parent-ids (vec (keys recoveries))
                     :parent-states recoveries
                     :removed-action-ids removed-action-ids
                     :ledger-released ledger-key
                     :result-ref result-ref}))))))))

;; ---------------------------------------------------------------------------
;; maintenance verbs

(defn- dispatch-cancel
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            reason (:reason cmd)
            children (:child-ids t)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (= :done (:state t)) (fail :TASK_DONE {:task-id tid})
          (not (nonblank? reason)) (fail :REASON_REQUIRED {:task-id tid})
          (seq (filter #(not= :done (get-in b [:tasks % :state])) children))
          (fail :HAS_UNFINISHED_CHILDREN
                {:task-id tid
                 :child-ids (vec (filter #(not= :done (get-in b [:tasks % :state]))
                                         children))})
          (action-in-flight? b tid)
          (fail :ACTION_IN_FLIGHT
                {:task-id tid
                 :action-ids (mapv :action-id (active-actions-for-task b tid))})
          :else
          (let [claim-id (:claim-id t)
                b (-> b
                      (remove-pre-side-effect-actions tid)
                      (cond-> claim-id (release-claim claim-id))
                      (update :tasks dissoc tid))
                ;; Remove every reference from waiting tasks.  A parent also
                ;; loses the child relation.  Cancellation never pretends the
                ;; dependency was satisfied; a waiter that becomes empty is
                ;; merely allowed to make a fresh structural decision.
                [b events recoveries]
                (reduce (fn [[b events recoveries] [wid w]]
                          (if (or (contains? (:wait-for w) tid)
                                  (contains? (set (:child-ids w)) tid))
                            (let [new-wait (disj (:wait-for w) tid)
                                  new-children (vec (remove #{tid} (:child-ids w)))
                                  parent? (contains? (set (:child-ids w)) tid)
                                  new-state (if (and (not= :done (:state w))
                                                     (= :waiting (:state w))
                                                     (empty? new-wait))
                                              :ready
                                              (:state w))
                                  w' (-> w
                                         (assoc :wait-for new-wait)
                                         (assoc :child-ids new-children)
                                         (assoc :state new-state)
                                         (update :rev inc))
                                  recovered? (and (not= :done (:state w))
                                                  (not= (:state w) new-state))]
                              [(assoc-in b [:tasks wid] w')
                               (conj events
                                     (cond-> {:type (if (and parent? recovered?)
                                                      :parent-recovered
                                                      :dependency-cancelled)
                                              :task-id wid
                                              :dependency-id tid
                                              :state new-state}
                                       recovered? (assoc :previous-state :waiting)))
                               (assoc recoveries wid new-state)])
                            [b events recoveries]))
                        [b [] {}]
                        (:tasks b))
                ;; Done children become independent historical tasks.
                b (reduce (fn [b cid]
                            (if (contains? (:tasks b) cid)
                              (update-in b [:tasks cid]
                                         #(-> % (assoc :parent-id nil) (update :rev inc)))
                              b))
                          b
                          children)
                ledger-key (:ledger-key t)
                b (if ledger-key (update b :ledger dissoc ledger-key) b)
                events (cond-> (conj events
                                     {:type :task-cancelled
                                      :event :cancelled
                                      :task-id tid
                                      :agent-id (:actor-id cmd)
                                      :reason reason
                                      :parent-ids (keys recoveries)})
                         ledger-key (conj {:type :ledger-released
                                           :ledger-key ledger-key
                                           :task-id tid}))]
            (ok b events
                {:op :cancel :task-id tid
                 :parent-ids (vec (keys recoveries))
                 :parent-states recoveries
                 :ledger-released ledger-key}))))))

(defn- dispatch-replace
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            replacement (:replacement cmd)
            replacement-wait (set (:wait-for replacement #{}))
            old-parent-id (:parent-id t)
            live-parent-id (when (and old-parent-id
                                      (not= :done
                                            (get-in b [:tasks old-parent-id
                                                       :state])))
                             old-parent-id)
            ancestor-ids (loop [id live-parent-id
                                acc #{}]
                           (if (or (nil? id) (contains? acc id))
                             acc
                             (recur (get-in b [:tasks id :parent-id])
                                    (conj acc id))))
            reason (:reason cmd)
            actor-id (:actor-id cmd)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (= :done (:state t)) (fail :TASK_DONE {:task-id tid})
          (not (nonblank? reason)) (fail :REASON_REQUIRED {:task-id tid})
          (not (map? replacement)) (fail :INVALID_REPLACEMENT {:task-id tid})
          (seq (filter #(not= :done (get-in b [:tasks % :state]))
                       (:child-ids t)))
          (fail :HAS_UNFINISHED_CHILDREN {:task-id tid})
          (action-in-flight? b tid)
          (fail :ACTION_IN_FLIGHT
                {:task-id tid
                 :action-ids (mapv :action-id (active-actions-for-task b tid))})
          (not (nonblank? (:goal replacement)))
          (fail :INVALID_REPLACEMENT {:field :goal :reason :missing})
          (not (nonblank? (:remaining-work replacement)))
          (fail :INVALID_REPLACEMENT {:field :remaining-work :reason :missing})
          (not (nonblank? (:done-when replacement)))
          (fail :INVALID_REPLACEMENT {:field :done-when :reason :missing})
          (and (contains? replacement :wait-for)
               (not (set? (:wait-for replacement))))
          (fail :INVALID_REPLACEMENT {:field :wait-for :reason :not-a-set})
          (some #(not (contains? (:tasks b) %)) replacement-wait)
          (fail :UNKNOWN_WAIT_TASK
                {:wait-for (set (remove #(contains? (:tasks b) %)
                                        replacement-wait))})
          (some #(= :done (get-in b [:tasks % :state])) replacement-wait)
          (fail :WAIT_TASK_DONE {:wait-for replacement-wait})
          (contains? replacement-wait tid)
          (fail :DEPENDENCY_CYCLE {:task-id tid :reason :replacement-waits-on-old})
          (some #(board/reaches? b % tid) replacement-wait)
          (fail :DEPENDENCY_CYCLE {:task-id tid :wait-for replacement-wait})
          (some #(contains? ancestor-ids %) replacement-wait)
          (fail :DEPENDENCY_CYCLE
                {:task-id tid :reason :replacement-waits-on-ancestor
                 :wait-for replacement-wait})
          (and (:owner-id replacement)
               (not= actor-id (:owner-id replacement)))
          (fail :INVALID_REPLACEMENT
                {:field :owner-id :reason :cannot-assign
                 :owner-id (:owner-id replacement)})
          (and (:owner-id replacement)
               (not= (:owner-id replacement) (:owner-id t)))
          (fail :INVALID_REPLACEMENT
                {:field :owner-id :reason :owner-not-preserved
                 :owner-id (:owner-id replacement)
                 :previous-owner-id (:owner-id t)})
          :else
          (let [claim-id (:claim-id t)
                ledger-key (:ledger-key t)
                needs-token (if (contains? replacement :needs-token)
                              (boolean (:needs-token replacement))
                              (:needs-token t))
                old-owner (:owner-id t)
                owner (cond
                        needs-token nil
                        (contains? replacement :owner-id)
                        (:owner-id replacement)
                        (and old-owner (board/alive? b old-owner)) old-owner
                        :else nil)
                new-state (if (seq replacement-wait) :waiting :ready)
                [b new-id] (board/alloc-id b :task :task)
                new-task {:task-id new-id
                          :ledger-key ledger-key
                          :goal (:goal replacement)
                          :remaining-work (:remaining-work replacement)
                          :done-when (:done-when replacement)
                          :state new-state
                          :wait-for replacement-wait
                          :needs-token needs-token
                          :owner-id owner
                          :claim-id nil
                          :parent-id live-parent-id
                          :child-ids []
                          :result-refs []
                          :handoff-note (:handoff-note replacement)
                          :rev 0
                          :created-version (inc (:board-version b))
                          :done-at-version nil}
                b (-> b
                      (assoc-in [:tasks new-id] new-task)
                      (remove-pre-side-effect-actions tid)
                      (cond-> claim-id (release-claim claim-id))
                      (update :tasks dissoc tid))
                b (if ledger-key
                    (assoc-in b [:ledger ledger-key]
                              (assoc (get-in b [:ledger ledger-key])
                                     :task-id new-id))
                    b)
                ;; Parents and other waiters swap the dependency identity.
                [b events]
                (reduce (fn [[b events] [wid w]]
                          (let [wait? (contains? (:wait-for w) tid)
                                child? (contains? (set (:child-ids w)) tid)
                                done? (= :done (:state w))
                                w' (cond-> w
                                     wait? (-> (update :wait-for
                                                       (if done?
                                                         #(disj % tid)
                                                         #(conj (disj % tid) new-id)))
                                               (update :rev inc))
                                     child? (-> (update :child-ids
                                                        (if done?
                                                          #(vec (remove #{tid} %))
                                                          #(vec (replace {tid new-id} %))))
                                                (update :rev inc)))]
                            (if (or wait? child?)
                              [(assoc-in b [:tasks wid] w')
                               (conj events {:type :dependency-replaced
                                             :task-id wid
                                             :old-id tid
                                             :new-id new-id
                                             :removed? done?})]
                              [b events])))
                        [b []]
                        (:tasks b))
                ;; Done children of the replaced leaf become independent.
                b (reduce (fn [b cid]
                            (if (contains? (:tasks b) cid)
                              (update-in b [:tasks cid]
                                         #(-> % (assoc :parent-id nil) (update :rev inc)))
                              b))
                          b
                          (:child-ids t))]
            (ok b
                (conj events
                      {:type :task-replaced
                       :event :replaced
                       :old-id tid :new-id new-id
                       :agent-id actor-id :reason reason})
                {:op :replace :old-task-id tid :new-task-id new-id}))))))

(defn- dispatch-reopen
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            reason (:reason cmd)
            ledger-key (:ledger-key t)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (not= :done (:state t)) (fail :TASK_NOT_DONE {:task-id tid})
          (not (nonblank? reason)) (fail :REASON_REQUIRED {:task-id tid})
          (and ledger-key (contains? (:ledger b) ledger-key))
          (fail :LEDGER_KEY_IN_USE
                {:task-id tid :ledger-key ledger-key})
          :else
          (let [owner (:owner-id t)
                live-owner (when (and owner (board/alive? b owner)) owner)
                b (-> b
                      (update-in [:tasks tid]
                                 #(-> %
                                      (assoc :state :ready)
                                      (assoc :claim-id nil)
                                      (assoc :wait-for #{})
                                      (assoc :owner-id live-owner)
                                      (assoc :done-at-version nil)
                                      (update :rev inc)))
                      (cond-> ledger-key
                        (assoc-in [:ledger ledger-key]
                                  {:task-id tid :state :open
                                   :created-version (inc (:board-version b))})))]
            (ok b
                [{:type :task-reopened :task-id tid
                  :agent-id (:actor-id cmd) :reason reason}]
                {:op :reopen :task-id tid}))))))

(defn- dispatch-comment
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            text (:text cmd)]
        (cond
          (not (contains? (:tasks b) tid)) (fail :UNKNOWN_TASK {:task-id tid})
          (not (nonblank? text)) (fail :COMMENT_REQUIRED {:task-id tid})
          :else
          (let [comment {:agent-id (:actor-id cmd)
                         :text text
                         :at-version (inc (:board-version b))}
                b (update-in b [:comments tid] (fnil conj []) comment)]
            (ok b
                [{:type :comment-added :task-id tid
                  :agent-id (:actor-id cmd)}]
                {:op :comment :task-id tid
                 :comment-count (count (get-in b [:comments tid]))}))))))

(defn- may-maintain?
  [b actor-id t]
  (if (:needs-token t)
    (board/holder? b actor-id)
    (or (= actor-id (:owner-id t))
        (nil? (:owner-id t)))))

(defn- dispatch-update
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            actor-id (:actor-id cmd)
            allowed #{:remaining-work :wait-for :done-when :needs-token
                      :owner-id :handoff-note}
            provided (set (keys (dissoc cmd :op :actor-id :task-id
                                       :request-id :expected-cond :expected)))
            illegal (set/difference provided allowed)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (= :done (:state t)) (fail :TASK_DONE {:task-id tid})
          (seq illegal) (fail :IMMUTABLE_FIELD {:fields illegal})
          (empty? provided) (fail :NO_UPDATES {:task-id tid})
          (not (may-maintain? b actor-id t))
          (fail :NOT_RESPONSIBLE {:task-id tid :owner-id (:owner-id t)})
          (expected-mismatch b t cmd)
          (fail :EXPECTED_CONDITION_FAILED (expected-mismatch b t cmd))
          :else
          (let [owner (:owner-id cmd)
                needs-token (:needs-token cmd)
                wait-for (:wait-for cmd)
                final-needs-token (if (contains? provided :needs-token)
                                    (boolean needs-token)
                                    (:needs-token t))
                removed-wait (when (and (contains? provided :wait-for)
                                        (set? wait-for))
                               (set/difference (:wait-for t) wait-for))
                orphan-children (when removed-wait
                                  (set (filter #(and (contains? (set (:child-ids t)) %)
                                                     (not= :done
                                                           (get-in b [:tasks % :state])))
                                               removed-wait)))
                changes (cond-> {}
                          (contains? provided :remaining-work)
                          (assoc :remaining-work (:remaining-work cmd))
                          (contains? provided :done-when)
                          (assoc :done-when (:done-when cmd))
                          (contains? provided :handoff-note)
                          (assoc :handoff-note (:handoff-note cmd)))]
            (cond
              (and (contains? provided :remaining-work)
                   (not (nonblank? (:remaining-work cmd))))
              (fail :INVALID_UPDATE {:field :remaining-work})

              (and (contains? provided :done-when)
                   (not (nonblank? (:done-when cmd))))
              (fail :INVALID_UPDATE {:field :done-when})

              (and (contains? provided :handoff-note)
                   (not (string? (:handoff-note cmd))))
              (fail :INVALID_UPDATE {:field :handoff-note})

              (and (contains? provided :owner-id)
                   owner
                   (not (board/alive? b owner)))
              (fail :UNKNOWN_ACTOR {:agent-id owner})

              (and (contains? provided :owner-id)
                   (:claim-id t))
              (fail :CLAIMED {:task-id tid :claim-id (:claim-id t)})

              (and (contains? provided :owner-id)
                   owner
                   (not= actor-id (:owner-id t))
                   (some? (:owner-id t)))
              (fail :NOT_RESPONSIBLE {:task-id tid :owner-id (:owner-id t)})

              (and (contains? provided :owner-id)
                   owner
                   (nil? (:owner-id t))
                   (not= actor-id owner))
              (fail :CANNOT_ASSIGN {:task-id tid :owner-id owner})

              (and (contains? provided :needs-token)
                   (not (boolean? needs-token)))
              (fail :INVALID_UPDATE {:field :needs-token
                                     :reason :not-a-boolean})

              (and (contains? provided :needs-token)
                   (:claim-id t))
              (fail :CLAIMED {:task-id tid :claim-id (:claim-id t)})

              (and (contains? provided :wait-for)
                   (:claim-id t))
              (fail :CLAIMED {:task-id tid :claim-id (:claim-id t)})

              (and (contains? provided :wait-for)
                   (not (set? wait-for)))
              (fail :INVALID_UPDATE {:field :wait-for :reason :not-a-set})

              (and (contains? provided :wait-for)
                   (contains? wait-for tid))
              (fail :DEPENDENCY_CYCLE {:task-id tid :reason :self-wait})

              (and (contains? provided :wait-for)
                   (some #(not (contains? (:tasks b) %)) wait-for))
              (fail :UNKNOWN_WAIT_TASK
                    {:wait-for (set (remove #(contains? (:tasks b) %) wait-for))})

              (and (contains? provided :wait-for)
                   (some #(= :done (get-in b [:tasks % :state])) wait-for))
              (fail :WAIT_TASK_DONE {:wait-for wait-for})

              (and (contains? provided :wait-for)
                   (seq orphan-children))
              (fail :ORPHAN_DEPENDENCY
                    {:task-id tid
                     :child-ids (vec (sort orphan-children))})

              (and (contains? provided :wait-for)
                   (some #(board/reaches? b % tid) wait-for))
              (fail :DEPENDENCY_CYCLE {:task-id tid :wait-for wait-for})

              :else
              (let [changes (cond-> changes
                              (contains? provided :owner-id)
                              (assoc :owner-id owner)
                              (contains? provided :needs-token)
                              (assoc :needs-token final-needs-token)
                              (and (contains? provided :needs-token)
                                   final-needs-token)
                              (assoc :owner-id nil)
                              (and (contains? provided :needs-token)
                                   (not final-needs-token)
                                   (not (contains? provided :owner-id))
                                   (:owner-id t)
                                   (not (board/alive? b (:owner-id t))))
                              (assoc :owner-id nil)
                              (contains? provided :wait-for)
                              (assoc :wait-for (set wait-for)))
                    new-wait (if (contains? provided :wait-for)
                               (set wait-for)
                               (:wait-for t))
                    new-state (cond
                                (contains? provided :wait-for)
                                (if (seq new-wait) :waiting :ready)
                                :else (:state t))
                    changes (assoc changes :state new-state)
                    b (update-in b [:tasks tid]
                                 #(-> % (merge changes) (update :rev inc)))]
                (ok b
                    [{:type :task-updated :task-id tid
                      :agent-id actor-id :fields (vec (sort provided))}]
                    {:op :update :task-id tid
                     :fields (vec (sort provided))
                     :state new-state}))))))))

;; ---------------------------------------------------------------------------
;; token competition

(defn- dispatch-take-or-die
  [b cmd]
  (or (require-actor b cmd)
      (let [actor-id (:actor-id cmd)
            confirm (:confirm cmd)]
        (cond
          (not= board/token-confirm confirm)
          (fail :CONFIRMATION_REQUIRED
                {:expected board/token-confirm :actual confirm})

          (board/holder? b actor-id)
          (fail :ALREADY_HOLDER {:agent-id actor-id})

          (seq (filter #(contains? board/active-action-phases (:phase %))
                       (actions-for-actor b actor-id)))
          (fail :ACTION_IN_FLIGHT
                {:agent-id actor-id
                 :action-ids (mapv :action-id
                                   (filter #(contains? board/active-action-phases
                                                        (:phase %))
                                           (actions-for-actor b actor-id)))})

          (some? (get-in b [:token-slot :holder-id]))
          (let [holder-id (get-in b [:token-slot :holder-id])
                claim-ids (for [[cid c] (:claims b)
                                :when (= actor-id (:agent-id c))]
                            cid)
                claimed-task-ids (set (map #(get-in b [:claims % :task-id])
                                           claim-ids))
                owned-task-ids (into #{}
                                     (for [[tid t] (:tasks b)
                                           :when (and (board/unfinished? t)
                                                      (= actor-id (:owner-id t)))]
                                       tid))
                published-task-ids (into #{}
                                         (filter #(not (:needs-token
                                                        (get-in b [:tasks %]))))
                                         owned-task-ids)
                action-ids (for [a (actions-for-actor b actor-id)
                                 :when (not= :finished (:phase a))]
                             (:action-id a))
                version (inc (:board-version b))
                b (-> b
                      (update :waiters dissoc actor-id)
                      (update :claims #(apply dissoc % claim-ids))
                      (update :tasks
                              (fn [tasks]
                                (reduce (fn [tasks tid]
                                          (if (contains? tasks tid)
                                            (update-in tasks [tid]
                                                       #(-> %
                                                            (assoc :claim-id nil)
                                                            (cond-> (contains? owned-task-ids tid)
                                                              (assoc :owner-id nil))
                                                            (update :rev inc)))
                                            tasks))
                                        tasks
                                        (into claimed-task-ids owned-task-ids))))
                      (update :actions #(apply dissoc % action-ids))
                      (update-in [:actors actor-id]
                                 #(-> %
                                      (assoc :role :normal)
                                      (assoc :life-state :dead)
                                      (assoc :active-action-id nil)
                                      (assoc :dead-at-version version))))]
            (ok b
                [{:type :attempt-lost
                  :agent-id actor-id
                  :holder-id holder-id
                  :released-claim-ids (vec claim-ids)
                  :published-task-ids (vec (sort published-task-ids))}]
                {:op :take-or-die
                 :outcome :attempt-lost
                 :agent-id actor-id
                 :holder-id holder-id
                 :released-claim-ids (vec claim-ids)
                 :published-task-ids (vec (sort published-task-ids))}))

          ;; A holder with an empty R(a) is retired in the same finalize
          ;; transition.  Competing for a free token with no ordinary
          ;; responsibility and no token task therefore wins and immediately
          ;; dies.  Refuse the request before changing the board; the operator
          ;; must create a `:needs-token true` task first.  An actor that
          ;; already owns an unfinished ordinary task keeps that responsibility
          ;; after winning, so the competition is legal.
          (and (empty? (board/responsibility b actor-id))
               (not (some #(and (board/unfinished? %) (:needs-token %))
                          (vals (:tasks b)))))
          (fail :TOKEN_TASK_REQUIRED
                {:agent-id actor-id
                 :hint "create a :needs-token true task before competing for the token"})

          :else
          (let [term-id (inc (get-in b [:token-slot :term-id] 0))
                b (-> b
                      (assoc-in [:token-slot :holder-id] actor-id)
                      (assoc-in [:token-slot :term-id] term-id)
                      (assoc-in [:token-slot :state] :held)
                      (assoc-in [:actors actor-id :role] :holder)
                      (update :waiters dissoc actor-id))]
            (ok b
                [{:type :attempt-won :agent-id actor-id :term-id term-id}]
                {:op :take-or-die :outcome :attempt-won
                 :agent-id actor-id :term-id term-id}))))))

;; ---------------------------------------------------------------------------
;; park / wake / user input

(defn- dispatch-park-slot
  [b cmd]
  (or (require-actor b cmd)
      (let [actor-id (:actor-id cmd)
            cursor (:observed-cursor cmd)]
        (cond
          (not (integer? cursor))
          (fail :CURSOR_REQUIRED {:agent-id actor-id})

          (not= cursor (:board-version b))
          (fail :STALE_CURSOR {:agent-id actor-id
                               :observed-cursor cursor
                               :board-version (:board-version b)})

          (contains? (:waiters b) actor-id)
          (fail :ALREADY_PARKED {:agent-id actor-id})

          (get-in b [:actors actor-id :active-action-id])
          (fail :ACTION_IN_FLIGHT
                {:agent-id actor-id
                 :action-id (get-in b [:actors actor-id :active-action-id])})

          (board/global-progressable? b)
          (fail :PROGRESS_AVAILABLE
                {:agent-id actor-id
                 :reasons (board/progressable-reasons b)
                 :ready-tasks (vec (sort (get-in b [:derived :ready-tasks] #{})))
                 :unhandled-inputs (vec (sort (for [[id i] (:inputs b)
                                                     :when (= :unhandled (:state i))]
                                                 id)))
                 :summary "global non-blocking work exists; active sleep is forbidden"})

          :else
          (let [b (assoc-in b [:waiters actor-id]
                            {:cursor cursor
                             :registered-version (:board-version b)})]
            (ok b
                [{:type :slot-parked :agent-id actor-id :cursor cursor}]
                {:op :park-slot :agent-id actor-id :parked? true
                 :cursor cursor}))))))

(defn- dispatch-wake-all
  [b cmd]
  ;; Waiters are cleared by finalize so the woken set is captured in one
  ;; place and the event always carries the same commit version.
  (ok b []
      {:op :wake-all
       :wake {:reasons #{:explicit}}})) 

(defn- dispatch-user-input
  [b cmd]
  (let [text (:text cmd)
        attachments (:attachments cmd [])]
    (cond
      (not (nonblank? text))
      (fail :INPUT_REQUIRED)

      (not (or (nil? attachments) (sequential? attachments)))
      (fail :INVALID_ATTACHMENT
            {:reason "attachments must be sequential"})

      (some #(when-not (valid-input-attachment? %) %) attachments)
      (fail :INVALID_ATTACHMENT
            {:attachment (some #(when-not (valid-input-attachment? %) %)
                               attachments)})

      :else
      (let [[b id] (board/alloc-id b :input :input)
            message-id (:message-id cmd)
            attachments (normalize-input-attachments (vec (or attachments [])))
            input (cond-> {:input-id id
                           :text text
                           :state :unhandled
                           :received-version (inc (:board-version b))
                           :actor-id (:actor-id cmd)
                           :resolution nil
                           :message-id (when (and (string? message-id)
                                                  (not (str/blank? message-id)))
                                         message-id)}
                    (seq attachments) (assoc :attachments attachments))]
        (ok (assoc-in b [:inputs id] input)
            [{:type :user-input :input-id id
              :actor-id (:actor-id cmd)}]
            {:op :user-input :input-id id
             :wake {:reasons #{:user-input}}})))))

(defn- dispatch-resolve-input
  [b cmd]
  (or (require-actor b cmd)
      (let [iid (:input-id cmd)
            input (get-in b [:inputs iid])
            task-id (:task-id cmd)
            summary (:summary cmd)]
        (cond
          (nil? input) (fail :UNKNOWN_INPUT {:input-id iid})
          (= :handled (:state input))
          (ok b [] {:op :resolve-input :input-id iid :already-handled? true})
          (and (nil? task-id) (not (nonblank? summary)))
          (fail :INTERPRETATION_REQUIRED
                {:input-id iid
                 :reason "resolution must name a task or a concrete summary"})
          (and task-id (not (contains? (:tasks b) task-id)))
          (fail :UNKNOWN_TASK {:task-id task-id})
          :else
          (let [resolution {:task-id task-id :summary summary
                            :at-version (inc (:board-version b))
                            :actor-id (:actor-id cmd)}
                b (-> b
                      (assoc-in [:inputs iid :state] :handled)
                      (assoc-in [:inputs iid :resolution] resolution))]
            (ok b
                [{:type :input-resolved :input-id iid
                  :task-id task-id :actor-id (:actor-id cmd)}]
                {:op :resolve-input :input-id iid :resolution resolution}))))))

;; ---------------------------------------------------------------------------
;; stub actions (phase one: no file or process side effects)

(defn- dispatch-begin-action
  [b cmd]
  (or (require-actor b cmd)
      (let [tid (:task-id cmd)
            t (get-in b [:tasks tid])
            cid (:claim-id cmd)
            actor-id (:actor-id cmd)
            kind (:kind cmd)]
        (cond
          (nil? t) (fail :UNKNOWN_TASK {:task-id tid})
          (= :done (:state t)) (fail :TASK_DONE {:task-id tid})
          (not (contains? #{:board :edit :shell} kind))
          (fail :INVALID_ACTION_KIND {:kind kind})
          (not= cid (:claim-id t))
          (fail :CLAIM_CHANGED {:task-id tid :claim-id cid
                                :current-claim-id (:claim-id t)})
          (not= actor-id (get-in b [:claims cid :agent-id]))
          (fail :CLAIM_CHANGED {:task-id tid :claim-id cid})
          (get-in b [:actors actor-id :active-action-id])
          (fail :ACTION_IN_FLIGHT
                {:agent-id actor-id
                 :action-id (get-in b [:actors actor-id :active-action-id])})
          (action-for-claim b cid)
          (fail :ACTION_IN_FLIGHT {:claim-id cid})
          (and (= kind :shell) (not (board/holder? b actor-id)))
          (fail :TOKEN_REQUIRED {:agent-id actor-id :kind :shell})
          (and (= kind :shell) (not (:needs-token t)))
          (fail :TOKEN_REQUIRED
                {:agent-id actor-id :task-id tid :kind :shell
                 :reason :task-does-not-need-token})
          :else
          (let [[b aid] (board/alloc-id b :action :action)
                action {:action-id aid
                        :task-id tid
                        :agent-id actor-id
                        :claim-id cid
                        :request-id (:request-id cmd)
                        :kind kind
                        :phase :claimed
                        :result-ref nil
                        :created-version (inc (:board-version b))
                        :updated-version (inc (:board-version b))}]
            (ok (-> b
                    (assoc-in [:actions aid] action)
                    (assoc-in [:actors actor-id :active-action-id] aid))
                [{:type :action-begun :action-id aid :task-id tid
                  :agent-id actor-id :claim-id cid :kind kind}]
                {:op :begin-action :action-id aid :task-id tid
                 :claim-id cid :phase :claimed}))))))

(def ^:private action-transitions
  {:claimed #{:preparing :waiting-gate :applying :running}
   :preparing #{:waiting-gate :applying :running}
   :waiting-gate #{:applying :running}
   :applying #{:finished :uncertain}
   :running #{:finished :uncertain}
   :uncertain #{:finished}
   :finished #{}})

(defn- dispatch-advance-action
  [b cmd]
  (or (require-actor b cmd)
      (let [aid (:action-id cmd)
            a (get-in b [:actions aid])
            actor-id (:actor-id cmd)
            target (:phase cmd)]
        (cond
          (nil? a) (fail :UNKNOWN_ACTION {:action-id aid})
          (not= actor-id (:agent-id a))
          (fail :NOT_ACTION_OWNER {:action-id aid
                                   :agent-id (:agent-id a)})
          (not (contains? board/action-phases target))
          (fail :INVALID_ACTION_PHASE {:phase target})
          (not (contains? (get action-transitions (:phase a) #{}) target))
          (fail :INVALID_ACTION_TRANSITION
                {:action-id aid :from (:phase a) :to target})
          (and (contains? #{:applying :running} target)
               (or (not (contains? (:tasks b) (:task-id a)))
                   (not= (:claim-id a)
                         (get-in b [:tasks (:task-id a) :claim-id]))))
          (fail :CLAIM_CHANGED {:action-id aid :task-id (:task-id a)})
          :else
          (let [finished? (= :finished target)
                b (-> b
                      (assoc-in [:actions aid :phase] target)
                      (assoc-in [:actions aid :updated-version]
                                (inc (:board-version b)))
                      (cond-> (:result-ref cmd)
                        (assoc-in [:actions aid :result-ref] (:result-ref cmd)))
                      (cond-> finished?
                        (assoc-in [:actors actor-id :active-action-id] nil)))]
            (ok b
                [{:type :action-advanced :action-id aid
                  :task-id (:task-id a) :agent-id actor-id
                  :from (:phase a) :to target}]
                {:op :advance-action :action-id aid
                 :from (:phase a) :to target
                 :finished? finished?}))))))

;; ---------------------------------------------------------------------------
;; recovery acknowledgement

(def ^:private recovery-pending-phases
  "Any action that has not finished still owns a possible side effect or a
  pending qualification.  Recovery acknowledgement is legal only after every
  one of them has been resolved explicitly."
  #{:claimed :preparing :waiting-gate :applying :running :uncertain})

(defn- dispatch-acknowledge-recovery
  [b cmd]
  (or (require-actor b cmd)
      (let [evidence (:evidence cmd)
            pending (->> (:actions b)
                         (keep (fn [[aid action]]
                                 (when (contains? recovery-pending-phases
                                                  (:phase action))
                                   {:action-id aid
                                    :phase (:phase action)
                                    :task-id (:task-id action)
                                    :agent-id (:agent-id action)})))
                         (sort-by (comp str :action-id))
                         vec)
            previous (get-in b [:recovery :acknowledgement])
            version (inc (:board-version b))
            ack {:acknowledged? true
                 :actor-id (:actor-id cmd)
                 :evidence evidence
                 :write-gate-held? (boolean (:write-gate-held? cmd))
                 :at-version version}]
        (cond
          (not (and (string? evidence) (not (str/blank? evidence))))
          (fail :RECOVERY_EVIDENCE_REQUIRED
                {:reason "acknowledge-recovery requires non-blank :evidence"})

          (seq pending)
          (fail :RECOVERY_ACTIONS_PENDING
                {:reason "resolve every active action before acknowledging recovery"
                 :actions pending})

          :else
          (ok (assoc-in b [:recovery :acknowledgement] ack)
              [{:type :recovery-acknowledged
                :actor-id (:actor-id cmd)
                :evidence evidence
                :write-gate-held? (:write-gate-held? ack)
                :previous-acknowledgement? (boolean previous)}]
              {:op :acknowledge-recovery
               :acknowledged? true
               :already-acknowledged? (boolean previous)
               :evidence evidence
               :write-gate-held? (:write-gate-held? ack)})))))

;; ---------------------------------------------------------------------------
;; persistence checkpoint

(defn- dispatch-checkpoint
  "Emit one committed event without changing authority.  A publication retry
  uses it to force the already-committed board value through the durable
  event hook; a checkpoint that cannot be persisted fails like any other
  command and must be retried."
  [b cmd]
  (or (require-actor b cmd)
      (ok b [{:type :board-checkpoint
              :actor-id (:actor-id cmd)
              :reason (:reason cmd)}]
          {:op :checkpoint :checked? true})))

;; ---------------------------------------------------------------------------
;; batch

(declare dispatch)

(defn- dispatch-batch
  [b cmd env]
  (let [commands (:commands cmd)]
    (if-not (and (sequential? commands) (seq commands))
      (fail :EMPTY_BATCH)
      (loop [b b
             events []
             results []
             wake-reasons #{}
             index 0
             [c & more] (seq commands)]
        (if c
          (cond
            (not (map? c))
            (fail :INVALID_BATCH_COMMAND {:index index})

            (= :batch (:op c))
            (fail :NESTED_BATCH {:index index})

            (contains? c :request-id)
            (fail :REQUEST_ID_NOT_ALLOWED_IN_BATCH {:index index})

            :else
            (let [out (dispatch b c env)]
              (if (:ok? out)
                (recur (:board out)
                       (into events (:events out))
                       (conj results (:result out))
                       (into wake-reasons
                             (get-in out [:result :wake :reasons]))
                       (inc index)
                       more)
                (assoc out :batch-index index
                           :batch-results results))))
          (ok b events
              (cond-> {:op :batch :results results}
                (seq wake-reasons)
                (assoc :wake {:reasons wake-reasons}))))))))

;; ---------------------------------------------------------------------------
;; dispatch / execute / finalize

(defn- dispatch
  [b cmd env]
  (case (:op cmd)
    :register-actor (dispatch-register-actor b cmd)
    :create-task (dispatch-create-task b cmd)
    :split (dispatch-split b cmd)
    :claim (dispatch-claim b cmd)
    :release (dispatch-release b cmd)
    :finish (dispatch-finish b cmd env)
    :cancel (dispatch-cancel b cmd)
    :replace (dispatch-replace b cmd)
    :reopen (dispatch-reopen b cmd)
    :comment (dispatch-comment b cmd)
    :update (dispatch-update b cmd)
    :take-or-die (dispatch-take-or-die b cmd)
    :park-slot (dispatch-park-slot b cmd)
    :wake-all (dispatch-wake-all b cmd)
    :user-input (dispatch-user-input b cmd)
    :resolve-input (dispatch-resolve-input b cmd)
    :begin-action (dispatch-begin-action b cmd)
    :advance-action (dispatch-advance-action b cmd)
    :acknowledge-recovery (dispatch-acknowledge-recovery b cmd)
    :checkpoint (dispatch-checkpoint b cmd)
    :batch (dispatch-batch b cmd env)
    (fail :UNKNOWN_OP {:op (:op cmd)})))

(defn- maybe-retire-holder
  "If the holder's complete R(a) is empty, release the token, revoke the
  identity and publish the death in the same immutable board value."
  [b]
  (let [holder (get-in b [:token-slot :holder-id])]
    (if (and holder
             (= :alive (get-in b [:actors holder :life-state]))
             (empty? (board/responsibility b holder)))
      (let [version (inc (:board-version b))
            claim-ids (vec (sort (for [[cid c] (:claims b)
                                       :when (= holder (:agent-id c))]
                                   cid)))
            action-ids (vec (sort (for [[aid a] (:actions b)
                                        :when (and (= holder (:agent-id a))
                                                   (not= :finished (:phase a)))]
                                    aid)))
            b (-> b
                  (remove-actions action-ids)
                  (as-> b (reduce release-claim b claim-ids))
                  (assoc-in [:token-slot :holder-id] nil)
                  (assoc-in [:token-slot :term-id]
                            (inc (get-in b [:token-slot :term-id] 0)))
                  (assoc-in [:token-slot :state] :free)
                  (update :waiters dissoc holder)
                  (update-in [:actors holder]
                             #(-> %
                                  (assoc :role :normal)
                                  (assoc :life-state :dead)
                                  (assoc :active-action-id nil)
                                  (assoc :dead-at-version version))))]
        [b [{:type :holder-retired
             :agent-id holder
             :responsibility #{}
             :released-claim-ids claim-ids
             :removed-action-ids action-ids}]])
      [b []])))

(defn- wake-all
  [b reasons]
  (let [woken (set (keys (:waiters b)))]
    [(-> b
         (assoc :waiters {})
         (assoc :last-wake {:reasons (set reasons)
                            :woken woken
                            :at-version (inc (:board-version b))}))
     {:type :wake-all
      :reasons (set reasons)
      :woken woken}]))

(defn- commit-events
  [b events]
  (loop [b b
         [ev & more] (seq events)
         out []]
    (if ev
      (let [n (inc (:event-seq b))
            ev' (assoc ev :event-seq n :at-version (:board-version b))]
        (recur (-> b
                   (assoc :event-seq n)
                   (update :events conj ev'))
               more
               (conj out ev')))
      [b out])))

(defn- finalize
  [old-board raw-board events result cmd env]
  (let [b1 (board/derive raw-board)
        [b2 retire-events] (maybe-retire-holder b1)
        b3 (board/derive b2)
        old-progress? (boolean (get-in old-board [:derived :global-progressable?]))
        new-progress? (boolean (get-in b3 [:derived :global-progressable?]))
        explicit-reasons (set (get-in result [:wake :reasons]))
        reasons (cond-> explicit-reasons
                  (and (not old-progress?) new-progress?)
                  (conj :progress-resumed))
        [b4 wake-event] (if (seq reasons)
                          (wake-all b3 reasons)
                          [b3 nil])
        b4 (board/derive b4)
        b5 (update b4 :board-version inc)
        all-events (concat events retire-events (when wake-event [wake-event]))
        [b6 committed-events] (commit-events b5 all-events)
        retired? (boolean (some #(= :holder-retired (:type %)) retire-events))
        retired-agent-id (some #(when (= :holder-retired (:type %)) (:agent-id %))
                               retire-events)
        result' (-> result
                    (dissoc :wake)
                    (assoc :ok? true
                           :board-version (:board-version b6)
                           :events (vec committed-events)
                           :retired? retired?
                           :retired-agent-id retired-agent-id
                           :woken (or (:woken wake-event) #{})))
        b7 (if-let [rid (:request-id cmd)]
             (assoc-in b6 [:requests rid]
                       {:command (dissoc cmd :request-id)
                        :result result'
                        ;; The kernel marks this true only after the event
                        ;; hook confirms publication.  A replay of an
                        ;; unpersisted request must not claim success.
                        :persisted? (empty? all-events)})
             b6)]
    {:ok? true :board b7 :events (vec committed-events) :result result'}))

(defn- failure-result
  [out board]
  {:ok? false
   :result (cond-> {:ok? false
                    :error (:error out)
                    :board-version (:board-version board)}
             (:detail out) (assoc :detail (:detail out))
             (:batch-index out) (assoc :batch-index (:batch-index out)
                                       :batch-results (:batch-results out)))})

(defn execute
  "Apply one command to BOARD.  Pure: returns
  {:ok? true :board board' :events [...] :result {...}} or
  {:ok? false :result {:ok? false :error ... :detail ...}}.

  ENV may contain :verify-read-set, a pure function of the finish read-set.
  A non-empty read-set additionally requires :under-write-gate? true and an
  explicit verifier; a non-empty verified-read-set requires
  :under-write-gate? true and is recorded as the caller's verified basis.
  Without the W proof finish rejects
  :WRITE_GATE_REQUIRED; with W but without a verifier it rejects
  :READ_SET_VERIFICATION_REQUIRED.  The kernel's execute-under-write-gate!
  sets this proof only while it holds the injected W port; direct callers of
  this pure function must not set it unless they really hold W.  workspace_gate
  supplies the real stamp check."
  ([board cmd]
   (execute board cmd {}))
  ([board cmd env]
   (let [cmd (if (map? cmd) cmd {:op cmd})
         rid (:request-id cmd)
         dispatch-cmd
         (if (and (= :split (:op cmd))
                  (nil? (:expected-cond cmd))
                  (nil? (:expected cmd)))
           (assoc cmd :expected-cond
                  {:rev (get-in (or (:observed-board env) board)
                                [:tasks (:task-id cmd) :rev])})
           cmd)]
     (if rid
       (if-let [prior (get-in board [:requests rid])]
         (if (= (:command prior) (dissoc cmd :request-id))
           (if (false? (:persisted? prior))
             {:ok? false
              :result {:ok? false
                       :error :BOARD_EVENT_PERSIST_FAILED
                       :persisted? false
                       :board-version (:board-version board)
                       :detail {:request-id rid
                                :requires-checkpoint? true}}}
             {:ok? true
              :board board
              :events []
              :result (:result prior)})
           {:ok? false
            :result {:ok? false
                     :error :REQUEST_ID_CONFLICT
                     :board-version (:board-version board)
                     :detail {:request-id rid}}})
         (let [out (dispatch board dispatch-cmd env)]
           (if (:ok? out)
             (finalize board (:board out) (:events out) (:result out) cmd env)
             (failure-result out board))))
       (let [out (dispatch board dispatch-cmd env)]
         (if (:ok? out)
           (finalize board (:board out) (:events out) (:result out) cmd env)
           (failure-result out board)))))))
