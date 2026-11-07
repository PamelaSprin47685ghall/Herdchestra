(ns hc.server.app.snapshot-unresolved-calls-test
  "The browser baseline publishes unknown calls without their request bodies.

  E2E 10 needs a real operator entry: the client must see which call is
  unknown, in which stage, and whether the operator already checked it.  The
  snapshot must never carry the program source or the model request."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.snapshot :as snapshot]
            [hc.client.sync.normalize :as sync-normalize]))

(defn- owner-snapshot [calls]
  {:version 3
   :state {:session-id "s1"
           :conversation []
           :runs {}
           :calls calls}})

(defn- baseline [calls]
  (snapshot/baseline
   {:sessions [{:session-id "s1" :title "E2E 10"}]
    :owner-snapshots {"s1" (owner-snapshot calls)}
    :active-session "s1"
    :now 5000}))

(deftest unknown-calls-are-published-with-a-check-entry
  (let [base (baseline
              {"call-open" {:call-id "call-open"
                            :kind :tool
                            :role :worker
                            :lane :execution
                            :tenure-id "tenure-3"
                            :agent-id "agent-2"
                            :member "clj-worker"
                            :task-id "task-1"
                            :state :outcome-unknown
                            :created-at 1000
                            :request {:snapshot {:secret "do-not-publish"}}
                            :result {:source "program source"}}
               "call-closed" {:call-id "call-closed"
                              :kind :tool
                              :state :succeeded
                              :created-at 1100
                              :ack {:outcome :succeeded
                                    :by :operator
                                    :at 2000
                                    :note "checked"}}
               "call-failed" {:call-id "call-failed"
                              :kind :tool
                              :state :failed
                              :created-at 1200}})
        rows (get-in base [:session/by-id "s1" :unresolved-calls])
        open (some #(when (= "call-open" (:call-id %)) %) rows)
        closed (some #(when (= "call-closed" (:call-id %)) %) rows)]
    (testing "the unknown call is published with identity and stage"
      (is (= 2 (count rows)) "only unknown and acknowledged calls are published")
      (is (= :tool (:kind open)))
      (is (= :worker (:role open)))
      (is (= "clj-worker" (:member open)))
      (is (= "task-1" (:task-id open)))
      (is (= :outcome-unknown (:last-known-stage open)))
      (is (true? (:outcome-unknown? open))))
    (testing "the request and program source never reach the browser"
      (is (not (contains? open :request)))
      (is (not (contains? open :result)))
      (is (not (contains? open :source))))
    (testing "an acknowledged call keeps the operator conclusion"
      (is (= :succeeded (get-in closed [:ack :outcome])))
      (is (= :operator (get-in closed [:ack :by])))
      (is (= "checked" (get-in closed [:ack :note])))
      (is (nil? (:outcome-unknown? closed))))))

(deftest the-published-call-list-is-bounded
  (let [calls (into {}
                    (for [i (range 30)]
                      [(str "call-" i)
                       {:call-id (str "call-" i)
                        :kind :tool
                        :state :outcome-unknown
                        :created-at i}]))
        rows (get-in (baseline calls)
                     [:session/by-id "s1" :unresolved-calls])]
    (is (= 20 (count rows)))
    (is (= "call-29" (:call-id (last rows))))
    (is (= "call-10" (:call-id (first rows))))))

(deftest a-protected-commit-conflict-publishes-both-stamps
  (let [old {:mtime-sec 1 :mtime-nsec 2 :size 9}
        fresh {:mtime-sec 3 :mtime-nsec 4 :size 14}
        base (snapshot/baseline
              {:sessions [{:session-id "s1"}]
               :owner-snapshots
               {"s1" {:state
                      {:session-id "s1"
                       :worker-jobs
                       {"job-1" {:job-id "job-1"
                                 :task-id :task-2
                                 :claim-id :claim-1
                                 :state :finished
                                 :created-at 1
                                 :result
                                 {:status :failed
                                  :receipts
                                  [{:type :workspace/settle
                                    :status :FILE_CONFLICT
                                    :result
                                    {:path "/w/e2e09.txt"
                                     :status :FILE_CONFLICT
                                     :code :stamp-mismatch
                                     :expected [old fresh]
                                     :actual fresh
                                     :observations [{:stamp old}
                                                    {:stamp fresh}]}}]}}}}}}
               :active-session "s1"})
        row (first (get-in base [:session/by-id "s1" :worker-programs]))]
    (is (= :FILE_CONFLICT (:program-status row)))
    (is (= "/w/e2e09.txt" (get-in row [:conflicts 0 :path])))
    (is (= :stamp-mismatch (get-in row [:conflicts 0 :reason])))
    (is (= old (get-in row [:conflicts 0 :expected])))
    (is (= fresh (get-in row [:conflicts 0 :actual])))
    (is (= "/w/e2e09.txt" (:file-path row)))))

(deftest board-entity-tables-publish-string-ids-the-client-accepts
  ;; D-16: board records carry keyword IDs (`:task-1`).  Publishing them
  ;; verbatim made the client's `normalize-table` reject the whole baseline
  ;; with `:invalid-entity`, so every STATE_SNAPSHOT fell into a resync loop
  ;; and the workbench stayed at "waiting for sync".  The baseline must emit
  ;; string entity IDs for every `*/by-id` table.
  (let [base (snapshot/baseline
              {:sessions [{:session-id "s1"}]
               :owner-snapshots
               {"s1" {:state {:session-id "s1"
                              :worker-jobs
                              {"job-1" {:job-id "job-1"
                                        :task-id :task-2
                                        :state :finished
                                        :created-at 1
                                        :result {:status :succeeded}}}}}}
               :board {:board-version 3
                       :tasks {"a" {:task-id :task-1 :title "root"}
                               "b" {:task-id :task-2 :title "child"
                                    :parent-id :task-1}}
                       :actions {"x" {:action-id :act-1
                                       :task-id :task-1
                                       :state :finished}}
                       :actors {"g" {:agent-id :agent-4
                                     :state :idle}}
                       :slots {"s" {:slot-id :slot-a
                                    :state :free}}
                       :token-slot {:token "held"}}
               :swarm-id "local"
               :active-session "s1"})
        tables (select-keys base [:task/by-id :action/by-id :agent/by-id
                                  :slot/by-id :token-slot/by-id])
        non-string-keys
        (into {}
              (keep (fn [[table rows]]
                      (let [bad (remove string? (keys rows))]
                        (when (seq bad) [table bad]))))
              tables)
        converted (sync-normalize/to-db base)]
    (is (empty? non-string-keys)
        (str "every */by-id table must use string entity IDs; got "
             (pr-str non-string-keys)))
    (is (true? (:ok? converted))
        (str "the client normalize/to-db must accept the baseline; got "
             (pr-str (:error converted))))
    (is (= "task-1" (first (keys (:task/by-id base)))))
    (is (= "job-1"
           (:job-id (first (get-in converted [:value :session/by-id "s1"
                                              :worker-programs])))))))
