(ns hc.server.storage.disk-smoke
  "Child-process driver for the real-disk durability smoke.

  This namespace is not a test namespace; `hc.server.storage.disk-smoke-test`
  starts it in a separate JVM so that a commit, a close and a reopen cross a
  process boundary.

  Usage:
    write      <dir> <key> <value>
    read       <dir> <key> <expected-value>
    lock-check <dir>"
  (:require [hc.server.storage :as storage])
  (:gen-class))

(defn- view [store]
  (storage/read-session-view store {:partition "smoke"}))

(defn- find-doc [store key]
  (->> (get-in (view store) [:records :smoke])
       (filter #(= key (:storage/id %)))
       first))

(defn- fail! [message]
  (binding [*out* *err*]
    (println "SMOKE-FAIL" message))
  (System/exit 2))

(defn -main [& args]
  (let [[mode dir & more] args]
    (case mode
      "write"
      (let [[key value] more
            store (storage/open-store {:path dir :create? true})]
        (storage/commit-migration!
         store
         {:migration-id (str "smoke-write-" key)
          :partition "smoke"
          :put [{:kind :smoke :id key :doc {:value value}}]})
        (storage/close-store! store)
        (println "OK"))

      "read"
      (let [[key expected] more
            store (storage/open-store {:path dir :create? false})
            doc   (find-doc store key)]
        (when-not (= expected (:value doc))
          (fail! (str "expected " (pr-str expected) ", got " (pr-str doc))))
        (storage/commit-migration!
         store
         {:migration-id (str "smoke-read-" key)
          :partition "smoke"
          :put [{:kind :smoke :id (str key "-seen") :doc {:value expected}}]})
        (storage/close-store! store)
        (println "OK"))

      "lock-check"
      (try
        (let [store (storage/open-store {:path dir :create? false})]
          (storage/close-store! store)
          (println "UNEXPECTED-OPEN")
          (System/exit 1))
        (catch clojure.lang.ExceptionInfo e
          (if (= :STORAGE_LOCKED (storage/error-code e))
            (println "LOCKED")
            (fail! (str "unexpected error " (pr-str (storage/error-describe e)))))))

      (fail! (str "unknown mode " (pr-str mode))))))
