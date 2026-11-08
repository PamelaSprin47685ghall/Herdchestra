(ns hc.server.app.settings-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.app.settings :as settings]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.model-auth.service :as model-auth-service]
            [hc.server.model-auth.store :as model-auth-store]
            [hc.server.sessions.credentials :as credentials]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.secrets :as secrets]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as storage-support])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- memory-persistence
  "A revision-aware config store for the settings service."
  ([]
   (memory-persistence 0))
  ([revision]
   (memory-persistence revision {}))
  ([revision initial]
   (let [state (atom (merge {:connections [] :revision revision} initial))]
     (reify ports/SessionPersistence
      (read-session [_ _] {:status :missing})
      (commit-session! [_ _ _]
        {:status :unknown
         :error {:code :TEST_PERSISTENCE_UNSUPPORTED
                 :message "session persistence is not used in this test"}})
      (list-session-ids [_] {:status :ok :ids []})
      (delete-session! [_ _ _] {:status :missing})
      (read-config [_] {:status :found :config @state})
      (commit-config! [_ expected config]
        (let [actual (long (or (:revision @state) 0))]
          (if (not= actual (long (or expected 0)))
            {:status :conflict :revision actual}
            (let [next (assoc config :revision (inc actual))]
              (reset! state next)
              {:status :committed :revision (:revision next)}))))))))

(defn- service
  ([]
   (service {}))
  ([{:keys [config revision]
     :or {config {:connections [] :revision 0}
          revision 0}}]
   (let [store (secrets/memory-secret-store)]
     [store
      (settings/service {:persistence (memory-persistence revision)
                         :config config
                         :secret-store store})])))

(defn- fields
  [name]
  {:id "conn-1"
   :name name
   :base-url "https://api.example.com/v1"
   :kind :openai-compatible
   :service-kind :cloud-key
   :key-strategy :priority
   :models []})

(defn- projection-model
  [id display-name]
  {:id id
   :model-id id
   :model-name (str id "-provider")
   :provider-model-id (str id "-provider")
   :name id
   :display-name display-name
   :full-id (str "vendor/" id)
   :capabilities {:text true}
   :context-window 128000
   :max-output-tokens 4096
   :pricing {:free? true}
   :availability :available
   :enabled? true
   :alias "slow"
   :notes "keep"
   :source :listed})

(def ^:private same-name-connections-config
  {:connections
   [{:id "conn-a"
     :name "Same provider"
     :kind :openai-compatible
     :base-url "https://a.example.com/v1"
     :models [(projection-model "stub-model" "Stub Slow Model")]}
    {:id "conn-b"
     :name "Same provider"
     :kind :openai-compatible
     :base-url "https://b.example.com/v1"
     :models [(projection-model "stub-model" "Stub Slow Model")]}]
   :roles {:main {:connection "conn-a"
                  :model "stub-model"
                  :mode :explicit
                  :confirmed? true}}})

(deftest settings-view-preserves-model-display-metadata
  (let [view (snapshot/settings-view same-name-connections-config)]
    (testing "the configured display name survives the projection"
      (is (= "Stub Slow Model"
             (get-in view [:settings/models "conn-a" 0 :display-name])))
      (is (= "stub-model"
             (get-in view [:settings/models "conn-a" 0 :id])))
      (is (= "stub-model"
             (get-in view [:settings/models "conn-a" 0 :model-id])))
      (is (= "stub-model"
             (get-in view [:settings/models "conn-a" 0 :name]))
          "the legacy :name field is preserved")
      (is (= "vendor/stub-model"
             (get-in view [:settings/models "conn-a" 0 :full-id])))
      (is (= "slow"
             (get-in view [:settings/models "conn-a" 0 :alias])))
      (is (= "keep"
             (get-in view [:settings/models "conn-a" 0 :notes])))
      (is (true? (get-in view [:settings/models "conn-a" 0 :enabled?])))
      (is (= {:free? true}
             (get-in view [:settings/models "conn-a" 0 :pricing])))
      (is (= 128000
             (get-in view [:settings/models "conn-a" 0 :context-window])))
      (is (= 4096
             (get-in view [:settings/models "conn-a" 0 :max-output-tokens])))
      (is (= {:text true}
             (get-in view [:settings/models "conn-a" 0 :capabilities]))))
    (testing "same-named connections remain separate identities"
      (is (= #{"conn-a" "conn-b"}
             (set (keys (:settings/models view)))))
      (is (= "Same provider"
             (get-in view [:settings/by-id "conn-a" :name])))
      (is (= "Same provider"
             (get-in view [:settings/by-id "conn-b" :name])))
      (is (= "https://a.example.com/v1"
             (get-in view [:settings/by-id "conn-a" :base-url])))
      (is (= "https://b.example.com/v1"
             (get-in view [:settings/by-id "conn-b" :base-url]))))
    (testing "role aliases are still normalized"
      (is (= "conn-a"
             (get-in view [:settings/roles :main :connection-id])))
      (is (= "stub-model"
             (get-in view [:settings/roles :main :model-id]))))
    (testing "a launch set declaration survives the wire projection"
      ;; The launch config writes `:capabilities #{:tools}`; the client's
      ;; capability projection treats the set as declared support.  Losing
      ;; the set here would render the tools badge as `尚未确认`.
      (let [view (snapshot/settings-view
                  {:connections [{:id "launch"
                                  :name "Launch"
                                  :kind :openai-compatible
                                  :base-url "http://127.0.0.1:20321/v1"
                                  :models [{:id "same-model"
                                            :model-name "same-model"
                                            :display-name "Same Model"
                                            :capabilities #{:tools}}]}]})]
        (is (= #{:tools}
               (get-in view [:settings/models "launch" 0 :capabilities])))))))

(deftest list-models-receipt-preserves-model-display-metadata
  (let [component {:state (atom {:config same-name-connections-config})}
        receipt (:receipt (settings/handle!
                           component
                           {:kind :settings/list-models}))]
    (is (= :configured-list-only (:model-list-status receipt)))
    (is (= "Stub Slow Model"
           (get-in receipt [:connections "conn-a" 0 :display-name])))
    (is (= "stub-model"
           (get-in receipt [:connections "conn-b" 0 :id])))
    (is (= "slow"
           (get-in receipt [:connections "conn-b" 0 :alias])))))

(deftest settings-view-accepts-map-shaped-model-collections
  (let [view (snapshot/settings-view
              {:connections
               [{:id "conn-map"
                 :name "Map provider"
                 :models {"map-model" {:display-name "Mapped Model"
                                       :capabilities {:text true}}}}]})]
    (is (= "map-model"
           (get-in view [:settings/models "conn-map" 0 :id])))
    (is (= "Mapped Model"
           (get-in view [:settings/models "conn-map" 0 :display-name])))))

(deftest save-connection-persists-user-declared-model-capabilities
  (let [[_ component] (service)
        model {:id "manual-model"
               :model-id "manual-model"
               :model-name "manual-model"
               :name "Manual Model"
               :display-name "Manual Model"
               :enabled? true
               :source :manual
               :availability :unconfirmed
               :capabilities {:tools true}
               :capability-sources {:tools :user}
               :capability-baseline {}}
        saved (settings/handle!
               component
               {:kind :settings/save-connection
                :fields (assoc (fields "Provider")
                               :id "conn-a"
                               :models [model])
                :keys []
                :credentials []})
        config (settings/config component)
        view (snapshot/settings-view config)
        saved-model (get-in config [:connections 0 :models 0])
        view-model (get-in view [:settings/models "conn-a" 0])]
    (is (:ok? saved))
    (is (true? (get-in saved-model [:capabilities :tools]))
        "the explicit user declaration survives persistence")
    (is (= :user (get-in saved-model [:capability-sources :tools])))
    (is (= {} (:capability-baseline saved-model)))
    (is (true? (get-in view-model [:capabilities :tools])))
    (is (= :user (get-in view-model [:capability-sources :tools])))
    (is (= {} (:capability-baseline view-model)))))

(deftest settings-view-publishes-credential-vector
  (let [view (snapshot/settings-view
              {:connections
               [{:id "conn"
                 :name "Provider"
                 :base-url "https://api.example.com/v1"
                 :has-key? true
                 :credentials
                 [{:credential-id "k1" :alias "one" :enabled? true
                   :priority 1 :masked-key "sk-...1"}
                  {:credential-id "k2" :alias "two" :enabled? false
                   :priority 2 :masked-key "sk-...2"}]
                 :models []}]})]
    (is (= ["k1" "k2"]
           (mapv :credential-id
                 (get-in view [:settings/by-id "conn" :credentials])))
        "the client must receive every saved key, not one synthetic key")
    (is (true? (get-in view [:settings/by-id "conn" :has-key?])))))

(defn- with-http-server
  "Run F with an Authorization recorder and a loopback base URL."
  [f]
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (let [headers (.getRequestHeaders ^HttpExchange exchange)]
           (swap! seen conj (.getFirst headers "Authorization")))
         (.sendResponseHeaders ^HttpExchange exchange 200 0)
         (.close ^HttpExchange exchange))))
    (.start server)
    (try
      (f seen (str "http://127.0.0.1:"
                   (.getPort (.getAddress server))))
      (finally
        (.stop server 0)))))

(defn- with-model-server
  "Run F with a fake OpenAI-compatible service.

  HANDLER receives `{:method :path :headers :body}` and returns `{:status n
  :body string}`.  SEEN records every request.  The base URL includes `/v1`,
  so a correct probe calls `/v1/models` and `/v1/chat/completions`."
  [handler f]
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (let [headers (into {}
                             (map (fn [[name values]]
                                    [(str/lower-case (str name))
                                     (vec values)]))
                             (.getRequestHeaders ^HttpExchange exchange))
               body (String. (.readAllBytes (.getRequestBody
                                             ^HttpExchange exchange))
                             StandardCharsets/UTF_8)
               request {:method (.getRequestMethod ^HttpExchange exchange)
                        :path (.getPath (.getRequestURI ^HttpExchange exchange))
                        :headers headers
                        :body body}
               response (handler request)
               bytes (.getBytes (str (:body response ""))
                                StandardCharsets/UTF_8)]
           (swap! seen conj request)
           (.sendResponseHeaders ^HttpExchange exchange
                                 (int (or (:status response) 200))
                                 (alength bytes))
           (with-open [output (.getResponseBody ^HttpExchange exchange)]
             (.write output bytes))))))
    (.start server)
    (try
      (f seen (str "http://127.0.0.1:"
                   (.getPort (.getAddress server))
                   "/v1"))
      (finally
        (.stop server 0)))))

(defn- save-connection!
  [component connection keys credentials]
  (settings/handle!
   component
   {:kind :settings/save-connection
    :fields connection
    :keys keys
    :credentials credentials
    :clear-credentials []}))

(defn- oauth-component
  [credentials]
  (model-auth-service/start!
   {:store (model-auth-store/memory-store
            {:credentials credentials})
    :providers {}
    :env {}}))

(deftest saving-an-existing-connection-preserves-credentials
  (let [[store component] (service)
        first-save (settings/handle!
                    component
                    {:kind :settings/save-connection
                     :fields (fields "Provider")
                     :keys [{:key-id "k1" :alias "main"
                             :enabled? true :priority 1}]
                     :credentials [{:key-id "k1" :secret "sk-one"}]
                     :clear-credentials []})
        second-save (settings/handle!
                     component
                     {:kind :settings/save-connection
                      :fields (fields "Renamed")
                      :keys [{:key-id "k1" :alias "primary"
                              :enabled? true :priority 3}]
                      :credentials []
                      :clear-credentials []})
        connection (first (:connections (settings/config component)))
        resolved (credentials/resolve-credential store connection 5000)]
    (is (:ok? first-save))
    (is (:ok? second-save))
    (is (= "Renamed" (:name connection)))
    (is (= #{"k1"} (set (keys (:credentials connection)))))
    (is (= "primary" (get-in connection [:credentials "k1" :alias])))
    (is (= 3 (get-in connection [:credentials "k1" :priority])))
    (is (= "sk-one" (:secret resolved)))))

(deftest concurrent-settings-writes-are-serialized
  (let [base (memory-persistence 0 {:connections [(fields "Provider")]})
        in-flight (atom 0)
        max-in-flight (atom 0)
        slow (reify ports/SessionPersistence
               (read-session [_ session-id]
                 (ports/read-session base session-id))
               (commit-session! [_ expected-revision session]
                 (ports/commit-session! base expected-revision session))
               (list-session-ids [_]
                 (ports/list-session-ids base))
               (delete-session! [_ expected-revision session-id]
                 (ports/delete-session! base expected-revision session-id))
               (read-config [_]
                 (ports/read-config base))
               (commit-config! [_ expected-revision config]
                 (let [now (swap! in-flight inc)]
                   (swap! max-in-flight max now)
                   (try
                     (Thread/sleep 40)
                     (ports/commit-config! base expected-revision config)
                     (finally
                       (swap! in-flight dec))))))
        component (settings/service
                   {:persistence slow
                    :config {:connections [(fields "Provider")]}})
        saves [(future
                (settings/handle!
                 component
                 {:kind :settings/save-connection
                  :fields (fields "Renamed")}))
               (future
                (settings/handle!
                 component
                 {:kind :settings/save-role
                  :roles {:main {:connection "conn-1" :model "m1"}}}))]
        results (mapv deref saves)]
    (is (every? :ok? results))
    (is (= 1 @max-in-flight)
        "settings writes must not overlap inside the document commit")))

(deftest settings-save-recovers-persisted-config-revision
  ;; `app.system/effective-config` merges the model body but drops the
  ;; config revision.  `settings/service` hydrates the persisted body and
  ;; revision itself, so persisted state wins and the restart CAS starts
  ;; from the stored revision instead of 0.
  (let [[_ component] (service {:config {:connections [] :revision 0}
                                :revision 7})
        saved (save-connection! component (fields "Provider") [] [])]
    (is (:ok? saved))
    (is (= 8 (get-in saved [:receipt :config-revision])))
    (is (= 8 (:revision (settings/config component))))))

(def ^:private launch-connection
  {:id "alpha"
   :name "Alpha Provider"
   :kind :openai-compatible
   :base-url "http://127.0.0.1:20321/v1"
   :api-key "stub-a"
   :models [{:id "same-model"
             :model-name "same-model"
             :display-name "Same Model"
             :capabilities #{:tools}}]})

(def ^:private second-launch-connection
  {:id "beta"
   :name "Beta Provider"
   :kind :openai-compatible
   :base-url "http://127.0.0.1:20322/v1"
   :api-key "stub-b"
   :models [{:id "same-model"
             :model-name "same-model"
             :display-name "Same Model"
             :capabilities #{:tools}}]})

(deftest bootstrap-then-save-save-delete-advance-one-revision
  ;; A launch-seeded bootstrap commits revision 0 -> 1.  The service must
  ;; hydrate that committed revision; otherwise every later save conflicts.
  (let [persistence (memory-persistence)
        component (settings/service
                   {:persistence persistence
                    :config {:connections [launch-connection
                                           second-launch-connection]}})
        added (settings/handle!
               component
               {:kind :settings/save-connection
                :fields {:id "ui-conn"
                         :name "UI Provider"
                         :kind :openai-compatible
                         :base-url "https://ui.example.com/v1"
                         :models []}})
        saved (settings/handle!
               component
               {:kind :settings/save-connection
                :fields {:id "ui-conn"
                         :name "UI Provider Renamed"
                         :kind :openai-compatible
                         :base-url "https://ui.example.com/v1"
                         :models []}})
        deleted (settings/handle!
                 component
                 {:kind :settings/delete-connection
                  :connection-id "ui-conn"})]
    (is (:ok? added))
    (is (= 2 (get-in added [:receipt :config-revision])))
    (is (:ok? saved))
    (is (= 3 (get-in saved [:receipt :config-revision])))
    (is (:ok? deleted))
    (is (= 4 (get-in deleted [:receipt :config-revision])))
    (is (= 4 (:revision (settings/config component))))
    (is (= ["alpha" "beta"]
           (mapv :id (:connections (settings/config component)))))))

(deftest stale-settings-revision-cannot-overwrite-a-newer-save
  ;; Two services hydrate the same revision.  The first save advances the
  ;; store; the second must fail its CAS instead of overwriting the newer
  ;; document.
  (let [persistence (memory-persistence)
        launch {:connections [launch-connection second-launch-connection]}
        first-service (settings/service {:persistence persistence
                                         :config launch})
        stale-service (settings/service {:persistence persistence
                                         :config launch})
        first-save (settings/handle!
                    first-service
                    {:kind :settings/save-connection
                     :fields {:id "ui-conn"
                              :name "UI Provider"
                              :kind :openai-compatible
                              :base-url "https://ui.example.com/v1"
                              :models []}})
        stale-save (settings/handle!
                    stale-service
                    {:kind :settings/save-connection
                     :fields {:id "other-conn"
                              :name "Other Provider"
                              :kind :openai-compatible
                              :base-url "https://other.example.com/v1"
                              :models []}})
        read-back (ports/read-config persistence)]
    (is (= 1 (:revision (settings/config stale-service))))
    (is (:ok? first-save))
    (is (= 2 (get-in first-save [:receipt :config-revision])))
    (is (false? (:ok? stale-save)))
    (is (= :SETTINGS_CONFLICT (get-in stale-save [:error :code])))
    (is (= 2 (get-in stale-save [:error :details :revision])))
    (is (= ["alpha" "beta" "ui-conn"]
           (mapv :id (get-in read-back [:config :connections])))
        "the stale save must not add its connection")))

(deftest disabling-a-connection-marks-its-session-binding-unavailable
  (let [[_ component] (service {:config same-name-connections-config})
        saved (save-connection!
               component
               (assoc (fields "Same provider")
                      :id "conn-a"
                      :enabled? false
                      :models [(projection-model "stub-model"
                                                 "Stub Slow Model")])
               [] [])
        config (settings/config component)
        baseline (snapshot/baseline
                  {:sessions [{:session-id "s1"
                               :model-binding
                               {:connection-id "conn-a"
                                :model-entry-id "stub-model"}}]
                   :session-records []
                   :active-session "s1"
                   :config {:models config}})]
    (is (:ok? saved))
    (is (false? (:enabled?
                 (some #(when (= "conn-a" (:id %)) %)
                       (:connections config)))))
    (is (= :connection-disabled
           (get-in baseline [:session/by-id "s1" :model-availability])))))

(deftest removing-a-launch-connection-revokes-the-session-binding
  ;; The launch configuration is the instance-level authorization.  A role
  ;; save records the launch ids it saw; a later launch edit that drops one
  ;; of them must revoke it instead of letting a persisted copy resurrect
  ;; the connection.
  (let [persistence (memory-persistence)
        first-service (settings/service
                       {:persistence persistence
                        :config {:connections [launch-connection
                                               second-launch-connection]}})
        saved (settings/handle!
               first-service
               {:kind :settings/save-role
                :roles {:main {:connection-id "beta"
                               :model-entry-id "same-model"}}})
        restarted (settings/service
                   {:persistence persistence
                    :config {:connections [second-launch-connection]}})
        config (settings/config restarted)
        baseline (snapshot/baseline
                  {:sessions [{:session-id "s1"
                               :model-binding
                               {:connection-id "alpha"
                                :model-entry-id "same-model"}}]
                   :session-records []
                   :active-session "s1"
                   :config {:models config}})]
    (is (:ok? saved))
    (is (= ["beta"] (mapv :id (:connections config)))
        "the launch file, not the persisted copy, decides connection existence")
    (is (= :connection-removed
           (get-in baseline [:session/by-id "s1" :model-availability])))
    (is (nil? (get-in baseline [:settings/by-id "alpha"]))
        "the settings view cannot offer the revoked connection")))

(deftest a-settings-created-connection-survives-a-launch-restart
  (let [persistence (memory-persistence)
        first-service (settings/service
                       {:persistence persistence
                        :config {:connections [launch-connection]}})
        added (settings/handle!
               first-service
               {:kind :settings/save-connection
                :fields {:id "ui-conn"
                         :name "UI Provider"
                         :kind :openai-compatible
                         :base-url "https://ui.example.com/v1"
                         :models []}})
        restarted (settings/service
                   {:persistence persistence
                    :config {:connections [launch-connection]}})]
    (is (:ok? added))
    (is (= #{"alpha" "ui-conn"}
           (set (map :id (:connections (settings/config restarted)))))
        "a connection the operator added has no launch declaration to revoke it")))

(deftest deleting-a-launch-connection-survives-restart
  (let [persistence (memory-persistence)
        first-service (settings/service
                       {:persistence persistence
                        :config {:connections [launch-connection
                                               second-launch-connection]}})
        deleted (settings/handle! first-service
                                  {:kind :settings/delete-connection
                                   :connection-id "alpha"})
        restarted (settings/service
                   {:persistence persistence
                    :config {:connections [launch-connection
                                           second-launch-connection]}})]
    (is (:ok? deleted))
    (is (= ["beta"] (mapv :id (:connections (settings/config restarted)))))
    (is (= :connection-removed
           (get-in (snapshot/baseline
                    {:sessions [{:session-id "s1"
                                 :model-binding
                                 {:connection-id "alpha"
                                  :model-entry-id "same-model"}}]
                     :session-records []
                     :active-session "s1"
                     :config {:models (settings/config restarted)}})
                   [:session/by-id "s1" :model-availability])))))

(deftest an-inline-key-is-not-connection-provenance
  ;; A legacy document has no reliable origin.  An inline key must not be
  ;; read as "this came from the launch file": a settings-created connection
  ;; may carry one too, and deleting it would violate restart persistence.
  (let [persistence (memory-persistence
                     1
                     {:connections [{:id "ui-conn"
                                     :name "UI Provider"
                                     :kind :openai-compatible
                                     :base-url "https://ui.example.com/v1"
                                     :api-key "sk-user"
                                     :models []}]})
        component (settings/service
                   {:persistence persistence
                    :config {:connections [launch-connection]}})]
    (is (= #{"alpha" "ui-conn"}
           (set (map :id (:connections (settings/config component)))))
        "an inline key must not mark the connection for launch revocation")))

(deftest a-launch-removal-hides-without-erasing-saved-fields
  ;; A launch edit hides a connection from the effective config.  The
  ;; settings document keeps its fields: re-declaring the connection
  ;; restores them, and an unrelated save must not erase them.
  (let [persistence (memory-persistence)
        first-service (settings/service
                       {:persistence persistence
                        :config {:connections [launch-connection
                                               second-launch-connection]}})
        saved (settings/handle!
               first-service
               {:kind :settings/save-connection
                :fields (assoc launch-connection
                               :name "Alpha Edited"
                               :models [{:id "same-model"
                                         :model-name "same-model"
                                         :display-name "Custom Alpha"
                                         :capabilities #{:tools}}])
                :keys []
                :credentials []})
        hidden (settings/service
                {:persistence persistence
                 :config {:connections [second-launch-connection]}})
        role-saved (settings/handle!
                    hidden
                    {:kind :settings/save-role
                     :roles {:main {:connection "beta"
                                    :model "same-model"}}})
        restored (settings/service
                  {:persistence persistence
                   :config {:connections [launch-connection
                                          second-launch-connection]}})
        alpha (some #(when (= "alpha" (:id %)) %)
                    (:connections (settings/config restored)))]
    (is (:ok? saved))
    (is (:ok? role-saved))
    (is (= ["beta"] (mapv :id (:connections (settings/config hidden))))
        "the launch file still decides connection existence")
    (is (= "Alpha Edited" (:name alpha))
        "the hidden connection keeps its saved fields")
    (is (= "Custom Alpha" (get-in alpha [:models 0 :display-name]))
        "a launch removal must not erase the operator's model edits")))

(deftest a-saved-connection-is-not-recorded-as-launch-provenance
  ;; A saved connection's inline key is not launch provenance.  The launch
  ;; declaration set records origin; saving a UI connection must not add it.
  (let [persistence (memory-persistence)
        component (settings/service
                   {:persistence persistence
                    :config {:connections [launch-connection]}})
        saved (settings/handle!
               component
               {:kind :settings/save-connection
                :fields {:id "ui-conn"
                         :name "UI Provider"
                         :kind :openai-compatible
                         :base-url "https://ui.example.com/v1"
                         :api-key "sk-user"
                         :models []}
                :keys []
                :credentials []})
        read-back (:config (ports/read-config persistence))]
    (is (:ok? saved))
    (is (= #{"alpha"} (:launch-connection-ids read-back))
        "the launch declaration, not a saved credential, records provenance")
    (is (= #{"alpha" "ui-conn"}
           (set (map :id (:connections (settings/config component))))))))

(deftest settings-config-persists-through-real-storage
  (let [dir (storage-support/temp-dir "hc-settings-store-")
        db-path (str dir "/db")]
    (try
      (let [store (storage/open-store {:path db-path
                                       :create? true
                                       :durability :sync})
            persistence (session-persistence/persistence store)
            component (settings/service {:persistence persistence
                                         :config {:connections [] :revision 0}})]
        (try
          (let [saved (save-connection! component (fields "First") [] [])
                read-back (ports/read-config persistence)]
            (is (:ok? saved))
            (is (= 1 (get-in saved [:receipt :config-revision])))
            (is (= :found (:status read-back)))
            (is (= 1 (get-in read-back [:config :revision])))
            (is (= "First" (get-in read-back [:config :connections 0 :name]))))
          (finally (storage/close-store! store))))
      (let [store (storage/open-store {:path db-path})
            persistence (session-persistence/persistence store)
            component (settings/service {:persistence persistence
                                         :config {:connections [] :revision 0}})]
        (try
          (let [read-back (ports/read-config persistence)]
            (is (= :found (:status read-back)))
            (is (= 1 (get-in read-back [:config :revision])))
            (is (= 1 (:revision (settings/config component))))
            (is (= "First"
                   (get-in (settings/config component)
                           [:connections 0 :name]))))
          (finally (storage/close-store! store))))
      (finally (storage-support/delete-tree! dir)))))

(deftest clearing-a-credential-through-settings-deletes-the-secret
  (let [[store component] (service)
        first-save (settings/handle!
                    component
                    {:kind :settings/save-connection
                     :fields (fields "Provider")
                     :keys [{:key-id "k1" :alias "main"
                             :enabled? true :priority 1}]
                     :credentials [{:key-id "k1" :secret "sk-one"}]
                     :clear-credentials []})
        ref (get-in (first (:connections (settings/config component)))
                    [:credentials "k1" :secret-ref])
        clear-save (settings/handle!
                    component
                    {:kind :settings/save-connection
                     :fields (fields "Provider")
                     :keys []
                     :credentials []
                     :clear-credentials ["k1"]})
        connection (first (:connections (settings/config component)))]
    (is (:ok? first-save))
    (is (:ok? clear-save))
    (is (empty? (:credentials connection)))
    (is (not (secrets/secret-exists? store ref)))))

(deftest account-login-connection-saves-without-api-keys
  (let [[_ component] (service)
        auth-fields (assoc (fields "Google account")
                           :service-kind :account-login
                           :base-url
                           "https://generativelanguage.googleapis.com/v1beta/openai"
                           :auth {:oauth {:provider-id :google
                                          :issuer "https://accounts.google.com"
                                          :subject "subject-1"
                                          :account-label "person@example.com"}
                                  :auth-ref "account-1"})
        saved (settings/handle!
               component
               {:kind :settings/save-connection
                :fields auth-fields
                :keys []
                :credentials []
                :clear-credentials []})
        connection (first (:connections (settings/config component)))]
    (is (:ok? saved))
    (is (= :account-login (:service-kind connection)))
    (is (= "account-1" (get-in connection [:auth :auth-ref])))
    (is (= "subject-1" (get-in connection [:auth :oauth :subject])))
    (is (empty? (:credentials connection)))))

(deftest test-connection-uses-the-stored-api-key
  (with-http-server
    (fn [seen url]
      (let [[_ component] (service)
            saved (save-connection!
                   component
                   (assoc (fields "Provider") :base-url url)
                   [{:key-id "k1" :alias "main"
                     :enabled? true :priority 1}]
                   [{:key-id "k1" :secret "sk-one"}])
            result (settings/handle!
                    component
                    {:kind :settings/test-connection
                     :connection-id "conn-1"
                     :fields (assoc (fields "Provider") :base-url url)}
                    {})
            layers (get-in result [:receipt :layers])]
        (is (:ok? saved))
        (is (= ["Bearer sk-one"] @seen))
        (is (= :ok (get-in layers [:auth-ok :status])))
        (is (= :ok (get-in layers [:models-listed :status])))))))

(deftest test-connection-uses-a-one-time-key-edit
  (with-http-server
    (fn [seen url]
      (let [[_ component] (service)
            result (settings/handle!
                    component
                    {:kind :settings/test-connection
                     :fields (assoc (fields "Draft") :base-url url)
                     :keys [{:key-id "k1" :alias "main"
                             :enabled? true :priority 1}]
                     :credentials [{:key-id "k1" :alias "main"
                                    :enabled? true :priority 1
                                    :secret "typed-key"}]}
                    {})
            layers (get-in result [:receipt :layers])]
        (is (= ["Bearer typed-key"] @seen))
        (is (= :ok (get-in layers [:auth-ok :status])))
        (is (= :ok (get-in layers [:models-listed :status])))))))

(defn- local-fields
  [model]
  (assoc (fields "Local")
         :service-kind :local-service
         :models [{:id model
                   :name model
                   :enabled? true
                   :source :manual}]))

(defn- test-layers
  [url fields]
  (let [[_ component] (service)
        result (settings/handle!
                component
                {:kind :settings/test-connection
                 :fields (assoc fields :base-url url)
                 :keys []
                 :credentials []}
                {})]
    (get-in result [:receipt :layers])))

(defn- openai-reply
  [content]
  {:status 200
   :body (json/write-str
          {:id "chatcmpl-test"
           :object "chat.completion"
           :choices [{:index 0
                      :message {:role "assistant" :content content}
                      :finish_reason "stop"}]})})

(deftest test-connection-proves-a-minimal-model-request
  (with-model-server
    (fn [{:keys [method path]}]
      (cond
        (and (= "GET" method) (= "/v1/models" path))
        {:status 200
         :body (json/write-str {:object "list"
                                :data [{:id "stub-model"}]})}

        (and (= "POST" method) (= "/v1/chat/completions" path))
        (openai-reply "OK")

        :else
        {:status 404 :body "{}"}))
    (fn [seen url]
      (let [layers (test-layers url (local-fields "stub-model"))
            posts (filterv #(= "POST" (:method %)) @seen)
            request (some-> posts first :body (json/read-str :key-fn keyword))]
        (is (= :ok (get-in layers [:reachable :status])))
        (is (= :ok (get-in layers [:auth-ok :status])))
        (is (= :ok (get-in layers [:models-listed :status])))
        (is (= :ok (get-in layers [:minimal-request-ok :status])))
        (is (= 1 (count posts)))
        (is (= "/v1/chat/completions" (:path (first posts))))
        (is (= "stub-model" (:model request)))
        (is (false? (:stream request)))
        (is (= [{:role "user"
                 :content "Herdchestra connection test. Reply with OK."}]
               (:messages request)))
        (is (not (contains? request :tools)))
        (is (not (contains? request :attachments)))))))

(deftest test-connection-receipt-echoes-the-config-digest
  (with-model-server
    (fn [{:keys [method path]}]
      (cond
        (and (= "GET" method) (= "/v1/models" path))
        {:status 200
         :body (json/write-str {:object "list"
                                :data [{:id "stub-model"}]})}

        (and (= "POST" method) (= "/v1/chat/completions" path))
        (openai-reply "OK")

        :else
        {:status 404 :body "{}"}))
    (fn [_ url]
      (let [[_ component] (service)
            result (settings/handle!
                    component
                    {:kind :settings/test-connection
                     :command-id "test-1"
                     :config-digest "digest-1"
                     :fields (assoc (local-fields "stub-model") :base-url url)
                     :keys []
                     :credentials []}
                    {})]
        (is (:ok? result))
        (is (= :complete (get-in result [:receipt :status])))
        (is (= "test-1" (get-in result [:receipt :command-id])))
        (is (= "digest-1" (get-in result [:receipt :config-digest])))
        (is (= :ok
               (get-in result
                       [:receipt :layers :minimal-request-ok :status])))))))

(deftest test-connection-uses-the-stored-key-for-the-minimal-request
  (with-model-server
    (fn [{:keys [method path]}]
      (cond
        (and (= "GET" method) (= "/v1/models" path))
        {:status 200
         :body (json/write-str {:object "list"
                                :data [{:id "stub-model"}]})}

        (and (= "POST" method) (= "/v1/chat/completions" path))
        (openai-reply "OK")

        :else
        {:status 404 :body "{}"}))
    (fn [seen url]
      (let [[_ component] (service)
            saved (save-connection!
                   component
                   (assoc (local-fields "stub-model") :base-url url)
                   [{:key-id "k1" :alias "main"
                     :enabled? true :priority 1}]
                   [{:key-id "k1" :secret "sk-one"}])
            result (settings/handle!
                    component
                    {:kind :settings/test-connection
                     :connection-id "conn-1"
                     :fields (assoc (local-fields "stub-model")
                                    :base-url url)
                     :keys []
                     :credentials []}
                    {})
            layers (get-in result [:receipt :layers])
            authorizations (mapv #(get-in % [:headers "authorization" 0])
                                 @seen)]
        (is (:ok? saved))
        (is (= :ok (get-in layers [:minimal-request-ok :status])))
        (is (= ["Bearer sk-one" "Bearer sk-one"] authorizations)
            "the list probe and the model request must use the same resolved key")))))

(deftest test-connection-tests-a-manual-model-when-the-list-fails
  (with-model-server
    (fn [{:keys [method path]}]
      (cond
        (and (= "GET" method) (= "/v1/models" path))
        {:status 404 :body "{}"}

        (and (= "POST" method) (= "/v1/chat/completions" path))
        (openai-reply "OK")

        :else
        {:status 404 :body "{}"}))
    (fn [seen url]
      (let [layers (test-layers url (local-fields "manual-model"))
            posts (filterv #(= "POST" (:method %)) @seen)]
        (is (= :ok (get-in layers [:auth-ok :status])))
        (is (= :failed (get-in layers [:models-listed :status])))
        (is (= :ok (get-in layers [:minimal-request-ok :status]))
            "a missing /models endpoint must not hide a working chat model")
        (is (= 1 (count posts)))))))

(deftest test-connection-does-not-invent-a-model-to-test
  (with-model-server
    (fn [_]
      {:status 200
       :body (json/write-str {:object "list"
                              :data [{:id "stub-model"}]})})
    (fn [seen url]
      (let [layers (test-layers url
                                (assoc (local-fields "stub-model")
                                       :models [{:id "stub-model"
                                                 :name "stub-model"
                                                 :enabled? false
                                                 :source :manual}]))
            minimal (get-in layers [:minimal-request-ok])]
        (is (= :not-run (:status minimal)))
        (is (re-find #"no enabled model" (:reason minimal)))
        (is (empty? (filter #(= "POST" (:method %)) @seen)))))))

(deftest test-connection-reports-a-minimal-request-without-a-reply
  (with-model-server
    (fn [{:keys [method path]}]
      (cond
        (and (= "GET" method) (= "/v1/models" path))
        {:status 200
         :body (json/write-str {:object "list"
                                :data [{:id "stub-model"}]})}

        (and (= "POST" method) (= "/v1/chat/completions" path))
        (openai-reply "")

        :else
        {:status 404 :body "{}"}))
    (fn [_ url]
      (let [layers (test-layers url (local-fields "stub-model"))
            minimal (get-in layers [:minimal-request-ok])]
        (is (= :failed (:status minimal)))
        (is (re-find #"no reply text" (:message minimal)))))))

(deftest test-connection-reports-a-rejected-minimal-request
  (with-model-server
    (fn [{:keys [method path]}]
      (cond
        (and (= "GET" method) (= "/v1/models" path))
        {:status 200
         :body (json/write-str {:object "list"
                                :data [{:id "stub-model"}]})}

        (and (= "POST" method) (= "/v1/chat/completions" path))
        {:status 500 :body "{}"}

        :else
        {:status 404 :body "{}"}))
    (fn [_ url]
      (let [layers (test-layers url (local-fields "stub-model"))
            minimal (get-in layers [:minimal-request-ok])]
        (is (= :failed (:status minimal)))
        (is (= 500 (:http-status minimal)))
        (is (re-find #"provider error" (:message minimal)))))))

(deftest test-connection-does-not-send-a-model-request-after-auth-rejection
  (with-model-server
    (fn [{:keys [method path]}]
      (if (and (= "GET" method) (= "/v1/models" path))
        {:status 401 :body "{}"}
        (openai-reply "OK")))
    (fn [seen url]
      (let [layers (test-layers url (local-fields "stub-model"))]
        (is (= :failed (get-in layers [:auth-ok :status])))
        (is (= :not-run (get-in layers [:minimal-request-ok :status])))
        (is (empty? (filter #(= "POST" (:method %)) @seen))
            "a rejected credential must not be replayed to a paid endpoint")))))

(deftest test-connection-uses-the-oauth-access-token
  (let [auth (oauth-component
              {"oauth-1" {:connection-id "oauth-1"
                          :access-token "oauth-token"
                          :expires-at-ms (+ (System/currentTimeMillis) 3600000)
                          :provider-id :google
                          :issuer "https://accounts.google.com"
                          :subject "subject-1"
                          :account-label "person@example.com"}})]
    (try
      (with-http-server
        (fn [seen url]
          (let [[_ component] (service)
                auth-fields (assoc (fields "Google account")
                                   :id "oauth-1"
                                   :service-kind :account-login
                                   :base-url url
                                   :auth {:oauth {:provider-id :google}
                                          :auth-ref "oauth-1"})
                result (settings/handle!
                        component
                        {:kind :settings/test-connection
                         :fields auth-fields
                         :keys []
                         :credentials []}
                        {:model-auth auth})
                layers (get-in result [:receipt :layers])]
            (is (= ["Bearer oauth-token"] @seen))
            (is (= :ok (get-in layers [:auth-ok :status])))
            (is (= :ok (get-in layers [:models-listed :status]))))))
      (finally
        (model-auth-service/stop! auth)))))

(deftest test-connection-reports-a-missing-oauth-account
  (let [auth (oauth-component {})]
    (try
      (with-http-server
        (fn [seen url]
          (let [[_ component] (service)
                auth-fields (assoc (fields "Google account")
                                   :id "oauth-1"
                                   :service-kind :account-login
                                   :base-url url
                                   :auth {:oauth {:provider-id :google}
                                          :auth-ref "oauth-1"})
                result (settings/handle!
                        component
                        {:kind :settings/test-connection
                         :fields auth-fields
                         :keys []
                         :credentials []}
                        {:model-auth auth})
                layers (get-in result [:receipt :layers])]
            (is (empty? @seen))
            (is (= :failed (get-in layers [:auth-ok :status])))
            (is (= :MODEL_AUTH_NOT_CONNECTED
                   (get-in layers [:auth-ok :code])))
            (is (= :not-run (get-in layers [:models-listed :status]))))))
      (finally
        (model-auth-service/stop! auth)))))

(deftest deleting-a-connection-removes-its-stored-key
  (let [[store component] (service)
        saved (save-connection!
               component
               (fields "Provider")
               [{:key-id "k1" :alias "main"
                 :enabled? true :priority 1}]
               [{:key-id "k1" :secret "sk-delete"}])
        ref (get-in (first (:connections (settings/config component)))
                    [:credentials "k1" :secret-ref])
        deleted (settings/handle!
                 component
                 {:kind :settings/delete-connection
                  :connection-id "conn-1"}
                 {})]
    (is (:ok? saved))
    (is (:ok? deleted))
    (is (empty? (:connections (settings/config component))))
    (is (not (secrets/secret-exists? store ref)))))

(deftest deleting-an-oauth-connection-removes-the-account
  (let [auth (oauth-component
              {"oauth-1" {:connection-id "oauth-1"
                          :provider-id :google}})]
    (try
      (let [[_ component] (service)
            saved (save-connection!
                   component
                   (assoc (fields "Google account")
                          :id "oauth-1"
                          :service-kind :account-login
                          :auth {:oauth {:provider-id :google}
                                 :auth-ref "oauth-1"})
                   []
                   [])
            deleted (settings/handle!
                     component
                     {:kind :settings/delete-connection
                      :connection-id "oauth-1"}
                     {:model-auth auth})]
        (is (:ok? saved))
        (is (:ok? deleted))
        (is (empty? (:connections (settings/config component))))
        (is (nil? (model-auth-store/read-credential (:store auth)
                                                    "oauth-1"))))
      (finally
        (model-auth-service/stop! auth)))))

(deftest deleting-oauth-without-model-auth-is-refused
  (let [[_ component] (service)
        saved (save-connection!
               component
               (assoc (fields "Google account")
                      :id "oauth-1"
                      :service-kind :account-login
                      :auth {:oauth {:provider-id :google}
                             :auth-ref "oauth-1"})
               []
               [])
        deleted (settings/handle!
                 component
                 {:kind :settings/delete-connection
                  :connection-id "oauth-1"}
                 {})]
    (is (:ok? saved))
    (is (not (:ok? deleted)))
    (is (= :MODEL_AUTH_UNAVAILABLE (get-in deleted [:error :code])))
    (is (= 1 (count (:connections (settings/config component)))))))

(deftest saving-a-connection-drops-inline-secret-fields
  (let [[_ component] (service)
        saved (settings/handle!
               component
               {:kind :settings/save-connection
                :fields (assoc (fields "Provider")
                               :auth {:access-token "auth-leak"
                                      :provider-id :google
                                      :oauth {:refresh-token "refresh-leak"
                                              :provider-id :google}}
                               :headers {"Authorization" "Bearer header-leak"
                                         "X-Trace" "ok"}
                               :options {:api-key "option-leak"
                                         :temperature 0.2})
                :keys []
                :credentials []
                :clear-credentials []})
        connection (first (:connections (settings/config component)))
        text (pr-str [connection (settings/snapshot component)])]
    (is (:ok? saved))
    (is (nil? (get-in connection [:auth :access-token])))
    (is (nil? (get-in connection [:auth :oauth :refresh-token])))
    (is (nil? (get-in connection [:headers "Authorization"])))
    (is (= "ok" (get-in connection [:headers "X-Trace"])))
    (is (nil? (get-in connection [:options :api-key])))
    (is (= 0.2 (get-in connection [:options :temperature])))
    (is (not (re-find #"leak" text)))))

(deftest probe-error-does-not-echo-url-credentials
  (let [[_ component] (service)
        result (settings/handle!
                component
                {:kind :settings/test-connection
                 :fields (assoc (fields "Bad")
                                :base-url
                                "https://user:secret@127.0.0.1:1/v1")
                 :keys []
                 :credentials []}
                {})
        text (pr-str result)]
    (is (= :failed (get-in result [:receipt :layers :reachable :status])))
    (is (not (re-find #"secret" text)))
    (is (not (re-find #"user:" text)))))

(deftest settings-save-preserves-backup-and-preference-sections
  ;; The backup service CAS-writes `[:backup :targets]` into the same
  ;; committed document.  A settings save must carry those sections
  ;; forward instead of overwriting the document with only the model
  ;; fields it manages.
  (let [persistence (memory-persistence
                     0
                     {:connections []
                      :backup {:targets {:local {:kind :file
                                                :root "/tmp/backups"}}
                               :default-target-id :local}
                      :appearance {:theme :dark}
                      :assistants {"default" {:id "default"
                                              :name "Default"
                                              :revision 1
                                              :enabled? true}}})
        component (settings/service
                   {:persistence persistence
                    :config {:connections [] :revision 0}
                    :secret-store (secrets/memory-secret-store)})
        saved (save-connection! component (fields "Provider") [] [])
        read-back (:config (ports/read-config persistence))]
    (is (:ok? saved))
    (is (= {:local {:kind :file :root "/tmp/backups"}}
           (get-in read-back [:backup :targets]))
        "a connection save must not drop backup targets")
    (is (= :local (get-in read-back [:backup :default-target-id])))
    (is (= {:theme :dark} (:appearance read-back))
        "a connection save must not drop appearance preferences")
    (is (= "default" (get-in read-back [:assistants "default" :id]))
        "a connection save must not drop assistants")
    (is (= "default" (get-in (settings/document component)
                             [:assistants "default" :id])))))

(deftest settings-role-save-preserves-backup-and-preference-sections
  ;; Role saves share the same committed document; they must also carry
  ;; `:backup`, `:appearance`, and `:assistants` forward.
  (let [persistence (memory-persistence
                     0
                     {:connections []
                      :backup {:targets {:local {:kind :file
                                                :root "/tmp/backups"}}}
                      :appearance {:theme :light}
                      :assistants {"default" {:id "default"
                                              :name "Default"
                                              :revision 1
                                              :enabled? true}}})
        component (settings/service
                   {:persistence persistence
                    :config {:connections [] :revision 0}
                    :secret-store (secrets/memory-secret-store)})
        saved (settings/handle!
               component
               {:kind :settings/save-role
                :roles {:main {:connection "conn-1" :model "m1"}}})
        read-back (:config (ports/read-config persistence))]
    (is (:ok? saved))
    (is (= {:local {:kind :file :root "/tmp/backups"}}
           (get-in read-back [:backup :targets])))
    (is (= {:theme :light} (:appearance read-back)))
    (is (= "default" (get-in read-back [:assistants "default" :id])))))
