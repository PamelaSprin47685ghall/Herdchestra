(ns hc.client.speech.ui-test
  "Real React render tests for the speech message translation panel.

  The panel is opened by default here so the result lookup runs during
  server rendering.  That is the path that previously dereferenced a
  useState map and threw before any network request could start."
  (:require ["react" :as react]
            ["react-dom/server" :as rds]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.speech.ui :as speech-ui]))

(defn- render
  [props]
  (rds/renderToStaticMarkup
   (react/createElement speech-ui/translation-controls props)))

(deftest translation-panel-renders-without-dereferencing-a-map
  (testing "an open panel renders the original text and controls"
    (let [markup (render #js {:messageId "m1"
                              :versionId "v1"
                              :text "Hello world"
                              :initialOpen true})]
      (is (str/includes? markup "原文（不会被覆盖）"))
      (is (str/includes? markup "Hello world"))
      (is (str/includes? markup "目标语言"))
      (is (str/includes? markup "翻译"))))
  (testing "the panel stays closed until the operator asks for it"
    (let [markup (render #js {:messageId "m1"
                              :versionId "v1"
                              :text "Hello world"})]
      (is (not (str/includes? markup "原文（不会被覆盖）"))))))


(def ^:private translation-result
  {:translated-text "译文"
   :fragment? true
   :source-at-request "Hello world!"
   :request-start 6
   :request-end 11
   :request-text "world"
   :source-language "en"
   :target-language "en"
   :service {:id "svc"}})

(defn- translation-props
  [selection]
  #js {:messageId "m1"
       :versionId "v1"
       :text "Hello world!"
       :draftText "Hello world!"
       :selection (clj->js selection)
       :initialOpen true
       :initialResults {"en" translation-result
                        "zh-CN" translation-result}
       :onReplace (fn [_] nil)})

(deftest translation-panel-offers-replace-only-for-the-requested-selection
  (testing "the current selection can replace exactly the requested fragment"
    (let [markup (render (translation-props {:start 6 :end 11 :text "world"}))]
      (is (str/includes? markup "来自草稿选区"))
      (is (str/includes? markup "替换当前选区"))
      (is (not (str/includes? markup "选区已变化")))))
  (testing "a moved selection falls back to copy/insert"
    (let [markup (render (translation-props {:start 0 :end 5 :text "Hello"}))]
      (is (not (str/includes? markup "替换当前选区")))
      (is (str/includes? markup "选区已变化"))))
  (testing "a whole-message translation has no replace target"
    (let [markup (render #js {:messageId "m1"
                              :versionId "v1"
                              :text "Hello world!"
                              :draftText "Hello world!"
                              :selection (clj->js {:start 0 :end 0 :text ""})
                              :initialOpen true
                              :initialResults {}
                              :onReplace (fn [_] nil)})]
      (is (not (str/includes? markup "替换当前选区"))))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.speech.ui-test))

(set! *main-cli-fn* -main)
