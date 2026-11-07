(ns hc.server.gateway.ws-boundary-test
  "The 1 MiB Sente boundary over a real HTTP listener and the event handler.

  The Sente transport carries the same limit in three places: the `/chsk`
  request body, the inbound event estimate, and the outbound packer.  This
  test drives the first two through a real http-kit listener and the gateway
  handler; the outbound reference path is covered by `gateway.sync-object-test`
  and `gateway.feed-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.gateway :as gateway]
            [org.httpkit.server :as hk]
            [taoensso.sente :as sente]
            [taoensso.sente.java-ws-client :as java-ws]
            [taoensso.sente.packers.transit :as sente-transit]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers)
           (java.util.concurrent TimeUnit)
           (org.java_websocket.client WebSocketClient)))

(def ^:private mib (* 1024 1024))
(def ^:private ws-client (atom nil))

(defn- ws-constructor
  "Capture the Java WebSocket so teardown can close it; Sente's JVM client
  has no public close call and would otherwise reconnect forever."
  [opts]
  (let [delay (java-ws/make-client-ws opts)]
    (reset! ws-client delay)
    delay))

(defn- payload
  [n]
  (apply str (repeat n "x")))

(defn- start-stack!
  []
  (let [gw (gateway/start! {:sch-adapter (sente-http-kit/get-sch-adapter)
                            :allowed-origins :all
                            :snapshot {:session/by-id {}}
                            :max-sente-message-bytes mib})
        server (hk/run-server (gateway/handler gw)
                              {:ip "127.0.0.1" :port 0
                               :legacy-return-value? false})]
    {:gateway gw
     :server server
     :port (hk/server-port server)}))

(defn- post-chsk
  [port body]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder
                     (URI. (str "http://127.0.0.1:" port "/chsk")))
                    (.header "content-type"
                             "application/x-www-form-urlencoded")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(deftest http-chsk-body-boundary-is-exact
  (let [{:keys [gateway server port]} (start-stack!)]
    (try
      (testing "a body just below 1 MiB is not rejected for size"
        (let [body (str "client-id=boundary-under&padding="
                        (payload (- mib 2048)))
              response (post-chsk port body)]
          (is (not= 413 (:status response))
              (str "status " (:status response) " body " (:body response)))))
      (testing "a body above 1 MiB is refused with the stable code"
        (let [body (str "client-id=boundary-over&padding="
                        (payload (+ mib 2048)))
              response (post-chsk port body)]
          (is (= 413 (:status response)))
          (is (str/includes? (:body response) "SENTE_BODY_TOO_LARGE"))))
      (finally
        (when-let [delay @ws-client]
          (try
            (.close ^WebSocketClient @delay 1000 "test done")
            (catch Throwable _ nil)))
        (reset! ws-client nil)
        (hk/server-stop! server)
        (gateway/stop! gateway)))))

(deftest inbound-event-boundary-is-exact
  (let [gw (gateway/start! {:sch-adapter (sente-http-kit/get-sch-adapter)
                            :allowed-origins :all
                            :snapshot {:session/by-id {}}
                            :max-sente-message-bytes mib})]
    (try
      (testing "a near-limit event reaches the subscribe handler"
        (let [reply (promise)]
          (gateway/handle-event!
           gw {:event [:hc/subscribe
                       {:subscription-id 1
                        :padding (payload (- (* 900 1024) 512))}]
               :uid "boundary-user"
               :?reply-fn #(deliver reply %)})
          (is (true? (:ok? @reply)) (pr-str @reply))
          (is (= 1 (:subscription-id @reply)))))
      (testing "an over-limit event is refused before the handler"
        (let [reply (promise)]
          (gateway/handle-event!
           gw {:event [:hc/subscribe
                       {:subscription-id 2
                        :padding (payload (+ mib 1024))}]
               :uid "boundary-user"
               :?reply-fn #(deliver reply %)})
          (is (= :SENTE_MESSAGE_TOO_LARGE (get-in @reply [:error :code])))
          (is (<= mib (get-in @reply [:error :details :size])))
          (is (= mib (get-in @reply [:error :details :limit])))))
      (finally
        (gateway/stop! gw)))))

(deftest real-websocket-roundtrip-crosses-the-boundary
  (let [{:keys [gateway server port]} (start-stack!)]
    (try
      (let [client (sente/make-channel-socket-client!
                    "/chsk"
                    nil
                    {:type :ws
                     :host "127.0.0.1"
                     :port port
                     :packer (sente-transit/get-packer :json)
                     :ws-constructor ws-constructor
                     :backoff-ms-fn (constantly nil)
                     :wrap-recv-evs? true})]
        (is (some? client))
        (is (loop [n 0]
              (cond
                (true? (:open? @(:state client))) true
                (< n 250) (do (Thread/sleep 20) (recur (inc n)))
                :else false))
            "the real WebSocket handshake completes with the matching packer")
        (let [near (promise)
              over (promise)]
          ((:send-fn client)
           [:hc/subscribe {:subscription-id 9
                           :padding (payload (- (* 900 1024) 512))}]
           10000
           #(deliver near %))
          (is (true? (:ok? (deref near 12000 {:ok? false})))
              "a near-1MiB event round-trips over the real socket")
          ((:send-fn client)
           [:hc/subscribe {:subscription-id 10
                           :padding (payload (+ mib 1024))}]
           10000
           #(deliver over %))
          (let [reply (deref over 12000 ::timeout)]
            (is (or (= :SENTE_MESSAGE_TOO_LARGE
                       (get-in reply [:error :code]))
                    (false? (:open? @(:state client))))
                (str "an over-1MiB event is refused or closes the socket: "
                     (pr-str reply)))))
        ;; The server stop in the finally block closes the socket; Sente's
        ;; JVM client exposes no public close call.
        )
      (finally
        (when-let [delay @ws-client]
          (try
            (.close ^WebSocketClient @delay 1000 "test done")
            (catch Throwable _ nil)))
        (reset! ws-client nil)
        (hk/server-stop! server)
        (gateway/stop! gateway)))))
