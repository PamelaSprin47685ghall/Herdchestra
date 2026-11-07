(ns hc.server.storage.support
  "Temporary-store helpers for storage and recovery tests.

  Every test creates its own directory under the system temporary directory
  and removes it afterwards.  No test touches a user project or a real store."

  (:require [clojure.java.io :as io]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn temp-dir
  "Create a fresh temporary directory and return its path string."
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn delete-tree!
  "Delete `path` recursively.  Missing paths are ignored."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [x (reverse (file-seq f))]
        (io/delete-file x true)))))

(defmacro with-temp-store
  "Open a store in a fresh temporary directory, run `body`, close and delete.
  `opts` is merged over `{:create? true :durability :sync}`."
  [[store-sym opts] & body]
  `(let [dir# (temp-dir "hc-store-")
         ~store-sym (storage/open-store (merge {:path dir# :create? true}
                                               ~opts))]
     (try
       ~@body
       (finally
         (try (storage/close-store! ~store-sym) (catch Throwable ~'_ nil))
         (delete-tree! dir#)))))

(defn commit!
  "Commit a small document migration and return the result."
  [store migration-id partition session-id kind id doc]
  (storage/commit-migration! store
                             {:migration-id migration-id
                              :partition partition
                              :session-id session-id
                              :put [{:kind kind :id id :doc doc}]}))
