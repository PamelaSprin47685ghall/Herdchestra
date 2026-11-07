(ns hc.server.documents.pages
  "Explicit 1-based page selection.

  A page selection is either absent (all pages), a sequence of positive
  integers, or a user-facing range string such as `\"2-5\"` or
  `\"第 2–5 页\"`.  Page numbers are never rebased: page 2 stays page 2 in
  every result, so a citation can point back to the original document."
  (:require [clojure.string :as str]
            [hc.server.documents.error :as error]))

(def default-max-pages 10000)

(defn- normalize-text
  [s]
  (-> s
      (str/replace #"[–—−~～至]" "-")
      (str/replace #"\s*-\s*" "-")
      (str/replace #"^\s*第\s*" "")
      (str/replace #"\s*页\s*$" "")
      str/trim))

(defn- expand-token
  [token max-pages]
  (let [token (str/trim token)]
    (cond
      (str/blank? token)
      {:ok? true :pages []}

      (re-matches #"\d+" token)
      (let [page (parse-long token)]
        (if (and page (pos? page))
          {:ok? true :pages [page]}
          {:ok? false :invalid [token]}))

      (re-matches #"\d+-\d+" token)
      (let [[_ start end] (re-matches #"(\d+)-(\d+)" token)
            start (parse-long start)
            end (parse-long end)]
        (cond
          (or (nil? start) (nil? end)
              (not (pos? start)) (not (pos? end)))
          {:ok? false :invalid [token]}

          (> start end)
          {:ok? false :invalid [token]}

          (> (- end start -1) max-pages)
          {:ok? false :too-many [token]}

          :else
          {:ok? true :pages (vec (range start (inc end)))}))

      :else
      {:ok? false :invalid [token]})))

(defn parse-range
  "Parse a page selection without knowing the document page count.

  Returns `{:ok? true :pages nil :all? true}` when nothing was selected, or
  `{:ok? true :pages [1 2 ...] :all? false}`.  Duplicates are removed and the
  result is in document order.  A refusal uses `:PAGE_RANGE`."
  ([selection]
   (parse-range selection {}))
  ([selection {:keys [max-pages] :or {max-pages default-max-pages}}]
   (let [max-pages (or max-pages default-max-pages)]
     (cond
       (nil? selection)
       {:ok? true :pages nil :all? true}

       (string? selection)
       (let [normalized (normalize-text selection)]
         (if (str/blank? normalized)
           {:ok? true :pages nil :all? true}
           (let [tokens (->> (str/split normalized #"[\s,，、;；]+")
                             (remove str/blank?))
                 parsed (mapv #(expand-token % max-pages) tokens)
                 invalid (mapcat :invalid parsed)
                 too-many (mapcat :too-many parsed)]
             (cond
               (seq invalid)
               (error/failure :PAGE_RANGE
                              "page selection must use positive 1-based page numbers"
                              {:invalid (vec invalid) :selection selection})

               (seq too-many)
               (error/failure :PAGE_RANGE
                              "page selection expands to too many pages"
                              {:invalid (vec too-many) :max-pages max-pages})

               :else
               (let [pages (->> (mapcat :pages parsed) distinct sort vec)]
                 (if (> (count pages) max-pages)
                   (error/failure :PAGE_RANGE
                                  "page selection contains too many pages"
                                  {:max-pages max-pages :selection selection})
                   {:ok? true :pages pages :all? false}))))))

       (sequential? selection)
       (let [pages (vec selection)
             invalid (->> pages
                          (remove #(and (integer? %) (pos? %)))
                          vec)]
         (cond
           (seq invalid)
           (error/failure :PAGE_RANGE
                          "page selection must contain positive 1-based integers"
                          {:invalid invalid :selection selection})

           (> (count (distinct pages)) max-pages)
           (error/failure :PAGE_RANGE
                          "page selection contains too many pages"
                          {:max-pages max-pages})

           :else
           {:ok? true :pages (->> pages distinct sort vec) :all? false}))

       (integer? selection)
       (parse-range [selection] {:max-pages max-pages})

       :else
       (error/failure :PAGE_RANGE
                      "page selection must be a range string or a sequence of page numbers"
                      {:selection selection})))))

(defn validate
  "Check selected pages against the real document page count.

  `selected` is the `:pages` value from `parse-range` (nil means all pages).
  Returns nil when valid, otherwise a `:PAGE_RANGE` refusal.  The refusal
  lists out-of-range pages and the actual count; it never clamps silently."
  ([selected page-count]
   (validate selected page-count {}))
  ([selected page-count {:keys [max-pages] :or {max-pages default-max-pages}}]
   (let [max-pages (or max-pages default-max-pages)]
     (cond
       (nil? page-count)
       (error/failure :PAGE_RANGE "document page count is unknown")

       (not (and (integer? page-count) (not (neg? page-count))))
       (error/failure :PAGE_RANGE "document page count is invalid"
                      {:page-count page-count})

       (nil? selected)
       (when (> page-count max-pages)
         (error/failure :PAGE_RANGE
                        "document has too many pages for one extraction"
                        {:page-count page-count :max-pages max-pages}))

       :else
       (let [invalid (->> selected (remove #(<= 1 % page-count)) vec)]
         (when (seq invalid)
           (error/failure :PAGE_RANGE
                          "selected pages are outside the document"
                          {:invalid invalid :page-count page-count})))))))

(defn selected
  "The concrete page vector for a parsed selection, or all 1..page-count."
  [parsed page-count]
  (or (:pages parsed) (vec (range 1 (inc page-count)))))

(defn page-ref
  "Source and page identity for one extracted page."
  [source page]
  {:source (select-keys source [:id :name :mime])
   :page page})
