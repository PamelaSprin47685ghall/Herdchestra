(ns hc.server.diagnostics.integration-test
  "Application-shell bridge: command dispatch, baseline, blob-backed export."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.blob-store :as blob]
            [hc.server.diagnostics.integration :as integration]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn- temp-dir []
  (str (Files/createTempDirectory "hc-diagnostics-int-"
                                  (make-array FileAttribute 0))))

(deftest app-bridge-dispatches-and-exports-through-blob-store
  (let [store-path (temp-dir)
        blob-root (temp-dir)
        at (.toEpochMilli (Instant/parse "2026-11-03T12:00:00Z"))
        opened (storage/open-store {:path store-path :create? true})]
    (try
      (let [diagnostics (integration/attach {:store opened
                                             :blob-root blob-root
                                             :app-version "0.0.1"})
            _ (integration/dispatch
               diagnostics
               {:kind :diagnostics/log-enable :mode :next-request})
            stats-receipt (integration/dispatch
                           diagnostics
                           {:kind :diagnostics/statistics
                            :query {:from "2026-11-01" :to "2026-11-03"
                                    :zone "UTC"}})
            preview-receipt (integration/dispatch
                             diagnostics
                             {:kind :diagnostics/bundle-preview
                              :bundle {:error-category :rate-limited
                                       :services [{:actual-host "api.example.com"}]}})
            preview (get-in preview-receipt [:receipt :bundle])
            edited (get-in (integration/dispatch
                            diagnostics
                            {:kind :diagnostics/bundle-remove-lines
                             :bundle preview
                             :line-ids ["app-version"]})
                           [:receipt :bundle])
            edited (get-in (integration/dispatch
                            diagnostics
                            {:kind :diagnostics/bundle-hide-hosts
                             :bundle edited})
                           [:receipt :bundle])
            export-receipt (integration/dispatch
                            diagnostics
                            {:kind :diagnostics/bundle-export
                             :bundle edited})
            artifact (get-in export-receipt [:receipt :artifact])
            baseline (integration/baseline diagnostics)]
        (is (true? (:ok? stats-receipt)))
        (is (= :no-data (get-in stats-receipt
                                [:receipt :statistics :status])))
        (is (true? (:ok? preview-receipt)))
        (is (= :local-only (get-in preview [:transfer :mode])))
        (is (not (contains? (set (mapcat (fn [section]
                                           (map :id (:lines section)))
                                         (:sections edited)))
                            "app-version")))
        (is (not (str/includes? (pr-str edited) "api.example.com")))
        (is (true? (:ok? export-receipt)))
        (is (= :exported (get-in export-receipt [:receipt :status])))
        (is (string? (:id artifact)))
        (is (false? (get-in export-receipt [:receipt :uploaded?])))
        (is (false? (get-in export-receipt [:receipt :automatic-upload?])))
        (is (string? (:filename artifact)))
        (is (string? (String. ^bytes (blob/read-bytes blob-root (:id artifact))
                              "UTF-8")))
        (is (some #(= (:id artifact) (:id %))
                  (:refs (storage/manage-blob-refs!
                          opened {:partition "session/diagnostics"
                                  :op :list}))))
        (is (map? (:log baseline)))
        (is (true? (get-in baseline [:log :capturing?]))))
      (finally
        (try (storage/close-store! opened) (catch Throwable _ nil))))))

(deftest export-requires-a-server-issued-preview
  (let [store-path (temp-dir)
        blob-root (temp-dir)
        opened (storage/open-store {:path store-path :create? true})]
    (try
      (let [diagnostics (integration/attach {:store opened
                                             :blob-root blob-root
                                             :app-version "0.0.1"})
            no-preview (integration/dispatch
                        diagnostics
                        {:kind :diagnostics/bundle-export
                         :request {:error-category :rate-limited}})
            preview (get-in (integration/dispatch
                             diagnostics
                             {:kind :diagnostics/bundle-preview
                              :bundle {:error-category :rate-limited}})
                            [:receipt :bundle])
            tampered (assoc-in preview [:content :app :app-version] "9.9.9")
            refused (integration/dispatch
                     diagnostics
                     {:kind :diagnostics/bundle-export :bundle tampered})
            exported (integration/dispatch
                      diagnostics
                      {:kind :diagnostics/bundle-export :bundle preview})]
        (is (false? (:ok? no-preview)))
        (is (= :DIAGNOSTICS_BUNDLE_REQUIRED
               (get-in no-preview [:error :code])))
        (is (false? (:ok? refused)))
        (is (= :DIAGNOSTICS_BUNDLE_CHANGED
               (get-in refused [:error :code])))
        (is (true? (:ok? exported))))
      (finally
        (try (storage/close-store! opened) (catch Throwable _ nil))))))
