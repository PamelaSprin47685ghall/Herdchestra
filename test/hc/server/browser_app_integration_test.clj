(ns hc.server.browser-app-integration-test
  "End-to-end integration test for the managed browser in the real app.

  It starts a local HTTP page, launches an isolated headless Chrome, starts
  `hc.server.app.system` with `[:browser :endpoint]`, and drives the app's
  browser command adapter.  This verifies the actual wiring, not only the
  browser package in isolation.

  The test is defined only when Chrome is available; a missing browser is an
  honest skip."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.system :as system]
            [hc.server.browser :as browser]
            [hc.server.browser.port :as browser-port]
            [hc.server.storage :as storage])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.io File]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- chrome-executable
  []
  (or (System/getenv "HC_CHROME")
      (some #(when (.exists (File. %)) %)
            ["/usr/bin/google-chrome"
             "/usr/bin/google-chrome-stable"
             "/usr/bin/chromium"
             "/usr/bin/chromium-browser"
             "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"])))

(defn- start-page-server!
  []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        html (str "<!doctype html><html><head><title>App Browser</title>"
                  "</head><body><h1>App Step</h1>"
                  "<label for=\"email\">Email</label>"
                  "<input id=\"email\" name=\"email\" type=\"email\" "
                  "oninput=\"document.getElementById('mirror').textContent=this.value\">"
                  "<div id=\"mirror\"></div>"
                  "<button id=\"advance\" type=\"button\" "
                  "onclick=\"document.getElementById('step').textContent='Done'\">"
                  "Advance</button><div id=\"step\">Waiting</div>"
                  "</body></html>")
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (let [bytes (.getBytes ^String html StandardCharsets/UTF_8)]
                      (.add (.getResponseHeaders exchange)
                            "Content-Type" "text/html; charset=utf-8")
                      (.sendResponseHeaders exchange 200 (alength bytes))
                      (with-open [stream (.getResponseBody exchange)]
                        (.write stream bytes)))))]
    (.createContext server "/" handler)
    (.start server)
    server))

(defn- app-config
  [endpoint]
  (let [root (temp-dir "hc-browser-app-root-")
        data (temp-dir "hc-browser-app-data-")
        static (temp-dir "hc-browser-app-static-")]
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
      :models {}
      :browser {:endpoint endpoint}})))

(defn- node-by
  [observation pred]
  (first (filter pred (:nodes observation))))

(when-not (chrome-executable)
  (println "SKIP hc.server.browser-app-integration-test: no Chrome executable found"))

(when (chrome-executable)
  (deftest app-wires-managed-browser
    (let [page-server (start-page-server!)
          base-url (str "http://127.0.0.1:"
                        (.getPort (.getAddress page-server)))
          chrome (browser/launch! {:chrome (chrome-executable)})
          started (system/start! (app-config (:endpoint chrome)))]
      (try
        (is (false? (:blocked? started)) (pr-str (select-keys started [:error])))
        (testing "the app creates one browser service for the session"
          (let [services @(:browser-services started)
                [session-id service] (first services)]
            (is (some? session-id))
            (is (some? service)
                (pr-str (select-keys started [:browser-errors])))
            (testing "the app command adapter opens, observes and acts"
              (let [permission (browser/command!
                                service
                                {:kind :browser/permission
                                 :enabled? true
                                 :scope :site
                                 :sites #{"127.0.0.1"}})
                    opened (browser/command!
                            service
                            {:kind :browser/open-tab
                             :url (str base-url "/form")})
                    tab-id (get-in opened [:receipt :result :tab :tab-id])
                    observed (browser/command!
                              service
                              {:kind :browser/observe
                               :tab-id tab-id})
                    observation (get-in observed [:receipt :result :observation])
                    email (node-by observation #(= "email" (:type %)))
                    typed (browser/command!
                           service
                           {:kind :browser/act
                            :action :type
                            :tab-id tab-id
                            :observation-id (:observation-id observation)
                            :target email
                            :text "app@example.test"})
                    after-type (browser/command!
                                service
                                {:kind :browser/observe
                                 :tab-id tab-id})]
                (is (:ok? permission))
                (is (:ok? opened))
                (is (:ok? observed))
                (is (:ok? typed))
                (is (= :ok (get-in typed [:receipt :result :status])))
                (is (str/includes?
                     (get-in after-type [:receipt :result :observation :text])
                     "app@example.test")))

            (testing "the model tool executor routes to the same browser service"
              (let [exec (browser/routing-executor
                          (browser/executor service)
                          (fn [_] {:status :failed
                                   :error {:kind :wrong-executor}}))
                    tab-id (-> (browser/snapshot service)
                               :tabs keys first)
                    observed (exec {:call-id "tool-observe"
                                    :attempt-id "attempt-1"
                                    :request {:tool-name "browser_observe"
                                              :arguments {:tab-id tab-id}}})
                    observation (get-in observed [:payload :observation])
                    email (node-by observation #(= "email" (:type %)))
                    typed (exec {:call-id "tool-type"
                                 :attempt-id "attempt-1"
                                 :request {:tool-name "browser_type"
                                           :arguments
                                           {:tab-id tab-id
                                            :observation-id
                                            (:observation-id observation)
                                            :target email
                                            :text "tool@example.test"}}})]
                (is (= :succeeded (:status observed)))
                (is (= :succeeded (:status typed)))
                (is (= :ok (get-in typed [:payload :status])))
                (is (str/includes?
                     (get-in (browser/observe! service {:tab-id tab-id})
                             [:observation :text])
                     "tool@example.test"))))

            (testing "takeover blocks actions and resume preserves state"
              (let [tab-id (-> (browser/snapshot service)
                               :tabs
                               keys
                               first)
                    taken (browser/command!
                           service
                           {:kind :browser/takeover :tab-id tab-id})
                    refused (browser/command!
                             service
                             {:kind :browser/act
                              :action :click
                              :tab-id tab-id
                              :observation-id "obs-any"
                              :target {:ref "hc-1"}})
                    resumed (browser/command!
                             service
                             {:kind :browser/resume :tab-id tab-id})]
                (is (:ok? taken))
                (is (:ok? refused))
                (is (= :refused (get-in refused [:receipt :result :status])))
                (is (= :takeover-active
                       (get-in refused [:receipt :result :error :code])))
                (is (:ok? resumed))
                (is (= :agent-active
                       (get-in resumed [:receipt :result :takeover])))
                (is (str/includes?
                     (get-in resumed [:receipt :result :observation :text])
                     "tool@example.test"))))

            (testing "snapshot, cards and health are exposed by the app"
              (let [snapshot (browser/command! service {:kind :browser/snapshot})
                    cards (browser/command! service {:kind :browser/cards})]
                (is (:ok? snapshot))
                (is (:ok? cards))
                (is (pos? (count (get-in cards [:receipt :cards :cards]))))
                (is (true? (:connected? (browser/health service)))))))))
        (finally
          ;; Close the browser first: its live CDP/WebSocket connection can
          ;; keep the app's HTTP/Sente stop from draining, and a failed stop
          ;; must not leave the store open after the test.
          (try (browser-port/close! chrome) (catch Throwable _ nil))
          (try (.stop page-server 0) (catch Throwable _ nil))
          (let [stopped (try (system/stop! started) (catch Throwable _ nil))]
            (when-not (:stopped? stopped)
              ;; Test teardown only: production system/stop! still refuses to
              ;; close a store while a writer is live.  Release the store so
              ;; a component stop failure cannot leak the process-global
              ;; Datalevin runtime, then fail the test honestly.
              (try (storage/close-store! (:store started))
                   (catch Throwable _ nil))
              (throw (ex-info "application stop was not confirmed"
                              {:result stopped})))))))))
