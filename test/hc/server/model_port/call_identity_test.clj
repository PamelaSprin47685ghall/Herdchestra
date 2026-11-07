(ns hc.server.model-port.call-identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.model-port.call-identity :as identity]))

(defn call
  ([index] (call index nil))
  ([index provider-id]
   {:attempt-id "a1"
    :output-index index
    :provider-call-id provider-id
    :tool-name "read"
    :arguments {:path "README.md"}
    :complete? true}))

(defn register!
  [registry value]
  (identity/register-call registry value))

(deftest allocates-project-call-ids
  (let [created (register! (identity/new-registry) (call 0 "provider-0"))]
    (is (true? (:ok created)))
    (is (= :created (:status created)))
    (is (= "a1/tool-0" (:call-id created)))
    (is (not= "call-1" (:call-id created))
        "a project tool id must not share the Owner model-call namespace")
    (is (= {:path "README.md"}
           (get-in created [:registry :calls "a1/tool-0" :arguments])))))

(deftest repeated-identity-merges-once
  (let [first (register! (identity/new-registry) (call 0 "provider-0"))
        second (register! (:registry first) (call 0 "provider-0"))]
    (is (= :merged (:status second)))
    (is (= (:call-id first) (:call-id second)))
    (is (= 1 (count (get-in second [:registry :calls]))))
    (is (= 1 (:next-id (:registry second))))
    (is (= ["a1/tool-0"]
           (identity/call-ids-for-attempt (:registry second) "a1")))))

(deftest forget-attempt-removes-only-that-attempt
  (let [first (register! (identity/new-registry) (call 0 "provider-0"))
        second (register! (:registry first)
                          (assoc (call 1 "provider-1") :attempt-id "a2"))
        forgotten (identity/forget-attempt (:registry second) "a1")]
    (is (= 1 (count (:calls forgotten))))
    (is (= ["a2/tool-1"] (identity/call-ids-for-attempt forgotten "a2")))
    (is (empty? (identity/call-ids-for-attempt forgotten "a1")))
    (is (empty? (get-in forgotten [:by-provider ["a1" "provider-0"]])))))

(deftest same-arguments-at-another-index-stay-distinct
  (let [first (register! (identity/new-registry) (call 0 "provider-0"))
        second (register! (:registry first) (call 1 "provider-1"))]
    (is (= :created (:status second)))
    (is (not= (:call-id first) (:call-id second)))
    (is (= ["a1/tool-0" "a1/tool-1"]
           (identity/call-ids-for-attempt (:registry second) "a1")))
    (is (= 2 (count (get-in second [:registry :calls]))))))

(deftest project-ids-are-namespaced-by-attempt
  (let [first (register! (identity/new-registry) (call 0 "provider-0"))
        second (register! (:registry first)
                          (assoc (call 0 "provider-0") :attempt-id "a2"))]
    (is (= "a1/tool-0" (:call-id first)))
    (is (= "a2/tool-0" (:call-id second)))
    (is (not= (:call-id first) (:call-id second))
        "the same output index in another attempt is another call")))

(deftest alias-ids-cannot-collide-with-index-ids
  (let [indexed (register! (identity/new-registry) (call 0 "provider-0"))
        alias (register! (:registry indexed)
                         {:attempt-id "a1"
                          :provider-call-id "provider-alias"
                          :tool-name "read"
                          :arguments {:path "README.md"}
                          :complete? true})
        indexed-two (register! (:registry alias) (call 2 "provider-2"))]
    (is (= "a1/tool-0" (:call-id indexed)))
    (is (= "a1/tool-alias-2" (:call-id alias)))
    (is (= "a1/tool-2" (:call-id indexed-two)))
    (is (= 3 (count (set [(:call-id indexed) (:call-id alias)
                          (:call-id indexed-two)]))))))

(deftest changed-payload-is-a-conflict
  (let [first (register! (identity/new-registry) (call 0 "provider-0"))
        changed (assoc (call 0 "provider-0") :arguments {:path "other.md"})
        conflict (register! (:registry first) changed)]
    (is (false? (:ok conflict)))
    (is (= :call-identity-conflict (get-in conflict [:error :kind])))
    (is (= (:registry first) (:registry conflict)))
    (is (= 1 (count (get-in conflict [:registry :calls]))))))

(deftest final-response-merges-with-stream
  (let [streamed (register! (identity/new-registry) (call 0 "provider-0"))
        response (identity/merge-response
                  (:registry streamed)
                  "a1"
                  [(call 0 "provider-0")])
        new-call (identity/merge-response
                  (:registry streamed)
                  "a1"
                  [(call 0 "provider-0")
                   (assoc (call 1 "provider-1")
                          :tool-name "write"
                          :arguments {:path "out.md"})])]
    (is (= [] (:new-call-ids response)))
    (is (= :merged (get-in response [:calls 0 :status])))
    (is (= ["a1/tool-1"] (:new-call-ids new-call)))
    (is (= 2 (count (get-in new-call [:registry :calls]))))))

(deftest response-position-supplies-missing-index
  (let [result (identity/merge-response
                (identity/new-registry)
                "a1"
                [{:provider-call-id "p"
                  :tool-name "read"
                  :arguments {:path "a"}}
                 {:provider-call-id "q"
                  :tool-name "read"
                  :arguments {:path "b"}}])]
    (is (true? (:ok result)))
    (is (= [0 1] (map :output-index (:calls result))))
    (is (= ["a1/tool-0" "a1/tool-1"] (:new-call-ids result)))))

(deftest ambiguous-provider-id-needs-an-output-index
  (let [first (register! (identity/new-registry) (call 0 "shared"))
        second (register! (:registry first) (call 1 "shared"))
        by-index (register! (:registry second) (call 1 "shared"))
        by-alias (register! (:registry second)
                            {:attempt-id "a1"
                             :provider-call-id "shared"
                             :tool-name "read"
                             :arguments {:path "README.md"}
                             :complete? true})]
    (is (= :created (:status second)))
    (is (= 2 (count (get-in second [:registry :by-provider ["a1" "shared"]]))))
    (is (= :merged (:status by-index)))
    (is (false? (:ok by-alias)))
    (is (= :provider-id-ambiguous (get-in by-alias [:error :kind])))
    (is (= 2 (count (get-in by-alias [:error :candidates]))))))

(deftest explicit-call-id-survives-retry
  (let [first (register! (identity/new-registry) (call 0 "provider-0"))
        retried (register! (:registry first)
                           (assoc (call 0 "provider-retry")
                                  :attempt-id "a2"
                                  :call-id (:call-id first)))]
    (is (= :merged (:status retried)))
    (is (= (:call-id first) (:call-id retried)))
    (is (= ["a1/tool-0"] (identity/call-ids-for-attempt (:registry retried) "a1")))
    (is (= ["a1/tool-0"] (identity/call-ids-for-attempt (:registry retried) "a2")))
    (is (= 1 (:next-id (:registry retried))))))

(deftest invalid-calls-are-rejected
  (is (= :invalid-call
         (get-in (register! (identity/new-registry)
                            (assoc (call 0) :arguments "{}"))
                 [:error :kind])))
  (is (= :invalid-call
         (get-in (register! (identity/new-registry)
                            (assoc (call 0) :tool-name " "))
                 [:error :kind])))
  (is (= :invalid-call
         (get-in (register! (identity/new-registry)
                            (assoc (call 0) :complete? false))
                 [:error :kind]))))

(defspec distinct-indices-always-get-distinct-ids 100
  (prop/for-all [n (gen/choose 1 20)
                 path gen/string-alphanumeric]
    (let [calls (mapv (fn [index]
                        {:attempt-id "a1"
                         :output-index index
                         :provider-call-id (str "p" index)
                         :tool-name "read"
                         :arguments {:path path}
                         :complete? true})
                      (range n))
          registry (reduce (fn [registry value]
                             (:registry (identity/register-call registry value)))
                           (identity/new-registry)
                           calls)
          ids (mapv #(get-in registry [:by-output ["a1" (:output-index %)]])
                    calls)]
      (and (= n (count (set ids)))
           (= n (:next-id registry))))))

(defspec registration-is-idempotent 100
  (prop/for-all [n (gen/choose 1 10)
                 path gen/string-alphanumeric]
    (let [calls (mapv (fn [index]
                        {:attempt-id "a1"
                         :output-index index
                         :provider-call-id (str "p" index)
                         :tool-name "read"
                         :arguments {:path path}
                         :complete? true})
                      (range n))
          registry (reduce (fn [registry value]
                             (:registry (identity/register-call registry value)))
                           (identity/new-registry)
                           calls)
          again (reduce (fn [registry value]
                          (let [result (identity/register-call registry value)]
                            (when-not (= :merged (:status result))
                              (throw (ex-info "expected merge" {:result result})))
                            (:registry result)))
                        registry
                        calls)]
      (= registry again))))
