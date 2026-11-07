(ns hc.server.search.app-path-test
  "The app router must not run search before the Owner accepts a message.

  Search intent stays on the accepted payload; the model gateway runs it
  after the Owner commits the turn.  These tests use a real Owner and a real
  loopback search stub, so a business-refused send that still reaches the
  search adapter fails with an observable external request."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hc.compose.owner :as owner]
            [hc.server.app.commands :as commands]
            [hc.server.app.system :as system]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.model-port.contract :as contract]
            [hc.server.search.integration :as integration]
            [hc.server.search.support :as support]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- router
  ([owner] (router owner nil))
  ([owner search-service]
   (let [router (commands/router {:registry nil
                                  :settings nil
                                  :session-config nil
                                  :search search-service})]
     (swap! (:state router) assoc :active-session-id "s1")
     (swap! (:state router) assoc-in [:owners "s1"] owner)
     router)))

(defn- fake-owner
  [seen]
  {:snapshot (fn []
               {:state {:commands {:command-epoch 1
                                   :retired-through 0}}})
   :submit-command! (fn [request]
                      (reset! seen request)
                      {:status :accepted
                       :command-id (:command-id request)})})

(defn- send!
  ([router payload] (send! router "c1" payload))
  ([router command-id payload]
   (let [reply (atom nil)]
     (gateway-ports/submit-command! router
                                    {:command-id command-id
                                     :command-epoch 1
                                     :payload (assoc payload
                                                     :session-id "s1")}
                                    #(reset! reply %))
     @reply)))

(defn- user-text
  [request]
  (some (fn [message]
          (when (= :user (:role message))
            (:text message)))
        (reverse (get-in request [:snapshot :messages]))))

(defn- user-turn-start?
  [request]
  (and (true? (get-in request [:snapshot :user-turn?]))
       (nil? (get-in request [:snapshot :nudge-id]))))

(defn- user-turn-starts
  [starts]
  (filterv user-turn-start? @starts))

(defn- search-gateway
  "A minimal model gateway that mirrors the production order.

  The Owner starts it only after it commits the accepted turn.  It runs the
  search intent from that accepted request, and it does not call the model
  when required search refuses."
  [search-service starts model-starts]
  {:start! (fn [request deliver!]
             (swap! starts conj request)
             (let [handle {:attempt-id (:attempt-id request)
                           :call-id (:call-id request)}]
               (if (get-in request [:snapshot :nudge-id])
                 ;; A Nudge call is a separate internal turn.  Leave it
                 ;; running so it neither searches nor loops the fake
                 ;; gateway; the Owner stop settles it.
                 handle
                 (let [intent (or (:search request)
                                  (get-in request [:snapshot :search]))
                       result (when intent
                                (integration/run-intent!
                                 search-service intent
                                 {:text (or (user-text request) "")
                                  :turn-id (or (:turn-command-id request)
                                               (get-in request
                                                       [:snapshot
                                                        :turn-command-id])
                                               (:call-id request))
                                  :session-id "s1"}))]
                   (if (and result (false? (:continue? result)))
                     (deliver! {:event :failed
                                :kind :search-required-failed
                                :category :permanent
                                :search-error? true
                                :error (assoc (or (:error result) {})
                                              :code (or (get-in result
                                                                [:error :code])
                                                        :SEARCH_REQUIRED_FAILED)
                                              :kind :search-required-failed
                                              :category :permanent
                                              :message
                                              (or (get-in result
                                                          [:error :message])
                                                  "required web search failed"))})
                     (do
                       (swap! model-starts inc)
                       (deliver!
                        (cond-> {:event :completed
                                 :text "answer"}
                          result
                          (assoc :search-status (:status result)
                                 :search-degraded?
                                 (and (false? (:ok? result))
                                      (not (:must-search-failed?
                                            result))))))))
                   handle))))
   :cancel! (fn [_] nil)})

(defn- with-owner
  [gateway f]
  (let [handle (owner/start-owner! {:session-id "s1"
                                    :ephemeral? true
                                    :model-gateway gateway})]
    (try
      (f handle)
      (finally
        ((:stop! handle))))))

(defn- search-server
  []
  (support/start-server
   {"/search"
    (fn [exchange _]
      (support/json-response!
       exchange 200
       {:results [{:title "Alpha"
                   :url "https://example.test/a"
                   :content "Alpha snippet"}]}))}))

(defn- search-service
  [server]
  (support/search-service
   {:services [(support/searx-service (:base server))]}))

(defn- send-intent
  [text]
  {:mode :required
   :service-id "searx"
   :queries [{:query-id "q1" :text text}]})

(defn- production-gateway
  [service model-port]
  ((var-get #'system/model-gateway)
   (atom model-port) nil nil "s1" false (atom nil)
   (atom {}) (atom service) (atom nil) false))

(defn- recording-model-port
  [requests]
  (reify contract/ModelPort
    (start-request! [_ request receiver]
      (swap! requests inc)
      (contract/deliver! receiver
                         {:event :completed
                          :attempt-id (:attempt-id request)
                          :text "answer"})
      (contract/handle request))
    (cancel-request! [_ _] nil)))

(deftest send-message-passes-search-intent-without-a-preflight-request
  (let [server (search-server)
        service (search-service server)
        seen (atom nil)
        router (router (fake-owner seen) service)
        intent (send-intent "herdchestra")
        result (send! router {:kind :send-message
                              :text "herdchestra"
                              :search intent})]
    (try
      (is (true? (:ok? result)))
      (is (= :accepted (get-in result [:receipt :status])))
      (is (= intent (get-in @seen [:payload :search]))
          "the Owner receives the search intent unchanged")
      (is (= "c1" (:command-id @seen)))
      (is (zero? (support/request-count server))
          "the router must not run the search itself")
      (finally
        (support/stop! server)))))

(deftest business-refused-send-does-not-search
  (let [server (search-server)
        service (search-service server)
        starts (atom [])
        model-starts (atom 0)]
    (try
      (with-owner (search-gateway service starts model-starts)
        (fn [handle]
          (let [router (router handle service)
                result (send! router
                              {:kind :send-message
                               :text "herdchestra"
                               :attachments [{:id "attachment-without-content"}]
                               :search (send-intent "herdchestra")})]
            (is (false? (:ok? result)))
            (is (= :ATTACHMENT_CONTENT_REQUIRED
                   (get-in result [:error :code]))
                "the Owner refused before creating a turn")
            (Thread/sleep 200)
            (is (zero? (support/request-count server))
                "a refused send must not reach the external search service")
            (is (empty? @starts)
                "a refused send must not start the model gateway")
            (is (zero? @model-starts))
            (is (empty? (get-in ((:snapshot handle))
                                [:state :conversation]))))))
      (finally
        (support/stop! server)))))

(deftest accepted-send-runs-search-after-owner-commits
  (let [server (search-server)
        service (search-service server)
        starts (atom [])
        model-starts (atom 0)]
    (try
      (with-owner (search-gateway service starts model-starts)
        (fn [handle]
          (let [router (router handle service)
                result (send! router
                              {:kind :send-message
                               :text "herdchestra"
                               :search (send-intent "herdchestra")})]
            (is (true? (:ok? result)))
            (is (wait-for #(= 1 (count (user-turn-starts starts))) 3000))
            (is (wait-for #(= 1 (support/request-count server)) 3000))
            (is (wait-for #(= 1 @model-starts) 3000))
            (is (some (fn [request]
                        (and (= "/search" (:path request))
                             (str/includes? (str (:query request))
                                            "herdchestra")))
                      (support/requests server))
                "the search used the accepted user text")
            (let [turn-request (first (user-turn-starts starts))]
              (is (= "herdchestra" (user-text turn-request)))
              (is (some #(= "herdchestra" (:text %))
                        (get-in turn-request [:snapshot :messages]))
                  "the user message was committed before the gateway started")
              (is (= :required (get-in turn-request [:search :mode]))))
            (is (wait-for
                 #(some (fn [message]
                          (and (= :ok (:search-status message))
                               (= "answer" (:text message))))
                        (get-in ((:snapshot handle))
                                [:state :conversation]))
                 3000)))))
      (finally
        (support/stop! server)))))

(deftest replayed-send-does-not-search-again
  (let [server (search-server)
        service (search-service server)
        starts (atom [])
        model-starts (atom 0)]
    (try
      (with-owner (search-gateway service starts model-starts)
        (fn [handle]
          (let [router (router handle service)
                request {:kind :send-message
                         :text "herdchestra"
                         :search (send-intent "herdchestra")}
                first-result (send! router "c1" request)]
            (is (true? (:ok? first-result)))
            (is (wait-for #(= 1 (support/request-count server)) 3000))
            (let [replay (send! router "c1" request)]
              (is (true? (:replayed? replay)))
              (is (= (:receipt first-result) (:receipt replay))))
            (Thread/sleep 200)
            (is (= 1 (support/request-count server))
                "a command-id replay must not repeat the external search")
            (is (= 1 (count (user-turn-starts starts))))
            (is (some #(= "c1" (:turn-command-id %))
                      (user-turn-starts starts))))))
      (finally
        (support/stop! server)))))

(deftest required-search-failure-blocks-the-model
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 500 "boom" "text/plain"))})
        service (search-service server)
        starts (atom [])
        model-starts (atom 0)]
    (try
      (with-owner (search-gateway service starts model-starts)
        (fn [handle]
          (let [router (router handle service)
                result (send! router
                              {:kind :send-message
                               :text "herdchestra"
                               :search (send-intent "herdchestra")})]
            (is (true? (:ok? result)))
            (is (wait-for #(= 1 (count (user-turn-starts starts))) 3000))
            (is (wait-for #(= :failed
                              (get-in ((:snapshot handle))
                                      [:state :calls "call-1" :state]))
                          3000))
            (is (zero? @model-starts)
                "required search failure must not call the model")
            (let [snap ((:snapshot handle))
                  run (val (first (get-in snap [:state :runs])))]
              (is (= :failed (:status run)))
              (is (= :search-required-failed
                     (get-in run [:error :kind]))))
            (is (= 1 (support/request-count server))))))
      (finally
        (support/stop! server)))))

(deftest auto-search-failure-continues-with-degraded-answer
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 500 "boom" "text/plain"))})
        service (search-service server)
        starts (atom [])
        model-starts (atom 0)]
    (try
      (with-owner (search-gateway service starts model-starts)
        (fn [handle]
          (let [router (router handle service)
                result (send! router
                              {:kind :send-message
                               :text "herdchestra"
                               :search {:mode :auto
                                        :service-id "searx"
                                        :queries [{:query-id "q1"
                                                   :text "herdchestra"}]}})]
            (is (true? (:ok? result)))
            (is (wait-for #(= 1 (count (user-turn-starts starts))) 3000))
            (is (wait-for #(= 1 (support/request-count server)) 3000))
            (is (wait-for #(= 1 @model-starts) 3000))
            (is (wait-for #(= :succeeded
                              (get-in ((:snapshot handle))
                                      [:state :calls "call-1" :state]))
                          3000))
            (let [message (first (filter #(= :failed (:search-status %))
                                         (get-in ((:snapshot handle))
                                                 [:state :conversation])))]
              (is (some? message))
              (is (= :failed (:search-status message)))
              (is (true? (:search-degraded? message)))))))
      (finally
        (support/stop! server)))))

(deftest production-gateway-runs-required-search-before-model
  (let [server (search-server)
        service (search-service server)
        model-requests (atom 0)
        gateway (production-gateway service
                                    (recording-model-port model-requests))
        events (atom [])
        handle ((:start! gateway)
                {:attempt-id "a1"
                 :call-id "c1"
                 :snapshot {:messages [{:role :user :text "herdchestra"}]}
                 :search (send-intent "herdchestra")}
                #(swap! events conj %))]
    (try
      (is (= :model-gateway-pending (:handle handle)))
      (is (wait-for #(some (fn [event]
                             (= :completed (:event event)))
                           @events)
                    3000))
      (is (= 1 @model-requests))
      (is (= 1 (support/request-count server)))
      (let [completed (first (filter #(= :completed (:event %)) @events))]
        (is (false? (boolean (:search-degraded? completed))))
        (is (= :ok (:search-status completed))))
      (finally
        (support/stop! server)))))

(deftest production-gateway-required-failure-blocks-model
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 500 "boom" "text/plain"))})
        service (search-service server)
        model-requests (atom 0)
        gateway (production-gateway service
                                    (recording-model-port model-requests))
        events (atom [])
        handle ((:start! gateway)
                {:attempt-id "a1"
                 :call-id "c1"
                 :snapshot {:messages [{:role :user :text "herdchestra"}]}
                 :search (send-intent "herdchestra")}
                #(swap! events conj %))]
    (try
      (is (= :model-gateway-pending (:handle handle)))
      (is (wait-for #(some (fn [event]
                             (= :failed (:event event)))
                           @events)
                    3000))
      (is (zero? @model-requests))
      (is (= :search-required-failed
             (get-in (first (filter #(= :failed (:event %)) @events))
                     [:error :kind])))
      (is (= 1 (support/request-count server)))
      (finally
        (support/stop! server)))))

(deftest production-gateway-auto-failure-continues-degraded
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (support/response! exchange 500 "boom" "text/plain"))})
        service (search-service server)
        model-requests (atom 0)
        gateway (production-gateway service
                                    (recording-model-port model-requests))
        events (atom [])
        handle ((:start! gateway)
                {:attempt-id "a1"
                 :call-id "c1"
                 :snapshot {:messages [{:role :user :text "herdchestra"}]}
                 :search {:mode :auto
                          :service-id "searx"
                          :queries [{:query-id "q1"
                                     :text "herdchestra"}]}}
                #(swap! events conj %))]
    (try
      (is (= :model-gateway-pending (:handle handle)))
      (is (wait-for #(some (fn [event]
                             (= :completed (:event event)))
                           @events)
                    3000))
      (is (= 1 @model-requests))
      (let [completed (first (filter #(= :completed (:event %)) @events))]
        (is (= :failed (:search-status completed)))
        (is (true? (:search-degraded? completed))))
      (finally
        (support/stop! server)))))

(deftest production-gateway-cancel-stops-search-before-model
  (let [server (support/start-server
                {"/search"
                 (fn [exchange _]
                   (Thread/sleep 500)
                   (support/json-response!
                    exchange 200
                    {:results [{:title "Alpha"
                                :url "https://example.test/a"
                                :content "Alpha snippet"}]}))})
        service (search-service server)
        model-requests (atom 0)
        gateway (production-gateway service
                                    (recording-model-port model-requests))
        events (atom [])
        handle ((:start! gateway)
                {:attempt-id "a1"
                 :call-id "c1"
                 :snapshot {:messages [{:role :user :text "herdchestra"}]}
                 :search (send-intent "herdchestra")}
                #(swap! events conj %))]
    (try
      (Thread/sleep 50)
      ((:cancel! gateway) handle)
      (is (wait-for #(some (fn [event]
                             (= :cancelled (:event event)))
                           @events)
                    3000))
      (is (zero? @model-requests)
          "cancellation during search must not start the model")
      (finally
        (support/stop! server)))))
