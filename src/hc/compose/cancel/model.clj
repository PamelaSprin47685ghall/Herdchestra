(ns hc.compose.cancel.model
  "Pure cancellation tree for one session.

  The tree is plain EDN.  Every node has a stable `:node-id`, a `:kind`, a
  `:parent-id`, and a kind-specific `:state`.  The session points at its
  active run; other runs may coexist and are never touched by a cancellation
  rooted at a different run.

  Kinds and states follow the four-layer lifecycle in AGENTS.md §5.1 and the
  board action phases in §3.2:

    :run      :preparing :running :cancelling :cancelled :succeeded :failed
    :call     :ready :dispatching :running :retry-wait
              :succeeded :failed :cancelled :outcome-unknown
    :attempt  :dispatching :running
              :succeeded :failed :cancelled :outcome-unknown
    :action   :claimed :preparing :waiting-gate :applying :running
              :finished :uncertain
    :process  :starting :running :stopping :exited :uncertain

  `:outcome-unknown` and `:uncertain` are outcomes, not execution states.
  They survive cancellation unchanged.  A derived `:node-seq` preserves the
  breadth-first order used by cancellation propagation; it is assigned by
  `add-node` and is not an independent authority.

  This namespace never starts, stops, or interrupts anything.  It only
  validates and updates immutable data."
  (:require [clojure.string :as str]))

(def node-kinds
  #{:run :call :attempt :action :process})

(def states-by-kind
  {:run #{:preparing :running :cancelling :cancelled :succeeded :failed}
   :call #{:ready :dispatching :running :retry-wait
           :succeeded :failed :cancelled :outcome-unknown}
   :attempt #{:dispatching :running
              :succeeded :failed :cancelled :outcome-unknown}
   :action #{:claimed :preparing :waiting-gate :applying :running
             :finished :uncertain}
   :process #{:starting :running :stopping :exited :uncertain}})

(def terminal-states-by-kind
  "States that no cancellation may rewrite."
  {:run #{:cancelled :succeeded :failed}
   :call #{:succeeded :failed :cancelled :outcome-unknown}
   :attempt #{:succeeded :failed :cancelled :outcome-unknown}
   :action #{:finished}
   :process #{:exited}})

(def uncertain-states-by-kind
  "States whose outcome is already unknown and must be preserved."
  {:run #{}
   :call #{:outcome-unknown}
   :attempt #{:outcome-unknown}
   :action #{:uncertain}
   :process #{:uncertain}})

(def execution-states-by-kind
  "States that still own a live execution resource.

  An unknown outcome is not a live execution state.  A deferred `:applying`
  action is live: its current minimal commit must finish first."
  {:run #{:preparing :running}
   :call #{:dispatching :running :retry-wait}
   :attempt #{:dispatching :running}
   :action #{:claimed :preparing :waiting-gate :applying :running}
   :process #{:starting :running :stopping}})

(def parent-kinds
  "Allowed parent kind for each child kind.  `:run` has no parent."
  {:run #{nil}
   :call #{:run :call}
   :attempt #{:call}
   :action #{:run}
   :process #{:action :run}})

(def action-kinds
  #{:board :edit :shell})

(defn legal-state?
  "True when STATE is legal for KIND."
  [kind state]
  (contains? (get states-by-kind kind) state))

(defn terminal?
  "True when NODE already has a terminal outcome."
  [node]
  (contains? (get terminal-states-by-kind (:kind node)) (:state node)))

(defn uncertain?
  "True when NODE's state itself records an unknown outcome."
  [node]
  (contains? (get uncertain-states-by-kind (:kind node)) (:state node)))

(defn uncertain-outcome?
  "True when NODE is unknown either by state or by an explicit outcome."
  [node]
  (or (uncertain? node)
      (= :outcome-unknown (:outcome node))))

(defn provisional-unknown?
  "True when an unknown outcome is still awaiting a final observation.

  A stopping managed process carries `:outcome :outcome-unknown` so the
  uncertainty is visible, but its state is still `:stopping`.  A terminal
  observation may replace that provisional outcome.  Once the process
  becomes `:uncertain`, the unknown outcome is fixed."
  [node]
  (and (= :process (:kind node))
       (= :stopping (:state node))
       (= :outcome-unknown (:outcome node))))

(defn fixed-unknown?
  "True when NODE's unknown outcome may no longer be replaced by a late
  terminal result.  Explicit reconciliation remains the only exit."
  [node]
  (and (uncertain-outcome? node)
       (not (provisional-unknown? node))))

(defn executing?
  "True when NODE still owns a live execution resource."
  [node]
  (contains? (get execution-states-by-kind (:kind node)) (:state node)))

(defn run?
  [node]
  (= :run (:kind node)))

(defn- blank-id?
  [id]
  (or (nil? id)
      (and (string? id) (str/blank? id))))

(defn- invalid!
  [message details]
  (throw (ex-info message (assoc details :type ::invalid-node))))

(defn- default-node
  [kind id parent-id]
  (case kind
    :run
    {:node-id id
     :kind :run
     :parent-id nil
     :state :running
     :admission :open
     :side-effecting? false
     :cancel-requested? false
     :evidence []}

    :call
    {:node-id id
     :kind :call
     :parent-id parent-id
     :state :ready
     :side-effecting? true
     :cancel-requested? false
     :evidence []}

    :attempt
    {:node-id id
     :kind :attempt
     :parent-id parent-id
     :state :dispatching
     :side-effecting? true
     :cancel-requested? false
     :evidence []}

    :action
    {:node-id id
     :kind :action
     :parent-id parent-id
     :state :claimed
     :action-kind :board
     :cancel-requested? false
     :evidence []}

    :process
    {:node-id id
     :kind :process
     :parent-id parent-id
     :state :starting
     :side-effecting? true
     :cancel-requested? false
     :evidence []}

    (invalid! "unknown cancellation node kind" {:node-id id :kind kind})))

(defn- derive-resource-alive
  [kind state]
  (case kind
    :run false
    :call (contains? #{:dispatching :running :retry-wait} state)
    :attempt (contains? #{:dispatching :running} state)
    :action (contains? #{:applying :running} state)
    :process (contains? #{:starting :running :stopping} state)
    false))

(defn- validate-node!
  [state node parent]
  (let [kind (:kind node)
        id (:node-id node)
        state-name (:state node)
        parent-id (:parent-id node)]
    (when-not (contains? node-kinds kind)
      (invalid! "unknown cancellation node kind"
                {:node-id id :kind kind}))
    (when (blank-id? id)
      (invalid! "a cancellation node needs a non-blank :node-id"
                {:node-id id}))
    (when-not (legal-state? kind state-name)
      (invalid! "illegal state for cancellation node"
                {:node-id id :kind kind :state state-name}))
    (when-not (contains? (get parent-kinds kind) (:kind parent))
      (invalid! "illegal parent kind for cancellation node"
                {:node-id id
                 :kind kind
                 :parent-id parent-id
                 :parent-kind (when parent (:kind parent))}))
    (when (and (not= :run kind) (nil? parent))
      (invalid! "a non-run cancellation node needs an existing parent"
                {:node-id id :kind kind :parent-id parent-id}))
    (when (and (= :run kind) (some? parent-id))
      (invalid! "a run cancellation node cannot have a parent"
                {:node-id id :parent-id parent-id}))
    (when-not (or (nil? (:node-seq node))
                  (and (integer? (:node-seq node))
                       (pos? (:node-seq node))))
      (invalid! "node-seq must be a positive integer"
                {:node-id id :node-seq (:node-seq node)}))
    (when-not (or (nil? (:side-effecting? node))
                  (boolean? (:side-effecting? node)))
      (invalid! "side-effecting? must be a boolean"
                {:node-id id :side-effecting? (:side-effecting? node)}))
    (when-not (or (nil? (:cancel-requested? node))
                  (boolean? (:cancel-requested? node)))
      (invalid! "cancel-requested? must be a boolean"
                {:node-id id :cancel-requested? (:cancel-requested? node)}))
    (when-not (or (nil? (:resource-alive? node))
                  (boolean? (:resource-alive? node)))
      (invalid! "resource-alive? must be a boolean"
                {:node-id id :resource-alive? (:resource-alive? node)}))
    (when (and (= :action kind)
               (not (contains? action-kinds (:action-kind node))))
      (invalid! "an action node needs a known :action-kind"
                {:node-id id :action-kind (:action-kind node)}))
    (when (and (= :run kind)
               (not (contains? #{:open :closed} (:admission node))))
      (invalid! "a run node needs an admission gate of :open or :closed"
                {:node-id id :admission (:admission node)}))
    (when (and (= :run kind)
               (not (and (string? (:session-id node))
                         (not (str/blank? (:session-id node))))))
      (invalid! "a run node needs a non-blank :session-id"
                {:node-id id :session-id (:session-id node)}))
    (when (and (= :run kind)
               (not= (:session-id node) (:session-id state)))
      (invalid! "a run node must belong to this session"
                {:node-id id
                 :session-id (:session-id node)
                 :tree-session-id (:session-id state)}))
    (when (and (contains? node :evidence)
               (not (sequential? (:evidence node))))
      (invalid! "evidence must be sequential"
                {:node-id id :evidence (:evidence node)}))
    node))

(defn add-node
  "Add NODE to STATE and return the new state.

  Missing defaults are filled from the node kind.  Parent links, states,
  action kinds, and boolean flags are validated before the node enters the
  tree.  A duplicate `:node-id` is a wiring error."
  [state node]
  (let [kind (:kind node)
        id (:node-id node)
        parent-id (:parent-id node)]
    (when (contains? (:nodes state) id)
      (invalid! "duplicate cancellation node id"
                {:node-id id}))
    (let [parent (when (some? parent-id)
                   (get-in state [:nodes parent-id]))
          merged (merge (default-node kind id parent-id) node)
          merged (if (and (= :action kind)
                          (not (contains? node :side-effecting?)))
                   (assoc merged :side-effecting?
                          (not= :board (:action-kind merged)))
                   merged)
          merged (if (and (= :run kind)
                          (nil? (:session-id merged)))
                   (assoc merged :session-id (:session-id state))
                   merged)
          merged (if (contains? node :resource-alive?)
                   merged
                   (assoc merged :resource-alive?
                          ;; A model attempt and a pure board action execute
                          ;; without owning a durable external resource.
                          ;; Deriving `:resource-alive?` from the state alone
                          ;; would mark them live and block `finish-run`
                          ;; forever; the explicit flag stays authoritative
                          ;; when a node supplies one.
                          (and (not (false? (:side-effecting? merged)))
                               (derive-resource-alive kind (:state merged)))))
          seq-no (or (:node-seq merged)
                     (:next-node-seq state)
                     1)]
      (when (some #(= seq-no (:node-seq %)) (vals (:nodes state)))
        (invalid! "duplicate node-seq"
                  {:node-id id :node-seq seq-no}))
      (validate-node! state (assoc merged :node-seq seq-no) parent)
      (-> state
          (assoc-in [:nodes id] (assoc merged :node-seq seq-no))
          (assoc :next-node-seq (max (inc (long seq-no))
                                     (long (or (:next-node-seq state) 1))))))))

(defn add-nodes
  "Add NODES in order and return the new state."
  [state nodes]
  (reduce add-node state nodes))

(defn new-state
  "Create a session with one run and no child nodes.

  Options: `:session-id`, `:run-id`, `:run-state`, `:admission`."
  ([] (new-state {}))
  ([{:keys [session-id run-id run-state admission]
     :or {session-id "session-1"
          run-id "run-1"
          run-state :running
          admission :open}}]
   (-> {:cancel-version 1
        :session-id session-id
        :active-run-id run-id
        :nodes {}
        :next-node-seq 1
        :receipts {}}
       (add-node {:node-id run-id
                  :kind :run
                  :parent-id nil
                  :state run-state
                  :admission admission
                  :session-id session-id}))))

(defn node
  "Return the node with NODE-ID, or nil."
  [state node-id]
  (get-in state [:nodes node-id]))

(defn nodes-of-kind
  "All nodes of KIND in construction order."
  [state kind]
  (->> (:nodes state)
       vals
       (filter #(= kind (:kind %)))
       (sort-by #(or (:node-seq %) 0))
       vec))

(defn children
  "Direct children of PARENT-ID in construction order."
  [state parent-id]
  (->> (:nodes state)
       vals
       (filter #(= parent-id (:parent-id %)))
       (sort-by #(or (:node-seq %) 0))
       vec))

(defn breadth-first
  "Return the subtree rooted at ROOT-ID in breadth-first order.

  Siblings are visited in `:node-seq` order.  The root is first.  A missing
  root is a wiring error."
  [state root-id]
  (when-not (node state root-id)
    (invalid! "cancellation root does not exist"
              {:node-id root-id}))
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY root-id)
         seen #{}
         out []]
    (if (empty? queue)
      out
      (let [id (peek queue)
            queue (pop queue)]
        (if (contains? seen id)
          (recur queue seen out)
          (let [current (node state id)
                next-ids (map :node-id (children state id))]
            (recur (into queue next-ids)
                   (conj seen id)
                   (conj out current))))))))

(defn descendant-nodes
  "The subtree below ROOT-ID, excluding the root."
  [state root-id]
  (vec (rest (breadth-first state root-id))))

(defn assoc-node
  "Replace NODE in STATE."
  [state node]
  (assoc-in state [:nodes (:node-id node)] node))

(defn update-node
  "Apply F to the node named NODE-ID."
  [state node-id f & args]
  (if (node state node-id)
    (apply update-in state [:nodes node-id] f args)
    state))

(defn- run-roots
  [state]
  (nodes-of-kind state :run))

(defn- reachable-ids
  [state]
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                     (map :node-id (run-roots state)))
         seen #{}
         out #{}]
    (if (empty? queue)
      out
      (let [id (peek queue)
            queue (pop queue)]
        (if (or (contains? seen id)
                (nil? (node state id)))
          (recur queue seen out)
          (recur (into queue (map :node-id (children state id)))
                 (conj seen id)
                 (conj out id)))))))

(defn validate-state
  "Return a vector of problems with STATE.  Empty means valid.

  The checks cover node identity, kind/state legality, parent links,
  reachability from a run, unique node sequence, and the active run."
  [state]
  (let [problems (transient [])
        add! (fn [problem] (conj! problems problem))]
    (cond
      (not (map? state))
      (persistent! (add! {:problem :not-a-map}))

      (not= 1 (:cancel-version state))
      (persistent! (add! {:problem :unknown-version
                          :version (:cancel-version state)}))

      (not (and (string? (:session-id state))
                (not (str/blank? (:session-id state)))))
      (persistent! (add! {:problem :invalid-session-id
                          :session-id (:session-id state)}))

      (not (map? (:nodes state)))
      (persistent! (add! {:problem :invalid-nodes
                          :nodes (:nodes state)}))

      :else
      (do
        (doseq [[id node] (:nodes state)]
          (when-not (= id (:node-id node))
            (add! {:problem :node-id-mismatch
                   :key id
                   :node-id (:node-id node)}))
          (when-not (contains? node-kinds (:kind node))
            (add! {:problem :unknown-kind
                   :node-id id
                   :kind (:kind node)}))
          (when-not (legal-state? (:kind node) (:state node))
            (add! {:problem :illegal-state
                   :node-id id
                   :kind (:kind node)
                   :state (:state node)}))
          (when (and (= :run (:kind node))
                     (not (contains? #{:open :closed} (:admission node))))
            (add! {:problem :illegal-admission
                   :node-id id
                   :admission (:admission node)}))
          (when (and (= :run (:kind node))
                     (= :cancelling (:state node))
                     (not= :closed (:admission node)))
            (add! {:problem :cancelling-run-open-admission
                   :node-id id
                   :admission (:admission node)}))
          (when (contains? node :outcome)
            (let [allowed (if (= :run (:kind node))
                            #{:cancelled :partial :succeeded :failed}
                            #{:succeeded :failed :cancelled :outcome-unknown})]
              (when-not (contains? allowed (:outcome node))
                (add! {:problem :invalid-outcome
                       :node-id id
                       :kind (:kind node)
                       :outcome (:outcome node)}))))
          (when (and (contains? #{:call :attempt} (:kind node))
                     (contains? node :outcome)
                     (not= (:state node) (:outcome node)))
            (add! {:problem :outcome-state-mismatch
                   :node-id id
                   :kind (:kind node)
                   :state (:state node)
                   :outcome (:outcome node)}))
          (let [parent-id (:parent-id node)
                parent (get-in state [:nodes parent-id])]
            (when-not (contains? (get parent-kinds (:kind node))
                                 (:kind parent))
              (add! {:problem :illegal-parent
                     :node-id id
                     :kind (:kind node)
                     :parent-id parent-id
                     :parent-kind (when parent (:kind parent))}))
            (when (and (not= :run (:kind node)) (nil? parent))
              (add! {:problem :missing-parent
                     :node-id id
                     :parent-id parent-id})))
          (when-not (and (integer? (:node-seq node))
                         (pos? (:node-seq node)))
            (add! {:problem :invalid-node-seq
                   :node-id id
                   :node-seq (:node-seq node)})))
        (let [seqs (map :node-seq (vals (:nodes state)))]
          (when-not (apply distinct? seqs)
            (add! {:problem :duplicate-node-seq
                   :seqs (vec seqs)})))
        (let [max-seq (reduce max 0 (keep :node-seq (vals (:nodes state))))]
          (when (and (contains? state :next-node-seq)
                     (not (and (integer? (:next-node-seq state))
                               (> (long (:next-node-seq state)) max-seq))))
            (add! {:problem :invalid-next-node-seq
                   :next-node-seq (:next-node-seq state)
                   :max-node-seq max-seq})))
        (let [active-run (node state (:active-run-id state))]
          (when-not (= :run (:kind active-run))
            (add! {:problem :active-run-missing
                   :active-run-id (:active-run-id state)}))
          (doseq [run (run-roots state)]
            (when-not (= (:session-id state) (:session-id run))
              (add! {:problem :run-session-mismatch
                     :run-id (:node-id run)
                     :session-id (:session-id run)}))))
        (let [reachable (reachable-ids state)
              all-ids (set (keys (:nodes state)))]
          (when-not (= reachable all-ids)
            (add! {:problem :unreachable-nodes
                   :unreachable (vec (sort-by str (remove reachable all-ids)))})))
        (persistent! problems)))))

(defn valid-state?
  "True when `validate-state` finds no problems."
  [state]
  (empty? (validate-state state)))
