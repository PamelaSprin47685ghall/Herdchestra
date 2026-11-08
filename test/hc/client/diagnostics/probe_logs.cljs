(ns hc.client.diagnostics.probe-logs
  (:require ["react-dom/server" :as rds]
            [hc.client.diagnostics.view :as view]
            [hc.client.diagnostics.fixture :as fixture]))
(defn -main [& _]
  (let [s (assoc (fixture/state) :tab :logs)]
    (js/console.log "LOGS-TAB-BEGIN")
    (js/console.log (rds/renderToStaticMarkup
      (view/diagnostics-element s (fn [_] nil))))
    (js/console.log "LOGS-TAB-END")))
(set! *main-cli-fn* -main)
