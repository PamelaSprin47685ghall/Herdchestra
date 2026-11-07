(ns hc.server.diagnostics.property-test
  (:require [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.diagnostics.redact :as redact]
            [hc.server.diagnostics.usage :as usage]))

(def gen-token
  (gen/one-of [(gen/choose 0 100000) (gen/return :unknown)]))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 3)
                  (gen/map gen/keyword inner {:max-elements 3})]))
   (gen/one-of [gen/string-alphanumeric
                gen/small-integer
                (gen/return nil)])))

(defspec subset-total-never-adds-cache-twice 100
  (prop/for-all [input (gen/choose 0 100000)
                 output gen-token
                 cache (gen/choose 0 100000)]
    (or (> cache input)
        (let [request (usage/request
                       {:request-id "r"
                        :usage {:coverage :final
                                :input-tokens input
                                :output-tokens output
                                :cache-read-tokens cache
                                :cache-write-tokens 0}})
              summary (usage/token-summary request)]
          (if (= :unknown output)
            (= :unknown (:total-tokens summary))
            (= (+ input output) (:total-tokens summary)))))))

(defspec unknown-field-never-becomes-zero 100
  (prop/for-all [input gen-token
                 output gen-token]
    (let [request (usage/request
                   {:request-id "r"
                    :usage {:coverage :final
                            :input-tokens input
                            :output-tokens output
                            :cache-read-tokens 0
                            :cache-write-tokens 0}})
          summary (usage/token-summary request)]
      (and (if (= :unknown input)
             (= :unknown (:input-tokens summary))
             (= input (:input-tokens summary)))
           (if (= :unknown output)
             (= :unknown (:output-tokens summary))
             (= output (:output-tokens summary)))))))

(defspec redaction-is-idempotent-on-random-data 50
  (prop/for-all [value gen-value]
    (let [once (redact/redact value {:mask-hosts? true :mask-paths? true})
          twice (redact/redact (:value once)
                               {:mask-hosts? true :mask-paths? true})]
      (and (= (:value once) (:value twice))
           (empty? (:findings twice))))))

(defspec redaction-never-emits-a-known-secret 50
  (prop/for-all [suffix (gen/choose 0 1000000)]
    (let [secret (str "sk-" suffix "-abcdefghijklmnop")
          result (redact/redact {:api-key secret
                                 :note (str "key " secret)})
          printed (pr-str result)]
      (and (not (str/includes? printed secret))
           (= 2 (count (:findings result)))
           (= "<redacted:secret:1>"
              (get-in result [:value :api-key]))
           (str/includes? (get-in result [:value :note])
                          "<redacted:secret:1>")))))
