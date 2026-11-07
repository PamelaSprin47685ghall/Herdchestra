(ns hc.client.files.model
  "Pure state and display projections for the workspace file browser.

  The browser holds only a server-reported copy: relative path, entry kind,
  existence and the exact `(mtime-sec, mtime-nsec, size)` stamp.  It never
  scans a local path and never invents an entry, a diff, or a successful
  read.  `apply-read` keeps the previous text as the diff baseline only when
  the user actually saw it."
  (:require [clojure.string :as str]
            [hc.client.files.diff :as diff]))

;; ---------------------------------------------------------------------------
;; Small access helpers

(defn field
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (if (keyword? k) (subs (str k) 1) (keyword k))))))

;; ---------------------------------------------------------------------------
;; Paths

(defn path-segments
  [path]
  (->> (str/split (str (or path "")) #"/")
       (remove str/blank?)
       vec))

(defn parent-path
  [path]
  (when-not (str/blank? (str path))
    (let [segments (path-segments path)]
      (if (<= (count segments) 1)
        ""
        (str/join "/" (butlast segments))))))

(defn breadcrumbs
  "Return `[{:label :path}]` from the workspace root to PATH."
  ([path] (breadcrumbs path "工作区"))
  ([path root-label]
   (let [segments (path-segments path)]
     (into [{:label root-label :path ""}]
           (map-indexed (fn [index segment]
                          {:label segment
                           :path (str/join "/" (subvec segments 0 (inc index)))}))
           segments))))

;; ---------------------------------------------------------------------------
;; Formatting

(defn- format-decimal
  [value places]
  #?(:clj (format (str "%." places "f") (double value))
     :cljs (.toFixed (double value) places)))

(defn format-size
  [size]
  (if (nil? size)
    "—"
    (let [size (long size)]
      (cond
        (< size 1024) (str size " B")
        (< size (* 1024 1024)) (str (format-decimal (/ size 1024.0) 1) " KiB")
        (< size (* 1024 1024 1024)) (str (format-decimal (/ size 1024.0 1024.0) 1) " MiB")
        :else (str (format-decimal (/ size 1024.0 1024.0 1024.0) 1) " GiB")))))

(defn- pad
  [value width]
  (let [text (str value)]
    (str (apply str (repeat (max 0 (- width (count text))) "0"))
         text)))

(defn format-time
  "Format an epoch-second/nanosecond observation for display.  Returns nil
  when no time was observed."
  ([mtime-sec] (format-time mtime-sec 0))
  ([mtime-sec mtime-nsec]
   (when (some? mtime-sec)
     #?(:clj
        (let [instant (java.time.Instant/ofEpochSecond
                       (long mtime-sec) (long (or mtime-nsec 0)))
              formatter (java.time.format.DateTimeFormatter/ofPattern
                         "yyyy-MM-dd HH:mm:ss"
                         java.util.Locale/ROOT)]
          (.format formatter
                   (.atZone instant (java.time.ZoneId/systemDefault))))
        :cljs
        (let [date (js/Date. (* 1000 (long mtime-sec)))]
          (str (.getFullYear date)
               "-" (pad (inc (.getMonth date)) 2)
               "-" (pad (.getDate date) 2)
               " " (pad (.getHours date) 2)
               ":" (pad (.getMinutes date) 2)
               ":" (pad (.getSeconds date) 2)))))))

(defn stamp-equal?
  [a b]
  (and (map? a) (map? b)
       (= (field a :mtime-sec) (field b :mtime-sec))
       (= (field a :mtime-nsec) (field b :mtime-nsec))
       (= (field a :size) (field b :size))))

(defn stamp-label
  [stamp]
  (when (map? stamp)
    (str "mtime " (field stamp :mtime-sec)
         "." (pad (or (field stamp :mtime-nsec) 0) 9)
         " · size " (format-size (field stamp :size)))))

(defn kind-label
  [kind]
  (case kind
    :directory "目录"
    :file "文件"
    :other "其他类型"
    :missing "不存在"
    :symlink-outside "链接（工作区外）"
    "类型未知"))

(defn entry-type-label
  [entry]
  (cond
    (:symlink? entry)
    (if (:outside? entry)
      "符号链接（工作区外）"
      (str "符号链接 → " (kind-label (:kind entry))))

    :else (kind-label (:kind entry))))

;; ---------------------------------------------------------------------------
;; State

(defn initial
  []
  {:status :idle
   :path ""
   :parent nil
   :workspace nil
   :entries []
   :total-entries 0
   :truncated? false
   :error nil
   :request-id 0
   :selected-path nil
   :preview nil
   :preview-status :idle
   :preview-error nil
   :preview-request-id 0
   :baseline nil
   :diff nil
   :search-query ""
   :search {:status :idle
            :query ""
            :mode :name
            :results []
            :truncated? false
            :error nil
            :request-id 0}})

(defn normalize-entry
  [entry]
  (when (map? entry)
    (let [stamp (field entry :stamp)]
      {:name (or (field entry :name) "")
       :path (or (field entry :path) "")
       :kind (or (field entry :kind) :missing)
       :exists? (boolean (field entry :exists?))
       :symlink? (boolean (field entry :symlink?))
       :outside? (boolean (field entry :outside?))
       :stamp-applicable? (boolean (field entry :stamp-applicable?))
       :previewable? (boolean (field entry :previewable?))
       :reason (field entry :reason)
       :reason-label (field entry :reason-label)
       :error-code (or (field entry :error-code)
                       (get-in entry [:error :code])
                       (get-in entry [:preview-error :code]))
       :binary? (boolean (field entry :binary?))
       :too-large? (boolean (field entry :too-large?))
       :size (field entry :size)
       :mtime-sec (field entry :mtime-sec)
       :mtime-nsec (field entry :mtime-nsec)
       :stamp (when (map? stamp) stamp)})))

(defn- sort-entries
  [entries]
  (sort-by (fn [entry]
             [(if (= :directory (:kind entry)) 0 1)
              (str/lower-case (str (:name entry)))
              (str (:name entry))])
           entries))

(defn apply-list
  "Apply one successful `/list` result.  A failed result keeps the previous
  truthful listing and stores the error for display."
  [state result]
  (if (:ok? result)
    (let [entries (->> (field result :entries)
                       (keep normalize-entry)
                       sort-entries
                       vec)
          workspace (field result :workspace)]
      (assoc state
             :status :loaded
             :error nil
             :path (or (field result :path) "")
             :parent (field result :parent)
             :workspace workspace
             :entries entries
             :total-entries (or (field result :total-entries) (count entries))
             :truncated? (boolean (field result :truncated?))
             :loaded-at #?(:clj (System/currentTimeMillis)
                           :cljs (.getTime (js/Date.)))))
    (assoc state
           :status :error
           :error result)))

(defn apply-read
  "Apply one `/read` result.

  A successful read becomes the new preview and the new diff baseline.  When
  a previous baseline for the same path exists, the diff is computed from the
  text the user actually saw, and the old and current stamps are attached.
  A failed read leaves the previous preview and baseline intact."
  [state result]
  (if (:ok? result)
    (let [path (field result :path)
          requested (:selected-path state)
          text (field result :text)
          stamp (field result :stamp)
          baseline (:baseline state)
          same-path? (and baseline (= path (:path baseline)))
          diff-result (when (and same-path?
                                 (not (stamp-equal? (:stamp baseline) stamp)))
                        (assoc (diff/diff-text (:text baseline) text)
                               :base-stamp (:stamp baseline)
                               :current-stamp stamp))
          preview {:path path
                   :text text
                   :stamp stamp
                   :size (field result :size)
                   :kind (field result :kind)}]
      (if (and (some? requested) (not= requested path))
        (assoc state
               :preview-status :error
               :preview-error {:ok? false
                               :error {:code :PATH_MISMATCH
                                       :message (str "服务器返回了不同路径：" path
                                                     "；当前请求：" requested)}})
        (assoc state
               :selected-path path
               :preview preview
               :preview-status :loaded
               :preview-error nil
               :diff diff-result
               :baseline {:path path :text text :stamp stamp})))
    (assoc state
           :preview-status :error
           :preview-error result)))

(defn apply-search
  [state result]
  (let [search (:search state)]
    (if (:ok? result)
      (assoc state :search
             (assoc search
                    :status :loaded
                    :error nil
                    :query (or (field result :query) (:query search))
                    :mode (or (field result :mode) :name)
                    :results (->> (field result :results)
                                  (keep normalize-entry)
                                  vec)
                    :truncated? (boolean (field result :truncated?))
                    :visited-dirs (field result :visited-dirs)
                    :visited-entries (field result :visited-entries)))
      (assoc state :search
             (assoc search :status :error :error result)))))

(defn navigate
  "Clear the file preview and diff because the user is looking at another
  directory."
  [state path]
  (assoc state
         :path (str (or path ""))
         :status :loading
         :error nil
         :selected-path nil
         :preview nil
         :preview-status :idle
         :preview-error nil
         :baseline nil
         :diff nil))

(defn clear-search
  [state]
  (assoc state :search (assoc (:search state)
                              :status :idle
                              :query ""
                              :results []
                              :truncated? false
                              :error nil)))

(defn diff-summary
  [diff]
  (when (map? diff)
    (let [summary (:summary diff)]
      (str "+" (or (:added summary) 0)
           " 行 / −" (or (:removed summary) 0) " 行"))))
