(ns hc.server.search.providers.protocol
  "One independent search service provider.")

(defprotocol SearchProvider
  (-search
    [provider service request]
    "Perform one query and return normalized results.

    REQUEST contains :query, :query-id, :count, :language, :country,
    :safe-search, :date-range, :timeout-ms, :cancelled? and :api-key.  The
    provider returns either a refusal or

      {:ok? true
       :http-status 200
       :results [{:url ... :title ... :snippet ...
                  :published-at ... :content-status ...}]
       :documents {canonical-url {...}}
       :date-filter {:requested ... :applied ... :notice ...}
       :invalid-count 0}

    The provider never reads a clock, never writes a reference set and never
    retries on its own."))
