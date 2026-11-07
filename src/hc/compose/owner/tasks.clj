(ns hc.compose.owner.tasks
  "Explicit business combinators over Missionary tasks (AGENTS.md §5.3.4).

  Each combinator returns a task that completes with a data value.  A
  business failure never throws out of the combinator, so one branch cannot
  cancel its siblings through `m/join` fail-fast by accident.

    run-required-steps     all steps must succeed; first failure cancels the
                           group with :parent-terminated
    run-independent-tools  every branch runs; each failure is a value
    run-first-accepted     first success wins; remaining branches are
                           cancelled with :sibling-accepted

  Multi-worktree join is a FIFO queue, not `run-first-accepted`; it does not
  belong here."
  (:require [hc.compose.retry.error :as retry-error]
            [missionary.core :as m])
  (:import [missionary Cancelled]))

(defn- error-data
  [^Throwable error]
  (let [data (ex-data error)
        classification (retry-error/classify data)]
    (merge {:kind (or (:kind data) :unknown)
            :message (or (not-empty (str (.getMessage error)))
                         (.getName (class error)))
            :class (.getName (class error))}
           (select-keys classification
                        [:category :retryable? :reconcilable? :cancelled?]))))

(defn- cancelled?
  [^Throwable error]
  (or (instance? Cancelled error)
      (= :cancelled (:category (retry-error/classify (ex-data error))))))

(defn run-required-steps
  "Run TASKS concurrently; all must succeed.

  A failure cancels the remaining steps with `:parent-terminated` and
  completes with `{:result/status :failed :result/error ...}`.  An external
  cancellation completes with `{:result/status :cancelled
  :reason :parent-terminated}`."
  [tasks]
  (m/sp
    (try
      {:result/status :succeeded
       :value (m/? (apply m/join vector tasks))}
      (catch Throwable error
        (if (cancelled? error)
          {:result/status :cancelled :reason :parent-terminated}
          {:result/status :failed
           :result/error (error-data error)})))))

(defn- branch-outcome
  [id thunk]
  (try
    {:id id :result/status :succeeded :value (thunk)}
    (catch Throwable error
      (if (cancelled? error)
        {:id id :result/status :cancelled :reason :parent-terminated}
        {:id id :result/status :failed :result/error (error-data error)}))))

(defn run-independent-tools
  "Run TASKS concurrently and collect every outcome.

  No branch cancels a sibling.  The returned task always succeeds with
  `{:result/status :succeeded :results [outcome ...]}` unless the caller
  cancels it."
  [tasks]
  (let [indexed (vec (map-indexed (fn [index task] [index task]) tasks))]
    (m/sp
      (try
        (let [thunks (m/? (apply m/all vector (map second indexed)))
              results (mapv (fn [[id _] thunk]
                              (branch-outcome id thunk))
                            indexed thunks)]
          {:result/status :succeeded :results results})
        (catch Throwable error
          (if (cancelled? error)
            {:result/status :cancelled :reason :parent-terminated}
            {:result/status :failed :result/error (error-data error)}))))))

(defn run-first-accepted
  "Run TASKS concurrently; the first success wins.

  Remaining branches are cancelled with `:sibling-accepted`.  If every task
  fails, the task completes with `{:result/status :failed
  :result/error ...}` and no branch is reported as an upstream cancellation."
  [tasks]
  (m/sp
    (try
      {:result/status :accepted
       :reason :sibling-accepted
       :value (m/? (apply m/race tasks))}
      (catch Throwable error
        (if (cancelled? error)
          {:result/status :cancelled :reason :sibling-accepted}
          {:result/status :failed
           :result/error (error-data error)})))))
