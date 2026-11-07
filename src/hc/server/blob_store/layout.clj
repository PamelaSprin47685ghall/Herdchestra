(ns hc.server.blob-store.layout
  "Filesystem layout of the managed blob area.

  The layout is deliberately small and fixed:

      <root>/objects/sha256/<first-two-hex>/<full-hex>   finalized object
      <root>/tmp/                                        staging files
      <root>/.blob-store.lock                            reference/cleanup lock

  Staging and finalized objects share one root so the final publish is a
  same-filesystem atomic rename.  No caller may derive an object path from
  unchecked input; `object-path` validates the content ID first."
  (:require [hc.server.blob-store.content :as content])
  (:import (java.nio.file Files LinkOption NoSuchFileException NotDirectoryException Path Paths)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.lang.String;" empty-string-array
  (make-array String 0))

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-link-options
  (make-array LinkOption 0))

(def ^:private ^"[Ljava.nio.file.attribute.FileAttribute;" no-file-attributes
  (make-array FileAttribute 0))

(defn path-of
  "Normalize `path` to an absolute Path.  Symlinks are not resolved."
  ^Path [path]
  (-> (Paths/get (str path) empty-string-array)
      (.toAbsolutePath)
      (.normalize)))

(defn root-path
  "The managed root as an absolute Path."
  ^Path [root]
  (path-of root))

(defn objects-dir
  "The directory that contains finalized objects."
  ^Path [root]
  (.resolve ^Path (root-path root) "objects"))

(defn sha256-dir
  "The SHA-256 namespace inside `objects`."
  ^Path [root]
  (.resolve ^Path (objects-dir root) ^String content/algorithm-name))

(defn temp-dir
  "The staging directory.  It must stay on the same filesystem as objects."
  ^Path [root]
  (.resolve ^Path (root-path root) "tmp"))

(defn lock-file
  "The file used to serialize reference commits and cleanup."
  ^Path [root]
  (.resolve ^Path (root-path root) ".blob-store.lock"))

(defn fanout-dir
  "The two-character fanout directory for `id`."
  ^Path [root id]
  (let [hex (content/id->hex id)]
    (.resolve ^Path (sha256-dir root) (subs hex 0 2))))

(defn object-path
  "The final path of `id`.  The ID is validated before any path is built."
  ^Path [root id]
  (let [hex (content/id->hex id)]
    (.resolve ^Path (fanout-dir root id) ^String hex)))

(defn object-relative-path
  "The object path relative to the root, for metadata and documentation."
  [id]
  (let [hex (content/id->hex id)]
    (str "objects/" content/algorithm-name "/" (subs hex 0 2) "/" hex)))

(defn ensure-root!
  "Create the managed root and its fixed directories when absent."
  [root]
  (Files/createDirectories (sha256-dir root) no-file-attributes)
  (Files/createDirectories (temp-dir root) no-file-attributes)
  root)

(defn attrs
  "BasicFileAttributes for `path` without following symlinks.

  Missing paths and non-directory parents return nil: absence is an ordinary
  observation.  Other I/O failures propagate."
  ^BasicFileAttributes [^Path path]
  (try
    (Files/readAttributes path BasicFileAttributes no-link-options)
    (catch NoSuchFileException _ nil)
    (catch NotDirectoryException _ nil)
    (catch java.io.FileNotFoundException _ nil)))

(defn regular-file?
  "True when `path` itself is a regular file (symlinks do not qualify)."
  [^Path path]
  (let [a (attrs path)]
    (boolean (and a (.isRegularFile a)))))

(defn directory?
  "True when `path` itself is a directory (symlinks do not qualify)."
  [^Path path]
  (let [a (attrs path)]
    (boolean (and a (.isDirectory a)))))

(defn directory-entries
  "All direct entries of `dir`, or an empty vector when it is absent."
  [^Path dir]
  (if (directory? dir)
    (with-open [stream (Files/newDirectoryStream dir)]
      (vec (iterator-seq (.iterator stream))))
    []))
