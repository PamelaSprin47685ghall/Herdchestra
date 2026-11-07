(ns hc.server.materials.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]))

(defn- enabled-state
  []
  (:state (materials/update-settings (materials/new-state)
                                     {:memory-read? true
                                      :memory-suggest? true})))

(defn- remember!
  [state id body & [extra]]
  (:state (materials/remember state
                              (merge {:id id
                                      :body body
                                      :category :expression-preference
                                      :scope :global
                                      :epistemic :preference}
                                     extra))))

(deftest new-state-has-both-switches-off
  (let [state (materials/new-state)]
    (is (false? (materials/memory-read-enabled? state)))
    (is (false? (materials/memory-suggest-enabled? state)))
    (is (empty? (materials/check-invariants state)))
    (is (empty? (:memories state)))))

(deftest all-four-memory-categories-are-accepted
  (let [state (enabled-state)
        categories [:identity-background :working-style
                    :expression-preference :long-term-requirement]
        results (mapv (fn [index category]
                        (materials/remember
                         state
                         {:id (str "m" index)
                          :body (str "category " (name category))
                          :category category
                          :scope :global
                          :epistemic :preference}))
                      (range)
                      categories)]
    (is (every? :ok? results))
    (is (= (set categories)
           (set (map (comp :category :memory) results))))))

(deftest manual-save-is-allowed-while-read-is-off
  (let [state (materials/new-state)
        result (materials/remember state
                                   {:id "m1"
                                    :body "User prefers Chinese."
                                    :category :expression-preference
                                    :scope :global
                                    :epistemic :preference})]
    (is (true? (:ok? result)))
    (is (= :active (get-in result [:memory :status])))
    (is (= [] (:memories (materials/effective-memories (:state result) {}))))
    (is (= :memory-read-disabled
           (:reason (materials/effective-memories (:state result) {}))))))

(deftest effective-scope-order-and-temporary-session
  (let [state (-> (enabled-state)
                  (remember! "global" "Global preference.")
                  (remember! "assistant" "Assistant preference."
                             {:scope {:kind :assistant :assistant-id "a1"}})
                  (remember! "other" "Other assistant preference."
                             {:scope {:kind :assistant :assistant-id "a2"}})
                  (remember! "project" "Project convention."
                             {:scope {:kind :project :project-id "p1"}}))
        visible (:memories (materials/effective-memories
                            state
                            {:assistant-id "a1" :project-id "p1"}))]
    (is (= ["assistant" "project" "global"] (map :id visible)))
    (is (= [0 1 2] (map :scope-rank visible)))
    (is (= ["other" "global"]
           (map :id (:memories (materials/effective-memories
                                state
                                {:assistant-id "a2"})))))
    (testing "temporary sessions read nothing without an explicit read-only grant"
      (is (= :temporary-session
             (:reason (materials/effective-memories
                       state
                       {:assistant-id "a1" :temporary? true}))))
      (is (= 3 (count (:memories (materials/effective-memories
                                  state
                                  {:assistant-id "a1"
                                   :project-id "p1"
                                   :temporary? true
                                   :temporary-read? true})))))
      (is (false? (:ok? (materials/remember state
                                            {:id "tmp"
                                             :body "No write."
                                             :category :working-style
                                             :scope :global
                                             :temporary? true})))))))

(deftest candidate-review-and-replacement-history
  (let [state (enabled-state)
        proposed (materials/propose-memory
                  state
                  {:id "c1"
                   :body "User works on compilers."
                   :category :identity-background
                   :scope {:kind :assistant :assistant-id "a1"}
                   :epistemic :fact
                   :source {:session-id "s1"
                            :message-ids ["msg-1"]
                            :excerpt "I work on compilers."}})]
    (is (true? (:ok? proposed)))
    (is (= :candidate (get-in proposed [:memory :status])))
    (is (= [] (:memories (materials/effective-memories
                          (:state proposed)
                          {:assistant-id "a1"}))))
    (let [approved (materials/approve-memory (:state proposed) "c1" {})
          state (:state approved)]
      (is (true? (:ok? approved)))
      (is (= :active (get-in approved [:memory :status])))
      (is (= ["c1"] (map :id (:memories (materials/effective-memories
                                         state
                                         {:assistant-id "a1"})))))
      (let [replaced (materials/replace-memory
                      state "c1"
                      {:body "User works on programming languages."
                       :reason "user correction"})]
        (is (true? (:ok? replaced)))
        (is (= "User works on compilers."
               (get-in replaced [:memory :versions 0 :body])))
        (is (= "user correction"
               (get-in replaced [:memory :versions 0 :reason])))
        (is (= 1 (count (get-in replaced [:memory :versions]))))
        (is (= "User works on programming languages."
               (get-in replaced [:memory :body])))
        (is (empty? (materials/check-invariants (:state replaced))))))))

(deftest reject-suppresses-identical-suggestion
  (let [state (enabled-state)
        request {:body "User likes tea."
                 :category :expression-preference
                 :scope :global
                 :epistemic :preference
                 :source {:session-id "s1"
                          :message-ids ["m1"]
                          :excerpt "I like tea."}}
        proposed (materials/propose-memory state (assoc request :id "c1"))
        rejected (materials/reject-memory (:state proposed) "c1" {:reason "not stable"})
        repeated (materials/propose-memory (:state rejected)
                                           (assoc request :id "c2"))]
    (is (true? (:ok? rejected)))
    (is (= :rejected (get-in rejected [:memory :status])))
    (is (true? (:ok? repeated)))
    (is (true? (:suppressed? repeated)))
    (is (= :previously-rejected (:reason repeated)))
    (is (not (contains? (get-in repeated [:state :memories]) "c2")))))

(deftest automatic-review-refuses-secrets-and-missing-evidence
  (let [state (enabled-state)
        secret (materials/propose-memory
                state
                {:id "c1"
                 :body "password: hunter2"
                 :category :identity-background
                 :scope :global
                 :epistemic :fact
                 :source {:session-id "s1"
                          :message-ids ["m1"]
                          :excerpt "password: hunter2"}})
        no-source (materials/propose-memory
                   state
                   {:id "c2"
                    :body "User lives in Shanghai."
                    :category :identity-background
                    :scope :global
                    :epistemic :fact})
        no-epistemic (materials/propose-memory
                      state
                      {:id "c3"
                       :body "User lives in Shanghai."
                       :category :identity-background
                       :scope :global
                       :source {:session-id "s1"
                                :message-ids ["m1"]
                                :excerpt "I live in Shanghai."}})]
    (is (false? (:ok? secret)))
    (is (= :MEMORY_SENSITIVE_CONTENT (get-in secret [:error :code])))
    (is (false? (:ok? no-source)))
    (is (= :MEMORY_SOURCE_INVALID (get-in no-source [:error :code])))
    (is (false? (:ok? no-epistemic)))
    (is (= :MEMORY_EPISTEMIC_INVALID (get-in no-epistemic [:error :code])))
    (testing "manual save may keep a sensitive record, marked for management"
      (let [saved (materials/remember state
                                      {:id "m1"
                                       :body "password: hunter2"
                                       :category :identity-background
                                       :scope :global})]
        (is (true? (:ok? saved)))
        (is (true? (get-in saved [:memory :sensitive?])))))))

(deftest archive-restore-and-tombstone
  (let [state (-> (enabled-state)
                  (remember! "m1" "User prefers short answers."))
        archived (:state (materials/archive-memory state "m1" {:reason "old"}))
        restored (:state (materials/restore-memory archived "m1"))
        archived-again (:state (materials/archive-memory restored "m1" {}))
        deleted (materials/delete-memory archived-again "m1" {:confirm true})]
    (is (= [] (:memories (materials/effective-memories archived {}))))
    (is (= :archived (get-in archived [:memories "m1" :status])))
    (is (= :active (get-in restored [:memories "m1" :status])))
    (is (true? (:ok? deleted)))
    (is (= :deleted (get-in deleted [:memory :status])))
    (is (nil? (get-in deleted [:memory :body])))
    (is (string? (get-in deleted [:memory :body-hash])))
    (is (= [] (:memories (materials/search-memories
                          (:state deleted)
                          {:query "short"}))))
    (is (= :MEMORY_CONFIRM_REQUIRED
           (get-in (materials/delete-memory archived-again "m1" {}) [:error :code])))
    (is (empty? (materials/check-invariants (:state deleted))))))

(deftest selection-never-truncates
  (let [state (-> (enabled-state)
                  (remember! "always" "ALWAYS" {:always? true})
                  (remember! "normal" "NORMAL"))
        memories (:memories (materials/effective-memories state {}))
        selection (materials/select-memories memories {:budget 7})]
    (is (true? (:ok? selection)))
    (is (= ["always"] (map :id (:provided selection))))
    (is (= [{:memory-id "normal" :reason :budget :chars 6 :always? false}]
           (:omitted selection)))
    (is (= 6 (:used-chars selection)))))

(deftest backup-merge-keeps-archive-and-delete-decisions
  (let [state (-> (enabled-state)
                  (remember! "m1" "Original preference."))
        archived (:state (materials/archive-memory state "m1" {}))
        incoming {:id "m1"
                  :body "Old backup preference."
                  :category :expression-preference
                  :scope :global
                  :epistemic :preference
                  :status :active}
        merged (materials/merge-memory-backup archived
                                              {:import-id "imp1"
                                               :memories [incoming]})
        conflict-id "merge-imp1-m1"]
    (is (true? (:ok? merged)))
    (is (= :archived (get-in merged [:state :memories "m1" :status])))
    (is (= :candidate (get-in merged [:state :memories conflict-id :status])))
    (is (= "m1" (get-in merged [:state :memories conflict-id :conflict-of])))
    (is (= [] (:memories (materials/effective-memories (:state merged) {}))))
    (is (empty? (materials/check-invariants (:state merged))))
    (testing "re-import is idempotent"
      (let [again (materials/merge-memory-backup (:state merged)
                                                 {:import-id "imp1"
                                                  :memories [incoming]})]
        (is (true? (:ok? again)))
        (is (= 1 (count (filter #(= conflict-id (:id %))
                                (vals (get-in again [:state :memories]))))))))))

(deftest backup-merge-never-resurrects-a-deleted-memory
  (let [state (-> (enabled-state)
                  (remember! "m1" "Deleted preference."))
        archived (:state (materials/archive-memory state "m1" {}))
        deleted (:state (materials/delete-memory archived "m1" {:confirm true}))
        incoming {:id "m1"
                  :body "Old backup preference."
                  :category :expression-preference
                  :scope :global
                  :epistemic :preference
                  :status :active}
        merged (materials/merge-memory-backup deleted
                                              {:import-id "imp-deleted"
                                               :memories [incoming]})
        conflict-id "merge-imp-deleted-m1"]
    (is (true? (:ok? merged)))
    (is (= :deleted (get-in merged [:state :memories "m1" :status])))
    (is (= :candidate (get-in merged [:state :memories conflict-id :status])))
    (is (= [] (:memories (materials/effective-memories (:state merged) {}))))
    (is (empty? (materials/check-invariants (:state merged))))))
