(ns hc.server.documents.ocr
  "OCR engine boundary.

  An engine is a plain map:

      {:id :tesseract
       :engine-label \"Tesseract (local CPU)\"
       :location :device
       :availability (fn [] {:available? true ...})
       :recognize! (fn [request] {:ok? true :text \"...\"})}

  The request contains `:png-bytes`, `:languages`, `:psm` and `:timeout-ms`.
  This namespace adds a deadline and converts unexpected engine failures into
  refusals.  It never invents text when an engine is missing or returns
  nothing."
  (:require [hc.server.documents.error :as error]))

(def default-timeout-ms 120000)

(defn engine?
  [value]
  (and (map? value)
       (keyword? (:id value))
       (fn? (:recognize! value))))

(defn availability
  "Ask an engine whether it can run, with a bounded, readable answer."
  [engine]
  (cond
    (not (engine? engine))
    {:available? false :reason :OCR_ENGINE_INVALID
     :message "the OCR engine is not a valid engine map"}

    (not (fn? (:availability engine)))
    {:available? true :engine (:id engine)}

    :else
    (try
      (let [answer ((:availability engine))]
        (if (map? answer)
          (assoc answer :engine (or (:engine answer) (:id engine)))
          {:available? false :engine (:id engine)
           :reason :OCR_ENGINE_INVALID
           :message "the OCR engine returned an invalid availability answer"}))
      (catch Throwable t
        {:available? false
         :engine (:id engine)
         :reason :OCR_UNAVAILABLE
         :message (error/safe-message t)}))))

(defn- invoke-with-deadline
  [f request timeout-ms]
  (let [result (promise)
        thread (doto (Thread.
                      (fn []
                        (try
                          (deliver result (f request))
                          (catch Throwable t
                            (deliver result
                                     (error/failure :OCR_FAILED
                                                    "the OCR engine failed"
                                                    {:reason (error/safe-message t)}))))))
                 (.setDaemon true)
                 (.setName "hc-document-ocr"))]
    (.start thread)
    (let [value (if (and (integer? timeout-ms) (pos? timeout-ms))
                  (deref result timeout-ms ::timeout)
                  (deref result))]
      (if (= ::timeout value)
        (do
          (.interrupt thread)
          (error/failure :OCR_TIMEOUT
                         "OCR did not finish before the deadline"
                         {:timeout-ms timeout-ms}))
        value))))

(defn recognize!
  "Run one OCR request against an engine.

  Refuses before calling when the engine is unavailable.  The deadline is
  enforced from the caller side; a native engine call cannot be force-killed,
  so the result says `:OCR_TIMEOUT` and the daemon thread is interrupted."
  [engine request]
  (cond
    (not (engine? engine))
    (error/failure :OCR_ENGINE_INVALID
                   "the OCR engine is not a valid engine map")

    :else
    (let [available (availability engine)]
      (if-not (:available? available)
        (error/failure :OCR_UNAVAILABLE
                       "the selected OCR engine is not available"
                       (select-keys available [:engine :reason :message :languages]))
        (let [request (assoc request
                             :languages (or (:languages request) ["chi_sim" "eng"])
                             :psm (or (:psm request) 3))
              timeout-ms (or (:timeout-ms request) default-timeout-ms)
              result (invoke-with-deadline (:recognize! engine) request timeout-ms)]
          (cond
            (not (map? result))
            (error/failure :OCR_FAILED "the OCR engine returned an invalid result")

            (not (contains? result :ok?))
            (error/failure :OCR_FAILED "the OCR engine returned no result status")

            (and (:ok? result) (not (string? (:text result))))
            (error/failure :OCR_FAILED "the OCR engine returned no text field")

            :else
            (merge {:engine (:id engine)
                    :languages (:languages request)
                    :psm (:psm request)}
                   result)))))))
