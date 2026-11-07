(ns hc.client.appearance.model
  "Pure appearance and accessibility preferences.

  The module owns only serializable values: theme, font scale, contrast, and
  reduced motion.  It does not touch the DOM, localStorage, or Fulcro.  A host
  may use it from the browser workbench, a settings panel, or a test."
  (:require [clojure.string :as str]))

(def themes [:system :light :dark])
(def contrasts [:normal :high])
(def reduced-motions [:system :reduce :no-preference])
(def min-font-scale 80)
(def max-font-scale 200)
(def font-step 10)

(def default
  {:theme :system
   :font-scale 100
   :contrast :normal
   :reduced-motion :system})

(defn- keyword-or-nil
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else nil))

(defn- clamp
  [n low high]
  (max low (min high n)))

(defn- parse-number
  [value]
  (cond
    (number? value) value
    (string? value) (try
                      #?(:clj (Double/parseDouble value)
                         :cljs (js/parseFloat value))
                      (catch #?(:clj Exception :cljs :default) _ nil))
    :else nil))

(defn- round-number
  [value]
  #?(:clj (Math/round (double value))
     :cljs (js/Math.round value)))

(defn normalize-font-scale
  "Clamp and round a font scale to the supported 10% grid."
  [value]
  (let [n (long (or (parse-number value) 100))
        stepped (* font-step (long (round-number (/ (double n) font-step))))]
    (clamp stepped min-font-scale max-font-scale)))

(defn normalize-theme
  [value]
  (let [value (keyword-or-nil value)]
    (if (contains? (set themes) value) value :system)))

(defn theme-attribute
  "Return the document `data-theme` value.

  The workbench stylesheet uses `auto` for the system-following theme; the
  model keeps the clearer `:system` keyword."
  [value]
  (let [theme (normalize-theme value)]
    (if (= :system theme) "auto" (name theme))))

(defn normalize-contrast
  [value]
  (let [value (keyword-or-nil value)]
    (if (contains? (set contrasts) value) value :normal)))

(defn normalize-reduced-motion
  [value]
  (cond
    (contains? #{:system :reduce :no-preference} (keyword-or-nil value))
    (keyword-or-nil value)

    (true? value) :reduce

    ;; `false` is the legacy default in the workbench database.  Treat it as
    ;; system-following; an explicit opt-out is stored as `:no-preference`.
    (false? value) :system

    :else :system))

(defn normalize
  "Return a complete, valid appearance state.  Unknown values fall back to
  the documented defaults; a partial map is not an error."
  [state]
  (let [state (if (map? state) state {})]
    {:theme (normalize-theme (:theme state))
     :font-scale (normalize-font-scale (:font-scale state))
     :contrast (normalize-contrast (:contrast state))
     :reduced-motion (normalize-reduced-motion
                      (if (contains? state :reduced-motion)
                        (:reduced-motion state)
                        (:reduced-motion? state)))}))

(defn from-db
  "Read appearance values from a workbench/Fulcro database."
  [db]
  (normalize {:theme (:ui/theme db)
              :font-scale (:ui/font-scale db)
              :contrast (:ui/contrast db)
              :reduced-motion (:ui/reduced-motion db)}))

(defn to-db
  "Return the workbench/Fulcro keys for a normalized appearance state."
  [state]
  (let [state (normalize state)]
    {:ui/theme (:theme state)
     :ui/font-scale (:font-scale state)
     :ui/contrast (:contrast state)
     :ui/reduced-motion (:reduced-motion state)}))

(defn to-ui
  "Return the flat keys used by the workbench `set-appearance` mutation."
  [state]
  (let [state (normalize state)]
    {:theme (:theme state)
     :font-scale (:font-scale state)
     :contrast (:contrast state)
     :reduced-motion? (:reduced-motion state)}))

(defn action?
  "True for an appearance action descriptor."
  [action]
  (and (map? action)
       (= "appearance" (namespace (:type action)))))

(defn set-theme
  [state theme]
  (let [state (normalize state)
        theme (normalize-theme theme)]
    (if (= theme (:theme state))
      state
      (assoc state :theme theme))))

(defn set-font-scale
  [state value]
  (let [state (normalize state)
        value (normalize-font-scale value)]
    (if (= value (:font-scale state))
      state
      (assoc state :font-scale value))))

(defn reset-font
  "Reset only the font scale to 100%, leaving theme/contrast/motion alone."
  [state]
  (let [state (normalize state)]
    (if (= 100 (:font-scale state))
      state
      (assoc state :font-scale 100))))

(defn step-font
  [state direction]
  (let [state (normalize state)
        delta (if (= :increase direction) font-step (- font-step))]
    (set-font-scale state (+ (:font-scale state) delta))))

(defn set-contrast
  [state value]
  (let [state (normalize state)
        value (if (boolean? value)
                (if value :high :normal)
                (normalize-contrast value))]
    (if (= value (:contrast state))
      state
      (assoc state :contrast value))))

(defn set-reduced-motion
  [state value]
  (let [state (normalize state)
        value (normalize-reduced-motion value)]
    (if (= value (:reduced-motion state))
      state
      (assoc state :reduced-motion value))))

(defn reduce-action
  "Apply one appearance action.

  Returns `{:state next :effects [{:effect :apply} {:effect :persist}]}`.
  Effects are values; the host decides how to apply and persist them."
  [state action]
  (let [state (normalize state)
        next (case (:type action)
               :appearance/set-theme
               (set-theme state (:theme action))

               :appearance/set-font-scale
               (set-font-scale state (:value action))

               :appearance/reset-font
               (reset-font state)

               :appearance/increase-font
               (step-font state :increase)

               :appearance/decrease-font
               (step-font state :decrease)

               :appearance/set-contrast
               (set-contrast state (:value action))

               :appearance/set-reduced-motion
               (set-reduced-motion state (:value action))

               :appearance/reset
               default

               state)]
    {:state next
     :effects (if (= next state)
                []
                [{:effect :apply} {:effect :persist}])}))
