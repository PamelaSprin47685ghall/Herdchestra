(ns hc.server.recovery.plan
  "Turn classified obligations into one atomic update plan.

  Updates are grouped by the obligation's original session scope, because the
  storage key includes that scope.  Recovery may therefore commit a few small
  transactions instead of one large mixed-scope transaction; each group is
  still atomic, and no group can leave a half-updated obligation."

  (:require [hc.server.recovery.classify :as classify]))

(defn- changed? [obligation decision]
  (or (and (contains? decision :next-phase)
           (not= (:phase obligation) (:next-phase decision)))
      (and (contains? decision :open?)
           (not= (:open? obligation) (:open? decision)))))

(def ^:private reserved-keys
  [:storage/id :storage/kind :storage/session-id :storage/version
   :storage/partition :storage/updated-at])

(defn- update-doc [obligation decision at]
  (cond-> (merge (apply dissoc obligation reserved-keys)
                 {:recovery {:at at
                             :action (:action decision)
                             :reason (:reason decision)
                             :probe-result (:probe-result decision)
                             :probe-error (:probe-error decision)}})
    (contains? decision :failure)
    (assoc :failure (:failure decision))

    (contains? decision :next-phase)
    (assoc :phase (:next-phase decision))

    (contains? decision :open?)
    (assoc :open? (:open? decision))))

(defn plan
  "Classify every obligation and build the update groups.

  `ctx`: `:current-generation`, `:now-ms`, `:at`, and a `:decide` function
  `(fn [obligation] decision)` supplied by the facade after probing.

  Returns
    {:scanned n
     :findings [decision ..]
     :updates-by-scope {[partition session-id] [updated-doc ..]}
     :applied [finding ..]
     :pending-reports [obligation ..]
     :pending-retries [obligation ..]
     :blocked-retries [obligation ..]
     :outcome-unknown [obligation ..]
     :attention [finding ..]}"
  [obligations {:keys [now-ms at decide]}]
  (let [at       (or at (str (java.time.Instant/now)))
        decide   (or decide (fn [o] (classify/classify o {:current-generation nil})))
        findings (mapv (fn [o]
                         (assoc (decide o) :obligation o))
                       obligations)
        updates  (->> findings
                      (filter (fn [f] (changed? (:obligation f) f)))
                      (group-by (fn [f]
                                  (let [o (:obligation f)]
                                    [(:storage/partition o)
                                     (:storage/session-id o)])))
                      (into {}
                            (map (fn [[scope fs]]
                                   [scope
                                    (mapv (fn [f]
                                            (update-doc (:obligation f) f at))
                                          fs)]))))]
    {:scanned (count obligations)
     :findings findings
     :updates-by-scope updates
     :applied (vec (filter (fn [f] (changed? (:obligation f) f)) findings))
     :pending-reports (->> findings
                           (filter (fn [f] (= :redeliver (:action f))))
                           (mapv :obligation))
     :pending-retries (->> findings
                           (filter (fn [f] (= :await-retry (:action f))))
                           (mapv :obligation))
     :blocked-retries (->> findings
                           (filter (fn [f] (= :STORAGE_RETRY_UNSUPPORTED
                                              (:error f))))
                           (mapv :obligation))
     :outcome-unknown (->> findings
                           (filter (fn [f] (= :outcome-unknown (:next-phase f))))
                           (mapv :obligation))
     :attention (->> findings
                     (filter (fn [f] (= :inspect (:action f))))
                     vec)}))
