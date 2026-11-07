(ns hc.client.generated-card.dom-test
  "Real DOM interaction smoke for the generated card.

  This mounts the actual React component with jsdom, types into fields, clicks
  local/send/table/filter buttons, and proves the component never touches
  `fetch`.  It is verification-only; it is not part of the production build."
  (:require ["react" :as react]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.generated-card.view :as view]))

(def form-card
  {:card-id "form-dom"
   :kind :form
   :title "提交月度预算"
   :purpose "本地填写；只有显式发送才交给宿主。"
   :provenance :model
   :form {:fields [{:name :amount :type :number :label "预算金额" :unit "元"
                    :required? true :default 100 :min 0 :max 10000}
                   {:name :date :type :date :label "生效日期" :required? true
                    :default "2026-11-03" :timezone "Asia/Shanghai"}
                   {:name :mode :type :select :label "预算模式" :default "monthly"
                    :options [{:value "monthly" :label "按月"}
                              {:value "yearly" :label "按年"}]}
                   {:name :has-attachment :type :boolean :label "有附件"}
                   {:name :attachment :type :text :label "附件说明"
                    :visible-when {:field :has-attachment :equals true}}
                   {:name :token :type :secret :label "凭据" :required? true}]}})

(def chart-card
  {:card-id "chart-dom"
   :kind :chart
   :title "月度用量"
   :purpose "筛选、排序与数据表。"
   :provenance :user
   :source "meter.csv"
   :show-table? true
   :chart {:type :bar
           :x-label "月份" :y-label "数值"
           :series [{:key :usage :label "用量" :unit "kWh"}]
           :data [{:x "2026-01" :usage 120}
                  {:x "2026-02" :usage nil}
                  {:x "2026-03" :usage 150}]}})

(defn- setup-dom!
  []
  (if (and (exists? js/window) (exists? js/document))
    nil
    (let [jsdom (js/require "jsdom")
          dom (new (.-JSDOM jsdom) "<!doctype html><html><body></body></html>"
                   #js {:url "http://localhost/"})
          window (.-window dom)]
    (set! js/global.window window)
    (set! js/global.document (.-document window))
    (set! js/global.navigator (.-navigator window))
    (set! js/global.HTMLElement (.-HTMLElement window))
    (set! js/global.Node (.-Node window))
    (set! js/global.Event (.-Event window))
    (set! js/global.MouseEvent (.-MouseEvent window))
    (set! js/global.KeyboardEvent (.-KeyboardEvent window))
    (set! js/global.getComputedStyle (.-getComputedStyle window))
    (set! js/global.requestAnimationFrame (fn [f] (js/setTimeout f 0)))
    (set! js/global.cancelAnimationFrame (fn [id] (js/clearTimeout id)))
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/global) true)
      dom)))

(defn- act!
  [f]
  ((.-act react) f))

(defn- render!
  [props]
  (let [container (.createElement js/document "div")
        root ((.-createRoot (js/require "react-dom/client")) container)]
    (.appendChild js/document.body container)
    (act! (fn []
            (.render root (react/createElement view/generated-card #js {:props props}))))
    [container root]))

(defn- cleanup!
  [container root]
  (when container
    (act! (fn [] (.unmount root)))
    (.remove container)))

(defn- buttons
  [root]
  (array-seq (.querySelectorAll root "button")))

(defn- button-by-text
  [root text]
  (first (filter #(= text (str/trim (.-textContent %))) (buttons root))))

(defn- input-by-name
  [root name]
  (.querySelector root (str "input[name='" name "']")))

(defn- simulate-change!
  [node value]
  (let [simulate (.-Simulate (js/require "react-dom/test-utils"))]
    (.change simulate node #js {:target #js {:value value}})))

(defn- click!
  [node]
  (when node
    (act! (fn []
            (.dispatchEvent node (js/MouseEvent. "click" #js {:bubbles true :cancelable true}))))))

(deftest form-local-actions-and-explicit-send
  (let [calls (atom [])
        local-saves (atom [])
        fetch-calls (atom 0)
        original-fetch js/global.fetch
        [container root] (render! {:card form-card
                                   :on-submit! (fn [payload] (swap! calls conj payload))
                                   :on-local-save! (fn [payload] (swap! local-saves conj payload))})]
    (set! js/global.fetch (fn [& _] (swap! fetch-calls inc) (js/Promise.resolve nil)))
    (try
      (testing "local save does not call the host"
        (click! (button-by-text container "仅保存到本卡"))
        (is (str/includes? (.-textContent container) "已保存到本卡"))
        (is (empty? @calls))
        (is (= 1 (count @local-saves)))
        (is (not (contains? (get-in (first @local-saves) [:values]) :token)))
        (is (= [:token] (get-in (first @local-saves) [:excluded-secret-fields]))))
      (testing "invalid send reports the field and does not call the host"
        (act! (fn [] (simulate-change! (input-by-name container "amount") "20000")))
        (click! (button-by-text container "发送给助手"))
        (is (str/includes? (.-textContent container) "不能大于"))
        (is (empty? @calls)))
      (testing "valid send passes only visible non-secret fields"
        (act! (fn [] (simulate-change! (input-by-name container "amount") "500")))
        (click! (button-by-text container "发送给助手"))
        (is (= 1 (count @calls)))
        (is (= 500 (get-in (first @calls) [:values :amount])))
        (is (not (contains? (get-in (first @calls) [:values]) :token)))
        (is (not (contains? (get-in (first @calls) [:values]) :attachment)))
        (is (str/includes? (.-textContent container) "已提交给助手")))
      (testing "Escape exits full screen"
        (click! (button-by-text container "全屏"))
        (is (some? (.querySelector container ".hc-gencard-fullscreen")))
        (act! (fn []
                (let [event (js/Event. "keydown" #js {:bubbles true})]
                  (set! (.-key event) "Escape")
                  (.dispatchEvent js/document event))))
        (is (nil? (.querySelector container ".hc-gencard-fullscreen"))))
      (testing "the component never uses fetch"
        (is (zero? @fetch-calls)))
      (finally
        (set! js/global.fetch original-fetch)
        (cleanup! container root)))))

(deftest chart-filter-reset-and-table-toggle
  (let [fetch-calls (atom 0)
        original-fetch js/global.fetch
        [container root] (render! {:card chart-card})]
    (set! js/global.fetch (fn [& _] (swap! fetch-calls inc) (js/Promise.resolve nil)))
    (try
      (testing "the table starts visible and states the total"
        (is (some? (.querySelector container "table")))
        (is (str/includes? (.-textContent container) "3 / 3 行")))
      (testing "category filter updates chart, summary, and table together"
        (let [select (.querySelector container "select")]
          (act! (fn []
                  (set! (.-value select) "2026-02")
                  (.dispatchEvent select (js/Event. "change" #js {:bubbles true})))))
        (is (str/includes? (.-textContent container) "1 / 3 行"))
        (is (str/includes? (.-textContent container) "缺失")))
      (testing "reset restores all rows without a model request"
        (click! (button-by-text container "重置视图"))
        (is (str/includes? (.-textContent container) "3 / 3 行"))
        (is (zero? @fetch-calls)))
      (testing "the data table can be hidden and shown"
        (click! (button-by-text container "隐藏数据"))
        (is (nil? (.querySelector container "table")))
        (click! (button-by-text container "查看数据"))
        (is (some? (.querySelector container "table"))))
      (finally
        (set! js/global.fetch original-fetch)
        (cleanup! container root)))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (setup-dom!)
  (run-tests 'hc.client.generated-card.dom-test))

(set! *main-cli-fn* -main)
