(ns hc.compose.workspace.error-property-test
  "Property tests for the Owner-facing error projection.

  `from-throwable` must classify any throwable without throwing again.
  Cancellation and retryability are independent predicates that may both be
  false, but must never both be true.  `error-data` produces plain EDN that
  survives `pr-str`/`read-string`."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.workspace.error :as error]))

(def ^:private gen-message
  (gen/fmap (fn [text] (str "message-" text))
            gen/string-alphanumeric))

(def ^:private gen-code
  (gen/fmap (fn [[present? code]]
              (when present? code))
            (gen/tuple gen/boolean gen/keyword)))

(def ^:private gen-throwable
  (gen/one-of
   [(gen/fmap (fn [[message code]]
                (ex-info message {:code code}))
              (gen/tuple gen-message gen-code))
    (gen/fmap (fn [message]
                (ex-info message {}))
              gen-message)
    (gen/fmap (fn [[message code cause-code]]
                (ex-info message
                         {:code code}
                         (ex-info "nested cause" {:code cause-code})))
              (gen/tuple gen-message gen-code gen-code))
    (gen/fmap (fn [message]
                (RuntimeException. ^String message))
              gen-message)
    (gen/fmap (fn [message]
                (Error. ^String message))
              gen-message)]))

(defspec from-throwable-always-returns-a-classified-map 80
  (prop/for-all [throwable gen-throwable]
    (let [result (try
                   (error/from-throwable throwable)
                   (catch Throwable _ ::threw))]
      (and (not= ::threw result)
           (map? result)
           (contains? result :code)
           (some? (:code result))
           (contains? result :message)
           (some? (:message result))
           (false? (:retryable? result))
           (not (error/retryable? result))))))

(def ^:private gen-status
  (gen/elements [:STALE_CURSOR
                 :WAITING_FOR_WRITE_GATE
                 :WRITE_GATE_ACQUIRE_FAILED
                 :CANCELLED
                 :STOP_REQUESTED
                 :HARD_FAILURE
                 :APPLIED
                 :OTHER]))

(defspec cancellation-and-retryability-are-never-both-true 100
  (prop/for-all [status gen-status
                 cancelled-flag gen/boolean
                 code gen-code]
    (let [result (cond-> {:status status}
                   (some? code) (assoc :code code)
                   cancelled-flag (assoc :cancelled? true))
          retry? (error/retryable? result)
          cancelled? (error/cancelled? result)
          stop (try
                 (error/stop-signal code)
                 nil
                 (catch clojure.lang.ExceptionInfo exception
                   exception))
          projected (when stop (error/from-throwable stop))]
      (and (not (and retry? cancelled?))
           (error/stop-signal? stop)
           (not (error/stop-signal? (ex-info "other" {:code :CANCELLED})))
           (not (error/stop-signal? (ex-info "other" {:code :OTHER})))
           (not (error/stop-signal? nil))
           (not (error/stop-signal? {:code :STOP_REQUESTED}))
           (some? projected)
           (error/cancelled? projected)
           (not (error/retryable? projected))))))

(def ^:private gen-edn
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 3)
                  (gen/map gen/keyword inner {:max-elements 3})]))
   (gen/one-of [gen/string-alphanumeric
                gen/keyword
                gen/small-integer
                gen/boolean])))

(defspec error-data-round-trips-through-pr-str-and-read-string 100
  (prop/for-all [status (gen/elements [:FILE_CONFLICT
                                       :STRUCTURE_CONFLICT
                                       :HARD_FAILURE
                                       :CALL_REPLAY_MISMATCH
                                       :OTHER])
                 code gen-code
                 message (gen/fmap (fn [[present? text]]
                                     (when present? text))
                                   (gen/tuple gen/boolean
                                              gen/string-alphanumeric))
                 details gen-edn]
    (let [result (cond-> {:status status :details details}
                   (some? code) (assoc :code code)
                   (some? message) (assoc :message message))
          value (error/error-data result)
          reread (read-string (pr-str value))]
      (and (map? value)
           (contains? value :code)
           (contains? value :message)
           (boolean? (:retryable? value))
           (= value reread)))))
