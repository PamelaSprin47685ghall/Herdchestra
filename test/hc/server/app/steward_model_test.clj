(ns hc.server.app.steward-model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.core.ufss :as ufss]
            [hc.server.app.prompts :as prompts]
            [hc.server.app.steward-model :as steward-model]
            [hc.server.model-port.contract :as contract]
            [hc.server.program.capability :as capability]))

(defn- fake-port
  [respond]
  (let [calls (atom 0)
        requests (atom [])]
    {:port
     (reify contract/ModelPort
       (start-request! [_ request receiver]
         (swap! calls inc)
         (swap! requests conj request)
         (let [outcome (respond request @calls)]
           (cond
             (= :timeout outcome) nil
             (instance? Throwable outcome) (throw outcome)
             :else
             (contract/deliver-event!
              receiver
              (assoc outcome
                     :attempt-id (:attempt-id request)
                     :call-id (:call-id request)))))
         (contract/handle request))
       (cancel-request! [_ _] {:ok true}))
     :calls calls
     :requests requests}))

(defn- fake-gateway
  [respond]
  (let [calls (atom 0)
        requests (atom [])]
    {:port
     {:start! (fn [request deliver!]
                (swap! calls inc)
                (swap! requests conj request)
                (let [outcome (respond request @calls)]
                  (cond
                    (= :timeout outcome) nil
                    (instance? Throwable outcome) (throw outcome)
                    :else
                    (deliver! (assoc outcome
                                     :attempt-id (:attempt-id request)
                                     :call-id (:call-id request)))))
                {:handle :fake-gateway})
      :cancel! (fn [_] {:ok true})}
     :calls calls
     :requests requests}))

(defn- meditate-effect
  ([] (meditate-effect {}))
  ([overrides]
   (merge {:type :model/meditate
           :role :meditator
           :evidence-ids ["git:a"]
           :evidence-count 1
           :evidence-seq 1
           :prompt-version 1
           :at 1000}
          overrides)))

(defn- decide-effect
  ([] (decide-effect {}))
  ([overrides]
   (merge {:type :model/decide
           :role :steward
           :frontier-id "frontier-1"
           :frontier {:frontier-id "frontier-1"
                      :reasons [:operator-intent]}
           :cba {:cognition-version 2
                 :evidence-ids ["git:a"]
                 :evidence {"git:a" {:evidence-id "git:a"
                                     :summary "commit a"}}
                 :best-answer {:cognition-version 2
                               :non-final? true}}
           :candidates-hint {:operator-intent "Improve the repository"}
           :prompt-version 1
           :at 2000}
          overrides)))

(defn- meditate-text
  ([] (meditate-text "A better answer"))
  ([summary]
   (ufss/write-str
    {:revision {:summary summary
                :frontier-of-thought "The evidence is still thin."
                :confidence 0.6
                :assumptions []
                :claims []}})))

(defn- decide-text
  ([] (decide-text "request-1"))
  ([request-id]
   (ufss/write-str
    {:request-id request-id
     :candidates
     [{:candidate-id "candidate-1"
       :title "Improve reliability"
       :goal "Make the failure path observable"
       :consequence 0.9
       :evidence 0.8
       :reversibility 0.9
       :dependency 0.1
       :opportunity 0.1
       :demand 1}]})))

(defn- candidate-map
  []
  {:candidate-id "candidate-1"
   :title "Improve reliability"
   :consequence 0.9
   :evidence 0.8
   :reversibility 0.9
   :dependency 0.1
   :opportunity 0.1
   :demand 1})

(defn- model
  [port & [opts]]
  (steward-model/model-fn
   (merge {:port port
           :max-attempts 2
           :timeout-ms 2000
           :retry-policy {:base-delay-ms 0
                          :max-delay-ms 0
                          :jitter-ms 0}}
          opts)))

(defn- call-with-event
  [event]
  (let [{:keys [port]} (fake-port (fn [_ _] event))]
    ((model port) (meditate-effect))))

(defn- request-messages
  [request]
  (get-in request [:snapshot :messages]))

(defn- user-payload
  [request]
  (let [text (:text (second (request-messages request)))]
    (ufss/read-str-safe text {:json true})))

(deftest valid-edn-results
  (testing "a meditator revision becomes :revision"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text (meditate-text "A better answer")}))
          result ((model port) (meditate-effect))]
      (is (true? (:ok? result)))
      (is (= "A better answer"
             (get-in result [:value :revision :summary])))
      (is (= {:prompt-tokens :unknown
              :completion-tokens :unknown
              :source :unknown}
             (:usage result)))))
  (testing "a steward decision becomes normalized candidates"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text (decide-text "request-1")}))
          result ((model port) (decide-effect))
          value (:value result)
          candidate (first (:candidates value))]
      (is (true? (:ok? result)))
      (is (= "request-1" (:request-id value)))
      (is (= "frontier-1" (:frontier-id value)))
      (is (= "candidate-1" (:candidate-id candidate)))
      (is (= "Improve reliability" (:title candidate)))
      (is (= "Make the failure path observable" (:summary candidate)))
      (is (= 0.9 (:consequence candidate)))
      (is (= 0.8 (:evidence-strength candidate)))
      (is (= 0.1 (:dependency-cost candidate)))
      (is (= 0.1 (:opportunity-cost candidate)))
      (is (= 1 (:demand candidate)))
      (is (= ["git:a"] (:evidence-ids candidate)))
      (is (not (contains? value :policy))))))

(deftest json-and-escaped-text-still-parse
  (testing "bare JSON is accepted"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text "{\"revision\":{\"summary\":\"JSON answer\",\"frontier-of-thought\":\"json frontier\"}}"}))
          result ((model port) (meditate-effect))]
      (is (true? (:ok? result)))
      (is (= "JSON answer"
             (get-in result [:value :revision :summary])))))
  (testing "escaped JSON quotes are accepted"
    (let [text "{\"revision\":{\"summary\":\"Quoted \\\"value\\\"\",\"frontier-of-thought\":\"json frontier\"}}"
          {:keys [port]} (fake-port
                          (fn [_ _] {:event :completed :text text}))
          result ((model port) (meditate-effect))]
      (is (true? (:ok? result)))
      (is (= "Quoted \"value\""
             (get-in result [:value :revision :summary])))))
  (testing "a truncated response is unparseable"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text "{:revision {:summary"}))
          result ((model port) (meditate-effect))]
      (is (false? (:ok? result)))
      (is (= :STEWARD_MODEL_UNPARSEABLE
             (get-in result [:error :code])))
      (is (= "{:revision {:summary"
             (get-in result [:error :details :raw]))))))

(deftest exactly-one-form-is-required
  (let [text (str (meditate-text "one") " " (meditate-text "two"))
        {:keys [port]} (fake-port
                        (fn [_ _] {:event :completed :text text}))
        result ((model port) (meditate-effect))]
    (is (false? (:ok? result)))
    (is (= :STEWARD_MODEL_UNPARSEABLE (get-in result [:error :code])))))

(deftest unparseable-vs-invalid-shape-boundary
  (let [cases
        [{:name "truncated EDN"
          :text "{:revision"
          :expected :STEWARD_MODEL_UNPARSEABLE}
         {:name "prose"
          :text "not ednn at all"
          :expected :STEWARD_MODEL_UNPARSEABLE}
         {:name "readable empty map"
          :text "{}"
          :expected :STEWARD_MODEL_INVALID_SHAPE}
         {:name "readable wrong revision type"
          :text "{:revision 1}"
          :expected :STEWARD_MODEL_INVALID_SHAPE}]]
    (doseq [{:keys [name text expected]} cases]
      (let [result (call-with-event
                    {:event :completed :text text})]
        (is (= expected (get-in result [:error :code])) name))))
  (testing "readable decide map with missing candidates"
    (let [{:keys [port]}
          (fake-port
           (fn [_ _]
             {:event :completed
              :text (ufss/write-str {:request-id "x"})}))
          result ((model port) (decide-effect))]
      (is (= :STEWARD_MODEL_INVALID_SHAPE
             (get-in result [:error :code]))))))

(deftest invalid-shapes-are-refused
  (testing "missing :revision"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed :text (ufss/write-str {})}))
          result ((model port) (meditate-effect))]
      (is (false? (:ok? result)))
      (is (= :STEWARD_MODEL_INVALID_SHAPE
             (get-in result [:error :code])))
      (is (= [:revision]
             (get-in result [:error :details :missing])))))
  (testing "an unexpected meditate key"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text (ufss/write-str
                                    {:revision {:summary "s"
                                                :frontier-of-thought "f"}
                                     :note "extra"})}))
          result ((model port) (meditate-effect))]
      (is (false? (:ok? result)))
      (is (= [:note]
             (get-in result [:error :details :unexpected])))))
  (testing "missing :candidates"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text (ufss/write-str {:request-id "only"})}))
          result ((model port) (decide-effect))]
      (is (false? (:ok? result)))
      (is (= :STEWARD_MODEL_INVALID_SHAPE
             (get-in result [:error :code])))
      (is (= [:candidates]
             (get-in result [:error :details :missing])))))
  (testing "an invalid candidate field"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text (ufss/write-str
                                    {:request-id "bad"
                                     :candidates
                                     [{:candidate-id "c1"
                                       :title "Bad"
                                       :consequence "high"
                                       :evidence 0.5
                                       :reversibility 0.5}]})}))
          result ((model port) (decide-effect))]
      (is (false? (:ok? result)))
      (is (= :STEWARD_MODEL_INVALID_SHAPE
             (get-in result [:error :code])))
      (is (= []
             (get-in result [:error :details :missing])))
      (is (= [:consequence]
             (get-in result [:error :details :invalid]))))))

(deftest invalid-shape-envelopes-are-uniform
  (let [cases
        [{:name "missing :candidates"
          :text (ufss/write-str {:request-id "r"})
          :missing [:candidates]}
         {:name "candidate extra key"
          :text (ufss/write-str
                 {:request-id "r"
                  :candidates [(assoc (candidate-map) :extra true)]})
          :unexpected [:extra]}
         {:name "candidate missing field"
          :text (ufss/write-str
                 {:request-id "r"
                  :candidates [(dissoc (candidate-map) :consequence)]})
          :missing [:consequence]}
         {:name "candidate invalid field"
          :text (ufss/write-str
                 {:request-id "r"
                  :candidates [(assoc (candidate-map)
                                      :consequence "high")]})
          :invalid [:consequence]}]]
    (doseq [{:keys [name text missing invalid unexpected]} cases]
      (let [{:keys [port]} (fake-port
                            (fn [_ _] {:event :completed :text text}))
            result ((model port) (decide-effect))
            details (get-in result [:error :details])]
        (is (false? (:ok? result)) name)
        (is (= #{:ok? :error :usage} (set (keys result))) name)
        (is (= #{:code :message :details} (set (keys (:error result)))) name)
        (is (= :STEWARD_MODEL_INVALID_SHAPE
               (get-in result [:error :code])) name)
        (is (every? #(contains? details %)
                    [:task :missing :invalid :unexpected]) name)
        (when missing
          (is (= missing (:missing details)) name))
        (when invalid
          (is (= invalid (:invalid details)) name))
        (when unexpected
          (is (= unexpected (:unexpected details)) name))))))

(deftest retryable-and-permanent-errors
  (testing "429 is retried and the second attempt succeeds"
    (let [{:keys [port calls]}
          (fake-port
           (fn [_ attempt]
             (if (= 1 attempt)
               {:event :failed
                :error {:status 429
                        :category :rate-limited
                        :message "rate limited"}}
               {:event :completed :text (meditate-text)})))
          result ((model port {:max-attempts 2})
                  (meditate-effect))]
      (is (true? (:ok? result)))
      (is (= 2 @calls))))
  (testing "401 is not retried"
    (let [{:keys [port calls]}
          (fake-port
           (fn [_ _]
             {:event :failed
              :error {:status 401 :message "bad key"}}))
          result ((model port {:max-attempts 3})
                  (decide-effect))]
      (is (false? (:ok? result)))
      (is (= 1 @calls))
      (is (= :STEWARD_MODEL_AUTH (get-in result [:error :code])))))
  (testing "a missing model is not retried"
    (let [{:keys [port calls]}
          (fake-port
           (fn [_ _]
             {:event :failed
              :error {:kind :model-not-found
                      :message "no such model"}}))
          result ((model port {:max-attempts 3})
                  (meditate-effect))]
      (is (false? (:ok? result)))
      (is (= 1 @calls))
      (is (= :STEWARD_MODEL_NOT_FOUND (get-in result [:error :code])))))
  (testing "an oversized input is not retried"
    (let [{:keys [port calls]}
          (fake-port
           (fn [_ _]
             {:event :failed
              :error {:status 413 :message "too large"}}))
          result ((model port {:max-attempts 3})
                  (meditate-effect))]
      (is (false? (:ok? result)))
      (is (= 1 @calls))
      (is (= :STEWARD_MODEL_CONTEXT_TOO_LONG
             (get-in result [:error :code]))))))

(deftest timeout-is-bounded
  (let [{:keys [port calls]} (fake-port (fn [_ _] :timeout))
        result ((model port {:timeout-ms 20
                             :max-attempts 1})
                (meditate-effect))]
    (is (false? (:ok? result)))
    (is (= 1 @calls)
        "the retry cap and total budget admit one attempt")
    (is (= :STEWARD_MODEL_TIMEOUT (get-in result [:error :code])))))

(deftest repeated-effect-reuses-the-successful-result
  (let [{:keys [port calls requests]}
        (fake-port (fn [_ _] {:event :completed :text (decide-text)}))
        call (model port)
        first (call (decide-effect))
        second (call (decide-effect))]
    (is (true? (:ok? first)))
    (is (= first second))
    (is (= 1 @calls))
    (is (= 1 (count @requests)))
    (testing "a different frontier is a different call"
      (let [third (call (decide-effect {:frontier-id "frontier-2"}))]
        (is (true? (:ok? third)))
        (is (= 2 @calls))))))

(deftest usage-is-never-zero-when-missing
  (testing "a completed event without usage reports unknown"
    (let [{:keys [port]} (fake-port
                          (fn [_ _] {:event :completed
                                     :text (meditate-text)}))
          result ((model port) (meditate-effect))]
      (is (= {:prompt-tokens :unknown
              :completion-tokens :unknown
              :source :unknown}
             (:usage result)))))
  (testing "a reported usage is mapped to prompt/completion"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text (meditate-text)
                             :usage {:usage {:input-tokens 11
                                             :output-tokens 7}
                                     :sources {:input-tokens :reported
                                               :output-tokens :reported}}}))
          result ((model port) (meditate-effect))]
      (is (= {:prompt-tokens 11
              :completion-tokens 7
              :source :provider}
             (:usage result)))))
  (testing "a partial usage keeps the unknown half unknown"
    (let [{:keys [port]} (fake-port
                          (fn [_ _]
                            {:event :completed
                             :text (meditate-text)
                             :usage {:input-tokens 3}}))
          result ((model port) (meditate-effect))]
      (is (= {:prompt-tokens 3
              :completion-tokens :unknown
              :source :provider}
             (:usage result))))))

(deftest usage-missing-fields-are-unknown
  (let [unknown {:prompt-tokens :unknown
                 :completion-tokens :unknown
                 :source :unknown}
        cases
        [{:name "no usage"
          :event {:event :completed :text (meditate-text)}
          :expected unknown}
         {:name "nil usage"
          :event {:event :completed :text (meditate-text) :usage nil}
          :expected unknown}
         {:name "empty usage"
          :event {:event :completed :text (meditate-text) :usage {}}
          :expected unknown}
         {:name "explicit unknown tokens"
          :event {:event :completed
                  :text (meditate-text)
                  :usage {:input-tokens :unknown
                          :output-tokens :unknown}}
          :expected unknown}
         {:name "only prompt tokens"
          :event {:event :completed
                  :text (meditate-text)
                  :usage {:input-tokens 3}}
          :expected {:prompt-tokens 3
                     :completion-tokens :unknown
                     :source :provider}}
         {:name "only completion tokens"
          :event {:event :completed
                  :text (meditate-text)
                  :usage {:output-tokens 4}}
          :expected {:prompt-tokens :unknown
                     :completion-tokens 4
                     :source :provider}}
         {:name "report-shaped without sources"
          :event {:event :completed
                  :text (meditate-text)
                  :usage {:usage {:input-tokens 4
                                  :output-tokens 2}}}
          :expected {:prompt-tokens 4
                     :completion-tokens 2
                     :source :unknown}}
         {:name "estimated provenance is not provider"
          :event {:event :completed
                  :text (meditate-text)
                  :usage {:usage {:input-tokens 4}
                          :sources {:input-tokens :estimated}}}
          :expected {:prompt-tokens 4
                     :completion-tokens :unknown
                     :source :unknown}}
         {:name "a confirmed zero is a real value"
          :event {:event :completed
                  :text (meditate-text)
                  :usage {:input-tokens 0 :output-tokens 0}}
          :expected {:prompt-tokens 0
                     :completion-tokens 0
                     :source :provider}}]]
    (doseq [{:keys [name event expected]} cases]
      (let [result (call-with-event event)
            usage (:usage result)]
        (is (= expected usage) name)
        (doseq [token [(:prompt-tokens usage)
                       (:completion-tokens usage)]]
          (is (or (= :unknown token)
                  (and (integer? token) (not (neg? token))))
              name)
          (when (= :unknown token)
            (is (not (contains? #{0 0.0 "0"} token)) name)
            (is (not (nil? token)) name)))
        (is (contains? #{:provider :unknown} (:source usage)) name)
        (is (not= :estimated (:source usage)) name)))))

(deftest usage-failure-paths-stay-unknown
  (testing "unparseable without usage"
    (let [result (call-with-event {:event :completed :text "{:revision"})]
      (is (= :STEWARD_MODEL_UNPARSEABLE
             (get-in result [:error :code])))
      (is (= {:prompt-tokens :unknown
              :completion-tokens :unknown
              :source :unknown}
             (:usage result)))))
  (testing "invalid shape without usage"
    (let [result (call-with-event
                  {:event :completed :text (ufss/write-str {})})]
      (is (= :STEWARD_MODEL_INVALID_SHAPE
             (get-in result [:error :code])))
      (is (= {:prompt-tokens :unknown
              :completion-tokens :unknown
              :source :unknown}
             (:usage result)))))
  (testing "timeout"
    (let [{:keys [port]} (fake-port (fn [_ _] :timeout))
          result ((model port {:timeout-ms 20 :max-attempts 1})
                  (meditate-effect))]
      (is (= :STEWARD_MODEL_TIMEOUT
             (get-in result [:error :code])))
      (is (= {:prompt-tokens :unknown
              :completion-tokens :unknown
              :source :unknown}
             (:usage result)))))
  (testing "authentication failure without usage"
    (let [result (call-with-event
                  {:event :failed
                   :error {:status 401 :message "bad key"}})]
      (is (= :STEWARD_MODEL_AUTH (get-in result [:error :code])))
      (is (= {:prompt-tokens :unknown
              :completion-tokens :unknown
              :source :unknown}
             (:usage result)))))
  (testing "a known usage survives a parse failure"
    (let [result (call-with-event
                  {:event :completed
                   :text "{:revision"
                   :usage {:usage {:input-tokens 5 :output-tokens 2}
                           :sources {:input-tokens :reported
                                     :output-tokens :reported}}})]
      (is (= :STEWARD_MODEL_UNPARSEABLE
             (get-in result [:error :code])))
      (is (= {:prompt-tokens 5
              :completion-tokens 2
              :source :provider}
             (:usage result))))))

(deftest retry-success-without-final-usage-stays-unknown
  (let [{:keys [port calls]}
        (fake-port
         (fn [_ attempt]
           (if (= 1 attempt)
             {:event :failed
              :error {:status 500 :message "boom"}
              :usage {:input-tokens 9 :output-tokens 4}}
             {:event :completed :text (meditate-text)})))
        result ((model port {:max-attempts 2}) (meditate-effect))]
    (is (true? (:ok? result)))
    (is (= 2 @calls))
    (is (= {:prompt-tokens :unknown
            :completion-tokens :unknown
            :source :unknown}
           (:usage result))
        "the final successful attempt has no usage; do not reuse or invent tokens")))

(deftest usage-on-cache-hit-is-the-original-cost
  (let [{:keys [port calls]}
        (fake-port
         (fn [_ _]
           {:event :completed
            :text (meditate-text)
            :usage {:usage {:input-tokens 11 :output-tokens 7}
                    :sources {:input-tokens :reported
                              :output-tokens :reported}}}))
        call (model port)
        first (call (meditate-effect))
        second (call (meditate-effect))]
    (is (true? (:ok? first)))
    (is (= 1 @calls))
    (is (= first second))
    (is (= {:prompt-tokens 11
            :completion-tokens 7
            :source :provider}
           (:usage first)))
    (is (= (:usage first) (:usage second))
        "a cache hit reuses the single call's usage; it does not add tokens")
    (is (= 11 (get-in second [:usage :prompt-tokens])))
    (is (= 7 (get-in second [:usage :completion-tokens])))))

(deftest prompts-are-strict-edn
  (testing "meditate uses the meditator system prompt"
    (let [{:keys [port requests]}
          (fake-port (fn [_ _] {:event :completed
                                :text (meditate-text)}))
          _ ((model port) (meditate-effect))
          request (first @requests)
          messages (request-messages request)
          system (:text (first messages))
          user (:text (second messages))]
      (is (str/includes? system "<Meditation>"))
      (is (= prompts/role-prompt-marker
             (subs system 0 (count prompts/role-prompt-marker))))
      (is (str/includes? system "Do not run a program"))
      (is (not (str/includes? user "\\\"")))
      (is (str/includes? user ":output-shape"))
      (let [parsed (ufss/read-str-safe user {:json true})]
        (is (map? (:ok parsed))))
      (is (= 1 (count (filter #(str/includes? (:text %) "<Meditation>")
                              (filter #(= :system (:role %)) messages)))))))
  (testing "decide uses the steward system prompt"
    (let [{:keys [port requests]}
          (fake-port (fn [_ _] {:event :completed
                                :text (decide-text)}))
          _ ((model port) (decide-effect))
          request (first @requests)
          messages (request-messages request)
          system (:text (first messages))
          user (:text (second messages))
          payload (:ok (user-payload request))]
      (is (str/includes? system "<Stewardship>"))
      (is (= prompts/role-prompt-marker
             (subs system 0 (count prompts/role-prompt-marker))))
      (is (str/includes? system "Do not run a program"))
      (is (str/includes? user ":candidate-shape"))
      (is (str/includes? user ":output-shape"))
      (is (= :decide (:task payload)))
      (is (= "frontier-1" (get-in payload [:frontier :frontier-id])))
      (is (= "git:a" (get-in payload [:cba :evidence-ids 0])))
      (is (map? (get-in payload [:output-shape])))
      (is (= 1 (count (filter #(str/includes? (:text %) "<Stewardship>")
                              (filter #(= :system (:role %)) messages))))))))

(deftest snapshot-carries-program-role-and-tools
  (testing "the Meditator snapshot has no tool face"
    (let [{:keys [port requests]}
          (fake-gateway (fn [_ _] {:event :completed
                                   :text (meditate-text)}))
          _ ((model port) (meditate-effect))
          snapshot (get-in (first @requests) [:snapshot])]
      (is (= :clj-meditator (:program-role snapshot)))
      (is (= :meditator (:role snapshot)))
      (is (= :main (:model-role snapshot)))
      (is (= [] (:tools snapshot)))))
  (testing "the Steward tool face derives from the capability set"
    (let [{:keys [port requests]}
          (fake-gateway (fn [_ _] {:event :completed
                                   :text (decide-text)}))
          _ ((model port) (decide-effect))
          snapshot (get-in (first @requests) [:snapshot])
          expected (set (map name (capability/capabilities :clj-steward)))]
      (is (= :clj-steward (:program-role snapshot)))
      (is (= :steward (:role snapshot)))
      (is (= :main (:model-role snapshot)))
      (is (= expected (set (map :name (:tools snapshot))))))))

(deftest readers-enrich-the-prompt
  (let [seen (atom nil)
        {:keys [port requests]}
        (fake-port (fn [request _]
                     (reset! seen request)
                     {:event :completed :text (meditate-text)}))
        call (model port
                    {:evidence-reader
                     (fn [_]
                       [{:evidence-id "git:a" :summary "commit a"}])
                     :cba-reader
                     (fn [_]
                       {:cognition-version 2
                        :evidence-ids ["git:a"]
                        :evidence {"git:a" {:evidence-id "git:a"
                                            :summary "commit a"}}
                        :summary "current answer"})
                     :frontier-reader
                     (fn [_] {:frontier-id "reader-frontier"})})
        result (call (meditate-effect))
        payload (:ok (user-payload (first @requests)))]
    (is (true? (:ok? result)))
    (is (some? @seen))
    (is (= "commit a" (get-in payload [:evidence 0 :summary])))
    (is (= "current answer" (get-in payload [:cba :summary])))
    (is (= "reader-frontier"
           (get-in payload [:frontier :frontier-id])))))

(deftest reader-refusal-is-a-structured-failure
  (let [{:keys [port calls]}
        (fake-port (fn [_ _] {:event :completed :text (meditate-text)}))
        result ((model port
                       {:evidence-reader
                        (fn [_]
                          {:ok? false
                           :error {:code :EVIDENCE_OFFLINE
                                   :message "no evidence store"}})})
                (meditate-effect))]
    (is (false? (:ok? result)))
    (is (= 0 @calls))
    (is (= :EVIDENCE_OFFLINE (get-in result [:error :code])))
    (is (= :unknown (get-in result [:usage :prompt-tokens])))))

(deftest effect-contract-guards
  (let [{:keys [port]} (fake-port
                        (fn [_ _] {:event :completed
                                   :text (meditate-text)}))
        call (model port)]
    (is (false? (:ok? (call :not-a-map))))
    (is (= :STEWARD_MODEL_INVALID_EFFECT
           (get-in (call :not-a-map) [:error :code])))
    (is (= :STEWARD_MODEL_UNKNOWN_EFFECT
           (get-in (call {:type :model/unknown}) [:error :code])))
    (is (= :STEWARD_MODEL_INVALID_EFFECT
           (get-in (call (meditate-effect {:role :steward}))
                   [:error :code])))
    (is (= :STEWARD_MODEL_PROMPT_VERSION
           (get-in (call (meditate-effect {:prompt-version 2}))
                   [:error :code])))))

(deftest decoration-adds-each-role-prompt-exactly-once
  (doseq [[effect response tag]
          [[(meditate-effect) (meditate-text) "<Meditation>"]
           [(decide-effect) (decide-text) "<Stewardship>"]]]
    (let [{:keys [port requests]}
          (fake-gateway (fn [_ _] {:event :completed :text response}))
          _ ((model port) effect)
          request (first @requests)
          decorated (prompts/decorate-request request)
          systems (->> (get-in decorated [:snapshot :messages])
                       (filter #(= :system (:role %)))
                       (map :text))
          occurrences (count (filter #(str/includes? % tag) systems))]
      (is (= 1 occurrences))
      (let [twice (prompts/decorate-request decorated)
            systems' (->> (get-in twice [:snapshot :messages])
                          (filter #(= :system (:role %)))
                          (map :text))]
        (is (= 1 (count (filter #(str/includes? % tag) systems'))))))))

(deftest decoration-without-program-role-is-a-no-op
  (let [snapshot {:role :main
                  :messages [{:role :user :text "plain text"}]
                  :tools []}
        request {:snapshot snapshot}
        decorated (prompts/decorate-request request {:tools-supported? true})]
    (is (= request decorated))
    (is (not (str/includes? (pr-str decorated) "<Stewardship>")))
    (is (not (str/includes? (pr-str decorated) "<Meditation>")))))

(deftest prompt-overrides-are-used
  (let [{:keys [port requests]}
        (fake-port (fn [_ _] {:event :completed :text (meditate-text)}))
        _ ((model port {:prompts {:meditator "CUSTOM MEDITATOR PROMPT"}})
           (meditate-effect))
        messages (request-messages (first @requests))
        systems (filter #(= :system (:role %)) messages)
        system (:text (first systems))]
    (is (= prompts/role-prompt-marker
           (subs system 0 (count prompts/role-prompt-marker))))
    (is (str/includes? system "CUSTOM MEDITATOR PROMPT"))
    (is (= 1 (count (filter #(str/includes? (:text %)
                                             "CUSTOM MEDITATOR PROMPT")
                            systems))))))

(deftest effect-fingerprint-is-model-only
  (let [meditate {:type :model/meditate
                  :role :meditator
                  :evidence-seq 7
                  :evidence-count 2
                  :evidence-ids ["e1" "e2"]
                  :prompt-version 3
                  :at 1000}
        decide {:type :model/decide
                :role :steward
                :frontier-id "f1"
                :request-id "r1"
                :prompt-version 3
                :at 1000}
        outside [{:type :report/emit}
                 {:type :plan/start}
                 {:type :fleet/fork}
                 {:type :diagnostic}
                 {:type :persist}]]
    (testing "model keys stay byte-identical and ignore :at"
      (is (= [:model/meditate :meditator 7 2 ["e1" "e2"] 3]
             (steward-model/effect-fingerprint meditate)))
      (is (= [:model/decide :steward "f1" "r1" 3]
             (steward-model/effect-fingerprint decide)))
      (is (= (steward-model/effect-fingerprint meditate)
             (steward-model/effect-fingerprint (assoc meditate :at 2000))))
      (is (= (steward-model/effect-fingerprint decide)
             (steward-model/effect-fingerprint (assoc decide :at 2000)))))
    (testing "non-model effects are outside the domain"
      (doseq [effect outside]
        (let [thrown (try
                       (steward-model/effect-fingerprint effect)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (some? thrown) (str (:type effect) " did not throw"))
          (is (= :EFFECT_OUTSIDE_MODEL_DOMAIN (:code data)))
          (is (= (:type effect) (get-in data [:details :type])))
          (is (= #{:model/meditate :model/decide}
                 (get-in data [:details :allowed]))))))
    (testing "no [:unknown ...] folding remains"
      (is (not-any? (fn [effect]
                      (try
                        (let [key (steward-model/effect-fingerprint effect)]
                          (and (vector? key) (= :unknown (first key))))
                        (catch clojure.lang.ExceptionInfo _
                          false)))
                    (concat [meditate decide] outside))))))
