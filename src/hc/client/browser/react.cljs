(ns hc.client.browser.react
  "React adapter for the pure browser view tree.

  The component is controlled: server state, address draft, selected node and
  command status live in the caller's Fulcro state.  Unmounting it does not
  clear a draft or claim a browser action completed."
  (:require [clojure.string :as str]
            [hc.client.browser.view :as view]
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

(defn- key-match?
  [spec event]
  (let [expected (:key spec)
        actual (.-key event)]
    (or (nil? expected)
        (= (name expected) actual)
        (and (= :enter expected) (= "Enter" actual))
        (and (= :escape expected) (= "Escape" actual)))))

(defn- event-handler
  [spec dispatch!]
  (fn [event]
    (when (key-match? spec event)
      (when (:prevent-default? spec)
        (.preventDefault event))
      (let [action (cond-> (:action spec)
                     (:value spec) (assoc :value (read-target spec event)))]
        (when dispatch!
          (dispatch! action))))))

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
        event-map (:events all-attrs)
        normal-attrs (-> (into {} (remove (comp event-attr? key)) all-attrs)
                         (dissoc :events))
        attrs (attr-props normal-attrs)
        events (event-props (merge inline-events event-map (:events node))
                            dispatch!)]
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
  "Render one view-tree node as a React element."
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
    (map? node) (pr-str node)
    :else node))

(defn BrowserPanel
  "React function component.  Props: `:state` and `:dispatch!`."
  [props]
  (let [state (or (.-state props) {})
        dispatch! (or (aget props "dispatch!")
                      (aget props "dispatch_BANG_")
                      (.-dispatch props))]
    (render-node (view/browser-panel-tree state) dispatch!)))

(def BrowserView BrowserPanel)

(defn browser-element
  "Create a React element for STATE and DISPATCH!."
  [state dispatch!]
  (react/createElement BrowserPanel #js {:state state :dispatch! dispatch!}))
