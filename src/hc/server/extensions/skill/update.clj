(ns hc.server.extensions.skill.update
  "Three-way skill updates.

  The installed skill records the digest of every file from the last accepted
  upstream version.  The current files are the local side.  A new upstream
  snapshot is the third side.  Digests are enough to classify a path; the
  actual bytes are needed only for the files the plan writes.

  Conflicts never overwrite the local version by default.  The caller receives
  the per-path classification so the UI can show upstream, local and base
  before asking the user to choose."
  (:require [clojure.set :as set]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.skill.store :as store]))

(defn- digest-of
  [value]
  (cond
    (nil? value) nil
    (map? value) (:sha256 value)
    (bytes? value) (common/digest-id value)
    (string? value) value
    :else
    (error/fail! :SKILL_UPDATE_INVALID
                 "file side must be bytes, a digest or metadata"
                 {:class (some-> value class .getName)})))

(defn- digests
  [files]
  (into {} (map (fn [[path value]] [path (digest-of value)])) (or files {})))

(defn- status-for
  [base local upstream]
  (cond
    (and (nil? base) (nil? local) (nil? upstream)) :absent
    (and (nil? base) (nil? local)) :upstream-added
    (and (nil? base) (nil? upstream)) :local-only
    (and (nil? base) (= local upstream)) :same-add
    (and (nil? base)) :conflict

    (and (nil? local) (nil? upstream)) :absent
    (and (nil? local) (= upstream base)) :local-deleted
    (and (nil? local)) :conflict

    (and (nil? upstream) (= local base)) :upstream-deleted
    (and (nil? upstream)) :conflict

    (= local upstream) :unchanged
    (= local base) :upstream-changed
    (= upstream base) :local-changed
    :else :conflict))

(defn three-way
  "Classify every path across base, local and upstream sides.

  Each side is a map of path to bytes, digest or file metadata.  Returns
  `{:paths [...] :writes {path bytes} :deletes #{path} :conflicts #{path}
  :summary {...}}`.  `writes` and `deletes` are the safe plan under the
  default `:keep-local` strategy."
  [base local upstream]
  (let [base-d (digests base)
        local-d (digests local)
        upstream-d (digests upstream)
        paths (sort (set/union (set (keys base-d))
                               (set (keys local-d))
                               (set (keys upstream-d))))
        rows (mapv (fn [path]
                     (let [status (status-for (get base-d path)
                                              (get local-d path)
                                              (get upstream-d path))]
                       {:path path
                        :status status
                        :base (get base-d path)
                        :local (get local-d path)
                        :upstream (get upstream-d path)}))
                   paths)
        by-status (group-by :status rows)
        conflicts (set (map :path (get by-status :conflict)))
        writes (into {}
                     (keep (fn [{:keys [path status]}]
                             (when (#{:upstream-added :upstream-changed} status)
                               [path (get (or upstream {}) path)])))
                     rows)
        deletes (set (map :path (get by-status :upstream-deleted)))]
    {:paths rows
     :writes writes
     :deletes deletes
     :conflicts conflicts
     :summary (into {}
                    (map (fn [[status rows]] [status (count rows)]))
                    by-status)}))

(defn- local-modified?
  [diff]
  (boolean
   (some #(#{:local-changed :local-only :local-deleted :conflict} (:status %))
         (:paths diff))))

(defn- metadata-from-files
  [files]
  (into {}
        (map (fn [[path bytes]] [path (store/file-metadata path bytes)]))
        files))

(defn apply-update!
  "Apply an upstream snapshot to an installed skill.

  `opts` supports `:root` (defaults to the skill's managed root),
  `:local-files` (for tests or an already-read snapshot), and `:strategy`
  (`:keep-local` default, or `:take-upstream`).  The skill is never enabled by
  this operation."
  [catalog id upstream-files opts]
  (let [skill (get-in catalog [:skills id])
        strategy (or (:strategy opts) :keep-local)]
    (cond
      (nil? skill)
      {:ok? false
       :catalog catalog
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}}

      (not (#{:keep-local :take-upstream} strategy))
      {:ok? false
       :catalog catalog
       :error {:code :SKILL_UPDATE_STRATEGY_INVALID
               :message "update strategy must be :keep-local or :take-upstream"
               :details {:strategy strategy}}}

      :else
      (let [root (or (:root opts) (:storage-root skill))
            local-result (if (contains? opts :local-files)
                           {:ok? true :files (:local-files opts)}
                           (store/read-files root id))]
        (if-not (:ok? local-result)
          {:ok? false :catalog catalog :error (:error local-result)}
          (let [local-files (:files local-result)
                diff (three-way (:files skill) local-files upstream-files)
                writes (if (= :take-upstream strategy)
                         (into {}
                               (keep (fn [{:keys [path status]}]
                                       (when (and (#{:upstream-added
                                                     :upstream-changed
                                                     :conflict} status)
                                                  (some? (get upstream-files path)))
                                         [path (get upstream-files path)])))
                               (:paths diff))
                         (:writes diff))
                deletes (if (= :take-upstream strategy)
                          (into (:deletes diff)
                                (keep (fn [{:keys [path status]}]
                                        (when (and (= :conflict status)
                                                   (nil? (get upstream-files path)))
                                          path)))
                                (:paths diff))
                          (:deletes diff))
                applied (store/replace-files! root id writes deletes)]
            (if-not (:ok? applied)
              {:ok? false :catalog catalog :error (:error applied)}
              (let [result-files (-> local-files
                                     (merge writes)
                                     (as-> files (apply dissoc files deletes)))
                    now (common/now-ms)
                    updated (cond-> (assoc skill
                                           :files (metadata-from-files result-files)
                                           :upstream {:files (metadata-from-files upstream-files)
                                                      :updated-at now}
                                           :local-modified? (boolean
                                                             (and (not= :take-upstream strategy)
                                                                  (local-modified? diff)))
                                           :review-required? (boolean (seq (:conflicts diff)))
                                           :updated-at now)
                              (contains? opts :description)
                              (assoc :description (:description opts))
                              (contains? opts :version)
                              (assoc :version (:version opts)))]
                {:ok? true
                 :catalog (assoc-in catalog [:skills id] updated)
                 :skill updated
                 :diff diff
                 :applied applied}))))))))
