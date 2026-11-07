(ns hc.core.workspace-gate.prepare
  "Batch in-memory view and complete dry-run.

  `write`, `mkdir`, `rm` and `mv` never touch the real file system.  They
  stage an ordered plan in memory, so an exception, a `park`, or an ordinary
  rejection leaves every real file unchanged.  `prepare-plan` re-checks the
  collected stamps and structure conditions under the write gate and returns
  a `Prepared` value that is distinct from a lock-free `Preview`."
  (:refer-clojure :exclude [cat])
  (:require [clojure.string :as str]
            [hc.core.workspace-gate.error :as err]
            [hc.core.workspace-gate.reader :as reader]
            [hc.core.workspace-gate.stamp :as stamp])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path Paths)))

(set! *warn-on-reflection* true)

(def default-limits
  {:max-file-bytes (* 2 1024 1024)
   :max-batch-bytes (* 16 1024 1024)
   :max-path-ops 256})

(def ^:private byte-array-class (Class/forName "[B"))

(defn new-batch
  "Start an empty in-memory batch rooted at `root`."
  ([root] (new-batch root {}))
  ([root opts]
   {:root (stamp/root-path root)
    :limits (merge default-limits opts)
    :read-set {}
    :checks {}
    :virtual {}
    :ops []
    :moves []
    :total-write-bytes 0}))

;; ---------------------------------------------------------------------------
;; Observations and checks

(defn- observation-signature [obs]
  (select-keys obs [:path :kind :stamp :unstable?]))

(defn- add-observation [batch obs]
  (let [p (:path obs)
        obs (select-keys obs [:path :kind :stamp :unstable? :logical-path])
        existing (get-in batch [:read-set p] [])]
    (if (some #(= (observation-signature %) (observation-signature obs)) existing)
      batch
      (assoc-in batch [:read-set p] (conj existing obs)))))

(defn- check-for-observation [obs]
  (case (:kind obs)
    :file (let [s (:stamp obs)
                unstable? (boolean (:unstable? obs))]
            {:path (:path obs)
             :expect :file
             :stamps (if s #{s} #{})
             :unstable? unstable?
             :observations (if s
                             [{:stamp s
                               :unstable? unstable?
                               :logical-path (:logical-path obs)}]
                             [])})
    :missing {:path (:path obs) :expect :missing}
    :directory {:path (:path obs) :expect :directory}
    :other {:path (:path obs) :expect :other}
    (err/fail! :UNSUPPORTED_OPERATION :unknown-observation
               "Cannot turn observation into a structure condition"
               {:observation obs})))

(defn- merge-observations
  [old new]
  (let [key-of (fn [o] [(:stamp o) (:unstable? o) (:logical-path o)])
        [acc seen]
        (reduce (fn [[acc seen] o]
                  (let [k (key-of o)]
                    (if (contains? seen k)
                      [acc seen]
                      [(conj acc o) (conj seen k)])))
                [(vec old) (into #{} (map key-of old))]
                new)]
    acc))

(defn- add-check [batch check]
  (let [p (:path check)
        old (get-in batch [:checks p])]
    (cond
      (nil? old)
      (assoc-in batch [:checks p]
                (cond-> (select-keys check [:path :expect :stamps :unstable?
                                            :observations])
                  (= :file (:expect check)) (update :stamps (fnil identity #{}))
                  (= :file (:expect check)) (update :observations (fnil identity []))
                  (:empty? check) (assoc :empty? true)))

      (not= (:expect old) (:expect check))
      (err/fail! :PREPARE_REJECTED :inconsistent-observation
                 "A path changed type during this batch"
                 {:path p :first (:expect old) :second (:expect check)})

      (= :file (:expect check))
      (assoc-in batch [:checks p]
                (cond-> (-> old
                            (update :stamps (fnil into #{}) (or (:stamps check) #{}))
                            (update :observations (fnil merge-observations [])
                                    (or (:observations check) []))
                            (assoc :unstable? (or (:unstable? old) (:unstable? check))))
                  (:empty? check) (assoc :empty? true)))

      :else
      (assoc-in batch [:checks p]
                (cond-> (assoc old :unstable? (or (:unstable? old) (:unstable? check)))
                  (:empty? check) (assoc :empty? true))))))

(defn- record-observation [batch obs]
  (-> batch
      (add-observation obs)
      (add-check (check-for-observation obs))))

(defn- stamp-shaped? [x]
  (and (map? x)
       (contains? x :mtime-sec)
       (contains? x :mtime-nsec)
       (contains? x :size)))

(defn- check-for-read-set-entry [path entry]
  (let [path (or (when (map? entry) (:path entry)) path)]
    (when (nil? path)
      (err/fail! :UNSUPPORTED_OPERATION :bad-read-set-entry
                 "Read-set entry requires a path"
                 {:entry entry}))
    (cond
      (and (map? entry) (contains? entry :kind))
      (check-for-observation (assoc entry :path path))

      (and (map? entry) (contains? entry :stamp))
      (check-for-observation {:path path
                              :kind :file
                              :stamp (:stamp entry)
                              :unstable? (:unstable? entry)})

      (stamp-shaped? entry)
      (check-for-observation {:path path :kind :file :stamp entry})

      :else
      (err/fail! :UNSUPPORTED_OPERATION :bad-read-set-entry
                 "Read-set entry is neither an observation nor a stamp"
                 {:path path :entry entry}))))

(defn- normalize-read-set-checks
  "Merge every read-set observation into `:checks`.  `session` already does
  this, but a hand-built batch must not be able to carry an unchecked read
  basis into apply."
  [batch]
  (let [read-set (:read-set batch)]
    (cond
      (nil? read-set) batch

      (map? read-set)
      (reduce-kv
       (fn [b path observations]
         (if (sequential? observations)
           (reduce (fn [b obs] (add-check b (check-for-read-set-entry path obs)))
                   b observations)
           (add-check b (check-for-read-set-entry path observations))))
       batch read-set)

      (sequential? read-set)
      (reduce (fn [b entry]
                (if (map? entry)
                  (add-check b (check-for-read-set-entry (:path entry) entry))
                  (err/fail! :UNSUPPORTED_OPERATION :bad-read-set-entry
                             "Read-set entry must be a map"
                             {:entry entry})))
              batch read-set)

      :else
      (err/fail! :UNSUPPORTED_OPERATION :bad-read-set
                 "Read-set must be a map or sequence"
                 {:actual (some-> read-set class str)}))))

(defn- normalize-batch [batch]
  (try
    {:batch (normalize-read-set-checks batch)}
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected-failure? e)
        {:failure (ex-data e)}
        (throw e)))))

;; ---------------------------------------------------------------------------
;; Virtual view and move resolution

(defn- real-path [batch p]
  (stamp/path-of (:root batch) p))

(defn- resolve-real
  "Map a logical path back to the real path it currently names.

  Moves are interpreted in reverse so a later move is resolved before an
  earlier one.  `:moved-away` means the path was moved by this batch and has
  not been recreated in the virtual view."
  [batch p]
  (loop [cur p
         moves (reverse (:moves batch))]
    (if-let [{:keys [source target]} (first moves)]
      (cond
        (or (= cur target) (stamp/under? cur target))
        (recur (if (= cur target)
                 source
                 (stamp/rebase cur target source))
               (rest moves))

        (or (= cur source) (stamp/under? cur source))
        {:kind :moved-away :path cur}

        :else
        (recur cur (rest moves)))
      {:kind :real :path cur})))

(defn- virtual-ancestor [batch p]
  (loop [cur (.getParent ^Path (real-path batch p))]
    (when cur
      (let [s (str cur)
            node (get (:virtual batch) s)]
        (if node
          {:path s :node node}
          (recur (.getParent ^Path cur)))))))

(defn- real-ancestor-non-dir
  "Return the first existing real ancestor of `p` that is not a directory.

  `stamp/attrs` deliberately swallows `NotDirectoryException` for the final
  path.  Walking the parents recovers the difference between \"missing\" and
  \"under a regular file\" without changing the shared attrs helper."
  [^Path p]
  (loop [cur (.getParent p)]
    (when cur
      (let [a (stamp/attrs cur)]
        (cond
          (nil? a) (recur (.getParent cur))
          (.isDirectory ^java.nio.file.attribute.BasicFileAttributes a)
          (recur (.getParent cur))
          :else cur)))))

(defn- real-lookup [batch p]
  (let [{:keys [kind path]} (resolve-real batch p)]
    (if (= :moved-away kind)
      {:kind :missing :path p :virtual? false :deleted? true :moved-away? true}
      (let [rp (real-path batch path)
            blocker (real-ancestor-non-dir rp)]
        (if blocker
          {:kind :not-dir
           :path p
           :real-path (str rp)
           :virtual? false
           :deleted? false
           :ancestor (str blocker)}
          (let [a (stamp/attrs rp)
                k (cond
                    (nil? a) :missing
                    (.isDirectory ^java.nio.file.attribute.BasicFileAttributes a) :dir
                    (.isRegularFile ^java.nio.file.attribute.BasicFileAttributes a) :file
                    :else :other)]
            (cond-> {:kind k
                     :path p
                     :real-path (str rp)
                     :virtual? false
                     :deleted? false}
              (= :file k) (assoc :stamp (stamp/stamp-of-attrs a)))))))))

(defn lookup
  "Resolve a logical path through the virtual view and move chain.

  Result `:kind` is `:file`, `:dir`, `:missing`, `:other` or `:not-dir`.
  `:virtual?` marks state produced by this batch; `:deleted?` marks a path
  this batch removed or moved away."
  [batch p]
  (if-let [node (get (:virtual batch) p)]
    (assoc node :path p :virtual? true :deleted? (= :missing (:kind node)))
    (if-let [{:keys [path node]} (virtual-ancestor batch p)]
      (case (:kind node)
        :file {:kind :not-dir :path p :virtual? true :ancestor path}
        :missing {:kind :missing :path p :virtual? true :deleted? true}
        :dir (real-lookup batch p)
        {:kind :not-dir :path p :virtual? true :ancestor path})
      (real-lookup batch p))))

(defn- observation-kind
  "The view uses `:dir`; reader observations use `:directory`."
  [obs]
  (case (:kind obs)
    :directory :dir
    (:kind obs)))

(defn- ensure-parent [batch p]
  (let [pp (.getParent ^Path (real-path batch p))]
    (when (nil? pp)
      (err/fail! :STRUCTURE_CONFLICT :no-parent
                 "The workspace root cannot be used as a file target"
                 {:path p}))
    (let [ps (str pp)
          node (lookup batch ps)]
      (case (:kind node)
        :dir (if (:virtual? node)
               batch
               (let [obs (reader/observe (:root batch) (:real-path node))]
                 (when-not (= :dir (observation-kind obs))
                   (err/fail! :STRUCTURE_CONFLICT :parent-not-directory
                              "Parent path is no longer a directory"
                              {:path ps :actual (:kind obs)}))
                 (record-observation batch obs)))
        :missing
        (if (:deleted? node)
          ;; F04: the batch removed the parent and did not recreate it.  The
          ;; operation's structure condition is false in the batch view, so
          ;; this is a structure conflict, not an ordinary prepare refusal.
          (err/fail! :STRUCTURE_CONFLICT :missing-parent
                     "Parent directory was deleted in this batch and not recreated"
                     {:path p :parent ps :reason :missing-parent})
          (err/fail! :PREPARE_REJECTED :missing-parent
                     "Parent directory does not exist"
                     {:path p :parent ps}))
        (err/fail! :STRUCTURE_CONFLICT :parent-not-directory
                   "Parent path is not a directory"
                   {:path p :parent ps})))))

(defn- observe-real-node
  "Observe a real node and require its current kind to be one of
  `expected-kinds`.  A virtual node needs no disk observation."
  [batch node expected-kinds]
  (if (:virtual? node)
    batch
    (let [obs (reader/observe (:root batch) (:real-path node))]
      (when-not (contains? expected-kinds (observation-kind obs))
        (err/fail! :STRUCTURE_CONFLICT :type-changed
                   "Path type changed while the batch was being built"
                   {:path (:path node) :expected expected-kinds :actual (:kind obs)}))
      (record-observation batch obs))))

(defn- record-missing [batch p]
  (add-check batch {:path p :expect :missing}))

(defn- add-op [batch op]
  (when (>= (count (:ops batch)) (get-in batch [:limits :max-path-ops]))
    (err/fail! :LIMIT_EXCEEDED :too-many-path-operations
               "Batch exceeds the configured path-operation limit"
               {:limit (get-in batch [:limits :max-path-ops])}))
  (update batch :ops conj op))

(defn- virtual-direct-children [batch p]
  (into {}
        (keep (fn [[k node]]
                (let [kp (real-path batch k)
                      parent (.getParent ^Path kp)]
                  (when (and parent (= (str parent) p))
                    [(str (.getFileName ^Path kp)) node]))))
        (:virtual batch)))

(defn- child-path
  [p name]
  (str (.resolve ^Path (Paths/get (str p) (make-array String 0))
                 (str name))))

(defn- direct-child-name
  [p path]
  (let [pp (Paths/get (str p) (make-array String 0))
        cp (Paths/get (str path) (make-array String 0))
        parent (.getParent cp)]
    (when (and parent (= (str parent) (str pp)))
      (str (.getFileName cp)))))

(defn- real-directory-map
  "Merge the real listing with the batch view.

  A real child that this batch moved away or removed must disappear from the
  listing; `lookup` supplies that fact.  A real child that is still present
  keeps its current view kind."
  [batch p real]
  (into {}
        (keep (fn [name]
                (let [dir? (str/ends-with? name "/")
                      base (if dir? (subs name 0 (dec (count name))) name)
                      node (lookup batch (child-path p base))]
                  (when-not (:deleted? node)
                    [base (if (= :dir (:kind node)) :dir :file)]))))
        real))

(defn- moved-in-map
  "Names that this batch moved directly into `p`.

  The source may be a real path, so it has no `:virtual` node to move; the
  move list is the only record of its new name."
  [batch p]
  (into {}
        (keep (fn [{:keys [target]}]
                (when-let [name (direct-child-name p target)]
                  (let [node (lookup batch target)]
                    (when (and (not= :missing (:kind node))
                               (not (:deleted? node)))
                      [name (if (= :dir (:kind node)) :dir :file)])))))
        (:moves batch)))

(defn- dir-empty? [^Path p]
  (try
    (with-open [stream (Files/list p)]
      (zero? (count (iterator-seq (.iterator stream)))))
    (catch java.nio.file.NoSuchFileException _ false)
    (catch java.nio.file.NotDirectoryException _ false)))

(defn- remap-virtual [batch p q source-dir?]
  (update batch :virtual
          (fn [virtual]
            (let [virtual (if source-dir?
                            (into {} (remove (fn [[k _]] (stamp/under? k q))) virtual)
                            (dissoc virtual q))]
              (into {}
                    (map (fn [[k node]]
                           (cond
                             (= k p) [q node]
                             (and source-dir? (stamp/under? k p))
                             [(stamp/rebase k p q) node]
                             :else [k node])))
                    virtual)))))

;; ---------------------------------------------------------------------------
;; Batch operations

(defn read-path
  "Stage a `cat`.  Returns `[batch {:text ... :stamp ... ...}]`.

  The read does not take the write gate.  A read whose stamp moved between
  the two metadata samples is recorded as unstable and forces a later
  `:FILE_CONFLICT` at commit."
  [batch path]
  (let [p (stamp/path-string (:root batch) path)
        node (lookup batch p)]
    (case (:kind node)
      :file
      (if (:virtual? node)
        [batch {:path p
                :virtual? true
                :text (reader/decode-text (:bytes node) p)
                :changed-during-read? false}]
        (let [r (reader/read-text (:root batch) (:real-path node) (:limits batch))
              obs {:path (:path r)
                   :kind :file
                   :stamp (:stamp r)
                   :unstable? (:changed-during-read? r)
                   :logical-path p}]
          [(record-observation batch obs)
           {:path p
            :real-path (:path r)
            :virtual? false
            :text (:text r)
            :stamp (:stamp r)
            :changed-during-read? (:changed-during-read? r)}]))

      :dir
      (err/fail! :STRUCTURE_CONFLICT :not-a-file
                 "Path is a directory"
                 {:path p})

      :missing
      (if (:deleted? node)
        (err/fail! :PREPARE_REJECTED :deleted-in-batch
                   "Cannot read a path this batch deleted or moved away"
                   {:path p})
        (err/fail! :STRUCTURE_CONFLICT :missing
                   "Path does not exist"
                   {:path p}))

      :not-dir
      (err/fail! :STRUCTURE_CONFLICT :ancestor-not-directory
                 "An ancestor is a file"
                 {:path p})

      :other
      (err/fail! :UNSUPPORTED_OPERATION :not-a-regular-file
                 "Only regular files support text reads"
                 {:path p}))))

(defn stat-path
  "Stage a `stat`.  Missing real paths are recorded as structure checks."
  [batch path]
  (let [p (stamp/path-string (:root batch) path)
        node (lookup batch p)]
    (case (:kind node)
      :file
      (if (:virtual? node)
        [batch {:exists true :is-dir false :size (alength ^bytes (:bytes node)) :virtual? true}]
        [(observe-real-node batch node #{:file})
         (assoc (reader/stat-path (:root batch) (:real-path node))
                :real-path (:real-path node))])

      :dir
      (if (:virtual? node)
        [batch {:exists true :is-dir true :virtual? true}]
        [(observe-real-node batch node #{:dir})
         (assoc (reader/stat-path (:root batch) (:real-path node))
                :real-path (:real-path node))])

      :missing
      (if (:deleted? node)
        (err/fail! :PREPARE_REJECTED :deleted-in-batch
                   "Cannot stat a path this batch deleted or moved away"
                   {:path p})
        (let [obs (reader/observe (:root batch) (:real-path node))]
          [(record-observation batch obs) {:exists false}]))

      :not-dir
      [batch {:exists false :ancestor-not-directory true}]

      :other
      [(observe-real-node batch node #{:other})
       (assoc (reader/stat-path (:root batch) (:real-path node))
              :real-path (:real-path node))])))

(defn list-path
  "Stage an `ls`.  Pure probe: no read-set entry, no structure check."
  [batch path]
  (let [p (stamp/path-string (:root batch) path)
        node (lookup batch p)]
    (case (:kind node)
      :dir
      (let [real (if (:virtual? node)
                   []
                   (reader/list-dir (:root batch) (:real-path node)))
            real-map (real-directory-map batch p real)
            virtual (virtual-direct-children batch p)
            moved-in (moved-in-map batch p)
            merged (merge real-map
                          (into {}
                                (map (fn [[name node]]
                                       [name (:kind node)]))
                                virtual))
            merged (merge merged moved-in)
            merged (into {}
                         (remove (fn [[_ kind]] (= :missing kind)))
                         merged)]
        [batch
         (->> merged
              (map (fn [[name kind]]
                     (if (= :dir kind) (str name "/") name)))
              sort
              vec)])

      :file
      (err/fail! :STRUCTURE_CONFLICT :not-a-directory
                 "Path is not a directory"
                 {:path p})

      :missing
      (if (:deleted? node)
        (err/fail! :PREPARE_REJECTED :deleted-in-batch
                   "Cannot list a path this batch deleted or moved away"
                   {:path p})
        (err/fail! :STRUCTURE_CONFLICT :missing
                   "Directory does not exist"
                   {:path p}))

      :not-dir
      (err/fail! :STRUCTURE_CONFLICT :ancestor-not-directory
                 "An ancestor is a file"
                 {:path p})

      :other
      (err/fail! :UNSUPPORTED_OPERATION :not-a-directory
                 "Only directories can be listed"
                 {:path p}))))

(defn- stage-write-op [batch p ^bytes bytes create?]
  (-> batch
      (add-op {:op :write :path p :bytes bytes :create? (boolean create?)})
      (update :total-write-bytes + (alength bytes))
      (assoc-in [:virtual p] {:kind :file :bytes bytes})))

(defn stage-write
  "Stage a full-file UTF-8 write.

  Replacing an existing real file auto-observes its current stamp; an earlier
  read of the same path remains in the read-set, so a stale patch cannot hide
  behind the newer stamp (F17-04)."
  [batch path content]
  (when-not (string? content)
    (err/fail! :UNSUPPORTED_OPERATION :not-text
               "Only UTF-8 text content is supported"
               {:path (stamp/path-string (:root batch) path)}))
  (let [p (stamp/path-string (:root batch) path)
        bytes (.getBytes ^String content StandardCharsets/UTF_8)
        round-trip (String. ^bytes bytes StandardCharsets/UTF_8)
        n (alength bytes)
        limits (:limits batch)]
    (when-not (= content round-trip)
      (err/fail! :UNSUPPORTED_OPERATION :invalid-text
                 "Content contains code units that UTF-8 would silently replace"
                 {:path p}))
    (when (> n (:max-file-bytes limits))
      (err/fail! :LIMIT_EXCEEDED :file-too-large
                 "Content exceeds the configured single-file limit"
                 {:path p :size n :limit (:max-file-bytes limits)}))
    (when (> (+ (:total-write-bytes batch) n) (:max-batch-bytes limits))
      (err/fail! :LIMIT_EXCEEDED :batch-too-large
                 "Content exceeds the configured batch byte limit"
                 {:path p :size n :limit (:max-batch-bytes limits)}))
    (let [node (lookup batch p)]
      (case (:kind node)
        :dir
        (err/fail! :STRUCTURE_CONFLICT :is-directory
                   "Cannot replace a directory with a file"
                   {:path p})

        :other
        (err/fail! :UNSUPPORTED_OPERATION :not-a-regular-file
                   "Only regular files can be replaced"
                   {:path p})

        :not-dir
        (err/fail! :STRUCTURE_CONFLICT :ancestor-not-directory
                   "An ancestor is a file"
                   {:path p})

        :missing
        (let [b (ensure-parent batch p)
              b (if (:deleted? node) b (record-missing b p))]
          (stage-write-op b p bytes true))

        :file
        (let [b (ensure-parent batch p)
              b (observe-real-node b node #{:file})]
          (stage-write-op b p bytes false))))))

(defn stage-mkdir
  "Stage creation of one directory.  Parent must already exist in the view."
  [batch path]
  (let [p (stamp/path-string (:root batch) path)
        node (lookup batch p)]
    (case (:kind node)
      :missing
      (let [b (ensure-parent batch p)
            b (if (:deleted? node) b (record-missing b p))]
        (-> b
            (add-op {:op :mkdir :path p})
            (assoc-in [:virtual p] {:kind :dir})))

      :dir
      (err/fail! :PREPARE_REJECTED :already-exists
                 "Directory already exists"
                 {:path p})

      :file
      (err/fail! :STRUCTURE_CONFLICT :already-exists
                 "A file already exists at the target path"
                 {:path p})

      :not-dir
      (err/fail! :STRUCTURE_CONFLICT :ancestor-not-directory
                 "An ancestor is a file"
                 {:path p})

      :other
      (err/fail! :UNSUPPORTED_OPERATION :not-a-directory
                 "Target is not an ordinary path"
                 {:path p}))))

(defn stage-rm
  "Stage deletion of one observed file or one empty directory.

  Broad recursive deletion is deliberately left to the shell path."
  [batch path]
  (let [p (stamp/path-string (:root batch) path)
        node (lookup batch p)]
    (case (:kind node)
      :file
      (let [b (observe-real-node batch node #{:file})]
        (-> b
            (add-op {:op :rm :path p})
            (assoc-in [:virtual p] {:kind :missing})))

      :dir
      (do
        (when (some (fn [[_ node]] (not= :missing (:kind node)))
                    (virtual-direct-children batch p))
          (err/fail! :UNSUPPORTED_OPERATION :non-empty-directory
                     "Directory contains staged entries; remove them first"
                     {:path p}))
        (let [b (if (:virtual? node)
                  batch
                  (let [b (observe-real-node batch node #{:dir})
                        rp (real-path batch (:real-path node))]
                    (when-not (dir-empty? rp)
                      (err/fail! :UNSUPPORTED_OPERATION :non-empty-directory
                                 "Recursive directory deletion belongs to the shell path"
                                 {:path p}))
                    (add-check b {:path (:real-path node) :expect :directory :empty? true})))]
          (-> b
              (add-op {:op :rm :path p})
              (assoc-in [:virtual p] {:kind :missing}))))

      :missing
      (err/fail! :STRUCTURE_CONFLICT :missing
                 "Cannot delete a path that does not exist"
                 {:path p})

      :not-dir
      (err/fail! :STRUCTURE_CONFLICT :ancestor-not-directory
                 "An ancestor is a file"
                 {:path p})

      :other
      (err/fail! :UNSUPPORTED_OPERATION :not-a-regular-file
                 "Only regular files and empty directories can be deleted"
                 {:path p}))))

(defn stage-mv
  "Stage a path move.  Sequential moves are interpreted in program order."
  ([batch source target] (stage-mv batch source target {}))
  ([batch source target {:keys [overwrite?]}]
   (let [p (stamp/path-string (:root batch) source)
         q (stamp/path-string (:root batch) target)]
     (when (= p q)
       (err/fail! :PREPARE_REJECTED :same-path
                  "Source and target are the same path"
                  {:path p}))
     (let [sn (lookup batch p)
           tn (lookup batch q)]
       (case (:kind sn)
         :missing
         (err/fail! :STRUCTURE_CONFLICT :missing-source
                    "Move source does not exist"
                    {:path p})

         :not-dir
         (err/fail! :STRUCTURE_CONFLICT :ancestor-not-directory
                    "Move source has a file ancestor"
                    {:path p})

         :other
         (err/fail! :UNSUPPORTED_OPERATION :not-a-regular-file
                    "Only regular files and directories can be moved"
                    {:path p})

         (let [source-dir? (= :dir (:kind sn))]
           (when (and source-dir? (stamp/under? q p))
             (err/fail! :PREPARE_REJECTED :move-cycle
                        "Cannot move a directory inside itself"
                        {:source p :target q}))
           (case (:kind tn)
             :not-dir
             (err/fail! :STRUCTURE_CONFLICT :ancestor-not-directory
                        "Move target has a file ancestor"
                        {:path q})

             :dir
             (err/fail! :STRUCTURE_CONFLICT :target-is-directory
                        "A move cannot replace a directory"
                        {:path q})

             :file
             (do
               (when-not overwrite?
                 (err/fail! :PREPARE_REJECTED :target-exists
                            "Move target exists and overwrite was not declared"
                            {:path q}))
               (when source-dir?
                 (err/fail! :STRUCTURE_CONFLICT :cannot-overwrite-file-with-directory
                            "A directory cannot replace a file"
                            {:source p :target q})))

             :other
             (err/fail! :UNSUPPORTED_OPERATION :not-a-regular-file
                        "Move target is not an ordinary path"
                        {:path q})

             :missing nil)
           (let [b (observe-real-node batch sn #{:file :dir})
                 b (if (and (= :file (:kind tn)) (not (:virtual? tn)))
                     (observe-real-node b tn #{:file})
                     b)
                 b (ensure-parent b q)
                 b (if (and (= :missing (:kind tn)) (not (:deleted? tn)))
                     (record-missing b q)
                     b)
                 b (remap-virtual b p q source-dir?)]
             (-> b
                 (add-op {:op :mv :source p :target q :overwrite? (boolean overwrite?)})
                 (update :moves conj {:source p :target q})))))))))

;; ---------------------------------------------------------------------------
;; Final checks, dry-run replay and prepared values

(defn- attrs-kind [^java.nio.file.attribute.BasicFileAttributes a]
  (cond
    (nil? a) :missing
    (.isDirectory a) :directory
    (.isRegularFile a) :file
    :else :other))

(defn- check-one [batch p check]
  (let [rp (real-path batch p)
        a (stamp/attrs rp)
        kind (attrs-kind a)]
    (case (:expect check)
      :missing
      (when-not (= :missing kind)
        (err/failure :STRUCTURE_CONFLICT :expected-missing
                     "Path exists but was observed as absent"
                     {:path p :expected :missing :actual kind}))

      :directory
      (cond
        (not= :directory kind)
        (err/failure :STRUCTURE_CONFLICT :expected-directory
                     "Path is no longer a directory"
                     {:path p :expected :directory :actual kind})

        (and (:empty? check) (not (dir-empty? rp)))
        (err/failure :STRUCTURE_CONFLICT :directory-not-empty
                     "Directory is no longer empty"
                     {:path p :expected :empty-directory :actual :non-empty})

        :else nil)

      :file
      (let [current (when (= :file kind) (stamp/stamp-of-attrs a))
            stamps (:stamps check)]
        (cond
          (not= :file kind)
          (err/failure :STRUCTURE_CONFLICT :expected-file
                       "Path is no longer a regular file"
                       {:path p :expected :file :actual kind})

          (:unstable? check)
          (err/failure :FILE_CONFLICT :changed-during-read
                       "Read was unstable and cannot support a commit"
                       {:path p
                        :expected stamps
                        :observations (vec (:observations check))
                        :actual current})

          (or (empty? stamps)
              (not (every? #(stamp/same-stamp? % current) stamps)))
          (err/failure :FILE_CONFLICT :stamp-mismatch
                       "File stamp no longer matches the read basis"
                       {:path p
                        :expected (vec stamps)
                        :observations (vec (:observations check))
                        :actual current})

          :else nil))

      :other
      (when-not (= :other kind)
        (err/failure :STRUCTURE_CONFLICT :expected-other
                     "Path type changed"
                     {:path p :expected :other :actual kind}))

      (err/failure :UNSUPPORTED_OPERATION :unknown-check
                   "Unknown structure condition"
                   {:path p :check check}))))

(defn check-current
  "Re-check every collected stamp and structure condition against the real
  workspace.  Returns nil when all conditions hold, otherwise an ordinary
  rejection result.  Never modifies the file system."
  [batch]
  (when-let [failure (some (fn [[p check]] (check-one batch p check))
                           (sort-by key (:checks batch)))]
    (assoc failure :observation-basis :mtime+size)))

(defn- state-kind [state p]
  (get state p :missing))

(defn- remap-state [state p q source-dir?]
  (let [state (if source-dir?
                (into {} (remove (fn [[k _]] (stamp/under? k q))) state)
                (dissoc state q))]
    (into {}
          (map (fn [[k kind]]
                 (cond
                   (= k p) [q kind]
                   (and source-dir? (stamp/under? k p))
                   [(stamp/rebase k p q) kind]
                   :else [k kind])))
          state)))

(defn- parent-path [path]
  (some-> (java.nio.file.Paths/get (str path) (make-array String 0))
          (.getParent)
          str))

(defn- state-direct-children
  [state p]
  (keep (fn [[k kind]]
          (when (and (not= :missing kind)
                     (= p (parent-path k)))
            k))
        state))

(defn- directory-emptiness-verified?
  "A virtual directory is empty when the state has no live children.  A real
  directory needs an `:empty?` check (the session adds one when it stages the
  delete); otherwise a hand-built plan could reach apply with an unverified
  non-empty directory."
  [batch path]
  (let [node (lookup batch path)
        real (:real-path node)]
    (or (:virtual? node)
        (boolean (get-in batch [:checks path :empty?]))
        (and real (boolean (get-in batch [:checks real :empty?]))))))

(defn- valid-utf8-bytes?
  [^bytes bytes]
  (try
    (let [text (String. bytes StandardCharsets/UTF_8)]
      (java.util.Arrays/equals bytes (.getBytes text StandardCharsets/UTF_8)))
    (catch Throwable _
      false)))

(declare describe-op)

(defn- validate-op
  [batch state {:keys [op path source target overwrite?] :as operation}]
  (case op
    :mkdir
    (let [parent (parent-path path)
          parent-kind (state-kind state parent)]
      (cond
        (not= :directory parent-kind)
        (err/failure :PREPARE_REJECTED :inconsistent-plan
                     "Planned mkdir parent is not a directory"
                     {:op (describe-op operation)
                      :parent parent
                      :actual parent-kind})

        (not= :missing (state-kind state path))
        (err/failure :PREPARE_REJECTED :inconsistent-plan
                     "Planned mkdir target is not absent"
                     {:op (describe-op operation)
                      :actual (state-kind state path)})

        :else [(assoc state path :directory)]))

    :write
    (let [bytes (:bytes operation)]
      (cond
        (not (instance? byte-array-class bytes))
        (err/failure :PREPARE_REJECTED :invalid-write-bytes
                     "A planned write must carry a byte array"
                     {:op (describe-op operation)
                      :actual (some-> bytes class str)})

        (not (valid-utf8-bytes? bytes))
        (err/failure :PREPARE_REJECTED :non-canonical-utf8
                     "Planned write bytes do not round-trip through UTF-8"
                     {:op (describe-op operation)})

        :else
        (let [parent (parent-path path)
              parent-kind (state-kind state parent)
              target-kind (state-kind state path)
              create? (if (contains? operation :create?)
                        (:create? operation)
                        (= :missing target-kind))
              expected (if create? :missing :file)]
          (cond
            (not= :directory parent-kind)
            (err/failure :PREPARE_REJECTED :inconsistent-plan
                         "Planned write parent is not a directory"
                         {:op (describe-op operation)
                          :parent parent
                          :actual parent-kind})

            (not= expected target-kind)
            (err/failure :PREPARE_REJECTED :inconsistent-plan
                         "Planned write target does not match create/replace mode"
                         {:op (describe-op operation)
                          :expected expected
                          :actual target-kind})

            :else [(assoc state path :file)]))))

    :rm
    (let [kind (state-kind state path)]
      (cond
        (not (contains? #{:file :directory} kind))
        (err/failure :PREPARE_REJECTED :inconsistent-plan
                     "Planned delete target does not exist"
                     {:op (describe-op operation) :actual kind})

        (and (= :directory kind)
             (seq (state-direct-children state path)))
        (err/failure :PREPARE_REJECTED :non-empty-directory
                     "Planned delete target still has live children"
                     {:op (describe-op operation)
                      :children (vec (state-direct-children state path))})

        (and (= :directory kind)
             (not (directory-emptiness-verified? batch path)))
        (err/failure :PREPARE_REJECTED :directory-emptiness-unverified
                     "A real directory delete needs an empty-directory check"
                     {:op (describe-op operation)})

        :else [(assoc state path :missing)]))

    :mv
    (let [parent (parent-path target)
          parent-kind (state-kind state parent)
          source-kind (state-kind state source)
          target-kind (state-kind state target)
          same-path? (= source target)
          cycle? (and (= :directory source-kind)
                      (stamp/under? target source))
          ok? (and (contains? #{:file :directory} source-kind)
                   (or (= :missing target-kind)
                       (and overwrite? (= :file source-kind) (= :file target-kind))))]
      (cond
        same-path?
        (err/failure :PREPARE_REJECTED :same-path
                     "Planned move source and target are the same path"
                     {:op (describe-op operation)})

        cycle?
        (err/failure :PREPARE_REJECTED :move-cycle
                     "Planned move puts a directory inside itself"
                     {:op (describe-op operation)
                      :source source
                      :target target})

        (not= :directory parent-kind)
        (err/failure :PREPARE_REJECTED :inconsistent-plan
                     "Planned move target parent is not a directory"
                     {:op (describe-op operation)
                      :parent parent
                      :actual parent-kind})

        ok?
        (let [state (remap-state state source target (= :directory source-kind))]
          [(assoc state target source-kind)])

        :else
        (err/failure :PREPARE_REJECTED :inconsistent-plan
                     "Planned move preconditions do not hold"
                     {:op (describe-op operation)
                      :source source-kind
                      :target target-kind})))

    (err/failure :UNSUPPORTED_OPERATION :unknown-plan-operation
                 "Unknown planned operation"
                 {:op (describe-op operation)})))

(defn- validate-ops [batch]
  (try
    (let [initial (into {}
                        (map (fn [[p check]]
                               [p (case (:expect check)
                                    :file :file
                                    :directory :directory
                                    :missing :missing
                                    :other :other)]))
                        (:checks batch))]
      (loop [state initial
             ops (:ops batch)]
        (if-let [op (first ops)]
          (let [result (validate-op batch state op)]
            (if (:status result)
              result
              (recur (first result) (rest ops))))
          nil)))
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected-failure? e)
        (ex-data e)
        (err/failure :PREPARE_REJECTED :invalid-plan
                     "Planned operations cannot be replayed"
                     {:exception (err/throwable->data e)})))
    (catch Throwable t
      (err/failure :PREPARE_REJECTED :invalid-plan
                   "Planned operations cannot be replayed"
                   {:exception (err/throwable->data t)}))))

(defn- check-limits [batch]
  (let [ops (:ops batch)
        limits (:limits batch)
        writes (filter #(= :write (:op %)) ops)
        invalid (some (fn [operation]
                        (when-not (instance? byte-array-class (:bytes operation))
                          operation))
                      writes)]
    (cond
      invalid
      (err/failure :PREPARE_REJECTED :invalid-write-bytes
                   "A planned write must carry a byte array"
                   {:op (describe-op invalid)
                    :actual (some-> (:bytes invalid) class str)})

      (> (count ops) (:max-path-ops limits))
      (err/failure :LIMIT_EXCEEDED :too-many-path-operations
                   "Batch exceeds the configured path-operation limit"
                   {:count (count ops) :limit (:max-path-ops limits)})

      :else
      (let [total (reduce + 0 (map #(alength ^bytes (:bytes %)) writes))
            largest (reduce max 0 (map #(alength ^bytes (:bytes %)) writes))]
        (cond
          (> largest (:max-file-bytes limits))
          (err/failure :LIMIT_EXCEEDED :file-too-large
                       "A planned file exceeds the configured single-file limit"
                       {:size largest :limit (:max-file-bytes limits)})

          (> total (:max-batch-bytes limits))
          (err/failure :LIMIT_EXCEEDED :batch-too-large
                       "Planned writes exceed the configured batch byte limit"
                       {:size total :limit (:max-batch-bytes limits)})

          :else nil)))))

(defn summary [batch]
  {:op-count (count (:ops batch))
   :write-count (count (filter #(= :write (:op %)) (:ops batch)))
   :create-count (count (filter #(and (= :write (:op %)) (:create? %))
                                (:ops batch)))
   :replace-count (count (filter #(and (= :write (:op %)) (not (:create? %)))
                                 (:ops batch)))
   :write-bytes (:total-write-bytes batch)
   :read-set-count (count (:read-set batch))
   :check-count (count (:checks batch))
   :changed-paths (->> (:ops batch)
                       (mapcat (fn [{:keys [op path source target]}]
                                 (if (= :mv op) [source target] [path])))
                       set
                       sort
                       vec)})

(defn describe-op
  "EDN-friendly operation description.  Preview results never carry raw
  bytes; `Prepared` keeps the final bytes for the applier."
  [{:keys [bytes] :as operation}]
  (cond-> (select-keys operation [:op :path :source :target :overwrite? :create?])
    (instance? byte-array-class bytes) (assoc :size (alength ^bytes bytes))))

(defrecord Preview [ops summary root])
(defrecord Prepared [ops summary root gate-token])

(defn preview
  "Lock-free dry-run.  Returns a `Preview` or an ordinary rejection result.

  A preview is not a write permit; it may be stale immediately after it is
  returned."
  [batch]
  (let [{:keys [batch failure]} (normalize-batch batch)]
    (or failure
        (check-limits batch)
        (check-current batch)
        (validate-ops batch)
        (->Preview (mapv describe-op (:ops batch)) (summary batch) (:root batch)))))

(defn prepare-plan
  "Final dry-run under the write gate.  Returns a `Prepared` value or an
  ordinary rejection result.  Apply accepts only `Prepared`, and a prepared
  value is bound to the write-gate permit that produced it."
  ([batch] (prepare-plan batch {}))
  ([batch {:keys [gate-token]}]
   (let [{:keys [batch failure]} (normalize-batch batch)]
     (or failure
         (check-limits batch)
         (check-current batch)
         (validate-ops batch)
         (->Prepared (vec (:ops batch)) (summary batch) (:root batch) gate-token)))))

(defn preview? [x] (instance? Preview x))
(defn prepared? [x] (instance? Prepared x))

;; ---------------------------------------------------------------------------
;; Stateful session used by a program executor

(defrecord BatchSession [state])

(defn session
  "Create a single-threaded batch session.  Each tool call mutates the
  session only when it succeeds; a throw leaves the previous view intact."
  ([root] (session root {}))
  ([root opts] (->BatchSession (atom (new-batch root opts)))))

(defn snapshot [s] @(:state s))

(defn cat [s path]
  (let [[b r] (read-path @(:state s) path)]
    (reset! (:state s) b)
    (:text r)))

(defn stat [s path]
  (let [[b r] (stat-path @(:state s) path)]
    (reset! (:state s) b)
    r))

(defn ls [s path]
  (let [[b r] (list-path @(:state s) path)]
    (reset! (:state s) b)
    r))

(defn write [s path content]
  (let [b (stage-write @(:state s) path content)]
    (reset! (:state s) b)
    {:status :STAGED :path (stamp/path-string (:root b) path)}))

(defn mkdir [s path]
  (let [b (stage-mkdir @(:state s) path)]
    (reset! (:state s) b)
    {:status :STAGED :path (stamp/path-string (:root b) path)}))

(defn rm [s path]
  (let [b (stage-rm @(:state s) path)]
    (reset! (:state s) b)
    {:status :STAGED :path (stamp/path-string (:root b) path)}))

(defn mv
  ([s source target] (mv s source target {}))
  ([s source target opts]
   (let [b (stage-mv @(:state s) source target opts)]
     (reset! (:state s) b)
     {:status :STAGED
      :source (stamp/path-string (:root b) source)
      :target (stamp/path-string (:root b) target)})))
