(ns hc.client.workbench.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.client.conversation.surface :as surface]
            [hc.client.conversation.stream :as stream]
            [hc.client.settings.support :as settings-support]
            [hc.client.sync :as sync]
            [hc.client.workbench.model :as model]))

(deftest route-roundtrip
  (testing "known routes and session fragments"
    (is (= {:route :board :session-id nil} (model/parse-hash "#/board")))
    (is (= {:route :sessions :session-id "s-1"} (model/parse-hash "#/sessions/s-1")))
    (is (= {:route :sessions :session-id nil} (model/parse-hash "")))
    (is (= "#/sessions/s-1" (model/route->hash :sessions "s-1")))
    (is (= "#/settings" (model/route->hash :settings nil)))
    (is (= :sessions (model/route "nonsense")))))

(deftest swarm-route-is-a-board-alias
  (is (= {:route :board :session-id nil}
         (model/parse-hash "#/swarm/s-1")))
  (is (= :board (:route (model/parse-hash "#/swarm"))))
  (is (= :board (:route (model/parse-hash "#/board/s-1")))))

(deftest send-guard-blocks-card-conflicts-and-missing-placeholders
  (let [conflict {:instruction-mode :turn
                  :turn-card-ids ["c1" "c2"]
                  :cards {"c1" {:id "c1" :name "A" :enabled? true
                                :output-format :text}
                          "c2" {:id "c2" :name "B" :enabled? true
                                :output-format :json}}}
        missing {:instruction-mode :turn
                 :turn-card-ids ["c1"]
                 :cards {"c1" {:id "c1" :name "A" :enabled? true
                               :placeholders [{:key "topic" :required? true}]}}
                 :instruction-values {}}
        ok {:instruction-mode :turn :turn-card-ids [] :cards {}}]
    (is (= :instruction-format-conflict
           (:reason (model/send-guard conflict))))
    (is (re-find #"text" (:message (model/send-guard conflict))))
    (is (= :missing-required-placeholders
           (:reason (model/send-guard missing))))
    (is (re-find #"A / topic" (:message (model/send-guard missing))))
    (is (nil? (model/send-guard ok)))
    (is (nil? (model/send-guard nil)))))

(deftest transcript-insert-requires-a-session
  (let [draft {:text "ab" :cursor 2}]
    (is (= {:session-id "s1" :text "aXYb" :cursor 3 :at "t"}
           (model/insert-transcript-change draft "s1" "XY" 1 "t")))
    (is (= {:session-id "s1" :text "abZ" :cursor 3 :at "t"}
           (model/insert-transcript-change draft "s1" "Z" 99 "t"))
        "an out-of-range insert position clamps to the end")
    (is (nil? (model/insert-transcript-change draft nil "XY" 1 "t")))
    (is (nil? (model/insert-transcript-change draft "  " "XY" 1 "t")))
    (is (nil? (model/insert-transcript-change draft "s1" "  " 1 "t")))))

(deftest route-property
  (let [property (prop/for-all [route (gen/elements (vec model/routes))
                                 session-id (gen/one-of [gen/string-alphanumeric
                                                         (gen/return nil)])]
                    (let [hash (model/route->hash route session-id)
                          parsed (model/parse-hash hash)]
                      (= {:route route
                          :session-id (when (and (= route :sessions)
                                                 (string? session-id)
                                                 (seq session-id))
                                        session-id)}
                         parsed)))]
    (is (:pass? (tc/quick-check 100 property)))))

(deftest connection-view-honesty
  (let [disconnected (model/connection-view {:phase :disconnected :subscription 0})
        synced (model/connection-view {:phase :synced :subscription 3 :epoch 7 :seq 40})
        awaiting (model/connection-view {:phase :connected-awaiting-sync :subscription 3})]
    (is (false? (:command-allowed? disconnected)))
    (is (true? (:command-allowed? synced)))
    (is (false? (:command-allowed? awaiting)))
    (is (= :ok (:tone synced)))
    (is (= :danger (:tone disconnected)))
    (is (false? (:can-resync? disconnected)))
    (is (true? (:can-resync? synced)))))

(deftest session-with-model-carries-live-availability
  (testing "the real summary shape reaches the composer gate"
    (let [wire {:session-id "s1"
                :title "Same Model"
                ;; The immutable run snapshot the session keeps.
                :model-binding {:connection-id "alpha"
                                :connection-name "Alpha Provider"
                                :model-entry-id "same-model"
                                :display-name "Same Model"
                                :capabilities {:tools true}}
                ;; The live fact the server publishes next to it.
                :model {:connection-id "alpha"
                        :model-entry-id "same-model"
                        :display-name "Same Model"
                        :connection-name "Alpha Provider"}
                :model-availability :connection-removed}
          session (model/session-with-model wire)
          view-model (surface/surface
                      {:session-id "s1"
                       :session session
                       :draft {:text "" :version 0}
                       :stream (stream/initial)
                       :connection {:phase :synced}
                       :messages []
                       :callbacks {:send! identity :stop! identity
                                   :fix! identity}})
          reason (get-in view-model [:composer :send :reason])]
      (is (= :connection-removed (get-in session [:model :availability])))
      (is (= :select-model (get-in view-model [:composer :send :intent])))
      (is (true? (get-in view-model [:composer :send :disabled?])))
      (is (re-find #"连接" reason)
          "an empty draft must not hide the connection explanation")
      (is (not (re-find #"输入为空" reason)))
      (is (re-find #"连接已移除" (get-in view-model [:header :model-label])))
      (is (some #(= "选择其他模型" (:label %))
                (mapcat :actions (:banners view-model))))))
  (testing "unconfirmed is a live fact, not a disabled binding"
    (let [session (model/session-with-model
                   {:session-id "s1"
                    :model-binding {:connection-id "alpha"
                                    :model-entry-id "same-model"}
                    :model-availability :unconfirmed})
          view-model (surface/surface
                      {:session-id "s1"
                       :session session
                       :draft {:text "" :version 0}
                       :stream (stream/initial)
                       :connection {:phase :synced}
                       :messages []
                       :callbacks {:send! identity :stop! identity}})]
      (is (= :unconfirmed (get-in session [:model :availability])))
      (is (= :disabled (get-in view-model [:composer :send :intent])))
      (is (= "输入为空" (get-in view-model [:composer :send :reason])))))
  (testing "a bare identity invents no availability"
    (let [session (model/session-with-model
                   {:session-id "s1"
                    :model-binding {:connection-id "alpha"
                                    :model-entry-id "same-model"}})]
      (is (= "same-model" (get-in session [:model :id])))
      (is (not (contains? (:model session) :availability))))))

(deftest overlay-stack-follows-the-drawer-layout
  (testing "wide columns are persistent, not Escape overlays"
    (is (= []
           (model/overlay-stack
            {:ui/viewport-width 1440
             :ui/sidebar-open true
             :ui/aux-open true
             :ui/sidebar-return-id "wb-menu"
             :ui/aux-return-id "hc-header-action-open-aux"}))))
  (testing "drawer overlays keep the control that opened each one"
    (is (= [{:id :nav :trigger-id "wb-menu"}
            {:id :aux :trigger-id "hc-header-action-open-aux"}]
           (model/overlay-stack
            {:ui/viewport-width 800
             :ui/sidebar-open true
             :ui/aux-open true
             :ui/sidebar-return-id "wb-menu"
             :ui/aux-return-id "hc-header-action-open-aux"}))))
  (testing "missing return ids fall back to the topbar controls"
    (is (= [{:id :aux :trigger-id "wb-aux-toggle"}]
           (model/overlay-stack
            {:ui/viewport-width 800
             :ui/aux-open true})))))

(deftest connection-property
  (let [property (prop/for-all [phase (gen/elements [:disconnected :connecting
                                                     :connected-awaiting-sync :synced
                                                     :interrupted])
                                 epoch (gen/one-of [gen/nat (gen/return nil)])
                                 seq (gen/one-of [gen/nat (gen/return nil)])]
                    (let [view (model/connection-view {:phase phase
                                                       :epoch epoch
                                                       :seq seq})]
                      (= (:synced? view)
                         (and (= :synced phase) (some? epoch) (some? seq)))))]
    (is (:pass? (tc/quick-check 100 property)))))

(deftest sessions-sort-and-message-order
  (let [db {:session/by-id
            {"s-1" {:id "s-1" :title "ordinary" :updated-at "2026-01-01"}
             "s-2" {:id "s-2" :title "pinned" :pinned? true :updated-at "2025-01-01"}
             "s-3" {:id "s-3" :title "newer" :updated-at "2026-02-01"}}
            :message/by-id
            {"m-1" {:id "m-1" :session-id "s-1" :created-at "1" :content "one"}
             "m-2" {:id "m-2" :session-id "s-2" :created-at "2" :content "two"}
             "m-3" {:id "m-3" :session-id "s-1" :created-at "3" :content "three"}}
            :message/order ["m-1" "m-2" "m-3"]}]
    (is (= ["s-2" "s-3" "s-1"] (mapv :id (model/sessions db))))
    (is (= ["m-1" "m-3"] (mapv :id (model/messages-for-session db "s-1"))))
    (is (= ["m-2"] (mapv :id (model/messages-for-session db "s-2"))))))

(deftest explicit-session-order-keeps-late-extras
  (let [db {:session/by-id {"s1" {:id "s1" :message-ids ["m1"]}}
            :message/by-id {"m1" {:id "m1" :session-id "s1" :content "one"}
                            "m2" {:id "m2" :session-id "s1" :content "two"}}
            :message/order ["m1" "m2"]}]
    (is (= ["m1" "m2"]
           (mapv :id (model/messages-for-session db "s1")))
        "a snapshot order that predates a live message must not hide it")))

(deftest server-shaped-session-and-messages
  (let [db {:session/by-id {"s1" {:session-id "s1"
                                  :title "Server session"
                                  :status :active
                                  :updated-at 100}}
            :session/active-id "s1"
            :message/by-id {"m1" {:message-id "m1"
                                  :session-id "s1"
                                  :role :user
                                  :content "hello"}}
            :message/order ["m1"]}]
    (is (= ["s1"] (mapv :id (model/sessions db))))
    (is (= "s1" (model/selected-session-id db)))
    (is (= ["m1"] (mapv :message-id (model/messages-for-session db "s1"))))
    (is (= "s1" (model/id-of {:session-id "s1"})))))

(deftest per-session-command-epoch-never-uses-feed-epoch
  (let [db {:sync {:command-epoch 3}
            :session/active-id "s1"
            :session/by-id {"s1" {:command-epoch 5}
                            "s2" {:command-epoch 7}}}]
    (is (= 5 (model/session-command-epoch db "s1")))
    (is (= 7 (model/session-command-epoch db "s2")))
    (is (nil? (model/session-command-epoch db "missing")))
    (testing "wire-string keys and non-positive epochs are handled honestly"
      (is (= 9 (model/session-command-epoch
                {"session/by-id" {"s1" {"command-epoch" 9}}} "s1")))
      (is (nil? (model/session-command-epoch
                 {:session/by-id {"s1" {:command-epoch 0}}} "s1"))))
    (testing "a deleted-list row carries the Owner epoch for restore/purge"
      (is (= 11 (model/session-command-epoch
                 {:ui/session-list [{:session-id "s1"
                                     :command-epoch 11}]}
                 "s1")))
      (testing "another deleted row's epoch is never borrowed"
        (is (nil? (model/session-command-epoch
                   {:ui/session-list [{:session-id "s2"
                                       :command-epoch 13}]}
                   "s1")))))
    (testing "a stale display row without an epoch does not shadow the list"
      (is (= 11 (model/session-command-epoch
                 {:session/by-id {"s1" {:id "s1"}}
                  :ui/session-list [{:session-id "s1"
                                     :command-epoch 11}]}
                 "s1"))))
    (testing "the live display database is consulted before the adapter view"
      (is (= 11 (model/resolve-session-command-epoch
                 {:session/by-id {}
                  :ui/session-list [{:session-id "s1"
                                     :command-epoch 11}]}
                 {:session/by-id {}
                  :sync {:command-epoch 9}}
                 "s1")))
      (is (nil? (model/resolve-session-command-epoch
                 {:session/by-id {}
                  :ui/session-list [{:session-id "s2"
                                     :command-epoch 13}]}
                 {:session/by-id {}
                  :sync {:command-epoch 9}}
                 "s1"))
          "neither another deleted row nor the app epoch authorizes s1"))
    (is (nil? (model/session-command-epoch
               (update db :session/by-id dissoc "s1") "s1"))
        "the app epoch never authorizes a session command")))

(deftest in-flight-command-gate-is-kind-and-status-aware
  (let [db {:ui/command
            {"stop" {:command-id "stop" :kind :stop-run
                     :session-id "s1" :status :accepted}
             "send" {:command-id "send" :kind :send-message
                     :session-id "s1" :status :submitting}}}]
    (is (= "send" (:command-id (model/in-flight-session-command
                                db "s1" #{:send-message}))))
    (is (nil? (model/in-flight-session-command
               db "s2" #{:send-message})))
    (is (nil? (model/in-flight-session-command
               (assoc-in db [:ui/command "send" :status] :accepted)
               "s1" #{:send-message})))
    (is (nil? (model/in-flight-session-command
               db "s1" #{:answer-ask}))
        "an accepted stop never blocks a different command kind")))

(deftest server-run-state-allows-stop
  (let [base {:session/by-id {"s1" {:session-id "s1" :current-run-id "r1"}}
              :run/by-id {"r1" {:run-id "r1" :status :running}}}]
    (is (true? (model/run-active? base "s1")))
    (is (true? (model/run-active? {:session/by-id {"s1" {:session-id "s1"
                                                          :run-status :starting}}} "s1")))
    (is (false? (model/run-active? {:session/by-id {"s1" {:session-id "s1"}}} "s1")))))

(deftest branch-origin-is-a-real-jump-only-when-visible
  (let [db {:session/by-id
            {"s1" {:id "s1" :title "Branch" :branch-of "s0"
                   :origin-message-id "m1"}
             "s0" {:id "s0" :title "Source"}}}
        origin (model/branch-origin db "s1")]
    (is (= "s0" (:session-id origin)))
    (is (true? (:visible? origin)))
    (is (= "Source" (:title origin)))
    (is (= "m1" (:origin-message-id origin))))
  (let [db {:session/by-id
            {"s1" {:id "s1" :title "Branch"
                   :origin-session-id "gone"}}}
        origin (model/branch-origin db "s1")]
    (is (= "gone" (:session-id origin)))
    (is (false? (:visible? origin)))
    (is (nil? (:title origin))))
  (is (nil? (model/branch-origin {:session/by-id {"s1" {:id "s1"}}} "s1")))
  (is (nil? (model/branch-origin
             {:session/by-id {"s1" {:id "s1" :branch-of "s1"}}} "s1"))))

(deftest missing-session-messages-detects-an-inconsistent-snapshot
  (is (true? (model/missing-session-messages?
              {:session/by-id {"s1" {:id "s1" :message-ids ["m1"]}}}
              "s1")))
  (is (false? (model/missing-session-messages?
               {:session/by-id {"s1" {:id "s1" :message-ids ["m1"]}}
                :message/by-id {"m1" {:id "m1" :session-id "s1"}}
                :message/order ["m1"]}
               "s1")))
  (is (false? (model/missing-session-messages?
               {:session/by-id {"s1" {:id "s1" :message-ids []}}}
               "s1")))
  (is (false? (model/missing-session-messages? {} "s1"))))

(deftest stop-does-not-depend-on-the-local-stream
  (let [connection {:command-allowed? true}
        idle (stream/initial)]
    (is (= {:ok? true :reason nil}
           (model/stop-command-decision
            {:connection connection
             :session-id "s1"
             :epoch 7
             :stream idle}))
        "an idle visible stream is not evidence that the server has no work")
    (is (= :no-session
           (:reason (model/stop-command-decision
                     {:connection connection :session-id nil
                      :epoch 7 :stream idle}))))
    (is (= :command-epoch-unknown
           (:reason (model/stop-command-decision
                     {:connection connection :session-id "s1"
                      :epoch nil :stream idle})))
        "a session command never borrows the app epoch")
    (is (= :stopping
           (:reason (model/stop-command-decision
                     {:connection connection :session-id "s1"
                      :epoch 7 :stream (assoc idle :stop-state :stopping)}))))))

(deftest nudge-messages-are-not-rendered
  (let [db {:session/by-id {"s1" {:session-id "s1" :message-ids ["m1" "m2" "m3"]}}
            :message/by-id {"m1" {:message-id "m1" :content "real"}
                            "m2" {:message-id "m2" :content "nudge" :nudge? true}
                            "m3" {:message-id "m3" :content "nudge-id" :nudge-id "n1"}}}]
    (is (= ["m1"] (mapv :message-id (model/messages-for-session db "s1"))))))

(deftest empty-server-is-empty
  (let [db {:ui/route :sessions}
        roles (model/role-cards db)]
    (is (empty? (model/sessions db)))
    (is (empty? (model/swarms db)))
    (is (empty? (model/tasks db)))
    (is (= 5 (count roles))
        "the fixed role vocabulary is visible before any model is configured")
    (is (every? nil? (map :selection-label roles))
        "an empty server never invents a selected model")
    (is (re-find #"未选择任务模型"
                 (:status (first (filter #(= :task (:role %)) roles)))))
    (is (empty? (model/connection-cards db)))
    (is (empty? (model/artifacts db)))
    (is (string? (model/empty-state :board)))))

(deftest assistant-role-ownership-comes-from-settings
  (let [settings (settings-support/state
                  {:connections
                   {"a" (settings-support/connection
                         "a" "Main Conn" "https://a.example.com/v1")}
                   :models
                   {"a" [(settings-support/model "m" "Main Model")]}
                   :roles
                   {:main {:connection-id "a" :model-id "m"
                           :mode :explicit :confirmed? true}
                    :task {:connection-id "a" :model-id "m"
                           :mode :explicit :confirmed? true}}})
        db {:ui/settings settings
            ;; These legacy tables are not part of the baseline.  A stale
            ;; value must not become a second role or connection truth.
            :profile/by-id {"legacy" {:id "legacy" :role :main
                                      :model-label "Legacy Model"}}
            :connection/by-id {"legacy" {:id "legacy" :name "Legacy Conn"
                                         :models [{:id "legacy"}]}}}
        roles (model/role-cards db)
        by-role (into {} (map (juxt :role identity)) roles)
        connections (model/connection-cards db)]
    (is (= 5 (count roles)))
    (is (re-find #"Main Model"
                 (get-in by-role [:main :selection-label])))
    (is (re-find #"Main Model"
                 (get-in by-role [:task :selection-label])))
    (is (= 1 (count connections)))
    (is (= "Main Conn" (:name (first connections))))
    (is (= 1 (:model-count (first connections))))
    (is (= "a" (:connection-id (first connections))))
    (is (not-any? #(= "Legacy Conn" (:name %)) connections))
    (is (not-any? #(= "Legacy Model" (:selection-label %)) roles))))

(deftest task-tree-keeps-cycles-visible
  (let [db {:task/by-id
            {"root" {:id "root" :goal "root" :children ["a"]}
             "a" {:id "a" :goal "a" :parent-id "root" :children ["b"]}
             "b" {:id "b" :goal "b" :parent-id "a"}
             "cycle-1" {:id "cycle-1" :goal "c1" :parent-id "cycle-2"}
             "cycle-2" {:id "cycle-2" :goal "c2" :parent-id "cycle-1"}}}
        tree (model/task-tree db)]
    (is (= #{"root" "a" "b" "cycle-1" "cycle-2"} (set (map :id tree))))
    (is (= 0 (:depth (first (filter #(= "root" (:id %)) tree)))))
    (is (= 1 (:depth (first (filter #(= "a" (:id %)) tree)))))
    (is (= 2 (:depth (first (filter #(= "b" (:id %)) tree)))))))

(deftest availability-does-not-fake-buttons
  (let [base {:connection {:command-allowed? false}
              :session-id nil
              :session nil
              :model nil
              :streaming? false
              :stopping? false
              :draft-sendable? false
              :command-pending? false
              :ask nil}]
    (is (false? (get-in (model/availability base) [:send :enabled?])))
    (is (= :connection-not-ready (get-in (model/availability base) [:send :reason])))
    (is (false? (get-in (model/availability base) [:stop :enabled?])))
    (is (false? (get-in (model/availability base) [:resync :enabled?])))
    (is (true? (get-in (model/availability (assoc base :connection {:command-allowed? true :can-resync? true}))
                       [:resync :enabled?])))
    (let [ready (assoc base
                       :connection {:command-allowed? true :can-resync? true}
                       :session-id "s-1"
                       :session {:id "s-1"}
                       :model {:id "m"}
                       :draft-sendable? true)]
      (is (true? (get-in (model/availability ready) [:send :enabled?])))
      (is (false? (get-in (model/availability (assoc ready :command-pending? true)) [:send :enabled?])))
      (is (false? (get-in (model/availability (assoc ready :streaming? true)) [:send :enabled?])))
      (is (true? (get-in (model/availability ready) [:stop :enabled?]))
          "the stop control stays available while the local stream is idle")
      (is (true? (get-in (model/availability (assoc ready :streaming? true)) [:stop :enabled?])))
      (is (false? (get-in (model/availability (assoc ready :stopping? true)) [:stop :enabled?]))))))

(deftest availability-property
  (let [property (prop/for-all [synced? gen/boolean
                                 session? gen/boolean
                                 model? gen/boolean
                                 sendable? gen/boolean
                                 pending? gen/boolean
                                 streaming? gen/boolean
                                 stopping? gen/boolean]
                    (let [availability (model/availability
                                        {:connection {:command-allowed? synced?}
                                         :session-id (when session? "s")
                                         :session (when session? {:id "s"})
                                         :model (when model? {:id "m"})
                                         :draft-sendable? sendable?
                                         :command-pending? pending?
                                         :streaming? streaming?
                                         :stopping? stopping?})]
                      (= (get-in availability [:send :enabled?])
                         (and synced? session? model? sendable?
                              (not pending?)
                              (not streaming?)
                              (not stopping?)))))]
    (is (:pass? (tc/quick-check 200 property)))))

(deftest draft-save-labels
  (is (= "草稿暂未保存"
         (get (model/draft-view {:save-state :failed :version 2 :saved-version 1}) :save-label)))
  (is (= "草稿未保存"
         (get (model/draft-view {:save-state :unsaved :version 2 :saved-version 1}) :save-label)))
  (is (nil?
       (get (model/draft-view {:save-state :saved :version 2 :saved-version 2}) :save-label))))

(deftest stale-selection-falls-back-to-visible-session
  (let [db {:ui/selected-session-id "gone"
            :session/active-id "s2"
            :session/by-id {"s1" {:session-id "s1" :title "Alive"
                                  :updated-at 2}
                            "s2" {:session-id "s2" :title "Server active"
                                  :updated-at 1}}}]
    (is (= "s1" (model/selected-session-id db)))
    (is (= "s1" (model/resolve-selected-session-id db "gone")))
    (is (= "s1" (model/resolve-selected-session-id db nil)))
    (testing "a valid requested selection wins over the first visible session"
      (is (= "s1" (model/resolve-selected-session-id db "s1"))))
    (testing "the server presentation pointer is not a stale-selection fallback"
      (is (= "s1" (model/resolve-selected-session-id db "gone"))))))

(deftest session-selection-is-local-authority
  (let [db {:ui/selected-session-id "s1"
            :session/active-id "s2"
            :session/by-id {"s1" {:session-id "s1" :title "First"
                                  :updated-at 2}
                            "s2" {:session-id "s2" :title "Second"
                                  :updated-at 1}}
            :message/by-id {"m1" {:id "m1" :session-id "s1" :content "first"}
                            "m2" {:id "m2" :session-id "s2" :content "second"}
                            "m3" {:id "m3" :content "unowned"}
                            "m4" {:id "m4" :message/session-id "s1"
                                  :content "live"}}
            :message/order ["m1" "m2" "m3" "m4"]}]
    (is (= "s1" (model/selected-session-id db)))
    (is (= ["m1" "m4"] (mapv :id (model/messages-for-session db "s1"))))
    (is (= ["m2"] (mapv :id (model/messages-for-session db "s2"))))
    (testing "without a local choice the server presentation id bootstraps"
      (is (= "s2" (model/selected-session-id
                   (dissoc db :ui/selected-session-id)))))
    (testing "a late snapshot cannot replace the local choice"
      (let [merged (sync/merge-into-db
                    db
                    {:server {:session/active-id "s2"}
                     :db {:session/active-id "s2"
                          :session/by-id (:session/by-id db)
                          :message/by-id (:message/by-id db)
                          :message/order (:message/order db)}})]
        (is (= "s1" (model/selected-session-id merged)))
        (is (= "s2" (get-in merged [:session/active-id])))
        (is (= ["m1" "m4"]
               (mapv :id (model/messages-for-session merged "s1"))))))))

(deftest connection-view-reads-string-keyed-sync
  (let [view (model/connection-view
              {:phase :synced
               :subscription 1
               :epoch 7
               :seq 3
               :server {"sync" {"command-epoch" 11}}
               :db {"sync" {"command-epoch" 11}}})]
    (is (= 11 (:command-epoch view)))))

(deftest session-filter-is-local-and-honest
  (let [sessions [{:id "s1" :title "配置改造" :model-label "gpt"}
                  {:id "s2" :title "发布计划" :model-label "claude"}]]
    (is (= ["s1" "s2"] (mapv :id (model/filter-sessions sessions ""))))
    (is (= ["s1"] (mapv :id (model/filter-sessions sessions "配置"))))
    (is (= ["s2"] (mapv :id (model/filter-sessions sessions "CLAUDE"))))
    (is (empty? (model/filter-sessions sessions "不存在")))))

(deftest session-sources-project-real-wire-fields
  (let [db {:session/by-id {"s1" {:id "s1" :message-ids ["m1" "m2"]}}
            :message/by-id {"m1" {:id "m1" :session-id "s1"
                                  :citations [{:title "官方文档"
                                               :url "https://example.test/a"}]}
                            "m2" {:id "m2" :session-id "s1"
                                  :quotes [{:text "引用片段"}]}}
            :message/order ["m1" "m2"]}]
    (is (= [{:label "官方文档" :url "https://example.test/a" :kind nil}
            {:label "引用片段" :url nil :kind nil}]
           (model/session-sources db "s1")))
    (is (empty? (model/session-sources db "missing")))))

(deftest escape-stop-only-applies-to-the-conversation-workspace
  (is (true? (model/escape-stop-allowed? {:ui/route :sessions})))
  (is (true? (model/escape-stop-allowed? {})))
  (is (false? (model/escape-stop-allowed? {:ui/route :settings})))
  (is (false? (model/escape-stop-allowed? {:ui/route :board}))))

(deftest shortcuts-list-only-implemented-bindings
  (let [ids (set (map :id model/shortcuts))]
    (is (contains? ids :settings))
    (is (contains? ids :new-session))
    (is (contains? ids :find-session))
    (is (contains? ids :global-search))
    (is (contains? ids :escape))
    (is (every? (comp not str/blank? :keys-label)
                model/shortcuts))))
