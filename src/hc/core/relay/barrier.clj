(ns hc.core.relay.barrier
  "Quiescence predicates and context-barrier construction.

  The barrier is deliberately narrow: the successor sees the raw user input
  history, the draft, the current workspace, and a system successor hint.
  It never sees the predecessor's ledger, findings, board, actions, or
  private conversation."
  (:require [clojure.string :as str]
            [hc.core.relay.ledger :as ledger]))

(def visible-context-keys
  #{:user-input :user-attachments :draft-path :draft :workspace
    :milestone :successor-hint})

(defn- valid-count?
  "A quiescence counter is a non-negative integer.  `nil`, strings, and
  negative numbers are not counts; treating them as zero would let a
  malformed host snapshot pass the closing gate."
  [value]
  (and (integer? value) (not (neg? value))))

(defn- count-at
  [board k]
  (let [value (get board k)]
    (if (valid-count? value)
      (long value)
      0)))

(defn- count-invalid?
  [board k]
  (and (contains? board k)
       (not (valid-count? (get board k)))))

(defn- boolean-invalid?
  [board k]
  (and (contains? board k)
       (not (boolean? (get board k)))))

(defn inflight-count
  "Count in-flight side-effecting actions.

  Hosts may provide `:inflight-actions` as an already-summed count.  The
  individual phases `:applying`, `:running`, and `:uncertain` are summed as
  well, so a host that supplies both cannot accidentally hide work behind a
  stale aggregate."
  [board]
  (let [board (or board {})]
    (+ (count-at board :inflight-actions)
       (count-at board :applying)
       (count-at board :running)
       (count-at board :uncertain))))

(defn unresolved-count
  [board]
  (count-at (or board {}) :unresolved-tasks))

(defn execution-relay-reasons
  "Return every reason the execution relay barrier is not ready.

  The board is a snapshot owned by the swarm STM.  It must explicitly say
  that the holder has retired; absence is not consent.  Unhandled user
  input is public work (root AGENTS.md A02-20 / §3.5), so the barrier waits
  until every input has been handled; retiring the tenure would otherwise
  discard the only copy of that input."
  [tenure board]
  (let [board (or board {})]
    (cond-> []
      (not (contains? board :unresolved-tasks))
      (conj :unresolved-tasks-missing)

      (count-invalid? board :unresolved-tasks)
      (conj :unresolved-tasks-invalid)

      (not (contains? board :unhandled-inputs))
      (conj :unhandled-inputs-missing)

      (count-invalid? board :unhandled-inputs)
      (conj :unhandled-inputs-invalid)

      (not (contains? board :user-inputs))
      (conj :user-inputs-missing)

      (not (or (contains? board :inflight-actions)
               (contains? board :applying)
               (contains? board :running)
               (contains? board :uncertain)))
      (conj :inflight-actions-missing)

      (count-invalid? board :inflight-actions)
      (conj :inflight-actions-invalid)

      (count-invalid? board :applying)
      (conj :applying-invalid)

      (count-invalid? board :running)
      (conj :running-invalid)

      (count-invalid? board :uncertain)
      (conj :uncertain-invalid)

      (not (contains? board :holder-retired?))
      (conj :holder-state-missing)

      (boolean-invalid? board :holder-retired?)
      (conj :holder-state-invalid)

      (not (ledger/ledger-empty? (:ledger tenure)))
      (conj :ledger-not-empty)

      (not (every? #(= :done (:state %)) (:root-tasks tenure)))
      (conj :root-tasks-not-done)

      (pos? (unresolved-count board))
      (conj :unresolved-tasks)

      (pos? (count-at board :unhandled-inputs))
      (conj :unhandled-inputs)

      (pos? (inflight-count board))
      (conj :inflight-actions)

      (not (true? (:holder-retired? board)))
      (conj :holder-active)

      (boolean-invalid? board :all-root-tasks-done?)
      (conj :board-roots-invalid)

      (not (true? (:all-root-tasks-done? board)))
      (conj :board-roots-not-done))))

(defn execution-relay-ready?
  [tenure board]
  (and (map? board)
       (empty? (execution-relay-reasons tenure board))))

(defn closing-reasons
  "Per-tenure minimum quiescence set for the `收尾` -> `已完成` decision.

  The master integration lock belongs to join and is intentionally absent.
  Unhandled user input is included for the same reason as the relay
  barrier: completion must not strand a public input event."
  [board]
  (let [board (or board {})]
    (cond-> []
      (not (contains? board :applying)) (conj :applying-missing)
      (count-invalid? board :applying) (conj :applying-invalid)
      (not (contains? board :running)) (conj :running-missing)
      (count-invalid? board :running) (conj :running-invalid)
      (not (contains? board :uncertain)) (conj :uncertain-missing)
      (count-invalid? board :uncertain) (conj :uncertain-invalid)
      (not (contains? board :unhandled-inputs))
      (conj :unhandled-inputs-missing)
      (count-invalid? board :unhandled-inputs)
      (conj :unhandled-inputs-invalid)
      (not (contains? board :call-children)) (conj :call-children-missing)
      (count-invalid? board :call-children) (conj :call-children-invalid)
      (not (contains? board :report-pending)) (conj :report-pending-missing)
      (count-invalid? board :report-pending) (conj :report-pending-invalid)
      (not (contains? board :pending-rebase)) (conj :pending-rebase-missing)
      (count-invalid? board :pending-rebase) (conj :pending-rebase-invalid)
      (not (contains? board :holds-write-lock?))
      (conj :write-lock-state-missing)
      (boolean-invalid? board :holds-write-lock?)
      (conj :write-lock-state-invalid)
      (not (contains? board :unresolved-token-tasks))
      (conj :unresolved-token-tasks-missing)
      (count-invalid? board :unresolved-token-tasks)
      (conj :unresolved-token-tasks-invalid)
      (count-invalid? board :inflight-actions)
      (conj :inflight-actions-invalid)
      (count-invalid? board :unresolved-tasks)
      (conj :unresolved-tasks-invalid)

      (pos? (count-at board :applying)) (conj :applying)
      (pos? (count-at board :running)) (conj :running)
      (pos? (count-at board :uncertain)) (conj :uncertain)
      (pos? (count-at board :unhandled-inputs)) (conj :unhandled-inputs)
      (pos? (count-at board :inflight-actions)) (conj :inflight-actions)
      (pos? (count-at board :unresolved-tasks)) (conj :unresolved-tasks)
      (boolean-invalid? board :all-root-tasks-done?)
      (conj :board-roots-invalid)
      (and (contains? board :all-root-tasks-done?)
           (boolean? (:all-root-tasks-done? board))
           (not (true? (:all-root-tasks-done? board))))
      (conj :board-roots-not-done)
      (pos? (count-at board :call-children)) (conj :call-children)
      (pos? (count-at board :report-pending)) (conj :report-pending)
      (pos? (count-at board :pending-rebase)) (conj :pending-rebase)
      (and (contains? board :holds-write-lock?)
           (not (false? (:holds-write-lock? board)))) (conj :holds-write-lock)
      (pos? (count-at board :unresolved-token-tasks))
      (conj :unresolved-token-tasks))))

(defn closing-quiescent?
  [board]
  (and (map? board)
       (empty? (closing-reasons board))))

(defn ff-ready?
  [board]
  (true? (:ff-ready? board)))

(defn successor-context
  "Construct exactly the context a successor is allowed to observe.

  `:user-input` is every raw user text carried in `:user-inputs`, joined in
  arrival order.  `:user-attachments` carries only attachment references and
  display metadata.  Attachment content, resolutions, summaries, board
  records, and predecessor reasoning are never part of this context."
  [state tenure]
  (let [inputs (:user-inputs state)
        user-input (if (seq inputs)
                     (str/join "\n\n" (keep :text inputs))
                     (:user-input state))
        user-attachments (->> inputs
                              (mapcat (fn [input]
                                        (map (fn [attachment]
                                               (cond-> attachment
                                                 (:message-id input)
                                                 (assoc :message-id
                                                        (:message-id input))))
                                             (:attachments input))))
                              vec)
        context {:user-input user-input
                 :user-attachments user-attachments
                 :draft-path (get-in state [:draft :path])
                 :draft (get-in state [:draft :content])
                 :workspace :current
                 :milestone (:milestone tenure)
                 :successor-hint (:successor-hint state)}]
    (cond-> (into {}
                  (remove (fn [[_ v]] (nil? v)))
                  context)
      (empty? user-attachments) (dissoc :user-attachments))))

(defn context-keys-valid?
  [context]
  (and (map? context)
       (every? visible-context-keys (keys context))))

(defn make-barrier
  [state from-tenure to-tenure barrier-id]
  {:barrier-id barrier-id
   :session-id (:session-id state)
   :lane (:lane to-tenure)
   :from-tenure-id (:tenure-id from-tenure)
   :from-epoch (:epoch from-tenure)
   :from-milestone (:milestone from-tenure)
   :to-tenure-id (:tenure-id to-tenure)
   :to-epoch (:epoch to-tenure)
   :milestone (:milestone to-tenure)
   :visible-keys visible-context-keys
   :context (successor-context state to-tenure)
   :predecessor-hidden? true})
