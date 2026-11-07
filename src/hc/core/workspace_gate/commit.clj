(ns hc.core.workspace-gate.commit
  "Protected commit orchestration: wait for W, re-check the read basis,
  complete the dry-run, fix the applying qualification, install the plan,
  then publish the result as a pure control update.

  The board adapter is deliberately a protocol.  A production adapter must
  perform `begin-apply!` and `publish-applied!` inside its own STM
  transaction; the in-memory reference lives in
  `hc.core.workspace-gate.board`."
  (:require [clojure.string :as str]
            [hc.core.workspace-gate.applier :as applier]
            [hc.core.workspace-gate.board :as board]
            [hc.core.workspace-gate.error :as err]
            [hc.core.workspace-gate.gate :as gate]
            [hc.core.workspace-gate.prepare :as prepare]
            [hc.core.workspace-gate.stamp :as stamp])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat)))

(set! *warn-on-reflection* true)

(def ^:private byte-array-class (Class/forName "[B"))

;; ---------------------------------------------------------------------------
;; Context

(defrecord WorkspaceGate [root gate board limits publish-retries apply-fn
                          registry stops transition-lock allow-boardless?])

(defn workspace-gate
  "Compose the file contract for one workspace.

  `:gate` may be supplied to share W with the managed shell session layer.
  `:board` defaults to the reference in-memory adapter.  An explicit
  `:board nil` is a test-only seam and requires `:allow-boardless? true`;
  without that flag the constructor refuses to create a gate that could
  apply files without an eligibility check.  `:apply-fn` is an injection
  point for tests."
  [opts]
  (let [{:keys [root gate board limits publish-retries apply-fn registry stops
                transition-lock allow-boardless?]
         :as opts} (if (map? opts) opts {:root opts})]
   (when-not root
     (throw (IllegalArgumentException. "workspace-gate requires :root")))
   (when (and (contains? opts :board)
              (nil? board)
              (not allow-boardless?))
     (throw (IllegalArgumentException.
             "workspace-gate :board nil is test-only; pass :allow-boardless? true")))
   (->WorkspaceGate (stamp/root-path root)
                    (or gate (gate/write-gate))
                    (if (contains? opts :board) board (board/memory-board))
                    (merge prepare/default-limits limits)
                    (or publish-retries 5)
                    (or apply-fn applier/apply-plan!)
                    (or registry (atom {}))
                    (or stops (atom #{}))
                    (or transition-lock (Object.))
                    (boolean allow-boardless?))))

(defn request-stop!
  "Register an explicit stop for a request waiting for W.  A request already
  past `begin-apply!` continues its current minimal batch to completion.
  Returns `:STOP_AFTER_APPLYING` when the batch has already fixed its
  applying qualification; the batch still finishes and reports
  `:cancel-after-apply? true`."
  [wg request-id]
  (locking (:transition-lock wg)
    (let [entry (get @(:registry wg) request-id)]
      (cond
        (= :finished (:status entry))
        {:status :STOP_ALREADY_FINISHED :request-id request-id}

        (= :applying (:phase entry))
        (do
          (swap! (:stops wg) conj request-id)
          {:status :STOP_AFTER_APPLYING
           :request-id request-id
           :cancel-after-apply? true})

        :else
        (do
          (swap! (:stops wg) conj request-id)
          {:status :STOP_REGISTERED :request-id request-id})))))

(defn clear-stop!
  [wg request-id]
  (swap! (:stops wg) disj request-id)
  {:status :STOP_CLEARED :request-id request-id})

;; ---------------------------------------------------------------------------
;; Request identity

(defn- canonical [x]
  (cond
    (map? x)
    (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map (fn [[k v]] [k (canonical v)]))
          x)

    (vector? x) (mapv canonical x)

    (set? x)
    (into (sorted-set-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map canonical x))

    (instance? byte-array-class x)
    [:bytes (String. ^bytes x StandardCharsets/UTF_8)]

    :else x))

(defn- batch-fingerprint [batch]
  (when batch
    {:root (str (:root batch))
     :limits (:limits batch)
     :ops (mapv (fn [operation]
                  (if-not (map? operation)
                    {:op :invalid :value (pr-str operation)}
                    (let [{:keys [path source target overwrite? bytes]} operation]
                      (cond-> (select-keys operation [:op])
                        path (assoc :path path)
                        source (assoc :source source)
                        target (assoc :target target)
                        (contains? operation :overwrite?) (assoc :overwrite? overwrite?)
                        (contains? operation :create?) (assoc :create? (:create? operation))
                        (instance? byte-array-class bytes)
                        (assoc :content (String. ^bytes bytes StandardCharsets/UTF_8))
                        (and (some? bytes) (not (instance? byte-array-class bytes)))
                        (assoc :invalid-bytes (pr-str bytes))))))
                (:ops batch))
     :read-set (:read-set batch)
     :checks (:checks batch)}))

(defn- sha256 [^String text]
  (let [digest (MessageDigest/getInstance "SHA-256")
        ^bytes bytes (.digest digest (.getBytes text StandardCharsets/UTF_8))
        ^HexFormat hex (HexFormat/of)]
    (.formatHex hex bytes)))

(defn- request-fingerprint [request]
  (sha256 (pr-str (canonical (-> request
                                 (dissoc :on-op :request-id :pre-apply!)
                                 (update :batch batch-fingerprint))))))

;; ---------------------------------------------------------------------------
;; Request registry (same request-id -> one apply)

(defn- begin-request! [registry request-id fingerprint]
  (let [p (promise)
        entry {:fingerprint fingerprint
               :status :pending
               :phase :accepted
               :promise p}]
    (loop []
      (let [[old new] (swap-vals! registry
                                  (fn [m]
                                    (if (contains? m request-id)
                                      m
                                      (assoc m request-id entry))))]
        (if (identical? old new)
          (let [existing (get old request-id)]
            (cond
              (not= fingerprint (:fingerprint existing))
              {:role :mismatch :entry existing}

              (= :pending (:status existing))
              {:role :waiter :promise (:promise existing)}

              :else
              {:role :cached :result (:result existing)}))
          {:role :owner :promise p})))))

(declare cancel-after-apply-result?)

(defn- finish-request!
  "Finalize a request under the same transition lock as `request-stop!`.

  A stop that wins before this lock is reflected in `:cancel-after-apply?`;
  a stop that arrives after the lock sees `:finished` and cannot claim that
  an applied batch was cancelled."
  [wg request-id p result]
  (locking (:transition-lock wg)
    (let [result (if (and (contains? @(:stops wg) request-id)
                          (cancel-after-apply-result? result))
                   (assoc result :cancel-after-apply? true)
                   result)
          phase (cond
                  (and (= :APPLIED (:status result))
                       (false? (:published? result))) :publish-pending
                  (= :APPLIED (:status result)) :finished
                  (= :HARD_FAILURE (:status result)) :uncertain
                  (and (= :WAITING_FOR_WRITE_GATE (:status result))
                       (:cancelled? result)) :cancelled
                  :else :rejected)]
      (swap! (:registry wg) update request-id assoc
             :status :finished :phase phase :result result)
      (deliver p result)
      result)))

(defn- cancel-after-apply-result?
  "True when the result describes an apply that a late stop must not undo."
  [result]
  (or (= :APPLIED (:status result))
      (and (= :HARD_FAILURE (:status result))
           (contains? #{:applying :uncertain} (:phase result)))))

(defn- mark-phase! [wg request-id phase]
  (swap! (:registry wg) update request-id assoc :phase phase))

(defn action
  "Query a request by id.  A missing request returns nil; a pending request
  reports `:pending`.  Never executes the original request."
  [wg request-id]
  (when-let [entry (get @(:registry wg) request-id)]
    {:request-id request-id
     :status (:status entry)
     :phase (:phase entry)
     :publish-pending? (boolean (:retry-data entry))
     :result (:result entry)}))

(declare publish-applied publish-hard-failure)

(defn retry-publish!
  "Retry only the board publication of an applied or hard-failure request.

  Never reapplies files.  The retry permit and result are kept in the
  process-local registry when the first publication exhausts its attempts.
  A hard-failure publication that succeeds moves the board action to
  `:uncertain`, not `:finished`."
  [wg request-id]
  (let [entry (get @(:registry wg) request-id)
        retry-data (:retry-data entry)]
    (cond
      (nil? entry)
      (err/failure :UNKNOWN_REQUEST :unknown-request
                   "No request with this id"
                   {:request-id request-id})

      (not= :finished (:status entry))
      (if (= :applying (:phase entry))
        {:status :ACTION_IN_PROGRESS
         :code :action-in-progress
         :outcome :in-progress
         :file-changed? :unknown
         :request-id request-id
         :phase (:phase entry)
         :message "The request is applying; publication can be retried after it finishes"}
        (err/failure :WAITING_FOR_WRITE_GATE :request-not-finished
                     "Request is still running; query it before retrying publication"
                     {:request-id request-id}))

      (nil? retry-data)
      (err/failure :UNSUPPORTED_OPERATION :nothing-to-retry
                   "Request has no pending board publication"
                   {:request-id request-id})

      :else
      (let [{:keys [permit result kind]} retry-data
            kind (or kind :applied)
            published (if (= :failure kind)
                        (publish-hard-failure wg permit result)
                        (publish-applied wg permit result))
            final-phase (if (= :failure kind) :uncertain :finished)]
        (swap! (:registry wg) update request-id
               (fn [e]
                 (cond-> (assoc e :result published)
                   (:published? published)
                   (-> (dissoc :retry-data) (assoc :phase final-phase))
                   (not (:published? published))
                   (assoc :retry-data {:kind kind :permit permit :result published}
                          :phase :publish-pending))))
        published))))
;; ---------------------------------------------------------------------------
;; Orchestration

(defn- hard-failure-result
  [t phase]
  {:status :HARD_FAILURE
   :outcome :unknown
   :file-changed? :unknown
   :phase phase
   :exception (err/throwable->data t)
   :message (.getMessage ^Throwable t)})

(defn- board-result-failure
  "Classify a board adapter return value.

  The protocol expects ordinary refusals to be thrown, but a non-throwing
  adapter must not be able to turn a refusal into a successful permit or a
  successful publication.  `nil` is the success value for the eligibility
  check."
  [result]
  (cond
    (and (map? result) (false? (:ok? result)))
    (err/sanitize
     (merge {:status :BOARD_EVENT_PERSIST_FAILED
             :code :board-event-persist-failed
             :outcome :not-written
             :file-changed? false
             :phase :applying}
            result
            {:status (or (:status result) :BOARD_EVENT_PERSIST_FAILED)}))

    (and (map? result) (contains? result :status))
    (err/sanitize
     (merge {:outcome :not-written
             :file-changed? false
             :phase :applying}
            result))

    (and (map? result) (false? (:published result)))
    (err/sanitize
     (merge {:status :BOARD_EVENT_PERSIST_FAILED
             :code :board-publication-failed
             :outcome :not-written
             :file-changed? false
             :phase :applying}
            result
            {:status (or (:status result) :BOARD_EVENT_PERSIST_FAILED)}))

    :else nil))

(defn- call-board [f]
  (try
    (let [result (f)]
      (if-let [failure (board-result-failure result)]
        {:failure failure}
        {:ok result}))
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected-failure? e)
        {:failure (err/sanitize (ex-data e))}
        {:failure (hard-failure-result e :board)}))
    (catch Throwable t
      {:failure (hard-failure-result t :board)})))

(defn- run-pre-apply!
  "Run an under-W pre-apply check before the dry-run/apply.

  The hook may reserve a board-side name or verify a target that must remain
  absent.  A refusal is an ordinary rejection; no file has been touched.
  The hook is not part of the request fingerprint."
  [f request]
  (try
    (let [result (f request)]
      (if (and (map? result)
               (or (false? (:ok? result))
                   (and (contains? result :status)
                        (not (contains? #{:OK :APPLIED} (:status result))))))
        {:failure (err/sanitize
                   (merge {:status (or (:status result) :PREPARE_REJECTED)
                           :outcome :not-written
                           :file-changed? false
                           :phase :final-check}
                          result))}
        {:ok true}))
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected-failure? e)
        {:failure (err/sanitize (assoc (ex-data e) :phase :final-check))}
        {:failure {:status :HARD_FAILURE
                   :outcome :unknown
                   :file-changed? false
                   :phase :final-check
                   :exception (err/throwable->data e)
                   :message (.getMessage ^Throwable e)}}))
    (catch Throwable t
      {:failure {:status :HARD_FAILURE
                 :outcome :unknown
                 :file-changed? false
                 :phase :final-check
                 :exception (err/throwable->data t)
                 :message (.getMessage ^Throwable t)}})))

(defn- publish-applied [wg permit applied]
  (if-not (:board wg)
    (assoc applied :published? true :publish-attempts 0)
    (loop [attempt 1]
      (let [{:keys [ok failure]} (call-board
                                  #(board/publish-applied! (:board wg) permit applied))]
        (cond
          ok
          (assoc applied :published? true :publish-attempts attempt)

          (< attempt (:publish-retries wg))
          (recur (inc attempt))

          :else
          (assoc applied
                 :published? false
                 :publish-attempts attempt
                 :publish-error failure
                 :retry :publish-only
                 :message "Files applied; board publication still pending"))))))

(defn- publish-hard-failure [wg permit applied]
  (if-not (:board wg)
    (assoc applied :published? true :publish-attempts 0 :phase :uncertain)
    (loop [attempt 1]
      (let [{:keys [ok failure]} (call-board
                                  #(board/publish-failure! (:board wg) permit applied))]
        (cond
          ok
          (assoc applied
                 :published? true
                 :publish-attempts attempt
                 :phase :uncertain)

          (< attempt (:publish-retries wg))
          (recur (inc attempt))

          :else
          (assoc applied
                 :published? false
                 :publish-attempts attempt
                 :publish-error failure
                 :retry :publish-only
                 :phase :applying
                 :message "Hard failure recorded; board publication still pending"))))))

(defn- begin-applying!
  [wg request prepared]
  (locking (:transition-lock wg)
    (if (contains? @(:stops wg) (:request-id request))
      {:stopped true}
      (let [result (if (:board wg)
                     (call-board
                      #(board/begin-apply! (:board wg) request
                                           (:summary prepared)))
                     {:ok nil})]
        (cond
          (:failure result)
          result

          (and (:board wg)
               (or (not (map? (:ok result)))
                   (not (some? (or (:action-id (:ok result))
                                   (:request-id (:ok result))
                                   (:board (:ok result))
                                   (:permit-id (:ok result)))))))
          {:failure (err/failure :PREPARE_REJECTED :invalid-board-permit
                                 "begin-apply! did not return a permit"
                                 {:request-id (:request-id request)
                                  :actual (some-> (:ok result) class str)})}

          :else
          (do
            (mark-phase! wg (:request-id request) :applying)
            result))))))

(defn- apply-and-publish!
  [wg request prepared begun]
  (let [request-id (:request-id request)
        board-permit (or (:ok begun)
                         {:action-id request-id
                          :request-id request-id})
        applied (try
                  ((:apply-fn wg) prepared
                   {:on-op (:on-op request)
                    :gate-token (:gate-token prepared)})
                  (catch clojure.lang.ExceptionInfo e
                    (if (err/expected-failure? e)
                      (err/sanitize (ex-data e))
                      (hard-failure-result e :applying)))
                  (catch Throwable t
                    (hard-failure-result t :applying)))
        applied (if (and (map? applied) (contains? applied :status))
                  applied
                  (hard-failure-result
                   (ex-info "apply-fn returned no classified result" {})
                   :applying))
        applied (err/sanitize applied)]
    (if (err/rejection? applied)
      applied
      (let [result (if (= :APPLIED (:status applied))
                     (publish-applied wg board-permit applied)
                     (publish-hard-failure wg board-permit applied))
            retry-kind (when (and (contains? #{:APPLIED :HARD_FAILURE}
                                             (:status result))
                                  (false? (:published? result)))
                         (if (= :HARD_FAILURE (:status result))
                           :failure
                           :applied))]
        (when retry-kind
          (swap! (:registry wg) update request-id
                 assoc :retry-data
                 {:kind retry-kind
                  :permit board-permit
                  :result result}))
        (if (contains? @(:stops wg) request-id)
          (assoc result :cancel-after-apply? true)
          result)))))

(defn- run-under-gate
  [wg request gate-permit]
  (let [board (:board wg)
        batch (:batch request)]
    (if-let [f (and board
                    (:failure (call-board #(board/check-eligibility! board request))))]
      (assoc f :phase :final-check)
      (let [pre (when-let [f (:pre-apply! request)]
                  (run-pre-apply! f request))]
        (if-let [failure (:failure pre)]
          failure
          (let [prepared (prepare/prepare-plan batch {:gate-token gate-permit})]
            (if-not (prepare/prepared? prepared)
              (assoc prepared :phase :preparing)
              (let [begun (begin-applying! wg request prepared)]
                (cond
                  (:stopped begun)
                  (err/failure :WAITING_FOR_WRITE_GATE :stopped-before-apply
                               "Request was stopped before the applying qualification"
                               {:request-id (:request-id request)
                                :cancelled? true :queued? false})

                  (:failure begun)
                  (assoc (:failure begun) :phase :final-check)

                  :else
                  (let [request-id (:request-id request)
                        board-permit (or (:ok begun)
                                         {:action-id request-id
                                          :request-id request-id})]
                    (try
                      (apply-and-publish! wg request prepared begun)
                      (catch Throwable t
                        (let [failure (hard-failure-result t :applying)
                              result (publish-hard-failure wg board-permit failure)]
                          (when (false? (:published? result))
                            (swap! (:registry wg) update request-id
                                   assoc :retry-data
                                   {:kind :failure
                                    :permit board-permit
                                    :result result}))
                          (if (contains? @(:stops wg) request-id)
                            (assoc result :cancel-after-apply? true)
                            result))))))))))))))

(defn- execute-commit! [wg request]
  (let [request-id (:request-id request)
        batch (:batch request)]
    (cond
      (nil? request-id)
      (err/failure :INVALID_ARGUMENT :missing-request-id
                   "A protected commit requires :request-id" {})

      (not (and (some? (:actor-id request))
                (not (str/blank? (str (:actor-id request))))))
      (err/failure :INVALID_ARGUMENT :missing-actor-id
                   "A protected commit requires a non-blank :actor-id"
                   {:actor-id (:actor-id request)})

      (nil? batch)
      (err/failure :INVALID_ARGUMENT :missing-batch
                   "A protected commit requires a batch snapshot" {})

      (not (and (map? batch)
                (contains? batch :root)
                (contains? batch :ops)
                (contains? batch :read-set)
                (contains? batch :checks)))
      (err/failure :INVALID_ARGUMENT :invalid-batch
                   "Commit requires a batch snapshot, not a Preview or Prepared value"
                   {:actual (some-> batch class str)})

      (not= (str (:root batch)) (str (:root wg)))
      (err/failure :STRUCTURE_CONFLICT :root-mismatch
                   "Batch was built for a different workspace root"
                   {:batch-root (str (:root batch))
                    :gate-root (str (:root wg))})

      (and (empty? (:ops batch))
           (empty? (:read-set batch))
           (empty? (:checks batch)))
      (err/failure :UNSUPPORTED_OPERATION :no-file-facts
                   "Nothing to verify or apply; use the board STM directly" {})

      (contains? @(:stops wg) request-id)
      (err/failure :WAITING_FOR_WRITE_GATE :stopped-before-commit
                   "Request was stopped before it could acquire W"
                   {:request-id request-id :cancelled? true :queued? false})

      :else
      (let [_ (mark-phase! wg request-id :waiting-gate)
            acquired (try
                       {:permit (gate/acquire!
                                 (:gate wg) request-id
                                 {:stop? #(contains? @(:stops wg) request-id)})}
                       (catch clojure.lang.ExceptionInfo e
                         (if (err/expected-failure? e)
                           {:failure (ex-data e)}
                           (throw e))))]
        (if-let [f (:failure acquired)]
          f
          (try
            (mark-phase! wg request-id :preparing)
            (if (contains? @(:stops wg) request-id)
              (err/failure :WAITING_FOR_WRITE_GATE :stopped-before-apply
                           "Request was stopped before applying"
                           {:request-id request-id :cancelled? true :queued? false})
              (run-under-gate wg request (:permit acquired)))
            (finally
              (gate/release! (:permit acquired)))))))))

(defn commit!
  "Run one protected commit.

  Returns a classified result.  Ordinary rejections are `:not-written`;
  `:APPLIED` means the plan was installed; `:HARD_FAILURE` means the site may
  have changed.  The same `:request-id` with the same payload replays the
  stored result without a second apply; a different payload returns
  `:CALL_REPLAY_MISMATCH`."
  [wg request]
  (let [request-id (:request-id request)]
    (if (nil? request-id)
      (err/failure :INVALID_ARGUMENT :missing-request-id
                   "A protected commit requires :request-id" {})
      (let [fingerprint (request-fingerprint request)
            {:keys [role promise result]} (begin-request! (:registry wg)
                                                          request-id
                                                          fingerprint)]
        (case role
          :mismatch
          (err/failure :CALL_REPLAY_MISMATCH :call-replay-mismatch
                       "The same request-id was reused with a different payload"
                       {:request-id request-id})

          :cached
          result

          :waiter
          @promise

          :owner
          (let [result (try
                         (execute-commit! wg request)
                         (catch Throwable t
                           (hard-failure-result t :commit)))]
            (let [result (assoc result :request-id request-id)
                  result (if (and (contains? @(:stops wg) request-id)
                                  (cancel-after-apply-result? result))
                           (assoc result :cancel-after-apply? true)
                           result)]
              (finish-request! wg request-id promise result))))))))
