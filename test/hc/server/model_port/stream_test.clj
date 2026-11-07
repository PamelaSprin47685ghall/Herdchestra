(ns hc.server.model-port.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.model-port.call-identity :as identity]
            [hc.server.model-port.stream :as stream]))

(defn parser [raw]
  {:raw raw})

(defn fragment
  ([index complete? arguments-fragment]
   {:attempt-id "a1"
    :output-index index
    :provider-call-id (str "provider-" index)
    :tool-name "echo"
    :arguments-fragment arguments-fragment
    :complete? complete?
    :parse-arguments parser}))

(deftest incomplete-fragments-stay-private
  (let [result (stream/accept-fragment (stream/new-assembler)
                                       (identity/new-registry)
                                       (fragment 0 false "{\"x\":"))]
    (is (true? (:ok result)))
    (is (nil? (:event result)))
    (is (empty? (:published (:assembler result))))
    (is (= 0 (:next-id (:registry result))))))

(deftest complete-fragments-publish-once
  (let [first (stream/accept-fragment (stream/new-assembler)
                                      (identity/new-registry)
                                      (fragment 0 false "{\"x\":"))
        second (stream/accept-fragment (:assembler first)
                                       (:registry first)
                                       (fragment 0 true "1}"))
        duplicate (stream/accept-fragment (:assembler second)
                                          (:registry second)
                                          (fragment 0 true "1}"))]
    (is (nil? (:event first)))
    (is (= :tool-call (:event (:event second))))
    (is (= {:raw "{\"x\":1}"} (:arguments (:event second))))
    (is (= "a1/tool-0" (:call-id (:event second))))
    (is (nil? (:event duplicate)))
    (is (= :merged (:status duplicate)))
    (is (= 1 (count (get-in duplicate [:registry :calls]))))))

(deftest final-response-does-not-duplicate-streamed-call
  (let [streamed (stream/accept-fragment
                  (stream/new-assembler)
                  (identity/new-registry)
                  {:attempt-id "a1"
                   :output-index 0
                   :provider-call-id "provider-0"
                   :tool-name "echo"
                   :arguments {:x 1}
                   :complete? true})
        final (stream/accept-final (:assembler streamed)
                                   (:registry streamed)
                                   "a1"
                                   [{:output-index 0
                                     :provider-call-id "provider-0"
                                     :tool-name "echo"
                                     :arguments {:x 1}}
                                    {:output-index 1
                                     :provider-call-id "provider-1"
                                     :tool-name "echo"
                                     :arguments {:x 2}}])]
    (is (= 1 (count (:events final))))
    (is (= "a1/tool-1" (:call-id (first (:events final)))))
    (is (= 2 (count (get-in final [:registry :calls]))))))

(deftest changed-complete-fragment-is-rejected
  (let [streamed (stream/accept-fragment
                  (stream/new-assembler)
                  (identity/new-registry)
                  {:attempt-id "a1"
                   :output-index 0
                   :tool-name "echo"
                   :arguments {:x 1}
                   :complete? true})
        conflict (stream/accept-fragment
                  (:assembler streamed)
                  (:registry streamed)
                  {:attempt-id "a1"
                   :output-index 0
                   :tool-name "echo"
                   :arguments {:x 2}
                   :complete? true})]
    (is (false? (:ok conflict)))
    (is (= :late-fragment-conflict (get-in conflict [:error :kind])))
    (is (= (:registry streamed) (:registry conflict)))))

(deftest a-fragment-cannot-change-tool-name-or-provider-id
  (let [partial (stream/accept-fragment
                 (stream/new-assembler)
                 (identity/new-registry)
                 (fragment 0 false "{\"x\":"))
        name-conflict (stream/accept-fragment
                       (:assembler partial)
                       (:registry partial)
                       (assoc (fragment 0 false "1}")
                              :tool-name "other"))
        provider-conflict (stream/accept-fragment
                           (:assembler partial)
                           (:registry partial)
                           (assoc (fragment 0 false "1}")
                                  :provider-call-id "other-provider"))]
    (is (false? (:ok name-conflict)))
    (is (false? (:ok provider-conflict)))
    (is (= :late-fragment-conflict (get-in name-conflict [:error :kind])))
    (is (= :late-fragment-conflict (get-in provider-conflict [:error :kind])))))

(deftest missing-parser-keeps-arguments-private
  (let [result (stream/accept-fragment
                (stream/new-assembler)
                (identity/new-registry)
                {:attempt-id "a1"
                 :output-index 0
                 :tool-name "echo"
                 :arguments-fragment "{\"x\":1}"
                 :complete? true})]
    (is (false? (:ok result)))
    (is (= :arguments-incomplete (get-in result [:error :kind])))
    (is (= :parser-missing (get-in result [:error :reason])))
    (is (= 0 (:next-id (:registry result))))))

(deftest final-response-conflict-is-atomic
  (let [registry (identity/new-registry)
        result (stream/accept-final (stream/new-assembler)
                                    registry
                                    "a1"
                                    [{:output-index 0
                                      :tool-name "echo"
                                      :arguments {:x 1}}
                                     {:output-index 0
                                      :tool-name "echo"
                                      :arguments {:x 2}}])]
    (is (false? (:ok result)))
    (is (= registry (:registry result)))))

(deftest a-late-stream-callback-merges-after-the-final-response
  (let [final (stream/accept-final
               (stream/new-assembler)
               (identity/new-registry)
               "a1"
               [{:output-index 0
                 :provider-call-id "provider-0"
                 :tool-name "echo"
                 :arguments {:x 1}}])
        late (stream/accept-fragment
              (:assembler final)
              (:registry final)
              {:attempt-id "a1"
               :output-index 0
               :provider-call-id "provider-0"
               :tool-name "echo"
               :arguments {:x 1}
               :complete? true})]
    (is (true? (:ok late)))
    (is (nil? (:event late)))
    (is (= :merged (:status late)))
    (is (= 1 (count (get-in late [:registry :calls]))))))

(deftest forget-attempt-releases-dedup-state
  (let [assembled (stream/accept-fragment
                   (stream/new-assembler)
                   (identity/new-registry)
                   {:attempt-id "a1"
                    :output-index 0
                    :tool-name "echo"
                    :arguments {:x 1}
                    :complete? true})
        forgotten (stream/forget-attempt (:assembler assembled) "a1")]
    (is (empty? (:published forgotten)))
    (is (empty? (:partials forgotten)))))

(defspec fragment-concatenation-publishes-exactly-one-event 100
  (prop/for-all [chunks (gen/vector gen/string-alphanumeric 1 8)]
    (let [indexed (map-indexed vector chunks)
          last-index (dec (count chunks))
          results (reductions
                   (fn [state [index chunk]]
                     (stream/accept-fragment
                      (:assembler state)
                      (:registry state)
                      {:attempt-id "a1"
                       :output-index 0
                       :tool-name "echo"
                       :arguments-fragment chunk
                       :complete? (= index last-index)
                       :parse-arguments parser}))
                   {:assembler (stream/new-assembler)
                    :registry (identity/new-registry)}
                   indexed)
          events (keep :event results)]
      (and (= 1 (count events))
           (= {:raw (apply str chunks)}
              (:arguments (first events)))
           (= 1 (:next-id (:registry (last results))))))))
