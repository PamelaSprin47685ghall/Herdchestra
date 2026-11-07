(ns hc.compose.mission.control-test
  "Pure adapters from clj-review/clj-plan control requests to Owner commands."
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.control :as control]
            [hc.compose.mission.support :as support]))

(deftest review-command-becomes-a-real-mission-review
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        ctx {:role :reviewer
             :lane :execution
             :tenure-id execution-id
             :call-id "review-tool-call"}
        command (control/review-command
                 ctx
                 {:findings [{:key "k" :value "Do the work"}]})
        result (mission/apply-command
                (mission/snapshot h)
                (-> command
                    (dissoc :kind :call-id)
                    (assoc :op :review :call-id (:call-id command))))]
    (is (= :review/submit (:kind command)))
    (is (= :execution (:lane command)))
    (is (= execution-id (:tenure-id command)))
    (is (:ok? result) (pr-str (:error result)))
    (is (= :swarm-started (get-in result [:receipt :outcome])))
    (is (= #{"k"}
           (set (map :ledger-key
                     (vals (get-in result
                                   [:state :boards execution-id :tasks]))))))))

(deftest review-command-pins-the-context-identity
  (let [command (control/review-command
                 {:role :plan
                  :lane :plan
                  :tenure-id "tenure-1"
                  :call-id "call-1"}
                 {:lane :execution
                  :tenure-id "tenure-9"
                  :call-id "call-9"
                  :findings []})]
    (is (= :plan (:lane command))
        "the model's primitive arguments cannot choose the review lane")
    (is (= "tenure-1" (:tenure-id command))
        "the model's primitive arguments cannot choose the review tenure")
    (is (= "call-1" (:call-id command))
        "the model's primitive arguments cannot choose the review call id")))

(deftest review-lane-fallback-understands-program-roles
  (is (= :plan
         (:lane (control/review-command {:role :clj-plan} {:findings []}))))
  (is (= :execution
         (:lane (control/review-command {:role :clj-review} {:findings []})))))

(deftest draft-command-is-the-owner-draft-write
  (let [command (control/draft-command "plans/control.md" "Draft body"
                                       "tenure-1")]
    (is (= {:kind :plan/write-draft
            :path "plans/control.md"
            :content "Draft body"
            :tenure-id "tenure-1"}
           command))
    (is (control/draft-path? "plans/control.md"))
    (is (not (control/draft-path? "src/control.md")))
    (is (not (control/draft-path? "plans/../control.md")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (control/draft-command "src/control.md" "x" "tenure-1")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (control/draft-command "plans/control.md" "x" nil))
        "a draft write without the pinned Plan tenure is refused")))

(deftest applied-draft-paths-are-extracted-from-workspace-effects
  (let [result {:effects [{:type :files/applied
                           :changed-paths ["plans/a.md" "src/a.clj"]}
                          {:type :files/applied
                           :changed-paths ["plans/b.md" "plans/a.md"]}]
                :receipts [{:type :files/applied
                            :changed-paths ["notes.md"]}]}]
    (is (= ["plans/a.md" "plans/b.md"]
           (control/applied-draft-paths result)))))
