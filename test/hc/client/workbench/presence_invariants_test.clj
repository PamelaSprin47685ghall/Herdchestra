(ns hc.client.workbench.presence-invariants-test
  "Cheap text guards for client presence and global history search.

  These tests read source text only.  They prove that a named function or DOM
  id still exists with the expected definition count, and that `main.cljs`
  keeps the Escape branch order (history panel first, then overlay/selection,
  then the stop gesture).  They do not prove argument order, rendering, or
  runtime behavior; those need the real browser tests."
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

(defn- definition-count
  [text symbol]
  (count (re-seq (re-pattern
                  (str "(?m)^\\s*\\(defn-?\\s+"
                       (Pattern/quote symbol)
                       "(?=\\s|\\[|$)"))
                 text)))

(defn- value-definition-count
  [text symbol]
  (count (re-seq (re-pattern
                  (str "(?m)^\\s*\\(def\\s+"
                       (Pattern/quote symbol)
                       "(?=\\s|$)"))
                 text)))

(defn- occurrences
  [text needle]
  (count (re-seq (re-pattern (Pattern/quote needle)) text)))

(defn- index-of
  [text needle]
  (str/index-of text needle))

(deftest shared-client-definitions-stay-single
  (testing "transport keeps one stable three-tier handshake identity"
    (let [text (source "src/hc/client/workbench/transport.cljs")]
      (is (= 1 (definition-count text "stable-client-id")))
      (is (= 1 (value-definition-count text "client-id-storage-key")))
      (is (str/includes? text "\"hc.client-id\""))
      (is (str/includes? text "sessionStorage"))
      (is (str/includes? text "localStorage"))))

  (testing "presence bridge keeps the one-shot attach API"
    (let [text (source "src/hc/client/workbench/presence_bridge.cljs")]
      (doseq [symbol ["attach-decision" "attempt!" "install!"]]
        (is (= 1 (definition-count text symbol))
            (str symbol " must have exactly one definition")))
      (is (str/includes? text "(session-id, subscription-id)"))
      (is (str/includes? text "attempted"))))

  (testing "observer refusal still points at the takeover action"
    (let [text (source "src/hc/client/conversation.cljc")]
      (is (str/includes? text ":NOT_CONTROLLER"))
      (is (str/includes? text ":action :takeover"))))

  (testing "surface still names controller, observer, and free roles"
    (let [text (source "src/hc/client/conversation/surface.cljc")]
      (is (= 1 (definition-count text "presence-view")))
      (doseq [label ["你正在控制本会话"
                     "另一客户端正在控制本会话"
                     "本会话暂无控制客户端"]]
        (is (str/includes? text label)))))

  (testing "commands keep one definition of each presence/search entry"
    (let [text (source "src/hc/client/workbench/commands.cljs")]
      (doseq [symbol ["attach-client!"
                      "takeover-session!"
                      "search-history!"
                      "search-history-status!"]]
        (is (= 1 (definition-count text symbol))
            (str symbol " must have exactly one definition"))))))

(deftest view-and-history-components-stay-single
  (let [text (source "src/hc/client/conversation/view.cljs")]
    (is (= 1 (definition-count text "presence-control"))
        "presence-control must be defined exactly once")
    (is (= 1 (definition-count text "first-run-guide"))
        "first-run-guide must be defined exactly once")
    (is (str/includes? text "hc-presence-notice"))
    (is (str/includes? text "hc-takeover-control")))

  (let [logic (source "src/hc/client/search/history.cljc")
        view (source "src/hc/client/search/history_view.cljs")]
    (doseq [symbol ["apply-result" "apply-error" "move-selection"
                    "format-hit-context"]]
      (is (= 1 (definition-count logic symbol))
          (str symbol " must have exactly one definition")))
    (is (str/includes? view "#hc-history-search")
        "the history search entry id is documented by the view")
    (is (str/includes? view "Escape"))
    (is (str/includes? view "panel-keydown!"))))

(deftest shell-keeps-one-navigation-and-the-history-entry
  (let [text (source "src/hc/client/workbench/views/shell.cljs")]
    (is (= 1 (count (re-seq #"\(dom/nav\b" text)))
        "the shell must render exactly one nav landmark")
    (is (str/includes? text "#hc-history-search")
        "the history-search entry survives the nav landmark merge")
    (is (re-find #":id\s+\"hc-history-search\"" text)
        "the explicit entry keeps its DOM id")
    (is (str/includes? text "defn session-title-label")
        "the display-only default-title mapping survives")
    (is (str/includes? text "session-labels/title-label")
        "the title mapping still delegates to the shared labels namespace")
    (is (= 1 (count (re-seq #"history/panel-element" text)))
        "the history panel is mounted exactly once")))

(deftest main-keeps-history-shortcut-and-escape-order
  (let [text (source "src/hc/client/workbench/main.cljs")
        open (index-of text "history/open!")
        close (index-of text "history/close!")
        overlay (index-of text ":close-overlay")
        clear (index-of text ":clear-selection")
        stop (index-of text "commands/stop-run!")]
    (testing "Ctrl/Cmd+Shift+F opens the real history panel"
      (is (some? open))
      (is (re-find #"(?s)\(and meta\? shift\? \(= \"f\" lower\)\).*?history/open!"
                   text))
      (is (not (str/includes? text "全局搜索尚未接入"))
          "the old not-wired notice is gone"))
    (testing "Escape closes history before overlay, selection, and stop"
      (is (some? (index-of text "history/open?")))
      (is (= 1 (occurrences text "history/open!")))
      (is (= 1 (occurrences text "history/close!")))
      (is (and close overlay clear stop)
          "all Escape branches must exist")
      (when (and close overlay clear stop)
        (is (< close overlay clear stop)
            "history close must precede overlay/selection/stop")))))

(deftest session-load-earlier-binds-the-oldest-message-explicitly
  (let [text (source "src/hc/client/workbench/views/session.cljs")
        squashed (str/replace text #"\s+" " ")]
    (is (str/includes? text "加载更早的消息"))
    (is (str/includes? text "commands/fetch-history-window!"))
    (testing "no undeclared % inside some->>"
      (is (not (str/includes? squashed ":before (some->> messages first"))))
    (testing "the oldest loaded message is bound before reading its id"
      (is (str/includes?
           squashed
           (str ":before (let [oldest (first messages)] "
                "(or (:id oldest) (:message-id oldest)))"))))))
