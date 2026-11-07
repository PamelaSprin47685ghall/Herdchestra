(ns hc.server.extensions.mcp.stdio
  "Newline-delimited JSON-RPC over a child process.

  The program and its arguments are passed to `ProcessBuilder` separately; no
  shell is involved.  Standard error is drained into a bounded buffer so a
  noisy server cannot block on a full pipe, but the raw buffer is only used for
  a redacted diagnostic and is never published as a tool result."
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.mcp.json :as json])
  (:import (java.io BufferedReader BufferedWriter File InputStreamReader
                    OutputStreamWriter)
           (java.nio.charset StandardCharsets)
           (java.util ArrayDeque)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private eof ::eof)
(def ^:private stderr-limit 8192)

(defn- redact-stderr
  [text secrets]
  (-> text
      (common/redact-text secrets)
      (common/truncate 2048)))

(defn- start-stderr-drain
  [^java.io.InputStream stream secrets buffer]
  (doto (Thread.
         (fn []
           (with-open [reader (BufferedReader. (InputStreamReader. stream StandardCharsets/UTF_8))]
             (loop []
               (when-let [line (.readLine reader)]
                 (let [line (str line "\n")]
                   (locking buffer
                     (.addLast ^ArrayDeque buffer line)
                     (while (> (reduce + (map count buffer)) stderr-limit)
                       (.removeFirst ^ArrayDeque buffer))))
                 (recur)))))
         "hc-mcp-stderr")
    (.setDaemon true)
    (.start)))

(defn- start-stdout-reader
  [^java.io.InputStream stream queue]
  (doto (Thread.
         (fn []
           (with-open [reader (BufferedReader. (InputStreamReader. stream StandardCharsets/UTF_8))]
             (try
               (loop []
                 (when-let [line (.readLine reader)]
                   (if (str/blank? line)
                     (recur)
                     (let [message (try
                                     (json/read-str line)
                                     (catch clojure.lang.ExceptionInfo t
                                       {:__parse-error (error/error-map t)}))]
                       (.put ^LinkedBlockingQueue queue message)
                       (recur)))))
               (finally
                 (.put ^LinkedBlockingQueue queue eof)))))
         "hc-mcp-stdout")
    (.setDaemon true)
    (.start)))

(defn connect!
  "Start a child process and return a connection map."
  [server opts]
  (let [transport (:transport server)
        command (:command transport)
        args (or (:args transport) [])
        env (or (:env transport) {})
        cwd (:cwd transport)
        secrets (concat (vals env) (vals (:headers transport)))]
    (try
      (let [command-list (vec (cons command args))
            builder (ProcessBuilder. ^java.util.List command-list)
            _ (when cwd
                (.directory builder (File. ^String cwd)))
            process-env (.environment builder)]
        (doseq [[k v] env]
          (.put process-env (str k) (str v)))
        (let [process (.start builder)
              stdin (BufferedWriter. (OutputStreamWriter. (.getOutputStream process)
                                                          StandardCharsets/UTF_8))
              queue (LinkedBlockingQueue.)
              stderr-buffer (ArrayDeque.)]
          (start-stdout-reader (.getInputStream process) queue)
          (start-stderr-drain (.getErrorStream process) secrets stderr-buffer)
          {:ok? true
           :connection {:kind :stdio
                        :process process
                        :stdin stdin
                        :queue queue
                        :stderr stderr-buffer
                        :secrets secrets
                        :next-id (atom 0)
                        :closed? (atom false)}}))
      (catch Exception t
        {:ok? false
         :error {:code :MCP_PROCESS_START
                 :message (or (.getMessage ^Throwable t) (str t))
                 :details {:command command
                           :args (vec args)}}}))))

(defn- send-line!
  [connection message]
  (let [^BufferedWriter writer (:stdin connection)
        text (json/write-str message)]
    (try
      (.write writer text)
      (.newLine writer)
      (.flush writer)
      {:ok? true}
      (catch Exception t
        {:ok? false
         :error {:code :MCP_PROCESS_IO
                 :message (or (.getMessage ^Throwable t) (str t))}}))))

(defn- rpc-error
  [error-object]
  {:ok? false
   :error {:code :MCP_JSON_RPC
           :message (or (json/field error-object :message)
                        "server returned a JSON-RPC error")
           :details {:rpc-error error-object}}})

(defn- parse-error?
  [message]
  (or (contains? message :__parse-error)
      (contains? message "__parse-error")))

(defn- parse-error-value
  [message]
  (or (get message :__parse-error) (get message "__parse-error")))

(defn- server-request?
  [message]
  (and (map? message)
       (some? (json/field message :method))
       (some? (json/field message :id))))

(defn- answer-unsupported-request!
  [connection message]
  (send-line! connection
              {:jsonrpc "2.0"
               :id (json/field message :id)
               :error {:code -32601
                       :message "Herdchestra does not implement this server request"}}))

(defn request!
  "Send one request and wait for its response.  Server-initiated requests are
  answered with `-32601`; they are not silently executed."
  [connection method params timeout-ms]
  (let [id (swap! (:next-id connection) inc)
        sent (send-line! connection
                         {:jsonrpc "2.0"
                          :id id
                          :method method
                          :params (or params {})})]
    (if-not (:ok? sent)
      sent
      (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
        (loop []
          (let [remaining (- deadline (System/nanoTime))]
            (if (neg? remaining)
              {:ok? false
               :error {:code :MCP_TIMEOUT
                       :message "server did not answer before the deadline"
                       :details {:method method :timeout-ms timeout-ms}}}
              (let [message (.poll ^LinkedBlockingQueue (:queue connection)
                                   (max 1 (long (/ remaining 1000000)))
                                   TimeUnit/MILLISECONDS)]
                (cond
                  (nil? message)
                  (recur)

                  (= eof message)
                  {:ok? false
                   :error {:code :MCP_PROCESS_EXITED
                           :message "server closed its output before answering"
                           :details {:method method
                                     :stderr (redact-stderr
                                              (locking (:stderr connection)
                                                (str/join (seq (:stderr connection))))
                                              (:secrets connection))}}}

                  (parse-error? message)
                  {:ok? false
                   :error {:code :MCP_PROTOCOL
                           :message "server sent a line that is not JSON-RPC"
                           :details {:method method
                                     :parse (parse-error-value message)}}}

                  (server-request? message)
                  (do (answer-unsupported-request! connection message)
                      (recur))

                  (= id (json/field message :id))
                  (if (or (contains? message :error) (contains? message "error"))
                    (rpc-error (json/field message :error))
                    {:ok? true :result (json/field message :result)})

                  :else
                  (recur))))))))))

(defn notify!
  "Send a JSON-RPC notification.  There is no response."
  [connection method params]
  (send-line! connection
              {:jsonrpc "2.0"
               :method method
               :params (or params {})}))

(defn close!
  [connection]
  (when (compare-and-set! (:closed? connection) false true)
    (let [^Process process (:process connection)]
      (try
        (.close ^BufferedWriter (:stdin connection))
        (catch Exception _ nil))
      (try
        (when-not (.waitFor process 200 TimeUnit/MILLISECONDS)
          (.destroy process)
          (when-not (.waitFor process 200 TimeUnit/MILLISECONDS)
            (.destroyForcibly process)))
        (catch Exception _ nil))
      (try
        (.close (.getInputStream process))
        (catch Exception _ nil))
      (try
        (.close (.getErrorStream process))
        (catch Exception _ nil))))
  {:ok? true})
