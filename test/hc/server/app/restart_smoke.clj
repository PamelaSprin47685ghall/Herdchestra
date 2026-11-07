(ns hc.server.app.restart-smoke
  "Child-process driver for the real application restart smoke.

    seed     starts the application, commits a session document and
             obligations in two partitions/scopes through the app's store,
             stops the application
    verify   starts the application again on the same data directory; startup
             recovery must run before the gateway and must move stale
             :running obligations to :outcome-unknown while keeping the
             report-pending obligation open; then stops the application
    blocked  corrupts one storage envelope, starts the application, and
             expects a blocked result with no gateway/URL
    retry-blocked seeds a storage :retry-wait obligation, starts the
             application, and expects a blocked result without rewriting
             the obligation
    session-list-blocked injects a session-list read error, starts the
             application, and expects a blocked result with no default
             session created

  This is not a test namespace; `hc.server.app.restart-smoke-test` starts it
  in child JVMs."
  (:require [hc.server.app.config :as config]
            [hc.server.app.system :as system]
            [hc.server.recovery :as recovery]
            [hc.server.sessions.registry :as registry]
            [hc.server.storage :as storage]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.keys :as keys])
  (:gen-class))

(defn- fail! [message data]
  (binding [*out* *err*]
    (println "APP-RESTART-SMOKE-FAIL" message (pr-str data)))
  (System/exit 2))

(defn- require! [condition message data]
  (when-not condition
    (fail! message data)))

(defn- app-config [data]
  (let [root   (str data "/root")
        static (str data "/static")]
    (.mkdirs (java.io.File. root))
    (.mkdirs (java.io.File. static))
    (spit (str static "/index.html") "<!doctype html><title>hc</title>")
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root root
      :static-root static
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :models {}})))

(defn- start! [data]
  (let [started (system/start! (app-config data))]
    (when (:blocked? started)
      (fail! "application blocked" (select-keys started [:error :hint :path])))
    started))

(defn- obligations-by-id [store]
  (->> (storage/list-open-obligations store {:all-partitions? true
                                             :open-only? false})
       :obligations
       (into {} (map (juxt :obligation/id identity)))))

(defn- seed! [data]
  (let [started (start! data)
        store   (:store started)]
    (try
      (storage/commit-migration!
       store
       {:migration-id "app-smoke-session"
        :partition "session/s1"
        :session-id "s1"
        :put [{:kind :session :id "s1" :doc {:title "app-restart"}}]
        :obligations
        {:put [{:obligation/id "run-session" :open? true :phase :running
                :executor-generation "gen-old" :safe-to-repeat? false}
               {:obligation/id "report/r1" :obligation/kind :report
                :open? true :phase :report-pending
                :report {:report-id "r1" :parent-call-id "c1"}}]}})
      (storage/commit-migration!
       store
       {:migration-id "app-smoke-master"
        :partition "master"
        :obligations
        {:put [{:obligation/id "run-master" :open? true :phase :running
                :executor-generation "gen-old" :safe-to-repeat? false}]}})
      (finally
        (system/stop! started)))
    (println "OK")))

(defn- verify! [data]
  (let [started (start! data)
        store   (:store started)]
    (try
      (let [all (obligations-by-id store)
            view (storage/read-session-view store {:partition "session/s1"
                                                   :session-id "s1"})]
        (require! (= "app-restart" (get-in view [:session :title]))
                  "session document did not survive restart" view)
        (require! (= :outcome-unknown (:phase (get all "run-session")))
                  "session running obligation was not recovered" all)
        (require! (= :outcome-unknown (:phase (get all "run-master")))
                  "master running obligation was not recovered" all)
        (require! (= :report-pending (:phase (get all "report/r1")))
                  "report obligation was not kept pending" all)
        (require! (true? (:open? (get all "report/r1")))
                  "report obligation was closed without ack" all))
      (finally
        (system/stop! started)))
    (println "OK")))

(defn- corrupt-store! [data]
  (let [store (storage/open-store {:path (str data "/db")})]
    (try
      (engine/transact!
       (:engine store)
       [[:put (keys/doc-key "session/s1" "s1" :task "bad")
         {:storage/kind :wrong
          :storage/id "bad"
          :storage/partition "session/s1"
          :storage/session-id "s1"
          :storage/version 1
          :storage/updated-at "x"
          :doc {}}]])
      (finally
        (storage/close-store! store)))))

(defn- blocked! [data]
  (corrupt-store! data)
  (let [started (system/start! (app-config data))]
    (require! (true? (:blocked? started))
              "corrupt store did not block the application" started)
    (require! (some? (:error started)) "blocked result has no error" started)
    (require! (string? (:hint started)) "blocked result has no hint" started)
    (require! (some? (:recovery-entry started))
              "blocked result has no recovery entry" started)
    (require! (nil? (:url started)) "blocked result started an HTTP listener" started)
    (require! (nil? (:gateway started)) "blocked result started a gateway" started)
    (println "OK")))

(defn- retry-blocked! [data]
  (let [config (app-config data)
        store (storage/open-store {:path (:db-path config) :create? true})]
    (try
      (storage/commit-migration!
       store
       {:migration-id "app-smoke-retry"
        :partition "session/s1"
        :session-id "s1"
        :obligations
        {:put [{:obligation/id "retry-future"
                :open? true
                :phase :retry-wait
                :attempts-used 1
                :max-attempts 3
                :retry-at (+ (System/currentTimeMillis) 3600000)}]}})
      (finally
        (storage/close-store! store)))
    (let [started (system/start! config)]
      (require! (true? (:blocked? started))
                "unclaimed retry did not block the application" started)
      (require! (= :STORAGE_RETRY_UNSUPPORTED (get-in started [:error :error]))
                "wrong retry block error" started)
      (require! (= ["retry-future"]
                   (mapv :obligation/id
                         (get-in started [:error :data :obligations])))
                "retry block does not list the obligation" started)
      (require! (string? (:hint started)) "retry block has no hint" started)
      (require! (nil? (:url started)) "retry block started a listener" started)
      (require! (nil? (:gateway started)) "retry block started a gateway" started))
    (let [store (storage/open-store {:path (:db-path config)})]
      (try
        (let [all (obligations-by-id store)]
          (require! (= :retry-wait (:phase (get all "retry-future")))
                    "retry obligation changed" all)
          (require! (true? (:open? (get all "retry-future")))
                    "retry obligation closed" all))
        (finally
          (storage/close-store! store))))
    (println "OK")))

(defn- session-list-blocked! [data]
  (let [config (app-config data)]
    (with-redefs [registry/list-sessions
                  (fn [_ _]
                    {:status :error
                     :error {:code :SESSION_LIST_FAILED
                             :message "injected session list failure"}})]
      (let [started (system/start! config)]
        (require! (true? (:blocked? started))
                  "session-list error did not block the application" started)
        (require! (= :SESSION_LIST_FAILED (get-in started [:error :code]))
                  "wrong session-list block error" started)
        (require! (string? (:hint started))
                  "session-list block has no hint" started)
        (require! (nil? (:url started))
                  "session-list block started a listener" started)
        (require! (nil? (:gateway started))
                  "session-list block started a gateway" started)))
    (let [store (storage/open-store {:path (:db-path config)})]
      (try
        (let [view (storage/read-session-view store {:partition "sessions"})]
          (require! (empty? (get-in view [:records :session-record]))
                    "default session was created" view))
        (finally
          (storage/close-store! store))))
    (println "OK")
    (flush)
    ;; This mode intentionally exercises a late startup failure after
    ;; model-auth has started.  Exit the child directly so its executors
    ;; cannot keep the smoke JVM alive.
    (System/exit 0)))

(defn -main [& args]
  (let [[mode data] args]
    (case mode
      "seed" (seed! data)
      "verify" (verify! data)
      "blocked" (blocked! data)
      "retry-blocked" (retry-blocked! data)
      "session-list-blocked" (session-list-blocked! data)
      (fail! "unknown mode" {:mode mode}))))
