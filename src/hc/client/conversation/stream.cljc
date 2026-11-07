(ns hc.client.conversation.stream
  "Streaming run state: follow-the-bottom, selection protection, stop freezing,
  and late-arrival classification.

  This namespace is deliberately independent of message storage.  A caller
  asks `chunk` or `tool-result` what to do with an arrival; only `:appended`
  and `:apply` may mutate the visible message.  Once a stop is requested the
  stream is frozen, so late text is counted but never appended, and late tool
  results become visible records labelled as having completed before the stop.

  Scroll requests are serializable counters, not DOM handles.  The view may
  read `:scroll-request` and scroll when it changes; the model never stores a
  node, timer, or editor instance."
  (:refer-clojure :exclude [chunk])
  (:require [clojure.string :as str]))

(def terminal-tool-statuses
  "Tool statuses that end the waiting display.  Argument completion alone is
  not a terminal status."
  #{:succeeded :success :completed :complete :done :failed :error :errored
    :cancelled :canceled :outcome-unknown :unknown :stopped})

(def bottom-threshold
  "Distance from the bottom, in logical pixels, that still counts as following.
  The view and the model share this value so one scroll observation cannot mean
  two different things in the two places that classify it."
  8)

(def ^:private unknown-stop-run
  "Sentinel for a stop requested before any run identity was known.

  The operator may click stop immediately after sending, before `RUN_STARTED`
  carries a run id.  Recording nil would let the same run's late start look
  like a different run and reopen the frozen stream.  The sentinel keeps the
  freeze; `start-run` adopts the first run id it sees as the stopped run."
  ::unknown-stop-run)

(defn- unknown-stop-run?
  [run-id]
  (= unknown-stop-run run-id))

(defn initial
  []
  {:follow? true
   :at-bottom? true
   :show-jump? false
   :selection nil
   :selection-locked? false
   :follow-before-selection? true
   :streaming? false
   :stop-state :idle
   :stop-reason nil
   :stop-requested-at nil
   :stop-run-id nil
   :streaming-before-stop nil
   :stopped-run-id nil
   :stopped-at nil
   :run-id nil
   :active-message-id nil
   :late-tool-results []
   :late-text-count 0
   :last-chunk-seq nil
   :content-version 0
   :scroll-request 0
   ;; Last observed scroll offset.  nil means "never measured", so a fresh
   ;; session is not forced to the top when it is selected again.
   :scroll-top nil
   :decision nil
   :pending-send nil
   :last-outcome {:kind :initial :result :idle}})

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn- outcome
  [state kind result details]
  (assoc state :last-outcome (merge {:kind kind :result result} details)))

(defn streaming?
  [state]
  (boolean (:streaming? state)))

(defn stopping?
  [state]
  (= :stopping (keywordize (:stop-state state))))

(defn stopped?
  [state]
  (= :stopped (keywordize (:stop-state state))))

(defn interrupted?
  [state]
  (= :interrupted (keywordize (:stop-state state))))

(defn frozen?
  "True once the operator asked to stop.  Freezing starts at the click, not
  when the server acknowledges it, so no late token can be spliced into the
  visible answer after the operator stopped reading."
  [state]
  (contains? #{:stopping :stopped :interrupted}
              (keywordize (:stop-state state))))

(defn status-label
  [state]
  (cond
    (stopping? state) "正在停止"
    (stopped? state) "已停止"
    (interrupted? state) "已中断"
    (streaming? state) "正在生成"
    :else "已完成"))

(defn tool-terminal?
  [status]
  (contains? terminal-tool-statuses (keywordize status)))

(defn tool-waiting?
  "A tool is still waiting when its arguments are complete but its domain
  status is not terminal.  Accept both plain and `:message/*` display keys."
  [tool]
  (let [arguments-complete? (or (:arguments-complete? tool)
                                (:message/arguments-complete? tool))
        status (or (:status tool) (:message/status tool))]
    (and (true? arguments-complete?)
         (not (tool-terminal? status)))))

;; ---------------------------------------------------------------------------
;; Scroll and selection

(defn scroll
  "Update follow mode from a scroll observation.

  A user scroll away from the bottom stops following immediately.  A
  programmatic scroll while following stays following.  Returning to the
  bottom resumes following and hides the jump button."
  [state {:keys [scroll-top scroll-height client-height threshold programmatic?]}]
  (let [threshold (or threshold 8)
        scroll-top (or scroll-top (:scroll-top state))
        distance (- (+ (or scroll-height 0))
                     (+ (or scroll-top 0) (or client-height 0)))
        at-bottom? (<= distance threshold)]
    (cond
      (and programmatic? (:follow? state))
      (-> state
          (assoc :scroll-top scroll-top
                 :at-bottom? at-bottom?
                 :show-jump? false)
          (outcome :scroll :programmatic {:at-bottom? at-bottom?}))

      at-bottom?
      (-> state
          (assoc :scroll-top scroll-top
                 :follow? true
                 :at-bottom? true
                 :show-jump? false)
          (outcome :scroll :follow {:at-bottom? true}))

      :else
      (-> state
          (assoc :scroll-top scroll-top
                 :follow? false
                 :at-bottom? false
                 :show-jump? true)
          (outcome :scroll :detached {:at-bottom? false})))))

(defn content-grew
  "Record visible growth.  Follow mode requests one scroll; detached reading
  only reveals the return-to-latest affordance.  A locked selection suppresses
  scrolling so the selected range does not move under the pointer."
  [state]
  (if (frozen? state)
    (outcome state :content-grew :ignored {:reason :frozen})
    (let [state (update state :content-version inc)]
      (if (and (:follow? state) (not (:selection-locked? state)))
        (-> state
            (assoc :at-bottom? true :show-jump? false)
            (update :scroll-request inc)
            (outcome :content-grew :follow {}))
        (-> state
            (assoc :show-jump? true)
            (outcome :content-grew :detached {}))))))

(defn selection
  "Update selection state.

  `:start` and `:change` lock follow so streaming layout cannot move the
  selected text.  `:end` releases the lock but keeps the selection and the
  detached reading position.  `:clear` removes the selection; returning to the
  bottom remains an explicit user action."
  [state {:keys [phase selection]}]
  (case phase
    :start
    (-> state
        (assoc :selection selection
               :selection-locked? true
               :follow-before-selection? (:follow? state)
               :follow? false
               :show-jump? true)
        (outcome :selection :locked {:selection selection}))

    :change
    (-> state
        (assoc :selection selection
               :selection-locked? true)
        (outcome :selection :changed {:selection selection}))

    :end
    (-> state
        (assoc :selection selection
               :selection-locked? false)
        (cond-> (seq selection)
          (assoc :show-jump? true))
        (outcome :selection :released {:selection selection}))

    :clear
    (-> state
        (assoc :selection nil
               :selection-locked? false)
        (outcome :selection :cleared {}))

    (outcome state :selection :ignored {:phase phase})))

(defn clear-selection
  [state]
  (selection state {:phase :clear}))

(defn jump-to-latest
  "Return to follow mode and request one scroll."
  [state]
  (-> state
      (assoc :follow? true
             :at-bottom? true
             :show-jump? false)
      (update :scroll-request inc)
      (outcome :jump :applied {})))

;; ---------------------------------------------------------------------------
;; Run lifecycle

(defn start-run
  "Begin a new run.  A previous stop belongs to the previous run and must not
  freeze the next one.

  A start event for the run that is already being stopped is a late arrival,
  not a new run: keeping the freeze prevents a stop receipt from being ignored
  because the visible stream was reopened after the operator clicked stop.

  When the stop was requested before a run id was known, the first start event
  is adopted as the stopped run and still ignored.  A nil stop id would make
  every late start look like a new run and silently reopen the answer.

  `:force? true` is the explicit escape for a caller that has independent
  evidence of a new visible run (for example a text delta for a different
  message id after a reconnect).  It never comes from a bare RUN_STARTED."
  ([state] (start-run state {}))
  ([state {:keys [run-id force?]}]
   (let [late-results (:late-tool-results state)]
     (if force?
       (-> state
           (merge (initial))
           (assoc :streaming? true
                  :run-id run-id
                  :late-tool-results late-results)
           (outcome :start-run :applied {:run-id run-id :forced? true}))
       (let [current-run-id (:run-id state)
             stop-run-id (:stop-run-id state)
             stopped-run-id (:stopped-run-id state)
             current? (and run-id (= run-id current-run-id))
             stopped-run? (and run-id
                               (or (= run-id stop-run-id)
                                   (= run-id stopped-run-id)))
             unknown-stop? (or (and (stopping? state)
                                    (unknown-stop-run? stop-run-id))
                               (and (stopped? state)
                                    (unknown-stop-run? stopped-run-id)))]
         (cond
           (and (stopping? state) (or current? stopped-run? unknown-stop?))
           (outcome (cond-> state
                      (and run-id (nil? current-run-id))
                      (assoc :run-id run-id)
                      (and run-id (unknown-stop-run? stop-run-id))
                      (assoc :stop-run-id run-id))
                    :start-run :ignored
                    {:reason :stop-pending :run-id run-id})

           (and (stopped? state) (or stopped-run? unknown-stop?))
           (outcome (cond-> state
                      (and run-id (nil? current-run-id))
                      (assoc :run-id run-id)
                      (and run-id (unknown-stop-run? stopped-run-id))
                      (assoc :stopped-run-id run-id))
                    :start-run :ignored
                    {:reason :already-stopped :run-id run-id})

           (and (or (interrupted? state)
                    (= :finished (get-in state [:last-outcome :kind])))
                current?)
           (outcome (cond-> state
                      (and run-id (nil? current-run-id))
                      (assoc :run-id run-id))
                    :start-run :ignored
                    {:reason :run-ended :run-id run-id})

           :else
           (-> state
               (merge (initial))
               (assoc :streaming? true
                      :run-id run-id
                      ;; Late tool records belong to the stopped answer, not
                      ;; to the run that happens to start next.  Keep them
                      ;; until the stopped message is replaced by a snapshot.
                      :late-tool-results late-results)
               (outcome :start-run :applied {:run-id run-id}))))))))

(defn begin
  "Record the active message without discarding scroll or selection state."
  [state {:keys [message-id]}]
  (-> state
      (assoc :active-message-id message-id
             :streaming? true)
      (outcome :begin :applied {:message-id message-id})))

(defn chunk
  "Classify one text chunk.

  Results:

    :appended  append to the visible message
    :late-text  do not append; the stop was already requested
    :stale      sequence already passed; drop it
  "
  [state {:keys [seq text message-id] :as chunk}]
  (cond
    (frozen? state)
    (-> state
        (update :late-text-count inc)
        (outcome :chunk :late-text
                 {:message-id message-id
                  :seq seq
                  :length (count (or text ""))}))

    (and (integer? seq)
         (integer? (:last-chunk-seq state))
         (or (< seq (:last-chunk-seq state))
             (and (= seq (:last-chunk-seq state))
                  (= (or text "") (or (:last-chunk-text state) "")))))
    (outcome state :chunk :stale {:seq seq :last-seq (:last-chunk-seq state)})

    (and (integer? seq)
         (= seq (:last-chunk-seq state))
         (not= (or text "") (or (:last-chunk-text state) "")))
    (let [state (assoc state :last-chunk-text (or text ""))
          grown (content-grew state)]
      (outcome grown :chunk :appended
               {:message-id message-id :seq seq :length (count (or text ""))
                :scroll-request (:scroll-request grown)}))

    :else
    (let [state (cond-> state
                  (some? message-id) (assoc :active-message-id message-id)
                  true (assoc :streaming? true)
                  (integer? seq) (assoc :last-chunk-seq seq :last-chunk-text (or text "")))
          grown (content-grew state)]
      (outcome grown :chunk :appended
               {:message-id message-id
                :seq seq
                :length (count (or text ""))
                :scroll-request (:scroll-request grown)}))))

(defn tool-result
  "Classify a tool result.

  A result that arrives after the stop is preserved as a visible late record.
  It never reopens the model turn and never appends to the stopped text."
  [state {:keys [tool-call-id status message-id completed-before-stop? at]
          :as result}]
  (if (frozen? state)
    (-> state
        (update :late-tool-results conj
                {:tool-call-id tool-call-id
                 :message-id (or message-id (:active-message-id state))
                 :status status
                 :completed-before-stop? (boolean completed-before-stop?)
                 :at at
                 :label (if completed-before-stop?
                          "停止前已执行，结果后来返回"
                          "停止时仍未完成，结果后来返回")})
        (outcome :tool-result :late-tool-result
                 {:tool-call-id tool-call-id :status status}))
    (outcome state :tool-result :apply
             {:tool-call-id tool-call-id :status status})))

(defn stop-requested
  "Freeze the visible answer immediately and mark the stop as in progress."
  ([state at] (stop-requested state at nil))
  ([state at run-id]
   (if (or (stopping? state) (stopped? state))
     (outcome state :stop-requested :ignored {:reason :already-stopping})
     (-> state
         (assoc :stop-state :stopping
                :stop-requested-at at
                :stop-run-id (or run-id (:run-id state) unknown-stop-run)
                :stop-error nil
                :streaming-before-stop (:streaming? state)
                :streaming? true)
         (outcome :stop-requested :applied {:run-id run-id})))))

(defn stop-rejected
  "The server did not accept the stop.  The run may still be producing, so
  follow mode is restored to its pre-stop state rather than pretending the
  answer ended.

  `:streaming?` is restored to its pre-stop value.  A stop requested for an
  idle session (unattended work outside the visible stream) must not leave the
  composer permanently in `:generating` after the Owner refuses the stop."
  [state error]
  (let [streaming? (if (contains? state :streaming-before-stop)
                     (:streaming-before-stop state)
                     (:streaming? state))]
    (-> state
        (assoc :stop-state :idle
               :stop-error error
               :streaming? (boolean streaming?)
               :stop-run-id nil
               :streaming-before-stop nil)
        (outcome :stop-rejected :rejected {:error error}))))

(defn send-rejected
  "The send command never became a run.  Keep the draft recovery outside this
  namespace, but clear the optimistic run flags."
  [state error]
  (-> state
      (assoc :streaming? false
             :stop-state :idle
             :stop-run-id nil
             :streaming-before-stop nil
             :decision nil
             :send-error error)
      (outcome :send-rejected :rejected {:error error})))

(defn stop-confirmed
  [state {:keys [reason at]}]
  (let [candidate (or (:stop-run-id state) (:run-id state))
        stopped-run-id (if (unknown-stop-run? candidate)
                         (:run-id state)
                         candidate)]
    (-> state
        (assoc :stop-state :stopped
               :stop-reason reason
               ;; If no run id ever arrived, keep the sentinel so the same
               ;; run's late start cannot reopen the stopped answer.
               :stop-run-id (or stopped-run-id unknown-stop-run)
               :stopped-run-id (or stopped-run-id unknown-stop-run)
               :stopped-at at
               :streaming? false
               :streaming-before-stop nil)
        (outcome :stop-confirmed :applied {:reason reason}))))

(defn finished
  "The server ended the run.  A stop that was requested but not confirmed
  becomes `:stopped`; a normal finish returns to `:idle`; an already stopped
  answer stays stopped."
  [state]
  (let [stopping? (stopping? state)
        candidate (or (:stop-run-id state) (:run-id state))
        stopped-run-id (if (unknown-stop-run? candidate)
                         (:run-id state)
                         candidate)]
    (-> state
        (assoc :streaming? false
               :stop-state (if (or stopping? (stopped? state))
                             :stopped
                             :idle)
               :stop-run-id (if (or stopping? (stopped? state))
                              (or stopped-run-id unknown-stop-run)
                              (:stop-run-id state))
               :stopped-run-id (cond
                                 stopped-run-id stopped-run-id
                                 (or stopping? (stopped? state))
                                 unknown-stop-run
                                 :else (:stopped-run-id state))
               :streaming-before-stop nil)
        (outcome :finished :applied {})
        (dissoc :decision))))

(defn interrupted
  [state reason]
  (-> state
      (assoc :streaming? false
             :stop-state :interrupted
             :stop-reason reason)
      (outcome :interrupted :applied {:reason reason})))

(defn late?
  [state]
  (frozen? state))
