(ns hc.server.extensions.mcp.http
  "MCP transports over HTTP.

  Two shapes are supported:

  * `:http` — streamable HTTP: every JSON-RPC message is a POST, and the
    response may be JSON or an SSE stream.
  * `:sse` — the older two-channel shape: a GET stream carries an `endpoint`
    event and then server messages, while requests are POSTed to that endpoint.

  Both paths stop after listing tools.  No `tools/call` is sent by this
  namespace."
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.http :as web]
            [hc.server.extensions.mcp.json :as json])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private eof ::eof)
(def ^:private response-limit (* 4 1024 1024))

(defn- header-value
  [response name]
  (let [target (str/lower-case name)]
    (some (fn [[k vs]]
            (when (= target (str/lower-case k)) (first vs)))
          (:headers response))))

(defn- auth-error
  [status]
  {:ok? false
   :error {:code :MCP_AUTH_REQUIRED
           :message "server rejected the credentials or requires a login"
           :details {:http-status status}}})

(defn- http-error
  [status body]
  {:ok? false
   :error {:code :MCP_HTTP_STATUS
           :message (str "server returned HTTP " status)
           :details {:http-status status
                     :body (common/truncate
                            (or (when body (common/bytes->utf8 body)) "")
                            512)}}})

(defn- parse-json-message
  [body]
  (let [text (common/bytes->utf8 body)]
    (when-not text
      (error/fail! :MCP_PROTOCOL "response body is not UTF-8" {}))
    (json/read-str text)))

(defn- rpc-error
  [error-object]
  {:ok? false
   :error {:code :MCP_JSON_RPC
           :message (or (json/field error-object :message)
                        "server returned a JSON-RPC error")
           :details {:rpc-error error-object}}})

(defn- has-key?
  [m k]
  (or (contains? m k) (contains? m (name k))))

(defn- parse-error?
  [message]
  (has-key? message :__parse-error))

(defn- parse-error-value
  [message]
  (or (get message :__parse-error) (get message "__parse-error")))

(defn- server-request?
  [message]
  (and (map? message)
       (some? (json/field message :method))
       (some? (json/field message :id))))

(defn- response-for
  [message id]
  (and (map? message)
       (= id (json/field message :id))
       (or (has-key? message :result) (has-key? message :error))))

(defn- response-value
  [message]
  (if (has-key? message :error)
    (rpc-error (json/field message :error))
    {:ok? true :result (json/field message :result)}))

(defn- parse-sse-text
  "Parse a complete SSE body into a vector of `{:event :data :id}` maps."
  [text]
  (let [lines (str/split-lines (or text ""))]
    (loop [lines lines
           event nil
           id nil
           data []
           result []]
      (if-not (seq lines)
        (if (or event (seq data))
          (conj result {:event (or event "message")
                        :id id
                        :data (str/join "\n" data)})
          result)
        (let [line (first lines)]
          (cond
            (str/blank? line)
            (recur (rest lines) nil nil []
                   (if (or event (seq data))
                     (conj result {:event (or event "message")
                                   :id id
                                   :data (str/join "\n" data)})
                     result))

            (str/starts-with? line ":")
            (recur (rest lines) event id data result)

            :else
            (let [idx (str/index-of line ":")
                  field (if idx (subs line 0 idx) line)
                  value (if idx
                          (let [v (subs line (inc idx))]
                            (if (str/starts-with? v " ") (subs v 1) v))
                          "")]
              (case field
                "event" (recur (rest lines) value id data result)
                "id" (recur (rest lines) event value data result)
                "data" (recur (rest lines) event id (conj data value) result)
                (recur (rest lines) event id data result)))))))))

(defn- find-response
  [id messages]
  (some #(when (response-for % id) %) messages))

(defn- request-headers
  [connection]
  (cond-> (merge {"Content-Type" "application/json"
                  "Accept" "application/json, text/event-stream"}
                 (:headers connection))
    (some? @(:session-id connection))
    (assoc "Mcp-Session-Id" @(:session-id connection))))

(defn- send-post
  [connection url body]
  (try
    {:ok? true
     :response (web/send url {:method :post
                              :headers (request-headers connection)
                              :body (common/utf8-bytes (json/write-str body))
                              :timeout-ms (:timeout-ms connection 10000)
                              :limit response-limit
                              :client (:client connection)})}
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        (throw t)))
    (catch java.net.http.HttpTimeoutException t
      {:ok? false
       :error {:code :MCP_TIMEOUT
               :message "HTTP request did not complete before the deadline"
               :details {:url url}}})
    (catch Exception t
      {:ok? false
       :error {:code :MCP_TRANSPORT
               :message (or (.getMessage ^Throwable t) (str t))
               :details {:url url}}})))

(defn- capture-session!
  [connection response]
  (when-let [session (header-value response "Mcp-Session-Id")]
    (reset! (:session-id connection) session)))

(defn- send-request-http!
  [connection method params timeout-ms]
  (let [id (swap! (:next-id connection) inc)
        body {:jsonrpc "2.0" :id id :method method :params (or params {})}
        response (send-post connection (:url connection) body)]
    (if-not (:ok? response)
      response
      (let [{:keys [status body]} (:response response)]
        (cond
          (web/auth-status? status) (auth-error status)
          (not (web/ok-status? status)) (http-error status body)
          :else
          (do
            (capture-session! connection response)
            (let [content-type (web/content-type response)]
              (cond
                (and content-type (str/includes? content-type "text/event-stream"))
                (let [messages (mapcat (fn [event]
                                         (when (= "message" (:event event))
                                           [(parse-json-message
                                             (common/utf8-bytes (:data event)))]))
                                       (parse-sse-text (common/bytes->utf8 body)))
                      found (find-response id messages)]
                  (if found
                    (response-value found)
                    {:ok? false
                     :error {:code :MCP_PROTOCOL
                             :message "SSE response did not contain the matching id"
                             :details {:method method}}}))

                (or (nil? content-type)
                    (str/includes? content-type "application/json"))
                (if (empty? body)
                  {:ok? false
                   :error {:code :MCP_EMPTY_RESPONSE
                           :message "server returned an empty response"
                           :details {:method method}}}
                  (let [message (try
                                  (parse-json-message body)
                                  (catch clojure.lang.ExceptionInfo t
                                    {:__parse-error (error/error-map t)}))]
                    (cond
                      (parse-error? message)
                      {:ok? false
                       :error {:code :MCP_PROTOCOL
                               :message "response is not JSON-RPC"
                               :details {:method method
                                         :parse (parse-error-value message)}}}

                      (response-for message id) (response-value message)

                      :else
                      {:ok? false
                       :error {:code :MCP_PROTOCOL
                               :message "response id does not match the request"
                               :details {:method method}}})))

                :else
                {:ok? false
                 :error {:code :MCP_PROTOCOL
                         :message "unsupported response content type"
                         :details {:content-type content-type}}}))))))))

(defn- send-notify-http!
  [connection method params]
  (let [body {:jsonrpc "2.0" :method method :params (or params {})}
        response (send-post connection (:url connection) body)]
    (if-not (:ok? response)
      response
      (let [{:keys [status body]} (:response response)]
        (cond
          (web/auth-status? status) (auth-error status)
          (not (web/ok-status? status)) (http-error status body)
          :else (do (capture-session! connection response)
                    {:ok? true}))))))

(defn connect-http!
  [server opts]
  (let [transport (:transport server)
        timeout-ms (:timeout-ms opts 10000)]
    (try
      {:ok? true
       :connection {:kind :http
                    :client (web/make-client timeout-ms)
                    :url (:url transport)
                    :headers (or (:headers transport) {})
                    :timeout-ms timeout-ms
                    :next-id (atom 0)
                    :session-id (atom nil)
                    :secrets (vals (:headers transport))}}
      (catch Exception t
        {:ok? false
         :error {:code :MCP_TRANSPORT
                 :message (or (.getMessage ^Throwable t) (str t))
                 :details {:url (:url transport)}}}))))

(defn- read-sse-event
  [^BufferedReader reader]
  (loop [event nil id nil data []]
    (let [line (try (.readLine reader) (catch Exception _ nil))]
      (cond
        (nil? line) (when (or event (seq data))
                      {:event (or event "message")
                       :id id
                       :data (str/join "\n" data)})

        (str/blank? line)
        (if (or event (seq data))
          {:event (or event "message")
           :id id
           :data (str/join "\n" data)}
          (recur nil nil []))

        (str/starts-with? line ":")
        (recur event id data)

        :else
        (let [idx (str/index-of line ":")
              field (if idx (subs line 0 idx) line)
              value (if idx
                      (let [v (subs line (inc idx))]
                        (if (str/starts-with? v " ") (subs v 1) v))
                      "")]
          (case field
            "event" (recur value id data)
            "id" (recur event value data)
            "data" (recur event id (conj data value))
            (recur event id data)))))))

(defn- start-sse-reader
  [^java.io.InputStream stream endpoint-q message-q]
  (doto (Thread.
         (fn []
           (try
             (with-open [reader (BufferedReader. (InputStreamReader. stream StandardCharsets/UTF_8))]
               (loop []
                 (when-let [event (read-sse-event reader)]
                   (case (:event event)
                     "endpoint" (.put ^LinkedBlockingQueue endpoint-q (:data event))
                     "message" (try
                                 (.put ^LinkedBlockingQueue message-q
                                       (json/read-str (:data event)))
                                 (catch clojure.lang.ExceptionInfo t
                                   (.put ^LinkedBlockingQueue message-q
                                         {:__parse-error (error/error-map t)})))
                     nil)
                   (recur))))
             (finally
               (.put ^LinkedBlockingQueue endpoint-q eof)
               (.put ^LinkedBlockingQueue message-q eof))))
         "hc-mcp-sse")
    (.setDaemon true)
    (.start)))

(defn connect-sse!
  [server opts]
  (let [transport (:transport server)
        timeout-ms (:timeout-ms opts 10000)
        client (web/make-client timeout-ms)
        url (:url transport)
        headers (merge {"Accept" "text/event-stream"}
                       (:headers transport))]
    (try
      (let [request (-> (HttpRequest/newBuilder (URI/create url))
                        (.timeout (java.time.Duration/ofMillis (long timeout-ms)))
                        (.GET)
                        (as-> builder
                          (reduce-kv (fn [^HttpRequest$Builder b k v]
                                       (.header b (str k) (str v)))
                                     builder headers))
                        (.build))
            response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofInputStream))
            status (.statusCode response)]
        (cond
          (web/auth-status? status)
          (do (.close (.body response))
              {:ok? false :error (:error (auth-error status))})

          (not (web/ok-status? status))
          (do (.close (.body response))
              {:ok? false :error (:error (http-error status (byte-array 0)))})

          :else
          (let [endpoint-q (LinkedBlockingQueue.)
                message-q (LinkedBlockingQueue.)]
            (start-sse-reader (.body response) endpoint-q message-q)
            (let [endpoint (try
                             (.poll endpoint-q (long timeout-ms) TimeUnit/MILLISECONDS)
                             (catch Exception _ nil))]
              (cond
                (nil? endpoint)
                (do (.close (.body response))
                    {:ok? false
                     :error {:code :MCP_SSE_ENDPOINT_TIMEOUT
                             :message "SSE stream did not announce an endpoint"
                             :details {:url url}}})

                (= eof endpoint)
                (do (.close (.body response))
                    {:ok? false
                     :error {:code :MCP_SSE_ENDPOINT_MISSING
                             :message "SSE stream ended before announcing an endpoint"
                             :details {:url url}}})

                :else
                (let [endpoint-url (str (.resolve (URI/create url) (str/trim (str endpoint))))]
                  {:ok? true
                   :connection {:kind :sse
                                :client client
                                :url url
                                :endpoint endpoint-url
                                :headers (or (:headers transport) {})
                                :timeout-ms timeout-ms
                                :next-id (atom 0)
                                :message-q message-q
                                :stream (.body response)
                                :secrets (vals (:headers transport))}}))))))
      (catch java.net.http.HttpTimeoutException t
        {:ok? false
         :error {:code :MCP_TIMEOUT
                 :message "SSE connection did not open before the deadline"
                 :details {:url url}}})
      (catch Exception t
        {:ok? false
         :error {:code :MCP_TRANSPORT
                 :message (or (.getMessage ^Throwable t) (str t))
                 :details {:url url}}}))))

(defn- send-post-sse!
  [connection body]
  (try
    (let [response (web/send (:endpoint connection)
                             {:method :post
                              :headers (merge {"Content-Type" "application/json"}
                                              (:headers connection))
                              :body (common/utf8-bytes (json/write-str body))
                              :timeout-ms (:timeout-ms connection 10000)
                              :limit response-limit
                              :client (:client connection)})]
      {:ok? true :response response})
    (catch Exception t
      {:ok? false
       :error {:code :MCP_TRANSPORT
               :message (or (.getMessage ^Throwable t) (str t))
               :details {:endpoint (:endpoint connection)}}})))

(defn- wait-sse-response
  [connection id timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (let [remaining (- deadline (System/nanoTime))]
        (if (neg? remaining)
          {:ok? false
           :error {:code :MCP_TIMEOUT
                   :message "SSE server did not answer before the deadline"
                   :details {:timeout-ms timeout-ms}}}
          (let [message (.poll ^LinkedBlockingQueue (:message-q connection)
                               (max 1 (long (/ remaining 1000000)))
                               TimeUnit/MILLISECONDS)]
            (cond
              (nil? message) (recur)
              (= eof message) {:ok? false
                               :error {:code :MCP_PROCESS_EXITED
                                       :message "SSE stream closed before answering"}}
              (parse-error? message) {:ok? false
                                      :error {:code :MCP_PROTOCOL
                                              :message "SSE message is not JSON-RPC"
                                              :details (parse-error-value message)}}
              (server-request? message) (recur)
              (response-for message id) (response-value message)
              :else (recur))))))))

(defn- request-sse!
  [connection method params timeout-ms]
  (let [id (swap! (:next-id connection) inc)
        sent (send-post-sse! connection {:jsonrpc "2.0" :id id
                                         :method method :params (or params {})})]
    (if-not (:ok? sent)
      sent
      (let [{:keys [status body]} (:response sent)]
        (cond
          (web/auth-status? status) (auth-error status)
          (not (web/ok-status? status)) (http-error status body)
          (and body (not (zero? (alength ^bytes body))))
          (let [message (try
                          (parse-json-message body)
                          (catch clojure.lang.ExceptionInfo t
                            {:__parse-error (error/error-map t)}))]
            (if (parse-error? message)
              (wait-sse-response connection id timeout-ms)
              (if (response-for message id)
                (response-value message)
                (wait-sse-response connection id timeout-ms))))
          :else
          (wait-sse-response connection id timeout-ms))))))

(defn- notify-sse!
  [connection method params]
  (let [sent (send-post-sse! connection {:jsonrpc "2.0"
                                         :method method
                                         :params (or params {})})]
    (if-not (:ok? sent)
      sent
      (let [{:keys [status body]} (:response sent)]
        (cond
          (web/auth-status? status) (auth-error status)
          (not (web/ok-status? status)) (http-error status body)
          :else {:ok? true})))))

(defn connect!
  [server opts]
  (if (= :sse (get-in server [:transport :type]))
    (connect-sse! server opts)
    (connect-http! server opts)))

(defn request!
  [connection method params timeout-ms]
  (if (= :sse (:kind connection))
    (request-sse! connection method params timeout-ms)
    (send-request-http! connection method params timeout-ms)))

(defn notify!
  [connection method params]
  (if (= :sse (:kind connection))
    (notify-sse! connection method params)
    (send-notify-http! connection method params)))

(defn close!
  [connection]
  (when (= :sse (:kind connection))
    (try
      (.close ^java.io.InputStream (:stream connection))
      (catch Exception _ nil)))
  {:ok? true})
