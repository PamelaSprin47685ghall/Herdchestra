(ns hc.server.attachments.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.attachments.model :as model]
            [hc.server.sessions.drafts :as drafts]))

(defn- blob-id
  [character]
  (str "sha256-" (apply str (repeat 64 character))))

(defn- attachment
  ([] (attachment {}))
  ([overrides]
   (merge {:attachment-id "att-1"
           :name "notes.txt"
           :mime "text/plain"
           :size 12
           :blob-id (blob-id "a")
           :local-copy? true
           :status :ready
           :extraction {:status :ready :text "hello"}
           :media {:kind :text}}
          overrides)))

(deftest product-limits-match-the-session-draft-contract
  (is (= drafts/max-attachments model/max-attachments))
  (is (= drafts/max-attachment-bytes model/max-attachment-bytes))
  (is (= 20 model/max-attachments))
  (is (= (* 100 1024 1024) model/max-attachment-bytes)))

(deftest names-are-display-safe
  (is (= "report.pdf" (model/normalize-name "/tmp/secret/report.pdf")))
  (is (= "report.pdf" (model/normalize-name "C:\\Users\\me\\report.pdf")))
  (is (= "a b.txt" (model/normalize-name "  a b.txt  ")))
  (is (nil? (model/normalize-name "   ")))
  (is (nil? (model/normalize-name nil)))
  (is (= 255 (count (model/normalize-name (apply str (repeat 400 "x")))))))

(deftest mime-types-are-normalized-and-classified
  (is (= "image/png" (model/normalize-mime "IMAGE/PNG; charset=binary")))
  (is (nil? (model/normalize-mime "not a mime")))
  (is (= :image (model/mime->kind "image/jpeg")))
  (is (= :pdf (model/mime->kind "application/pdf")))
  (is (= :text (model/mime->kind "application/json")))
  (is (= :audio (model/mime->kind "audio/mpeg")))
  (is (= :video (model/mime->kind "video/mp4")))
  (is (= :archive (model/mime->kind "application/zip")))
  (is (= :file (model/mime->kind "application/octet-stream")))
  (is (model/text-extractable? "text/plain" "a.txt"))
  (is (model/text-extractable? "application/octet-stream" "a.md"))
  (is (not (model/text-extractable? "application/pdf" "a.pdf")))
  (is (not (model/text-extractable? "image/png" "a.png"))))

(deftest descriptor-validation-refuses-dishonest-sources
  (is (empty? (model/descriptor-issues (attachment))))
  (is (some #(= :ATTACHMENT_BLOB_ID_INVALID (:code %))
            (model/descriptor-issues (attachment {:blob-id "not-a-blob"}))))
  (is (some #(= :ATTACHMENT_TOO_LARGE (:code %))
            (model/descriptor-issues
             (attachment {:size (inc model/max-attachment-bytes)}))))
  (is (some #(= :ATTACHMENT_NAME_INVALID (:code %))
            (model/descriptor-issues (attachment {:name "  "}))))
  (is (some #(= :ATTACHMENT_SOURCE_INVALID (:code %))
            (model/descriptor-issues
             (attachment {:local-copy? false :blob-id nil}))))
  (is (empty? (model/descriptor-issues
               (attachment {:local-copy? false
                            :blob-id nil
                            :workspace {:root-id "ws"
                                        :relative-path "a.txt"
                                        :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}})))))

(deftest compatibility-never-treats-unknown-as-support
  (let [image (attachment {:name "a.png" :mime "image/png"
                           :media {:kind :image}
                           :extraction nil})]
    (is (= :native (:route (model/compatibility image {:capabilities #{:vision}}))))
    (is (= :unknown (:route (model/compatibility image {}))))
    (is (= :unsupported (:route (model/compatibility image {:capabilities #{:audio}}))))
    (is (= :text (:route (model/compatibility
                          (assoc image :extraction {:status :ready :text "diagram"})
                          {:capabilities #{:text}}))))))

(deftest compatibility-distinguishes-native-and-extraction
  (let [pdf (attachment {:name "a.pdf" :mime "application/pdf"
                         :media {:kind :pdf}
                         :extraction nil
                         :extractable? true})]
    (is (= :native (:route (model/compatibility pdf {:capabilities #{:document}}))))
    (is (= :needs-choice (:route (model/compatibility pdf {:capabilities #{:text}}))))
    (is (= :text (:route (model/compatibility
                          (assoc pdf :extraction {:status :ready :text "page text"})
                          {:capabilities #{:text}})))))
  (let [audio (attachment {:name "a.mp3" :mime "audio/mpeg"
                           :media {:kind :audio}
                           :extraction nil})]
    (is (= :native (:route (model/compatibility audio {:capabilities #{:audio}}))))
    (is (= :unsupported (:route (model/compatibility audio {:capabilities #{:vision}}))))))

(deftest explicit-text-route-wins-over-native
  (let [pdf (attachment {:name "a.pdf" :mime "application/pdf"
                         :media {:kind :pdf}
                         :extraction {:status :ready :text "page text"}})
        model {:capabilities #{:document :text}}]
    (is (= :native (:route (model/compatibility pdf model))))
    (is (= :text (:route (model/compatibility
                          (assoc pdf :preferred-route :text)
                          model))))
    (is (= :native (:route (model/compatibility
                            (assoc pdf :preferred-route :native)
                            model))))))

(deftest preferred-text-route-produces-send-text
  (let [item (attachment {:name "a.pdf" :mime "application/pdf"
                          :media {:kind :pdf}
                          :preferred-route :text
                          :extraction {:status :ready :text "page text"
                                       :source {:id (blob-id "a")}}})
        result (model/prepare-send [item] {:capabilities #{:document :text}})]
    (is (true? (:ok? result)))
    (is (= :text (get-in result [:sendable 0 :send-route])))
    (is (= "page text" (get-in result [:sendable 0 :send-text])))
    (is (not (contains? (get-in result [:sendable 0]) :preferred-route))
        "the resolved route is the only route field the message carries")))

(deftest send-descriptor-drops-draft-only-presentation-fields
  (let [url (str "/blobs/" (blob-id "a") "?filename=pixel.png")
        item (attachment {:name "pixel.png"
                          :mime "image/png"
                          :media {:kind :image}
                          :extraction nil
                          :preferred-route nil
                          :status-label "可发送"
                          :remove-label "移除附件 pixel.png"
                          :url url})
        result (model/prepare-send [item] {:capabilities #{:vision}})
        sent (first (:sendable result))]
    (is (:ok? result))
    (is (= :native (:send-route sent)))
    (is (= (blob-id "a") (:send-blob-id sent)))
    (is (= "image/png" (:send-mime sent)))
    (is (= url (:url sent))
        "the immutable blob link survives for the history view")
    (is (not (contains? sent :status-label)))
    (is (not (contains? sent :remove-label)))
    (is (not (contains? sent :preferred-route)))
    (is (every? model/sendable-attachment-keys (keys sent))
        "the sendable map is a closed descriptor, not the composer card")))

(deftest selected-pages-drive-the-service-page-limit
  (let [limits {:max-items 20 :max-bytes (* 100 1024 1024) :max-pages 2}]
    (is (empty? (model/limit-issues
                 (attachment {:extraction {:status :ready :text "page"
                                           :page-count 100
                                           :selected-pages [2 3]}})
                 limits)))
    (is (some #(= :ATTACHMENT_SERVICE_PAGE_LIMIT (:code %))
              (model/limit-issues
               (attachment {:extraction {:status :ready :text "page"
                                         :page-count 100}})
               limits)))))

(deftest extraction-source-and-route-are-checked
  (is (empty? (model/descriptor-issues
               (attachment {:extraction {:status :ready :text "x"
                                         :source {:id (blob-id "a")}}}))))
  (is (some #(= :ATTACHMENT_EXTRACTION_SOURCE_MISMATCH (:code %))
            (model/descriptor-issues
             (attachment {:extraction {:status :ready :text "x"
                                       :source {:id (blob-id "b")}}}))))
  (is (some #(= :ATTACHMENT_ROUTE_INVALID (:code %))
            (model/descriptor-issues
             (attachment {:preferred-route :bogus})))))

(deftest service-limits-are-stricter-never-looser
  (is (= model/default-limits (model/service-limits nil)))
  (is (= {:max-items 3 :max-bytes 10 :max-pages nil}
         (model/service-limits {:attachment-limits {:max-items 3 :max-bytes 10}})))
  (is (= model/default-limits
         (model/service-limits {:attachment-limits {:max-items 200
                                                    :max-bytes (* 200 1024 1024)}})))
  (is (= :unsupported
         (:route (model/compatibility
                  (attachment {:size 20})
                  {:capabilities #{:text}
                   :attachment-limits {:max-bytes 10}})))))

(deftest validate-draft-keeps-failed-items-in-the-count
  (let [ready (attachment)
        failed (attachment {:attachment-id "att-2" :status :failed :ready? false})
        result (model/validate-draft [ready failed] {:capabilities #{:text}})]
    (is (not (:ok? result)))
    (is (= 2 (get-in result [:summary :total])))
    (is (= 1 (get-in result [:summary :ready])))
    (is (= 1 (get-in result [:summary :blocked])))
    (is (= 1 (count (:sendable result))))
    (is (= 1 (count (:blocked result))))))

(deftest twenty-items-fit-and-twenty-one-do-not
  (let [items (mapv #(attachment {:attachment-id (str "att-" %)
                                  :name (str "f" % ".txt")})
                    (range 20))]
    (is (:ok? (model/validate-draft items {:capabilities #{:text}})))
    (let [result (model/validate-draft (conj items (attachment {:attachment-id "att-21"}))
                                       {:capabilities #{:text}})]
      (is (not (:ok? result)))
      (is (some #(= :ATTACHMENT_LIMIT_EXCEEDED (:code %)) (:issues result))))))

(deftest exactly-one-hundred-mib-is-allowed-and-one-byte-more-is-not
  (is (empty? (model/descriptor-issues
               (attachment {:size model/max-attachment-bytes}))))
  (is (some #(= :ATTACHMENT_TOO_LARGE (:code %))
            (model/descriptor-issues
             (attachment {:size (inc model/max-attachment-bytes)})))))

(deftest prepared-image-copy-is-the-default-send-copy
  (let [item (attachment {:name "wide.png"
                          :mime "image/png"
                          :media {:kind :image}
                          :extraction nil
                          :prepared {:blob-id (blob-id "b")
                                     :mime "image/png"
                                     :size 100}
                          :size 1000})
        result (model/prepare-send [item] {:capabilities #{:vision}})]
    (is (:ok? result))
    (is (= (blob-id "b") (get-in result [:sendable 0 :send-blob-id])))
    (is (= "image/png" (get-in result [:sendable 0 :send-mime])))))

(deftest workspace-association-needs-a-verified-stamp
  (let [workspace {:root-id "ws"
                   :relative-path "a.txt"
                   :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}
        item (attachment {:local-copy? false
                          :blob-id nil
                          :workspace workspace
                          :extraction {:status :ready :text "text"}})]
    (is (= :needs-choice (:route (model/compatibility item {}))))
    (is (= :text (:route (model/compatibility
                          (assoc item :workspace-verified? true)
                          {}))))))

(deftest verified-workspace-native-route-needs-a-local-copy
  (let [workspace {:root-id "ws"
                   :relative-path "a.png"
                   :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}
        item (attachment {:local-copy? false
                          :blob-id nil
                          :name "a.png"
                          :mime "image/png"
                          :media {:kind :image}
                          :extraction nil
                          :workspace workspace
                          :workspace-verified? true})
        result (model/prepare-send [item] {:capabilities #{:vision}})]
    (is (= :needs-choice (:route (model/compatibility item
                                                       {:capabilities #{:vision}}))))
    (is (false? (:ok? result)))
    (is (empty? (:sendable result)))))

(deftest preferred-text-never-falls-back-to-native
  (let [item (attachment {:name "a.pdf"
                          :mime "application/pdf"
                          :media {:kind :pdf}
                          :preferred-route :text
                          :extraction {:status :failed
                                       :error {:code :NO_TEXT}}})]
    (is (= :needs-choice
           (:route (model/compatibility item
                                        {:capabilities #{:document :text}}))))
    (is (nil? (:send-route (model/compatibility
                            item
                            {:capabilities #{:document :text}}))))))

(deftest excluded-structural-issues-do-not-block-the-rest
  (let [oversized (attachment {:attachment-id "att-bad"
                               :name "bad.bin"
                               :mime "application/octet-stream"
                               :size (inc model/max-attachment-bytes)
                               :excluded? true
                               :status :failed})
        ready (attachment {:attachment-id "att-ok"})
        result (model/prepare-send [oversized ready] {:capabilities #{:text}})]
    (is (:ok? result))
    (is (= ["att-ok"] (map :attachment-id (:sendable result))))
    (is (zero? (get-in result [:summary :blocked])))))

(deftest prepare-send-carries-only-confirmed-extraction-text
  (let [confirmed (attachment {:name "notes.txt" :mime "text/plain"
                               :media {:kind :text}
                               :extraction {:status :ready :text "confirmed"}})
        preview-only (attachment {:attachment-id "att-2"
                                  :name "preview.txt" :mime "text/plain"
                                  :media {:kind :text}
                                  :extraction nil
                                  :preview {:status :ready :text "preview"}})
        confirmed-result (model/prepare-send [confirmed] {})
        preview-result (model/prepare-send [preview-only] {})]
    (is (:ok? confirmed-result))
    (is (= "confirmed" (get-in confirmed-result [:sendable 0 :send-text])))
    (is (false? (:ok? preview-result)))
    (is (= :needs-choice (get-in preview-result [:blocked 0 :compatibility :route])))
    (is (nil? (get-in preview-result [:blocked 0 :send-text])))))

(deftest all-excluded-items-do-not-block-a-text-only-send
  (let [failed (attachment {:attachment-id "att-1"
                            :status :failed
                            :excluded? true})
        result (model/prepare-send [failed] {:capabilities #{:text}})]
    (is (:ok? result))
    (is (empty? (:sendable result)))
    (is (zero? (get-in result [:summary :blocked])))))

(defspec summary-counts-never-exceed-the-item-list
  50
  (prop/for-all [n gen/nat]
    (let [items (mapv (fn [index]
                        (attachment {:attachment-id (str "att-" index)
                                     :name (str "f" index ".txt")
                                     :status (if (even? index) :ready :failed)}))
                      (range (min n 25)))
          result (model/validate-draft items {:capabilities #{:text}})
          summary (:summary result)]
      (and (<= (:ready summary) (:total summary))
           (<= (:blocked summary) (:total summary))
           (= (:total summary) (count items))))))

(deftest text-model-refuses-an-image-before-any-send
  (let [image (attachment {:name "scan.png" :mime "image/png"
                           :media {:kind :image}
                           :extraction nil})
        result (model/prepare-send [image] {:capabilities #{:text}})]
    (is (false? (:ok? result)))
    (is (empty? (:sendable result))
        "an incompatible image never enters the send descriptor")
    (is (= :unsupported (get-in result [:attachments 0 :compatibility :route])))
    (is (= :ATTACHMENT_INCOMPATIBLE (get-in result [:issues 0 :code])))
    (is (= [:select-model :remove]
           (get-in result [:attachments 0 :compatibility :repairs])))))

(deftest text-route-keeps-original-page-numbers
  (let [item (attachment {:name "scan.pdf" :mime "application/pdf"
                          :media {:kind :pdf}
                          :preferred-route :text
                          :extraction {:status :ready
                                       :text "page two page three"
                                       :selected-pages [2 3]
                                       :pages [{:page 2 :status :text :text "page two"}
                                               {:page 3 :status :text :text "page three"}]}})
        result (model/prepare-send [item] {:capabilities #{:document :text}})
        sent (first (:sendable result))]
    (is (true? (:ok? result)))
    (is (= "第 2 页：\npage two\n\n第 3 页：\npage three"
           (:send-text sent)))
    (is (not (re-find #"第 1 页" (:send-text sent))))))

(deftest text-route-omits-unreadable-pages-but-keeps-page-numbers
  (let [item (attachment {:name "scan.pdf" :mime "application/pdf"
                          :media {:kind :pdf}
                          :preferred-route :text
                          :extraction {:status :ready
                                       :text "page two"
                                       :selected-pages [2 3 5]
                                       :pages [{:page 2 :status :text :text "page two"}
                                               {:page 3 :status :no-text :text ""}
                                               {:page 5 :status :text :text "page five"}]}})
        result (model/prepare-send [item] {:capabilities #{:document :text}})
        sent (first (:sendable result))]
    (is (true? (:ok? result)))
    (is (= "第 2 页：\npage two\n\n第 5 页：\npage five" (:send-text sent)))
    (is (not (re-find #"第 3 页" (:send-text sent))))))

(deftest page-marked-text-scales-to-many-pages
  (let [pages (mapv (fn [page] {:page page :status :text :text (str "page " page)})
                    (range 1 10001))
        item (attachment {:name "big.pdf" :mime "application/pdf"
                          :media {:kind :pdf}
                          :preferred-route :text
                          :extraction {:status :ready :text "page"
                                       :selected-pages (vec (range 1 10001))
                                       :pages pages}})
        result (model/prepare-send [item] {:capabilities #{:document :text}})
        sent (:send-text (first (:sendable result)))]
    (is (true? (:ok? result)))
    (is (clojure.string/starts-with? sent "第 1 页：\npage 1\n\n第 2 页："))
    (is (clojure.string/ends-with? sent "第 10000 页：\npage 10000"))
    (is (= 10000 (count (re-seq #"第 \d+ 页：" sent))))))

(deftest text-attachments-use-the-text-route-not-native
  (let [item (attachment {:name "notes.txt" :mime "text/plain"
                          :media {:kind :text}
                          :extraction {:status :ready :route :text
                                       :text "hello text"}})
        model {:capabilities #{:text :text-input}}
        compatibility (model/compatibility item model)
        result (model/prepare-send [item] model)
        sent (first (:sendable result))]
    (testing "a declared text capability does not make the file a native attachment"
      (is (false? (:native? compatibility)))
      (is (= :text (:route compatibility)))
      (is (= :text (:send-route compatibility))))
    (testing "the send descriptor carries the confirmed text"
      (is (true? (:ok? result)))
      (is (= :text (:send-route sent)))
      (is (= "hello text" (:send-text sent)))
      (is (not= :native (:send-route sent))))))
