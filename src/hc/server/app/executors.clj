(ns hc.server.app.executors
  "Adapt managed components to the Owner executor contract.

  An Owner executor receives `{:kind :call-id :attempt-id :request ...}` and
  returns `{:status :succeeded|:failed|:cancelled|:outcome-unknown
  :payload ... :error ...}`.  It never posts a message and never throws for
  an expected rejection."
  (:require [hc.server.shell :as shell]))

(defn- shell-failure
  [result]
  (or (:error result)
      {:kind (or (:status result) :shell-failed)
       :message (or (:message result)
                    (str "shell session ended with " (:status result)))
       :details (select-keys result [:request-id :phase :exit-code])}))

(defn- shell-status
  [result]
  (cond
    (and (= :COMPLETED (:status result))
         (false? (:published? result))) :outcome-unknown
    (and (= :COMPLETED (:status result)) (:cancelled? result)) :cancelled
    (= :COMPLETED (:status result))
    (if (and (integer? (:exit-code result)) (zero? (:exit-code result)))
      :succeeded
      :failed)
    (= :CANCELLED (:status result)) :cancelled
    (contains? #{:UNCERTAIN :TIMEOUT} (:status result)) :outcome-unknown
    :else :failed))

(defn- shell-publish-pending-error
  [result]
  {:kind :shell-publish-pending
   :message "shell completed but board publication is still pending"
   :retry :publish-only
   :request-id (:request-id result)
   :publish-error (:publish-error result)})

(defn- shell-result
  [result]
  (case (shell-status result)
    :succeeded {:status :succeeded :payload result}
    :cancelled {:status :cancelled :payload result :error (:error result)}
    :outcome-unknown
    {:status :outcome-unknown
     :payload result
     :error (if (false? (:published? result))
              (shell-publish-pending-error result)
              (:error result))}
    {:status :failed :payload result :error (shell-failure result)}))

(defn shell-retry-publish!
  "Retry only a shell action's board publication.

  Never reruns the shell.  A still-pending publication stays
  `:outcome-unknown` with `:retry :publish-only` and keeps the action
  responsibility."
  [runner request-id]
  (let [result (try
                 (shell/retry-publish! runner request-id)
                 (catch Throwable t
                   {:status :outcome-unknown
                    :error {:kind :executor-error
                            :message (or (ex-message t) (str (class t)))}}))]
    (if (true? (:published? result))
      (if (or (= :CANCELLED (:status result))
              (:cancelled? result))
        {:status :cancelled :payload result :error (:error result)}
        {:status :succeeded :payload result})
      {:status :outcome-unknown
       :payload result
       :error (shell-publish-pending-error result)})))

(defn shell-executor
  "Build the `:shell` executor for one session runtime.

  `board-snapshot-fn` returns the Owner's current board so the executor can
  pass the authoritative token term.  `request-id` is the call id; the
  shell board adapter uses it for idempotent begin/publish.

  A `{:op :retry-publish :request-id id}` request retries only the board
  publication through `shell/retry-publish!`; it never reruns the process."
  [runner board-snapshot-fn]
  (fn [{:keys [call-id attempt-id request]}]
    (if (= :retry-publish (:op request))
      (shell-retry-publish! runner (:request-id request))
      (let [board (try (board-snapshot-fn) (catch Throwable _ nil))
            token (:token-slot board)
            payload (or request {})
            request (-> payload
                        (assoc :request-id call-id
                               :attempt-id attempt-id
                               :purpose (or (:purpose payload)
                                            "managed shell call")
                               :actor-id (or (:actor-id payload)
                                             (:agent-id payload))
                               :claim (or (:claim payload)
                                          (:claim-id payload))
                               :token-term (or (:token-term payload)
                                               (:term-id token))
                               :cwd (or (:cwd payload)
                                        (:workspace payload)
                                        ".")))
            result (try
                     (shell/run! runner request)
                     (catch Throwable t
                       {:status :UNCERTAIN
                        :message (.getMessage t)
                        :outcome :unknown}))
            result (if (false? (:published? result))
                     (try
                       (shell/retry-publish! runner (:request-id result))
                       (catch Throwable _ result))
                     result)]
        (shell-result result)))))

(defn workspace-executor
  "Build the `:workspace` executor from a structured-operation function.

  `execute-fn` receives the request map and returns the workspace bridge
  result `{:status .. :value .. :receipts .. :effects .. :error ..}`."
  [execute-fn]
  (fn [{:keys [attempt-id request]}]
    (let [result (try
                   (execute-fn (assoc request :attempt-id attempt-id))
                   (catch Throwable t
                     {:status :outcome-unknown
                      :error {:kind :executor-error
                              :message (.getMessage t)}}))]
      (case (:status result)
        :succeeded {:status :succeeded :payload result}
        :cancelled {:status :cancelled :payload result :error (:error result)}
        :outcome-unknown {:status :outcome-unknown
                          :payload result :error (:error result)}
        {:status :failed :payload result :error (:error result)}))))

(defn tool-executor
  "Build the `:tool` executor from the program bridge.

  The program bridge returns the same status vocabulary as the Owner, so the
  payload is passed through without inventing a second result shape."
  [run-fn]
  (fn [{:keys [call-id attempt-id request]}]
    (let [result (try
                   (run-fn (assoc request
                                  :call-id call-id
                                  :attempt-id attempt-id))
                   (catch Throwable t
                     {:status :failed
                      :error {:kind :executor-error
                              :message (.getMessage t)}}))]
      (case (:status result)
        :succeeded {:status :succeeded :payload result}
        :cancelled {:status :cancelled :payload result :error (:error result)}
        :outcome-unknown {:status :outcome-unknown
                          :payload result :error (:error result)}
        {:status :failed :payload result :error (:error result)}))))
