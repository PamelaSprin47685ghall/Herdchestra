(ns hc.server.app.live-session-config-test
  "A saved model configuration must reach the next session creation and the
  next `:session/select-model` without a restart.

  It must not reach backwards: an existing session keeps the binding it
  copied at creation, and a run keeps the snapshot it froze."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.commands :as app-commands]
            [hc.server.app.session-config :as session-config]
            [hc.server.app.settings :as settings]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.commands.durable :as durable]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.config :as sessions-config]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]))

(defn- connection
  "One provider connection and its listed model."
  [connection-id model-id]
  {:id connection-id
   :name connection-id
   :kind :openai-compatible
   :base-url "https://api.example.com/v1"
   :models [{:id model-id
             :name model-id
             :model-name (str model-id "-provider")
             :capabilities {:text true}}]})

(defn- live-session-config
  "The one reader the app assembly shares between creation and the Owner
  `:session-port`."
  [settings-service]
  (fn [] (session-config/session-config (settings/config settings-service))))

(defn- fake-owner
  [session]
  (let [state (atom {:commands {:command-epoch 1 :retired-through 0}
                     :session session})]
    {:snapshot (fn [] {:state @state})
     :stop! (fn [] {:ok? true})
     :submit-command! (fn [_] {:status :accepted})}))

(defn- make-registry
  [persistence]
  (registry/registry
   {:persistence persistence
    :id-source (ids/counter-source)
    :now-fn (constantly 1000)
    :owner-factory (fn [{:keys [session]}] (fake-owner session))}))

(defn- submit!
  [router request]
  (let [reply (promise)]
    (gateway-ports/submit-command! router request #(deliver reply %))
    (deref reply 2000 ::timeout)))

(defn- save-connection!
  "Commit one provider connection through the real settings service."
  [settings-service connection-id model-id]
  (settings/handle! settings-service
                    {:kind :settings/save-connection
                     :fields (connection connection-id model-id)}))

(defn- choose-main!
  "Bind the `:main` role, which is a new session's default model."
  [settings-service connection-id model-id]
  (settings/handle! settings-service
                    {:kind :settings/save-role
                     :roles {:main {:connection-id connection-id
                                    :model-entry-id model-id}}}))

(defn- settings-service-for
  [persistence models]
  (settings/service {:persistence persistence :config models}))

(defn- binding-of
  [reg session-id]
  (get-in (registry/get-session reg session-id) [:session :model-binding]))

(deftest a-saved-model-reaches-the-next-session-without-a-restart
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        config* (live-session-config settings-service)
        reg (make-registry persistence)
        router (app-commands/router
                {:registry reg
                 :settings settings-service
                 :session-config config*
                 :dedup-store (durable/memory-store)})
        create (fn [command-id]
                 (submit! router {:command-id command-id
                                  :command-epoch 1
                                  :payload {:kind :session/create
                                            :title "New"}}))]
    (is (true? (:ok? (save-connection! settings-service "conn-old"
                                        "model-old"))))
    (is (true? (:ok? (choose-main! settings-service "conn-old" "model-old"))))
    (let [first-result (create "cmd-create-old")
          first-id (get-in first-result [:receipt :session-id])]
      (is (true? (:ok? first-result)))
      (testing "the session copies the committed binding at creation"
        (is (= "conn-old" (:connection-id (binding-of reg first-id))))
        (is (= "model-old" (:model-entry-id (binding-of reg first-id)))))

      (testing "a settings save commits the new model and main role"
        (is (true? (:ok? (save-connection! settings-service "conn-new"
                                            "model-new"))))
        (is (true? (:ok? (choose-main! settings-service "conn-new"
                                       "model-new")))))

      (let [second-result (create "cmd-create-new")
            second-id (get-in second-result [:receipt :session-id])]
        (is (true? (:ok? second-result)))
        (testing "a new session reads the saved model, not the start-time map"
          (is (= "conn-new" (:connection-id (binding-of reg second-id))))
          (is (= "model-new" (:model-entry-id (binding-of reg second-id)))))
        (testing "the earlier session keeps its creation-time binding"
          (is (= "conn-old" (:connection-id (binding-of reg first-id))))
          (is (= "model-old" (:model-entry-id (binding-of reg first-id)))))))))

(deftest a-declared-tool-capability-reaches-a-new-session-binding
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        config* (live-session-config settings-service)
        reg (make-registry persistence)
        router (app-commands/router
                {:registry reg
                 :settings settings-service
                 :session-config config*
                 :dedup-store (durable/memory-store)})
        connection {:id "conn-manual"
                    :name "Manual provider"
                    :kind :openai-compatible
                    :base-url "https://api.example.com/v1"
                    :models [{:id "manual-model"
                              :name "Manual Model"
                              :enabled? true
                              :source :manual
                              :capabilities {:tools true}
                              :capability-sources {:tools :user}}]}]
    (is (:ok? (settings/handle! settings-service
                                {:kind :settings/save-connection
                                 :fields connection})))
    (is (:ok? (settings/handle! settings-service
                                {:kind :settings/save-role
                                 :roles {:main {:connection-id "conn-manual"
                                                :model-entry-id "manual-model"}}})))
    (let [result (submit! router {:command-id "cmd-create-manual"
                                  :command-epoch 1
                                  :payload {:kind :session/create
                                            :title "Manual"}})
          session-id (get-in result [:receipt :session-id])
          binding (binding-of reg session-id)]
      (is (true? (:ok? result)))
      (is (true? (get-in binding [:capabilities :tools]))
          "the explicit declaration reaches the frozen session binding"))))

(deftest select-model-reads-the-committed-configuration-each-command
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        config* (live-session-config settings-service)
        _ (is (true? (:ok? (save-connection! settings-service "conn-old"
                                             "model-old"))))
        _ (is (true? (:ok? (choose-main! settings-service "conn-old"
                                         "model-old"))))
        created (sessions/new-session
                 {:session-id "s1"
                  :owner-id "o1"
                  :config (config*)
                  :id-source (ids/counter-source)
                  :now 1000})
        ;; The Owner port resolves the configuration once per command.
        port (fn [session payload]
               (sessions/apply-command session payload
                                       {:now 1000 :config (config*)}))
        select! (fn [session connection-id model-entry-id]
                  (port session {:kind :session/select-model
                                 :connection-id connection-id
                                 :model-entry-id model-entry-id}))]
    (is (:ok? created))
    (is (= "model-old" (get-in created [:session :model-binding
                                        :model-entry-id])))
    (is (true? (:ok? (save-connection! settings-service "conn-new"
                                       "model-new"))))
    (testing "a saved model is selectable on the same session, no restart"
      (let [result (select! (:session created) "conn-new" "model-new")]
        (is (true? (:ok? result)))
        (is (= "conn-new" (get-in result [:session :model-binding
                                          :connection-id])))
        (is (= "model-new" (get-in result [:session :model-binding
                                           :model-entry-id])))))
    (testing "a connection the live config no longer holds is refused"
      (let [result (select! (:session created) "conn-gone" "model-gone")]
        (is (false? (:ok? result)))
        (is (= :CONNECTION_NOT_FOUND (get-in result [:error :code])))))
    (testing "the original session value is immutable"
      (is (= "model-old" (get-in created [:session :model-binding
                                          :model-entry-id]))))))

(deftest a-resumed-owner-is-visible-to-the-registry-after-restart
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        config* (live-session-config settings-service)
        _ (is (true? (:ok? (save-connection! settings-service "conn-old"
                                             "model-old"))))
        _ (is (true? (:ok? (choose-main! settings-service "conn-old"
                                         "model-old"))))
        created (sessions/new-session
                 {:session-id "s-restart"
                  :owner-id "o-restart"
                  :config (config*)
                  :id-source (ids/counter-source)
                  :now 1000})
        _ (is (:ok? created))
        _ (is (= :committed
                 (:status (sessions/commit-session!
                           persistence (:session created)))))
        ;; A restarted process resumes the Owner from its own committed state.
        ;; The registry record still holds the creation-time session, so the
        ;; live Owner must be registered for the projection to merge it.
        live-session (assoc (:session created)
                            :model-binding
                            (get-in created [:session :model-binding]))
        live-session (assoc live-session :title "resumed")
        submitted (atom nil)
        owner {:snapshot (fn [] {:state {:session live-session}})
               :stop! (fn [] {:ok? true})
               :submit-command! (fn [request]
                                  (reset! submitted request)
                                  {:status :accepted})}
        registry (registry/registry
                  {:persistence persistence
                   :owner-factory (fn [_]
                                    (throw (ex-info "not used" {})))})]
    (is (not= "resumed"
              (get-in (registry/get-session registry "s-restart")
                      [:session :title]))
        "before registration the projection still reads the stored record")
    (is (identical?
         owner
         (app-commands/register-owner!
          (app-commands/router
           {:registry registry
            :settings settings-service
            :session-config config*})
          "s-restart" owner)))
    (testing "get-session merges the resumed Owner session"
      (let [loaded (registry/get-session registry "s-restart")]
        (is (= :found (:status loaded)))
        (is (= "resumed" (get-in loaded [:session :title])))
        (is (= "conn-old"
               (get-in loaded [:session :model-binding :connection-id])))))
    (testing "list-sessions merges the same live session"
      (let [listed (registry/list-sessions registry {:include-deleted? false})
            row (first (:sessions listed))]
        (is (= :ok (:status listed)))
        (is (= "resumed" (:title row)))
        ;; The registry list summary publishes the binding projection as
        ;; `:model`; the app snapshot layer renames it to `:model-binding`.
        (is (= "conn-old" (get-in row [:model :connection-id])))
        (is (= "conn-old"
               (get-in (snapshot/session-view [row])
                       ["s-restart" :model-binding :connection-id]))
            "the client baseline still receives :model-binding")))
    (testing "metadata commands find the resumed Owner, not :NO_OWNER"
      (let [receipt (registry/update-session!
                     registry "s-restart"
                     {:kind :session/rename
                      :title "renamed"
                      :command-id "cmd-rename"
                      :command-epoch 1})]
        (is (= :accepted (:status receipt)))))
    (testing "a blank reuse keeps the resumed Owner instead of starting one"
      (let [reused (registry/create-session! registry {:reuse-blank? true})]
        (is (= :created (:status reused)))
        (is (true? (:reused? reused)))
        (is (identical? owner (:owner reused)))
        (is (identical? owner (registry/owner-for registry "s-restart")))))
    (testing "the router forwards flat metadata fields into the Owner envelope"
      (let [router (app-commands/router
                    {:registry registry
                     :settings settings-service
                     :session-config config*})
            reply (promise)]
        (is (identical? owner
                        (app-commands/register-owner! router "s-restart" owner)))
        (gateway-ports/submit-command!
         router
         {:command-id "cmd-rename-flat"
          :command-epoch 1
          :payload {:kind :session/rename
                    :session-id "s-restart"
                    :title "源会话 A"}}
         #(deliver reply %))
        (let [result (deref reply 2000 ::timeout)]
          (is (= :accepted (get-in result [:receipt :status])))
          (is (= "源会话 A" (get-in @submitted [:payload :title])))
          (is (= :session/rename (get-in @submitted [:payload :kind]))))))
    (testing "a deleted session list row carries the live Owner epoch"
      (let [deleted (assoc live-session :status :deleted :deleted-at 3000)
            epoch-owner {:snapshot (fn []
                                     {:state {:session deleted
                                              :commands {:command-epoch 7}}})
                         :stop! (fn [] {:ok? true})
                         :submit-command! (fn [_] {:status :accepted})}
            router (app-commands/router
                    {:registry registry
                     :settings settings-service
                     :session-config config*})
            reply (promise)]
        (is (identical? epoch-owner
                        (app-commands/register-owner! router "s-restart"
                                                      epoch-owner)))
        (gateway-ports/submit-command!
         router
         {:command-id "cmd-list-deleted"
          :command-epoch 1
          :payload {:kind :session/list :include-deleted? true}}
         #(deliver reply %))
        (let [result (deref reply 2000 ::timeout)
              row (some #(when (= "s-restart" (:session-id %)) %)
                        (get-in result [:receipt :sessions]))]
          (is (= 7 (:command-epoch row))))))
    (testing "a second session keeps its own live binding"
      (let [other (sessions/new-session
                   {:session-id "s-other"
                    :owner-id "o-other"
                    :config (config*)
                    :id-source (ids/counter-source)
                    :now 2000})
            _ (is (:ok? other))
            _ (is (= :committed
                     (:status (sessions/commit-session!
                               persistence (:session other)))))
            other-owner {:snapshot (fn [] {:state {:session
                                                   (assoc (:session other)
                                                          :model-binding
                                                          nil)}})
                         :stop! (fn [] {:ok? true})}
            router (app-commands/router
                    {:registry registry
                     :settings settings-service
                     :session-config config*})]
        (is (identical? other-owner
                        (app-commands/register-owner! router "s-other"
                                                      other-owner)))
        (is (= "conn-old"
               (get-in (registry/get-session registry "s-restart")
                       [:session :model-binding :connection-id])))
        (is (nil? (get-in (registry/get-session registry "s-other")
                          [:session :model-binding])))))))

(deftest purge-uses-the-committed-deleted-status
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        config* (live-session-config settings-service)
        created (sessions/new-session
                 {:session-id "s-purge"
                  :owner-id "o-purge"
                  :id-source (ids/counter-source)
                  :now 1000})
        _ (is (:ok? created))
        active (:session created)
        committed (sessions/commit-session! persistence active)
        _ (is (= :committed (:status committed)))
        deleted (-> (:session committed)
                    (assoc :status :deleted :deleted-at 3000)
                    (update :revision inc))
        _ (is (= :committed
                 (:status (sessions/commit-session! persistence deleted))))
        ;; A lagging live Owner still says active; the committed record is
        ;; deleted.  Purge must use the committed deletion state, stop the
        ;; Owner, then remove the record.
        live (assoc active :status :active)
        owner {:snapshot (fn []
                           {:state {:session live
                                    :commands {:command-epoch 1}}})
               :stop! (fn [] {:ok? true :stopped? true :drained? true})
               :submit-command! (fn [_] {:status :accepted})}
        reg (registry/registry
             {:persistence persistence
              :owner-factory (fn [_]
                               (throw (ex-info "not used" {})))})
        router (app-commands/router
                {:registry reg
                 :settings settings-service
                 :session-config config*})
        reply (promise)]
    (is (identical? owner
                    (app-commands/register-owner! router "s-purge" owner)))
    (gateway-ports/submit-command!
     router
     {:command-id "cmd-purge"
      :command-epoch 1
      :payload {:kind :session/purge
                :session-id "s-purge"
                :confirm? true}}
     #(deliver reply %))
    (let [result (deref reply 2000 ::timeout)]
      (is (= :accepted (get-in result [:receipt :status])))
      (is (true? (get-in result [:receipt :purged?])))
      (is (= :missing
             (:status (registry/get-committed-session reg "s-purge")))))))

(deftest purge-refuses-a-live-deletion-that-was-never-committed
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        config* (live-session-config settings-service)
        created (sessions/new-session
                 {:session-id "s-live-purge"
                  :owner-id "o-live-purge"
                  :id-source (ids/counter-source)
                  :now 1000})
        _ (is (:ok? created))
        active (:session created)
        _ (is (= :committed
                 (:status (sessions/commit-session! persistence active))))
        ;; The live Owner says deleted but the committed record is still
        ;; active.  That is not an accepted business delete: the receipt
        ;; would have committed the deletion first.  Purge must not turn the
        ;; lagging live value into a committed deletion.
        live (assoc active :status :deleted :deleted-at 3000)
        stopped (atom false)
        owner {:snapshot (fn []
                           {:state {:session live
                                    :commands {:command-epoch 1}}})
               :stop! (fn []
                        (reset! stopped true)
                        {:ok? true :stopped? true :drained? true})
               :submit-command! (fn [_] {:status :accepted})}
        reg (registry/registry
             {:persistence persistence
              :owner-factory (fn [_]
                               (throw (ex-info "not used" {})))})
        router (app-commands/router
                {:registry reg
                 :settings settings-service
                 :session-config config*})
        reply (promise)]
    (is (= :active
           (get-in (registry/get-committed-session reg "s-live-purge")
                   [:session :status])))
    (is (identical? owner
                    (app-commands/register-owner! router "s-live-purge" owner)))
    (gateway-ports/submit-command!
     router
     {:command-id "cmd-live-purge"
      :command-epoch 1
      :payload {:kind :session/purge
                :session-id "s-live-purge"
                :confirm? true}}
     #(deliver reply %))
    (let [result (deref reply 2000 ::timeout)]
      (is (false? (:ok? result)))
      (is (= :SESSION_NOT_DELETED (get-in result [:error :code])))
      (is (false? @stopped)
          "the non-deleted committed record must be refused before stopping")
      (is (= :active
             (get-in (registry/get-committed-session reg "s-live-purge")
                     [:session :status]))))))

(deftest the-router-reads-the-resolver-once-per-creation
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        _ (is (true? (:ok? (save-connection! settings-service "conn-old"
                                             "model-old"))))
        _ (is (true? (:ok? (choose-main! settings-service "conn-old"
                                         "model-old"))))
        reads (atom [])
        config* (live-session-config settings-service)
        router (app-commands/router
                {:registry (make-registry persistence)
                 :settings settings-service
                 :session-config (fn []
                                   (swap! reads conj :read)
                                   (config*))
                 :dedup-store (durable/memory-store)})]
    (is (true? (:ok? (submit! router {:command-id "cmd-1"
                                      :command-epoch 1
                                      :payload {:kind :session/create}}))))
    (is (= [:read] @reads)
        "one creation resolves the configuration exactly once")
    (is (true? (:ok? (save-connection! settings-service "conn-new"
                                       "model-new"))))
    (is (true? (:ok? (submit! router {:command-id "cmd-2"
                                      :command-epoch 1
                                      :payload {:kind :session/create}}))))
    (is (= [:read :read] @reads)
        "the next creation resolves again, not a captured map")))

(deftest the-live-config-satisfies-session-invariants
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        _ (is (true? (:ok? (save-connection! settings-service "conn-old"
                                             "model-old"))))
        _ (is (true? (:ok? (choose-main! settings-service "conn-old"
                                         "model-old"))))
        live (session-config/session-config (settings/config settings-service))]
    (is (empty? (sessions-config/check-invariants live))
        "a live settings save converts to an invariant-valid session config")))

(deftest snapshots-project-the-live-settings-config
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        _ (is (true? (:ok? (save-connection! settings-service "conn-live"
                                             "model-live"))))
        _ (is (true? (:ok? (choose-main! settings-service "conn-live"
                                         "model-live"))))
        view (snapshot/settings-view (settings/config settings-service))]
    (is (= #{"conn-live"} (set (keys (:settings/by-id view)))))
    (is (= "model-live"
           (get-in view [:settings/models "conn-live" 0 :id])))
    (is (= "conn-live"
           (get-in view [:settings/roles :main :connection-id])))
    (is (= "model-live"
           (get-in view [:settings/roles :main :model-id])))))

(deftest settings-mutations-notify-the-publisher
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        changes (atom 0)
        router (app-commands/router
                {:registry (make-registry persistence)
                 :settings settings-service
                 :session-config (live-session-config settings-service)
                 :on-change (fn [_] (swap! changes inc))
                 :dedup-store (durable/memory-store)})
        save (submit! router {:command-id "cmd-save"
                              :command-epoch 1
                              :payload {:kind :settings/save-connection
                                        :fields (connection "conn-notify"
                                                            "model-notify")}})]
    (is (true? (:ok? save)))
    (is (= 1 @changes)
        "a committed connection must schedule a snapshot with the new config")
    (let [listed (submit! router {:command-id "cmd-list"
                                  :command-epoch 1
                                  :payload {:kind :settings/list-models}})]
      (is (true? (:ok? listed)))
      (is (= 1 @changes)
          "a read-only list command must not schedule a redundant publish"))))

(deftest a-plain-config-map-is-used-as-data
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        _ (is (true? (:ok? (save-connection! settings-service "conn-old"
                                             "model-old"))))
        _ (is (true? (:ok? (choose-main! settings-service "conn-old"
                                         "model-old"))))
        live (session-config/session-config (settings/config settings-service))
        reg (make-registry persistence)
        router (app-commands/router
                {:registry reg
                 :settings settings-service
                 :session-config live
                 :dedup-store (durable/memory-store)})
        result (submit! router {:command-id "cmd-map-config"
                                :command-epoch 1
                                :payload {:kind :session/create}})
        session-id (get-in result [:receipt :session-id])]
    (is (true? (:ok? result))
        "a map is configuration data, not a zero-argument resolver")
    (is (= "conn-old" (:connection-id (binding-of reg session-id))))
    (is (= "model-old" (:model-entry-id (binding-of reg session-id))))))

(deftest a-config-read-failure-is-a-clean-refusal
  (let [persistence (ports/memory-persistence)
        reg (make-registry persistence)
        router (app-commands/router
                {:registry reg
                 :settings nil
                 :session-config (fn []
                                   (throw (ex-info "settings unavailable" {})))
                 :dedup-store (durable/memory-store)})
        result (submit! router {:command-id "cmd-config-down"
                                :command-epoch 1
                                :payload {:kind :session/create}})
        listed (registry/list-sessions reg {:include-deleted? false})]
    (is (false? (:ok? result)))
    (is (= :SESSION_CONFIG_UNAVAILABLE (get-in result [:error :code])))
    (is (not= :outcome-unknown (:outcome result))
        "a refusal must not claim a side effect may have happened")
    (testing "no session is committed and no placeholder is left for replay"
      (is (= :ok (:status listed)))
      (is (empty? (:sessions listed)))
      (is (= :not-found
             (:status (gateway-ports/command-receipt
                       router "cmd-config-down")))))))

(deftest session-config-prefers-the-explicit-model-display-name
  (let [config
        (session-config/session-config
         {:connections
          [{:id "conn-1"
            :name "Provider"
            :kind :openai-compatible
            :base-url "https://api.example.com/v1"
            :models [{:id "stub-model"
                      :name "stub-model"
                      :display-name "Stub Slow Model"
                      :capabilities {:text true}}]}]})
        entry (get-in config [:connections "conn-1" :models "stub-model"])]
    (is (= "Stub Slow Model" (:display-name entry)))
    (testing "a legacy model without :display-name keeps its :name"
      (let [legacy
            (session-config/session-config
             {:connections
              [{:id "conn-2"
                :name "Provider"
                :kind :openai-compatible
                :base-url "https://api.example.com/v1"
                :models [{:id "legacy-model"
                          :name "Legacy Model"
                          :capabilities {:text true}}]}]})]
        (is (= "Legacy Model"
               (get-in legacy
                       [:connections "conn-2" :models "legacy-model"
                        :display-name])))))
    (testing "a model-id alias supplies the entry id"
      (let [alias-config
            (session-config/session-config
             {:connections
              [{:id "conn-3"
                :name "Provider"
                :kind :openai-compatible
                :base-url "https://api.example.com/v1"
                :models [{:model-id "alias-model"
                          :name "Alias Model"
                          :capabilities {:text true}}]}]})]
        (is (= "Alias Model"
               (get-in alias-config
                       [:connections "conn-3" :models "alias-model"
                        :display-name])))))))

(deftest declared-tools-survive-select-model-into-the-send-gate
  (let [persistence (ports/memory-persistence)
        settings-service (settings-service-for persistence {})
        config* (live-session-config settings-service)
        connection {:id "conn-manual"
                    :name "Manual provider"
                    :kind :openai-compatible
                    :base-url "https://api.example.com/v1"
                    :models [{:id "manual-model"
                              :name "Manual Model"
                              :enabled? true
                              :source :manual
                              :capabilities {:tools true}}]}
        _ (is (:ok? (settings/handle! settings-service
                                      {:kind :settings/save-connection
                                       :fields connection})))
        created (sessions/new-session
                 {:session-id "s-tools"
                  :owner-id "o-tools"
                  :config (config*)
                  :id-source (ids/counter-source)
                  :now 1000})
        selected (sessions/apply-command
                  (:session created)
                  {:kind :session/select-model
                   :connection-id "conn-manual"
                   :model-entry-id "manual-model"}
                  {:now 1000 :config (config*)})
        binding (get-in selected [:session :model-binding])
        live-config (config*)
        live-model (get-in live-config
                           [:connections "conn-manual" :models "manual-model"])]
    (is (true? (:ok? selected)))
    (is (true? (get-in binding [:capabilities :tools]))
        "select-model copies the saved declaration into the session binding")
    (is (true? (get-in live-model [:capabilities :tools]))
        "the live session projection keeps the declaration for the send gate")))
