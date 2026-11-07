(ns hc.compose.workspace.error
  "Map file-contract and swarm results to the executor error shape.

  The Owner needs one stable shape: `{:code :message :details :retryable?}`.
  Ordinary rejections keep `:retryable? false` unless the caller can retry
  the same decision without re-reading the site.  A hard failure is never
  retryable automatically.")

(def ^:private retryable-codes
  #{:STALE_CURSOR
    :WAITING_FOR_WRITE_GATE
    :WRITE_GATE_ACQUIRE_FAILED})

(def ^:private status-first-codes
  "File and board results whose `:status` is the stable result code.  Other
  bridge-generated results carry a more specific `:code`."
  #{:FILE_CONFLICT :STRUCTURE_CONFLICT :PREPARE_REJECTED :LIMIT_EXCEEDED
    :UNSUPPORTED_OPERATION :CLAIM_CHANGED :ACTOR_INACTIVE
    :WAITING_FOR_WRITE_GATE :CALL_REPLAY_MISMATCH :HARD_FAILURE
    :NO_FACTS :RECOVERY_UNRESOLVED :BOARD_EVENT_PERSIST_FAILED})

(defn retryable?
  [result]
  (and (contains? retryable-codes (:status result))
       (not (:cancelled? result))
       (not= :HARD_FAILURE (:status result))))

(defn error-data
  "Build the public error value from a classified result."
  [result]
  (when result
    (let [code (if (or (contains? status-first-codes (:status result))
                       (nil? (:code result)))
                 (or (:status result) (:error result) :INTERNAL)
                 (:code result))]
      {:code code
     :message (or (:message result)
                  (str "Workspace operation rejected: "
                       code))
     :details (-> result
                  (dissoc :status :code :message :outcome :file-changed?
                          :exception :retry :retryable? :cancelled? :queued?))
     :retryable? (boolean (retryable? result))})))

(defn from-throwable
  "Build the public error value from an unexpected exception."
  [^Throwable t]
  (let [data (ex-data t)]
    (cond-> {:code (or (:code data) (:status data) :INTERNAL)
             :message (or (:message data) (.getMessage t) (.getName (class t)))
             :details (dissoc data :code :status :message :outcome
                              :cancelled? :file-changed?)
             :retryable? false}
      (:cancelled? data) (assoc :cancelled? true)
      (:file-changed? data) (assoc :file-changed? (:file-changed? data)))))

(defn cancelled?
  [result]
  (and (map? result)
       (or (:cancelled? result)
           (contains? #{:CANCELLED :STOP_REQUESTED} (:status result)))))

(defn stop-signal
  "Throw a cooperative stop between operations."
  [key]
  (throw (ex-info "The program was stopped between operations"
                  {:status :CANCELLED
                   :code :STOP_REQUESTED
                   :cancelled? true
                   :outcome :not-written
                   :stop-key key})))

(defn stop-signal?
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (= :STOP_REQUESTED (:code (ex-data t)))))
