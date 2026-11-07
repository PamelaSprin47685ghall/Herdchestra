(ns hc.client.backup.password-input-test
  "Regression for the backup password draft lifecycle.

  The real-browser defect kept only the last typed character because the
  password field was controlled by a Fulcro render that the bridge schedules
  asynchronously.  React restored the last committed value between fast
  keystrokes, and the next input event read only the new character.  These
  checks pin the structural contract that prevents it: password fields are
  draft inputs, and only an explicit clear changes their reset token."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.backup.model :as model]
            [hc.client.backup.view :as view]))

(defn- connected []
  (-> (model/initial-state)
      (assoc :sync {:phase :synced :command-epoch 7})
      (assoc-in [:ui :transport-available?] true)
      (assoc-in [:ui :target-id] :local)
      (assoc-in [:server :default-target-id] :local)))

(defn- find-node
  "Walk hiccup nodes and the vectors produced by `mapv`; `tree-seq` alone
  stops at a nested vector and would miss the restore panel's field."
  [tree pred]
  (cond
    (pred tree) tree
    (map? tree) (some #(find-node % pred) (:children tree))
    (sequential? tree) (some #(find-node % pred) tree)
    :else nil))

(defn- by-aria-label
  [tree label]
  (find-node tree
             (fn [node]
               (= label (get-in node [:attrs :aria-label])))))

(deftest password-fields-are-draft-owned
  (let [tree (view/backup-tree (connected))
        create (by-aria-label tree "备份密码")
        confirm (by-aria-label tree "再次输入备份密码")]
    (testing "the create fields are present and marked as draft inputs"
      (is (some? create))
      (is (some? confirm))
      (is (true? (get-in create [:attrs :draft-input])))
      (is (true? (get-in confirm [:attrs :draft-input])))
      (is (= 0 (get-in create [:attrs :reset-key])))
      (is (= 0 (get-in confirm [:attrs :reset-key]))))
    (testing "the live value is a seed, not the only source of truth"
      (is (= "" (get-in create [:attrs :value])))
      (is (some? (get-in create [:attrs :on-input])))
      (is (some? (get-in create [:attrs :on-composition-end]))))))

(deftest clear-password-bumps-the-reset-token
  (let [typed (-> (connected)
                  (assoc-in [:ui :password] "abcdef123456")
                  (assoc-in [:ui :password-confirm] "abcdef123456"))
        cleared (:state (model/reduce-action
                         typed {:type :backup/clear-password}))]
    (is (= "" (get-in cleared [:ui :password])))
    (is (= "" (get-in cleared [:ui :password-confirm])))
    (is (= 1 (get-in cleared [:ui :password-reset])))
    (testing "the create and restore fields both see the new reset token"
      (let [create-tree (view/backup-tree
                         (assoc-in cleared [:ui :section] :backup))
            restore-tree (view/backup-tree
                          (assoc-in cleared [:ui :section] :restore))
            create-input (by-aria-label create-tree "备份密码")
            restore-input (by-aria-label restore-tree "恢复用备份密码")]
        (is (some? create-input) "create password input is missing")
        (is (some? restore-input) "restore password input is missing")
        (is (= 1 (get-in create-input [:attrs :reset-key])))
        (is (= 1 (get-in restore-input [:attrs :reset-key])))))))

(deftest password-visibility-toggle-keeps-the-value
  (let [tree (view/backup-tree (connected))
        input (by-aria-label tree "备份密码")]
    (is (= "password" (get-in input [:attrs :type])))
    (let [visible (-> (connected)
                      (assoc-in [:ui :password] "abcdef123456")
                      (assoc-in [:ui :password-visible?] true))
          visible-tree (view/backup-tree visible)
          visible-input (by-aria-label visible-tree "备份密码")]
      (is (= "text" (get-in visible-input [:attrs :type])))
      (is (= "abcdef123456" (get-in visible-input [:attrs :value])))
      (is (some? (find-node visible-tree
                            (fn [node]
                              (and (= :button (get-in node [:tag]))
                                   (= "隐藏密码"
                                      (first (:children node))))))))
      (let [toggled (:state (model/reduce-action
                             visible
                             {:type :backup/toggle-field
                              :field :password-visible?}))]
        (is (false? (get-in toggled [:ui :password-visible?])))
        (is (= "abcdef123456" (get-in toggled [:ui :password]))))))
  (testing "the copy control is an explicit button"
    (let [tree (view/backup-tree (assoc-in (connected)
                                           [:ui :password]
                                           "abcdef123456"))]
      (is (some? (find-node tree
                            (fn [node]
                              (and (= :button (get-in node [:tag]))
                                   (= "复制密码"
                                      (first (:children node)))))))))))
