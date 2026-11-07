(ns hc.client.schedule.time
  "Browser recurrence arithmetic for schedule previews.

  The server owns execution and persisted `:next-run` facts.  This namespace
  only answers what a proposed recurrence would do in an IANA zone, so the
  create/edit form can show real dates before saving.  It uses `Intl` rather
  than a second server round trip and mirrors the server rules that matter to
  the user:

  - a nonexistent local time (spring-forward gap) is reported as a gap and
    never shifted to a neighbouring time;
  - an ambiguous local time (fall-back overlap) resolves to the earlier
    instant;
  - occurrences are strictly after the supplied instant.

  Every value is plain data.  No command, draft, or domain state is changed."
  (:require [clojure.string :as str]))

(def day-names
  ["周一" "周二" "周三" "周四" "周五" "周六" "周日"])

(def default-max-days 800)

(defn device-zone
  "The browser's current IANA zone.  Falls back to `UTC` rather than
  pretending the zone is unknown."
  []
  (try
    (let [opts (.resolvedOptions (js/Intl.DateTimeFormat.))]
      (or (aget opts "timeZone") "UTC"))
    (catch :default _
      "UTC")))

(defn valid-zone?
  [zone]
  (and (string? zone)
       (not (str/blank? zone))
       (try
         (js/Intl.DateTimeFormat. "en-US" #js {:timeZone zone})
         true
         (catch :default _ false))))

(defn- pad2
  [n]
  (let [n (js/Math.abs (js/Math.trunc n))
        s (str n)]
    (if (< (count s) 2) (str "0" s) s)))

(defn- formatter
  [zone]
  (js/Intl.DateTimeFormat.
   "en-US"
   #js {:timeZone zone
        :year "numeric"
        :month "2-digit"
        :day "2-digit"
        :hour "2-digit"
        :minute "2-digit"
        :second "2-digit"
        :hourCycle "h23"}))

(defn zone-parts
  "The wall-clock fields of EPOCH-MS in ZONE."
  [zone epoch-ms]
  (let [parts (.formatToParts (formatter zone) (js/Date. epoch-ms))]
    (reduce (fn [m part]
              (let [t (.-type part)
                    v (.-value part)]
                (case t
                  "year" (assoc m :year (js/parseInt v 10))
                  "month" (assoc m :month (js/parseInt v 10))
                  "day" (assoc m :day (js/parseInt v 10))
                  "hour" (assoc m :hour (js/parseInt v 10))
                  "minute" (assoc m :minute (js/parseInt v 10))
                  "second" (assoc m :second (js/parseInt v 10))
                  m)))
            {:zone zone}
            (array-seq parts))))

(defn offset-minutes
  "The zone offset at EPOCH-MS, in minutes east of UTC."
  [zone epoch-ms]
  (let [{:keys [year month day hour minute second]} (zone-parts zone epoch-ms)
        second (or second 0)
        as-utc (js/Date.UTC year (dec month) day hour minute second)
        whole-second-ms (* 1000 (js/Math.floor (/ epoch-ms 1000)))]
    (js/Math.round (/ (- as-utc whole-second-ms) 60000))))

(defn offset-label
  [minutes]
  (let [sign (if (neg? minutes) "-" "+")
        abs (js/Math.abs minutes)
        hours (js/Math.floor (/ abs 60))
        mins (mod abs 60)]
    (str sign (pad2 hours) ":" (pad2 mins))))

(defn local-date-time
  "`YYYY-MM-DDTHH:MM` in ZONE."
  [zone epoch-ms]
  (let [{:keys [year month day hour minute]} (zone-parts zone epoch-ms)]
    (str year "-" (pad2 month) "-" (pad2 day)
         "T" (pad2 hour) ":" (pad2 minute))))

(defn local-date
  "`YYYY-MM-DD` in ZONE."
  [zone epoch-ms]
  (let [{:keys [year month day]} (zone-parts zone epoch-ms)]
    (str year "-" (pad2 month) "-" (pad2 day))))

(defn display-date-time
  "`YYYY-MM-DD HH:MM` in ZONE."
  [zone epoch-ms]
  (let [{:keys [year month day hour minute]} (zone-parts zone epoch-ms)]
    (str year "-" (pad2 month) "-" (pad2 day)
         " " (pad2 hour) ":" (pad2 minute))))

(defn local-view
  "One instant in its own zone and in the viewing device zone.

  Historical facts use their recorded zone; this function never rewrites
  them.  `:device-*` fields are absent when both zones are equal."
  [epoch-ms zone device-zone]
  (when (and (number? epoch-ms) (valid-zone? zone))
    (let [device-zone (if (valid-zone? device-zone) device-zone zone)
          base {:zone zone
                :offset (offset-label (offset-minutes zone epoch-ms))
                :local-date-time (local-date-time zone epoch-ms)
                :display (display-date-time zone epoch-ms)}]
      (cond-> base
        (not= device-zone zone)
        (assoc :device-zone device-zone
               :device-offset (offset-label
                               (offset-minutes device-zone epoch-ms))
               :device-local-date-time (local-date-time device-zone epoch-ms)
               :device-display (display-date-time device-zone epoch-ms))))))

(defn- date-add
  [date days]
  (let [[y m d] (mapv #(js/parseInt % 10) (str/split date #"-"))
        dt (js/Date. (js/Date.UTC y (dec m) (+ d days)))]
    (str (.getUTCFullYear dt)
         "-" (pad2 (inc (.getUTCMonth dt)))
         "-" (pad2 (.getUTCDate dt)))))

(defn- iso-weekday
  [date]
  (let [[y m d] (mapv #(js/parseInt % 10) (str/split date #"-"))
        day (.getUTCDay (js/Date. (js/Date.UTC y (dec m) d)))]
    (if (zero? day) 7 day)))

(defn- date-compare
  [a b]
  (compare a b))

(defn valid-local-date?
  "True for a real `YYYY-MM-DD` date.  A regex match alone would accept
  `2026-02-31`, which the server rejects."
  [value]
  (when (and (string? value)
             (re-matches #"\d{4}-\d{2}-\d{2}" value))
    (let [[y m d] (mapv #(js/parseInt % 10) (str/split value #"-"))
          dt (js/Date. (js/Date.UTC y (dec m) d))]
      (and (= y (.getUTCFullYear dt))
           (= m (inc (.getUTCMonth dt)))
           (= d (.getUTCDate dt))))))

(defn- parse-local-date
  [value]
  (when (valid-local-date? value) value))

(defn- normalize-time
  [time]
  (let [hour (js/parseInt (str (:hour time)) 10)
        minute (js/parseInt (str (:minute time)) 10)]
    (when (and (<= 0 hour 23) (<= 0 minute 59))
      {:hour hour :minute minute})))

(defn valid-recurrence?
  [recurrence]
  (and (map? recurrence)
       (contains? #{:once :daily :weekdays :weekly} (:kind recurrence))
       (some? (normalize-time (:time recurrence)))
       (case (:kind recurrence)
         :once (some? (parse-local-date (:local-date recurrence)))
         :weekly (and (sequential? (:days recurrence))
                      (seq (:days recurrence))
                      (every? #(and (integer? %) (<= 1 % 7))
                              (:days recurrence)))
         true)
       (or (nil? (:starts-on recurrence))
           (some? (parse-local-date (:starts-on recurrence))))))

(defn resolve-local
  "Resolve a wall-clock date and time in ZONE.

  Returns `{:status :ok|:overlap|:gap ...}`.  An overlap chooses the earlier
  instant; a gap is never silently moved."
  [zone year month day hour minute]
  (let [guess (js/Date.UTC year (dec month) day hour minute 0)
        offsets (distinct
                 (map #(offset-minutes zone %)
                      [(- guess 86400000) guess (+ guess 86400000)]))
        matches? (fn [epoch]
                   (let [{py :year pm :month pd :day ph :hour pmi :minute}
                         (zone-parts zone epoch)]
                     (= [py pm pd ph pmi]
                        [year month day hour minute])))
        candidates (->> offsets
                        (map #(- guess (* % 60000)))
                        (filter matches?)
                        distinct
                        sort
                        vec)]
    (cond
      (empty? candidates)
      {:status :gap
       :zone zone
       :local-date-time (str year "-" (pad2 month) "-" (pad2 day)
                             "T" (pad2 hour) ":" (pad2 minute))
       :nominal-epoch-ms (- guess
                            (* 60000 (offset-minutes zone
                                                     (- guess 86400000))))}

      (= 1 (count candidates))
      (let [epoch (first candidates)]
        {:status :ok
         :epoch-ms epoch
         :zone zone
         :offset (offset-label (offset-minutes zone epoch))
         :local-date-time (local-date-time zone epoch)})

      :else
      (let [epoch (first candidates)]
        {:status :overlap
         :epoch-ms epoch
         :ambiguous? true
         :zone zone
         :offset (offset-label (offset-minutes zone epoch))
         :local-date-time (local-date-time zone epoch)}))))

(defn- day-matches?
  [recurrence date]
  (case (:kind recurrence)
    :once (= date (:local-date recurrence))
    :daily true
    :weekdays (<= 1 (iso-weekday date) 5)
    :weekly (contains? (set (:days recurrence)) (iso-weekday date))
    false))

(defn- occurrence
  [zone date time]
  (let [[y m d] (mapv #(js/parseInt % 10) (str/split date #"-"))]
    (resolve-local zone y m d (:hour time) (:minute time))))

(defn- start-date
  [recurrence fallback]
  (case (:kind recurrence)
    :once (:local-date recurrence)
    (or (parse-local-date (:starts-on recurrence)) fallback)))

(defn next-occurrences
  "Up to N real occurrences strictly after NOW-MS, plus gaps before them.

  Returns `{:runs [...] :gaps [...] :exhausted? bool}`.  A gap does not count
  toward N."
  [recurrence zone now-ms n]
  (let [now-ms (long now-ms)
        n (max 1 (long n))
        time (normalize-time (:time recurrence))
        from-date (local-date zone now-ms)
        first-date (start-date recurrence from-date)
        start (if (pos? (date-compare first-date from-date))
                first-date
                from-date)]
    (if (or (nil? time) (not (valid-zone? zone)))
      {:runs [] :gaps [] :exhausted? true}
      (loop [date start
             remaining default-max-days
             runs []
             gaps []]
        (let [stop-once? (and (= :once (:kind recurrence))
                              (pos? (date-compare date first-date)))]
          (if (or (zero? remaining)
                  (>= (count runs) n)
                  stop-once?)
            {:runs runs
             :gaps gaps
             :exhausted? (and (zero? remaining)
                              (< (count runs) n)
                              (not stop-once?))}
            (if (day-matches? recurrence date)
              (let [occ (occurrence zone date time)]
                (case (:status occ)
                  :gap
                  (recur (date-add date 1)
                         (dec remaining)
                         runs
                         (if (> (:nominal-epoch-ms occ) now-ms)
                           (conj gaps occ)
                           gaps))
                  (recur (date-add date 1)
                         (dec remaining)
                         (if (> (:epoch-ms occ) now-ms)
                           (conj runs occ)
                           runs)
                         gaps)))
              (recur (date-add date 1)
                     (dec remaining)
                     runs
                     gaps))))))))

(defn describe-recurrence
  [recurrence]
  (let [time (normalize-time (:time recurrence))
        hhmm (if time
               (str (pad2 (:hour time)) ":" (pad2 (:minute time)))
               "--:--")]
    (case (:kind recurrence)
      :once (str "单次 " (:local-date recurrence) " " hhmm)
      :daily (str "每天 " hhmm)
      :weekdays (str "工作日 " hhmm)
      :weekly (str "每周"
                   (str/join "、" (map #(subs (nth day-names (dec %)) 1)
                                       (sort (distinct (:days recurrence)))))
                   " " hhmm)
      (str "未知重复规则 " hhmm))))

(defn- view-occurrence
  [occurrence device-zone]
  (let [zone (:zone occurrence)
        epoch (:epoch-ms occurrence)]
    (cond-> occurrence
      (and (some? epoch)
           (valid-zone? device-zone)
           (not= device-zone zone))
      (merge (select-keys (local-view epoch zone device-zone)
                          [:device-zone :device-offset
                           :device-local-date-time :device-display])))))

(defn preview
  "Compute the next COUNT real occurrences for RECURRENCE in TIMEZONE.

  `timezone` is `{:mode :follow-device|:fixed :zone \"...\"}`.  Returns
  `{:ok? true :preview {...}}` or `{:ok? false :error {...}}`."
  ([recurrence timezone now-ms]
   (preview recurrence timezone now-ms {}))
  ([recurrence timezone now-ms {:keys [count] :as opts}]
   (let [zone (:zone timezone)
         requested-zone (:device-zone opts)
         viewer-zone (if (valid-zone? requested-zone)
                       requested-zone
                       (device-zone))]
     (cond
       (not (valid-recurrence? recurrence))
       {:ok? false
        :error {:code :INVALID_RECURRENCE
                :message "重复规则不完整或时间无效"}}

       (not (valid-zone? zone))
       {:ok? false
        :error {:code :INVALID_TIMEZONE
                :message "时区无效"}}

       :else
       (let [{:keys [runs gaps exhausted?]}
             (next-occurrences recurrence zone now-ms count)]
         {:ok? true
          :preview {:zone zone
                    :timezone-mode (:mode timezone)
                    :rule recurrence
                    :readable (str (describe-recurrence recurrence)
                                   " (" zone ")")
                    :times (mapv #(view-occurrence % viewer-zone) runs)
                    :gaps (mapv #(view-occurrence % viewer-zone) gaps)
                    :exhausted? (boolean exhausted?)
                    :next-run (:epoch-ms (first runs))}})))))

(defn format-epoch
  "Display an epoch in ZONE with the offset, or an honest placeholder."
  [epoch-ms zone]
  (if (and (number? epoch-ms) (valid-zone? zone))
    (str (display-date-time zone epoch-ms)
         " (" zone ", " (offset-label (offset-minutes zone epoch-ms)) ")")
    "时间未知"))
