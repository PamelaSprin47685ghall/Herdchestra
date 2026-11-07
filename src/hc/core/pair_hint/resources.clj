(ns hc.core.pair-hint.resources
  "Paired bilingual resources and deterministic template rendering.

  A language is usable only when both `en.md` and `zh-CN.md` exist and are
  non-blank.  Loading never falls back from one language to the other: a
  missing half is a failure, because pretending another language succeeded
  would make the session language a lie."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def supported-languages
  "The only languages Pair Hint admits."
  [:en :zh-CN])

(def language-set
  (set supported-languages))

(def default-resource-paths
  {:en "hc/core/pair_hint/en.md"
   :zh-CN "hc/core/pair_hint/zh-CN.md"})

(def placeholder-pattern #"\{\{([A-Za-z0-9_-]+)\}\}")

(def zh-cn-fixed-first-sentence
  "The first sentence every zh-CN hint resource must carry."
  "思考过程与全部对外输出，一律用简体中文")

(defn valid-language?
  [language]
  (contains? language-set language))

(defn placeholders
  "Placeholder names in first-appearance order, without duplicates."
  [template]
  (vec (distinct (map second (re-seq placeholder-pattern template)))))

(defn- read-classpath
  [path]
  (when-let [url (io/resource path)]
    (slurp url :encoding "UTF-8")))

(defn- load-one
  [loader path]
  (when (and (some? path) (ifn? loader))
    (let [value (loader path)]
      (cond
        (string? value) value
        (some? value) (slurp value :encoding "UTF-8")
        :else nil))))

(defn load-pair
  "Load both language resources.

  Options:
  - `:resources` a complete `{:en \"...\" :zh-CN \"...\"}` map (tests, embedding)
  - `:loader` a function from classpath path to text (defaults to `io/resource`)
  - `:paths` override the two classpath paths

  Throws `:PAIR_HINT_RESOURCE_MISSING` when either half is absent or blank,
  and `:PAIR_HINT_RESOURCE_MISMATCH` when the two templates do not name the
  same placeholders."
  ([] (load-pair {}))
  ([{:keys [resources loader paths] :or {paths default-resource-paths}}]
   (let [texts
         (if (some? resources)
           (select-keys resources supported-languages)
           (into {}
                 (map (fn [[language path]]
                        [language (load-one (or loader read-classpath) path)]))
                 paths))
         missing (->> supported-languages
                      (remove #(let [text (get texts %)]
                                 (and (string? text) (not (str/blank? text)))))
                      vec)]
     (when (seq missing)
       (throw (ex-info "pair-hint: both language resources are required"
                       {:error :PAIR_HINT_RESOURCE_MISSING
                        :missing missing
                        :paths (when (nil? resources) paths)})))
     (let [en-placeholders (placeholders (:en texts))
           zh-placeholders (placeholders (:zh-CN texts))]
       (when (not= (set en-placeholders) (set zh-placeholders))
         (throw (ex-info "pair-hint: language resources do not pair"
                         {:error :PAIR_HINT_RESOURCE_MISMATCH
                          :en en-placeholders
                          :zh-CN zh-placeholders})))
       (when-not (str/starts-with? (:zh-CN texts) zh-cn-fixed-first-sentence)
         (throw (ex-info "pair-hint: zh-CN resource must start with the fixed sentence"
                         {:error :PAIR_HINT_RESOURCE_MISMATCH
                          :field :zh-CN-first-sentence
                          :expected zh-cn-fixed-first-sentence})))
       {:en (:en texts)
        :zh-CN (:zh-CN texts)}))))

(defn resource-pair?
  "True when both supported language resources are present and non-blank."
  [resources]
  (and (map? resources)
       (every? #(let [text (get resources %)]
                  (and (string? text) (not (str/blank? text))))
               supported-languages)
       (= (set (placeholders (:en resources)))
          (set (placeholders (:zh-CN resources))))
       (str/starts-with? (:zh-CN resources) zh-cn-fixed-first-sentence)))

(defn render
  "Substitute `{{name}}` placeholders in `template`.

  Every placeholder must have a value.  Extra values are ignored, so callers
  may share one value map between both language resources.  A missing value is
  a failure rather than an empty string; a silent blank would corrupt the
  hint without saying so."
  [template values]
  (when-not (string? template)
    (throw (ex-info "pair-hint: template must be a string"
                    {:error :PAIR_HINT_TEMPLATE_INVALID})))
  (let [needed (placeholders template)
        missing (->> needed (remove #(some? (get values %))) vec)]
    (when (seq missing)
      (throw (ex-info "pair-hint: template value missing"
                      {:error :PAIR_HINT_TEMPLATE_VALUE_MISSING
                       :missing missing})))
    (str/replace template placeholder-pattern
                 (fn [[_ name]] (str (get values name))))))

(defn render-language
  "Render one language resource from a loaded pair."
  [resources language values]
  (when-not (valid-language? language)
    (throw (ex-info "pair-hint: unsupported language"
                    {:error :PAIR_HINT_LANGUAGE_INVALID
                     :language language
                     :supported supported-languages})))
  (when-not (resource-pair? resources)
    (throw (ex-info "pair-hint: resources are not a complete pair"
                    {:error :PAIR_HINT_RESOURCE_MISSING
                     :language language})))
  (render (get resources language) values))
