(ns hc.core.pair-hint.hint
  "Synthetic Pair Hint injection, idempotent placement, and session assembly.

  A hint is one synthetic tool call plus its paired result, both with the empty
  tool name.  The call id is derived from the placement and text, so retrying
  the same placement replays the same pair instead of adding another.  An
  ephemeral side turn warms the cache but changes no session accounting and
  does not move the prefix chain."
  (:require [hc.core.pair-hint.canon :as canon]
            [hc.core.pair-hint.language :as language]
            [hc.core.pair-hint.observation :as observation]
            [hc.core.pair-hint.prefix :as prefix]
            [hc.core.pair-hint.resources :as resources]))

(defn- unknown
  [language]
  (if (= :zh-CN language) "未知" "unknown"))

(declare render-value)

(defn- scalar-fact?
  [value]
  (or (string? value)
      (number? value)
      (boolean? value)
      (keyword? value)
      (symbol? value)))

(defn- format-context-window
  [value language opts]
  (cond
    (nil? value) (unknown language)
    (integer? value) (str value " tokens")
    (scalar-fact? value) (str value)
    :else (render-value opts value)))

(defn- format-wall-clock
  [seconds language opts]
  (cond
    (nil? seconds) (unknown language)
    (not (number? seconds)) (if (scalar-fact? seconds)
                              (str seconds)
                              (render-value opts seconds))
    (< seconds 60) (str seconds "s")
    :else (let [minutes (quot (long seconds) 60)
                rest-seconds (rem (long seconds) 60)]
            (str minutes "m " rest-seconds "s"))))

(defn- format-count
  [value language opts]
  (cond
    (nil? value) (unknown language)
    (scalar-fact? value) (str value)
    :else (render-value opts value)))

(defn- render-value
  [opts value]
  (let [render (:render opts)]
    (if-not (ifn? render)
      (throw (ex-info "pair-hint: an explicit EDN+UFSS renderer is required"
                      {:error :PAIR_HINT_RENDERER_REQUIRED
                       :hint "inject hc.core.ufss/write-str from the composition layer"}))
      (let [rendered (render value)]
        (when-not (string? rendered)
          (throw (ex-info "pair-hint: renderer must return a string"
                          {:error :PAIR_HINT_RENDERER_INVALID
                           :value rendered})))
        rendered))))

(defn- format-parked-handles
  [handles opts]
  (let [handles (cond
                  (nil? handles) []
                  (and (coll? handles) (not (map? handles))) handles
                  :else [handles])]
    (if (empty? handles)
      "[]"
      (render-value opts (vec (sort-by str handles))))))

(defn- text-or-render
  [opts value fallback]
  (cond
    (nil? value) fallback
    (string? value) value
    :else (render-value opts value)))

(defn hint-values
  "Build the placeholder map for one hint.

  Context window, turns, and wall clock are facts supplied by the caller.
  The resource files own the wording; this function owns only the values.
  Non-empty `:parked-handles` require an injected EDN/UFSS `:render`
  function; there is no `pr-str` fallback."
  [session opts]
  (let [language (:language session)]
    {"context-window" (format-context-window (:context-window opts) language opts)
     "turns-used" (format-count (:turns-used opts) language opts)
     "turns-remaining" (format-count (:turns-remaining opts) language opts)
     "wall-clock" (format-wall-clock (:wall-clock-seconds opts) language opts)
     "parked-handles" (format-parked-handles (:parked-handles opts) opts)
     "board-observation" (text-or-render
                          opts
                          (or (:board-text opts)
                              (:board-observation opts)
                              (:board-text session))
                          "[]")
     "fleet-summary" (text-or-render
                      opts
                      (or (:fleet-text opts)
                          (:fleet-summary opts)
                          (:fleet-text session))
                      "[]")}))

(defn hint-text
  "Render the hint text for the session's bound language."
  [session opts]
  (language/validate-session-language session)
  (resources/render-language (:resources session) (:language session)
                             (hint-values session opts)))

(defn hint-pair
  "Build the synthetic call/result pair.

  Both messages use the empty tool name.  The call arguments stay `{}`; the
  result text carries the payload.  `:meta` marks the pair as synthetic and
  optionally ephemeral.

  `:format :blocks` (default) returns host-neutral content blocks.
  `:format :flat` returns the flat app message shape:
  `{:role :assistant :tool-calls [...]}` and
  `{:role :tool :tool-call-id ... :name \"\" :text ...}`.  Both shapes have
  the same semantic message digest.

  `:format :notes` is the degradation for a model that declares no `:tools`
  capability: the same payload arrives as one plain assistant message, so no
  `tool_calls`/`tool` envelope is sent to a model that cannot use one.  It is
  an explicit, caller-selected fallback — the capability is never dropped
  silently, and the adapter's `:unsupported-capability` rule still applies to
  any request that genuinely requires tools."
  [{:keys [text placement ephemeral? format]}]
  (let [format (or format :blocks)]
    (when-not (contains? #{:blocks :flat :notes} format)
      (throw (ex-info "pair-hint: unknown message format"
                      {:error :PAIR_HINT_FORMAT_INVALID
                       :format format
                       :allowed #{:blocks :flat :notes}})))
    (if (= :notes format)
      ;; One assistant message, no tool envelope.  `:tool-call-id` is still
      ;; derived so the placement key, replay detection, and digest stay
      ;; stable across formats.
      (let [note-id (str "pair-hint-"
                         (canon/short-digest {:placement placement :text text} 16))
            note {:role :assistant
                  :pair-hint true
                  :pair-hint-id note-id
                  :text text
                  :meta {:pair-hint true
                         :pair-hint-id note-id
                         :ephemeral (boolean ephemeral?)
                         :format :notes}}]
        {:call note
         :result nil
         :tool-call-id note-id
         :text text
         :digest (canon/digest [note])
         :ephemeral? (boolean ephemeral?)
         :format :notes})
      (let [tool-call-id (str "pair-hint-"
                              (canon/short-digest {:placement placement
                                                   :text text} 16))
            meta {:pair-hint true
                  :ephemeral (boolean ephemeral?)
                  :format format}
            call (case format
                   :flat {:role :assistant
                          :pair-hint true
                          :tool-calls [{:id tool-call-id
                                        :name ""
                                        :arguments {}}]
                          :meta meta}
                   {:role :assistant
                    :pair-hint true
                    :content [{:type :tool-call
                               :tool-call-id tool-call-id
                               :tool-name ""
                               :arguments {}}]
                    :meta meta})
            result (case format
                     :flat {:role :tool
                            :pair-hint true
                            :tool-call-id tool-call-id
                            :name ""
                            :text text
                            :meta meta}
                     {:role :tool
                      :pair-hint true
                      :tool-call-id tool-call-id
                      :tool-name ""
                      :content [{:type :text :text text}]
                      :meta meta})]
        {:call call
         :result result
         :tool-call-id tool-call-id
         :text text
         :digest (canon/digest [call result])
         :ephemeral? (boolean ephemeral?)
         :format format}))))

(defn build-hint
  "Build a hint pair without touching session state."
  [session opts]
  (language/validate-session-language session)
  (let [values (hint-values session opts)
        text (resources/render-language (:resources session)
                                        (:language session)
                                        values)
        pair (hint-pair {:text text
                         :placement (:placement opts)
                         :ephemeral? (:ephemeral? opts)
                         :format (:format opts)})]
    (assoc pair
           :language (:language session)
           :values values)))

(defn- find-placement
  [placements placement]
  (when (some? placement)
    (let [key (canon/canonical-string placement)]
      (some #(when (= key (:key %)) %) placements))))

(defn- insert-pair
  [messages pair insert-at]
  (let [i (or insert-at (count messages))]
    (when-not (and (integer? i) (<= 0 i (count messages)))
      (throw (ex-info "pair-hint: insert-at is outside the message sequence"
                      {:error :PAIR_HINT_POSITION_INVALID
                       :insert-at i
                       :message-count (count messages)})))
    ;; `:notes` carries no result message, so only insert what exists.
    (into (vec (concat (subvec messages 0 i)
                       (cond-> [(:call pair)]
                         (some? (:result pair)) (conj (:result pair)))))
          (subvec messages i))))

(defn- default-anchor
  "Choose a durable insertion anchor after the last base message.

  Message ids survive conversation growth; call ids cover tool messages that
  have no message id.  The index fallback is only for hosts that expose no
  stable identity, and then a later trim requires an explicit boundary."
  [messages]
  (if-let [message (last messages)]
    (cond
      (some? (:message-id message))
      {:after-message-id (:message-id message)}

      (some? (or (:tool-call-id message) (:call-id message)))
      {:after-call-id (or (:tool-call-id message) (:call-id message))}

      :else
      {:after-index (count messages)})
    {:after-index 0}))

(defn- anchor-index
  [messages anchor]
  (cond
    (nil? anchor) (count messages)

    (contains? anchor :after-message-id)
    (when-let [id (:after-message-id anchor)]
      (some (fn [[i message]]
              (when (= id (:message-id message)) (inc i)))
            (map-indexed vector messages)))

    (contains? anchor :after-call-id)
    (when-let [id (:after-call-id anchor)]
      (some (fn [[i message]]
              (when (= id (or (:tool-call-id message) (:call-id message)))
                (inc i)))
            (map-indexed vector messages)))

    (contains? anchor :after-index)
    (let [i (:after-index anchor)]
      (when (and (integer? i) (<= 0 i (count messages))) i))

    (contains? anchor :tail) (count messages)

    :else nil))

(defn- insertion-index
  "Advance past synthetic pairs already sitting at the same anchor.

  Two requests can share an unchanged base message (for example a retry with
  a new call id).  The new pair must follow the prior pairs, not jump in front
  of them, or the previous chain stops being a prefix."
  [messages anchor]
  (when-let [i (anchor-index messages anchor)]
    (loop [i i]
      (if (and (< i (count messages))
               (:pair-hint (nth messages i)))
        (recur (inc i))
        i))))

(defn- insert-pair-at
  [messages pair index]
  (insert-pair messages pair index))

(defn- pair-present?
  [messages pair]
  (let [id (:tool-call-id pair)]
    (boolean
     (some (fn [message]
             (or (= id (get-in message [:tool-calls 0 :id]))
                 (= id (:tool-call-id message))
                 (= id (get-in message [:content 0 :tool-call-id]))
                 ;; A `:notes` hint carries its id in `:meta`, because it has
                 ;; neither a tool call nor a tool result to look for.
                 (= id (get-in message [:meta :pair-hint-id]))
                 (= id (get-in message [:pair-hint-id]))))
           messages))))

(defn- place-pair
  [messages pair opts]
  (if (contains? opts :insert-at)
    (insert-pair messages pair (:insert-at opts))
    (if-let [anchor (:anchor opts)]
      (if-let [i (insertion-index messages anchor)]
        (insert-pair-at messages pair i)
        (throw (ex-info "pair-hint: placement anchor is not in the request"
                        {:error :PAIR_HINT_ANCHOR_MISSING
                         :anchor anchor})))
      (insert-pair messages pair nil))))

(defn- replay-placements
  "Insert prior synthetic pairs at their durable anchors.

  The current placement is excluded because `append-hint` owns its replay
  path.  Pairs already present in the base sequence are left untouched."
  [messages placements current-key]
  (loop [messages (vec messages)
         remaining (seq placements)]
    (if-not remaining
      {:ok? true :messages messages}
      (let [record (first remaining)
            pair (:pair record)
            anchor (or (:anchor record) {:tail true})]
        (cond
          (= (:key record) current-key)
          (recur messages (next remaining))

          (pair-present? messages pair)
          (recur messages (next remaining))

          :else
          (if-let [i (insertion-index messages anchor)]
            (recur (insert-pair-at messages pair i) (next remaining))
            {:ok? false
             :error {:code :PAIR_HINT_ANCHOR_MISSING
                     :placement (:key record)
                     :anchor anchor}}))))))

(defn- apply-chain-result
  [session chain-result placement pair boundary? anchor]
  (let [placement-record {:key (canon/canonical-string placement)
                          :pair pair
                          :digest (:digest pair)
                          :anchor anchor}
        base (-> session
                 (assoc :chain (:chain chain-result)
                        :chain-boundary (:boundary (:chain chain-result)))
                 (update :hint-count (fnil inc 0)))]
    (cond-> base
      boundary? (assoc :placements [])
      (some? placement) (update :placements (fnil conj []) placement-record)
      boundary? (assoc :pending-boundary nil)
      boundary? (update :epoch-id (fnil inc 0)))))

(defn- merged-boundary
  "Keep both durable facts when an explicit boundary and a pending boundary
  meet in one request.  The explicit boundary owns the new epoch; the pending
  one is recorded as `:supersedes` so it is not silently discarded.  The
  function is idempotent because `project-request` may pass an already merged
  boundary into `append-hint`."
  [explicit pending]
  (cond
    (and (some? explicit)
         (some? pending)
         (not= explicit pending)
         (not (contains? explicit :supersedes)))
    (assoc explicit :supersedes pending)

    (some? explicit) explicit
    :else pending))

(defn append-hint
  "Append one synthetic hint pair to the rendered request sequence.

  `:messages` must be the full sequence the provider would see *before* this
  hint.  Within an epoch, that sequence must extend the stored chain; a
  changed earlier message returns `:PREFIX_DRIFT` instead of silently paying
  for a full recomputation.

  Options:
  - `:messages` full rendered messages without the current hint
  - `:placement` durable placement key; repeats replay instead of duplicating
  - `:insert-at` zero-based insertion index; defaults to the tail
  - `:anchor` durable insertion anchor, e.g. `{:after-message-id id}`;
    defaults to the tail when neither anchor nor insert-at is supplied
  - `:boundary` explicit new prefix boundary (relay, scope, compaction, board)
  - `:ephemeral?` side turn: inject, warm cache, change no state
  - `:format` `:blocks` (default), `:flat` (actual app message shape), or
    `:notes` (one plain assistant message for a tools-less model)

  Returns `{:ok? true :state ... :pair ... :rendered [...]}` or
  `{:ok? false :error {...} :state session}`."
  [session opts]
  (let [ephemeral? (boolean (:ephemeral? opts))
        placement (:placement opts)
        _ (when (and (not ephemeral?) (nil? placement))
            (throw (ex-info "pair-hint: placement is required for a recorded hint"
                            {:error :PAIR_HINT_POSITION_INVALID
                             :field :placement})))
        existing (find-placement (:placements session) placement)
        built (build-hint session opts)
        messages (vec (or (:messages opts) []))
        boundary (merged-boundary (:boundary opts) (:pending-boundary session))
        anchor (:anchor opts)
        rendered (place-pair messages built opts)]
    (cond
      ephemeral?
      {:ok? true
       :state session
       :pair built
       :rendered rendered
       :ephemeral? true}

      (and (some? existing) (nil? boundary))
      (let [existing-anchor (or (:anchor existing) anchor)
            replay-opts (cond-> (assoc opts :anchor existing-anchor)
                          (some? (:anchor existing)) (dissoc :insert-at))
            rendered (place-pair messages (:pair existing) replay-opts)
            check (if-let [prior (:chain session)]
                    (prefix/verify-prefix prior rendered)
                    {:ok? true})]
        (if (:ok? check)
          {:ok? true
           :state session
           :pair (:pair existing)
           :rendered rendered
           :replay? true}
          {:ok? false :error (:error check) :state session}))

      :else
      (let [chain-result
            (if boundary
              {:ok? true :chain (prefix/chain-of rendered boundary)}
              (if-let [prior (:chain session)]
                (prefix/verify-prefix prior rendered)
                {:ok? true :chain (prefix/chain-of rendered nil)}))]
        (if (:ok? chain-result)
          (let [stored-anchor (or anchor
                                  (when (contains? opts :insert-at)
                                    {:after-index (:insert-at opts)})
                                  {:tail true})]
            {:ok? true
             :state (apply-chain-result session chain-result placement built
                                        (boolean boundary) stored-anchor)
             :pair built
             :rendered rendered
             :chain (:chain chain-result)})
          {:ok? false
           :error (:error chain-result)
           :state session})))))

(defn record-request
  "Record a rendered request in the prefix chain without appending a hint.

  Useful for hosts that compose hints elsewhere, and for verifying that a
  replay or a boundary is honest."
  [session messages boundary]
  (let [messages (vec (or messages []))
        boundary (merged-boundary boundary (:pending-boundary session))
        chain-result
        (if boundary
          {:ok? true :chain (prefix/chain-of messages boundary)}
          (if-let [prior (:chain session)]
            (prefix/verify-prefix prior messages)
            {:ok? true :chain (prefix/chain-of messages nil)}))]
    (if (:ok? chain-result)
      {:ok? true
       :state (cond-> (assoc session
                             :chain (:chain chain-result)
                             :chain-boundary (:boundary (:chain chain-result)))
                boundary (assoc :pending-boundary nil :placements [])
                boundary (update :epoch-id (fnil inc 0)))
       :chain (:chain chain-result)}
      {:ok? false :error (:error chain-result) :state session})))

(defn reanchor
  "Open a new prefix boundary without recording a request yet.

  The boundary is validated here; the durable fact that justifies it belongs
  to the caller."
  [session boundary]
  (when (nil? boundary)
    (throw (ex-info "pair-hint: reanchor requires an explicit boundary"
                    {:error :PAIR_HINT_BOUNDARY_INVALID
                     :value nil})))
  (let [boundary (prefix/normalize-boundary boundary)]
    (-> session
        (assoc :chain nil
               :chain-boundary boundary
               :pending-boundary boundary
               :placements []))))

(defn create-session
  "Resolve and bind a new session.

  Options are those of `language/bind-session` plus `:session-id` and any
  initial state under `:session`."
  ([] (create-session {}))
  ([opts]
   (let [bound (language/bind-session opts)]
     (merge {:epoch-id 0
             :chain nil
             :chain-boundary nil
             :placements []
             :hint-count 0
             :compaction-cursor nil
             :board observation/initial-board-state
             :fleet observation/initial-fleet-state
             :board-text nil
             :fleet-text nil
             :scope-boundary nil
             :pending-boundary nil}
            bound
            (:session opts)
            {:language (:language bound)
             :language-source (or (:language-source bound) (:source bound))
             :resources (:resources bound)
             :language-bound? true}))))

(defn resume-session
  "Resume persisted state without re-resolving the language."
  [persisted opts]
  (let [resumed (merge {:epoch-id 0
                        :chain nil
                        :chain-boundary nil
                        :placements []
                        :hint-count 0
                        :compaction-cursor nil
                        :board observation/initial-board-state
                        :fleet observation/initial-fleet-state
                        :board-text nil
                        :fleet-text nil
                        :scope-boundary nil
                        :pending-boundary nil}
                       persisted
                       (language/resume-session persisted opts))]
    (assoc resumed :language-source
           (or (:language-source resumed) (:source resumed)))))

(defn child-session
  "Create a child session or swarm slot bound to the parent's language."
  ([parent] (child-session parent nil {}))
  ([parent task-language] (child-session parent task-language {}))
  ([parent task-language opts]
   (let [created (create-session {:resources (:resources parent)
                                  :explicit-language (:language parent)})]
     (merge created
            (language/inherit-language parent task-language)
            (select-keys opts [:session-id :task-id])
            {:resources (:resources created)}))))

(defn apply-board-observation
  "Apply a board observation and store its text for the next hint.

  A clean-break sets a pending prefix boundary and clears the old chain and
  placements.  The next `append-hint` opens the new epoch."
  [session observation opts]
  (let [result (observation/observe-board (:board session) observation opts)
        next-session
        (if (:ok? result)
          (cond-> (assoc session
                         :board (:state result)
                         :board-text (:text result))
            (= :clean-break (:action result))
            (assoc :chain nil
                   :chain-boundary (:boundary result)
                   :placements []
                   :pending-boundary (:boundary result)))
          session)]
    (assoc result :session next-session)))

(defn apply-fleet-observation
  "Apply a Steward fleet observation and store its text for the next hint.

  A fleet clean-break also opens a board-clean-break prefix boundary, because
  the old summary is no longer part of the rendered sequence."
  [session observation opts]
  (let [result (observation/observe-fleet (:fleet session) observation opts)
        boundary (when (= :clean-break (:action result))
                   {:kind :board-clean-break
                    :id (str "fleet-" (:observed-cursor result))
                    :board-version (:observed-cursor result)})
        next-session
        (if (:ok? result)
          (cond-> (assoc session
                         :fleet (:state result)
                         :fleet-text (:text result))
            (some? boundary)
            (assoc :chain nil
                   :chain-boundary boundary
                   :placements []
                   :pending-boundary boundary))
          session)]
    (assoc result :session next-session :boundary boundary)))

(defn- exception-error
  [exception]
  (let [data (or (ex-data exception) {})]
    (cond-> data
      (contains? data :error) (assoc :code (:error data))
      (contains? data :error) (dissoc :error))))



(defn- apply-observation-entry
  [session entry apply-fn opts]
  (if (nil? entry)
    {:ok? true :session session :result nil}
    (let [observation (if (and (map? entry) (contains? entry :observation))
                        (:observation entry)
                        entry)
          entry-opts (:opts entry)
          ;; The composition layer's renderer is authoritative.  An entry may
          ;; supply one only when the wrapper has none, so a per-entry
          ;; `pr-str` can never silently replace the required EDN/UFSS
          ;; printer.
          options (assoc entry-opts :render (or (:render opts)
                                                (:render entry-opts)))]
      (try
        (let [result (apply-fn session observation options)]
          (if (:ok? result)
            {:ok? true :session (:session result) :result result}
            {:ok? false :error (:error result) :session session}))
        (catch clojure.lang.ExceptionInfo exception
          {:ok? false :error (exception-error exception) :session session})))))

(defn project-request
  "Project one observed outbound request through Pair Hint.

  `request` is the actual app request map with `:snapshot :messages`; the
  messages may use the flat app shape or the block shape.  The result is a
  new request whose `:snapshot :messages` ends with the synthetic pair.

  Options:
  - `:observations` `{:board entry :fleet entry}`; each entry is either the
    observation map or `{:observation ... :opts ...}`.  Observations are
    applied before the hint and are skipped for ephemeral turns.
  - `:context` values passed to the hint (context window, turns, wall clock,
    parked handles)
  - `:placement` durable placement key
  - `:boundary` explicit new prefix boundary
  - `:ephemeral?` side turn: project the pair but change no session state
  - `:render` required EDN/UFSS renderer for structured observations
  - `:format` default `:flat` for the actual app message shape
  - `:tools-supported?` `false` selects `:notes` for a model that declares no
    `:tools` capability; an explicit `:format` still wins

  Returns `{:ok? true :session ... :request ... :projection ...}` or
  `{:ok? false :error {...} :session original-session :request request}`.
  The session is committed only when every step succeeds."
  [session request opts]
  (let [messages (get-in request [:snapshot :messages])
        ephemeral? (boolean (:ephemeral? opts))]
    (if-not (and (map? request) (vector? messages))
      {:ok? false
       :error {:code :PAIR_HINT_REQUEST_INVALID
               :message "request needs a :snapshot map with a :messages vector"}
       :session session
       :request request}
      (let [board-result
            (if ephemeral?
              {:ok? true :session session :result nil}
              (apply-observation-entry session
                                       (get-in opts [:observations :board])
                                       apply-board-observation
                                       opts))
            fleet-result
            (if (or ephemeral? (not (:ok? board-result)))
              (if (:ok? board-result)
                {:ok? true :session (:session board-result) :result nil}
                board-result)
              (apply-observation-entry (:session board-result)
                                       (get-in opts [:observations :fleet])
                                       apply-fleet-observation
                                       opts))]
        (if-not (:ok? fleet-result)
          {:ok? false
           :error (:error fleet-result)
           :session session
           :request request}
          (let [projected-session (:session fleet-result)
                placement (or (:placement opts)
                              (when-not ephemeral?
                                (when-let [call-id (or (:call-id request)
                                                       (:attempt-id request))]
                                  {:call-id call-id})))
                boundary (merged-boundary (:boundary opts)
                                          (:pending-boundary projected-session))
                ;; A boundary may carry further durable facts under
                ;; `:supersedes` (for example a Scope boundary chained ahead
                ;; of a `:compaction` one).  Every fact in the chain owns a
                ;; cursor in the session, so scan the whole chain instead of
                ;; only the outermost boundary.
                boundary-chain (take-while some? (iterate :supersedes boundary))
                scope-boundary (some #(when (contains? #{:scope-reset :scope-remove}
                                                        (:kind %))
                                       %)
                                     boundary-chain)
                compaction-boundary (some #(when (= :compaction (:kind %)) %)
                                         boundary-chain)
                scope-cursor (when scope-boundary
                               (or (:raw scope-boundary)
                                   (case (:kind scope-boundary)
                                     :scope-reset
                                     {:kind :reset-scope
                                      :call-id (:call-id scope-boundary)}
                                     :scope-remove
                                     {:kind :remove-last
                                      :call-id (:call-id scope-boundary)})))
                current-key (when (and (some? placement)
                                       (not ephemeral?)
                                       (nil? boundary))
                              (canon/canonical-string placement))
                current-record (some #(when (= current-key (:key %)) %)
                                     (:placements projected-session))
                current-present? (and current-record
                                      (pair-present? messages (:pair current-record)))
                replayed (cond
                           ephemeral?
                           {:ok? true :messages messages}

                           (some? boundary)
                           {:ok? true :messages messages}

                           current-present?
                           {:ok? true :messages messages}

                           :else
                           (replay-placements messages
                                              (:placements projected-session)
                                              current-key))
                anchor (if ephemeral?
                         {:tail true}
                         (or (:anchor opts)
                             (default-anchor messages)))
                append-result
                (cond
                  (not (:ok? replayed))
                  {:ok? false :error (:error replayed) :state projected-session}

                  current-present?
                  (let [check (try
                                (if-let [prior (:chain projected-session)]
                                  (prefix/verify-prefix prior messages)
                                  {:ok? true})
                                (catch clojure.lang.ExceptionInfo exception
                                  {:ok? false
                                   :error (exception-error exception)}))]
                    (if (:ok? check)
                      {:ok? true
                       :state projected-session
                       :pair (:pair current-record)
                       :rendered messages
                       :replay? true
                       :chain (:chain check)}
                      {:ok? false :error (:error check) :state projected-session}))

                  :else
                  (try
                    (append-hint projected-session
                                 (cond-> (merge (:context opts)
                                                {:messages (:messages replayed)
                                                 :placement placement
                                                 :anchor anchor
                                                 :boundary boundary
                                                 :ephemeral? ephemeral?
                                                 :format (or (:format opts)
                                                             (when (false? (:tools-supported? opts))
                                                               :notes)
                                                             :flat)})
                                   (contains? opts :render)
                                   (assoc :render (:render opts))))
                    (catch clojure.lang.ExceptionInfo exception
                      {:ok? false
                       :error (exception-error exception)
                       :state projected-session})))]
            (if (:ok? append-result)
              {:ok? true
               :session (cond-> (:state append-result)
                          ;; Ephemeral turns project the pair without opening
                          ;; an epoch; they must not consume a boundary
                          ;; cursor, so the next real request keeps the
                          ;; boundary the ephemeral turn never applied.
                          (and scope-boundary (not ephemeral?))
                          (assoc :scope-boundary scope-cursor)
                          (and compaction-boundary (not ephemeral?))
                          (assoc :compaction-cursor (:id compaction-boundary)))
               :request (assoc-in request [:snapshot :messages]
                                  (:rendered append-result))
               :projection {:pair (:pair append-result)
                            :chain (:chain append-result)
                            :anchor anchor
                            :boundary boundary
                            :replay? (:replay? append-result)
                            :ephemeral? (:ephemeral? append-result)
                            :board-action (get-in board-result [:result :action])
                            :fleet-action (get-in fleet-result [:result :action])
                            :needs-snapshot (true? (get-in board-result
                                                           [:result :needs-snapshot]))}}
              {:ok? false
               :error (:error append-result)
               :session session
               :request request})))))))

(defn observed-cursor
  "Latest board version observed by this session."
  [session]
  (observation/board-cursor (:board session)))
