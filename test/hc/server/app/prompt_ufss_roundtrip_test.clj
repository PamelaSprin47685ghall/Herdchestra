(ns hc.server.app.prompt-ufss-roundtrip-test
  "Prove that every prompt/tool-schema string the server shows a model
  survives `hc.core.ufss/write-str` -> `read-str` verbatim.

  `prompts.clj` currently holds no `MODEL_TOOLS_UNAVAILABLE`-style message
  templates; those live in `hc.server.app.system`, which this ticket leaves
  untouched.  This namespace therefore covers every public prompt value, the
  composed role prompts, and every string leaf of the program tool schemas
  (`scope-schema` included)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.core.ufss :as ufss]
            [hc.server.app.prompts :as prompts]))

(defn- string-leaves
  [value]
  (cond
    (string? value) [value]
    (map? value) (mapcat string-leaves (vals value))
    (vector? value) (mapcat string-leaves value)
    (set? value) (mapcat string-leaves value)
    :else []))

(defn- max-hash-run-after
  "Longest unbroken `#` run immediately after MARKER in S."
  [^String s ^String marker]
  (let [length (.length s)
        marker-length (.length marker)]
    (loop [from 0 max-run 0]
      (let [at (.indexOf s marker from)]
        (if (neg? at)
          max-run
          (let [end (+ at marker-length)
                after (loop [k end]
                        (if (and (< k length) (= \# (.charAt s k)))
                          (recur (inc k))
                          k))
                run (- after end)]
            (recur (inc at) (max max-run run))))))))

(defn- rendered-fence
  "Classify the top-level string rendering and return its hash count."
  [^String rendered]
  (let [n (count (take-while #(= \# %) rendered))
        hashes (apply str (repeat n \#))]
    (cond
      (str/starts-with? rendered (str hashes "\"\"\"")) {:kind :block :n n}
      (str/starts-with? rendered (str hashes "\"")) {:kind :single :n n}
      :else {:kind :plain :n 0})))

(defn- fence-text
  [{:keys [kind n]}]
  (case kind
    :block (str (apply str (repeat n \#)) "\"\"\"")
    :single (str (apply str (repeat n \#)) "\"")
    :plain "\""))

(defn- role-prompt
  [role holder?]
  ((deref #'prompts/role-prompt) role holder?))

(defn- prompt-entries
  "Table of [label text] pairs; order is stable enough to read a failure."
  []
  (let [scope-schema (deref #'prompts/scope-schema)
        program-tool (deref #'prompts/program-tool)
        roles [:clj-plan :clj-review :clj-worker :clj-steward :clj-meditator]]
    (concat
     [["common" prompts/common]
      ["plan-prompt" prompts/plan-prompt]
      ["reviewer-prompt" prompts/reviewer-prompt]
      ["swarm-worker-prompt" prompts/swarm-worker-prompt]
      ["holder-prompt" prompts/holder-prompt]
      ["steward-prompt" prompts/steward-prompt]
      ["meditator-prompt" prompts/meditator-prompt]]
     (for [role roles
           :let [value (role-prompt role false)]
           :when (string? value)]
       [(str "role-prompt " role) value])
     [[(str "role-prompt :clj-worker holder")
       (role-prompt :clj-worker true)]]
     (map-indexed (fn [index value]
                    [(str "scope-schema string " index) value])
                  (string-leaves (scope-schema)))
     (mapcat (fn [role]
               (->> (string-leaves (program-tool role false))
                    (map-indexed
                     (fn [index value]
                       [(str "program-tool " role " string " index) value]))))
             [:clj-plan :clj-review :clj-worker :clj-steward]))))

(deftest prompt-strings-roundtrip-without-escape-noise
  (doseq [[label text] (prompt-entries)]
    (testing (str label " (" (count text) " chars)")
      (let [rendered (ufss/write-str text)
            fence (rendered-fence rendered)
            read-back (try
                        (ufss/read-str rendered)
                        (catch Throwable error error))
            max-single (max-hash-run-after text "\"")
            max-triple (max-hash-run-after text "\"\"\"")]
        (is (string? text) (str label ": prompt value is not a string"))
        (is (= text read-back)
            (str label ": roundtrip mismatch, fence=" (fence-text fence)))
        ;; No JSON-style escaping noise: raw quotes, raw newlines.
        (is (not (str/includes? rendered "\\\""))
            (str label ": rendered output contains an escaped quote"))
        (is (not (str/includes? rendered "\\n"))
            (str label ": rendered output contains a newline escape"))
        (is (not (str/includes? rendered "\\t"))
            (str label ": rendered output contains a tab escape"))
        (is (not (str/includes? rendered "\\\\"))
            (str label ": rendered output contains a backslash escape"))
        ;; The delimiter must be longer than any delimiter-like run in the
        ;; text, so the first real terminator closes the value exactly.
        (if (= :block (:kind fence))
          (is (or (not (str/includes? text "\"\"\""))
                  (> (:n fence) max-triple))
              (str label ": block fence does not clear content triple quotes; "
                   "fence=" (fence-text fence)))
          (is (or (zero? (:n fence)) (> (:n fence) max-single))
              (str label ": single-line fence does not clear quote-hash run; "
                   "fence=" (fence-text fence))))
        (when (and (str/includes? text "#\"")
                   (= :single (:kind fence)))
          (is (>= (:n fence) 2)
              (str label ": #\" needs at least ##\"; fence="
                   (fence-text fence))))
        (when (str/includes? text "#\"\"\"")
          (is (>= (:n fence) 1)
              (str label ": #\"\"\" needs a longer block fence; fence="
                   (fence-text fence))))))))

(deftest control-characters-use-the-escaped-string-fallback
  ;; §6.3: control characters are the one case where the printer may fall
  ;; back to a standard escaped string.  This is the negative capability that
  ;; keeps the "no escape noise" rule from being misread as "never escape".
  (doseq [[label text fragment]
          [["carriage-return" "left\rright" "\\r"]
           ["nul" "left\u0000right" "\\u0000"]]]
    (testing label
      (let [rendered (ufss/write-str text)]
        (is (= text (ufss/read-str rendered))
            (str label ": control-character roundtrip failed"))
        (is (str/starts-with? rendered "\"")
            (str label ": control text must use the escaped string form"))
        (is (not (str/starts-with? rendered "#"))
            (str label ": control text must not use a raw fence"))
        (is (str/includes? rendered fragment)
            (str label ": expected escape " fragment " in " (pr-str rendered)))
        (is (str/includes? rendered "\\")
            (str label ": expected a backslash escape"))))))

(deftest prompts-source-never-uses-pr-str
  ;; AGENTS.md §6.3: model-facing presentation uses ufss/write-str, never
  ;; Clojure's pr-str.  This is a mechanical guard, not a style preference.
  (let [source (slurp (io/resource "hc/server/app/prompts.clj"))
        hits (keep-indexed
              (fn [index line]
                (when (str/includes? line "pr-str")
                  (inc index)))
              (str/split-lines source))]
    (is (empty? hits)
        (str "prompts.clj uses pr-str at lines " (pr-str hits)))))
