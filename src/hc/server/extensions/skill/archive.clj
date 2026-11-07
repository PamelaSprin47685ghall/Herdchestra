(ns hc.server.extensions.skill.archive
  "Safe in-memory reading of a skill archive.

  The archive is never extracted to the final skill directory before every
  entry has passed the path, size and duplicate checks.  A skill archive is
  data, not a program: entries are written as regular files later, and no
  entry is executed while previewing or installing."
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error])
  (:import (java.io ByteArrayInputStream)
           (java.util.zip ZipEntry ZipInputStream)))

(def max-entries 200)
(def max-entry-bytes (* 5 1024 1024))
(def max-total-bytes (* 20 1024 1024))

(defn- read-entry
  [^ZipInputStream zip]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop [total 0]
      (let [buffer (byte-array 8192)
            n (.read zip buffer)]
        (if (neg? n)
          (.toByteArray out)
          (let [next-total (+ total n)]
            (when (> next-total max-entry-bytes)
              (error/fail! :SKILL_ARCHIVE_TOO_LARGE
                           "archive entry exceeds the size limit"
                           {:limit max-entry-bytes}))
            (.write out buffer 0 n)
            (recur next-total)))))))

(defn- directory-entry?
  [^ZipEntry entry]
  (or (.isDirectory entry)
      (str/ends-with? (.getName entry) "/")))


(defn read-archive
  "Read every safe file entry from a ZIP archive.

  Paths, duplicate entries, entry count and total size are validated before
  anything is returned.  The result is a path->bytes map plus warnings; it
  does not choose a skill root."
  [^bytes archive-bytes]
  (when-not (bytes? archive-bytes)
    (error/fail! :SKILL_ARCHIVE_INVALID "archive must be a byte array"
                 {:class (some-> archive-bytes class .getName)}))
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. archive-bytes))]
    (loop [entries []
           seen #{}
           total 0
           entry-count 0]
      (let [^ZipEntry entry (.getNextEntry zip)]
        (if (nil? entry)
          {:files (into {} entries) :warnings []}
          (if (directory-entry? entry)
            (recur entries seen total entry-count)
            (let [name (.getName entry)]
              (when (>= entry-count max-entries)
                (error/fail! :SKILL_ARCHIVE_TOO_MANY_ENTRIES
                             "archive contains too many entries"
                             {:limit max-entries}))
              (let [path (common/normalize-relative-path name)]
                (when (contains? seen (str/lower-case path))
                  (error/fail! :SKILL_DUPLICATE_PATH
                               "archive contains duplicate paths"
                               {:path path}))
                (let [bytes (read-entry zip)
                      total (+ total (alength ^bytes bytes))]
                  (when (> total max-total-bytes)
                    (error/fail! :SKILL_ARCHIVE_TOO_LARGE
                                 "archive exceeds the total size limit"
                                 {:limit max-total-bytes}))
                  (recur (conj entries [path bytes])
                         (conj seen (str/lower-case path))
                         total
                         (inc entry-count)))))))))))

(defn- strip-root
  [files root]
  (let [prefix (str root "/")]
    (into {}
          (map (fn [path]
                 [(subs path (count prefix)) (get files path)]))
          (filter #(str/starts-with? % prefix) (keys files)))))

(defn unpack
  "Read a ZIP archive that contains exactly one skill.

  Exactly one `SKILL.md` entry must remain after the common root is stripped.
  Files outside that root are reported as warnings and ignored; they are not
  installed silently."
  [archive-bytes]
  (let [{:keys [files]} (read-archive archive-bytes)
        paths (keys files)
        skill-paths (filter #(or (= % "SKILL.md")
                                 (str/ends-with? % "/SKILL.md"))
                            paths)]
    (when (empty? skill-paths)
      (error/fail! :SKILL_ENTRY_MISSING
                   "archive must contain a SKILL.md entry"
                   {:entries (vec (sort paths))}))
    (when (> (count skill-paths) 1)
      (error/fail! :SKILL_DUPLICATE_ENTRY
                   "archive contains more than one SKILL.md entry"
                   {:entries (vec (sort skill-paths))}))
    (let [entry-path (first skill-paths)
          root (when (not= entry-path "SKILL.md")
                 (str/replace
                  (subs entry-path 0 (- (count entry-path) (count "SKILL.md")))
                  #"/$" ""))
          [files warnings]
          (if root
            (let [outside (remove #(str/starts-with? % (str root "/")) paths)
                  stripped (strip-root files root)]
              (if (contains? stripped "SKILL.md")
                [stripped
                 (when (seq outside)
                   [{:code :SKILL_ARCHIVE_OUTSIDE_ROOT
                     :message "files outside the skill root were ignored"
                     :paths (vec (sort outside))}])]
                (error/fail! :SKILL_ENTRY_MISSING
                             "archive skill root has no SKILL.md"
                             {:root root})))
            [files []])]
      {:files files
       :entry "SKILL.md"
       :warnings (vec warnings)})))

(defn unpack-candidates
  "Read every distinct skill root in one archive.

  A top-level `SKILL.md` and nested `SKILL.md` files produce separate
  candidates.  A candidate contains only files under its own root; files
  outside every candidate are reported as warnings."
  [archive-bytes]
  (let [{:keys [files]} (read-archive archive-bytes)
        paths (keys files)
        skill-paths (sort (filter #(or (= % "SKILL.md")
                                       (str/ends-with? % "/SKILL.md"))
                                  paths))]
    (when (empty? skill-paths)
      (error/fail! :SKILL_ENTRY_MISSING
                   "archive must contain a SKILL.md entry"
                   {:entries (vec (sort paths))}))
    (let [roots (set (map (fn [path]
                            (if (= path "SKILL.md")
                              ""
                              (str/replace
                               (subs path 0 (- (count path) (count "SKILL.md")))
                               #"/$" "")))
                          skill-paths))
          candidates (mapv (fn [root]
                             (let [candidate-files (if (str/blank? root)
                                                     files
                                                     (strip-root files root))
                                   outside (if (str/blank? root)
                                             []
                                             (remove #(str/starts-with?
                                                       % (str root "/"))
                                                     paths))]
                               {:files candidate-files
                                :entry "SKILL.md"
                                :root root
                                :warnings (when (seq outside)
                                            [{:code :SKILL_ARCHIVE_OUTSIDE_ROOT
                                              :message "files outside this skill root were ignored"
                                              :paths (vec (sort outside))}])}))
                           (sort roots))]
      {:candidates candidates
       :warnings []})))
