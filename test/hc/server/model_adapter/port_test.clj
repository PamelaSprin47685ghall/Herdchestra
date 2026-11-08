(ns hc.server.model-adapter.port-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter :as adapter]
            [hc.server.model-adapter.port :as port]
            [hc.server.model-adapter.providers :as providers]
            [hc.server.model-adapter.support :as support]
            [hc.server.model-port.call-identity :as identity]
            [hc.server.model-port.contract :as contract]
            [hc.server.model-port.stream :as stream])
  (:import [dev.langchain4j.model.chat.response CompleteToolCall PartialToolCall]
           [dev.langchain4j.model.output FinishReason]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def snapshot
  {:role :main
   :messages [{:role :user :text "read a.txt"}]
   :tools [{:name "cat"
            :description "read a file"
            :parameters {:type "object"
                         :properties {:path {:type "string"}}
                         :required ["path"]}}]
   :stream? true})

(defn- request
  ([attempt-id] (request attempt-id snapshot))
  ([attempt-id snapshot]
   (contract/request {:attempt-id attempt-id
                      :call-id (str "business-" attempt-id)
                      :snapshot snapshot
                      :required-capabilities (:required-capabilities snapshot)})))

(defn- completed
  [rec]
  (support/wait-for
   #(first (filter (comp #{:completed} :event) @(:events rec)))))

(defn- failed
  [rec]
  (support/wait-for
   #(first (filter (comp #{:failed} :event) @(:events rec)))))

(deftest streaming-tool-call-is-published-once-and-merged
  (let [script (fn [_request handler]
                 (.onPartialResponse handler "Hel")
                 (.onPartialResponse handler "lo")
                 (.onPartialToolCall
                  handler
                  (-> (PartialToolCall/builder)
                      (.index 0)
                      (.id "provider-1")
                      (.name "cat")
                      (.partialArguments "{\"path\":")
                      (.build)))
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request "provider-1" "cat"
                                                             {:path "a.txt"})))
                 (.onCompleteResponse
                  handler
                  (support/response {:text "Hello"
                                     :tool-calls [(support/tool-request
                                                   "provider-1" "cat"
                                                   {:path "a.txt"})]
                                     :usage (support/token-usage 10 5)
                                     :id "response-1"
                                     :model-name "test-model"
                                     :finish-reason FinishReason/TOOL_EXECUTION})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (let [handle (contract/start! port (request "a1") (:receiver rec))
            result (completed rec)]
        (is (some? handle))
        (is (some? result))
        (is (= ["Hel" "lo"]
               (mapv :text (support/events-of (:events rec) :chunk))))
        (let [calls (support/events-of (:events rec) :tool-call)]
          (is (= 1 (count calls))
              "the complete callback and final response must merge by identity")
          (is (= "a1/tool-0" (:call-id (first calls))))
          (is (= 0 (:output-index (first calls))))
          (is (= "cat" (:tool-name (first calls))))
          (is (= {:path "a.txt"} (:arguments (first calls)))))
        (is (= ["a1/tool-0"] (:published-call-ids result)))
        (is (= 10 (get-in result [:usage :usage :input-tokens])))
        (is (= 5 (get-in result [:usage :usage :output-tokens])))
        (is (= :tool-execution (:finish-reason result)))
        (is (= "response-1" (:response-id result))))
      (finally
        (adapter/stop-port! port)))))

(deftest differing-streamed-and-final-indices-still-merge
  ;; OpenAI-compatible providers number streamed tool-call deltas by position
  ;; in the delta, while the final aiMessage lists calls by array position.
  ;; The two indices can disagree for a legitimate single call; the provider
  ;; id and payload must still merge it into one project call.
  (let [script (fn [_request handler]
                 (.onPartialResponse handler "ok")
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 1 (support/tool-request
                                        "provider-shifted" "cat"
                                        {:path "a.txt"})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "ok"
                    :tool-calls [(support/tool-request
                                  "provider-shifted" "cat"
                                  {:path "a.txt"})]
                    :finish-reason FinishReason/TOOL_EXECUTION})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "index-shift-1") (:receiver rec))
      (let [calls (support/wait-for
                   #(when-let [event
                               (first (support/events-of (:events rec)
                                                         :completed))]
                      (support/events-of (:events rec) :tool-call)))]
        (is (some? calls)
            "a differing final list position must not fail the attempt")
        (is (empty? (support/events-of (:events rec) :failed)))
        (is (= 1 (count calls)))
        (is (= 1 (:output-index (first calls)))
            "the streamed output index wins over the final list position")
        (is (= ["index-shift-1/tool-1"]
               (:published-call-ids
                (first (support/events-of (:events rec) :completed))))))
      (finally
        (adapter/stop-port! port)))))

(deftest cancellation-is-terminal-and-late-callbacks-are-dropped
  (let [started (promise)
        release (CountDownLatch. 1)
        script (fn [_request handler]
                 (.onPartialResponse handler "first")
                 (deliver started true)
                 (try
                   (.await release 2 TimeUnit/SECONDS)
                   (catch InterruptedException _))
                 (.onPartialResponse handler "late")
                 (.onCompleteResponse handler
                                      (support/response {:text "late"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (let [handle (contract/start! port (request "cancel-1") (:receiver rec))]
        (is (deref started 2000 false))
        (is (support/wait-for
             #(first (support/events-of (:events rec) :chunk))))
        (is (:ok (contract/cancel! port handle)))
        (.countDown release)
        (is (support/wait-for
             #(first (support/events-of (:events rec) :cancelled))))
        (Thread/sleep 100)
        (is (= 1 (count (support/events-of (:events rec) :cancelled))))
        (is (empty? (support/events-of (:events rec) :completed)))
        (is (= ["first"]
               (mapv :text (support/events-of (:events rec) :chunk))))
        (is (= 1 (:cancelled (adapter/stats port))))
        (is (pos? (:late-events (adapter/stats port)))
            "callbacks after cancellation stay diagnostic and counted"))
      (finally
        (adapter/stop-port! port)))))

(deftest final-response-merges-a-sparse-streamed-index
  (let [script (fn [_request handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 3 (support/tool-request
                                        "provider-sparse" "cat"
                                        {:path "a.txt"})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request
                                  "provider-sparse" "cat"
                                  {:path "a.txt"})]
                    :finish-reason FinishReason/TOOL_EXECUTION})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "sparse-index-1") (:receiver rec))
      (let [result (completed rec)
            calls (support/events-of (:events rec) :tool-call)]
        (is (some? result))
        (is (= 1 (count calls))
            "a repeated provider call must not become a second project call")
        (is (= 3 (:output-index (first calls)))
            "the streamed output index wins over final-response list position")
        (is (= ["sparse-index-1/tool-3"] (:published-call-ids result))))
      (finally
        (adapter/stop-port! port)))))

(deftest port-close-cancels-with-port-close-reason
  (let [started (promise)
        release (CountDownLatch. 1)
        script (fn [_request handler]
                 (.onPartialResponse handler "first")
                 (deliver started true)
                 (try
                   (.await release 2 TimeUnit/SECONDS)
                   (catch InterruptedException _)))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (contract/start! port (request "close-1") (:receiver rec))
    (is (deref started 2000 false))
    (adapter/stop-port! port)
    (.countDown release)
    (let [event (support/wait-for
                 #(first (support/events-of (:events rec) :cancelled)))]
      (is (some? event))
      (is (= :port-close (:reason event))))))

(deftest port-close-releases-retained-attempt-state
  (let [script (fn [_ handler]
                 (.onCompleteResponse handler
                                      (support/response {:text "done"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (contract/start! port (request "close-state-1") (:receiver rec))
    (is (some? (completed rec)))
    (is (pos? (:total (adapter/stats port))))
    (adapter/stop-port! port)
    (is (zero? (:total (adapter/stats port))))))

(deftest provider-id-reuse-with-different-payload-fails
  (let [script (fn [_request handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request
                                        "provider-dup" "cat"
                                        {:path "a.txt"})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request
                                  "provider-dup" "cat"
                                  {:path "b.txt"})]})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "provider-dup-1") (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :provider-id-ambiguous (get-in result [:error :kind])))
        (is (= :PROVIDER_ID_AMBIGUOUS (get-in result [:error :code])))
        (is (= ["provider-dup-1/tool-0"] (:published-call-ids result)))
        (is (true? (:partial? result))))
      (finally
        (adapter/stop-port! port)))))

(deftest invalid-tool-arguments-fail-without-publishing-a-call
  (let [script (fn [_request handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request "provider-1" "cat"
                                                             "{not json")))
                 (.onCompleteResponse handler
                                      (support/response {:text "unreachable"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "bad-args") (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :tool-arguments-invalid (get-in result [:error :kind])))
        (is (empty? (support/events-of (:events rec) :tool-call)))
        (is (empty? (support/events-of (:events rec) :completed))))
      (finally
        (adapter/stop-port! port)))))

(deftest stream-failure-reports-published-tool-calls
  (let [script (fn [_request handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request "provider-1" "cat"
                                                             {:path "a.txt"})))
                 (.onError handler (java.io.IOException. "stream broke")))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "partial-failure-1") (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (true? (:partial? result)))
        (is (= ["partial-failure-1/tool-0"] (:published-call-ids result)))
        (is (= "cat" (get-in result [:published-calls 0 :tool-name])))
        (is (= {:path "a.txt"}
               (get-in result [:published-calls 0 :arguments])))
        (is (= :network (get-in result [:error :kind])))
        (is (true? (:retryable? result)))
        (is (= :retry (:disposition result)))
        (is (= :network (:category result))))
      (finally
        (adapter/stop-port! port)))))

(deftest accepts-edn-and-ufss-tool-arguments
  (let [arguments (str "{:path #\"" "a\\b.txt" "\"#}")
        script (fn [_request handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request
                                        "provider-ufss" "cat" arguments)))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request
                                  "provider-ufss" "cat" arguments)]
                    :finish-reason FinishReason/TOOL_EXECUTION})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "ufss-1") (:receiver rec))
      (let [result (completed rec)
            calls (support/events-of (:events rec) :tool-call)]
        (is (some? result))
        (is (= 1 (count calls)))
        (is (= {:path "a\\b.txt"} (:arguments (first calls))))
        (is (= {:path "a\\b.txt"}
               (get-in result [:tool-calls 0 :arguments]))))
      (finally
        (adapter/stop-port! port)))))

(deftest unsupported-capability-fails-before-the-model-is-called
  (let [called? (atom false)
        script (fn [_request handler]
                 (reset! called? true)
                 (.onCompleteResponse handler
                                      (support/response {:text "unexpected"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "cap-1" (assoc snapshot
                                               :required-capabilities
                                               #{:reasoning}))
                       (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :unsupported-capability (get-in result [:error :kind])))
        (is (false? @called?)))
      (finally
        (adapter/stop-port! port)))))

(deftest missing-usage-stays-unknown
  (let [script (fn [_request]
                 (support/response {:text "done"
                                    :finish-reason FinishReason/STOP}))
        port (adapter/create-model-port
              (support/custom-config nil
                                     (support/chat-model script)
                                     #{:tools}))
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "usage-1" (assoc snapshot :stream? false))
                       (:receiver rec))
      (let [result (completed rec)]
        (is (some? result))
        (is (= :unknown (get-in result [:usage :usage :input-tokens])))
        (is (= :unknown (get-in result [:usage :usage :output-tokens])))
        (is (= :unknown (get-in result [:usage :sources :input-tokens]))))
      (finally
        (adapter/stop-port! port)))))

(deftest requested-transport-must-exist
  (let [port (adapter/create-model-port
              (support/custom-config (support/streaming-model (fn [_ _]))
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "transport-1" (assoc snapshot :stream? false))
                       (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :chat-model-unavailable (get-in result [:error :kind]))))
      (finally
        (adapter/stop-port! port)))))

(deftest role-selects-the-configured-model
  (let [main-called? (atom false)
        task-called? (atom false)
        main-model (support/streaming-model
                    (fn [_ handler]
                      (reset! main-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "main"}))))
        task-model (support/streaming-model
                    (fn [_ handler]
                      (reset! task-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "task"}))))
        port (adapter/create-model-port
              {:connections [{:id "local"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "main-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model main-model}
                                       {:id "task-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model task-model}]}]
               :roles {:main {:connection "local" :model "main-model"}
                       :task {:connection "local" :model "task-model"}}
               :executor-threads 2})
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "role-1" (assoc snapshot :role :task))
                       (:receiver rec))
      (let [result (completed rec)]
        (is (some? result))
        (is (= "task" (:text result)))
        (is (true? @task-called?))
        (is (false? @main-called?)))
      (finally
        (adapter/stop-port! port)))))

(deftest main-role-session-binding-overrides-the-role-target
  (let [main-called? (atom false)
        session-called? (atom false)
        main-model (support/streaming-model
                    (fn [_ handler]
                      (reset! main-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "main"}))))
        session-model (support/streaming-model
                       (fn [_ handler]
                         (reset! session-called? true)
                         (.onCompleteResponse handler
                                              (support/response
                                               {:text "session"}))))
        port (adapter/create-model-port
              {:connections [{:id "main-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "main-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model main-model}]}
                             {:id "session-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "session-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model session-model}]}]
               :roles {:main {:connection "main-conn" :model "main-model"}
                       :task {:connection "main-conn" :model "main-model"}}
               :executor-threads 2})]
    (try
      (let [rec (support/receiver)]
        (contract/start! port
                         (request "session-binding"
                                  (merge snapshot
                                         {:role :main
                                          :connection "session-conn"
                                          :model "session-model"}))
                         (:receiver rec))
        (let [result (completed rec)]
          (is (some? result))
          (is (= "session" (:text result)))
          (is (true? @session-called?))
          (is (false? @main-called?))))
      (let [rec (support/receiver)]
        (contract/start! port
                         (request "task-cannot-borrow"
                                  (merge snapshot
                                         {:role :task
                                          :connection "session-conn"
                                          :model "session-model"}))
                         (:receiver rec))
        (let [result (failed rec)]
          (is (= :role-connection-mismatch (get-in result [:error :kind]))
              "a task request must stay on the task role target")))
      (finally
        (adapter/stop-port! port)))))

(deftest task-snapshot-connection-cannot-override-the-role-target
  (let [main-called? (atom false)
        task-called? (atom false)
        main-model (support/streaming-model
                    (fn [_ handler]
                      (reset! main-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "main"}))))
        task-model (support/streaming-model
                    (fn [_ handler]
                      (reset! task-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "task"}))))
        port (adapter/create-model-port
              {:connections [{:id "main-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "main-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model main-model}]}
                             {:id "task-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "task-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model task-model}]}]
               :roles {:main {:connection "main-conn" :model "main-model"}
                       :task {:connection "task-conn" :model "task-model"}}
               :executor-threads 2})]
    (try
      (doseq [[attempt-id override]
              [["role-connection-override"
                {:connection "main-conn"}]
               ["role-provider-override"
                {:provider "main-conn"}]
               ["role-connection-model-override"
                {:connection "main-conn" :model "main-model"}]
               ["role-provider-model-override"
                {:provider "main-conn" :model "main-model"}]]]
        (let [rec (support/receiver)]
          (contract/start! port
                           (request attempt-id
                                    (merge snapshot {:role :task} override))
                           (:receiver rec))
          (let [result (failed rec)]
            (is (some? result))
            (is (= :role-connection-mismatch
                   (get-in result [:error :kind])))
            (is (= "main-conn"
                   (get-in result [:error :requested-connection])))
            (is (= "task-conn"
                   (get-in result [:error :role-connection]))))))
      (is (false? @main-called?))
      (is (false? @task-called?))
      (finally
        (adapter/stop-port! port)))))

(deftest role-override-that-names-the-role-target-is-accepted
  (let [main-called? (atom false)
        task-called? (atom false)
        main-model (support/streaming-model
                    (fn [_ handler]
                      (reset! main-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "main"}))))
        task-model (support/streaming-model
                    (fn [_ handler]
                      (reset! task-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "task"}))))
        port (adapter/create-model-port
              {:connections [{:id "main-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "main-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model main-model}]}
                             {:id "task-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "task-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model task-model}]}]
               :roles {:main {:connection "main-conn" :model "main-model"}
                       :task {:connection "task-conn" :model "task-model"}}
               :executor-threads 2})]
    (try
      (doseq [[attempt-id override]
              [["accepted-connection" {:connection "task-conn"}]
               ["accepted-provider" {:provider "task-conn"}]
               ["accepted-connection-model"
                {:connection "task-conn" :model "task-model"}]]]
        (let [rec (support/receiver)]
          (contract/start! port
                           (request attempt-id
                                    (merge snapshot {:role :task} override))
                           (:receiver rec))
          (let [result (completed rec)]
            (is (some? result) attempt-id)
            (is (= "task" (:text result)) attempt-id)
            (is (empty? (support/events-of (:events rec) :failed))
                attempt-id))))
      (is (true? @task-called?))
      (is (false? @main-called?))
      (finally
        (adapter/stop-port! port)))))

(deftest task-role-cannot-override-its-model
  (let [task-model (support/streaming-model
                    (fn [_ handler]
                      (.onCompleteResponse handler
                                           (support/response {:text "task"}))))
        port (adapter/create-model-port
              {:connections [{:id "main-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "main-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model
                                        (support/streaming-model (fn [_ _]))}]}
                             {:id "task-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "task-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model task-model}]}]
               :roles {:main {:connection "main-conn" :model "main-model"}
                       :task {:connection "task-conn" :model "task-model"}}
               :executor-threads 2})
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "borrow-main-model"
                                (merge snapshot
                                       {:role :task
                                        :connection "task-conn"
                                        :model "main-model"}))
                       (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :role-model-mismatch (get-in result [:error :kind]))
            "a task worker must use the task role's model"))
      (finally
        (adapter/stop-port! port)))))

(deftest snapshot-connection-must-repeat-the-role-target
  (let [main-called? (atom false)
        task-a-called? (atom false)
        task-b-called? (atom false)
        main-model (support/streaming-model
                    (fn [_ handler]
                      (reset! main-called? true)
                      (.onCompleteResponse handler
                                           (support/response {:text "main"}))))
        task-a-model (support/streaming-model
                      (fn [_ handler]
                        (reset! task-a-called? true)
                        (.onCompleteResponse handler
                                             (support/response {:text "task-a"}))))
        task-b-model (support/streaming-model
                      (fn [_ handler]
                        (reset! task-b-called? true)
                        (.onCompleteResponse handler
                                             (support/response {:text "task-b"}))))
        port (adapter/create-model-port
              {:connections [{:id "main-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "main-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model main-model}]}
                             {:id "task-conn"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "task-a-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model task-a-model}
                                       {:id "task-b-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model task-b-model}]}]
               :roles {:main {:connection "main-conn" :model "main-model"}
                       :task {:connection "task-conn" :model "task-a-model"}}
               :executor-threads 2})
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "role-same-1"
                                (assoc snapshot
                                       :role :task
                                       :connection "task-conn"
                                       :model "task-b-model"))
                       (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :role-model-mismatch (get-in result [:error :kind])))
        (is (false? @task-b-called?))
        (is (false? @task-a-called?))
        (is (false? @main-called?)))
      (finally
        (adapter/stop-port! port)))))

(deftest role-is-required-when-both-roles-exist
  (let [port (adapter/create-model-port
              (support/custom-config
               (support/streaming-model
                (fn [_ handler]
                  (.onCompleteResponse handler
                                       (support/response {:text "unexpected"}))))
               nil
               #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "role-required-1" (dissoc snapshot :role))
                       (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :role-required (get-in result [:error :kind]))))
      (finally
        (adapter/stop-port! port)))))

(deftest task-role-is-unconfigured-when-only-main-exists
  (let [called? (atom false)
        model (support/streaming-model
               (fn [_ handler]
                 (reset! called? true)
                 (.onCompleteResponse handler
                                      (support/response {:text "main"}))))
        port (adapter/create-model-port
              {:connections [{:id "local"
                              :kind :custom
                              :implicit-retries 0
                              :models [{:id "main-model"
                                        :capabilities #{:tools :streaming}
                                        :streaming-chat-model model}]}]
               :roles {:main {:connection "local" :model "main-model"}}
               :executor-threads 2})]
    (try
      (let [rec (support/receiver)]
        (contract/start! port
                         (request "task-unconfigured" (assoc snapshot :role :task))
                         (:receiver rec))
        (let [result (failed rec)]
          (is (some? result))
          (is (= :role-unconfigured (get-in result [:error :kind])))
          (is (= :permanent (get-in result [:error :category])))
          (is (= :configure-task-model
                 (get-in result [:error :remedy])))
          (is (= :task (get-in result [:error :role])))))
      (let [rec (support/receiver)]
        (contract/start! port
                         (request "bogus-role" (assoc snapshot :role :bogus))
                         (:receiver rec))
        (let [result (failed rec)]
          (is (some? result))
          (is (= :unknown-role (get-in result [:error :kind])))
          (is (nil? (get-in result [:error :remedy])))))
      (let [rec (support/receiver)]
        (contract/start! port
                         (request "main-still-works" (assoc snapshot :role :main))
                         (:receiver rec))
        (let [result (completed rec)]
          (is (some? result))
          (is (= "main" (:text result)))
          (is (true? @called?))))
      (finally
        (adapter/stop-port! port)))))

(deftest empty-configuration-is-explicitly-not-configured
  (is (false? (adapter/model-configured? {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"no model connection"
                        (adapter/create-model-port {}))))

(deftest implicit-provider-retries-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"max-retries"
                        (adapter/create-model-port
                         (-> (support/custom-config
                              (support/streaming-model (fn [_ _]))
                              nil
                              #{:tools :streaming})
                             (assoc-in [:connections 0 :max-retries] 2))))))

(defn- recording-receiver
  [deliver-fn]
  (let [events (atom [])]
    {:events events
     :receiver (reify contract/Receiver
                 (deliver! [_ event]
                   (let [result (deliver-fn event)]
                     ;; Record only accepted deliveries.  A refused event was
                     ;; still attempted, but the adapter must not treat it as
                     ;; a completed delivery in assertions.
                     (when-not (or (false? (:ok result))
                                   (false? (:ok? result)))
                       (swap! events conj event))
                     result)))}))

(deftest mailbox-shaped-refusal-fails-the-attempt
  (let [script (fn [_ handler]
                 (.onPartialResponse handler "first")
                 (.onCompleteResponse handler
                                      (support/response {:text "done"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (recording-receiver
             (fn [event]
               (if (= :chunk (:event event))
                 {:ok? false
                  :error {:code :MAILBOX_FULL
                          :message "mailbox is full"}}
                 {:ok? true})))]
    (try
      (contract/start! port (request "mailbox-full-1") (:receiver rec))
      (let [event (failed rec)]
        (is (some? event))
        (is (= :delivery-error (get-in event [:error :kind])))
        (is (= :MAILBOX_FULL (get-in event [:error :code])))
        (is (= :overflow (get-in event [:error :delivery])))
        (is (empty? (support/events-of (:events rec) :completed)))
        (is (= 1 (:failed (adapter/stats port))))
        (is (zero? (:active (adapter/stats port)))))
      (finally
        (adapter/stop-port! port)))))

(deftest receiver-exception-fails-the-attempt
  (let [script (fn [_ handler]
                 (.onPartialResponse handler "first")
                 (.onCompleteResponse handler
                                      (support/response {:text "done"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (recording-receiver
             (fn [event]
               (if (= :chunk (:event event))
                 (throw (ex-info "receiver failed" {}))
                 {:ok? true})))]
    (try
      (contract/start! port (request "receiver-throw-1") (:receiver rec))
      (let [event (failed rec)]
        (is (some? event))
        (is (= :delivery-error (get-in event [:error :kind])))
        (is (= :MODEL_EVENT_DELIVERY_FAILED
               (get-in event [:error :code])))
        (is (= :delivery-error (get-in event [:error :delivery])))
        (is (= 1 (:text-chunks event)))
        (is (empty? (support/events-of (:events rec) :completed))))
      (finally
        (adapter/stop-port! port)))))

(deftest terminal-refusal-becomes-a-failed-terminal-event
  (let [script (fn [_ handler]
                 (.onPartialResponse handler "hello")
                 (.onCompleteResponse handler
                                      (support/response {:text "done"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        ;; The Owner mailbox was already closed when the business call began:
        ;; every event is refused, not just the terminal one.
        rec (recording-receiver
             (fn [_event]
               {:ok? false
                :error {:code :MAILBOX_CLOSED
                        :message "mailbox is closed"}}))]
    (try
      (contract/start! port (request "terminal-refusal-1") (:receiver rec))
      ;; Every event is refused, so the recording receiver never accepts one.
      ;; The attempt's own terminal event is the only witness left.
      (let [event (support/wait-for
                   #(let [result (adapter/await-attempt
                                  port "terminal-refusal-1" 1000)]
                      (when (= :failed (:event result)) result)))]
        (is (some? event))
        (is (= :delivery-error (get-in event [:error :kind])))
        (is (= :MAILBOX_CLOSED (get-in event [:error :code])))
        (is (= :closed (get-in event [:error :delivery])))
        (is (= 1 (:text-chunks event))
            "the count reports what the model produced; the refusal is in :error")
        (is (empty? (support/events-of (:events rec) :completed)))
        (is (empty? (support/events-of (:events rec) :chunk))
            "the refused chunk is not recorded as delivered")
        (is (zero? (:active (adapter/stats port)))))
    (finally
      (adapter/stop-port! port)))))

(deftest refused-completion-still-terminates-as-failed
  ;; Only the terminal event is refused.  The attempt must still end
  ;; :failed, carry the refusal as its cause, and never look completed.
  (let [script (fn [_ handler]
                 (.onPartialResponse handler "hello")
                 (.onCompleteResponse handler
                                      (support/response {:text "done"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (recording-receiver
             (fn [event]
               (if (= :completed (:event event))
                 {:ok? false
                  :error {:code :MAILBOX_CLOSED
                          :message "mailbox is closed"}}
                 {:ok? true})))]
    (try
      (contract/start! port (request "terminal-refused-1") (:receiver rec))
      (let [event (failed rec)
            awaited (adapter/await-attempt port "terminal-refused-1" 1000)]
        (is (some? event))
        (is (= :failed (:event awaited))
            "a refused completion must not look completed")
        (is (= :delivery-error (get-in event [:error :kind])))
        (is (= :MAILBOX_CLOSED (get-in event [:error :code])))
        (is (= 1 (:text-chunks event)))
        (is (= 1 (:delivery-drops (adapter/stats port)))
            "the refused terminal is counted, not silently swallowed")
        (is (empty? (support/events-of (:events rec) :completed))))
      (finally
        (adapter/stop-port! port)))))

(deftest refused-cancellation-terminates-as-failed-with-its-reason
  (let [started (promise)
        release (CountDownLatch. 1)
        script (fn [_ handler]
                 (.onPartialResponse handler "first")
                 (deliver started true)
                 (try
                   (.await release 2 TimeUnit/SECONDS)
                   (catch InterruptedException _)))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (recording-receiver
             (fn [event]
               (if (= :cancelled (:event event))
                 {:ok? false
                  :error {:code :MAILBOX_CLOSED
                          :message "mailbox is closed"}}
                 {:ok? true})))]
    (try
      (let [handle (contract/start! port (request "cancel-refused-1")
                                    (:receiver rec))]
        (is (deref started 2000 false))
        (is (= 1 (count (support/events-of (:events rec) :chunk))))
        (let [result (contract/cancel! port handle)]
          (is (true? (:delivery-failed? result))
              "the caller learns the cancellation event was not delivered")
          (is (= :failed (get-in result [:event :event]))
              "the returned terminal is the failed event, not the refused one")
          (is (= :MAILBOX_CLOSED
                 (get-in result [:event :error :code]))))
        (.countDown release)
        (let [event (failed rec)]
          (is (some? event))
          (is (= :user-cancel (:reason event))
              "the refusal still names why the attempt was cancelled")
          (is (= :delivery-error (get-in event [:error :kind]))))
        (is (empty? (support/events-of (:events rec) :cancelled)))
        (is (zero? (:active (adapter/stats port)))))
      (finally
        (.countDown release)
        (adapter/stop-port! port)))))

(deftest overflow-after-tool-call-reports-partial
  (let [script (fn [_ handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request
                                        "provider-1" "cat"
                                        {:path "a.txt"})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request
                                  "provider-1" "cat"
                                  {:path "a.txt"})]})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (recording-receiver
             (fn [event]
               (if (= :completed (:event event))
                 {:ok false :reason :overflow}
                 {:ok? true})))]
    (try
      (contract/start! port (request "overflow-tool-1") (:receiver rec))
      (let [event (failed rec)]
        (is (some? event))
        (is (true? (:partial? event)))
        (is (= ["overflow-tool-1/tool-0"] (:published-call-ids event)))
        (is (= "cat" (get-in event [:published-calls 0 :tool-name])))
        (is (= {:path "a.txt"}
               (get-in event [:published-calls 0 :arguments])))
        (is (= :delivery-error (get-in event [:error :kind])))
        (is (= :overflow (get-in event [:error :delivery])))
        (is (= :MODEL_EVENT_DELIVERY_FAILED
               (get-in event [:error :code]))))
      (finally
        (adapter/stop-port! port)))))

(deftest malformed-final-response-fails-cleanly
  (let [script (fn [_ handler]
                 (.onCompleteResponse handler nil))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "malformed-final-1") (:receiver rec))
      (let [event (failed rec)]
        (is (some? event))
        (is (= :invalid-response (get-in event [:error :kind])))
        (is (= :INVALID_MODEL_RESPONSE (get-in event [:error :code])))
        (is (empty? (support/events-of (:events rec) :completed))))
      (finally
        (adapter/stop-port! port)))))

(deftest invalid-token-usage-fails-cleanly
  (let [script (fn [_ handler]
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :usage (support/token-usage -1 0)})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "invalid-usage-1") (:receiver rec))
      (let [event (failed rec)]
        (is (some? event))
        (is (empty? (support/events-of (:events rec) :completed))))
      (finally
        (adapter/stop-port! port)))))

(deftest blank-provider-ids-do-not-collide
  (let [script (fn [_ handler]
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request "" "cat"
                                                        {:path "a.txt"})
                                 (support/tool-request "" "dog"
                                                        {:path "b.txt"})]})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "blank-provider-1") (:receiver rec))
      (let [result (completed rec)
            calls (support/events-of (:events rec) :tool-call)]
        (is (some? result))
        (is (= 2 (count calls)))
        (is (= #{"blank-provider-1/tool-0" "blank-provider-1/tool-1"}
               (set (:published-call-ids result))))
        (is (empty? (support/events-of (:events rec) :failed))))
      (finally
        (adapter/stop-port! port)))))

(deftest final-provider-id-change-merges-by-unique-payload
  (let [script (fn [_ handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request
                                        "provider-a" "cat"
                                        {:path "a.txt"})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request
                                  "provider-b" "cat"
                                  {:path "a.txt"})]})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                              nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "provider-change-1") (:receiver rec))
      (let [result (completed rec)
            calls (support/events-of (:events rec) :tool-call)]
        (is (some? result))
        (is (= 1 (count calls)))
        (is (= ["provider-change-1/tool-0"] (:published-call-ids result)))
        (is (empty? (support/events-of (:events rec) :failed))))
      (finally
        (adapter/stop-port! port)))))

(deftest negative-stream-index-is-rejected
  (let [result (stream/accept-fragment
                (stream/new-assembler)
                (identity/new-registry)
                {:attempt-id "negative-index"
                 :output-index -1
                 :provider-call-id "provider-1"
                 :tool-name "cat"
                 :arguments-fragment "{\"path\":"
                 :complete? false})]
    (is (false? (:ok result)))
    (is (= :invalid-fragment (get-in result [:error :kind])))
    (is (nil? (:event result)))))

(deftest context-callback-style-is-scoped-per-channel
  (let [rec (recording-receiver (fn [_] {:ok? true}))
        state (atom {:status :running
                     :context-channels #{}
                     :text-parts []
                     :reasoning-parts []
                     :published-call-ids []
                     :published-calls []
                     :terminal-event nil
                     :promise (promise)})
        attempt {:attempt-id "context-1"
                 :call-id "context-call-1"
                 :lock (Object.)
                 :state state
                 :receiver (:receiver rec)
                 :port {:counters (atom {:delivery-drops 0})}}]
    (#'port/chunk! attempt :text "context-text" true)
    (#'port/chunk! attempt :text "ordinary-text" false)
    (#'port/chunk! attempt :reasoning "ordinary-reasoning" false)
    (let [chunks (support/events-of (:events rec) :chunk)
          text (mapv :text (filter #(= :text (:channel %)) chunks))
          reasoning (mapv :text (filter #(= :reasoning (:channel %)) chunks))]
      (is (= ["context-text"] text)
          "ordinary text is suppressed only after context text")
      (is (= ["ordinary-reasoning"] reasoning)
          "reasoning is not suppressed by a text context callback"))))

(deftest complete-callback-reuses-a-unique-partial-index
  (let [script (fn [_ handler]
                 (.onPartialToolCall
                  handler
                  (-> (PartialToolCall/builder)
                      (.index 0)
                      (.id "provider-1")
                      (.name "cat")
                      (.partialArguments "{\"path\":")
                      (.build)))
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 1 (support/tool-request
                                        "provider-1" "cat"
                                        {:path "a.txt"})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request
                                  "provider-1" "cat"
                                  {:path "a.txt"})]})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil
                                     #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "partial-index-shift") (:receiver rec))
      (let [result (completed rec)
            calls (support/events-of (:events rec) :tool-call)]
        (is (some? result))
        (is (= 1 (count calls)))
        (is (= 0 (:output-index (first calls))))
        (is (= {:path "a.txt"} (:arguments (first calls))))
        (is (= ["partial-index-shift/tool-0"] (:published-call-ids result)))
        (is (empty? (support/events-of (:events rec) :failed))))
      (finally
        (adapter/stop-port! port)))))

(deftest request-credential-profile-is-closed-at-terminal
  (let [closed? (atom false)
        streaming (support/streaming-model
                   (fn [_ handler]
                     (.onCompleteResponse
                      handler
                      (support/response {:text "ok"}))))
        profile {:connection-id "local"
                 :model-id "test-model"
                 :model-name "test-model"
                 :provider-kind :custom
                 :connection {:id "local"}
                 :model {:id "test-model"}
                 :capabilities #{:tools :streaming}
                 :honoured-capabilities #{:tools :streaming}
                 :degradations {}
                 :implicit-retries 0
                 :streaming-chat-model streaming
                 :close-fns [(fn [] (reset! closed? true))]
                 :requires-credential? true}
        config {:connections [{:id "local"
                               :kind :openai-compatible
                               :base-url "http://127.0.0.1:1/v1"
                               :credentials {"c1" {:alias "default"
                                                   :secret-ref "vault://c1"}}
                               :models [{:id "test-model"
                                         :capabilities #{:tools :streaming}}]}]
                :roles {:main {:connection "local" :model "test-model"}}
                :credential-resolver (fn [_] {:ok? true :secret "rotated"})
                :executor-threads 2}
        rec (support/receiver)]
    (with-redefs [providers/build-profile (fn [_ _] profile)]
      (let [port (adapter/create-model-port config)]
        (try
          (contract/start! port (request "request-profile-close")
                           (:receiver rec))
          (is (some? (completed rec)))
          (is (true? @closed?)
              "the request-level profile close-fn runs at the terminal event")
          (finally
            (adapter/stop-port! port)))))))

(deftest explicit-output-index-resolves-a-reused-provider-id
  (let [r0 (identity/new-registry)
        r1 (:registry (identity/register-call
                       r0
                       {:attempt-id "a"
                        :output-index 0
                        :provider-call-id "dup"
                        :tool-name "cat"
                        :arguments {:path "a.txt"}
                        :complete? true}))
        r2 (:registry (identity/register-call
                       r1
                       {:attempt-id "a"
                        :output-index 1
                        :provider-call-id "dup"
                        :tool-name "cat"
                        :arguments {:path "b.txt"}
                        :complete? true}))
        aligned (#'port/align-final-calls
                 r2 "a"
                 [{:provider-call-id "dup"
                   :output-index 1
                   :tool-name "cat"
                   :arguments {:path "b.txt"}}])
        ambiguous (#'port/align-final-calls
                   r2 "a"
                   [{:provider-call-id "dup"
                     :tool-name "cat"
                     :arguments {:path "b.txt"}}])]
    (is (:ok aligned))
    (is (= 1 (:output-index (first (:calls aligned)))))
    (is (false? (:ok ambiguous)))))

(deftest identical-final-tool-calls-are-not-deduped
  (let [script (fn [_ handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request
                                        "p0" "echo" {:x 1})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request "p0" "echo" {:x 1})
                                 (support/tool-request "p1" "echo" {:x 1})]})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "identical-1") (:receiver rec))
      (is (some? (completed rec)))
      (let [calls (support/events-of (:events rec) :tool-call)]
        (is (= 2 (count calls))
            "a second legitimate call with identical arguments stays distinct")
        (is (= #{"identical-1/tool-0" "identical-1/tool-1"}
               (set (map :call-id calls)))))
      (finally
        (adapter/stop-port! port)))))

(deftest reordered-final-call-does-not-collide-with-streamed-index
  (let [script (fn [_ handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request
                                        "p0" "echo" {:x 1})))
                 (.onCompleteResponse
                  handler
                  (support/response
                   {:text "done"
                    :tool-calls [(support/tool-request "p1" "write" {:x 2})
                                 (support/tool-request "p0" "echo" {:x 1})]})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "reordered-1") (:receiver rec))
      (is (some? (completed rec)))
      (let [calls (support/events-of (:events rec) :tool-call)]
        (is (= 2 (count calls)))
        (is (= #{"reordered-1/tool-0" "reordered-1/tool-1"}
               (set (map :call-id calls)))))
      (finally
        (adapter/stop-port! port)))))

(deftest repeated-complete-callback-at-new-index-merges
  (let [script (fn [_ handler]
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 0 (support/tool-request
                                        "p0" "echo" {:x 1})))
                 (.onCompleteToolCall
                  handler
                  (CompleteToolCall. 1 (support/tool-request
                                        "p0" "echo" {:x 1})))
                 (.onCompleteResponse handler
                                      (support/response {:text "done"})))
        port (adapter/create-model-port
              (support/custom-config (support/streaming-model script)
                                     nil #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (contract/start! port (request "repeat-complete-1") (:receiver rec))
      (is (some? (completed rec)))
      (is (= 1 (count (support/events-of (:events rec) :tool-call))))
      (finally
        (adapter/stop-port! port)))))

(deftest cancel-rejects-a-foreign-port-handle
  (let [port (adapter/create-model-port
              (support/custom-config
               (support/streaming-model (fn [_ _]))
               nil #{:tools :streaming}))]
    (try
      (is (= {:ok false :reason :wrong-port}
             (contract/cancel! port {:attempt-id "a"
                                     :call-id "b"
                                     :port-id "another-port"})))
      (finally
        (adapter/stop-port! port)))))

(deftest start-requires-request-identity
  (let [port (adapter/create-model-port
              (support/custom-config
               (support/streaming-model (fn [_ _]))
               nil #{:tools :streaming}))
        rec (support/receiver)]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (contract/start! port
                                    {:attempt-id "a"
                                     :call-id "b"}
                                    (:receiver rec))))
      (finally
        (adapter/stop-port! port)))))

(deftest credential-resolver-errors-do-not-leak-details
  (let [port (adapter/create-model-port
              {:connections
               [{:id "local"
                 :kind :openai-compatible
                 :base-url "http://127.0.0.1:1/v1"
                 :credentials {"c1" {:alias "default"
                                     :secret-ref "vault://c1"}}
                 :models [{:id "m" :capabilities #{:streaming}}]}]
               :roles {:main {:connection "local" :model "m"}}
               :credential-resolver
               (fn [_]
                 {:ok? false
                  :error {:code :no-secret
                          :message "resolver failed"
                          :details {:secret "leak-me"}}})
               :executor-threads 1})
        rec (support/receiver)]
    (try
      (contract/start! port
                       (request "resolver-leak"
                                {:role :main
                                 :messages [{:role :user :text "hi"}]
                                 :stream? true})
                       (:receiver rec))
      (let [result (failed rec)]
        (is (some? result))
        (is (= :credential-resolution-failed
               (get-in result [:error :kind])))
        (is (not (str/includes? (pr-str result) "leak-me"))))
      (finally
        (adapter/stop-port! port)))))

(deftest unused-broken-model-does-not-block-the-role-target
  (let [config (-> (support/custom-config
                    (support/streaming-model
                     (fn [_ handler]
                       (.onCompleteResponse
                        handler (support/response {:text "ok"}))))
                    nil #{:streaming})
                   (update :connections
                           conj
                           {:id "unused"
                            :kind :openai-compatible
                            :base-url "http://127.0.0.1:1/v1"
                            :models [{:id "bad"
                                      :capabilities #{:tools}
                                      :options {:unknown-option true}}]}))
        port (adapter/create-model-port config)]
    (try
      (is (some? (adapter/stats port))
          "only role-referenced models are built")
      (finally
        (adapter/stop-port! port)))))
