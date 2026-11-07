(ns hc.client.workbench.routing
  "One hash router for the workbench.

  The Fulcro database holds `:ui/route`; the URL hash is the only external
  navigation source.  There is no second watcher synchronizing two navigation
  models: `navigate!` writes both once, and `hashchange` reads the hash back."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]))

(defonce listener
  (atom nil))

(defonce pop-listener
  (atom nil))

(defn- current-hash []
  (or (.-hash js/window.location) ""))

(defn- apply-hash!
  [app hash]
  (let [{:keys [route session-id]} (model/parse-hash hash)]
    (comp/transact! app
                    [(mutations/set-route {:route route
                                           :session-id session-id})])
    (application/schedule-render! app)))

(defn start!
  "Install the single hash listener and apply the current location."
  [app]
  (when @listener
    (.removeEventListener js/window "hashchange" @listener true))
  (when @pop-listener
    (.removeEventListener js/window "popstate" @pop-listener true))
  (let [handler (fn [_]
                  ;; Applying the same route twice is idempotent.  Never
                  ;; suppress a real hash change: direct links, back/forward,
                  ;; and the sidebar all use this one path.
                  (apply-hash! app (current-hash)))
        pop-handler (fn [_]
                      (apply-hash! app (current-hash)))]
    (reset! listener handler)
    (reset! pop-listener pop-handler)
    ;; Capture phase: Framework7 may install a later window listener that
    ;; stops immediate propagation once a page is mounted.
    (.addEventListener js/window "hashchange" handler true)
    (.addEventListener js/window "popstate" pop-handler true)
    (if (str/blank? (current-hash))
      (do
        (.replaceState js/window.history nil ""
                       (str (.-pathname js/window.location)
                            (model/route->hash :sessions)))
        (apply-hash! app (model/route->hash :sessions)))
      (apply-hash! app (current-hash)))
    nil))

(defn stop!
  []
  (when @listener
    (.removeEventListener js/window "hashchange" @listener true)
    (reset! listener nil))
  (when @pop-listener
    (.removeEventListener js/window "popstate" @pop-listener true)
    (reset! pop-listener nil))
  nil)

(defn navigate!
  "Navigate to a route.  A nil session ID leaves the current selection
  untouched; selecting a session is explicit."
  [this route session-id]
  (let [hash (model/route->hash route session-id)
        app (comp/any->app this)]
    (comp/transact! this [(mutations/set-route {:route (model/route route)
                                                :session-id session-id})])
    (when app
      (application/schedule-render! app))
    (when (not= hash (current-hash))
      (set! (.-hash js/window.location) hash))
    app))
