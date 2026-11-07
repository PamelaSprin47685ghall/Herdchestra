(ns hc.client.conversation.attachments-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.client.conversation.attachments :as attachments]
            [hc.server.attachments.model :as server]))

(defn- blob-id
  [character]
  (str "sha256-" (apply str (repeat 64 character))))

(defn- queue-with
  [n]
  (reduce (fn [queue index]
            (:queue (attachments/add-file queue
                                          {:name (str "f" index ".txt")
                                           :mime "text/plain"
                                           :size 10})))
          (attachments/initial)
          (range n)))

(defn- uploaded
  [queue id name mime size]
  (attachments/mark-uploaded
   queue id
   {:id id :attachment-id id :name name :mime mime :size size
    :blob-id (blob-id "a") :local-copy? true :status :ready
    :media {:kind (attachments/mime->kind mime)}}))

(deftest a-draft-holds-twenty-items-and-refuses-the-twenty-first
  (let [queue (queue-with 20)
        result (attachments/add-file queue {:name "extra.txt" :size 10})]
    (is (= 20 (attachments/total queue)))
    (is (false? (:ok? result)))
    (is (= :ATTACHMENT_LIMIT_EXCEEDED (get-in result [:error :code])))
    (is (= 20 (attachments/total (:queue result))))))

(deftest one-hundred-mib-is-allowed-and-one-byte-more-is-not
  (is (:ok? (attachments/add-file (attachments/initial)
                                  {:name "big.bin"
                                   :size attachments/max-attachment-bytes})))
  (let [result (attachments/add-file (attachments/initial)
                                     {:name "too-big.bin"
                                      :size (inc attachments/max-attachment-bytes)})]
    (is (false? (:ok? result)))
    (is (= :ATTACHMENT_TOO_LARGE (get-in result [:error :code])))))

(deftest duplicate-addition-is-flagged-but-not-silently-dropped
  (let [first (:queue (attachments/add-file (attachments/initial)
                                            {:name "same.txt" :size 10
                                             :fingerprint "sha"}))
        second (attachments/add-file first {:name "same.txt" :size 10
                                            :fingerprint "sha"})]
    (is (:ok? second))
    (is (= "att-1" (:duplicate-of second)))
    (is (= 2 (attachments/total (:queue second))))))

(deftest upload-failure-and-exclusion-are-visible
  (let [queue (:queue (attachments/add-file (attachments/initial)
                                            {:name "a.png" :mime "image/png" :size 10}))
        failed (attachments/mark-failed queue "att-1"
                                        {:code :ATTACHMENT_STORE_FAILED
                                         :message "store unavailable"})
        summary (attachments/send-summary failed)]
    (is (= :failed (-> failed :items first :status)))
    (is (false? (-> failed :items first :ready?)))
    (is (= 1 (:blocked summary)))
    (is (not (:sendable? summary)))
    (let [excluded (attachments/exclude failed "att-1")
          summary (attachments/send-summary excluded)]
      (is (true? (-> excluded :items first :excluded?)))
      (is (zero? (:blocked summary)))
      (is (true? (:sendable? summary)))
      (is (false? (:has-ready? summary))))))

(deftest remove-does-not-delete-anything-else
  (let [queue (uploaded (queue-with 2) "att-1" "a.txt" "text/plain" 10)
        removed (attachments/remove-item queue "att-1")]
    (is (= ["att-2"] (mapv :id (:items removed))))
    (is (= 2 (attachments/total queue)))))

(deftest a-real-descriptor-is-required-before-ready
  (let [queue (:queue (attachments/add-file (attachments/initial)
                                            {:name "a.txt" :mime "text/plain" :size 10}))
        fake (attachments/mark-uploaded queue "att-1"
                                        {:id "att-1" :name "a.txt" :mime "text/plain"
                                         :size 10 :local-copy? true :blob-id "not-a-blob"})]
    (is (= :failed (-> fake :items first :status)))
    (is (false? (-> fake :items first :ready?)))
    (let [real (uploaded queue "att-1" "a.txt" "text/plain" 10)]
      (is (= :ready (-> real :items first :status)))
      (is (true? (-> real :items first :ready?))))))

(deftest model-compatibility-blocks-unknown-and-unsupported
  (let [queue (-> (attachments/initial)
                  (attachments/add-file {:name "a.png" :mime "image/png" :size 10})
                  :queue
                  (uploaded "att-1" "a.png" "image/png" 10))
        unknown (attachments/apply-model queue {})
        unsupported (attachments/apply-model queue {:capabilities #{:audio}})
        native (attachments/apply-model queue {:capabilities #{:vision}})]
    (is (= :needs-choice (-> unknown :items first :status)))
    (is (= :unknown (-> unknown :items first :route)))
    (is (= :unsupported (-> unsupported :items first :status)))
    (is (= :ready (-> native :items first :status)))
    (is (true? (-> native :items first :ready?)))))

(deftest send-summary-keeps-failed-items-in-the-total
  (let [queue (-> (queue-with 3)
                  (uploaded "att-1" "a.txt" "text/plain" 10)
                  (uploaded "att-2" "b.txt" "text/plain" 10)
                  (attachments/mark-failed "att-3" {:message "broken"}))
        summary (attachments/send-summary queue)]
    (is (= 3 (:total summary)))
    (is (= 2 (:ready summary)))
    (is (= 1 (:blocked summary)))
    (is (= "已加入 3 个，1 个需要处理" (:message summary)))
    (is (not (:sendable? summary)))))

(deftest snapshot-carries-only-active-items-and-real-text
  (let [queue (-> (attachments/initial)
                  (attachments/add-file {:name "a.txt" :mime "text/plain" :size 10})
                  :queue
                  (uploaded "att-1" "a.txt" "text/plain" 10)
                  (attachments/mark-extracted "att-1" {:status :ready :text "confirmed"})
                  (attachments/apply-model {})
                  (attachments/add-file {:name "b.txt" :mime "text/plain" :size 10})
                  :queue
                  (attachments/exclude "att-2"))
        snapshots (attachments/snapshot queue)]
    (is (= 1 (count snapshots)))
    (is (= "att-1" (:id (first snapshots))))
    (is (= "confirmed" (:send-text (first snapshots))))))

(deftest snapshot-keeps-the-blob-link-and-drops-card-labels
  (let [url (str "/blobs/" (blob-id "a") "?filename=pixel.png")
        queue (-> (attachments/initial)
                  (attachments/add-file {:name "pixel.png"
                                         :mime "image/png"
                                         :size 70})
                  :queue
                  (attachments/mark-uploaded
                   "att-1"
                   {:id "att-1"
                    :attachment-id "att-1"
                    :name "pixel.png"
                    :mime "image/png"
                    :size 70
                    :blob-id (blob-id "a")
                    :local-copy? true
                    :status :ready
                    :media {:kind :image}
                    :url url}))
        item (first (attachments/snapshot queue))]
    (is (= url (:url item))
        "the history view can link the immutable blob after the send")
    (is (not (contains? item :status-label))
        "a composer card label is not message data")
    (is (not (contains? item :remove-label))
        "a composer control label is not message data")))

(deftest existing-draft-attachments-are-adopted-without-inventing-readiness
  (let [draft {:attachments [{:id "att-1" :name "a.txt" :mime "text/plain"
                              :size 10 :status :ready :ready? true
                              :local-copy? true :blob-id (blob-id "a")}
                             {:id "att-2" :name "b.png" :mime "image/png"
                              :size 10 :status :failed :ready? false}]}
        queue (attachments/from-draft draft)]
    (is (= 2 (attachments/total queue)))
    (is (true? (-> queue :items first :ready?)))
    (is (false? (-> queue :items second :ready?)))
    (is (= :failed (-> queue :items second :status)))
    (let [added (:queue (attachments/add-file queue {:name "new.txt" :size 1}))]
      (is (= ["att-1" "att-2" "att-3"] (mapv :id (:items added)))))))

(deftest upload-callbacks-merge-into-the-latest-queue
  (let [base {:items [{:id "att-1" :name "a.txt" :status :ready :ready? true
                       :extraction {:status :ready :text "confirmed"}}
                      {:id "att-3" :name "kept.txt" :status :ready :ready? true}]
              :next-seq 3}
        incoming {:items [{:id "att-1" :name "a.txt" :status :ready :ready? true
                           :extraction nil}
                          {:id "att-2" :name "new.txt" :status :ready :ready? true}]
                  :next-seq 2}
        merged (attachments/merge-items base incoming)]
    (is (= ["att-1" "att-3" "att-2"] (mapv :id (:items merged))))
    (is (= "confirmed" (get-in merged [:items 0 :extraction :text])))
    (is (= 3 (:next-seq merged)))))

(deftest stale-upload-callback-cannot-overwrite-local-route-or-exclusion
  (let [base {:items [{:id "att-1" :name "a.pdf" :status :ready :ready? true
                       :excluded? true :route :text :send-route :text
                       :extraction {:status :ready :text "confirmed"}}]
              :next-seq 1}
        incoming {:items [{:id "att-1" :name "a.pdf" :status :adding
                           :ready? false :excluded? false
                           :route :native :send-route :native
                           :extraction nil}]
                  :next-seq 1}
        merged (attachments/merge-items base incoming)
        item (first (:items merged))]
    (is (= :ready (:status item)))
    (is (true? (:ready? item)))
    (is (true? (:excluded? item)))
    (is (= :text (:route item)))
    (is (= "confirmed" (get-in item [:extraction :text])))))

(deftest local-id-allocation-skips-an-existing-id
  (let [queue {:items [{:id "att-1" :name "a.txt" :status :ready}]
               :next-seq 0}
        result (attachments/add-file queue {:name "b.txt" :size 1})]
    (is (:ok? result))
    (is (= "att-2" (get-in result [:item :id])))
    (is (= 2 (get-in result [:queue :next-seq])))))

(deftest partial-pdf-offers-page-ocr-and-text-route
  (let [item {:id "att-1" :name "a.pdf" :mime "application/pdf" :size 10
              :local-copy? true :blob-id (blob-id "a")
              :status :ready :ready? true
              :route :native :send-route :native
              :extraction {:status :ready :text "page one"
                           :pages [{:page 1 :status :text}
                                   {:page 2 :status :no-text}]
                           :warnings [{:code :PAGES_WITHOUT_TEXT}]}
              :compatibility {:native? true :text-capable? true}}
        card (attachments/card item)]
    (is (true? (:can-extract? card)))
    (is (= :ocr (:extract-route card)))
    (is (true? (:can-switch-text? card)))
    (is (false? (:can-switch-native? card)))))

(deftest preferred-text-route-is-visible-before-native
  (let [pdf {:name "a.pdf" :mime "application/pdf" :size 10
             :local-copy? true :blob-id (blob-id "a")
             :extraction {:status :ready :text "page"}
             :preferred-route :text}]
    (is (= :text (:route (attachments/compatibility
                          pdf {:capabilities #{:document :text}}))))))

(deftest successful-extraction-recovers-a-failed-card
  (let [queue {:items [{:id "att-1" :name "a.pdf" :status :failed
                        :ready? false :local-copy? true
                        :blob-id (blob-id "a")}]
               :next-seq 1}
        updated (attachments/mark-extracted
                 queue "att-1" {:status :ready :text "recognized"})
        item (first (:items updated))]
    (is (= :ready (:status item)))
    (is (true? (:ready? item)))))

(deftest draft-merge-does-not-resurrect-removed-or-sent-items
  (let [queue {:items [{:id "att-1" :name "a.txt" :status :ready :ready? true}]
               :next-seq 3
               :tombstones #{"att-2"}}
        draft {:attachments [{:id "att-1" :name "a.txt" :status :ready :ready? true}
                             {:id "att-2" :name "removed.txt" :status :ready :ready? true}
                             {:id "att-3" :name "new.txt" :status :ready :ready? true}]}
        merged (attachments/merge-draft queue draft)]
    (is (= ["att-1" "att-3"] (mapv :id (:items merged))))
    (is (= #{"att-2"} (:tombstones merged)))
    (is (= 3 (:next-seq merged)))))

(deftest clearing-a-sent-snapshot-keeps-newer-items
  (let [queue (-> (queue-with 2)
                  (uploaded "att-1" "a.txt" "text/plain" 10)
                  (uploaded "att-2" "b.txt" "text/plain" 10))
        cleared (attachments/clear-sent queue #{"att-1"})]
    (is (= ["att-2"] (mapv :id (:items cleared))))
    (is (= 2 (attachments/total queue)))))

(defn- compatibility-fixtures
  []
  (let [base {:attachment-id "att" :name "a" :size 10
              :blob-id (blob-id "a") :local-copy? true :status :ready}
        workspace {:root-id "ws" :relative-path "a.txt"
                   :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}]
    [{:label :text-ready
      :item (assoc base :mime "text/plain" :media {:kind :text}
                   :extraction {:status :ready :text "hello"})
      :model {}}
     {:label :text-no-extraction
      :item (assoc base :mime "text/plain" :media {:kind :text})
      :model {}}
     {:label :text-native
      :item (assoc base :mime "text/plain" :media {:kind :text})
      :model {:capabilities #{:text}}}
     {:label :image-vision
      :item (assoc base :mime "image/png" :media {:kind :image})
      :model {:capabilities #{:vision}}}
     {:label :image-unknown
      :item (assoc base :mime "image/png" :media {:kind :image})
      :model {}}
     {:label :image-extracted
      :item (assoc base :mime "image/png" :media {:kind :image}
                   :extraction {:status :ready :text "ocr"})
      :model {:capabilities #{:text}}}
     {:label :pdf-native
      :item (assoc base :mime "application/pdf" :media {:kind :pdf})
      :model {:capabilities #{:document}}}
     {:label :pdf-extract-choice
      :item (assoc base :mime "application/pdf" :media {:kind :pdf}
                   :extractable? true)
      :model {:capabilities #{:text}}}
     {:label :pdf-extracted
      :item (assoc base :mime "application/pdf" :media {:kind :pdf}
                   :extraction {:status :ready :text "page"})
      :model {:capabilities #{:text}}}
     {:label :audio-native
      :item (assoc base :mime "audio/mpeg" :media {:kind :audio})
      :model {:capabilities #{:audio}}}
     {:label :video-native
      :item (assoc base :mime "video/mp4" :media {:kind :video})
      :model {:capabilities #{:video}}}
     {:label :file-native
      :item (assoc base :mime "application/zip" :media {:kind :archive})
      :model {:capabilities #{:file}}}
     {:label :service-limit
      :item (assoc base :mime "text/plain" :media {:kind :text} :size 20)
      :model {:capabilities #{:text} :attachment-limits {:max-bytes 10}}}
     {:label :workspace-unverified
      :item (assoc base :local-copy? false :blob-id nil :workspace workspace
                   :mime "text/plain" :media {:kind :text}
                   :extraction {:status :ready :text "hello"})
      :model {}}
     {:label :workspace-verified
      :item (assoc base :local-copy? false :blob-id nil :workspace workspace
                   :workspace-verified? true
                   :mime "text/plain" :media {:kind :text}
                   :extraction {:status :ready :text "hello"})
      :model {}}]))

(deftest client-and-server-compatibility-agree
  (doseq [{:keys [label item model]} (compatibility-fixtures)]
    (testing (name label)
      (is (= (:route (server/compatibility item model))
             (:route (attachments/compatibility item model)))
          (pr-str {:label label
                   :server (server/compatibility item model)
                   :client (attachments/compatibility item model)}))
      (is (= (:send-route (server/compatibility item model))
             (:send-route (attachments/compatibility item model)))))))

(defspec adding-files-never-exceeds-twenty
  50
  (prop/for-all [names (gen/vector gen/string-alphanumeric 0 30)]
    (let [queue (reduce (fn [queue name]
                          (if-let [result (attachments/add-file
                                           queue {:name (str name ".txt")
                                                  :size 1})]
                            (:queue result)
                            queue))
                        (attachments/initial)
                        names)]
      (<= (attachments/total queue) 20))))

(deftest card-lists-original-page-range-and-unreadable-pages
  (let [item {:id "att-1" :name "scan.pdf" :mime "application/pdf" :size 100
              :local-copy? true :blob-id (blob-id "a")
              :status :ready :ready? true
              :preferred-route :text
              :extraction {:status :ready :text "page two and five"
                           :page-count 6
                           :selected-pages [2 3 4 5]
                           :pages [{:page 2 :status :text}
                                   {:page 3 :status :no-text}
                                   {:page 4 :status :error}
                                   {:page 5 :status :text}]
                           :warnings [{:code :PAGES_WITHOUT_TEXT :pages [3]}
                                      {:code :PAGES_UNREADABLE :pages [4]}]}}
        card (attachments/card item)]
    (testing "selected pages keep the document's own numbers"
      (is (= "第 2–5 页，共 4 页" (:page-label card)))
      (is (= [3 4] (:unreadable-pages card)))
      (is (= "不可读：第 3 页、第 4 页" (:unreadable-label card))))
    (testing "a local copy is named as a saved copy"
      (is (= "已保存副本" (:copy-label card)))
      (is (nil? (:workspace-label card))))))

(deftest card-names-a-workspace-reference-and-relocation-guidance
  (let [workspace {:root-id "/work" :root-path "/work"
                   :relative-path "docs/scan.pdf"
                   :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}
        item {:id "att-2" :name "scan.pdf" :mime "application/pdf" :size 100
              :local-copy? false :workspace workspace
              :status :needs-choice :ready? false}
        card (attachments/card item)]
    (is (= "外部引用" (:copy-label card)))
    (is (re-find #"外部引用 · docs/scan\.pdf" (:workspace-label card)))
    (is (re-find #"重新定位" (:workspace-label card)))
    (is (nil? (:page-label card)))
    (is (nil? (:unreadable-label card)))))

(deftest message-attachment-facts-never-invent-pages
  (is (nil? (:page-label (attachments/attachment-facts
                          {:attachment-id "a" :name "plain.txt"}))))
  (is (nil? (:unreadable-label (attachments/attachment-facts
                                {:attachment-id "a" :name "plain.txt"}))))
  (is (nil? (:copy-label (attachments/attachment-facts
                          {:attachment-id "a" :name "plain.txt"})))))
