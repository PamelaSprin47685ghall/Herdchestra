(ns hc.server.backup.webdav-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.backup :as backup]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.http-common :as http]
            [hc.server.backup.package :as package]
            [hc.server.backup.port :as port]
            [hc.server.backup.support :as support]
            [hc.server.backup.webdav :as webdav])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn- response!
  [exchange status body]
  (let [bytes (when body (.getBytes (str body) StandardCharsets/UTF_8))]
    (when bytes
      (.set (.getResponseHeaders exchange) "content-type" "application/xml"))
    (.sendResponseHeaders exchange status (if bytes (alength ^bytes bytes) -1))
    (when bytes
      (with-open [out (.getResponseBody exchange)]
        (.write out ^bytes bytes)))))

(defn- auth-ok?
  [exchange expected]
  (= expected (.getFirst (.getRequestHeaders exchange) "authorization")))

(defn- propfind-xml
  [paths]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<d:multistatus xmlns:d=\"DAV:\">"
       (apply str
              (for [path paths]
                (str "<d:response><d:href>" path "</d:href>"
                     "<d:propstat><d:prop>"
                     "<d:getcontentlength>0</d:getcontentlength>"
                     "</d:prop><d:status>HTTP/1.1 200 OK</d:status>"
                     "</d:propstat></d:response>")))
       "</d:multistatus>"))

(defn- start-server
  [state expected-auth]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (if-not (auth-ok? exchange expected-auth)
                        (response! exchange 401 "unauthorized")
                        (let [method (.getRequestMethod exchange)
                              path (.getPath (.getRequestURI exchange))]
                          (case method
                            "MKCOL"
                            (do (swap! state update :collections conj path)
                                (response! exchange 201 nil))

                            "PUT"
                            (let [body (.readAllBytes (.getRequestBody exchange))]
                              (swap! state assoc-in [:objects path] body)
                              (response! exchange 201 nil))

                            "GET"
                            (if-let [body (get-in @state [:objects path])]
                              (do (.sendResponseHeaders exchange 200 (alength ^bytes body))
                                  (with-open [out (.getResponseBody exchange)]
                                    (.write out ^bytes body)))
                              (response! exchange 404 "missing"))

                            "DELETE"
                            (if (get-in @state [:objects path])
                              (do (swap! state update :objects dissoc path)
                                  (response! exchange 204 nil))
                              (response! exchange 404 "missing"))

                            "PROPFIND"
                            (let [directory path
                                  objects (keys (get-in @state [:objects]))
                                  paths (into [directory]
                                              (filter #(str/starts-with? % (str directory "/"))
                                                      objects))]
                              (response! exchange 207 (propfind-xml paths)))

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

(deftest webdav-target-real-http-roundtrip
  (let [state (atom {:objects {} :collections #{}})
        auth (str "Basic " (.encodeToString (Base64/getEncoder)
                                            (.getBytes "user:pass"
                                                       StandardCharsets/UTF_8)))
        server (start-server state auth)]
    (try
      (let [target (webdav/target {:base-url (str "http://127.0.0.1:" (:port server) "/dav")
                                   :directory "backups"
                                   :username "user"
                                   :password "pass"
                                   :timeout-ms 5000})
            object (sealed "b-1" "hello")
            written (port/put-object! target {:id "b-1" :bytes (:bytes object)})]
        (is (= :verified (:integrity written)))
        (is (= (seq (:bytes object)) (seq (port/get-object target "b-1"))))
        (let [listed (port/list-objects target)]
          (is (= 1 (count listed)))
          (is (= "b-1" (:id (first listed))))
          (is (= :verified (:integrity (first listed)))))
        (is (= :verified (:integrity
                          (port/verify-object target "b-1"
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
