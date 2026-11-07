(ns hc.client.conversation.attachments-storage-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.client.conversation.attachments :as attachments]
            [hc.client.conversation.attachments.storage :as storage]))

(use-fixtures :each (fn [f] (storage/reset-memory!) (f)))

(defn- blob-id
  [character]
  (str "sha256-" (apply str (repeat 64 character))))

(deftest a-queue-round-trips-without-file-handles-or-previews
  (let [queue {:items [{:id "att-1"
                        :attachment-id "att-1"
                        :name "a.txt"
                        :mime "text/plain"
                        :size 10
                        :status :ready
                        :ready? true
                        :local-copy? true
                        :blob-id (blob-id "a")
                        :extraction {:status :ready :text "confirmed"}
                        :preview {:status :ready :text "not persisted"}
                        :file :opaque-handle}
                       {:id "att-2"
                        :name "b.png"
                        :mime "image/png"
                        :size 20
                        :status :adding
                        :ready? false}]
                :next-seq 2
                :pending-send {:ids ["att-1"] :commands ["cmd-1"]}
                :tombstones #{"att-0"}
                :limits attachments/default-limits}
        decoded (storage/decode (storage/encode queue))]
    (is (= ["att-1" "att-2"] (mapv :id (:items decoded))))
    (is (= "confirmed" (get-in decoded [:items 0 :extraction :text])))
    (is (nil? (get-in decoded [:items 0 :preview])))
    (is (nil? (get-in decoded [:items 0 :file])))
    (is (= :failed (get-in decoded [:items 1 :status])))
    (is (= :UPLOAD_INTERRUPTED (get-in decoded [:items 1 :error :code])))
    (is (false? (get-in decoded [:items 1 :ready?])))
    (is (= ["att-1"] (get-in decoded [:pending-send :ids])))
    (is (= ["cmd-1"] (get-in decoded [:pending-send :commands])))
    (is (= #{"att-0"} (:tombstones decoded)))))

(deftest corrupt-or-foreign-storage-is-not-treated-as-an-empty-success
  (is (nil? (storage/decode "not edn")))
  (is (nil? (storage/decode "{:items 1}")))
  (is (nil? (storage/decode "[]"))))

(deftest memory-store-survives-session-switch
  (let [queue (:queue (attachments/add-file (attachments/initial)
                                            {:name "a.txt" :size 1}))]
    (is (= {:ok? true} (storage/save-queue! "s1" queue)))
    (is (true? (:found? (storage/load-queue "s1"))))
    (is (= 1 (attachments/total (:queue (storage/load-queue "s1")))))
    (is (false? (:found? (storage/load-queue "s2"))))
    (storage/save-queue! "s1" (attachments/initial))
    (is (true? (:found? (storage/load-queue "s1"))))
    (is (zero? (attachments/total (:queue (storage/load-queue "s1")))))
    (storage/clear-queue! "s1")
    (is (false? (:found? (storage/load-queue "s1"))))))

(deftest memory-cache-is-a-bounded-lru
  (dotimes [i 25]
    (storage/save-queue! (str "s" i)
                         {:items [{:id (str "att-" i) :size 1}]
                          :limits attachments/default-limits}))
  (is (<= (:entries (storage/cache-stats))
          storage/max-cache-entries))
  (is (false? (:found? (storage/load-queue "s0"))))
  (is (true? (:found? (storage/load-queue "s24")))))

(deftest memory-cache-evicts-over-the-byte-limit
  (let [big (* 40 1024 1024)]
    (storage/save-queue! "a" {:items [{:id "att-a" :size big}]
                              :limits attachments/default-limits})
    (storage/save-queue! "b" {:items [{:id "att-b" :size big}]
                              :limits attachments/default-limits})
    (is (<= (:bytes (storage/cache-stats))
            storage/max-cache-bytes))
    (is (= 1 (:entries (storage/cache-stats))))
    (is (false? (:found? (storage/load-queue "a"))))
    (is (true? (:found? (storage/load-queue "b"))))))

(deftest memory-cache-touch-keeps-a-recently-used-entry
  (dotimes [i 20]
    (storage/save-queue! (str "s" i)
                         {:items [{:id (str "att-" i) :size 1}]
                          :limits attachments/default-limits}))
  (is (true? (:found? (storage/load-queue "s0"))))
  (storage/save-queue! "s20"
                       {:items [{:id "att-20" :size 1}]
                        :limits attachments/default-limits})
  (is (true? (:found? (storage/load-queue "s0"))))
  (is (false? (:found? (storage/load-queue "s1"))))
  (is (true? (:found? (storage/load-queue "s20")))))

(deftest client-identity-separates-two-tabs-on-one-session
  (let [a (:queue (attachments/add-file (attachments/initial)
                                        {:name "a.txt" :size 1}))
        b (:queue (attachments/add-file (attachments/initial)
                                        {:name "b.txt" :size 2}))]
    (storage/save-queue! "s1" a {:client-id "tab-a"})
    (storage/save-queue! "s1" b {:client-id "tab-b"})
    (is (= ["att-1"] (mapv :id (:items (:queue (storage/load-queue
                                                "s1" {:client-id "tab-a"}))))))
    (is (= ["b.txt"] (mapv :name (:items (:queue (storage/load-queue
                                                  "s1" {:client-id "tab-b"}))))))
    (storage/clear-queue! "s1" {:client-id "tab-a"})
    (is (false? (:found? (storage/load-queue "s1" {:client-id "tab-a"}))))
    (is (true? (:found? (storage/load-queue "s1" {:client-id "tab-b"}))))))

(deftest restore-refuses-a-ready-card-without-a-canonical-blob
  (let [decoded (storage/decode
                 (storage/encode
                  {:items [{:id "att-1" :name "a.txt" :status :ready
                            :ready? true :local-copy? true
                            :blob-id "not-a-blob"}]
                   :next-seq 1
                   :limits {:max-items 999 :max-bytes (* 999 1024 1024)
                            :max-pages nil}}))
        item (first (:items decoded))]
    (is (= :needs-choice (:status item)))
    (is (false? (:ready? item)))
    (is (false? (:local-copy? item)))
    (is (= 20 (get-in decoded [:limits :max-items])))
    (is (= attachments/max-attachment-bytes
           (get-in decoded [:limits :max-bytes])))))
