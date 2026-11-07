(ns hc.server.backup.recovery
  "Local recovery-point retention: 7 recent + 4 weekly + 3 monthly.

  A recovery point is a device-local rollback aid, not an off-device backup.
  The retention planner never deletes the point a running restore depends on,
  pinned points, failed points, or points owned by another device/kind."
  (:require [hc.server.backup.model :as model])
  (:import (java.time Instant YearMonth ZoneOffset ZonedDateTime)
           (java.time.temporal IsoFields)))

(def default-policy
  {:keep 7
   :weeks 4
   :months 3})

(defn- instant-of
  [point]
  (or (model/parse-instant (:created-at point))
      (model/parse-instant (:imported-at point))))

(defn- week-key
  [^ZonedDateTime z]
  [(.get z IsoFields/WEEK_BASED_YEAR)
   (.get z IsoFields/WEEK_OF_WEEK_BASED_YEAR)])

(defn- month-key
  [^ZonedDateTime z]
  (YearMonth/from z))

(defn- zoned
  [^Instant instant]
  (ZonedDateTime/ofInstant instant ZoneOffset/UTC))

(defn- protected?
  [point current-id]
  (or (:pinned? point)
      (= (:id point) current-id)
      (nil? (instant-of point))
      (not= :local (:kind point :local))
      (not= :success (:status point :success))))

(defn plan-retention
  "Return {:keep-ids [...] :delete-ids [...] :keep-reasons {...}}.

  `points` may be recovery-point metadata or full point maps.  The current
  restore's point is protected by `:current-id`.  Timestamps that cannot be
  parsed are kept: deleting on unknown time would be a guess."
  ([points]
   (plan-retention points {}))
  ([points {:keys [now recent-limit keep-limit weeks months current-id]
            :or {weeks 4 months 3}}]
   (let [recent-limit (long (or recent-limit keep-limit 7))
         now (or (cond
                   (instance? Instant now) now
                   (string? now) (model/parse-instant now)
                   :else nil)
                 (Instant/now))
         points (vec points)
         with-time (mapv (fn [p]
                           (assoc p ::instant (instant-of p)))
                         points)
         sortable (sort-by (fn [p]
                             (if-let [i (::instant p)]
                               (.toEpochMilli ^Instant i)
                               Long/MIN_VALUE))
                           >
                           with-time)
         ;; Only a successful local point can occupy a retention slot.  A
         ;; failed or foreign point is kept by `protected?` anyway; letting it
         ;; consume a recent slot would evict a real successful point.
         eligible (filter (fn [p]
                            (and (some? (::instant p))
                                 (= :local (:kind p :local))
                                 (= :success (:status p :success))))
                          sortable)
         recent (take recent-limit eligible)
         ^ZonedDateTime now-z (zoned now)
         week-keys (set (for [n (range weeks)]
                          (week-key (.minusWeeks now-z (long n)))))
         month-keys (set (for [n (range months)]
                           (month-key (.minusMonths now-z (long n)))))
         weekly (->> eligible
                     (filter (fn [p]
                               (when-let [i (::instant p)]
                                 (contains? week-keys (week-key (zoned i))))))
                     (reduce (fn [seen p]
                               (let [k (week-key (zoned (::instant p)))]
                                 (if (contains? seen k)
                                   seen
                                   (assoc seen k p))))
                             {})
                     vals)
         monthly (->> eligible
                      (filter (fn [p]
                                (when-let [i (::instant p)]
                                  (contains? month-keys (month-key (zoned i))))))
                      (reduce (fn [seen p]
                                (let [k (month-key (zoned (::instant p)))]
                                  (if (contains? seen k)
                                    seen
                                    (assoc seen k p))))
                              {})
                      vals)
         reasons (reduce (fn [m [reason ps]]
                           (reduce (fn [m p]
                                     (update m (:id p) (fnil conj #{}) reason))
                                   m
                                   ps))
                         {}
                         [[:recent recent]
                          [:weekly weekly]
                          [:monthly monthly]])
         protected (filter #(protected? % current-id) points)
         keep-ids (->> (concat recent weekly monthly protected)
                       (keep :id)
                       distinct
                       vec)
         delete-ids (->> points
                         (remove #(contains? (set keep-ids) (:id %)))
                         (remove #(protected? % current-id))
                         (keep :id)
                         vec)]
     {:keep-ids keep-ids
      :delete-ids delete-ids
      :keep-reasons reasons
      :policy {:keep recent-limit :weeks weeks :months months}})))
