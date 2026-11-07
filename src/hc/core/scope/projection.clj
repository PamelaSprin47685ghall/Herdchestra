(ns hc.core.scope.projection
  "Build the outbound request copy.

  Everything after the newest settled reset boundary stays. Before it, the
  copy keeps operator messages, every reset call and its paired result, the
  pinned members and their paired results, assistant prose, and observed
  provider output. It drops older tool calls, tool results and thinking.

  A removeLast record rewrites the beat before its erasing call into one
  assistant message. The input vector is never changed."
  (:require [hc.core.scope.errors :as errors]))

(defn- item-kind [item]
  (when (map? item)
    (let [kind (:kind item)]
      (cond
        (keyword? kind)
        (case kind
          (:tool-call :toolCall :tool_call) :tool-call
          (:tool-result :toolResult :tool_result) :tool-result
          :message :message
          :thinking :thinking
          :provider :provider
          kind)

        (string? kind) (recur (keyword kind))
        :else nil))))

(defn- item-role [item]
  (let [role (:role item)]
    (cond
      (keyword? role) role
      (string? role) (keyword role)
      :else nil)))

(defn- annotation-kind [item]
  (let [scope (:scope item)]
    (when (map? scope)
      (let [kind (:kind scope)]
        (cond
          (keyword? kind) kind
          (string? kind) (keyword kind)
          :else nil)))))

(defn- reset-annotation? [item]
  (contains? #{:reset-scope :resetScope} (annotation-kind item)))

(defn- reset-item? [item]
  (or (= errors/reset-member (errors/member-name (:member item)))
      (reset-annotation? item)))

(defn- reset-carrier? [item]
  (and (map? item) (reset-annotation? item)))

(defn- same-id? [left right]
  (and (some? left)
       (some? right)
       (= (str left) (str right))))

(defn- call-id-compatible? [left right]
  (or (nil? left)
      (nil? right)
      (= (str left) (str right))))

(defn- operator-message? [item]
  (and (= :message (item-kind item))
       (= :operator (item-role item))))

(defn- assistant-prose? [item]
  (and (= :message (item-kind item))
       (= :assistant (item-role item))
       (string? (:text item))
       (pos? (count (:text item)))))

(defn- pinned? [item pinned]
  (and (contains? #{:tool-call :tool-result} (item-kind item))
       (let [member (errors/member-name (:member item))]
         (and (some? member) (contains? pinned member)))))

(defn- carrier-call [items index]
  (let [item (nth items index)
        kind (item-kind item)]
    (cond
      (= kind :tool-call) index

      (= kind :tool-result)
      (let [previous (when (pos? index) (nth items (dec index)))]
        (if (and previous
                 (= :tool-call (item-kind previous))
                 (call-id-compatible? (:call-id item) (:call-id previous)))
          (dec index)
          -1))

      :else -1)))

(defn newest-reset-index
  "Index of the call that opened the current Scope, or -1.

  With `:settled? true`, `:reset-call-id` is authoritative: nil means no
  boundary, and a call id that is absent from `items` also means no boundary.
  A call that also carries the reset member/annotation wins over a later
  ordinary call that reuses the same call id; otherwise a duplicate id could
  move the boundary forward and drop content that is after the real reset.
  Without a settled fact, the newest item carrying a reset annotation is the
  fallback."
  ([items] (newest-reset-index items {}))
  ([items {:keys [reset-call-id settled?]}]
   (let [items (vec items)
         n (count items)]
     (if settled?
       (if (nil? reset-call-id)
         -1
         (let [matching (keep-indexed
                         (fn [index item]
                           (when (and (= :tool-call (item-kind item))
                                      (same-id? (:call-id item) reset-call-id))
                             index))
                         items)
               reset-match (some (fn [index]
                                   (when (reset-item? (nth items index))
                                     index))
                                 matching)]
           (if (some? reset-match)
             reset-match
             (or (last matching) -1))))
       (loop [index (dec n)]
         (if (neg? index)
           -1
           (if (reset-carrier? (nth items index))
             (let [call (carrier-call items index)]
               (if (neg? call)
                 (recur (dec index))
                 call))
             (recur (dec index)))))))))

(defn- reset-result-index [items reset-at]
  (if (neg? reset-at)
    -1
    (let [next-index (inc reset-at)
          call (nth items reset-at)]
      (if (< next-index (count items))
        (let [next-item (nth items next-index)]
          (if (and (= :tool-result (item-kind next-item))
                   (call-id-compatible? (:call-id call) (:call-id next-item)))
            next-index
            -1))
        -1))))

(defn- call-key [item]
  (when-let [call-id (:call-id item)]
    (str call-id)))

(defn- pair-indexes
  "Pair each tool result with the nearest preceding tool call of the same id.

  Providers can emit several tool results after one assistant message.  A
  pair is identified by call id, not by adjacency; adjacency is only the
  fallback for items without a call id.  A repeated call id starts a new
  occurrence, so a duplicate id cannot attach an earlier result to a later
  call."
  [items]
  (loop [index 0
         current {}
         by-call {}
         by-result {}]
    (if (>= index (count items))
      {:by-call by-call :by-result by-result}
      (let [item (nth items index)
            key (call-key item)]
        (if (nil? key)
          (recur (inc index) current by-call by-result)
          (case (item-kind item)
            :tool-call
            (recur (inc index)
                   (assoc current key {:call index :results []})
                   (assoc by-call index [index])
                   by-result)

            :tool-result
            (if-let [occurrence (get current key)]
              (let [call-index (:call occurrence)]
                (recur (inc index)
                       (update-in current [key :results] conj index)
                       (update by-call call-index conj index)
                       (assoc by-result index call-index)))
              (recur (inc index) current by-call by-result))

            (recur (inc index) current by-call by-result)))))))

(defn- pair-members
  "All indexes in this item's pair occurrence, including the item itself."
  [pairs item index]
  (case (item-kind item)
    :tool-call (get-in pairs [:by-call index] [index])
    :tool-result (if-let [call-index (get-in pairs [:by-result index])]
                   (get-in pairs [:by-call call-index] [index])
                   [index])
    [index]))

(defn- reset-pair? [items index]
  (let [item (nth items index)]
    (if (reset-item? item)
      true
      (let [kind (item-kind item)]
        (if-not (contains? #{:tool-call :tool-result} kind)
          false
          (let [partner-index (if (= kind :tool-call) (inc index) (dec index))
                partner (when (and (not (neg? partner-index))
                                   (< partner-index (count items)))
                          (nth items partner-index))
                partner-kind (if (= kind :tool-call) :tool-result :tool-call)]
            (and partner
                 (= partner-kind (item-kind partner))
                 (call-id-compatible? (:call-id item) (:call-id partner))
                 (reset-item? partner))))))))

(defn spin-line [count]
  (str "Spin count: " count))

(defn- same-source-turn?
  [left right]
  (and (some? (:source-index left))
       (some? (:source-index right))
       (= (:source-index left) (:source-index right))))

(defn- protected-beat-item?
  [item pinned-set]
  (or (reset-item? item)
      (and (contains? #{:tool-call :tool-result} (item-kind item))
           (pinned? item pinned-set))))

(defn- preceding-result-index
  "The nearest tool result before the eraser, outside the eraser's own turn."
  [items floor at eraser]
  (loop [index (dec at)]
    (if (< index floor)
      -1
      (let [item (nth items index)]
        (if (and (= :tool-result (item-kind item))
                 (not (same-source-turn? eraser item)))
          index
          (recur (dec index)))))))

(defn- previous-tool-round
  "Indexes of the completed tool round that ends at `result-at`, or nil.

  The round is the result's call, all results paired with that call by
  call-id, and the thinking items in the call's source-index group before the
  call. Pinned and reset items are never erased."
  [items floor result-at pinned-set pairs]
  (let [result-item (nth items result-at)
        call-at (or (get-in pairs [:by-result result-at])
                    (let [candidate (dec result-at)]
                      (when (and (>= candidate floor)
                                 (= :tool-call (item-kind (nth items candidate)))
                                 (call-id-compatible?
                                  (:call-id result-item)
                                  (:call-id (nth items candidate))))
                        candidate)))]
    (when (and call-at (>= call-at floor))
      (let [call-item (nth items call-at)
            members (get-in pairs [:by-call call-at] [call-at result-at])
            thinking (when (some? (:source-index call-item))
                       (keep-indexed
                        (fn [index item]
                          (when (and (< index call-at)
                                     (>= index floor)
                                     (= :thinking (item-kind item))
                                     (= (:source-index call-item)
                                        (:source-index item)))
                            index))
                        items))
            beat (vec (distinct (concat thinking members)))]
        (when-not (some #(protected-beat-item? (nth items %) pinned-set)
                        beat)
          beat)))))

(defn- previous-assistant-prose
  "The nearest assistant prose before the eraser, outside its own turn."
  [items floor at eraser]
  (loop [index (dec at)]
    (if (< index floor)
      nil
      (let [item (nth items index)]
        (cond
          (same-source-turn? eraser item) (recur (dec index))
          (and (= :message (item-kind item))
               (assistant-prose? item)) [index]
          :else (recur (dec index)))))))

(defn- beat-before [items reset-at at pinned-set pairs]
  (when (pos? at)
    (let [eraser (nth items at)
          floor (inc reset-at)]
      (when (= :tool-call (item-kind eraser))
        (let [result-at (preceding-result-index items floor at eraser)]
          (if (neg? result-at)
            (previous-assistant-prose items floor at eraser)
            (previous-tool-round items floor result-at pinned-set pairs)))))))

(defn project
  "Return the outbound copy of `items`, or a refusal value.

  Options:
  - `:erase` vector of `{:call-id :replacement :spin}` records.
  - `:pinned` extra members to keep; the default pinned members always stay.
  - `:spin-text` function from spin count to the rendered line.
  - `:reset-call-id` the settled reset call. Pass `:settled? true` so a nil
    or absent call id means no boundary instead of falling back to an
    annotation."
  ([items] (project items {}))
  ([items {:keys [erase pinned spin-text reset-call-id settled?]
           :or {erase [] pinned [] spin-text spin-line settled? false}}]
   (if-not (sequential? items)
     (errors/refuse errors/field-invalid "request items must be a sequence")
     (let [items (vec items)
           pinned-set (into (set errors/default-pinned-members)
                            (keep errors/member-name)
                            pinned)
           reset-at (newest-reset-index items {:reset-call-id reset-call-id
                                               :settled? settled?})
           reset-result-at (reset-result-index items reset-at)
           pairs (pair-indexes items)
           base-keep? (fn [index]
                        (let [item (nth items index)
                              kind (item-kind item)]
                          (cond
                            (= kind :provider) true
                            (> index reset-at) true
                            (= index reset-at) true
                            (= index reset-result-at) true
                            (= kind :message) (or (operator-message? item)
                                                  (assistant-prose? item))
                            (contains? #{:tool-call :tool-result} kind)
                            (or (pinned? item pinned-set)
                                (reset-pair? items index))
                            :else false)))
           keep? (fn [item index]
                   (let [kind (item-kind item)]
                     (if (contains? #{:tool-call :tool-result} kind)
                       (let [members (pair-members pairs item index)]
                         (if (seq members)
                           (boolean (some base-keep? members))
                           (base-keep? index)))
                       (base-keep? index))))
           beats (keep (fn [entry]
                         (when-let [eraser-at
                                    (first (keep-indexed
                                            (fn [index item]
                                              (when (and (= :tool-call (item-kind item))
                                                         (same-id? (:call-id item)
                                                                   (:call-id entry)))
                                                index))
                                            items))]
                           (when-let [indices (beat-before items reset-at eraser-at
                                                           pinned-set pairs)]
                             {:indices indices :entry entry})))
                       erase)
           beat-at (reduce (fn [result {:keys [indices] :as beat}]
                             (reduce #(assoc %1 %2 beat) result indices))
                           {}
                           beats)]
       (loop [index 0
              output []]
         (if (>= index (count items))
           output
           (if-let [beat (get beat-at index)]
             (if (= index (first (:indices beat)))
               (let [entry (:entry beat)]
                 (recur (inc index)
                        (conj output {:kind :message
                                      :role :assistant
                                      :text (str (:replacement entry) "\n"
                                                 (spin-text (:spin entry)))
                                      :erased-call-id (:call-id entry)})))
               (recur (inc index) output))
             (let [item (nth items index)
                   next-item (when (< (inc index) (count items))
                               (nth items (inc index)))
                   pair? (and (= :tool-call (item-kind item))
                              (= :tool-result (item-kind next-item))
                              (call-id-compatible? (:call-id item)
                                                   (:call-id next-item)))
                   keep-item? (keep? item index)
                   keep-next? (when pair? (keep? next-item (inc index)))]
               (if (and pair? (not= keep-item? keep-next?))
                 (errors/refuse errors/field-invalid
                                (str "tool call " (pr-str (:call-id item))
                                     " and its result disagree on retention")
                                {:call-id (:call-id item)})
                 (if keep-item?
                   (recur (inc index) (conj output item))
                   (recur (if pair? (+ index 2) (inc index)) output)))))))))))
