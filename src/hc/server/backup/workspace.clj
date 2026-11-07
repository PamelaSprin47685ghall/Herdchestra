(ns hc.server.backup.workspace
  "Managed and linked workspaces for backup/restore.

  A workspace is either:

    :managed   app-owned files under `<store-root>/managed/<id>`
    :external  a linked directory referenced by an absolute path

  Managed content is copied when the `:workspaces` category is selected.
  Linked external content is copied only when the caller explicitly passes
  `:include-external-files? true`; even then it is written to a new
  app-owned import directory, never back to the original path.  Restore
  records an external path as `:relink-required? true :authorized? false`;
  it never creates that path and never grants filesystem permission.

  The registry is one canonical EDN file, `<store-root>/workspaces.edn`.
  File reads verify size/mtime before and after reading each file, so a
  changing managed workspace is reported instead of captured half-written.
  Restore validates every ID, kind, external path, relative file path and
  base64 payload before deleting or writing any workspace directory."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err])
  (:import (java.nio.file Files LinkOption OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)
           (java.util.regex Pattern)))

(def format-version 1)

(defprotocol WorkspacePort
  (read-workspaces [port opts]
    "Return workspace items with :id :name :kind :path :label
    :authorized? :relink-required? :files-included? :files-content.
    Managed files are read when :workspaces is selected.  External files are
    read only with :include-external-files? true.")
  (restore-workspaces! [port items]
    "Restore final workspace items.  Managed files go under the store's
    managed root; external paths are recorded as relink-required and are never
    created or authorized."))

(defrecord FileWorkspaceStore [root extras])

(def ^:private id-pattern
  (Pattern/compile "^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$"))

(defn- valid-id?
  [id]
  (and (string? id)
       (not (str/blank? id))
       (boolean (re-matches id-pattern id))))

(defn- assert-id!
  [id]
  (when-not (valid-id? id)
    (err/fail! :BACKUP_WORKSPACE_INVALID
               "Workspace ID is not a safe directory name"
               {:id id}))
  id)

(defn- safe-relative
  [path]
  (let [path (str/replace (str path) "\\" "/")]
    (when (or (str/blank? path)
              (str/starts-with? path "/")
              (re-find #"(?i)^[a-z]:.*" path)
              (str/includes? path "\u0000")
              (some #{".." "."} (str/split path #"/")))
      (err/fail! :BACKUP_WORKSPACE_INVALID
                 "Workspace file path is not a safe relative path"
                 {:path path}))
    path))

(defn- root-path
  ^Path [root]
  (Path/of (str root) (make-array String 0)))

(defn- managed-root
  ^Path [root]
  (.resolve (root-path root) "managed"))

(defn- external-root
  ^Path [root]
  (.resolve (root-path root) "external-imports"))

(defn- registry-file
  ^Path [root]
  (.resolve (root-path root) "workspaces.edn"))

(defn- no-links
  []
  (make-array LinkOption 0))

(defn- attrs
  ^BasicFileAttributes [^Path path]
  (Files/readAttributes path BasicFileAttributes
                        ^"[Ljava.nio.file.LinkOption;" (no-links)))

(defn- normalize-kind
  [kind]
  (cond
    (contains? #{:managed :external} kind) kind
    (= "managed" (str kind)) :managed
    (= "external" (str kind)) :external
    :else (err/fail! :BACKUP_WORKSPACE_INVALID
                     "Workspace kind must be :managed or :external"
                     {:kind kind})))

(defn- normalize-extra
  [ws]
  (let [id (assert-id! (:id ws))
        kind (normalize-kind (:kind ws :external))]
    (when (and (= kind :external)
               (str/blank? (str (:path ws))))
      (err/fail! :BACKUP_WORKSPACE_INVALID
                 "External workspace needs a non-blank :path"
                 {:id id}))
    (when (and (= kind :managed)
               (contains? ws :source-path)
               (str/blank? (str (:source-path ws))))
      (err/fail! :BACKUP_WORKSPACE_INVALID
                 "Managed workspace :source-path must be non-blank"
                 {:id id}))
    (cond-> {:id id
             :name (or (:name ws) (:label ws) id)
             :kind kind
             :label (or (:label ws) (:name ws) id)}
      (= kind :managed) (assoc :path (or (:path ws) id)
                               :source-path (:source-path ws))
      (= kind :external) (assoc :path (str (:path ws))))))

(defn store
  "Create a file-backed workspace store.

  Options:
    :workspaces  optional extra workspace descriptors.  Managed entries may
                 provide :source-path; external entries must provide :path."
  ([root] (store root {}))
  ([root {:keys [workspaces]}]
   (->FileWorkspaceStore (str root)
                         (into {}
                               (map (fn [ws]
                                      (let [ws (normalize-extra ws)]
                                        [(:id ws) ws])))
                               (or workspaces [])))))

(defn- read-registry
  [store]
  (let [file (registry-file (:root store))]
    (if-not (Files/exists file (no-links))
      {:format-version format-version :workspaces {}}
      (let [value (try
                    (codec/edn-read (Files/readAllBytes file))
                    (catch clojure.lang.ExceptionInfo e
                      (throw e))
                    (catch Exception e
                      (err/fail! :BACKUP_WORKSPACE_INVALID
                                 "Workspace registry could not be read"
                                 {:path (str file)}
                                 e)))]
        (when-not (and (map? value)
                       (= format-version (:format-version value))
                       (map? (:workspaces value)))
          (err/fail! :BACKUP_WORKSPACE_INVALID
                     "Workspace registry has an unsupported shape"
                     {:path (str file)}))
        value))))

(defn- fsync-directory!
  [^Path dir]
  (try
    (with-open [channel (java.nio.channels.FileChannel/open
                         dir
                         (into-array OpenOption [StandardOpenOption/READ]))]
      (.force channel true))
    (catch Exception _ nil)))

(defn- write-registry!
  [store registry]
  (let [root (root-path (:root store))
        file (registry-file (:root store))
        _ (Files/createDirectories root (make-array FileAttribute 0))
        temp (Files/createTempFile root ".workspaces-" ".tmp"
                                   (make-array FileAttribute 0))
        bytes (codec/canonical-bytes registry)]
    (try
      (with-open [out (java.io.FileOutputStream. (.toFile temp))]
        (.write out bytes)
        (.flush out)
        (.sync (.getFD out)))
      (Files/move temp file
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (fsync-directory! root)
      registry
      (finally
        (try (Files/deleteIfExists temp) (catch Exception _ nil))))))

(defn- all-workspaces
  [store]
  (merge (:workspaces (read-registry store)) (:extras store)))

(defn- file-tree
  [^Path base {:keys [max-files max-total-bytes]}]
  (if-not (Files/exists base (no-links))
    {:ok? false :reason :missing}
    (try
      (let [root-before (attrs base)
            result
            (with-open [stream (Files/walk base (make-array java.nio.file.FileVisitOption 0))]
              (loop [paths (iterator-seq (.iterator stream))
                     files {}
                     excluded []
                     total 0]
                (if-let [^Path path (first paths)]
                  (cond
                    (= path base)
                    (recur (rest paths) files excluded total)

                    (Files/isSymbolicLink path)
                    (recur (rest paths) files
                           (conj excluded (str (.relativize base path)))
                           total)

                    (not (Files/isRegularFile path (no-links)))
                    (recur (rest paths) files excluded total)

                    :else
                    (let [before (attrs path)
                          bytes (Files/readAllBytes path)
                          after (attrs path)
                          relative (str/replace (str (.relativize base path)) "\\" "/")
                          size (alength ^bytes bytes)
                          total (+ total size)]
                      (when-not (and (= (.size before) (.size after))
                                     (= (.lastModifiedTime before)
                                        (.lastModifiedTime after)))
                        (err/fail! :BACKUP_SNAPSHOT_INCOHERENT
                                   "Workspace file changed while being read"
                                   {:path (str path)}))
                      (when (and max-files (> (inc (count files)) max-files))
                        (err/fail! :BACKUP_WORKSPACE_TOO_LARGE
                                   "Workspace has too many files"
                                   {:limit max-files}))
                      (when (and max-total-bytes (> total max-total-bytes))
                        (err/fail! :BACKUP_WORKSPACE_TOO_LARGE
                                   "Workspace exceeds the backup byte limit"
                                   {:limit max-total-bytes}))
                      (recur (rest paths)
                             (assoc files relative
                                    {:size size
                                     :sha256 (str "sha256-" (codec/sha256-hex bytes))
                                     :bytes-base64 (codec/base64-encode bytes)})
                             excluded
                             total)))
                  {:ok? true :files files :excluded excluded})))]
        (when-not (= (.lastModifiedTime root-before)
                     (.lastModifiedTime (attrs base)))
          (err/fail! :BACKUP_SNAPSHOT_INCOHERENT
                     "Workspace directory changed while being read"
                     {:path (str base)}))
        result)
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (err/fail! :BACKUP_WORKSPACE_IO
                   "Workspace files could not be read"
                   {:path (str base)}
                   e)))))

(defn- workspace-item
  [store ws {:keys [include-external-files? max-files max-total-bytes]}]
  (let [kind (:kind ws)
        external? (= kind :external)
        _ (when (and external? (str/blank? (str (:path ws))))
            (err/fail! :BACKUP_WORKSPACE_INVALID
                       "External workspace has no path"
                       {:id (:id ws)}))
        base (if external?
               (root-path (:path ws))
               (if-let [source (:source-path ws)]
                 (root-path source)
                 (.resolve (managed-root (:root store)) (:id ws))))
        read (when (or (not external?) include-external-files?)
               (file-tree base {:max-files max-files
                                :max-total-bytes max-total-bytes}))
        files (if (:ok? read) (:files read) {})]
    (cond-> {:id (:id ws)
             :name (:name ws)
             :kind kind
             :path (if external? (:path ws) (:id ws))
             :label (:label ws)
             :authorized? (not external?)
             :relink-required? external?
             :files-included? (boolean (:ok? read))
             :files-content files}
      (seq (:excluded read)) (assoc :excluded-paths (:excluded read))
      (not (:ok? read))
      (assoc :missing? true
             :missing-reason (:reason read)))))

(defn- read-workspaces*
  [store opts]
  (mapv #(workspace-item store % opts) (vals (all-workspaces store))))

(defn- write-file!
  [^Path root relative ^bytes bytes]
  (let [relative (safe-relative relative)
        target (.resolve root relative)]
    (when-not (.startsWith target root)
      (err/fail! :BACKUP_WORKSPACE_INVALID
                 "Workspace path escapes its root"
                 {:path relative}))
    (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
    (Files/write target bytes
                 (into-array StandardOpenOption
                             [StandardOpenOption/CREATE
                              StandardOpenOption/TRUNCATE_EXISTING
                              StandardOpenOption/WRITE]))))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (no-links))
    (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path path (->> (iterator-seq (.iterator stream))
                              (sort-by #(.getNameCount ^Path %))
                              reverse)]
        (Files/deleteIfExists path)))))

(defn- restore-managed!
  [store item]
  (let [id (assert-id! (:id item))
        target (.resolve (managed-root (:root store)) id)
        files (:files-content item)]
    (if (:missing? item)
      ;; The directory was unavailable when the local state was read.  Keep
      ;; the registry entry and its authorization decision, but do not create
      ;; or delete files from a path we could not inspect.
      {:id id
       :name (:name item)
       :kind :managed
       :path id
       :label (:label item)
       :authorized? true
       :missing? true}
      (do
        (when-not (map? files)
          (err/fail! :BACKUP_WORKSPACE_INCOMPLETE
                     "Managed workspace has no file content"
                     {:workspace-id id}))
        (delete-tree! target)
        (Files/createDirectories target (make-array FileAttribute 0))
        (doseq [[relative content] files]
          (write-file! target relative (codec/base64-decode (:bytes-base64 content))))
        {:id id
         :name (:name item)
         :kind :managed
         :path id
         :label (:label item)
         :authorized? true}))))

(defn- restore-external!
  [store item]
  (let [id (assert-id! (:id item))
        _ (when (str/blank? (str (:path item)))
            (err/fail! :BACKUP_WORKSPACE_INVALID
                       "External workspace restore has no path"
                       {:workspace-id id}))
        imported? (and (map? (:files-content item))
                       (seq (:files-content item)))
        import-dir (.resolve (external-root (:root store)) id)]
    (when imported?
      (delete-tree! import-dir)
      (Files/createDirectories import-dir (make-array FileAttribute 0))
      (doseq [[relative content] (:files-content item)]
        (write-file! import-dir relative
                     (codec/base64-decode (:bytes-base64 content)))))
    (cond-> {:id id
             :name (:name item)
             :kind :external
             :path (:path item)
             :label (:label item)
             :authorized? false
             :relink-required? true}
      imported? (assoc :imported-path (str import-dir)))))

(defn- validate-files!
  [workspace-id files]
  (when-not (map? files)
    (err/fail! :BACKUP_WORKSPACE_INCOMPLETE
               "Workspace item has no file content"
               {:workspace-id workspace-id}))
  (doseq [[relative content] files]
    (safe-relative relative)
    (when-not (string? (:bytes-base64 content))
      (err/fail! :BACKUP_WORKSPACE_INVALID
                 "Workspace file has no base64 content"
                 {:workspace-id workspace-id :path relative}))
    (codec/base64-decode (:bytes-base64 content))))

(defn- validate-item!
  [item]
  (let [id (assert-id! (:id item))]
    (case (:kind item)
      :managed
      (when-not (:missing? item)
        (validate-files! id (:files-content item)))

      :external
      (do
        (when (str/blank? (str (:path item)))
          (err/fail! :BACKUP_WORKSPACE_INVALID
                     "External workspace restore has no path"
                     {:workspace-id id}))
        (when (map? (:files-content item))
          (validate-files! id (:files-content item))))

      (err/fail! :BACKUP_WORKSPACE_INVALID
                 "Workspace item kind must be :managed or :external"
                 {:id id :kind (:kind item)}))))

(extend-type FileWorkspaceStore
  WorkspacePort
  (read-workspaces [store opts]
    (read-workspaces* store opts))
  (restore-workspaces! [store items]
    (let [items (vec items)
          _ (when-not (every? map? items)
              (err/fail! :BACKUP_WORKSPACE_INVALID
                         "Workspace items must be maps"
                         {}))
          _ (doseq [item items] (validate-item! item))
          _ (let [ids (map :id items)]
              (when-not (= (count ids) (count (set ids)))
                (err/fail! :BACKUP_WORKSPACE_INVALID
                           "Workspace restore contains duplicate IDs"
                           {:ids (vec (for [[id n] (frequencies ids)
                                            :when (> n 1)]
                                        id))})))
          entries (mapv (fn [item]
                          (case (:kind item)
                            :external (restore-external! store item)
                            :managed (restore-managed! store item)
                            (err/fail! :BACKUP_WORKSPACE_INVALID
                                       "Workspace item kind must be :managed or :external"
                                       {:id (:id item) :kind (:kind item)})))
                        items)
          registry {:format-version format-version
                    :workspaces (into {} (map (juxt :id identity)) entries)}
          previous (all-workspaces store)
          final-ids (set (map :id entries))]
      (doseq [[id ws] previous
              :when (and (= :managed (:kind ws))
                         (not (contains? final-ids id)))]
        (delete-tree! (.resolve (managed-root (:root store)) id)))
      (doseq [[id ws] previous
              :when (and (= :external (:kind ws))
                         (not (contains? final-ids id)))]
        (delete-tree! (.resolve (external-root (:root store)) id)))
      (write-registry! store registry)
      {:status :applied
       :workspaces (mapv #(select-keys % [:id :kind :path :authorized?
                                          :relink-required? :imported-path])
                         entries)})))
