(ns hc.server.diagnostics.persistence-test
  "Real storage path: commit diagnostics, close, reopen, read again."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.service :as diagnostics]
            [hc.server.diagnostics.store :as store]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn- temp-dir []
  (str (Files/createTempDirectory "hc-diagnostics-"
                                  (make-array FileAttribute 0))))

(defn- ms [text]
  (.toEpochMilli (Instant/parse text)))

(deftest diagnostics-survive-reopen-and-drive-real-statistics
  (let [path (temp-dir)
        at (ms "2026-11-03T12:00:00Z")
        token-price {:currency "USD" :input 3 :output 15
                     :cache-read 0.3 :cache-write 3.75
                     :source "user" :source-date "2026-11-01"}
        image-price {:currency "USD" :unit :per-image :rate 0.04
                     :source "user" :source-date "2026-11-01"}
        opened (storage/open-store {:path path :create? true})]
    (try
      (let [svc (diagnostics/service {:store opened
                                      :now-fn (constantly at)
                                      :app-version "0.0.1"
                                      :platform "linux"
                                      :os-version "7.3"})
            request {:request-id "r1" :turn-id "t1" :session-id "s1"
                     :assistant-id "a1" :service-id "svc1"
                     :service-name "Example" :model "m1"
                     :purpose :main :result :succeeded :at at
                     :price token-price
                     :usage {:coverage :final
                             :input-tokens 1000 :output-tokens 100
                             :cache-read-tokens 200 :cache-write-tokens 0}}
            _ (diagnostics/record-request! svc request)
            replay (diagnostics/record-request! svc request)
            _ (diagnostics/record-user-send! svc {:send-id "s1" :turn-id "t1"
                                                  :assistant-id "a1" :at at
                                                  :text "not persisted"})
            _ (diagnostics/record-tool-call! svc {:tool-call-id "tc1"
                                                  :turn-id "t1" :name "image"
                                                  :session-id "s1"
                                                  :assistant-id "a1"
                                                  :temporary? false
                                                  :at at})
            _ (diagnostics/record-service-usage! svc {:usage-id "u1"
                                                       :turn-id "t1"
                                                       :service-id "svc1"
                                                       :name "image"
                                                       :unit :per-image
                                                       :quantity 2
                                                       :at at
                                                       :price image-price})
            stats (diagnostics/statistics svc {:from "2026-11-01"
                                               :to "2026-11-03"
                                               :zone "UTC"})
            _ (diagnostics/enable-log! svc {:mode :next-request})
            logged (diagnostics/record-log! svc {:request-id "r1" :at at
                                                  :api-key "sk-1234567890abcdefghij"})
            status (diagnostics/log-status svc)
            preview (diagnostics/prepare-bundle svc {:error-category :rate-limited})
            exported (diagnostics/export-bundle! svc preview)]
        (is (= :ok (:status replay)))
        (is (= 1 (count (:requests (store/snapshot opened at)))))
        (is (= 1100 (get-in stats [:tokens :total-tokens])))
        (is (= 1 (get-in stats [:counts :user-sends])))
        (is (= 1 (get-in stats [:counts :model-requests])))
        (is (= 1 (get-in stats [:counts :tool-calls])))
        (is (= 0.003960M
               (get-in stats [:cost :by-currency "USD" :estimated-amount])))
        (is (= 1 (:service-usage-count stats)))
        (is (= 0.080000M
               (get-in stats [:service-cost :by-currency "USD"
                              :estimated-amount])))
        (is (= 2M (get-in stats [:service-cost :by-unit :per-image :quantity])))
        (is (true? (:recorded? logged)))
        (is (= 1 (:entry-count status)))
        (is (false? (:capturing? status)))
        (is (not (str/includes? (pr-str (store/snapshot opened at))
                                "not persisted")))
        (is (= "0.0.1" (get-in preview [:content :app :app-version])))
        (is (false? (:uploaded? exported)))
        (is (false? (:automatic-upload? exported))))
      (storage/close-store! opened)
      (let [reopened (storage/open-store {:path path})
            snapshot (store/snapshot reopened at)
            svc (diagnostics/service {:store reopened :now-fn (constantly at)})
            stats (diagnostics/statistics svc {:from "2026-11-01"
                                               :to "2026-11-03"
                                               :zone "UTC"})]
        (is (= 1 (count (:requests snapshot))))
        (is (= 1 (count (:service-usages snapshot))))
        (is (= "s1" (:session-id (first (:tool-calls snapshot)))))
        (is (= "a1" (:assistant-id (first (:tool-calls snapshot)))))
        (is (false? (:temporary? (first (:tool-calls snapshot)))))
        (is (= 1 (count (:log-entries snapshot))))
        (is (= :ok (:status (store/clear-log! reopened at))))
        (is (empty? (:log-entries (store/snapshot reopened at))))
        (is (= 1 (count (:requests (store/snapshot reopened at)))))
        (is (= 1100 (get-in stats [:tokens :total-tokens])))
        (is (= 1 (get-in stats [:counts :user-sends])))
        (is (= 1 (get-in stats [:counts :model-requests])))
        (is (= 1 (get-in stats [:counts :tool-calls])))
        (is (not (str/includes? (pr-str snapshot) "sk-1234567890abcdefghij")))
        (storage/close-store! reopened))
      (finally
        (try (storage/close-store! opened) (catch Throwable _ nil))))))

(deftest log-status-writes-and-expires-detailed-entries
  (let [path (temp-dir)
        at (ms "2026-11-03T12:00:00Z")
        now (atom at)
        opened (storage/open-store {:path path :create? true})]
    (try
      (let [svc (diagnostics/service {:store opened
                                      :now-fn #(deref now)})
            request {:request-id "log-r1" :turn-id "log-t1"
                     :session-id "s1" :assistant-id "a1"
                     :service-id "svc1" :model "m1"
                     :result :succeeded :at at
                     :usage {:coverage :final :input-tokens 10
                             :output-tokens 2
                             :cache-read-tokens 0
                             :cache-write-tokens 0}}]
        (is (true? (:ok? (diagnostics/enable-log! svc {:mode :next-request}))))
        (diagnostics/record-request! svc request)
        (let [status (diagnostics/log-status svc)]
          (is (= 1 (:entry-count status)))
          (is (pos? (:bytes status)))
          (is (false? (:capturing? status))))
        (reset! now (+ at (* 8 24 60 60 1000)))
        (let [status (diagnostics/log-status svc)
              snapshot (store/snapshot opened (deref now))]
          (is (zero? (:entry-count status)))
          (is (empty? (:log-entries snapshot)))))
      (finally
        (try (storage/close-store! opened) (catch Throwable _ nil))))))

(deftest stored-price-is-snapshotted-into-a-new-request
  (let [path (temp-dir)
        at (ms "2026-11-03T12:00:00Z")
        opened (storage/open-store {:path path :create? true})]
    (try
      (let [svc (diagnostics/service {:store opened :now-fn (constantly at)})]
        (is (= :ok (:status (diagnostics/save-price!
                             svc "s1" "m1"
                             {:currency "USD" :unit :per-million-tokens
                              :input 2M :output 1M
                              :cache-read 0M :cache-write 0M}))))
        (diagnostics/record-request!
         svc {:request-id "priced-r1" :turn-id "priced-t1"
              :session-id "s1" :service-id "s1" :model "m1"
              :result :succeeded :at at
              :usage {:coverage :final :input-tokens 1000000
                      :output-tokens 0 :cache-read-tokens 0
                      :cache-write-tokens 0}})
        (let [stats (diagnostics/statistics svc {:from "2026-11-03"
                                                 :to "2026-11-03"
                                                 :zone "UTC"})]
          (is (= 2M (get-in stats [:cost :by-currency "USD"
                                   :estimated-amount])))
          (is (= 1 (get-in stats [:cost :by-currency "USD"
                                  :estimated-requests])))))
      (finally
        (try (storage/close-store! opened) (catch Throwable _ nil))))))

(deftest detailed-log-material-merges-by-id-and-ignores-client-content
  (let [path (temp-dir)
        at (ms "2026-11-03T12:00:00Z")
        opened (storage/open-store {:path path :create? true})]
    (try
      (let [svc (diagnostics/service {:store opened
                                      :now-fn (constantly at)})
            _ (diagnostics/enable-log! svc {:mode :next-request})
            _ (diagnostics/record-log!
               svc {:request-id "merge-r1" :at at
                    :api-key "sk-1234567890abcdefghij"})
            request (fn [material]
                      {:error-category :rate-limited
                       :extra-materials [material]})
            selected (diagnostics/prepare-bundle
                      svc
                      (request {:id :detailed-log
                                :title "Client title"
                                :kind :log
                                :include? true
                                :purpose "show the log"
                                :content "CLIENT-FAKE-CONTENT"}))
            not-selected (diagnostics/prepare-bundle
                          svc
                          (request {:id :detailed-log
                                    :include? false}))
            blank-purpose (diagnostics/prepare-bundle
                           svc
                           (request {:id :detailed-log
                                     :include? true
                                     :purpose ""}))]
        (is (= 1 (count (filter #(= :detailed-log (:id %))
                                (:extra-materials selected)))))
        (is (true? (get-in selected [:extra-materials 0 :included?])))
        (is (= "show the log"
               (get-in selected [:extra-materials 0 :purpose])))
        (is (some #(= :extra-materials (:id %)) (:sections selected)))
        (is (str/includes? (pr-str selected) "merge-r1"))
        (is (not (str/includes? (pr-str selected) "CLIENT-FAKE-CONTENT")))
        (is (false? (get-in not-selected [:extra-materials 0 :included?])))
        (is (not (some #(= :extra-materials (:id %))
                       (:sections not-selected))))
        (is (true? (get-in blank-purpose [:extra-materials 0 :included?])))
        (is (= "Show request metadata and redacted parameters"
               (get-in blank-purpose [:extra-materials 0 :purpose]))))
      (finally
        (try (storage/close-store! opened) (catch Throwable _ nil))))))

(deftest detailed-log-material-without-server-log-is-not-fabricated
  (let [path (temp-dir)
        at (ms "2026-11-03T12:00:00Z")
        opened (storage/open-store {:path path :create? true})]
    (try
      (let [svc (diagnostics/service {:store opened :now-fn (constantly at)})
            preview (diagnostics/prepare-bundle
                     svc
                     {:error-category :rate-limited
                      :extra-materials [{:id :detailed-log
                                         :include? true
                                         :purpose "show"
                                         :content "CLIENT-FAKE-CONTENT"}]})]
        (is (empty? (:extra-materials preview)))
        (is (not (some #(= :extra-materials (:id %)) (:sections preview))))
        (is (not (str/includes? (pr-str preview) "CLIENT-FAKE-CONTENT"))))
      (finally
        (try (storage/close-store! opened) (catch Throwable _ nil))))))
