(ns hc.server.storage.export-sections-test
  "Reference-only export must not copy documents or obligations, and a
  whole-store export must not carry rebuildable derived partitions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.history.index :as index]
            [hc.server.storage :as storage]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.support :as support]
            [hc.server.storage.view :as storage-view]))

(defn- blob-id
  [i]
  (str "sha256-" (format "%064x" i)))

(def ^:private non-derived-partitions
  [["sessions" :session-record "s1"]
   ["config" :app-config "config"]
   ["materials" :materials-root "root"]
   ["scheduler" :scheduler-root "root"]
   ["diagnostics" :diagnostics-request "req-1"]
   ["steward/repository" :steward-state "current"]
   ["p" :task "t1"]])

(defn- seed-partition!
  [store [partition kind id]]
  (is (:ok? (storage/commit-migration!
             store
             {:migration-id (str "seed-" partition "-" (name kind) "-" id)
              :partition partition
              :put [{:kind kind :id id :doc {:seeded true}}]}))))

(deftest blob-refs-only-export-skips-documents
  (support/with-temp-store [store {}]
    (let [n 50
          docs (mapv (fn [i]
                       {:kind :session
                        :id (str "s" i)
                        :doc {:title (str "session-" i)}})
                     (range n))
          refs (mapv (fn [i]
                       {:id (blob-id i) :doc {:id (blob-id i)}})
                     (range n))]
      (is (:ok? (storage/commit-migration!
                 store
                 {:migration-id "export-sections-docs"
                  :partition "p"
                  :put docs})))
      (is (:ok? (storage/manage-blob-refs!
                 store
                 {:partition "p" :op :add :refs refs})))
      (let [full (storage/export-state store {:partition "p"})
            refs-only (storage/export-state
                       store
                       {:partition "p" :sections #{:blob-refs}})]
        (is (= n (count (get-in full [:partitions "p" :docs]))))
        (is (= n (count (get-in full [:partitions "p" :blob-refs]))))
        (is (nil? (get-in refs-only [:partitions "p" :docs])))
        (is (nil? (get-in refs-only [:partitions "p" :obligations])))
        (is (= n (count (get-in refs-only [:partitions "p" :blob-refs]))))
        (is (= (set (map :id refs))
               (set (map :id (get-in refs-only [:partitions "p" :blob-refs])))))
        (is (= (:store-version full) (:store-version refs-only)))))))

(deftest whole-store-export-excludes-derived-partitions-by-default-test
  (support/with-temp-store [store {}]
    (doseq [entry non-derived-partitions]
      (seed-partition! store entry))
    (seed-partition! store ["search" :search-index "meta"])
    (let [default (storage/export-state store {})
          with-derived (storage/export-state store {:include-derived? true})
          isolated (storage/export-state store {:partition "search"})
          whitelist (set (map first non-derived-partitions))]
      (is (= whitelist (set (keys (:partitions default))))
          "every non-derived partition must survive the default export")
      (is (not (contains? (:partitions default) "search")))
      (is (= (conj whitelist "search")
             (set (keys (:partitions with-derived)))))
      (is (contains? (:partitions with-derived) "search"))
      (is (= #{"search"} (set (keys (:partitions isolated))))
          "an explicitly named derived partition is still readable")
      (doseq [[partition kind id] non-derived-partitions]
        (is (= [id]
               (mapv :id (get-in default [:partitions partition :docs])))
            (str "default export lost " partition "/" kind))))))

(deftest restore-drops-a-derived-partition-and-warns-test
  (support/with-temp-store [store {}]
    (seed-partition! store ["sessions" :session-record "s1"])
    (seed-partition! store ["search" :search-index "meta"])
    (let [old (storage/export-state store {:include-derived? true})
          result (storage/restore-state! store old
                                          {:migration-id "restore-derived-warning"
                                           :quiescent? true})
          after (storage/export-state store {:include-derived? true})]
      (is (contains? (:partitions old) "search"))
      (is (:ok? result))
      (is (some (fn [warning]
                  (and (= :STORAGE_DERIVED_PARTITION_DROPPED
                          (:code warning))
                       (= "search" (:partition warning))
                       (string? (:message warning))))
                (:warnings result)))
      (is (not (contains? (:partitions after) "search"))
          "a restored old package must not resurrect the derived index")
      (is (= ["s1"] (mapv :id (get-in after [:partitions "sessions" :docs])))
          "non-derived state still restores"))))

(deftest whole-store-export-never-scans-derived-partitions-test
  (support/with-temp-store [store {}]
    (seed-partition! store ["p" :task "t1"])
    (seed-partition! store ["search" :search-index "meta"])
    (let [orig engine/scan
          scans (atom [])]
      (with-redefs [engine/scan
                    (fn [engine prefix]
                      (swap! scans conj prefix)
                      (orig engine prefix))]
        (let [export (storage/export-state store {})]
          (is (not (contains? (:partitions export) "search"))))
        (is (not-any? (fn [prefix]
                        (or (str/starts-with?
                             prefix
                             (keys/partition-doc-prefix "search"))
                            (str/starts-with?
                             prefix
                             (keys/partition-obligation-prefix "search"))
                            (str/starts-with?
                             prefix
                             (keys/partition-blob-ref-prefix "search"))))
                      @scans)
            "a whole-store export must skip the derived partition before assembly")
        (reset! scans [])
        (let [export (storage/export-state store {:partition "search"})]
          (is (contains? (:partitions export) "search")))
        (is (some #(= (keys/partition-doc-prefix "search") %) @scans)
            "an explicit derived partition request still reads that partition")))))

(defn- rebuild-session
  []
  {:session-id "s1"
   :revision 1
   :status :active
   :temporary? false
   :assistant-id "a1"
   :title "Rebuild"
   :messages {"m1" {:message-id "m1"
                    :role :user
                    :versions [{:version-id "m1-v1"
                                :content "alpha rebuild"
                                :created-at 1
                                :attachments []}]
                    :current-version 0
                    :attachments []
                    :created-at 1}}
   :message-order ["m1"]})

(deftest rebuild-writes-search-explicitly-but-default-export-excludes-it
  (support/with-temp-store [store {}]
    (seed-partition! store ["p" :task "t1"])
    (let [rebuilt (index/rebuild! store (fn [] [(rebuild-session)]))
          default (storage/export-state store {})
          explicit (storage/export-state store {:partition "search"})
          postings (->> (get-in explicit [:partitions "search" :docs])
                        (filter #(= index/posting-kind (:kind %))))]
      (is (:ok? rebuilt))
      (is (= 1 (:sessions rebuilt)))
      (is (not (contains? (:partitions default) "search"))
          "rebuild's new generation must not enter the default export")
      (is (contains? (:partitions explicit) "search")
          "an explicit partition request still reads the rebuilt index")
      (is (some #(= "alpha" (:id %)) postings)
          "the rebuilt posting is visible to an explicit search reader")
      (is (some? (storage/read-document
                  store {:partition "p" :kind :task :id "t1"}))
          "clearing the derived index must not delete another partition"))))

(deftest index-reads-search-documents-outside-session-view
  (is (not (str/includes?
            (slurp (io/resource "hc/server/history/index.clj"))
            "read-session-view"))
      "the index must not depend on the narrowed session-view scan")
  (doseq [kind [index/meta-kind index/session-kind index/posting-kind]]
    (is (contains? storage-view/known-document-kinds kind)
        (str "known-document-kinds must include " kind))))
