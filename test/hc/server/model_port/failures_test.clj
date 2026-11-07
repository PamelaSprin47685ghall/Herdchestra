(ns hc.server.model-port.failures-test
  "Stable failure codes shared with `hc.client.copy.failures`.

  Every code must carry a Chinese message and the correct retry fact.  The
  four categories that must never be conflated are pinned separately:
  authentication, permission, missing model, quota, and rate limiting."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter.errors :as errors]
            [hc.server.model-port.failures :as failures])
  (:import [dev.langchain4j.exception HttpException InvalidRequestException]))

(deftest every-stable-code-has-copy-and-retry
  (doseq [[code {:keys [message retryable?]}] failures/codes]
    (is (keyword? code))
    (is (string? message))
    (is (not (clojure.string/blank? message)))
    (is (boolean? retryable?))))

(deftest the-ten-required-codes
  (let [cases
        [[{:kind :auth} :auth-invalid false]
         [{:kind :permission} :permission-denied false]
         [{:kind :invalid-model} :model-missing false]
         [{:kind :insufficient-quota} :quota-exhausted false]
         [{:kind :rate-limited} :rate-limited true]
         [{:kind :network} :network-unreachable true]
         [{:kind :transport} :transport-disconnected true]
         [{:kind :timeout} :response-timeout true]
         [{:kind :input-too-long} :context-too-long false]
         [{:kind :unsupported-feature} :attachment-unsupported false]]]
    (doseq [[classification code retryable?] cases]
      (let [result (failures/classify classification)]
        (is (= code (:code result)) (pr-str classification))
        (is (= retryable? (:retryable? result)) (pr-str classification))
        (is (string? (:message result)))))))

(deftest rate-limit-and-quota-are-separate
  (testing "429 is retryable rate limiting"
    (is (= {:code :rate-limited :retryable? true}
           (select-keys (failures/classify {:kind :rate-limited
                                            :http-status 429})
                        [:code :retryable?]))))
  (testing "402 and quota text are terminal quota exhaustion"
    (is (= {:code :quota-exhausted :retryable? false}
           (select-keys (failures/classify {:http-status 402})
                        [:code :retryable?])))
    (is (= :quota-exhausted
           (:code (failures/classify
                   {:kind :unclassified
                    :message "insufficient_quota: no credit balance"}))))))

(deftest missing-model-is-not-a-network-failure
  (is (= {:code :model-missing :retryable? false}
         (select-keys (failures/classify {:http-status 404})
                      [:code :retryable?])))
  (is (= {:code :model-missing :retryable? false}
         (select-keys (failures/classify {:kind :invalid-model})
                      [:code :retryable?]))))

(deftest adapter-classification-carries-the-stable-code
  (testing "HTTP statuses map to distinct codes"
    (is (= :auth-invalid
           (:code (errors/classify (HttpException. 401 "no")))))
    (is (= :permission-denied
           (:code (errors/classify (HttpException. 403 "forbidden")))))
    (is (= :model-missing
           (:code (errors/classify (HttpException. 404 "missing")))))
    (is (= :quota-exhausted
           (:code (errors/classify (HttpException. 402 "payment")))))
    (is (= :rate-limited
           (:code (errors/classify (HttpException. 429 "slow down")))))
    (is (= :response-timeout
           (:code (errors/classify (HttpException. 504 "gateway timeout"))))))
  (testing "context length is a terminal input error with Chinese copy"
    (let [result (errors/classify
                  (InvalidRequestException.
                   "maximum context length exceeded"))]
      (is (= :context-too-long (:code result)))
      (is (false? (:retryable? result)))
      (is (= "当前内容超过所选模型可接受的范围；请调整内容范围。"
             (:user-message result))))))
