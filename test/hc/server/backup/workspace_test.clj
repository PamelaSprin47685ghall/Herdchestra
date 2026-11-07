(ns hc.server.backup.workspace-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.workspace :as workspace])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- delete-tree
  [root]
  (let [path (Path/of (str root) (make-array String 0))]
    (when (Files/exists path (make-array LinkOption 0))
      (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
        (doseq [entry (->> (iterator-seq (.iterator stream))
                           (sort-by #(.getNameCount ^Path %))
                           reverse)]
          (try (Files/deleteIfExists entry) (catch Exception _ nil)))))))

(defmacro with-temp-dir
  [[sym] & body]
  `(let [~sym (str (Files/createTempDirectory "hc-workspace-test-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree ~sym)))))

(defn- write-file!
  [root relative text]
  (let [path (Path/of (str root) (make-array String 0))
        target (.resolve path relative)]
    (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
    (Files/write target (.getBytes (str text) "UTF-8")
                 (make-array java.nio.file.OpenOption 0))
    target))

(defn- read-text
  [root relative]
  (slurp (str (Path/of (str root) (make-array String 0))
              "/" relative)))

(deftest managed-and-external-workspace-roundtrip
  (with-temp-dir [root]
    (let [source-root (str root "/source-workspaces")
          target-root (str root "/target-workspaces")
          external (str root "/external-source")
          _ (write-file! source-root "managed/ws1/notes/a.txt" "managed a")
          _ (write-file! source-root "managed/ws1/root.txt" "managed root")
          _ (write-file! external "external.txt" "external content")
          missing-external (str root "/missing-external")
          extras [{:id "ws1" :name "Managed" :kind :managed}
                  {:id "ext1" :name "Linked" :kind :external :path external}
                  {:id "ext-missing" :name "Missing linked" :kind :external
                   :path missing-external}]
          source (workspace/store source-root {:workspaces extras})
          target (workspace/store target-root {:workspaces extras})
          default-items (workspace/read-workspaces source {})
          external-included (workspace/read-workspaces
                             source {:include-external-files? true})]
      (testing "managed content is copied; external content is config-only by default"
        (let [managed (first (filter #(= "ws1" (:id %)) default-items))
              ext (first (filter #(= "ext1" (:id %)) default-items))]
          (is (= #{"notes/a.txt" "root.txt"} (set (keys (:files-content managed)))))
          (is (true? (:files-included? managed)))
          (is (false? (:files-included? ext)))
          (is (empty? (:files-content ext)))
          (is (true? (:relink-required? ext)))
          (is (false? (:authorized? ext)))))
      (testing "external files are copied only when explicitly requested"
        (let [ext (first (filter #(= "ext1" (:id %)) external-included))]
          (is (true? (:files-included? ext)))
          (is (= #{"external.txt"} (set (keys (:files-content ext)))))))
      (testing "restore writes managed files and only relinks external paths"
        (workspace/restore-workspaces! target external-included)
        (is (= "managed a" (read-text target-root "managed/ws1/notes/a.txt")))
        (is (= "managed root" (read-text target-root "managed/ws1/root.txt")))
        (is (= "external content"
               (read-text target-root "external-imports/ext1/external.txt")))
        (is (not (Files/exists (Path/of missing-external (make-array String 0))
                               (make-array LinkOption 0))))
        (let [registry (workspace/read-workspaces target {})
              ext (first (filter #(= "ext1" (:id %)) registry))]
          (is (= external (:path ext)))
          (is (true? (:relink-required? ext)))
          (is (false? (:authorized? ext))))))))


(deftest missing-managed-workspace-is-not-silently-empty
  (with-temp-dir [root]
    (let [store (workspace/store (str root "/store")
                                 {:workspaces [{:id "missing" :kind :managed
                                                :name "Missing managed"}]})
          items (workspace/read-workspaces store {})]
      (is (true? (:missing? (first items))))
      (is (false? (:files-included? (first items))))
      (is (= :applied (:status (workspace/restore-workspaces! store items))))
      (let [registry (workspace/read-workspaces store {})
            entry (first (filter #(= "missing" (:id %)) registry))]
        (is (some? entry)
            "a merge preserves the registry entry instead of deleting it")
        (is (true? (:missing? entry)))
        (is (not (Files/exists (Path/of (str root "/store/managed/missing")
                                        (make-array String 0))
                               (make-array LinkOption 0)))
            "restore never creates a directory for an unreadable workspace")))))

(deftest external-workspace-needs-a-path
  (is (= :BACKUP_WORKSPACE_INVALID
         (try
           (workspace/store "/tmp/unused"
                            {:workspaces [{:id "ext" :kind :external}]})
           ::no-throw
           (catch clojure.lang.ExceptionInfo e
             (:error (ex-data e)))))))

(deftest duplicate-workspace-ids-are-refused
  (with-temp-dir [root]
    (let [store (workspace/store (str root "/store")
                                 {:workspaces [{:id "ws1" :kind :managed}
                                               {:id "ws2" :kind :managed}]})
          items [{:id "ws1" :kind :managed :name "A" :files-content {}}
                 {:id "ws1" :kind :managed :name "B" :files-content {}}]]
      (is (= :BACKUP_WORKSPACE_INVALID
             (try
               (workspace/restore-workspaces! store items)
               ::no-throw
               (catch clojure.lang.ExceptionInfo e
                 (:error (ex-data e)))))))))

(deftest unsafe-workspace-file-paths-are-refused
  (with-temp-dir [root]
    (let [store (workspace/store (str root "/store"))
          item {:id "ws1" :kind :managed :name "Unsafe"
                :files-content {"." {:bytes-base64 "aGk="
                                      :size 2
                                      :sha256 "sha256-x"}}}]
      (is (= :BACKUP_WORKSPACE_INVALID
             (try
               (workspace/restore-workspaces! store [item])
               ::no-throw
               (catch clojure.lang.ExceptionInfo e
                 (:error (ex-data e)))))))))

(deftest failed-preflight-does-not-delete-existing-workspace
  (with-temp-dir [root]
    (let [store-root (str root "/store")
          store (workspace/store store-root
                                 {:workspaces [{:id "ws1" :kind :managed}]})
          _ (write-file! store-root "managed/ws1/keep.txt" "keep")
          item {:id "ws1" :kind :managed :name "Unsafe"
                :files-content {"." {:bytes-base64 "aGk="
                                      :size 2
                                      :sha256 "sha256-x"}}}]
      (is (= :BACKUP_WORKSPACE_INVALID
             (try
               (workspace/restore-workspaces! store [item])
               ::no-throw
               (catch clojure.lang.ExceptionInfo e
                 (:error (ex-data e))))))
      (is (= "keep" (read-text store-root "managed/ws1/keep.txt"))))))
