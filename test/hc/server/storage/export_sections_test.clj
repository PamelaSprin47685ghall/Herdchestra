(ns hc.server.storage.export-sections-test
  "Reference-only export must not copy documents or obligations."
  (:require [clojure.test :refer [deftest is]]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- blob-id
  [i]
  (str "sha256-" (format "%064x" i)))

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
