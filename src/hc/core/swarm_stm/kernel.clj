(ns hc.core.swarm-stm.kernel
  "Atomic in-process kernel for the phase-one swarm board.

  The kernel is a single atom holding an immutable board value.  A command is
  applied by a pure function and published with compare-and-set!, so exactly
  one linearizable transition wins.  The atom is not a substitute for the
  protocol's STM semantics: every command handler validates complete
  preconditions and returns a new immutable board, and derived indexes,
  retirement and wake-all are computed from the final state in the same
  commit."
  (:require [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.commands :as commands]))

(defn create
  "Create a kernel.  Options:
  :verify-read-set pure fn of a finish read-set (phase-two workspace_gate seam);
                   required for any non-empty read-set
  :on-events       fn called with committed events after CAS.  Its return
                   value is observed: `{:ok? false ...}` means the committed
                   transition could not be made durable.  The board change is
                   not rolled back, but the command reports
                   `:BOARD_EVENT_PERSIST_FAILED` and `:persisted? false`, so a
                   caller must not proceed to a side effect.
  :initial-board   board value, mainly for deterministic tests"
  ([] (create {}))
  ([{:keys [verify-read-set on-events initial-board]}]
   (let [initial (or initial-board (board/empty-board))
         initial (board/derive initial)]
     {:state (atom initial)
      :env {:verify-read-set verify-read-set}
      :on-events (or on-events (fn [_] nil))})))

(defn- publish-events!
  [kernel events]
  (when (seq events)
    (try
      ((:on-events kernel) events)
      (catch Throwable t
        {:ok? false
         :error {:code :BOARD_EVENT_PERSIST_FAILED
                 :message (or (ex-message t) (str t))}}))))

(defn- persisted-result
  "Keep the committed command result, but fail it closed when the event hook
  could not make that transition durable.  The board is already published;
  callers must not treat this as a rollback or retry the side effect."
  [result published cmd]
  (if (and (map? published) (false? (:ok? published)))
    (assoc result
           :ok? false
           :persisted? false
           :error :BOARD_EVENT_PERSIST_FAILED
           :persist-error (:error published)
           :detail {:request-id (:request-id cmd)
                    :requires-checkpoint? true})
    result))

(defn- mark-request-persisted!
  "Mark the committed request as durable after the event hook succeeded.
  A checkpoint persists the whole board value, so it clears every pending
  request marker that was already present in the checkpoint's committed board."
  [kernel cmd committed-board]
  (let [rid (:request-id cmd)
        checkpoint? (= :checkpoint (:op cmd))
        checkpoint-rids (when checkpoint?
                          (set (keys (:requests committed-board))))]
    (when (or rid checkpoint?)
      (swap! (:state kernel)
             (fn [b]
               (cond
                 checkpoint?
                 (update b :requests
                         (fn [requests]
                           (into {}
                                 (map (fn [[k v]]
                                        [k (if (contains? checkpoint-rids k)
                                             (assoc v :persisted? true)
                                             v)]))
                                 requests)))
                 (get-in b [:requests rid])
                 (assoc-in b [:requests rid :persisted?] true)
                 :else b))))))

(defn- execute-in-env!
  [kernel cmd env]
  (let [env (assoc env :observed-board @(:state kernel))]
    (loop []
      (let [old @(:state kernel)
            out (commands/execute old cmd env)]
        (if (:ok? out)
          (if (compare-and-set! (:state kernel) old (:board out))
            (let [published (publish-events! kernel (:events out))]
              (when-not (and (map? published) (false? (:ok? published)))
                (mark-request-persisted! kernel cmd (:board out)))
              (persisted-result (:result out) published cmd))
            (recur))
          (:result out))))))

(defn requires-write-gate?
  "True when CMD contains a finish with a non-empty read-set or verified
  read-set.  Such a finish must be verified and committed while W is held."
  [cmd]
  (or (and (= :finish (:op cmd))
           (or (seq (:read-set cmd))
               (seq (:verified-read-set cmd))))
      (and (= :batch (:op cmd))
           (some requires-write-gate? (:commands cmd)))))

(defn- prior-request-outcome
  [kernel cmd]
  (when-let [rid (:request-id cmd)]
    (when-let [prior (get-in @(:state kernel) [:requests rid])]
      (if (= (:command prior) (dissoc cmd :request-id))
        (if (false? (:persisted? prior))
          {:ok? false
           :error :BOARD_EVENT_PERSIST_FAILED
           :persisted? false
           :board-version (:board-version @(:state kernel))
           :detail {:request-id rid
                    :requires-checkpoint? true}}
          {:ok? true :result (:result prior)})
        {:ok? false
         :error :REQUEST_ID_CONFLICT
         :board-version (:board-version @(:state kernel))
         :detail {:request-id rid}}))))

(defn execute!
  "Apply CMD atomically.  Returns the command result map.  A successful
  request-id is replayed without changing the board.

  A finish with a non-empty read-set or verified-read-set is rejected with
  :WRITE_GATE_REQUIRED here.  Use execute-under-write-gate! so verification
  stays inside W through the board CAS."
  [kernel cmd]
  (execute-in-env! kernel cmd (:env kernel)))

(defn execute-under-write-gate!
  "Run CMD while holding the injected write gate W.

  GATE is a port map {:acquire (fn [owner] permit) :release (fn [permit])}.
  For a file-dependent finish, W is acquired before the command, the verifier
  runs inside the command, the board CAS/commit and event publication happen
  before release, and W is released in a finally block.  Non-file-dependent
  commands are executed without acquiring W.

  Do not call this while already holding the same non-reentrant W permit."
  [kernel gate owner cmd]
  (if-let [prior (prior-request-outcome kernel cmd)]
    (if (:ok? prior) (:result prior) prior)
    (if-not (requires-write-gate? cmd)
      (execute! kernel cmd)
      (let [{:keys [acquire release]} gate]
        (if-not (and (ifn? acquire) (ifn? release))
          {:ok? false
           :error :WRITE_GATE_PORT_REQUIRED
           :board-version (:board-version @(:state kernel))}
          (let [acquired (try
                           {:permit (acquire owner)}
                           (catch Throwable e
                             {:error e}))]
            (if-let [e (:error acquired)]
              (if (and (instance? clojure.lang.ExceptionInfo e)
                       (contains? (ex-data e) :status)
                       (contains? (ex-data e) :outcome))
                (assoc (ex-data e)
                       :ok? false
                       :board-version (:board-version @(:state kernel)))
                {:ok? false
                 :error :WRITE_GATE_ACQUIRE_FAILED
                 :detail {:message (.getMessage e)}
                 :board-version (:board-version @(:state kernel))})
              (let [permit (:permit acquired)]
                (if (nil? permit)
                  {:ok? false
                   :error :WRITE_GATE_NOT_ACQUIRED
                   :board-version (:board-version @(:state kernel))}
                  (try
                    (execute-in-env! kernel cmd
                                     (assoc (:env kernel) :under-write-gate? true))
                    (finally
                      (release permit))))))))))))

(defn execute-batch!
  "Apply several commands in one board transition.  All commands must succeed;
  otherwise nothing is committed.  Sub-commands may not carry request-id.
  A batch containing a file-dependent finish must use
  execute-batch-under-write-gate!."
  [kernel cmds]
  (execute! kernel {:op :batch :commands (vec cmds)}))

(defn execute-batch-under-write-gate!
  "Batch form of execute-under-write-gate!."
  [kernel gate owner cmds]
  (execute-under-write-gate! kernel gate owner
                             {:op :batch :commands (vec cmds)}))

(defn snapshot
  [kernel]
  @(:state kernel))

(defn board-version
  [kernel]
  (:board-version (snapshot kernel)))

(defn task
  [kernel task-id]
  (get-in (snapshot kernel) [:tasks task-id]))

(defn actor
  [kernel agent-id]
  (get-in (snapshot kernel) [:actors agent-id]))

(defn responsibility
  [kernel agent-id]
  (board/responsibility (snapshot kernel) agent-id))

(defn global-progressable?
  [kernel]
  (board/global-progressable? (snapshot kernel)))

(defn waiters
  [kernel]
  (set (keys (:waiters (snapshot kernel)))))

(defn events
  [kernel]
  (:events (snapshot kernel)))

(defn check-invariants
  [kernel]
  (board/check-invariants (snapshot kernel)))

(defn replace-board!
  "Atomically replace the kernel board with NEW-BOARD only while the current
  board is still EXPECTED.

  EXPECTED must be the exact value previously read from `snapshot`.  NEW-BOARD
  is derived before the compare-and-set.  Returns true when this caller
  published the board and false when another command committed first.  A
  caller that read a board, computed a replacement, and must not overwrite a
  concurrent command uses this instead of `reset-board!`."
  [kernel expected new-board]
  (compare-and-set! (:state kernel) expected (board/derive new-board)))

(defn reset-board!
  "Replace the kernel state.  Intended for deterministic tests, never for a
  protocol participant."
  [kernel new-board]
  (reset! (:state kernel) (board/derive new-board)))
