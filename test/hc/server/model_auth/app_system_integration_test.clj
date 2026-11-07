(ns hc.server.model-auth.app-system-integration-test
  "Start the real application shell, route model-auth commands through its
  router, and complete a local OAuth callback.  This proves the adapter is
  mounted, not only unit-tested."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.system :as system]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.ports :as ports]
            [hc.server.model-auth.app :as model-auth-app]
            [hc.server.model-auth.support :as support])
  (:import (java.net ServerSocket)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write-file!
  [path content]
  (let [file (java.io.File. ^String path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content)))

(defn- test-config
  [oauth & [opts]]
  (let [root (temp-dir "hc-auth-app-root-")
        data (temp-dir "hc-auth-app-data-")
        static (temp-dir "hc-auth-app-static-")
        base {:host "127.0.0.1"
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
              :model-auth {:providers (support/providers oauth)
                           :device-poll? false}}]
    (write-file! (str static "/index.html") "<!doctype html><title>hc</title>")
    (config/normalize
     (merge base
            (dissoc opts :model-auth)
            {:model-auth (merge (:model-auth base) (:model-auth opts))}))))

(defn- http-get
  [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))
        request (-> (HttpRequest/newBuilder (java.net.URI/create url))
                    (.GET)
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/discarding))))

(defn- submit!
  [system payload]
  (let [result (atom nil)]
    (ports/submit-command!
     (:router system)
     {:command-id (str (random-uuid))
      :command-epoch 1
      :payload payload}
     #(reset! result %))
    @result))

(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest external-callback-route-is-mounted-before-fallback
  (let [oauth (support/start-oauth-server)
        port (free-port)
        redirect (str "http://127.0.0.1:" port "/model-auth/callback")
        started (system/start! (test-config
                                oauth
                                {:port port
                                 :model-auth {:callback-mode :external
                                              :external-redirect-uri redirect}}))]
    (try
      (is (false? (:blocked? started)))
      (let [started-flow (submit! started
                                  {:kind :model-auth/begin
                                   :connection-id "conn-external"
                                   :provider-id "local"
                                   :flow-type :authorization-code})
            flow (:receipt started-flow)
            location (support/callback-url (:authorize-url flow))
            callback (http-get location)
            status (submit! started {:kind :model-auth/poll
                                     :flow-id (:flow-id flow)})]
        (is (:ok? started-flow))
        (is (str/starts-with? location redirect))
        (is (= 200 (.statusCode callback)))
        (is (= :authorized (get-in status [:receipt :status])))
        (is (= "user-1" (get-in status [:receipt :account :subject]))))
      (testing "the callback route does not shadow ordinary app routes"
        (let [health (http-get (str (:url started) "healthz"))]
          (is (= 200 (.statusCode health)))))
      (finally
        (system/stop! started)
        (support/stop! oauth)))))

(deftest app-shell-routes-model-auth-and-injects-token
  (let [oauth (support/start-oauth-server)
        started (system/start! (test-config oauth))]
    (try
      (is (false? (:blocked? started)))
      (is (some? (:model-auth started)))
      (testing "the router exposes the provider catalog"
        (let [result (submit! started {:kind :model-auth/providers})]
          (is (:ok? result))
          (is (some #(= :local (:id %))
                    (get-in result [:receipt :providers])))
          (is (not (re-find #"client-secret|access-token"
                            (pr-str result))))))
      (testing "a browser callback completes through the app router"
        (let [started-flow (submit! started
                                    {:kind :model-auth/begin
                                     :connection-id "conn-app"
                                     :provider-id "local"
                                     :flow-type :authorization-code})
              flow (:receipt started-flow)
              location (support/callback-url (:authorize-url flow))
              response (http-get location)
              status (submit! started {:kind :model-auth/poll
                                       :flow-id (:flow-id flow)})]
          (is (:ok? started-flow))
          (is (= 200 (.statusCode response)))
          (is (= :authorized (get-in status [:receipt :status])))
          (is (= "user-1" (get-in status [:receipt :account :subject])))))
      (testing "the live app component injects a bearer token into model config"
        (let [{:keys [models errors]}
              (model-auth-app/inject-models
               (:model-auth started)
               {:connections [{:id "conn-app"
                               :service-kind :account-login}]})]
          (is (empty? errors))
          (is (= "access-1" (get-in models [:connections 0 :api-key])))))
      (testing "the gateway baseline carries model-auth providers"
        (let [snapshot (:snapshot (feed/snapshot (:feed (:gateway started))))]
          (is (some #(= :local (:id %))
                    (:model-auth/providers snapshot)))))
      (testing "the app snapshot carries providers and accounts without tokens"
        (let [snapshot (model-auth-app/snapshot (:model-auth started))]
          (is (some #(= :local (:id %)) (:model-auth/providers snapshot)))
          (is (= "user-1"
                 (get-in snapshot [:model-auth/accounts 0 :subject])))
          (is (not (re-find #"access-1|refresh-1" (pr-str snapshot))))))
      (finally
        (system/stop! started)
        (support/stop! oauth)))))
