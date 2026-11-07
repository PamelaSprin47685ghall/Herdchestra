(ns hc.client.diagnostics.browser-smoke
  "Verification-only browser entry for the diagnostics workspace.

  It mounts the real component with a deterministic fixture so headless Chrome
  can inspect the actual DOM and scoped CSS.  It is not part of the production
  build and submits no commands."
  (:require ["react-dom/client" :as rdom]
            [hc.client.diagnostics.fixture :as fixture]
            [hc.client.diagnostics.view :as view]))

(defn ^:export init
  []
  (let [element (.getElementById js/document "app")
        root (rdom/createRoot element)]
    (.render root
             (view/diagnostics-element
              (fixture/state)
              (fn [_] nil)))))

(set! *main-cli-fn* init)
