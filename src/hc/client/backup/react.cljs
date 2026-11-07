(ns hc.client.backup.react
  "React adapter for the pure backup tree.

  State and dispatch come from the caller.  A password is never copied into
  component-local state, so unmounting or re-rendering cannot persist it.

  Password fields use `DraftInput`: the DOM owns the live text, caret,
  selection, and IME composition, while the caller's state receives every
  input.  This is deliberate.  A controlled value plus an asynchronous Fulcro
  render lets React restore the last committed value between fast keystrokes,
  so reading `event.target.value` on the next key loses the earlier text.
  Only an explicit reset key writes the DOM value back."
  (:require [clojure.string :as str]
            [hc.client.backup.view :as view]
            ["react" :as react]))

(defn- attr-name
  [k]
  (case k
    :class "className"
    :for "htmlFor"
    :tabindex "tabIndex"
    :readonly "readOnly"
    :autocomplete "autoComplete"
    :reset-key "resetKey"
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
    :on-composition-end "onCompositionEnd"
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

(defn- DraftInput
  "Uncontrolled text input for drafts that must survive asynchronous renders.

  The DOM owns the visible text, caret, selection, and IME composition.  The
  caller's `:value` seeds the field; it is written back only when `:resetKey`
  changes.  Ordinary renders, server pushes, and sibling updates therefore
  cannot truncate fast typing or replace a focused draft.

  The workbench unmounts the backup panel when the operator leaves the data
  center.  Before unmounting, DOM text that no `input` or `compositionend`
  event has delivered yet is committed to the caller, so a section switch
  cannot discard an in-flight password draft."
  [props]
  (let [node-ref (react/useRef nil)
        ;; React clears a ref on unmount.  Keep the node itself so the
        ;; unmount cleanup can still read the live DOM value.
        last-node-ref (react/useRef nil)
        ;; Custom props are not DOM/React externs.  Advanced compilation
        ;; renames `.-inputAttrs` but not the string key in the `#js` literal
        ;; built by `draft-input-element`; string access keeps them matched.
        initial-value-ref (react/useRef (or (aget props "value") ""))
        last-reset-ref (react/useRef (aget props "resetKey"))
        last-sent-ref (react/useRef (or (aget props "value") ""))
        reset-key-ref (react/useRef (aget props "resetKey"))
        on-input-ref (react/useRef (aget props "onInput"))
        value (or (aget props "value") "")
        reset-key (aget props "resetKey")
        input-attrs (aget props "inputAttrs")
        on-input (aget props "onInput")
        on-composition-end (aget props "onCompositionEnd")]
    ;; Latest committed values for event handlers and the unmount cleanup.
    ;; These refs do not affect render output.
    (set! (.-current reset-key-ref) reset-key)
    (set! (.-current on-input-ref) on-input)
    (let [set-node! (react/useCallback
                     (fn [node]
                       (set! (.-current node-ref) node)
                       (when node
                         (set! (.-current last-node-ref) node)))
                     #js [])
          commit! (fn [next-value]
                    (set! (.-current last-sent-ref) next-value)
                    (when-let [handler (.-current on-input-ref)]
                      (handler #js {:target #js {:value next-value}})))
          flush-unsent! (fn []
                          (when-let [node (.-current last-node-ref)]
                            (let [dom-value (.-value node)
                                  sent (.-current last-sent-ref)]
                              ;; A pending reset means state already holds
                              ;; the authoritative empty value; the old DOM
                              ;; text must not be written back over it.
                              (when (and (not= dom-value sent)
                                         (= (.-current reset-key-ref)
                                            (.-current last-reset-ref)))
                                (commit! dom-value)))))]
      (react/useEffect
       (fn []
         (when-let [node (.-current node-ref)]
           (when (not= reset-key (.-current last-reset-ref))
             (set! (.-value node) value)
             (set! (.-current initial-value-ref) value)
             (set! (.-current last-sent-ref) value)
             (set! (.-current last-reset-ref) reset-key)))
         js/undefined)
       #js [reset-key])
      ;; Blur covers the ordinary click path; the unmount cleanup also covers
      ;; keyboard/route changes and a composition that is still open.
      (react/useEffect
       (fn []
         flush-unsent!)
       #js [])
      (react/createElement
       "input"
       (doto input-attrs
         ;; Freeze the seed after mount.  Later state values reach the DOM only
         ;; through the explicit reset effect below; a changed defaultValue must
         ;; never disturb a pristine input or an IME composition.
         (aset "defaultValue" (.-current initial-value-ref))
         (aset "ref" set-node!)
         (aset "onInput"
               (fn [event]
                 (let [next-value (.. event -target -value)]
                   (set! (.-current last-sent-ref) next-value)
                   (when on-input
                     (on-input event)))))
         (aset "onBlur" (fn [_] (flush-unsent!)))
         (aset "onCompositionEnd"
               (fn [event]
                 ;; Some browsers publish compositionend before the final input
                 ;; event.  Dispatch the settled DOM text so the state cannot
                 ;; miss the last confirmed IME character.  The view supplies
                 ;; the same set-field action; fall back to onInput when it
                 ;; does not.
                 (let [next-value (.. event -target -value)]
                   (set! (.-current last-sent-ref) next-value)
                   (if on-composition-end
                     (on-composition-end event)
                     (when on-input
                       (on-input event)))))))))))

(defn- draft-input-element
  [node dispatch!]
  (let [attrs (node-props node dispatch!)
        value (or (aget attrs "value") "")
        reset-key (aget attrs "resetKey")
        on-input (aget attrs "onInput")
        on-composition-end (aget attrs "onCompositionEnd")]
    (doseq [k ["value" "resetKey" "draft-input"
               "onInput" "onCompositionEnd"]]
      (js-delete attrs k))
    (react/createElement DraftInput
                         #js {:inputAttrs attrs
                              :value value
                              :resetKey reset-key
                              :onInput on-input
                              :onCompositionEnd on-composition-end})))

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
          draft-input? (and (= "input" tag)
                            (:draft-input (:attrs node)))]
      (if draft-input?
        (draft-input-element node dispatch!)
        (let [props (node-props node dispatch!)
              children (render-children (:children node) dispatch!)]
          (apply react/createElement tag props children))))

    (map? node)
    (pr-str node)

    :else node))

(defn BackupPage
  "React function component.

  Props: `:state` backup state map, `:dispatch!` action callback, and optional
  `:transport-available?` boolean.  The tree reads the transport flag from
  `[:ui :transport-available?]`."
  [props]
  (let [state (or (aget props "state") {})
        dispatch! (or (aget props "dispatch!")
                      (aget props "dispatch_BANG_")
                      (aget props "dispatch"))
        transport? (aget props "transport-available?")
        state (if (some? transport?)
                (assoc-in state [:ui :transport-available?]
                          (boolean transport?))
                state)]
    (render-node (view/backup-tree state) dispatch!)))

(def BackupView
  "Route-friendly alias for BackupPage."
  BackupPage)

(defn backup-element
  "Create a React element for STATE and DISPATCH!."
  ([state dispatch!]
   (backup-element state dispatch! nil))
  ([state dispatch! transport-available?]
   (react/createElement BackupPage
                        #js {:state state
                             :dispatch! dispatch!
                             :transport-available? transport-available?})))
