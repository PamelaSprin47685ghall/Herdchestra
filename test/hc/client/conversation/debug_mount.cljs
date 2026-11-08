(ns hc.client.conversation.debug-mount
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [run-tests]]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.view :as conversation]
            [hc.client.conversation.surface :as surface]))

(defn- act! [f] ((.-act react) f))

(defn -main []
  (let [props {:session {:id "s1" :title "选择回归" :status :running}
               :messages [{:message/id "m1"
                           :message/role :user
                           :message/content "E2E03 长回复"
                           :message/status :completed
                           :message/created-at "2026-11-05T00:00:00Z"}
                          {:message/id "m2"
                           :message/role :assistant
                           :message/content "第一段：可选择的文字。\n\n第二段：继续阅读。"
                           :message/status :streaming
                           :message/created-at "2026-11-05T00:00:01Z"}]
               :draft (assoc draft/empty-draft :text "E2E03 下一条草稿")
               :stream (assoc (stream/initial)
                              :streaming? true
                              :active-message-id "m2")
               :connection {:phase :synced}
               :layout :wide
               :platform :desktop
               :callbacks {:send! (fn [_] nil) :stop! (fn [_] nil)
                           :edit-draft! (fn [_] nil) :scroll! (fn [_] nil)}}
        vm (surface/surface props)
        container (.createElement js/document "div")
        root (rdc/createRoot container)]
    (.appendChild js/document.body container)
    (println "VIEW-MODEL-KEYS:" (pr-str (keys vm)))
    (println "MESSAGES:" (pr-str (mapv #(select-keys % [:id :role :author :segments]) (:messages vm))))
    ;; Mount through the real entry point; wrap conversation-page so we can
    ;; see exactly what React hands it.
    (let [page-fn conversation/conversation-page]
      (act! #(.render root (react/createElement page-fn
                                      #js {:props props}))))
    (println "HTML-LEN:" (count (.-innerHTML container)))
    (println "HAS-HC-MESSAGE:" (.-innerHTML container))
    (.unmount root)
    (.remove container)))
(set! *main-cli-fn* -main)
