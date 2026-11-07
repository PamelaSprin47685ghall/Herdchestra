(ns hc.server.storage.export-benchmark-test
  "1000-session benchmark for reference-only export.

  Skipped by default.  Run with:
    clojure -J-Dhc.bench.export=true -M:test:compose:server:client
      -n hc.server.storage.export-benchmark-test"
  (:require [clojure.test :refer [deftest is]]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.lang.management ManagementFactory)
           (com.sun.management ThreadMXBean)))

(defn- allocated-bytes
  []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (if (instance? ThreadMXBean bean)
      (.getThreadAllocatedBytes ^ThreadMXBean bean
                                (.getId (Thread/currentThread)))
      0)))

(defn- blob-id [i] (str "sha256-" (format "%064x" i)))

(deftest export-state-1000-sessions-benchmark
  (if-not (Boolean/getBoolean "hc.bench.export")
    (is true)
    (support/with-temp-store [store {}]
      (let [n 1000
            body (apply str (repeat 4096 "x"))
            docs (mapv (fn [i]
                         {:kind :session
                          :id (str "s" i)
                          :doc {:title (str "session-" i) :body body}})
                       (range n))
            refs (mapv (fn [i]
                         {:id (blob-id i) :doc {:id (blob-id i)}})
                       (range n))]
        (is (:ok? (storage/commit-migration!
                   store {:migration-id "bench-docs" :partition "p" :put docs})))
        (is (:ok? (storage/manage-blob-refs!
                   store {:partition "p" :op :add :refs refs})))
        ;; Warm both paths so the comparison is not dominated by first-call JIT.
        (storage/export-state store {:partition "p"})
        (storage/export-state store {:partition "p" :sections #{:blob-refs}})
        (let [a0 (allocated-bytes) t0 (System/nanoTime)
              full (storage/export-state store {:partition "p"})
              a1 (allocated-bytes) t1 (System/nanoTime)
              _ (is (= n (count (get-in full [:partitions "p" :docs]))))
              _ (is (= n (count (get-in full [:partitions "p" :blob-refs]))))
              a2 (allocated-bytes) t2 (System/nanoTime)
              refs-only (storage/export-state
                         store {:partition "p" :sections #{:blob-refs}})
              a3 (allocated-bytes) t3 (System/nanoTime)]
          (is (= n (count (get-in refs-only [:partitions "p" :blob-refs]))))
          (is (nil? (get-in refs-only [:partitions "p" :docs])))
          (is (< (- a3 a2) (- a1 a0))
              "reference-only export must allocate less than the full export")
          (println "export-benchmark"
                   {:sessions n
                    :full-ms (/ (- t1 t0) 1e6)
                    :full-alloc-mib (/ (- a1 a0) 1048576.0)
                    :refs-ms (/ (- t3 t2) 1e6)
                    :refs-alloc-mib (/ (- a3 a2) 1048576.0)}))))))
