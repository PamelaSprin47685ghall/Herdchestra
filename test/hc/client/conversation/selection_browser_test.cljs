(ns hc.client.conversation.selection-browser-test
  "Real-browser consumer harness for the conversation surface.

  The Node runner mounts this page in Chromium, selects a streamed paragraph,
  appends more streamed text, and checks that the live Range survives.  It also
  simulates the Blink character-data reset and a deliberate composer click, so
  the restore path and its non-resurrection rule are both exercised.

  This namespace is not part of the production build; the `:app` entry never
  requires it."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.view :as conversation]))

(defonce app-state (atom nil))
(defonce root-ref (atom nil))

(defn- base-props
  [content]
  {:session {:id "s1" :title "选择浏览器回归" :status :running}
   :messages [{:message/id "m1"
               :message/role :user
               :message/content "E2E03 长回复"
               :message/status :completed
               :message/created-at "2026-11-05T00:00:00Z"}
              {:message/id "m2"
               :message/role :assistant
               :message/content content
               :message/status :streaming
               :message/created-at "2026-11-05T00:00:01Z"}]
   :draft (assoc draft/empty-draft :text "E2E03 下一条草稿")
   :stream (assoc (stream/initial)
                  :streaming? true
                  :active-message-id "m2"
                  :content-version (count content))
   :connection {:phase :synced}
   :layout :wide
   :platform :desktop
   :callbacks {:send! (fn [_] nil)
               :stop! (fn [_] nil)
               :edit-draft! (fn [_] nil)
               :scroll! (fn [_] nil)}})

(defn- render!
  []
  (when-let [root @root-ref]
    (.render root
             (react/createElement conversation/conversation-page
                                  #js {:props @app-state}))))

(defn ^:export mount!
  [content]
  (let [container (.getElementById js/document "app")
        root (rdc/createRoot container)]
    (reset! app-state (base-props content))
    (reset! root-ref root)
    (render!)
    true))

(defn ^:export append-text!
  [delta]
  (when @app-state
    (swap! app-state
           (fn [props]
             (let [content (str (get-in props [:messages 1 :message/content])
                                delta)]
               (-> props
                   (assoc-in [:messages 1 :message/content] content)
                   (assoc-in [:messages 1 :message/content-version] (count content))
                   (assoc-in [:stream :content-version] (count content))
                   (assoc-in [:stream :streaming?] true)))))
    (render!)
    true))

(defn ^:export collapse-selection!
  []
  (when-let [selection (.getSelection js/window)]
    (.removeAllRanges selection))
  true)

(defn ^:export selection-text
  []
  (str (.getSelection js/window)))

(defn ^:export unmount!
  []
  (when-let [root @root-ref]
    (.unmount root))
  (reset! root-ref nil)
  true)

(defn ^:export init
  []
  (set! (.-hcSelectionTest js/window)
        #js {:mount mount!
             :appendText append-text!
             :collapseSelection collapse-selection!
             :selectionText selection-text
             :unmount unmount!}))
