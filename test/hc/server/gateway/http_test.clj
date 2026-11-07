(ns hc.server.gateway.http-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.jdk-http :as jdk-http]
            [hc.server.gateway.ports :as ports]
            [taoensso.sente.interfaces :as sente-i]
            [taoensso.sente.packers.transit :as sente-transit])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def ^:private packer (sente-transit/get-packer :json))

(defn- pack
  [value]
  (let [result (promise)]
    (sente-i/pack packer false value #(deliver result %))
    (let [{:keys [value error]} @result]
      (if error (throw error) value))))

(defn- unpack
  [value]
  (let [result (promise)]
    (sente-i/unpack packer false value #(deliver result %))
    (let [{:keys [value error]} @result]
      (if error (throw error) value))))

(defn- url-encode
  [value]
  (URLEncoder/encode value "UTF-8"))

(defn- response-map
  [^java.net.http.HttpResponse response]
  {:status (.statusCode response)
   :headers (into {}
                  (for [[k values] (.map (.headers response))]
                    [(str/lower-case k) (first values)]))
   :body (.body response)})

(defn- request
  [^HttpClient client ^HttpRequest request]
  (response-map (.send client request (HttpResponse$BodyHandlers/ofString))))

(defn- GET
  ([client url] (GET client url {}))
  ([^HttpClient client url headers]
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds 12))
                     (.GET))]
     (doseq [[k v] headers] (.header builder k v))
     (request client (.build builder)))))

(defn- HEAD
  [^HttpClient client url]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 12))
                    (.method "HEAD" (HttpRequest$BodyPublishers/noBody)))]
    (request client (.build builder))))

(defn- PUT
  [^HttpClient client url ^bytes bytes]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 12))
                    (.PUT (HttpRequest$BodyPublishers/ofByteArray bytes)))]
    (request client (.build builder))))

(defn- chsk-post
  [client base event cb-uuid]
  (let [packed (pack [event cb-uuid])
        body (str "client-id=smoke&ppstr=" (url-encode packed))
        builder (-> (HttpRequest/newBuilder (URI/create (str base "/chsk")))
                    (.timeout (Duration/ofSeconds 12))
                    (.header "content-type"
                             "application/x-www-form-urlencoded")
                    (.POST (HttpRequest$BodyPublishers/ofString body)))]
    (request client (.build builder))))

(defn- unpack-post
  [response]
  (let [[reply cb] (unpack (:body response))]
    {:reply reply :callback cb}))

(defn- poll-events
  "Fetch one non-empty Sente Ajax poll batch, retrying a timeout."
  [client base]
  (loop [attempt 0]
    (let [response (GET client (str base "/chsk?client-id=smoke"))
          [events _] (unpack (:body response))]
      (cond
        (and (= 200 (:status response))
             (vector? events)
             (seq events))
        events

        (< attempt 3)
        (do (Thread/sleep 100) (recur (inc attempt)))

        :else
        []))))

(defn- pushed
  [events]
  (for [event events
        :when (and (vector? event)
                   (= :hc/event (first event)))]
    (second event)))

(defn- find-type
  [events type]
  (some #(when (= type (:type %)) %) (pushed events)))

(defn- fake-owner
  []
  (ports/owner-port
   (fn [request reply]
     (reply {:ok? true
             :replayed? false
             :receipt {:status :accepted
                       :command-id (:command-id request)
                       :command-epoch (:command-epoch request)}}))
   (fn [command-id]
     (when (= "cmd-http" command-id)
       {:status :accepted :command-id command-id :command-epoch 1}))))

(deftest actual-http-sente-and-blob-smoke
  (let [blobs (atom {})
        gw (gateway/start! {:sch-adapter (jdk-http/get-sch-adapter
                                          {:timeout-ms 15000})
                            :owner (fake-owner)
                            :store (ports/memory-blob-port blobs)
                            :lp-timeout-ms 5000
                            :max-upload-bytes 10
                            :fallback (fn [_]
                                        {:status 200
                                         :headers {"content-type" "text/plain"}
                                         :body "app"})})
        server (jdk-http/start! {:handler (gateway/handler gw)})
        base (str "http://127.0.0.1:" (:port server))
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 5))
                   (.build))]
    (try
      (testing "health and application fallback"
        (let [health (GET client (str base "/healthz"))]
          (is (= 200 (:status health)))
          (is (:ok (edn/read-string (:body health)))))
        (is (= 403 (:status (GET client (str base "/healthz")
                                     {"origin" "http://evil.example"}))))
        (let [root (GET client (str base "/"))]
          (is (= 200 (:status root)))
          (is (= "app" (:body root)))))

      (testing "real Sente Ajax handshake"
        (let [denied (GET client
                          (str base "/chsk?client-id=evil&handshake%3F=true")
                          {"origin" "http://evil.example"})]
          (is (= 403 (:status denied))))
        (let [allowed (GET client
                           (str base "/chsk?client-id=origin-smoke&handshake%3F=true")
                           {"origin" base})]
          (is (= 200 (:status allowed))))
        (let [response (GET client
                            (str base "/chsk?client-id=smoke&handshake%3F=true"))
              [event _] (unpack (:body response))]
          (is (= 200 (:status response)))
          (is (= :chsk/handshake (first event)))
          (is (= "smoke" (first (second event))))))

      (testing "subscribe delivers an atomic baseline"
        (let [{:keys [reply callback]}
              (unpack-post (chsk-post client base
                                      [:hc/subscribe {:subscription-id 1}]
                                      "cb-sub"))]
          (is (:ok? reply))
          (is (= 1 (:subscription-id reply))))
        (let [snapshot (or (find-type (poll-events client base) "STATE_SNAPSHOT")
                           (do
                             (unpack-post (chsk-post client base
                                                     [:hc/resync
                                                      {:subscription-id 1}]
                                                     "cb-resync"))
                             (find-type (poll-events client base)
                                        "STATE_SNAPSHOT")))]
          (is (some? snapshot))
          (is (= 1 (:epoch snapshot)))
          (is (= 0 (:seq snapshot)))
          (is (= 1 (:subscription-id snapshot)))))

      (testing "browser internal command kinds are denied before the Owner"
        (doseq [kind [:app/issue-epoch "app/issue-epoch"]]
          (let [suffix (if (keyword? kind) "kw" "str")
                {:keys [reply]}
                (unpack-post (chsk-post
                              client base
                              [:hc/command
                               {:command-id (str "cmd-epoch-" suffix)
                                :command-epoch 1
                                :subscription-id 1
                                :payload {:kind kind}}]
                              (str "cb-epoch-" suffix)))]
            (is (= :COMMAND_KIND_FORBIDDEN (get-in reply [:error :code])))
            (is (= :operator-message-required
                   (get-in reply [:error :details :action]))))))

      (testing "STATE_DELTA advances the sequence over HTTP"
        (is (:ok? (gateway/publish!
                   gw
                   {:type :state-delta
                    :delta [{"op" "add" "path" "/x" "value" 1}]})))
        (let [delta (find-type (poll-events client base) "STATE_DELTA")]
          (is (some? delta))
          (is (= 1 (:seq delta)))
          (is (= 1 (:subscription-id delta)))))

      (testing "commands reach the Owner and return a CUSTOM receipt"
        (let [{:keys [reply]}
              (unpack-post (chsk-post
                            client base
                            [:hc/command
                             {:command-id "cmd-http"
                              :command-epoch 1
                              :subscription-id 1
                              :payload {:text "hi"}}]
                            "cb-cmd"))]
          (is (:ok? reply))
          (is (= :submitted (:status reply))))
        (let [receipt (some #(when (= "hc/command-receipt" (:name %)) %)
                            (pushed (poll-events client base)))]
          (is (some? receipt))
          (is (= "cmd-http" (get-in receipt [:value :command-id])))
          (is (= :accepted (get-in receipt [:value :receipt :status])))))

      (testing "a superseded subscription cannot command"
        (unpack-post (chsk-post client base
                                [:hc/subscribe {:subscription-id 2}]
                                "cb-sub2"))
        ;; Drain the new baseline so it cannot race the command reply on the
        ;; next Ajax POST.
        (poll-events client base)
        (let [{:keys [reply]}
              (unpack-post (chsk-post
                            client base
                            [:hc/command
                             {:command-id "cmd-stale"
                              :command-epoch 1
                              :subscription-id 1
                              :payload {}}]
                            "cb-stale"))]
          (is (= :STALE_SUBSCRIPTION (get-in reply [:error :code])))))

      (testing "blob transport is independent of Sente"
        (let [content (.getBytes "hello blob" StandardCharsets/UTF_8)
              upload (PUT client (str base "/blobs") content)
              {:keys [id size]} (edn/read-string (:body upload))]
          (is (= 201 (:status upload)))
          (is (= (alength content) size))
          (is (str/starts-with? id "sha256-"))

          (let [download (GET client (str base "/blobs/" id))]
            (is (= 200 (:status download)))
            (is (= "hello blob" (:body download)))
            (is (= (str "\"" id "\"") (get-in download [:headers "etag"])))
            (is (= (str size) (get-in download [:headers "content-length"]))))

          (is (= 403 (:status (GET client (str base "/blobs/" id)
                                     {"origin" "http://evil.example"}))))
          (is (= 200 (:status (GET client (str base "/blobs/" id)
                                     {"origin" base}))))

          (let [head (HEAD client (str base "/blobs/" id))]
            (is (= 200 (:status head)))
            (is (= "" (:body head)))
            (is (= (str size) (get-in head [:headers "content-length"]))))

          (let [range (GET client (str base "/blobs/" id)
                           {"range" "bytes=0-4"})]
            (is (= 206 (:status range)))
            (is (= "hello" (:body range)))
            (is (= "bytes 0-4/10" (get-in range [:headers "content-range"]))))

          (let [bad-range (GET client (str base "/blobs/" id)
                               {"range" "bytes=99-100"})]
            (is (= 416 (:status bad-range)))
            (is (= "bytes */10"
                   (get-in bad-range [:headers "content-range"]))))

          (let [cached (GET client (str base "/blobs/" id)
                            {"if-none-match" (str "\"" id "\"")})]
            (is (= 304 (:status cached)))
            (is (= "" (:body cached))))

          (let [named (GET client (str base "/blobs/" id
                                       "?filename=hello.txt"))]
            (is (= "attachment; filename=\"hello.txt\""
                   (get-in named [:headers "content-disposition"]))))

          (is (= 404 (:status (GET client
                                   (str base "/blobs/sha256-"
                                        (apply str (repeat 64 "0")))))))

          (let [too-large (PUT client (str base "/blobs")
                               (.getBytes "hello blob!" StandardCharsets/UTF_8))]
            (is (= 413 (:status too-large)))
            (is (= :BLOB_TOO_LARGE
                   (:error (edn/read-string (:body too-large))))))))

      (finally
        ((:stop! server))
        (gateway/stop! gw)))))

(deftest a-configured-sente-boundary-cannot-exceed-one-mib
  (let [gw (gateway/start! {:sch-adapter (jdk-http/get-sch-adapter
                                          {:timeout-ms 1000})
                            :max-message-bytes (* 2 1024 1024)
                            :max-sente-body (* 2 1024 1024)
                            :max-sente-message-bytes (* 2 1024 1024)})
        huge (apply str (repeat (inc gateway/default-max-message-bytes) "x"))]
    (try
      (let [result (gateway/publish! gw
                                     {:type :state-snapshot
                                      :snapshot {:x huge}})]
        (is (not (:ok? result)))
        (is (= :SENTE_MESSAGE_TOO_LARGE (get-in result [:error :code]))))
      (finally
        (gateway/stop! gw)))))

(deftest invalid-initial-gateway-config-is-refused
  (let [adapter (jdk-http/get-sch-adapter {:timeout-ms 1000})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (gateway/start! {:sch-adapter adapter
                                  :snapshot []})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gateway/start! {:sch-adapter adapter
                                  :epoch 0})))))

(deftest large-state-object-over-http
  (let [blobs (atom {})
        gw (gateway/start! {:sch-adapter (jdk-http/get-sch-adapter
                                          {:timeout-ms 15000})
                            :store (ports/memory-blob-port blobs)
                            :lp-timeout-ms 5000
                            :max-message-bytes 512
                            :max-state-object-bytes (* 1024 1024)
                            :max-state-objects 8})
        server (jdk-http/start! {:handler (gateway/handler gw)})
        base (str "http://127.0.0.1:" (:port server))
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 5))
                   (.build))]
    (try
      (unpack-post (chsk-post client base
                              [:hc/subscribe {:subscription-id 1}]
                              "cb-object-sub"))
      ;; Drain the initial empty baseline before publishing the large tree.
      (poll-events client base)
      (let [snapshot {:payload (apply str (repeat 2000 "x"))}
            published (gateway/publish! gw {:type :state-snapshot
                                            :snapshot snapshot})
            event (loop [attempt 0]
                    (let [candidate
                          (some #(when (and (= "STATE_SNAPSHOT" (:type %))
                                            (:snapshot-ref %))
                                  %)
                                (pushed (poll-events client base)))]
                      (cond
                        candidate candidate
                        (< attempt 3) (recur (inc attempt))
                        :else nil)))
            ref (:snapshot-ref event)
            token (:token ref)
            url (str base "/sync-objects/" token)]
        (is (:ok? published))
        (is (some? ref))
        (is (nil? (:snapshot event)))
        (is (= 1 (:epoch ref)))
        (is (= 1 (:seq ref)))
        (testing "the reference route streams the exact EDN bytes without caching"
          (let [download (GET client url)]
            (is (= 200 (:status download)))
            (is (= "no-store, private"
                   (get-in download [:headers "cache-control"])))
            (is (= "nosniff"
                   (get-in download [:headers "x-content-type-options"])))
            (is (= (str (:size ref))
                   (get-in download [:headers "content-length"])))
            (is (= snapshot (edn/read-string (:body download))))))
        (testing "HEAD returns metadata and no body"
          (let [head (HEAD client url)]
            (is (= 200 (:status head)))
            (is (= "" (:body head)))
            (is (= (str (:size ref))
                   (get-in head [:headers "content-length"])))))
        (testing "authorization and unknown tokens fail closed"
          (is (= 403 (:status (GET client url
                                   {"origin" "http://evil.example"}))))
          (let [unknown (GET client
                             (str base "/sync-objects/"
                                  (apply str (repeat 32 "0"))))]
            (is (= 404 (:status unknown)))
            (is (= :SYNC_OBJECT_NOT_FOUND
                   (:error (edn/read-string (:body unknown)))))))
        (testing "an old epoch token is rejected"
          (is (:ok? (gateway/publish!
                     gw
                     {:type :state-snapshot
                      :snapshot {:payload (apply str (repeat 2000 "y"))}
                      :new-epoch? true})))
          (let [stale (GET client url)]
            (is (= 410 (:status stale)))
            (let [body (edn/read-string (:body stale))]
              (is (= :SYNC_OBJECT_STALE_EPOCH (:error body)))
              (is (= {:epoch 1 :current-epoch 2}
                     (select-keys (:details body)
                                  [:epoch :current-epoch])))))))
      (finally
        ((:stop! server))
        (gateway/stop! gw)))))
