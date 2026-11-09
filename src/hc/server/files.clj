(ns hc.server.files
  "Read-only user workspace browser API.

  Every observation goes through `hc.core.workspace-gate`:

  * `stat` and `read` collect the exact `(mtime-sec, mtime-nsec, size)`
    stamp;
  * `list` uses the lock-free directory probe and then `stat` for each
    entry; a directory listing time is never presented as a subtree version;
  * `read` rejects a file whose stamp changes between the two metadata
    samples with `:FILE_CONFLICT` instead of returning an unstable text.

  The browser path space is relative to one workspace root.  Absolute paths,
  `..` segments, NUL bytes and symlinks that resolve outside the root are
  rejected.  This is narrower than the agent-facing gate, which deliberately
  allows any path the operating system permits.

  This namespace is read-only.  It never writes, deletes, moves or creates a
  file.  A UI must not offer those actions from this API."
  (:require [clojure.string :as str]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.error :as werr]
            [hc.core.workspace-gate.stamp :as stamp])
  (:import (java.nio.file Files LinkOption NoSuchFileException Path)))

(set! *warn-on-reflection* true)

(def ^:private no-links
  (make-array LinkOption 0))

(def ^:private default-limits
  {:max-file-bytes (* 2 1024 1024)
   :max-entries 2000
   :max-search-results 200
   :max-search-dirs 500
   :max-search-entries 10000
   :max-search-depth 12
   :max-search-content-file-bytes 524288
   :max-search-content-total-bytes 8388608
   :max-search-content-files 2000
   :max-search-snippet-chars 160})

;; ---------------------------------------------------------------------------
;; Results

(defn- ok
  [value]
  (merge {:ok? true} value))

(defn- failure
  ([code message] (failure code message nil))
  ([code message details]
   (cond-> {:ok? false
            :error {:code code :message message}}
     details (assoc-in [:error :details] details))))

;; ---------------------------------------------------------------------------
;; Paths

(defn- real-path
  "Resolve symlinks for an existing path; nil when the path is absent or the
  resolution fails."
  [^Path path]
  (try
    (.toRealPath path no-links)
    (catch NoSuchFileException _ nil)
    (catch java.io.IOException _ nil)))

(defn- under?
  [^Path child ^Path parent]
  (and child parent (.startsWith child parent)))

(defn- normalize-relative
  "Return a canonical `/`-separated relative path, or throw an ordinary
  rejection.  The empty string names the workspace root."
  [path]
  (when-not (or (nil? path) (string? path))
    (werr/fail! :INVALID_PATH :INVALID_PATH
                "工作区路径必须是字符串"))
  (let [raw (or path "")]
    (when (str/includes? raw "\\")
      (werr/fail! :INVALID_PATH :INVALID_PATH
                  "工作区路径必须使用 / 分隔符，不能包含反斜杠"))
    (when (str/includes? raw "\u0000")
      (werr/fail! :INVALID_PATH :INVALID_PATH
                  "工作区路径包含 NUL 字符"))
    (when (or (str/starts-with? raw "/")
              (re-matches #"(?i)^[a-z]:.*" raw))
      (werr/fail! :INVALID_PATH :ABSOLUTE_PATH
                  "文件浏览器只接受工作区相对路径"))
    (let [segments (->> (str/split raw #"/")
                        (remove #(or (str/blank? %) (= "." %)))
                        vec)]
      (when (some #(= ".." %) segments)
        (werr/fail! :INVALID_PATH :PATH_ESCAPE
                    "工作区路径不能包含 .. 段"))
      (str/join "/" segments))))

(defn- resolve-browser-path
  "Resolve PATH inside SVC's root and reject a symlink escape."
  [svc path]
  (let [relative (normalize-relative path)
        absolute (stamp/path-of (:root svc) relative)
        real (real-path absolute)]
    (when-not (or (= absolute (:root-path svc))
                  (under? absolute (:root-path svc)))
      (werr/fail! :INVALID_PATH :OUTSIDE_WORKSPACE
                  "路径不在工作区内"
                  {:path relative}))
    (when (and real (not (under? real (:root-real svc))))
      (werr/fail! :INVALID_PATH :OUTSIDE_WORKSPACE
                  "符号链接指向工作区外，拒绝访问"
                  {:path relative}))
    {:relative relative
     :absolute absolute
     :target (or real absolute)}))

;; ---------------------------------------------------------------------------
;; Service

(defn service
  "Build one read-only workspace browser service.

  Options: `:root` (required), `:max-file-bytes`, `:max-entries`,
  `:max-search-results`, `:max-search-dirs`, `:max-search-entries`,
  `:max-search-depth`, `:max-search-content-file-bytes`,
  `:max-search-content-total-bytes`, `:max-search-content-files`,
  `:max-search-snippet-chars`."
  [{:keys [root] :as opts}]
  (when-not (and (string? root) (not (str/blank? root)))
    (throw (ex-info "Workspace files service requires a root"
                    {:error/kind :files-invalid :field :root})))
  (let [root-path (stamp/root-path root)
        root-real (or (real-path root-path) root-path)]
    (merge default-limits
           (select-keys opts (keys default-limits))
           {:root (str root-path)
            :root-path root-path
            :root-real root-real
            :available? (Files/isDirectory root-path no-links)})))

(defn root-info
  "Return the workspace root identity and the display name."
  [svc]
  (ok {:workspace {:root (:root svc)
                   :name (some-> ^Path (:root-path svc) .getFileName str)
                   :available? (boolean (:available? svc))
                   :path ""}}))

(defn- kind-of
  [svc relative]
  (let [absolute (stamp/path-of (:root svc) relative)]
    (stamp/kind-of absolute)))

(defn- parent-path
  [relative]
  (when-not (str/blank? relative)
    (let [index (str/last-index-of relative "/")]
      (if index (subs relative 0 index) ""))))

(defn- entry
  "Build one truthful entry.  A symlink outside the root keeps its identity
  but exposes no target metadata."
  [svc relative raw-name]
  (let [directory? (str/ends-with? raw-name "/")
        name (if directory?
               (subs raw-name 0 (dec (count raw-name)))
               raw-name)
        child (if (str/blank? relative) name (str relative "/" name))
        absolute (stamp/path-of (:root svc) child)
        symlink? (Files/isSymbolicLink absolute)
        real (when symlink? (real-path absolute))
        broken? (and symlink? (nil? real))
        outside? (and symlink?
                       real
                       (not (under? real (:root-real svc))))
        stat (when-not outside? (wg/stat-path (:root svc) child))
        kind (cond
               outside? :symlink-outside
               broken? :missing
               (and stat (:is-dir stat)) :directory
               (and stat (:exists stat)) (kind-of svc child)
               :else :missing)
        file? (= :file kind)
        stamp-value (when file?
                      {:mtime-sec (:mtime-sec stat)
                       :mtime-nsec (:mtime-nsec stat)
                       :size (:size stat)})]
    (cond-> {:name name
             :path child
             :kind kind
             :exists? (boolean (or outside? (and stat (:exists stat))))
             :symlink? (boolean symlink?)
             :broken? (boolean broken?)
             :outside? (boolean outside?)
             :stamp-applicable? file?
             :previewable? (boolean
                            (and file?
                                 (<= (long (or (:size stat) 0))
                                     (long (:max-file-bytes svc)))))}
      (and stat (:exists stat))
      (assoc :size (:size stat)
             :mtime-sec (:mtime-sec stat)
             :mtime-nsec (:mtime-nsec stat))

      stamp-value
      (assoc :stamp stamp-value))))

(defn- entry-sort-key
  [{:keys [kind name]}]
  [(if (= :directory kind) 0 1)
   (str/lower-case (str name))
   (str name)])

(defn list-entries
  "List one directory.  The result is relative to the workspace root and
  directories sort first."
  [svc path]
  (try
    (let [{:keys [relative target]} (resolve-browser-path svc path)]
      (cond
        (not (Files/exists target no-links))
        (failure :NOT_FOUND "目录不存在" {:path relative})

        (not (Files/isDirectory target no-links))
        (failure :NOT_A_DIRECTORY "路径不是目录" {:path relative})

        :else
        (let [raw-names (wg/list-dir (:root svc) (str target))
              entries (->> raw-names
                           (map #(entry svc relative %))
                           (sort-by entry-sort-key)
                           vec)
              total (count entries)
              limit (long (:max-entries svc))
              shown (if (> total limit) (subvec entries 0 limit) entries)]
          (ok {:path relative
               :parent (parent-path relative)
               :workspace {:root (:root svc)
                           :name (some-> ^Path (:root-path svc) .getFileName str)}
               :entries shown
               :total-entries total
               :truncated? (> total limit)}))))
    (catch clojure.lang.ExceptionInfo t
      (if (werr/expected-failure? t)
        (let [data (ex-data t)
              status (:status data)
              code (if (= :STRUCTURE_CONFLICT status)
                     :NOT_FOUND
                     (or (:code data) status))]
          (failure code
                   (or (:message data) "目录读取失败")
                   (select-keys data [:path :reason])))
        (throw t)))
    (catch java.io.IOException t
      (failure :IO_ERROR "无法读取目录"
               {:class (.getName (class t))
                :message (.getMessage t)}))))

(defn stat-path
  "Observe one path.  A missing path is an ordinary observation, not an
  error."
  [svc path]
  (try
    (let [{:keys [relative absolute target]} (resolve-browser-path svc path)
          symlink? (Files/isSymbolicLink absolute)
          stat (wg/stat-path (:root svc) (str target))
          kind (if (:exists stat)
                 (if (:is-dir stat) :directory (stamp/kind-of target))
                 :missing)]
      (ok {:path relative
           :exists? (boolean (:exists stat))
           :kind kind
           :symlink? (boolean symlink?)
           :size (:size stat)
           :mtime-sec (:mtime-sec stat)
           :mtime-nsec (:mtime-nsec stat)
           :stamp (when (= :file kind)
                    {:mtime-sec (:mtime-sec stat)
                     :mtime-nsec (:mtime-nsec stat)
                     :size (:size stat)})}))
    (catch clojure.lang.ExceptionInfo t
      (if (werr/expected-failure? t)
        (let [data (ex-data t)]
          (failure (or (:code data) (:status data))
                   (or (:message data) "路径读取失败")
                   (select-keys data [:path :reason])))
        (throw t)))
    (catch java.io.IOException t
      (failure :IO_ERROR "无法读取路径"
               {:class (.getName (class t))
                :message (.getMessage t)}))))

(defn read-text
  "Read one UTF-8 text file through the protected reader.

  A stamp change during the read returns `:FILE_CONFLICT` with the expected
  and current stamps and no text.  Binary files, non-regular files and files
  above `:max-file-bytes` are ordinary rejections."
  [svc path]
  (try
    (let [{:keys [relative target]} (resolve-browser-path svc path)
          stat (wg/stat-path (:root svc) (str target))]
      (cond
        (not (:exists stat))
        (failure :NOT_FOUND "文件不存在" {:path relative})

        (:is-dir stat)
        (failure :NOT_A_FILE "路径是目录，不能按文件读取" {:path relative})

        (not (Files/isRegularFile target no-links))
        (failure :NOT_A_FILE "只有普通文件支持文本读取" {:path relative})

        (> (long (:size stat)) (long (:max-file-bytes svc)))
        (failure :FILE_TOO_LARGE
                 "文件超过浏览器预览上限"
                 {:path relative
                  :size (:size stat)
                  :limit (:max-file-bytes svc)})

        :else
        (let [result (wg/read-file (:root svc) (str target)
                                   {:max-file-bytes (:max-file-bytes svc)})]
          (if (= :FILE_CONFLICT (:status result))
            (failure :FILE_CONFLICT
                     "文件在读取期间发生变化；本次内容未作为稳定快照"
                     {:path relative
                      :expected (:stamp result)
                      :current (wg/current-stamp (:root svc) (str target))})
            (ok {:path relative
                 :text (:text result)
                 :stamp (:stamp result)
                 :size (:size result)
                 :kind :file
                 :changed-during-read? false})))))
    (catch clojure.lang.ExceptionInfo t
      (let [data (ex-data t)]
        (if (werr/expected-failure? t)
          (case (:status data)
            :LIMIT_EXCEEDED
            (failure :FILE_TOO_LARGE
                     "文件超过浏览器预览上限"
                     {:path (:path data)
                      :size (:size data)
                      :limit (:limit data)})

            :UNSUPPORTED_OPERATION
            (failure :BINARY_FILE
                     "文件不是可显示的 UTF-8 文本"
                     {:path (:path data)
                      :reason (:code data)})

            :STRUCTURE_CONFLICT
            (failure :NOT_FOUND "文件在读取前已不存在" {:path (:path data)})

            (failure (or (:code data) (:status data))
                     (or (:message data) "文件读取失败")
                     (select-keys data [:path :reason])))
          (throw t))))
    (catch java.io.IOException t
      (failure :IO_ERROR "无法读取文件"
               {:class (.getName (class t))
                :message (.getMessage t)}))))

(defn- search-match
  [svc {:keys [path name]}]
  (let [result (stat-path svc path)]
    (when (:ok? result)
      (merge result {:name name}))))

(defn- unicode-lower-case
  "Unicode lower-case with the root locale, so the result does not depend on
  the server's default locale."
  ^String [value]
  (.toLowerCase ^String (str value) java.util.Locale/ROOT))

(defn- code-points
  "Split TEXT into Unicode code points.  Clojure string sequences expose
  UTF-16 code units, which would split a supplementary character in two."
  ^ints [^String text]
  (let [length (.length text)
        total (.codePointCount text 0 length)
        points (int-array total)]
    (loop [index 0
           cursor 0]
      (if (>= index length)
        points
        (let [code-point (.codePointAt text index)]
          (aset-int points cursor code-point)
          (recur (+ index (Character/charCount code-point)) (inc cursor)))))))

(defn- points->string
  [^ints points start end]
  (let [n (- (long end) (long start))]
    (if (pos? n)
      (String. points (int start) (int n))
      "")))

(defn- count-occurrences
  "Count non-overlapping occurrences of NEEDLE in HAYSTACK."
  ^long [^String haystack ^String needle]
  (let [step (.length needle)]
    (loop [from 0
           total 0]
      (let [index (.indexOf haystack needle (int from))]
        (if (neg? index)
          total
          (recur (+ index step) (inc total)))))))

(defn- content-line-hit
  "Locate the first occurrence of NEEDLE on LINE.  The snippet keeps at most
  80 code points on each side of the match and at most SNIPPET-LIMIT code
  points in total."
  [^String line ^String needle snippet-limit]
  (let [^String lower (.toLowerCase line java.util.Locale/ROOT)
        index (.indexOf lower needle)]
    (when (>= index 0)
      (let [column (inc (.codePointCount lower 0 index))
            needle-length (.codePointCount needle 0 (.length needle))
            limit (long snippet-limit)
            points (code-points line)
            total (alength points)
            match-start (min total (dec column))
            match-end (min total (+ match-start needle-length))
            visible (min needle-length limit)
            left-budget (min 80 (max 0 (- limit visible)))
            start (max 0 (- match-start left-budget))
            left-taken (- match-start start)
            right-budget (min 80 (max 0 (- limit visible left-taken)))
            end (if (> needle-length limit)
                  (min total (+ match-start limit))
                  (min total (+ match-end right-budget)))]
        {:column column
         :snippet (points->string points start end)}))))

(defn- content-hit
  "Find the first matching line in TEXT and count every match in the file."
  [^String text ^String needle snippet-limit]
  (let [lines (str/split text #"\n" -1)]
    (loop [index 0
           match-count 0
           first-hit nil]
      (if (>= index (count lines))
        (when first-hit
          (assoc first-hit :match-count match-count))
        (let [^String line (nth lines index)
              ^String lower (.toLowerCase line java.util.Locale/ROOT)
              occurrences (count-occurrences lower needle)]
          (recur (inc index)
                 (+ match-count occurrences)
                 (or first-hit
                     (when (pos? occurrences)
                       (assoc (content-line-hit line needle snippet-limit)
                              :line (inc index))))))))))

(defn- content-file-result
  "Read one entry and classify the outcome.  A hit carries the read stamp, so
  the reported size and stamp describe exactly the text that matched."
  [svc entry ^String needle]
  (try
    (let [result (wg/read-file
                  (:root svc) (:path entry)
                  {:max-file-bytes
                   (long (:max-search-content-file-bytes svc))})]
      (cond
        (= :FILE_CONFLICT (:status result))
        {:skip :unstable}

        (= :OBSERVED (:status result))
        (if-let [hit (content-hit (:text result) needle
                                  (long (:max-search-snippet-chars svc)))]
          {:hit (merge {:path (:path entry)
                        :name (:name entry)
                        :kind :file
                        :exists? true
                        :size (:size result)
                        :stamp (:stamp result)}
                       hit)}
          {})

        :else
        {:skip :unstable}))
    (catch clojure.lang.ExceptionInfo t
      (case (:status (ex-data t))
        :LIMIT_EXCEEDED {:skip :too-large}
        (:FILE_CONFLICT :STRUCTURE_CONFLICT) {:skip :unstable}
        :UNSUPPORTED_OPERATION {:skip :binary}
        {:skip :unstable}))
    (catch java.io.IOException _
      {:skip :unstable})))

(defn- content-candidate?
  [{:keys [kind exists? outside? broken?]}]
  (and (= :file kind) (boolean exists?) (not outside?) (not broken?)))

(defn- zero-content-state
  []
  {:scanned-bytes 0
   :scanned-files 0
   :skipped {:binary 0 :unstable 0 :too-large 0}})

(defn- content-step
  "Scan the regular files among ENTRIES.  Stops without reading more when the
  result, file or byte budget is reached."
  [svc entries needle limit results state]
  (let [max-files (long (:max-search-content-files svc))
        max-total (long (:max-search-content-total-bytes svc))
        max-file (long (:max-search-content-file-bytes svc))
        finish (fn [results state stop? truncated?]
                 (merge state
                        {:results results
                         :stop? (boolean stop?)
                         :truncated? (boolean truncated?)}))]
    (loop [files (filterv content-candidate? entries)
           results results
           state state]
      (cond
        (empty? files)
        (finish results state false false)

        (>= (count results) limit)
        (finish results state true true)

        (>= (long (:scanned-files state)) max-files)
        (finish results state true true)

        :else
        (let [entry (first files)
              size (long (or (:size entry) 0))]
          (cond
            (> size max-file)
            (recur (subvec files 1) results
                   (update-in state [:skipped :too-large] (fnil inc 0)))

            (> (+ (long (:scanned-bytes state)) size) max-total)
            (finish results state true true)

            :else
            (let [outcome (content-file-result svc entry needle)]
              (recur (subvec files 1)
                     (if-let [hit (:hit outcome)]
                       (conj results hit)
                       results)
                     (cond-> (-> state
                                 (update :scanned-bytes + size)
                                 (update :scanned-files inc))
                       (:skip outcome)
                       (update-in [:skipped (:skip outcome)]
                                  (fnil inc 0)))))))))))

(defn- search-response
  [query mode results truncated? visited-dirs visited-entries content-state]
  (ok (merge {:query query
              :mode mode
              :results results
              :truncated? (boolean truncated?)
              :visited-dirs visited-dirs
              :visited-entries visited-entries}
             (when (= :content mode)
               (select-keys content-state
                            [:scanned-bytes :scanned-files :skipped])))))

(defn search
  "Search file names or file contents below the root.

  `:name` mode compares entry names case-insensitively.  `:content` mode
  reads regular UTF-8 files and reports the first matching line per file
  plus the number of matches in that file.  Both walks are bounded by
  `:max-search-dirs`, `:max-search-entries`, `:max-search-results` and
  `:max-search-depth`; content reads add `:max-search-content-file-bytes`,
  `:max-search-content-total-bytes` and `:max-search-content-files`.  A
  truncated result says so; it is never presented as complete."
  [svc {:keys [query mode limit] :or {limit (:max-search-results svc)}}]
  (let [mode (or mode :name)]
    (cond
      (not (contains? #{:name :content} mode))
      (failure :UNSUPPORTED_OPERATION
               "未知的文件搜索模式"
               {:mode mode
                :allowed [:name :content]})

      (str/blank? (str query))
      (failure :INVALID_QUERY "搜索词不能为空" {})

      :else
      (let [query-text (str/trim (str query))
            needle (if (= :content mode)
                     (unicode-lower-case query-text)
                     (str/lower-case query-text))
            limit (min (long (or limit (:max-search-results svc)))
                       (long (:max-search-results svc)))]
        (loop [queue [{:path "" :depth 0}]
               visited-dirs 0
               visited-entries 0
               results []
               truncated? false
               content-state (zero-content-state)]
          (if (or (empty? queue) (>= (count results) limit))
            (search-response query-text mode results
                             (if (= :content mode)
                               (or truncated? (seq queue)
                                   (>= (count results) limit))
                               (or truncated? (seq queue)))
                             visited-dirs visited-entries content-state)
            (let [{:keys [path depth]} (first queue)
                  queue (subvec queue 1)]
              (cond
                (> depth (long (:max-search-depth svc)))
                (recur queue visited-dirs visited-entries results true
                       content-state)

                (>= visited-dirs (long (:max-search-dirs svc)))
                (search-response query-text mode results true
                                 visited-dirs visited-entries content-state)

                :else
                (let [listing (list-entries svc path)]
                  (if-not (:ok? listing)
                    (recur queue (inc visited-dirs) visited-entries results
                           truncated? content-state)
                    (let [entries (:entries listing)
                          next-visited (+ visited-entries (count entries))
                          over-entries? (> next-visited
                                           (long (:max-search-entries svc)))
                          child-dirs (->> entries
                                          (filter #(= :directory (:kind %)))
                                          (remove :outside?)
                                          (map (fn [e]
                                                 {:path (:path e)
                                                  :depth (inc depth)})))]
                      (if (= :content mode)
                        (let [step (content-step svc entries needle limit
                                                 results content-state)
                              step-state (select-keys step
                                                      [:scanned-bytes
                                                       :scanned-files
                                                       :skipped])
                              truncated? (or truncated?
                                             (:truncated? step)
                                             over-entries?)]
                          (if (or (:stop? step) truncated?)
                            (search-response query-text mode (:results step)
                                             true (inc visited-dirs)
                                             next-visited step-state)
                            (recur (into queue child-dirs)
                                   (inc visited-dirs)
                                   next-visited
                                   (:results step)
                                   false
                                   step-state)))
                        (let [all-matches (filter #(str/includes?
                                                    (str/lower-case
                                                     (str (:name %)))
                                                    needle)
                                                  entries)
                              remaining (- limit (count results))
                              matched (->> all-matches
                                           (take remaining)
                                           (keep #(search-match svc %))
                                           vec)
                              more-matches? (> (count all-matches)
                                               (count matched))
                              truncated? (or truncated?
                                             more-matches?
                                             over-entries?)]
                          (if truncated?
                            (search-response query-text mode
                                             (into results matched)
                                             true (inc visited-dirs)
                                             next-visited content-state)
                            (recur (into queue child-dirs)
                                   (inc visited-dirs)
                                   next-visited
                                   (into results matched)
                                   false
                                   content-state)))))))))))))))

;; ---------------------------------------------------------------------------
;; Dispatch

(defn dispatch
  "Dispatch one browser read operation.

  `operation` is `:root`, `:list`, `:stat`, `:read` or `:search`."
  [svc operation params]
  (case operation
    :root (root-info svc)
    :list (list-entries svc (:path params))
    :stat (stat-path svc (:path params))
    :read (read-text svc (:path params))
    :search (search svc params)
    (failure :UNSUPPORTED_OPERATION "未知的工作区读取操作"
             {:operation operation})))
