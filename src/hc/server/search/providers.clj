(ns hc.server.search.providers
  "Explicit provider construction.  A display name never chooses a provider."
  (:require [hc.server.search.providers.brave :as brave]
            [hc.server.search.providers.searxng :as searxng]
            [hc.server.search.providers.tavily :as tavily]))

(defn provider
  "Build the provider for an explicit `:kind`, or nil when unsupported."
  [kind client]
  (case kind
    :searxng (searxng/provider client)
    :brave (brave/provider client)
    :tavily (tavily/provider client)
    nil))
