(ns hc.client.conversation.a11y
  "Accessible responsive surface policy.

  Layout is chosen from the logical viewport width, never from a device guess.
  The three bands match the workbench contract:

    360px phone / narrow desktop  -> :compact
    820px tablet                  -> :medium
    1280px desktop                -> :wide

  Escape follows one strict order: close the topmost overlay, then leave the
  key to an input method or text selection, and only then stop generation.
  The model records focus return targets so a closed overlay can restore focus
  to the control that opened it.")

(def layouts
  {:compact {:sidebar :drawer
             :aux-panel :overlay
             :message-padding :small
             :composer :sticky
             :primary-target 44
             :secondary-target 44
             :show-labels? false}
   :medium {:sidebar :collapsible
            :aux-panel :overlay
            :message-padding :normal
            :composer :sticky
            :primary-target 44
            :secondary-target 44
            :show-labels? true}
   :wide {:sidebar :fixed
          :aux-panel :side
          :message-padding :normal
          :composer :docked
          :primary-target 44
          :secondary-target 32
          :show-labels? true}})

(defn layout-for-width
  "Choose the responsive band from a logical pixel width."
  [width]
  (cond
    (nil? width) :wide
    (< width 600) :compact
    (< width 1024) :medium
    :else :wide))

(defn layout
  [band]
  (get layouts (keyword band) (:wide layouts)))

(defn overlay-layout?
  "True when the navigation and detail columns become modal drawers.

  The wide band keeps both columns visible, so an open drawer flag is not an
  overlay there: there is nothing to cover and Escape must not steal focus
  from the page.  The breakpoint matches the workbench stylesheet."
  [width]
  (not= :wide (layout-for-width width)))

(defn target-size
  "Minimum logical hit area.  Primary controls stay at 44 even on desktop;
  secondary desktop controls may use 32."
  ([band] (target-size band :primary))
  ([band kind]
   (let [layout (layout band)]
     (if (= kind :secondary)
       (:secondary-target layout)
       (:primary-target layout)))))

;; ---------------------------------------------------------------------------
;; Overlay and focus stack

(defn top-overlay
  [state]
  (peek (:overlays state)))

(defn open-overlay
  "Push an overlay and record the trigger that must regain focus on close."
  [state overlay]
  (-> state
      (update :overlays conj overlay)
      (assoc :focus {:target (:id overlay)
                     :return-to (:trigger-id overlay)
                     :reason :overlay-opened})))

(defn close-overlay
  "Close the topmost overlay and return focus to its trigger."
  [state]
  (if-let [overlay (top-overlay state)]
    (-> state
        (update :overlays pop)
        (assoc :focus {:target (:trigger-id overlay)
                       :return-to (:trigger-id overlay)
                       :reason :overlay-closed}))
    (assoc state :focus {:target nil :return-to nil :reason :no-overlay})))

(defn close-overlay-id
  [state overlay-id]
  (if-let [overlay (some #(when (= overlay-id (:id %)) %) (:overlays state))]
    (-> state
        (update :overlays
                (fn [overlays]
                  (filterv #(not= overlay-id (:id %)) overlays)))
        (assoc :focus {:target (:trigger-id overlay)
                       :return-to (:trigger-id overlay)
                       :reason :overlay-closed}))
    state))

;; ---------------------------------------------------------------------------
;; Escape hierarchy

(defn escape-decision
  "Decide what Escape means in the current surface.

  `:overlays` is a stack; `:stream-selection` is the conversation selection
  map.  `:text-selection?` covers a selection inside the composer or another
  text control.  A composing input method always wins over selection and stop."
  [{:keys [overlays composing? text-selection? stream-selection
           generating? stopping?]}]
  (cond
    (seq overlays)
    {:action :close-overlay
     :overlay (peek overlays)
     :focus-target (:trigger-id (peek overlays))}

    composing?
    {:action :ime-candidate
     :message "Escape belongs to the input method while composing"}

    (or text-selection? (seq stream-selection))
    {:action :clear-selection}

    (and generating? (not stopping?))
    {:action :stop}

    :else
    {:action :ignore}))

;; ---------------------------------------------------------------------------
;; Live regions and labels

(defn live-region
  "One polite live region.  Errors and confirmations are announced by their
  visible text; the region never carries hidden model reasoning."
  [text]
  {:role "status"
   :aria-live "polite"
   :aria-atomic "true"
   :text (or text "")})

(defn alert-region
  [text]
  {:role "alert"
   :aria-live "assertive"
   :aria-atomic "true"
   :text (or text "")})

(defn labelled-by
  [id]
  {:aria-labelledby id})

(defn described-by
  [id]
  {:aria-describedby id})

(defn shortcut
  [keys]
  {:aria-keyshortcuts keys})
