(ns hc.server.model-adapter.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter.events :as events]
            [hc.server.model-adapter.support :as support])
  (:import [dev.langchain4j.model.openai OpenAiTokenUsage
            OpenAiTokenUsage$InputTokensDetails]
           [dev.langchain4j.model.output FinishReason TokenUsage]))

(deftest usage-never-invents-zero
  (testing "a missing usage report is entirely unknown"
    (let [report (events/usage-report nil)]
      (is (= :unknown (get-in report [:usage :input-tokens])))
      (is (= :unknown (get-in report [:usage :output-tokens])))
      (is (= :unknown (get-in report [:usage :cache-read-tokens])))
      (is (= :unknown (get-in report [:usage :cache-write-tokens])))))

  (testing "a partially reported usage keeps the unknown half unknown"
    (let [report (events/usage-report (TokenUsage. (int 5) nil))]
      (is (= 5 (get-in report [:usage :input-tokens])))
      (is (= :unknown (get-in report [:usage :output-tokens])))
      (is (= :reported (get-in report [:sources :input-tokens])))
      (is (= :unknown (get-in report [:sources :output-tokens]))))))

(deftest openai-cache-tokens-are-reported
  (let [details (-> (OpenAiTokenUsage$InputTokensDetails/builder)
                    (.cachedTokens (int 7))
                    (.cacheWriteTokens (int 3))
                    (.build))
        usage (-> (OpenAiTokenUsage/builder)
                  (.inputTokenCount (int 20))
                  (.outputTokenCount (int 5))
                  (.inputTokensDetails details)
                  (.build))
        report (events/usage-report usage)]
    (is (= 20 (get-in report [:usage :input-tokens])))
    (is (= 5 (get-in report [:usage :output-tokens])))
    (is (= 7 (get-in report [:usage :cache-read-tokens])))
    (is (= 3 (get-in report [:usage :cache-write-tokens])))
    (is (= :reported (get-in report [:sources :cache-read-tokens]))))
  (testing "the base TokenUsage still leaves cache fields unknown"
    (let [report (events/usage-report (TokenUsage. (int 5) (int 2)))]
      (is (= :unknown (get-in report [:usage :cache-read-tokens])))
      (is (= :unknown (get-in report [:usage :cache-write-tokens]))))))

(deftest finish-reason-mapping-is-explicit
  (is (= :stop (events/finish-reason->keyword FinishReason/STOP)))
  (is (= :length (events/finish-reason->keyword FinishReason/LENGTH)))
  (is (= :tool-execution
         (events/finish-reason->keyword FinishReason/TOOL_EXECUTION)))
  (is (= :content-filter
         (events/finish-reason->keyword FinishReason/CONTENT_FILTER)))
  (is (nil? (events/finish-reason->keyword nil))))

(deftest final-tool-call-parse-is-all-or-nothing
  (let [response (support/response
                  {:text "x"
                   :tool-calls [(support/tool-request "p1" "cat" "{bad")]})
        parsed (events/response-tool-calls response
                                           (fn [_] (throw (ex-info "bad" {}))))]
    (is (false? (:ok parsed)))
    (is (= :tool-arguments-invalid (get-in parsed [:error :kind])))))
