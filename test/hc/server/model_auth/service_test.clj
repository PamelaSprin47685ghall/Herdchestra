(ns hc.server.model-auth.service-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter :as adapter]
            [hc.server.model-adapter.config :as model-config]
            [hc.server.model-auth.oauth :as oauth]
            [hc.server.model-auth.service :as service]
            [hc.server.model-auth.store :as store]
            [hc.server.model-auth.support :as support])
  (:import (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(defn- temp-dir
  []
  (Files/createTempDirectory "hc-model-auth-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [p (reverse (vec (iterator-seq (.iterator stream))))]
        (try (Files/deleteIfExists p) (catch Exception _ nil))))))

(defn- http-get
  [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))
        request (-> (HttpRequest/newBuilder (java.net.URI/create url))
                    (.GET)
                    (.build))
        response (.send client request
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)
     :location (some-> (.firstValue (.headers response) "location")
                       (.orElse nil))}))

(defn- raw-file
  [^Path path]
  (String. (Files/readAllBytes path) StandardCharsets/ISO_8859_1))

(defn- permission-string
  [^Path path]
  (PosixFilePermissions/toString (Files/getPosixFilePermissions path (make-array LinkOption 0))))

(defn- begin-flow!
  [svc & [opts]]
  (service/handle! svc (merge {:kind :model-auth/begin
                               :connection-id "conn-1"
                               :provider-id "local"
                               :flow-type :authorization-code}
                              opts)))

(defn- tree-key?
  [value key]
  (cond
    (map? value) (or (contains? value key)
                     (some #(tree-key? % key) (vals value)))
    (sequential? value) (boolean (some #(tree-key? % key) value))
    :else false))

(deftest authorization-code-pkce-flow-test
  (let [dir (temp-dir)
        oauth (support/start-oauth-server)
        svc (service/start! {:data-dir (str dir)
                             :providers (support/providers oauth)
                             :env {}
                             :device-poll? false})]
    (try
      (let [started (begin-flow! svc)
            receipt (:receipt started)]
        (is (:ok? started))
        (is (= :pending (:status receipt)))
        (is (string? (:authorize-url receipt)))
        (is (not (contains? receipt :access-token)))
        (is (not (contains? receipt :refresh-token)))
        (is (not (contains? receipt :device-code)))
        (is (not (contains? receipt :client-secret)))
        (let [location (support/callback-url (:authorize-url receipt))]
          (is (string? location))
          (is (str/starts-with? location "http://127.0.0.1:"))
          (let [callback (http-get location)]
            (is (= 200 (:status callback)))
            (is (str/includes? (:body callback) "Authorization received"))))
        (let [status (service/handle! svc {:kind :model-auth/poll
                                           :flow-id (:flow-id receipt)})
              state (:receipt status)]
          (is (:ok? status))
          (is (= :authorized (:status state)))
          (is (= "alice@example.test"
                 (get-in state [:account :account-label])))
          (is (= "user-1" (get-in state [:account :subject])))
          (is (= :local (get-in state [:account :provider-id])))
          (is (not (contains? state :access-token)))
          (is (not (contains? state :refresh-token))))
        (let [token (service/access-token svc "conn-1")]
          (is (:ok? token))
          (is (= "access-1" (:token token)))
          (is (= "user-1" (:subject token))))
        (let [credentials (java.io.File. (str dir) "model-auth/credentials.edn.enc")
              key-file (java.io.File. (str dir) "model-auth/key.bin")]
          (is (.isFile credentials))
          (is (.isFile key-file))
          (is (= "rw-------" (permission-string (.toPath credentials))))
          (is (= "rw-------" (permission-string (.toPath key-file))))
          (is (not (str/includes? (raw-file (.toPath credentials))
                                  "access-1")))
          (is (not (str/includes? (raw-file (.toPath credentials))
                                  "refresh-1"))))
        (testing "a tampered state does not complete the flow"
          (let [started (begin-flow! svc)
                flow (:receipt started)
                location (support/callback-url (:authorize-url flow))
                tampered (str/replace location #"state=[^&]+" "state=wrong")
                callback (http-get tampered)]
            (is (= 400 (:status callback)))
            (is (= :pending
                   (:status (:receipt
                             (service/handle! svc {:kind :model-auth/poll
                                                   :flow-id (:flow-id flow)})))))))
          (testing "the same listener still accepts the correct state"
            (let [started (begin-flow! svc)
                  flow (:receipt started)
                  location (support/callback-url (:authorize-url flow))
                  tampered (str/replace location #"state=[^&]+" "state=wrong")]
              (is (= 400 (:status (http-get tampered))))
              (is (= 200 (:status (http-get location))))
              (is (= :authorized
                     (:status (:receipt
                               (service/handle!
                                svc {:kind :model-auth/poll
                                     :flow-id (:flow-id flow)})))))))
        (testing "the credential survives a service restart"
          (service/stop! svc)
          (let [restarted (service/start! {:data-dir (str dir)
                                           :providers (support/providers oauth)
                                           :env {}
                                           :device-poll? false})]
            (try
              (let [token (service/access-token restarted "conn-1")]
                (is (:ok? token))
                (is (= "access-1" (:token token))))
              (finally (service/stop! restarted))))))
      (finally
        (service/stop! svc)
        (support/stop! oauth)
        (delete-tree! dir)))))

(deftest device-code-flow-test
  (let [oauth (support/start-oauth-server)
        svc (service/start! {:providers (support/providers oauth)
                             :env {}
                             :device-poll? false})]
    (try
      (let [started (service/handle! svc {:kind :model-auth/begin
                                          :connection-id "conn-device"
                                          :provider-id "local"
                                          :flow-type :device-code})
            flow (:receipt started)]
        (is (:ok? started))
        (is (= :pending (:status flow)))
        (is (= "ABCD-EFGH" (:user-code flow)))
        (is (string? (:verification-uri flow)))
        (is (not (contains? flow :device-code)))
        (is (not (contains? flow :access-token)))
        (let [pending (service/handle! svc {:kind :model-auth/poll
                                            :flow-id (:flow-id flow)})]
          (is (= :pending (:status (:receipt pending))))
          (is (pos? (:device-polls @(:state oauth)))))
        (swap! (:state oauth) assoc :device-approved? true)
        (let [authorized (service/handle! svc {:kind :model-auth/poll
                                               :flow-id (:flow-id flow)})
              receipt (:receipt authorized)]
          (is (= :authorized (:status receipt)))
          (is (= "alice@example.test"
                 (get-in receipt [:account :account-label]))))
        (let [token (service/access-token svc "conn-device")]
          (is (:ok? token))
          (is (= "access-device" (:token token))))
        (let [logged-out (service/handle! svc {:kind :model-auth/logout
                                               :connection-id "conn-device"})]
          (is (= :logged-out (get-in logged-out [:receipt :status])))
          (is (= :not-connected
                 (:status (:receipt (service/handle!
                                     svc {:kind :model-auth/status
                                          :connection-id "conn-device"})))))
          (is (= "refresh-device"
                 (:token (:revoked @(:state oauth)))))))
      (finally
        (service/stop! svc)
        (support/stop! oauth)))))

(deftest external-callback-route-test
  (let [oauth (support/start-oauth-server)
        redirect "http://127.0.0.1:9999/model-auth/callback"
        svc (service/start! {:providers (support/providers oauth)
                             :env {}
                             :callback-mode :external
                             :external-redirect-uri redirect
                             :device-poll? false})]
    (try
      (let [started (begin-flow! svc)
            flow (:receipt started)
            location (support/callback-url (:authorize-url flow))
            _ (is (str/starts-with? location redirect))
            query (second (str/split location #"\?" 2))
            handler (service/ring-handler svc)
            response (handler {:uri "/model-auth/callback"
                               :request-method :get
                               :query-string query})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Authorization received"))
        (is (= "no-store"
               (get-in response [:headers "cache-control"])))
        (is (not (re-find #"state=|code=|access-token|refresh-token"
                          (:body response))))
        (let [status (service/handle! svc {:kind :model-auth/poll
                                           :flow-id (:flow-id flow)})]
          (is (= :authorized (:status (:receipt status))))
          (is (= "user-1"
                 (get-in status [:receipt :account :subject])))))
      (finally
        (service/stop! svc)
        (support/stop! oauth)))))

(deftest password-and-identity-conflict-test
  (let [oauth (support/start-oauth-server)
        providers (assoc (support/providers oauth)
                         :local2 (support/provider oauth {:id :local2
                                                         :name "Other"}))
        svc (service/start! {:providers providers :env {} :device-poll? false})]
    (try
      (let [refused (begin-flow! svc {:password "hunter2"})]
        (is (false? (:ok? refused)))
        (is (= :MODEL_AUTH_PASSWORD_FORBIDDEN (get-in refused [:error :code])))
        (is (empty? (support/requests-for oauth "/authorize"))))
      (let [refused (service/handle! svc {:kind :model-auth/status
                                          :connection-id "conn-1"
                                          :access-token "leak"})]
        (is (false? (:ok? refused)))
        (is (= :MODEL_AUTH_PASSWORD_FORBIDDEN (get-in refused [:error :code]))))
      (let [started (begin-flow! svc)
            flow (:receipt started)
            location (support/callback-url (:authorize-url flow))]
        (http-get location)
        (is (= :authorized
               (:status (:receipt (service/handle!
                                   svc {:kind :model-auth/poll
                                        :flow-id (:flow-id flow)}))))))
      (let [conflict (begin-flow! svc {:provider-id "local2"})]
        (is (false? (:ok? conflict)))
        (is (= :MODEL_AUTH_IDENTITY_CONFLICT
               (get-in conflict [:error :code])))
        (is (= :local (get-in conflict [:error :details :existing
                                         :provider-id]))))
      (let [replaced (begin-flow! svc {:provider-id "local2"
                                       :replace? true})
            flow (:receipt replaced)
            location (support/callback-url (:authorize-url flow))]
        (http-get location)
        (is (= :authorized
               (:status (:receipt (service/handle!
                                   svc {:kind :model-auth/poll
                                        :flow-id (:flow-id flow)})))))
        (is (= :local2
               (:provider-id (store/read-credential (:store svc) "conn-1")))))
      (finally
        (service/stop! svc)
        (support/stop! oauth)))))

(deftest refresh-preserves-identity-test
  (let [oauth (support/start-oauth-server)
        svc (service/start! {:providers (support/providers oauth)
                             :env {}
                             :device-poll? false})]
    (try
      (let [started (begin-flow! svc)
            flow (:receipt started)
            location (support/callback-url (:authorize-url flow))]
        (http-get location)
        (service/handle! svc {:kind :model-auth/poll :flow-id (:flow-id flow)})
        (let [credential (store/read-credential (:store svc) "conn-1")
              expired (assoc credential :expires-at-ms 0)]
          (store/write-credential! (:store svc) "conn-1" expired))
        (let [token (service/access-token svc "conn-1")
              credential (store/read-credential (:store svc) "conn-1")]
          (is (:ok? token))
          (is (= "access-2" (:token token)))
          (is (= "refresh-2" (:refresh-token credential)))
          (is (= "user-1" (:subject credential)))
          (is (= :local (:provider-id credential)))
          (is (= "alice@example.test" (:account-label credential)))))
      (finally
        (service/stop! svc)
        (support/stop! oauth)))))

(deftest inject-access-tokens-test
  (let [oauth (support/start-oauth-server)
        svc (service/start! {:providers (support/providers oauth)
                             :env {}
                             :device-poll? false})]
    (try
      (let [started (begin-flow! svc)
            flow (:receipt started)
            location (support/callback-url (:authorize-url flow))]
        (http-get location)
        (service/handle! svc {:kind :model-auth/poll :flow-id (:flow-id flow)})
        (let [result (service/inject-access-tokens
                      svc
                      {:connections [{:id "conn-1"
                                      :kind :oauth
                                      :service-kind :account-login
                                      :models [{:id "m"
                                                :model-name "m"}]}]
                       :roles {:main {:connection "conn-1"
                                      :model "m"}}})]
          (is (= "access-1"
                 (get-in result [:config :connections 0 :api-key])))
          (is (= :connected
                 (get-in result [:config :connections 0 :auth-status])))
          (is (= :openai-compatible
                 (get-in result [:config :connections 0 :kind])))
          (is (= (str (:base oauth) "/v1")
                 (get-in result [:config :connections 0 :base-url])))
          (is (map? (model-config/normalize-config (:config result)))
              "the injected connection is accepted by the model adapter")
          (let [port (adapter/create-model-port (:config result))]
            (is (some? (adapter/stats port)))
            (adapter/stop-port! port))
          (is (empty? (:errors result)))
          (is (not (tree-key? (:config result) :refresh-token)))
          (is (not (tree-key? (:config result) :client-secret)))))
      (finally
        (service/stop! svc)
        (support/stop! oauth)))))

(deftest unsafe-provider-endpoint-test
  (is (thrown? clojure.lang.ExceptionInfo
               (service/start! {:providers {:bad
                                            {:id :bad
                                             :name "Bad"
                                             :authorization-endpoint
                                             "http://example.com/authorize"
                                             :token-endpoint
                                             "http://example.com/token"
                                             :flows #{:authorization-code}
                                             :client-id "x"}}
                                :env {}}))))

(deftest callback-query-is-bounded
  (is (false? (:ok? (oauth/parse-callback-pairs
                     "state=x&unexpected=1"))))
  (is (= "%ZZ"
         (get-in (oauth/parse-callback-pairs "state=%ZZ")
                 [:pairs :state]))
      "a malformed percent escape is kept as text instead of throwing")
  (let [oauth-server (support/start-oauth-server)
        svc (service/start! {:providers (support/providers oauth-server)
                             :env {}
                             :device-poll? false})]
    (try
      (let [started (begin-flow! svc)
            flow (:receipt started)
            location (support/callback-url (:authorize-url flow))]
        (is (= 400 (:status (http-get (str location "&unexpected=1")))))
        (is (= :pending
               (:status (:receipt
                         (service/handle! svc {:kind :model-auth/poll
                                               :flow-id (:flow-id flow)})))))
        (is (= 200 (:status (http-get location)))
            "an unknown callback field does not close the listener")
        (is (= :authorized
               (:status (:receipt
                         (service/handle! svc {:kind :model-auth/poll
                                               :flow-id (:flow-id flow)}))))))
      (finally
        (service/stop! svc)
        (support/stop! oauth-server)))))

(deftest inject-failure-drops-a-stale-api-key
  (let [svc (service/start! {:providers {}
                             :env {}
                             :device-poll? false})]
    (try
      (let [result (service/inject-access-tokens
                    svc
                    {:connections [{:id "missing"
                                    :service-kind :account-login
                                    :api-key "stale-token"}]})
            connection (first (get-in result [:config :connections]))]
        (is (not (contains? connection :api-key)))
        (is (= :reauthorization-required (:auth-status connection)))
        (is (contains? (:errors result) "missing")))
      (finally
        (service/stop! svc)))))
