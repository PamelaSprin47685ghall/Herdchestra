(ns hc.server.model-adapter.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter.errors :as errors])
  (:import [dev.langchain4j.exception AuthenticationException
            ContentFilteredException HttpException InvalidRequestException
            RateLimitException TimeoutException ToolArgumentsException]
           [java.io IOException]
           [java.util.concurrent CancellationException]))

(deftest classifies-transient-and-terminal-failures
  (testing "transient failures remain retryable for the Owner"
    (is (= {:kind :network :retryable? true}
           (select-keys (errors/classify (IOException. "reset"))
                        [:kind :retryable?])))
    (is (= {:kind :rate-limited :retryable? true}
           (select-keys (errors/classify (RateLimitException. "429"))
                        [:kind :retryable?])))
    (is (= {:kind :timeout :retryable? true}
           (select-keys (errors/classify (TimeoutException. "slow"))
                        [:kind :retryable?])))
    (is (= {:kind :server-error :retryable? true}
           (select-keys (errors/classify (HttpException. 503 "down"))
                        [:kind :retryable?]))))

  (testing "configuration and content failures are terminal"
    (is (= {:kind :auth :retryable? false}
           (select-keys (errors/classify (AuthenticationException. "401"))
                        [:kind :retryable?])))
    (is (= {:kind :auth :retryable? false}
           (select-keys (errors/classify (HttpException. 401 "no"))
                        [:kind :retryable?])))
    (is (= {:kind :content-refused :retryable? false}
           (select-keys (errors/classify (ContentFilteredException. "filtered"))
                        [:kind :retryable?])))
    (is (= {:kind :input-too-long :retryable? false}
           (select-keys (errors/classify
                         (InvalidRequestException.
                          "maximum context length exceeded"))
                        [:kind :retryable?]))))

  (testing "tool argument failure asks for a corrected attempt, not a replay"
    (is (= :new-attempt
           (:disposition (errors/classify
                          (ToolArgumentsException. "bad arguments"))))))

  (testing "user cancellation never enters the retry budget"
    (is (= :cancel
           (:disposition (errors/classify
                          (CancellationException. "cancelled")))))))

(deftest error-messages-do-not-carry-credentials
  (let [classified
        (errors/classify
         (AuthenticationException.
          (str "Incorrect API key provided: sk-live-secret-123 "
               "Authorization: Bearer abc.def.ghi "
               "api_key=plain-secret token=other-secret "
               "secret:final-secret")))]
    (is (= :auth (:kind classified)))
    (is (= "dev.langchain4j.exception.AuthenticationException"
           (:exception-class classified)))
    (doseq [secret ["sk-live-secret-123" "abc.def.ghi" "plain-secret"
                    "other-secret" "final-secret"]]
      (is (not (clojure.string/includes? (:message classified) secret))
          (str "redacted: " secret)))))
