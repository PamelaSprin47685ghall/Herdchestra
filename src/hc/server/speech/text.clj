(ns hc.server.speech.text
  "Turn one selected message version into speakable text.

  The default policy follows AGENTS.md §7.4 旅程 6.7（用语音）: skip fenced code, raw URLs,
  large tables and formulas, and read a short notice instead.  Small tables
  become comma-separated rows.  Images use their alt text or the word
  \"image\".  The caller decides which visible message version to pass; this
  namespace never reads history or tool logs."
  (:require [clojure.string :as str])
  (:import (java.util.regex Matcher)))

(def default-notices
  {:en {:code-block "There is a code block."
        :indented-code "There is a code block."
        :table "There is a table."
        :url "There is a link."
        :formula "There is a formula."
        :image "Image."}
   :zh-CN {:code-block "这里有代码块。"
           :indented-code "这里有代码块。"
           :table "这里有表格。"
           :url "这里有一个网址。"
           :formula "这里有一个公式。"
           :image "图片。"}})

(defn- notice-map
  [notices language]
  (let [language (or language :en)
        base (get default-notices language
                  (get default-notices :en))
        custom (cond
                 (and (map? notices) (contains? notices language))
                 (get notices language)

                 (and (map? notices)
                      (some #(contains? notices %) (keys default-notices)))
                 (get notices :en)

                 (map? notices) notices
                 :else {})]
    (merge base custom)))

(defn- notice
  [notices language kind]
  (or (get-in notices [language kind])
      (get-in notices [:en kind])
      (get-in default-notices [:en kind])
      ""))

(defn- add-skip
  [skipped kind notice-text count]
  (if (zero? count)
    skipped
    (if-let [existing (some #(when (= kind (:kind %)) %) skipped)]
      (mapv (fn [item]
              (if (= kind (:kind item))
                (update item :count + count)
                item))
            skipped)
      (conj skipped {:kind kind :count count :notice notice-text}))))

(defn- replace-matches
  "Replace every match with `(f matcher)`.  This preserves capture groups
  without depending on how a string replacement function receives them."
  [^String text ^java.util.regex.Pattern pattern f]
  (let [matcher (.matcher pattern text)
        output (StringBuilder.)]
    (loop [last-end 0]
      (if (.find matcher)
        (do
          (.append output (subs text last-end (.start matcher)))
          (.append output (str (f matcher)))
          (recur (.end matcher)))
        (do
          (.append output (subs text last-end))
          (str output))))))

(defn- replace-simple
  [text pattern replacement]
  (let [count (count (re-seq pattern text))]
    [(str/replace text pattern replacement) count]))

(defn- strip-fences
  [text]
  (-> text
      (str/replace #"(?m)^\s*(```|~~~).*$" "")
      (str/replace #"(?m)^\s*(```|~~~)\s*$" "")))

(defn- indented-line?
  [line]
  (or (str/starts-with? line "    ")
      (str/starts-with? line "\t")))

(defn- replace-indented-code
  [text notice-text min-lines]
  (let [lines (str/split text #"\n" -1)]
    (loop [remaining lines
           output []
           block []
           blocks 0]
      (if (empty? remaining)
        [(str/join "\n"
                   (if (>= (count block) min-lines)
                     (conj output notice-text)
                     (into output block)))
         (if (>= (count block) min-lines) (inc blocks) blocks)]
        (let [line (first remaining)]
          (if (indented-line? line)
            (recur (rest remaining) output (conj block line) blocks)
            (if (>= (count block) min-lines)
              (recur remaining (conj output notice-text) [] (inc blocks))
              (recur (rest remaining)
                     (conj (into output block) line)
                     []
                     blocks))))))))

(defn- table-separator?
  [line]
  (boolean
   (re-matches #"\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*"
               line)))

(defn- table-row?
  [line]
  (str/includes? line "|"))

(defn- table->text
  [block]
  (->> block
       (remove table-separator?)
       (map (fn [line]
              (-> line
                  str/trim
                  (str/replace #"^\|" "")
                  (str/replace #"\|$" "")
                  (str/split #"\|")
                  (->> (map str/trim)
                       (remove str/blank?)
                       (str/join ", ")))))
       (remove str/blank?)
       (str/join ". ")))

(defn- replace-tables
  [text notice-text min-rows]
  (let [lines (str/split text #"\n" -1)]
    (loop [remaining lines
           output []
           blocks 0]
      (if (empty? remaining)
        [(str/join "\n" output) blocks]
        (let [line (first remaining)
              next-line (second remaining)]
          (if (and (table-row? line)
                   next-line
                   (table-separator? next-line))
            (let [block (loop [rows (nnext remaining)
                               collected [line next-line]]
                          (if (and (seq rows) (table-row? (first rows)))
                            (recur (rest rows) (conj collected (first rows)))
                            [collected rows]))
                  [collected rows] block
                  data-rows (max 0 (- (count collected) 2))
                  large? (or (>= data-rows min-rows)
                             (some #(> (count %) 120) collected))]
              (if large?
                (recur rows (conj output notice-text) (inc blocks))
                (recur rows (conj output (table->text collected)) blocks)))
            (recur (rest remaining) (conj output line) blocks)))))))

(defn- replace-images
  [text image-notice]
  (let [count (atom 0)]
    [(replace-matches
      text
      #"!\[([^\]]*)\]\([^)]*\)"
      (fn [^Matcher matcher]
        (swap! count inc)
        (let [alt (.group matcher 1)]
          (if (str/blank? alt) image-notice alt))))
     @count]))

(defn- replace-links
  [text]
  (replace-matches text
                   #"\[([^\]]+)\]\([^)]*\)"
                   (fn [^Matcher matcher] (.group matcher 1))))

(defn- replace-formulas
  [text notice-text]
  (let [[text block-count] (replace-simple text #"(?s)\$\$.*?\$\$" notice-text)
        [text inline-count] (replace-simple
                             text
                             #"(?<!\\)\$(?=\S)[^$\n]*?\S\$(?!\d)"
                             notice-text)]
    [text (+ block-count inline-count)]))

(defn- truncate
  [text max-chars]
  (if (<= (count text) max-chars)
    {:text text :truncated? false}
    (let [cut (subs text 0 (max 0 max-chars))
          at (str/last-index-of cut " ")
          cut (if (and at (pos? at)) (subs cut 0 at) cut)]
      {:text (str/trim cut) :truncated? true})))

(defn prepare
  "Prepare TEXT for one read-aloud request.

  Options: `:notices`, `:language`, `:max-chars`, `:skip-code?`,
  `:skip-urls?`, `:skip-tables?`, `:skip-formulas?`, `:table-min-rows`.

  Returns `{:text spoken :skipped [{:kind .. :count .. :notice ..}]
  :truncated? bool}`.  The text never contains a raw URL, fenced code or a
  large table when the corresponding skip option is on."
  ([text] (prepare text {}))
  ([text {:keys [notices language max-chars skip-code? skip-urls?
                 skip-tables? skip-formulas? table-min-rows]
          :or {max-chars 20000
               skip-code? true
               skip-urls? true
               skip-tables? true
               skip-formulas? true
               table-min-rows 4}}]
   (let [language (or language :en)
         notices (notice-map notices language)
         text (str/replace (str text) "\r\n" "\n")
         text (str/replace text "\r" "\n")
         skipped []
         [text skipped]
         (if skip-code?
           (let [[text count] (replace-simple
                               text
                               #"(?s)```.*?```|~~~.*?~~~"
                               (notice notices language :code-block))
                 [text count-2] (replace-simple
                                 text
                                 #"(?s)```.*$|~~~.*$"
                                 (notice notices language :code-block))]
             [text (-> skipped
                       (add-skip :code-block
                                 (notice notices language :code-block)
                                 (+ count count-2)))])
           [(strip-fences text) skipped])
         [text code-blocks] (if skip-code?
                              (replace-indented-code
                               text
                               (notice notices language :indented-code)
                               3)
                              [text 0])
         skipped (add-skip skipped :code-block
                           (notice notices language :indented-code)
                           code-blocks)
         [text tables] (if skip-tables?
                         (replace-tables
                          text
                          (notice notices language :table)
                          table-min-rows)
                         (let [[text] (replace-tables text ""
                                                      Long/MAX_VALUE)]
                           [text 0]))
         skipped (add-skip skipped :table
                           (notice notices language :table)
                           tables)
         [text image-count] (replace-images
                             text
                             (notice notices language :image))
         skipped (add-skip skipped :image
                           (notice notices language :image)
                           image-count)
         text (replace-links text)
         [text formulas] (if skip-formulas?
                           (replace-formulas
                            text
                            (notice notices language :formula))
                           [text 0])
         skipped (add-skip skipped :formula
                           (notice notices language :formula)
                           formulas)
         [text urls] (if skip-urls?
                       (replace-simple text #"https?://[^\s<>\"')]+"
                                       (notice notices language :url))
                       [text 0])
         skipped (add-skip skipped :url
                           (notice notices language :url)
                           urls)
         text (-> text
                  (str/replace #"(?m)^#{1,6}\s+" "")
                  (str/replace #"(?m)^>\s?" "")
                  (str/replace #"(?m)^\s*(?:[-*+]|\d+[.)])\s+" "")
                  (str/replace #"(?m)^\s*(?:[-*_]\s*){3,}$" "")
                  (str/replace #"</?[A-Za-z][^>]*>" "")
                  (str/replace #"[ \t]+" " ")
                  (str/replace #"\n{3,}" "\n\n")
                  str/trim)
         text (if (str/blank? text)
                (->> skipped
                     (map :notice)
                     (remove str/blank?)
                     (distinct)
                     (str/join " "))
                text)
         {text :text truncated? :truncated?} (truncate text max-chars)]
     {:text text
      :skipped skipped
      :truncated? truncated?})))
