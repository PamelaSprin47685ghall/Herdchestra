(ns hc.server.app.prompts-common-verbatim-test
  "Machine guard for the prompt text against root `AGENTS.md` §6.1.

  `COMMON` is copied verbatim from the specification into
  `hc.server.app.prompts/common`.  A human compared the two once; this
  namespace reads `AGENTS.md` from disk on every run and fails with the
  first differing line and its context when they drift.

  `PLAN_PROMPT`, `REVIEWER_PROMPT`, and `SWARM_WORKER_PROMPT` are composed
  text in the specification, so they get key-invariant assertions instead of
  a verbatim comparison."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.prompts :as prompts]))

(def ^:private common-heading "#### `COMMON` 全文")

;; ---------------------------------------------------------------------------
;; Reading and extracting the specification

(defn- slurp-agents
  []
  (try
    (slurp "AGENTS.md")
    (catch java.io.FileNotFoundException _ nil)))

(defn- find-line-index
  [lines pred from]
  (first
   (keep-indexed (fn [index line]
                   (when (and (>= index from) (pred line))
                     index))
                 lines)))

(defn- extract-text-fence
  "Return the body of the first ```text fence after HEADING.

  Returns `{:ok? true :body \"...\"}` or `{:ok? false :reason \"...\"}`.
  A missing heading, a missing opening fence, and an unclosed fence are all
  explicit failures; they are exactly the specification-format drift this
  test must report."
  [markdown heading]
  (let [lines (vec (str/split-lines (str/replace (or markdown "") "\r\n" "\n")))
        heading-index (find-line-index lines
                                       #(str/starts-with? (str/trim %) heading)
                                       0)]
    (cond
      (nil? heading-index)
      {:ok? false
       :reason (str "heading not found: " heading)}

      :else
      (let [open-index (find-line-index lines
                                        #(= "```text" (str/trim %))
                                        (inc heading-index))]
        (cond
          (nil? open-index)
          {:ok? false
           :reason (str "no ```text fence after heading: " heading)}

          :else
          (let [close-index (find-line-index lines
                                             #(= "```" (str/trim %))
                                             (inc open-index))]
            (if (nil? close-index)
              {:ok? false
               :reason (str "unclosed ```text fence after heading: " heading)}
              {:ok? true
               :body (str/join "\n"
                               (subvec lines (inc open-index) close-index))})))))))

;; ---------------------------------------------------------------------------
;; Verbatim normalization and diff context

(defn- normalize-verbatim
  "Trailing whitespace per line, runs of blank lines -> one, outer trim.

  Single newlines stay significant: this is a verbatim guard, not a
  word-level comparison."
  [text]
  (let [lines (map str/trimr
                   (str/split-lines (str/replace (or text "") "\r\n" "\n")))]
    (->> lines
         (reduce (fn [acc line]
                   (if (and (= "" line) (= "" (peek acc)))
                     acc
                     (conj acc line)))
                 [])
         (str/join "\n")
         str/trim)))

(defn- first-diff-index
  [expected actual]
  (let [limit (min (count expected) (count actual))]
    (or (first
         (keep-indexed (fn [index _]
                         (when (not= (nth expected index)
                                     (nth actual index))
                           index))
                       (range limit)))
        (when (not= (count expected) (count actual))
          limit))))

(defn- line-context
  [label lines index]
  (let [count-lines (count lines)
        focus (if (zero? count-lines)
                0
                (min index (dec count-lines)))
        start (max 0 (- focus 3))
        end (min count-lines (+ focus 4))
        body (map (fn [line-index]
                    (format "%5d | %s"
                            (inc line-index)
                            (nth lines line-index)))
                  (range start end))]
    (str label "\n"
         (if (seq body) (str/join "\n" body) "(empty)")
         (when (>= index count-lines)
           (str "\n  (text ends at line " count-lines ")")))))

(defn- diff-message
  [label expected-text actual-text]
  (let [expected (str/split-lines expected-text)
        actual (str/split-lines actual-text)]
    (if-let [index (first-diff-index expected actual)]
      (str label " differs from AGENTS.md at normalized line " (inc index) "\n"
           (line-context "--- AGENTS.md ---" expected index) "\n"
           (line-context "--- prompts/common ---" actual index))
      "")))

;; ---------------------------------------------------------------------------
;; 1. COMMON verbatim

(deftest common-is-verbatim-with-agents-md
  (if-let [markdown (slurp-agents)]
    (let [{:keys [ok? body reason]} (extract-text-fence markdown common-heading)]
      (is (true? ok?) (str "COMMON fence parse failed: " reason))
      (when ok?
        (let [expected (normalize-verbatim body)
              actual (normalize-verbatim prompts/common)]
          (is (= expected actual)
              (diff-message "COMMON" expected actual)))))
    (do
      (println "SKIP: AGENTS.md not found")
      (is true))))

;; ---------------------------------------------------------------------------
;; 2. COMMON Scope shape

(def ^:private common-scope-markers
  [:reset-scope
   :keep-last
   :remove-last
   :me-too
   :new-scope-goal
   :new-scope-retention-budget
   :replacement
   :SCOPE_UNINITIALIZED
   :SCOPE_ANNOTATION_MISSING
   :SCOPE_KIND_INVALID
   :SCOPE_FIELD_INVALID
   :SCOPE_FIELD_UNEXPECTED
   :SCOPE_BUDGET_SPENT
   :SCOPE_RECORD_FAILED
   :SCOPE_TURN_MISSING])

(def ^:private plan-compact-scope-phrases
  ["Every tool call carries flat `:scope`"
   "One true annotation per model turn"
   "budget 0 admits"])

(deftest common-scope-section-is-single-and-full
  (let [text prompts/common
        headings (re-seq #"(?m)^# Scope\s*$" text)]
    (is (= 1 (count headings))
        (str "COMMON must contain exactly one '# Scope' heading, found "
             (count headings)))
    (doseq [marker common-scope-markers]
      (is (str/includes? text (str marker))
          (str "COMMON Scope is missing " marker)))
    (doseq [phrase plan-compact-scope-phrases]
      (is (not (str/includes? text phrase))
          (str "PLAN_PROMPT compact Scope wording leaked into COMMON: "
               phrase)))))

;; ---------------------------------------------------------------------------
;; 3. PLAN_PROMPT invariants

(def ^:private plan-invariants
  [["S1 milestone" ["S1 (Requirements Analysis)"]]
   ["S2 milestone" ["S2 (Implementation Skeleton)"]]
   ["S3 milestone" ["S3 (Parallel Development Methodology)"]]
   ["待执行 handoff" ["待执行 (Ready for Execution)"]]
   ["draft path rule" ["plans/<name>.md" "fixes the path"]]])

(deftest plan-prompt-invariants
  (doseq [[label required] plan-invariants]
    (let [missing (remove #(str/includes? prompts/plan-prompt %) required)]
      (is (empty? missing)
          (str "PLAN_PROMPT " label " is missing: " (pr-str missing)))))
  (let [headings (re-seq #"(?m)^# Scope\s*$" prompts/plan-prompt)]
    (is (= 1 (count headings))
        (str "PLAN_PROMPT must contain exactly one '# Scope' heading, found "
             (count headings)))))

;; ---------------------------------------------------------------------------
;; 4. REVIEWER_PROMPT invariants

(def ^:private reviewer-invariants
  [["review ledger" ["(review {:findings" "obligation"]]
   ["empty review promotes the milestone"
    ["Empty findings" "promote the milestone"]]
   ["initial empty review becomes ask"
    ["On the initial tenure's first empty review" "becomes `(ask"]]])

(deftest reviewer-prompt-invariants
  (doseq [[label required] reviewer-invariants]
    (let [missing (remove #(str/includes? prompts/reviewer-prompt %) required)]
      (is (empty? missing)
          (str "REVIEWER_PROMPT " label " is missing: " (pr-str missing))))))

;; ---------------------------------------------------------------------------
;; 5. SWARM_WORKER_PROMPT invariants

(def ^:private swarm-worker-invariants
  [["split before claim" ["Split before claim"]]
   ["parent never auto-completes" ["never auto-completes it"]]
   ["take-or-die" ["take-or-die"]]
   ["park-slot" ["park-slot"]]
   ["finish does not drain" ["`finish`" "does not drain"]]])

(deftest swarm-worker-prompt-invariants
  (doseq [[label required] swarm-worker-invariants]
    (let [missing (remove #(str/includes? prompts/swarm-worker-prompt %)
                          required)]
      (is (empty? missing)
          (str "SWARM_WORKER_PROMPT " label " is missing: "
               (pr-str missing))))))
