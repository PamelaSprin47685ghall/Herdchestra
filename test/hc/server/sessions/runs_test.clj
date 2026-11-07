(ns hc.server.sessions.runs-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.sessions.runs :as runs]
            [hc.server.sessions.test-support :as support]))

(defn- session-with-run
  ([] (session-with-run {}))
  ([{:keys [message-ids] :or {message-ids []}}]
   (let [context (support/build-context)
         session (support/new-test-session context)
         started (runs/start-run
                  session
                  {:run-id "run-1"
                   :call-id "call-1"
                   :model-binding (:model-binding session)
                   :message-ids message-ids
                   :language :en
                   :now 2000})]
     (is (:ok? started) (pr-str started))
     (:session started))))

(defn- claimed
  [session]
  (let [result (runs/claim-call session
                                {:call-id "call-1"
                                 :attempt-id "attempt-1"
                                 :now 3000})]
    (is (:ok? result) (pr-str result))
    (:session result)))

(deftest start-run-creates-a-ready-call-and-a-frozen-snapshot
  (let [session (session-with-run {:message-ids ["m-1"]})
        run (get-in session [:runs "run-1"])
        call (get-in session [:calls "call-1"])]
    (is (= :running (:state run)))
    (is (= :ready (:state call)))
    (is (= (:input-snapshot run) (:input-snapshot call)))
    (is (= ["m-1"] (get-in run [:input-snapshot :message-ids])))
    (is (= :en (get-in run [:input-snapshot :language])))
    (is (= 2000 (get-in run [:input-snapshot :frozen-at])))
    (let [edited (assoc-in session [:messages "m-1" :versions 0 :content] "changed")]
      (is (= ["m-1"] (get-in edited [:runs "run-1" :input-snapshot :message-ids])))
      (is (= (:input-snapshot run)
             (get-in edited [:runs "run-1" :input-snapshot]))))))

(deftest a-run-needs-a-model-binding
  (let [context (support/build-context)
        session (support/new-test-session context)
        result (runs/start-run session
                               {:run-id "run-1"
                                :call-id "call-1"
                                :model-binding nil
                                :message-ids []
                                :language :en
                                :now 2000})]
    (is (not (:ok? result)))
    (is (= :MODEL_BINDING_REQUIRED (get-in result [:error :code])))))

(deftest only-one-running-run-is-allowed
  (let [session (session-with-run)
        result (runs/start-run session
                               {:run-id "run-2"
                                :call-id "call-2"
                                :model-binding (:model-binding session)
                                :message-ids []
                                :language :en
                                :now 3000})]
    (is (not (:ok? result)))
    (is (= :RUN_ALREADY_ACTIVE (get-in result [:error :code])))))

(deftest claim-before-dispatch-records-the-attempt
  (let [session (session-with-run)
        result (runs/claim-call session
                                {:call-id "call-1"
                                 :attempt-id "attempt-1"
                                 :now 3000})
        call (get-in (:session result) [:calls "call-1"])
        attempt (get-in (:session result) [:attempts "attempt-1"])]
    (is (:ok? result))
    (is (= :dispatching (:state call)))
    (is (= "attempt-1" (:current-attempt-id call)))
    (is (= :dispatching (:state attempt)))
    (is (= (:input-snapshot call) (:input-snapshot attempt)))
    (is (= :dispatch-model (get-in result [:effects 0 :kind])))
    (is (= "attempt-1" (get-in result [:effects 0 :attempt-id])))))

(deftest attempt-ids-are-never-reused
  (let [session (claimed (session-with-run))
        result (runs/start-attempt session
                                   {:call-id "call-1"
                                    :attempt-id "attempt-1"
                                    :now 4000})]
    (is (not (:ok? result)))
    (is (= :ATTEMPT_ID_TAKEN (get-in result [:error :code])))))

(deftest chunks-are-buffered-and-fenced-by-attempt
  (let [session (claimed (session-with-run))
        chunk (runs/accept-event session
                                 {:kind :chunk
                                  :attempt-id "attempt-1"
                                  :text "hello"}
                                 4000)
        stale (runs/accept-event (:session chunk)
                                 {:kind :chunk
                                  :attempt-id "attempt-unknown"
                                  :text "ignored"}
                                 4001)]
    (is (:ok? chunk))
    (is (:visible? chunk))
    (is (= 1 (count (get-in (:session chunk)
                            [:attempts "attempt-1" :chunks]))))
    (is (not (:ok? stale)))
    (is (= :ATTEMPT_UNKNOWN (get-in stale [:error :code])))))

(deftest terminal-events-are-idempotent
  (let [session (claimed (session-with-run))
        completed (runs/accept-event session
                                     {:kind :completed
                                      :attempt-id "attempt-1"
                                      :content "done"}
                                     4000)
        duplicate (runs/accept-event (:session completed)
                                     {:kind :completed
                                      :attempt-id "attempt-1"
                                      :content "done"}
                                     4001)
        different (runs/accept-event (:session completed)
                                     {:kind :failed
                                      :attempt-id "attempt-1"
                                      :error {:code :late}}
                                     4002)]
    (is (:ok? completed))
    (is (= :succeeded (get-in (:session completed)
                              [:calls "call-1" :state])))
    (is (= :completed (get-in (:session completed)
                              [:runs "run-1" :state])))
    (is (:ignored duplicate))
    (is (= :duplicate-terminal (:ignored duplicate)))
    (is (:ignored different))
    (is (= :late-terminal (:ignored different)))
    (is (= :succeeded (get-in (:session different)
                              [:calls "call-1" :state])))))

(deftest retry-keeps-call-id-and-changes-attempt-id
  (let [session (claimed (session-with-run))
        failed (runs/accept-event session
                                  {:kind :failed
                                   :attempt-id "attempt-1"
                                   :error {:code :transient}}
                                  4000)
        retried (runs/start-attempt (:session failed)
                                    {:call-id "call-1"
                                     :attempt-id "attempt-2"
                                     :now 5000})
        call (get-in (:session retried) [:calls "call-1"])
        attempt (get-in (:session retried) [:attempts "attempt-2"])]
    (is (:ok? retried))
    (is (= :dispatching (:state call)))
    (is (= "attempt-2" (:current-attempt-id call)))
    (is (= ["attempt-1" "attempt-2"] (:attempt-order call)))
    (is (= "attempt-1" (get-in (:session retried)
                               [:attempts "attempt-1" :attempt-id])))
    (is (= (:input-snapshot call) (:input-snapshot attempt)))
    (let [old-terminal (runs/accept-event (:session retried)
                                          {:kind :completed
                                           :attempt-id "attempt-1"
                                           :content "old"}
                                          6000)]
      (is (:ignored old-terminal))
      (is (= :stale-attempt (:ignored old-terminal)))
      (is (= :dispatching (get-in (:session old-terminal)
                                  [:calls "call-1" :state]))))))

(deftest tool-call-dedup-uses-the-project-tool-call-id
  (let [session (claimed (session-with-run))
        first-call (runs/accept-event session
                                      {:kind :tool-call
                                       :attempt-id "attempt-1"
                                       :tool-call-id "tool-1"
                                       :name "read"
                                       :arguments {:path "x"}}
                                      4000)
        duplicate (runs/accept-event (:session first-call)
                                     {:kind :tool-call
                                      :attempt-id "attempt-1"
                                      :tool-call-id "tool-1"
                                      :name "read"
                                      :arguments {:path "x"}}
                                     4001)
        conflict (runs/accept-event (:session first-call)
                                    {:kind :tool-call
                                     :attempt-id "attempt-1"
                                     :tool-call-id "tool-1"
                                     :name "read"
                                     :arguments {:path "y"}}
                                    4002)]
    (is (:ok? first-call))
    (is (= :accept-tool-request (get-in first-call [:effects 0 :kind])))
    (is (= :duplicate-tool-call (:ignored duplicate)))
    (is (not (:ok? conflict)))
    (is (= :TOOL_CALL_PAYLOAD_CONFLICT (get-in conflict [:error :code])))))

(deftest cancel-run-keeps-calls-and-emits-cancellation
  (let [session (claimed (session-with-run))
        cancelled (runs/cancel-run session
                                   {:run-id "run-1"
                                    :reason :user-cancel
                                    :now 4000})
        call (get-in (:session cancelled) [:calls "call-1"])]
    (is (:ok? cancelled))
    (is (= :cancelled (get-in (:session cancelled) [:runs "run-1" :state])))
    (is (true? (:cancel-requested? call)))
    (is (= :user-cancel (:cancel-reason call)))
    (is (= :dispatching (:state call)))
    (is (= :cancel-attempt (get-in cancelled [:effects 0 :kind])))
    (let [late (runs/accept-event (:session cancelled)
                                  {:kind :completed
                                   :attempt-id "attempt-1"
                                   :content "late"}
                                  5000)]
      (is (:late? late))
      (is (= :succeeded (get-in (:session late) [:calls "call-1" :state])))
      (is (= :cancelled (get-in (:session late) [:runs "run-1" :state]))))))

(deftest a-cancelled-run-allows-a-new-run
  (let [session (claimed (session-with-run))
        cancelled (:session (runs/cancel-run session
                                             {:run-id "run-1"
                                              :reason :input-changed
                                              :now 4000}))
        next-run (runs/start-run cancelled
                                 {:run-id "run-2"
                                  :call-id "call-2"
                                  :model-binding (:model-binding cancelled)
                                  :message-ids []
                                  :language :en
                                  :now 5000})]
    (is (:ok? next-run))
    (is (= "run-2" (:current-run-id (:session next-run))))
    (is (= :running (get-in (:session next-run) [:runs "run-2" :state])))))

(deftest run-invariants-hold-across-the-lifecycle
  (let [session (claimed (session-with-run))
        chunked (:session (runs/accept-event session
                                             {:kind :chunk
                                              :attempt-id "attempt-1"
                                              :text "x"}
                                             4000))
        terminal (:session (runs/accept-event chunked
                                              {:kind :completed
                                               :attempt-id "attempt-1"
                                               :content "x"}
                                              5000))]
    (is (empty? (runs/check-invariants session)))
    (is (empty? (runs/check-invariants chunked)))
    (is (empty? (runs/check-invariants terminal)))))

(deftest a-cancelled-run-refuses-tool-calls-and-new-attempts
  (let [session (claimed (session-with-run))
        cancelled (:session (runs/cancel-run session
                                             {:run-id "run-1"
                                              :reason :user-cancel
                                              :now 4000}))
        tool-call (runs/accept-event cancelled
                                     {:kind :tool-call
                                      :attempt-id "attempt-1"
                                      :tool-call-id "tool-1"
                                      :name "read"
                                      :arguments {:path "x"}}
                                     5000)
        retry (runs/start-attempt cancelled
                                   {:call-id "call-1"
                                    :attempt-id "attempt-2"
                                    :now 6000})]
    (is (:ok? tool-call))
    (is (= :call-cancelled (:ignored tool-call)))
    (is (empty? (:effects tool-call)))
    (is (not (:ok? retry)))
    (is (= :RUN_CANCELLED (get-in retry [:error :code])))))

(defspec terminal-events-never-reopen-a-call
  50
  (prop/for-all [first-kind (gen/elements [:completed :failed :cancelled])]
    (let [session (claimed (session-with-run))
          first (runs/accept-event session
                                   {:kind first-kind
                                    :attempt-id "attempt-1"
                                    :content "first"}
                                   4000)
          second (runs/accept-event (:session first)
                                    {:kind :completed
                                     :attempt-id "attempt-1"
                                     :content "second"}
                                    5000)]
      (and (:ok? first)
           (:ok? second)
           (= (get-in first [:session :calls "call-1" :state])
              (get-in second [:session :calls "call-1" :state]))
           (= (get-in first [:session :attempts "attempt-1" :state])
              (get-in second [:session :attempts "attempt-1" :state]))))))
