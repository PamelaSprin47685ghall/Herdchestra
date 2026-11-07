(ns hc.server.documents.selection
  "Explicit processing selection.

  Extraction never chooses a route for the user.  `:native` sends the original
  bytes; `:text` extracts text locally; `:ocr` runs an explicitly chosen OCR
  engine.  A route that is not available is refused with the reason and a
  suggestion, never silently replaced by another route."
  (:require [hc.server.documents.error :as error]))

(def routes #{:native :text :ocr})

(defn processing
  "User-facing processing facts: where the bytes go and what the model sees."
  ([route]
   (processing route nil))
  ([route ocr]
   (case route
     :native
     {:route :native
      :location :original
      :description "send the original file"
      :sends-original? true
      :sends-text? false}

     :text
     {:route :text
      :location :device
      :description "extract text on this device"
      :sends-original? false
      :sends-text? true}

     :ocr
     (merge {:route :ocr
             :location :device
             :description "recognize text with local OCR"
             :sends-original? false
             :sends-text? true
             :review-required? true}
            (select-keys (or ocr {}) [:engine :engine-label :location]))

     nil)))

(defn- text-route
  [kind]
  (contains? #{:text :pdf} kind))

(defn- ocr-route
  [kind]
  (contains? #{:image :pdf} kind))

(defn select-route
  "Validate an explicit route against the detected content kind.

  Arguments:
  - `:requested` one of `:native`, `:text`, `:ocr`; nil is a refusal.
  - `:kind` detected content family: `:text`, `:pdf`, `:image` or
    `:unsupported`.
  - `:ocr` the availability map from an OCR engine, e.g.
    `{:available? true :engine :tesseract :location :device}`.

  Returns `{:ok? true :route ... :processing {...}}` or an `error/failure`."
  [{:keys [requested kind ocr text-available?]
    :or {text-available? true}}]
  (cond
    (nil? requested)
    (error/failure :ROUTE_REQUIRED
                   "choose how this material should be processed"
                   {:routes (sort routes)})

    (not (contains? routes requested))
    (error/failure :ROUTE_UNKNOWN
                   "the selected processing route is not recognized"
                   {:requested requested :routes (sort routes)})

    (= :native requested)
    {:ok? true :route :native :processing (processing :native)}

    (= :unsupported kind)
    (error/failure :UNSUPPORTED_MIME
                   "no extraction route supports the detected content"
                   {:requested requested})

    (= :text requested)
    (cond
      (not (text-route kind))
      (error/failure :ROUTE_UNAVAILABLE
                     "text extraction is not available for this content"
                     {:requested :text
                      :kind kind
                      :suggested-route (when (= :image kind) :ocr)})

      (false? text-available?)
      (error/failure :ROUTE_UNAVAILABLE
                     "the text extractor is not available"
                     {:requested :text :kind kind})

      :else
      {:ok? true :route :text :processing (processing :text)})

    (= :ocr requested)
    (cond
      (not (ocr-route kind))
      (error/failure :ROUTE_UNAVAILABLE
                     "OCR is not available for this content"
                     {:requested :ocr
                      :kind kind
                      :suggested-route (when (= :text kind) :text)})

      (not (:available? ocr))
      (error/failure :OCR_UNAVAILABLE
                     "the selected OCR engine is not available"
                     (merge {:requested :ocr
                             :kind kind
                             :suggested-route :native}
                            (select-keys (or ocr {}) [:engine :reason :message :languages])))

      :else
      {:ok? true :route :ocr :processing (processing :ocr ocr)})

    :else
    (error/failure :ROUTE_UNKNOWN
                   "the selected processing route is not recognized"
                   {:requested requested :routes (sort routes)})))
