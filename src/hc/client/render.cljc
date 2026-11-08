(ns hc.client.render
  "Markdown enrichment for message segments.

  The timeline already splits a message body into `:code`, `:math`, `:table`,
  and `:text` segments.  This namespace only classifies those segments and
  detects inline math so the view can label source blocks that have no real
  renderer.  It deliberately keeps no DOM or React dependency: it runs on
  both Clojure (unit tests) and ClojureScript (the browser surface).

  H-07 keeps the degradation explicit: a fenced Mermaid block and a display
  math block are labelled 「图表源码」/「公式源码」 and stay copyable instead
  of being shown as anonymous raw text.  Inline `$…$` spans are wrapped in a
  code element so the reader can see which text is formula source.

  The patterns use JavaScript/Java lookbehind for the inline-math boundary;
  both the CLJ test JVM and the CLJS target support it."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Block kinds

(def ^:private mermaid-langs
  #{"mermaid"})

(defn mermaid?
  "True when a *closed* fenced code segment carries a Mermaid diagram.  The
  language tag is case-insensitive; leading/trailing whitespace is
  tolerated.  An unclosed fence is still streaming source, not a diagram."
  [segment]
  (boolean
   (and (= :code (:type segment))
        (true? (:closed? segment))
        (contains? mermaid-langs
                   (str/lower-case (str/trim (str (:lang segment))))))))

(defn math-block?
  "True for the timeline's closed `:math` segments (display math `$$…$$`).
  Streaming text that never saw its closing delimiter still arrives as
  `:text`, so an unclosed block never reaches this predicate."
  [segment]
  (and (= :math (:type segment)) (true? (:closed? segment))))

(defn block-kind
  "Classify a timeline segment for enrichment.  Returns one of
  `:mermaid`, `:math`, `:code`, `:table`, `:text`, or nil for unknown
  shapes.  Mermaid and math win over the generic code kind."
  [segment]
  (cond
    (mermaid? segment) :mermaid
    (math-block? segment) :math
    (= :code (:type segment)) :code
    (= :table (:type segment)) :table
    (= :text (:type segment)) :text
    :else nil))

;; ---------------------------------------------------------------------------
;; Inline math

(def ^:private inline-math-pattern
  "Match `$…$` spans that look like TeX: non-blank delimiters, at least one
  non-whitespace character inside, and no `$` inside.  The digit guards
  keep currency like `$5 and $6` from matching; the extra dollar guards
  keep `$$…$$` display math from being eaten as two inline spans.
  Lookbehind is supported by both the Clojure JVM and modern browsers."
  #"(?<!\d)(?<!\$)\$([^\s$](?:[^$]*[^\s$])?)\$(?!\d)(?!\$)")

(defn inline-math?
  "True when TEXT contains a `$…$` formula span worth styling."
  [text]
  (boolean (and (string? text) (re-find inline-math-pattern text))))

(defn inline-math-spans
  "Return the matched `$…$` formula bodies in order, without the delimiters."
  [text]
  (when (string? text)
    (mapv second (re-seq inline-math-pattern text))))

;; ---------------------------------------------------------------------------
;; Display labels

(def ^:private block-labels
  {:mermaid "图表源码 · Mermaid 未渲染"
   :math "公式源码 · TeX 原文"})

(defn block-label
  "Reader-facing label for an enriched block.  Returns nil for kinds that
  keep their existing header (plain code, table, text)."
  [kind]
  (get block-labels kind))

(defn copy-label
  "Copy button text for an enriched block."
  [kind]
  (case kind
    :mermaid "复制图表源码"
    :math "复制公式原文"
    "复制代码"))
