(ns hc.core.workspace-gate.stamp
  "System metadata credentials for the protected file contract.

  A stamp is exactly `{:mtime-sec :mtime-nsec :size}`.  `atime` is never
  observed or compared.  Paths are resolved against a workspace root; the
  empty string means the root itself and absolute paths are used as-is.
  No artificial root boundary is enforced: the operating system decides
  whether an access succeeds."
  (:import (java.nio.file Files LinkOption NoSuchFileException NotDirectoryException Path Paths)
           (java.nio.file.attribute BasicFileAttributes)))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-link-options
  (make-array LinkOption 0))

(defn root-path
  "Return `root` as a normalized absolute Path."
  ^Path [root]
  (-> (Paths/get (str root) (make-array String 0))
      (.toAbsolutePath)
      (.normalize)))

(defn path-of
  "Resolve `path` against `root`.

  The empty string resolves to the root.  Absolute paths are used as-is.
  The result is lexically normalized but symlinks are not resolved."
  ^Path [root path]
  (let [p (Paths/get (str path) (make-array String 0))]
    (if (.isAbsolute p)
      (.normalize p)
      (-> (.resolve (root-path root) p) (.normalize)))))

(defn path-string
  "Canonical string identity for a resolved path."
  [root path]
  (str (path-of root path)))

(defn under?
  "True when `child` is a strict descendant of `parent`."
  [child parent]
  (let [c (Paths/get (str child) (make-array String 0))
        p (Paths/get (str parent) (make-array String 0))]
    (and (not= c p) (.startsWith c p))))

(defn rebase
  "Map `path` from the `from` subtree into the `to` subtree."
  [path from to]
  (let [p (Paths/get (str path) (make-array String 0))
        f (Paths/get (str from) (make-array String 0))
        t (Paths/get (str to) (make-array String 0))]
    (str (.resolve t (.relativize f p)))))

(defn stamp-of-attrs
  "Build a stamp from BasicFileAttributes without losing nanoseconds."
  [^BasicFileAttributes attrs]
  (let [instant (.toInstant (.lastModifiedTime attrs))]
    {:mtime-sec (.getEpochSecond instant)
     :mtime-nsec (.getNano instant)
     :size (.size attrs)}))

(defn attrs
  "Read BasicFileAttributes for `path`, or nil when it does not exist.

  Missing paths are an ordinary observation, not an error.  Other IO errors
  propagate so callers can distinguish a hard failure from absence."
  ^BasicFileAttributes [^Path path]
  (try
    (Files/readAttributes path BasicFileAttributes no-link-options)
    (catch NoSuchFileException _ nil)
    (catch NotDirectoryException _ nil)
    (catch java.io.FileNotFoundException _ nil)))

(defn stat-map
  "The public `stat` result shape.  Missing paths return `{:exists false}`."
  [^Path path]
  (if-let [a (attrs path)]
    (let [instant (.toInstant (.lastModifiedTime a))]
      {:exists true
       :is-dir (.isDirectory a)
       :size (.size a)
       :mtime-sec (.getEpochSecond instant)
       :mtime-nsec (.getNano instant)})
    {:exists false}))

(defn current-stamp
  "The current stamp of a regular file, or nil when absent/not a file."
  [^Path path]
  (when-let [a (attrs path)]
    (when (.isRegularFile a)
      (stamp-of-attrs a))))

(defn kind-of
  "One of :file, :directory, :other or :missing."
  [^Path path]
  (if-let [a (attrs path)]
    (cond
      (.isDirectory a) :directory
      (.isRegularFile a) :file
      :else :other)
    :missing))

(defn same-stamp?
  "Exact three-field equality.  mtime is not treated as monotonic."
  [a b]
  (and (map? a) (map? b)
       (= (:mtime-sec a) (:mtime-sec b))
       (= (:mtime-nsec a) (:mtime-nsec b))
       (= (:size a) (:size b))))

