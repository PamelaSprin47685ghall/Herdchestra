(ns hc.client.materials.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.materials.model :as model]
            [hc.client.materials.view :as view]))

(defn- synced-state
  []
  (assoc (model/initial-state)
         :sync {:phase :synced :command-epoch 7}
         :transport-available? true))

(def memory-a
  {:id "mem-a"
   :v 1
   :status :candidate
   :body "偏好先给结论"
   :category :expression-preference
   :scope {:kind :global}
   :epistemic :preference
   :always? false
   :source {:session-id "s-1"
            :message-ids ["m-1"]
            :excerpt "以后先给结论"}
   :created-seq 3
   :updated-seq 4
   :versions []})

(def memory-b
  {:id "mem-b"
   :v 1
   :status :active
   :body "项目使用 Clojure"
   :category :identity-background
   :scope {:kind :project :project-id "p-1"}
   :epistemic :fact
   :always? true
   :source nil
   :created-seq 1
   :updated-seq 2
   :versions [{:body "旧正文"}]})

(def book
  {:id "wb-1"
   :v 1
   :name "世界观"
   :description "测试世界书"
   :enabled? true
   :assistant-ids #{"a-1"}
   :created-seq 1
   :updated-seq 2
   :seq 1})

(def entry
  {:id "wbe-1"
   :v 1
   :book-id "wb-1"
   :title "港口"
   :body "港口只开放白天。"
   :enabled? true
   :trigger {:kind :keyword
             :valid? true
             :keywords ["港口" "码头"]
             :match :any}
   :key? true
   :position {:kind :base-background :depth 0}
   :sticky 2
   :cooldown 1
   :delay 0
   :order 1
   :created-seq 1
   :updated-seq 2
   :seq 2})

(def card
  {:id "card-1"
   :v 1
   :name "列出假设"
   :description "回答前列出假设"
   :body "回答前列出 {{count}} 条假设。"
   :placeholders [{:key "count" :label "数量" :required? true}]
   :output-format :text
   :enabled? true
   :created-seq 1
   :updated-seq 2})

(def card-two
  {:id "card-2"
   :v 1
   :name "JSON 输出"
   :description "输出 JSON"
   :body "只输出 JSON。"
   :placeholders []
   :output-format :json
   :enabled? true
   :created-seq 3
   :updated-seq 4})

(def memory-archived
  {:id "mem-c"
   :v 2
   :status :archived
   :body "旧项目约定已归档"
   :category :long-term-requirement
   :scope {:kind :global}
   :epistemic :requirement
   :always? false
   :source nil
   :created-seq 1
   :updated-seq 5
   :versions [{:body "更早的约定"}]})

(def snapshot
  {:materials/ready? true
   :materials/revision 9
   :materials/settings {:memory-read? true :memory-suggest? false}
   :materials/session {:session-id "s-1"
                       :assistant-id "a-1"
                       :project-id "p-1"
                       :temporary? false}
   :memory/by-id {"mem-a" memory-a "mem-b" memory-b}
   :memory/order ["mem-a" "mem-b"]
   :memory/counts {:candidate 1 :active 1 :archived 0 :rejected 0
                   :deleted 0}
   :worldbook/by-id {"wb-1" book}
   :worldbook-entry/by-id {"wbe-1" entry}
   :worldbook-session {:turn 3
                       :runtime {"wbe-1" {:last-trigger-turn 2
                                          :sticky-until 4
                                          :cooldown-until 3}}}
   :instruction-card/by-id {"card-1" card "card-2" card-two}
   :instruction-session {:session-id "s-1" :card-ids ["card-1"]}
   :assistants {:a-1 {:id "a-1" :name "主助手"}
                :a-2 {:id "a-2" :name "审阅助手"}}})

(deftest snapshot-projection
  (let [state (model/apply-snapshot (synced-state) snapshot)]
    (is (:ready? state))
    (is (= 9 (:revision state)))
    (is (= 2 (count (:memories state))))
    (is (= "wb-1" (:selected-book-id state)))
    (is (= ["card-1"] (model/session-card-ids state)))
    (is (= [] (model/turn-card-ids state)))
    (is (= :turn (:instruction-mode state)))
    (is (= "项目 p-1" (model/scope-label (:scope (get-in state [:memories "mem-b"])))))
    (is (= 3 (get-in state [:worldbook-session :turn])))
    (is (= 2 (count (model/memory-items state))))
    (is (= ["mem-a" "mem-b"]
           (mapv :id (model/memory-items (assoc state :memory-filter :all)))))))

(deftest partial-and-empty-snapshots-preserve-the-ready-projection
  (let [full (model/apply-snapshot
              (synced-state)
              (assoc snapshot :materials/revision 7))]
    (testing "an empty snapshot is a no-op"
      (let [next (model/apply-snapshot full {})]
        (is (= full next))
        (is (true? (:ready? next)))
        (is (= 7 (:revision next)))
        (is (= 2 (count (:memories next))))))
    (testing "a partial snapshot without materials fields keeps the ready projection"
      (let [next (model/apply-snapshot
                  full
                  {:materials/session {:session-id "s-1"}})]
        (is (true? (:ready? next)))
        (is (= 7 (:revision next)))
        (is (= 2 (count (:memories next))))
        (is (= (get-in full [:memory/by-id])
               (get-in next [:memory/by-id])))
        (is (= (get-in full [:settings])
               (get-in next [:settings])))))
    (testing "an explicit ready false still clears readiness"
      (let [next (model/apply-snapshot full {:materials/ready? false})]
        (is (false? (:ready? next)))
        (is (= 7 (:revision next)))
        (is (= 2 (count (:memories next))))))))

(deftest stale-snapshot-is-ignored
  (let [current (model/apply-snapshot (synced-state) snapshot)
        stale (assoc snapshot
                     :materials/revision 8
                     :memory/by-id {})
        next (model/apply-snapshot current stale)]
    (is (= 9 (:revision next)))
    (is (= 2 (count (:memories next))))))

(deftest cross-session-snapshot-is-ignored
  (let [current (-> (model/apply-snapshot (synced-state) snapshot)
                    (model/begin-session "s-2"))
        next (model/apply-snapshot current snapshot)]
    (is (= "s-2" (:selected-session-id next)))
    (is (empty? (:memories next)))
    (is (pos? (:turn-selection-version next)))
    (is (not (:ready? next))
        "a dropped snapshot must not claim readiness")
    ;; A dropped snapshot is inert, but its reason has to survive: otherwise
    ;; the panel shows a not-ready badge with a null error and nothing can
    ;; tell "rejected" from "never arrived" (D-54).
    (is (= :cross-session (get-in next [:last-rejected :reason])))
    (is (= "s-2" (get-in next [:last-rejected :selected-session-id]))
        "the rejection names the session the client wanted")
    (is (= "s-1" (get-in next [:last-rejected :snapshot-session-id]))
        "and the session the snapshot described")))

(deftest a-stale-snapshot-is-recorded-as-stale
  (let [current (model/apply-snapshot (synced-state) snapshot)
        current (assoc current :revision 9)
        older (assoc snapshot :materials/revision 4)
        next (model/apply-snapshot current older)]
    (is (= 9 (:revision next)) "the older revision must not win")
    (is (= :stale (get-in next [:last-rejected :reason]))
        "an older revision is kept out, and says so")))

(deftest commands-target-selected-session
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (model/begin-session "s-2"))
        next (:state (model/reduce-action
                      state
                      {:type :materials/refresh}))
        envelope (first (:outbox next))]
    (is (= "s-2" (get-in envelope [:payload :session-id])))
    (is (= "s-2" (get-in next [:commands
                               (:command-id envelope) :payload :session-id])))))

(deftest snapshot-preserves-local-state
  (let [state (-> (synced-state)
                  (assoc :memory-query "Clojure"
                         :memory-draft (model/memory-draft {:body "draft"})
                         :last-error {:message "old"}))
        next (model/apply-snapshot state snapshot)]
    (is (= "Clojure" (:memory-query next)))
    (is (= "draft" (get-in next [:memory-draft :body])))
    (is (= {:message "old"} (:last-error next)))))

(deftest memory-filter-and-query
  (let [state (model/apply-snapshot (synced-state) snapshot)]
    (is (= ["mem-a"]
           (mapv :id (model/memory-items
                      (assoc state :memory-filter :candidate)))))
    (is (= ["mem-b"]
           (mapv :id (model/memory-items
                      (assoc state :memory-query "clojure"
                             :memory-filter :all)))))))

(deftest memory-validation
  (testing "four categories and explicit scope"
    (let [checked (model/validate-memory-draft
                   (model/memory-draft
                    {:body "  事实  "
                     :category :identity-background
                     :scope-kind :project
                     :project-id " p-1 "}))]
      (is (:ok? checked))
      (is (= "事实" (get-in checked [:payload :body])))
      (is (= {:kind :project :project-id "p-1"}
             (get-in checked [:payload :scope])))))

  (testing "the 2,000 character limit counts code points"
    (let [body (apply str (repeat 2000 "😀"))]
      (is (:ok? (model/validate-memory-draft
                 (model/memory-draft {:body body}))))
      (is (false? (:ok? (model/validate-memory-draft
                         (model/memory-draft {:body (str body "x")})))))))

  (testing "assistant scope needs an id"
    (is (false? (:ok? (model/validate-memory-draft
                       (model/memory-draft
                        {:body "x" :scope-kind :assistant}))))))

  (testing "blank body is refused"
    (is (false? (:ok? (model/validate-memory-draft
                       (model/memory-draft {:body "   "})))))))

(deftest worldbook-validation
  (testing "keyword trigger keeps phrases and any/all"
    (let [checked (model/validate-entry-draft
                   (model/entry-draft
                    {:book-id "wb-1"
                     :title "港口"
                     :body "正文"
                     :trigger-kind :keyword
                     :keywords "港口\n夜班 码头\n"
                     :match :all
                     :sticky "2"
                     :cooldown "1"
                     :delay "0"
                     :order "3"}))]
      (is (:ok? checked))
      (is (= ["港口" "夜班 码头"]
             (get-in checked [:payload :trigger :keywords])))
      (is (= :all (get-in checked [:payload :trigger :match])))
      (is (= 2 (get-in checked [:payload :sticky])))
      (is (= 3 (get-in checked [:payload :order])))))

  (testing "expression trigger is parsed as EDN and validated"
    (let [checked (model/validate-entry-draft
                   (model/entry-draft
                    {:book-id "wb-1"
                     :title "表达式"
                     :body "正文"
                     :trigger-kind :expression
                     :expression "[:all [[:contains \"港口\"] [:not [:contains \"夜晚\"]]]]"}))]
      (is (:ok? checked))
      (is (= :expression (get-in checked [:payload :trigger :kind]))))
    (is (false? (:ok? (model/validate-entry-draft
                       (model/entry-draft
                        {:book-id "wb-1"
                         :title "坏表达式"
                         :body "正文"
                         :trigger-kind :expression
                         :expression "[:unknown \"x\"]"})))))
    (is (false? (:ok? (model/validate-entry-draft
                       (model/entry-draft
                        {:book-id "wb-1"
                         :title "坏表达式"
                         :body "正文"
                         :trigger-kind :expression
                         :expression "not-edn"}))))))

  (testing "counters reject negative and non-numeric values"
    (is (false? (:ok? (model/validate-entry-draft
                       (model/entry-draft
                        {:book-id "wb-1"
                         :title "港口"
                         :body "正文"
                         :trigger-kind :constant
                         :sticky "-1"})))))))

(deftest assistant-options-current-session-first
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-session-id "s-2"
                         :session {:session-id "s-2" :assistant-id "a-2"}
                         :session/by-id {"s-1" {:assistant-id "a-1"}
                                         "s-2" {:assistant-id "a-2"}}))
        options (model/assistant-options state)]
    (is (= ["a-2" "a-1"] (mapv :id options)))
    (is (true? (:current? (first options))))
    (is (= "主助手（a-1）" (:label (second options))))
    (is (= options
           (model/assistant-options
            (assoc state :assistant-options options))))
    (testing "items-shaped assistant collections"
      (is (= ["a-9"]
             (mapv :id (model/assistant-options
                        (assoc (synced-state)
                               :assistants
                               {:items [{:id "a-9"
                                         :name "列表助手"}]}))))))))

(deftest worldbook-bind-submits-selected-assistant
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-book-id "wb-1"
                         :book-draft (model/book-draft
                                      {:mode :edit
                                       :book-id "wb-1"
                                       :assistant-id "a-2"})))
        next (:state (model/reduce-action
                      state
                      {:type :materials/book-edit-field
                       :field :assistant-id
                       :value "a-2"}))
        bound (:state (model/reduce-action next {:type :materials/book-bind}))
        command (first (filter #(= model/worldbook-bind-kind (:kind %))
                               (vals (:commands bound))))]
    (is (= "a-2" (get-in next [:book-draft :assistant-id])))
    (is (= "wb-1" (get-in command [:payload :book-id])))
    (is (= "a-2" (get-in command [:payload :assistant-id])))
    (is (= "s-1" (get-in command [:payload :session-id])))))

(deftest worldbook-bind-without-assistants-gives-guidance
  (let [state (-> (synced-state)
                  (assoc :books {"wb-1" (assoc book :assistant-ids #{})}
                         :selected-book-id "wb-1"
                         :session nil
                         :book-draft (model/book-draft
                                      {:mode :edit :book-id "wb-1"})))
        next (:state (model/reduce-action state {:type :materials/book-bind}))]
    (is (empty? (model/assistant-options state)))
    (is (= "请先创建或选择助手。"
           (get-in next [:book-draft :error])))))

(deftest instruction-card-validation
  (testing "placeholders and output format"
    (let [checked (model/validate-card-draft
                   (model/card-draft
                    {:name " 列出假设 "
                     :body "回答前列出 {{count}} 条假设。"
                     :placeholders-text "count | 数量 | required"
                     :output-format "text"}))]
      (is (:ok? checked))
      (is (= "列出假设" (get-in checked [:payload :name])))
      (is (= [{:key "count" :label "数量" :required? true}]
             (get-in checked [:payload :placeholders])))
      (is (= :text (get-in checked [:payload :output-format])))))

  (testing "duplicate placeholder keys are refused"
    (is (false? (:ok? (model/validate-card-draft
                       (model/card-draft
                        {:name "x"
                         :body "{{a}}"
                         :placeholders-text "a\na"})))))))

(deftest selection-conflict
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :instruction-mode :session)
                  (assoc-in [:instruction-session :card-ids]
                            ["card-1" "card-2"]))]
    (is (= [:text :json] (model/selection-format-conflict state)))
    (is (= ["count"] (model/required-placeholder-keys card)))))

(deftest turn-selection-attaches-to-send
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :turn-card-ids ["card-1"]
                         :instruction-values {"count" "3"}))
        attached (model/attach-turn-selection state {:text "hi"})]
    (is (= ["card-1"] (:turn-card-ids attached)))
    (is (= {"count" "3"} (:instruction-values attached)))
    (is (= "hi" (:text attached)))
    (is (= {:turn-card-ids ["card-1"]
            :instruction-values {"count" "3"}
            :turn-selection-version 0}
           (model/turn-selection state)))
    (is (nil? (model/turn-selection (assoc state :instruction-mode :session))))))

(deftest turn-selection-stays-local-until-send
  (let [state (model/apply-snapshot (synced-state) snapshot)
        next (:state (model/reduce-action
                      state
                      {:type :materials/turn-card-toggle
                       :card-id "card-1"}))]
    (is (= ["card-1"] (:turn-card-ids next)))
    (is (empty? (:commands next)))
    (is (empty? (:outbox next)))
    (is (= {:turn-card-ids ["card-1"]
            :instruction-values {}
            :turn-selection-version 1}
           (model/turn-selection next)))))

(deftest submitted-turn-selection-version-protects-newer-selection
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :turn-card-ids ["card-1"]
                         :instruction-values {"count" "3"}))
        submitted (model/turn-selection state)
        changed (:state (model/reduce-action
                         state
                         {:type :materials/turn-card-toggle
                          :card-id "card-2"}))
        value-changed (:state (model/reduce-action
                               state
                               {:type :materials/instruction-value
                                :key "count"
                                :value "4"}))
        cleared (model/clear-turn-selection state)
        newer-after-clear (:state (model/reduce-action
                                   cleared
                                   {:type :materials/turn-card-toggle
                                    :card-id "card-2"}))
        legacy (dissoc submitted :turn-selection-version)]
    (is (model/turn-selection-matches? state submitted))
    (is (not (model/turn-selection-matches? changed submitted)))
    (is (not (model/turn-selection-matches? value-changed submitted)))
    (is (not (model/turn-selection-matches? cleared submitted)))
    (is (not (model/turn-selection-matches? newer-after-clear submitted)))
    (testing "a legacy submission without a version compares content"
      (is (model/turn-selection-matches? state legacy))
      (is (not (model/turn-selection-matches? changed legacy))))
    (testing "clearing bumps the version even when already empty"
      (let [empty (model/clear-turn-selection cleared)]
        (is (> (:turn-selection-version empty)
               (:turn-selection-version cleared)))))))

(deftest switching-to-session-ignores-turn-selection
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :turn-card-ids ["card-1"]
                         :instruction-values {"count" "3"}))
        next (:state (model/reduce-action
                      state
                      {:type :materials/instruction-mode :mode :session}))]
    (is (= :session (:instruction-mode next)))
    (is (nil? (model/turn-selection next)))
    (is (empty? (:outbox next)))))

(deftest command-queue-requires-sync
  (let [state (model/initial-state)
        result (model/queue-command state :materials/snapshot {} {})]
    (is (false? (:ok? result)))
    (is (empty? (get-in result [:state :outbox])))))

(deftest memory-command-queue
  (let [state (-> (synced-state)
                  (assoc :memory-draft
                         (model/memory-draft
                          {:body "先给结论"
                           :category :expression-preference})))
        next (:state (model/reduce-action state {:type :materials/memory-save}))
        command (first (vals (:commands next)))
        envelope (first (:outbox next))]
    (is (= model/memory-remember-kind (:kind command)))
    (is (= :queued (:status command)))
    (is (= model/memory-remember-kind (get-in envelope [:payload :kind])))
    (is (= "先给结论" (get-in envelope [:payload :body])))
    (is (= 7 (:command-epoch envelope)))
    (let [duplicate (:state (model/reduce-action next
                                                 {:type :materials/memory-save}))]
      (is (= 1 (count (filter #(= model/memory-remember-kind (:kind %))
                              (vals (:commands duplicate))))))
      (is (= {:message "同一目标已有待确认命令；请等待回执。"}
             (:last-error duplicate))))))

(deftest consecutive-entry-creates-queue
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :entry-draft
                         (model/entry-draft
                          {:book-id "wb-1"
                           :title "第一条"
                           :body "正文一"
                           :trigger-kind :constant})))
        first-state (:state (model/reduce-action state
                                                 {:type :materials/entry-save}))
        first-command (first (filter #(= model/entry-create-kind (:kind %))
                                     (vals (:commands first-state))))
        second-state (:state (model/reduce-action first-state
                                                  {:type :materials/entry-new}))
        second-state (assoc-in second-state [:entry-draft :title] "第二条")
        second-state (assoc-in second-state [:entry-draft :body] "正文二")
        second-state (assoc-in second-state [:entry-draft :trigger-kind]
                               :constant)
        next (:state (model/reduce-action second-state
                                          {:type :materials/entry-save}))
        creates (filter #(= model/entry-create-kind (:kind %))
                        (vals (:commands next)))]
    (is (= 2 (count creates)))
    (is (= 2 (count (set (map :command-id creates)))))
    (is (= 2 (count (set (map :key creates)))))
    (is (nil? (:last-error next)))
    (testing "an accepted receipt clears its own pending command"
      (let [accepted (model/apply-receipt
                      next
                      {:ok? true
                       :command-id (:command-id first-command)
                       :receipt {:status :accepted
                                 :command-id (:command-id first-command)
                                 :result {:entry {:id "wbe-2"}}}})
            third-state (:state (model/reduce-action
                                 accepted
                                 {:type :materials/entry-new}))
            third-state (assoc-in third-state [:entry-draft :title] "第三条")
            third-state (assoc-in third-state [:entry-draft :body] "正文三")
            third-state (assoc-in third-state [:entry-draft :trigger-kind]
                                  :constant)
            third (:state (model/reduce-action third-state
                                               {:type :materials/entry-save}))]
        (is (= :accepted
               (get-in accepted [:commands (:command-id first-command)
                                 :status])))
        (is (= 3 (count (filter #(= model/entry-create-kind (:kind %))
                                (vals (:commands third))))))))))

(deftest accepted-entry-receipt-allows-the-next-create
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :entry-draft
                         (model/entry-draft
                          {:book-id "wb-1"
                           :title "第一条"
                           :body "正文一"
                           :trigger-kind :constant})))
        queued (:state (model/reduce-action state {:type :materials/entry-save}))
        command (first (filter #(= model/entry-create-kind (:kind %))
                               (vals (:commands queued))))
        accepted (model/apply-receipt
                  queued
                  {:ok? true
                   :command-id (:command-id command)
                   :receipt {:status :accepted
                             :command-id (:command-id command)
                             :result {:entry {:id "wbe-2"}}}})
        reset-draft (:entry-draft accepted)
        next (-> accepted
                 (assoc-in [:entry-draft :title] "第二条")
                 (assoc-in [:entry-draft :body] "正文二")
                 (assoc-in [:entry-draft :trigger-kind] :constant))
        next (:state (model/reduce-action next {:type :materials/entry-save}))
        creates (filter #(= model/entry-create-kind (:kind %))
                        (vals (:commands next)))]
    (is (not= (:nonce (:entry-draft queued)) (:nonce reset-draft)))
    (is (= 2 (count creates)))
    (is (= 2 (count (set (map :key creates)))))
    (is (= :accepted (get-in next [:commands (:command-id command) :status])))))

(deftest refused-entry-receipt-allows-retry
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :entry-draft
                         (model/entry-draft
                          {:book-id "wb-1"
                           :title "第一条"
                           :body "正文一"
                           :trigger-kind :constant})))
        queued (:state (model/reduce-action state {:type :materials/entry-save}))
        command (first (filter #(= model/entry-create-kind (:kind %))
                               (vals (:commands queued))))
        refused (model/apply-receipt
                 queued
                 {:ok? false
                  :command-id (:command-id command)
                  :error {:message "refused"}
                  :receipt {:status :refused}})
        retry-state (-> refused
                        (assoc-in [:entry-draft :title] "第二条")
                        (assoc-in [:entry-draft :body] "正文二")
                        (assoc-in [:entry-draft :trigger-kind] :constant))
        next (:state (model/reduce-action retry-state
                                          {:type :materials/entry-save}))
        creates (filter #(= model/entry-create-kind (:kind %))
                        (vals (:commands next)))]
    (is (= :refused
           (get-in refused [:commands (:command-id command) :status])))
    (is (= 2 (count creates)))
    (is (= 2 (count (set (map :command-id creates)))))))

(deftest accepted-write-queues-one-snapshot
  (let [state (-> (synced-state)
                  (assoc :memory-draft
                         (model/memory-draft
                          {:body "先给结论"
                           :category :expression-preference})))
        queued (:state (model/reduce-action state {:type :materials/memory-save}))
        command (first (vals (:commands queued)))
        receipt {:ok? true
                 :command-id (:command-id command)
                 :receipt {:status :accepted
                           :command-id (:command-id command)
                           :result {:memory {:id "mem-x"}}}}
        next (model/apply-receipt queued receipt)
        snapshot-commands (filter #(= :materials/snapshot (:kind %))
                                  (vals (:commands next)))]
    (is (= 1 (count snapshot-commands)))
    (is (= [:materials/snapshot]
           (:key (first snapshot-commands))))
    (is (= :accepted (get-in next [:commands (:command-id command) :status])))
    (is (= 1 (count (:outbox next))))))

(deftest snapshot-receipt-applies-result
  (let [state (synced-state)
        result (model/queue-command state :materials/snapshot {} {})
        queued (:state result)
        command (first (vals (:commands queued)))
        receipt {:ok? true
                 :command-id (:command-id command)
                 :receipt {:status :accepted
                           :result snapshot}}
        next (model/apply-receipt queued receipt)]
    (is (:ready? next))
    (is (= 9 (:revision next)))
    (is (= 2 (count (:memories next))))
    (is (empty? (:outbox next)))))

(deftest refused-write-queues-reconcile-snapshot
  (let [state (-> (synced-state)
                  (assoc :memory-draft
                         (model/memory-draft {:body "x"})))
        queued (:state (model/reduce-action state {:type :materials/memory-save}))
        command (first (vals (:commands queued)))
        receipt {:ok? false
                 :command-id (:command-id command)
                 :error {:code :MEMORY_BODY_INVALID
                         :message "body refused"}
                 :receipt {:status :refused}}
        next (model/apply-receipt queued receipt)]
    (is (= :refused (get-in next [:commands (:command-id command) :status])))
    (is (= "body refused" (get-in next [:last-error :message])))
    (is (some #(= :materials/snapshot (:kind %)) (vals (:commands next))))))

(deftest command-send-failure-does-not-claim-success
  (let [state (-> (synced-state)
                  (assoc :memory-draft
                         (model/memory-draft {:body "x"})))
        queued (:state (model/reduce-action state {:type :materials/memory-save}))
        command (first (vals (:commands queued)))
        next (model/mark-send-failed queued (:command-id command) "channel closed")]
    (is (= :failed (get-in next [:commands (:command-id command) :status])))
    (is (empty? (:outbox next)))))

(deftest view-exposes-real-sections
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-memory-id "mem-a")
                  (assoc :turn-card-ids ["card-1" "card-2"]))
        memory-tree (view/panel-tree state :memory)
        worldbook-tree (view/panel-tree state :worldbook)
        cards-tree (view/panel-tree state :instructions)
        rendered (pr-str [memory-tree worldbook-tree cards-tree])]
    (is (re-find #"读取已保存记忆" rendered))
    (is (re-find #"自动建议新记忆" rendered))
    (is (re-find #"来源对话片段" rendered))
    (is (re-find #"拒绝原因（可选）" rendered))
    (is (re-find #"港口只开放白天" rendered))
    (is (re-find #"持续 2" rendered))
    (is (re-find #"指令卡应用方式" rendered))
    (is (re-find #"仅本轮：随下一条用户消息一次性发送" rendered))
    (is (re-find #"格式冲突" rendered))))

(deftest instruction-mode-shows-both-selection-counts
  (let [base (model/apply-snapshot (synced-state) snapshot)
        turn-state (assoc base
                          :instruction-mode :turn
                          :turn-card-ids ["card-1" "card-2"])
        session-state (-> base
                          (assoc :instruction-mode :session)
                          (assoc-in [:instruction-session :card-ids]
                                    ["card-1" "card-2"]))
        turn-render (pr-str (view/panel-tree turn-state :instructions))
        session-render (pr-str (view/panel-tree session-state :instructions))]
    (testing "turn mode still shows the persistent count"
      (is (re-find #"仅本轮（2）" turn-render))
      (is (re-find #"本会话持续（1）" turn-render))
      (is (re-find #"另一集合：本会话持续 1 条" turn-render)))
    (testing "session mode still shows the per-turn count"
      (is (re-find #"仅本轮（0）" session-render))
      (is (re-find #"本会话持续（2）" session-render))
      (is (re-find #"另一集合：仅本轮 0 条" session-render)))))

(deftest candidate-card-shows-review-fields-and-scope-change
  (let [candidate (assoc memory-a
                         :assumption? true
                         :truncated? true
                         :source {:session-id "s-1"
                                  :message-ids ["m-1"]
                                  :excerpt "以后先给结论"})
        state (-> (model/apply-snapshot (synced-state)
                                        (assoc snapshot :memory/by-id
                                               {"mem-a" candidate}))
                  (assoc :selected-memory-id "mem-a")
                  (assoc :memory-draft
                         (model/memory-draft
                          {:mode :edit
                           :memory-id "mem-a"
                           :body (:body candidate)
                           :category (:category candidate)
                           :epistemic (:epistemic candidate)
                           :scope-kind :global})))
        rendered (pr-str (view/panel-tree state :memory))]
    (is (re-find #"偏好先给结论" rendered)
        "the candidate body is visible")
    (is (re-find #"表达偏好" rendered))
    (is (re-find #"全局" rendered))
    (is (re-find #"来源片段：以后先给结论" rendered))
    (is (re-find #"假设，待确认" rendered))
    (is (re-find #"已截断至 2000 字" rendered))
    (is (re-find #"改范围：在上方“范围”选择新的助手或项目" rendered))
    (is (re-find #"批准候选（按当前范围）" rendered))
    (is (re-find #"拒绝" rendered))))

(defn- tree-nodes
  [node]
  (cond
    (map? node)
    (cons node (mapcat tree-nodes (:children node)))
    (sequential? node)
    (mapcat tree-nodes node)
    :else []))

(defn- nodes-with-class
  [nodes class-name]
  (filter #(some #{class-name}
                 (str/split (str (get-in % [:attrs :class])) #"\s+"))
          nodes))

(deftest worldbook-bind-renders-assistant-select
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-book-id "wb-1"
                         :book-draft (model/book-draft
                                      {:mode :edit
                                       :book-id "wb-1"
                                       :assistant-id "a-2"})))
        tree (view/panel-tree state :worldbook)
        nodes (tree-nodes tree)
        select-node (first (filter #(and (= :select (:tag %))
                                         (= "绑定助手"
                                            (get-in % [:attrs :aria-label])))
                                   nodes))
        bind-node (first (filter #(and (= :button (:tag %))
                                       (= ["绑定"] (:children %)))
                                 nodes))
        spec (get-in select-node [:attrs :events :on-change])]
    (is select-node)
    (is (= "a-2" (get-in select-node [:attrs :value])))
    (is (some #(and (= :option (:tag %))
                    (= "a-2" (get-in % [:attrs :value])))
              (tree-nodes select-node)))
    (is (= {:type :materials/book-edit-field :field :assistant-id}
           (:action spec)))
    (is (= :target-value (:value spec)))
    (is (= {:type :materials/book-bind :assistant-id "a-2"}
           (get-in bind-node [:attrs :events :on-click :action])))
    (is (re-find #"主助手" (pr-str select-node)))
    (is (not (re-find #"助手 ID" (pr-str tree))))))

(deftest worldbook-runtime-sections-share-one-container
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :book-draft (model/book-draft
                                      {:mode :edit :book-id "wb-1"})))
        nodes (tree-nodes (view/panel-tree state :worldbook))
        runtimes (nodes-with-class nodes "hc-mat-runtime")
        subs (nodes-with-class nodes "hc-mat-runtime-sub")]
    (is (= 1 (count runtimes))
        "the runtime block keeps one bordered container")
    (is (= 1 (count subs))
        "the omitted section uses the inner divider class")
    (is (not (some #(seq (nodes-with-class (rest (tree-nodes %))
                                           "hc-mat-runtime"))
                   runtimes))
        "hc-mat-runtime never nests inside itself")))

(deftest worldbook-bind-renders-guidance-without-assistants
  (let [state (-> (synced-state)
                  (assoc :books {"wb-1" (assoc book :assistant-ids #{})}
                         :selected-book-id "wb-1"
                         :session nil
                         :book-draft (model/book-draft
                                      {:mode :edit :book-id "wb-1"})))
        tree (view/panel-tree state :worldbook)
        rendered (pr-str tree)
        nodes (tree-nodes tree)
        select-node (first (filter #(and (= :select (:tag %))
                                         (= "绑定助手"
                                            (get-in % [:attrs :aria-label])))
                                   nodes))]
    (is (empty? (model/assistant-options state)))
    (is (re-find #"先创建/选择助手" rendered))
    (is (true? (get-in select-node [:attrs :disabled])))
    (is (not (re-find #"助手 ID" rendered)))))

(deftest instruction-card-section-renders-when-non-empty
  (let [state (model/apply-snapshot (synced-state) snapshot)
        rendered (pr-str (view/panel-tree state :instructions))]
    (is (re-find #"列出假设" rendered))
    (is (re-find #"JSON 输出" rendered))
    (is (re-find #"指令卡" rendered))))

(deftest view-event-specs-match-renderer
  (let [state (model/apply-snapshot (synced-state) snapshot)
        nodes (tree-nodes (view/panel-tree state :memory))
        checkbox (first (filter (fn [node]
                                  (and (= :input (:tag node))
                                       (= "checkbox"
                                          (get-in node [:attrs :type]))))
                                nodes))
        textarea (first (filter #(= :textarea (:tag %)) nodes))
        spec (get-in checkbox [:attrs :events :on-change])]
    (is (= :target-checked (:value spec)))
    (is (map? (:action spec)))
    (is (contains? (:attrs textarea) :default-value))
    (is (not (contains? (:attrs textarea) :value)))))

(deftest view-shows-command-refusal
  (let [state (assoc (model/apply-snapshot (synced-state) snapshot)
                     :last-error {:message "同一目标已有待确认命令。"})]
    (is (re-find #"同一目标已有待确认命令"
                 (pr-str (view/panel-tree state :memory))))))

(defn- temporary-state
  []
  (model/apply-snapshot
   (synced-state)
   (assoc snapshot :materials/session
          {:session-id "s-temp"
           :temporary? true
           :assistant-id "a-1"
           :project-id "p-1"})))

(deftest temporary-read-is-one-shot-and-scoped
  (let [state (temporary-state)
        enabled (:state (model/reduce-action
                         state
                         {:type :materials/temporary-read :value true}))
        attached (model/attach-turn-selection enabled {:text "hi"})]
    (is (true? (model/temporary-session? state)))
    (is (= "s-temp" (:session-id (model/temporary-read-scope state))))
    (is (true? (:temporary-read? enabled)))
    (is (true? (:temporary-read? attached)))
    (is (false? (:temporary-read? (model/clear-turn-selection enabled))))
    (is (false? (:temporary-read? (model/begin-session enabled "s-2"))))
    (is (false? (:temporary-read?
                 (:state (model/reduce-action
                          (model/apply-snapshot (synced-state) snapshot)
                          {:type :materials/temporary-read :value true})))))))

(deftest begin-session-resets-instruction-mode
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :instruction-mode :session
                         :temporary-read? true))
        next (model/begin-session state "s-2")]
    (is (= :turn (:instruction-mode next)))
    (is (false? (:temporary-read? next)))))

(deftest worldbook-omitted-rows-are-bounded-and-honest
  (let [state (assoc (model/apply-snapshot (synced-state) snapshot)
                     :worldbook-session
                     {:turn 3
                      :last-turn
                      {:result
                       {:omitted [{:entry-id "wbe-1"
                                   :reason :budget
                                   :chars 42}
                                  {:entry-id "missing"
                                   :reason :cooldown}]}}})
        view (model/worldbook-omitted-rows state)]
    (is (true? (:reported? view)))
    (is (= 2 (:count view)))
    (is (= "港口" (:title (first (:rows view)))))
    (is (= "上下文预算不足" (:reason-label (first (:rows view)))))
    (is (= 42 (:chars (first (:rows view)))))
    (is (= "冷却中" (:reason-label (second (:rows view)))))
    (is (false? (:reported? (model/worldbook-omitted-rows (synced-state)))))
    (is (true? (:reported?
                (model/worldbook-omitted-rows
                 (assoc (synced-state)
                        :worldbook-session
                        {:turn 1 :last-turn {:result {:omitted []}}})))))))

(deftest reject-reason-is-carried
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-memory-id "mem-a")
                  (assoc-in [:memory-draft :reject-reason] "不是事实"))
        next (:state (model/reduce-action state {:type :materials/memory-reject}))
        command (first (filter #(= model/memory-reject-kind (:kind %))
                               (vals (:commands next))))]
    (is (= "不是事实" (get-in command [:payload :reason])))))

(deftest invalid-position-depth-is-refused
  (let [draft (model/entry-draft
               {:book-id "wb-1"
                :title "港口"
                :body "正文"
                :trigger-kind :constant
                :position-depth "abc"})]
    (is (false? (:ok? (model/validate-entry-draft draft))))
    (is (= 2 (get-in (model/validate-entry-draft
                      (assoc draft :position-depth "2"))
                     [:payload :position :depth])))))

(deftest format-conflict-ignores-disabled-cards
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :instruction-mode :session)
                  (assoc-in [:instruction-session :card-ids]
                            ["card-1" "card-2"])
                  (assoc-in [:cards "card-2" :enabled?] false))]
    (is (nil? (model/selection-format-conflict state)))
    (is (= ["card-1"] (mapv :id (model/effective-cards state))))))

(deftest view-shows-temporary-read-and-omitted
  (let [state (-> (temporary-state)
                  (assoc :temporary-read? true
                         :book-draft (model/book-draft
                                      {:mode :edit :book-id "wb-1"}))
                  (assoc :worldbook-session
                         {:turn 3
                          :last-turn
                          {:result
                           {:omitted [{:entry-id "wbe-1"
                                       :reason :budget
                                       :chars 42}]}}}))
        rendered (pr-str [(view/panel-tree state :memory)
                          (view/panel-tree state :worldbook)])]
    (is (re-find #"仅本次读取已保存记忆" rendered))
    (is (re-find #"命中但未提供" rendered))
    (is (re-find #"上下文预算不足" rendered))
    (is (re-find #"42 字" rendered))
    (is (re-find #"服务器未报告本轮命中但未提供列表"
                 (pr-str (view/panel-tree
                          (assoc (synced-state)
                                 :book-draft
                                 (model/book-draft
                                  {:mode :edit :book-id "wb-1"}))
                          :worldbook))))))

(deftest send-preflight-detects-turn-conflict-and-missing-placeholder
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :instruction-mode :turn
                         :turn-card-ids ["card-1" "card-2"]))]
    (is (= [:text :json] (model/selection-format-conflict state)))
    (is (= [{:card-id "card-1" :card-name "列出假设" :key "count"}]
           (model/missing-required-placeholders state)))))

(deftest entry-move-receipt-remembers-undo
  (let [state (synced-state)
        command {:command-id "move-1"
                 :command-epoch 7
                 :kind model/entry-move-kind
                 :key [:materials/worldbook-entry "wbe-1"]
                 :payload {:entry-ids ["wbe-1"] :book-id "wb-2"}
                 :status :submitting}
        state (assoc-in state [:commands "move-1"] command)
        receipt {:command-id "move-1"
                 :status :accepted
                 :receipt {:status :accepted
                           :command-id "move-1"
                           :result {:moved ["wbe-1"]
                                    :undo {:entry-ids ["wbe-1"]
                                           :book-ids {"wbe-1" "wb-1"}}}}}
        next (model/apply-receipt state receipt)]
    (is (= {:entry-ids ["wbe-1"] :book-ids {"wbe-1" "wb-1"}}
           (:last-move next)))
    (let [undo (:state (model/reduce-action
                        next {:type :materials/entry-undo-move}))
          payload (some #(when (= model/entry-move-kind
                                     (get-in % [:payload :kind]))
                           (:payload %))
                        (:outbox undo))]
      (is (= {:entry-ids ["wbe-1"] :book-id "wb-1" :undo? true}
             (select-keys payload [:entry-ids :book-id :undo?])))
      (is (= model/entry-move-kind (:kind payload))))))

(deftest gateway-envelope-move-receipt-sets-and-clears-last-move
  (testing "D-28: the gateway wraps the domain receipt as
  `{:ok? :command-id :receipt {...}}`; `:result` lives only inside the
  inner receipt, so an unwrap that keeps the envelope drops `:undo` and
  the undo button never renders."
    (let [state (synced-state)
          queued (:state
                  (model/queue-command
                   state model/entry-move-kind
                   {:entry-ids ["wbe-1"] :book-id "wb-2"}
                   {:key [:materials/worldbook-entry "wbe-1"]
                    :command-id "move-gw"}))
          command (get-in queued [:commands "move-gw"])
          ;; The exact shape `gateway/deliver-command-result!` sends:
          ;; `public-command-result` keeps `:command-id` on the envelope
          ;; and nests the Owner receipt under `:receipt`.
          receipt {:ok? true
                   :command-id "move-gw"
                   :command-epoch 7
                   :receipt {:status :accepted
                             :command-id "move-gw"
                             :command-epoch 7
                             :result {:moved ["wbe-1"]
                                      :undo {:entry-ids ["wbe-1"]
                                             :book-ids {"wbe-1" "wb-1"}}}}}
          accepted (model/apply-receipt queued receipt)]
      (is (= :accepted
             (get-in accepted [:commands "move-gw" :status])))
      (is (= {:entry-ids ["wbe-1"] :book-ids {"wbe-1" "wb-1"}}
             (:last-move accepted))
          "the envelope's inner :result must reach :last-move")
      ;; The undo move receipt uses the same envelope shape and must clear
      ;; `:last-move` so a second undo cannot reuse stale book ids.
      (let [undo-state (:state (model/reduce-action
                                accepted {:type :materials/entry-undo-move}))
            undo-command (some (fn [[_ c]]
                                 (when (and (= model/entry-move-kind (:kind c))
                                            (:undo? (:payload c)))
                                   c))
                               (:commands undo-state))
            undo-receipt {:ok? true
                          :command-id (:command-id undo-command)
                          :command-epoch 7
                          :receipt {:status :accepted
                                    :command-id (:command-id undo-command)
                                    :command-epoch 7
                                    :result {:moved ["wbe-1"]
                                             :undo {:entry-ids ["wbe-1"]
                                                    :book-ids {"wbe-1" "wb-2"}}}}}
            undone (model/apply-receipt undo-state undo-receipt)]
        (is (some? undo-command)
            "an accepted move receipt queues the undo move command")
        (is (nil? (:last-move undone))
            "the undo receipt clears :last-move")))))

(deftest worldbook-creates-key-on-draft-nonce
  (testing "a fresh book draft may submit while an older create is pending"
    (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                    (assoc :book-draft
                           (model/book-draft {:name "第一本"
                                              :description "第一本说明"})))
          first-state (:state (model/reduce-action state {:type :materials/book-save}))
          first-command (first (filter #(= model/worldbook-create-kind (:kind %))
                                       (vals (:commands first-state))))
          ;; A double click on the same form shares the nonce and stays
          ;; blocked while the first create is pending.
          duplicate (:state (model/reduce-action first-state {:type :materials/book-save}))
          fresh (:state (model/reduce-action
                         (assoc first-state :book-draft
                                (model/book-draft {:name "第二本"
                                                   :description "第二本说明"}))
                         {:type :materials/book-save}))
          creates (filter #(= model/worldbook-create-kind (:kind %))
                          (vals (:commands fresh)))]
      (is (= 1 (count (filter #(= model/worldbook-create-kind (:kind %))
                              (vals (:commands duplicate))))))
      (is (= "同一目标已有待确认命令；请等待回执。"
             (:message (:last-error duplicate))))
      (is (= 2 (count creates)))
      (is (= 2 (count (set (map :key creates)))))
      (is (= (:nonce (:book-draft first-state))
             (get-in first-command [:key 2])))
      (let [accepted (model/apply-receipt
                      fresh
                      {:ok? true
                       :command-id (:command-id first-command)
                       :receipt {:status :accepted
                                 :command-id (:command-id first-command)
                                 :result {:book {:id "wb-2"}}}})]
        (is (= :accepted
               (get-in accepted [:commands (:command-id first-command)
                                 :status])))
        ;; The accepted receipt no longer blocks the next create.
        (let [third (:state (model/reduce-action
                             (assoc accepted :book-draft
                                    (model/book-draft {:name "第三本"}))
                             {:type :materials/book-save}))]
          (is (= 3 (count (filter #(= model/worldbook-create-kind (:kind %))
                                  (vals (:commands third)))))))))))

(deftest memory-and-card-creates-key-on-draft-nonce
  (testing "memory creates queue across fresh drafts"
    (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                    (assoc :memory-draft
                           (model/memory-draft {:body "第一条记忆"})))
          first-state (:state (model/reduce-action state {:type :materials/memory-save}))
          first-command (first (filter #(= model/memory-remember-kind (:kind %))
                                       (vals (:commands first-state))))
          duplicate (:state (model/reduce-action first-state {:type :materials/memory-save}))
          fresh (:state (model/reduce-action
                         (assoc first-state :memory-draft
                                (model/memory-draft {:body "第二条记忆"}))
                         {:type :materials/memory-save}))
          creates (filter #(= model/memory-remember-kind (:kind %))
                          (vals (:commands fresh)))]
      (is (= 1 (count (filter #(= model/memory-remember-kind (:kind %))
                              (vals (:commands duplicate))))))
      (is (= "同一目标已有待确认命令；请等待回执。"
             (:message (:last-error duplicate))))
      (is (= 2 (count creates)))
      (is (= 2 (count (set (map :key creates)))))
      (is (= (:nonce (:memory-draft first-state))
             (get-in first-command [:key 2])))))
  (testing "instruction card creates queue across fresh drafts"
    (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                    (assoc :card-draft
                           (model/card-draft {:name "卡一" :body "正文一"})))
          first-state (:state (model/reduce-action state {:type :materials/card-save}))
          first-command (first (filter #(= model/instruction-create-kind (:kind %))
                                       (vals (:commands first-state))))
          duplicate (:state (model/reduce-action first-state {:type :materials/card-save}))
          fresh (:state (model/reduce-action
                         (assoc first-state :card-draft
                                (model/card-draft {:name "卡二" :body "正文二"}))
                         {:type :materials/card-save}))
          creates (filter #(= model/instruction-create-kind (:kind %))
                          (vals (:commands fresh)))]
      (is (= 1 (count (filter #(= model/instruction-create-kind (:kind %))
                              (vals (:commands duplicate))))))
      (is (= "同一目标已有待确认命令；请等待回执。"
             (:message (:last-error duplicate))))
      (is (= 2 (count creates)))
      (is (= 2 (count (set (map :key creates)))))
      (is (= (:nonce (:card-draft first-state))
             (get-in first-command [:key 2]))))))

(deftest snapshot-without-card-table-preserves-instruction-selection
  (testing "a snapshot that omits :instruction-card/by-id keeps turn cards"
    (let [base (-> (model/apply-snapshot (synced-state) snapshot)
                   (assoc :turn-card-ids ["card-1"]
                          :instruction-values {"count" "3"}
                          :instruction-session {:session-id "s-1"
                                                :card-ids ["card-1"]})
                   (assoc :instruction-turn {:turn 4 :card-ids ["card-1"]}))
          partial (dissoc snapshot :instruction-card/by-id
                          :instruction-session :instruction-turn)
          next (model/apply-snapshot base partial)]
      (is (= ["card-1"] (:turn-card-ids next)))
      (is (= {"count" "3"} (:instruction-values next)))
      (is (= ["card-1"] (get-in next [:instruction-session :card-ids])))
      (is (= {:turn 4 :card-ids ["card-1"]} (:instruction-turn next)))
      (is (= ["card-1"] (:turn-card-ids
                         (model/attach-turn-selection next {:text "hi"}))))))
  (testing "a snapshot that carries the card table still filters"
    (let [base (-> (model/apply-snapshot (synced-state) snapshot)
                   (assoc :turn-card-ids ["card-1" "gone"]
                          :instruction-values {"count" "3" "stale" "x"}))
          next (model/apply-snapshot base snapshot)]
      (is (= ["card-1"] (:turn-card-ids next)))
      (is (= {"count" "3"} (:instruction-values next))))))

(deftest accepted-entry-move-resets-the-entry-draft
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-book-id "wb-1"
                         :entry-draft
                         (model/entry-draft
                          {:mode :edit :entry-id "wbe-1" :book-id "wb-1"
                           :title "港口" :body "正文" :trigger-kind :constant
                           :move-book-id "wb-2"})))
        queued (:state (model/reduce-action state {:type :materials/entry-move}))
        command (first (filter #(= model/entry-move-kind (:kind %))
                               (vals (:commands queued))))
        draft-before (:entry-draft queued)
        accepted (model/apply-receipt
                  queued
                  {:ok? true
                   :command-id (:command-id command)
                   :receipt {:status :accepted
                             :command-id (:command-id command)
                             :result {:moved ["wbe-1"]
                                      :undo {:entry-ids ["wbe-1"]
                                             :book-ids {"wbe-1" "wb-1"}}}}})]
    (is (= draft-before (:entry-draft queued)))
    (is (not= draft-before (:entry-draft accepted)))
    (is (= {:entry-ids ["wbe-1"] :book-ids {"wbe-1" "wb-1"}}
           (:last-move accepted)))
    (is (= "wb-1" (get-in accepted [:entry-draft :book-id])))))

(deftest accepted-entry-delete-resets-the-entry-draft
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-book-id "wb-1"
                         :entry-draft
                         (model/entry-draft
                          {:mode :edit :entry-id "wbe-1" :book-id "wb-1"
                           :title "港口" :body "正文" :trigger-kind :constant})))
        queued (:state (model/reduce-action state {:type :materials/entry-delete}))
        command (first (filter #(= model/entry-delete-kind (:kind %))
                               (vals (:commands queued))))
        draft-before (:entry-draft queued)
        accepted (model/apply-receipt
                  queued
                  {:ok? true
                   :command-id (:command-id command)
                   :receipt {:status :accepted
                             :command-id (:command-id command)
                             :result {:removed-entry "wbe-1"}}})]
    (is (= draft-before (:entry-draft queued)))
    (is (not= draft-before (:entry-draft accepted)))
    (is (nil? (:entry-id (:entry-draft accepted))))))

(deftest orphan-receipt-drains-once-command-lands
  (let [state (synced-state)
        receipt {:command-id "move-1"
                 :status :accepted
                 :receipt {:status :accepted
                           :command-id "move-1"
                           :result {:moved ["wbe-1"]
                                    :undo {:entry-ids ["wbe-1"]
                                           :book-ids {"wbe-1" "wb-1"}}}}}
        orphaned (model/apply-receipt state receipt)
        unknown (model/apply-receipt orphaned
                                   {:command-id "never-seen"
                                    :receipt {:status :accepted
                                              :command-id "never-seen"}})]
    (is (= ["move-1" "never-seen"]
           (mapv :command-id
                 (:orphan-receipts unknown))))
    (let [command {:command-id "move-1"
                   :command-epoch 7
                   :kind model/entry-move-kind
                   :key [:materials/worldbook-entry "wbe-1"]
                   :payload {:entry-ids ["wbe-1"] :book-id "wb-2"}
                   :status :submitting}
          landed (assoc-in unknown [:commands "move-1"] command)
          drained (model/drain-orphan-receipts landed)]
      (is (= {:entry-ids ["wbe-1"] :book-ids {"wbe-1" "wb-1"}}
             (:last-move drained)))
      (is (= ["never-seen"]
             (mapv :command-id
                   (:orphan-receipts drained))))
      (is (= :accepted (get-in drained [:commands "move-1" :status])))
      (testing "an undo move receipt clears last-move through the drain"
        (let [undo-receipt {:command-id "undo-1"
                            :receipt {:status :accepted
                                      :command-id "undo-1"
                                      :result {:moved ["wbe-1"]
                                               :undo {:entry-ids ["wbe-1"]
                                                      :book-ids {"wbe-1" "wb-2"}}}}}
              parked (model/apply-receipt drained undo-receipt)
              undo-command {:command-id "undo-1"
                            :command-epoch 7
                            :kind model/entry-move-kind
                            :key [:materials/entry-undo "wb-1"]
                            :payload {:entry-ids ["wbe-1"] :book-id "wb-1"
                                      :undo? true}
                            :status :submitting}
              landed2 (assoc-in parked [:commands "undo-1"] undo-command)
              drained2 (model/drain-orphan-receipts landed2)]
          (is (nil? (:last-move drained2))))))))

(deftest begin-session-preserves-turn-card-staging
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :turn-card-ids ["card-1"]
                         :instruction-values {"count" "3"}))
        switched (model/begin-session state "s-2")
        ;; The next session's snapshot still filters staged cards against
        ;; its own card table; cards the new session lacks drop out.
        next-snapshot (assoc snapshot
                             :materials/session {:session-id "s-2"}
                             :instruction-card/by-id {"card-1" card
                                                      "card-2" card-two}
                             :instruction-session {:session-id "s-2"
                                                   :card-ids ["card-2"]})
        applied (model/apply-snapshot switched next-snapshot)]
    (is (= ["card-1"] (:turn-card-ids switched)))
    (is (= {"count" "3"} (:instruction-values switched)))
    (is (= ["card-1"] (:turn-card-ids applied)))
    (is (= ["card-2"] (get-in applied [:instruction-session :card-ids])))))

(deftest book-select-keeps-pending-move-entry-draft
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :entry-draft
                         (model/entry-draft
                          {:mode :edit :entry-id "wbe-1" :book-id "wb-1"
                           :title "港口" :body "正文"
                           :move-book-id "wb-1"})))
        ;; wb-1 is the only book in the fixture; add a second book so the
        ;; switch target exists.
        state (assoc-in state [:books "wb-2"]
                        (assoc book :id "wb-2" :name "第二本"))
        moved (:state (model/reduce-action state
                                          {:type :materials/book-select
                                           :book-id "wb-2"}))]
    (is (= "wb-2" (:selected-book-id moved)))
    (is (= "wbe-1" (get-in moved [:entry-draft :entry-id])))
    (is (= "wb-1" (get-in moved [:entry-draft :move-book-id]))))
  (testing "a plain create draft still resets on book switch"
    (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                    (assoc :entry-draft
                           (model/entry-draft {:book-id "wb-1"
                                               :title "草稿"})))
          next (:state (model/reduce-action state
                                            {:type :materials/book-select
                                             :book-id "wb-1"}))]
      (is (= "wb-1" (get-in next [:entry-draft :book-id])))
      (is (nil? (get-in next [:entry-draft :entry-id]))))))

(deftest merge-local-state-never-resurrects-pending-over-terminal
  (testing "a stale write cannot revert an applied receipt to pending"
    (let [current {:revision 8
                   :commands {"cmd-1" {:command-id "cmd-1"
                                       :status :accepted
                                       :receipt {:status :accepted}}
                              "cmd-2" {:command-id "cmd-2"
                                       :status :submitting}}}
          stale-next {:revision 7
                      :commands {"cmd-1" {:command-id "cmd-1"
                                          :status :submitting}
                                 "cmd-3" {:command-id "cmd-3"
                                          :status :queued}}}
          merged (model/merge-local-state current stale-next)]
      (is (= :accepted (get-in merged [:commands "cmd-1" :status])))
      (is (= :submitting (get-in merged [:commands "cmd-2" :status])))
      (is (= :queued (get-in merged [:commands "cmd-3" :status])))))
  (testing "a fresh pending write still lands when current is pending too"
    (let [current {:revision 8
                   :commands {"cmd-1" {:command-id "cmd-1"
                                       :status :queued}}}
          next {:revision 8
                :commands {"cmd-1" {:command-id "cmd-1"
                                    :status :submitting}}}
          merged (model/merge-local-state current next)]
      (is (= :submitting (get-in merged [:commands "cmd-1" :status]))))))

(deftest last-move-survives-stale-merge-and-clear-propagates
  (testing "a stale write keeps the move receipt's :last-move"
    (let [current {:revision 9 :last-move {:entry-ids ["e-1"]
                                           :book-ids {"e-1" "wb-1"}}}
          stale-next {:revision 8}
          merged (model/merge-local-state current stale-next)]
      (is (= {:entry-ids ["e-1"] :book-ids {"e-1" "wb-1"}}
             (:last-move merged)))))
  (testing "an undo receipt's explicit clear is not resurrected by a stale write"
    (let [state (synced-state)
          command {:command-id "move-1"
                   :command-epoch 7
                   :kind model/entry-move-kind
                   :key [:materials/worldbook-entry "wbe-1"]
                   :payload {:entry-ids ["wbe-1"] :book-id "wb-2"}
                   :status :submitting}
          state (-> state
                    (assoc-in [:commands "move-1"] command)
                    (assoc :last-move {:entry-ids ["wbe-1"]
                                       :book-ids {"wbe-1" "wb-1"}}))
          undo-command {:command-id "undo-1"
                        :command-epoch 7
                        :kind model/entry-move-kind
                        :key [:materials/entry-undo "wb-1"]
                        :payload {:entry-ids ["wbe-1"] :book-id "wb-1"
                                  :undo? true}
                        :status :submitting}
          state (assoc-in state [:commands "undo-1"] undo-command)
          receipt {:command-id "undo-1"
                   :receipt {:status :accepted
                             :command-id "undo-1"
                             :result {:moved ["wbe-1"]
                                      :undo {:entry-ids ["wbe-1"]
                                             :book-ids {"wbe-1" "wb-2"}}}}}
          cleared (model/apply-receipt state receipt)
          ;; A stale projection that never saw the receipt must not bring
          ;; the old :last-move back when the cleared state merges onto it.
          merged (model/merge-local-state
                  {:revision 9 :last-move {:entry-ids ["wbe-1"]
                                           :book-ids {"wbe-1" "wb-1"}}}
                  cleared)]
      (is (contains? cleared :last-move)
          "the clear is an explicit nil so presence-based merge can see it")
      (is (nil? (:last-move merged))))))

(deftest settings-update-receipt-clears-pending-and-queues-snapshot
  (let [state (synced-state)
        queued (:state (model/queue-command
                        state model/settings-update-kind
                        {:memory-read? true :memory-suggest? true}
                        {:key [:materials/settings]}))
        command-id (->> (:commands queued)
                        (filter (fn [[_ c]] (= [:materials/settings]
                                               (:key c))))
                        first val :command-id)
        submitting (model/mark-submitting queued command-id)
        receipt {:ok? true
                 :command-id command-id
                 :command-epoch 7
                 :receipt {:status :accepted
                           :command-id command-id
                           :command-epoch 7
                           :result {:settings {:memory-read? true
                                               :memory-suggest? true}}}}
        applied (model/apply-receipt submitting receipt)]
    (is (= :accepted
           (get-in applied [:commands command-id :status])))
    (is (some #(= :materials/snapshot (:kind (:payload %)))
              (:outbox applied)))
    (testing "a second settings command is accepted after the receipt"
      (let [result (model/queue-command
                    applied model/settings-update-kind
                    {:memory-read? true :memory-suggest? false}
                    {:key [:materials/settings]})]
        (is (true? (:ok? result)))))))

(deftest submitted-transport-ack-does-not-release-pending-lock
  (testing "the gateway's :submitted ack is non-terminal"
    (let [state (synced-state)
          queued (:state (model/queue-command
                          state model/settings-update-kind
                          {:memory-read? true :memory-suggest? true}
                          {:key [:materials/settings]}))
          command-id (->> (:commands queued)
                          (filter (fn [[_ c]] (= [:materials/settings]
                                                 (:key c))))
                          first val :command-id)
          submitting (model/mark-submitting queued command-id)
          ack {:ok? true
               :command-id command-id
               :command-epoch 7
               :subscription-id 3
               :status :submitted}
          after (model/apply-receipt submitting ack)]
      (is (= :submitted (model/receipt-status ack)))
      (is (= :submitting
             (get-in after [:commands command-id :status]))
          "the pending lock stays until the domain receipt arrives")
      (is (empty? (:outbox after))
          "a transport ack never queues a reconcile snapshot")
      (testing "a second settings command is still refused"
        (let [result (model/queue-command
                      after model/settings-update-kind
                      {:memory-read? true :memory-suggest? false}
                      {:key [:materials/settings]})]
          (is (false? (:ok? result))))))
    (testing "the domain receipt still resolves the command"
      (let [state (synced-state)
            queued (:state (model/queue-command
                            state model/settings-update-kind
                            {:memory-read? true :memory-suggest? true}
                            {:key [:materials/settings]}))
            command-id (->> (:commands queued)
                            (filter (fn [[_ c]] (= [:materials/settings]
                                                   (:key c))))
                            first val :command-id)
            submitting (model/mark-submitting queued command-id)
            acked (model/apply-receipt
                   submitting
                   {:ok? true :command-id command-id :status :submitted})
            accepted (model/apply-receipt
                      acked
                      {:ok? true
                       :command-id command-id
                       :receipt {:status :accepted
                                 :command-id command-id
                                 :result {:settings {:memory-read? true}}}})]
        (is (= :accepted
               (get-in accepted [:commands command-id :status])))
        (is (some #(= :materials/snapshot (:kind (:payload %)))
                  (:outbox accepted)))))))

(deftest receipt-status-normalizes-string-and-missing-status
  (testing "a string :submitted status stays non-terminal"
    (is (= :submitted
           (model/receipt-status {:ok? true :status "submitted"}))))
  (testing "a missing status still defaults to :accepted"
    (is (= :accepted
           (model/receipt-status {:ok? true :command-id "c1"})))))

(deftest not-found-receipt-query-refuses-the-pending-command
  (testing "the sweep's :not-found answer releases the pending lock"
    (let [state (synced-state)
          queued (:state (model/queue-command
                          state model/settings-update-kind
                          {:memory-read? true :memory-suggest? true}
                          {:key [:materials/settings]}))
          command-id (->> (:commands queued)
                          (filter (fn [[_ c]] (= [:materials/settings]
                                                 (:key c))))
                          first val :command-id)
          submitting (model/mark-submitting queued command-id)
          ;; The durable gate answers a receipt query for a command it never
          ;; accepted with `:status :not-found`; `receipt-status` maps that
          ;; to `:refused`, the terminal state that frees the key.
          refused (model/apply-receipt
                   submitting
                   {:ok? true
                    :command-id command-id
                    :status :not-found
                    :receipt nil})]
      (is (= :refused
             (get-in refused [:commands command-id :status])))
      (let [retry (model/queue-command
                   refused model/settings-update-kind
                   {:memory-read? true :memory-suggest? false}
                   {:key [:materials/settings]})]
        (is (true? (:ok? retry))
            "a refused command frees the pending key for a retry")))))

(defn- button-labeled
  [nodes label]
  (first (filter #(and (= :button (:tag %))
                       (= [label] (:children %)))
                 nodes)))

(deftest view-shows-assistant-display-names
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-book-id "wb-1"
                         :book-draft (model/book-draft
                                      {:mode :edit :book-id "wb-1"
                                       :assistant-id "a-2"})))
        rendered (pr-str (view/panel-tree state :worldbook))]
    (is (re-find #"主助手" rendered))
    (is (not (re-find #"绑定 assistant-writer" rendered)))))

(deftest card-format-badges-are-chinese
  (let [state (model/apply-snapshot (synced-state) snapshot)
        nodes (tree-nodes (view/panel-tree state :instructions))
        badges (->> nodes
                    (filter #(and (= :span (:tag %))
                                  (= "hc-mat-badge"
                                     (get-in % [:attrs :class]))))
                    (mapcat :children)
                    set)]
    (is (contains? badges "JSON"))
    (is (contains? badges "文本"))
    (is (not (contains? badges "json")))))

(deftest card-output-format-uses-enum-select
  (let [state (-> (synced-state)
                  (assoc :selected-card-id "card-1")
                  (model/apply-snapshot snapshot))
        nodes (tree-nodes (view/panel-tree state :instructions))
        selects (filter #(= :select (:tag %)) nodes)
        format-select (first (filter (fn [node]
                                       (some #(and (= :option (:tag %))
                                                  (some #{"JSON"}
                                                        (:children %)))
                                             (tree-nodes node)))
                                     selects))]
    (is format-select "the output format field is a select")
    (is (= "text" (get-in format-select [:attrs :value])))
    (is (some #(and (= :option (:tag %)) (= "json" (get-in % [:attrs :value])))
              (tree-nodes format-select)))
    (is (some #(and (= :option (:tag %)) (= "" (get-in % [:attrs :value])))
              (tree-nodes format-select)))))

(deftest card-format-validation-refuses-unknown
  (is (false? (:ok? (model/validate-card-draft
                     (model/card-draft {:name "x" :body "b"
                                        :output-format "yaml"}))))
      "a free-text format the server would refuse is refused locally"))

(deftest one-new-button-per-material-block
  (doseq [[section label] [[:memory "新建记忆"]
                          [:instructions "新建指令卡"]]]
    (let [base (model/apply-snapshot (synced-state) snapshot)
          state (assoc base
                       :memory-draft (model/memory-draft
                                      {:mode :edit :memory-id "mem-a"}))
          rendered (pr-str (view/panel-tree state section))
          new-buttons (count (re-seq #":materials/(memory|book|entry|card)-new" rendered))]
      (is (= 1 new-buttons)
          (str section " renders exactly one new-button action, got " new-buttons)))))

(deftest worldbook-block-has-one-new-action-per-editor
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :book-draft (model/book-draft
                                      {:mode :edit :book-id "wb-1"})
                         :entry-draft (model/entry-draft
                                       {:mode :edit :entry-id "wbe-1"
                                        :book-id "wb-1"})))
        rendered (pr-str (view/panel-tree state :worldbook))]
    (is (= #{:materials/book-new :materials/entry-new}
           (set (map #(keyword (subs % 1))
                     (re-seq #":materials/(?:book|entry)-new" rendered))))
        "the worldbook block keeps one 新建 action per editor")))

(deftest entry-position-depth-only-for-between-recent
  (let [base (-> (model/apply-snapshot (synced-state) snapshot)
                 (assoc :entry-draft (model/entry-draft
                                      {:mode :edit :entry-id "wbe-1"
                                       :book-id "wb-1"})))
        background (pr-str (view/panel-tree base :worldbook))
        between (pr-str (view/panel-tree
                         (assoc-in base [:entry-draft :position-kind]
                                   :between-recent)
                         :worldbook))]
    (is (not (re-find #"位置深度" background)))
    (is (re-find #"位置深度" between))))

(deftest entry-move-target-excludes-current-book
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :entry-draft (model/entry-draft
                                       {:mode :edit :entry-id "wbe-1"
                                        :book-id "wb-1"
                                        :move-book-id ""})))
        nodes (tree-nodes (view/panel-tree state :worldbook))
        move-select (first (filter (fn [node]
                                     (and (= :select (:tag node))
                                          (some #(= ["选择目标世界书"] (:children %))
                                                (tree-nodes node))))
                                   nodes))]
    (is move-select)
    (is (not (some #(and (= :option (:tag %))
                         (= "wb-1" (get-in % [:attrs :value])))
                  (tree-nodes move-select))))))

(deftest delete-confirmation-is-two-step
  (doseq [[section draft-key draft] [[:worldbook :book-draft
                                      (model/book-draft {:mode :edit :book-id "wb-1"})]
                                     [:worldbook :entry-draft
                                      (model/entry-draft {:mode :edit
                                                          :entry-id "wbe-1"
                                                          :book-id "wb-1"})]
                                     [:instructions :card-draft
                                      (model/card-draft {:mode :edit :card-id "card-1"})]
                                     [:memory :memory-draft
                                      (model/memory-draft {:mode :edit
                                                           :memory-id "mem-c"})]]]
    (let [snap (if (= :memory section)
                 (-> snapshot
                     (assoc :memory/by-id
                            (assoc (:memory/by-id snapshot)
                                   "mem-c" memory-archived))
                     (assoc :memory/order ["mem-a" "mem-b" "mem-c"]
                            :memory/counts {:candidate 1 :active 1
                                            :archived 1 :rejected 0
                                            :deleted 0}))
                 snapshot)
          state (-> (model/apply-snapshot (synced-state) snap)
                    (assoc draft-key draft)
                    (cond-> (= :memory section)
                      (assoc :selected-memory-id "mem-c")))
          tree (view/panel-tree state section)
          nodes (tree-nodes tree)
          save-rows (nodes-with-class nodes "hc-mat-save-row")
          danger-rows (nodes-with-class nodes "hc-mat-danger-row")
          first-render (pr-str tree)]
      (is (re-find #"永久删除|删除条目|删除世界书|删除指令卡"
                   first-render))
      (is (not (re-find #"确认删除|确认永久删除" first-render)))
      (is (seq save-rows)
          (str section " renders the save row"))
      (is (= 1 (count danger-rows))
          (str section " renders one danger row"))
      (is (not (re-find #"删除|拒绝|归档" (pr-str save-rows)))
          (str section " keeps destructive actions out of the save row"))
      (is (re-find #"删除|拒绝" (pr-str danger-rows))
          (str section " puts destructive actions in the danger row"))
      (is (every? #(= "true" (get-in % [:attrs :data-danger])) danger-rows))
      (let [confirmed (assoc-in state [draft-key :confirm-delete?] true)
            confirmed-tree (view/panel-tree confirmed section)
            confirmed-nodes (tree-nodes confirmed-tree)
            confirmed-save-rows (nodes-with-class confirmed-nodes
                                                  "hc-mat-save-row")
            confirmed-danger-rows (nodes-with-class confirmed-nodes
                                                    "hc-mat-danger-row")
            danger-buttons (remove nil? (:children (first confirmed-danger-rows)))
            second-render (pr-str confirmed-tree)]
        (is (re-find #"确认" second-render))
        (is (seq confirmed-save-rows))
        (is (= 1 (count confirmed-danger-rows)))
        (is (= 2 (count danger-buttons))
            "the confirm pair stays together in the danger row")
        (is (re-find #"确认.*删除" (pr-str (first danger-buttons))))
        (is (re-find #"取消" (pr-str (second danger-buttons))))))))

(deftest memory-archive-action-gets-its-own-row
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :selected-memory-id "mem-b"
                         :memory-draft (model/memory-draft
                                        {:mode :edit :memory-id "mem-b"})))
        nodes (tree-nodes (view/panel-tree state :memory))
        save-rows (nodes-with-class nodes "hc-mat-save-row")
        danger-rows (nodes-with-class nodes "hc-mat-danger-row")]
    (is (= 1 (count save-rows)))
    (is (= 1 (count danger-rows)))
    (is (re-find #"保存替换" (pr-str save-rows)))
    (is (not (re-find #"归档" (pr-str save-rows))))
    (is (re-find #"归档" (pr-str danger-rows)))))

(deftest instruction-selection-pending-disables-controls
  (let [state (-> (model/apply-snapshot (synced-state) snapshot)
                  (assoc :instruction-mode :session))
        queued (:state (model/reduce-action
                        state
                        {:type :materials/card-toggle-selection
                         :card-id "card-1"}))
        command (first (filter #(= model/instruction-session-set-kind (:kind %))
                               (vals (:commands queued))))
        submitting (model/mark-submitting queued (:command-id command))
        rendered (pr-str (view/panel-tree submitting :instructions))
        nodes (tree-nodes (view/panel-tree submitting :instructions))
        checkboxes (filter #(and (= :input (:tag %))
                                 (= "checkbox" (get-in % [:attrs :type])))
                           nodes)]
    (is (model/instruction-selection-pending? submitting))
    (is (every? #(true? (get-in % [:attrs :disabled])) checkboxes))
    (is (re-find #"待确认" rendered))))

(deftest memory-suggest-receipt-sets-result-and-view-renders-note
  (let [state (synced-state)
        queued (:state (model/reduce-action
                        state {:type :materials/memory-suggest}))
        command-id (->> (:commands queued)
                        (filter (fn [[_ c]]
                                  (= (keyword "materials" "memory/suggest")
                                     (:kind c))))
                        first val :command-id)
        submitting (model/mark-submitting queued command-id)
        receipt {:ok? true
                 :command-id command-id
                 :command-epoch 7
                 :receipt {:status :accepted
                           :command-id command-id
                           :command-epoch 7
                           :result {:candidates [{:body "喜欢简洁回答"
                                                  :category :expression-preference}]
                                    :skipped 2
                                    :skipped-reasons {:empty 2}
                                    :dropped-message-count 0}}}
        applied (model/apply-receipt submitting receipt)]
    (is command-id "the suggest command was queued")
    (is (= :accepted
           (get-in applied [:commands command-id :status])))
    (is (some? (:memory-suggest-result applied))
        "an accepted suggest receipt stores :memory-suggest-result")
    (is (= 1 (count (:candidates (:memory-suggest-result applied)))))
    (is (= 2 (:skipped (:memory-suggest-result applied))))
    (is (some #(= :materials/snapshot (get-in % [:payload :kind]))
              (:outbox applied))
        "the receipt queues a reconcile snapshot")
    (testing "the memory panel renders the 本次整理 note"
      (let [snap-state (-> (model/apply-snapshot (synced-state) snapshot)
                           (assoc :selected-session-id "s-1"
                                  :memory-suggest-result
                                  (:memory-suggest-result applied)))
            rendered (pr-str (view/panel-tree snap-state :memory))]
        (is (re-find #"本次整理" rendered))
        (is (re-find #"候选 1" rendered))))))
