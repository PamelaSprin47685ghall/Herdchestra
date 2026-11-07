(ns hc.server.materials.state
  "Current-state shape for the materials domain.

  The state is one plain EDN map. It contains the current memory records, the
  worldbook definitions and their per-session trigger counters, and the two
  independent memory switches. Search references are not stored here: they
  belong to the message that cites them.

  Every successful domain transition increments :revision exactly once. The
  store compares the expected revision atomically; a stale writer is refused
  rather than silently overwriting newer records.")

(def state-version 1)

(def default-settings
  "Both memory switches start closed. Root AGENTS.md §5.3."
  {:memory-read? false
   :memory-suggest? false})

(defn new-state
  "An empty materials state at revision 0."
  []
  {:v state-version
   :revision 0
   :seq 0
   :settings default-settings
   :memories {}
   :worldbook {:books {}
               :entries {}
               :sessions {}}
   :instruction-cards {}
   :instruction-sessions {}
   :instruction-turns {}
   :instruction-turn-receipts {}})

(defn normalize-loaded
  "Fill top-level keys that a state written by an older build lacks.

  Existing values win; this never invents records.  A seed or restored
  library from before a schema addition therefore loads as a valid state
  instead of being reported as permanently unready.  A truly damaged
  document still fails validation after this fill."
  [state]
  (if-not (map? state)
    state
    (let [defaults (new-state)]
      (-> (merge defaults state)
          (update :settings #(merge default-settings (or % {})))
          (update :worldbook #(merge (:worldbook defaults) (or % {})))))))

(defn bump-revision
  "Advance the state revision by one. Call exactly once per transition."
  [state]
  (update state :revision (fnil inc 0)))

(defn next-seq
  "Allocate the next stable creation sequence.

  Sequence numbers give deterministic ordering without reading a clock and
  without making persistence depend on wall time. Returns [state' n]."
  [state]
  (let [n (inc (long (or (:seq state) 0)))]
    [(assoc state :seq n) n]))

(defn prepare
  "Allocate a sequence number and advance the revision.

  Returns [state' n]. The caller attaches n to the new entity."
  [state]
  (let [[state n] (next-seq state)]
    [(bump-revision state) n]))

(defn non-negative-integer?
  [value]
  (and (integer? value) (not (neg? value))))

(defn positive-integer?
  [value]
  (and (integer? value) (pos? value)))

(defn check
  "Basic state-shape violations. Domain modules add their own checks."
  [state]
  (cond
    (not (map? state))
    [{:invariant :state-not-a-map}]

    :else
    (let [settings (:settings state)
          worldbook (:worldbook state)]
      (cond-> []
        (not= state-version (:v state))
        (conj {:invariant :state-version-invalid
               :version (:v state)
               :expected state-version})

        (not (non-negative-integer? (:revision state)))
        (conj {:invariant :state-revision-invalid
               :revision (:revision state)})

        (not (non-negative-integer? (:seq state)))
        (conj {:invariant :state-seq-invalid
               :seq (:seq state)})

        (not (map? settings))
        (conj {:invariant :settings-not-a-map
               :settings settings})

        (and (map? settings)
             (not (boolean? (:memory-read? settings))))
        (conj {:invariant :memory-read-switch-invalid
               :value (:memory-read? settings)})

        (and (map? settings)
             (not (boolean? (:memory-suggest? settings))))
        (conj {:invariant :memory-suggest-switch-invalid
               :value (:memory-suggest? settings)})

        (not (map? (:memories state)))
        (conj {:invariant :memories-not-a-map
               :memories (:memories state)})

        (not (map? (:instruction-cards state)))
        (conj {:invariant :instruction-cards-not-a-map
               :cards (:instruction-cards state)})

        (not (map? (:instruction-sessions state)))
        (conj {:invariant :instruction-sessions-not-a-map
               :sessions (:instruction-sessions state)})

        (not (map? (:instruction-turns state)))
        (conj {:invariant :instruction-turns-not-a-map
               :turns (:instruction-turns state)})

        (not (map? (:instruction-turn-receipts state)))
        (conj {:invariant :instruction-turn-receipts-not-a-map
               :receipts (:instruction-turn-receipts state)})

        (not (map? worldbook))
        (conj {:invariant :worldbook-not-a-map
               :worldbook worldbook})

        (and (map? worldbook)
             (not (map? (:sessions worldbook))))
        (conj {:invariant :worldbook-sessions-not-a-map
               :sessions (:sessions worldbook)})))))

(defn memory-read-enabled?
  [state]
  (true? (get-in state [:settings :memory-read?])))

(defn memory-suggest-enabled?
  [state]
  (true? (get-in state [:settings :memory-suggest?])))

(defn update-settings
  "Change the two independent memory switches.

  Absent keys leave the current value unchanged. Unknown keys are refused.
  Both switches default to false and are never inferred from each other."
  [state request]
  (let [known #{:memory-read? :memory-suggest?}
        unknown (remove known (keys (or request {})))
        invalid (for [[k v] request
                      :when (and (contains? known k)
                                 (not (boolean? v)))]
                  [k v])]
    (cond
      (not (map? request))
      {:ok? false
       :state state
       :error {:code :MEMORY_SETTINGS_INVALID
               :message "settings must be a map"}
       :effects []}

      (seq unknown)
      {:ok? false
       :state state
       :error {:code :MEMORY_SETTINGS_UNKNOWN
               :message "settings contain unknown keys"
               :details {:unknown (vec unknown)}}
       :effects []}

      (seq invalid)
      {:ok? false
       :state state
       :error {:code :MEMORY_SETTINGS_INVALID
               :message "memory switches must be booleans"
               :details {:invalid (into {} invalid)}}
       :effects []}

      (empty? request)
      {:ok? true
       :state state
       :replayed? true
       :effects []}

      :else
      {:ok? true
       :state (bump-revision (update state :settings merge request))
       :settings (merge (:settings state) request)
       :effects []})))

