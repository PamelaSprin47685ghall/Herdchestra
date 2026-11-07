(ns hc.client.settings.react
  "React adapter for the pure settings tree.

  The component is controlled: state and dispatch come from the caller.
  Unmounting it does not clear a draft because drafts live in the caller's
  state map, not in component-local state."
  (:require [clojure.string :as str]
            [hc.client.settings.view :as view]
            ["react" :as react]))

(defn- attr-name
  [k]
  (case k
    :class "className"
    :for "htmlFor"
    :tabindex "tabIndex"
    :readonly "readOnly"
    :autocomplete "autoComplete"
    (name k)))

(defn- js-value
  [value]
  (cond
    (map? value) (clj->js value)
    (keyword? value) (name value)
    :else value))

(defn- event-name
  [k]
  (case k
    :on-click "onClick"
    :on-change "onChange"
    :on-input "onInput"
    :on-submit "onSubmit"
    :on-key-down "onKeyDown"
    (name k)))

(defn- read-target
  [spec event]
  (case (:value spec)
    :target-value (.. event -target -value)
    :target-checked (.. event -target -checked)
    nil))

(defn- event-handler
  [spec dispatch!]
  (fn [event]
    (when (:prevent-default? spec)
      (.preventDefault event))
    (let [action (cond-> (:action spec)
                   (:value spec) (assoc :value (read-target spec event)))]
      (when dispatch!
        (dispatch! action)))))

(defn- event-props
  [events dispatch!]
  (reduce-kv (fn [object k spec]
               (aset object (event-name k) (event-handler spec dispatch!))
               object)
             #js {}
             (or events {})))

(defn- attr-props
  [attrs]
  (reduce-kv (fn [object k value]
               (aset object (attr-name k) (js-value value))
               object)
             #js {}
             (or attrs {})))

(defn- event-attr?
  [k]
  (and (keyword? k) (str/starts-with? (name k) "on-")))

(defn- node-props
  [node dispatch!]
  (let [all-attrs (:attrs node)
        inline-events (into {} (filter (comp event-attr? key)) all-attrs)
        normal-attrs (into {} (remove (comp event-attr? key)) all-attrs)
        attrs (attr-props normal-attrs)
        events (event-props (merge inline-events (:events node)) dispatch!)]
    (doseq [k (js-keys events)]
      (aset attrs k (aget events k)))
    attrs))

(declare render-node)

(defn- render-children
  [children dispatch!]
  (cond
    (map? children)
    (mapv (fn [[_ child]] (render-node child dispatch!)) children)

    (sequential? children)
    (into [] (keep (fn [child] (render-node child dispatch!))) children)

    :else []))

(defn render-node
  "Render one tree node as a React element."
  [node dispatch!]
  (cond
    (nil? node) nil
    (false? node) nil
    (map-entry? node) (str (pr-str node))
    (string? node) node
    (number? node) node
    (and (sequential? node) (not (map? node)))
    (mapv (fn [child] (render-node child dispatch!)) node)
    (sequential? node) (render-children node dispatch!)
    (and (map? node) (:tag node))
    (let [tag (name (:tag node))
          props (node-props node dispatch!)
          children (render-children (:children node) dispatch!)]
      (apply react/createElement tag props children))

    (map? node)
    (pr-str node)

    :else node))

(defn- prop
  "Read a custom React prop by its JavaScript string key.

  Advanced compilation renames `.-camelCase` property access, but the string
  keys of a `#js` prop object are not renamed.  A component that reads
  `(.-settings props)` therefore sees nil in a release build while the same
  code works in a `:none`/jsdom smoke build."
  [object k]
  (aget object k))

(defn SettingsPage
  "React function component.

  Props: `:settings` state map, `:dispatch!` action callback, and optional
  `:transport-available?` boolean.  The tree also reads the transport flag
  from `[:ui :transport-available?]`; the prop is kept for callers that set
  the flag in state."
  [props]
  (let [state (or (prop props "settings") {})
        dispatch! (or (prop props "dispatch!")
                      (prop props "dispatch_BANG_")
                      (prop props "dispatch"))
        transport? (prop props "transport-available?")
        state (if (some? transport?)
                (assoc-in state [:ui :transport-available?]
                          (boolean transport?))
                state)]
    (render-node (view/settings-tree state) dispatch!)))

(def SettingsView
  "Route-friendly alias for SettingsPage."
  SettingsPage)

(defn settings-element
  "Create a React element for STATE and DISPATCH!."
  ([state dispatch!]
   (settings-element state dispatch! nil))
  ([state dispatch! transport-available?]
   (react/createElement SettingsPage
                        #js {:settings state
                             :dispatch! dispatch!
                             :transport-available? transport-available?})))
