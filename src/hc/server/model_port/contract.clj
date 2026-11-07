(ns hc.server.model-port.contract
  "The narrow model port contract from AGENTS.md §5.4.

  A port exposes exactly two operations: start one attempt from a frozen
  request snapshot, and cancel one handle.  Provider callbacks cross into the
  kernel only through a Receiver, one lightweight event at a time.  This
  namespace writes no storage, starts no task, and implements no provider.

  The request snapshot is deep-frozen here.  Compiling the snapshot from
  session state, Pair Hint, and Scope belongs to the caller; the port only
  promises that the value it receives cannot change during the attempt."
  (:require [clojure.set :as set]))

(defprotocol ModelPort
  (start-request! [port request receiver]
    "Start one attempt from a frozen request and a receiver.
    The port must not mutate `request`, and must attach its attempt identity
    to every callback it delivers.")
  (cancel-request! [port handle]
    "Ask the port to stop one started attempt.
    Late callbacks remain diagnostic; they never reopen the attempt."))

(defprotocol Receiver
  (deliver! [receiver event]
    "Accept one lightweight event on the transport thread.
    A receiver may enqueue or merge, but must not perform blocking I/O or
    start sub-tasks on that thread."))

(def event-kinds
  "Events a port may deliver for one attempt."
  #{:chunk :tool-call :completed :failed :cancelled})

(defn invalid!
  "Throw one contract violation.  Callers that cross a trust boundary may
  convert the exception to a structured failure."
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-request
                          :reason reason}
                         data))))

(defn invalid-request?
  "True for the exception shape produced by `invalid!`."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (= :invalid-request (:error/kind (ex-data t)))))

(defn- scalar?
  [value]
  (or (nil? value)
      (boolean? value)
      (keyword? value)
      (symbol? value)
      (number? value)
      (string? value)
      (char? value)
      (uuid? value)
      (inst? value)
      (instance? java.time.temporal.Temporal value)))

(defn freeze-value
  "Copy `value` into persistent Clojure data.

  Maps, vectors, sets, and other sequential collections are copied
  recursively.  Mutable or opaque objects are rejected: a snapshot that can
  change after dispatch is not a snapshot."
  [value]
  (cond
    (scalar? value) value

    (map? value)
    (persistent!
     (reduce-kv (fn [m k v]
                  (assoc! m (freeze-value k) (freeze-value v)))
                (transient {})
                value))

    (vector? value) (mapv freeze-value value)

    (set? value) (into #{} (map freeze-value) value)

    (sequential? value) (mapv freeze-value value)

    :else
    (invalid! :mutable-value
              "request snapshot contains a mutable or opaque value"
              {:class (some-> value class .getName)})))

(defn freeze-snapshot
  "Deep-freeze one request snapshot.  The result is persistent data."
  [snapshot]
  (when-not (map? snapshot)
    (invalid! :snapshot-not-map
              "request snapshot must be a map"
              {:class (some-> snapshot class .getName)}))
  (freeze-value snapshot))

(defn request
  "Build the value passed to `start-request!`.

  `:attempt-id` identifies this concrete try.  `:call-id` identifies the
  business call across retries.  `:snapshot` is the immutable provider input.
  `:required-capabilities` names input features the snapshot depends on."
  [{:keys [attempt-id call-id snapshot required-capabilities]}]
  (when (nil? attempt-id)
    (invalid! :attempt-id-missing
              "a request needs an attempt-id"
              {}))
  (when (nil? call-id)
    (invalid! :call-id-missing
              "a request needs a call-id"
              {}))
  {:attempt-id attempt-id
   :call-id call-id
   :snapshot (freeze-snapshot snapshot)
   :required-capabilities (set (or required-capabilities []))})

(defn handle
  "The cancellation handle for one started attempt."
  [request]
  {:attempt-id (:attempt-id request)
   :call-id (:call-id request)})

(defn handle?
  [value]
  (and (map? value)
       (some? (:attempt-id value))
       (some? (:call-id value))))

(defn resolve-capabilities
  "Check the snapshot's required capabilities against a provider declaration.

  `supported` is a set of capability keywords.  `degradations` maps a missing
  capability to a caller-declared fallback.  An unsupported capability without
  a declaration is an explicit failure; fields are never dropped silently.
  Returns `{:ok true :request ... :degraded ...}` or `{:ok false :error ...}`."
  [request supported degradations]
  (let [required (set (:required-capabilities request))
        supported (set supported)
        missing (set/difference required supported)
        declared (set (keys degradations))
        undeclared (set/difference missing declared)]
    (if (seq undeclared)
      {:ok false
       :request request
       :error {:kind :unsupported-capability
               :missing (vec (sort-by str undeclared))
               :declared (vec (sort-by str declared))}}
      {:ok true
       :request request
       :degraded (select-keys degradations (vec missing))})))

(defn event
  "Build one transport event with its attempt identity attached.

  `:event` is one of `event-kinds`; `:attempt-id` is mandatory.  A tool-call
  event also carries the project `:call-id` and `:output-index` assigned by
  the adapter.  The payload is plain data so the transport thread only copies
  and enqueues."
  [{:keys [kind attempt-id] :as fields}]
  (when-not (contains? event-kinds kind)
    (invalid! :unknown-event
              (str "unknown model port event: " (pr-str kind))
              {:event kind}))
  (when (nil? attempt-id)
    (invalid! :attempt-id-missing
              "an event needs its attempt-id"
              {:event kind}))
  (-> fields
      (dissoc :kind)
      (assoc :event kind :attempt-id attempt-id)))

(defn deliver-event!
  "Validate and hand one event to a receiver.  No I/O happens here."
  [receiver event]
  (when-not (satisfies? Receiver receiver)
    (invalid! :receiver-invalid
              "event target does not implement Receiver"
              {}))
  (when-not (and (map? event)
                 (contains? event-kinds (:event event))
                 (some? (:attempt-id event)))
    (invalid! :event-invalid
              "event must carry a known kind and an attempt-id"
              {:event event}))
  (deliver! receiver event))

(defn start!
  "Validate the port and receiver, then dispatch one attempt."
  [port request receiver]
  (when-not (satisfies? ModelPort port)
    (invalid! :port-invalid
              "port does not implement ModelPort"
              {}))
  (when-not (satisfies? Receiver receiver)
    (invalid! :receiver-invalid
              "receiver does not implement Receiver"
              {}))
  (start-request! port request receiver))

(defn cancel!
  "Validate the port and handle, then request cancellation."
  [port handle]
  (when-not (satisfies? ModelPort port)
    (invalid! :port-invalid
              "port does not implement ModelPort"
              {}))
  (when-not (handle? handle)
    (invalid! :handle-invalid
              "cancel needs an attempt handle"
              {:handle handle}))
  (cancel-request! port handle))
