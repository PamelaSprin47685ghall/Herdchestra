(ns hc.core.steward-sphinx.evidence
  "Git and observation evidence for the Standing Sphinx.

  Evidence is immutable data.  A Git commit is identified by
  `git:<commit>`; every other evidence kind must carry a non-blank
  `:evidence-id`.  Re-submitting the same identity with different content is
  a conflict, not new information.

  This namespace never runs `git` and never touches the filesystem.  A server
  adapter turns a Git observation into a map and submits it here."
  (:require [clojure.string :as str]
            [hc.core.steward-sphinx.util :as util]))

(def root-question
  "The permanent root question of the Standing Sphinx."
  "How can this be better?")

(def evidence-kinds
  "Evidence kinds understood by the Sphinx core."
  #{:git-commit :operator-intent :observation})

(def repo-wide-paths
  "Path values that mean the whole repository."
  #{"" "." "/"})

(defn- invalid
  [message details]
  (util/fail :INVALID_EVIDENCE message details))

(defn normalize-path
  "Normalize a repository-relative path for prefix comparison.

  Backslashes become slashes, leading `./` and `/` are removed, and trailing
  slashes are removed.  The repository-wide spellings `\"\"`, `\".\"` and
  `\"/\"` are preserved."
  [path]
  (let [path (str path)
        path (str/replace path "\\" "/")
        path (str/replace path #"^(\./)+" "")
        path (str/replace path #"^/+" "")
        path (str/replace path #"/+$" "")]
    path))

(defn repo-wide-path?
  [path]
  (contains? repo-wide-paths (normalize-path path)))

(defn normalize-paths
  "Normalize, de-duplicate and sort a collection of repository paths."
  [paths]
  (util/sorted-distinct (map normalize-path paths)))

(defn path-prefix?
  "True when CHANGED is WATCH itself or lies below the WATCH directory."
  [watch changed]
  (let [watch (normalize-path watch)
        changed (normalize-path changed)]
    (or (repo-wide-path? watch)
        (repo-wide-path? changed)
        (= watch changed)
        (str/starts-with? changed (str watch "/")))))

(defn affects-assumption?
  "True when EVIDENCE touched a path watched by ASSUMPTION.

  An assumption with no watch paths is repository-wide.  Evidence with no
  changed paths is not a content change and affects nothing."
  [assumption evidence]
  (let [watches (normalize-paths (:watch-paths assumption))
        changed (normalize-paths (:changed-paths evidence))]
    (cond
      (empty? changed) false
      (empty? watches) true
      :else (boolean (some (fn [watch]
                             (some #(path-prefix? watch %) changed))
                           watches)))))

(defn- optional-string
  [value]
  (or (nil? value) (string? value)))

(defn- normalize-git
  [raw]
  (let [commit (:commit raw)
        parents (:parents raw [])
        changed (:changed-paths raw [])
        subject (:subject raw)
        observed-at (:observed-at raw)
        tree (:tree raw)
        author (:author raw)
        committed-at (:committed-at raw)
        insertions (:insertions raw)
        deletions (:deletions raw)]
    (cond
      (not (util/non-blank-string? commit))
      (invalid "git evidence needs a non-blank :commit" {:commit commit})

      (not (sequential? parents))
      (invalid ":parents must be sequential" {:parents parents})

      (not (every? util/non-blank-string? parents))
      (invalid ":parents must contain non-blank strings" {:parents parents})

      (not (sequential? changed))
      (invalid ":changed-paths must be sequential" {:changed-paths changed})

      (not (every? util/non-blank-string? changed))
      (invalid ":changed-paths must contain non-blank strings"
               {:changed-paths changed})

      (not (optional-string subject))
      (invalid ":subject must be a string when present" {:subject subject})

      (not (optional-string tree))
      (invalid ":tree must be a string when present" {:tree tree})

      (not (optional-string author))
      (invalid ":author must be a string when present" {:author author})

      (not (optional-string committed-at))
      (invalid ":committed-at must be a string when present"
               {:committed-at committed-at})

      (not (or (nil? insertions) (util/non-negative-integer? insertions)))
      (invalid ":insertions must be a non-negative integer when present"
               {:insertions insertions})

      (not (or (nil? deletions) (util/non-negative-integer? deletions)))
      (invalid ":deletions must be a non-negative integer when present"
               {:deletions deletions})

      :else
      (let [evidence-id (str "git:" commit)
            supplied-id (:evidence-id raw)]
        (if (and (some? supplied-id) (not= supplied-id evidence-id))
          (invalid ":evidence-id does not match the commit"
                   {:expected evidence-id :actual supplied-id})
          {:ok? true
           :value
           (cond-> {:evidence-id evidence-id
                    :kind :git-commit
                    :source :git
                    :commit commit
                    :parents (vec parents)
                    :changed-paths (normalize-paths changed)
                    :subject subject}
             (some? observed-at) (assoc :observed-at observed-at)
             (some? tree) (assoc :tree tree)
             (some? author) (assoc :author author)
             (some? committed-at) (assoc :committed-at committed-at)
             (some? insertions) (assoc :insertions insertions)
             (some? deletions) (assoc :deletions deletions))})))))

(defn- normalize-other
  [raw]
  (let [kind (:kind raw)
        evidence-id (:evidence-id raw)
        summary (or (:summary raw) (:text raw))
        changed (:changed-paths raw [])
        source (:source raw)
        observed-at (:observed-at raw)
        revision (:revision raw)]
    (cond
      (not (util/non-blank-string? evidence-id))
      (invalid "non-git evidence needs a non-blank :evidence-id"
               {:evidence-id evidence-id})

      (not (util/non-blank-string? summary))
      (invalid "evidence needs a non-blank :summary or :text"
               {:summary summary})

      (not (sequential? changed))
      (invalid ":changed-paths must be sequential" {:changed-paths changed})

      (not (every? util/non-blank-string? changed))
      (invalid ":changed-paths must contain non-blank strings"
               {:changed-paths changed})

      (not (or (nil? source) (keyword? source) (string? source)))
      (invalid ":source must be a keyword or string when present"
               {:source source})

      (not (or (nil? revision) (util/non-negative-integer? revision)))
      (invalid ":revision must be a non-negative integer when present"
               {:revision revision})

      :else
      {:ok? true
       :value
       (cond-> {:evidence-id evidence-id
                :kind kind
                :summary summary
                :changed-paths (normalize-paths changed)}
         (some? source) (assoc :source source)
         (some? observed-at) (assoc :observed-at observed-at)
         (some? revision) (assoc :revision revision))})))

(defn normalize-evidence
  "Validate and normalize one evidence map.

  Returns `{:ok? true :value evidence}` or `{:ok? false :error ...}`.
  Git commits get the identity `git:<commit>`; other kinds keep the caller's
  `:evidence-id`."
  [raw]
  (cond
    (not (map? raw))
    (invalid "evidence must be a map" {:evidence raw})

    (not (contains? evidence-kinds (:kind raw)))
    (invalid "unknown evidence kind"
             {:kind (:kind raw) :supported evidence-kinds})

    (= :git-commit (:kind raw))
    (normalize-git raw)

    :else
    (normalize-other raw)))

(defn evidence-summary
  "The bounded view of one evidence record used in the Current Best Answer."
  [evidence]
  (into {}
        (remove (comp nil? val))
        (select-keys evidence
                     [:evidence-id :kind :seq :commit :subject :summary
                      :source :changed-paths])))
