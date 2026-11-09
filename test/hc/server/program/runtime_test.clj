(ns hc.server.program.runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.program.runtime :as program]))

(def long-intent (apply str (repeat 200 "x")))
(def short-intent "x")

(defn- make-runtime
  ([] (make-runtime {}))
  ([opts] (program/runtime (merge {:max-source-bytes 100000} opts))))

(defn- exec!
  ([rt source] (exec! rt source {}))
  ([rt source opts]
   (program/evaluate-program! rt
                              (merge {:call-id (str (random-uuid))
                                      :role :clj-worker
                                      :intent long-intent
                                      :source source}
                                     opts))))

(deftest completes-a-pure-program
  (let [rt (make-runtime)]
    (try
      (let [result (exec! rt "(defprogram add []\n  (let [a 1 b 2]\n    (+ a b)))")]
        (is (= :COMPLETED (:status result)))
        (is (= :completed (:state result)))
        (is (= 3 (:value result)))
        (is (= "add" (:name result)))
        (is (string? (:numbered-source result)))
        (is (= 200 (:intent-bytes result)))
        (is (= 10000 (:deadline-ms result))))
      (testing "for, case, whereAll, and a map value"
        (let [result (exec! rt "(defprogram shapes []
                                {:squares (for [i (range 3)] (* i i))
                                 :case (case 2 1 :one 2 :two :other)
                                 :spans (whereAll {:content \"a a\" :needle \"a\"})})")]
          (is (= :COMPLETED (:status result)))
          (is (= {:squares [0 1 4] :case :two :spans [[0 1] [2 3]]}
                 (:value result)))))
      (finally (program/stop! rt)))))

(deftest explicit-park-and-unpark
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram p [] (park {:payload {:x 1}}))"
                         {:call-id "park-1"})]
        (is (= :PARKED (:status parked)))
        (is (= :parked (:state parked)))
        (is (= :explicit (:reason parked)))
        (is (= {:x 1} (:payload parked)))
        (is (= [] (:pending-operations parked)))
        (is (= "" (:delta parked)))
        (is (nil? (:command parked)))
        (is (number? (:park-id parked)))
        ;; §4.3.4: remaining budget at the park instant, captured before the
        ;; deadline is canceled.
        (is (pos? (:remaining-wait-ms parked)))
        (is (<= (:remaining-wait-ms parked) (:deadline-ms parked)))
        ;; §4.3.4: `:source` is the line-numbered program text; `:raw-source`
        ;; keeps the submitted bytes.
        (is (str/starts-with? (:source parked) "1 | "))
        (is (= (:source parked) (:numbered-source parked)))
        (is (str/starts-with? (:raw-source parked) "(defprogram"))
        (is (string? (:numbered-source parked))))
      (let [resumed (program/unpark! rt {:call-id "unpark-1"
                                         :name "p"
                                         :value 42
                                         :intent long-intent})]
        (is (= :COMPLETED (:status resumed)))
        (is (= 42 (:value resumed))))
      (finally (program/stop! rt)))))

(deftest where-ambiguity-parks-and-resumes
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram w []
                              (let [c \"ab ab\"]
                                (let [[b e] (where {:content c :needle \"ab\"})]
                                  (subs c b e))))")]
        (is (= :PARKED (:status parked)))
        (is (= :where-ambiguous (:reason parked)))
        (is (= 2 (count (get-in parked [:payload :candidates]))))
        (is (= [0 2] (get-in parked [:payload :candidates 0 :span]))))
      (let [resumed (program/unpark! rt {:call-id "where-u"
                                         :name "w"
                                         :value [0 2]
                                         :intent long-intent})]
        (is (= :COMPLETED (:status resumed)))
        (is (= "ab" (:value resumed))))
      (finally (program/stop! rt)))))

(deftest where-zero-match-carries-nearest
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram w [] (where {:content \"hello world\" :needle \"hellp\"}))")]
        (is (= :PARKED (:status parked)))
        (is (= :where-ambiguous (:reason parked)))
        (is (= [] (get-in parked [:payload :candidates])))
        (is (map? (get-in parked [:payload :nearest]))))
      (finally (program/stop! rt)))))

(deftest ask-is-s1-only
  (let [rt (make-runtime)]
    (try
      (let [refused (exec! rt "(defprogram ask-bad [] (ask {:question \"?\"}))"
                          {:role :clj-plan})]
        (is (= :ASK_FORBIDDEN_OUTSIDE_S1 (:status refused))))
      (let [parked (exec! rt "(defprogram ask-good [] (ask {:question \"why?\"}))"
                         {:role :clj-plan :s1? true})]
        (is (= :PARKED (:status parked)))
        (is (= :ask (:reason parked)))
        (is (true? (:interrupt parked)))
        (is (= {:question "why?"} (:payload parked))))
      (let [resumed (program/unpark! rt {:call-id "ask-u"
                                         :name "ask-good"
                                         :role :clj-plan
                                         :value "because"
                                         :intent long-intent})]
        (is (= :COMPLETED (:status resumed)))
        (is (= "because" (:value resumed))))
      (finally (program/stop! rt)))))

(deftest deadline-parks-a-loop-and-resumes
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram d [] (loop [i 0] (if (< i 1000000) (recur (inc i)) i)))"
                         {:call-id "deadline-1" :intent short-intent})]
        (is (= :PARKED (:status parked)))
        (is (= :deadline (:reason parked)))
        (is (= 1 (get-in parked [:payload :intent-bytes]))))
      (let [resumed (program/unpark! rt {:call-id "deadline-u"
                                         :name "d"
                                         :value nil
                                         :intent (apply str (repeat 1000 "x"))})]
        (is (= :COMPLETED (:status resumed)))
        (is (= 1000000 (:value resumed))))
      (finally (program/stop! rt)))))

(deftest name-occupancy-and-reuse
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram same [] (park {}))" {:call-id "same-1"})]
        (is (= :PARKED (:status parked)))
        (let [taken (exec! rt "(defprogram same [] 2)" {:call-id "same-2"})]
          (is (= :NAME_TAKEN (:status taken)))
          (is (= "same" (:name taken))))
        (is (= :CANCELLED (:status (program/cancel! rt "same"))))
        (let [reused (exec! rt "(defprogram same [] 3)" {:call-id "same-3"})]
          (is (= :COMPLETED (:status reused)))
          (is (= 3 (:value reused)))))
      (finally (program/stop! rt)))))

(deftest call-id-replay-and-mismatch
  (let [rt (make-runtime)]
    (try
      (let [source "(defprogram replay [] (park {:payload 1}))"
            first (exec! rt source {:call-id "call-replay"})
            second (exec! rt source {:call-id "call-replay"})]
        (is (= :PARKED (:status first)))
        (is (= first second))
        (let [mismatch (exec! rt "(defprogram replay [] (park {:payload 2}))"
                             {:call-id "call-replay"})]
          (is (= :CALL_REPLAY_MISMATCH (:status mismatch)))))
      (let [resumed (program/unpark! rt {:call-id "replay-u"
                                         :name "replay"
                                         :value 7
                                         :intent long-intent})
            replay (exec! rt "(defprogram replay [] (park {:payload 1}))"
                         {:call-id "call-replay"})]
        (is (= :COMPLETED (:status resumed)))
        (is (= 7 (:value resumed)))
        (testing "the old physical call still replays its original parked receipt"
          (is (= :PARKED (:status replay)))))
      (finally (program/stop! rt)))))

(deftest unpark-four-checks
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram target [] (park {}))" {:call-id "target-1"})]
        (is (= :PARKED (:status parked)))
        (testing "observed-park-id mismatch is stale control and does not resume"
          (let [stale (program/unpark! rt {:call-id "u-bad"
                                           :name "target"
                                           :value 1
                                           :observed-park-id (inc (:park-id parked))
                                           :intent long-intent})]
            (is (= :STALE_CONTROL (:status stale)))
            (is (= :parked (:state (program/program-state rt "target"))))))
        (testing "unknown target"
          (is (= :PROGRAM_UNKNOWN
                 (:status (program/unpark! rt {:call-id "u-unknown"
                                               :name "nope"
                                               :value 1
                                               :intent long-intent})))))
        (testing "self-reference is refused"
          (let [result (exec! rt "(defprogram self [] (unpark {:name \"self\" :value 1}))"
                             {:call-id "self-1"})]
            (is (= :COMPLETED (:status result)))
            (is (= :INVALID_ARGUMENT (get-in result [:value :status])))))
        (testing "capability narrowing removes unpark"
          (is (= :CAPABILITY_DENIED
                 (:status (program/unpark! rt {:call-id "u-denied"
                                               :name "target"
                                               :value 1
                                               :capabilities #{}
                                               :intent long-intent})))))
        (testing "double resume is stale"
          (let [ok (program/unpark! rt {:call-id "u-ok"
                                        :name "target"
                                        :value 1
                                        :intent long-intent})
                again (program/unpark! rt {:call-id "u-again"
                                           :name "target"
                                           :value 2
                                           :intent long-intent})]
            (is (= :COMPLETED (:status ok)))
            (is (= :STALE_CONTROL (:status again))))))
      (finally (program/stop! rt)))))

(deftest terminal-callback-and-cancel
  (let [seen (atom [])
        rt (make-runtime {:on-terminal #(swap! seen conj (:status %))})]
    (try
      (let [parked (exec! rt "(defprogram p [] (park {}))"
                         {:call-id "p-1"
                          :on-terminal (fn [result]
                                         (swap! seen conj (:status result)))})]
        (is (= :PARKED (:status parked)))
        (is (empty? @seen))
        (program/unpark! rt {:call-id "p-u" :name "p" :value 1 :intent long-intent})
        (Thread/sleep 50)
        (is (some #{:COMPLETED} @seen)))
      (let [parked (exec! rt "(defprogram c [] (park {}))" {:call-id "c-1"})]
        (is (= :PARKED (:status parked)))
        (let [cancelled (program/cancel! rt "c")]
          (is (= :CANCELLED (:status cancelled)))
          (is (= :cancelled (:state cancelled)))
          (is (= :STALE_CONTROL
                 (:status (program/unpark! rt {:call-id "c-u"
                                               :name "c"
                                               :value 1
                                               :intent long-intent}))))))
      (finally (program/stop! rt)))))

(deftest stop-refuses-new-work
  (let [rt (make-runtime)]
    (let [parked (exec! rt "(defprogram p [] (park {}))" {:call-id "p-1"})]
      (is (= :PARKED (:status parked))))
    (let [stopped (program/stop! rt)]
      (is (= :STOPPED (:status stopped)))
      ;; `:active` is captured before the cancels; a parked program appears
      ;; there with its pending operations.
      (is (= ["p"] (mapv :name (:active stopped))))
      (is (= [] (:pending-operations (first (:active stopped))))))
    (is (= :STOPPED (:status (exec! rt "(defprogram q [] 1)"))))))

(deftest invalid-requests
  (let [rt (make-runtime)]
    (try
      (is (= :INVALID_ARGUMENT (:status (exec! rt "(defprogram x [] 1)" {:call-id ""}))))
      (is (= :INVALID_ARGUMENT (:status (exec! rt "(defprogram x [] 1)" {:role :nope}))))
      (is (= :INVALID_ARGUMENT (:status (exec! rt "(defprogram x [] 1)" {:intent nil}))))
      (is (= :CAPABILITY_DENIED (:status (exec! rt "(defprogram x [] (eval 1))"))))
      (finally (program/stop! rt)))))
(deftest shell-is-an-alias-for-the-call-port
  (let [rt (make-runtime {:shell (fn [ctx request]
                                    {:status 0
                                     :ctx ctx
                                     :command (:command request)})})]
    (try
      (let [result (exec! rt "(defprogram sh [] (call {:command \"echo hi\"}))"
                          {:holder? true})]
        (is (= :COMPLETED (:status result)))
        (is (= 0 (get-in result [:value :status])))
        (is (= "echo hi" (get-in result [:value :command]))))
      (finally (program/stop! rt)))))
(deftest plan-read-surface-requires-context
  (let [rt (make-runtime)]
    (try
      (let [denied (exec! rt "(defprogram p [] (cat {:path \"plans/x.md\"}))"
                          {:role :clj-plan})]
        (is (= :CAPABILITY_DENIED (:status denied))))
      (finally (program/stop! rt)))))

(deftest call-role-surface
  (let [rt (make-runtime {:shell (fn [_ctx _request]
                                   {:status 0 :stdout "ok"})})]
    (try
      (testing "a normal worker cannot start a shell"
        (is (= :TOKEN_REQUIRED
               (:status (exec! rt "(defprogram w [] (call {:command \"x\"}))"
                                {:holder? false})))))
      (testing "the holder can start a shell"
        (is (= :COMPLETED
               (:status (exec! rt "(defprogram w [] (call {:command \"x\"}))"
                                {:holder? true})))))
      (testing "a dedicated worker can start a shell without a token"
        (is (= :COMPLETED
               (:status (exec! rt "(defprogram d [] (call {:command \"x\"}))"
                                {:role :dedicated-worker
                                 :holder? false})))))
      (finally (program/stop! rt)))))

(deftest call-accepts-the-documented-shell-command-forms
  (let [seen (atom [])
        rt (make-runtime {:shell (fn [_ctx request]
                                   (swap! seen conj (:command request))
                                   {:status 0 :command (:command request)})})]
    (try
      (let [vector-result
            (exec! rt "(defprogram vector-call []
                        (call {:command [\"sh\" \"-c\" \"echo hi\"]}))"
                   {:holder? true})
            map-result
            (exec! rt "(defprogram map-call []
                        (call {:command {:program \"sh\"
                                         :args [\"-c\" \"echo hi\"]}}))"
                   {:holder? true})]
        (is (= :COMPLETED (:status vector-result)) (pr-str vector-result))
        (is (= ["sh" "-c" "echo hi"]
               (get-in vector-result [:value :command])))
        (is (= :COMPLETED (:status map-result)) (pr-str map-result))
        (is (= {:program "sh" :args ["-c" "echo hi"]}
               (get-in map-result [:value :command])))
        (is (= [["sh" "-c" "echo hi"]
                {:program "sh" :args ["-c" "echo hi"]}]
               @seen))
        (is (= :INVALID_ARGUMENT
               (:status (exec! rt "(defprogram bad-call []
                                    (call {:command []}))"
                                {:holder? true})))))
      (finally (program/stop! rt)))))

(deftest every-capability-call-checks-the-deadline
  (let [clock (atom 0)
        cats (atom 0)
        workspace {:open-session (fn [_] :session)
                   :cat (fn [_ _] (swap! cats inc) "unexpected")
                   :stat (fn [_ _] {})
                   :ls (fn [_ _] [])
                   :write (fn [_ _ _] {:status :STAGED})
                   :mkdir (fn [_ _] {:status :STAGED})
                   :rm (fn [_ _] {:status :STAGED})
                   :mv (fn [_ _ _] {:status :STAGED})
                   :facts (fn [_] {:ops [] :read-set {} :checks {}})
                   :settle! (fn [_ _]
                              {:status :APPLIED
                               :session :session
                               :result {:status :APPLIED}})}
        control {:execute! (fn [_ctx _request]
                             (swap! clock + 1000)
                             {:status :OK})}
        rt (make-runtime {:workspace workspace
                          :control control
                          :clock (fn [] @clock)
                          ;; No timer: the test advances the clock and
                          ;; proves the capability-call checkpoint itself.
                          :schedule (fn [_ _] (fn [] nil))})]
    (try
      (let [result (exec! rt "(defprogram deadline-check []
                                (split {:parent-id \"p\" :children []})
                                (cat {:path \"a.txt\"}))"
                          {:holder? true :intent "x"})]
        (is (= :PARKED (:status result)) (pr-str result))
        (is (= :deadline (:reason result)))
        (is (zero? @cats)
            "the second capability must not run after the deadline"))
      (finally (program/stop! rt)))))

(deftest call-after-a-drain-keeps-the-intent-deadline
  (let [release (promise)
        clock (atom 0)
        timer (atom nil)
        session (atom {:ops [] :read-set {} :checks {}})
        workspace {:open-session (fn [_] session)
                   :cat (fn [_ _] "unexpected")
                   :stat (fn [_ _] {})
                   :ls (fn [_ _] [])
                   :write (fn [s path content]
                            (swap! s update :ops conj
                                   {:op :write :path path :content content})
                            {:status :STAGED :path path})
                   :mkdir (fn [_ _] {:status :STAGED})
                   :rm (fn [_ _] {:status :STAGED})
                   :mv (fn [_ _ _] {:status :STAGED})
                   :facts (fn [s] @s)
                   :settle! (fn [s _]
                              ;; Simulate time spent in the protected drain.
                              (swap! clock + 10)
                              (reset! s {:ops [] :read-set {} :checks {}})
                              {:status :APPLIED
                               :session s
                               :result {:status :APPLIED :op-count 1}})}
        rt (make-runtime {:workspace workspace
                          :shell (fn [_ _]
                                   ;; Fire the timer installed by the
                                   ;; post-drain re-arm.  If the drain left
                                   ;; the deadline canceled, the old timer's
                                   ;; generation is stale and nothing parks.
                                   (when-let [f @timer] (f))
                                   @release
                                   {:status 0 :stdout ""})
                          :clock (fn [] @clock)
                          :schedule (fn [_ f]
                                      (reset! timer f)
                                      (fn [] nil))})
        result-promise (promise)
        thread (Thread.
                #(deliver result-promise
                          (exec! rt "(defprogram drain-then-call []
                                      (write {:path \"a.txt\" :content \"x\"})
                                      (call {:command \"slow\"}))"
                                 {:holder? true :intent "x"})))]
    (try
      (.setDaemon thread true)
      (.start thread)
      (let [result (deref result-promise 2000 ::timeout)]
        (is (not= ::timeout result)
            "the deadline must survive the pre-call drain")
        (is (= :PARKED (:status result)) (pr-str result))
        (is (= :deadline (:reason result))))
      (finally
        (deliver release {:status 0 :stdout ""})
        (program/stop! rt)))))
(deftest park-receipt-delta-is-consumed
  (let [rt (make-runtime {:shell (fn [_ctx _request]
                                    {:status 0 :stdout "chunk" :delta "chunk"})})]
    (try
      (let [first-park (exec! rt "(defprogram d []
                                    (call {:command \"one\"})
                                    (park {:payload 1})
                                    (park {:payload 2}))"
                               {:holder? true})]
        (is (= :PARKED (:status first-park)))
        (is (= "chunk" (:delta first-park))))
      (let [second-park (program/unpark! rt {:call-id "delta-u"
                                             :name "d"
                                             :value nil
                                             :intent long-intent})]
        (is (= :PARKED (:status second-park)))
        (is (= "" (:delta second-park))))
      (finally (program/stop! rt)))))
(deftest finish-does-not-drain-the-batch
  (let [session (atom {:ops [] :read-set {} :checks {}})
        events (atom [])
        workspace {:open-session (fn [_ctx] session)
                   :cat (fn [_ _] (throw (ex-info "unused" {})))
                   :stat (fn [_ _] (throw (ex-info "unused" {})))
                   :ls (fn [_ _] (throw (ex-info "unused" {})))
                   :write (fn [s path content]
                            (swap! s update :ops conj {:op :write :path path :content content})
                            {:status :STAGED :path path})
                   :mkdir (fn [_ _] (throw (ex-info "unused" {})))
                   :rm (fn [_ _] (throw (ex-info "unused" {})))
                   :mv (fn [_ _ _] (throw (ex-info "unused" {})))
                   :facts (fn [s] @s)
                   :settle! (fn [s request]
                              (swap! events conj {:kind :settle
                                                  :ops (:ops @s)
                                                  :request request})
                              (reset! s {:ops [] :read-set {} :checks {}})
                              {:status :APPLIED :session s
                               :result {:status :APPLIED :op-count (count (:ops @s))}})}
        control {:execute! (fn [_ctx {:keys [primitive]}]
                             (swap! events conj {:kind :control
                                                 :primitive primitive
                                                 :ops (:ops @session)
                                                 :settled? (boolean (some #(= :settle (:kind %)) @events))})
                             {:status :OK})}
        rt (make-runtime {:workspace workspace :control control})]
    (try
      (let [result (exec! rt "(defprogram ordered []
                                (write {:path \"a.txt\" :content \"x\"})
                                (finish {:task-id \"t\" :claim-id \"c\" :result-summary \"done\"}))")]
        (is (= :COMPLETED (:status result)))
        (is (= :APPLIED (get-in result [:commit :status])))
        (is (= :finish (get-in result [:control-results 0 :primitive])))
        (is (= 1 (count (:commits result))))
        (is (= :settling (get-in result [:commits 0 :phase])))
        (let [[finish-event settle-event] @events]
          (is (= :control (:kind finish-event)))
          (is (= :finish (:primitive finish-event)))
          (is (= 1 (count (:ops finish-event))))
          (is (false? (:settled? finish-event)))
          (is (= :settle (:kind settle-event)))
          (is (= 1 (count (:ops settle-event))))))
      (finally (program/stop! rt)))))
(deftest where-unicode-spans-resume-correctly
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram emoji []
                                (let [c \"😀x😀\"]
                                  (let [[b e] (where {:content c :needle \"😀\"})]
                                    (subs c b e))))")]
        (is (= :PARKED (:status parked)))
        (is (= [[0 2] [3 5]]
               (mapv :span (get-in parked [:payload :candidates]))))
        (let [resumed (program/unpark! rt {:call-id "emoji-u"
                                           :name "emoji"
                                           :value [3 5]
                                           :intent long-intent})]
          (is (= :COMPLETED (:status resumed)))
          (is (= "😀" (:value resumed)))))
      (finally (program/stop! rt)))))

(deftest where-nearest-span-resumes-correctly
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram near []
                                (let [c \"alpha\\nbeta\"]
                                  (let [[b e] (where {:content c :needle \"bata\"})]
                                    (subs c b e))))")]
        (is (= :PARKED (:status parked)))
        (is (= :where-ambiguous (:reason parked)))
        (is (= [6 10] (get-in parked [:payload :nearest :span])))
        (let [resumed (program/unpark! rt {:call-id "near-u"
                                           :name "near"
                                           :value (get-in parked [:payload :nearest :span])
                                           :intent long-intent})]
          (is (= :COMPLETED (:status resumed)))
          (is (= "beta" (:value resumed)))))
      (finally (program/stop! rt)))))
(deftest finish-success-survives-a-claim-changed-settle
  (let [session (atom {:ops [] :read-set {} :checks {}})
        workspace {:open-session (fn [_ctx] session)
                   :cat (fn [_ _] (throw (ex-info "unused" {})))
                   :stat (fn [_ _] (throw (ex-info "unused" {})))
                   :ls (fn [_ _] (throw (ex-info "unused" {})))
                   :write (fn [s path content]
                            (swap! s update :ops conj {:op :write :path path :content content})
                            {:status :STAGED :path path})
                   :mkdir (fn [_ _] (throw (ex-info "unused" {})))
                   :rm (fn [_ _] (throw (ex-info "unused" {})))
                   :mv (fn [_ _ _] (throw (ex-info "unused" {})))
                   :facts (fn [s] @s)
                   :settle! (fn [_s _request]
                              {:status :CLAIM_CHANGED
                               :session session
                               :result {:status :CLAIM_CHANGED
                                        :error :CLAIM_CHANGED
                                        :message "Claim was released by finish"}})}
        control {:execute! (fn [_ctx _req] {:status :OK})}
        rt (make-runtime {:workspace workspace :control control})]
    (try
      (let [result (exec! rt "(defprogram ordered []
                                (write {:path \"a.txt\" :content \"x\"})
                                (finish {:task-id \"t\" :claim-id \"c\" :result-summary \"done\"}))")]
        (is (= :CLAIM_CHANGED (:status result)))
        (is (= :failed (:state result)))
        (is (= :finish (get-in result [:control-results 0 :primitive])))
        (is (= :OK (get-in result [:control-results 0 :result :status])))
        (is (= :CLAIM_CHANGED (get-in result [:commit :status])))
        (is (empty? (:commits result))))
      (finally (program/stop! rt)))))
(deftest cancel-by-attempt-id-and-active-sweep
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram p [] (park {}))"
                          {:call-id "call-x" :attempt-id "att-x"})]
        (is (= :PARKED (:status parked)))
        (is (= "att-x" (:attempt-id parked)))
        (is (= [{:name "p" :call-id "call-x" :attempt-id "att-x" :state :parked}]
               (program/active-programs rt)))
        (is (= :parked (:state (program/program-state rt "att-x"))))
        (let [cancelled (program/cancel! rt "att-x")]
          (is (= :CANCELLED (:status cancelled)))
          (is (= :cancelled (:state cancelled)))
          (is (= "call-x" (:call-id cancelled)))
          (is (= "att-x" (:attempt-id cancelled))))
        (is (empty? (program/active-programs rt)))
        (testing "cancel is idempotent after the terminal state"
          (is (= :CANCELLED (:status (program/cancel! rt "att-x"))))
          (is (= :cancelled (:state (program/program-state rt "att-x"))))))
      (finally (program/stop! rt)))))
(deftest phase-flow-draining-waiting-settling
  (let [rt-ref (atom nil)
        session (atom {:ops [] :read-set {} :checks {}})
        seen (atom [])
        workspace {:open-session (fn [_ctx] session)
                   :cat (fn [_ _] (throw (ex-info "unused" {})))
                   :stat (fn [_ _] (throw (ex-info "unused" {})))
                   :ls (fn [_ _] (throw (ex-info "unused" {})))
                   :write (fn [s path content]
                            (swap! s update :ops conj {:op :write :path path :content content})
                            {:status :STAGED :path path})
                   :mkdir (fn [_ _] (throw (ex-info "unused" {})))
                   :rm (fn [_ _] (throw (ex-info "unused" {})))
                   :mv (fn [_ _ _] (throw (ex-info "unused" {})))
                   :facts (fn [s] @s)
                   :settle! (fn [s _request]
                              (swap! seen conj
                                     {:kind :settle
                                      :state (:state (program/program-state @rt-ref "phases"))})
                              (reset! s {:ops [] :read-set {} :checks {}})
                              {:status :APPLIED :session s
                               :result {:status :APPLIED}})}
        shell (fn [_ctx _request]
                (swap! seen conj
                       {:kind :call
                        :state (:state (program/program-state @rt-ref "phases"))})
                {:status 0})
        rt (make-runtime {:workspace workspace :shell shell})
        _ (reset! rt-ref rt)]
    (try
      (let [result (exec! rt "(defprogram phases []
                                (write {:path \"a\" :content \"1\"})
                                (call {:command \"x\"})
                                (write {:path \"b\" :content \"2\"}))"
                          {:holder? true})]
        (is (= :COMPLETED (:status result)))
        (is (= [:draining :waiting :settling]
               (mapv :state @seen)))
        (is (= [:draining :settling]
               (mapv :phase (:commits result)))))
      (finally (program/stop! rt)))))

(deftest drained-commit-survives-a-shell-failure
  (let [session (atom {:ops [] :read-set {} :checks {}})
        workspace {:open-session (fn [_ctx] session)
                   :cat (fn [_ _] (throw (ex-info "unused" {})))
                   :stat (fn [_ _] (throw (ex-info "unused" {})))
                   :ls (fn [_ _] (throw (ex-info "unused" {})))
                   :write (fn [s path content]
                            (swap! s update :ops conj {:op :write :path path :content content})
                            {:status :STAGED :path path})
                   :mkdir (fn [_ _] (throw (ex-info "unused" {})))
                   :rm (fn [_ _] (throw (ex-info "unused" {})))
                   :mv (fn [_ _ _] (throw (ex-info "unused" {})))
                   :facts (fn [s] @s)
                   :settle! (fn [s _request]
                              (reset! s {:ops [] :read-set {} :checks {}})
                              {:status :APPLIED :session s
                               :result {:status :APPLIED :op-count 1}})}
        shell (fn [_ctx _request]
                (throw (ex-info "shell exploded" {:status :FAILED})))
        rt (make-runtime {:workspace workspace :shell shell})]
    (try
      (let [result (exec! rt "(defprogram drain-then-fail []
                                (write {:path \"a\" :content \"x\"})
                                (call {:command \"boom\"}))"
                          {:holder? true})]
        ;; A shell throw is a command result, not a program crash.  The
        ;; drained file commit still survives in :commits.
        (is (= :COMPLETED (:status result)))
        (is (= :FAILED (get-in result [:value :status])))
        (is (= "shell exploded" (get-in result [:value :message])))
        (is (nil? (:commit result)))
        (is (= 1 (count (:commits result))))
        (is (= :draining (get-in result [:commits 0 :phase])))
        (is (= :APPLIED (get-in result [:commits 0 :result :status]))))
      (finally (program/stop! rt)))))

(deftest late-deadline-after-settle-cancel-does-not-park
  (let [deadline-fn (atom nil)
        session (atom {:ops [] :read-set {} :checks {}})
        workspace {:open-session (fn [_ctx] session)
                   :cat (fn [_ _] (throw (ex-info "unused" {})))
                   :stat (fn [_ _] (throw (ex-info "unused" {})))
                   :ls (fn [_ _] (throw (ex-info "unused" {})))
                   :write (fn [s path content]
                            (swap! s update :ops conj {:op :write :path path :content content})
                            {:status :STAGED :path path})
                   :mkdir (fn [_ _] (throw (ex-info "unused" {})))
                   :rm (fn [_ _] (throw (ex-info "unused" {})))
                   :mv (fn [_ _ _] (throw (ex-info "unused" {})))
                   :facts (fn [s] @s)
                   :settle! (fn [s _request]
                              ;; Simulate a timer that was already captured
                              ;; and fires after the runtime canceled it.
                              (when-let [f @deadline-fn] (f))
                              (reset! s {:ops [] :read-set {} :checks {}})
                              {:status :APPLIED :session s
                               :result {:status :APPLIED}})}
        rt (make-runtime {:workspace workspace
                          :schedule (fn [_delay f]
                                      (reset! deadline-fn f)
                                      (fn [] nil))})]
    (try
      (let [result (exec! rt "(defprogram late [] (write {:path \"a\" :content \"x\"}))")]
        (is (= :COMPLETED (:status result)))
        (is (= :completed (:state result)))
        (is (= 1 (count (:commits result)))))
      (finally (program/stop! rt)))))

(deftest deadline-waiting-receipt-carries-live-command-view
  (let [rt (make-runtime
            {:shell (fn [_ctx request]
                      (let [view! (:on-command-view request)]
                        (view! {:command "slow"
                                :status :running
                                :phase :running
                                :stdout "partial"
                                :stderr ""
                                :stdout-bytes 7
                                :stderr-bytes 0
                                :duration-ms 120})
                        (Thread/sleep 200)
                        {:status 0 :stdout "partial done"}))})]
    (try
      (let [parked (exec! rt "(defprogram live [] (call {:command \"slow\"}) 42)"
                          {:holder? true :intent "x"})]
        (is (= :PARKED (:status parked)))
        (is (= :deadline (:reason parked)))
        (is (= "partial" (get-in parked [:command :stdout])))
        (is (= 7 (get-in parked [:command :stdout-bytes])))
        (is (= 120 (get-in parked [:command :duration-ms])))
        (is (= "partial" (:delta parked))))
      (finally (program/stop! rt)))))

(deftest shell-failure-after-deadline-resumes-as-command-result
  (let [rt (make-runtime
            {:shell (fn [_ctx request]
                      (let [view! (:on-command-view request)]
                        (view! {:command "boom"
                                :status :running
                                :phase :running
                                :stdout "before"
                                :stderr ""
                                :stdout-bytes 6
                                :stderr-bytes 0
                                :duration-ms 80})
                        (Thread/sleep 200)
                        (throw (ex-info "shell exploded" {:status :FAILED}))))})]
    (try
      (let [parked (exec! rt "(defprogram fail-later [] (call {:command \"boom\"}))"
                          {:holder? true :intent "x"})]
        (is (= :PARKED (:status parked)))
        (is (= :deadline (:reason parked)))
        (let [resumed (program/unpark! rt {:call-id "fail-later-u"
                                           :name "fail-later"
                                           :value nil
                                           :intent long-intent})]
          (is (= :COMPLETED (:status resumed)))
          (is (= :FAILED (get-in resumed [:value :status])))
          (is (= "shell exploded"
                 (get-in resumed [:value :message])))))
      (finally (program/stop! rt)))))

(deftest unpark-is-idempotent-by-attempt-id
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram p [] (park {}))"
                          {:call-id "park-idem" :attempt-id "att-idem"})]
        (is (= :PARKED (:status parked)))
        (let [first (program/unpark! rt {:attempt-id "unpark-att"
                                         :name "p"
                                         :value 7
                                         :intent long-intent})
              second (program/unpark! rt {:attempt-id "unpark-att"
                                          :name "p"
                                          :value 7
                                          :intent long-intent})]
          (is (= :COMPLETED (:status first)))
          (is (= 7 (:value first)))
          (is (= first second))))
      (finally (program/stop! rt)))))

(deftest cancel-stops-a-pending-shell-call
  (let [started (promise)
        release (promise)
        stopped (atom nil)
        rt (make-runtime
            {:shell (fn [_ctx request]
                      (deliver started (:operation-id request))
                      @release
                      {:status 0})
             :call-stop! (fn [_ctx request-id]
                           (reset! stopped request-id))})
        run-result (promise)
        thread (doto (Thread. #(deliver run-result
                                        (exec! rt
                                               "(defprogram stop-call []
                                                  (call {:command \"slow\"}))"
                                               {:holder? true})))
                 (.setDaemon true)
                 (.start))]
    (try
      (let [operation-id (deref started 1000 nil)]
        (is (string? operation-id))
        (let [ack (program/cancel! rt "stop-call")]
          (is (= :CANCELLING (:status ack)))
          (is (true? (:cancel-pending? ack)))
          (is (= operation-id @stopped))
          (is (= [operation-id] (:pending-operations ack))))
        (testing "the terminal is not fixed while the shell is still running"
          (is (nil? (deref run-result 100 nil))))
        (deliver release {:status :CANCELLED})
        (let [terminal (deref run-result 1000 nil)]
          (is (= :CANCELLED (:status terminal)))
          (is (= :cancelled (:state terminal)))
          (is (= :CANCELLED (get-in terminal [:command :status])))
          (is (empty? (:pending-operations terminal)))))
      (finally
        (deliver release {:status :CANCELLED})
        (when (.isAlive thread) (.interrupt thread))
        (program/stop! rt)))))

(deftest cancellation-wins-before-the-terminal-is-fixed
  ;; A shell that returns while `:call-stop!` is still running is the real
  ;; observation.  The program settles cancelled from its own checkpoint;
  ;; `cancel!` then returns that fixed terminal instead of inventing one.
  (let [started (promise)
        shell-release (promise)
        stop-entered (promise)
        stop-release (promise)
        rt (make-runtime
            {:shell (fn [_ctx request]
                      (deliver started (:operation-id request))
                      @shell-release
                      {:status 0})
             :call-stop! (fn [_ctx _request-id]
                           (deliver shell-release true)
                           (deliver stop-entered true)
                           @stop-release)})
        run-result (promise)
        cancel-result (promise)
        program-thread (doto (Thread. #(deliver run-result
                                                 (exec! rt
                                                        "(defprogram stop-race []
                                                           (call {:command \"slow\"}))"
                                                        {:holder? true})))
                         (.setDaemon true)
                         (.start))
        cancel-thread (doto (Thread. #(deliver cancel-result
                                               (program/cancel! rt "stop-race")))
                        (.setDaemon true))]
    (try
      (is (string? (deref started 1000 nil)))
      (.start cancel-thread)
      (is (true? (deref stop-entered 1000 nil)))
      (testing "the real call return settles the program before cancel! returns"
        (let [terminal (deref run-result 1000 nil)]
          (is (= :CANCELLED (:status terminal)))
          (is (= :CANCELLED (get-in terminal [:command :status])))
          (is (empty? (:pending-operations terminal)))))
      (deliver stop-release true)
      (is (= :CANCELLED (:status (deref cancel-result 1000 nil))))
      (finally
        (deliver stop-release true)
        (deliver shell-release true)
        (when (.isAlive program-thread) (.interrupt program-thread))
        (when (.isAlive cancel-thread) (.interrupt cancel-thread))
        (program/stop! rt)))))

(deftest cancel-with-an-unconfirmed-call-keeps-responsibility
  (let [started (promise)
        release (promise)
        stopped (atom nil)
        rt (make-runtime
            {:shell (fn [_ctx request]
                      (deliver started (:operation-id request))
                      @release
                      {:status :UNCERTAIN :outcome :unknown})
             :call-stop! (fn [_ctx request-id]
                           (reset! stopped request-id))})
        run-result (promise)
        thread (doto (Thread. #(deliver run-result
                                        (exec! rt
                                               "(defprogram unconfirmed []
                                                  (call {:command \"stubborn\"}))"
                                               {:holder? true})))
                 (.setDaemon true)
                 (.start))]
    (try
      (let [operation-id (deref started 1000 nil)]
        (is (string? operation-id))
        (let [ack (program/cancel! rt "unconfirmed")]
          (is (= :CANCELLING (:status ack)))
          (is (= [operation-id] (:pending-operations ack)))
          (is (= operation-id @stopped)))
        (is (nil? (deref run-result 100 nil)))
        (deliver release {:status :UNCERTAIN :outcome :unknown})
        (let [terminal (deref run-result 1000 nil)]
          (is (= :OUTCOME_UNKNOWN (:status terminal)))
          (is (= :cancelled (:state terminal)))
          (is (true? (:cancel-unconfirmed? terminal)))
          (is (= [operation-id] (:pending-operations terminal)))
          (is (= :UNCERTAIN (get-in terminal [:command :status])))
          (is (= :UNCERTAIN (get-in terminal [:error :status])))))
      (finally
        (deliver release {:status :UNCERTAIN})
        (when (.isAlive thread) (.interrupt thread))
        (program/stop! rt)))))

(deftest cancel-does-not-rewrite-a-real-call-failure
  (let [started (promise)
        release (promise)
        rt (make-runtime
            {:shell (fn [_ctx request]
                      (deliver started (:operation-id request))
                      @release
                      {:status :FAILED
                       :error {:status :FAILED
                               :message "shell failed"}})
             :call-stop! (fn [_ctx _request-id] nil)})
        run-result (promise)
        thread (doto (Thread. #(deliver run-result
                                        (exec! rt
                                               "(defprogram failed-call []
                                                  (call {:command \"boom\"}))"
                                               {:holder? true})))
                 (.setDaemon true)
                 (.start))]
    (try
      (is (string? (deref started 1000 nil)))
      (is (= :CANCELLING (:status (program/cancel! rt "failed-call"))))
      (deliver release true)
      (let [terminal (deref run-result 1000 nil)]
        (is (= :FAILED (:status terminal)))
        (is (= :failed (:state terminal)))
        (is (true? (:cancel-requested? terminal)))
        (is (= "shell failed" (get-in terminal [:error :message])))
        (is (= :FAILED (get-in terminal [:command :status]))))
      (finally
        (deliver release true)
        (when (.isAlive thread) (.interrupt thread))
        (program/stop! rt)))))

(deftest unrelated-exceptioninfo-is-not-masked-by-cancellation
  (let [settle-entered (promise)
        release (promise)
        session (atom {:ops [] :read-set {} :checks {}})
        workspace {:open-session (fn [_ctx] session)
                   :cat (fn [_ _] (throw (ex-info "unused" {})))
                   :stat (fn [_ _] (throw (ex-info "unused" {})))
                   :ls (fn [_ _] (throw (ex-info "unused" {})))
                   :write (fn [s path content]
                            (swap! s update :ops conj
                                   {:op :write :path path :content content})
                            {:status :STAGED :path path})
                   :mkdir (fn [_ _] (throw (ex-info "unused" {})))
                   :rm (fn [_ _] (throw (ex-info "unused" {})))
                   :mv (fn [_ _ _] (throw (ex-info "unused" {})))
                   :facts (fn [s] @s)
                   :settle! (fn [_s _request]
                              (deliver settle-entered true)
                              @release
                              (throw (ex-info "disk exploded"
                                              {:status :FAILED})))}
        rt (make-runtime {:workspace workspace})
        run-result (promise)
        thread (doto (Thread. #(deliver run-result
                                        (exec! rt
                                               "(defprogram fail-commit []
                                                  (write {:path \"a.txt\"
                                                          :content \"x\"}))")))
                 (.setDaemon true)
                 (.start))]
    (try
      (is (true? (deref settle-entered 1000 nil)))
      (let [ack (program/cancel! rt "fail-commit")]
        (is (= :CANCELLED (:status ack)))
        (is (true? (:cancel-after-apply? ack))))
      (deliver release true)
      (let [terminal (deref run-result 1000 nil)]
        (is (= :FAILED (:status terminal)))
        (is (= :failed (:state terminal)))
        (is (= "disk exploded" (get-in terminal [:error :message]))))
      (finally
        (deliver release true)
        (when (.isAlive thread) (.interrupt thread))
        (program/stop! rt)))))

(deftest control-and-session-context-carry-tenure-lane-workspace
  (let [opened (atom nil)
        controlled (atom nil)
        workspace {:open-session (fn [ctx]
                                   (reset! opened ctx)
                                   nil)}
        control {:execute! (fn [ctx _request]
                             (reset! controlled ctx)
                             {:status :OK})}
        rt (make-runtime {:workspace workspace :control control})]
    (try
      (let [result (exec! rt "(defprogram ctx [] (split {:parent-id \"p\" :children []}))"
                          {:tenure-id "tenure-1"
                           :lane :swarm
                           :workspace "/ws"})]
        (is (= :COMPLETED (:status result)))
        (is (= "tenure-1" (:tenure-id @opened)))
        (is (= :swarm (:lane @opened)))
        (is (= "/ws" (:workspace @opened)))
        (is (= "tenure-1" (:tenure-id @controlled)))
        (is (= :swarm (:lane @controlled)))
        (is (= "/ws" (:workspace @controlled))))
      (finally (program/stop! rt)))))

(deftest call-id-replay-includes-tenure-identity
  (let [rt (make-runtime)]
    (try
      (let [first (exec! rt "(defprogram tenure [] 1)"
                         {:call-id "tenure-replay"
                          :tenure-id "tenure-a"
                          :lane :swarm})
            second (exec! rt "(defprogram tenure [] 1)"
                          {:call-id "tenure-replay"
                           :tenure-id "tenure-b"
                           :lane :swarm})]
        (is (= :COMPLETED (:status first)))
        (is (= :CALL_REPLAY_MISMATCH (:status second))))
      (finally (program/stop! rt)))))

(deftest call-id-replay-includes-workspace-identity
  (let [rt (make-runtime)]
    (try
      (let [first (exec! rt "(defprogram workspace-id [] 1)"
                         {:call-id "workspace-replay"
                          :workspace "/ws-a"})
            second (exec! rt "(defprogram workspace-id [] 1)"
                          {:call-id "workspace-replay"
                           :workspace "/ws-b"})]
        (is (= :COMPLETED (:status first)))
        (is (= :CALL_REPLAY_MISMATCH (:status second))))
      (finally (program/stop! rt)))))

(deftest non-scalar-workspace-is-rejected
  (let [rt (make-runtime)]
    (try
      (let [result (exec! rt "(defprogram workspace-id [] 1)"
                          {:call-id "workspace-map"
                           :workspace {:root "/ws"}})]
        (is (= :INVALID_ARGUMENT (:status result))))
      (finally (program/stop! rt)))))

;; ---------------------------------------------------------------------------
;; commit/cancel boundary, control signals, identity and delta regressions

(deftest cancel-defers-while-a-commit-is-pending
  (let [rt (make-runtime)]
    (try
      (let [parked (exec! rt "(defprogram pending [] (park {}))"
                          {:call-id "pending-1"})]
        (is (= :PARKED (:status parked)))
        (let [p (get @(:programs rt) "pending")]
          ;; The window after a protected batch applied and before the
          ;; program thread fixes its terminal.  A clean cancellation here
          ;; would hide the commit.
          (reset! (:settle-pending p) {:phase :settling
                                       :status :applied
                                       :result {:status :APPLIED}})
          (let [ack (program/cancel! rt "pending")]
            (is (= :CANCELLED (:status ack)))
            (is (true? (:cancel-after-apply? ack)))
            (is (= :applied (:settle-status ack)))
            (is (= :parked (:state (program/program-state rt "pending")))))
          ;; The program thread clears the marker when it fixes the terminal;
          ;; a later cancellation is clean again.
          (reset! (:settle-pending p) nil)
          (let [terminal (program/cancel! rt "pending")]
            (is (= :CANCELLED (:status terminal)))
            (is (= :cancelled (:state terminal))))))
      (finally (program/stop! rt)))))

(deftest non-map-request-is-invalid
  (let [rt (make-runtime)]
    (try
      (is (= :INVALID_ARGUMENT
             (:status (program/evaluate-program! rt "not-a-map"))))
      (is (= :INVALID_ARGUMENT
             (:status (program/unpark! rt "not-a-map"))))
      (finally (program/stop! rt)))))

(deftest ambiguous-attempt-id-is-refused
  (let [rt (make-runtime)]
    (try
      (let [a (exec! rt "(defprogram amb-a [] (park {}))"
                     {:call-id "amb-a" :attempt-id "shared-attempt"})
            b (exec! rt "(defprogram amb-b [] (park {}))"
                     {:call-id "amb-b" :attempt-id "shared-attempt"})]
        (is (= :PARKED (:status a)))
        (is (= :PARKED (:status b)))
        (let [ambiguous (program/cancel! rt "shared-attempt")]
          (is (= :AMBIGUOUS_TARGET (:status ambiguous)))
          (is (= 2 (count (:candidates ambiguous)))))
        (is (= :parked (:state (program/program-state rt "amb-a"))))
        (is (= :parked (:state (program/program-state rt "amb-b"))))
        (is (= :CANCELLED (:status (program/cancel! rt "amb-a")))))
      (finally (program/stop! rt)))))

(deftest cancellation-cannot-be-swallowed-by-exception-catch
  (let [started (promise)
        release (promise)
        rt (make-runtime
            {:shell (fn [_ctx _request]
                      (deliver started true)
                      @release
                      {:status :CANCELLED})
             :call-stop! (fn [_ctx _request-id] nil)})]
    (try
      (let [run (future
                  (exec! rt "(defprogram swallow []
                               (try
                                 (call {:command \"slow\"})
                                 (catch clojure.lang.ExceptionInfo e :swallowed)
                                 (catch RuntimeException e :swallowed))
                               (park {:payload :after}))"
                         {:call-id "swallow-1" :holder? true}))]
        (is (true? (deref started 2000 false)))
        (is (= :CANCELLING (:status (program/cancel! rt "swallow"))))
        (deliver release true)
        (let [result (deref run 3000 ::timeout)]
          (is (not= ::timeout result))
          (is (= :CANCELLED (:status result)))
          (is (= :cancelled (:state result)))))
      (finally
        (deliver release true)
        (program/stop! rt)))))

(deftest park-delta-tracks-each-stream
  (let [rt (make-runtime
            {:shell (fn [_ctx request]
                      (let [view! (:on-command-view request)]
                        (view! {:command "two-streams"
                                :status :running
                                :phase :running
                                :stdout "a"
                                :stderr "x"
                                :stdout-bytes 1
                                :stderr-bytes 1})
                        (Thread/sleep 100)
                        (view! {:command "two-streams"
                                :status :running
                                :phase :running
                                :stdout "ab"
                                :stderr "x"
                                :stdout-bytes 2
                                :stderr-bytes 1})
                        (Thread/sleep 300)
                        {:status 0 :stdout "ab" :stderr "x"}))})]
    (try
      ;; Four bytes = 200 ms, so the deadline parks after the second view.
      (let [parked (exec! rt "(defprogram delta []
                                (call {:command \"two-streams\"}))"
                          {:holder? true :intent "xxxx"})]
        (is (= :PARKED (:status parked)))
        (is (= :deadline (:reason parked)))
        (is (= "ab" (get-in parked [:command :stdout])))
        (is (= "x" (get-in parked [:command :stderr])))
        ;; Only the stdout increment is new; concatenating the streams first
        ;; would re-emit "abx".
        (is (= "b" (:delta parked))))
      (finally (program/stop! rt)))))

(deftest uncertain-call-stays-visible-after-it-returns
  (let [rt (make-runtime
            {:shell (fn [_ctx _request] {:status :UNCERTAIN :outcome :unknown})
             :call-stop! (fn [_ctx _request-id] nil)})]
    (try
      (let [parked (exec! rt "(defprogram uncertain []
                                (call {:command \"x\"})
                                (park {}))"
                          {:holder? true :call-id "uncertain-1"})]
        (is (= :PARKED (:status parked)))
        (is (= 1 (count (:pending-operations parked))))
        (let [terminal (program/cancel! rt "uncertain")]
          (is (= :OUTCOME_UNKNOWN (:status terminal)))
          (is (true? (:cancel-unconfirmed? terminal)))
          (is (= 1 (count (:pending-operations terminal))))))
      (finally (program/stop! rt)))))

(deftest missing-call-port-is-a-program-error
  (let [rt (make-runtime)]
    (try
      (let [result (exec! rt "(defprogram no-port [] (call {:command \"x\"}))"
                          {:holder? true})]
        (is (= :UNSUPPORTED_OPERATION (:status result)))
        (is (= :failed (:state result)))
        (is (= :call-port-missing (get-in result [:error :reason]))))
      (finally (program/stop! rt)))))

(deftest name-conflict-does-not-open-a-second-session
  (let [opens (atom 0)
        session (atom {:ops [] :read-set {} :checks {}})
        workspace {:open-session (fn [_] (swap! opens inc) session)
                   :cat (fn [_ _] "")
                   :stat (fn [_ _] {:exists false})
                   :ls (fn [_ _] [])
                   :write (fn [_ _ _] {:status :STAGED})
                   :mkdir (fn [_ _] {:status :STAGED})
                   :rm (fn [_ _] {:status :STAGED})
                   :mv (fn [_ _ _ _] {:status :STAGED})
                   :facts (fn [_] @session)
                   :settle! (fn [_ _] {:status :APPLIED :session session})}
        rt (make-runtime {:workspace workspace})]
    (try
      (is (= :PARKED
             (:status (exec! rt "(defprogram same [] (park {}))"
                             {:call-id "same-1"}))))
      (is (= :NAME_TAKEN
             (:status (exec! rt "(defprogram same [] 1)"
                             {:call-id "same-2"}))))
      (is (= 1 @opens))
      (finally (program/stop! rt)))))

(deftest call-context-carries-the-host-resolved-token-term
  (let [seen (atom nil)
        rt (make-runtime
            {:call (fn [ctx _request]
                     (reset! seen ctx)
                     {:status :COMPLETED
                      :request-id "r1"
                      :exit-code 0
                      :output {:stdout {:inline "" :bytes 0}
                               :stderr {:inline "" :bytes 0}}})})]
    (try
      (let [result (exec! rt "(defprogram c [] (call {:command \"true\"}))"
                          {:context {:token-term 7} :holder? true})]
        (is (= :COMPLETED (:status result)))
        (is (= 7 (:token-term @seen))
            "the host term reaches the call port through the program context"))
      (finally (program/stop! rt)))))

(deftest stewardship-roles-have-independent-capability-gates
  (let [control {:execute! (fn [_ctx {:keys [primitive]}]
                             {:status :OK :primitive primitive})}
        workspace {:open-session (fn [_] :session)
                   :cat (fn [_ _] "seen")}
        rt (make-runtime {:workspace workspace :control control})]
    (try
      (testing "the Steward may assume and orchestrate"
        (doseq [source ["(defprogram steward-assume []
                            (assume {:assumption \"the draft is sound\"}))"
                        "(defprogram steward-fork []
                            (fork {:slices []}))"
                        "(defprogram steward-join [] (join {}))"
                        "(defprogram steward-fleet [] (fleet-state {}))"]]
          (let [result (exec! rt source {:role :clj-steward})]
            (is (= :COMPLETED (:status result)) (pr-str result))
            (is (= :clj-steward (:role result))))))
      (testing "the Steward reads files but cannot write, call, or review"
        (let [read (exec! rt "(defprogram steward-read []
                                (cat {:path \"src/x.clj\"}))"
                          {:role :clj-steward})]
          (is (= :COMPLETED (:status read)))
          (is (= "seen" (:value read))))
        (doseq [source ["(defprogram steward-write []
                            (write {:path \"x\" :content \"y\"}))"
                        "(defprogram steward-call [] (call {:command \"true\"}))"
                        "(defprogram steward-review []
                            (review {:findings []}))"]]
          (is (= :CAPABILITY_DENIED
                 (:status (exec! rt source {:role :clj-steward})))
              source)))
      (testing "the Meditator reads files but has no continuation or write"
        (let [read (exec! rt "(defprogram meditator-read []
                                (cat {:path \"src/x.clj\"}))"
                          {:role :clj-meditator})]
          (is (= :COMPLETED (:status read)))
          (is (= "seen" (:value read))))
        (doseq [source ["(defprogram meditator-park [] (park {}))"
                        "(defprogram meditator-write []
                            (write {:path \"x\" :content \"y\"}))"]]
          (is (= :CAPABILITY_DENIED
                 (:status (exec! rt source {:role :clj-meditator})))
              source)))
      (testing "neither stewardship role can abandon, even with a spoofed
                holder flag"
        (doseq [role [:clj-steward :clj-meditator]]
          (is (= :CAPABILITY_DENIED
                 (:status (exec! rt "(defprogram abandon-anyway []
                                        (abandon {:reason \"r\"}))"
                                 {:role role :holder? true})))
              (str role))))
      (testing "a spoofed holder flag is cleared on a Steward park receipt"
        (let [parked (exec! rt "(defprogram steward-park [] (park {}))"
                            {:call-id "steward-park"
                             :role :clj-steward
                             :holder? true})]
          (is (= :PARKED (:status parked)))
          (is (= :clj-steward (:role parked)))
          (is (false? (:holder? parked)))
          (let [resumed (program/unpark! rt
                                         {:call-id "steward-unpark"
                                          :name "steward-park"
                                          :role :clj-steward
                                          :value 42
                                          :intent long-intent})]
            (is (= :COMPLETED (:status resumed)))
            (is (= 42 (:value resumed)))
            (is (= :clj-steward (:role resumed))))))
      (testing "the Meditator cannot unpark"
        (is (= :PARKED
               (:status (exec! rt "(defprogram med-target [] (park {}))"
                               {:call-id "med-target"
                                :role :clj-worker}))))
        (let [refused (program/unpark! rt {:call-id "med-unpark"
                                           :name "med-target"
                                           :role :clj-meditator
                                           :value 1
                                           :intent long-intent})]
          (is (= :CAPABILITY_DENIED (:status refused)))
          (is (= :clj-meditator (:role refused)))))
      (finally (program/stop! rt)))))
