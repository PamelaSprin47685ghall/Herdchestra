(ns hc.server.app.commands-dedup-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.commands :as app-commands]
            [hc.server.commands.durable :as durable]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]))

(defn- fake-owner
  [window]
  {:snapshot (fn []
               {:state {:commands (merge {:receipts {}} window)}})
   :stop! (fn [] {:ok? true :stopped? true :drained? true})
   :submit-command! (fn [_]
                      {:status :accepted
                       :receipt {:status :accepted}})})

(defn- make-registry
  [persistence]
  (registry/registry
   {:persistence persistence
    :id-source (ids/counter-source)
    :now-fn (constantly 1000)
    :owner-factory (fn [_] (fake-owner {:command-epoch 1
                                        :retired-through 0}))}))

(defn- make-router
  [persistence dedup-store & {:as opts}]
  (let [reg (make-registry persistence)
        router (app-commands/router
                (merge {:registry reg
                        :settings nil
                        :session-config nil
                        :dedup-store dedup-store}
                       opts))]
    (app-commands/register-owner! router "s1"
                                  (fake-owner {:command-epoch 1
                                               :retired-through 0}))
    (app-commands/register-owner! router "s2"
                                  (fake-owner {:command-epoch 1
                                               :retired-through 0}))
    {:registry reg :router router}))

(defn- submit!
  [router request]
  (gateway-ports/submit-command! router request nil))

(defn- submit-capture!
  [router request]
  (let [reply (promise)]
    (gateway-ports/submit-command! router request #(deliver reply %))
    (deref reply 2000 ::timeout)))

(defn- session-count
  [reg]
  (let [result (registry/list-sessions reg {:include-deleted? false})]
    (when (= :ok (:status result))
      (count (:sessions result)))))

(deftest router-session-create-replays-without-a-second-session
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        {:keys [registry router]} (make-router persistence dedup-store)
        request {:command-id "cmd-create"
                 :command-epoch 1
                 :payload {:kind :session/create :title "New"}}
        first-result (submit! router request)
        first-session-id (get-in first-result [:receipt :session-id])
        replay (submit! router request)
        query (gateway-ports/command-receipt router "cmd-create")
        mismatch (submit! router
                          (assoc request
                                 :payload {:kind :session/create
                                           :title "Different"}))]
    (is (true? (:ok? first-result)))
    (is (some? first-session-id))
    (is (= 1 (session-count registry)))
    (is (true? (:ok? replay)))
    (is (true? (:replayed? replay)))
    (is (= (:receipt first-result) (:receipt replay)))
    (is (= 1 (session-count registry)))
    (is (= (:receipt first-result) query))
    (is (false? (:ok? mismatch)))
    (is (= :COMMAND_ID_PAYLOAD_CONFLICT (get-in mismatch [:error :code])))
    (is (= 1 (session-count registry)))
    (testing "a fresh router reads the durable receipt and does not create again"
      (let [{:keys [registry router]} (make-router persistence dedup-store)
            query' (gateway-ports/command-receipt router "cmd-create")
            replay' (submit! router request)]
        (is (= (:receipt first-result) query'))
        (is (true? (:replayed? replay')))
        (is (= (:receipt first-result) (:receipt replay')))
        (is (= 1 (session-count registry)))))))

(deftest router-never-substitutes-the-presentation-active-session
  (let [{:keys [router]} (make-router (ports/memory-persistence)
                                      (durable/memory-store))
        _ (swap! (:state router) assoc :active-session-id "s1")
        result (submit! router
                        {:command-id "cmd-no-session"
                         :command-epoch 1
                         :payload {:kind :send-message :text "hello"}})]
    (is (false? (:ok? result)))
    (is (= :SESSION_REQUIRED (get-in result [:error :code]))
        "a session command must name its target even when a presentation pointer exists")))

(deftest app-receipt-is-not-visible-through-a-session-scope
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        {:keys [router]} (make-router persistence dedup-store)
        request {:command-id "cmd-create"
                 :command-epoch 1
                 :payload {:kind :session/create :title "New"}}]
    (is (true? (:ok? (submit! router request))))
    (is (= (:receipt (submit! router request))
           (gateway-ports/command-receipt
            router {:command-id "cmd-create"})))
    (is (= :not-found
           (:status (gateway-ports/command-receipt
                     router {:command-id "cmd-create"
                             :session-id "s1"}))))
    (is (= :not-found
           (:status (gateway-ports/command-receipt
                     router {:command-id "cmd-create"
                             :session-id "s2"}))))))

(deftest session-create-owner-recovery-keeps-unknown-until-live
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        attempts (atom 0)
        registry (registry/registry
                  {:persistence persistence
                   :id-source (ids/counter-source)
                   :now-fn (constantly 1000)
                   :owner-factory
                   (fn [_]
                     (if (= 1 (swap! attempts inc))
                       (throw (ex-info "owner start failed"
                                       {:code :OWNER_START_FAILED}))
                       (fake-owner {:command-epoch 1
                                    :retired-through 0})))})
        router (app-commands/router {:registry registry
                                     :settings nil
                                     :session-config nil
                                     :dedup-store dedup-store})
        request {:command-id "cmd-create"
                 :command-epoch 1
                 :payload {:kind :session/create :title "New"}}
        first (submit! router request)
        session-id (get-in first [:receipt :session-id])
        early-query (gateway-ports/command-receipt router "cmd-create")
        owner-after-early (registry/owner-for registry session-id)
        replay (submit! router request)
        query (gateway-ports/command-receipt router "cmd-create")]
    (is (false? (:ok? first)))
    (is (= :outcome-unknown (get-in first [:receipt :status])))
    (is (some? session-id))
    (is (= 1 (session-count registry)))
    (is (= :outcome-unknown (:status early-query)))
    (is (nil? owner-after-early))
    (is (true? (:ok? replay)))
    (is (true? (:replayed? replay)))
    (is (= :accepted (get-in replay [:receipt :status])))
    (is (= session-id (get-in replay [:receipt :session-id])))
    (is (= (:receipt replay) query))
    (is (= 1 (session-count registry)))
    (is (some? (registry/owner-for registry session-id)))))

(deftest router-session-create-refuses-a-retired-epoch
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        {:keys [registry router]} (make-router persistence dedup-store)
        request {:command-id "cmd-create"
                 :command-epoch 1
                 :payload {:kind :session/create :title "New"}}]
    (is (true? (:ok? (submit! router request))))
    (is (= 1 (session-count registry)))
    (testing "a newer app epoch retires the old command window"
      (let [retired-store
            (durable/memory-store
             {:initial-state {:command-epoch 2
                              :retired-through 1
                              :receipts {}}
              :initial-version 1})
            next-router (app-commands/router
                         {:registry registry
                          :settings nil
                          :session-config nil
                          :dedup-store retired-store})]
        (app-commands/register-owner! next-router "s1"
                                      (fake-owner {:command-epoch 2
                                                   :retired-through 1}))
        (let [result (submit! next-router request)]
          (is (false? (:ok? result)))
          (is (= :COMMAND_EPOCH_RETIRED (get-in result [:error :code])))
          (is (= 1 (session-count registry))))))))

(deftest temporary-delete-replays-after-its-owner-retires
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        stores (atom {})
        dedup-store-for (fn [scope]
                          (or (get @stores scope)
                              (let [store (durable/memory-store)]
                                (swap! stores assoc scope store)
                                store)))
        {:keys [registry router]} (make-router
                                   persistence dedup-store
                                   :dedup-store-for dedup-store-for)
        created (registry/create-session! registry
                                          {:title "Temp"
                                           :temporary? true})
        session-id (get-in created [:session :session-id])
        request {:command-id "cmd-delete"
                 :command-epoch 1
                 :payload {:kind :session/delete
                           :session-id session-id}}
        first-result (submit! router request)
        replay (submit! router request)
        query (gateway-ports/command-receipt
               router {:command-id "cmd-delete"
                       :session-id session-id})
        other (gateway-ports/command-receipt
               router {:command-id "cmd-delete"
                       :session-id "s1"})]
    (is (true? (:ok? first-result)))
    (is (true? (get-in first-result [:receipt :discarded?])))
    (is (true? (:replayed? replay)))
    (is (= (:receipt first-result) (:receipt replay)))
    (is (= (:receipt first-result) query))
    (is (= :not-found (:status other)))
    (testing "a fresh router still answers the replay without a live Owner"
      (let [{:keys [router]} (make-router persistence dedup-store
                                          :dedup-store-for dedup-store-for)
            replay' (submit! router request)
            query' (gateway-ports/command-receipt
                    router {:command-id "cmd-delete"
                            :session-id session-id})]
        (is (true? (:replayed? replay')))
        (is (= (:receipt first-result) (:receipt replay')))
        (is (= (:receipt first-result) query'))))))

(deftest purge-requires-confirmation-and-stops-the-owner
  (let [now (atom 1000)
        persistence (ports/memory-persistence)
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (fn [] @now)})
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        _ (registry/close-session! reg session-id {:delete? true})
        store (durable/memory-store
               {:initial-state {:command-epoch 1
                                :retired-through 0
                                :receipts {}}
                :initial-version 1})
        router (app-commands/router
                {:registry reg
                 :settings nil
                 :session-config nil
                 :dedup-store (durable/memory-store)
                 :dedup-store-for
                 (fn [scope]
                   (if (= scope session-id)
                     store
                     (durable/memory-store)))})
        request (fn [command-id confirm?]
                  {:command-id command-id
                   :command-epoch 1
                   :payload {:kind :session/purge
                             :session-id session-id
                             :confirm? confirm?}})]
    (testing "the deleted list carries the per-session Owner epoch"
      (let [listed (submit! router
                            {:command-id "list-deleted"
                             :command-epoch 1
                             :payload {:kind :session/list
                                       :include-deleted? true}})]
        (is (true? (:ok? listed)))
        (is (= 1 (get-in listed [:receipt :sessions 0 :command-epoch])))))
    (testing "confirmation is required before any destructive step"
      (let [result (submit! router (request "purge-no-confirm" false))]
        (is (false? (:ok? result)))
        (is (= :CONFIRMATION_REQUIRED (get-in result [:error :code])))
        (is (= :found (:status (registry/get-session reg session-id))))))
    (testing "an Owner that does not drain blocks the destructive step"
      (let [owner {:snapshot (fn []
                               {:state {:commands {:command-epoch 1
                                                   :retired-through 0}}})
                   :stop! (fn [] {:stopped? false :drained? false})}
            _ (app-commands/register-owner! router session-id owner)
            result (submit! router (request "purge-stuck" true))]
        (is (false? (:ok? result)))
        (is (= :OWNER_STOP_INCOMPLETE (get-in result [:error :code])))
        (is (= :found (:status (registry/get-session reg session-id))))))
    (testing "a stopped but undrained Owner still blocks the destructive step"
      (let [owner {:snapshot (fn []
                               {:state {:commands {:command-epoch 1
                                                   :retired-through 0}}})
                   :stop! (fn [] {:stopped? true :drained? false})}
            _ (app-commands/register-owner! router session-id owner)
            result (submit! router (request "purge-undrained" true))]
        (is (false? (:ok? result)))
        (is (= :OWNER_STOP_INCOMPLETE (get-in result [:error :code])))
        (is (= :found (:status (registry/get-session reg session-id))))))
    (testing "a bare ok result is not evidence of a drain"
      (let [owner {:snapshot (fn []
                               {:state {:commands {:command-epoch 1
                                                   :retired-through 0}}})
                   :stop! (fn [] {:ok? true})}
            _ (app-commands/register-owner! router session-id owner)
            result (submit! router (request "purge-bare-ok" true))]
        (is (false? (:ok? result)))
        (is (= :OWNER_STOP_INCOMPLETE (get-in result [:error :code])))
        (is (= :found (:status (registry/get-session reg session-id))))))
    (testing "a confirmed purge stops the Owner before removing the record"
      (let [stopped (atom false)
            owner {:snapshot (fn []
                               {:state {:commands {:command-epoch 1
                                                   :retired-through 0}}})
                   :stop! (fn []
                            (reset! stopped true)
                            {:stopped? true :drained? true})}
            _ (app-commands/register-owner! router session-id owner)
            result (submit! router (request "purge-ok" true))
            replay (submit! router (request "purge-ok" true))]
        (is (true? @stopped))
        (is (true? (:ok? result)))
        (is (true? (get-in result [:receipt :purged?])))
        (is (true? (:replayed? replay)))
        (is (= (:receipt result) (:receipt replay)))
        (is (= :missing (:status (registry/get-session reg session-id))))))))

(deftest app-epoch-rotates-and-fences-old-receipts
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        {:keys [registry router]} (make-router persistence dedup-store)
        create {:command-id "cmd-create"
                :command-epoch 1
                :payload {:kind :session/create :title "One"}}
        first-result (submit! router create)
        rotation (submit! router
                          {:command-id "cmd-rotate"
                           :command-epoch 1
                           :payload {:kind :app/issue-epoch}})
        new-epoch (get-in rotation [:receipt :command-epoch])
        old-replay (submit! router create)
        query (gateway-ports/command-receipt router "cmd-create")
        next-create (submit! router
                             (assoc create
                                    :command-id "cmd-create-2"
                                    :command-epoch new-epoch))]
    (is (true? (:ok? first-result)))
    (is (true? (:ok? rotation)))
    (is (= 2 new-epoch))
    (is (= 2 (app-commands/app-command-epoch router)))
    (is (false? (:ok? old-replay)))
    (is (= :COMMAND_EPOCH_RETIRED (get-in old-replay [:error :code])))
    (is (= :not-found (:status query)))
    (is (true? (:ok? next-create)))
    (is (= 2 (session-count registry)))
    (testing "a stale rotation request cannot rotate again"
      (let [stale (submit! router
                           {:command-id "cmd-rotate-2"
                            :command-epoch 1
                            :payload {:kind :app/issue-epoch}})]
        (is (false? (:ok? stale)))
        (is (= :COMMAND_EPOCH_RETIRED (get-in stale [:error :code])))
        (is (= 2 (app-commands/app-command-epoch router)))))
    (testing "rotation requires the current command-epoch"
      (let [missing (submit! router
                             {:command-id "cmd-rotate-3"
                              :payload {:kind :app/issue-epoch}})]
        (is (false? (:ok? missing)))
        (is (= :COMMAND_EPOCH_INVALID (get-in missing [:error :code])))
        (is (= 2 (app-commands/app-command-epoch router)))))))

(deftest app-gate-opens-a-retired-store-at-the-next-epoch
  (let [store (durable/memory-store
               {:initial-state {:command-epoch nil
                                :retired-through 4
                                :receipts {}}
                :initial-version 1})
        router (app-commands/router
                {:registry nil
                 :settings nil
                 :session-config nil
                 :dedup-store store})]
    (is (= 5 (app-commands/app-command-epoch router)))
    (is (empty? (get-in (durable/state (:app-gate router)) [:receipts])))))

(deftest session-create-command-id-is-epoch-scoped
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        {:keys [registry router]} (make-router persistence dedup-store)
        create (fn [epoch]
                 {:command-id "cmd-reuse"
                  :command-epoch epoch
                  :payload {:kind :session/create :title "Epoch"}})
        first-result (submit! router (create 1))
        rotation (submit! router
                          {:command-id "cmd-rotate"
                           :command-epoch 1
                           :payload {:kind :app/issue-epoch}})
        second-result (submit! router (create 2))]
    (is (true? (:ok? first-result)))
    (is (true? (:ok? rotation)))
    (is (true? (:ok? second-result)))
    (is (not= (get-in first-result [:receipt :session-id])
              (get-in second-result [:receipt :session-id]))
        "the same command-id in a new epoch creates a new session")
    (is (= 2 (session-count registry)))))

(deftest reuse-receipt-carries-the-request-identity
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        {:keys [registry router]} (make-router persistence dedup-store)
        blank (registry/create-session! registry {:title "Blank"})
        blank-id (get-in blank [:session :session-id])
        result (submit! router
                        {:command-id "cmd-reuse"
                         :command-epoch 1
                         :payload {:kind :session/create
                                   :title "Reuse"
                                   :reuse-blank? true}})]
    (is (= :created (:status blank)))
    (is (true? (:ok? result)))
    (is (= blank-id (get-in result [:receipt :session-id])))
    (is (= "cmd-reuse" (get-in result [:receipt :command-id])))
    (is (= 1 (get-in result [:receipt :command-epoch])))
    (is (= 1 (session-count registry)))))

(deftest session-receipt-query-follows-the-owner-epoch
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        window (atom {:command-epoch 1 :retired-through 0})
        owner {:snapshot (fn []
                           {:state {:commands
                                    (merge {:receipts {}} @window)}})
               :stop! (fn [] {:ok? true})
               :submit-command! (fn [_]
                                  {:status :accepted
                                   :receipt {:status :accepted}})}
        {:keys [router]} (make-router persistence dedup-store)
        _ (app-commands/register-owner! router "s3" owner)
        request {:command-id "cmd-send"
                 :command-epoch 1
                 :payload {:kind :send-message
                           :session-id "s3"
                           :text "hello"}}
        first-result (submit! router request)
        before (gateway-ports/command-receipt
                router {:command-id "cmd-send" :session-id "s3"})]
    (is (true? (:ok? first-result)))
    (is (= (:receipt first-result) before))
    (reset! window {:command-epoch 2 :retired-through 1})
    (is (= :not-found
           (:status (gateway-ports/command-receipt
                     router {:command-id "cmd-send" :session-id "s3"}))))
    (let [replay (submit! router request)]
      (is (false? (:ok? replay)))
      (is (= :COMMAND_EPOCH_RETIRED (get-in replay [:error :code]))))))

(defn- crash-after-accept-store
  "A gate store that fails the completion commit exactly once.

  The first commit stores the `:dispatching` placeholder.  The second commit
  is the receipt completion after the session was already committed.  Later
  commits (gate-open migration and replay completion) succeed."
  []
  (let [inner (durable/memory-store
               {:initial-state {:command-epoch 1
                                :retired-through 0
                                :receipts {}
                                :receipt-index {}}
                :initial-version 1})
        commits (atom 0)]
    (reify durable/Store
      (load-state [_] (durable/load-state inner))
      (commit! [_ expected new-state]
        (if (= 2 (swap! commits inc))
          (throw (ex-info "simulated crash after the session commit" {}))
          (durable/commit! inner expected new-state)))
      (close-store! [_] (durable/close-store! inner)))))

(deftest session-create-replay-resolves-a-crash-window
  (let [persistence (ports/memory-persistence)
        reg (make-registry persistence)
        store (crash-after-accept-store)
        router (app-commands/router {:registry reg
                                     :settings nil
                                     :session-config nil
                                     :dedup-store store})
        request {:command-id "cmd-crash"
                 :command-epoch 1
                 :payload {:kind :session/create :title "Crash"}}
        first-result (submit-capture! router request)
        session-id (get-in first-result [:receipt :session-id])
        expected-receipt (get-in first-result [:receipt])
        stored (ports/read-session persistence session-id)]
    (is (false? (:ok? first-result)))
    (is (= :unknown (:outcome first-result)))
    (is (some? session-id))
    (is (= 1 (:command-epoch expected-receipt)))
    (is (= 1 (session-count reg)))
    (is (= {:scope "app" :command-epoch 1 :command-id "cmd-crash"}
           (:creation-command (:session stored))))
    (testing "a restarted gate resolves the unknown receipt from the session"
      (let [restarted (app-commands/router {:registry reg
                                            :settings nil
                                            :session-config nil
                                            :dedup-store store})]
        (is (= expected-receipt
               (gateway-ports/command-receipt restarted "cmd-crash")))
        (let [replay (submit-capture! restarted request)]
          (is (true? (:ok? replay)))
          (is (true? (:replayed? replay)))
          (is (= expected-receipt (:receipt replay)))
          (is (= 1 (session-count reg)))
          (is (= :accepted
                 (:status (durable/receipt-for (:app-gate restarted)
                                               "app" "cmd-crash")))))
        (durable/close! (:app-gate restarted))))
    (durable/close! (:app-gate router))))

(deftest reuse-create-replay-resolves-from-the-persisted-claim
  (let [persistence (ports/memory-persistence)
        reg (make-registry persistence)
        store (crash-after-accept-store)
        blank (registry/create-session! reg {:title "Blank"})
        blank-id (get-in blank [:session :session-id])
        router (app-commands/router {:registry reg
                                     :settings nil
                                     :session-config nil
                                     :dedup-store store})
        request {:command-id "cmd-reuse-crash"
                 :command-epoch 1
                 :payload {:kind :session/create
                           :title "Reuse"
                           :reuse-blank? true}}
        first-result (submit-capture! router request)
        expected-receipt (get-in first-result [:receipt])]
    (is (= :created (:status blank)))
    (is (false? (:ok? first-result)))
    (is (= :unknown (:outcome first-result)))
    (is (= blank-id (get-in first-result [:receipt :session-id])))
    (is (= 1 (session-count reg)))
    (testing "the persisted claim resolves the receipt after restart"
      (let [restarted (app-commands/router {:registry reg
                                            :settings nil
                                            :session-config nil
                                            :dedup-store store})]
        (is (= expected-receipt
               (gateway-ports/command-receipt restarted
                                              "cmd-reuse-crash")))
        (let [replay (submit-capture! restarted request)]
          (is (true? (:ok? replay)))
          (is (true? (:replayed? replay)))
          (is (= expected-receipt (:receipt replay)))
          (is (= 1 (session-count reg))))
        (durable/close! (:app-gate restarted))))
    (durable/close! (:app-gate router))))

(defn- post-commit-unknown-persistence
  [persistence]
  (reify ports/SessionPersistence
    (read-session [_ session-id]
      (ports/read-session persistence session-id))
    (commit-session! [_ expected session]
      (ports/commit-session! persistence expected session)
      (throw (ex-info "simulated post-commit failure" {})))
    (list-session-ids [_]
      (ports/list-session-ids persistence))
    (delete-session! [_ expected session-id]
      (ports/delete-session! persistence expected session-id))
    (read-config [_]
      (ports/read-config persistence))
    (commit-config! [_ expected config]
      (ports/commit-config! persistence expected config))))

(deftest session-create-commit-unknown-is-outcome-unknown
  (let [persistence (ports/memory-persistence)
        reg (registry/registry
             {:persistence (post-commit-unknown-persistence persistence)
              :id-source (ids/counter-source)
              :now-fn (constantly 1000)
              :owner-factory
              (fn [_] (fake-owner {:command-epoch 1 :retired-through 0}))})
        router (app-commands/router {:registry reg
                                     :settings nil
                                     :session-config nil
                                     :dedup-store (durable/memory-store)})
        request {:command-id "cmd-unknown"
                 :command-epoch 1
                 :payload {:kind :session/create :title "Unknown"}}
        first-result (submit-capture! router request)
        session-id (get-in first-result [:receipt :session-id])]
    (is (false? (:ok? first-result)))
    (is (= :unknown (:outcome first-result)))
    (is (some? session-id))
    (is (= 1 (session-count reg)))
    (is (= :outcome-unknown
           (:status (durable/receipt-for (:app-gate router)
                                         "app" "cmd-unknown"))))
    (let [replay (submit-capture! router request)]
      (is (true? (:ok? replay)))
      (is (true? (:replayed? replay)))
      (is (= session-id (get-in replay [:receipt :session-id])))
      (is (= 1 (session-count reg))))
    (durable/close! (:app-gate router))))

(deftest session-create-owner-start-failure-is-outcome-unknown
  (let [persistence (ports/memory-persistence)
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (constantly 1000)
              :owner-factory
              (fn [_] (throw (ex-info "owner down" {})))})
        router (app-commands/router {:registry reg
                                     :settings nil
                                     :session-config nil
                                     :dedup-store (durable/memory-store)})
        request {:command-id "cmd-owner"
                 :command-epoch 1
                 :payload {:kind :session/create :title "Owner"}}
        first-result (submit-capture! router request)
        session-id (get-in first-result [:receipt :session-id])]
    (is (false? (:ok? first-result)))
    (is (= :unknown (:outcome first-result)))
    (is (some? session-id))
    (is (= 1 (session-count reg)))
    (is (= :outcome-unknown
           (:status (durable/receipt-for (:app-gate router)
                                         "app" "cmd-owner"))))
    (let [replay (submit-capture! router request)]
      (is (false? (:ok? replay)))
      (is (= :outcome-unknown (get-in replay [:receipt :status])))
      (is (= :SESSION_OWNER_RECOVERY_FAILED
             (get-in replay [:receipt :error :code])))
      (is (nil? (registry/owner-for reg session-id))))
    (durable/close! (:app-gate router))))

(defn- list-failure-persistence
  [persistence status]
  (reify ports/SessionPersistence
    (read-session [_ id]
      (ports/read-session persistence id))
    (commit-session! [_ expected session]
      (ports/commit-session! persistence expected session))
    (list-session-ids [_]
      {:status status
       :error {:code :STORAGE_READ_UNSTABLE
               :message "store changed during the reuse candidate scan"}})
    (delete-session! [_ expected id]
      (ports/delete-session! persistence expected id))
    (read-config [_]
      (ports/read-config persistence))
    (commit-config! [_ expected config]
      (ports/commit-config! persistence expected config))
    ports/SessionCommandLookup
    (find-session-by-command [_ identity]
      (ports/lookup-session-by-command persistence identity))))

(deftest reuse-candidate-read-failure-is-outcome-unknown
  (let [backing (ports/memory-persistence)
        persistence (list-failure-persistence backing :unknown)
        dedup-store (durable/memory-store)
        {:keys [router]} (make-router persistence dedup-store)
        request {:command-id "cmd-reuse-read"
                 :command-epoch 1
                 :payload {:kind :session/create
                           :title "Reuse"
                           :reuse-blank? true}}
        first-result (submit! router request)]
    (is (false? (:ok? first-result)))
    (is (= :unknown (:outcome first-result))
        "a reuse scan failure happens before any business effect")
    (is (= :outcome-unknown (get-in first-result [:receipt :status])))
    (is (= :outcome-unknown
           (:status (durable/receipt-for (:app-gate router)
                                         "app" "cmd-reuse-read")))
        "the durable receipt must not claim a final refusal")
    (is (= :outcome-unknown
           (:status (gateway-ports/command-receipt router "cmd-reuse-read")))
        "a reconnect receipt query returns the same unknown placeholder")
    (is (= [] (:ids (ports/list-session-ids backing))))
    (testing "a replay returns the unknown placeholder without creating a session"
      (let [replay (submit! router request)]
        (is (true? (:replayed? replay)))
        (is (= :outcome-unknown (get-in replay [:receipt :status])))
        (is (= [] (:ids (ports/list-session-ids backing))))))
    (testing "business validation refusals remain final refusals"
      (let [invalid (submit! router
                             {:command-id "cmd-reuse-invalid"
                              :command-epoch 1
                              :payload {:kind :session/create
                                        :reuse-blank? true
                                        :language :fr}})]
        (is (false? (:ok? invalid)))
        (is (nil? (:outcome invalid)))
        (is (= :LANGUAGE_INVALID (get-in invalid [:error :code])))
        (is (= :refused
               (:status (durable/receipt-for (:app-gate router)
                                             "app" "cmd-reuse-invalid"))))))))

(deftest discard-stop-is-bounded-and-reports-unsettled-work
  (let [owner-state (atom {:commands {:command-epoch 1}
                           :cancel {:active-run-id "run-1"
                                    :nodes {"run-1" {:state :running}}}})
        calls (atom [])
        owner {:snapshot (fn [] {:state @owner-state})
               :submit-command! (fn [command]
                                  (swap! calls conj command)
                                  (swap! owner-state
                                         assoc-in
                                         [:cancel :nodes "run-1" :state]
                                         :cancelled)
                                  {:status :accepted})
               :stop! (fn [opts]
                        (swap! calls conj {:stop opts})
                        {:ok? true :stopped? true :drained? false})}
        router {:state (atom {:owners {"s1" owner}})}
        result (#'app-commands/stop-owner-for-discard!
                router "s1")]
    (is (:ok? result) (pr-str result))
    (is (true? (:unsettled? result))
        "a bounded stop with live work is reported, not waited on forever")
    (is (= :stop-run (get-in (first @calls) [:payload :kind]))
        "the P0 stop-run is submitted before the stop")
    (is (= {:timeout-ms 15000} (:stop (second @calls)))
        "the stop itself is bounded")))

(deftest deleted-session-gets-an-epoch-and-restores-without-an-owner
  (let [persistence (ports/memory-persistence)
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (constantly 1000)
              :owner-factory (fn [_] (fake-owner {:command-epoch 1
                                                  :retired-through 0}))})
        created (registry/create-session! reg {:title "deleted row"})
        sid (:session-id (:session created))
        _ (registry/forget-owner! reg sid)
        base (:session created)
        deleted (assoc base
                       :status :deleted
                       :deleted-at "2026-11-01T00:00:00Z"
                       :revision (inc (long (:revision base)))
                       :persisted-revision (inc (long (:revision base))))
        _ (ports/commit-session! persistence (:revision base) deleted)
        router (app-commands/router {:registry reg
                                     :dedup-store (durable/memory-store)})
        listed (submit-capture!
                router
                {:command-id "list-del"
                 :command-epoch 1
                 :payload {:kind :session/list :include-deleted? true}})
        epoch (get-in listed [:receipt :sessions 0 :command-epoch])]
    (is (true? (:ok? listed)) (pr-str listed))
    (is (pos-int? epoch)
        "a listed deleted session without an Owner gets a durable epoch")
    (let [restored (submit-capture!
                    router
                    {:command-id "restore-del"
                     :command-epoch epoch
                     :payload {:kind :session/restore
                               :session-id sid}})]
      (is (true? (:ok? restored)) (pr-str restored))
      (is (= :active (:status (:session (ports/read-session persistence
                                                           sid))))
          "restore commits the active row without a live Owner"))
    (let [again (submit-capture!
                 router
                 {:command-id "restore-del-2"
                  :command-epoch epoch
                  :payload {:kind :session/restore
                            :session-id sid}})]
      (is (true? (:ok? again)) (pr-str again))
      (is (= :active (:status (:session (ports/read-session persistence
                                                           sid))))))))

(deftest select-skills-routes-to-the-session-owner
  (let [persistence (ports/memory-persistence)
        dedup-store (durable/memory-store)
        seen (atom nil)
        owner {:snapshot (fn [] {:state {:commands {:receipts {}}}})
               :stop! (fn [] {:ok? true})
               :submit-command! (fn [request]
                                  (reset! seen request)
                                  {:status :accepted
                                   :receipt {:status :accepted}})}
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (constantly 1000)
              :owner-factory (fn [_] owner)})
        router (app-commands/router {:registry reg
                                     :settings nil
                                     :session-config nil
                                     :dedup-store dedup-store})]
    (app-commands/register-owner! router "s1" owner)
    (let [result (submit-capture!
                  router
                  {:command-id "cmd-select-skills"
                   :command-epoch 1
                   :payload {:kind :session/select-skills
                             :session-id "s1"
                             :mode :custom
                             :skills ["a"]}})]
      (is (true? (:ok? result)))
      (is (= :session/select-skills (get-in @seen [:payload :kind])))
      (is (= "s1" (get-in @seen [:payload :session-id])))
      (is (= :custom (get-in @seen [:payload :mode])))
      (is (= ["a"] (get-in @seen [:payload :skills]))))))
