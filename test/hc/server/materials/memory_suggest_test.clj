(ns hc.server.materials.memory-suggest-test
  "Memory suggestion: generate -> review -> approve/reject.

  The model is a fake ModelPort that records its one request.  No test starts
  a real provider, and no test lets AiServices run an automatic loop."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]
            [hc.server.materials.memory-suggest :as memory-suggest]
            [hc.server.materials.session :as session]
            [hc.server.model-port.contract :as contract])
  (:import (java.util UUID)))

(defn- service
  []
  (let [store (materials/memory-store)
        svc (session/service store)
        initialized (session/initialize! svc)]
    (is (true? (:ok? initialized)))
    svc))

(defn- command!
  [svc kind payload]
  (session/handle-command! svc
                           {:session {:session-id "s1"}
                            :command-id (str (UUID/randomUUID))
                            :payload (assoc payload :kind kind)}))

(defn- enable!
  [svc]
  (let [result (command! svc :materials/settings/update
                         {:memory-read? true
                          :memory-suggest? true})]
    (is (true? (:ok? result)))
    result))

(defn- current-state
  [svc]
  (:state (materials/load-state (:store svc))))

(defn- fake-port
  ([response calls]
   (reify contract/ModelPort
     (start-request! [_ request receiver]
       (swap! calls conj request)
       (contract/deliver! receiver
                          {:event :completed
                           :attempt-id (:attempt-id request)
                           :text response})
       (contract/handle request))
     (cancel-request! [_ _] nil)))
  ([response] (fake-port response (atom []))))

(defn- scripted-port
  "A port that returns RESPONSES in call order, repeating the last one."
  [responses calls]
  (let [responses (vec responses)]
    (reify contract/ModelPort
      (start-request! [_ request receiver]
        (swap! calls conj request)
        (let [index (dec (count @calls))
              response (nth responses index (peek responses))]
          (contract/deliver! receiver
                             {:event :completed
                              :attempt-id (:attempt-id request)
                              :text response})
          (contract/handle request)))
      (cancel-request! [_ _] nil))))

(def ^:private messages
  [{:message-id "m1" :role :user :content "hello"}
   {:message-id "m2" :role :user
    :content "Please keep answers concise Chinese."}
   {:message-id "m3" :role :assistant :content "Sure."}])

(defn- response
  [candidates]
  (json/write-str candidates))

(defn- candidate
  ([text] (candidate text {}))
  ([text extra]
   (merge {:text text
           :category "expression-preference"
           :epistemic "preference"
           :confidence 0.9
           :source-message-id "m2"
           :quote "concise"}
          extra)))

(defn- suggest!
  [svc port]
  (command! svc :materials/memory/suggest
            {:messages messages
             :source-range {:recent-rounds 2}
             :categories [:identity-background :working-style
                          :expression-preference :long-term-requirement]
             :model-binding {:role :task}
             :model-port port}))

(defn- result-of
  [response]
  (get-in response [:receipt :result]))

(deftest generated-candidate-waits-for-approval
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (fake-port (response [(candidate "Prefers concise Chinese answers.")])
                        calls)
        generated (suggest! svc port)
        result (result-of generated)
        candidate-id (:id (first (:candidates result)))]
    (is (true? (:ok? generated)))
    (is (= 1 (count (:candidates result))))
    (is (= 0 (:skipped result)))
    (is (= "expression-preference"
           (name (:category (first (:candidates result))))))
    (is (= :pending-review (:status (first (:candidates result)))))
    (is (= "m2" (get-in result [:candidates 0 :source :message-id])))
    (is (= [(str/index-of (:content (second messages)) "concise")
            (+ (str/index-of (:content (second messages)) "concise") 7)]
           (get-in result [:candidates 0 :source :span])))
    (is (= :candidate
           (get-in (current-state svc) [:memories candidate-id :status]))
        "generation never activates a candidate")
    (is (= [] (:memories (materials/effective-memories (current-state svc)
                                                       {:assistant-id "a1"}))))

    (testing "approval makes the candidate effective"
      (let [approved (command! svc :materials/memory/approve
                               {:memory-id candidate-id})]
        (is (true? (:ok? approved)))
        (is (= :active
               (get-in (current-state svc) [:memories candidate-id :status])))
        (is (= [candidate-id]
               (mapv :id (:memories
                          (materials/effective-memories (current-state svc)
                                                        {:assistant-id "a1"})))))))))

(deftest rejected-candidate-does-not-affect-future-requests
  (let [svc (service)
        _ (enable! svc)
        port (fake-port (response [(candidate "Prefers concise Chinese answers.")]))
        first-run (suggest! svc port)
        candidate-id (get-in first-run [:receipt :result :candidates 0 :id])]
    (is (true? (:ok? (command! svc :materials/memory/reject
                               {:memory-id candidate-id :reason "not durable"}))))
    (is (= :rejected (get-in (current-state svc) [:memories candidate-id :status])))
    (is (= [] (:memories (materials/effective-memories (current-state svc) {}))))

    (testing "the same suggestion is suppressed after rejection"
      (let [again (suggest! svc port)
            result (result-of again)]
        (is (true? (:ok? again)))
        (is (= [] (:candidates result)))
        (is (= 1 (get-in result [:skipped-reasons :already-rejected])))))))

(deftest assumption-is-preserved
  (let [svc (service)
        _ (enable! svc)
        port (fake-port
              (response [(candidate "The user may prefer dark mode."
                                    {:epistemic "assumption"
                                     :quote "concise"})]))
        generated (suggest! svc port)
        candidate-view (get-in generated [:receipt :result :candidates 0])
        candidate-id (:id candidate-view)]
    (is (true? (:assumption? candidate-view)))
    (is (= :assumption
           (get-in (current-state svc) [:memories candidate-id :epistemic])))
    (is (true? (get-in (current-state svc)
                       [:memories candidate-id :assumption?]))
        "the assumption flag is persisted on the stored record")
    (is (true? (:ok? (command! svc :materials/memory/approve
                               {:memory-id candidate-id}))))
    (is (= :assumption
           (get-in (current-state svc) [:memories candidate-id :epistemic]))
        "approval does not silently promote an assumption to fact")))

(deftest sensitive-content-is-filtered
  (let [svc (service)
        _ (enable! svc)
        port (fake-port
              (response [(candidate "The password = hunter2 for the router.")
                         (candidate "Prefers concise Chinese answers.")]))
        generated (suggest! svc port)
        result (result-of generated)]
    (is (true? (:ok? generated)))
    (is (= ["Prefers concise Chinese answers."]
           (mapv :text (:candidates result))))
    (is (= 1 (get-in result [:skipped-reasons :sensitive])))
    (is (empty? (filter #(str/includes? (str (:body %)) "hunter2")
                        (vals (get-in (current-state svc) [:memories]))))
        "the credential never reaches persisted memory")))

(deftest body-is-truncated-to-two-thousand-characters
  (let [svc (service)
        _ (enable! svc)
        long-text (apply str (repeat 2500 "a"))
        port (fake-port (response [(candidate long-text
                                              {:quote ""})]))
        generated (suggest! svc port)
        candidate-view (get-in generated [:receipt :result :candidates 0])
        stored (get-in (current-state svc)
                       [:memories (:id candidate-view)])]
    (is (true? (:truncated? candidate-view)))
    (is (= 2000 (count (:text candidate-view))))
    (is (= 2000 (count (:body stored))))
    (is (true? (:truncated? stored))
        "the truncation flag survives into the stored candidate")))

(deftest duplicate-suggestion-is-not-repeated
  (let [svc (service)
        _ (enable! svc)
        port (fake-port (response [(candidate "Prefers concise Chinese answers.")]))
        first-run (suggest! svc port)
        second-run (suggest! svc port)]
    (is (= 1 (count (get-in first-run [:receipt :result :candidates]))))
    (is (= [] (get-in second-run [:receipt :result :candidates])))
    (is (= 1 (get-in second-run
                      [:receipt :result :skipped-reasons :already-candidate])))
    (is (= 1 (count (filter #(= :candidate (:status %))
                            (vals (get-in (current-state svc) [:memories])))))
        "a repeated response does not create a second pending candidate")))

(deftest archived-memory-is-not-revived
  (let [svc (service)
        _ (enable! svc)
        remembered (command! svc :materials/memory/remember
                             {:body "Prefers concise Chinese answers."
                              :category :expression-preference
                              :scope :global
                              :epistemic :preference})
        memory-id (get-in remembered [:receipt :result :memory :id])
        _ (command! svc :materials/memory/archive {:memory-id memory-id})
        port (fake-port (response [(candidate "Prefers concise Chinese answers.")]))
        generated (suggest! svc port)
        result (result-of generated)]
    (is (true? (:ok? generated)))
    (is (= [] (:candidates result)))
    (is (= 1 (get-in result [:skipped-reasons :archived])))
    (is (= :archived (get-in (current-state svc) [:memories memory-id :status])))
    (is (= [] (:memories (materials/effective-memories (current-state svc) {}))))
    (is (= 1 (count (get-in (current-state svc) [:memories])))
        "no new candidate is created for archived content")))

(deftest model-port-is-called-once-with-the-task-role
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (fake-port (response [(candidate "Prefers concise Chinese answers.")])
                        calls)
        _ (suggest! svc port)
        request (first @calls)]
    (is (= 1 (count @calls)) "exactly one low-level attempt")
    (is (= :task (get-in request [:snapshot :role])))
    (is (= 2 (count (get-in request [:snapshot :messages]))))
    (is (= :system (get-in request [:snapshot :messages 0 :role])))
    (is (= :user (get-in request [:snapshot :messages 1 :role])))
    (is (str/includes? (get-in request [:snapshot :messages 1 :text])
                       "[m2] user:"))))

(deftest suggestion-switch-closed-is-refused
  (let [svc (service)
        port (fake-port (response [(candidate "Prefers concise Chinese answers.")]))
        result (suggest! svc port)]
    (is (false? (:ok? result)))
    (is (= :MEMORY_SUGGEST_DISABLED (get-in result [:error :code])))))

(deftest parse-accepts-array-object-and-fenced-json
  (let [prepared (memory-suggest/prepare
                  {:session-id "s1"
                   :messages messages})
        c (candidate "Prefers concise Chinese answers.")
        array (json/write-str [c])
        object (json/write-str {:candidates [c]})
        fenced (str "```json\n" array "\n```")]
    (doseq [[label text] [["array" array]
                          ["object" object]
                          ["fenced" fenced]]]
      (testing label
        (let [parsed (memory-suggest/parse prepared text)]
          (is (true? (:ok? parsed)))
          (is (= 1 (count (:candidates parsed))))
          (is (= "Prefers concise Chinese answers."
                 (get-in parsed [:candidates 0 :text]))))))))

(deftest parse-failure-retries-once-and-succeeds
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (scripted-port
              ["not a json array"
               (response [(candidate "Prefers concise Chinese answers.")])]
              calls)
        result (suggest! svc port)
        result-map (result-of result)]
    (is (true? (:ok? result)))
    (is (= 2 (:attempts result-map)))
    (is (= 1 (count (:candidates result-map))))
    (is (= 2 (count @calls)))
    (is (str/includes? (get-in (second @calls) [:snapshot :messages 1 :text])
                       "Previous response")
        "the repair request carries the previous output")
    (is (= 1 (count (filter #(= :candidate (:status %))
                            (vals (get-in (current-state svc) [:memories]))))))))

(deftest parse-failure-after-repair-is-structured
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (scripted-port ["not a json array" "still not a json array"] calls)
        result (suggest! svc port)]
    (is (false? (:ok? result)))
    (is (= :parse-failed (:status result)))
    (is (= :retry (:remedy result)))
    (is (= 2 (:attempts result)))
    (is (= :MEMORY_SUGGEST_RESPONSE_INVALID (get-in result [:error :code])))
    (is (= 2 (count @calls)))
    (is (empty? (get-in (current-state svc) [:memories])))))

(deftest parse-error-redacts-sensitive-raw-output
  (let [prepared (memory-suggest/prepare
                  {:session-id "s1" :messages messages})
        parsed (memory-suggest/parse prepared
                                     "password = hunter2\nnot a json array")]
    (is (false? (:ok? parsed)))
    (is (= :MEMORY_SUGGEST_RESPONSE_INVALID (get-in parsed [:error :code])))
    (is (nil? (get-in parsed [:error :details :raw-excerpt])))
    (is (true? (get-in parsed [:error :details :redacted?])))))

(deftest candidate-overflow-is-truncated-not-dropped
  (let [svc (service)
        _ (enable! svc)
        port (fake-port
              (response (mapv #(candidate (str "Preference " %)) (range 25))))
        result (result-of (suggest! svc port))]
    (is (= 20 (count (:candidates result))))
    (is (true? (:truncated? result)))
    (is (= 5 (:dropped-candidates result)))
    (is (= 20 (count (filter #(= :candidate (:status %))
                             (vals (get-in (current-state svc)
                                           [:memories]))))))))

(deftest prepare-rejects-unknown-categories-before-the-model-call
  (let [prepared (memory-suggest/prepare
                  {:session-id "s1"
                   :messages messages
                   :categories [:unknown]})]
    (is (false? (:ok? prepared)))
    (is (= :MEMORY_SUGGEST_CATEGORIES_INVALID (get-in prepared [:error :code])))))

(defn- session-message
  [id role text]
  {:message-id id
   :role role
   :versions [{:version-id (str id "-v0") :content text}]
   :current-version 0})

(defn- session-with-messages
  []
  {:session-id "s1"
   :message-order ["m1" "m2" "m3"]
   :messages {"m1" (session-message "m1" :user "old context")
              "m2" (session-message "m2" :user
                                    "Please keep answers concise Chinese.")
              "m3" (session-message "m3" :assistant "Sure.")}})

(defn- suggest-from-session!
  [svc session port & [extra]]
  (session/handle-command!
   svc
   {:session session
    :command-id (str (UUID/randomUUID))
    :payload (merge {:kind :materials/memory/suggest
                     :source-range {:recent-rounds 1}
                     :model-binding {:role :task}
                     :model-port port}
                    extra)}))

(deftest suggest-reads-recent-rounds-from-the-session
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (fake-port (response [(candidate "Prefers concise Chinese answers.")])
                        calls)
        result (suggest-from-session! svc (session-with-messages) port)
        prompt (get-in (first @calls) [:snapshot :messages 1 :text])]
    (is (true? (:ok? result)))
    (is (= ["m2" "m3"]
           (get-in result [:receipt :result :included-message-ids]))
        "one user round keeps its following assistant message")
    (is (str/includes? prompt "[m2] user:"))
    (is (str/includes? prompt "[m3] assistant:"))
    (is (not (str/includes? prompt "[m1]"))
        "older rounds stay out of the transcript")
    (is (= "m2" (get-in result [:receipt :result :candidates 0
                               :source :message-id])))))

(deftest session-read-reports-transcript-truncation
  (let [svc (service)
        _ (enable! svc)
        session (assoc (session-with-messages)
                       :messages
                       {"m1" (session-message "m1" :user "first")
                        "m2" (session-message "m2" :user "second")
                        "m3" (session-message "m3" :user "third")})
        port (fake-port (response [(candidate "Prefers concise Chinese answers."
                                              {:source-message-id "m3"
                                               :quote ""})]))
        result (suggest-from-session! svc session port
                                      {:source-range {:recent-rounds 3}
                                       :max-transcript-chars 20})]
    (is (true? (:ok? result)))
    (is (pos? (get-in result [:receipt :result :dropped-message-count])))
    (is (= ["m3"]
           (get-in result [:receipt :result :included-message-ids]))
        "the newest message is kept even when older ones do not fit")))

(deftest near-duplicate-punctuation-is-suppressed
  (let [svc (service)
        _ (enable! svc)
        first-port (fake-port
                    (response [(candidate "Prefers concise Chinese answers.")]))
        first-run (suggest! svc first-port)
        second-port (fake-port
                     (response [(candidate "Prefers concise Chinese answers"
                                           {:quote ""})]))
        second-run (suggest! svc second-port)
        result (result-of second-run)]
    (is (= 1 (count (get-in first-run [:receipt :result :candidates]))))
    (is (= [] (:candidates result)))
    (is (= 1 (get-in result [:skipped-reasons :already-candidate]))
        "punctuation-only difference does not create a second candidate")))

(deftest near-duplicate-active-memory-is-suppressed
  (let [svc (service)
        _ (enable! svc)
        _ (command! svc :materials/memory/remember
                    {:body "Prefers concise Chinese answers"
                     :category :expression-preference
                     :scope :global
                     :epistemic :preference})
        port (fake-port
              (response [(candidate "Prefers concise Chinese answers.")]))
        result (result-of (suggest! svc port))]
    (is (= [] (:candidates result)))
    (is (= 1 (get-in result [:skipped-reasons :already-active])))))

(defn- suggest-with!
  [svc session port extra]
  (session/handle-command!
   svc
   {:session session
    :command-id (str (UUID/randomUUID))
    :payload (merge {:kind :materials/memory/suggest
                     :messages messages
                     :source-range {:recent-rounds 2}
                     :model-port port}
                    extra)}))

(deftest suggest-defaults-to-the-utility-task-role
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (fake-port
              (response [(candidate "Prefers concise Chinese answers.")])
              calls)
        result (suggest-with! svc {:session-id "s1"} port {})]
    (is (true? (:ok? result)))
    (is (= :task (get-in (first @calls) [:snapshot :role]))
        "an absent binding uses the adapter's task role, never main")))

(deftest session-main-binding-is-not-borrowed-silently
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (fake-port
              (response [(candidate "Prefers concise Chinese answers.")])
              calls)
        session {:session-id "s1"
                 :model-binding {:connection-id "conn-session"
                                 :model-entry-id "model-session"}}
        result (suggest-with! svc session port {})]
    (is (true? (:ok? result)))
    (is (= :task (get-in (first @calls) [:snapshot :role]))
        "a conversation main binding is not reused for a utility task")))

(deftest utility-role-maps-to-the-task-role
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (fake-port
              (response [(candidate "Prefers concise Chinese answers.")])
              calls)
        result (suggest-with! svc {:session-id "s1"} port
                              {:model-binding {:role :utility}})]
    (is (true? (:ok? result)))
    (is (= :task (get-in (first @calls) [:snapshot :role]))
        "the utility role uses the adapter's task target")))

(deftest missing-model-port-gives-an-actionable-error
  (let [svc (service)
        _ (enable! svc)
        result (suggest-with! svc {:session-id "s1"} nil {})]
    (is (false? (:ok? result)))
    (is (= :MEMORY_SUGGEST_MODEL_UNCONFIGURED (get-in result [:error :code])))
    (is (str/includes? (get-in result [:error :message])
                       "utility/task model"))
    (is (= :configure-utility-model
           (get-in result [:error :details :action])))
    (is (= :task (get-in result [:error :details :role])))))

(defn- role-aware-port
  "Refuse the task role, accept the main role.  Records every request."
  [response calls]
  (reify contract/ModelPort
    (start-request! [_ request receiver]
      (swap! calls conj request)
      (if (= :task (get-in request [:snapshot :role]))
        (contract/deliver! receiver
                           {:event :failed
                            :attempt-id (:attempt-id request)
                            :error {:kind :unknown-role
                                    :role :task}})
        (contract/deliver! receiver
                           {:event :completed
                            :attempt-id (:attempt-id request)
                            :text response}))
      (contract/handle request))
    (cancel-request! [_ _] nil)))

(deftest unavailable-task-role-is-refused-without-main-fallback
  (let [svc (service)
        _ (enable! svc)
        calls (atom [])
        port (role-aware-port
              (response [(candidate "Prefers concise Chinese answers.")])
              calls)
        result (suggest-with! svc {:session-id "s1"} port
                              {:model-binding {:role :task}})]
    (is (false? (:ok? result)))
    (is (= :MEMORY_SUGGEST_MODEL_UNCONFIGURED (get-in result [:error :code])))
    (is (= [:task] (mapv #(get-in % [:snapshot :role]) @calls))
        "the unavailable task role is never retried on the main model")
    (is (= :configure-utility-model
           (get-in result [:error :details :action])))))
