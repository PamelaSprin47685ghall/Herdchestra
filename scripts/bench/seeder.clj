(ns bench.seeder
  "Seed one Herdchestra store with N sessions for the P-BENCH1 benchmark.

  Run BEFORE starting the app, against a store directory that does not exist
  or is empty:

    clojure -M:dev:server -m bench.seeder
      --data-dir /tmp/hc-bench1/data --sessions 1000
      --messages 20 --msg-chars 400

  Every session is one `:session-record` document in the `sessions`
  partition plus one partition-scoped `:session-index` document listing all
  ids, written through `hc.server.storage/commit-migration!` -- the same
  atomic channel the app uses.  No Owner state is written: the app proves
  each visible session's `session/<id>` partition is readable, and an
  unversioned empty partition is the legitimate 'new session' shape.

  Sessions carry `:messages` records (user/assistant alternation, varied
  content kinds) and, for every tenth session, one applied context-summary
  compression over the first contiguous range -- the summary shape the
  history domain produces without inventing a separate record kind."
  (:require [hc.server.history.message :as message]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions :as sessions]
            [hc.server.storage :as storage]))

(def ^:private session-partition "sessions")

(defn- parse-args
  [args]
  (loop [rest (seq args) acc {}]
    (if-let [[k v & more] (seq rest)]
      (recur more (assoc acc (keyword (subs (str k) 2)) v))
      acc)))

(defn- message-content
  [i chars]
  (let [body (case (mod i 5)
               0 (str "普通消息 " i " 中文与 English 混排。")
               1 (str "```clojure\n(defn f" i " [x] (+ x " i "))\n```")
               2 "| 列 A | 列 B |\n|---|---|\n| 1 2 |"
               3 (str "工具结果 " i " 返回了结构化数据。")
               4 (str "引用与附件消息 " i))]
    (str body (apply str (repeat (max 0 (quot chars 2)) "中")))))

(defn- make-message
  [session-id index role chars at]
  (let [message-id (str session-id "-m" index)]
    (message/new-message
     message-id role (message-content index chars)
     {:now (System/currentTimeMillis)})))

(defn- make-session
  [store-i message-count msg-chars at]
  (let [session-id (str "bench-session-" (format "%04d" store-i))
        {:keys [session] :as created} (sessions/new-session
                           {:session-id session-id
                            :owner-id (str "bench-owner-" (format "%04d" store-i))
                            :title (str "基准会话 " store-i " 性能与容量")
                            :language :zh-CN
                            :now at
                            :id-source (ids/counter-source {:prefix "hc"})})
        _ (when-not (:ok? created)
            (throw (ex-info "new-session failed" {:error (:error created)})))
        created-at (- at (* store-i 1000))]
    (assoc session
           :created-at created-at
           :activity-at created-at
           :updated-at created-at
           :revision 1
           :persisted-revision 1
           :status :active
           :messages (into {}
                           (map-indexed
                            (fn [idx _]
                              (let [{:keys [message]}
                                    (make-message session-id idx
                                                  (if (even? idx) :user :assistant)
                                                  msg-chars at)]
                                [(:message-id message) message])))
                           (range message-count))
           :message-order (mapv #(str session-id "-m" %) (range message-count))
           :drafts {}
           :calls {}
           :attempts {}
           :runs {}
           :run-order []
           :current-run-id nil
           :context (if (zero? (mod store-i 10))
                      {:compressions
                       {"bench-cmp-1"
                        {:compression-id "bench-cmp-1"
                         :state :applied
                         :message-ids (mapv #(str session-id "-m" %)
                                             (range (min 8 message-count)))
                         :range {:from-message-id (str session-id "-m0")
                                 :to-message-id (str session-id "-m7")}
                         :summary (str "基准摘要 " store-i
                                       "：前八条消息已压缩为上下文摘要，"
                                       "用于验证按需渲染与摘要形态。")
                         :source-version-ids {}
                         :created-at created-at
                         :updated-at created-at
                         :applied-at created-at
                         :reverted-at nil}}
                       :compression-order ["bench-cmp-1"]}
                      {}))))

(defn- commit-session!
  [store session at-str]
  (let [result (storage/commit-migration!
                store
                {:migration-id (str "bench-seed-" (:session-id session))
                 :partition session-partition
                 :session-id (:session-id session)
                 :reason :bench-seed
                 :at at-str
                 :put [{:kind :session-record
                        :id (:session-id session)
                        :doc session}]})]
    (when-not (:ok? result)
      (throw (ex-info "session commit failed"
                      {:session-id (:session-id session) :result result})))))

(defn -main
  [& args]
  (let [opts (parse-args args)
        data-dir (or (:data-dir opts) (System/getenv "HC_DATA_DIR") "/tmp/hc-bench1/data")
        count (Long/parseLong (str (or (:sessions opts) (System/getenv "HC_BENCH_SESSIONS") "1000")))
        message-count (Long/parseLong (str (or (:messages opts) (System/getenv "HC_BENCH_MESSAGES") "20")))
        msg-chars (Long/parseLong (str (or (:msg-chars opts) (System/getenv "HC_BENCH_MSG_CHARS") "400")))
        db-path (str data-dir "/db")
        at-ms (System/currentTimeMillis)
        at (str (java.time.Instant/ofEpochMilli at-ms))
        store (storage/open-store {:path db-path :create? true :create-parents? true
                                   :durability :nosync :lock? true})]
    (try
      (println (pr-str {:seeding {:sessions count :messages message-count
                                  :msg-chars msg-chars :data-dir data-dir :db-path db-path}}))
      (flush)
      (let [start (System/nanoTime)]
        (dotimes [i count]
          (commit-session! store (make-session i message-count msg-chars at-ms) at))
        ;; One partition-scoped index lists every session id; it is the same
        ;; document `list-session-ids` reads through `session/<_>` scope.
        (let [ids-all (mapv #(str "bench-session-" (format "%04d" %)) (range count))]
          (let [result (storage/commit-migration!
                        store
                        {:migration-id "bench-seed-index"
                         :partition session-partition
                         :reason :bench-seed-index
                         :at at
                         :put [{:kind :session-index
                                :id "sessions"
                                :doc {:id "sessions" :ids ids-all}}]})]
            (when-not (:ok? result)
              (throw (ex-info "index commit failed" {:result result})))))
        (println (pr-str {:seeded {:sessions count
                                   :elapsed-ms (/ (- (System/nanoTime) start) 1e6)}}))
        (flush))
      (finally
        (storage/close-store! store)))))
