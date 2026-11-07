(ns hc.server.model-port.call-identity
  "Project-owned tool call identity.

  Providers are not trusted to supply globally unique call ids, and two
  legitimate calls may carry identical tool names and arguments.  Identity is
  therefore anchored on `[attempt-id output-index]`; a provider id is only an
  alias used when the output index is absent.  The adapter allocates one
  project `call-id` when the first complete tool request forms and keeps the
  mapping to provider id, attempt id, and output index.

  Project call ids are globally unique across model and tool identities:
  they are namespaced by the attempt, which the caller owns and never reuses.
  An index-backed call is `<attempt-id>/tool-<output-index>`; an alias-only
  call is `<attempt-id>/tool-alias-<n>`.  An allocated project id can
  therefore never collide with an Owner model call of the same spelling.

  `register-call` is idempotent for the same identity and payload.  A repeated
  complete callback and the final response merge into one call; a changed
  payload is an explicit conflict, never a silent second request.

  All functions are pure.  A caller replaces its registry with the returned
  one only when `:ok` is true."
  (:require [clojure.string :as str]))

(def registry-version 1)

(defn new-registry
  "An empty identity registry."
  []
  {:v registry-version
   :next-id 0
   :calls {}
   :by-output {}
   :by-provider {}
   :by-attempt {}})

(defn- error [kind message data]
  {:ok false
   :error (merge {:kind kind :message message} data)})

(defn- valid-index?
  [value]
  (and (integer? value) (not (neg? value))))

(defn- normalize-provider-id
  [value]
  (cond
    (nil? value) nil
    (and (string? value) (str/blank? value)) nil
    (string? value) value
    :else ::invalid))

(defn- normalize-call
  [{:keys [attempt-id output-index provider-call-id tool-name arguments
           complete? call-id] :as raw}]
  (let [provider-id (normalize-provider-id provider-call-id)
        index (or output-index (:index raw))]
    (cond
      (nil? attempt-id)
      (error :invalid-call "a tool call needs an attempt-id" {:call raw})

      (not (and (string? tool-name) (not (str/blank? tool-name))))
      (error :invalid-call "a tool call needs a non-blank tool name" {:call raw})

      (not (map? arguments))
      (error :invalid-call "tool arguments must be a complete map" {:call raw})

      (false? complete?)
      (error :invalid-call "incomplete tool arguments are not published" {:call raw})

      (and (some? index) (not (valid-index? index)))
      (error :invalid-call "output-index must be a non-negative integer"
             {:call raw :output-index index})

      (= ::invalid provider-id)
      (error :invalid-call "provider-call-id must be a string or nil" {:call raw})

      (and (some? call-id)
           (not (and (string? call-id) (not (str/blank? call-id)))))
      (error :invalid-call "call-id must be a non-blank string" {:call raw})

      :else
      {:ok true
       :call {:attempt-id attempt-id
              :output-index index
              :provider-call-id provider-id
              :tool-name tool-name
              :arguments arguments
              :call-id call-id}})))

(defn- lookup-identity
  "Find one existing call by output index, then by provider alias.

  A provider id shared by more than one call in the same attempt is ambiguous.
  Without an output index, the adapter must not guess which call the provider
  means; it returns an explicit conflict instead."
  [registry attempt-id output-index provider-id]
  (cond
    (some? output-index)
    (if-let [call-id (get-in registry [:by-output [attempt-id output-index]])]
      {:call (get-in registry [:calls call-id])}
      nil)

    (some? provider-id)
    (let [call-ids (get-in registry [:by-provider [attempt-id provider-id]])]
      (cond
        (nil? call-ids) nil
        (= 1 (count call-ids))
        {:call (get-in registry [:calls (first call-ids)])}
        :else
        {:ambiguous (vec (sort-by str call-ids))}))

    :else nil))

(defn- same-payload?
  [existing call]
  (and (= (:tool-name existing) (:tool-name call))
       (= (:arguments existing) (:arguments call))
       (or (nil? (:provider-call-id existing))
           (nil? (:provider-call-id call))
           (= (:provider-call-id existing) (:provider-call-id call)))))

(defn- same-content?
  "A retry may present a new provider id for the same business call."
  [existing call]
  (and (= (:tool-name existing) (:tool-name call))
       (= (:arguments existing) (:arguments call))))

(defn- attach-alias
  [registry call]
  (let [attempt-id (:attempt-id call)
        call-id (:call-id call)
        output-index (:output-index call)
        provider-id (:provider-call-id call)]
    (cond-> registry
      (some? output-index)
      (assoc-in [:by-output [attempt-id output-index]] call-id)

      (some? provider-id)
      (update-in [:by-provider [attempt-id provider-id]]
                 (fnil conj #{}) call-id)

      true
      (update-in [:by-attempt attempt-id] (fnil conj #{}) call-id))))

(defn- allocate-call-id
  "Allocate one project call id inside ATTEMPT-ID's namespace.

  The attempt is the uniqueness boundary: an index-backed call carries its
  output index, so a replayed identity always resolves to the same id.  An
  alias-only call uses a distinct `tool-alias-` segment so it can never
  collide with an index-backed call.  `:next-id` counts every allocation,
  including index-backed ones, and is never reset by `forget-attempt`."
  [registry attempt-id output-index]
  (let [n (inc (:next-id registry))
        registry (assoc registry :next-id n)]
    [registry
     (if (valid-index? output-index)
       (str attempt-id "/tool-" output-index)
       (str attempt-id "/tool-alias-" n))]))

(defn register-call
  "Register one complete tool request and return
  `{:ok true :registry next :call-id id :status :created|:merged}`.

  Identity is `[attempt-id output-index]` when the index is known, otherwise
  `[attempt-id provider-call-id]` when that alias is unambiguous.  Passing the
  previous `:call-id` explicitly keeps a business call stable across retries.

  A repeated identity with the same payload merges.  A changed payload, a
  changed provider id, or an ambiguous provider alias returns `:ok false` and
  leaves the registry untouched."
  [registry call]
  (let [normalized (normalize-call call)]
    (if-not (:ok normalized)
      (assoc normalized :registry registry)
      (let [{:keys [attempt-id output-index provider-call-id
                    tool-name arguments call-id]} (:call normalized)
            found (lookup-identity registry attempt-id output-index provider-call-id)
            by-id (when (some? call-id) (get-in registry [:calls call-id]))]
        (cond
          (:ambiguous found)
          (-> (error :provider-id-ambiguous
                     "provider call id maps to more than one call; an output index is required"
                     {:attempt-id attempt-id
                      :provider-call-id provider-call-id
                      :candidates (:ambiguous found)})
              (assoc :registry registry))

          (and by-id found (not= (:call-id by-id) (:call-id (:call found))))
          (-> (error :call-identity-conflict
                     "call-id and provider identity name different calls"
                     {:call-id call-id
                      :found-call-id (:call-id (:call found))})
              (assoc :registry registry))

          by-id
          (let [candidate (assoc (:call normalized) :call-id call-id)]
            (if (same-content? by-id candidate)
              {:ok true
               :registry (attach-alias registry candidate)
               :call-id call-id
               :status :merged}
              (-> (error :call-identity-conflict
                         "call-id was reused with a different payload"
                         {:call-id call-id
                          :existing (select-keys by-id [:tool-name :arguments
                                                        :provider-call-id])})
                  (assoc :registry registry))))

          (:call found)
          (let [existing (:call found)
                candidate (assoc (:call normalized) :call-id (:call-id existing))]
            (cond
              (not (same-payload? existing candidate))
              (-> (error :call-identity-conflict
                         "the same identity arrived with a different payload"
                         {:call-id (:call-id existing)
                          :existing (select-keys existing [:tool-name :arguments
                                                           :provider-call-id])})
                  (assoc :registry registry))

              (and (some? call-id) (not= call-id (:call-id existing)))
              (-> (error :call-identity-conflict
                         "the identity already belongs to another call-id"
                         {:call-id call-id :existing-call-id (:call-id existing)})
                  (assoc :registry registry))

              :else
              {:ok true
               :registry (attach-alias registry candidate)
               :call-id (:call-id existing)
               :status :merged}))

          :else
          (let [[registry' call-id']
                (if (some? call-id)
                  [registry call-id]
                  (allocate-call-id registry attempt-id output-index))
                candidate (assoc (:call normalized) :call-id call-id')
                registry'' (-> registry'
                               (assoc-in [:calls call-id'] candidate)
                               (attach-alias candidate))]
            {:ok true
             :registry registry''
             :call-id call-id'
             :status :created}))))))

(defn- used-output-indices
  [registry attempt-id]
  (into #{}
        (keep (fn [[[call-attempt-id index] _]]
                (when (= call-attempt-id attempt-id)
                  index)))
        (:by-output registry)))

(defn- next-free-index
  [used]
  (loop [index 0]
    (if (contains? used index)
      (recur (inc index))
      index)))

(defn merge-response
  "Merge the complete tool requests of one final response.

  A call that does not name an output index receives the next free index for
  the attempt, not its position in the response.  A streamed call may already
  occupy an earlier index, so position and identity are not the same thing.
  Every call is registered in order; any conflict rejects the whole response
  and returns the original registry.  Returns
  `{:ok true :registry ... :calls [...] :new-call-ids [...]}`."
  [registry attempt-id calls]
  (when-not (sequential? calls)
    (throw (ex-info "final response tool calls must be sequential"
                    {:error/kind :invalid-request
                     :reason :tool-calls-not-sequential})))
  (loop [remaining (seq calls)
         used (used-output-indices registry attempt-id)
         registry registry
         results []
         new-ids []]
    (if-let [call (first remaining)]
      (let [explicit? (or (some? (:output-index call)) (some? (:index call)))
            effective-index (if explicit?
                              (or (:output-index call) (:index call))
                              (next-free-index used))
            indexed (assoc call :output-index effective-index)
            result (register-call registry (assoc indexed :attempt-id attempt-id))]
        (if (:ok result)
          (let [effective-index (or (:output-index indexed)
                                    (:index indexed))]
            (recur (next remaining)
                   (conj used effective-index)
                   (:registry result)
                   (conj results {:call-id (:call-id result)
                                  :status (:status result)
                                  :output-index effective-index})
                   (cond-> new-ids
                     (= :created (:status result)) (conj (:call-id result)))))
          (assoc result :registry registry)))
      {:ok true
       :registry registry
       :calls results
       :new-call-ids new-ids})))

(defn call
  "Look up one call record by project call-id."
  [registry call-id]
  (get-in registry [:calls call-id]))

(defn call-ids-for-attempt
  "All project call ids seen for one attempt, sorted for stable output."
  [registry attempt-id]
  (vec (sort-by str (get-in registry [:by-attempt attempt-id] #{}))))

(defn forget-attempt
  "Remove every call and alias owned by ATTEMPT-ID.

  Used when the port prunes a terminal attempt so the global registry stays
  bounded.  Other attempts' identities are left untouched."
  [registry attempt-id]
  (let [ids (set (get-in registry [:by-attempt attempt-id] #{}))
        by-output (into {}
                        (remove (fn [[[call-attempt-id _] _]]
                                  (= call-attempt-id attempt-id)))
                        (:by-output registry))
        by-provider
        (reduce-kv
         (fn [result [call-attempt-id provider-id] call-ids]
           (if (= call-attempt-id attempt-id)
             result
             (let [remaining (reduce disj (set call-ids) ids)]
               (if (seq remaining)
                 (assoc result [call-attempt-id provider-id] remaining)
                 result))))
         {}
         (:by-provider registry))]
    (-> registry
        (update :calls #(apply dissoc % ids))
        (assoc :by-attempt (dissoc (:by-attempt registry) attempt-id)
               :by-output by-output
               :by-provider by-provider))))

(defn provider-ids
  "Provider aliases registered for one attempt."
  [registry attempt-id]
  (->> (get-in registry [:by-provider])
       (keep (fn [[[aid provider-id] call-ids]]
               (when (= attempt-id aid)
                 [provider-id (vec (sort-by str call-ids))])))
       (sort-by (comp str first))
       (vec)))
