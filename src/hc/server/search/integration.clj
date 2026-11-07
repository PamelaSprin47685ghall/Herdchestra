(ns hc.server.search.integration
  "Execute one accepted turn's search intent.

  The app router must not perform the search before the Owner accepts and
  commits the message: a rejected message would still have been sent to an
  external service.  This namespace is called after that commit, from the
  model gateway, with the intent fixed on the accepted turn.

  `:continue?` is the only model-gateway gate:

    true   run the model (search succeeded, was skipped, or failed in :auto)
    false  do not run the model (required search failed or was cancelled)

  The result still carries the truthful search status and context; the caller
  must not turn an auto failure into an online claim."
  (:require [hc.server.diagnostics.service :as diagnostics]
            [hc.server.search :as search]
            [hc.server.search.context :as context]
            [hc.server.search.errors :as errors]))

(defn- intent-mode
  [intent]
  (some-> (:mode intent) keyword))

(defn- intent-queries
  [intent text]
  (or (:queries intent)
      [{:query-id "q1" :text (or text "")}]))

(defn- record-usage!
  [diagnostics-service usages]
  (when diagnostics-service
    (doseq [usage usages]
      (try
        (diagnostics/record-service-usage! diagnostics-service usage)
        (catch Throwable _ nil)))))

(defn run-intent!
  "Run the search intent for one accepted turn.

  INTENT is `{:mode :off|:auto|:required :service-id \"...\" :queries [...]}`.
  OPTIONS: `:text` current user text, `:turn-id`, `:session-id`, optional
  `:cancelled?` predicate or atom, and optional `:diagnostics` service.
  Provider-reported `:service-usages` are recorded only when that service is
  supplied; missing usage is not invented.

  Returns `{:continue? bool :ok? bool :status ... :context ... :result ...
  :error ...}`.  `:context` is a `hc.server.search.context` value or nil."
  [search-service intent options]
  (let [intent (or intent {})
        mode (intent-mode intent)
        text (or (:text options) "")]
    (cond
      (nil? mode)
      {:continue? false
       :ok? false
       :status :failed
       :searched? false
       :must-search-failed? false
       :cancelled? false
       :context nil
       :result nil
       :service-usages []
       :error (errors/error :SEARCH_MODE_INVALID
                            "the search intent needs an explicit mode"
                            {:mode (:mode intent)
                             :allowed (vec (sort search/modes))})}

      (not (contains? search/modes mode))
      {:continue? false
       :ok? false
       :status :failed
       :searched? false
       :must-search-failed? false
       :cancelled? false
       :context nil
       :result nil
       :service-usages []
       :error (errors/error :SEARCH_MODE_INVALID
                            "the search intent mode is not recognized"
                            {:mode (:mode intent)
                             :allowed (vec (sort search/modes))})}

      (= :off mode)
      {:continue? true
       :ok? true
       :status :skipped
       :searched? false
       :must-search-failed? false
       :cancelled? false
       :context nil
       :result nil
       :service-usages []
       :error nil}

      (nil? search-service)
      (let [error (if (errors/non-blank-string? (:service-id intent))
                    (errors/error :SEARCH_UNAVAILABLE
                                  "no search service is running"
                                  {:service-id (:service-id intent)})
                    (errors/error :SEARCH_SERVICE_NOT_SELECTED
                                  "no search service is selected for this turn"
                                  {}))]
        {:continue? (not= :required mode)
         :ok? false
         :status :failed
         :searched? false
         :must-search-failed? (= :required mode)
         :cancelled? false
         :context nil
         :result nil
         :service-usages []
         :error error})

      :else
      (let [result (search/run! search-service
                                {:service-id (:service-id intent)
                                 :mode mode
                                 :turn-id (:turn-id options)
                                 :session-id (:session-id options)
                                 :cancelled? (:cancelled? options)
                                 :queries (intent-queries intent text)})
            usages (vec (or (:service-usages result) []))
            _ (record-usage! (:diagnostics options) usages)
            turn (context/turn-context result)
            cancelled? (true? (:cancelled? result))
            must-search-failed? (true? (:must-search-failed? result))
            continue? (cond
                        cancelled? false
                        (= :required mode) (not must-search-failed?)
                        :else true)]
        {:continue? continue?
         :ok? (true? (:ok? result))
         :status (:status result)
         :searched? (true? (:searched? result))
         :must-search-failed? must-search-failed?
         :cancelled? cancelled?
         :context turn
         :result result
         :service-usages usages
         :error (:error result)}))))
