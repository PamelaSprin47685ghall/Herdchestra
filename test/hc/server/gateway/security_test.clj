(ns hc.server.gateway.security-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.commands :as commands]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.auth :as auth]
            [hc.server.gateway.blob :as blob]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.http :as http]
            [hc.server.gateway.ports :as ports]
            [hc.server.gateway.sente :as sente]
            [taoensso.sente.interfaces :as sente-i]
            [taoensso.sente.packers.transit :as sente-transit]))

(defn- make-gw
  ([owner] (make-gw owner {}))
  ([owner opts]
   (let [out (atom [])
         gw {:feed (feed/create (merge {:now-fn (constantly "2026-11-03T00:00:00Z")}
                                       (select-keys opts [:snapshot :max-pending])))
             :owner owner
             :store nil
             :socket {:send-fn (fn [uid event] (swap! out conj [uid event]))}
             :out out
             :stopped? (atom false)
             :opts opts}]
     (assoc gw :handler (fn [_] {:status 200})))))

(defn- event!
  [gw msg]
  (let [reply (atom nil)]
    (gateway/handle-event! gw (assoc msg :?reply-fn #(reset! reply %)))
    @reply))

(defn- subscribe!
  [gw uid sid]
  (event! gw {:uid uid :event [:hc/subscribe {:subscription-id sid}]}))

(defn- command!
  [gw uid request]
  (event! gw {:uid uid :event [:hc/command request]}))

(defn- owner-calls
  [calls]
  (ports/owner-port
   (fn [request reply]
     (swap! calls conj request)
     (reply {:ok? true :receipt {:status :accepted
                                 :command-id (:command-id request)}}))
   (fn [_] nil)))

(defn- request
  [{:keys [remote origin referer host]}]
  {:remote-addr remote
   :scheme :http
   :headers (cond-> {"host" (or host "127.0.0.1:7575")}
              origin (assoc "origin" origin)
              referer (assoc "referer" referer))})

(deftest loopback-same-origin-policy
  (is (true? (auth/authorize {} (request {:remote "127.0.0.1"}))))
  (is (true? (auth/authorize {}
                             (request {:remote "127.0.0.1"
                                       :origin "http://127.0.0.1:7575"}))))
  (is (true? (auth/authorize {}
                             (request {:remote "127.0.0.1"
                                       :referer "http://127.0.0.1:7575/session"}))))
  (is (false? (auth/authorize {}
                              (request {:remote "127.0.0.1"
                                        :origin "http://evil.example"}))))
  (is (false? (auth/authorize {}
                              (request {:remote "127.0.0.1"
                                        :referer "http://evil.example/x"}))))
  (is (false? (auth/authorize {}
                              (request {:remote "127.0.0.1"
                                        :origin "null"}))))
  (is (false? (auth/authorize {}
                              (request {:remote "127.0.0.1"
                                        :origin "not a url"}))))
  (is (false? (auth/authorize {}
                              (request {:remote "127.0.0.1"
                                        :origin "http://evil.example"
                                        :host "evil.example:7575"}))))
  (is (false? (auth/authorize {} (request {:remote "10.0.0.5"}))))
  (is (false? (auth/authorize {} (request {:remote nil}))))
  (is (true? (auth/authorize {:allowed-origins #{"http://app.example"}}
                             (request {:remote "127.0.0.1"
                                       :origin "http://app.example"}))))
  (is (true? (auth/authorize {:allowed-origins #{"http://app.example"}}
                             (request {:remote "127.0.0.1"
                                       :origin "http://app.example"
                                       :host "evil.example:7575"}))))
  (is (true? (auth/authorize {:authorize-request (fn [_] true)}
                             (request {:remote "10.0.0.5"}))))
  (is (false? (auth/authorize {:authorize-request (fn [_] false)}
                              (request {:remote "127.0.0.1"})))))

(deftest strict-loopback-host-prevents-dns-rebinding
  (testing "literal loopback hosts remain same-origin"
    (is (true? (auth/authorize {}
                               (request {:remote "127.0.0.1"
                                         :host "127.0.0.1:7575"
                                         :origin "http://127.0.0.1:7575"}))))
    (is (true? (auth/authorize {}
                               (request {:remote "127.0.0.1"
                                         :host "127.1.2.3:7575"
                                         :origin "http://127.1.2.3:7575"}))))
    (is (true? (auth/authorize {}
                               (request {:remote "127.0.0.1"
                                         :host "localhost:7575"
                                         :origin "http://localhost:7575"}))))
    (is (true? (auth/authorize {}
                               (request {:remote "127.0.0.1"
                                         :host "[::1]:7575"
                                         :origin "http://[::1]:7575"})))))
  (testing "a hostname that merely starts with 127. is not loopback"
    (doseq [host ["127.evil.example:7575"
                  "127.0.0.1.nip.io:7575"
                  "127.0.0.1.evil.example:7575"
                  "localhost.evil.example:7575"]]
      (is (false? (auth/authorize {}
                                  (request {:remote "127.0.0.1"
                                            :host host
                                            :origin (str "http://" host)})))
          host)))
  (testing "the peer check is a literal address check"
    (is (true? (auth/authorize {} (request {:remote "127.1.2.3"}))))
    (is (true? (auth/authorize {} (request {:remote "::ffff:7f00:1"}))))
    (is (false? (auth/authorize {} (request {:remote "127.evil.example"})))))
  (testing "a headerless request still needs a trusted Host"
    (is (false? (auth/authorize {}
                                (request {:remote "127.0.0.1"
                                          :host "127.evil.example:7575"}))))
    (is (false? (auth/authorize {}
                                (request {:remote "127.0.0.1"
                                          :host "127.0.0.1:7575@evil"}))))
    (is (false? (auth/authorize {}
                                (request {:remote "127.0.0.1"
                                          :host "127.0.0.1:7575:80"}))))
    (is (true? (auth/authorize {}
                               (request {:remote "127.0.0.1"
                                         :host "127.0.0.1:7575"}))))))

(deftest default-ports-normalize-to-one-origin
  (is (true? (auth/authorize {}
                             (request {:remote "127.0.0.1"
                                       :host "localhost:80"
                                       :origin "http://localhost"}))))
  (is (true? (auth/authorize {:allowed-origins #{"http://app.example:80/"}}
                             (request {:remote "127.0.0.1"
                                       :origin "http://app.example"})))))

(deftest remote-peers-require-an-authorization-hook
  (testing "Host and Origin are not credentials"
    (is (false? (auth/authorize {:allow-remote? true}
                                (request {:remote "10.0.0.5"}))))
    (is (false? (auth/authorize {:allow-remote? true
                                 :allowed-origins #{"http://app.example"}}
                                (request {:remote "10.0.0.5"
                                          :origin "http://app.example"}))))
    (is (false? (auth/authorize {:allow-remote? true
                                 :trusted-hosts #{"app.example"}}
                                (request {:remote "10.0.0.5"
                                          :host "app.example:7575"}))))
    (is (false? (auth/authorize {:allow-remote? true}
                                (request {:remote "10.0.0.5"
                                          :origin "http://evil.example"})))))
  (testing "a supplied hook is the authority"
    (is (true? (auth/authorize {:allow-remote? true
                                :authorized?-fn (fn [_] true)}
                               (request {:remote "10.0.0.5"}))))
    (is (false? (auth/authorize {:allow-remote? true
                                 :authorized?-fn (fn [_] false)}
                                (request {:remote "10.0.0.5"}))))
    (is (true? (auth/authorize {:allow-remote? true
                                :authorize-request (fn [_] true)}
                               (request {:remote "10.0.0.5"})))))
  (is (false? (auth/authorize {:allow-remote? true}
                              (request {:remote nil})))))

(deftest auth-middleware-rejects-cross-origin
  (let [handler (auth/wrap (fn [_] {:status 200 :body "ok"}) {})]
    (is (= 200 (:status (handler (request {:remote "127.0.0.1"})))))
    (is (= 403 (:status (handler (request {:remote "127.0.0.1"
                                           :origin "http://evil.example"})))))))

(deftest browser-internal-command-kinds-are-denied-by-default
  (let [calls (atom [])
        gw (make-gw (owner-calls calls))
        _ (subscribe! gw "u1" 1)]
    (doseq [kind [:review/submit :board/command :report/ack
                  :report/register :session/issue-epoch :app/issue-epoch]]
      (let [reply (command! gw "u1" {:command-id (str "c-" (name kind))
                                     :command-epoch 1
                                     :subscription-id 1
                                     :payload {:kind kind}})]
        (is (= :COMMAND_KIND_FORBIDDEN (get-in reply [:error :code])))
        (is (= :operator-message-required
               (get-in reply [:error :details :action])))))
    (is (empty? @calls)))
  (testing "the application can explicitly allow a kind"
    (let [calls (atom [])
          gw (make-gw (owner-calls calls)
                      {:allowed-command-kinds #{:board/command}})
          _ (subscribe! gw "u1" 1)
          reply (command! gw "u1" {:command-id "c-board"
                                   :command-epoch 1
                                   :subscription-id 1
                                   :payload {:kind :board/command}})]
      (is (:ok? reply))
      (is (= 1 (count @calls))))))

(deftest forbidden-kinds-cannot-be-smuggled-as-strings
  (let [calls (atom [])
        gw (make-gw (owner-calls calls))
        _ (subscribe! gw "u1" 1)]
    (doseq [kind ["review/submit" "board/command" "app/issue-epoch"
                  "fleet/fork"]]
      (let [reply (command! gw "u1" {:command-id (str "c-" kind)
                                     :command-epoch 1
                                     :subscription-id 1
                                     :payload {:kind kind}})]
        (is (= :COMMAND_KIND_FORBIDDEN (get-in reply [:error :code]))
            kind)))
    (is (empty? @calls))))

(deftest subscription-id-is-optional-but-validated-when-supplied
  (let [calls (atom [])
        gw (make-gw (owner-calls calls))
        _ (subscribe! gw "u1" 1)]
    (is (:ok? (command! gw "u1" {:command-id "c1"
                                 :command-epoch 1
                                 :payload {:kind :user/message}})))
    (is (= 1 (count @calls)))
    (is (= :not-found
           (:status (event! gw {:uid "u1"
                                :event [:hc/receipt {:command-id "c1"}]}))))
    (is (:ok? (event! gw {:uid "u1" :event [:hc/ack {:seq 0}]})))
    (is (:ok? (event! gw {:uid "u1" :event [:hc/resync {}]})))
    (is (= :SUBSCRIPTION_ID_REQUIRED
           (get-in (command! gw "u1" {:command-id "c2"
                                      :command-epoch 1
                                      :subscription-id "x"
                                      :payload {:kind :user/message}})
                   [:error :code])))
    (is (= :STALE_SUBSCRIPTION
           (get-in (command! gw "u1" {:command-id "c3"
                                      :command-epoch 1
                                      :subscription-id 0
                                      :payload {:kind :user/message}})
                   [:error :code]))))
  (testing "no current subscription is stale even without a supplied id"
    (let [gw (make-gw (owner-calls (atom [])))]
      (is (= :STALE_SUBSCRIPTION
             (get-in (command! gw "u2" {:command-id "c4"
                                        :command-epoch 1
                                        :payload {:kind :user/message}})
                     [:error :code]))))))

(deftest receipt-not-found-is-explicit
  (let [gw (make-gw (ports/owner-port
                     (fn [_ reply] (reply {:ok? true}))
                     (fn [_] nil)))
        _ (subscribe! gw "u1" 1)
        reply (event! gw {:uid "u1"
                          :event [:hc/receipt {:command-id "missing"
                                               :subscription-id 1}]})]
    (is (:ok? reply))
    (is (= :not-found (:status reply)))
    (is (nil? (:receipt reply)))
    (is (= :resync-state (:action reply))))
  (testing "an Owner not-found marker is normalized"
    (let [gw (make-gw (ports/owner-port
                       (fn [_ reply] (reply {:ok? true}))
                       (fn [command-id]
                         {:status :not-found :command-id command-id})))
          _ (subscribe! gw "u1" 1)
          reply (event! gw {:uid "u1"
                            :event [:hc/receipt {:command-id "missing"
                                                 :subscription-id 1}]})]
      (is (= :not-found (:status reply)))
      (is (nil? (:receipt reply))))))

(deftest command-epoch-is-forwarded-verbatim
  (let [calls (atom [])
        gw (make-gw (owner-calls calls))
        _ (subscribe! gw "u1" 1)]
    (command! gw "u1" {:command-id "c-create"
                       :command-epoch nil
                       :subscription-id 1
                       :payload {:kind :session/create}})
    (command! gw "u1" {:command-id "c-string"
                       :command-epoch "not-an-epoch"
                       :subscription-id 1
                       :payload {:kind :session/list}})
    (is (= [nil "not-an-epoch"] (mapv :command-epoch @calls)))
    (is (= ["c-create" "c-string"] (mapv :command-id @calls)))
    (is (= [1 1] (mapv :subscription-id @calls)))
    (is (= ["u1" "u1"] (mapv :uid @calls)))))

(deftest duplicate-and-retired-epochs-are-owner-decisions
  (let [domain (atom (:state (commands/issue-epoch (commands/new-state))))
        owner (ports/owner-port
               (fn [request reply]
                 (let [result (commands/accept @domain request)]
                   (when (:ok? result)
                     (reset! domain (:state result)))
                   (reply result)))
               (fn [command-id]
                 (commands/receipt-for @domain command-id)))
        gw (make-gw owner)
        _ (subscribe! gw "u1" 1)
        base {:command-id "c1" :command-epoch 1
              :subscription-id 1 :payload {:kind :user/message :text "a"}}]
    (command! gw "u1" base)
    (command! gw "u1" base)
    (command! gw "u1" (assoc base :payload {:kind :user/message :text "b"}))
    (let [receipts (->> @(:out gw)
                        (map second)
                        (map second)
                        (filter #(= "hc/command-receipt" (:name %)))
                        (mapv :value))]
      (is (false? (:replayed? (nth receipts 0))))
      (is (true? (:replayed? (nth receipts 1))))
      (is (= :COMMAND_ID_PAYLOAD_CONFLICT
             (get-in (nth receipts 2) [:error :code]))))
    (reset! domain (:state (commands/retire-epoch @domain)))
    (command! gw "u1" (assoc base :command-id "c2"))
    (let [last-receipt (->> @(:out gw)
                            (map second)
                            (map second)
                            (filter #(= "hc/command-receipt" (:name %)))
                            last
                            :value)]
      (is (= :COMMAND_EPOCH_RETIRED
             (get-in last-receipt [:error :code]))))))

(deftest resync-heals-a-stale-gateway-subscription
  (let [gw (make-gw nil {:max-pending 1})
        _ (subscribe! gw "u1" 1)
        _ (event! gw {:uid "u1" :event [:hc/ack {:subscription-id 1 :seq 0}]})
        _ (gateway/publish! gw {:type :state-delta
                                :delta [{"op" "add" "path" "/a" "value" 1}]})
        _ (gateway/publish! gw {:type :state-delta
                                :delta [{"op" "add" "path" "/b" "value" 2}]})]
    (is (:stale? (feed/subscription (:feed gw) "u1" 1)))
    (let [reply (event! gw {:uid "u1"
                            :event [:hc/resync {:subscription-id 1}]})]
      (is (:ok? reply))
      (is (not (:stale? (feed/subscription (:feed gw) "u1" 1))))
      (is (= "STATE_SNAPSHOT" (-> @(:out gw) last second second :type))))))

(deftest sente-message-boundary
  (let [gw (make-gw nil)
        _ (subscribe! gw "u1" 1)
        before (gateway/snapshot gw)
        huge (apply str (repeat (inc gateway/default-max-message-bytes) "x"))
        result (gateway/publish! gw {:type :state-snapshot :snapshot {:x huge}})]
    (is (not (:ok? result)))
    (is (= :SENTE_MESSAGE_TOO_LARGE (get-in result [:error :code])))
    (is (= before (gateway/snapshot gw))))
  (let [calls (atom [])
        gw (make-gw (owner-calls calls))
        _ (subscribe! gw "u1" 1)
        huge (apply str (repeat (inc gateway/default-max-message-bytes) "x"))
        reply (command! gw "u1" {:command-id "c1"
                                 :command-epoch 1
                                 :subscription-id 1
                                 :payload {:kind :user/message :text huge}})]
    (is (= :SENTE_MESSAGE_TOO_LARGE (get-in reply [:error :code])))
    (is (empty? @calls)))
  (testing "the Sente packer rejects an oversized packed value"
    (let [packer (sente/->BoundedPacker (sente-transit/get-packer :json) 64)
          result (promise)]
      (sente-i/pack packer false {:x (apply str (repeat 256 "y"))}
                    #(deliver result %))
      (is (= :SENTE_MESSAGE_TOO_LARGE
             (get-in (ex-data (:error @result)) [:code])))
      (is (some? (:error @result)))))
  (testing "quote-heavy strings are measured after escaping"
    (let [gw (make-gw nil)
          _ (subscribe! gw "u1" 1)
          before (gateway/snapshot gw)
          huge (apply str (repeat 600000 "\""))
          result (gateway/publish! gw
                                   {:type :state-snapshot
                                    :snapshot {:x huge}})]
      (is (not (:ok? result)))
      (is (= :SENTE_MESSAGE_TOO_LARGE (get-in result [:error :code])))
      (is (= before (gateway/snapshot gw)))))
  (testing "maps with many small keys are measured with their punctuation"
    (let [gw (make-gw nil)
          _ (subscribe! gw "u1" 1)
          before (gateway/snapshot gw)
          huge (into {} (map (fn [index]
                               [(str "key-" index)
                                (str "value-" index)])
                            (range 60000)))
          result (gateway/publish! gw
                                   {:type :state-snapshot
                                    :snapshot huge})]
      (is (not (:ok? result)))
      (is (= :SENTE_MESSAGE_TOO_LARGE (get-in result [:error :code])))
      (is (= before (gateway/snapshot gw))))))

(deftest chsk-post-body-is-bounded-for-binary-requests
  (let [gw (make-gw nil {:max-sente-body 1024})
        handler (http/ring-handler gw)
        body (java.io.ByteArrayInputStream. (byte-array 2048))
        response (handler {:uri "/chsk"
                           :request-method :post
                           :headers {"host" "127.0.0.1:7575"
                                     "content-type" "application/octet-stream"}
                           :remote-addr "127.0.0.1"
                           :body body})]
    (is (= 413 (:status response)))
    (is (= :SENTE_BODY_TOO_LARGE
           (:error (edn/read-string (:body response)))))))

(deftest active-blob-content-is-sandboxed
  (let [store (ports/memory-blob-port (atom {}))
        bytes (.getBytes "<!doctype html><script>alert(1)</script>"
                         java.nio.charset.StandardCharsets/UTF_8)
        {:keys [id]} (ports/put-blob! store
                                      (java.io.ByteArrayInputStream. bytes)
                                      {})
        opts {:content-type-fn (fn [_ _] "text/html")}
        request {:uri (str "/blobs/" id)
                 :request-method :get
                 :headers {}}
        response (blob/handle store opts request)
        named (blob/handle store opts
                           (assoc request :params {"filename" "page.html"}))]
    (is (= 200 (:status response)))
    (is (= "text/html" (get-in response [:headers "content-type"])))
    (is (= "sandbox" (get-in response [:headers "content-security-policy"])))
    (is (= "nosniff" (get-in response [:headers "x-content-type-options"])))
    (is (= "attachment; filename=\"page.html\""
           (get-in named [:headers "content-disposition"])))))

(deftest unknown-query-params-stay-strings
  (let [seen (atom nil)
        gw {:feed (feed/create {})
            :socket {:ajax-get-or-ws-handshake-fn
                     (fn [request]
                       (reset! seen request)
                       {:status 200 :headers {} :body "ok"})
                     :ajax-post-fn (fn [_] {:status 200})}
            :owner nil
            :store nil
            :stopped? (atom false)
            :opts {}}
        handler (http/ring-handler gw)
        response (handler {:uri "/chsk"
                           :request-method :get
                           :query-string "client-id=c&unique-param-xyz=1"
                           :headers {"host" "127.0.0.1:7575"}
                           :remote-addr "127.0.0.1"})]
    (is (= 200 (:status response)))
    (is (= "c" (get-in @seen [:params :client-id])))
    (is (= "1" (get-in @seen [:params "unique-param-xyz"])))
    (is (not (contains? (:params @seen) :unique-param-xyz)))
    (let [malformed (handler {:uri "/chsk"
                              :request-method :get
                              :query-string "client-id=%ZZ"
                              :headers {"host" "127.0.0.1:7575"}
                              :remote-addr "127.0.0.1"})]
      (is (= 200 (:status malformed))))))
