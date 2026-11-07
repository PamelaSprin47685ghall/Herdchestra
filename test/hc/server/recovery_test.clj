(ns hc.server.recovery-test
  "Tests for the six restart windows and the recovery facade.

  The classification tests are pure.  The facade tests use a real store in a
  temporary directory and a fake probe/delivery, so no external service is
  contacted."

  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.server.recovery :as recovery]
            [hc.server.recovery.classify :as classify]
            [hc.server.storage :as storage]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.support :as support]))

(defn- decision [obligation ctx]
  (select-keys (classify/classify obligation ctx)
               [:action :next-phase :open? :reason]))

(deftest ready-window-test
  (is (= {:action :dispatch :reason :awaiting-claim}
         (decision {:obligation/id "o" :open? true :phase :ready} {}))))

(deftest dispatching-window-test
  (is (= {:action :redispatch :next-phase :ready :open? true
          :reason :idempotent-redispatch}
         (decision {:obligation/id "o" :open? true :phase :dispatching
                    :idempotent? true} {})))
  (is (= {:action :interrupt :next-phase :outcome-unknown :open? true
          :reason :start-unobserved-not-idempotent}
         (decision {:obligation/id "o" :open? true :phase :dispatching
                    :idempotent? false} {}))))

(deftest started-unobserved-window-test
  (is (= {:action :finished :next-phase :closed :open? false
          :reason :external-terminal}
         (decision {:obligation/id "o" :open? true :phase :started-unobserved}
                   {:probe-result :succeeded})))
  (is (= {:action :outcome-unknown :next-phase :outcome-unknown :open? true
          :reason :external-start-unconfirmed}
         (decision {:obligation/id "o" :open? true :phase :started-unobserved}
                   {:probe-result :unknown})))
  (is (= :ready
         (:next-phase
          (classify/classify {:obligation/id "o" :open? true :phase :started-unobserved
                              :safe-to-repeat? true}
                             {:probe-result :not-started})))))

(deftest running-window-test
  (is (= {:action :live :reason :executor-still-current}
         (decision {:obligation/id "o" :open? true :phase :running
                    :executor-generation 7}
                   {:current-generation 7})))
  (is (= {:action :outcome-unknown :next-phase :outcome-unknown :open? true
          :reason :executor-gone-unconfirmed}
         (decision {:obligation/id "o" :open? true :phase :running
                    :executor-generation 1}
                   {:current-generation 2 :probe-result :unknown})))
  (is (= {:action :finished :next-phase :closed :open? false
          :reason :external-terminal}
         (decision {:obligation/id "o" :open? true :phase :running
                    :executor-generation 1}
                   {:current-generation 2 :probe-result :failed}))))

(deftest report-window-test
  (is (= {:action :redeliver :next-phase :report-pending :open? true
          :reason :awaiting-parent-confirmation}
         (decision {:obligation/id "o" :open? true :phase :report-pending}
                   {:delivery-result :unknown})))
  (is (= {:action :close-report :next-phase :report-accepted :open? false
          :reason :parent-accepted}
         (decision {:obligation/id "o" :open? true :phase :report-pending}
                   {:delivery-result :accepted})))
  (is (= {:action :inspect :reason :awaiting-operator-reconciliation}
         (decision {:obligation/id "o" :open? true :phase :outcome-unknown} {}))))

(deftest recover-applies-safe-transitions-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "seed"
      :partition "p"
      :session-id "s"
      :obligations
      {:put [{:obligation/id "ready" :open? true :phase :ready}
             {:obligation/id "disp" :open? true :phase :dispatching
              :idempotent? false}
             {:obligation/id "started" :open? true :phase :started-unobserved}
             {:obligation/id "run" :open? true :phase :running
              :executor-generation 1 :safe-to-repeat? false}
             {:obligation/id "report-ok" :open? true :phase :report-pending
              :report {:report-id "r1" :parent-call-id "c1"}}
             {:obligation/id "report-pending" :open? true :phase :report-pending
              :report {:report-id "r2" :parent-call-id "c2"}}]}})
    (let [result (recovery/recover!
                  store
                  {:partition "p"
                   :current-generation 2
                   :probe (fn [o]
                            (case (:obligation/id o)
                              "started" {:state :succeeded}
                              {:state :unknown}))
                   :deliver-report (fn [o]
                                     (if (= "report-ok" (:obligation/id o))
                                       {:accepted? true}
                                       {:accepted? false}))})
          open (->> (storage/list-open-obligations store {:partition "p"
                                                         :session-id "s"
                                                         :open-only? false})
                    :obligations
                    (into {} (map (juxt :obligation/id identity))))]
      (is (= :ok (:status result)))
      (is (= 6 (:scanned result)))
      (is (= ["report-pending"] (mapv :obligation/id (:pending-reports result))))
      (is (= #{"disp" "run"} (set (mapv :obligation/id (:outcome-unknown result)))))
      (is (= :outcome-unknown (:phase (get open "disp"))))
      (is (= :outcome-unknown (:phase (get open "run"))))
      (is (= :ready (:phase (get open "ready"))))
      (is (false? (:open? (get open "started"))))
      (is (false? (:open? (get open "report-ok"))))
      (is (true? (:open? (get open "report-pending"))))
      (is (= :report-accepted (:phase (get open "report-ok")))))))

(deftest recover-without-probe-is-conservative-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "seed"
      :partition "p"
      :session-id "s"
      :obligations
      {:put [{:obligation/id "run" :open? true :phase :running
              :executor-generation 1 :safe-to-repeat? true}
             {:obligation/id "started" :open? true :phase :started-unobserved
              :safe-to-repeat? true}]}})
    (let [result (recovery/recover! store {:partition "p" :current-generation 2})]
      (is (= #{"run" "started"}
             (set (mapv :obligation/id (:outcome-unknown result))))))))

(deftest recovery-entry-never-creates-test
  (is (= :blocked (:status (recovery/recovery-entry {}))))
  (let [dir (str (support/temp-dir "hc-store-") "/missing")]
    (try
      (let [entry (recovery/recovery-entry {:path dir})]
        (is (= :blocked (:status entry)))
        (is (= :STORAGE_NOT_FOUND (get-in entry [:error :error])))
        (is (string? (:hint entry)))
        (is (not (.exists (io/file dir)))))
      (finally (support/delete-tree! (.getParent (io/file dir)))))))

(deftest recovery-entry-refuses-foreign-directory-test
  (let [dir (support/temp-dir "hc-store-")
        f   (io/file dir "user.txt")]
    (try
      (spit f "keep me")
      (let [entry (recovery/recovery-entry {:path dir :create? true})]
        (is (= :blocked (:status entry)))
        (is (= :STORAGE_NOT_A_STORE (get-in entry [:error :error])))
        (is (= "keep me" (slurp f)))
        (is (= #{"user.txt"} (set (map #(.getName %) (.listFiles (io/file dir)))))))
      (finally (support/delete-tree! dir)))))


(deftest recover-all-scans-every-partition-and-scope-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "s1"
      :partition "session/s1"
      :session-id "s1"
      :obligations
      {:put [{:obligation/id "sess" :open? true :phase :ready}
             {:obligation/id "report" :open? true :phase :report-pending
              :report {:report-id "r1" :parent-call-id "c1"}}]}})
    (storage/commit-migration!
     store
     {:migration-id "s1p"
      :partition "session/s1"
      :obligations
      {:put [{:obligation/id "part" :open? true :phase :running
              :executor-generation 1}]}})
    (storage/commit-migration!
     store
     {:migration-id "master"
      :partition "master"
      :obligations
      {:put [{:obligation/id "master" :open? true :phase :running
              :executor-generation 1}]}})
    (let [result (recovery/recover-all! store
                                        {:current-generation 2
                                         :deliver-report (fn [_] {:accepted? false})})
          all    (->> (storage/list-open-obligations store {:all-partitions? true
                                                            :open-only? false})
                      :obligations
                      (into {} (map (juxt :obligation/id identity))))]
      (is (= :ok (:status result)))
      (is (= 4 (:scanned result)))
      (is (= #{"session/s1" "master"} (set (:partitions result))))
      (is (= 2 (count (:commits result))))
      (is (= :ready (:phase (get all "sess"))))
      (is (= :outcome-unknown (:phase (get all "part"))))
      (is (= :outcome-unknown (:phase (get all "master"))))
      (is (= :report-pending (:phase (get all "report"))))
      (is (= ["report"] (mapv :obligation/id (:pending-reports result)))))))

(deftest recovery-entry-verifies-integrity-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (storage/commit-migration! store {:migration-id "seed" :partition "p"
                                          :put [{:kind :task :id "t" :doc {}}]})
        (engine/transact!
         (:engine store)
         [[:put (keys/doc-key "p" nil :task "bad")
           {:storage/kind :wrong
            :storage/id "bad"
            :storage/partition "p"
            :storage/session-id nil
            :storage/version 1
            :storage/updated-at "x"
            :doc {}}]])
        (storage/close-store! store))
      (let [entry (recovery/recovery-entry {:path dir})]
        (is (= :blocked (:status entry)))
        (is (= :STORAGE_CORRUPT (get-in entry [:error :error])))
        (is (string? (:hint entry))))
      (testing "the blocked open released the lock"
        (let [store (storage/open-store {:path dir})]
          (is (storage/store-open? store))
          (storage/close-store! store)))
      (testing "integrity verification can be skipped explicitly"
        (let [entry (recovery/recovery-entry {:path dir :verify-integrity? false})]
          (is (= :opened (:status entry)))
          (storage/close-store! (:store entry))))
      (finally (support/delete-tree! dir)))))


(deftest storage-retry-wait-is-retained-and-reported-test
  (testing "a storage retry-wait stays open and is reported for blocking"
    (doseq [obligation [{:obligation/id "exhausted" :open? true
                         :phase :retry-wait
                         :attempts-used 3 :max-attempts 3 :retry-at 50}
                        {:obligation/id "due" :open? true :phase :retry-wait
                         :attempts-used 1 :max-attempts 3 :retry-at 50}
                        {:obligation/id "future" :open? true :phase :retry-wait
                         :attempts-used 1 :max-attempts 3 :retry-at 500}
                        {:obligation/id "budgetless" :open? true
                         :phase :retry-wait}]]
      (let [decision (classify/classify obligation {:now-ms 100})]
        (is (= :inspect (:action decision)))
        (is (= :storage-retry-unsupported (:reason decision)))
        (is (= :STORAGE_RETRY_UNSUPPORTED (:error decision)))
        (is (not (contains? decision :next-phase)))
        (is (not (contains? decision :open?)))
        (is (= (:phase obligation) (:phase decision))))))
  (testing "a due retry already moved to :ready is retained too"
    (let [decision (classify/classify {:obligation/id "legacy-ready"
                                       :open? true :phase :ready
                                       :attempts-used 1 :max-attempts 3
                                       :retry-at 50}
                                      {:now-ms 100})]
      (is (= :inspect (:action decision)))
      (is (= :storage-retry-unsupported (:reason decision)))
      (is (= :STORAGE_RETRY_UNSUPPORTED (:error decision)))
      (is (not (contains? decision :next-phase)))
      (is (not (contains? decision :open?)))))
  (testing "budget exhaustion still closes a claimed call"
    (is (= :failed
           (:next-phase
            (classify/classify {:obligation/id "r" :open? true :phase :ready
                                :attempts-used 2 :max-attempts 2}
                               {:now-ms 100}))))))

(deftest recover-reports-blocked-retry-wait-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "seed"
      :partition "p"
      :session-id "s"
      :obligations
      {:put [{:obligation/id "exhausted" :open? true :phase :retry-wait
              :attempts-used 3 :max-attempts 3 :retry-at 50}
             {:obligation/id "due" :open? true :phase :retry-wait
              :attempts-used 1 :max-attempts 3 :retry-at 50}
             {:obligation/id "future" :open? true :phase :retry-wait
              :attempts-used 1 :max-attempts 3 :retry-at 500}]}})
    (let [result (recovery/recover-all! store {:partition "p"
                                               :session-id "s"
                                               :now-ms 100
                                               :current-generation 2})
          all    (->> (storage/list-open-obligations store {:partition "p"
                                                            :session-id "s"
                                                            :open-only? false})
                      :obligations
                      (into {} (map (juxt :obligation/id identity))))]
      (is (= :ok (:status result)))
      (is (and (contains? result :pending-retries)
               (empty? (:pending-retries result)))
          "pending-retries is present but empty; legacy unowned retry is blocked")
      (is (true? (recovery/blocked-retries? result)))
      (is (= #{"exhausted" "due" "future"}
             (set (mapv :obligation/id (recovery/blocked-retries result)))))
      (doseq [id ["exhausted" "due" "future"]]
        (is (= :retry-wait (:phase (get all id))) id)
        (is (true? (:open? (get all id))) id))
      (is (empty? (:commits result)))
      (is (some (fn [finding]
                  (= :STORAGE_RETRY_UNSUPPORTED (:error finding)))
                (:attention result))))))


(deftest generation-and-blocked-entry-helpers-test
  (let [a (recovery/new-generation)
        b (recovery/new-generation)]
    (is (string? a))
    (is (not= a b)))
  (is (= :blocked (:status (recovery/blocked-entry {:status :error
                                                    :error {:error :STORAGE_CORRUPT}
                                                    :path "/db"}))))
  (is (nil? (recovery/blocked-entry {:status :ok})))
  (support/with-temp-store [store {}]
    (is (= :STORAGE_INVALID_OPTIONS
           (try
             (recovery/recover! store {:current-generation {}})
             nil
             (catch clojure.lang.ExceptionInfo e
               (storage/error-code e)))))))

(deftest retry-call-is-pending-not-blocked-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "seed-retry-call"
      :partition "p"
      :session-id "s"
      :obligations
      {:put [{:obligation/id "retry/call-1"
              :obligation/kind :retry-call
              :open? true
              :phase :retry-wait
              :session-id "s"
              :call-id "call-1"
              :attempts-used 1
              :max-attempts 3
              :retry-at 500
              :effects {:status :none :repeat-safe? true}}
             {:obligation/id "retry/call-2"
              :obligation/kind :retry-call
              :open? true
              :phase :ready
              :session-id "s"
              :call-id "call-2"
              :attempts-used 2
              :max-attempts 3
              :retry-at 500
              :effects {:status :none :repeat-safe? true}}
             {:obligation/id "retry/call-3"
              :obligation/kind :retry-call
              :open? true
              :phase :retry-wait
              :session-id "s"
              :call-id "call-3"
              :attempts-used 3
              :max-attempts 3
              :retry-at 500
              :effects {:status :none :repeat-safe? true}}]}})
    (let [result (recovery/recover-all! store {:partition "p"
                                               :session-id "s"
                                               :now-ms 100
                                               :current-generation "gen-1"})
          open (storage/list-open-obligations store {:partition "p"
                                                     :session-id "s"})
          obligation (first (:obligations open))]
      (is (= :ok (:status result)))
      (is (= ["retry/call-1" "retry/call-2" "retry/call-3"]
             (mapv :obligation/id (:pending-retries result))))
      (is (empty? (:blocked-retries result))
          "a retry-call index is owner-owned, not an unsupported storage retry")
      (is (= :retry-wait (:phase obligation)))
      (is (true? (:open? obligation)))
      (is (= 500 (:retry-at obligation))
          "recovery must not move the Owner's persisted retry-at")
      (is (= :retry-wait
             (:phase (first (filter #(= "retry/call-3" (:obligation/id %))
                                    (:obligations open)))))
          "an exhausted retry-call is left for the Owner, not faked terminal"))))

(deftest report-no-owner-stays-pending-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "seed"
      :partition "p"
      :session-id "s"
      :obligations
      {:put [{:obligation/id "report/r1" :obligation/kind :report
              :open? true :phase :report-pending
              :report {:report-id "r1" :parent-call-id "c1"}}]}})
    (let [result (recovery/recover-all! store {:partition "p"
                                               :session-id "s"
                                               :current-generation "gen-1"
                                               :deliver-report (fn [_] {:accepted? false
                                                                        :reason :no-owner})})
          obligation (first (:pending-reports result))]
      (is (= :ok (:status result)))
      (is (= ["report/r1"] (mapv :obligation/id (:pending-reports result))))
      (is (= :report-pending (:phase obligation)))
      (is (true? (:open? obligation))))))

(deftest terminal-open-obligation-is-closed-test
  (is (= {:action :none :open? false :reason :terminal}
         (decision {:obligation/id "o" :open? true :phase :failed} {})))
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "terminal-open"
      :partition "p"
      :session-id "s"
      :obligations
      {:put [{:obligation/id "failed-open" :open? true :phase :failed
              :updated-at 123}]}})
    (let [result (recovery/recover! store {:partition "p"
                                           :session-id "s"
                                           :current-generation "g"
                                           :now-ms 200})
          export (storage/export-state store {:partition "p"})
          doc (some #(when (= "failed-open" (:id %)) (:doc %))
                    (get-in export [:partitions "p" :obligations]))]
      (is (= :ok (:status result)))
      (is (false? (:open? doc)))
      (is (= 123 (:updated-at doc))
          "recovery must not change the producer's numeric timestamp")
      (is (not (contains? doc :storage/partition)))
      (is (not (contains? doc :storage/updated-at))))))

(deftest recovery-entry-lock-options-test
  (support/with-temp-store [store {}]
    (let [entry (recovery/recovery-entry {:path (storage/store-path store)})]
      (is (= :blocked (:status entry)))
      (is (= :STORAGE_LOCKED (get-in entry [:error :error])))
      (is (some #{:stop-other-process} (get-in entry [:recovery-entry :options]))))))
