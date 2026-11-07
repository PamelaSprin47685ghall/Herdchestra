(ns hc.server.diagnostics.app-integration-test
  "Actual application path: start the app system, dispatch diagnostics
  commands through its router, and export a bundle through its blob root."
  (:require [clojure.test :refer [deftest is]]
            [hc.server.app.commands :as app-commands]
            [hc.server.app.config :as config]
            [hc.server.app.lifecycle-test-support :as lifecycle]
            [hc.server.app.system :as system]
            [hc.server.blob-store :as blob]
            [hc.server.diagnostics.service :as diagnostics-service]
            [hc.server.gateway.ports :as ports])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn- temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write-file! [path content]
  (let [file (java.io.File. ^String path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- test-config []
  (let [root (temp-dir "hc-diag-app-root-")
        data (temp-dir "hc-diag-app-data-")
        static (temp-dir "hc-diag-app-static-")]
    (write-file! (str static "/index.html") "<!doctype html><title>hc</title>")
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root root
      :static-root static
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :models {}})))

(defn- command!
  [router payload]
  (let [reply (promise)]
    (ports/submit-command! router
                           {:command-id (str "diag-test-" (random-uuid))
                            :command-epoch (app-commands/app-command-epoch router)
                            :payload payload}
                           #(deliver reply %))
    (deref reply 5000 {:ok? false :error {:code :TIMEOUT}})))

(deftest app-router-exposes-diagnostics-and-exports-through-blob
  (let [config (test-config)
        started (system/start! config)]
    (try
      (is (false? (:blocked? started)))
      (is (some? (:diagnostics started)))
      (let [router (:router started)
            at (.toEpochMilli (Instant/parse "2026-11-03T12:00:00Z"))
            stats (command! router {:kind :diagnostics/statistics
                                    :query {:from "2026-11-01"
                                            :to "2026-11-03"
                                            :zone "UTC"}})
            recorded-request (diagnostics-service/record-request!
               (:diagnostics started)
               {:request-id "app-r1" :turn-id "app-t1" :at at
                :model "m1" :service-id "s1"
                :usage {:coverage :final :input-tokens 10 :output-tokens 2
                        :cache-read-tokens 0 :cache-write-tokens 0}})
            recorded-usage (diagnostics-service/record-service-usage!
               (:diagnostics started)
               {:usage-id "app-u1" :turn-id "app-t1"
                :service-id "image-svc" :name "image"
                :unit :per-image :quantity 2
                :quantity-source :provider-reported
                :at at
                :price {:currency "USD" :unit :per-image :rate 0.04}})
            stats-after (command! router {:kind :diagnostics/statistics
                                          :query {:from "2026-11-01"
                                                  :to "2026-11-03"
                                                  :zone "UTC"}})
            preview (command! router {:kind :diagnostics/bundle-preview
                                      :bundle {:error-category :rate-limited}})
            bundle (get-in preview [:receipt :bundle])
            exported (command! router {:kind :diagnostics/bundle-export
                                       :bundle bundle})
            artifact (get-in exported [:receipt :artifact])]
        (is (true? (:ok? stats)))
        (is (= :no-data (get-in stats [:receipt :statistics :status])))
        (is (= :ok (:status recorded-request)))
        (is (= :ok (:status recorded-usage)))
        (is (true? (:ok? stats-after)))
        (is (= 1 (get-in stats-after [:receipt :statistics :counts
                                      :model-requests])))
        (is (some #(and (= "s1" (:service-id %))
                        (= "m1" (:model %)))
                  (get-in stats-after [:receipt :statistics :models]))
            "the service row is present for the price form")
        (is (= 12 (get-in stats-after [:receipt :statistics :tokens
                                       :total-tokens])))
        (is (= 1 (get-in stats-after [:receipt :statistics
                                      :service-usage-count])))
        (is (= 2M (get-in stats-after [:receipt :statistics :service-cost
                                       :by-unit :per-image :quantity])))
        (is (= 1 (get-in stats-after [:receipt :statistics :service-cost
                                      :reported-quantity-usages])))
        (is (true? (:ok? preview)))
        (is (= :local-only (get-in bundle [:transfer :mode])))
        (is (true? (:ok? exported)))
        (is (= :exported (get-in exported [:receipt :status])))
        (is (false? (get-in exported [:receipt :uploaded?])))
        (is (false? (get-in exported [:receipt :automatic-upload?])))
        (is (string? (:id artifact)))
        (is (pos? (count (blob/read-bytes (:blob-root config) (:id artifact))))))
      (finally
        (lifecycle/stop-system! started)))))
