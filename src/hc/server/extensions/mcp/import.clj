(ns hc.server.extensions.mcp.import
  "Preview and apply imported MCP server configurations.

  Import is a configuration operation.  A candidate is parsed, normalized and
  reported; it is never connected and no command is started.  Every imported
  server is created disabled.  Duplicate names default to a new name, because
  overwriting an existing connection without an explicit update choice would
  silently change where a session's tools point.

  Unknown fields stay in the import report.  They are not copied into the live
  configuration and are never executed."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.mcp.config :as config]
            [hc.server.extensions.mcp.json :as json]
            [hc.server.extensions.mcp.state :as state]))

(defn- keywordize-keys
  [m]
  (when (map? m)
    (into {}
          (map (fn [[k v]] [(keyword (if (keyword? k) (name k) (str k))) v]))
          m)))

(defn- parse-edn
  [text]
  (try
    {:ok? true
     :value (edn/read-string {:readers {}
                              :default (fn [tag value]
                                         (error/fail! :MCP_IMPORT_TAGGED_VALUE
                                                      "tagged values are not accepted in an import"
                                                      {:tag tag}))}
                             text)}
    (catch Exception t
      {:ok? false :error {:code :MCP_IMPORT_PARSE
                          :message "configuration is neither JSON nor EDN"
                          :details {:reason (.getMessage ^Throwable t)}}})))

(defn parse-text
  "Parse a pasted or uploaded configuration.  JSON is tried first because
  common MCP exports are JSON; EDN is accepted for project-native files."
  [text]
  (cond
    (map? text) {:ok? true :value text}
    (not (string? text)) {:ok? false
                          :error {:code :MCP_IMPORT_PARSE
                                  :message "configuration must be text or a map"}}
    (str/blank? text) {:ok? false
                       :error {:code :MCP_IMPORT_EMPTY
                               :message "configuration is empty"}}
    :else
    (let [json-result (json/parse text)]
      (if (:ok? json-result)
        {:ok? true :value (:value json-result) :format :json}
        (let [edn-result (parse-edn text)]
          (if (:ok? edn-result)
            (assoc edn-result :format :edn)
            {:ok? false
             :error {:code :MCP_IMPORT_PARSE
                     :message "configuration is neither valid JSON nor EDN"
                     :details {:json (:error (:error json-result))
                               :edn (:error (:error edn-result))}}}))))))

(defn- candidate-entries
  [value]
  (let [root (keywordize-keys value)
        mcp-servers (keywordize-keys (:mcpServers root))
        servers (:servers root)]
    (cond
      (map? mcp-servers)
      {:entries (mapv (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])
                      mcp-servers)}

      (map? servers)
      {:entries (mapv (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])
                      (keywordize-keys servers))}

      (vector? servers)
      {:entries (mapv (fn [item]
                        (let [item (keywordize-keys item)]
                          [(or (:name item) (get item "name")) item]))
                      servers)}

      (and (some? (:name root))
           (or (some? (:url root)) (some? (:command root))
               (some? (:transport root))))
      {:entries [[(:name root) root]]}

      (vector? value)
      {:entries (mapv (fn [item]
                        (let [item (keywordize-keys item)]
                          [(or (:name item) (get item "name")) item]))
                      value)}

      :else
      {:error {:code :MCP_IMPORT_SHAPE
               :message "configuration has no mcpServers, servers or named server"
               :details {:keys (vec (sort (map str (keys root))))}}})))

(defn- preview-one
  [name raw]
  (if-not (map? raw)
    {:ok? false
     :name name
     :error {:code :MCP_IMPORT_CANDIDATE
             :message "server entry must be a map"
             :details {:name name}}}
    (let [raw (assoc (keywordize-keys raw) :name name)
          result (config/normalize raw)]
      (if-not (:ok? result)
        {:ok? false :name name :error (:error result)}
        {:ok? true
         :name (:name (:server-config result))
         :server-config (:server-config result)
         :transport-type (get-in result [:server-config :transport :type])
         :target (config/describe-target (:server-config result))
         :warnings (:warnings result)
         :unknown-fields (:unknown-fields result)
         :has-secrets? (:has-secrets? result)}))))

(defn preview
  "Parse and normalize every candidate.  Invalid candidates are reported next
  to valid ones; one bad entry does not discard the rest."
  [value]
  (let [parsed (if (string? value) (parse-text value) {:ok? true :value value})]
    (if-not (:ok? parsed)
      parsed
      (let [{:keys [entries error]} (candidate-entries (:value parsed))]
        (if error
          {:ok? false :error error}
          (let [results (mapv (fn [[name raw]] (preview-one name raw)) entries)
                candidates (filterv :ok? results)
                failed (filterv #(not (:ok? %)) results)]
            {:ok? true
             :format (:format parsed)
             :candidates candidates
             :failed failed
             :warnings (vec (concat
                             (when (and (map? (:value parsed))
                                        (:mcpServers (keywordize-keys (:value parsed)))
                                        (:servers (keywordize-keys (:value parsed))))
                               [{:code :MCP_IMPORT_MULTIPLE_ROOTS
                                 :message "both mcpServers and servers are present; mcpServers was used"}])
                             (mapcat :warnings candidates)))}))))))

(defn import!
  "Apply previewed candidates.

  `opts` supports `:on-duplicate` (`:rename` default, `:skip` or `:update`).
  A failed candidate leaves the catalog at its last successful state; the
  result lists exactly what was created, updated, skipped or refused."
  ([catalog previewed]
   (import! catalog previewed {}))
  ([catalog previewed opts]
   (let [catalog (catalog/ensure catalog)
         on-duplicate (or (:on-duplicate opts) :rename)]
    (if-not (:ok? previewed)
      {:ok? false
       :catalog catalog
       :error (:error previewed)}
      (if-not (#{:rename :skip :update} on-duplicate)
        {:ok? false
         :catalog catalog
         :error {:code :MCP_IMPORT_DUPLICATE_POLICY
                 :message "duplicate policy must be rename, skip or update"
                 :details {:on-duplicate on-duplicate}}}
        (loop [catalog catalog
               remaining (seq (:candidates previewed))
               created []
               updated []
               skipped []
               failed []]
          (if-not remaining
            {:ok? true
             :catalog catalog
             :created created
             :updated updated
             :skipped skipped
             :failed (into failed (:failed previewed))
             :partial? (boolean (or (seq failed) (seq (:failed previewed))))}
            (let [candidate (first remaining)
                  existing (catalog/server-by-name catalog (:name candidate))]
              (if (and existing (= on-duplicate :skip))
                (recur catalog (next remaining) created updated
                       (conj skipped {:name (:name candidate) :id (:id existing)})
                       failed)
                (let [source {:kind :import
                              :uri (:uri (:source candidate))
                              :unknown-fields (:unknown-fields candidate)
                              :has-secrets? (:has-secrets? candidate)}
                      result (cond
                               (and existing (= on-duplicate :update))
                               (let [updated (state/update-server! catalog (:id existing)
                                                                   (:server-config candidate)
                                                                   {:source source})]
                                 updated)

                               existing
                               (let [name (catalog/unique-server-name catalog (:name candidate))
                                     config (assoc-in (:server-config candidate)
                                                      [:name] name)]
                                 (state/add-server! catalog config
                                                    {:source source}))

                               :else
                               (state/add-server! catalog (:server-config candidate)
                                                  {:source source}))]
                  (if (:ok? result)
                    (recur (:catalog result)
                           (next remaining)
                           (if (and existing (= on-duplicate :update))
                             created
                             (conj created {:name (get-in result [:server :name])
                                            :id (get-in result [:server :id])
                                            :enabled? false}))
                           (if (and existing (= on-duplicate :update))
                             (conj updated {:name (get-in result [:server :name])
                                            :id (get-in result [:server :id])
                                            :enabled? (get-in result [:server :enabled?])})
                             updated)
                           skipped
                           failed)
                    (recur catalog
                           (next remaining)
                           created updated skipped
                           (conj failed {:name (:name candidate)
                                         :error (:error result)})))))))))))))
