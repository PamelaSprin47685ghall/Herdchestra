(ns hc.server.app.send-command-test
  "The process entry's command submission reaches the real router.

  `--send-command` failed with SINGLETON_SUBMIT_FAILED because the entry
  looked up `:submit-command!` as a map key on a defrecord.  The record
  implements `OwnerPort`; only the protocol function reaches the method.
  This test starts the real system (no transport mock) and submits through
  the same private function the CLI path calls."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app :as app]
            [hc.server.app.config :as config]
            [hc.server.app.lifecycle-test-support :as lifecycle]
            [hc.server.app.system :as system]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.registry :as registry])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- test-config
  ([]
   (test-config {}))
  ([models]
  (let [root (temp-dir "hc-send-command-")
        data (str root "/data")
        static (str root "/static")]
    (let [index (java.io.File. (str static "/index.html"))]
      (.mkdirs (.getParentFile index))
      (spit index "<!doctype html><title>hc</title>"))
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root (str root "/work")
      :static-root static
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :models models}))))

(deftest send-command-reaches-the-real-router
  (let [config (test-config)
        started (system/start! config)]
    (try
      (is (false? (:blocked? started)))
      (testing "the CLI submission path returns an accepted receipt"
        (let [result (#'app/submit-command! (:router started)
                                            {:kind :session/create
                                             :title "cli command"})]
          (is (true? (:ok? result)) (pr-str result))
          (is (= :accepted
                 (or (get-in result [:receipt :status])
                     (get-in result [:receipt :receipt :status])
                     (get-in result [:receipt :session :status])))
              (pr-str result))))
      (finally
        (lifecycle/stop-system! started)))))

(def ^:private ollama-models
  {:connections [{:id "local-ollama"
                  :name "Local Ollama"
                  :kind :ollama
                  :base-url "http://127.0.0.1:11434"
                  :enabled? true
                  :models [{:id "qwen"
                            :model-name "qwen"
                            :display-name "Qwen"
                            :enabled? true}]}]
   :roles {:main {:connection "local-ollama" :model "qwen"}}})

(deftest new-session-binds-the-default-main-model
  (let [started (system/start! (test-config ollama-models))]
    (try
      (is (true? (get-in (system/health started) [:models :configured?])))
      (let [result (#'app/submit-command! (:router started)
                                          {:kind :session/create
                                           :title "bound"})
            sessions (:sessions (registry/list-sessions (:registry started)
                                                        {:include-deleted? false}))
            summary (last (sort-by :created-at sessions))
            session (:session (registry/get-session (:registry started)
                                                    (:session-id summary)))]
        (is (true? (:ok? result)) (pr-str result))
        (is (some? session))
        (is (= "local-ollama"
               (get-in session [:model-binding :connection-id])))
        (is (= "qwen"
               (get-in session [:model-binding :model-entry-id])))
        (is (= "local-ollama"
               (get-in (sessions/session-summary session)
                       [:model-binding :connection-id]))
            "the client summary must carry the binding identity"))
      (finally (lifecycle/stop-system! started)))))

(deftest new-session-stays-unbound-without-a-model
  (let [started (system/start! (test-config))]
    (try
      (let [result (#'app/submit-command! (:router started)
                                          {:kind :session/create
                                           :title "unbound"})
            sessions (:sessions (registry/list-sessions (:registry started)
                                                        {:include-deleted? false}))
            summary (last (sort-by :created-at sessions))
            session (:session (registry/get-session (:registry started)
                                                    (:session-id summary)))]
        (is (true? (:ok? result)) (pr-str result))
        (is (nil? (:model-binding session)))
        (is (false? (get-in (system/health started) [:models :configured?]))))
      (finally (lifecycle/stop-system! started)))))

(deftest startup-default-session-binds-the-main-model
  (let [started (system/start! (test-config ollama-models))]
    (try
      (let [sessions (:sessions (registry/list-sessions (:registry started)
                                                        {:include-deleted? false}))
            summary (first sessions)
            session (:session (registry/get-session (:registry started)
                                                    (:session-id summary)))]
        (is (= "New session" (:title session)))
        (is (= "local-ollama"
               (get-in session [:model-binding :connection-id]))
            "the startup default session must bind the configured main model")
        (is (= "qwen" (get-in session [:model-binding :model-entry-id])))
        (is (= "local-ollama"
               (get-in summary [:model-binding :connection-id]))
            "the client summary carries the binding identity"))
      (finally (lifecycle/stop-system! started)))))

(deftest memory-suggest-command-is-routed
  (let [started (system/start! (test-config ollama-models))]
    (try
      (let [session (first (:sessions (registry/list-sessions
                                       (:registry started)
                                       {:include-deleted? false})))
            result (#'app/submit-command! (:router started)
                                          {:kind :materials/memory/suggest
                                           :session-id (:session-id session)})]
        (is (not= :SESSION_NAMESPACE_UNKNOWN (get-in result [:error :code]))
            "materials is a routed session namespace")
        (is (not= :COMMAND_KIND_REQUIRED (get-in result [:error :code])))
        (is (= :MEMORY_SUGGEST_NO_MESSAGES (get-in result [:error :code]))
            (str "the materials handler ran and refused the empty range: "
                 (pr-str result))))
      (finally (lifecycle/stop-system! started)))))

(deftest diagnostics-identity-derives-the-service-from-the-connection
  (let [identity ((resolve 'hc.server.app.system/diagnostics-identity)
                  (fn []
                    {:connections
                     {"local-ollama"
                      {:name "Local Ollama"
                       :models {"qwen" {:display-name "Qwen"}}}}}))
        projected (identity {:snapshot {:session-id "s1"
                                        :connection "local-ollama"
                                        :model "qwen"}}
                            nil)]
    (is (= "local-ollama" (:service-id projected)))
    (is (= "Local Ollama" (:service-name projected)))
    (is (= "Qwen" (:model projected)))
    (is (= "s1" (:session-id projected)))))

(deftest branch-session-is-registered-in-the-client-view
  (let [started (system/start! (test-config ollama-models))]
    (try
      (let [source (first (:sessions (registry/list-sessions
                                      (:registry started)
                                      {:include-deleted? false})))
            result (#'app/submit-command!
                    (:router started)
                    {:kind :history/branch
                     :session-id (:session-id source)})
            branch-id (get-in result [:receipt :session-id])
            listed (:sessions (registry/list-sessions
                               (:registry started)
                               {:include-deleted? false}))
            branch (get-in (registry/get-session (:registry started) branch-id)
                           [:session])]
        (is (true? (:ok? result)) (pr-str result))
        (is (string? branch-id))
        (is (some #(= branch-id (:session-id %)) listed)
            "the registry lists the branch")
        (is (some? (get @(:owners started) branch-id))
            "the application owners map includes the branch")
        (is (= (:session-id source) (:origin-session-id branch)))
        (let [owner (get @(:owners started) branch-id)
              epoch (get-in ((:snapshot owner))
                            [:state :commands :command-epoch])]
          (is (pos-int? epoch))
          (is (= epoch (get-in result [:receipt :session :command-epoch]))
              "the branch receipt carries the Owner command epoch")))
      (finally (lifecycle/stop-system! started)))))
