(ns hc.server.model-port.contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.model-port.contract :as contract]))

(defn recording-receiver
  [events]
  (reify contract/Receiver
    (deliver! [_ event]
      (swap! events conj event)
      {:ok true})))

(defn recording-port
  [started cancelled]
  (reify contract/ModelPort
    (start-request! [_ request receiver]
      (reset! started {:request request :receiver receiver})
      {:started true})
    (cancel-request! [_ handle]
      (reset! cancelled handle)
      {:cancelled true})))

(deftest freeze-rejects-mutable-values
  (testing "a mutable object cannot enter an attempt snapshot"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"mutable or opaque"
         (contract/freeze-value (java.util.ArrayList.))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"mutable or opaque"
         (contract/freeze-value (object-array 1))))
    (is (contract/invalid-request?
         (try
           (contract/freeze-value (java.util.ArrayList.))
           (catch clojure.lang.ExceptionInfo t t))))))

(deftest request-requires-identity
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"attempt-id"
                        (contract/request {:call-id :c :snapshot {}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"call-id"
                        (contract/request {:attempt-id :a :snapshot {}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                        (contract/request {:attempt-id :a :call-id :c
                                           :snapshot []}))))

(deftest request-freezes-the-snapshot
  (let [snapshot {:messages [{:role :user :content "hello"}]
                  :tools #{{:name "read" :parameters {:path "x"}}}
                  :limits {:max-tokens 10}}
        request (contract/request {:attempt-id :a1
                                   :call-id :c1
                                   :snapshot snapshot
                                   :required-capabilities [:text :tools]})]
    (is (= snapshot (:snapshot request)))
    (is (= #{:text :tools} (:required-capabilities request)))
    (is (= {:attempt-id :a1 :call-id :c1} (contract/handle request)))
    (is (contract/handle? (contract/handle request)))))

(deftest unsupported-capabilities-are-explicit
  (let [request (contract/request {:attempt-id :a1
                                   :call-id :c1
                                   :snapshot {:messages []}
                                   :required-capabilities [:text :image]})
        failed (contract/resolve-capabilities request #{:text} {})
        degraded (contract/resolve-capabilities
                  request
                  #{:text}
                  {:image {:mode :text-only
                           :reason "provider has no vision input"}})]
    (is (false? (:ok failed)))
    (is (= [:image] (get-in failed [:error :missing])))
    (is (true? (:ok degraded)))
    (is (= {:image {:mode :text-only
                    :reason "provider has no vision input"}}
           (:degraded degraded)))
    (is (= request (:request degraded)))))

(deftest events-carry-attempt-identity
  (let [event (contract/event {:kind :chunk
                               :attempt-id :a1
                               :call-id :c1
                               :output-index 0
                               :text "hi"})
        events (atom [])
        receiver (recording-receiver events)]
    (is (= :chunk (:event event)))
    (is (= :a1 (:attempt-id event)))
    (is (= {:ok true} (contract/deliver-event! receiver event)))
    (is (= [event] @events))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown model port event"
                          (contract/event {:kind :surprise :attempt-id :a1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"attempt-id"
                          (contract/event {:kind :chunk})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"event must carry"
                          (contract/deliver-event! receiver {:event :chunk})))))

(deftest narrow-port-contract-dispatches
  (let [started (atom nil)
        cancelled (atom nil)
        port (recording-port started cancelled)
        events (atom [])
        receiver (recording-receiver events)
        request (contract/request {:attempt-id :a1
                                   :call-id :c1
                                   :snapshot {:messages []}})]
    (is (= {:started true} (contract/start! port request receiver)))
    (is (= request (:request @started)))
    (is (identical? receiver (:receiver @started)))
    (is (= {:cancelled true}
           (contract/cancel! port (contract/handle request))))
    (is (= {:attempt-id :a1 :call-id :c1} @cancelled))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"handle"
                          (contract/cancel! port {:attempt-id :a1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Receiver"
                          (contract/start! port request {})))))

(def gen-scalar
  (gen/one-of [gen/small-integer
               gen/string-alphanumeric
               gen/keyword
               gen/boolean
               (gen/return nil)]))

(def gen-edn
  (gen/one-of [gen-scalar
               (gen/vector gen-scalar)
               (gen/map gen/keyword (gen/vector gen-scalar))]))

(defspec freeze-roundtrip 100
  (prop/for-all [value gen-edn]
    (= value (contract/freeze-value value))))
