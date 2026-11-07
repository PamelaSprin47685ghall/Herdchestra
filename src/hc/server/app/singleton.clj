(ns hc.server.app.singleton
  "Machine-wide single-instance lock and loopback control channel.

  Herdchestra is a per-machine singleton: a second start request must reach
  the running process instead of opening a second store or listener.  This
  namespace owns the process lock, the state file that names the control
  endpoint, and a small EDN line protocol on loopback.

  The lock is taken before the store or the maintenance journal is opened.
  The state file is owner-only and carries a random token; the control server
  rejects requests without it.  The protocol is intentionally small and
  local: focus/create a session, submit one command payload, or ping."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedReader BufferedWriter InputStreamReader
                    OutputStreamWriter)
           (java.lang ProcessHandle)
           (java.net InetAddress ServerSocket Socket SocketException)
           (java.nio.channels FileChannel OverlappingFileLockException)
           (java.nio.file Files OpenOption Path StandardOpenOption)
           (java.nio.file.attribute PosixFilePermissions)
           (java.util UUID)))

(def ^:private max-line-chars (* 1024 1024))

(defn- safe-user
  []
  (let [name (or (System/getProperty "user.name") "user")]
    (str/replace name #"[^A-Za-z0-9._-]" "_")))

(defn default-dir
  "Per-user runtime directory used for the lock and endpoint state."
  []
  (str (System/getProperty "java.io.tmpdir")
       java.io.File/separator
       "herdchestra-" (safe-user)))

(defn- ensure-dir!
  [dir]
  (let [file (io/file dir)]
    (when-not (.isDirectory file)
      (.mkdirs file))
    (try
      (Files/setPosixFilePermissions
       (.toPath file)
       (PosixFilePermissions/fromString "rwx------"))
      (catch Exception _ nil))
    dir))

(defn lock-path
  [dir]
  (str (io/file dir "instance.lock")))

(defn state-path
  [dir]
  (str (io/file dir "instance.edn")))

(defn read-state
  "Read the endpoint state.  A missing or malformed file is nil."
  ([]
   (read-state (default-dir)))
  ([dir]
   (let [file (io/file (state-path dir))]
     (when (.isFile file)
       (try
         (let [value (edn/read-string (slurp file))]
           (when (and (map? value)
                      (integer? (:port value))
                      (string? (:token value)))
             value))
         (catch Exception _ nil))))))

(defn- write-state!
  [dir state]
  (ensure-dir! dir)
  (let [target (io/file (state-path dir))
        temp (io/file dir (str ".instance-" (UUID/randomUUID) ".edn"))]
    (spit temp (pr-str state))
    (try
      (Files/setPosixFilePermissions
       (.toPath temp)
       (PosixFilePermissions/fromString "rw-------"))
      (catch Exception _ nil))
    (try
      (Files/move (.toPath temp) (.toPath target)
                  (into-array java.nio.file.CopyOption
                              [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                               java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (catch Exception _
        (try
          (Files/move (.toPath temp) (.toPath target)
                      (into-array java.nio.file.CopyOption
                                  [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (catch Exception publish-error
            ;; Never leave a half-written endpoint file or a temp file that
            ;; names a port the process does not serve.  The caller turns
            ;; this into START_FAILED; the lock is released on the way out.
            (try (Files/deleteIfExists (.toPath temp)) (catch Exception _ nil))
            (throw (ex-info "control endpoint state could not be published"
                            {:error :CONTROL_ENDPOINT_PUBLISH_FAILED
                             :path (str target)}
                            publish-error))))))
    state))

(defn- delete-state!
  [dir]
  (try (Files/deleteIfExists (.toPath (io/file (state-path dir))))
       (catch Exception _ nil)))

(defn acquire!
  "Try to take the machine-wide instance lock.

  Returns `{:acquired? true :lock ... :dir dir}` or
  `{:acquired? false :state <endpoint state or nil> :dir dir}`.  The caller
  must keep the handle alive for the process lifetime and call `release!`."
  ([]
   (acquire! {}))
  ([{:keys [dir] :or {dir (default-dir)}}]
   (ensure-dir! dir)
   (let [channel (FileChannel/open
                  (.toPath (io/file (lock-path dir)))
                  (into-array OpenOption
                              [StandardOpenOption/CREATE
                               StandardOpenOption/WRITE]))
         lock (try
                (.tryLock channel)
                (catch OverlappingFileLockException _ nil)
                (catch Throwable _ nil))]
     (if lock
       (do
         (try
           (Files/setPosixFilePermissions
            (.toPath (io/file (lock-path dir)))
            (PosixFilePermissions/fromString "rw-------"))
           (catch Exception _ nil))
         {:acquired? true :dir dir :channel channel :lock lock})
       (do
         (try (.close channel) (catch Exception _ nil))
         {:acquired? false :dir dir :state (read-state dir)})))))

(defn release!
  "Release the instance lock and remove the endpoint state.  Idempotent."
  [{:keys [lock channel dir]}]
  (when lock
    (try (.release lock) (catch Exception _ nil)))
  (when channel
    (try (.close channel) (catch Exception _ nil)))
  (when dir
    (delete-state! dir))
  nil)

(defn- read-limited-line
  [^BufferedReader reader]
  (let [builder (StringBuilder.)]
    (loop []
      (let [c (.read reader)]
        (cond
          (neg? c) (when (pos? (.length builder)) (str builder))
          (= c (int \newline)) (str builder)
          (> (.length builder) max-line-chars)
          (throw (ex-info "control request is too large" {:limit max-line-chars}))
          :else (do (.append builder (char c)) (recur)))))))

(defn- send-request!
  [state request]
  (let [{:keys [port token]} state]
    (when-not (and (integer? port) (string? token))
      (throw (ex-info "no running instance endpoint" {:state state})))
    (with-open [socket (Socket.)]
      (.connect socket (java.net.InetSocketAddress.
                        (InetAddress/getLoopbackAddress) (int port)) 5000)
      (.setSoTimeout socket 15000)
      (let [writer (BufferedWriter.
                    (OutputStreamWriter. (.getOutputStream socket) "UTF-8"))
            reader (BufferedReader.
                    (InputStreamReader. (.getInputStream socket) "UTF-8"))]
        (.write writer (pr-str (assoc request :token token)))
        (.newLine writer)
        (.flush writer)
        (let [line (read-limited-line reader)]
          (if line
            (let [value (edn/read-string line)]
              (if (map? value)
                value
                {:ok? false
                 :error {:code :SINGLETON_BAD_REPLY
                         :message "the running instance returned a non-map"}}))
            {:ok? false
             :error {:code :SINGLETON_EMPTY_REPLY
                     :message "the running instance closed the control channel"}}))))))

(defn send!
  "Send one request to the endpoint in STATE.  Never starts a second system."
  [state request]
  (try
    (send-request! state request)
    (catch Throwable t
      {:ok? false
       :error {:code :SINGLETON_UNREACHABLE
               :message (or (ex-message t) (str t))
               :class (.getName (class t))}})))

(defn send-until-ready!
  "Send REQUEST to the endpoint, waiting for a starting instance to publish it.

  Returns the last reply.  If the endpoint never appears, returns an explicit
  `:SINGLETON_UNREACHABLE` error.  The caller must not start a second system
  while the lock is held."
  ([dir request]
   (send-until-ready! dir request 30000))
  ([dir request timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
     (loop [last-reply nil]
       (let [state (read-state dir)
             reply (if state (send! state request) last-reply)]
         (cond
           (and reply (:ok? reply)) reply
           (< (System/currentTimeMillis) deadline)
           (do (Thread/sleep 100)
               (recur (or reply last-reply)))
           :else
           (or reply
               {:ok? false
                :error {:code :SINGLETON_UNREACHABLE
                        :message "another instance holds the lock but its control endpoint is not ready"}})))))))

(defn- handle-connection
  [^Socket socket handler token]
  (try
    (.setSoTimeout socket 15000)
    (let [reader (BufferedReader.
                  (InputStreamReader. (.getInputStream socket) "UTF-8"))
          writer (BufferedWriter.
                  (OutputStreamWriter. (.getOutputStream socket) "UTF-8"))
          line (read-limited-line reader)
          request (when line (edn/read-string line))
          reply (cond
                  (not (map? request))
                  {:ok? false
                   :error {:code :SINGLETON_BAD_REQUEST
                           :message "control request must be an EDN map"}}

                  (not= token (:token request))
                  {:ok? false
                   :error {:code :SINGLETON_UNAUTHORIZED
                           :message "control token mismatch"}}

                  :else
                  (try
                    (handler (dissoc request :token))
                    (catch Throwable t
                      {:ok? false
                       :error {:code :SINGLETON_HANDLER_FAILED
                               :message (or (ex-message t) (str t))}})))]
      (.write writer (pr-str reply))
      (.newLine writer)
      (.flush writer))
    (catch Throwable _ nil)
    (finally
      (try (.close socket) (catch Exception _ nil)))))

(defn start-server!
  "Start the loopback control server and publish its endpoint state.

  HANDLER receives one request map (token removed) and returns a map.
  Returns `{:ok? true :server ... :state ... :stop! f}`.  The state file is
  written before the accept thread starts, so a waiting second instance can
  connect as soon as the port is bound."
  ([handler]
   (start-server! handler {}))
  ([handler {:keys [dir] :or {dir (default-dir)}}]
   (ensure-dir! dir)
   (let [server (ServerSocket. 0 50 (InetAddress/getLoopbackAddress))
         token (str (UUID/randomUUID))
         state {:port (.getLocalPort server)
                :token token
                :pid (.pid (ProcessHandle/current))
                :started-at (str (java.time.Instant/now))}
         closed? (atom false)
         thread (Thread.
                 ^Runnable
                 (fn []
                   (while (not @closed?)
                     (try
                       (let [socket (.accept server)]
                         (handle-connection socket handler token))
                       (catch SocketException _ nil)
                       (catch Throwable _ nil))))
                 "hc-instance-control")]
     (.setDaemon thread true)
     (write-state! dir state)
     (.start thread)
     {:ok? true
      :server server
      :state state
      :dir dir
      :stop! (fn []
               (reset! closed? true)
               (try (.close server) (catch Exception _ nil))
               (try (.join thread 2000) (catch Exception _ nil))
               (delete-state! dir)
               {:stopped? true})})))

(defn stop-server!
  [server]
  (when-let [stop! (:stop! server)]
    (stop!)))
