(ns hc.server.backup.s3-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.backup :as backup]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.package :as package]
            [hc.server.backup.port :as port]
            [hc.server.backup.support :as support]
            [hc.server.backup.s3 :as s3])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URI)
           (java.nio.charset StandardCharsets)
           (java.time ZonedDateTime ZoneOffset)))

(defn- response!
  [exchange status body]
  (let [bytes (when body (.getBytes (str body) StandardCharsets/UTF_8))]
    (when bytes
      (.set (.getResponseHeaders exchange) "content-type" "application/xml"))
    (.sendResponseHeaders exchange status (if bytes (alength ^bytes bytes) -1))
    (when bytes
      (with-open [out (.getResponseBody exchange)]
        (.write out ^bytes bytes)))))

(defn- list-xml
  [keys]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
       (apply str
              (for [key keys]
                (str "<Contents><Key>" key "</Key>"
                     "<Size>0</Size>"
                     "<LastModified>2026-11-03T00:00:00.000Z</LastModified>"
                     "</Contents>")))
       "<IsTruncated>false</IsTruncated>"
       "</ListBucketResult>"))

(defn- start-server
  [state]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (let [headers (.getRequestHeaders exchange)
                            header (fn [name]
                                     (some (fn [[k v]]
                                             (when (= (str/lower-case (str k))
                                                      (str/lower-case name))
                                               (first v)))
                                           headers))
                            auth (header "authorization")
                            amz-date (header "x-amz-date")
                            payload (header "x-amz-content-sha256")
                            method (.getRequestMethod exchange)
                            path (.getPath (.getRequestURI exchange))
                            query (.getQuery (.getRequestURI exchange))]
                        (if-not (and (str/starts-with? (str auth)
                                                       "AWS4-HMAC-SHA256 Credential=AKTEST/")
                                     (some? amz-date)
                                     (some? payload))
                          (response! exchange 403 "bad signature")
                          (case method
                            "PUT"
                            (let [body (.readAllBytes (.getRequestBody exchange))]
                              (swap! state assoc-in [:objects path] body)
                              (response! exchange 200 nil))

                            "GET"
                            (if (and (= path "/bucket") (some? query))
                              (response! exchange 200
                                         (list-xml (keys (get-in @state [:objects]))))
                              (if-let [body (get-in @state [:objects path])]
                                (do (.sendResponseHeaders exchange 200
                                                          (alength ^bytes body))
                                    (with-open [out (.getResponseBody exchange)]
                                      (.write out ^bytes body)))
                                (response! exchange 404 "missing")))

                            "DELETE"
                            (if (get-in @state [:objects path])
                              (do (swap! state update :objects dissoc path)
                                  (response! exchange 204 nil))
                              (response! exchange 404 "missing"))

                            (response! exchange 405 "method"))))
                      (finally
                        (.close exchange)))))]
    (.createContext server "/" handler)
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))
     :state state}))

(defn- stop-server!
  [{:keys [server]}]
  (.stop server 0))

(defn- sealed
  [id text]
  (package/seal-payload
   {:backup-id id
    :sampled-at "2026-11-03T00:00:00Z"
    :created-at "2026-11-03T00:00:00Z"
    :device "test"
    :app-version "0.0.1"}
   (codec/canonical-bytes {:format :hc.backup/payload
                           :format-version 1
                           :backup-id id
                           :text text})
   {:encrypt? false}))

(deftest s3-target-real-http-roundtrip
  (let [state (atom {:objects {}})
        server (start-server state)]
    (try
      (let [target (s3/target {:endpoint (str "http://127.0.0.1:" (:port server))
                               :bucket "bucket"
                               :region "us-east-1"
                               :access-key "AKTEST"
                               :secret-key "secret"
                               :prefix "backups"
                               :timeout-ms 5000})
            object (sealed "b-1" "hello")
            written (port/put-object! target {:id "b-1" :bytes (:bytes object)})]
        (is (= :verified (:integrity written)))
        (is (= (seq (:bytes object)) (seq (port/get-object target "b-1"))))
        (let [listed (port/list-objects target)]
          (is (= 1 (count listed)))
          (is (= "b-1" (:id (first listed))))
          (is (= :verified (:integrity (first listed)))))
        (is (= :verified
               (:integrity (port/verify-object target "b-1"
                                               (codec/sha256-hex (:bytes object))))))
        (testing "identical bytes deduplicate"
          (is (true? (:deduplicated?
                      (port/put-object! target {:id "b-1" :bytes (:bytes object)})))))
        (testing "different bytes conflict"
          (let [other (sealed "b-1" "different")]
            (is (= :BACKUP_OBJECT_CONFLICT
                   (try
                     (port/put-object! target {:id "b-1" :bytes (:bytes other)})
                     ::no-throw
                     (catch clojure.lang.ExceptionInfo e
                       (:error (ex-data e))))))))
        (testing "the real target carries a complete encrypted backup"
          (let [made (backup/backup! (support/domain (support/backup-state))
                                     target
                                     {:password "correct horse battery staple"
                                      :kdf-iterations 10000
                                      :now "2026-11-03T00:00:00Z"})
                id (get-in made [:backup :id])
                opened (backup/open-backup target id
                                           "correct horse battery staple")]
            (is (= :complete (:status made)))
            (is (true? (get-in made [:backup :encrypted?])))
            (is (= 1 (:count (get-in made [:manifest :attachments]))))
            (is (map? (:payload opened)))))
        (testing "delete is idempotent"
          (is (= :deleted (port/delete-object! target "b-1")))
          (is (nil? (port/get-object target "b-1")))
          (is (= :deleted (port/delete-object! target "b-1")))))
      (finally
        (stop-server! server)))))


(deftest s3-signature-matches-aws-documented-vector
  (let [target (s3/target {:endpoint "https://examplebucket.s3.amazonaws.com"
                           :bucket "examplebucket"
                           :region "us-east-1"
                           :access-key "AKIAIOSFODNN7EXAMPLE"
                           :secret-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                           :path-style? false})
        now (ZonedDateTime/of 2013 5 24 0 0 0 0 ZoneOffset/UTC)
        headers (#'s3/signed-headers target :get (URI. "https://examplebucket.s3.amazonaws.com/")
                                     {"lifecycle" ""} nil now)]
    (is (= "fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"
           (some-> (re-find #"Signature=([0-9a-f]+)"
                            (get headers "authorization"))
                   second)))
    (is (= "20130524T000000Z" (get headers "x-amz-date")))))
