(ns hc.server.model-port.stream
  "Streaming tool-call assembly.

  Argument fragments stay private until the adapter marks them complete and
  they pass basic validation.  Only then is the project `call-id` allocated
  and a `:tool-call` event published.  The final response passes through the
  same identity registry, so a call that appears both in a complete streaming
  callback and in the final response produces one event, not two.

  A parser is injected by the provider adapter.  This namespace does not
  assume JSON or EDN and never publishes an unparsed fragment."
  (:require [clojure.string :as str]
            [hc.server.model-port.call-identity :as identity]
            [hc.server.model-port.contract :as contract]))

(defn new-assembler
  "An empty assembler.  `:partials` holds unpublished argument fragments;
  `:published` holds the calls already emitted for deduplication."
  []
  {:partials {}
   :published {}})

(defn- invalid
  ([assembler kind message data]
   (invalid assembler nil kind message data))
  ([assembler registry kind message data]
   {:ok false
    :error (merge {:kind kind :message message} data)
    :assembler assembler
    :registry registry}))

(defn- key-of [attempt-id output-index]
  [attempt-id output-index])

(defn- partial-arguments
  "Combine the fragments seen for one call.  A map is already complete; a
  string sequence needs the injected parser."
  [{:keys [arguments fragments]} parse-arguments]
  (cond
    (map? arguments) {:ok true :arguments arguments}

    (and (seq fragments) (fn? parse-arguments))
    (try
      (let [parsed (parse-arguments (apply str fragments))]
        (if (map? parsed)
          {:ok true :arguments parsed}
          {:ok false :reason :arguments-not-a-map}))
      (catch Throwable t
        {:ok false :reason :arguments-unparsable :error (.getMessage t)}))

    (seq fragments)
    {:ok false :reason :parser-missing}

    :else
    {:ok false :reason :arguments-missing}))

(defn- publish
  [assembler registry attempt-id output-index provider-call-id tool-name arguments]
  (let [registered (identity/register-call
                    registry
                    {:attempt-id attempt-id
                     :output-index output-index
                     :provider-call-id provider-call-id
                     :tool-name tool-name
                     :arguments arguments
                     :complete? true})]
    (if-not (:ok registered)
      (invalid assembler registry (:kind (:error registered))
               (:message (:error registered))
               {:identity-error (:error registered)})
      (let [assembler' (assoc-in assembler
                                 [:published (key-of attempt-id output-index)]
                                 {:call-id (:call-id registered)
                                  :provider-call-id provider-call-id
                                  :tool-name tool-name
                                  :arguments arguments})
            event (when (= :created (:status registered))
                    (contract/event {:kind :tool-call
                                     :attempt-id attempt-id
                                     :call-id (:call-id registered)
                                     :output-index output-index
                                     :tool-name tool-name
                                     :arguments arguments}))]
        {:ok true
         :assembler assembler'
         :registry (:registry registered)
         :call-id (:call-id registered)
         :status (:status registered)
         :event event}))))

(defn accept-fragment
  "Accept one streamed tool-call fragment.

  Fragment keys: `:attempt-id`, `:output-index`, `:provider-call-id`,
  `:tool-name`, `:arguments-fragment` (string or map), `:arguments` (complete
  map), `:complete?`, and `:parse-arguments` (string -> map).

  Returns `{:ok true :assembler ... :registry ... :event event-or-nil}`.
  An incomplete fragment returns no event.  A duplicate complete fragment
  merges and returns no second event.  A changed payload is an explicit
  conflict."
  [assembler registry fragment]
  (let [{:keys [attempt-id output-index provider-call-id tool-name
                arguments-fragment arguments complete? parse-arguments]} fragment
        existing-published (get-in assembler [:published (key-of attempt-id output-index)])
        existing (get-in assembler [:partials (key-of attempt-id output-index)])]
    (cond
      (nil? attempt-id)
      (invalid assembler registry :invalid-fragment
               "a fragment needs an attempt-id" {})

      (not (and (integer? output-index) (not (neg? output-index))))
      (invalid assembler registry :invalid-fragment
               "a streamed tool call needs a non-negative output-index"
               {:output-index output-index})

      (and (some? tool-name) (not (and (string? tool-name)
                                       (not (str/blank? tool-name)))))
      (invalid assembler registry :invalid-fragment
               "tool-name must be a non-blank string"
               {:tool-name tool-name})

      (and existing-published
           (some? tool-name)
           (not= tool-name (:tool-name existing-published)))
      (invalid assembler registry :late-fragment-conflict
               "a completed tool call cannot change its tool name"
               {:call-id (:call-id existing-published)})

      (and existing-published
           (or (some? arguments) (map? arguments-fragment))
           (not= [(:tool-name existing-published) (:arguments existing-published)]
                 [(or tool-name (:tool-name existing-published))
                  (or arguments arguments-fragment)]))
      (invalid assembler registry :late-fragment-conflict
               "a completed tool call cannot change its payload"
               {:call-id (:call-id existing-published)})

      existing-published
      {:ok true
       :assembler assembler
       :registry registry
       :call-id (:call-id existing-published)
       :status :merged
       :event nil}

      (and existing
           (some? tool-name)
           (some? (:tool-name existing))
           (not= tool-name (:tool-name existing)))
      (invalid assembler registry :late-fragment-conflict
               "a streamed tool call cannot change its tool name"
               {:output-index output-index})

      (and existing
           (some? provider-call-id)
           (some? (:provider-call-id existing))
           (not= provider-call-id (:provider-call-id existing)))
      (invalid assembler registry :late-fragment-conflict
               "a streamed tool call cannot change its provider id"
               {:output-index output-index})

      :else
      (let [name (or tool-name (:tool-name existing))
            provider-id (or provider-call-id (:provider-call-id existing))
            fragments (cond-> (vec (:fragments existing))
                        (string? arguments-fragment)
                        (conj arguments-fragment))
            arguments (or arguments
                          (when (map? arguments-fragment) arguments-fragment)
                          (:arguments existing))
            pending (cond-> {:attempt-id attempt-id
                             :output-index output-index
                             :provider-call-id provider-id
                             :tool-name name
                             :arguments arguments
                             :fragments fragments}
                      (not complete?) (assoc :complete? false))]
        (if-not complete?
          {:ok true
           :assembler (assoc-in assembler
                                [:partials (key-of attempt-id output-index)]
                                pending)
           :registry registry
           :event nil}
          (let [parsed (partial-arguments pending parse-arguments)]
            (cond
              (nil? name)
              (invalid assembler registry :invalid-fragment
                       "a completed tool call needs a tool name"
                       {:output-index output-index})

              (not (:ok parsed))
              (invalid assembler registry :arguments-incomplete
                       "tool arguments are not complete and valid"
                       {:reason (:reason parsed)
                        :detail (select-keys parsed [:error])})

              :else
              (let [published (publish assembler registry attempt-id
                                       output-index provider-id name
                                       (:arguments parsed))]
                (if (:ok published)
                  (update published :assembler
                          (fn [a]
                            (update a :partials dissoc
                                    (key-of attempt-id output-index))))
                  published)))))))))

(defn accept-final
  "Merge a final response's complete tool calls into the registry.

  Returns `{:ok true :assembler ... :registry ... :events [...]}`.  Calls
  already published from the stream produce no second event.  Any identity
  conflict rejects the whole response and leaves the assembler unchanged."
  [assembler registry attempt-id calls]
  (let [merged (identity/merge-response registry attempt-id calls)]
    (if-not (:ok merged)
      (invalid assembler registry (:kind (:error merged))
               (:message (:error merged))
               {:identity-error (:error merged)})
      (let [events (into []
                         (keep (fn [entry]
                                 (when (= :created (:status entry))
                                   (let [call (identity/call (:registry merged)
                                                             (:call-id entry))]
                                     (contract/event
                                      {:kind :tool-call
                                       :attempt-id attempt-id
                                       :call-id (:call-id entry)
                                       :output-index (:output-index entry)
                                       :tool-name (:tool-name call)
                                       :arguments (:arguments call)})))))
                         (:calls merged))
            assembler' (reduce (fn [a entry]
                                 (let [call (identity/call (:registry merged)
                                                           (:call-id entry))]
                                   (assoc-in a
                                             [:published
                                              (key-of attempt-id
                                                      (:output-index entry))]
                                             {:call-id (:call-id entry)
                                              :provider-call-id
                                              (:provider-call-id call)
                                              :tool-name (:tool-name call)
                                              :arguments (:arguments call)})))
                               assembler
                               (:calls merged))]
        {:ok true
         :assembler assembler'
         :registry (:registry merged)
         :events events}))))

(defn forget-attempt
  "Drop an attempt's unpublished fragments and published dedup entries."
  [assembler attempt-id]
  (-> assembler
      (update :partials
              (fn [partials]
                (into {}
                      (remove (fn [[[aid _] _]] (= attempt-id aid)))
                      partials)))
      (update :published
              (fn [published]
                (into {}
                      (remove (fn [[[aid _] _]] (= attempt-id aid)))
                      published)))))
