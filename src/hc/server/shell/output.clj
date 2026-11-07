(ns hc.server.shell.output
  "Bounded output capture for one managed shell session.

  Two independent limits apply:

  - inline bytes returned to the model: 1 MiB by default;
  - artifact bytes retained for later reading: 64 MiB by default.

  Both stdout and stderr are drained to EOF even after their limits are
  reached, so a chatty child process can never block on a full pipe.  The
  artifact receives the interleaved raw byte stream in arrival order.  A
  truncated result is explicit; the capture never claims to have retained
  bytes it discarded."
  (:import (java.io ByteArrayOutputStream OutputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path Paths StandardOpenOption)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def default-inline-limit (* 1024 1024))
(def max-artifact-limit (* 64 1024 1024))
(def default-artifact-limit max-artifact-limit)

(def ^:private empty-file-attributes
  (make-array FileAttribute 0))

(defrecord Capture
    [inline-limit artifact-limit ^Path artifact-path ^OutputStream artifact
     ^ByteArrayOutputStream stdout ^ByteArrayOutputStream stderr lock state])

(defn- as-path
  [x]
  (cond
    (instance? Path x) x
    (nil? x) nil
    :else (Paths/get (str x) (make-array String 0))))

(defn- ensure-parent!
  [^Path path]
  (when-let [parent (.getParent path)]
    (Files/createDirectories parent empty-file-attributes)))

(defn- open-artifact!
  [^Path path]
  (when path
    (ensure-parent! path)
    (Files/newOutputStream path
                           (into-array OpenOption
                                       [StandardOpenOption/CREATE
                                        StandardOpenOption/TRUNCATE_EXISTING
                                        StandardOpenOption/WRITE]))))

(defn- temporary-artifact
  [artifact-dir]
  (let [dir (if artifact-dir
              (do (let [p (as-path artifact-dir)]
                    (Files/createDirectories p empty-file-attributes)
                    p))
              (as-path (System/getProperty "java.io.tmpdir")))]
    (Files/createTempFile dir "hc-shell-" ".log" empty-file-attributes)))

(defn capture
  "Create a bounded capture.

  Options:

    :inline-limit    maximum inline bytes, default 1 MiB
    :artifact-limit  artifact bytes, default and hard-capped at 64 MiB
    :artifact-path   explicit artifact path; otherwise a temporary file
    :artifact-dir    directory for the default temporary artifact
    :root            base for a relative :artifact-path

  The caller must call `finish!` after the process and its known children have
  closed their output pipes.  A negative limit is rejected."
  [opts]
  (let [{:keys [inline-limit artifact-limit artifact-path artifact-dir root]
         :or {inline-limit default-inline-limit
              artifact-limit default-artifact-limit}} opts
        inline-limit (long inline-limit)
        artifact-limit (long artifact-limit)]
    (when (neg? inline-limit)
      (throw (IllegalArgumentException. "inline output limit must be non-negative")))
    (when (neg? artifact-limit)
      (throw (IllegalArgumentException. "artifact output limit must be non-negative")))
    (let [artifact-limit (min artifact-limit (long max-artifact-limit))]
    (let [requested (when artifact-path
                      (let [^Path p (as-path artifact-path)]
                        (if (and (not (.isAbsolute p)) root)
                          (.resolve ^Path (as-path root) p)
                          p)))
          path (if (pos? artifact-limit)
                 (or requested (temporary-artifact artifact-dir))
                 nil)
          out (open-artifact! path)]
      (->Capture inline-limit
                 artifact-limit
                 path
                 out
                 (ByteArrayOutputStream.)
                 (ByteArrayOutputStream.)
                 (Object.)
                 (atom {:stdout-bytes 0
                        :stderr-bytes 0
                        :stdout-inline-bytes 0
                        :stderr-inline-bytes 0
                        :inline-bytes 0
                        :artifact-bytes 0
                        :stdout-truncated? false
                        :stderr-truncated? false
                        :artifact-truncated? false
                        :artifact-error nil
                        :closed? false}))))))

(defn- artifact-write!
  "Write up to the artifact limit.  A write failure is recorded, not thrown:
  the pump must keep draining the process pipe."
  [^Capture capture ^bytes data off len]
  (let [{:keys [artifact-bytes]} @(:state capture)
        ^OutputStream artifact (:artifact capture)
        remaining (max 0 (- (long (:artifact-limit capture)) (long artifact-bytes)))
        take (min (long len) remaining)
        written (if (and artifact (pos? take))
                  (try
                    (.write artifact data (int off) (int take))
                    take
                    (catch Throwable t
                      (swap! (:state capture) assoc :artifact-error (str t))
                      0))
                  0)]
    (swap! (:state capture)
           (fn [s]
             (cond-> (update s :artifact-bytes + written)
               (< written (long len)) (assoc :artifact-truncated? true))))))

(defn- inline-write!
  "Append the remaining inline budget to STREAM's buffer."
  [^Capture capture stream ^bytes data off len]
  (let [{:keys [inline-bytes]} @(:state capture)
        remaining (max 0 (- (long (:inline-limit capture)) (long inline-bytes)))
        take (min (long len) remaining)
        buffer (case stream :stdout (:stdout capture) :stderr (:stderr capture))]
    (when (pos? take)
      (.write ^ByteArrayOutputStream buffer data (int off) (int take)))
    (swap! (:state capture)
           (fn [s]
             (let [s (-> s
                         (update :inline-bytes + take)
                         (update (if (= :stdout stream)
                                   :stdout-inline-bytes
                                   :stderr-inline-bytes)
                                 + take)
                         (update (if (= :stdout stream)
                                   :stdout-bytes
                                   :stderr-bytes)
                                 + len))]
               (if (< take (long len))
                 (assoc s
                        (if (= :stdout stream)
                          :stdout-truncated?
                          :stderr-truncated?)
                        true)
                 s))))))

(defn append!
  "Append LEN bytes from DATA at OFF for STREAM (`:stdout` or `:stderr`).

  The method is thread-safe and never blocks on a full output limit.  It is
  intentionally silent about discarded bytes; `snapshot` reports them."
  [^Capture capture stream ^bytes data off len]
  (locking (:lock capture)
    (when (and (pos? len) (not (:closed? @(:state capture))))
      (inline-write! capture stream data off len)
      (artifact-write! capture data off len)))
  capture)

(defn- decode
  [^ByteArrayOutputStream buffer]
  (String. (.toByteArray buffer) StandardCharsets/UTF_8))

(defn snapshot
  "Return the current bounded view.  Safe while pumps are still running."
  [^Capture capture]
  (locking (:lock capture)
    (let [s @(:state capture)]
      {:stdout {:inline (decode (:stdout capture))
                :bytes (:stdout-bytes s)
                :inline-bytes (:stdout-inline-bytes s)
                :truncated? (:stdout-truncated? s)}
       :stderr {:inline (decode (:stderr capture))
                :bytes (:stderr-bytes s)
                :inline-bytes (:stderr-inline-bytes s)
                :truncated? (:stderr-truncated? s)}
       :inline-bytes (:inline-bytes s)
       :inline-limit (long (:inline-limit capture))
       :output-truncated? (or (:stdout-truncated? s)
                              (:stderr-truncated? s)
                              (:artifact-truncated? s))
       :artifact {:path (some-> ^Path (:artifact-path capture) str)
                  :bytes (:artifact-bytes s)
                  :limit (long (:artifact-limit capture))
                  :truncated? (:artifact-truncated? s)
                  :error (:artifact-error s)}
       :encoding "utf-8"
       :closed? (:closed? s)})))

(defn finish!
  "Close the artifact and return the final snapshot.  Idempotent."
  [^Capture capture]
  (locking (:lock capture)
    (let [s @(:state capture)]
      (when-not (:closed? s)
        (when-let [^OutputStream artifact (:artifact capture)]
          (try
            (.flush artifact)
            (.close artifact)
            (catch Throwable t
              (swap! (:state capture) assoc :artifact-error (str t)))))
        (swap! (:state capture) assoc :closed? true))))
  (snapshot capture))
