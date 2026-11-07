(ns hc.core.text-location
  "Public entry point for exact UTF-16 code-unit text location.

  `where` returns a unique `[begin end]` span.  Uniqueness counts every
  literal occurrence, including overlapping ones.  A zero-match or
  multiple-match request returns a park signal with the §4.3 payload.
  `whereAll` returns every non-overlapping span for replacement and never
  parks.

  The package is independent: it requires only Clojure and its own
  namespaces."
  (:require [hc.core.text-location.diagnostic :as diagnostic]
            [hc.core.text-location.nearest :as nearest]
            [hc.core.text-location.scan :as scan]))

;; Bounds fixed by AGENTS.md §4.3.
(def max-distance nearest/default-max-distance)
(def max-diagnostic-lines nearest/default-max-lines)
(def dp-budget nearest/default-dp-budget)
(def max-candidates diagnostic/max-candidates)
(def context-units scan/context-units)
(def max-text-units nearest/max-text-units)

(def park-signal? diagnostic/park-signal?)

(defn where
  "Unique exact UTF-16 slice, or a `:parked` signal with a diagnostic payload.

  Overlapping occurrences count toward uniqueness: `\"aaa\"` has two
  occurrences of `\"aa\"`, at 0 and 1, so `where` parks instead of choosing
  the first."
  [request]
  (let [{:keys [content needle]} (scan/request request)]
    (diagnostic/where content needle)))

(defn whereAll
  "All non-overlapping exact UTF-16 slices as `[[begin end] ...]`.

  The next search starts at the end of the previous match.  This is the
  replacement law; use `where` when you need an unambiguous anchor."
  [request]
  (let [{:keys [content needle]} (scan/request request)]
    (diagnostic/whereAll content needle)))

(defn locate
  "Classify a request as `:unique`, `:none`, or `:ambiguous`.

  The optional second argument overrides diagnostic bounds for tests and
  embedding.  The tool surface uses the §4.3 defaults."
  ([request] (locate request {}))
  ([request opts]
   (let [{:keys [content needle]} (scan/request request)]
     (diagnostic/locate content needle opts))))

(defn nearest
  "Run only the zero-match nearest-line diagnostic.

  Return `{:nearest map|nil :budget-exhausted? bool}`.  The optional second
  argument overrides diagnostic bounds."
  ([request] (nearest request {}))
  ([request opts]
   (let [{:keys [content needle]} (scan/request request)]
     (nearest/nearest-line content needle (diagnostic/normalize-options opts)))))
