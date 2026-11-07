(ns hc.server.history.snapshot
  "Client-facing history projection.

  The session record is authoritative.  This namespace turns it into the
  normalized `:message/by-id` / `:message/order` tables the workbench already
  consumes, plus per-session `:message-ids` and context metadata.

  It is a read-only projection.  It does not apply a boundary, delete a
  message, or start a run.  The outbound preview is included for the context
  panel; the actual send still uses `history/outbound-context` inside the
  session command."
  (:require [hc.server.history.context :as context]
            [hc.server.history.message :as message]))

(defn version-view
  "One version as a client display copy."
  [version]
  {:version-id (:version-id version)
   :content (:content version)
   :attachments (vec (or (:attachments version) []))
   :citations (vec (or (:citations version) []))
   :quotes (vec (or (:quotes version) []))
   :sources (vec (or (:citations version) []))
   :tool-records (vec (or (:tool-records version) []))
   :usage (:usage version)
   :edited? (boolean (:edited? version))
   :manual-edit? (boolean (:manual-edit? version))
   :source (:source version)
   :created-at (:created-at version)})

(defn message-entity
  "One message with every version and the current version's display fields."
  [session message]
  (let [message (message/normalize-message message)
        current (message/current-version message)
        id (:message-id message)]
    {:id id
     :message-id id
     :session-id (:session-id session)
     :role (:role message)
     :content (:content current)
     :attachments (vec (or (:attachments current) []))
     :citations (vec (or (:citations current) []))
     :quotes (vec (or (:quotes current) []))
     :sources (vec (or (:citations current) []))
     :tool-records (vec (or (:tool-records current) []))
     :usage (:usage current)
     :versions (mapv version-view (:versions message))
     :current-version (:current-version message)
     :version-id (:version-id current)
     :manual-edit? (boolean (:manual-edit? current))
     :edited? (> (count (:versions message)) 1)
     :deleted? (boolean (:deleted? message))
     :folded? (boolean (:folded? message))
     :deleted-at (:deleted-at message)
     :folded-at (:folded-at message)
     :created-at (:created-at message)
     :updated-at (:updated-at message)}))

(defn- compression-summary
  [compression]
  {:compression-id (:compression-id compression)
   :state (:state compression)
   :message-ids (vec (:message-ids compression))
   :range (:range compression)
   :summary (:summary compression)
   :source-version-ids (:source-version-ids compression)
   :created-at (:created-at compression)
   :applied-at (:applied-at compression)
   :reverted-at (:reverted-at compression)})

(def ^:private outbound-keys
  [:message-ids :summary-ids :version-ids :excluded :warnings :rounds
   :boundary :message-count :summary-count
   :system :assistant :instructions :memory :worldbook :instruction-cards
   :tools :history :attachments :estimates])

(def ^:private frozen-section-keys
  "Sections that describe what the last accepted run actually carried.

  The send range is deliberately not frozen here.  After a regenerate, the
  run's input is only the prefix before the target; the next send range must
  still include the target message and any message appended after it.  The
  range and exclusions therefore come from the fresh projection, while the
  injected materials come from the last accepted run."
  [:system :assistant :instructions :memory :worldbook :instruction-cards
   :tools :attachments :estimates])

(defn- frozen-outbound
  "The injected-section snapshot frozen when the most recent run was accepted.

  A run's input snapshot is the exact copy the model received.  Archiving a
  memory or editing a worldbook must not rewrite what the last reply actually
  used, so these sections come from the run.  The range stays fresh: a
  regenerate freezes only the prefix before its target, and a later send must
  still see the regenerated target and newer messages."
  [session]
  (let [run-id (last (:run-order session))
        context (get-in session [:runs run-id :input-snapshot :context])]
    (when (and (map? context) (seq context))
      (select-keys context frozen-section-keys))))

(defn context-view
  "Context metadata for the workbench context panel.

  `:outbound` prefers the most recent run's frozen request snapshot.  Without
  a run it is a compact preview; the actual message bodies remain in
  `:message/by-id`, so the projection carries IDs, summary IDs, exclusions,
  and warnings rather than a second copy of every body.  `:source` says which
  copy the panel is showing."
  ([session] (context-view session {}))
  ([session opts]
  (let [out (context/outbound-context session opts)
        frozen (frozen-outbound session)
        outbound (cond-> (select-keys out outbound-keys)
                   frozen (merge frozen)
                   true (assoc :source (if frozen :last-run :preview)))]
    {:boundary (context/boundary session)
     :recent-rounds (context/recent-rounds session)
     :pinned-attachment-ids (context/pinned-attachment-ids session)
     :pinned-attachments (:pinned-attachments out)
     :missing-pinned-attachments (:missing-pinned-attachments out)
     :compressions (mapv compression-summary
                         (vals (context/compressions session)))
     :outbound outbound
     :reading {:message-count (count (message/visible-messages session))
               :deleted-count (count (message/deleted-messages session))}})))

(defn session-history
  "Return one session's client history projection."
  ([session] (session-history session {}))
  ([session opts]
  (let [ordered (vec (or (:message-order session) []))
        listed (set ordered)
        missing (->> (keys (:messages session))
                     (remove listed)
                     (sort-by (fn [id]
                                (str (get-in session
                                             [:messages id :created-at]
                                             ""))))
                     vec)
        visible (->> (concat ordered missing)
                     (keep #(get-in session [:messages %]))
                     (remove #(or (:deleted? %) (:folded? %)))
                     (remove #(or (:nudge? %) (:nudge-id %)))
                     (mapv #(message-entity session %)))
        ids (mapv :id visible)]
    {:session-id (:session-id session)
     :message-ids ids
     :messages visible
     :context (context-view session opts)
     :branch-of (:origin-session-id session)})))

(defn baseline
  "Project every visible session into normalized history tables.

  Returns `:message/by-id`, `:message/order`, and `:session/history`.
  `:message/order` covers every entity in `:message/by-id`, which is what the
  client snapshot validator requires.  The workbench selects a session's own
  order from `:session/history` `:message-ids`.

  OPTS may carry `:outbound-opts-fn`, a function from one session to the
  outbound section options.  The application supplies it so the panel sees
  the live assistant, memory, worldbook, cards and tools instead of the
  default empty projection."
  ([sessions active-session] (baseline sessions active-session {}))
  ([sessions active-session opts]
  (let [opts-fn (:outbound-opts-fn opts)
        ;; Only the active session's panel is visible.  Computing the live
        ;; materials projection for every session on every publish would
        ;; repeatedly load the whole materials state and can exhaust the
        ;; heap on a long session list.
        opts-for (fn [session]
                   (if (and opts-fn
                            (= (:session-id session) active-session))
                     (merge (dissoc (or opts {}) :outbound-opts-fn)
                            (opts-fn session))
                     (dissoc (or opts {}) :outbound-opts-fn)))
        histories (mapv (fn [session]
                          (session-history session (opts-for session)))
                        (or sessions []))
        entities (mapcat :messages histories)
        by-id (into {} (map (juxt :id identity)) entities)
        order (->> histories (mapcat :message-ids) vec distinct)
        session-history-map (into {}
                                  (map (fn [{:keys [session-id] :as history}]
                                         [session-id (dissoc history :messages)]))
                                  histories)
        active (get session-history-map active-session)]
    {:message/by-id by-id
     :message/order order
     :session/history session-history-map
     :session/history-active-id active-session
     :session/history-active active})))

(defn session-summary-fields
  "Fields to merge into one `:session/by-id` summary."
  [history]
  (when history
    (select-keys history [:message-ids :context :branch-of])))
