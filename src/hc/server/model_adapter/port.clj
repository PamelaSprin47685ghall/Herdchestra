(ns hc.server.model-adapter.port
  "The low-level ModelPort implementation.

  One attempt owns a private stream assembler, a shared identity registry, and
  exactly one terminal event.  Transport callbacks enter here, are copied into
  plain data, and leave through the injected receiver.  No callback writes
  storage or starts a sub-task."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.server.model-port.call-identity :as identity]
            [hc.server.model-port.contract :as contract]
            [hc.server.model-port.retry :as retry]
            [hc.server.model-port.stream :as stream]
            [hc.server.model-adapter.errors :as errors]
            [hc.server.model-adapter.events :as events]
            [hc.server.model-adapter.providers :as providers]
            [hc.server.model-adapter.request :as request])
  (:import [dev.langchain4j.model.chat ChatModel StreamingChatModel]
           [dev.langchain4j.model.chat.response ChatResponse CompleteToolCall
            PartialResponse PartialResponseContext PartialThinking
            PartialThinkingContext PartialToolCall PartialToolCallContext
            StreamingChatResponseHandler StreamingHandle]
           [java.lang AutoCloseable]
           [java.util Collections IdentityHashMap]
           [java.util.concurrent Callable ExecutorService Future
            ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(defprotocol ModelAdapterControl
  (stats [port]
    "Counters and active-attempt count for diagnostics.")
  (await-attempt [port attempt-id timeout-ms]
    "Wait for one attempt's terminal event.  Returns `{:status :timeout ...}`
    when the attempt is still running.")
  (close-port! [port]
    "Cancel active attempts, stop dispatch, and release owned resources."))

(defn- thread-factory
  [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- counters
  []
  (atom {:started 0
         :completed 0
         :failed 0
         :cancelled 0
         :late-events 0
         :delivery-drops 0}))

(def ^:private max-retained-attempts
  64)

(defn- bump!
  [attempt k]
  (swap! (:counters (:port attempt)) update k (fnil inc 0)))

(defn- late!
  [attempt]
  (bump! attempt :late-events))

(defn- running?
  [attempt]
  (= :running (:status @(:state attempt))))

(defn- terminal?
  [attempt]
  (not (running? attempt)))

(defn- record-published!
  [attempt event]
  (swap! (:state attempt)
         (fn [state]
           (if (some #{(:call-id event)} (:published-call-ids state))
             state
             (-> state
                 (update :published-call-ids conj (:call-id event))
                 (update :published-calls conj
                         (select-keys event
                                      [:call-id :output-index :tool-name
                                       :arguments])))))))

(defn- cancel-resources!
  [attempt]
  (let [state @(:state attempt)
        ^StreamingHandle handle (:handle state)
        ^Future future (:future state)
        cancel-fn (:cancel-fn (:profile attempt))]
    (when handle
      (try (.cancel handle) (catch Throwable _)))
    (when future
      (try (.cancel future true) (catch Throwable _)))
    (when (fn? cancel-fn)
      (try (cancel-fn (:attempt-id attempt)) (catch Throwable _)))))

(defn- delivery-failure
  "Normalize the receiver result conventions to one failure map.

  The Owner mailbox returns `{:ok? ... :error {:code ...}}`; the bounded
  test/diagnostic receiver returns `{:ok ... :reason ...}`.  Both mean the
  event was not accepted."
  [result]
  (cond
    (false? (:ok result))
    {:reason (or (:reason result) :delivery-error)
     :code (:code result)
     :message (:message result)}

    (false? (:ok? result))
    (let [error (:error result)
          code (or (:code error) (:code result))
          reason (case code
                   :MAILBOX_FULL :overflow
                   :MAILBOX_CLOSED :closed
                   (or (:reason result) :delivery-error))]
      {:reason reason
       :code code
       :message (or (:message error) (:message result))})

    :else nil))

(defn- delivery-error
  [failure]
  {:kind :delivery-error
   :category :unclassified
   :disposition :fail
   :retryable? false
   :code (or (:code failure) :MODEL_EVENT_DELIVERY_FAILED)
   :message (or (:message failure) "the receiver refused a model event")
   :delivery (:reason failure)})

(defn- deliver-event!
  "Deliver one event and return a normalized failure map, or nil on success.

  A receiver exception is a delivery failure.  The adapter must terminate the
  attempt instead of silently dropping the event and continuing."
  [attempt event]
  (let [result (try
                 (contract/deliver-event! (:receiver attempt) event)
                 (catch Throwable _
                   {:ok false
                    :reason :delivery-error
                    :message "receiver delivery failed"}))
        failure (delivery-failure result)]
    (when failure
      (bump! attempt :delivery-drops))
    failure))

(defn- normalize-error
  [value]
  (cond
    (instance? Throwable value) (errors/classify value)
    (map? value) (merge {:category :unclassified
                         :disposition :fail
                         :retryable? false}
                        (dissoc value :error/kind)
                        {:kind (or (:kind value)
                                   (when (= :invalid-snapshot
                                            (:error/kind value))
                                     (:reason value))
                                   (:error/kind value)
                                   :unclassified)})
    :else {:kind :unclassified
           :category :unclassified
           :disposition :fail
           :retryable? false
           :message (str value)}))

(declare close-attempt-profile!)

(defn- finish!
  [attempt status event]
  (locking (:lock attempt)
    (when (or (running? attempt)
              ;; A non-terminal delivery failure may already mark the attempt
              ;; :failed while its terminal failed event still needs to reach
              ;; the promise.  Only a real terminal status blocks this.
              (and (= :failed status)
                   (= :failed (:status @(:state attempt)))
                   (nil? (:terminal-event @(:state attempt)))))
      (if-let [failure (deliver-event! attempt event)]
        ;; The receiver refused the terminal event.  Do not leave the attempt
        ;; looking completed while the Owner never saw the result.  Record a
        ;; failed terminal event and make one best-effort delivery of it.
        (let [state @(:state attempt)
              error (delivery-error failure)
              failed (cond-> (events/failed-event
                              (:attempt-id attempt)
                              (:call-id attempt)
                              {:error error
                               :published-call-ids (:published-call-ids state)
                               :published-calls (:published-calls state)
                               :usage (:usage state)
                               :text-chunks (count (:text-parts state))
                               :reasoning-chunks
                               (count (:reasoning-parts state))})
                       ;; A refused :cancelled terminal stays a cancellation
                       ;; for the Owner, but its error records the refusal so
                       ;; the loss is visible rather than silent.
                       (= :cancelled status) (assoc :reason (:cancel-reason
                                                             state)))]
          (swap! (:state attempt) assoc
                 :status :failed
                 :error error
                 :terminal-event failed
                 :delivery-failed? true)
          (bump! attempt :failed)
          (deliver-event! attempt failed)
          (when-let [delivery (:promise @(:state attempt))]
            (deliver delivery failed))
          (close-attempt-profile! attempt)
          failed)
        (do
          (swap! (:state attempt) assoc :status status :terminal-event event)
          (bump! attempt (case status
                           :completed :completed
                           :failed :failed
                           :cancelled :cancelled
                           :completed))
          (when-let [delivery (:promise @(:state attempt))]
            (deliver delivery event))
          (close-attempt-profile! attempt)
          event)))))

(defn- fail!
  [attempt error]
  (locking (:lock attempt)
    (if-not (running? attempt)
      (late! attempt)
      (let [state @(:state attempt)
            error (normalize-error error)
            event (events/failed-event
                   (:attempt-id attempt)
                   (:call-id attempt)
                   {:error error
                    :published-call-ids (:published-call-ids state)
                    :published-calls (:published-calls state)
                    :usage (:usage state)
                    :text-chunks (count (:text-parts state))
                    :reasoning-chunks (count (:reasoning-parts state))})]
        (swap! (:state attempt) assoc :error error)
        (finish! attempt :failed event)))))

(defn- chunk!
  [attempt channel text context-callback?]
  (when (and (string? text) (not (empty? text)))
    (locking (:lock attempt)
      (if-not (running? attempt)
        (late! attempt)
        (let [state @(:state attempt)]
          (when (or context-callback?
                    (not (contains? (:context-channels state) channel)))
            (swap! (:state attempt)
                   (fn [s]
                     (cond-> (cond-> s
                               context-callback?
                               (update :context-channels
                                       (fnil conj #{}) channel))
                       (= channel :text) (update :text-parts conj text)
                       (= channel :reasoning) (update :reasoning-parts conj text))))
            (when-let [failure
                       (deliver-event!
                        attempt
                        (events/chunk-event (:attempt-id attempt)
                                            channel
                                            text))]
              (fail! attempt (delivery-error failure)))))))))

(defn- capture-handle!
  [attempt ^StreamingHandle handle]
  (when handle
    (locking (:lock attempt)
      (swap! (:state attempt) assoc :handle handle)
      (when (= :cancelled (:status @(:state attempt)))
        (try (.cancel handle) (catch Throwable _))))))

(defn- fragment-error
  [error]
  (if (= :arguments-incomplete (:kind error))
    (let [classification (retry/classify {:kind :tool-arguments-invalid})]
      (merge error
             {:kind :tool-arguments-invalid
              :category (:category classification)
              :disposition (:disposition classification)
              :retryable? (:retryable? classification)}))
    error))

(defn- parse-complete-arguments
  [attempt text]
  (try
    (let [parse (:parse-arguments (:port attempt))
          parsed (if (str/blank? text) {} (parse text))]
      (if (map? parsed)
        {:ok true :arguments parsed}
        {:ok false
         :error (fragment-error
                 {:kind :arguments-incomplete
                  :message "tool arguments did not parse to a map"})}))
    (catch Throwable t
      {:ok false
       :error (fragment-error
               {:kind :arguments-incomplete
                :message (or (.getMessage t)
                             "tool arguments could not be parsed")})})))

(defn- partial-index-for-provider
  "Return the single unpublished partial index for PROVIDER-ID, or nil.

  A complete callback that changes only its index can merge into the partial
  it continues instead of creating a second project call."
  [state attempt-id provider-id]
  (when (and (string? provider-id) (not (str/blank? provider-id)))
    (let [assembler (:assembler state)
          published (set (keys (:published assembler)))
          indices (->> (:partials assembler)
                       (keep (fn [[[call-attempt-id index] fragment]]
                               (when (and (= call-attempt-id attempt-id)
                                          (not (contains? published
                                                          [call-attempt-id
                                                           index]))
                                          (= provider-id
                                             (:provider-call-id fragment)))
                                 index)))
                       set)]
      (when (= 1 (count indices))
        (first indices)))))

(defn- published-index-for-provider
  "Return the single published index already owned by PROVIDER-ID, or nil.

  A repeated complete callback may arrive at a different index after the
  first one was published.  Routing it back to the published index lets the
  stream registry merge the same payload or report a changed payload as a
  conflict instead of creating a second project call."
  [state attempt-id provider-id]
  (when (and (string? provider-id) (not (str/blank? provider-id)))
    (let [indices (->> (:published (:assembler state))
                       (keep (fn [[[call-attempt-id index] entry]]
                               (when (and (= call-attempt-id attempt-id)
                                          (= provider-id
                                             (:provider-call-id entry)))
                                 index)))
                       set)]
      (when (= 1 (count indices))
        (first indices)))))

(defn- accept-fragment!
  [attempt fragment]
  (locking (:lock attempt)
    (if-not (running? attempt)
      (late! attempt)
      (locking (:registry-lock attempt)
        (let [state @(:state attempt)
              fragment (if (:complete? fragment)
                         (if-let [index (or (partial-index-for-provider
                                             state
                                             (:attempt-id attempt)
                                             (:provider-call-id fragment))
                                            (published-index-for-provider
                                             state
                                             (:attempt-id attempt)
                                             (:provider-call-id fragment)))]
                           (assoc fragment :output-index index)
                           fragment)
                         fragment)
              result (stream/accept-fragment (:assembler state)
                                             @(:registry attempt)
                                             fragment)]
          (if (:ok result)
            (do
              (reset! (:registry attempt) (:registry result))
              (swap! (:state attempt) assoc :assembler (:assembler result))
              (when-let [event (:event result)]
                (record-published! attempt event)
                (when-let [failure (deliver-event! attempt event)]
                  (fail! attempt (delivery-error failure))))
              result)
            (do
              (fail! attempt (fragment-error (:error result)))
              result)))))))

(defn- normalized-provider-id
  [value]
  (when (and (string? value) (not (str/blank? value)))
    value))

(defn- call-at-output-index
  [registry attempt-id index]
  (when (and (integer? index) (not (neg? index)))
    (when-let [call-id (get-in registry [:by-output [attempt-id index]])]
      (identity/call registry call-id))))

(defn- provider-id-ambiguous
  [call provider-id existing message]
  {:kind :provider-id-ambiguous
   :category :unclassified
   :disposition :fail
   :retryable? false
   :code :PROVIDER_ID_AMBIGUOUS
   :message message
   :provider-call-id provider-id
   :incoming (select-keys call [:tool-name :arguments])
   :existing (select-keys existing [:tool-name :arguments])})

(defn- align-final-calls
  "Prefer an explicit final output index, then a streamed call's output index
  when the final response repeats the same provider id and payload at a
  different list position.  Blank provider ids are absent aliases, not a
  shared identity.  Content alone never proves identity when the final batch
  or the attempt contains more than one call: two legitimate calls may carry
  identical names and arguments.  A provider id reused for a different
  payload remains an explicit conflict."
  [registry attempt-id calls]
  (reduce
   (fn [acc call]
     (if-not (:ok acc)
       acc
       (let [provider-id (normalized-provider-id (:provider-call-id call))
             output-index (:output-index call)
             indexed (call-at-output-index registry attempt-id output-index)
             single-final? (= 1 (count calls))
             single-attempt? (= 1 (count (identity/call-ids-for-attempt
                                          registry attempt-id)))
             same-content? (fn [other]
                             (and (= (:tool-name other) (:tool-name call))
                                  (= (:arguments other) (:arguments call))))
             call-ids (when provider-id
                        (get-in registry [:by-provider
                                          [attempt-id provider-id]]))
             same-content-ids (when provider-id
                                (filter
                                 (fn [call-id]
                                   (same-content?
                                    (identity/call registry call-id)))
                                 (identity/call-ids-for-attempt
                                  registry attempt-id)))
             existing (cond
                        (and (some? output-index) indexed) indexed
                        (and provider-id (= 1 (count call-ids)))
                        (identity/call registry (first call-ids))
                        (and provider-id (empty? call-ids)
                             single-final? single-attempt?
                             (= 1 (count same-content-ids)))
                        (identity/call registry
                                       (first same-content-ids))
                        :else nil)
             existing-id (:call-id existing)]
         (cond
           (and existing-id (contains? (:used acc) existing-id))
           (assoc acc
                  :ok false
                  :error (provider-id-ambiguous
                          call provider-id existing
                          "more than one final call matched the same streamed call"))

           (and (some? output-index) indexed
                (not (same-content? indexed)))
           (assoc acc
                  :ok false
                  :error (provider-id-ambiguous
                          call provider-id indexed
                          "the final call index names a different payload"))

           (and (some? output-index) indexed)
           (-> acc
               (update :calls conj
                       (assoc call
                              :output-index (:output-index indexed)
                              :provider-call-id
                              (or (:provider-call-id indexed) provider-id)))
               (update :used conj existing-id))

           (and provider-id (> (count call-ids) 1))
           (assoc acc
                  :ok false
                  :error (provider-id-ambiguous
                          call provider-id nil
                          "a provider call id maps to more than one payload"))

           (and provider-id (= 1 (count call-ids))
                (not (same-content? existing)))
           (assoc acc
                  :ok false
                  :error (provider-id-ambiguous
                          call provider-id existing
                          "a provider call id was reused with a different payload"))

           (and provider-id (= 1 (count call-ids)))
           (-> acc
               (update :calls conj
                       (assoc call
                              :output-index (:output-index existing)
                              :provider-call-id
                              (or (:provider-call-id existing) provider-id)))
               (update :used conj existing-id))

           (and provider-id (empty? call-ids)
                single-final? single-attempt?
                (> (count same-content-ids) 1))
           (assoc acc
                  :ok false
                  :error (provider-id-ambiguous
                          call provider-id nil
                          "a new provider call id matches more than one existing payload"))

           (and provider-id (empty? call-ids)
                single-final? single-attempt?
                (= 1 (count same-content-ids)))
           (-> acc
               (update :calls conj
                       (assoc call
                              :output-index (:output-index existing)
                              :provider-call-id
                              (or (:provider-call-id existing) provider-id)))
               (update :used conj existing-id))

           :else
           (update acc :calls conj
                   (assoc call :provider-call-id provider-id))))))
   {:ok true :calls [] :used #{}}
   calls))

(defn- complete-response!
  [attempt ^ChatResponse response result]
  (reset! (:registry attempt) (:registry result))
  (swap! (:state attempt) assoc :assembler (:assembler result))
  (let [failure (some (fn [event]
                        (record-published! attempt event)
                        (deliver-event! attempt event))
                      (:events result))]
    (if failure
      (do
        (fail! attempt (delivery-error failure))
        result)
      (let [state @(:state attempt)
            usage (events/usage-report (.tokenUsage response))
            finish-reason (events/finish-reason->keyword
                           (.finishReason response))
            text (events/response-text response
                                       (apply str (:text-parts state)))
            reasoning (events/response-reasoning
                       response
                       (apply str (:reasoning-parts state)))
            event (events/completed-event
                   (:attempt-id attempt)
                   (:call-id attempt)
                   {:text text
                    :reasoning reasoning
                    :finish-reason finish-reason
                    :usage usage
                    :response-id (.id response)
                    :model-name (.modelName response)
                    :published-calls (:published-calls state)
                    :published-call-ids (:published-call-ids state)})]
        (swap! (:state attempt) assoc :usage usage)
        (finish! attempt :completed event)
        result))))

(defn- accept-final!
  [attempt ^ChatResponse response]
  (locking (:lock attempt)
    (if-not (running? attempt)
      (late! attempt)
      (let [parsed (events/response-tool-calls response
                                               (:parse-arguments (:port attempt)))]
        (if-not (:ok parsed)
          (fail! attempt (:error parsed))
          (locking (:registry-lock attempt)
            (let [aligned (align-final-calls @(:registry attempt)
                                             (:attempt-id attempt)
                                             (:calls parsed))]
              (if-not (:ok aligned)
                (fail! attempt (:error aligned))
                (let [result (stream/accept-final (:assembler @(:state attempt))
                                                  @(:registry attempt)
                                                  (:attempt-id attempt)
                                                  (:calls aligned))]
                  (if-not (:ok result)
                    (fail! attempt (:error result))
                    (complete-response! attempt response result)))))))))))

(defn- handler
  [attempt]
  (reify StreamingChatResponseHandler
    (onPartialResponse [_ text]
      (chunk! attempt :text text false)
      nil)

    (onPartialResponse [_ response context]
      (capture-handle! attempt (.streamingHandle
                                ^PartialResponseContext context))
      (chunk! attempt :text (.text ^PartialResponse response) true)
      nil)

    (onPartialThinking [_ thinking]
      (chunk! attempt :reasoning (.text ^PartialThinking thinking) false)
      nil)

    (onPartialThinking [_ thinking context]
      (capture-handle! attempt (.streamingHandle
                                ^PartialThinkingContext context))
      (chunk! attempt :reasoning (.text ^PartialThinking thinking) true)
      nil)

    (onPartialToolCall [_ call]
      (when-not (contains? (:context-channels @(:state attempt)) :tool)
        (accept-fragment! attempt
                          {:attempt-id (:attempt-id attempt)
                           :output-index (.index ^PartialToolCall call)
                           :provider-call-id (.id ^PartialToolCall call)
                           :tool-name (.name ^PartialToolCall call)
                           :arguments-fragment (.partialArguments
                                                ^PartialToolCall call)
                           :complete? false}))
      nil)

    (onPartialToolCall [_ call context]
      (capture-handle! attempt (.streamingHandle
                                ^PartialToolCallContext context))
      (locking (:lock attempt)
        (swap! (:state attempt) update :context-channels (fnil conj #{}) :tool))
      (accept-fragment! attempt
                        {:attempt-id (:attempt-id attempt)
                         :output-index (.index ^PartialToolCall call)
                         :provider-call-id (.id ^PartialToolCall call)
                         :tool-name (.name ^PartialToolCall call)
                         :arguments-fragment (.partialArguments
                                              ^PartialToolCall call)
                         :complete? false})
      nil)

    (onCompleteToolCall [_ call]
      (let [request (.toolExecutionRequest ^CompleteToolCall call)]
        (if (nil? request)
          (fail! attempt {:kind :tool-request-invalid
                          :category :unclassified
                          :disposition :fail
                          :retryable? false
                          :message "provider returned no complete tool request"})
          (let [arguments (or (.arguments request) "")
                parsed (parse-complete-arguments attempt arguments)]
            (if (:ok parsed)
              (accept-fragment! attempt
                                {:attempt-id (:attempt-id attempt)
                                 :output-index (.index ^CompleteToolCall call)
                                 :provider-call-id (.id request)
                                 :tool-name (.name request)
                                 :arguments (:arguments parsed)
                                 :complete? true})
              (fail! attempt (:error parsed))))))
      nil)

    (onCompleteResponse [_ response]
      (try
        (accept-final! attempt response)
        (catch Throwable t
          (fail! attempt (if (instance? clojure.lang.ExceptionInfo t)
                           (ex-data t)
                           t))))
      nil)

    (onError [_ error]
      (fail! attempt error)
      nil)))

(defn- store-future!
  [attempt ^Future future]
  (swap! (:state attempt) assoc :future future)
  (when (= :cancelled (:status @(:state attempt)))
    (try (.cancel future true) (catch Throwable _))))

(defn- submit!
  [attempt f]
  (let [^ExecutorService executor (:executor (:port attempt))
        task (reify Callable
               (call [_] (f)))]
    (try
      (store-future! attempt (.submit executor task))
      (catch Throwable t
        (fail! attempt t)))))

(defn- dispatch!
  [attempt chat-request]
  (let [profile (:profile attempt)
        snapshot (:snapshot (:request attempt))
        explicit-stream? (contains? snapshot :stream?)
        stream? (if explicit-stream?
                  (boolean (:stream? snapshot))
                  (some? (:streaming-chat-model profile)))
        ^StreamingChatModel streaming-model (:streaming-chat-model profile)
        ^ChatModel chat-model (:chat-model profile)]
    (cond
      (and stream? streaming-model)
      (submit! attempt
               (fn []
                 (try
                   (.chat streaming-model chat-request (handler attempt))
                   (catch Throwable t
                     (fail! attempt t)))))

      (and (not stream?) chat-model)
      (submit! attempt
               (fn []
                 (try
                   (accept-final! attempt (.chat chat-model chat-request))
                   (catch Throwable t
                     (fail! attempt t)))))

      (and stream? (nil? streaming-model))
      (fail! attempt {:kind :streaming-unavailable
                      :category :unclassified
                      :disposition :fail
                      :retryable? false
                      :message "the request requires streaming but the selected model has no StreamingChatModel"})

      (and (not stream?) (nil? chat-model))
      (fail! attempt {:kind :chat-model-unavailable
                      :category :unclassified
                      :disposition :fail
                      :retryable? false
                      :message "the request requires a non-streaming model but the selected model has no ChatModel"})

      :else
      (fail! attempt {:kind :no-model-transport
                      :category :unclassified
                      :disposition :fail
                      :retryable? false
                      :message "the selected provider has no usable model transport"}))))

(defn- id-str
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    :else value))

(defn- missing-role-error
  "Classify a role lookup miss inside `resolve-profile`.

  A `:task` request against a port that only configured `:main` is a
  missing configuration, not an unknown role: the vocabulary exists, the
  binding does not.  Every other miss keeps `:unknown-role`."
  [port-state role]
  (let [roles (:roles port-state)
        configured? (contains? roles role)
        kind (if (and (= :task role)
                      (contains? roles :main)
                      (not configured?))
               :role-unconfigured
               :unknown-role)]
    (cond-> {:kind kind
             :category (if (= :role-unconfigured kind)
                         :permanent
                         :unclassified)
             :disposition :fail
             :retryable? false
             :message (if (= :role-unconfigured kind)
                        "the :task role is not configured; only :main exists"
                        "snapshot names an unknown model role")
             :role role
             :roles (vec (sort-by str (keys roles)))}
      (= :role-unconfigured kind)
      (assoc :remedy :configure-task-model))))

(defn- resolve-profile
  [port-state snapshot]
  (let [role (keyword (or (:role snapshot) (:default-role port-state)))
        role-target (or (get-in port-state [:roles role])
                        {:error (missing-role-error port-state role)})
        role-connection (id-str (:connection role-target))
        requested-connection (id-str (or (:connection snapshot)
                                         (:provider snapshot)))
        ;; A main-role request may name the session's selected connection,
        ;; which can differ from the current main role target.  The role
        ;; target is the default for a request without an explicit identity.
        ;; A task request must stay on the task target: a worker must not
        ;; borrow the main model by naming its connection.
        connection-mismatch? (and (= :task role)
                                  (some? requested-connection)
                                  (some? role-connection)
                                  (not= requested-connection role-connection))
        model-mismatch? (and (= :task role)
                             (some? (:model snapshot))
                             (some? (:model role-target))
                             (not= (id-str (:model snapshot))
                                   (id-str (:model role-target))))
        connection (or requested-connection role-connection)
        model (id-str (or (:model snapshot) (:model role-target)))
        profile (get-in port-state [:profiles [connection model]])]
    (cond
      (nil? role)
      {:ok false
       :error {:kind :role-required
               :category :unclassified
               :disposition :fail
               :retryable? false
               :message "snapshot must name :main or :task when both roles are configured"
               :roles (vec (sort-by str (keys (:roles port-state))))}}

      (:error role-target) {:ok false :error (:error role-target)}
      connection-mismatch?
      {:ok false
       :error {:kind :role-connection-mismatch
               :category :unclassified
               :disposition :fail
               :retryable? false
               :message "snapshot connection disagrees with the role target"
               :role role
               :requested-connection requested-connection
               :role-connection role-connection}}
      model-mismatch?
      {:ok false
       :error {:kind :role-model-mismatch
               :category :unclassified
               :disposition :fail
               :retryable? false
               :message "a task request must use the task role's model"
               :role role
               :requested-model (:model snapshot)
               :role-model (:model role-target)}}
      (nil? connection) {:ok false
                         :error {:kind :connection-missing
                                 :category :unclassified
                                 :disposition :fail
                                 :retryable? false
                                 :message "snapshot does not name a provider connection"}}
      (nil? model) {:ok false
                    :error {:kind :model-missing
                            :category :unclassified
                            :disposition :fail
                            :retryable? false
                            :message "snapshot does not name a model"}}
      (nil? profile) {:ok false
                      :error {:kind :unknown-model
                              :category :unclassified
                              :disposition :fail
                              :retryable? false
                              :message "no configured provider profile matches the request"
                              :connection connection
                              :model model
                              :available (->> (keys (:profiles port-state))
                                              (mapv (fn [[c m]] [c m]))
                                              (sort-by pr-str)
                                              vec)}}
      :else {:ok true :profile profile :role role})))

(defn- new-attempt
  [port-state request receiver profile request-profile]
  {:attempt-id (:attempt-id request)
   :call-id (:call-id request)
   :request request
   :receiver receiver
   :profile profile
   :request-profile request-profile
   :port port-state
   :lock (Object.)
   :registry (:registry port-state)
   :registry-lock (:registry-lock port-state)
   :state (atom {:status :running
                 :assembler (stream/new-assembler)
                 :handle nil
                 :future nil
                 :published-call-ids []
                 :published-calls []
                 :text-parts []
                 :reasoning-parts []
                 :context-channels #{}
                 :request-profile request-profile
                 :profile-closed? (nil? request-profile)
                 :terminal-event nil
                 :promise (promise)})})

(defn- prune-attempts!
  "Drop the oldest terminal attempts above the retention limit.

  Active attempts are never evicted.  Their call identities are removed from
  the global registry so long-lived ports stay bounded."
  [port-state]
  (loop []
    (let [attempts @(:attempts port-state)
          order @(:attempt-order port-state)]
      (when (> (count attempts) max-retained-attempts)
        (let [victim (some (fn [attempt-id]
                             (when-let [attempt (get attempts attempt-id)]
                               (when-not (running? attempt)
                                 attempt-id)))
                           order)]
          (when victim
            (swap! (:attempts port-state) dissoc victim)
            (swap! (:attempt-order port-state)
                   (fn [ids] (vec (remove #{victim} ids))))
            (swap! (:registry port-state)
                   identity/forget-attempt victim)
            (recur)))))))

(defn- register-attempt!
  [port-state attempt]
  (let [attempt-id (:attempt-id attempt)]
    (when (contains? @(:attempts port-state) attempt-id)
      (contract/invalid! :duplicate-attempt-id
                         "attempt-id is already in use"
                         {:attempt-id attempt-id}))
    (swap! (:attempts port-state) assoc attempt-id attempt)
    (swap! (:attempt-order port-state) conj attempt-id)
    (prune-attempts! port-state)
    attempt))

(defn- preflight!
  [port-state attempt]
  (let [request (:request attempt)
        snapshot (:snapshot request)
        profile (:profile attempt)
        required (set/union (set (:required-capabilities request))
                            (request/required-capabilities snapshot))
        resolved (contract/resolve-capabilities
                  (assoc request :required-capabilities required)
                  (:honoured-capabilities profile)
                  (:degradations profile))]
    (cond
      (and (contains? snapshot :attempt-id)
           (not= (:attempt-id snapshot) (:attempt-id request)))
      (fail! attempt {:kind :invalid-request
                      :category :unclassified
                      :disposition :fail
                      :retryable? false
                      :message "snapshot attempt-id disagrees with the request"
                      :snapshot-attempt-id (:attempt-id snapshot)})

      (and (contains? snapshot :call-id)
           (not= (:call-id snapshot) (:call-id request)))
      (fail! attempt {:kind :invalid-request
                      :category :unclassified
                      :disposition :fail
                      :retryable? false
                      :message "snapshot call-id disagrees with the request"
                      :snapshot-call-id (:call-id snapshot)})

      (not (:ok resolved))
      (fail! attempt (:error resolved))

      (seq (:degraded resolved))
      (fail! attempt {:kind :unsupported-capability
                      :category :unclassified
                      :disposition :fail
                      :retryable? false
                      :message "the adapter does not implement the declared degradation"
                      :degraded (vec (sort-by str (keys (:degraded resolved))))})

      :else
      (let [transport (retry/validate-transport
                       {:implicit-retries (:implicit-retries profile)})]
        (if-not (:ok transport)
          (fail! attempt (:error transport))
          (try
            (let [chat-request (request/compile-request
                                snapshot profile
                                {:attachment-loader (:attachment-loader (:port attempt))})]
              (dispatch! attempt chat-request)
              attempt)
            (catch Throwable t
              (fail! attempt (if (instance? clojure.lang.ExceptionInfo t)
                               (ex-data t)
                               t))
              attempt)))))))

 (defn- resolve-request-profile
  "Resolve one request-time credential when the connection uses the multi-key
  path.  The raw secret lives only in the rebuilt provider model; it is not
  stored in the port state or any event."
  [port-state profile]
  (if-not (:requires-credential? profile)
    {:ok true :profile profile :rebuilt? false}
    (if-let [resolver (:credential-resolver port-state)]
      (try
        (let [resolved (resolver {:connection-id (:connection-id profile)
                                  :model-id (:model-id profile)
                                  :connection (:connection profile)
                                  :model (:model profile)})
              ok? (or (:ok? resolved) (:ok resolved))
              secret (or (:secret resolved)
                         (when (string? resolved) resolved))
              connection (merge (:connection profile)
                                (when (map? (:connection resolved))
                                  (:connection resolved))
                                (when (some? secret)
                                  {:api-key secret}))
              resolved-key (or (:api-key connection)
                               (get-in resolved [:connection :api-key]))]
          (if (and ok?
                   (string? resolved-key)
                   (not (str/blank? resolved-key)))
            {:ok true
             :profile (providers/build-profile connection (:model profile))
             :rebuilt? true}
            {:ok false
             :error (merge {:kind :credential-resolution-failed
                            :category :auth
                            :disposition :fail
                            :retryable? false
                            :message (if ok?
                                       "credential resolver returned no secret"
                                       "credential resolution failed")
                            :code :CREDENTIAL_RESOLUTION_FAILED}
                           (select-keys (:error resolved)
                                        [:message :code]))}))
        (catch Throwable t
          {:ok false
           :error {:kind :credential-resolution-failed
                   :category :auth
                   :disposition :fail
                   :retryable? false
                   :message (or (.getMessage t)
                                "credential resolution failed")
                   :code :CREDENTIAL_RESOLUTION_FAILED}}))
      {:ok false
       :error {:kind :credential-resolver-missing
               :category :auth
               :disposition :fail
               :retryable? false
               :message "connection uses :keys/:credentials but no credential-resolver is configured"
               :code :CREDENTIAL_RESOLVER_MISSING}})))

(defn- start!
  [port-state request receiver]
  (when @(:closed? port-state)
    (contract/invalid! :port-closed "the model port is closed" {}))
  (when-not (map? request)
    (contract/invalid! :request-invalid "request must be a map" {}))
  (when-not (and (some? (:attempt-id request))
                 (some? (:call-id request))
                 (map? (:snapshot request)))
    (contract/invalid! :request-invalid
                       "request needs :attempt-id, :call-id, and a snapshot map"
                       {:has-attempt-id? (some? (:attempt-id request))
                        :has-call-id? (some? (:call-id request))
                        :snapshot-map? (map? (:snapshot request))}))
  (when-not (satisfies? contract/Receiver receiver)
    (contract/invalid! :receiver-invalid
                       "event target does not implement Receiver"
                       {}))
  (let [snapshot (:snapshot request)
        resolved (resolve-profile port-state snapshot)]
    (if-not (:ok resolved)
      (let [attempt (register-attempt!
                     port-state
                     (new-attempt port-state request receiver nil nil))]
        (bump! attempt :started)
        (fail! attempt (:error resolved))
        (assoc (contract/handle request) :port-id (:id port-state)))
      (let [resolved-profile (resolve-request-profile
                              port-state (:profile resolved))]
        (if-not (:ok resolved-profile)
          (let [attempt (register-attempt!
                         port-state
                         (new-attempt port-state request receiver nil nil))]
            (bump! attempt :started)
            (fail! attempt (:error resolved-profile))
            (assoc (contract/handle request) :port-id (:id port-state)))
          (let [attempt (register-attempt!
                         port-state
                         (new-attempt port-state request receiver
                                      (:profile resolved-profile)
                                      (when (:rebuilt? resolved-profile)
                                        (:profile resolved-profile))))]
            (bump! attempt :started)
            (preflight! port-state attempt)
            (assoc (contract/handle request) :port-id (:id port-state))))))))

(defn- cancel!
  ([port-state handle] (cancel! port-state handle :user-cancel))
  ([port-state handle reason]
  (when-not (contract/handle? handle)
    (contract/invalid! :handle-invalid "cancel needs an attempt handle"
                       {:handle handle}))
  (if (and (some? (:port-id handle))
           (not= (:port-id handle) (:id port-state)))
    {:ok false :reason :wrong-port}
    (if-let [attempt (get @(:attempts port-state) (:attempt-id handle))]
      (if (not= (:call-id handle) (:call-id attempt))
        {:ok false :reason :handle-mismatch}
        (locking (:lock attempt)
          (if-not (running? attempt)
            {:ok false :reason :already-terminal
             :status (:status @(:state attempt))}
            (let [state @(:state attempt)
                  event (events/cancelled-event
                         (:attempt-id attempt)
                         (:call-id attempt)
                         {:reason reason
                          :published-call-ids (:published-call-ids state)
                          :published-calls (:published-calls state)
                          :usage (:usage state)})]
              (swap! (:state attempt) assoc :cancel-reason reason)
              (let [terminal (finish! attempt :cancelled event)]
                ;; Deliver the terminal event before calling potentially
                ;; blocking transport cancellation hooks.
                (cancel-resources! attempt)
                ;; A refused cancellation has already terminated the attempt
                ;; with a failed event; report that, never the undelivered one.
                (if (:delivery-failed? @(:state attempt))
                  {:ok true
                   :delivery-failed? true
                   :event (get-in @(:state attempt) [:terminal-event])}
                  {:ok true
                   :delivery-failed? false
                   :event (or terminal event)}))))))
      {:ok false :reason :not-found}))))

(defn- stats*
  [port-state]
  (let [attempts @(:attempts port-state)
        active (count (filter (fn [attempt] (running? attempt)) (vals attempts)))]
    (merge @(:counters port-state)
           {:active active
            :total (count attempts)
            :closed? (boolean @(:closed? port-state))})))

(defn- await*
  [port-state attempt-id timeout-ms]
  (if-let [attempt (get @(:attempts port-state) attempt-id)]
    (let [timeout-ms (or timeout-ms 5000)
          result (deref (:promise @(:state attempt)) timeout-ms ::timeout)]
      (if (= ::timeout result)
        (let [state @(:state attempt)]
          (or (:terminal-event state)
              {:status :timeout :attempt-id attempt-id
               :state (:status state)}))
        result))
    {:status :not-found :attempt-id attempt-id}))

(defn- close-model!
  [seen model]
  (when (and model (instance? AutoCloseable model))
    (let [identity-set ^java.util.Set seen]
      (when-not (.contains identity-set model)
        (.add identity-set model)
        (try (.close ^AutoCloseable model) (catch Throwable _))))))

(defn- close-profile!
  [profile close-models? seen-models closed-fns]
  (when close-models?
    (close-model! seen-models (:chat-model profile))
    (close-model! seen-models (:streaming-chat-model profile)))
  (doseq [close-fn (:close-fns profile)]
    (when (fn? close-fn)
      (when-not (contains? @closed-fns close-fn)
        (swap! closed-fns conj close-fn)
        (try (close-fn) (catch Throwable _))))))

(defn- close-attempt-profile!
  [attempt]
  (locking (:lock attempt)
    (let [state @(:state attempt)
          profile (:request-profile attempt)]
      (when (and profile (not (:profile-closed? state)))
        (swap! (:state attempt) assoc :profile-closed? true)
        (let [seen-models (Collections/newSetFromMap (IdentityHashMap.))]
          ;; A request-time profile was built for this attempt, so its
          ;; provider models are owned even when configured profiles are
          ;; shared and :close-models? is false.
          (close-profile! profile true
                           seen-models (:closed-fns (:port attempt))))))))

(defn- close*
  [port-state]
  (when (compare-and-set! (:closed? port-state) false true)
    (doseq [attempt (vals @(:attempts port-state))]
      (cancel! port-state (contract/handle (:request attempt)) :port-close))
    (let [^ExecutorService executor (:executor port-state)]
      (.shutdown executor)
      (try
        (.awaitTermination executor 5 TimeUnit/SECONDS)
        (catch InterruptedException _))
      (when-not (.isTerminated executor)
        (.shutdownNow executor)))
    (doseq [close-fn (:close-fns port-state)]
      (when (fn? close-fn)
        (when-not (contains? @(:closed-fns port-state) close-fn)
          (swap! (:closed-fns port-state) conj close-fn)
          (try (close-fn) (catch Throwable _)))))
    (let [seen-models (Collections/newSetFromMap (IdentityHashMap.))]
      (doseq [profile (vals (:profiles port-state))]
        (close-profile! profile (:close-models? port-state)
                         seen-models (:closed-fns port-state)))
      (doseq [attempt (vals @(:attempts port-state))]
        (close-attempt-profile! attempt)))
    (reset! (:attempts port-state) {})
    (reset! (:attempt-order port-state) [])
    (reset! (:registry port-state) (identity/new-registry)))
  (stats* port-state))

(defn create
  "Create one ModelPort from normalized roles, profiles, and an executor."
  [{:keys [id roles default-role profiles parse-arguments attachment-loader
           credential-resolver executor
           close-models? close-fns]}]
  (let [port-state {:id (or id (str (random-uuid)))
                    :roles roles
                    :default-role default-role
                    :profiles profiles
                    :parse-arguments parse-arguments
                    :attachment-loader attachment-loader
                    :credential-resolver credential-resolver
                    :executor executor
                    :close-models? (boolean close-models?)
                    :close-fns (vec close-fns)
                    :closed? (atom false)
                    :attempts (atom {})
                    :attempt-order (atom [])
                    :counters (counters)
                    :closed-fns (atom #{})
                    :registry (atom (identity/new-registry))
                    :registry-lock (Object.)}]
    (reify
      contract/ModelPort
      (start-request! [_ request receiver]
        (start! port-state request receiver))
      (cancel-request! [_ handle]
        (cancel! port-state handle))

      ModelAdapterControl
      (stats [_]
        (stats* port-state))
      (await-attempt [_ attempt-id timeout-ms]
        (await* port-state attempt-id timeout-ms))
      (close-port! [_]
        (close* port-state)))))
