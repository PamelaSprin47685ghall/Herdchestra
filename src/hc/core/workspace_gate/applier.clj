(ns hc.core.workspace-gate.applier
  "Deterministic installation of a final `Prepared` plan.

  The applier never re-reads templates, re-searches a patch, expands a glob,
  or calls a model.  It performs exactly the operations fixed by the dry-run.
  A normal path completes the whole batch; a hard failure leaves the observed
  partial site in place and reports it as uncertain.  There is no rollback."
  (:require [hc.core.workspace-gate.error :as err]
            [hc.core.workspace-gate.gate :as gate]
            [hc.core.workspace-gate.prepare :as prepare]
            [hc.core.workspace-gate.stamp :as stamp])
  (:import (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption
                          OpenOption Path StandardCopyOption StandardOpenOption)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def ^:private no-link-options (make-array LinkOption 0))
(def ^:private ^"[Ljava.nio.file.LinkOption;" nofollow-options
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private ^"[Ljava.nio.file.attribute.FileAttribute;" no-file-attributes
  (make-array java.nio.file.attribute.FileAttribute 0))

(defn- copy-permissions!
  "Best-effort permission preservation across the atomic replacement."
  [^Path source ^Path target]
  (when (Files/exists source no-link-options)
    (try
      (Files/setPosixFilePermissions target (Files/getPosixFilePermissions source no-link-options))
      (catch UnsupportedOperationException _ nil)
      (catch java.io.IOException _ nil))))

(defn- create-temp!
  "Create an exclusive same-directory temporary file with default (umask)
  permissions.  `createTempFile` would force 0600, which is wrong for a newly
  created workspace file."
  [^Path parent]
  (loop []
    (let [tmp (.resolve parent (str ".hc-write-" (UUID/randomUUID) ".tmp"))]
      (if (try
            (Files/createFile tmp no-file-attributes)
            true
            (catch java.nio.file.FileAlreadyExistsException _
              false))
        tmp
        (recur)))))

(defn atomic-write!
  "Write `bytes` through a same-directory exclusive temporary file, flush it,
  then rename it over the target.  Readers see the complete old content or
  the complete new content, never a truncated mixture.

  `:create? true` refuses to replace a target that exists at apply time; the
  check is best-effort against writes that arrive after it."
  ([^Path target ^bytes bytes] (atomic-write! target bytes {}))
  ([^Path target ^bytes bytes {:keys [create?]}]
  (let [parent (.getParent target)
        _ (when (nil? parent)
            (err/fail! :STRUCTURE_CONFLICT :no-parent
                       "Cannot atomically write a path without a parent"
                       {:path (str target)}))
        _ (when (and create? (Files/exists target nofollow-options))
            (throw (java.nio.file.FileAlreadyExistsException.
                    (str target) nil "Create target already exists")))
        tmp (create-temp! parent)]
    (try
      (with-open [channel (FileChannel/open tmp (into-array OpenOption
                                                             [StandardOpenOption/WRITE
                                                              StandardOpenOption/TRUNCATE_EXISTING]))]
        (let [buffer (ByteBuffer/wrap bytes)]
          (while (.hasRemaining buffer)
            (.write channel buffer))
          (.force channel true)))
      (copy-permissions! target tmp)
      (try
        (Files/move tmp target
                    (if create?
                      (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])
                      (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                              StandardCopyOption/REPLACE_EXISTING])))
        (catch AtomicMoveNotSupportedException e
          (throw (ex-info "Atomic replacement is not supported by this file system"
                          {:status :HARD_FAILURE
                           :code :atomic-replace-unsupported
                           :path (str target)}
                          e))))
      nil
      (finally
        (try
          (Files/deleteIfExists tmp)
          (catch java.io.IOException _ nil)))))))

(defn- apply-op!
  [^Path root {:keys [op path source target overwrite?] :as operation}]
  (case op
    :mkdir
    (let [target (stamp/path-of root path)]
      (when (Files/exists target nofollow-options)
        (throw (java.nio.file.FileAlreadyExistsException.
                (str target) nil "Create target already exists")))
      (Files/createDirectory target no-file-attributes))

    :write
    (atomic-write! (stamp/path-of root path) ^bytes (:bytes operation)
                   {:create? (:create? operation)})

    :rm
    (Files/delete (stamp/path-of root path))

    :mv
    (Files/move (stamp/path-of root source)
                (stamp/path-of root target)
                (if overwrite?
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])
                  (make-array CopyOption 0)))

    (throw (ex-info "Unknown planned operation"
                    {:status :HARD_FAILURE
                     :code :unknown-plan-operation
                     :operation operation}))))

(defn- apply-ops!
  [ops root on-op]
  (loop [index 0
         applied []]
    (if (= index (count ops))
      {:status :APPLIED
       :outcome :applied
       :file-changed? (boolean (seq applied))
       :observation-basis :mtime+size
       :content-equality :not-claimed
       :applied-ops applied
       :op-count (count ops)}
      (let [operation (nth ops index)
            attempt (try
                      (when on-op (on-op index operation))
                      (apply-op! root operation)
                      {:ok true}
                      (catch Throwable t
                        {:ok false :exception t}))]
        (if (:ok attempt)
          (recur (inc index) (conj applied (prepare/describe-op operation)))
          (let [t (:exception attempt)]
            {:status :HARD_FAILURE
             :outcome :unknown
             :file-changed? :unknown
             :observation-basis :mtime+size
             :content-equality :unknown
             :applied-ops applied
             :failed-op (prepare/describe-op operation)
             :failed-index index
             :remaining-ops (mapv prepare/describe-op (drop index ops))
             :exception (err/throwable->data t)
             :message (.getMessage ^Throwable t)}))))))

(defn- current-permit?
  "True when `token` is the permit currently holding W."
  [token]
  (and (map? token)
       (:gate token)
       (gate/held? (:gate token))
       (= (:owner token) (gate/owner (:gate token)))
       (= (:grant-id token) (gate/current-grant (:gate token)))))

(defn apply-plan!
  "Apply a `Prepared` plan in order.  Returns `:APPLIED` or `:HARD_FAILURE`.

  `opts :on-op` is called as `(on-op index operation)` before each operation;
  tests use it for deterministic hard-failure injection.  `opts :gate-token`
  must be the permit under which the plan was prepared.  The caller must
  already hold the write gate."
  ([prepared] (apply-plan! prepared {}))
  ([prepared {:keys [on-op gate-token]}]
   (cond
     (not (prepare/prepared? prepared))
     (err/failure :UNSUPPORTED_OPERATION :not-prepared
                  "Apply accepts only a Prepared value from the write-gate dry-run"
                  {:actual (some-> prepared class str)})

     (nil? (:gate-token prepared))
     (err/failure :UNSUPPORTED_OPERATION :unbound-prepared
                  "Prepared value was not bound to a write-gate permit"
                  {})

     (not (identical? (:gate-token prepared) gate-token))
     (err/failure :UNSUPPORTED_OPERATION :stale-prepared
                  "Prepared value is not bound to the current write-gate permit"
                  {})

     (not (current-permit? (:gate-token prepared)))
     (err/failure :UNSUPPORTED_OPERATION :write-gate-not-held
                  "Prepared value was made under a permit that no longer holds W"
                  {})

     :else
     (assoc (apply-ops! (:ops prepared) (:root prepared) on-op)
            :summary (:summary prepared)))))
