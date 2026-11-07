(ns hc.compose.retry.planner-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.retry.planner :as planner]
            [hc.compose.retry.policy :as policy]))

(def fixed-policy
  {:max-attempts 4
   :max-retries 3
   :deadline-ms 60000
   :base-delay-ms 100
   :max-delay-ms 1000
   :jitter-ms 0
   :retry-unknown-effects? false})

(defn call-state
  [overrides]
  (merge {:call-id "call-1"
          :attempt-id "attempt-1"
          :phase :running
          :attempts-used 1
          :retries-used 0
          :started-at 1000
          :deadline-at 61000
          :retry-at nil
          :last-error nil
          :decision-reason nil
          :effects {:status :none
                    :repeat-safe? true}}
         overrides))

(defn plan
  ([state failure now]
   (plan state failure now {}))
  ([state failure now overrides]
   (planner/plan-retry (merge {:state state
                               :policy fixed-policy
                               :failure failure
                               :now now}
                              overrides))))

(defn claim
  ([state now attempt-id]
   (claim state now attempt-id {}))
  ([state now attempt-id overrides]
   (planner/claim-due (merge {:state state
                              :policy fixed-policy
                              :now now
                              :attempt-id attempt-id}
                             overrides))))

(deftest transient-failure-schedules-one-fixed-retry
  (let [result (plan (call-state {}) {:kind :timeout} 1000)]
    (is (true? (:ok? result)))
    (is (= :retry-wait (:decision result)))
    (is (= :transient-error (:reason result)))
    (is (= 1100 (get-in result [:state :retry-at])))
    (is (= :retry-wait (get-in result [:state :phase])))
    (is (= 1 (get-in result [:state :attempts-used])))
    (is (= 0 (get-in result [:state :retries-used])))
    (is (= 61000 (get-in result [:state :deadline-at])))
    (is (= 2 (get-in result [:retry :attempt-number])))
    (is (= 1 (get-in result [:retry :retry-number])))))

(deftest duplicate-failure-cannot-move-retry-at
  (let [first (plan (call-state {}) {:kind :timeout} 1000)
        second (plan (:state first) {:kind :timeout} 1200)]
    (is (true? (:ok? first)))
    (is (= 1100 (get-in first [:state :retry-at])))
    (is (false? (:ok? second)))
    (is (= :RETRY_ALREADY_PLANNED (get-in second [:error :code])))
    (is (= 1100 (get-in first [:state :retry-at])))))

(deftest permanent-failures-do-not-retry
  (let [auth (plan (call-state {}) {:kind :auth} 1000)
        too-long (plan (call-state {}) {:kind :input-too-long} 1000)
        bad-args (plan (call-state {}) {:kind :invalid-tool-arguments} 1000)
        mystery (plan (call-state {}) {:kind :mystery} 1000)]
    (is (= :failed (:decision auth)))
    (is (= :non-retryable-error (:reason auth)))
    (is (= :none (:remedy auth)))
    (is (= :failed (get-in auth [:state :phase])))

    (is (= :failed (:decision too-long)))
    (is (= :adjust-context (:remedy too-long)))

    (is (= :failed (:decision bad-args)))
    (is (= :model-correction (:remedy bad-args)))

    (is (= :failed (:decision mystery)))
    (is (= :unknown-error (:reason mystery)))))


(deftest adapter-transient-error-schedules-retry
  (let [result (plan (call-state {})
                     {:kind :server-error
                      :category :server-error
                      :disposition :retry
                      :retryable? true
                      :http-status 503}
                     1000)]
    (is (= :retry-wait (:decision result)))
    (is (= 1100 (get-in result [:state :retry-at])))))

(deftest adapter-permanent-error-does-not-retry
  (doseq [failure [{:kind :auth :category :auth
                    :disposition :fail :retryable? false}
                   {:kind :insufficient-quota :category :insufficient-quota
                    :disposition :fail :retryable? false}
                   {:kind :input-too-long :category :input-too-long
                    :disposition :fail :retryable? false}]]
    (let [result (plan (call-state {}) failure 1000)]
      (is (= :failed (:decision result)))
      (is (= :non-retryable-error (:reason result))))))

(deftest published-tool-calls-block-model-retry
  (let [result (plan (call-state {})
                     {:kind :timeout
                      :category :timeout
                      :disposition :retry
                      :retryable? true
                      :partial? true
                      :published-call-ids ["call-2"]}
                     1000)]
    (is (= :outcome-unknown (:decision result)))
    (is (= :error-outcome-unknown (:reason result)))))

(deftest cancellation-never-retries
  (let [by-error (plan (call-state {}) {:kind :user-cancel} 1000)
        by-flag (plan (call-state {:cancelled? true}) {:kind :timeout} 1000)
        by-phase (plan (call-state {:phase :cancelled}) {:kind :timeout} 1000)]
    (doseq [result [by-error by-flag by-phase]]
      (is (= :cancelled (:decision result)))
      (is (= :cancelled (get-in result [:state :phase])))
      (is (nil? (get-in result [:state :retry-at]))))))

(deftest cancellation-keeps-unknown-effects-visible
  (let [pending (plan (call-state {:cancelled? true
                                   :effects {:status :pending}})
                      {:kind :user-cancel}
                      1000)
        unknown (plan (call-state {:cancelled? true
                                   :effects {:status :unknown
                                             :repeat-safe? true}})
                      {:kind :user-cancel}
                      1000)
        settled (plan (call-state {:cancelled? true
                                   :effects {:status :settled}})
                      {:kind :user-cancel}
                      1000)]
    (is (= :outcome-unknown (:decision pending)))
    (is (= :cancelled-with-pending-effects (:reason pending)))
    (is (= :outcome-unknown (:decision unknown)))
    (is (= :cancelled-with-unknown-effects (:reason unknown)))
    (is (= :cancelled (:decision settled)))))

(deftest cancellation-with-published-tools-reconciles
  (let [result (plan (call-state {:cancelled? true})
                     {:kind :timeout
                      :published-call-ids ["call-1/tool-0"]}
                     1000)]
    (is (= :outcome-unknown (:decision result)))
    (is (= :cancelled-with-published-effects (:reason result)))
    (is (= :outcome-unknown (get-in result [:state :phase])))))

(deftest pending-effects-require-coordination
  (let [result (plan (call-state {:effects {:status :pending
                                            :repeat-safe? true}})
                     {:kind :timeout} 1000)]
    (is (= :outcome-unknown (:decision result)))
    (is (= :side-effects-pending (:reason result)))
    (is (= :outcome-unknown (get-in result [:state :phase])))
    (is (nil? (get-in result [:state :retry-at])))))

(deftest unknown-effects-block-by-default
  (let [unsafe (plan (call-state {:effects {:status :unknown
                                            :repeat-safe? false}})
                     {:kind :timeout} 1000)
        safe (plan (call-state {:effects {:status :unknown
                                          :repeat-safe? true}})
                   {:kind :timeout} 1000)]
    (doseq [result [unsafe safe]]
      (is (= :outcome-unknown (:decision result)))
      (is (= :side-effects-unknown (:reason result))))))

(deftest missing-effects-are-unknown
  (let [result (plan (dissoc (call-state {}) :effects) {:kind :timeout} 1000)]
    (is (= :outcome-unknown (:decision result)))
    (is (= :side-effects-unknown (:reason result)))))

(deftest unknown-effects-may-retry-only-with-explicit-opt-in
  (let [result (plan (call-state {:effects {:status :unknown
                                            :repeat-safe? true}})
                     {:kind :timeout}
                     1000
                     {:policy (assoc fixed-policy
                                     :retry-unknown-effects? true)})]
    (is (= :retry-wait (:decision result)))
    (is (= 1100 (get-in result [:state :retry-at])))))

(deftest settled-effects-allow-retry
  (let [result (plan (call-state {:effects {:status :settled
                                            :repeat-safe? false}})
                     {:kind :timeout} 1000)]
    (is (= :retry-wait (:decision result)))))

(deftest outcome-unknown-error-requires-reconciliation
  (let [result (plan (call-state {}) {:kind :outcome-unknown} 1000)]
    (is (= :outcome-unknown (:decision result)))
    (is (= :error-outcome-unknown (:reason result)))))

(deftest attempt-budget-is-independent-of-retry-budget
  (let [attempts-out (plan (call-state {:attempts-used 2
                                        :retries-used 1})
                           {:kind :timeout}
                           1000
                           {:policy (assoc fixed-policy
                                           :max-attempts 2
                                           :max-retries 5)})
        retries-out (plan (call-state {:attempts-used 2
                                       :retries-used 1})
                          {:kind :timeout}
                          1000
                          {:policy (assoc fixed-policy
                                          :max-attempts 5
                                          :max-retries 1)})]
    (is (= :failed (:decision attempts-out)))
    (is (= :attempts-exhausted (:reason attempts-out)))
    (is (= :none (:remedy attempts-out)))
    (is (= :failed (:decision retries-out)))
    (is (= :retries-exhausted (:reason retries-out)))
    (is (= :none (:remedy retries-out)))))

(deftest deadline-is-absolute
  (let [expired (plan (call-state {:deadline-at 1000})
                      {:kind :timeout} 1000)
        too-late (plan (call-state {:deadline-at 1050})
                       {:kind :timeout} 1000)]
    (is (= :failed (:decision expired)))
    (is (= :deadline-exceeded (:reason expired)))
    (is (= :failed (:decision too-late)))
    (is (= :deadline-before-retry (:reason too-late)))
    (is (= 1100 (get-in too-late [:retry :retry-at])))))

(deftest claim-is-not-due-before-retry-at
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        early (claim scheduled 1099 "attempt-2")
        due (claim scheduled 1100 "attempt-2")]
    (is (false? (:ok? early)))
    (is (= :RETRY_NOT_DUE (get-in early [:error :code])))
    (is (true? (:ok? due)))
    (is (= :claimed (:decision due)))
    (is (= "attempt-2" (get-in due [:state :attempt-id])))
    (is (= "attempt-1" (get-in due [:attempt :previous-attempt-id])))
    (is (= 2 (get-in due [:state :attempts-used])))
    (is (= 1 (get-in due [:state :retries-used])))
    (is (= :dispatching (get-in due [:state :phase])))
    (is (nil? (get-in due [:state :retry-at])))
    (is (= 61000 (get-in due [:state :deadline-at])))))

(deftest claim-requires-a-new-attempt-id
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        same (claim scheduled 1100 "attempt-1")
        blank (claim scheduled 1100 "  ")]
    (is (false? (:ok? same)))
    (is (= :DUPLICATE_ATTEMPT_ID (get-in same [:error :code])))
    (is (false? (:ok? blank)))
    (is (= :MISSING_ATTEMPT_ID (get-in blank [:error :code])))))

(deftest claim-closes-terminal-conditions
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        expired (claim (assoc scheduled
                              :deadline 1100
                              :deadline-at 1100)
                       1100
                       "attempt-2")
        pending (claim (assoc scheduled :effects {:status :pending})
                       1100
                       "attempt-2")
        unknown (claim (assoc scheduled :effects {:status :unknown
                                                  :repeat-safe? false})
                       1100
                       "attempt-2")
        cancelled (claim (assoc scheduled :cancelled? true) 1100 "attempt-2")]
    (is (= :failed (:decision expired)))
    (is (= :deadline-exceeded (:reason expired)))
    (is (= :outcome-unknown (:decision pending)))
    (is (= :side-effects-pending (:reason pending)))
    (is (= :outcome-unknown (:decision unknown)))
    (is (= :side-effects-unknown (:reason unknown)))
    (is (= :cancelled (:decision cancelled)))))

(deftest guards-report-reasons
  (let [state (call-state {})
        plan-guard (planner/plan-candidate {:state state
                                            :policy fixed-policy
                                            :failure {:kind :timeout}
                                            :now 1000})
        permanent-guard (planner/plan-candidate {:state state
                                                 :policy fixed-policy
                                                 :failure {:kind :auth}
                                                 :now 1000})
        scheduled (:state (plan state {:kind :timeout} 1000))
        claim-guard (planner/claim-candidate {:state scheduled
                                              :policy fixed-policy
                                              :now 1100})
        early-guard (planner/claim-candidate {:state scheduled
                                              :policy fixed-policy
                                              :now 1099})]
    (is (true? (:enabled? plan-guard)))
    (is (= :retry-wait (:decision plan-guard)))
    (is (false? (:enabled? permanent-guard)))
    (is (= :failed (:decision permanent-guard)))
    (is (true? (:enabled? claim-guard)))
    (is (= :claimed (:decision claim-guard)))
    (is (false? (:enabled? early-guard)))
    (is (= :RETRY_NOT_DUE (:reason early-guard)))))

(deftest guards-are-deterministic
  (let [state (call-state {})
        plan-args {:state state
                   :policy fixed-policy
                   :failure {:kind :timeout}
                   :now 1000}
        claim-args {:state (:state (plan state {:kind :timeout} 1000))
                    :policy fixed-policy
                    :now 1100}]
    (is (= (planner/plan-candidate plan-args)
           (planner/plan-candidate plan-args)))
    (is (= (planner/claim-candidate claim-args)
           (planner/claim-candidate claim-args)))))

(deftest init-call-fixes-the-deadline
  (let [result (planner/init-call {:call-id "c"
                                   :attempt-id "a1"
                                   :now 5000
                                   :policy fixed-policy})]
    (is (true? (:ok? result)))
    (is (= 1 (get-in result [:state :attempts-used])))
    (is (= 0 (get-in result [:state :retries-used])))
    (is (= 65000 (get-in result [:state :deadline-at])))
    (is (= {:status :none :repeat-safe? true}
           (get-in result [:state :effects]))))
  (is (false? (:ok? (planner/init-call {:call-id ""
                                        :attempt-id "a1"
                                        :now 5000
                                        :policy fixed-policy})))))


(deftest persisted-obligation-fields
  (let [initial (:state (planner/init-call {:call-id "c"
                                            :attempt-id "a1"
                                            :now 1000
                                            :policy fixed-policy}))
        result (plan initial {:kind :timeout} 1000)
        state (:state result)]
    (is (= 4 (:max-attempts state)))
    (is (= 3 (:max-retries state)))
    (is (= 61000 (:deadline state)))
    (is (= 61000 (:deadline-at state)))
    (is (= 1100 (:retry-at state)))
    (is (= 1 (:attempts-used state)))
    (is (nil? (:failure state)))))

(deftest resume-preserves-future-retry-at
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        result (planner/resume-retry-wait {:state scheduled
                                           :policy fixed-policy
                                           :now 1050})]
    (is (true? (:ok? result)))
    (is (= :rearm-timer (:action result)))
    (is (= 1100 (:retry-at result)))
    (is (= 1100 (get-in result [:state :retry-at])))
    (is (= :retry-wait (get-in result [:state :phase])))))

(deftest resume-claims-a-due-retry-without-moving-it
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        result (planner/resume-retry-wait {:state scheduled
                                           :policy fixed-policy
                                           :now 1100})]
    (is (= :claim (:action result)))
    (is (= 1100 (:retry-at result)))
    (is (= 1100 (get-in result [:state :retry-at])))
    (is (= :retry-wait (get-in result [:state :phase])))))

(deftest resume-fails-an-exhausted-or-expired-retry
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        expired (planner/resume-retry-wait {:state scheduled
                                            :policy fixed-policy
                                            :now 61000})
        attempts-out (planner/resume-retry-wait
                      {:state (assoc scheduled
                                     :attempts-used 4
                                     :retries-used 3)
                       :policy fixed-policy
                       :now 1050})]
    (is (= :fail (:action expired)))
    (is (= :deadline-exceeded (:reason expired)))
    (is (= :failed (get-in expired [:state :phase])))
    (is (= :retry-budget-exhausted (get-in expired [:state :failure :kind])))
    (is (nil? (get-in expired [:state :retry-at])))
    (is (= :fail (:action attempts-out)))
    (is (= :attempts-exhausted (:reason attempts-out)))))

(deftest resume-reconciles-unknown-effects-and-cancel
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        unknown (planner/resume-retry-wait
                 {:state (assoc scheduled
                                :effects {:status :unknown
                                          :repeat-safe? false})
                  :policy fixed-policy
                  :now 1050})
        cancelled (planner/resume-retry-wait
                   {:state (assoc scheduled :cancelled? true)
                    :policy fixed-policy
                    :now 1050})]
    (is (= :reconcile (:action unknown)))
    (is (= :outcome-unknown (get-in unknown [:state :phase])))
    (is (= :cancel (:action cancelled)))
    (is (= :cancelled (get-in cancelled [:state :phase])))
    (is (nil? (get-in cancelled [:state :retry-at])))))

(deftest resume-reconciles-cancelled-pending-effects-first
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        pending (planner/resume-retry-wait
                 {:state (assoc scheduled
                                :cancelled? true
                                :effects {:status :pending})
                  :policy fixed-policy
                  :now 1050})
        unknown (planner/resume-retry-wait
                 {:state (assoc scheduled
                                :cancelled? true
                                :effects {:status :unknown
                                          :repeat-safe? true})
                  :policy fixed-policy
                  :now 1050})]
    (is (= :reconcile (:action pending)))
    (is (= :cancelled-with-pending-effects (:reason pending)))
    (is (= :outcome-unknown (get-in pending [:state :phase])))
    (is (= :reconcile (:action unknown)))
    (is (= :cancelled-with-unknown-effects (:reason unknown)))
    (is (= :outcome-unknown (get-in unknown [:state :phase])))))


(deftest resume-accepts-recovery-shaped-obligation
  (let [state {:call-id "c"
               :attempt-id "a1"
               :phase :retry-wait
               :attempts-used 1
               :retries-used 0
               :max-attempts 3
               :max-retries 2
               :deadline 2000
               :retry-at 1500
               :effects {:status :none :repeat-safe? true}}
        result (planner/resume-retry-wait {:state state
                                           :policy fixed-policy
                                           :now 1000})]
    (is (= :rearm-timer (:action result)))
    (is (= 1500 (:retry-at result)))
    (is (= 1500 (get-in result [:state :retry-at])))
    (is (= 2000 (get-in result [:state :deadline])))
    (is (= 2000 (get-in result [:state :deadline-at])))))

(deftest resume-rejects-non-retry-wait-and-terminal-states
  (let [running (planner/resume-retry-wait {:state (call-state {})
                                            :policy fixed-policy
                                            :now 1000})
        failed (planner/resume-retry-wait
                {:state (call-state {:phase :failed})
                 :policy fixed-policy
                 :now 1000})]
    (is (false? (:ok? running)))
    (is (= :NOT_RETRY_WAIT (get-in running [:error :code])))
    (is (= :terminal (:action failed)))))

(deftest terminal-phases-cannot-be-replanned
  (let [failed (plan (call-state {:phase :failed}) {:kind :timeout} 1000)
        succeeded (plan (call-state {:phase :succeeded}) {:kind :timeout} 1000)
        unknown (plan (call-state {:phase :outcome-unknown})
                      {:kind :timeout}
                      1000)
        ready (plan (call-state {:phase :ready
                                 :attempts-used 0
                                 :retries-used 0})
                    {:kind :timeout}
                    1000)]
    (is (false? (:ok? failed)))
    (is (= :CALL_TERMINAL (get-in failed [:error :code])))
    (is (false? (:ok? succeeded)))
    (is (= :CALL_TERMINAL (get-in succeeded [:error :code])))
    (is (false? (:ok? unknown)))
    (is (= :CALL_TERMINAL (get-in unknown [:error :code])))
    (is (false? (:ok? ready)))
    (is (= :CALL_NOT_STARTED (get-in ready [:error :code])))
    (is (true? (planner/terminal? {:phase :outcome-unknown})))
    (is (true? (planner/terminal? {:phase :cancelled})))
    (is (false? (planner/terminal? {:phase :retry-wait})))))

(deftest late-cancel-does-not-rewrite-a-terminal-phase
  (let [failed (plan (call-state {:phase :failed
                                  :cancelled? true})
                     {:kind :user-cancel}
                     1000)
        unknown (plan (call-state {:phase :outcome-unknown
                                   :cancelled? true})
                      {:kind :user-cancel}
                      1000)
        succeeded (claim (call-state {:phase :succeeded
                                      :cancelled? true})
                         1000
                         "attempt-2")]
    (is (false? (:ok? failed)))
    (is (= :CALL_TERMINAL (get-in failed [:error :code])))
    (is (false? (:ok? unknown)))
    (is (= :CALL_TERMINAL (get-in unknown [:error :code])))
    (is (false? (:ok? succeeded)))
    (is (= :CALL_TERMINAL (get-in succeeded [:error :code])))))

(deftest invalid-inputs-are-refused
  (let [bad-policy (plan (call-state {}) {:kind :timeout} 1000
                         {:policy {:max-attempts 0}})
        bad-state (plan (dissoc (call-state {}) :call-id)
                        {:kind :timeout}
                        1000)
        ready-with-attempts (plan (call-state {:phase :ready})
                                  {:kind :timeout}
                                  1000)
        started-without-attempt (plan (call-state {:attempts-used 0
                                                   :retries-used 0})
                                      {:kind :timeout}
                                      1000)
        bad-now (plan (call-state {}) {:kind :timeout} -1)
        bad-jitter (plan (call-state {}) {:kind :timeout} 1000
                         {:jitter-offset-ms -1})
        missing-failure (plan (call-state {}) nil 1000)
        empty-failure (plan (call-state {}) {} 1000)
        missing-failure-guard
        (planner/plan-candidate {:state (call-state {})
                                 :policy fixed-policy
                                 :failure nil
                                 :now 1000})
        bad-claim (claim (call-state {:phase :retry-wait
                                      :retry-at 1100})
                         -1
                         "attempt-2")]
    (is (= :INVALID_POLICY (get-in bad-policy [:error :code])))
    (is (= :INVALID_STATE (get-in bad-state [:error :code])))
    (is (= :INVALID_STATE (get-in ready-with-attempts [:error :code])))
    (is (= :INVALID_STATE (get-in started-without-attempt [:error :code])))
    (is (= :INVALID_NOW (get-in bad-now [:error :code])))
    (is (= :INVALID_JITTER (get-in bad-jitter [:error :code])))
    (is (= :INVALID_FAILURE (get-in missing-failure [:error :code])))
    (is (= :INVALID_FAILURE (get-in empty-failure [:error :code])))
    (is (= :INVALID_FAILURE (get-in missing-failure-guard [:error :code])))
    (is (false? (:enabled? missing-failure-guard)))
    (is (= :INVALID_NOW (get-in bad-claim [:error :code])))))

(deftest claim-opt-in-for-repeat-safe-unknown-effects
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        result (claim (assoc scheduled :effects {:status :unknown
                                                 :repeat-safe? true})
                      1100
                      "attempt-2"
                      {:policy (assoc fixed-policy
                                      :retry-unknown-effects? true)})]
    (is (true? (:ok? result)))
    (is (= :claimed (:decision result)))
    (is (= :retry-due (:reason result)))))

(deftest claim-resets-effects-for-the-new-attempt
  (let [scheduled (:state (plan (call-state {}) {:kind :timeout} 1000))
        result (claim (assoc scheduled :effects {:status :settled})
                      1100
                      "attempt-2")]
    (is (= :claimed (:decision result)))
    (is (= {:status :none :repeat-safe? true}
           (get-in result [:state :effects])))))

(defspec retry-at-is-bounded-and-fixed
  100
  (prop/for-all [now (gen/choose 0 1000000)
                 attempts (gen/choose 1 5)
                 retries (gen/choose 0 4)
                 deadline-extra (gen/choose 0 10000)]
    (or (not (< retries attempts))
        (let [state (call-state {:attempts-used attempts
                                 :retries-used retries
                                 :deadline-at (+ now deadline-extra)})
              result (plan state {:kind :timeout} now
                           {:policy (assoc fixed-policy :jitter-ms 0)})
              retry-at (get-in result [:state :retry-at])]
          (if (= :retry-wait (:decision result))
            (and (<= now retry-at)
                 (< retry-at (+ now deadline-extra))
                 (= attempts (get-in result [:state :attempts-used]))
                 (= retries (get-in result [:state :retries-used]))
                 (false? (:ok? (plan (:state result)
                                     {:kind :timeout}
                                     now
                                     {:policy (assoc fixed-policy
                                                     :jitter-ms 0)}))))
            true)))))

(defspec claim-increments-both-budgets-once
  100
  (prop/for-all [attempts (gen/choose 1 3)
                 retries (gen/choose 0 2)
                 now (gen/choose 0 100000)]
    (or (not (< retries attempts))
        (let [state (call-state {:attempts-used attempts
                                 :retries-used retries
                                 :deadline-at (+ now 60000)})
              scheduled (:state (plan state {:kind :timeout} now
                                       {:policy (assoc fixed-policy
                                                       :jitter-ms 0)}))]
          (if (= :retry-wait (:phase scheduled))
            (let [retry-at (:retry-at scheduled)
                  result (claim scheduled retry-at "attempt-next")]
              (and (true? (:ok? result))
                   (= :claimed (:decision result))
                   (= (inc attempts) (get-in result [:state :attempts-used]))
                   (= (inc retries) (get-in result [:state :retries-used]))
                   (= (+ now 60000) (get-in result [:state :deadline-at]))
                   (nil? (get-in result [:state :retry-at]))))
            true)))))

(defspec unknown-effects-never-retry-without-opt-in
  100
  (prop/for-all [now (gen/choose 0 1000000)
                 attempts (gen/choose 1 3)
                 retries (gen/choose 0 2)
                 repeat-safe? gen/boolean]
    (or (not (< retries attempts))
        (let [state (call-state {:attempts-used attempts
                                 :retries-used retries
                                 :effects {:status :unknown
                                           :repeat-safe? repeat-safe?}})
              result (plan state {:kind :timeout} now)]
          (not= :retry-wait (:decision result))))))
