(ns hc.compose.scheduler.time
  "Zone-aware recurrence arithmetic for scheduled tasks (AGENTS.md §7.4 旅程 6.8（定时任务）,
  §9.3–§9.4).

  All public values are plain data: epoch milliseconds, strings, maps, and
  vectors.  No `java.time` object escapes this namespace.  The caller supplies
  `now`; this namespace never reads a clock.

  DST rules:

  - A local time that does not exist (spring-forward gap) is returned as a
    `:gap` occurrence.  It is never shifted into a neighbouring time.
  - A local time that occurs twice (fall-back overlap) is resolved to the
    first occurrence, the earlier offset.  `:ambiguous?` records the fact.
  - A gap has `:nominal-epoch-ms` for ordering only.  It is not a run time.

  Recurrence maps:

    {:kind :once     :local-date \"2026-11-05\" :time {:hour 8 :minute 30}}
    {:kind :daily    :time {:hour 8 :minute 30}}
    {:kind :weekdays :time {:hour 8 :minute 30}}
    {:kind :weekly   :days [1 3] :time {:hour 8 :minute 30}}

  `:days` uses ISO day numbers, Monday = 1.  An optional `:starts-on` local
  date bounds daily, weekday, and weekly rules."
  (:require [clojure.string :as str])
  (:import (java.time Instant LocalDate LocalDateTime LocalTime ZoneId
                      ZonedDateTime)
           (java.time.temporal ChronoUnit)
           (java.util Date)))

(def day-names
  ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(def default-max-days
  "A hard bound for one recurrence scan.  It keeps a malformed rule from
  spinning; twenty years is far beyond any real schedule horizon."
  7320)

(defn to-epoch-ms
  "Normalize a clock value to epoch milliseconds.

  Accepts an integer, `java.time.Instant`, or `java.util.Date`."
  [value]
  (cond
    (integer? value) (long value)
    (instance? Instant value) (.toEpochMilli ^Instant value)
    (instance? Date value) (.getTime ^Date value)
    :else
    (throw (ex-info "scheduler time: expected epoch ms, Instant, or Date"
                    {:code :INVALID_CLOCK
                     :value value}))))

(defn valid-zone?
  [zone]
  (and (string? zone)
       (not (str/blank? zone))
       (try
         (ZoneId/of zone)
         true
         (catch Exception _ false))))

(defn zone-id
  "Parse ZONE or throw `ExceptionInfo` with `:code :INVALID_ZONE`.

  A `java.time.ZoneId` value is accepted unchanged so internal callers can
  pass the parsed zone without re-parsing it."
  [zone]
  (if (instance? ZoneId zone)
    zone
    (do
      (when-not (valid-zone? zone)
        (throw (ex-info "scheduler time: invalid zone id"
                        {:code :INVALID_ZONE :zone zone})))
      (ZoneId/of zone))))

(defn system-zone
  "The current JVM default zone id as a string."
  []
  (str (ZoneId/systemDefault)))

(defn valid-local-date?
  [value]
  (and (string? value)
       (try
         (LocalDate/parse value)
         true
         (catch Exception _ false))))

(defn parse-local-time
  "Parse `{:hour h :minute m}`, `[h m]`, or `\"HH:mm\"`.

  Returns `{:ok? true :time {:hour h :minute m}}` or an error value."
  [value]
  (let [candidate
        (cond
          (map? value)
          (if (and (integer? (:hour value))
                   (integer? (:minute value)))
            [(:hour value) (:minute value)]
            ::invalid)

          (and (sequential? value) (= 2 (count value)))
          (vec value)

          (string? value)
          (let [[hour minute extra] (str/split value #":" -1)
                parse (fn [text]
                        (try
                          (parse-long text)
                          (catch Exception _ nil)))]
            (if (and hour minute (nil? extra))
              [(parse hour) (parse minute)]
              ::invalid))

          :else ::invalid)]
    (if (or (= ::invalid candidate)
            (not (every? integer? candidate))
            (not (<= 0 (first candidate) 23))
            (not (<= 0 (second candidate) 59)))
      {:ok? false
       :error {:code :INVALID_LOCAL_TIME
               :message "a local time needs :hour 0-23 and :minute 0-59"
               :details {:value value}}}
      {:ok? true :time {:hour (long (first candidate))
                        :minute (long (second candidate))}})))

(defn format-time
  [{:keys [hour minute]}]
  (format "%02d:%02d" (long hour) (long minute)))

(defn resolve-local
  "Resolve a local date and time in ZONE.

  Returns one of:

    {:status :ok      :zdt zdt :epoch-ms n :offset \"+08:00\" ...}
    {:status :overlap :zdt zdt :epoch-ms n :ambiguous? true ...}
    {:status :gap     :local-date-time ldt :zone \"...\"}

  For an overlap, the earlier offset is chosen.  The occurrence map contains
  only plain data."
  [zone date time]
  (let [^ZoneId zone (if (instance? ZoneId zone) zone (zone-id zone))
        ^LocalDate date (if (instance? LocalDate date)
                          date
                          (LocalDate/parse (str date)))
        ldt (LocalDateTime/of date (LocalTime/of (long (:hour time))
                                                 (long (:minute time))))
        offsets (.getValidOffsets (.getRules zone) ldt)]
    (if (empty? offsets)
      {:status :gap
       :local-date-time (str ldt)
       :zone (str zone)}
      (let [zdt (ZonedDateTime/ofLocal ldt zone (first offsets))]
        {:status (if (= 2 (count offsets)) :overlap :ok)
         :zdt zdt
         :epoch-ms (.toEpochMilli (.toInstant zdt))
         :local-date-time (str ldt)
         :zone (str zone)
         :offset (str (.getOffset zdt))
         :ambiguous? (= 2 (count offsets))}))))

(defn- occurrence
  [zone date time]
  (let [resolved (resolve-local zone date time)]
    (if (= :gap (:status resolved))
      (let [ldt (LocalDateTime/parse (:local-date-time resolved))
            rules (.getRules (zone-id zone))
            ;; `getOffset` returns the offset before the spring-forward
            ;; transition for a gap.  Using the adjusted ZonedDateTime would
            ;; move the nominal instant forward by the gap length.
            offset (.getOffset rules ldt)
            nominal (.toEpochMilli
                     (.toInstant (.atOffset ldt offset)))]
        {:kind :gap
         :local-date-time (:local-date-time resolved)
         :zone (:zone resolved)
         :reason :nonexistent-local-time
         :nominal-epoch-ms nominal})
      {:kind :run
       :epoch-ms (:epoch-ms resolved)
       :local-date-time (:local-date-time resolved)
       :zone (:zone resolved)
       :offset (:offset resolved)
       :zdt (str (:zdt resolved))
       :ambiguous? (boolean (:ambiguous? resolved))})))

(defn- starts-on
  [recurrence]
  (when-let [value (:starts-on recurrence)]
    (LocalDate/parse value)))

(defn- day-matches?
  [recurrence ^LocalDate date]
  (case (:kind recurrence)
    :once (= (str date) (:local-date recurrence))
    :daily true
    :weekdays (<= 1 (.getValue (.getDayOfWeek date)) 5)
    :weekly (contains? (set (:days recurrence))
                       (.getValue (.getDayOfWeek date)))
    false))

(defn- recurrence-start-date
  [recurrence ^LocalDate fallback]
  (case (:kind recurrence)
    :once (LocalDate/parse (:local-date recurrence))
    (or (starts-on recurrence) fallback)))

(defn occurrences-between
  "Return occurrences whose nominal time falls in `[from-ms, until-ms]`.

  The result is `{:runs [...] :gaps [...] :exhausted? bool}` in chronological
  order.  `:runs` are real instants; `:gaps` are DST nonexistent times.  The
  scan starts on the local date of FROM, so an occurrence earlier that day is
  filtered by its epoch value, not by the date."
  ([recurrence zone-name from-ms until-ms]
   (occurrences-between recurrence zone-name from-ms until-ms {}))
  ([recurrence zone-name from-ms until-ms {:keys [max-days]
                                           :or {max-days default-max-days}}]
   (let [from-ms (to-epoch-ms from-ms)
         until-ms (to-epoch-ms until-ms)
         zone (zone-id zone-name)
         from-date (.toLocalDate (.atZone (Instant/ofEpochMilli from-ms) zone))
         until-date (.toLocalDate (.atZone (Instant/ofEpochMilli until-ms) zone))
         first-date (recurrence-start-date recurrence from-date)
         start-date (if (.isAfter ^LocalDate first-date from-date)
                      first-date
                      from-date)
         day-count (max 0 (inc (.between ChronoUnit/DAYS
                                         ^LocalDate start-date
                                         ^LocalDate until-date)))
         day-count (min (long day-count) (long max-days))
         past-once? (and (= :once (:kind recurrence))
                         (.isBefore ^LocalDate first-date from-date))]
     (if past-once?
       {:runs [] :gaps [] :exhausted? true}
       (loop [date start-date
              remaining day-count
              runs []
              gaps []]
         (if (or (zero? remaining)
                 (.isAfter ^LocalDate date until-date))
           {:runs runs
            :gaps gaps
            :exhausted? (> (inc (.between ChronoUnit/DAYS
                                            ^LocalDate start-date
                                            ^LocalDate until-date))
                           (long max-days))}
           (if (day-matches? recurrence date)
             (let [occ (occurrence zone date (:time recurrence))]
               (case (:kind occ)
                 :gap
                 (if (<= from-ms (:nominal-epoch-ms occ) until-ms)
                   (recur (.plusDays ^LocalDate date 1) (dec remaining)
                          runs (conj gaps occ))
                   (recur (.plusDays ^LocalDate date 1) (dec remaining)
                          runs gaps))
                 :run
                 (if (<= from-ms (:epoch-ms occ) until-ms)
                   (recur (.plusDays ^LocalDate date 1) (dec remaining)
                          (conj runs occ) gaps)
                   (recur (.plusDays ^LocalDate date 1) (dec remaining)
                          runs gaps))))
             (recur (.plusDays ^LocalDate date 1) (dec remaining)
                    runs gaps))))))))

(defn next-occurrences
  "Return up to N occurrences at or after FROM.

  With `:inclusive? false` (the default), occurrences strictly after FROM are
  returned.  Gaps are included in order but do not count toward N.  The scan
  stops as soon as N real occurrences are collected."
  ([recurrence zone-name from-ms n]
   (next-occurrences recurrence zone-name from-ms n {}))
  ([recurrence zone-name from-ms n {:keys [inclusive? max-days]
                                    :or {inclusive? false
                                         max-days default-max-days}}]
   (let [from-ms (to-epoch-ms from-ms)
         n (long n)
         zone (zone-id zone-name)
         from-date (.toLocalDate (.atZone (Instant/ofEpochMilli from-ms) zone))
         first-date (recurrence-start-date recurrence from-date)
         start-date (if (.isAfter ^LocalDate first-date from-date)
                      first-date
                      from-date)
         past-once? (and (= :once (:kind recurrence))
                         (.isBefore ^LocalDate first-date from-date))
         after? (if inclusive?
                  (fn [epoch] (>= epoch from-ms))
                  (fn [epoch] (> epoch from-ms)))]
     (if past-once?
       {:runs [] :gaps [] :exhausted? true}
       (loop [date start-date
              remaining (long max-days)
              runs []
              gaps []]
         (if (or (zero? remaining)
                 (>= (count runs) n)
                 (and (= :once (:kind recurrence))
                      (.isAfter ^LocalDate date
                                (LocalDate/parse (:local-date recurrence)))))
           {:runs runs
            :gaps gaps
            :exhausted? (and (zero? remaining) (< (count runs) n))}
           (if (day-matches? recurrence date)
             (let [occ (occurrence zone date (:time recurrence))]
               (case (:kind occ)
                 :gap
                 (recur (.plusDays ^LocalDate date 1) (dec remaining)
                        runs
                        (if (after? (:nominal-epoch-ms occ))
                          (conj gaps occ)
                          gaps))
                 :run
                 (recur (.plusDays ^LocalDate date 1) (dec remaining)
                        (if (after? (:epoch-ms occ))
                          (conj runs occ)
                          runs)
                        gaps)))
             (recur (.plusDays ^LocalDate date 1) (dec remaining)
                    runs gaps))))))))

(defn next-valid-occurrence
  "The first real occurrence strictly after AFTER-MS, or nil."
  [recurrence zone after-ms]
  (first (:runs (next-occurrences recurrence zone after-ms 1))))

(defn next-window
  "The first real occurrence after AFTER-MS plus any DST gaps before it.

  Returns `{:next-run epoch-or-nil :pending-gaps [...] :exhausted? bool}`.
  A one-shot rule that falls in a DST gap has no next run but still reports
  the gap, so the scheduler can record it as not run."
  [recurrence zone after-ms]
  (let [{:keys [runs gaps exhausted?]}
        (next-occurrences recurrence zone after-ms 1)]
    {:next-run (:epoch-ms (first runs))
     :pending-gaps gaps
     :exhausted? exhausted?}))

(defn preview-times
  "Up to N real occurrences strictly after NOW, plus the gaps before them.

  Returns `{:times [...] :gaps [...] :exhausted? bool}`."
  [recurrence zone now n]
  (let [{:keys [runs gaps exhausted?]}
        (next-occurrences recurrence zone now n)]
    {:times runs :gaps gaps :exhausted? exhausted?}))

(defn local-view
  "Show one instant in its schedule zone and in the viewing device zone.

  Historical records keep their own zone; this helper never rewrites them."
  [epoch-ms zone device-zone]
  (let [epoch-ms (to-epoch-ms epoch-ms)
        ^ZoneId zone (zone-id zone)
        ^ZoneId device-zone (zone-id device-zone)
        zdt (.atZone (Instant/ofEpochMilli epoch-ms) zone)
        device-zdt (.withZoneSameInstant zdt device-zone)]
    {:zone (str zone)
     :offset (str (.getOffset zdt))
     :local-date-time (str (.toLocalDateTime zdt))
     :zdt (str zdt)
     :device-zone (str device-zone)
     :device-offset (str (.getOffset device-zdt))
     :device-local-date-time (str (.toLocalDateTime device-zdt))
     :device-zdt (str device-zdt)}))

(defn describe-recurrence
  "A short English description of RECURRENCE.  The client owns presentation;
  this string is a stable fallback and a test aid."
  [recurrence]
  (let [time (format-time (:time recurrence))]
    (case (:kind recurrence)
      :once (str "once on " (:local-date recurrence) " at " time)
      :daily (str "every day at " time)
      :weekdays (str "every weekday at " time)
      :weekly (str "every "
                   (str/join ", "
                             (map #(nth day-names (dec %))
                                  (sort (:days recurrence))))
                   " at " time)
      (str "unknown recurrence at " time))))
