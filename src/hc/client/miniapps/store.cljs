(ns hc.client.miniapps.store
  "IndexedDB persistence for workspace mini-apps.

  The database is scoped to the browser origin and the stores are keyed by
  `app-id`:

    apps      one record per app identity
    kv        `[app-id key]` -> value; every app sees only its own keys
    versions  `[app-id version]` -> source snapshot for rollback/export

  Removing a workspace file never deletes an `apps` or `kv` record.  Clearing
  one app deletes only that app's `kv` rows.  There is no cross-app table and
  no browser-cookie access."
  )

(def db-name "herdchestra-miniapps")
(def db-version 2)
(def max-versions 3)

(defonce db-promise (atom nil))

(defn available?
  []
  (exists? js/indexedDB))

(defn- open-db
  []
  (if-let [existing @db-promise]
    existing
    (let [promise
          (js/Promise.
           (fn [resolve reject]
             (if-not (available?)
               (reject (js/Error. "当前浏览器不提供 IndexedDB 隔离存储"))
               (let [request (.open js/indexedDB db-name db-version)]
                 (set! (.-onupgradeneeded request)
                       (fn [event]
                         (let [db (.-result request)
                               old-version (long (or (.-oldVersion event) 0))
                               names (.-objectStoreNames db)]
                           ;; Never delete an existing store during upgrade.
                           ;; A v1 schema may be incompatible, but erasing its
                           ;; records would turn an upgrade into data loss.  If
                           ;; an old store blocks normal reads, the panel shows
                           ;; the IndexedDB error and the data stays on disk.
                           (when (and (< old-version 2)
                                      (not (.contains names "apps")))
                             ;; No v1 data store exists; the v2 stores below
                             ;; are created normally.
                             nil)
                           (when-not (.contains (.-objectStoreNames db) "apps")
                             (.createObjectStore db "apps" #js {:keyPath "app_id"}))
                           (when-not (.contains (.-objectStoreNames db) "kv")
                             (let [store (.createObjectStore
                                          db "kv"
                                          #js {:keyPath #js ["app_id" "key"]})]
                               (.createIndex store "by-app" "app_id" #js {:unique false})))
                           (when-not (.contains (.-objectStoreNames db) "versions")
                             (let [store (.createObjectStore
                                          db "versions"
                                          #js {:keyPath #js ["app_id" "version"]})]
                               (.createIndex store "by-app" "app_id" #js {:unique false}))))))
                 (set! (.-onsuccess request)
                       (fn [_] (resolve (.-result request))))
                 (set! (.-onerror request)
                       (fn [_] (reject (or (.-error request)
                                           (js/Error. "打开小应用存储失败")))))
                 (set! (.-onblocked request)
                       (fn [_] (reject (js/Error. "小应用存储被另一个页面占用"))))))))]
      (reset! db-promise promise)
      (.catch promise (fn [error]
                        (reset! db-promise nil)
                        (throw error))))))

(defn- request->promise
  [request]
  (js/Promise.
   (fn [resolve reject]
     (set! (.-onsuccess request) (fn [_] (resolve (.-result request))))
     (set! (.-onerror request) (fn [_] (reject (.-error request)))))))

(defn- to-js
  "Rename `:app-id` to the valid IndexedDB key-path property `app_id`.

  Only the key path must be a valid identifier; every other kebab-case field
  is stored unchanged and converted back by `from-js`."
  [value]
  (clj->js
   (if (and (map? value) (contains? value :app-id))
     (-> value
         (assoc :app_id (:app-id value))
         (dissoc :app-id))
     value)))

(defn- from-js
  [value]
  (when (some? value)
    (let [m (js->clj value :keywordize-keys true)]
      (if (contains? m :app_id)
        (-> m
            (assoc :app-id (:app_id m))
            (dissoc :app_id))
        m))))

(defn- read-all
  [store-name index-name key]
  (-> (open-db)
      (.then
       (fn [db]
         (let [tx (.transaction db store-name "readonly")
               store (.objectStore tx store-name)
               source (if index-name (.index store index-name) store)
               request (if (some? key) (.getAll source key) (.getAll source))]
           (request->promise request))))))

(defn- read-one
  [store-name key]
  (-> (open-db)
      (.then
       (fn [db]
         (let [tx (.transaction db store-name "readonly")
               store (.objectStore tx store-name)
               request (.get store key)]
           (request->promise request))))))

(defn- write-one!
  [store-name value]
  (-> (open-db)
      (.then
       (fn [db]
         (js/Promise.
          (fn [resolve reject]
            (let [tx (.transaction db store-name "readwrite")
                  store (.objectStore tx store-name)]
              (.put store (to-js value))
              (set! (.-oncomplete tx) (fn [_] (resolve true)))
              (set! (.-onerror tx) (fn [_] (reject (.-error tx))))
              (set! (.-onabort tx) (fn [_] (reject (.-error tx)))))))))))

(defn- delete-one!
  [store-name key]
  (-> (open-db)
      (.then
       (fn [db]
         (js/Promise.
          (fn [resolve reject]
            (let [tx (.transaction db store-name "readwrite")
                  store (.objectStore tx store-name)]
              (.delete store (clj->js key))
              (set! (.-oncomplete tx) (fn [_] (resolve true)))
              (set! (.-onerror tx) (fn [_] (reject (.-error tx))))
              (set! (.-onabort tx) (fn [_] (reject (.-error tx)))))))))))

(defn- cljs-value
  [value]
  (from-js value))

;; ---------------------------------------------------------------------------
;; Apps

(defn list-apps
  []
  (-> (read-all "apps" nil nil)
      (.then (fn [values]
               (->> (js->clj (or values #js []) :keywordize-keys true)
                    (map from-js)
                    (map (fn [app] (assoc app :status (keyword (:status app)))))
                    vec)))))

(defn get-app
  [app-id]
  (-> (read-one "apps" app-id)
      (.then (fn [value]
               (when-let [app (cljs-value value)]
                 (update app :status keyword))))))

(defn put-app!
  [app]
  (write-one! "apps" app))

;; ---------------------------------------------------------------------------
;; Key/value data

(defn kv-all
  [app-id]
  (-> (read-all "kv" "by-app" app-id)
      (.then (fn [values]
               (->> (js->clj (or values #js []) :keywordize-keys true)
                    (map from-js)
                    (sort-by :key)
                    vec)))))

(defn kv-get
  [app-id key]
  (-> (read-one "kv" #js [app-id key])
      (.then cljs-value)))

(defn kv-put!
  [app-id key value]
  (write-one! "kv" {:app_id app-id
                    :key (str key)
                    :value (str value)
                    :updated-at (.toISOString (js/Date.))}))

(defn kv-delete!
  [app-id key]
  (delete-one! "kv" #js [app-id key]))

(defn kv-clear!
  [app-id]
  (-> (open-db)
      (.then
       (fn [db]
         (js/Promise.
          (fn [resolve reject]
            (let [tx (.transaction db "kv" "readwrite")
                  store (.objectStore tx "kv")
                  index (.index store "by-app")
                  request (.openCursor index app-id)]
              (set! (.-onsuccess request)
                    (fn [event]
                      (let [cursor (.-result (.-target event))]
                        (when cursor
                          (.delete cursor)
                          (.continue cursor)))))
              (set! (.-oncomplete tx) (fn [_] (resolve true)))
              (set! (.-onerror tx) (fn [_] (reject (.-error tx))))
              (set! (.-onabort tx) (fn [_] (reject (.-error tx)))))))))))

;; ---------------------------------------------------------------------------
;; Source versions

(defn versions-list
  [app-id]
  (-> (read-all "versions" "by-app" app-id)
      (.then (fn [values]
               (->> (js->clj (or values #js []) :keywordize-keys true)
                    (map from-js)
                    (sort-by :version)
                    vec)))))

(defn version-get
  [app-id version]
  (-> (read-one "versions" #js [app-id version])
      (.then cljs-value)))

(defn version-put!
  [version]
  (write-one! "versions" version))

(defn version-delete!
  [app-id version]
  (delete-one! "versions" #js [app-id version]))

(defn versions-prune!
  [app-id keep]
  (-> (versions-list app-id)
      (.then
       (fn [versions]
         (let [drop-count (max 0 (- (count versions) (long (or keep max-versions))))
               dropped (take drop-count versions)]
           (if (seq dropped)
             (-> (js/Promise.all
                  (clj->js (map #(version-delete! app-id (:version %)) dropped)))
                 (.then (fn [_] true)))
             true))))))

;; ---------------------------------------------------------------------------
;; Whole-app cleanup (only after explicit user action)

(defn delete-app!
  "Remove the app record, its data and its source versions.  The caller must
  have shown the user exactly what will be lost."
  [app-id]
  (-> (js/Promise.all
       #js [(delete-one! "apps" app-id)
            (kv-clear! app-id)
            (-> (versions-list app-id)
                (.then (fn [versions]
                         (js/Promise.all
                          (clj->js (map #(version-delete! app-id (:version %)) versions))))))])
      (.then (fn [_] true))))
