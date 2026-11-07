(ns hc.client.schedule.react
  "React adapter for the pure schedule tree.

  Two entry points exist:

  - `SchedulePage` renders a caller-supplied state map.  Tests and embedding
    hosts use it.
  - `ScheduleStorePage` subscribes to the bridge's external store with
    `useSyncExternalStore`, so a receipt can update the page without adding a
    second Fulcro key.  Unmounting only releases the subscription; it never
    cancels a server run."
  (:require [clojure.string :as str]
            [hc.client.schedule.view :as view]
            ["react" :as react]))

(defn- attr-name
  [k]
  (case k
    :class "className"
    :for "htmlFor"
    :tabindex "tabIndex"
    :readonly "readOnly"
    :autocomplete "autoComplete"
    :maxlength "maxLength"
    :minlength "minLength"
    :colspan "colSpan"
    :rowspan "rowSpan"
    :datetime "dateTime"
    (name k)))

(defn- boolean-attr?
  [k]
  (contains? #{:checked :disabled :required :readonly :multiple} k))

(defn- js-value
  [k value]
  (cond
    (boolean-attr? k)
    (if (string? value) (= "true" value) (boolean value))

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
    :on-blur "onBlur"
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
               (aset object (attr-name k) (js-value k value))
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

(defn SchedulePage
  "React function component for a caller-owned state map.

  Props: `:state` schedule state, `:dispatch!` action callback."
  [props]
  (let [state (or (.-state props) {})
        dispatch! (or (aget props "dispatch!")
                      (aget props "dispatch_BANG_")
                      (.-dispatch props))]
    (render-node (view/schedule-tree state) dispatch!)))

(def ScheduleView
  "Route-friendly alias for SchedulePage."
  SchedulePage)

(defn schedule-element
  "Create a React element for STATE and DISPATCH!."
  [state dispatch!]
  (react/createElement SchedulePage
                       #js {:state state
                            :dispatch! dispatch!}))

(defn- use-store
  [store]
  (let [subscribe (.-subscribe store)
        get-snapshot (.-getSnapshot store)]
    (react/useSyncExternalStore subscribe get-snapshot get-snapshot)))

(defn ScheduleStorePage
  "React function component bound to the bridge store.

  Props: `:store` with `subscribe`, `getSnapshot`, and `dispatch`.
  `:db` is the current Fulcro database; the bridge's dispatch reconciles
  catalog, sync phase, and platform in an effect."
  [props]
  (let [store (.-store props)
        db (.-db props)
        state (use-store store)
        dispatch! (.-dispatch store)]
    (react/useEffect
     (fn []
       (dispatch! {:type :schedule/sync-from-db :db db})
       js/undefined)
     #js [db])
    (render-node (view/schedule-tree state) dispatch!)))

(defn schedule-store-element
  "Create a React element bound to STORE and the current DB."
  [store db]
  (react/createElement ScheduleStorePage
                       #js {:store store
                            :db db}))
