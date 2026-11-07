(ns hc.server.app.assistant-default-test
  "The built-in assistant identity that lets worldbooks and skills bind in a
  fresh workspace."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.lifecycle-test-support :as lifecycle]
            [hc.server.app.session-config :as session-config]
            [hc.server.app.settings :as settings]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.app.system :as system]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.registry :as registry])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- test-config
  []
  (let [root (temp-dir "hc-assistant-default-")
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
      :models {}})))

(deftest default-assistant-is-created-only-when-none-exist
  (testing "an empty configuration exposes the built-in assistant"
    (let [config (session-config/session-config {:connections []})]
      (is (= #{"default"} (set (keys (:assistants config)))))
      (is (= "default" (get-in config [:assistants "default" :id])))
      (is (= "通用助手" (get-in config [:assistants "default" :name])))
      (is (= 1 (get-in config [:assistants "default" :revision])))))
  (testing "an existing assistant is preserved"
    (let [config (session-config/session-config
                  {:connections []}
                  {"a1" {:id "a1" :name "Helper" :revision 7}})]
      (is (= #{"a1"} (set (keys (:assistants config)))))
      (is (= "Helper" (get-in config [:assistants "a1" :name])))
      (is (= 7 (get-in config [:assistants "a1" :revision])))
      (is (nil? (get-in config [:assistants "default"])))))
  (testing "an existing default assistant is not overwritten"
    (let [config (session-config/session-config
                  {:connections []}
                  {"default" {:id "default" :name "Custom" :revision 3}})]
      (is (= "Custom" (get-in config [:assistants "default" :name])))
      (is (= 3 (get-in config [:assistants "default" :revision]))))))

(deftest new-sessions-bind-the-default-assistant
  (let [config (session-config/session-config {:connections []})
        session (:session (sessions/new-session {:session-id "s1"
                                                 :config config}))]
    (is (= "default" (:assistant-id session)))
    (is (= "default" (:assistant-id (sessions/session-summary session)))))
  (let [config (session-config/session-config
                {:connections []}
                {"a1" {:id "a1" :name "Helper"}})
        session (:session (sessions/new-session {:session-id "s2"
                                                 :config config}))]
    (is (= "a1" (:assistant-id session)))))

(deftest outbound-context-publishes-the-assistant-identity
  (let [opts (#'system/outbound-context-opts
              {:session {:session-id "s1" :assistant-id "default"}
               :materials nil
               :session-config
               (fn []
                 {:assistants {"default" {:id "default"
                                          :name "通用助手"
                                          :revision 1}}})})]
    (is (= {:id "default" :name "通用助手" :revision 1}
           (:assistant opts)))))

(deftest settings-snapshot-exposes-assistants-for-bindings
  (let [component {:state (atom {:config {:connections [] :roles {}}
                                 :document
                                 {:assistants
                                  {"default" {:id "default"
                                              :name "通用助手"
                                              :revision 1}}}})}
        view (settings/snapshot component)]
    (is (= "通用助手" (get-in view [:settings/assistants "default" :name])))
    (is (= 1 (get-in view [:settings/assistants "default" :revision])))
    (is (= ["default"] (:settings/assistant-order view))))
  (testing "an empty settings document still exposes the built-in assistant"
    (let [component {:state (atom {:config {:connections [] :roles {}}
                                   :document {}})}
          view (settings/snapshot component)]
      (is (= "通用助手" (get-in view [:settings/assistants "default" :name])))
      (is (= 1 (get-in view [:settings/assistants "default" :revision])))))
  (testing "the projection also accepts the config map shape directly"
    (let [view (snapshot/settings-view
                {:assistants {"a1" {:assistant-id "a1" :name "Helper"}}})]
      (is (= "Helper" (get-in view [:settings/assistants "a1" :name])))
      (is (= 1 (get-in view [:settings/assistants "a1" :revision]))))))

(deftest a-fresh-data-dir-starts-with-a-bound-default-assistant
  (let [started (system/start! (test-config))]
    (try
      (let [sessions (:sessions (registry/list-sessions
                                 (:registry started)
                                 {:include-deleted? false}))
            summary (first sessions)
            session (:session (registry/get-session
                               (:registry started)
                               (:session-id summary)))]
        (is (some? session))
        (is (= "default" (:assistant-id session)))
        (is (= "default" (:assistant-id summary))
            "the client summary carries the assistant identity"))
      (finally
        (lifecycle/stop-system! started)))))
