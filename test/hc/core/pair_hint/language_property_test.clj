(ns hc.core.pair-hint.language-property-test
  "Property tests for locale resolution and strict explicit-language
  normalization.

  Locale tags are heuristic input: they may only select `:en`, `:zh-CN`, or
  the explicit `:en` default recorded as `:source :default`.  Explicit
  language values are exact: `:EN`, `:zh`, and `:zh_CN` are invalid rather
  than silently rewritten."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.pair-hint.language :as language]))

(def ^:private primaries
  [["en" :en]
   ["EN" :en]
   ["En" :en]
   ["zh" :zh-CN]
   ["ZH" :zh-CN]
   ["Zh" :zh-CN]
   ["enx" nil]
   ["zhx" nil]
   ["fr" nil]
   ["de" nil]
   ["" nil]
   [" en" nil]
   ["en " nil]])

(def ^:private suffixes
  ["" "-US" "_CN" "_US.UTF-8" "-Hant-TW" "-x" "_x"])

(def ^:private exact-keywords
  [:en :EN :zh :zh-CN :zh_CN :en-US :fr :foo/en])

(defspec locale-resolution-stays-in-the-supported-set 100
  (prop/for-all [[primary expected] (gen/elements primaries)
                 suffix (gen/elements suffixes)]
    (let [locale (str primary suffix)
          resolved (language/locale->language locale)
          ladder (language/resolve-language {:locale locale})
          ide (language/resolve-language {:ide-locale locale})]
      (and
       (= expected resolved)
       ;; The locale path never invents a third language.
       (contains? #{:en :zh-CN} (:language ladder))
       (contains? #{:en :zh-CN} (:language ide))
       ;; A selected locale is reported as such; a miss records the default.
       (if resolved
         (and (= :locale (:source ladder))
              (= resolved (:language ladder))
              (= locale (:raw ladder))
              (= :ide-locale (:source ide))
              (= resolved (:language ide)))
         (and (= :default (:source ladder))
              (= :default (:source ide))
              (= :en (:language ladder))
              (= :en (:language ide))
              (true? (:undetermined? ladder))
              (nil? (:raw ladder))))))))

(defspec explicit-language-values-are-normalized-strictly 100
  (prop/for-all [value (gen/one-of [gen/string-alphanumeric
                                    (gen/elements exact-keywords)])]
    (let [normalized (language/normalize-language value)
          expected (cond
                     (contains? #{:en "en"} value) :en
                     (contains? #{:zh-CN "zh-CN"} value) :zh-CN
                     :else nil)]
      (and (contains? #{nil :en :zh-CN} normalized)
           (= expected normalized)
           ;; An explicit value outside the exact pair must fail, not fall
           ;; back to the locale ladder or the other language.
           (if (nil? expected)
             (let [threw (try
                           (language/resolve-language
                            {:explicit-language value
                             :locale "en-US"})
                           nil
                           (catch clojure.lang.ExceptionInfo e
                             (= :PAIR_HINT_LANGUAGE_INVALID (:error (ex-data e)))))]
               (boolean threw))
             (= expected
                (:language (language/resolve-language
                            {:explicit-language value
                             :locale "zh-CN"}))))))))
