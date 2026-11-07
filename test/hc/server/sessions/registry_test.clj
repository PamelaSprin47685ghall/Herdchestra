(ns hc.server.sessions.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.commands :as commands]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.drafts :as drafts]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]))

(defn- serialized-owner
  "A test Owner with one lock and one command gate.

  It applies metadata commands through `sessions/execute`, commits the
  session, and confirms a draft save after the pending commit.  This models
  the single-mailbox contract without threads or Missionary."
  [persistence session-id]
  (let [lock (Object.)
        gate (atom (:state (commands/issue-epoch (commands/new-state))))
        id-source (ids/counter-source)]
    (reify ports/OwnerPort
      (enqueue! [_ message]
        {:status :accepted :message message})
      (submit-command! [_ request]
        (locking lock
          (let [loaded (ports/read-session persistence session-id)]
            (if-not (= :found (:status loaded))
              {:status :refused
               :error {:code :SESSION_NOT_FOUND
                       :message "session does not exist"}}
              (let [result (sessions/execute (:session loaded)
                                             request
                                             {:gate @gate
                                              :now 1000
                                              :id-source id-source})]
                (reset! gate (:gate-state result))
                (let [committed (sessions/commit-session!
                                 persistence (:session result))
                      session (:session committed)
                      draft-confirmed?
                      (when (and (:ok? result)
                                 (not (:replayed? result))
                                 (= :session/update-draft (:type request)))
                        (let [client-id (drafts/normalize-client-id
                                         (or (:client-id request)
                                             (get-in request [:payload :client-id])))
                              revision (get-in session [:drafts client-id :revision])
                              confirmed (sessions/confirm-draft-saved
                                         session client-id revision 1001)]
                          (= :committed
                             (:status
                              (sessions/commit-session!
                               persistence (:session confirmed))))))]
                  (cond-> (select-keys result
                                       [:ok? :receipt :error :replayed?])
                    (:ok? result)
                    (assoc :status (get-in result [:receipt :status]))

                    (some? draft-confirmed?)
                    (assoc :draft-confirmed? draft-confirmed?)

                    (not (:ok? result))
                    (assoc :status :refused)))))))))))

(defn- make-serialized-registry
  []
  (let [persistence (ports/memory-persistence)]
    (registry/registry
     {:persistence persistence
      :id-source (ids/counter-source)
      :now-fn (fn [] 1000)
      :owner-factory (fn [{:keys [session-id]}]
                       (serialized-owner persistence session-id))})))

(defn- make-registry
  ([]
   (make-registry {}))
  ([{:keys [owner-factory persistence]
     :or {persistence (ports/memory-persistence)}}]
   (registry/registry {:persistence persistence
                       :id-source (ids/counter-source)
                       :now-fn (fn [] 1000)
                       :owner-factory owner-factory})))

(deftest create-commit-and-read-one-session
  (let [reg (make-registry)
        created (registry/create-session! reg {:config nil
                                               :client-id "w1"})]
    (is (= :created (:status created)))
    (is (= "hc-session-1" (get-in created [:session :session-id])))
    (let [session-id (get-in created [:session :session-id])
          loaded (registry/get-session reg session-id)
          listed (registry/list-sessions reg)]
      (is (= :found (:status loaded)))
      (is (= 0 (get-in loaded [:session :revision])))
      (is (= :ok (:status listed)))
      (is (= 1 (count (:sessions listed)))))))

(deftest metadata-commands-use-the-owner-gate
  (let [reg (make-serialized-registry)
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        renamed (registry/update-session! reg session-id
                                          {:command-id "meta-1"
                                           :command-epoch 1
                                           :type :session/rename
                                           :payload {:title "Named"}})
        drafted (registry/update-session! reg session-id
                                          {:command-id "meta-2"
                                           :command-epoch 1
                                           :type :session/update-draft
                                           :client-id "w1"
                                           :payload {:text "hello"}})]
    (is (= :accepted (:status renamed)))
    (is (= :accepted (get-in renamed [:receipt :status])))
    (is (= :accepted (:status drafted)))
    (is (true? (:draft-confirmed? drafted)))
    (let [session (:session (registry/get-session reg session-id))]
      (is (= "Named" (:title session)))
      (is (= :saved (get-in session [:drafts "w1" :save-status])))
      (is (= (get-in session [:drafts "w1" :revision])
             (get-in session [:drafts "w1" :persisted-revision]))))
    (let [replay (registry/update-session! reg session-id
                                           {:type :session/rename
                                            :command-id (:command-id
                                                         (:receipt renamed))
                                            :command-epoch (:command-epoch
                                                            (:receipt renamed))
                                            :payload {:title "Named"}})]
      (is (= :accepted (:status replay)))
      (is (true? (:replayed? replay)))
      (is (= (:receipt renamed) (:receipt replay))))))

(deftest conversation-commands-are-not-routed-through-the-metadata-gate
  (let [reg (make-registry)
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        result (registry/update-session! reg session-id
                                         {:type :session/send-message
                                          :payload {:text "hello"}})]
    (is (= :error (:status result)))
    (is (= :CONVERSATION_COMMAND_NOT_ROUTED (get-in result [:error :code])))
    (is (some #{:session/rename}
              (get-in result [:error :details :metadata-types])))))

(deftest an-owner-is-created-after-commit-and-routed-internal-messages
  (let [owners (atom [])
        owner-factory (fn [session]
                        (let [owner (ports/recording-owner)]
                          (swap! owners conj [session owner])
                          owner))
        reg (make-registry {:owner-factory owner-factory})
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        owner (registry/owner-for reg session-id)]
    (is (some? owner))
    (is (registry/registered? reg session-id))
    (is (= 1 (count @owners)))
    (let [closed (registry/close-session! reg session-id {:delete? true})]
      (is (= :submitted (:status closed)))
      (is (= [{:kind :session/close
               :session-id session-id
               :options {:delete? true}}]
             (ports/recorded owner)))
      (is (nil? (registry/owner-for reg session-id))))))

(deftest close-without-an-owner-is-synchronous
  (let [reg (make-registry)
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        closed (registry/close-session! reg session-id {:delete? false})]
    (is (= :closed (:status closed)))
    (is (= :archived (get-in closed [:session :status])))
    (is (= :found (:status (registry/get-session reg session-id))))))

(deftest stop-all-clears-routes-without-deleting-sessions
  (let [owner (ports/recording-owner)
        reg (make-registry {:owner-factory (fn [_] owner)})
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        stopped (registry/stop-all! reg)]
    (is (= :stopped (:status stopped)))
    (is (= 1 (:count stopped)))
    (is (= [{:kind :session/stop :session-id session-id}]
           (ports/recorded owner)))
    (is (nil? (registry/owner-for reg session-id)))
    (is (= :found (:status (registry/get-session reg session-id))))))

(deftest a-commit-conflict-creates-no-owner-and-no-route
  (let [backing (ports/memory-persistence)
        conflict-store (reify ports/SessionPersistence
                         (read-session [_ id]
                           (ports/read-session backing id))
                         (commit-session! [_ _ _]
                           {:status :conflict :revision 0})
                         (list-session-ids [_]
                           (ports/list-session-ids backing))
                         (delete-session! [_ expected id]
                           (ports/delete-session! backing expected id))
                         (read-config [_] (ports/read-config backing))
                         (commit-config! [_ expected config]
                           (ports/commit-config! backing expected config)))
        owners (atom [])
        reg (make-registry {:persistence conflict-store
                            :owner-factory (fn [session]
                                             (swap! owners conj session)
                                             (ports/recording-owner))})
        created (registry/create-session! reg {:config nil :client-id "w1"})]
    (is (= :error (:status created)))
    (is (= :SESSION_COMMIT_CONFLICT (get-in created [:error :code])))
    (is (empty? @owners))
    (is (empty? (get-in @(:state reg) [:owners])))))

(deftest an-owner-start-failure-does-not-hide-the-committed-session
  (let [reg (make-registry
             {:owner-factory (fn [_] (throw (ex-info "boom" {})))})
        created (registry/create-session! reg {:config nil :client-id "w1"})]
    (is (= :error (:status created)))
    (is (= :OWNER_START_FAILED (get-in created [:error :code])))
    (is (= :found (:status (registry/get-session
                            reg (get-in created [:session :session-id])))))
    (is (nil? (registry/owner-for reg
                                  (get-in created [:session :session-id]))))))

(deftest ensure-owner-for-session-recovers-a-failed-start
  (let [calls (atom 0)
        owner {:snapshot (fn [] {:state {}})
               :stop! (fn [] {:ok? true})}
        reg (make-registry
             {:owner-factory
              (fn [_]
                (if (= 1 (swap! calls inc))
                  (throw (ex-info "owner down" {}))
                  owner))})
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session (:session created)
        session-id (:session-id session)]
    (is (= :error (:status created)))
    (is (= :OWNER_START_FAILED (get-in created [:error :code])))
    (is (nil? (registry/owner-for reg session-id)))
    (let [recovered (registry/ensure-owner-for-session! reg session)]
      (is (true? (:ok? recovered)))
      (is (identical? owner (:owner recovered)))
      (is (= 2 @calls))
      (is (identical? owner (registry/owner-for reg session-id))))
    (testing "an existing Owner is reused without a second start"
      (let [again (registry/ensure-owner-for-session! reg session)]
        (is (true? (:ok? again)))
        (is (= 2 @calls))))))

(deftest ensure-owner-for-session-refuses-a-missing-session
  (let [calls (atom 0)
        reg (make-registry
             {:owner-factory (fn [_]
                               (swap! calls inc)
                               (ports/recording-owner))})]
    (doseq [session [nil {} {:session-id ""}]]
      (let [result (registry/ensure-owner-for-session! reg session)]
        (is (false? (:ok? result)))
        (is (= :SESSION_INVALID (get-in result [:error :code])))))
    (doseq [status [:archived :deleted]]
      (let [result (registry/ensure-owner-for-session!
                    reg {:session-id "inactive" :status status})]
        (is (false? (:ok? result)))
        (is (= :SESSION_NOT_ACTIVE (get-in result [:error :code])))
        (is (= status (get-in result [:error :details :status])))))
    (is (zero? @calls))
    (is (empty? (get-in @(:state reg) [:owners])))))

(deftest ensure-owner-for-refuses-a-closed-committed-session
  (let [persistence (ports/memory-persistence)
        creator (make-registry {:persistence persistence})
        deleted (registry/create-session! creator {:config nil :client-id "w1"})
        deleted-id (get-in deleted [:session :session-id])
        archived (registry/create-session! creator {:config nil :client-id "w1"})
        archived-id (get-in archived [:session :session-id])
        active (registry/create-session! creator {:config nil :client-id "w1"})
        active-id (get-in active [:session :session-id])
        _ (is (= :closed (:status (registry/close-session! creator deleted-id
                                                           {:delete? true}))))
        _ (is (= :closed (:status (registry/close-session! creator archived-id
                                                           {:delete? false}))))
        calls (atom 0)
        reg (make-registry
             {:persistence persistence
              :owner-factory (fn [_]
                               (swap! calls inc)
                               (ports/recording-owner))})]
    (doseq [[session-id status] [[deleted-id :deleted]
                                 [archived-id :archived]]]
      (let [result (registry/ensure-owner-for! reg session-id)]
        (is (false? (:ok? result)))
        (is (= :SESSION_NOT_ACTIVE (get-in result [:error :code])))
        (is (= status (get-in result [:error :details :status])))
        (is (nil? (registry/owner-for reg session-id)))))
    (is (zero? @calls)
        "retry recovery does not start an Owner for a closed session")
    (let [result (registry/ensure-owner-for! reg active-id)]
      (is (true? (:ok? result)))
      (is (some? (:owner result)))
      (is (= 1 @calls))
      (is (some? (registry/owner-for reg active-id))))
    (testing "an existing Owner is reused without a second start"
      (let [again (registry/ensure-owner-for! reg active-id)]
        (is (true? (:ok? again)))
        (is (true? (:reused? again)))
        (is (= 1 @calls))))))

(defn- list-failure-persistence
  [backing status]
  (reify ports/SessionPersistence
    (read-session [_ id]
      (ports/read-session backing id))
    (commit-session! [_ expected session]
      (ports/commit-session! backing expected session))
    (list-session-ids [_]
      {:status status
       :error {:code :STORAGE_READ_UNSTABLE
               :message "store changed during the reuse scan"}})
    (delete-session! [_ expected id]
      (ports/delete-session! backing expected id))
    (read-config [_]
      (ports/read-config backing))
    (commit-config! [_ expected config]
      (ports/commit-config! backing expected config))
    ports/SessionCommandLookup
    (find-session-by-command [_ identity]
      (ports/lookup-session-by-command backing identity))))

(deftest a-reuse-candidate-read-failure-is-an-unknown-outcome
  (doseq [status [:error :unknown]]
    (testing (str "the persistence adapter reports " status)
      (let [reg (registry/registry
                 {:persistence (list-failure-persistence
                                (ports/memory-persistence) status)
                  :id-source (ids/counter-source)
                  :now-fn (fn [] 1000)})
            result (registry/create-session! reg
                                             {:config nil
                                              :client-id "w1"
                                              :reuse-blank? true})]
        (is (= :error (:status result)))
        (is (= :unknown (:outcome result))
            "a read failure before any business effect is not a refusal")
        (is (= :STORAGE_READ_UNSTABLE (get-in result [:error :code])))
        (is (nil? (:session result)))
        (is (= :error (:status (registry/list-sessions reg)))
            "a list failure is an error, not an empty success")))))

(defn- unknown-read-persistence
  "Wrap BACKING so one session read reports the storage adapter's `:unknown`."
  [backing session-id]
  (reify ports/SessionPersistence
    (read-session [_ id]
      (if (= id session-id)
        {:status :unknown
         :error {:code :STORAGE_READ_UNSTABLE
                 :message "session read became unstable"}}
        (ports/read-session backing id)))
    (commit-session! [_ expected session]
      (ports/commit-session! backing expected session))
    (list-session-ids [_]
      (ports/list-session-ids backing))
    (delete-session! [_ expected id]
      (ports/delete-session! backing expected id))
    (read-config [_]
      (ports/read-config backing))
    (commit-config! [_ expected config]
      (ports/commit-config! backing expected config))
    ports/SessionCommandLookup
    (find-session-by-command [_ identity]
      (ports/lookup-session-by-command backing identity))))

(deftest an-unknown-session-read-fails-the-whole-scan
  (let [backing (ports/memory-persistence)
        base (registry/registry
              {:persistence backing
               :id-source (ids/counter-source)
               :now-fn (fn [] 1000)})
        created (registry/create-session! base {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        reg (registry/registry
             {:persistence (unknown-read-persistence backing session-id)
              :id-source (ids/counter-source)
              :now-fn (fn [] 1000)})]
    (testing "a single unknown read fails the list instead of dropping the session"
      (let [listed (registry/list-sessions reg)]
        (is (= :error (:status listed)))
        (is (= :STORAGE_READ_UNSTABLE (get-in listed [:error :code])))))
    (testing "reuse discovery stops before creating another session"
      (let [result (registry/create-session! reg
                                             {:config nil
                                              :client-id "w2"
                                              :reuse-blank? true})]
        (is (= :error (:status result)))
        (is (= :unknown (:outcome result)))
        (is (= :STORAGE_READ_UNSTABLE (get-in result [:error :code])))
        (is (= 1 (count (:sessions (registry/list-sessions base)))))))))

(deftest lookup-fallback-surfaces-a-session-read-failure
  (let [backing (ports/memory-persistence)
        base (registry/registry
              {:persistence backing
               :id-source (ids/counter-source)
               :now-fn (fn [] 1000)})
        created (registry/create-session! base {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        fallback (reify ports/SessionPersistence
                   (read-session [_ id]
                     (if (= id session-id)
                       {:status :unknown
                        :error {:code :STORAGE_READ_UNSTABLE
                                :message "session read became unstable"}}
                       (ports/read-session backing id)))
                   (commit-session! [_ expected session]
                     (ports/commit-session! backing expected session))
                   (list-session-ids [_]
                     (ports/list-session-ids backing))
                   (delete-session! [_ expected id]
                     (ports/delete-session! backing expected id))
                   (read-config [_]
                     (ports/read-config backing))
                   (commit-config! [_ expected config]
                     (ports/commit-config! backing expected config)))
        result (ports/lookup-session-by-command
                fallback
                {:scope "app" :command-epoch 1 :command-id "missing"})]
    (is (= :error (:status result)))
    (is (= :STORAGE_READ_UNSTABLE (get-in result [:error :code])))))

(deftest registry-invariants-hold
  (let [reg (make-registry)]
    (is (empty? (registry/check-invariants reg)))))

(deftest branch-session-commits-and-registers
  (let [reg (make-registry)
        created (registry/create-session! reg {:config nil :client-id "w1"})
        source-id (get-in created [:session :session-id])
        branch (registry/branch-session! reg source-id {})]
    (is (= :created (:status branch)))
    (is (= source-id (get-in branch [:session :origin-session-id])))
    (is (not= source-id (get-in branch [:session :session-id])))
    (is (= :found (:status (registry/get-session
                            reg (get-in branch [:session :session-id])))))
    (is (= 2 (count (:sessions (registry/list-sessions reg)))))))

(deftest metadata-commands-are-serialized-by-one-owner
  (let [reg (make-serialized-registry)
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        requests [{:command-id "race-w1"
                   :command-epoch 1
                   :client-id "w1"
                   :type :session/update-draft
                   :payload {:text "from w1"}}
                  {:command-id "race-w2"
                   :command-epoch 1
                   :client-id "w2"
                   :type :session/update-draft
                   :payload {:text "from w2"}}]
        results (mapv deref
                      (mapv #(future (registry/update-session! reg session-id %))
                            requests))]
    (is (every? #(= :accepted (:status %)) results))
    (let [session (:session (registry/get-session reg session-id))]
      (is (= "from w1" (get-in session [:drafts "w1" :text])))
      (is (= "from w2" (get-in session [:drafts "w2" :text])))
      (is (drafts/saved? (get-in session [:drafts "w1"])))
      (is (drafts/saved? (get-in session [:drafts "w2"]))))
    (let [same {:command-id "race-same"
                :command-epoch 1
                :client-id "w3"
                :type :session/update-draft
                :payload {:text "same"}}
          pair (mapv deref
                     [(future (registry/update-session! reg session-id same))
                      (future (registry/update-session! reg session-id same))])
          statuses (set (map :status pair))
          replayed (filter :replayed? pair)
          session (:session (registry/get-session reg session-id))]
      (is (= #{:accepted} statuses))
      (is (= 1 (count replayed)))
      (is (= "same" (get-in session [:drafts "w3" :text])))
      (is (= 1 (get-in session [:drafts "w3" :revision]))))))

(deftest registry-accepts-a-compose-owner-handle-map
  (let [persistence (ports/memory-persistence)
        calls (atom [])
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (fn [] 1000)
              :owner-factory
              (fn [_]
                {:submit-command! (fn [request]
                                    (swap! calls conj request)
                                    {:status :accepted
                                     :receipt {:status :accepted}})
                 :submit! (fn [_] {:status :accepted})})})
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])
        result (registry/update-session! reg session-id
                                         {:command-id "handle-1"
                                          :command-epoch 1
                                          :type :session/rename
                                          :payload {:title "X"}})]
    (is (= :accepted (:status result)))
    (is (= 1 (count @calls)))
    (is (= "handle-1" (get-in @calls [0 :command-id])))
    (is (= :session/rename (get-in @calls [0 :payload :kind])))))

(deftest registry-reads-merge-a-newer-live-owner-session
  (let [persistence (ports/memory-persistence)
        live (atom nil)
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (fn [] 1000)
              :owner-factory
              (fn [session]
                (reset! live session)
                {:snapshot (fn [] {:state {:session @live}})
                 :submit-command! (fn [_] {:status :accepted})
                 :submit! (fn [_] {:status :accepted})})})
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])]
    (swap! live assoc
           :revision 9
           :title "Live title"
           :model-binding {:connection-id "conn-live"
                           :model-entry-id "model-live"
                           :display-name "Live model"}
           :drafts {"w1" {:client-id "w1"
                          :text "live draft"
                          :revision 3
                          :persisted-revision 3
                          :save-status :saved}})
    (let [loaded (registry/get-session reg session-id)
          listed (registry/list-sessions reg)
          branch (registry/branch-session! reg session-id {})]
      (is (= "Live title" (get-in loaded [:session :title])))
      (is (= "conn-live" (get-in loaded [:session :model-binding
                                          :connection-id])))
      (is (= "live draft" (get-in loaded [:session :drafts "w1" :text])))
      (is (= "Live title" (get-in listed [:sessions 0 :title])))
      (is (= :created (:status branch)))
      (is (= "Branch of Live title" (get-in branch [:session :title]))))))

(deftest create-session-reuses-a-blank-session
  (let [reg (make-registry)
        first (registry/create-session! reg {:config nil :client-id "w1"})
        first-id (get-in first [:session :session-id])
        second (registry/create-session! reg {:config nil :client-id "w1"
                                              :reuse-blank? true})]
    (is (= :created (:status second)))
    (is (true? (:reused? second)))
    (is (= first-id (get-in second [:session :session-id])))
    (is (= 1 (count (:sessions (registry/list-sessions reg)))))))

(deftest explicit-capability-scope-disables-blank-reuse
  (let [reg (make-registry)
        first (registry/create-session! reg {:config nil :client-id "w1"
                                             :reuse-blank? true})
        first-id (get-in first [:session :session-id])
        scoped (registry/create-session! reg
                                         {:config nil
                                          :client-id "w1"
                                          :reuse-blank? true
                                          :capability-scope
                                          {:mode :restricted}})]
    (is (= :created (:status scoped)))
    (is (not= first-id (get-in scoped [:session :session-id])))
    (is (= {:mode :restricted}
           (get-in scoped [:session :capability-scope])))
    (is (= 2 (count (:sessions (registry/list-sessions reg)))))))

(deftest blank-reuse-stops-after-a-draft-and-for-explicit-choices
  (let [reg (make-serialized-registry)
        first (registry/create-session! reg {:config nil :client-id "w1"
                                             :reuse-blank? true})
        first-id (get-in first [:session :session-id])
        drafted (registry/update-session! reg first-id
                                          {:command-id "draft-1"
                                           :command-epoch 1
                                           :client-id "w1"
                                           :type :session/update-draft
                                           :payload {:text "typed"}})
        second (registry/create-session! reg {:config nil :client-id "w1"
                                              :reuse-blank? true})
        third (registry/create-session! reg {:config nil :client-id "w1"
                                             :reuse-blank? true
                                             :language :zh-CN})
        temporary (registry/create-session! reg {:config nil :client-id "w1"
                                                 :reuse-blank? true
                                                 :temporary? true})]
    (is (= :accepted (:status drafted)))
    (is (not= first-id (get-in second [:session :session-id])))
    (is (not= first-id (get-in third [:session :session-id])))
    (is (true? (get-in temporary [:session :temporary?])))
    (is (not= first-id (get-in temporary [:session :session-id])))
    (is (= 4 (count (:sessions (registry/list-sessions reg)))))))

(deftest temporary-session-transfer-and-restart-discard
  (let [persistence (ports/memory-persistence)
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (fn [] 1000)
              :owner-factory (fn [session]
                               (let [owner (serialized-owner persistence
                                                             (:session-id session))]
                                 owner))})
        temp (registry/create-session! reg {:config nil :client-id "w1"
                                            :temporary? true})
        session-id (get-in temp [:session :session-id])
        transferred (registry/update-session! reg session-id
                                              {:command-id "transfer-1"
                                               :command-epoch 1
                                               :type :session/transfer
                                               :payload {}})
        loaded (registry/get-session reg session-id)]
    (is (:ok? transferred))
    (is (false? (get-in transferred [:receipt :result :temporary?])))
    (is (false? (get-in loaded [:session :temporary?])))
    (is (= :found (:status loaded)))))

(deftest discard-temporary-session-survives-a-registry-restart
  (let [persistence (ports/memory-persistence)
        reg-1 (make-registry {:persistence persistence})
        created (registry/create-session! reg-1 {:config nil :client-id "w1"
                                                 :temporary? true})
        session-id (get-in created [:session :session-id])
        discarded (registry/discard-session! reg-1 session-id)
        reg-2 (make-registry {:persistence persistence})
        loaded (registry/get-session reg-2 session-id)
        listed (registry/list-sessions reg-2)]
    (is (= :discarded (:status discarded)))
    (is (= :missing (:status loaded)))
    (is (= :ok (:status listed)))
    (is (empty? (:sessions listed)))))

(deftest purge-removes-any-soft-deleted-record-immediately
  (let [now (atom 1000)
        persistence (ports/memory-persistence)
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (fn [] @now)})
        temp (registry/create-session! reg {:config nil :client-id "w1"
                                            :temporary? true})
        temp-id (get-in temp [:session :session-id])
        normal (registry/create-session! reg {:config nil :client-id "w1"})
        normal-id (get-in normal [:session :session-id])]
    (registry/close-session! reg temp-id {:delete? true})
    (registry/close-session! reg normal-id {:delete? true})
    (is (= :purged (:status (registry/purge-session! reg temp-id))))
    (is (= :purged (:status (registry/purge-session! reg normal-id))))))

(deftest forgetting-a-stopped-owner-keeps-the-session-record
  (let [reg (make-registry {:owner-factory (fn [_] (ports/recording-owner))})
        created (registry/create-session! reg {:config nil :client-id "w1"})
        session-id (get-in created [:session :session-id])]
    (is (some? (registry/owner-for reg session-id)))
    (is (= :forgotten (:status (registry/forget-owner! reg session-id))))
    (is (nil? (registry/owner-for reg session-id)))
    (is (= :found (:status (registry/get-session reg session-id))))))

(deftest discard-stops-a-live-owner-and-removes-the-record
  (let [stopped (atom false)
        reg (make-registry
             {:owner-factory
              (fn [_]
                {:stop! (fn [] (reset! stopped true)
                          {:stopped? true :drained? true})
                 :snapshot (fn [] {:state {:session nil}})
                 :submit-command! (fn [_] {:status :accepted})
                 :submit! (fn [_] {:status :accepted})})})
        created (registry/create-session! reg {:config nil :client-id "w1"
                                               :temporary? true})
        session-id (get-in created [:session :session-id])
        discarded (registry/discard-session! reg session-id)]
    (is (true? @stopped))
    (is (= :discarded (:status discarded)))
    (is (= :missing (:status (registry/get-session reg session-id))))
    (is (nil? (registry/owner-for reg session-id)))))

(deftest discard-refuses-an-owner-without-drain-evidence
  (let [reg (make-registry
             {:owner-factory
              (fn [_]
                {:stop! (fn [] {:ok? true})
                 :snapshot (fn [] {:state {:session nil}})
                 :submit-command! (fn [_] {:status :accepted})
                 :submit! (fn [_] {:status :accepted})})})
        created (registry/create-session! reg {:config nil :client-id "w1"
                                               :temporary? true})
        session-id (get-in created [:session :session-id])
        discarded (registry/discard-session! reg session-id)]
    (is (= :error (:status discarded)))
    (is (= :OWNER_STOP_INCOMPLETE (get-in discarded [:error :code])))
    (is (= :found (:status (registry/get-session reg session-id))))
    (is (some? (registry/owner-for reg session-id)))))
