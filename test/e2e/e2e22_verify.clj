;; E2E-22 verify: read-back verification for the cross-device migration spec.
;;
;; Reads one restored store plus its extension catalog and prints a single
;; JSON object with the facts the spec asserts on:
;;
;;   - session records: ids, status, deleted-at, drafts, model-binding
;;   - materials memories: id -> status
;;   - extension catalog MCP servers: id -> enabled?
;;   - app-config backup section (targets must be absent on the destination)
;;
;;   clojure -M:server -i test/e2e/e2e22_verify.clj --eval \
;;     '(e2e22-verify/run "<db-path>" "<extensions-root>")'
;;
;; extensions-root may be nil/"" to skip the catalog read.
(ns e2e22-verify
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.extensions.durable :as extensions-durable]
            [hc.server.storage :as storage]))

(set! *warn-on-reflection* true)

(defn- docs-of
  [export partition kind]
  (->> (get-in export [:partitions partition :docs] [])
       (filter #(= kind (:kind %)))
       (map :doc)
       vec))

(defn- session-summary
  [doc]
  {:sessionId (:session-id doc)
   :title (:title doc)
   :status (:status doc)
   :deletedAt (:deleted-at doc)
   :draftIds (vec (keys (:drafts doc)))
   :draftContents (into {} (map (fn [[k v]] [k (:content v)])) (:drafts doc))
   :modelBinding (into {}
                       (map (fn [[k v]]
                              [(keyword (str/replace (name k) #"-([a-z])"
                                                     (fn [[_ c]] (str/upper-case c))))
                               v]))
                       (select-keys (:model-binding doc)
                                    [:connection-id :connection-name
                                     :model-entry-id :provider-model-id
                                     :display-name :effective-base-url]))
   :pinned (boolean (:pinned? doc))
   :messageCount (count (:message-order doc))})

(defn- memory-summary
  [doc]
  {:id (:id doc)
   :status (:status doc)
   :body (:body doc)
   :sourceKind (:source-kind doc)
   :source (:source doc)})

(defn- mcp-summary
  [catalog]
  (into {}
        (map (fn [[id server]]
               [id {:enabled? (boolean (:enabled? server))
                    :name (:name server)
                    :tools (into {} (map (fn [[tn t]]
                                           [tn {:enabled? (boolean (:enabled? t))
                                                :review-required?
                                                (boolean (:review-required? t))}]))
                                         (:tools server))}]))
        (get-in catalog [:mcp :servers] {})))

(defn run
  "Print the verification map for DB-PATH as one JSON line."
  [db-path extensions-root]
  (let [store (storage/open-store {:path (str db-path)
                                   :create? false
                                   :durability :sync})
        export (try
                 (storage/export-state store {})
                 (finally
                   (storage/close-store! store)))
        sessions (mapv session-summary (docs-of export "sessions" :session-record))
        config (or (first (docs-of export "config" :app-config)) {})
        memories (mapv memory-summary (docs-of export "materials" :materials-memory))
        catalog (when (and extensions-root (not (str/blank? (str extensions-root))))
                  (let [loaded (extensions-durable/load!
                                (extensions-durable/open (str extensions-root)))]
                    (when (:ok? loaded)
                      (:catalog loaded))))
        mcp (when catalog (mcp-summary catalog))]
    (println
     (json/write-str
      {:ok true
       :sessions sessions
       :sessionIndex (or (some-> (first (docs-of export "sessions" :session-index))
                                 :ids vec)
                         [])
       :memories memories
       :mcp mcp
       :backup (select-keys config [:backup])
       :configKeys (vec (keys config))}))))
