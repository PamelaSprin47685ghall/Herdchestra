(ns hc.client.conversation.scroll-restore-test
  "P-BENCH2 route-switch scroll restoration.

  The server half of P-BENCH2 is session_scale_test.  This harness covers the
  client half: a reader at message 950 of a 2000-message conversation switches
  route (the surface unmounts) and returns.  The restored viewport must show
  the same message id, not the bottom of the newly built window.

  jsdom has no layout engine, so the harness installs a deterministic
  prototype: every message is MESSAGE-HEIGHT pixels tall, the scroller is
  VIEWPORT-HEIGHT tall, and scrollTop clamps to the real max.  The pure
  anchor helpers are also tested without DOM."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.view :as view]))

(def ^:private total-messages 2000)
(def ^:private anchor-index 950)
(def ^:private message-height 20)
(def ^:private viewport-height 600)
(def ^:private bottom-id "m1999")

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

(defn- scroller?
  [element]
  (boolean
   (and element
        (some? (.-classList element))
        (.contains (.-classList element) "hc-conversation-messages"))))

(defn- message-node?
  [element]
  (boolean (and element (.getAttribute element "data-message-id"))))

(defn- install-layout!
  "Give jsdom a deterministic vertical layout for the conversation scroller."
  []
  (let [proto (.-prototype js/HTMLElement)]
    (js/Object.defineProperty
     proto "scrollTop"
     #js {:configurable true
          :get (fn [] (this-as this (double (or (.-hcScrollTop this) 0))))
          :set (fn [value]
                 (this-as this
                   (let [max-top (max 0 (- (.-scrollHeight this)
                                           (.-clientHeight this)))]
                     (set! (.-hcScrollTop this)
                           (max 0 (min (double value) (double max-top)))))))})
    (js/Object.defineProperty
     proto "scrollHeight"
     #js {:configurable true
          :get (fn []
                 (this-as this
                   (if (scroller? this)
                     (* message-height
                        (.-length (.querySelectorAll
                                   this "[data-message-id]")))
                     0)))})
    (js/Object.defineProperty
     proto "clientHeight"
     #js {:configurable true
          :get (fn []
                 (this-as this
                   (if (scroller? this) viewport-height 0)))})
    (js/Object.defineProperty
     proto "getBoundingClientRect"
     #js {:configurable true
          :value (fn []
                   (this-as this
                     (cond
                       (scroller? this)
                       #js {:top 0 :left 0 :width 0
                            :height viewport-height}

                       (message-node? this)
                       (let [scroller (.closest this ".hc-conversation-messages")
                             nodes (array-seq
                                    (.querySelectorAll
                                     scroller "[data-message-id]"))
                             index (.indexOf (to-array nodes) this)
                             scroll-top (.-scrollTop scroller)]
                         #js {:top (- (* index message-height) scroll-top)
                              :left 0 :width 0 :height message-height})

                       :else
                       #js {:top 0 :left 0 :width 0 :height 0})))})))

(defn- act!
  [f]
  ((.-act react) f))

(defn- message-props
  [index]
  {:message/id (str "m" index)
   :message/role (if (even? index) :user :assistant)
   :message/content (str "消息 " index " 的可读正文。")
   :message/status :completed
   :message/created-at "2026-11-09T00:00:00Z"})

(defn- base-props
  [session-id scroll-top]
  {:session {:id session-id :title "P-BENCH2 滚动恢复" :status :running}
   :messages (mapv message-props (range total-messages))
   :draft draft/empty-draft
   :stream (-> (stream/initial)
               (assoc :streaming? false
                      :follow? false
                      :scroll-top scroll-top))
   :connection {:phase :synced}
   :layout :wide
   :platform :desktop
   :callbacks {:send! (fn [_] nil)
               :stop! (fn [_] nil)
               :edit-draft! (fn [_] nil)
               :scroll! (fn [_] nil)}})

(defn- mount!
  [props on-scroll!]
  (let [container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)
        props (assoc-in props [:callbacks :scroll!]
                        (fn [payload]
                          (when on-scroll! (on-scroll! payload))))]
    (act! #(.render root
                    (react/createElement
                     view/conversation-page
                     #js {:props props})))
    {:container container :root root}))

(defn- unmount!
  [{:keys [container root]}]
  (act! #(.unmount root))
  (.remove container))

(defn- scroller
  [container]
  (.querySelector container ".hc-conversation-messages"))

(defn- expand-all!
  [container]
  (dotimes [_ 12]
    (when-let [button (.querySelector container ".hc-message-more button")]
      (act! #(.click button)))))

(defn- dispatch-scroll!
  [container]
  (let [node (scroller container)]
    (act! #(.dispatchEvent node (js/Event. "scroll" #js {:bubbles true})))))

(defn- top-visible-id
  [container]
  (let [node (scroller container)]
    (some (fn [element]
            (let [rect (.getBoundingClientRect element)]
              (when (> (+ (.-top rect) (.-height rect)) 0)
                (.getAttribute element "data-message-id"))))
          (array-seq (.querySelectorAll node "[data-message-id]")))))

(defn- scroll-bottom
  [container]
  (let [node (scroller container)]
    (- (.-scrollHeight node) (.-clientHeight node))))

(deftest anchor-helpers-are-message-id-based
  (let [rendered (mapv (fn [index]
                         {:message-id (str "m" index)
                          :top (* index message-height)
                          :height message-height})
                       (range 10))]
    (testing "the first visible message wins over raw pixels"
      (is (= {:message-id "m3" :delta 5}
             (view/scroll-anchor-at 65 rendered)))
      (is (= {:message-id "m3" :delta 0}
             (view/scroll-anchor-at 60 rendered)))
      (is (= {:message-id "m0" :delta 0}
             (view/scroll-anchor-at 0 rendered)))
      (is (nil? (view/scroll-anchor-at 0 []))))
    (testing "the anchor target survives a changed window slice"
      (let [expanded (mapv (fn [position]
                             {:message-id (str "m" (+ 750 position))
                              :top (* position message-height)
                              :height message-height})
                           (range 1250))]
        (is (= 4000.0
               (view/scroll-restore-target
                {:message-id "m950" :delta 0}
                expanded)))
        (is (= 4012.0
               (view/scroll-restore-target
                {:message-id "m950" :delta 12}
                expanded)))))
    (testing "the window size covers the anchor and never shrinks"
      (is (= 1250 (view/scroll-window-size 2000 950 200)))
      (is (= 300 (view/scroll-window-size 2000 1900 200)))
      (is (= 2000 (view/scroll-window-size 2000 0 200)))
      (is (= 2000 (view/scroll-window-size 2000 500 2000))))))

(deftest route-switch-restores-the-same-message-anchor
  (let [scrolls (atom nil)
        first-mount (mount! (base-props "s1" nil)
                            #(reset! scrolls %))]
    (try
      (expand-all! (:container first-mount))
      (let [node (scroller (:container first-mount))]
        (is (= total-messages
               (count (array-seq
                       (.querySelectorAll node "[data-message-id]"))))
            "all 2000 messages are rendered for the deep-scroll fixture")
        (is (= (* total-messages message-height)
               (.-scrollHeight node))
            (str "the full 2000-message window is rendered, scrollHeight "
                 (.-scrollHeight node)))
        (set! (.-scrollTop node) (* anchor-index message-height))
        (dispatch-scroll! (:container first-mount))
        (is (= (* anchor-index message-height)
               (:scroll-top @scrolls)))
        (is (= (str "m" anchor-index)
               (:anchor-message-id @scrolls))
            "the scroll event records the message id, not only pixels")
        (is (= (str "m" anchor-index)
               (top-visible-id (:container first-mount)))))
      (finally
        (unmount! first-mount)))
    (let [saved (:scroll-top @scrolls)
          second-mount (mount! (assoc-in (base-props "s1" saved)
                                         [:stream :follow?] false)
                               #(reset! scrolls %))]
      (try
        (let [node (scroller (:container second-mount))
              restored-id (top-visible-id (:container second-mount))
              restored-index (when restored-id
                               (js/parseInt (subs restored-id 1) 10))]
          (is (= (str "m" anchor-index) restored-id)
              (str "restored top message " restored-id
                   ", expected m" anchor-index))
          (is (<= (js/Math.abs (- restored-index anchor-index)) 1)
              "the restored message is within one row of the anchor")
          (is (not= (.-scrollTop node) (scroll-bottom (:container second-mount)))
              (str "restored scrollTop " (.-scrollTop node)
                   " equals the new bottom " (scroll-bottom (:container second-mount))))
          (is (not= bottom-id restored-id)
              "the route switch did not fall to the last message"))
        (finally
          (unmount! second-mount))))))

(deftest streaming-does-not-pull-a-reader-back-to-the-bottom
  (let [scrolls (atom nil)
        mount (mount! (base-props "s2" nil) #(reset! scrolls %))]
    (try
      (let [node (scroller (:container mount))]
        (set! (.-scrollTop node) 1000)
        (dispatch-scroll! (:container mount))
        (let [before-scroll (.-scrollTop node)
              before-id (top-visible-id (:container mount))
              props (-> (base-props "s2" 1000)
                        (assoc-in [:stream :follow?] false)
                        (assoc-in [:stream :streaming?] true)
                        (assoc-in [:stream :content-version] 1)
                        (update-in [:stream :scroll-request] inc))]
          (act! #(.render (:root mount)
                          (react/createElement
                           view/conversation-page
                           #js {:props props})))
          (is (= before-scroll (.-scrollTop node))
              "a detached reader keeps the scroll position during a stream")
          (is (= before-id (top-visible-id (:container mount)))
              "a detached reader keeps the visible message during a stream")
          (is (not= (.-scrollTop node)
                    (scroll-bottom (:container mount))))))
      (finally
        (unmount! mount)))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests]
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (setup-dom!)
  (install-layout!)
  (run-tests 'hc.client.conversation.scroll-restore-test))

(set! *main-cli-fn* -main)
