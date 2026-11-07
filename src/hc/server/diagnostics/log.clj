(ns hc.server.diagnostics.log
  "Detailed request-log policy and retention.

  Detailed logging is off by default.  Enabling it without a mode records
  exactly the next request, then turns itself off.  A timed mode is capped at
  seven days.  A temporary session is never persisted, even while detailed
  logging is on.

  Entries are redacted before they enter the policy.  Clearing the log removes
  only detailed entries; it does not touch chats, assistants, or usage
  summaries, which the caller owns elsewhere.  Pausing stops new entries but
  keeps existing ones until retention or an explicit clear removes them.

  This namespace performs no I/O.  The caller persists the returned policy
  and writes the exported preview only after the user chooses a destination."
  (:require [hc.server.diagnostics.redact :as redact])
  (:import (java.nio.charset StandardCharsets)))

(def max-retention-days 7)
(def default-retention-days 7)
(def max-entries
  "Upper bound on retained detailed entries.

  Retention is time-based.  Without a count bound, a timed capture could grow
  the Owner state and every export until the window closes."
  2000)
(def day-ms (* 24 60 60 1000))
(def modes #{:next-request :timed})

(defn- bound-entries
  [entries]
  (vec (take-last max-entries entries)))

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-log-policy
                          :reason reason}
                         data))))

(defn new-policy
  "A policy with detailed logging off and seven-day retention."
  ([]
   (new-policy {}))
  ([{:keys [retention-days]}]
   (let [retention (or retention-days default-retention-days)]
     (when-not (and (integer? retention)
                    (<= 1 retention max-retention-days))
       (invalid! :retention-invalid
                 "retention must be an integer from 1 to 7 days"
                 {:retention-days retention}))
     {:enabled? false
      :paused? false
      :mode :next-request
      :expires-at nil
      :retention-days retention
      :entries []
      :last-cleared-at nil})))

(defn- now-ms
  [value]
  (or value (System/currentTimeMillis)))

(defn enable
  "Enable detailed capture.

  The default `:next-request` mode captures one request and disables itself.
  `:timed` requires `:days` from 1 to 7.  Enabling again replaces the previous
  capture window; existing entries stay."
  [policy opts]
  (let [policy (or policy (new-policy))
        opts (or opts {})
        mode (or (:mode opts) :next-request)
        now (now-ms (:now opts))
        days (:days opts)]
    (when-not (contains? modes mode)
      (invalid! :mode-invalid
                "log mode must be :next-request or :timed"
                {:mode mode}))
    (cond
      (= :next-request mode)
      (assoc policy
             :enabled? true
             :paused? false
             :mode :next-request
             :expires-at nil)

      :else
      (do
        (when-not (and (integer? days) (<= 1 days max-retention-days))
          (invalid! :days-invalid
                    "timed detailed logging lasts from 1 to 7 days"
                    {:days days}))
        (assoc policy
               :enabled? true
               :paused? false
               :mode :timed
               :expires-at (+ now (* days day-ms)))))))

(defn disable
  "Turn detailed capture off.  Existing entries remain until retention."
  [policy]
  (assoc (or policy (new-policy))
         :enabled? false
         :paused? false
         :expires-at nil))

(defn pause
  "Stop new detailed entries without deleting existing ones."
  [policy]
  (assoc (or policy (new-policy)) :paused? true))

(defn resume
  "Resume a paused policy if its timed window has not expired."
  [policy now]
  (let [policy (or policy (new-policy))
        now (now-ms now)
        expired? (and (:enabled? policy)
                      (:expires-at policy)
                      (<= (:expires-at policy) now))]
    (if expired?
      (assoc policy :enabled? false :paused? false :expires-at nil)
      (assoc policy :paused? false))))

(defn expire
  "Drop entries older than the retention window and close an expired window."
  [policy now]
  (let [policy (or policy (new-policy))
        now (now-ms now)
        cutoff (- now (* (or (:retention-days policy)
                             default-retention-days)
                        day-ms))
        entries (->> (:entries policy)
                     (filterv (fn [entry]
                                (let [at (:recorded-at entry)]
                                  (and (integer? at) (>= at cutoff)))))
                     bound-entries)
        expired? (and (:enabled? policy)
                      (:expires-at policy)
                      (<= (:expires-at policy) now))]
    (cond-> (assoc policy :entries entries)
      expired? (assoc :enabled? false :paused? false :expires-at nil))))

(defn record
  "Redact and record one detailed entry when the policy permits it.

  `:temporary? true` is never recorded.  A disabled or paused policy records
  nothing.  A `:next-request` policy disables itself after one accepted entry."
  [policy entry opts]
  (let [opts (or opts {})
        now (now-ms (:now opts))
        policy (expire (or policy (new-policy)) now)]
    (cond
      (:temporary? entry)
      {:recorded? false
       :reason :temporary-session
       :policy policy}

      (not (:enabled? policy))
      {:recorded? false
       :reason :disabled
       :policy policy}

      (:paused? policy)
      {:recorded? false
       :reason :paused
       :policy policy}

      :else
      (let [redaction (redact/redact entry {:mask-hosts? (:mask-hosts? opts)
                                            :mask-paths? (:mask-paths? opts)
                                            :rename (:rename opts)})
            recorded (assoc (:value redaction)
                            :recorded-at now
                            :retention-days (:retention-days policy))
            policy' (-> policy
                        (update :entries conj recorded)
                        (update :entries bound-entries)
                        (cond->
                          (= :next-request (:mode policy))
                          (assoc :enabled? false
                                 :expires-at nil
                                 :paused? false)))]
        {:recorded? true
         :entry recorded
         :redaction (:findings redaction)
         :policy policy'}))))

(defn clear
  "Remove detailed entries only.  The capture policy is otherwise unchanged."
  [policy now]
  (assoc (or policy (new-policy))
         :entries []
         :last-cleared-at (now-ms now)))

(defn entries
  "The recorded detailed entries."
  [policy]
  (vec (:entries policy)))

(defn status
  "A display status for the diagnostics page.  Expired entries are omitted."
  [policy now]
  (let [policy (expire (or policy (new-policy)) (now-ms now))
        entries (:entries policy)
        entry-bytes (reduce (fn [total entry]
                              (+ total
                                 (alength (.getBytes (pr-str entry)
                                                     StandardCharsets/UTF_8))))
                            0
                            entries)]
    {:enabled? (boolean (:enabled? policy))
     :paused? (boolean (:paused? policy))
     :capturing? (boolean (and (:enabled? policy) (not (:paused? policy))))
     :mode (:mode policy)
     :retention-days (:retention-days policy)
     :entry-count (count entries)
     :bytes entry-bytes
     :oldest (:recorded-at (first entries))
     :newest (:recorded-at (last entries))
     :expires-at (:expires-at policy)
     :last-cleared-at (:last-cleared-at policy)}))
