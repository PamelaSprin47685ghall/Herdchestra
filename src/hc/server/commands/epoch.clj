(ns hc.server.commands.epoch
  "Server-issued command epoch windows.

  An epoch is a fencing token for one session Owner. Only the current epoch
  accepts commands. Earlier epochs are retired. Later epochs were never
  issued. The window keeps a high-water mark instead of a history set, so
  classification is O(1) and retired epochs need no unbounded archive.")

(def empty-window
  "A window with no active epoch and no retired epochs."
  {:command-epoch nil
   :retired-through 0})

(defn positive-integer?
  [value]
  (and (integer? value) (pos? value)))

(defn valid-epoch?
  [epoch]
  (positive-integer? epoch))

(defn active-epoch
  [window]
  (:command-epoch window))

(defn retired-through
  [window]
  (or (:retired-through window) 0))

(defn status
  "Classify EPOCH against WINDOW.

  Returns :active, :retired, :unknown, or :invalid. A retired epoch is
  never treated as a new command window, even when its command-id is
  unknown."
  [window epoch]
  (cond
    (not (valid-epoch? epoch)) :invalid
    (= epoch (:command-epoch window)) :active
    (<= epoch (retired-through window)) :retired
    :else :unknown))

(defn issue
  "Retire the current epoch, if any, and issue the next epoch.

  The next epoch is one greater than the highest epoch ever issued. The
  caller clears command receipts when it applies the new window."
  [window]
  (let [current (:command-epoch window)
        high-water (max (retired-through window) (or current 0))
        next-epoch (inc high-water)]
    {:ok? true
     :window {:command-epoch next-epoch
              :retired-through high-water}
     :epoch next-epoch
     :retired-epoch current
     :effects []}))

(defn retire
  "Retire the current epoch.

  The transition is idempotent when no epoch is active. The caller clears
  command receipts when it applies the returned window."
  [window]
  (let [current (:command-epoch window)]
    {:ok? true
     :window {:command-epoch nil
              :retired-through (max (retired-through window)
                                    (or current 0))}
     :retired-epoch current
     :effects []}))

(defn check
  "Return a vector of window invariant violations. An empty vector is valid."
  [window]
  (let [epoch (:command-epoch window)
        retired (:retired-through window)]
    (cond-> []
      (not (map? window))
      (conj {:invariant :window-not-a-map})

      (not (and (integer? retired) (not (neg? retired))))
      (conj {:invariant :retired-through-invalid
             :retired-through retired})

      (not (or (nil? epoch) (valid-epoch? epoch)))
      (conj {:invariant :command-epoch-invalid
             :command-epoch epoch})

      (and (valid-epoch? epoch)
           (integer? retired)
           (<= epoch retired))
      (conj {:invariant :command-epoch-not-after-retired-through
             :command-epoch epoch
             :retired-through retired}))))
