(ns hc.core.pair-hint.observation
  "Board and Steward fleet observation as delta / clean-break data.

  Board state is pushed through Pair Hint; there is no `(board ...)` read
  primitive.  A writer receives a full clean-break snapshot and discards old
  deltas.  A reader appends deltas until the count or version span exceeds its
  threshold, then asks for a clean-break snapshot.  The observed cursor is
  always the version of the most recent delta or snapshot."
  (:require [clojure.string :as str]))

(def fleet-row-fields
  [:swarm-id :slice-id :worktree :status
   :root-ready :root-waiting :root-done
   :queued-count :last-event :master-version])

(def fleet-event-fields
  "The only fields a Steward may see from a `:result-queued` event.

  The fleet view is a bounded summary, not a second task board.  Event
  payloads can carry reports or task details; selecting a fixed field set
  keeps those details out of the Steward's context."
  [:type :swarm-id :slice-id :status :queued-count :master-version :at])

(def default-thresholds
  {:count 8
   :version-span 16})

(def default-max-events
  16)

(def initial-board-state
  {:board-version nil
   :deltas []
   :delta-count 0
   :since-version nil
   :snapshot nil
   :snapshot-version nil
   :clean-breaks 0})

(def initial-fleet-state
  {:fleet-version nil
   :swarms []
   :events []
   :delta-count 0
   :since-version nil
   :clean-breaks 0})

(defn- renderer
  [opts]
  (let [render (:render opts)]
    (if (ifn? render)
      render
      (throw (ex-info "pair-hint: an explicit EDN+UFSS renderer is required"
                      {:error :PAIR_HINT_RENDERER_REQUIRED
                       :hint "inject hc.core.ufss/write-str from the composition layer"})))))

(defn- render-text
  [opts value]
  (let [rendered ((renderer opts) value)]
    (when-not (string? rendered)
      (throw (ex-info "pair-hint: renderer must return a string"
                      {:error :PAIR_HINT_RENDERER_INVALID
                       :value rendered})))
    rendered))

(defn- version!
  [value field]
  (when-not (and (integer? value) (not (neg? value)))
    (throw (ex-info (str "pair-hint: " field " must be a non-negative integer")
                    {:error :PAIR_HINT_OBSERVATION_INVALID
                     :field field
                     :value value})))
  value)

(defn- thresholds
  [opts]
  (merge default-thresholds (:thresholds opts)))

(defn- threshold-exceeded?
  [state opts]
  (let [{:keys [count version-span]} (thresholds opts)
        delta-count (:delta-count state)
        since (:since-version state)
        current (or (:board-version state) (:fleet-version state))
        span (if (and since current) (- current since) 0)]
    (or (> delta-count count)
        (> span version-span))))

(defn- event-type
  [event]
  (let [t (:type event)]
    (cond
      (keyword? t) t
      (string? t) (keyword (if (str/starts-with? t ":") (subs t 1) t))
      :else nil)))

(defn- normalize-fleet-event
  "Keep only a bounded `:result-queued` event.

  A status event or an event carrying a report/task detail is not part of the
  Steward's summary.  Returning nil lets the caller drop it without inventing
  a replacement."
  [event]
  (when (and (map? event) (= :result-queued (event-type event)))
    (assoc (select-keys event fleet-event-fields) :type :result-queued)))

(defn- normalize-fleet-events
  [events]
  (into [] (keep normalize-fleet-event) events))

(defn- stale-version?
  [state field version]
  (let [current (get state field)]
    (and (some? current) (< version current))))

(defn- board-clean-break
  [state version snapshot opts]
  (let [text (render-text opts
                          {:board-version version
                           :mode :clean-break
                           :snapshot snapshot})
        next-state (-> state
                       (assoc :board-version version
                              :deltas []
                              :delta-count 0
                              :since-version version
                              :snapshot snapshot
                              :snapshot-version version)
                       (update :clean-breaks (fnil inc 0)))]
    {:ok? true
     :action :clean-break
     :text text
     :state next-state
     :boundary {:kind :board-clean-break
                :id (str "board-" version)
                :board-version version}
     :observed-cursor version}))

(defn- board-snapshot-replay
  [state version snapshot opts]
  {:ok? true
   :action :replay
   :text (render-text opts {:board-version version
                            :mode :clean-break
                            :snapshot snapshot})
   :state state
   :observed-cursor version})

(defn- board-append
  [state version changes opts]
  (let [delta {:board-version version :changes changes}
        next-state (-> state
                       (update :deltas (fnil conj []) delta)
                       (assoc :board-version version)
                       (update :delta-count (fnil inc 0))
                       (update :since-version #(or % version)))
        text (render-text opts delta)
        candidate (:snapshot opts)
        state-snapshot (:snapshot next-state)
        snapshot (cond
                   (and (some? candidate)
                        (= version (:board-version candidate))) candidate
                   (and (some? state-snapshot)
                        (= version (:snapshot-version next-state))) state-snapshot
                   :else nil)]
    (if (threshold-exceeded? next-state opts)
      (if (some? snapshot)
        (board-clean-break next-state version snapshot opts)
        {:ok? true
         :action :append
         :text text
         :state next-state
         :observed-cursor version
         :needs-snapshot true})
      {:ok? true
       :action :append
       :text text
       :state next-state
       :observed-cursor version})))

(defn observe-board
  "Apply one board observation.

  Observation shapes:
  - `{:kind :delta :board-version N :changes [...]}`
  - `{:kind :snapshot :board-version N :snapshot {...}}`

  Options:
  - `:writer?` force a clean-break (writer must also supply `:snapshot`)
  - `:snapshot` full snapshot for a threshold clean-break
  - `:thresholds {:count N :version-span N}`
  - `:render` required EDN/UFSS renderer (no fallback)

  Returns `{:ok? true :action :append|:clean-break|:replay ...}` or
  `{:ok? false :error {...}}`.  A reader that crosses a threshold without a
  snapshot receives `:needs-snapshot true` and keeps its deltas."
  [state observation opts]
  (let [state (merge initial-board-state (or state {}))
        observation (or observation {})
        kind (keyword (or (:kind observation) :delta))
        version (version! (:board-version observation) :board-version)
        snapshot (or (:snapshot observation) (:snapshot opts))
        writer? (boolean (or (:writer? observation) (:writer? opts)))]
    (cond
      writer?
      (cond
        (stale-version? state :board-version version)
        {:ok? false
         :error {:code :PAIR_HINT_BOARD_STALE
                 :board-version version
                 :current (:board-version state)}
         :state state}

        (some? snapshot)
        (if (and (= version (:board-version state))
                 (= snapshot (:snapshot state)))
          (board-snapshot-replay state version snapshot opts)
          (board-clean-break state version snapshot opts))

        :else
        {:ok? false
         :error {:code :PAIR_HINT_BOARD_SNAPSHOT_REQUIRED
                 :reason :writer
                 :board-version version}
         :state state})

      (= kind :snapshot)
      (cond
        (stale-version? state :board-version version)
        {:ok? false
         :error {:code :PAIR_HINT_BOARD_STALE
                 :board-version version
                 :current (:board-version state)}
         :state state}

        (some? snapshot)
        (if (and (= version (:board-version state))
                 (= snapshot (:snapshot state)))
          (board-snapshot-replay state version snapshot opts)
          (board-clean-break state version snapshot opts))

        :else
        {:ok? false
         :error {:code :PAIR_HINT_BOARD_SNAPSHOT_REQUIRED
                 :reason :snapshot
                 :board-version version}
         :state state})

      (= kind :delta)
      (let [changes (vec (or (:changes observation) []))
            stored (some #(when (= version (:board-version %)) %) (:deltas state))
            known? (some? stored)
            replay-changes (if known? (:changes stored) changes)]
        (cond
          known?
          (if (= changes (:changes stored))
            {:ok? true
             :action :replay
             :text (render-text opts {:board-version version :changes replay-changes})
             :state state
             :observed-cursor version}
            {:ok? false
             :error {:code :PAIR_HINT_OBSERVATION_INVALID
                     :field :changes
                     :reason :same-version-different-changes
                     :board-version version}
             :state state})

          (and (some? (:board-version state))
               (= version (:board-version state)))
          (if (and (nil? (:snapshot state)) (seq changes))
            {:ok? false
             :error {:code :PAIR_HINT_OBSERVATION_INVALID
                     :field :changes
                     :reason :delta-at-snapshot-version
                     :board-version version}
             :state state}
            {:ok? true
             :action :replay
             :text (if-let [snapshot (:snapshot state)]
                     (render-text opts {:board-version version
                                        :mode :clean-break
                                        :snapshot snapshot})
                     (render-text opts {:board-version version
                                        :changes []}))
             :state state
             :observed-cursor version})

          (and (some? (:board-version state))
               (< version (:board-version state)))
          {:ok? false
           :error {:code :PAIR_HINT_BOARD_STALE
                   :board-version version
                   :current (:board-version state)}
           :state state}

          :else
          (board-append state version changes opts)))

      :else
      {:ok? false
       :error {:code :PAIR_HINT_OBSERVATION_INVALID
               :field :kind
               :value kind}
       :state state})))

(defn board-cursor
  "The cursor a `park-slot` records: latest delta or snapshot version."
  [state]
  (:board-version (merge initial-board-state state)))

(defn- normalize-fleet-row
  [row]
  (let [bounded (select-keys row fleet-row-fields)
        status (:status row)
        joined? (or (= :joined status)
                    (= "joined" (some-> status str)))]
    (cond-> bounded
      (and joined? (contains? row :report))
      ;; A joined slice carries the LLM-written report verbatim; it is not a
      ;; task-board detail and must not be truncated or summarized.
      (assoc :report (:report row)))))

(defn- fleet-rows
  [rows opts]
  (let [max-rows (or (:max-rows opts) 32)
        rows (->> rows
                  (map normalize-fleet-row)
                  (sort-by (comp str :swarm-id))
                  vec)]
    {:rows (vec (take max-rows rows))
     :rows-total (count rows)
     :truncated? (> (count rows) max-rows)}))

(defn- fleet-clean-break
  [state version rows events opts]
  (let [{:keys [rows rows-total truncated?]} (fleet-rows rows opts)
        events (vec (take-last (or (:max-events opts) default-max-events) events))
        text (render-text opts
                          {:fleet-version version
                           :mode :clean-break
                           :swarms rows
                           :events events
                           :rows-total rows-total
                           :truncated? truncated?})
        next-state (-> state
                       (assoc :fleet-version version
                              :swarms rows
                              :events events
                              :delta-count 0
                              :since-version version)
                       (update :clean-breaks (fnil inc 0)))]
    {:ok? true
     :action :clean-break
     :text text
     :state next-state
     :observed-cursor version}))

(defn- fleet-snapshot-replay
  [state version rows events opts]
  (let [{:keys [rows rows-total truncated?]} (fleet-rows rows opts)
        bounded-events (vec (take-last (or (:max-events opts) default-max-events)
                                       events))]
    (when (and (= version (:fleet-version state))
               (= rows (:swarms state))
               (= bounded-events (:events state)))
      {:ok? true
       :action :replay
       :text (render-text opts
                          {:fleet-version version
                           :mode :clean-break
                           :swarms rows
                           :events bounded-events
                           :rows-total rows-total
                           :truncated? truncated?})
       :state state
       :observed-cursor version})))

(defn- merge-fleet-rows
  [existing incoming]
  (let [incoming (map normalize-fleet-row incoming)
        by-id (into {} (map (juxt :swarm-id identity) existing))]
    (->> (reduce (fn [m row] (assoc m (:swarm-id row) row)) by-id incoming)
         vals
         (sort-by (comp str :swarm-id))
         vec)))

(defn observe-fleet
  "Apply one Steward fleet observation.

  Observation shapes:
  - `{:kind :delta :fleet-version N :swarms [...] :events [...]}`
  - `{:kind :snapshot :fleet-version N :swarms [...] :events [...]}`

  Only the bounded fields in `fleet-row-fields` are retained.  Status changes
  are deltas; threshold crossings clean-break the summary.  `:result-queued`
  events travel with the summary; single-task details never do."
  [state observation opts]
  (let [state (merge initial-fleet-state (or state {}))
        observation (or observation {})
        kind (keyword (or (:kind observation) :delta))
        version (version! (or (:fleet-version observation)
                             (:master-version observation))
                          :fleet-version)
        rows (vec (or (:swarms observation) []))
        events (normalize-fleet-events (or (:events observation) []))]
    (cond
      (= kind :snapshot)
      (if (stale-version? state :fleet-version version)
        {:ok? false
         :error {:code :PAIR_HINT_FLEET_STALE
                 :fleet-version version
                 :current (:fleet-version state)}
         :state state}
        (or (fleet-snapshot-replay state version rows events opts)
            (fleet-clean-break state version rows events opts)))

      (= kind :delta)
      (cond
        (= version (:fleet-version state))
        (let [max-rows (or (:max-rows opts) 32)
              max-events (or (:max-events opts) default-max-events)
              incoming-rows (vec (take max-rows (map normalize-fleet-row rows)))
              incoming-events (vec (take-last max-events events))
              last-delta (:last-delta state)
              same-delta? (= version (:fleet-version last-delta))
              replay-rows (if same-delta? (:swarms last-delta) incoming-rows)
              replay-events (if same-delta? (:events last-delta) incoming-events)
              divergent? (or (and same-delta?
                                  (not (and (= incoming-rows (:swarms last-delta))
                                            (= incoming-events (:events last-delta)))))
                             (and (nil? last-delta)
                                  (or (seq incoming-rows) (seq incoming-events))))]
          (if divergent?
            {:ok? false
             :error {:code :PAIR_HINT_OBSERVATION_INVALID
                     :field :swarms
                     :reason :same-version-different-content
                     :fleet-version version}
             :state state}
            {:ok? true
             :action :replay
             :text (render-text opts {:fleet-version version
                                      :swarms replay-rows
                                      :events replay-events})
             :state state
             :observed-cursor version}))

        (and (some? (:fleet-version state))
             (< version (:fleet-version state)))
        {:ok? false
         :error {:code :PAIR_HINT_FLEET_STALE
                 :fleet-version version
                 :current (:fleet-version state)}
         :state state}

        :else
        (let [max-rows (or (:max-rows opts) 32)
              max-events (or (:max-events opts) default-max-events)
              bounded-events (vec (take-last max-events events))
              merged (vec (take max-rows (merge-fleet-rows (:swarms state) rows)))
              next-state (-> state
                             (assoc :fleet-version version :swarms merged)
                             (assoc :last-delta {:fleet-version version
                                                 :swarms (vec (take max-rows
                                                                    (map normalize-fleet-row rows)))
                                                 :events bounded-events})
                             (update :events (fnil into []) events)
                             (update :events #(vec (take-last max-events %)))
                             (update :delta-count (fnil inc 0))
                             (update :since-version #(or % version)))
              text (render-text opts
                                {:fleet-version version
                                 :swarms (map normalize-fleet-row rows)
                                 :events bounded-events})]
          (if (threshold-exceeded? next-state opts)
            (fleet-clean-break next-state version merged (:events next-state) opts)
            {:ok? true
             :action :append
             :text text
             :state next-state
             :observed-cursor version})))

      :else
      {:ok? false
       :error {:code :PAIR_HINT_OBSERVATION_INVALID
               :field :kind
               :value kind}
       :state state})))

(defn fleet-cursor
  "Latest fleet summary version."
  [state]
  (:fleet-version (merge initial-fleet-state state)))
