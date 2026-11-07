(ns hc.server.blob-store-refs-test
  "Reference extraction, atomic session reference sync, and coordinated
  cleanup over a real Datalevin store and a real blob root."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.session-persistence :as persistence]
            [hc.server.blob-store :as blob]
            [hc.server.blob-store.coordinator :as coordinator]
            [hc.server.blob-store.layout :as layout]
            [hc.server.blob-store.refs :as refs]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileTime)))

(defn- utf8
  ^bytes [text]
  (.getBytes ^String text "UTF-8"))

(defn- set-mtime!
  [path millis]
  (Files/setLastModifiedTime (layout/path-of path) (FileTime/fromMillis millis)))

(defn- ref-ids
  [store]
  (->> (storage/manage-blob-refs! store {:partition "sessions" :op :list})
       :refs
       (map :id)
       set))

(deftest session-ref-extraction-is-structural
  (let [id1 (blob/id-of-bytes (utf8 "one"))
        id2 (blob/id-of-bytes (utf8 "two"))
        prepared (blob/id-of-bytes (utf8 "prepared"))
        stray (blob/id-of-bytes (utf8 "stray"))
        session {:session-id "s1"
                 :text (str "user text mentions " stray)
                 :attachments [{:attachment-id "top" :blob-id id1
                                :size 3 :sha256 "a"}]
                 :drafts {"d1" {:attachments
                                [{:attachment-id "draft"
                                  :blob-id id2
                                  :size 3
                                  :sha256 "b"
                                  :prepared {:blob-id prepared
                                             :size 8
                                             :sha256 "c"}}]}}
                 :messages {"m1" {:attachments
                                  [{:attachment-id "message"
                                    :blob-id id1
                                    :size 3
                                    :sha256 "a"}]
                                  :versions
                                  [{:attachments
                                    [{:attachment-id "old"
                                      :blob-id id2
                                      :size 3
                                      :sha256 "b"}]}]}}}
        result (refs/session-refs session)]
    (is (= #{id1 id2 prepared} (set (keys result))))
    (is (not (contains? result stray))
        "a content-ID-shaped string in user text is not a reference")
    (is (= 3 (get-in result [id1 :size])))
    (is (= "draft" (get-in result [prepared :attachment-id]))
        "the prepared copy keeps its parent attachment identity")
    (is (= #{id1 id2 prepared}
           (set (keys (refs/sessions-refs [session
                                           {:session-id "s2"
                                            :attachments [{:blob-id "not-an-id"}]}])))))))

(deftest reference-diff-and-export-shapes
  (let [a (blob/id-of-bytes (utf8 "a"))
        b (blob/id-of-bytes (utf8 "b"))
        before {a {:id a :size 1}}
        after {b {:id b :size 2}}
        change (refs/diff before after)]
    (is (= [b] (mapv :id (:add change))))
    (is (= [a] (:release change)))
    (is (= [] (:add (refs/diff before before))))
    (is (= [{:id b :doc {:id b :size 2}}]
           (refs/export-entries after)))
    (is (= #{a b}
           (refs/export-refs
            {:partitions
             {"p" {:blob-refs [{:id a :doc {:id a}}
                               {:id b :doc {:id b}}]}
              "q" {:blob-refs []}}})))))

(deftest session-commits-add-and-release-attachment-refs
  (let [dir (support/temp-dir "hc-ref-session-")
        blob-root (str dir "/blobs")
        store (storage/open-store {:path (str dir "/db") :create? true})]
    (try
      (let [p (persistence/persistence store)
            original (blob/put-bytes! blob-root (utf8 "original"))
            prepared (blob/put-bytes! blob-root (utf8 "prepared"))
            session {:session-id "s1"
                     :revision 0
                     :drafts {"d" {:attachments
                                   [{:attachment-id "att1"
                                     :blob-id (:id original)
                                     :size (:size original)
                                     :sha256 (:sha256 original)
                                     :prepared {:blob-id (:id prepared)
                                                :size (:size prepared)
                                                :sha256 (:sha256 prepared)}}]}}
                     :messages {}}
            committed (ports/commit-session! p 0 session)]
        (is (= :committed (:status committed)))
        (is (= #{(:id original) (:id prepared)} (ref-ids store))
            "both the original and the prepared copy are referenced atomically")
        (testing "removing the draft attachment releases both references"
          (let [stored (:session (ports/read-session p "s1"))
                updated (ports/commit-session!
                         p
                         (:revision stored)
                         (assoc stored :revision 1 :drafts {}))]
            (is (= :committed (:status updated)))
            (is (empty? (ref-ids store)))))
        (testing "two sessions sharing a blob keep the reference until the last is deleted"
          (let [s1 {:session-id "s1" :revision 2
                    :attachments [{:attachment-id "a"
                                   :blob-id (:id original)
                                   :size (:size original)}]}
                s2 {:session-id "s2" :revision 0
                    :attachments [{:attachment-id "b"
                                   :blob-id (:id original)
                                   :size (:size original)}]}]
            (let [stored (-> (ports/read-session p "s1") :session)]
              (is (= :committed
                     (:status (ports/commit-session!
                               p (:revision stored) s1)))))
            (is (= :committed (:status (ports/commit-session! p 0 s2))))
            (is (= #{(:id original)} (ref-ids store)))
            (let [stored (-> (ports/read-session p "s1") :session)]
              (is (= :deleted
                     (:status (ports/delete-session! p
                                                           (:revision stored)
                                                           "s1"))))
              (is (= #{(:id original)} (ref-ids store))
                  "s2 still references the shared blob"))
            (let [stored (-> (ports/read-session p "s2") :session)]
              (is (= :deleted
                     (:status (ports/delete-session! p
                                                           (:revision stored)
                                                           "s2"))))
              (is (empty? (ref-ids store)))))))
      (finally
        (storage/close-store! store)
        (support/delete-tree! dir)))))

(deftest coordinated-cleanup-uses-the-storage-reference-table
  (let [dir (support/temp-dir "hc-ref-cleanup-")
        blob-root (str dir "/blobs")
        store (storage/open-store {:path (str dir "/db") :create? true})]
    (try
      (let [kept (blob/put-bytes! blob-root (utf8 "kept"))
            orphan (blob/put-bytes! blob-root (utf8 "orphan"))]
        (storage/manage-blob-refs! store
                                   {:partition "sessions"
                                    :op :add
                                    :refs [{:id (:id kept) :size (:size kept)}]})
        (set-mtime! (layout/object-path blob-root (:id kept)) 1000000)
        (set-mtime! (layout/object-path blob-root (:id orphan)) 1000000)
        (is (= #{(:id kept)} (coordinator/storage-refs store)))
        (let [result (coordinator/cleanup-storage!
                      store blob-root
                      {:safety-window-ms 0 :now 2000000})]
          (is (= :completed (:outcome result)))
          (is (= [(:id orphan)] (:deleted result)))
          (is (true? (blob/exists? blob-root (:id kept))))
          (is (false? (blob/exists? blob-root (:id orphan))))
          (is (= 1 (get-in result [:storage :refs]))))
        (testing "releasing the last reference makes the object collectable"
          (storage/manage-blob-refs! store
                                     {:partition "sessions"
                                      :op :release
                                      :refs [(:id kept)]})
          (let [result (coordinator/cleanup-storage!
                        store blob-root
                        {:safety-window-ms 0 :now 2000000})]
            (is (= [(:id kept)] (:deleted result)))
            (is (false? (blob/exists? blob-root (:id kept)))))))
      (finally
        (storage/close-store! store)
        (support/delete-tree! dir)))))
