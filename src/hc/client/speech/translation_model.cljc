(ns hc.client.speech.translation-model
  "Pure selection-safe translation actions.

  A replacement is allowed only when the source text, the selection start/end,
  and the selected fragment all still match the request that produced the
  translation.  Any mismatch returns a blocked plan with an operator-facing
  message; the UI must then offer copy or insert-at-cursor instead.")

(defn replace-plan
  [{:keys [source selection result]}]
  (let [{:keys [start end]} selection
        expected (:request-text result)
        current (when (and (integer? start)
                           (integer? end)
                           (< start end)
                           (<= end (count source)))
                  (subs source start end))]
    (cond
      (not (:fragment? result))
      {:action :blocked
       :message "整段翻译没有可替换的选区；请复制译文。"}

      (not= source (:source-at-request result))
      {:action :blocked
       :message "原文已修改，不能替换旧位置；请复制译文或插入到当前光标。"}

      (or (not= start (:request-start result))
          (not= end (:request-end result))
          (not= current expected))
      {:action :blocked
       :message "选区已变化，不能替换旧位置；请复制译文或插入到当前光标。"}

      :else
      (let [translated (str (:translated-text result))]
        {:action :replace
         :text (str (subs source 0 start)
                    translated
                    (subs source end))
         :cursor (+ start (count translated))}))))

(defn insert-plan
  [source selection text]
  (let [start (long (or (:start selection) 0))
        start (max 0 (min (count source) start))
        text (str text)]
    {:action :insert
     :text (str (subs source 0 start) text (subs source start))
     :cursor (+ start (count text))}))

(defn select-service-id
  "Choose the configured default only when it is enabled; otherwise the first
  enabled service.  Returns nil when none is enabled."
  [services default-id]
  (let [enabled (vec (filter :enabled? services))]
    (if (some #(= default-id (:id %)) enabled)
      default-id
      (:id (first enabled)))))

(defn remember-result
  "Store one receipt under its target language without touching other
  languages.  Callers use this as a functional update so a late response
  cannot overwrite results added while it was in flight."
  [results target receipt]
  (assoc results target receipt))

(defn result-for
  [results target]
  (get results target))

(defn replace-request
  "Payload for the explicit replace action, or nil when replacement is not
  safe.  The caller must only render the replace button when this returns a
  map; copy and insert remain available for every other outcome."
  [source selection result]
  (let [plan (replace-plan {:source source :selection selection :result result})]
    (when (= :replace (:action plan))
      {:start (:start selection)
       :end (:end selection)
       :expected-text (:text selection)
       :text (:translated-text result)})))
