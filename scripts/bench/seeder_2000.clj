;; P-BENCH2: single session with 2000 mixed activities.
(ns bench.seeder-2000
  "Seed one session containing N mixed activity messages
   (user/assistant text, tool calls, tool results, versions).

   Usage:
     clojure -M:dev:server -m bench.seeder-2000
       --data-dir /tmp/hc-bench2/run/data --messages 2000 --msg-chars 400"
  (:require [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.storage :as storage]))

(def ^:private session-partition "sessions")

(defn- parse-args [args]
  (loop [rest (seq args) acc {}]
    (if-let [[k v & more] (seq rest)]
      (recur more (assoc acc (keyword (subs (str k) 2)) v))
      acc)))

(defn- message-content [i chars]
  (let [body (case (mod i 6)
               0 (str "用户消息 " i " 中文与 English 混排。")
               1 (str "```clojure\n(defn f" i " [x] (+ x " i "))\n```")
               2 "| 列 A | 列 B |\n|---|---|\n| 1 2 |"
               3 (str "工具结果 " i " 返回了结构化数据。")
               4 (str "工具调用 " i " 调用 grep 查找 bench。")
               5 (str "引用与附件消息 " i))]
    (str body (apply str (repeat (max 0 (quot chars 2)) "中")))))

(defn- make-message [session-id index role chars at]
  (let [message-id (str session-id "-m" index)]
    (message/new-message
     message-id role (message-content index chars)
     {:now (System/currentTimeMillis)})))

(defn- make-session
  [store-i message-count msg-chars at]
  (let [session-id (str "bench-2000-" (format "%04d" store-i))
        {:keys [session] :as created} (sessions/new-session
                           {:session-id session-id
                            :owner-id (str "bench-owner-" (format "%04d" store-i))
                            :title (str "单会话 2000 活动基准 " store-i)
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
           :messages
           ;; Mix: mostly user/assistant text plus :tool records every 8th
           ;; entry, with a few multi-version messages (regenerate/edit
           ;; simulation) to exercise version switching.
           (into {}
                 (map-indexed
                  (fn [idx _]
                    (let [mid (str session-id "-m" idx)
                          ;; cycle: user(0) assistant(1) tool(2) tool-result(3)
                          ;; then a pair of user/assistant; every 11th gets
                          ;; an extra version to simulate edits/regen.
                          role (case (mod idx 6)
                                 0 :user
                                 1 :assistant
                                 2 :tool
                                 3 :tool
                                 4 :user
                                 5 :assistant)
                          {:keys [message]} (make-message session-id idx role
                                                          msg-chars at)
                          message (if (and (= role :assistant)
                                           (zero? (mod idx 11)))
                                    ;; Add a second version (regenerated).
                                    (let [v2 {:version-id (str mid "-v2")
                                              :content (str "重新生成版本 " idx)
                                              :attachments []
                                              :citations []
                                              :quotes []
                                              :tool-records []
                                              :created-at at
                                              :edited? true
                                              :source :regenerated}
                                          versions (vec (:versions message))]
                                      (-> message
                                          (assoc :versions (conj versions v2)
                                                 :current-version 1)))
                                    message)]
                      [(:message-id message) message])))
                 (range message-count))
           :message-order (mapv #(str session-id "-m" %) (range message-count))
           :drafts {}
           :calls {}
           :attempts {}
           :runs {}
           :run-order []
           :current-run-id nil
           :context {})))

(defn -main [& args]
  (let [opts (parse-args args)
        data-dir (or (:data-dir opts) (System/getenv "HC_DATA_DIR") "/tmp/hc-bench2/run/data")
        count (Long/parseLong (str (or (:sessions opts) (System/getenv "HC_BENCH_SESSIONS") "1")))
        message-count (Long/parseLong (str (or (:messages opts) (System/getenv "HC_BENCH_MESSAGES") "2000")))
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
          (let [session (make-session i message-count msg-chars at-ms)
                result (storage/commit-migration!
                        store
                        {:migration-id (str "bench2-seed-" (:session-id session))
                         :partition session-partition
                         :session-id (:session-id session)
                         :reason :bench-seed
                         :at at
                         :put [{:kind :session-record
                                :id (:session-id session)
                                :doc session}]})]
            (when-not (:ok? result)
              (throw (ex-info "session commit failed"
                              {:session-id (:session-id session) :result result})))))
        (let [ids-all (mapv #(str "bench-2000-" (format "%04d" %)) (range count))
              result (storage/commit-migration!
                      store
                      {:migration-id "bench2-seed-index"
                       :partition session-partition
                       :reason :bench-seed-index
                       :at at
                       :put [{:kind :session-index
                              :id "sessions"
                              :doc {:id "sessions" :ids ids-all}}]})]
          (when-not (:ok? result)
            (throw (ex-info "index commit failed" {:result result}))))
        (println (pr-str {:seeded {:sessions count
                                   :elapsed-ms (/ (- (System/nanoTime) start) 1e6)}}))
        (flush))
      (finally
        (storage/close-store! store)))))
