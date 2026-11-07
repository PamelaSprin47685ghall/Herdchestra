(ns hc.server.extensions.skill
  "Installed skills: preview, install, activation, binding and updates.

  A skill is an instruction document plus inert companion files.  Installation
  never enables it and never binds it.  A global disable is final: no assistant
  binding and no session override can make the skill active.  Nothing here
  executes a skill script; the files are data for the model and the execution
  boundary."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.skill.preview :as preview]
            [hc.server.extensions.skill.store :as store]
            [hc.server.extensions.skill.update :as update]))

(defn preview
  "Preview a skill source without touching the catalog or the filesystem."
  [source]
  (preview/preview source))

(defn preview-candidates
  "Preview every skill candidate in a source (multiple only for archives)."
  [source]
  (preview/preview-candidates source))

(defn- install-record
  [skill-id name description candidate installed now storage-root]
  {:id skill-id
   :name name
   :description (or description (:description candidate) "")
   :version (:version candidate)
   :entry "SKILL.md"
   :files (:files installed)
   :root (:root installed)
   :storage-root (str storage-root)
   :source (:source candidate)
   :conditions (:conditions candidate)
   :enabled? false
   :bindings #{}
   :local-modified? false
   :review-required? false
   :installed-at now
   :updated-at now
   :content-digest (get-in installed [:files "SKILL.md" :sha256])})

(defn install!
  "Install a previewed candidate under the managed skill root.

  The name is made unique against the current catalog; identity remains the
  skill ID, so two skills with similar names cannot share state.  The new
  record is always disabled and unbound."
  ([catalog root candidate]
   (install! catalog root candidate {}))
  ([catalog root candidate opts]
   (let [catalog (catalog/ensure catalog)
         name (or (common/blank->nil (:name opts))
                  (common/blank->nil (:name candidate)))
         description (or (common/blank->nil (:description opts))
                         (:description candidate))]
     (cond
       (nil? candidate)
       {:ok? false
        :catalog catalog
        :error {:code :SKILL_CANDIDATE_REQUIRED
                :message "a previewed skill candidate is required"}}

       (nil? name)
       {:ok? false
        :catalog catalog
        :error {:code :SKILL_NAME_REQUIRED
                :message "a skill name is required before installation"}}

       :else
       (try
         (let [name (common/safe-name name)
               unique-name (catalog/unique-skill-name catalog name)
               skill-id (or (:id opts) (common/new-id :skill))]
           (when (catalog/skill catalog skill-id)
             (error/fail! :SKILL_ID_TAKEN
                          "skill id is already installed"
                          {:id skill-id}))
           (let [installed (store/install! root skill-id (:files candidate))]
             (if-not (:ok? installed)
               {:ok? false :catalog catalog :error (:error installed)}
               (let [now (common/now-ms)
                     skill (install-record skill-id unique-name description
                                           candidate installed now root)]
                 {:ok? true
                  :catalog (catalog/put-skill catalog skill)
                  :skill skill
                  :renamed? (not= name unique-name)}))))
         (catch clojure.lang.ExceptionInfo t
           (if (error/expected? t)
             {:ok? false :catalog catalog :error (error/error-map t)}
             (throw t))))))))

(defn install-source!
  "Preview and install in one call.  A preview refusal is returned unchanged."
  ([catalog root source]
   (install-source! catalog root source {}))
  ([catalog root source opts]
   (let [previewed (preview/preview source)]
     (if-not (:ok? previewed)
       {:ok? false :catalog (catalog/ensure catalog) :error (:error previewed)}
       (install! catalog root (:candidate previewed) opts)))))

(defn set-enabled!
  "Set the global enable flag.  Enabling does not bind the skill to anything."
  [catalog id enabled?]
  (let [catalog (catalog/ensure catalog)]
    (if-let [skill (catalog/skill catalog id)]
      {:ok? true
       :catalog (catalog/put-skill catalog (assoc skill :enabled? (boolean enabled?)))}
      {:ok? false
       :catalog catalog
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}})))

(defn bind!
  "Allow an assistant to select a skill.  Binding is not enabling."
  [catalog id assistant-id]
  (let [catalog (catalog/ensure catalog)]
    (if-let [skill (catalog/skill catalog id)]
      (if (str/blank? (str assistant-id))
        {:ok? false
         :catalog catalog
         :error {:code :SKILL_ASSISTANT_REQUIRED
                 :message "assistant id is required"}}
        {:ok? true
         :catalog (catalog/put-skill
                   catalog
                   (update skill :bindings (fnil conj #{}) assistant-id))})
      {:ok? false
       :catalog catalog
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}})))

(defn unbind!
  [catalog id assistant-id]
  (let [catalog (catalog/ensure catalog)]
    (if-let [skill (catalog/skill catalog id)]
      {:ok? true
       :catalog (catalog/put-skill
                 catalog
                 (update skill :bindings (fnil disj #{}) assistant-id))}
      {:ok? false
       :catalog catalog
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}})))

(defn delete!
  "Remove a skill record and its managed files.

  A deletion failure leaves the catalog untouched.  Files outside the managed
  skill root are never touched."
  [catalog root id]
  (let [catalog (catalog/ensure catalog)
        skill (catalog/skill catalog id)]
    (if-not skill
      {:ok? false
       :catalog catalog
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}}
      (let [deleted (store/delete! root id)]
        (if-not (:ok? deleted)
          {:ok? false :catalog catalog :error (:error deleted)}
          {:ok? true
           :catalog (catalog/remove-skill catalog id)
           :deleted (:deleted deleted)
           :root (:root deleted)})))))

(defn copy!
  "Create an independent local copy, disabled and unbound."
  [catalog root id opts]
  (let [catalog (catalog/ensure catalog)
        skill (catalog/skill catalog id)]
    (if-not skill
      {:ok? false
       :catalog catalog
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}}
      (let [files (store/read-files root id)]
        (if-not (:ok? files)
          {:ok? false :catalog catalog :error (:error files)}
          (let [candidate {:name (str (:name skill) " (local)")
                           :description (:description skill)
                           :files (:files files)
                           :entry "SKILL.md"
                           :conditions (:conditions skill)
                           :source (merge (:source skill)
                                          {:kind :copy :from id})
                           :warnings []}
                copy-opts (merge {:name (:name candidate)} opts)]
            (install! catalog root candidate copy-opts)))))))

(defn- condition-blockers
  [skill available]
  (let [conditions (:conditions skill)
        tools (set (map str (:requires-tools conditions)))
        available-tools (set (map str (:available-tools available)))
        missing-tools (vec (sort (set/difference tools available-tools)))]
    (cond-> []
      (and (:requires-workspace? conditions)
           (not (:workspace-bound? available)))
      (conj {:code :SKILL_REQUIRES_WORKSPACE
             :message "this skill needs a bound workspace"})

      (seq missing-tools)
      (conj {:code :SKILL_REQUIRES_TOOLS
             :message "this skill needs tools that are not available"
             :tools missing-tools})

      (and (:requires-network? conditions)
           (not (:network-available? available)))
      (conj {:code :SKILL_REQUIRES_NETWORK
             :message "this skill needs network access"})

      (and (seq (:requires-user-input conditions))
           (not (:user-input-provided? available)))
      (conj {:code :SKILL_REQUIRES_USER_INPUT
             :message "this skill needs user-provided information"
             :fields (:requires-user-input conditions)}))))

(defn- skill-summary
  [skill]
  {:id (:id skill)
   :name (:name skill)
   :description (:description skill)
   :entry (:entry skill)
   :root (:root skill)
   :files (:files skill)
   :conditions (:conditions skill)
   :source (:source skill)
   :version (:version skill)
   :local-modified? (:local-modified? skill)
   :review-required? (:review-required? skill)})

(defn resolve-skills
  "Resolve the skills visible to one assistant/session scope.

  `scope` is `{:assistant-id ... :session {:mode :follow|:custom
  :skills #{...}} :available {:workspace-bound? bool :available-tools [...]
  :network-available? bool :user-input-provided? bool}}`.  The result separates
  active skills from selected-but-blocked ones and from globally disabled
  ones.  No unselected skill is returned."
  [catalog scope]
  (let [catalog (catalog/ensure catalog)
        session (or (:session scope) {})
        mode (or (:mode session) :follow)
        selected (cond
                   (= :custom mode) (set (or (:skills session) #{}))
                   :else (set (keep (fn [[id skill]]
                                      (when (contains? (:bindings skill)
                                                       (:assistant-id scope))
                                        id))
                                    (:skills catalog))))
        available (or (:available scope) {})]
    (reduce (fn [acc id]
              (let [skill (catalog/skill catalog id)]
                (cond
                  (nil? skill)
                  (update acc :excluded conj
                          {:id id :reason :not-installed})

                  (not (:enabled? skill))
                  (update acc :excluded conj
                          {:id id :name (:name skill) :reason :globally-disabled})

                  :else
                  (let [blockers (condition-blockers skill available)]
                    (if (seq blockers)
                      (update acc :blocked conj
                              (assoc (skill-summary skill)
                                     :blockers blockers))
                      (update acc :active conj (skill-summary skill)))))))
            {:active [] :blocked [] :excluded []}
            (sort selected))))

(defn compare-update
  "Compare a new upstream preview against the installed skill without writing."
  [catalog id candidate]
  (let [catalog (catalog/ensure catalog)
        skill (catalog/skill catalog id)]
    (cond
      (nil? skill)
      {:ok? false
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}}

      (nil? candidate)
      {:ok? false
       :error {:code :SKILL_CANDIDATE_REQUIRED
               :message "a previewed candidate is required"}}

      :else
      (let [files (store/read-files (:storage-root skill) id)]
        (if-not (:ok? files)
          {:ok? false :error (:error files)}
          {:ok? true
           :diff (update/three-way (:files skill) (:files files) (:files candidate))
           :skill skill
           :candidate candidate})))))

(defn apply-update!
  "Apply a previewed upstream candidate.  Conflicts keep the local file by
  default and mark the skill for review; the skill is not enabled."
  ([catalog id candidate]
   (apply-update! catalog id candidate {}))
  ([catalog id candidate opts]
   (if (nil? candidate)
     {:ok? false
      :catalog (catalog/ensure catalog)
      :error {:code :SKILL_CANDIDATE_REQUIRED
              :message "a previewed candidate is required"}}
     (update/apply-update! catalog id (:files candidate)
                           (merge {:root (:storage-root (catalog/skill catalog id))}
                                  (select-keys candidate [:description :version])
                                  opts)))))

(defn validate-candidate
  "Re-run the preview rules on a caller-supplied update candidate.

  The files are normalized, `SKILL.md` must exist exactly once, and the entry
  and total size limits apply.  Preview-only fields from the caller are kept
  after validation."
  [candidate]
  (if-not (map? candidate)
    {:ok? false
     :error {:code :SKILL_CANDIDATE_REQUIRED
             :message "a previewed candidate is required"}}
    (let [checked (preview/preview {:kind :files
                                    :files (:files candidate)
                                    :name (:name candidate)
                                    :description (:description candidate)})]
      (if-not (:ok? checked)
        checked
        {:ok? true
         :candidate (merge (:candidate checked)
                           (select-keys candidate
                                        [:version :source :conditions
                                         :description :name]))}))))

(defn public-candidate
  "A candidate projection with file metadata and the entry text, no raw
  companion bytes."
  [candidate]
  (let [files (:files candidate)
        entry-text (some-> (get files "SKILL.md") common/bytes->utf8)]
    (-> candidate
        (dissoc :files)
        (assoc :files (into {}
                            (map (fn [[path bytes]]
                                   [path (store/file-metadata path bytes)]))
                            files)
               :entry-text entry-text
               :file-count (count files)
               :total-bytes (reduce + 0 (map alength (vals files)))))))

(defn read-file
  "Read one UTF-8 file from an installed skill."
  [catalog id path]
  (let [catalog (catalog/ensure catalog)
        skill (catalog/skill catalog id)]
    (cond
      (nil? skill)
      {:ok? false
       :error {:code :SKILL_NOT_FOUND
               :message "skill is not installed"
               :details {:id id}}}

      (nil? (:storage-root skill))
      {:ok? false
       :error {:code :SKILL_FILES_MISSING
               :message "skill storage root is missing"
               :details {:id id}}}

      :else
      (store/read-file (:storage-root skill) id path))))

(defn active-context
  "Return the active skills' instruction text for a model request.

  Only resolved active skills are read.  A missing or unreadable entry is
  omitted rather than replaced with an invented description."
  [catalog scope]
  (let [catalog (catalog/ensure catalog)
        resolved (resolve-skills catalog scope)]
    (->> (:active resolved)
         (keep (fn [skill]
                 (let [file (read-file catalog (:id skill) "SKILL.md")]
                   (when (:ok? file)
                     {:id (:id skill)
                      :name (:name skill)
                      :text (:text file)}))))
         vec)))

(defn public-skill
  "A view with file metadata and no file bytes."
  [skill]
  (select-keys skill
               [:id :name :description :entry :root :storage-root :files :source :conditions
                :enabled? :bindings :version :local-modified? :review-required?
                :installed-at :updated-at :content-digest]))

(defn public-resolution
  "Project a resolved skill set without the full file maps.

  The extensions view already carries each installed skill's files once.
  Repeating them inside `:active`/`:blocked`/`:excluded` doubles the payload
  for no UI benefit; the selector only needs identity, name, version, and
  blockers.  `resolve-skills` keeps its full summaries for callers that read
  files."
  [resolved]
  (-> resolved
      (update :active #(mapv (fn [skill]
                               (select-keys skill [:id :name :version]))
                             %))
      (update :blocked #(mapv (fn [skill]
                                (select-keys skill
                                             [:id :name :version :blockers]))
                              %))
      (update :excluded #(mapv (fn [skill]
                                 (select-keys skill [:id :name :reason]))
                               %))))

(defn list-skills
  [catalog]
  (->> (vals (:skills (catalog/ensure catalog)))
       (sort-by :name)
       (mapv public-skill)))
