(ns hc.client.conversation.render-dom-test
  "Real React DOM regression for H-07 message render enrichment.

  A message containing a fenced Mermaid block, display math, and inline
  `$…$` math must render labelled degradation blocks — 「图表源码」 and
  「公式源码」 — with copyable source, not anonymous raw text.  This harness
  mounts the real conversation surface in jsdom.

  This is verification-only; it is not part of the production build."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.view :as conversation]))

(defn- act!
  [f]
  ((.-act react) f))

(defn- base-props
  [content]
  {:session {:id "s1" :title "渲染" :status :running}
   :messages [{:message/id "m1"
               :message/role :assistant
               :message/content content
               :message/status :completed
               :message/created-at "2026-11-08T00:00:00Z"}]
   :draft draft/empty-draft
   :stream (assoc (stream/initial) :streaming? false)
   :connection {:phase :synced}
   :layout :wide
   :platform :desktop
   :callbacks {:send! (fn [_] nil)
               :stop! (fn [_] nil)
               :edit-draft! (fn [_] nil)
               :scroll! (fn [_] nil)}})

(defn- mount!
  [content]
  (let [container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (act! #(.render root
                    (react/createElement conversation/conversation-page
                                             #js {:props (base-props content)})))
    {:container container :root root}))

(defn- unmount!
  [{:keys [container root]}]
  (act! #(.unmount root))
  (.remove container))

(def ^:private rich-content
  (str "下面是一张图：\n\n"
       "```mermaid\n"
       "graph TD\n"
       "  A[开始] --> B{判断}\n"
       "  B -->|是| C[结束]\n"
       "```\n\n"
       "面积公式：\n"
       "$$S = \\pi r^2$$\n\n"
       "行内公式 $E=mc^2$ 也成立。\n"))

(deftest mermaid-block-is-labelled-degradation
  (let [{:keys [container root]} (mount! rich-content)]
    (try
      (let [code-blocks (array-seq (.querySelectorAll container ".hc-code"))
            mermaid (first (filter #(str/includes? (.-textContent %) "Mermaid")
                                   code-blocks))]
        (is (some? mermaid) "the mermaid fence renders a code block")
        (is (str/includes? (.-textContent mermaid) "图表源码")
            "the block carries the explicit 图表源码 label")
        (is (str/includes? (.-textContent mermaid) "graph TD")
            "the diagram source stays visible")
        (is (some? (.querySelector mermaid "button"))
            "the block offers a copy button"))
      (finally
        (unmount! {:container container :root root})))))

(deftest display-math-block-is-labelled-degradation
  (let [{:keys [container root]} (mount! rich-content)]
    (try
      (let [math (.querySelector container ".hc-math")]
        (is (some? math) "display math renders a math block")
        (is (str/includes? (.-textContent math) "公式源码")
            "the block carries the explicit 公式源码 label")
        (is (str/includes? (.-textContent math) "S = \\pi r^2")
            "the TeX source stays visible")
        (is (some? (.querySelector math ".hc-math-head button"))
            "the block offers a copy button"))
      (finally
        (unmount! {:container container :root root})))))

(deftest inline-math-is-wrapped-not-bare
  (let [{:keys [container root]} (mount! rich-content)]
    (try
      (let [inline (array-seq (.querySelectorAll container ".hc-inline-math"))]
        (is (= 1 (count inline))
            "exactly the inline $E=mc^2$ span is styled")
        (is (str/includes? (.-textContent (first inline)) "E=mc^2")))
      (finally
        (unmount! {:container container :root root})))))

(deftest ordinary-code-block-is-unchanged
  (let [{:keys [container root]} (mount! "```clj\n(+ 1 2)\n```")]
    (try
      (let [code (.querySelector container ".hc-code")]
        (is (some? code))
        (is (str/includes? (.-textContent code) "clj")
            "plain fences keep their language label"))
      (finally
        (unmount! {:container container :root root})))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.conversation.render-dom-test))

(set! *main-cli-fn* -main)
