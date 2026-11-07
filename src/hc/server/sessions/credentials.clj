(ns hc.server.sessions.credentials
  "Secure multi-key credential lifecycle for one connection.

  Authority: AGENTS.md §7.4 旅程 1.2–1.3（引导与选模型）.  This namespace connects the pure
  connection policy (`hc.server.sessions.connections`) to a `SecretStore`.
  It is the only place that handles a raw secret value: the store receives
  it, the connection state receives only `:secret-ref` and `:masked-key`.

  No function logs a secret or returns one from `apply-credentials!`.
  `resolve-credential` returns one raw secret to the model adapter for one
  request; the caller must not persist or log it."
  (:require [clojure.string :as str]
            [hc.server.sessions.connections :as connections]
            [hc.server.sessions.secrets :as secrets]))

(defn- error
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- connection-id
  [connection]
  (or (:connection-id connection) (:id connection)))

(defn- secret-id
  [connection-id key-id]
  (str connection-id "." key-id))

(defn- valid-key-id?
  [value]
  (and (string? value)
       (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,63}" value))))

(defn- credential-input
  [connection key-id entry]
  (let [entry (if (map? entry) entry {})
        secret (:secret entry)
        ref (secrets/put-secret! (:store connection)
                                 (secret-id (connection-id connection) key-id)
                                 secret)]
    (if-not (:ok? ref)
      ref
      (connections/credential-from-secret
       {:credential-id key-id
        :alias (or (:alias entry) key-id)
        :secret secret
        :secret-ref (:secret-ref ref)
        :enabled? (:enabled? entry)
        :priority (:priority entry)}))))

(defn apply-credentials!
  "Apply raw credential additions and removals to CONNECTION.

  Options:

    :store       a `SecretStore`
    :credentials vector of `{:key-id :alias :secret :enabled? :priority}`
    :clear       vector of key IDs to remove
    :now         epoch ms

  Returns `{:ok? true :connection c :deleted-refs [...]}` or a structured
  error.  Raw secrets never appear in the result or in CONNECTION.

  The three-argument form `(store connection opts)` is the assembly-friendly
  arity used by app settings."
  ([connection {:keys [store credentials clear now]}]
   (let [cid (connection-id connection)
         credentials (vec (or credentials []))
         clear (vec (or clear []))
         invalid (or (some (fn [entry]
                             (when-not (valid-key-id? (:key-id entry))
                               {:code :CREDENTIAL_ID_INVALID
                                :message "key-id must match [A-Za-z0-9._-]{0,63}"
                                :details {:key-id (:key-id entry)}}))
                           credentials)
                     (some (fn [entry]
                             (when-not (and (string? (:secret entry))
                                            (not (str/blank? (:secret entry))))
                               {:code :CREDENTIAL_SECRET_REQUIRED
                                :message "a new credential needs a non-blank secret"
                                :details {:key-id (:key-id entry)}}))
                           credentials))]
     (cond
       (not (satisfies? secrets/SecretStore store))
       (error :SECRET_STORE_REQUIRED
              "credential changes need a SecretStore" {})

       (str/blank? (str cid))
       (error :CONNECTION_ID_INVALID
              "connection needs a non-blank connection-id" {})

       invalid
       (error (:code invalid) (:message invalid) (:details invalid))

       :else
       (let [connection (or connection {})
             ;; Remove credentials first, then add/rotate.  This keeps a
             ;; clear+set in the same payload deterministic.
             [connection removed-refs]
             (reduce
              (fn [[connection refs] key-id]
                (if-let [credential (get-in connection [:credentials key-id])]
                  (let [removed (connections/remove-credential
                                 connection key-id now)]
                    (if (:ok? removed)
                      [(:connection removed) (conj refs (:secret-ref credential))]
                      [connection refs]))
                  [connection refs]))
              [connection []]
              clear)
             ;; Delete cleared secrets before adding/rotating so a
             ;; clear+set of the same key does not delete the new value.
             [connection delete-errors]
             (reduce
              (fn [[connection errors] ref]
                (if (nil? ref)
                  [connection errors]
                  (let [deleted (secrets/delete-secret! store ref)]
                    [connection
                     (if (:ok? deleted)
                       errors
                       (conj errors (:error deleted)))])))
              [connection []]
              removed-refs)
             [connection added-refs errors]
             (reduce
              (fn [[connection refs errors] entry]
                (let [key-id (:key-id entry)
                      prepared (credential-input
                                {:store store :connection-id cid}
                                key-id entry)]
                  (if-not (:ok? prepared)
                    [connection refs (conj errors (:error prepared))]
                    (let [credential (:credential prepared)
                          existing? (contains? (:credentials connection) key-id)
                          result (if existing?
                                   (connections/update-credential
                                    connection key-id
                                    (select-keys credential
                                                 [:alias :enabled? :priority
                                                  :secret-ref :masked-key])
                                    now)
                                   (connections/add-credential
                                    connection credential now))]
                      (if (:ok? result)
                        [(:connection result)
                         (conj refs (:secret-ref credential))
                         errors]
                        [connection refs (conj errors (:error result))])))))
              [connection [] []]
              credentials)]
         (let [errors (vec (concat delete-errors errors))]
           {:ok? (empty? errors)
            :connection connection
            :added-refs added-refs
            :deleted-refs removed-refs
            :errors errors})))))
  ([store connection opts]
   (apply-credentials! connection (assoc opts :store store))))

(defn apply-settings-credentials!
  "Apply the client settings payload to CONNECTION.

  The client sends key metadata in `:keys` and one-time raw values in
  `:credentials`; `:clear-credentials` lists key IDs to remove.  This helper
  merges metadata by key-id so app settings does not accidentally treat a
  metadata-only `:keys` entry as a secret."
  [store connection payload now]
  (let [key-meta (into {}
                       (map (juxt :key-id identity))
                       (or (:keys payload) []))
        credentials (mapv (fn [credential]
                            (merge (get key-meta (:key-id credential) {})
                                   credential))
                          (or (:credentials payload) []))
        clear (vec (or (:clear-credentials payload)
                       (keep (fn [key]
                               (when (or (:clear-secret? key)
                                         (= :clear (:auth-state key)))
                                 (:key-id key)))
                             (or (:keys payload) []))))]
    (let [result (apply-credentials! connection
                                     {:store store
                                      :credentials credentials
                                      :clear clear
                                      :now now})
          credential-ids (set (map :key-id credentials))
          cleared-ids (set clear)
          ;; Metadata edits for existing keys do not carry a raw secret.
          ;; Apply them after the secret lifecycle so alias, enablement, and
          ;; priority changes are not silently discarded.
          metadata-keys (keep (fn [key]
                                (let [key-id (:key-id key)
                                      auth-state (keyword (:auth-state key))]
                                  (when (and (string? key-id)
                                             (not (contains? credential-ids key-id))
                                             (not (contains? cleared-ids key-id))
                                             (not (contains? #{:set :clear}
                                                             auth-state)))
                                    key)))
                              (or (:keys payload) []))
          [connection metadata-errors]
          (reduce (fn [[connection errors] key]
                    (let [key-id (:key-id key)]
                      (if-not (contains? (:credentials connection) key-id)
                        ;; A new key without a secret is not a credential yet.
                        [connection errors]
                        (let [changes (select-keys key
                                                   [:alias :enabled? :priority])
                              updated (if (seq changes)
                                        (connections/update-credential
                                         connection key-id changes now)
                                        {:ok? true :connection connection})]
                          (if (:ok? updated)
                            [(:connection updated) errors]
                            [connection (conj errors (:error updated))])))))
                  [(:connection result) []]
                  metadata-keys)
          errors (vec (concat (:errors result) metadata-errors))
          ok? (and (:ok? result) (empty? errors))
          result (assoc result
                        :ok? ok?
                        :connection connection
                        :errors errors)]
      ;; These are registry display fields, not model-adapter config.
      ;; The resolver derives order from :credentials when absent.
      (cond-> result
        (:ok? result)
        (update :connection dissoc :credential-order :updated-at)

        (and (not ok?) (nil? (:error result)))
        (assoc :error
               (or (first metadata-errors)
                   {:code :CREDENTIAL_UPDATE_FAILED
                    :message "credential metadata could not be updated"}))))))

(defn delete-connection-credentials!
  "Remove every credential and its stored secret from CONNECTION.

  Used when a connection is deleted.  The returned connection has an empty
  credential table; the caller normally discards it.  A failed secret
  deletion is reported in `:errors` and leaves `:ok? false`."
  [store connection now]
  (apply-credentials!
   connection
   {:store store
    :clear (vec (keys (or (:credentials connection) {})))
    :now now}))

(defn resolve-credential
  "Choose one eligible credential and resolve its raw secret.

  Returns `{:ok? true :credential c :secret s :connection c'}`.  The raw
  secret is for one model request only.  The updated connection records the
  key's use count; callers may persist it."
  ([store connection now] (resolve-credential store connection now {}))
  ([store connection now options]
   (let [connection (-> connection
                        (assoc :connection-id
                               (or (:connection-id connection)
                                   (:id connection)))
                        (assoc :rotation-policy
                               (or (:rotation-policy connection)
                                   (:key-strategy connection)
                                   :priority))
                        (update :credential-order
                                #(or % (->> (:credentials connection)
                                            keys
                                            (sort-by str)
                                            vec))))
         chosen (connections/choose-credential connection now options)]
     (if-not (:ok? chosen)
       chosen
       (let [ref (get-in chosen [:credential :secret-ref])
             secret (secrets/get-secret store ref)]
         (if-not (:ok? secret)
           (assoc secret
                  :connection (:connection chosen)
                  :credential (:credential chosen))
           {:ok? true
            :credential (:credential chosen)
            :secret (:secret secret)
            :connection (:connection chosen)}))))))
