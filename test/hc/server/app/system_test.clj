(ns hc.server.app.system-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission :as mission]
            [hc.compose.fleet.service :as fleet]
            [hc.compose.fleet.git :as fleet-git]
            [hc.compose.fleet.support :as fleet-support]
            [hc.compose.supervisor :as supervisor]
            [hc.server.app.config :as config]
            [hc.server.app.fleet-gc :as fleet-gc]
            [hc.server.app.retention-gc :as retention-gc]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.app.snapshot :as app-snapshot]
            [hc.server.app.system :as system]
            [hc.server.backup.maintenance-journal :as journal]
            [hc.server.blob-store.refs :as blob-refs]
            [hc.server.gateway :as gateway]
            [hc.server.history.snapshot :as history-snapshot]
            [hc.server.model-adapter :as model-adapter]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ports :as session-ports]
            [hc.server.sessions.registry :as registry]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as storage-support])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$Builder
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(declare io-make-parents)

(defn- write-file! [path content]
  (let [file (java.io.File. ^String path)]
    (io-make-parents file)
    (spit file content)))

(defn- io-make-parents [^java.io.File file]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent)))

(defn- test-config
  ([]
   (test-config nil))
  ([workspace-root]
   (let [root (or workspace-root (temp-dir "hc-app-root-"))
         data (temp-dir "hc-app-data-")
         static (temp-dir "hc-app-static-")]
     (write-file! (str static "/index.html") "<!doctype html><title>hc</title>")
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
       :models {}}))))

(defn- stale-compression-session
  "One applied compression whose reviewed source version is no longer current."
  []
  {:session-id "stale-session"
   :owner-id "stale-owner"
   :status :active
   :title "Stale summary"
   :revision 0
   :created-at 1000
   :updated-at 2000
   :activity-at 2000
   :message-order ["m1"]
   :messages {"m1" {:message-id "m1"
                    :role :user
                    :created-at 1000
                    :updated-at 2000
                    :current-version 1
                    :versions [{:version-id "v1"
                                :content "original"
                                :created-at 1000}
                               {:version-id "v2"
                                :content "edited"
                                :created-at 2000}]}}
   :drafts {}
   :context {:compressions
             {"cmp-stale" {:compression-id "cmp-stale"
                           :state :applied
                           :message-ids ["m1"]
                           :source-version-ids {"m1" "v1"}
                           :summary "reviewed summary"
                           :applied-at 1500
                           :updated-at 1500}}
             :compression-order ["cmp-stale"]}})

(defn- await-fleet-gc-tick
  [gc]
  (let [deadline (+ (System/nanoTime) (* 2000 1000000))]
    (loop []
      (cond
        (pos? (long (or (:tick-count (fleet-gc/status gc)) 0))) true
        (> (System/nanoTime) deadline) false
        :else (do
                (Thread/sleep 10)
                (recur))))))

(defn- http-get
  ([url] (http-get url {}))
  ([url headers]
   (let [builder (HttpRequest/newBuilder (URI/create url))]
     (doseq [[header value] headers]
       (.header ^HttpRequest$Builder builder
                ^String (name header)
                ^String (str value)))
     (.send (HttpClient/newHttpClient)
            (.build (.GET ^HttpRequest$Builder builder))
            (HttpResponse$BodyHandlers/ofString)))))

(defn- http-post
  ([url] (http-post url {}))
  ([url headers]
   (let [builder (HttpRequest/newBuilder (URI/create url))]
     (doseq [[header value] headers]
       (.header ^HttpRequest$Builder builder
                ^String (name header)
                ^String (str value)))
     (.send (HttpClient/newHttpClient)
            (.build (.POST ^HttpRequest$Builder builder
                           (HttpRequest$BodyPublishers/noBody)))
            (HttpResponse$BodyHandlers/ofString)))))

(deftest initial-user-input-merge-seeds-relay-and-keeps-blob-refs
  (let [blob-id (str "sha256-" (apply str (repeat 64 "e")))
        attachment {:attachment-id "att-initial"
                    :blob-id blob-id
                    :name "initial.txt"
                    :mime "text/plain"
                    :size 7}
        session {:session-id "initial-input-session"
                 :status :active
                 :messages
                 {"m-1" {:message-id "m-1"
                         :role :user
                         :current-version 0
                         :versions [{:version-id "m-1-v1"
                                     :content "first question"
                                     :attachments [attachment]}]}}
                 :message-order ["m-1"]}
        initial (sessions/initial-user-input session)
        mission-state
        (mission/initial-state
         (merge {:session-id "initial-input-session"
                 :models {:main "main-model" :task "task-model"}}
                initial))]
    (is (= {:user-input "first question"
            :user-attachments [attachment]}
           initial))
    (is (= [{:text "first question"
             :attachments [attachment]}]
           (get-in mission-state [:relay :user-inputs])))
    (is (contains? (blob-refs/session-refs session) blob-id)
        "the same attachment id is already a live session blob reference")
    (is (:ok? (mission/check-invariants mission-state)))))

(deftest start-health-http-stop
  (let [config (test-config)
        started (system/start! config)
        stopped (atom nil)]
    (try
      (is (false? (:blocked? started)))
      (is (string? (:url started)))
      (is (str/starts-with? (:url started) "http://127.0.0.1:"))
      (is (= 4096 (get-in started [:gateway :opts :max-pending]))
          "the configured acknowledgement window reaches the feed")
      (is (= [] (:retry-owner-errors started))
          "a clean store has no retry-owner start failures")
      (testing "health snapshot is honest and complete"
        (let [health (system/health started)]
          (is (true? (:ok health)))
          (is (true? (get-in health [:store :open?])))
          (is (pos? (get-in health [:sessions :count])))
          (is (some? (:gateway health)))
          (is (= 4096 (get-in health [:gateway :max-pending])))))
      (testing "the bound port serves health and the client shell"
        (let [health-response (http-get (str (:url started) "healthz"))
              root-response (http-get (:url started))]
          (is (= 200 (.statusCode health-response)))
          (is (true? (:ok (edn/read-string (.body health-response)))))
          (is (= 200 (.statusCode root-response)))
          (is (str/includes? (.body root-response) "<title>hc</title>"))))
      (testing "health accepts GET only"
        (let [post-response (http-post (str (:url started) "healthz") {})]
          (is (= 405 (.statusCode post-response)))
          (is (= :METHOD_NOT_ALLOWED
                 (:error (edn/read-string (.body post-response)))))))
      (finally
        (reset! stopped (system/stop! started))))
    (testing "stop reports both writer-facing components stopped"
      (is (true? (:stopped? @stopped)))
      (is (= :stopped (get-in @stopped [:components :http :status])))
      (is (true? (get-in @stopped [:components :http :stopped?])))
      (is (true? (get-in @stopped [:components :gateway :stopped?]))))
    (testing "the store is closed after stop"
      (is (false? (:ok (system/health started)))))))

(defn- commands-active-id
  [started]
  (get-in @(:state (:router started)) [:active-session-id]))

(deftest a-fresh-store-publishes-its-default-session-in-the-first-baseline
  (let [started (system/start! (test-config))
        snapshot (:snapshot (gateway/snapshot (:gateway started)))]
    (try
      (is (false? (:blocked? started)))
      (let [summaries (:session/by-id snapshot)
            session-id (:session/active-id snapshot)
            summary (get summaries session-id)]
        (is (= 1 (count summaries))
            "the first STATE_SNAPSHOT carries the default session")
        (is (string? session-id)
            "the first STATE_SNAPSHOT carries an active session id")
        (is (= session-id (commands-active-id started))
            "the router selects the default session, not only the snapshot")
        (is (contains? summary :session/controller-id))
        (is (nil? (:session/controller-id summary))
            "a fresh session has no controller")
        (is (= [] (:session/observer-ids summary))
            "a fresh session has no observer"))
      (finally
        (system/stop! started)))))

(deftest startup-blocks-on-an-unfinished-offline-restore
  (let [config (test-config)
        state (journal/offline-restore-begin!
               config {:kind :backup})]
    (is (= :restoring (:status state)))
    (let [started (system/start! config)]
      (is (true? (:blocked? started)))
      (is (= :BACKUP_OFFLINE_RESTORE_INCOMPLETE (get-in started [:error :code])))
      (is (nil? (:url started)))
      (is (= (:maintenance-id state)
             (get-in started [:error :maintenance-id]))))))

(deftest startup-blocks-on-an-unacknowledged-supervisor-failure
  (let [config (test-config)
        state {:maintenance-id "supervised-1"
               :request {:kind :backup :source :supervisor
                         :backup-id "backup-1" :mode :merge
                         :categories [:conversations]}
               :status :failed-needs-recovery
               :phase :done
               :needs-recovery? true
               :storage-touched? true}]
    (journal/write-journal! config state)
    (let [started (system/start! config)]
      (is (true? (:blocked? started)))
      (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
             (get-in started [:error :code])))
      (is (= "supervised-1" (get-in started [:error :maintenance-id])))
      (is (true? (get-in started [:error :needs-recovery?])))
      (is (nil? (:url started))))))

(deftest gateway-config-reaches-the-live-policy
  (let [config (config/normalize
                (assoc (test-config)
                       :gateway
                       {:allowed-origins ["http://app.example"]
                        :trusted-hosts ["app.example"]
                        :allow-remote? false
                        :max-pending 7}))
        started (system/start! config)]
    (try
      (is (false? (:blocked? started)))
      (is (= 7 (get-in started [:gateway :opts :max-pending])))
      (is (= 7 (get-in (system/health started) [:gateway :max-pending]))
          "the feed consumes the configured acknowledgement window")
      (is (= #{"http://app.example"}
             (get-in started [:gateway :opts :allowed-origins])))
      (is (= #{"app.example"}
             (get-in started [:gateway :opts :trusted-hosts])))
      (is (false? (get-in started [:gateway :opts :allow-remote?])))
      (testing "one policy guards app-owned routes and health"
        (is (= 200 (.statusCode
                    (http-get (str (:url started) "healthz")
                              {"origin" "http://app.example"}))))
        (is (= 403 (.statusCode
                    (http-get (str (:url started) "healthz")
                              {"origin" "http://evil.example"}))))
        (is (= 200 (.statusCode
                    (http-get (str (:url started) "api/workspace")
                              {"origin" "http://app.example"}))))
        (is (= 403 (.statusCode
                    (http-get (str (:url started) "api/workspace")
                              {"origin" "http://evil.example"})))))
      (finally
        (system/stop! started)))))

(deftest persisted-config-cannot-override-launch-security
  (let [config (config/normalize
                {:host "127.0.0.1"
                 :gateway {:allowed-origins :all
                           :allow-remote? false}})
        merged (system/merge-persisted-config
                config
                {:connections {}
                 :roles {}
                 :default-role nil
                 :host "0.0.0.0"
                 :gateway {:allowed-origins #{"http://evil.example"}
                           :trusted-hosts #{"evil.example"}
                           :allow-remote? true}})]
    (is (= "127.0.0.1" (:host merged)))
    (is (= :all (get-in merged [:gateway :allowed-origins])))
    (is (= #{} (get-in merged [:gateway :trusted-hosts])))
    (is (false? (get-in merged [:gateway :allow-remote?])))))

(deftest launch-config-revokes-a-persisted-connection
  ;; The boot model port reads this effective config, so a connection the
  ;; launch file no longer declares must not survive through the settings
  ;; document's copy.
  (let [alpha {:id "alpha"
               :name "Alpha Provider"
               :kind :openai-compatible
               :base-url "http://127.0.0.1:20321/v1"
               :api-key "stub-a"
               :models [{:id "same-model" :model-name "same-model"}]}
        beta {:id "beta"
              :name "Beta Provider"
              :kind :openai-compatible
              :base-url "http://127.0.0.1:20322/v1"
              :api-key "stub-b"
              :models [{:id "same-model" :model-name "same-model"}]}
        merged (system/merge-persisted-config
                {:models {:connections [beta]}}
                {:connections [alpha beta]
                 :launch-connection-ids #{"alpha" "beta"}
                 :roles {:main {:connection "beta" :model "same-model"}}})]
    (is (= ["beta"] (mapv :id (get-in merged [:models :connections]))))
    (is (not (contains? (:models merged) :launch-connection-ids))
        "provenance never reaches the model adapter schema")
    (is (true? (model-adapter/model-configured? (:models merged)))))
  (testing "a settings-created connection has no launch declaration to revoke"
    (let [merged (system/merge-persisted-config
                  {:models {:connections []}}
                  {:connections [{:id "ui-conn"
                                  :name "UI Provider"
                                  :kind :openai-compatible
                                  :base-url "https://ui.example.com/v1"
                                  :models []}]
                   :launch-connection-ids #{}})]
      (is (= ["ui-conn"] (mapv :id (get-in merged [:models :connections]))))))
  (testing "a legacy document without provenance keeps its connections"
    ;; An inline key is not provenance.  Guessing from it would delete a
    ;; settings-created connection that happened to keep a key in the
    ;; document.  A legacy origin is unknown, so keep the row; the operator
    ;; can delete it explicitly.
    (let [merged (system/merge-persisted-config
                  {:models {:connections [{:id "beta"
                                           :kind :openai-compatible
                                           :models []}]}}
                  {:connections [{:id "alpha"
                                  :kind :openai-compatible
                                  :api-key "stub-a"
                                  :models []}
                                 {:id "beta"
                                  :kind :openai-compatible
                                  :models []}]})]
      (is (= ["beta" "alpha"]
             (mapv :id (get-in merged [:models :connections])))))))

(deftest launch-model-placeholders-are-projected-before-the-adapter
  (let [launch {:main nil
                :task nil
                :connections
                [{:id "conn-1"
                  :name "Local"
                  :kind :openai-compatible
                  :base-url "http://127.0.0.1:11434/v1"
                  :models [{:id "model-a" :model-name "llama"}]}]
                :roles {:main {:connection "conn-1" :model "model-a"}}}]
    (testing "the projection drops launch placeholders before the adapter"
      (is (false? (contains? (system/model-configuration launch) :main)))
      (is (false? (contains? (system/model-configuration launch) :task))))
    (testing "the projection keeps only the adapter-owned model fields"
      (is (= {:connections [{:id "conn-1"
                             :name "Local"
                             :kind :openai-compatible
                             :base-url "http://127.0.0.1:11434/v1"
                             :models [{:id "model-a" :model-name "llama"}]}]
              :roles {:main {:connection "conn-1" :model "model-a"}}}
             (system/model-configuration launch)))
      (is (true? (model-adapter/model-configured?
                  (system/model-configuration launch)))))
    (testing "merging a persisted settings body drops launch-only keys"
      (let [merged (system/merge-persisted-config
                    {:models {:main nil :task nil}}
                    {:connections (:connections launch)
                     :roles (:roles launch)})]
        (is (= #{:connections :roles} (set (keys (:models merged)))))
        (is (true? (model-adapter/model-configured? (:models merged))))))
    (testing "the shipped default no longer carries legacy role placeholders"
      (is (= {} (:models (config/load-config {:args [] :env {}})))))
    (testing "nil optional connection fields do not poison the adapter schema"
      (let [projected (system/model-configuration
                       {:connections [{:id "conn-nil"
                                       :kind :openai-compatible
                                       :headers nil
                                       :options nil
                                       :api-key nil
                                       :models [{:id "model-nil"
                                                 :options nil}]}]
                        :roles {:main {:connection "conn-nil"
                                       :model "model-nil"}
                                :task {:mode :unset}
                                :embedding :unset}})]
        (is (= {:id "conn-nil"
                :kind :openai-compatible
                :models [{:id "model-nil"}]}
               (first (:connections projected))))
        (is (not (contains? (first (:connections projected)) :headers)))
        (is (not (contains? (first (:connections projected)) :options)))
        (is (= {:main {:connection "conn-nil" :model "model-nil"}}
               (:roles projected))
            "unbound role placeholders never reach the adapter schema")))))

(deftest fleet-gc-scheduler-is-stopped-with-the-app
  (when (fleet-git/available? (fleet-git/git-cli "."))
    (let [root (temp-dir "hc-app-fleet-root-")
          _ (fleet-support/init-repo! root)
          config (test-config root)
          started (system/start! config)
          gc (:fleet-gc started)
          process-supervisor (:supervisor started)
          stopped (atom nil)]
      (try
        (is (false? (:blocked? started)))
        (is (some? (:fleet started)))
        (is (some? gc))
        (is (some? process-supervisor)
            "the background loop is supervised, not a bare timer")
        (is (true? (:running? (supervisor/status process-supervisor))))
        (is (true? (:running? (fleet-gc/status gc))))
        (is (await-fleet-gc-tick gc)
            "the app-owned timer runs a first sweep")
        (finally
          (reset! stopped (system/stop! started))))
      (is (true? (:stopped? (supervisor/status process-supervisor))))
      (is (false? (:running? (supervisor/status process-supervisor))))
      (is (true? (:stopped? (fleet-gc/status gc))))
      (is (true? (:terminated? (fleet-gc/status gc))))
      (is (false? (:running? (fleet-gc/status gc))))
      (is (true? (get-in @stopped [:components :fleet-gc :terminated?]))
          "stop evidence reports the executor termination"))))

(deftest stop-retires-the-supervisor-before-owners-and-the-fleet
  (when (fleet-git/available? (fleet-git/git-cli "."))
    (let [root (temp-dir "hc-app-fleet-root-")
          _ (fleet-support/init-repo! root)
          started (system/start! (test-config root))
          order (atom [])
          result (atom nil)
          owner {:stop! (fn []
                          (swap! order conj :owner)
                          {:ok? true :stopped? true :drained? true})}
          real-supervisor-stop supervisor/stop!
          real-fleet-stop fleet/stop!]
      (try
        (is (false? (:blocked? started)))
        (is (some? (:fleet started)))
        (swap! (:owners started) assoc "probe-owner" owner)
        (with-redefs [supervisor/stop!
                      (fn [sup & args]
                        (swap! order conj :supervisor)
                        (apply real-supervisor-stop sup args))
                      fleet/stop!
                      (fn [service]
                        (swap! order conj :fleet)
                        (real-fleet-stop service))]
          (reset! result (system/stop! started)))
        (is (true? (:stopped? @result)) (pr-str @result))
        (let [positions (zipmap @order (range))
              supervisor-at (:supervisor positions)
              owner-at (:owner positions)
              fleet-at (:fleet positions)]
          (is (some? supervisor-at))
          (is (some? owner-at))
          (is (some? fleet-at))
          (is (< supervisor-at owner-at)
              "background loops stop before session Owners")
          (is (< fleet-at owner-at)
              "the Fleet dispatch service stops before session Owners"))
        (finally
          ;; A failed stop leaves live writers; a second stop is idempotent.
          (system/stop! started))))))

(deftest retention-gc-runs-under-the-supervisor-and-stops-with-the-app
  (let [started (system/start! (test-config))
        retention (:retention-gc started)
        stopped (atom nil)]
    (try
      (is (some? retention)
          "retention scheduling is part of the supervised background loops")
      (is (true? (:running? (retention-gc/status retention))))
      (let [health (system/health started)]
        (is (true? (get-in health [:components :retention-gc :started?]))
            "the component snapshot reports the running scheduler")
        (is (zero? (get-in health [:retention-gc :session-runs])))
        (is (zero? (get-in health [:retention-gc :blob-runs])))
        (is (nil? (get-in health [:retention-gc :session-last-error])))
        (is (nil? (get-in health [:retention-gc :blob-last-error]))))
      (let [response (http-get (str (:url started) "healthz"))
            body (edn/read-string (.body response))]
        (is (= 200 (.statusCode response)))
        (is (contains? body :retention-gc)
            "/healthz exposes the retention observation")
        (is (zero? (get-in body [:retention-gc :session-runs])))
        (is (zero? (get-in body [:retention-gc :blob-runs]))))
      (finally
        (reset! stopped (system/stop! started))))
    (is (true? (:terminated? (retention-gc/status retention))))
    (is (false? (:running? (retention-gc/status retention))))
    (is (true? (get-in @stopped [:components :retention-gc :terminated?]))
        "stop evidence reports the real executor state")
    (is (true? (:stopped? @stopped)))))

(deftest retention-gc-stops-before-owners-and-the-store
  (let [order (atom [])
        open? (atom true)
        service {:executor nil
                 :stopped? (atom false)
                 :tasks (atom [])
                 :stop-timeout-ms 5000
                 :state (atom {:session-runs 0
                               :blob-runs 0
                               :session-last-result nil
                               :blob-last-result nil
                               :session-last-error nil
                               :blob-last-error nil
                               :last-run-at nil})}
        owner {:stop! (fn []
                        (swap! order conj :owner)
                        {:ok? true :stopped? true :drained? true})}
        result (atom nil)]
    (with-redefs [retention-gc/stop!
                  (fn [_service]
                    (swap! order conj :retention-gc)
                    {:stopped? true :terminated? true})
                  retention-gc/status
                  (fn [_service]
                    {:running? false :terminated? true})
                  storage/expect-ordered-close! (fn [_store] nil)
                  storage/store-open? (fn [_store] @open?)
                  storage/open-close-store!
                  (fn [_store _opts]
                    (swap! order conj :store)
                    (reset! open? false)
                    {:closed? true})]
      (reset! result
              (system/stop! {:blocked? false
                             :partial? true
                             :retention-gc service
                             :owners (atom {"s1" owner})
                             :store ::store})))
    (is (true? (:stopped? @result)) (pr-str @result))
    (let [positions (zipmap @order (range))
          owner-at (:owner positions)
          store-at (:store positions)
          retention-at (:retention-gc positions)]
      (is (some? retention-at))
      (is (some? owner-at))
      (is (some? store-at))
      (is (< retention-at owner-at)
          "the retention child stops before session Owners")
      (is (< retention-at store-at)
          "the retention child stops before the store closes"))))

(deftest retention-config-reaches-the-scheduler
  (let [config (test-config)
        captured (atom nil)
        fake-service {:executor nil
                      :stopped? (atom false)
                      :tasks (atom [])
                      :stop-timeout-ms 5000
                      :state (atom {:session-runs 0
                                    :blob-runs 0
                                    :session-last-result nil
                                    :blob-last-result nil
                                    :session-last-error nil
                                    :blob-last-error nil
                                    :last-run-at nil})}
        started (with-redefs [retention-gc/start!
                              (fn [options]
                                (reset! captured options)
                                {:ok? true :service fake-service})]
                  (system/start! config))]
    (try
      (is (= 86400000 (:session-interval-ms @captured))
          "[:retention :interval-ms] drives both sweeps")
      (is (= 86400000 (:blob-interval-ms @captured)))
      (is (= 30 (:session-days @captured)))
      (is (= 7 (:blob-days @captured)))
      (is (false? (:run-immediately? @captured)))
      (is (= (:blob-root config) (:blob-root @captured)))
      (is (= (:registry started) (:registry @captured)))
      (is (= (:store started) (:store @captured)))
      (is (not (contains? @captured :purge-session!))
          "the registry's purge-deleted-session! stays the default")
      (is (not (contains? @captured :blob-cleanup!))
          "the store/blob-root cleanup coordinator stays the default")
      (finally
        ;; The fake runtime has no executor; report the same stop evidence a
        ;; real terminated scheduler gives so the stop path stays honest.
        (with-redefs [retention-gc/stop!
                      (fn [_service]
                        {:stopped? true :terminated? true})
                      retention-gc/status
                      (fn [_service]
                        {:running? false :terminated? true})]
          (system/stop! started))))))

(deftest retention-start-failure-is-reported-without-taking-the-app-down
  (with-redefs [retention-gc/start!
                (fn [_options]
                  {:ok? false
                   :error {:code :RETENTION_TIMER_FAILED
                           :message "timer unavailable"}})]
    (let [started (system/start! (test-config))]
      (try
        (is (false? (:blocked? started)))
        (is (some? (:url started))
            "the rest of the application still starts")
        (is (nil? (:retention-gc started)))
        (let [health (system/health started)]
          (is (false? (:ok health))
              "a failed background component makes health explicitly unhealthy")
          (is (= :RETENTION_TIMER_FAILED
                 (get-in health [:component-errors
                                 :retention-gc-error :code])))
          (is (= :failed
                 (get-in health [:components :retention-gc :status]))))
        (finally
          (system/stop! started))))))

(deftest history-port-annotates-stale-compressions
  (let [port (#'system/session-port (constantly 1000)
                                    (constantly {})
                                    (atom nil)
                                    nil
                                    nil)
        session (stale-compression-session)]
    (testing "a successful command returns the recomputed marker"
      (let [result ((:apply-command port) session
                    {:kind :session/rename :title "Renamed"})]
        (is (true? (:ok? result)) (pr-str result))
        (is (true? (get-in result
                           [:session :context :compressions
                            "cmp-stale" :stale?])))))
    (testing "switching back to the reviewed version clears the marker"
      (let [result ((:apply-command port) session
                    {:kind :history/select-version
                     :message-id "m1"
                     :version-id "v1"})]
        (is (true? (:ok? result)) (pr-str result))
        (is (false? (get-in result
                            [:session :context :compressions
                             "cmp-stale" :stale?])))))))

(deftest restart-annotates-the-initial-owner-session
  (let [config (config/ensure-directories! (test-config))
        seeded (assoc (stale-compression-session)
                      :session-id "seeded-restart")
        store (storage/open-store {:path (:db-path config) :create? true})]
    (try
      (let [persistence (session-persistence/persistence store)]
        (is (= :committed
               (:status (session-ports/commit-session! persistence 0 seeded)))))
      (finally
        (storage/close-store! store)))
    (let [started (system/start! config)]
      (try
        (is (false? (:blocked? started)))
        (let [owner (get @(:owners started) "seeded-restart")
              session (get-in ((:snapshot owner)) [:state :session])]
          (is (some? owner))
          (is (some? session))
          (is (true? (get-in session
                             [:context :compressions "cmp-stale" :stale?]))
              "a restarted Owner sees the marker before its first command"))
        (finally
          (system/stop! started))))))

(deftest snapshot-requests-full-context-only-for-the-active-session
  (let [captured (atom {})
        s1 {:session-id "s1"
            :revision 1
            :status :active
            :message-order []
            :messages {}
            :drafts {}}
        s2 (assoc s1 :session-id "s2")]
    (with-redefs [history-snapshot/session-history
                  (fn [session opts]
                    (swap! captured assoc (:session-id session) opts)
                    {:session-id (:session-id session)
                     :message-ids []
                     :ordered-ids []
                     :messages []
                     :context {}})]
      (app-snapshot/baseline {:sessions [s1 s2]
                              :session-records [s1 s2]
                              :owner-snapshots {}
                              :active-session "s1"}))
    (is (true? (:context? (get @captured "s1")))
        "the active session keeps the full outbound/reading projection")
    (is (false? (:context? (get @captured "s2")))
        "an inactive session skips the panel-only projection")))

(deftest join-results-reach-only-the-mission-owner
  (let [messages (atom [])
        owner {:submit! (fn [message]
                          (swap! messages conj message)
                          {:status :accepted})}
        router {:state (atom {:owners {"s1" owner}})}
        service {:mission-id "mission-1" :clock (constantly 4242)}
        publish! (var-get #'system/publish-join-ff-ready!)]
    (is (= "s1" (system/link-mission-session! router "mission-1" "s1")))
    (testing "a verified fast-forward publishes true with its commit"
      (publish! router service {:swarm-id "w1" :ff-commit "abc123"})
      (is (= [{:type :git/ff-ready
               :ready? true
               :source :join
               :at 4242
               :ff-commit "abc123"}]
             @messages)))
    (testing "a merged join whose state write failed still publishes true"
      (reset! messages [])
      (publish! router service
                {:fleet/error {:code :FLEET_STATE_PERSIST_FAILED
                               :git-merged? true
                               :ff-commit "def456"}})
      (is (true? (:ready? (first @messages))))
      (is (= "def456" (:ff-commit (first @messages)))))
    (testing "any other fleet error publishes false"
      (reset! messages [])
      (publish! router service {:fleet/error {:code :GIT_MERGE_FAILED}})
      (is (= [{:type :git/ff-ready
               :ready? false
               :source :join
               :at 4242}]
             @messages)))
    (testing ":none and :no-results keep the previous Owner value"
      (reset! messages [])
      (publish! router service :none)
      (publish! router service :no-results)
      (is (empty? @messages)))
    (testing "an unknown mission never guesses an Owner"
      (reset! messages [])
      (publish! router (assoc service :mission-id "mission-2")
                {:ff-commit "abc123"})
      (is (empty? @messages)))))

(deftest health-counts-sessions-without-listing-them
  (let [started (system/start! (test-config))
        list-calls (atom 0)]
    (try
      ;; The publisher also lists sessions on its own thread; stop it first so
      ;; the counter measures the health path only.
      (is (true? (:stopped? ((:stop! (:publisher started))))))
      (with-redefs [registry/list-sessions
                    (fn [& _]
                      (swap! list-calls inc)
                      {:status :ok :sessions []})]
        (let [health (system/health started)]
          (is (true? (:ok health)))
          (is (= 1 (get-in health [:sessions :count]))
              "the count comes from the committed session-id index")
          (is (zero? @list-calls)
              "health never deserializes every session record"))
        (let [response (http-get (str (:url started) "healthz"))
              body (edn/read-string (.body response))]
          (is (= 200 (.statusCode response)))
          (is (true? (:ok body)))
          (is (= 1 (get-in body [:sessions :count]))
              "/healthz counts the index, not the records"))
        (is (zero? @list-calls)
            "the HTTP route never deserializes every session record"))
      (finally
        (system/stop! started)))))

(deftest health-marks-an-unavailable-session-count-as-unknown
  (testing "a system without persistence"
    (let [health (system/health {:store nil :gateway nil})]
      (is (= :unknown (get-in health [:sessions :count])))
      (is (= {:count :unknown} (:sessions health)))))
  (testing "the index read throws"
    (let [persistence (reify session-ports/SessionPersistence
                        (list-session-ids [_]
                          (throw (ex-info "index unavailable" {}))))
          health (system/health {:store nil
                                 :gateway nil
                                 :persistence persistence})]
      (is (= :unknown (get-in health [:sessions :count])))))
  (testing "the index read reports an error status"
    (let [persistence (reify session-ports/SessionPersistence
                        (list-session-ids [_] {:status :error}))
          health (system/health {:store nil
                                 :gateway nil
                                 :persistence persistence})]
      (is (= :unknown (get-in health [:sessions :count]))))))

(deftest owner-state-preflight-distinguishes-empty-and-corrupt
  (let [dir (temp-dir "hc-owner-preflight-")
        verify (resolve 'hc.server.app.system/verify-owner-state!)]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (testing "a session with no authoritative partition is legitimately empty"
            (is (= 0 (:version (verify store "empty"))))
            (is (nil? (:state (verify store "empty")))))
          (testing "a positive partition version without owner-state is corruption"
            (storage/commit-migration!
             store
             {:migration-id "seed-corrupt-owner"
              :partition "session/bad"
              :session-id "bad"
              :put [{:kind :task :id "t1" :doc {}}]})
            (let [error (try
                          (verify store "bad")
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
              (is (some? error))
              (is (= :OWNER_STORE_CORRUPT
                     (get-in (ex-data error)
                             [:recovery-blocked :error :error])))
              (is (= "bad"
                     (get-in (ex-data error)
                             [:recovery-blocked :error :data :session-id])))
              (is (false? (storage/store-open? store))
                  "the preflight closes the store before reporting corruption")))
          (finally
            (try (storage/close-store! store) (catch Throwable _ nil)))))
      (finally
        (storage-support/delete-tree! dir)))))

(deftest owner-state-preflight-leaves-a-legitimate-empty-session-alone
  (let [dir (temp-dir "hc-owner-preflight-loop-")
        persistence (reify session-ports/SessionPersistence
                      (list-session-ids [_] {:status :ok :ids ["fresh"]})
                      (read-session [_ _]
                        {:status :found
                         :session {:session-id "fresh" :status :active}}))
        verify-all (resolve 'hc.server.app.system/verify-owner-states!)]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (is (nil? (verify-all store persistence))
              "a session without an authoritative partition is not corrupt")
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))

(deftest removing-all-models-clears-the-live-port
  (let [port-ref (atom ::retired)
        system-ref (atom {})
        apply-models! (resolve 'hc.server.app.system/apply-models!)
        result (apply-models! port-ref
                              (atom [])
                              (atom nil)
                              system-ref
                              (fn [_] nil)
                              nil
                              (atom nil)
                              {:revision 9})]
    (is (= {:ok? true :applied? true :cleared? true} result))
    (is (nil? @port-ref)
        "the next model request cannot reach a removed connection")
    (is (not (contains? (get-in @system-ref [:last-apply :models])
                        :revision))
        "the settings CAS revision never reaches the closed adapter schema")))


(deftest startup-marker-blocks-a-missing-store
  (let [config (test-config)]
    (let [started (system/start! config)]
      (try
        (is (false? (:blocked? started)))
        (is (.exists (java.io.File. ^String
                                    (str (:data-dir config) "/initialized.edn"))))
        (finally (system/stop! started))))
    (storage-support/delete-tree! (:db-path config))
    (let [blocked (system/start! config)]
      (is (true? (:blocked? blocked)))
      (is (= :STORE_MISSING_AFTER_INIT (get-in blocked [:error :code])))
      (is (not (.exists (java.io.File. ^String (:db-path config))))
          "a missing store is not silently recreated over prior state"))))

(deftest process-generation-is-shared-with-the-scheduler
  (let [started (system/start! (test-config))]
    (try
      (is (string? (:process-generation started)))
      (is (= (:process-generation started)
             (get-in started [:scheduler :generation])))
      (finally (system/stop! started)))))

(deftest stop-refuses-an-undrained-component
  (let [result (system/stop!
                {:blocked? false
                 :partial? true
                 :http {:stop! (fn [] {:stopped? true :drained? false})}})]
    (is (false? (:stopped? result)))
    (is (= :http (get-in result [:failures 0 :component])))
    (is (false? (:drained? result)))))

(deftest pair-hint-injects-board-and-fleet-summary
  (let [owner (atom {:snapshot (fn [] {:state {:board {:board-version 4}}})})
        fleet-ref (atom ::service)
        observations (var-get #'system/pair-hint-observations)]
    (with-redefs [fleet/observation-summary (fn [_] {:swarms [] :events []})]
      (let [result ((observations owner fleet-ref) {} {})]
        (is (= :snapshot (get-in result [:board :observation :kind])))
        (is (= 4 (get-in result [:board :observation :board-version])))
        (is (= :delta (get-in result [:fleet :observation :kind])))))
    (testing "a fleet failure never removes the board"
      (with-redefs [fleet/observation-summary
                    (fn [_] (throw (ex-info "fleet unavailable" {})))]
        (let [result ((observations owner fleet-ref) {} {})]
          (is (contains? result :board))
          (is (not (contains? result :fleet))))))))

(deftest persisted-model-map-is-normalized-without-character-map-entries
  (testing "a persisted settings document stores models as a map"
    (let [projected (system/model-configuration
                     {:connections
                      [{:id "local-ollama"
                        :kind :openai-compatible
                        :created-at 1000
                        :updated-at 2000
                        :model-order ["qwen3.6:27b"]
                        :models {"qwen3.6:27b"
                                 {:id "qwen3.6:27b"
                                  :model-name "qwen3.6:27b"
                                  :model-entry-id "qwen3.6:27b"
                                  :created-at 1000
                                  :updated-at 2000}}}]
                       :roles {:main {:connection "local-ollama"
                                      :model "qwen3.6:27b"}
                               :task {:connection "local-ollama"
                                      :model "qwen3.6:27b"}}})]
      (is (= {"qwen3.6:27b" {:id "qwen3.6:27b"
                             :model-name "qwen3.6:27b"}}
             (get-in projected [:connections 0 :models]))
          "a model map keeps its ids instead of being reduced from characters")
      (is (false? (contains? (get-in projected [:connections 0]) :created-at))
          "application timestamps are not forwarded to the adapter")
      (is (false? (contains? (get-in projected [:connections 0 :models "qwen3.6:27b"])
                             :created-at)))
      (is (false? (contains? (get-in projected [:connections 0 :models "qwen3.6:27b"])
                             :model-entry-id)))
      (is (true? (model-adapter/model-configured? projected)))))
  (testing "a non-map value is returned unchanged for the adapter to refuse"
    (is (= "raw" (#'system/without-nil-values "raw")))))

(defn- compression-record
  [state updated-at]
  {:compression-id "cmp-1"
   :state state
   :message-ids ["m1" "m2"]
   :applied-at (when (= :applied state) updated-at)
   :updated-at updated-at})

(defn- owner-with-context
  [context]
  {:state {:session {:revision 1 :context context}}})

(deftest merge-owner-context-keeps-the-fresher-compression-record
  (testing "a lagging :applied record cannot overwrite a newer :reverted one"
    (let [base (owner-with-context
                {:compressions {"cmp-1" (compression-record :reverted
                                                           "2026-01-02T00:00:00Z")}
                 :compression-order []})
          other (owner-with-context
                 {:compressions {"cmp-1" (compression-record :applied
                                                            "2026-01-01T00:00:00Z")}
                  :compression-order ["cmp-1"]})
          context (get-in (#'system/merge-owner-context base other)
                          [:state :session :context])]
      (is (= :reverted
             (get-in context [:compressions "cmp-1" :state])))
      (is (= [] (:compression-order context)))))
  (testing "a genuinely newer record on the lagging handle still wins"
    (let [base (owner-with-context
                {:compressions {"cmp-1" (compression-record :reverted
                                                           "2026-01-01T00:00:00Z")}
                 :compression-order []})
          other (owner-with-context
                 {:compressions {"cmp-1" (compression-record :applied
                                                            "2026-01-02T00:00:00Z")}
                  :compression-order ["cmp-1"]})
          context (get-in (#'system/merge-owner-context base other)
                          [:state :session :context])]
      (is (= :applied
             (get-in context [:compressions "cmp-1" :state])))
      (is (= ["cmp-1"] (:compression-order context)))))
  (testing "an applied compression known only to the other handle joins the order"
    (let [base (owner-with-context {:compressions {}
                                    :compression-order []})
          other (owner-with-context
                 {:compressions {"cmp-9" {:compression-id "cmp-9"
                                          :state :applied
                                          :applied-at "2026-01-01T00:00:00Z"
                                          :updated-at "2026-01-01T00:00:00Z"}}
                  :compression-order ["cmp-9"]})
          context (get-in (#'system/merge-owner-context base other)
                          [:state :session :context])]
      (is (= ["cmp-9"] (:compression-order context)))))
  (testing "a context that never had :compression-order keeps the key absent"
    (let [base (owner-with-context {:compressions {"cmp-2" {:compression-id "cmp-2"
                                                             :state :draft}}})
          other (owner-with-context {:compressions {}})
          context (get-in (#'system/merge-owner-context base other)
                          [:state :session :context])]
      (is (false? (contains? context :compression-order)))
      (is (= :draft (get-in context [:compressions "cmp-2" :state]))))))

(deftest pair-request-boundary-fires-on-applied-compression-change
  (let [snapshot (fn []
                   {:state {:session
                            {:context
                             {:compressions
                              {"cmp-1" {:compression-id "cmp-1"
                                        :state :applied
                                        :applied-at "2026-01-01T00:00:00Z"
                                        :updated-at "2026-01-01T00:00:00Z"}}}}}})
        owner-ref (atom {:snapshot snapshot})]
    (testing "an applied set with no cursor emits a :compaction boundary"
      (let [boundary (#'system/pair-request-boundary owner-ref {})]
        (is (= :compaction (:kind boundary)))
        (is (string? (:id boundary)))))
    (testing "the consumed cursor suppresses re-emission"
      (let [boundary (#'system/pair-request-boundary owner-ref {})]
        (is (nil? (#'system/pair-request-boundary
                   owner-ref {:compaction-cursor (:id boundary)})))))
    (testing "an unchanged applied set emits nothing"
      (let [boundary (#'system/pair-request-boundary owner-ref {})]
        (is (nil? (#'system/pair-request-boundary
                   owner-ref {:compaction-cursor (:id boundary)})))))
    (testing "no applied compressions and no cursor emits nothing"
      (let [empty-owner (atom {:snapshot (fn [] {:state {:session {}}})})]
        (is (nil? (#'system/pair-request-boundary empty-owner {})))))))
