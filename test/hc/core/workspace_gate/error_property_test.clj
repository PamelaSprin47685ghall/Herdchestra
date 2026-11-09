(ns hc.core.workspace-gate.error-property-test
  "Property tests for the file-contract result classification.

  A result belongs to exactly one of three outcome classes: an ordinary
  rejection (`:not-written`), an applied write (`:APPLIED`), or a hard failure
  (`:HARD_FAILURE`).  `expected-failure?` classifies control exceptions, not
  result maps; both domains are exercised separately."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.workspace-gate.error :as error]))

(def ^:private ordinary-statuses
  "The module's own closed rejection set."
  (set @#'error/ordinary-statuses))

(def ^:private classified-statuses
  (conj ordinary-statuses :APPLIED :HARD_FAILURE))

(def ^:private neutral-result-statuses
  "Real non-outcome statuses carried by gate control receipts."
  [:OBSERVED :ACTION_IN_PROGRESS :STAGED :STOP_ALREADY_FINISHED
   :STOP_AFTER_APPLYING :STOP_REGISTERED :STOP_CLEARED :OK :pending])

(def ^:private result-codes
  [:changed-during-read :stamp-mismatch :no-parent :claim-changed
   :limit-exceeded :invalid-argument :unknown-request])

(defspec classified-result-statuses-have-one-owner 100
  (prop/for-all [status (gen/elements classified-statuses)
                 code (gen/elements result-codes)
                 message (gen/not-empty gen/string-alphanumeric)]
    (let [result {:status status :code code :message message}
          rejected? (error/rejection? result)
          applied? (error/applied? result)
          hard? (error/hard-failure? result)]
      (and
       ;; Exactly one outcome class holds for every classified status.
       (= 1 (count (filter true? [rejected? applied? hard?])))
       (= (contains? ordinary-statuses status) rejected?)
       (= (= :APPLIED status) applied?)
       (= (= :HARD_FAILURE status) hard?)
       ;; A result map is never a control exception.
       (not (error/expected-failure? result))))))

(defspec ordinary-failure-results-preserve-their-fields 100
  (prop/for-all [status (gen/elements (vec ordinary-statuses))
                 code (gen/elements result-codes)
                 message (gen/not-empty gen/string-alphanumeric)]
    (let [built (error/failure status code message)
          thrown (try
                   (error/fail! status code message)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (and
       ;; The returned rejection carries the exact input fields.
       (= status (:status built))
       (= code (:code built))
       (= message (:message built))
       (= :not-written (:outcome built))
       (false? (:file-changed? built))
       (error/rejection? built)
       (not (error/applied? built))
       (not (error/hard-failure? built))
       ;; `fail!` throws the same complete shape instead of a bare map.
       (some? thrown)
       (error/expected-failure? thrown)
       (= status (:status data))
       (= code (:code data))
       (= message (:message data))
       (= :not-written (:outcome data))
       (false? (:file-changed? data))
       (error/rejection? data)
       (not (error/applied? data))
       (not (error/hard-failure? data))))))

(defspec neutral-result-statuses-have-no-outcome-class 100
  (prop/for-all [status (gen/elements neutral-result-statuses)
                 code (gen/elements result-codes)]
    (let [result {:status status :code code}]
      (and (not (error/rejection? result))
           (not (error/applied? result))
           (not (error/hard-failure? result))
           (not (error/expected-failure? result))))))

(defspec expected-failure-recognizes-only-control-exceptions 100
  (prop/for-all [status (gen/elements classified-statuses)
                 code (gen/elements result-codes)
                 message (gen/not-empty gen/string-alphanumeric)
                 outcome (gen/elements [:not-written :applied :unknown])
                 has-status? gen/boolean
                 has-outcome? gen/boolean]
    (let [data (cond-> {:code code}
                 has-status? (assoc :status status)
                 has-outcome? (assoc :outcome outcome))
          control (ex-info message data)
          expected? (and has-status? has-outcome?)]
      (and
       (= expected? (error/expected-failure? control))
       ;; A control exception is never a result map, so no result predicate
       ;; may claim it.
       (not (error/rejection? control))
       (not (error/applied? control))
       (not (error/hard-failure? control))
       ;; Plain data and bare ex-info values are not control exceptions.
       (not (error/expected-failure? data))
       (not (error/expected-failure? nil))
       (not (error/expected-failure? (ex-info message {:code code})))))))
