(ns hc.server.extensions.skill.preview
  "Read a skill from text, a file map, a directory, an archive or a link.

  Preview is complete before installation: the caller sees the entry, every
  companion path, total size, warnings and conditions.  Nothing in a skill is
  executed here.  A link that resolves to an HTML project page is refused with
  a request for a concrete candidate; it is never treated as a whole-project
  download."
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.safe-fetch :as safe-fetch]
            [hc.server.extensions.skill.archive :as archive])
  (:import (java.net URI)
           (java.nio.file Files FileVisitOption LinkOption Path)))

(def max-entry-bytes (* 2 1024 1024))
(def max-total-bytes (* 20 1024 1024))
(def max-files 200)

(defn- split-frontmatter
  "Return [frontmatter body].  Only the small `key: value` subset used by skill
  manifests is understood; malformed or nested values remain ordinary text."
  [^String text]
  (if (or (str/starts-with? text "---\n")
          (str/starts-with? text "---\r\n"))
    (let [lines (str/split-lines text)
          rest-lines (rest lines)
          closing (first (keep-indexed (fn [i line]
                                         (when (= (str/trim line) "---") i))
                                       rest-lines))]
      (if closing
        [(str/join "\n" (take closing rest-lines))
         (str/join "\n" (drop (inc closing) rest-lines))]
        [nil text]))
    [nil text]))

(defn- unquote-value
  [value]
  (let [value (str/trim value)]
    (if (and (>= (count value) 2)
             (or (and (= (first value) \") (= (last value) \"))
                 (and (= (first value) \') (= (last value) \'))))
      (subs value 1 (dec (count value)))
      value)))

(defn- parse-frontmatter
  [text]
  (let [[front body] (split-frontmatter text)]
    (if (nil? front)
      {:frontmatter {} :body text}
      (let [meta (reduce (fn [m line]
                           (let [line (str/trim line)]
                             (if (or (str/blank? line)
                                     (str/starts-with? line "#"))
                               m
                               (let [idx (str/index-of line ":")]
                                 (if idx
                                   (let [k (str/trim (subs line 0 idx))
                                         v (unquote-value (subs line (inc idx)))]
                                     (if (str/blank? k) m (assoc m (keyword k) v)))
                                   m)))))
                         {}
                         (str/split-lines front))]
        {:frontmatter meta :body body}))))

(defn- first-heading
  [body]
  (some (fn [line]
          (when (re-find #"^#{1,6}\s+\S" line)
            (str/trim (str/replace line #"^#{1,6}\s+" ""))))
        (str/split-lines (or body ""))))

(defn- first-paragraph
  [body]
  (some (fn [line]
          (let [line (str/trim line)]
            (when (and (not (str/blank? line))
                       (not (str/starts-with? line "#")))
              line)))
        (str/split-lines (or body ""))))

(defn- parse-bool
  [value]
  (case (some-> value str str/trim str/lower-case)
    ("true" "yes" "1") true
    ("false" "no" "0") false
    nil))

(defn- parse-list
  [value]
  (cond
    (nil? value) []
    (vector? value) (mapv str value)
    (string? value)
    (let [value (str/trim value)
          value (if (and (str/starts-with? value "[")
                         (str/ends-with? value "]"))
                  (subs value 1 (dec (count value)))
                  value)]
      (->> (str/split value #",")
           (map str/trim)
           (remove str/blank?)
           vec))
    :else []))

(defn- conditions
  [meta]
  (let [requires (when (map? (:requires meta)) (:requires meta))]
    {:requires-workspace?
     (or (parse-bool (or (:requires-workspace meta) (:requires-workspace? meta)))
         (parse-bool (:workspace requires))
         false)
     :requires-tools
     (vec (distinct (concat (parse-list (:requires-tools meta))
                            (parse-list (:tools requires)))))
     :requires-network?
     (or (parse-bool (or (:requires-network meta) (:requires-network? meta)))
         (parse-bool (:network requires))
         false)
     :requires-user-input
     (vec (distinct (concat (parse-list (:requires-user-input meta))
                            (parse-list (:user-input requires)))))}))

(defn- frontmatter-name
  [meta]
  (common/blank->nil (:name meta)))

(defn- frontmatter-description
  [meta]
  (common/blank->nil (:description meta)))

(defn- metadata
  [text]
  (let [{:keys [frontmatter body]} (parse-frontmatter text)]
    {:frontmatter frontmatter
     :name (or (frontmatter-name frontmatter) (first-heading body))
     :description (or (frontmatter-description frontmatter)
                      (first-paragraph body))
     :version (common/blank->nil (:version frontmatter))
     :conditions (conditions frontmatter)
     :body body}))

(defn- normalize-files
  "Normalize a path->bytes map, reject duplicates and apply the size limits."
  [files]
  (when-not (map? files)
    (error/fail! :SKILL_FILES_INVALID "skill files must be a map"
                 {:class (some-> files class .getName)}))
  (when (> (count files) max-files)
    (error/fail! :SKILL_TOO_MANY_FILES
                 "skill contains too many files"
                 {:limit max-files :actual (count files)}))
  (let [result (reduce-kv
                (fn [acc path content]
                  (let [path (common/normalize-relative-path path)
                        bytes (cond
                                (bytes? content) content
                                (string? content) (common/utf8-bytes content)
                                :else
                                (error/fail! :SKILL_FILE_INVALID
                                             "skill file content must be text or bytes"
                                             {:path path
                                              :class (some-> content class .getName)}))]
                    (when (contains? (:seen acc) (str/lower-case path))
                      (error/fail! :SKILL_DUPLICATE_PATH
                                   "skill contains duplicate paths"
                                   {:path path}))
                    (when (> (alength ^bytes bytes) max-entry-bytes)
                      (error/fail! :SKILL_FILE_TOO_LARGE
                                   "skill file exceeds the size limit"
                                   {:path path :limit max-entry-bytes}))
                    (let [total (+ (:total acc) (alength ^bytes bytes))]
                      (when (> total max-total-bytes)
                        (error/fail! :SKILL_TOO_LARGE
                                     "skill exceeds the total size limit"
                                     {:limit max-total-bytes}))
                      (-> acc
                          (assoc-in [:files path] bytes)
                          (update :total + (alength ^bytes bytes))
                          (update :seen conj (str/lower-case path))))))
                {:files {} :total 0 :seen #{}}
                files)]
    (:files result)))

(defn- strip-common-root
  "If every path is nested under one directory, strip it.  The returned map
  always has SKILL.md at the root."
  [files]
  (let [paths (keys files)
        segments (map #(str/split % #"/") paths)
        roots (set (map first segments))
        nested? (every? #(> (count %) 1) segments)]
    (if (and (= 1 (count roots)) nested?)
      (let [root (first roots)
            prefix (str root "/")]
        (into {}
              (map (fn [path] [(subs path (count prefix)) (get files path)]))
              paths))
      files)))

(defn- ensure-entry
  [files]
  (let [files (strip-common-root files)
        entries (filter #(or (= % "SKILL.md")
                             (str/ends-with? % "/SKILL.md"))
                        (keys files))]
    (cond
      (empty? entries)
      (error/fail! :SKILL_ENTRY_MISSING
                   "skill must contain SKILL.md"
                   {:paths (vec (sort (keys files)))})

      (> (count entries) 1)
      (error/fail! :SKILL_DUPLICATE_ENTRY
                   "skill contains more than one SKILL.md"
                   {:entries (vec (sort entries))})

      (not= "SKILL.md" (first entries))
      (error/fail! :SKILL_ENTRY_NESTED
                   "SKILL.md must be at the skill root"
                   {:entry (first entries)})

      :else files)))

(defn- read-skill-text
  [files]
  (let [entry (get files "SKILL.md")
        text (common/bytes->utf8 entry)]
    (when-not text
      (error/fail! :SKILL_ENCODING_INVALID
                   "SKILL.md must be valid UTF-8"
                   {:path "SKILL.md"}))
    text))

(defn- candidate
  [{:keys [files text name description source warnings]}]
  (let [files (ensure-entry (normalize-files files))
        text (or text (read-skill-text files))
        meta (metadata text)
        name (common/blank->nil name)
        description (common/blank->nil description)
        name (or name (:name meta))
        description (or description (:description meta))
        warnings (vec (concat warnings
                              (when-not name [{:code :SKILL_NAME_MISSING
                                               :message "a name is required before installation"}])))]
    {:ok? true
     :candidate {:name name
                 :description description
                 :version (:version meta)
                 :entry "SKILL.md"
                 :files files
                 :conditions (:conditions meta)
                 :source (merge {:kind :unknown} source)
                 :warnings warnings
                 :total-bytes (reduce + (map alength (vals files)))
                 :file-count (count files)}}))

(defn- text-source
  [{:keys [text name description source] :as input}]
  (when-not (string? text)
    (error/fail! :SKILL_TEXT_INVALID "skill text must be a string"
                 {:class (some-> text class .getName)}))
  (when (str/blank? text)
    (error/fail! :SKILL_TEXT_EMPTY "skill text must not be blank" {}))
  (when (> (alength (common/utf8-bytes text)) max-entry-bytes)
    (error/fail! :SKILL_FILE_TOO_LARGE "skill text exceeds the size limit"
                 {:limit max-entry-bytes}))
  (candidate {:files {"SKILL.md" (common/utf8-bytes text)}
              :text text
              :name name
              :description description
              :source (merge {:kind :text} source)}))

(defn- files-source
  [{:keys [files text name description source]}]
  (let [files (normalize-files files)
        files (if (contains? files "SKILL.md")
                files
                (if text
                  (assoc files "SKILL.md" (common/utf8-bytes text))
                  files))]
    (candidate {:files files
                :name name
                :description description
                :source (merge {:kind :files} source)})))

(defn- archive-source
  [{:keys [bytes name description source]}]
  (let [{:keys [files warnings]} (archive/unpack bytes)]
    (candidate {:files files
                :name name
                :description description
                :source (merge {:kind :archive} source)
                :warnings warnings})))

(defn- directory-files
  [directory]
  (let [root (Path/of (str directory) (make-array String 0))]
    (when-not (Files/isDirectory root (make-array LinkOption 0))
      (error/fail! :SKILL_DIRECTORY_INVALID
                   "skill directory does not exist or is not a directory"
                   {:path (str directory)}))
    (with-open [stream (Files/walk root (make-array FileVisitOption 0))]
      (reduce
       (fn [{:keys [files total] :as acc} ^Path path]
         (cond
           (Files/isSymbolicLink path)
           (error/fail! :SKILL_SYMLINK
                        "skill directory contains a symbolic link"
                        {:path (str path)})

           (Files/isRegularFile path (make-array LinkOption 0))
           (let [relative (str/replace (str (.relativize root path)) "\\" "/")
                 bytes (common/read-limited
                        (Files/newInputStream path (make-array java.nio.file.OpenOption 0))
                        max-entry-bytes)
                 total (+ total (alength ^bytes bytes))]
             (when (> total max-total-bytes)
               (error/fail! :SKILL_TOO_LARGE
                            "skill directory exceeds the total size limit"
                            {:limit max-total-bytes}))
             (when (> (inc (count files)) max-files)
               (error/fail! :SKILL_TOO_MANY_FILES
                            "skill directory contains too many files"
                            {:limit max-files}))
             (assoc acc :files (assoc files relative bytes) :total total))

           :else acc))
       {:files {} :total 0}
       (iterator-seq (.iterator stream))))))

(defn- directory-source
  [{:keys [directory name description source]}]
  (let [{:keys [files]} (directory-files directory)]
    (candidate {:files files
                :name name
                :description description
                :source (merge {:kind :directory :path (str directory)}
                               source)})))

(defn- uri-path
  [^String url]
  (try
    (.getPath (URI/create url))
    (catch Exception _ nil)))

(defn- default-fetch
  [url timeout-ms allow-private?]
  (let [result (safe-fetch/get! url {:timeout-ms timeout-ms
                                     :limit max-total-bytes
                                     :allow-private? allow-private?})]
    (if (:ok? result)
      {:ok? true
       :body (:body result)
       :content-type (:content-type result)
       :final-url url}
      result)))

(defn- url-source
  [{:keys [url name description source fetcher timeout-ms allow-private?]
    :or {timeout-ms 10000}}]
  (when-not (string? url)
    (error/fail! :SKILL_SOURCE_INVALID "skill URL must be a string"
                 {:class (some-> url class .getName)}))
  (let [fetched (if fetcher
                  (fetcher url timeout-ms)
                  (default-fetch url timeout-ms (boolean allow-private?)))]
    (when-not (:ok? fetched)
      (throw (ex-info (:message (:error fetched))
                      (merge {:error (:code (:error fetched))}
                             (:details (:error fetched))))))
    (let [body (:body fetched)
          content-type (some-> (:content-type fetched) str str/lower-case)
          path (some-> (uri-path url) str str/lower-case)
          zip? (or (and content-type (str/includes? content-type "zip"))
                   (and path (str/ends-with? path ".zip")))
          text? (or (and content-type (or (str/starts-with? content-type "text/")
                                          (str/includes? content-type "markdown")))
                    (and path (or (str/ends-with? path ".md")
                                  (str/ends-with? path ".txt"))))]
      (cond
        zip? (archive-source {:bytes body
                              :name name
                              :description description
                              :source (merge {:kind :url
                                              :uri url
                                              :final-url (:final-url fetched)
                                              :fetched-at (common/now-ms)} source)})
        text? (text-source {:text (or (common/bytes->utf8 body)
                                      (error/fail! :SKILL_ENCODING_INVALID
                                                   "linked skill text must be valid UTF-8"
                                                   {:url url}))
                            :name name
                            :description description
                            :source (merge {:kind :url
                                            :uri url
                                            :final-url (:final-url fetched)
                                            :fetched-at (common/now-ms)} source)})
        :else
        (error/fail! :SKILL_SOURCE_NEEDS_SELECTION
                     "link is not a direct skill file; choose a candidate file or directory"
                     {:url url :content-type content-type})))))

(defn preview
  "Preview one skill source.  Returns `{:ok? true :candidate ...}` or a
  refusal.  The candidate's files are still in memory; installation is a
  separate, explicit operation."
  [source]
  (try
    (let [kind (or (:kind source)
                   (cond
                     (:text source) :text
                     (:files source) :files
                     (:directory source) :directory
                     (:bytes source) :archive
                     (:url source) :url
                     :else nil))]
      (case kind
        :text (text-source source)
        :files (files-source source)
        :directory (directory-source source)
        :archive (archive-source source)
        :url (url-source source)
        (error/fail! :SKILL_SOURCE_INVALID
                     "source must be text, files, directory, archive or url"
                     {:kind kind})))
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        (throw t)))))

(defn preview-candidates
  "Preview every candidate in a source.

  Only an archive can yield more than one candidate.  A single-file source
  returns a one-element vector so the caller can use one shape for the
  selection page.  `preview` remains the unambiguous single-install path."
  [source]
  (try
    (if (= :archive (or (:kind source)
                        (when (:bytes source) :archive)))
      (let [{:keys [candidates]} (archive/unpack-candidates (:bytes source))]
        {:ok? true
         :candidates (mapv (fn [candidate-files]
                             (:candidate
                              (candidate {:files (:files candidate-files)
                                          :name (:name source)
                                          :description (:description source)
                                          :source (merge {:kind :archive}
                                                         (:source source))
                                          :warnings (:warnings candidate-files)})))
                           candidates)})
      (let [result (preview source)]
        (if (:ok? result)
          {:ok? true :candidates [(:candidate result)]}
          result)))
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        (throw t)))))
