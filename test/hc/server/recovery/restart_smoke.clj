(ns hc.server.recovery.restart-smoke
  "Child-process driver for the real restart recovery smoke.

  Three separate JVMs share one store directory:

    seed    commits documents and obligations in two partitions and both
            session scopes, including :dispatching, :started-unobserved,
            :running, :report-pending and :report-accepted cases
    recover opens through recovery-entry, runs recover-all! with a probe and
            a report delivery function, and verifies the stored phases
    verify  reopens and re-checks the final state and the integrity scan

  This is not a test namespace; `hc.server.recovery.restart-smoke-test` starts
  it in child JVMs."
  (:require [hc.server.recovery :as recovery]
            [hc.server.storage :as storage])
  (:gen-class))

(defn- fail! [message data]
  (binding [*out* *err*]
    (println "RESTART-SMOKE-FAIL" message (pr-str data)))
  (System/exit 2))

(defn- require! [condition message data]
  (when-not condition
    (fail! message data)))

(defn- open! [dir opts]
  (let [entry (recovery/recovery-entry (merge {:path dir} opts))]
    (if (= :opened (:status entry))
      (:store entry)
      (fail! "recovery-entry blocked" entry))))

(defn- obligations-by-id [store]
  (->> (storage/list-open-obligations store {:all-partitions? true
                                             :open-only? false})
       :obligations
       (into {} (map (juxt :obligation/id identity)))))

(defn- seed! [dir]
  (let [store (open! dir {:create? true})]
    (storage/commit-migration!
     store
     {:migration-id "seed-session"
      :partition "session/s1"
      :session-id "s1"
      :put [{:kind :session :id "s1" :doc {:title "restart"}}
            {:kind :task :id "t1" :doc {:state :ready}}]
      :obligations
      {:put [{:obligation/id "ready" :open? true :phase :ready}
             {:obligation/id "disp" :open? true :phase :dispatching
              :idempotent? false}
             {:obligation/id "started" :open? true :phase :started-unobserved}
             {:obligation/id "report-ok" :open? true :phase :report-pending
              :report {:report-id "r1" :parent-call-id "c1"}}
             {:obligation/id "retry-exhausted" :open? true :phase :retry-wait
              :attempts-used 3 :max-attempts 3 :retry-at 50}]}})
    (storage/commit-migration!
     store
     {:migration-id "seed-partition"
      :partition "session/s1"
      :obligations
      {:put [{:obligation/id "run" :open? true :phase :running
              :executor-generation 1 :safe-to-repeat? false}
             {:obligation/id "report-pending" :open? true :phase :report-pending
              :report {:report-id "r2" :parent-call-id "c2"}}]}})
    (storage/commit-migration!
     store
     {:migration-id "seed-master"
      :partition "master"
      :obligations
      {:put [{:obligation/id "master-run" :open? true :phase :running
              :executor-generation 1 :safe-to-repeat? false}
             {:obligation/id "retry-future" :open? true :phase :retry-wait
              :attempts-used 1 :max-attempts 3 :retry-at 9999999999999}]}})
    (storage/close-store! store)
    (println "OK")))

(defn- recover! [dir]
  (let [store  (open! dir {})
        result (recovery/recover-all!
                store
                {:current-generation 2
                 :probe (fn [obligation]
                          (if (= "started" (:obligation/id obligation))
                            {:state :succeeded}
                            {:state :unknown}))
                 :deliver-report (fn [obligation]
                                   (if (= "r1" (get-in obligation [:report :report-id]))
                                     {:accepted? true}
                                     {:accepted? false}))})
        all    (obligations-by-id store)]
    (require! (= :ok (:status result)) "recover-all! did not return :ok" result)
    (require! (= 9 (:scanned result)) "unexpected scan count" result)
    (require! (= #{"session/s1" "master"} (set (:partitions result)))
              "recover-all! missed a partition" result)
    (require! (= 3 (count (:commits result)))
              "expected three scope commits" result)
    (require! (= ["report-pending"]
                 (mapv :obligation/id (:pending-reports result)))
              "wrong pending reports" result)
    (require! (and (contains? result :pending-retries)
                   (empty? (:pending-retries result)))
              "pending-retries must be present and empty for legacy blocked retries"
              result)
    (require! (= #{"retry-exhausted" "retry-future"}
                 (set (mapv :obligation/id (:blocked-retries result))))
              "wrong blocked retry set" result)
    (require! (= #{"disp" "run" "master-run"}
                 (set (mapv :obligation/id (:outcome-unknown result))))
              "wrong outcome-unknown set" result)
    (require! (= :ready (:phase (get all "ready"))) "ready changed" all)
    (require! (= :outcome-unknown (:phase (get all "disp"))) "disp" all)
    (require! (false? (:open? (get all "started"))) "started not closed" all)
    (require! (= :report-accepted (:phase (get all "report-ok"))) "report-ok" all)
    (require! (false? (:open? (get all "report-ok"))) "report-ok open" all)
    (require! (= :outcome-unknown (:phase (get all "run"))) "run" all)
    (require! (= :report-pending (:phase (get all "report-pending"))) "pending" all)
    (require! (true? (:open? (get all "report-pending"))) "pending open" all)
    (require! (= :outcome-unknown (:phase (get all "master-run"))) "master-run" all)
    (require! (= :retry-wait (:phase (get all "retry-exhausted"))) "retry-exhausted" all)
    (require! (true? (:open? (get all "retry-exhausted"))) "retry-exhausted open" all)
    (require! (= :retry-wait (:phase (get all "retry-future"))) "retry-future" all)
    (require! (true? (:open? (get all "retry-future"))) "retry-future open" all)
    (require! (:ok? (storage/verify-integrity store)) "integrity failed" {})
    (storage/close-store! store)
    (println "OK")))

(defn- verify! [dir]
  (let [store (open! dir {})
        all   (obligations-by-id store)
        view  (storage/read-session-view store {:partition "session/s1"
                                                :session-id "s1"})]
    (require! (= :ready (:phase (get all "ready"))) "ready changed" all)
    (require! (= :outcome-unknown (:phase (get all "disp"))) "disp" all)
    (require! (false? (:open? (get all "started"))) "started" all)
    (require! (= :report-accepted (:phase (get all "report-ok"))) "report-ok" all)
    (require! (false? (:open? (get all "report-ok"))) "report-ok open" all)
    (require! (= :outcome-unknown (:phase (get all "run"))) "run" all)
    (require! (= :report-pending (:phase (get all "report-pending"))) "pending" all)
    (require! (true? (:open? (get all "report-pending"))) "pending open" all)
    (require! (= :outcome-unknown (:phase (get all "master-run"))) "master-run" all)
    (require! (= :retry-wait (:phase (get all "retry-exhausted"))) "retry-exhausted" all)
    (require! (true? (:open? (get all "retry-exhausted"))) "retry-exhausted open" all)
    (require! (= :retry-wait (:phase (get all "retry-future"))) "retry-future" all)
    (require! (true? (:open? (get all "retry-future"))) "retry-future open" all)
    (require! (= "restart" (:title (:session view))) "session document lost" view)
    (require! (= ["t1"] (mapv :storage/id (:tasks (:board view)))) "task lost" view)
    (require! (:ok? (storage/verify-integrity store)) "integrity failed" {})
    (storage/close-store! store)
    (println "OK")))

(defn -main [& args]
  (let [mode (first args)
        dir (second args)]
    (case mode
      "seed" (seed! dir)
      "recover" (recover! dir)
      "verify" (verify! dir)
      (fail! "unknown mode" {:mode mode}))))
