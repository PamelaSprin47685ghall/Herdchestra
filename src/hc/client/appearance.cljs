(ns hc.client.appearance
  "Browser application of appearance/accessibility preferences.

  This namespace is deliberately small: the pure model and view live in
  `hc.client.appearance.model` and `hc.client.appearance.view`.  A host calls
  `apply!` after a state change; the function sets document attributes/CSS
  variables and ensures one self-contained style block for high contrast and
  reduced motion.  It never writes localStorage or Fulcro state."
  (:require [hc.client.appearance.model :as model]
            [hc.client.appearance.view :as view]))

(def style-element-id "hc-appearance-a11y")

(def style-text
  (str "html[data-contrast=\"high\"]{"
       "--wb-bg:#ffffff;--wb-surface:#ffffff;--wb-surface-2:#f2f2f2;"
       "--wb-ink:#000000;--wb-ink-muted:#1a1a1a;--wb-line:#000000;"
       "--wb-accent:#003f66;--wb-accent-ink:#ffffff;"
       "--wb-danger:#7a0000;--wb-warn:#4d2e00;--wb-ok:#0b4d24;"
       "--wb-shadow:none;"
       "--f7-label-color:#1a1a1a;--f7-card-bg-color:#ffffff;"
       "--f7-list-item-border-color:#000000;"
       "}"
       "html[data-contrast=\"high\"] .hc-button,"
       "html[data-contrast=\"high\"] input,"
       "html[data-contrast=\"high\"] textarea,"
       "html[data-contrast=\"high\"] select{"
       "border-color:#000000!important;"
       "}"
       "html[data-contrast=\"high\"] .hc-button:focus-visible,"
       "html[data-contrast=\"high\"] input:focus-visible,"
       "html[data-contrast=\"high\"] textarea:focus-visible,"
       "html[data-contrast=\"high\"] select:focus-visible{"
       "outline:3px solid #000000!important;outline-offset:2px;"
       "}"
       "html[data-reduced-motion=\"true\"] *,"
       "html[data-reduced-motion=\"true\"] *::before,"
       "html[data-reduced-motion=\"true\"] *::after{"
       "scroll-behavior:auto!important;"
       "transition:none!important;"
       "animation:none!important;"
       "}"
       "@media (prefers-reduced-motion: reduce){"
       "html[data-reduced-motion=\"auto\"] *,"
       "html[data-reduced-motion=\"auto\"] *::before,"
       "html[data-reduced-motion=\"auto\"] *::after{"
       "scroll-behavior:auto!important;"
       "transition:none!important;"
       "animation:none!important;"
       "}"))

(defn- ensure-style!
  []
  (when (exists? js/document)
    (let [head (.-head js/document)]
      (when-not (.getElementById js/document style-element-id)
        (let [node (.createElement js/document "style")]
          (set! (.-id node) style-element-id)
          (set! (.-textContent node) style-text)
          (.appendChild head node))))))

(defn apply!
  "Apply normalized STATE to the document root.  Safe to call repeatedly."
  [state]
  (when (exists? js/document)
    (let [state (model/normalize state)
          root (.-documentElement js/document)]
      (set! (.. root -dataset -theme) (model/theme-attribute (:theme state)))
      (set! (.. root -dataset -contrast) (name (:contrast state)))
      (set! (.. root -dataset -reducedMotion)
            (case (:reduced-motion state)
              :reduce "true"
              :no-preference "false"
              "auto"))
      (set! (.. root -dataset -language) (name (:language state)))
      (set! (.. root -dataset -background) (name (:background state)))
      (set! (.. root -dataset -bubble) (name (:bubble state)))
      (.setProperty (.-style root) "--wb-font-scale"
                    (str (/ (:font-scale state) 100)))
      (ensure-style!))))

(defn from-db
  [db]
  (model/from-db db))

(defn to-db
  [state]
  (model/to-db state))

(defn to-ui
  [state]
  (model/to-ui state))

(defn settings-tree
  [state]
  (view/settings-tree state))
