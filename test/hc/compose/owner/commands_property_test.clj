(ns hc.compose.owner.commands-property-test
  "Property tests for the Owner command gate: replay idempotency, payload
  conflict, and epoch retirement."
  (:require [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner.commands :as commands]))

(defn- registry []
  (commands/local-registry))

(def non-blank-id
  (gen/such-that #(not (str/blank? %)) gen/string-alphanumeric 100))

(defn- request
  [id payload]
  {:command-id id :command-epoch 1 :payload payload})

(defspec same-id-same-payload-is-idempotent 50
  (prop/for-all [id non-blank-id
                 payload gen/any]
    (let [r (registry)
          state (commands/new-state r)
          first-result (commands/accept r state (request id payload))
          replay (commands/accept r (:state first-result)
                                 (request id payload))]
      (and (:ok? first-result)
           (:ok? replay)
           (true? (:replayed? replay))
           (= (:state first-result) (:state replay))
           (= (:receipt first-result) (:receipt replay))))))

(defspec same-id-different-payload-is-rejected 50
  (prop/for-all [id non-blank-id
                 [a b] (gen/such-that (fn [[a b]] (not= a b))
                                       (gen/tuple gen/any gen/any)
                                       100)]
    (let [r (registry)
          state (commands/new-state r)
          first-result (commands/accept r state (request id a))
          conflict (commands/accept r (:state first-result)
                                    (request id b))]
      (and (:ok? first-result)
           (false? (:ok? conflict))
           (= :COMMAND_ID_PAYLOAD_CONFLICT
              (get-in conflict [:error :code]))))))

(defspec retired-epoch-is-rejected-and-next-epoch-accepted 50
  (prop/for-all [id non-blank-id
                 payload gen/any]
    (let [r (registry)
          state (commands/new-state r)
          accepted (commands/accept r state (request id payload))
          issued (commands/issue-epoch r (:state accepted))
          retired (commands/accept r (:state issued) (request id payload))
          next-request {:command-id id :command-epoch (:epoch issued)
                        :payload payload}
          next (commands/accept r (:state issued) next-request)]
      (and (:ok? accepted)
           (:ok? issued)
           (false? (:ok? retired))
           (= :COMMAND_EPOCH_RETIRED (get-in retired [:error :code]))
           (:ok? next)))))
