(ns hc.server.extensions.skill.store
  "Filesystem ownership for installed skills.

  A skill is installed under `<root>/skills/<skill-id>/`.  Installation writes
  a sibling temporary directory first and moves it into place only after every
  file has been written.  Deletion walks only the managed directory and never
  follows a symbolic link; user files outside that directory are untouched."
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error])
  (:import (java.nio.file CopyOption Files LinkOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(defn skills-root
  [root]
  (.resolve (Path/of (str root) (make-array String 0)) "skills"))

(defn skill-root
  [root id]
  (when-not (and (string? id)
                 (re-matches #"[A-Za-z0-9._-]+" id)
                 (not (contains? #{"." ".."} id)))
    (error/fail! :SKILL_ID_INVALID "skill id contains unsafe characters"
                 {:id id}))
  (let [skills (skills-root root)
        path (.resolve skills id)]
    (when-not (= (.getParent path) skills)
      (error/fail! :SKILL_ID_INVALID
                   "skill id must name a direct child of the skills root"
                   {:id id}))
    path))

(defn- symlink-below-root?
  [^Path root ^Path target]
  (loop [path target]
    (cond
      (nil? path) false
      (= path root) false
      (not (.startsWith path root)) true
      (Files/isSymbolicLink path) true
      :else (recur (.getParent path)))))

(defn- assert-writable-target!
  [^Path root ^Path target path]
  (when-not (common/under-root? root target)
    (error/fail! :SKILL_PATH_ESCAPE
                 "skill file resolves outside its root"
                 {:path path :root (str root)}))
  (when (symlink-below-root? root target)
    (error/fail! :SKILL_SYMLINK
                 "skill path crosses a symbolic link"
                 {:path path :root (str root)})))

(defn- write-file!
  [^Path root path ^bytes bytes]
  (let [target (.resolve root path)]
    (assert-writable-target! root target path)
    (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
    (Files/write target bytes
                 (into-array StandardOpenOption
                             [StandardOpenOption/CREATE_NEW
                              StandardOpenOption/WRITE]))))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array LinkOption 0))
    (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path path (->> (iterator-seq (.iterator stream))
                              (sort-by #(.getNameCount ^Path %))
                              reverse)]
        (Files/deleteIfExists path)))))

(defn file-metadata
  [path ^bytes bytes]
  {:path path
   :size (alength bytes)
   :sha256 (common/digest-id bytes)
   :text? (boolean (and (common/valid-utf8? bytes)
                        (or (str/ends-with? path ".md")
                            (str/ends-with? path ".txt")
                            (str/ends-with? path ".json")
                            (str/ends-with? path ".yaml")
                            (str/ends-with? path ".yml")
                            (str/ends-with? path ".edn"))))})

(defn install!
  "Write files under a new skill directory.

  `root` is the extension storage root, not a user workspace.  Returns
  `{:ok? true :root path :files metadata}`.  A failure removes the temporary
  directory and leaves any existing skill untouched."
  [root id files]
  (let [^Path final (skill-root root id)
        ^Path parent (.getParent final)
        ^Path temp (.resolve parent (str ".tmp-" (UUID/randomUUID)))]
    (try
      (when (Files/exists final (make-array LinkOption 0))
        (error/fail! :SKILL_ALREADY_INSTALLED
                     "skill directory already exists"
                     {:id id :path (str final)}))
      (Files/createDirectories temp (make-array FileAttribute 0))
      (doseq [[path bytes] files]
        (write-file! temp path bytes))
      (Files/createDirectories parent (make-array FileAttribute 0))
      (try
        (Files/move temp final (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move temp final (make-array CopyOption 0))))
      {:ok? true
       :root (str final)
       :files (into {} (map (fn [[path bytes]] [path (file-metadata path bytes)]) files))}
      (catch clojure.lang.ExceptionInfo t
        (delete-tree! temp)
        (if (error/expected? t)
          {:ok? false :error (error/error-map t)}
          (throw t)))
      (catch Exception t
        (delete-tree! temp)
        {:ok? false
         :error {:code :SKILL_INSTALL_IO
                 :message (or (.getMessage ^Throwable t) (str t))
                 :details {:id id :path (str final)}}}))))

(defn read-files
  "Read the current files of a skill as a path->bytes map."
  [root id]
  (let [^Path root (skill-root root id)]
    (if-not (Files/exists root (make-array LinkOption 0))
      {:ok? false
       :error {:code :SKILL_FILES_MISSING
               :message "skill directory is missing"
               :details {:id id :path (str root)}}}
      (try
        (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
          {:ok? true
           :files (into {}
                        (comp (map (fn [^Path path]
                                     (if (Files/isSymbolicLink path)
                                       (error/fail! :SKILL_SYMLINK
                                                    "skill directory contains a symbolic link"
                                                    {:path (str path)})
                                       path)))
                              (filter #(Files/isRegularFile ^Path %
                                                            (make-array LinkOption 0)))
                              (map (fn [^Path path]
                                     [(str/replace (str (.relativize root path)) "\\" "/")
                                      (Files/readAllBytes path)])))
                        (iterator-seq (.iterator stream)))})
        (catch clojure.lang.ExceptionInfo t
          (if (error/expected? t)
            {:ok? false :error (error/error-map t)}
            {:ok? false
             :error {:code :SKILL_READ_IO
                     :message (or (.getMessage ^Throwable t) (str t))
                     :details {:id id}}}))
        (catch Exception t
          {:ok? false
           :error {:code :SKILL_READ_IO
                   :message (or (.getMessage ^Throwable t) (str t))
                   :details {:id id}}})))))

(def ^:private max-preview-bytes (* 5 1024 1024))

(defn read-file
  "Read one UTF-8 file inside an installed skill.

  The path is validated against the skill root, symbolic links are refused,
  and the read is bounded.  This is a preview operation; it never writes."
  [root id path]
  (try
    (let [path (common/normalize-relative-path path)
          ^Path root (skill-root root id)
          target (.resolve root path)]
      (assert-writable-target! root target path)
      (cond
        (not (Files/exists target (make-array LinkOption 0)))
        {:ok? false
         :error {:code :SKILL_FILE_NOT_FOUND
                 :message "skill file does not exist"
                 :details {:id id :path path}}}

        (not (Files/isRegularFile target (make-array LinkOption 0)))
        {:ok? false
         :error {:code :SKILL_FILE_INVALID
                 :message "skill path is not a regular file"
                 :details {:id id :path path}}}

        :else
        (let [bytes (common/read-limited
                     (Files/newInputStream
                      target (make-array java.nio.file.OpenOption 0))
                     max-preview-bytes)
              text (common/bytes->utf8 bytes)]
          (if (nil? text)
            {:ok? false
             :error {:code :SKILL_ENCODING_INVALID
                     :message "skill file is not valid UTF-8"
                     :details {:id id :path path}}}
            {:ok? true
             :path path
             :text text
             :size (alength ^bytes bytes)
             :sha256 (common/digest-id bytes)}))))
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        {:ok? false
         :error {:code :SKILL_READ_IO
                 :message (or (.getMessage ^Throwable t) (str t))
                 :details {:id id :path path}}}))
    (catch Exception t
      {:ok? false
       :error {:code :SKILL_READ_IO
               :message (or (.getMessage ^Throwable t) (str t))
               :details {:id id :path path}}})))

(defn delete!
  "Delete the managed directory for one skill.  Missing is idempotent."
  [root id]
  (let [^Path root (skill-root root id)]
    (try
      (if (Files/exists root (make-array LinkOption 0))
        (do (delete-tree! root)
            {:ok? true :deleted true :root (str root)})
        {:ok? true :deleted false :root (str root)})
      (catch Exception t
        {:ok? false
         :error {:code :SKILL_DELETE_IO
                 :message (or (.getMessage ^Throwable t) (str t))
                 :details {:id id :path (str root)}}}))))

(defn replace-files!
  "Apply a path->bytes write plan and a set of deletions to an existing skill.

  Files are written atomically one at a time.  A caller must treat a partial
  failure as a real filesystem state: the returned error includes the paths
  already applied."
  [root id writes deletes]
  (let [^Path root (skill-root root id)]
    (if-not (Files/exists root (make-array LinkOption 0))
      {:ok? false
       :error {:code :SKILL_FILES_MISSING
               :message "skill directory is missing"
               :details {:id id}}}
      (let [written (volatile! [])
            deleted (volatile! [])]
        (try
          (doseq [[path bytes] writes]
            (let [target (.resolve root path)]
              (assert-writable-target! root target path)
              (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
              (Files/write target bytes
                           (into-array StandardOpenOption
                                       [StandardOpenOption/CREATE
                                        StandardOpenOption/TRUNCATE_EXISTING
                                        StandardOpenOption/WRITE]))
              (vswap! written conj path)))
          (doseq [path deletes]
            (let [target (.resolve root path)]
              (assert-writable-target! root target path)
              (Files/deleteIfExists target)
              (vswap! deleted conj path)))
          {:ok? true :written @written :deleted @deleted}
          (catch clojure.lang.ExceptionInfo t
            (if (error/expected? t)
              {:ok? false :error (error/error-map t)}
              {:ok? false
               :error {:code :SKILL_UPDATE_IO
                       :message (or (.getMessage ^Throwable t) (str t))
                       :details {:id id
                                 :written @written
                                 :deleted @deleted}}}))
          (catch Exception t
            {:ok? false
             :error {:code :SKILL_UPDATE_IO
                     :message (or (.getMessage ^Throwable t) (str t))
                     :details {:id id
                               :written @written
                               :deleted @deleted}}}))))))
