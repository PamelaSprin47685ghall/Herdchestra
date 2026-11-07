(ns hc.compose.owner.handlers-property-test
  "Property tests for Owner terminal handling: duplicate terminal facts are
  ignored, and attempt-id identity is preserved as part of the fact key."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]))

(defn- initial []
  (state/initial-state {:session-id "handlers-property" :now 1000}))

(defn- terminal
  [call-id attempt-id status]
  {:type :call/terminal
   :call-id call-id
   :attempt-id attempt-id
   :status status})

(defn- apply-terminal [s message]
  (:state (handlers/apply-message s message {:now 2000})))

(defn- terminal-key [t]
  [(:call-id t) (state/normalize-attempt-id (:attempt-id t)) (:status t)])

(defspec duplicate-terminal-fact-is-ignored 50
  (prop/for-all [call-id gen/string-alphanumeric
                 attempt-id gen/string-alphanumeric
                 status (gen/elements [:succeeded :failed :cancelled])]
    (let [message (terminal call-id attempt-id status)
          s1 (apply-terminal (initial) message)
          s2 (apply-terminal s1 message)
          matching (filter #(= (terminal-key message) (terminal-key %))
                           (:terminals s2))]
      (and (= 1 (count matching))
           (= (:terminals s1) (:terminals s2))))))

(defspec arbitrary-terminal-sequences-keep-one-fact-per-key 50
  (prop/for-all [messages (gen/vector
                           (gen/tuple (gen/elements ["c1" "c2"])
                                      (gen/elements ["a1" "a2" "a3"])
                                      (gen/elements [:succeeded :failed :cancelled]))
                           0 12)]
    (let [messages (mapv (fn [[call-id attempt-id status]]
                           (terminal call-id attempt-id status))
                         messages)
          expected (set (map terminal-key messages))
          final (reduce apply-terminal (initial) messages)
          actual (set (map terminal-key (:terminals final)))]
      (and (= expected actual)
           (= (count expected) (count (:terminals final)))))))
