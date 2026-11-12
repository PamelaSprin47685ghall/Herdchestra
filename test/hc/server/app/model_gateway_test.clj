(ns hc.server.app.model-gateway-test
  "Behavior of the application-owned model gateway seam.

  The gateway is the one place that turns an Owner request into a provider
  attempt.  These tests pin the turn-injection boundary: a refused material
  injection must not consume the turn, so a retry with the same call-id still
  injects the materials instead of silently running without them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.system :as system]
            [hc.server.model-port.contract :as contract]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- recording-port
  [requests]
  (reify contract/ModelPort
    (start-request! [_ request _receiver]
      (swap! requests conj request)
      (contract/handle request))
    (cancel-request! [_ _] nil)))

(deftest failed-material-injection-is-retried-on-the-same-call-id
  (let [requests (atom [])
        injected (atom 0)
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 ::materials
                 (atom {})
                 "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) false)
        request {:attempt-id "a1"
                 :call-id "c1"
                 :snapshot {:messages [{:role :user :text "hello"}]
                            :role :task}}
        deliver! (fn [_] {:ok? true})
        injection (resolve 'hc.server.app.system/material-injection)]
    (with-redefs-fn
      {injection
       (fn [_materials _registry _session-id req]
         (if (= 1 (swap! injected inc))
           (throw (ex-info "materials refused this turn"
                           {:code :MATERIALS_CONTEXT_REFUSED}))
           req))}
      (fn []
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"materials refused this turn"
                              ((:start! gateway) request deliver!))
            "a synchronous injection refusal is surfaced to dispatch")
        (is (wait-for #(= 1 @injected) 5000))
        (is (empty? @requests)
            "a refused injection must not start a model request")

        ((:start! gateway) request deliver!)
        (is (wait-for #(= 1 (count @requests)) 5000)
            "the retry injects the materials instead of running without them")

        ((:start! gateway) request deliver!)
        (is (wait-for #(= 2 (count @requests)) 5000))
        (is (= 2 @injected)
            "a successful injection is not repeated for the same call-id")))))

(deftest gateway-attaches-the-role-prompt-and-program-tool
  (let [requests (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) true)
        request {:attempt-id "a-review"
                 :call-id "c-review"
                 :snapshot {:program-role :clj-review
                            :messages [{:role :user :text "review the draft"}]}}]
    ((:start! gateway) request (fn [_] {:ok? true}))
    (is (wait-for #(= 1 (count @requests)) 5000))
    (let [snapshot (:snapshot (first @requests))
          system (first (:messages snapshot))]
      (is (= :system (:role system)))
      (is (str/includes? (:text system) "You are Reviewer"))
      (is (= ["clj-review"] (mapv :name (:tools snapshot))))
      (is (= ["source" "intent" "scope"]
             (get-in snapshot [:tools 0 :parameters :required]))))))

(deftest gateway-attaches-the-plan-prompt-and-clj-plan-tool
  (let [requests (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) true)
        request {:attempt-id "a-plan"
                 :call-id "c-plan"
                 :snapshot {:program-role :clj-plan
                            :tools [{:name "devops"
                                     :description "direct devops"
                                     :parameters {:type "object"}}]
                            :messages [{:role :user
                                        :text "draft the mission"}]}}]
    ((:start! gateway) request (fn [_] {:ok? true}))
    (is (wait-for #(= 1 (count @requests)) 5000))
    (let [snapshot (:snapshot (first @requests))
          system (first (:messages snapshot))
          tools (mapv :name (:tools snapshot))]
      (is (= :system (:role system)))
      (is (str/includes? (:text system) "You are Plan"))
      (is (str/includes? (:text system) "待执行"))
      (is (= ["devops" "clj-plan"] tools)
          "the direct devops tool stays and the role tool is appended once")
      (is (= ["source" "intent" "scope"]
             (get-in snapshot [:tools 1 :parameters :required]))))))

(deftest gateway-refuses-tool-required-roles-without-tools
  (doseq [[program-role mission-role]
          [[:clj-plan :plan] [:clj-review :reviewer]]]
    (let [requests (atom [])
          events (atom [])
          gateway ((resolve 'hc.server.app.system/model-gateway)
                   (atom (recording-port requests))
                   nil nil "s1" false (atom nil)
                   (atom {}) (atom nil) (atom nil) false)
          request {:attempt-id (str "a-" (name program-role))
                   :call-id (str "c-" (name program-role))
                   :snapshot {:program-role program-role
                              :mission-role mission-role
                              :messages [{:role :user :text "do the work"}]}}
          handle ((:start! gateway)
                  request
                  (fn [event] (swap! events conj event) {:ok? true}))]
      (is (= :model-gateway-pending (:handle handle))
          "the gateway still supervises one terminal")
      (is (wait-for #(= 1 (count @events)) 5000))
      (is (empty? @requests)
          "the model port is never started for a tool-required role")
      (let [event (first @events)]
        (is (= :failed (:event event)))
        (is (= :model-tools-unavailable (get-in event [:error :kind])))
        (is (= :MODEL_TOOLS_UNAVAILABLE (get-in event [:error :code])))
        (is (= :permanent (get-in event [:error :category])))
        (is (= program-role (get-in event [:error :program-role])))
        (is (= mission-role (get-in event [:error :mission-role])))
        (is (str/includes? (get-in event [:error :message])
                           (name program-role))
            "the refusal names the actual program role, not a stale list")
        (is (str/includes? (get-in event [:error :message])
                           "program tool surface"))
        (is (str/includes? (get-in event [:error :message]) ":tools"))))))

(deftest gateway-names-the-capabilities-source-in-the-refusal
  (let [owner (atom {:snapshot
                     (fn []
                       {:state {:session
                                {:model-binding
                                 {:connection-id "conn"
                                  :model-entry-id "model"
                                  :capabilities {:tools false}}}}})})
        live-source ((resolve 'hc.server.app.system/live-tools-source)
                     owner false
                     (fn [] {:connections
                             {"conn" {:models
                                      {"model" {:capabilities {}}}}}}))
        events (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port (atom [])))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil)
                 (fn [_request _session] false)
                 nil
                 (fn [request _session] (live-source request nil)))]
    ((:start! gateway)
     {:attempt-id "a-source"
      :call-id "c-source"
      :snapshot {:program-role :clj-plan
                 :connection "conn"
                 :model "model"
                 :messages [{:role :user :text "do the work"}]}}
     (fn [event] (swap! events conj event) {:ok? true}))
    (is (wait-for #(= 1 (count @events)) 5000))
    (is (= :MODEL_TOOLS_UNAVAILABLE
           (get-in (first @events) [:error :code])))
    (is (= :binding
           (get-in (first @events) [:error :capabilities-source]))
        "a binding-declared no-tools fact names :binding")))

(deftest live-tools-source-follows-the-same-merge-as-the-fact
  (let [owner (atom {:snapshot
                     (fn []
                       {:state {:session
                                {:model-binding
                                 {:connection-id "conn"
                                  :model-entry-id "model"
                                  :capabilities {:tools true}}}}})})
        config (atom {:connections
                      {"conn"
                       {:models
                        {"model" {:capabilities {}}}}}})
        source ((resolve 'hc.server.app.system/live-tools-source)
                owner false (fn [] @config))]
    (is (= :binding (source {} nil))
        "a live entry without a tools fact falls back to the binding")
    (reset! config {:connections
                    {"conn"
                     {:models
                      {"model" {:capabilities {:tools false}}}}}})
    (is (= :live-model (source {} nil))
        "a live entry with a tools fact is authoritative")
    (reset! owner {:snapshot (fn [] {:state {:session {}}})})
    (is (= :none (source {} nil))
        "no binding and no fact means :none")
    (reset! owner {:snapshot
                   (fn []
                     {:state {:session
                              {:model-binding
                               {:connection-id "conn"
                                :model-entry-id "model"
                                :capabilities {}}}}})})
    (reset! config {:connections {}})
    (is (= :none (source {} nil))
        "an empty capability map is not a declaration")))

(deftest gateway-omits-every-tool-for-a-tools-less-pure-chat
  (let [requests (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) false)
        request {:attempt-id "a-chat-tools-less"
                 :call-id "c-chat-tools-less"
                 :snapshot {:tools [{:name "devops"
                                     :description "direct devops"
                                     :parameters {:type "object"}}]
                            :messages [{:role :user
                                        :text "hello"}]}}]
    ((:start! gateway) request (fn [_] {:ok? true}))
    (is (wait-for #(= 1 (count @requests)) 5000)
        "pure chat still reaches the model port")
    (let [snapshot (:snapshot (first @requests))]
      (is (= [{:role :user :text "hello"}] (:messages snapshot))
          "pure chat gains no role prompt")
      (is (nil? (:tools snapshot))
          "the frozen tool surface is removed for text-only chat"))))

(deftest gateway-resolves-a-live-tools-supported-function
  (let [requests (atom [])
        calls (atom 0)
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil)
                 (fn [_request _session]
                   (swap! calls inc)
                   false))
        request {:attempt-id "a-live"
                 :call-id "c-live"
                 :snapshot {:messages [{:role :user :text "hello"}]}}]
    ((:start! gateway) request (fn [_] {:ok? true}))
    (is (wait-for #(= 1 (count @requests)) 5000))
    (is (= 1 @calls) "the option is resolved once per attempt")
    (is (nil? (get-in (first @requests) [:snapshot :tools])))))

(deftest chat-turn-reaches-a-tools-less-model-as-text
  (let [requests (atom [])
        events (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) false)
        request {:attempt-id "a-chat"
                 :call-id "c-chat"
                 :snapshot {:program-role :clj-plan
                            :mission-role :plan
                            :chat? true
                            :messages [{:role :user
                                        :text "what is S1"}]}}
        handle ((:start! gateway)
                request
                (fn [event] (swap! events conj event) {:ok? true}))]
    (is (= :model-gateway-pending (:handle handle)))
    (is (wait-for #(= 1 (count @requests)) 5000)
        "a chat turn is not refused for a tools-less model")
    (let [snapshot (:snapshot (first @requests))]
      (is (= [{:role :user :text "what is S1"}] (:messages snapshot))
          "no Plan program prompt is attached")
      (is (nil? (:tools snapshot))
          "no program tool schema is sent")
      (is (not (contains? snapshot :program-role))
          "internal role markers never reach the provider wire"))))

(deftest task-role-requests-keep-their-explicit-tool-contract
  (let [requests (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) false)
        request {:attempt-id "a-worker"
                 :call-id "c-worker"
                 :snapshot {:role :worker
                            :model-role :task
                            :program-role :clj-worker
                            :required-capabilities [:tools]
                            :messages [{:role :user :text "run the program"}]}}]
    ((:start! gateway) request (fn [_] {:ok? true}))
    (is (wait-for #(= 1 (count @requests)) 5000))
    (is (= ["clj-worker"]
           (mapv :name (get-in (first @requests) [:snapshot :tools])))
        "the main-model fact never strips a task worker's required tools")))

(deftest gateway-keeps-the-program-tool-when-the-binding-supports-it
  (let [requests (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom (recording-port requests))
                 nil nil "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil)
                 (fn [_request _session] true))
        request {:attempt-id "a-main-tools"
                 :call-id "c-main-tools"
                 :snapshot {:program-role :clj-review
                            :messages [{:role :user :text "review"}]}}]
    ((:start! gateway) request (fn [_] {:ok? true}))
    (is (wait-for #(= 1 (count @requests)) 5000))
    (is (= ["clj-review"]
           (mapv :name (get-in (first @requests) [:snapshot :tools]))))))

(deftest live-tool-support-follows-the-committed-binding
  (let [owner (atom {:snapshot
                     (fn []
                       {:state {:session
                                {:model-binding
                                 {:capabilities {:tools false}}}}})})
        live ((resolve 'hc.server.app.system/live-tools-supported)
              owner true)]
    (is (false? (live {} nil))
        "an explicit no-tools binding outranks the startup fallback")
    (reset! owner {:snapshot
                   (fn []
                     {:state {:session
                              {:model-binding
                               {:capabilities {:tools true}}}}})})
    (is (true? (live {} nil)))
    (reset! owner {:snapshot
                   (fn [] {:state {:session {:model-binding nil}}})})
    (is (true? (live {} nil))
        "a session without a binding uses the startup configuration fact")))

(deftest live-tool-support-follows-the-current-configuration
  (let [owner (atom {:snapshot
                     (fn []
                       {:state {:session
                                {:model-binding
                                 {:connection-id "conn"
                                  :model-entry-id "model"
                                  :capabilities {:tools :unknown}}}}})})
        config (atom {:connections
                      {"conn"
                       {:models
                        {"model" {:capabilities {:tools :known}}}}}})
        live ((resolve 'hc.server.app.system/live-tools-supported)
              owner false (fn [] @config))]
    (is (true? (live {} nil))
        "a saved user declaration outranks the frozen binding snapshot")
    (reset! config {:connections
                    {"conn"
                     {:models
                      {"model" {:capabilities {:tools :unknown}}}}}})
    (is (false? (live {} nil))
        "the live fact also removes support after an edit")
    (reset! config {:connections {}})
    (is (false? (live {} nil))
        "a missing live model falls back to the binding fact")))

(deftest live-tool-support-follows-the-frozen-request-binding
  (let [owner (atom {:snapshot
                     (fn []
                       {:state {:session
                                {:model-binding
                                 {:connection-id "session-conn"
                                  :model-entry-id "session-model"
                                  :capabilities {:tools false}}}}})})
        config (atom {:connections
                      {"session-conn"
                       {:models
                        {"session-model"
                         {:capabilities {:tools false}}}}
                       "turn-conn"
                       {:models
                        {"turn-model"
                         {:capabilities {:tools true}}}}}})
        live ((resolve 'hc.server.app.system/live-tools-supported)
              owner false (fn [] @config))]
    (is (true? (live {:snapshot {:connection "turn-conn"
                                 :model "turn-model"}}
                     nil))
        "the frozen request identity selects the capability fact")
    (is (false? (live {} nil))
        "a request without identity falls back to the session binding")))

(deftest live-tool-support-merges-live-entry-with-binding-declaration
  (let [owner (atom {:snapshot
                     (fn []
                       {:state {:session
                                {:model-binding
                                 {:connection-id "conn"
                                  :model-entry-id "model"
                                  :capabilities {:tools true}}}}})})
        config (atom {:connections
                      {"conn"
                       {:models
                        {"model" {:capabilities {}}}}}})
        live ((resolve 'hc.server.app.system/live-tools-supported)
              owner false (fn [] @config))]
    (is (true? (live {} nil))
        "a live entry without a tools fact falls back to the binding declaration")
    (reset! config {:connections
                    {"conn"
                     {:models
                      {"model" {:capabilities {:tools false}}}}}})
    (is (false? (live {} nil))
        "an explicit live no-tools fact removes support")
    (reset! config {:connections
                    {"conn"
                     {:models
                      {"model" {:capabilities {:tools :unknown}}}}}})
    (is (false? (live {} nil))
        "a live unknown tools fact is authoritative")))

(deftest disabled-model-binding-explains-the-refusal
  (let [events (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom nil) nil (atom {}) "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) true
                 (fn [_request] :connection-disabled))
        request {:attempt-id "a-disabled"
                 :call-id "c-disabled"
                 :snapshot {:role :main
                            :connection "conn-a"
                            :model "model-a"
                            :messages [{:role :user :text "hello"}]}}]
    ((:start! gateway)
     request
     (fn [event] (swap! events conj event) {:ok? true}))
    (is (wait-for #(= 1 (count @events)) 5000))
    (let [event (first @events)
          error (:error event)]
      (is (= :failed (:event event)))
      (testing "the existing code and the user-facing cause both survive"
        (is (= :MODEL_NOT_CONFIGURED (:code error)))
        (is (= :connection-disabled (:reason error)))
        (is (= :connection-disabled (:availability error)))
        (is (= :select-model (:remedy error)))
        (is (str/includes? (:message error) "disabled"))
        (is (str/includes? (:message error) "replacement connection"))))))

(deftest missing-model-port-keeps-the-generic-cause-when-nothing-is-bound
  (let [events (atom [])
        gateway ((resolve 'hc.server.app.system/model-gateway)
                 (atom nil) nil (atom {}) "s1" false (atom nil)
                 (atom {}) (atom nil) (atom nil) true
                 (fn [_request] nil))
        request {:attempt-id "a-unbound"
                 :call-id "c-unbound"
                 :snapshot {:role :main
                            :messages [{:role :user :text "hello"}]}}]
    ((:start! gateway)
     request
     (fn [event] (swap! events conj event) {:ok? true}))
    (is (wait-for #(= 1 (count @events)) 5000))
    (let [error (:error (first @events))]
      (is (= :MODEL_NOT_CONFIGURED (:code error)))
      (is (= "no model port is configured" (:message error)))
      (is (= :model-not-configured (:reason error))))))

(deftest availability-reader-uses-the-session-config-shape
  (let [reader ((resolve 'hc.server.app.system/binding-availability-fn)
                (fn []
                  {:connections
                   {"conn-a" {:connection-id "conn-a"
                              :enabled? false
                              :models {"m1" {:model-entry-id "m1"}}}}}))]
    (is (= :connection-disabled
           (reader {:snapshot {:connection "conn-a" :model "m1"}})))
    (is (nil? (reader {:snapshot {:messages []}}))
        "a request without a binding has no availability fact")))

(deftest session-binding-reads-a-vector-live-declaration
  (let [owner (atom {:snapshot
                     (fn []
                       {:state {:session
                                {:model-binding
                                 {:connection-id "conn"
                                  :model-entry-id "model"
                                  :capabilities {}}}}})})
        settings (fn []
                   {:connections
                    [{:id "conn"
                      :models [{:id "model"
                                :capabilities {:tools true}}]}]})
        supported ((resolve 'hc.server.app.system/live-tools-supported)
                   owner false settings)]
    (is (true? (supported {} nil))
        "a saved vector declaration reaches a session binding with no tools fact")))

(deftest saved-vector-declaration-passes-the-send-gate
  (let [owner (atom {:snapshot (fn [] {:state {:session {}}})})
        settings (fn []
                   {:connections
                    [{:id "conn"
                      :models [{:id "model"
                                :capabilities {:tools true}}]}]})
        supported ((resolve 'hc.server.app.system/live-tools-supported)
                   owner false settings)
        source ((resolve 'hc.server.app.system/live-tools-source)
                owner false settings)
        request {:snapshot {:connection "conn" :model "model"}}]
    (is (true? (supported request nil))
        "Plan's send gate must see a declaration saved as a model vector")
    (is (= :live-model (source request nil))
        "the refusal source must name the saved model, not an empty binding")))

(deftest live-tool-support-reads-a-vector-connection-model
  (let [owner (atom {:snapshot (fn [] {:state {:session {}}})})
        ;; Settings persistence stores connections and models as vectors.
        ;; The send gate must read that shape; a map-only lookup treats a
        ;; saved declaration as absent and Plan refuses the send.
        config (fn []
                 {:connections
                  [{:id "conn"
                    :models [{:id "model"
                              :capabilities {:tools true}}]}]})
        live ((resolve 'hc.server.app.system/live-tools-supported)
              owner false config)]
    (is (true? (live {:snapshot {:connection "conn"
                                 :model "model"}}
                     nil))
        "a saved vector model declaration is the live tools fact")))
