(ns hc.client.conversation.attachments.storage
  "Per-session persistence for the pre-send attachment queue.

  The queue is separate from the workbench draft, but it must survive a
  session switch and a reload.  Stored items never contain File handles,
  previews, model configs, passwords, or bytes.  A card that was still
  uploading when the page closed is restored as an explicit
  `:UPLOAD_INTERRUPTED` failure, never as ready and never silently dropped.

  The in-memory cache is always updated first; localStorage is best effort.
  If storage is unavailable the queue still survives a session switch in this
  tab, and the caller can show the unsaved warning.  The memory cache is a
  bounded LRU: a long-lived tab must not retain every session it ever opened."
  (:require [clojure.edn :as edn]
            [hc.client.conversation.attachments :as attachments]))

(def key-prefix "hc.attachments.v1")
(def client-id-key "hc.attachments.client-id")

(def max-cache-entries
  "Maximum number of session/client queues kept in memory."
  20)

(def max-cache-bytes
  "Maximum estimated attachment bytes kept in memory (64 MiB)."
  (* 64 1024 1024))

(defonce ^:private cache-tick (atom 0))
(defonce ^:private memory-cache (atom {}))
(defonce ^:private fallback-client-id (atom nil))

(defn- queue-bytes
  [queue]
  (+ 1024
     (reduce + 0
             (keep (fn [item]
                     (when (number? (:size item))
                       (:size item)))
                   (:items queue)))))

(defn- cache-bytes
  [cache]
  (reduce + 0 (map :bytes (vals cache))))

(defn- touch-entry
  [queue]
  {:queue queue
   :tick (swap! cache-tick inc)
   :bytes (queue-bytes queue)})

(defn- trim-cache
  [cache]
  (loop [cache cache]
    (if (and (<= (count cache) max-cache-entries)
             (<= (cache-bytes cache) max-cache-bytes))
      cache
      (if-let [[oldest-key _] (first (sort-by (comp :tick val) cache))]
        (recur (dissoc cache oldest-key))
        cache))))

(defn cache-stats
  "Current LRU size, for diagnostics and tests."
  []
  {:entries (count @memory-cache)
   :bytes (cache-bytes @memory-cache)
   :max-entries max-cache-entries
   :max-bytes max-cache-bytes})

(defn- fresh-client-id
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str "tab-" (js/Date.now) "-" (js/Math.random))))

(defn client-id
  "Stable identity for this browser tab.

  `sessionStorage` is per tab, so two tabs on the same session keep separate
  pre-send queues.  A reload in the same tab keeps the same identity.  When
  storage is unavailable, the in-memory fallback still separates this tab for
  the current page lifetime."
  []
  #?(:cljs
     (try
       (if (exists? js/sessionStorage)
         (or (.getItem js/sessionStorage client-id-key)
             (let [id (fresh-client-id)]
               (.setItem js/sessionStorage client-id-key id)
               id))
         (or @fallback-client-id
             (reset! fallback-client-id (fresh-client-id))))
       (catch :default _
         (or @fallback-client-id
             (reset! fallback-client-id (fresh-client-id)))))
     :clj
     (or @fallback-client-id
         (reset! fallback-client-id (fresh-client-id)))))

(defn key-for
  ([session-id]
   (key-for session-id (client-id)))
  ([session-id client-id]
   (str key-prefix ":" (or client-id "default") ":" (or session-id "default"))))

(defn- legacy-key-for
  [session-id]
  (str key-prefix ":" (or session-id "default")))

(def ^:private stored-keys
  [:id :attachment-id :name :mime :size :source :status :ready? :excluded?
   :local-copy? :blob-id :fingerprint :last-modified :created-at :detail
   :error :media :extraction :prepared :prepared-status :workspace
   :workspace-verified? :declared-mime :sniffed-mime :mime-mismatch?
   :route :preferred-route :send-route :send-text :extractable? :url])

(defn- stored-item
  [item]
  (-> (select-keys item stored-keys)
      ;; A File handle is never serializable and must not be stored.
      (dissoc :file :preview)))

(defn- restore-item
  [item]
  (let [raw-status (keyword (:status item))
        interrupted? (contains? #{:adding :uploading :preparing} raw-status)
        local? (and (true? (:local-copy? item))
                    (attachments/canonical-blob-id? (:blob-id item)))
        status (cond
                 interrupted? :failed
                 (and (= :ready raw-status) (not local?)) :needs-choice
                 :else raw-status)
        ready? (and (not interrupted?)
                    (= :ready status)
                    local?
                    (true? (:ready? item)))]
    (assoc item
           :status status
           :ready? ready?
           :excluded? (boolean (:excluded? item))
           :local-copy? local?
           :preferred-route (when (some? (:preferred-route item))
                              (keyword (:preferred-route item)))
           :detail (cond
                     interrupted? "上传中断，请重新选择文件"
                     (and (= :ready raw-status) (not local?))
                     "缺少可核对的应用内副本"
                     :else (:detail item))
           :error (if interrupted?
                    {:code :UPLOAD_INTERRUPTED
                     :message "上传中断，请重新选择文件"}
                    (:error item)))))

(defn- bounded-limits
  [value]
  (let [base attachments/default-limits
        positive (fn [v] (when (and (integer? v) (pos? v)) v))
        max-items (min (:max-items base)
                       (or (positive (:max-items value)) (:max-items base)))
        max-bytes (min (:max-bytes base)
                       (or (positive (:max-bytes value)) (:max-bytes base)))
        stored-pages (positive (:max-pages value))
        product-pages (:max-pages base)]
    {:max-items max-items
     :max-bytes max-bytes
     :max-pages (cond
                  (and product-pages stored-pages) (min product-pages stored-pages)
                  product-pages product-pages
                  stored-pages stored-pages
                  :else nil)}))

(defn- item-seq
  [id]
  (when-let [match (re-matches #"att-(\d+)" (str id))]
    #?(:clj (Long/parseLong (second match))
       :cljs (js/parseInt (second match) 10))))

(defn encode
  "Encode a queue as EDN.  Returns a string."
  [queue]
  (pr-str {:v 2
           :next-seq (long (or (:next-seq queue) 0))
           :limits (bounded-limits (:limits queue))
           :pending-send (when-let [pending (:pending-send queue)]
                           {:ids (vec (or (:ids pending) []))
                            :commands (vec (or (:commands pending) []))})
           :tombstones (vec (or (:tombstones queue) []))
           :items (mapv stored-item (:items queue))}))

(defn- decode-value
  [value]
  (when (and (map? value)
             (sequential? (:items value)))
    (let [items (mapv restore-item (:items value))
          stored-next (long (or (:next-seq value) 0))
          highest (reduce (fn [n item] (max n (or (item-seq (:id item)) 0)))
                          0 items)]
      {:items items
       :next-seq (max stored-next highest)
       :limits (bounded-limits (:limits value))
       :pending-send (when (map? (:pending-send value))
                       {:ids (vec (or (get-in value [:pending-send :ids]) []))
                        :commands (vec (or (get-in value [:pending-send :commands]) []))})
       :tombstones (set (or (:tombstones value) []))
       :model nil})))

(defn decode
  "Decode one stored queue.  Returns a queue or nil for corrupt input."
  [text]
  (try
    (some-> (edn/read-string text) decode-value)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- browser-store
  []
  #?(:cljs
     (try
       (when (exists? js/localStorage) js/localStorage)
       (catch :default _ nil))
     :clj nil))

(defn load-queue
  "Return `{:found? bool :queue ...}` for SESSION-ID and this tab's client ID.

  Memory wins over localStorage so an upload finishing after a session switch
  is visible immediately.  A corrupt stored value is reported as not found;
  the caller may fall back to the server draft and show a warning."
  ([session-id]
   (load-queue session-id {}))
  ([session-id {explicit-client-id :client-id}]
   (let [cid (or explicit-client-id (client-id))
         key (key-for session-id cid)
         legacy-key (legacy-key-for session-id)]
     (if-let [entry (get @memory-cache key)]
       (do
         (swap! memory-cache
                (fn [cache]
                  (trim-cache (assoc cache key (touch-entry (:queue entry))))))
         {:found? true :queue (:queue entry)})
       #?(:cljs
          (if-let [store (browser-store)]
            (try
              (let [text (or (.getItem store key)
                             (.getItem store legacy-key))]
                (if-let [queue (decode text)]
                  (do
                    (swap! memory-cache
                           (fn [cache]
                             (trim-cache (assoc cache key (touch-entry queue)))))
                    ;; Move a legacy per-session queue under this tab's identity.
                    (when (and (nil? (.getItem store key)) text)
                      (try (.setItem store key text)
                           (.removeItem store legacy-key)
                           (catch :default _ nil)))
                    {:found? true :queue queue})
                  (if (nil? text)
                    {:found? false}
                    {:found? false :error :invalid-stored-queue})))
              (catch :default error
                {:found? false :error :storage-unavailable
                 :message (str error)}))
            {:found? false :error :storage-unavailable})
          :clj
          {:found? false})))))

(defn save-queue!
  "Store SESSION-ID's queue under this tab's client identity."
  ([session-id queue]
   (save-queue! session-id queue {}))
  ([session-id queue {explicit-client-id :client-id}]
   (let [key (key-for session-id (or explicit-client-id (client-id)))
         queue (or queue (attachments/initial))]
     (swap! memory-cache
            (fn [cache]
              (trim-cache (assoc cache key (touch-entry queue)))))
     #?(:cljs
        (if-let [store (browser-store)]
          (try
            (.setItem store key (encode queue))
            {:ok? true}
            (catch :default error
              {:ok? false :reason :storage-unavailable
               :message (str error)}))
          {:ok? false :reason :storage-unavailable})
        :clj
        {:ok? true}))))

(defn clear-queue!
  ([session-id]
   (clear-queue! session-id {}))
  ([session-id {explicit-client-id :client-id}]
   (let [key (key-for session-id (or explicit-client-id (client-id)))]
     (swap! memory-cache dissoc key)
     #?(:cljs
        (when-let [store (browser-store)]
          (try (.removeItem store key)
               (catch :default _ nil)))
        :clj nil)
     {:ok? true})))

(defn reset-memory!
  "Test helper: forget all in-memory queues."
  []
  (reset! memory-cache {})
  (reset! cache-tick 0))
