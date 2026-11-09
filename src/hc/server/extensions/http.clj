(ns hc.server.extensions.http
  "Bounded HTTP reads shared by skill links and MCP transports.

  This namespace owns no policy: it returns status, headers and bytes, and
  refuses bodies larger than the caller's limit.  Authentication and redirect
  decisions remain with the caller."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder
                          HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn make-client
  [timeout-ms]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis (long timeout-ms)))
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.build)))

(defn header-map
  [^HttpResponse response]
  (let [result (transient {})]
    (doseq [^java.util.Map$Entry entry (.entrySet (.map (.headers response)))]
      (assoc! result (str (.getKey entry)) (vec (.getValue entry))))
    (persistent! result)))

(defn- add-header
  [^HttpRequest$Builder builder k v]
  (if (sequential? v)
    (reduce (fn [^HttpRequest$Builder b item] (.header b (str k) (str item)))
            builder
            v)
    (.header builder (str k) (str v))))

(defn send
  "Send a request and read at most `limit` body bytes.

  `opts` supports `:method`, `:headers`, `:body` (bytes or nil), `:timeout-ms`,
  `:limit`, and `:client`.  The response body is always consumed before this
  returns, so a caller cannot accidentally leave a connection open."
  [url {:keys [method headers body timeout-ms limit client]
        :or {method :get timeout-ms 10000 limit (* 2 1024 1024)}}]
  (let [^HttpClient http-client (or client (make-client timeout-ms))
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis (long timeout-ms))))
        builder (if (= :post method)
                  (.POST builder (if (nil? body)
                                   (HttpRequest$BodyPublishers/noBody)
                                   (HttpRequest$BodyPublishers/ofByteArray ^bytes body)))
                  (.GET builder))
        builder (reduce-kv add-header builder (or headers {}))
        request (.build builder)
        response (.send http-client request (HttpResponse$BodyHandlers/ofInputStream))
        status (.statusCode response)
        body-bytes (common/read-limited (.body response) limit)]
    {:status status
     :headers (header-map response)
     :body body-bytes}))

(defn content-type
  [response]
  (some-> (get-in response [:headers "content-type"])
          first
          (str/split #";" 2)
          first
          str/trim
          str/lower-case))

(defn ok-status?
  [status]
  (<= 200 status 299))

(defn auth-status?
  [status]
  (or (= status 401) (= status 403)))
