(ns hc.client.workbench.visual-invariants-test
  "Cheap text guards for cross-slice visual invariants.

  These tests read source text only.  They prove that a named function, DOM
  landmark, or CSS selector still exists; they do not prove its semantics,
  argument order, or rendering behavior.  Each invariant was added after a
  merge overwrote a slice's work, so the guard intentionally fails on a
  missing or duplicated definition rather than trying to reimplement the
  visual test in the JVM."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util.regex Pattern]))

(def ^:private root
  (or (System/getProperty "hc.project.root")
      (System/getProperty "user.dir")))

(defn- source
  [path]
  (slurp (io/file root path)))

(defn- strip-css-comments
  [text]
  (str/replace text #"(?s)/\*.*?\*/" ""))

(defn- css-source
  [path]
  (strip-css-comments (source path)))

(defn- conversation-min-height-floor?
  [css]
  (boolean
   (re-find #"(?s)\.hc-conversation-messages\s*\{[^}]*min-height:\s*150px"
            css)))

(defn- definition-count
  [text symbol]
  (count (re-seq (re-pattern
                  (str "(?m)^\\s*\\(defn-?\\s+"
                       (Pattern/quote symbol)
                       "\\b"))
                 text)))

(deftest conversation-view-definitions-stay-single
  (let [text (source "src/hc/client/conversation/view.cljs")]
    (testing "each shared or D5 helper is defined exactly once"
      (doseq [symbol ["first-run-guide"
                      "session-title-label"
                      "tool-access-label"
                      "attachment-kind-label"
                      "context-panel"
                      "presence-control"
                      "conversation-surface"]]
        (is (= 1 (definition-count text symbol))
            (str symbol " must have exactly one definition"))))
    (testing "the guide branch and presence mount both survive the merge"
      (is (re-find #"\(first-run-guide\s" text))
      (is (re-find #"\(session-title-label\s" text))
      (is (re-find #"presence-control" (source "src/hc/client/workbench/views/session.cljs"))
          "presence-control is mounted by the session page"))))

(deftest shell-has-one-navigation-landmark
  (let [text (source "src/hc/client/workbench/views/shell.cljs")]
    (is (= 1 (count (re-seq #"\(dom/nav\b" text)))
        "the shell renders exactly one nav element")
    (is (str/includes? text "hc-history-search")
        "the presence/history-search entry must survive")
    (is (str/includes? text "defn session-title-label")
        "the display-only default-title mapping must survive")))

(deftest visual-css-rules-stay-present
  (let [text (css-source "resources/public/css/workbench.css")]
    (is (str/includes? text ".hc-mat-danger-row"))
    (is (str/includes? text ".hc-board-columns"))
    (is (re-find #"@media\s*\(min-width:\s*820px\)\s*and\s*\(max-width:\s*1279px\)"
                 text)
        "the board/files single-column band must keep its 1279px boundary")
    (is (conversation-min-height-floor? text)
        "the conversation scroll area keeps its 150px readable-height floor")
    (testing "comment stripping keeps the rule-block match honest"
      (is (conversation-min-height-floor?
           (strip-css-comments
            ".hc-conversation-messages { /* { min-height: 0 } */ min-height: 150px !important; }"))
          "a comment containing a closing brace must not break the real floor")
      (is (not (conversation-min-height-floor?
                (strip-css-comments
                 ".hc-conversation-messages { min-height: 0; }")))
          "a rule without the 150px floor must still fail"))))
