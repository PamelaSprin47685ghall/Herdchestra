(ns hc.server.diagnostics
  "Public facade for usage/cost statistics and diagnostic redaction.

  Authority: root AGENTS.md §5.4, §7.4 旅程 8.6（导出诊断包）, §7.5, and §7.7.

  This package is pure data preparation.  It performs no storage writes and
  no network I/O.  In particular, there is no upload entry point: a
  diagnostic bundle is always `:local-only`, and the caller writes it only to
  a destination the user chose.

  The sub-namespaces are independent:

    usage    request identity, cache scope, token totals, provenance
    pricing  per-million-token price records and partial estimates
    stats    time ranges, distinct counts, heatmap, model rows, turns, cost
    redact   stable secret placeholders and optional host/path masking
    log      detailed-log policy, next-request default, seven-day retention
    bundle   local-only diagnostic preview with line removal and export

  The persistence and app-facing layers require the :server alias:
  `store`, `service`, `integration`, and `capture`.  The pure namespaces above
  need no server dependency.

  See `src/hc/server/diagnostics/AGENTS.md` for the data model and invariants."
  (:require [hc.server.diagnostics.bundle :as bundle]
            [hc.server.diagnostics.log :as detail-log]
            [hc.server.diagnostics.pricing :as pricing]
            [hc.server.diagnostics.redact :as redact]
            [hc.server.diagnostics.service-usage :as service-usage]
            [hc.server.diagnostics.stats :as stats]
            [hc.server.diagnostics.usage :as usage]))

;; Usage records
(def token-fields usage/token-fields)
(def cache-modes usage/cache-modes)
(defn request [raw] (usage/request raw))
(defn token-summary [request] (usage/token-summary request))
(defn usage-summary [requests] (usage/summary requests))
(defn turn-summary [requests] (usage/turn-summary requests))

;; Statistics
(def filter-keys stats/filter-keys)
(defn statistics [snapshot query] (stats/summary snapshot query))
(defn reprice-requests [requests catalog] (stats/reprice requests catalog))
(defn statistics-unavailable [message] (stats/unavailable message))

;; Prices and costs
(def price-fields pricing/price-fields)
(def price-units pricing/price-units)
(def service-units pricing/service-units)
(def tokens-per-unit pricing/tokens-per-unit)
(defn price [raw] (pricing/price raw))
(defn price-catalog [prices] (pricing/catalog prices))
(defn with-price [catalog service-id model price]
  (pricing/with-price catalog service-id model price))
(defn lookup-price [catalog service-id model]
  (pricing/lookup catalog service-id model))
(defn estimate-cost [price usage] (pricing/estimate price usage))
(defn unit-preview [price] (pricing/unit-preview price))
(defn service-price [raw] (pricing/service-price raw))
(defn estimate-service-cost [price quantity]
  (pricing/estimate-service price quantity))
(defn service-unit-preview [price] (pricing/service-unit-preview price))
(defn provider-service-usage [raw]
  (service-usage/provider-reported raw))
(defn stt-service-usage [response meta]
  (service-usage/stt-duration response meta))
(defn search-service-usage [response meta]
  (service-usage/search-credits response meta))
(defn billed [raw] (pricing/billed raw))

;; Redaction
(defn redact-value
  ([value] (redact/redact value))
  ([value opts] (redact/redact value opts)))
(defn sensitive-key? [key] (redact/sensitive-key? key))
(def placeholder-floor redact/placeholder-floor)

;; Detailed logs
(def max-log-retention-days detail-log/max-retention-days)
(defn new-log-policy
  ([] (detail-log/new-policy))
  ([opts] (detail-log/new-policy opts)))
(defn enable-log [policy opts] (detail-log/enable policy opts))
(defn disable-log [policy] (detail-log/disable policy))
(defn pause-log [policy] (detail-log/pause policy))
(defn resume-log [policy now] (detail-log/resume policy now))
(defn expire-log [policy now] (detail-log/expire policy now))
(defn record-log [policy entry opts] (detail-log/record policy entry opts))
(defn clear-log [policy now] (detail-log/clear policy now))
(defn log-entries [policy] (detail-log/entries policy))
(defn log-status [policy now] (detail-log/status policy now))

;; Diagnostic bundle
(def error-categories bundle/error-categories)
(def error-category-order bundle/error-category-order)
(def default-exclusions bundle/default-exclusions)
(defn prepare-bundle [request] (bundle/prepare request))
(defn remove-bundle-lines [bundle line-ids] (bundle/remove-lines bundle line-ids))
(defn rename-bundle [bundle mapping] (bundle/rename bundle mapping))
(defn hide-bundle-hosts [bundle] (bundle/hide-hosts bundle))
(defn hide-bundle-paths [bundle] (bundle/hide-paths bundle))
(defn bundle-export-view [bundle] (bundle/export-view bundle))
(defn local-artifact [bundle] (bundle/local-artifact bundle))
