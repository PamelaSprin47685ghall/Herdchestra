(ns hc.client.search.ui-smoke
  "Node smoke for the real React search controls.

  Run:
    clojure -M:cljs:client -co '{:target :nodejs
                                 :output-to \"target/search-ui/main.js\"
                                 :output-dir \"target/search-ui\"
                                 :node-modules-dirs [\"./node_modules\"]}'
      -m hc.client.search.ui-smoke"
  (:require [clojure.string :as str]
            [hc.client.search.model :as model]
            [hc.client.search.view :as view]
            ["react-dom/server" :as react-dom-server]))

(defn- check [condition message]
  (when-not condition
    (throw (js/Error. (str "SMOKE FAIL: " message)))))

(defn- includes? [text needle]
  (str/includes? text needle))

(defn- state []
  (-> model/initial-state
      (model/set-mode :required)
      (model/set-service "brave")
      (assoc :open? true)
      (model/record-run
       {:ok? true
        :status :ok
        :searched? true
        :must-search-failed? false
        :sources [{:label "S1"
                   :title "Alpha"
                   :url "https://example.test/a"
                   :site "example.test"
                   :published-at nil
                   :fetched-at "2026-11-03T10:00:00Z"
                   :snippet "Alpha snippet"
                   :content-status :summary}]})))

(defn- services []
  [{:id "brave" :name "Brave" :kind :brave :selectable? true
    :enabled? true :available? true :label "Brave · brave"}
   {:id "off" :name "Off" :kind :searxng :selectable? false
    :enabled? false :available? true :reason "服务已停用"
    :label "Off · searxng · 服务已停用"}])

(defn -main []
  (let [html (react-dom-server/renderToStaticMarkup
              (view/search-controls
               {:state (assoc-in (state) [:last-run :omitted-source-count] 2)
                :services (services)
                :error nil
                :disabled? false
                :on-change! (fn [_] nil)}))]
    (check (includes? html "联网搜索") "missing search title")
    (check (includes? html "本轮必须搜索") "missing required mode")
    (check (includes? html "aria-pressed=\"true\"") "mode is not pressed")
    (check (includes? html "Brave") "missing service")
    (check (includes? html "value=\"brave\" selected") "selected service is not marked selected")
    (check (not (includes? html "value=\"\" selected")) "unselected placeholder is still selected")
    (check (includes? html "Alpha") "missing source title")
    (check (includes? html "https://example.test/a") "missing source URL")
    (check (includes? html "发布：未知") "unknown publication date is not marked unknown")
    (check (includes? html "抓取：2026-11-03T10:00:00Z") "missing fetched time")
    (check (includes? html "另有 2 条来源未显示") "missing omitted source count")
    (check (includes? html "Alpha snippet") "missing snippet")
    (let [message-html
          (react-dom-server/renderToStaticMarkup
           (view/source-cards
            (model/message-sources
             {:citations [{:title "Cited"
                           :url "https://example.test/cited"
                           :citation-number 1
                           :published-at nil
                           :fetched-at "2026-11-03T11:00:00Z"
                           :snippet "Cited snippet"}]})))]
      (check (includes? message-html "[1]") "citation number missing")
      (check (includes? message-html "发布：未知") "citation date is not unknown")
      (check (includes? message-html "Cited snippet") "citation snippet missing"))
    (println "search ui smoke ok")))
