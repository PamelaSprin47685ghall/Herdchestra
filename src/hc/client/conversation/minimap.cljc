(ns hc.client.conversation.minimap
  "Pure topic overview and density map for long conversations.

  The view owns scrolling and DOM measurement.  This namespace only turns a
  message list into stable anchors, labels, and type-density runs.  Anchors
  are indexes in the input list; `locate` keeps the same anchor when new
  messages are appended after the target.  A long list may be sampled for
  display, but every input id remains locatable through `:id->anchor`."
  (:require [clojure.string :as str]))

(def kind-labels
  {:user "用户"
   :assistant "助手"
   :tool "工具"
   :attachment "附件"
   :context-boundary "上下文边界"})

(defn- kind-of
  [message]
  (keyword (or (:kind message) :assistant)))

(defn- label-of
  [message]
  (let [kind (kind-of message)
        explicit (:label message)
        text (str (or (:text message) ""))]
    (cond
      (and (string? explicit) (not (str/blank? explicit))) explicit
      (not (str/blank? text)) (str (get kind-labels kind "消息") "："
                                   (subs text 0 (min 32 (count text))))
      :else (get kind-labels kind "消息"))))

(defn- density-runs
  [items]
  (loop [remaining items
         runs []]
    (if-let [item (first remaining)]
      (let [kind (:kind item)
            same (take-while #(= kind (:kind %)) remaining)
            start (:anchor (first same))
            end (:anchor (last same))]
        (recur (drop (count same) remaining)
               (conj runs {:kind kind
                           :count (count same)
                           :start-index start
                           :end-index end})))
      runs)))

(defn- sampled
  "Keep first/last/selected/context boundaries and a stable stride."
  [items selected-id limit]
  (if (<= (count items) limit)
    items
    (let [total (count items)
          stride (max 1 (long (Math/ceil (/ (double total) limit))))
          selected-index (some (fn [item]
                                 (when (= selected-id (:id item))
                                   (:anchor item)))
                               items)
          special (set (concat [0 (dec total) selected-index]
                               (keep (fn [item]
                                       (when (= :context-boundary (:kind item))
                                         (:anchor item)))
                                     items)))
          strided (range 0 total stride)
          base (->> (concat special strided)
                    (remove nil?)
                    distinct
                    sort
                    (take (max limit 1))
                    vec)
          chosen (loop [chosen base]
                   (cond
                     (and selected-index
                          (not (some #{selected-index} chosen)))
                     (recur (conj (subvec chosen 0 (max 0 (dec (count chosen))))
                                  selected-index))

                     (and (pos? total)
                          (not (some #{(dec total)} chosen)))
                     (recur (conj (subvec chosen 0 (max 0 (dec (count chosen))))
                                  (dec total)))

                     :else chosen))]
      (mapv #(nth items %) (sort chosen)))))

(defn overview
  "Return `{:items :density :counts :truncated? :total-count :id->anchor}`.

  Items are `{:id :label :kind :anchor}`.  `:anchor` is the zero-based input
  index.  With more than `:limit` messages the display items are sampled, but
  `:id->anchor` still contains every id so `locate` remains stable."
  ([messages] (overview messages nil {}))
  ([messages selected-id] (overview messages selected-id {}))
  ([messages selected-id opts]
   (let [input (vec (or messages []))
         duplicates (->> input
                         (map :id)
                         frequencies
                         (keep (fn [[id n]] (when (> n 1) id)))
                         vec)
         messages (loop [remaining input
                         seen #{}
                         unique []]
                    (if-let [message (first remaining)]
                      (if (contains? seen (:id message))
                        (recur (rest remaining) seen unique)
                        (recur (rest remaining)
                               (conj seen (:id message))
                               (conj unique message)))
                      unique))
         limit (long (or (:limit opts) 200))
         all-items (mapv (fn [index message]
                           {:id (:id message)
                            :label (label-of message)
                            :kind (kind-of message)
                            :anchor index})
                         (range) messages)
         items (sampled all-items selected-id limit)]
     {:items items
      :density (density-runs items)
      :counts (frequencies (map :kind all-items))
      :truncated? (> (count all-items) (count items))
      :total-count (count all-items)
      :input-count (count input)
      :duplicate-ids duplicates
      :selected-id selected-id
      :id->anchor (into {} (map (juxt :id :anchor)) all-items)})))

(defn locate
  "Return the scroll anchor index for ID, or nil when the id is absent.

  The anchor is stable when new messages are appended after ID."
  [overview id]
  (get-in overview [:id->anchor id]))

(defn nearest-anchor
  "Return the displayed item at or before ID, or nil."
  [overview id]
  (when-let [anchor (locate overview id)]
    (->> (:items overview)
         (filter #(<= (:anchor %) anchor))
         last)))
