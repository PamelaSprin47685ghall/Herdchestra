(ns hc.compose.reports.support
  "Generators shared by the report-delivery tests."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hc.compose.reports.report :as report]))

(def gen-char
  "Characters that exercise quoting, escaping, Unicode, and indentation."
  (gen/elements [\a \Z \0 \space \newline \tab \" \\ \# \u4e16 \u754c \u00e9 \u03bb \u2014]))

(def gen-text
  "Arbitrary report text built from the edge-case characters."
  (gen/fmap #(apply str %) (gen/vector gen-char 0 40)))

(def gen-id
  "A non-blank identifier."
  (gen/such-that #(not (str/blank? %))
                 gen/string-alphanumeric
                 100))

(defn report-for
  "Build a full-inline report."
  [report-id parent-call-id text]
  (report/make-report
   {:report-id report-id
    :parent-call-id parent-call-id
    :payload {:kind :llm-report :text text}}))
