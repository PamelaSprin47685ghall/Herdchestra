(ns hc.client.artifacts-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.artifacts :as artifacts]))

(def full-artifact
  {:id "art-1"
   :name "报告.txt"
   :filename "报告.txt"
   :kind :file
   :media-type "text/plain"
   :content-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :size 2048
   :status :available
   :environment :instance
   :project-id "p1"
   :workspace-root "/work/project"
   :source-path "/work/project/out/报告.txt"
   :relative-path "out/报告.txt"
   :source-task-id "task-1"
   :source-call-id "call-1"
   :version 2
   :storage/version 17
   :registered-at "2026-11-03T10:00:00Z"
   :origin {:workspace-root "/work/project"
            :path "/work/project/out/报告.txt"
            :relative-path "out/报告.txt"
            :stamp {:mtime-sec 100 :mtime-nsec 200 :size 2048}}
   :inputs [{:kind :file :path "input.csv"}
            {:kind :artifact :content-id "sha256-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]
   :checks [{:check :blob-exists :ok? true}
            {:check :size-matches :ok? true}
            {:check :encoding :ok? true}]})

(deftest card-carries-file-identity-and-provenance
  (let [card (artifacts/card full-artifact)]
    (testing "the card states name, type, workspace, path, size and task"
      (is (= "art-1" (:id card)))
      (is (= "报告.txt" (:name card)))
      (is (= "文件" (:kind-label card)))
      (is (= "text/plain" (:type-label card)))
      (is (= "2 KiB" (:size-label card)))
      (is (= "可用" (:status-label card)))
      (is (= :ok (:status-tone card)))
      (is (= "实例工作区" (:environment-label card)))
      (is (= "/work/project/out/报告.txt" (:path card)))
      (is (= "out/报告.txt" (:relative-path card)))
      (is (= "task-1" (:source-task-id card)))
      (is (true? (:saved? card))))
    (testing "provenance keeps the source task, inputs and checks"
      (let [labels (mapv first (:provenance card))]
        (is (some #{"来源任务"} labels))
        (is (some #{"输入引用"} labels))
        (is (some #{"检查"} labels)))
      (is (some (fn [[_ value _]] (= "3/3 项通过" value))
                (:provenance card))))
    (testing "open and download are real actions with the immutable URL"
      (is (= "/blobs/sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?filename=_.txt"
             (:download-url card)))
      (is (= "/blobs/sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
             (:open-url card))
          "the open URL must not request Content-Disposition: attachment")
      (is (not (str/includes? (:open-url card) "filename=")))
      (is (= [:open :download :copy-content-id]
             (mapv :action (:actions card)))))))

(deftest missing-and-unbacked-artifacts-have-no-fake-actions
  (testing "a missing artifact has no open or download action"
    (let [card (artifacts/card (assoc full-artifact :status :missing))]
      (is (= "内容缺失" (:status-label card)))
      (is (= :error (:status-tone card)))
      (is (false? (:saved? card)))
      (is (nil? (:download-url card)))
      (is (= [:copy-content-id] (mapv :action (:actions card))))
      (is (= "内容缺失" (:missing-reason card)))))
  (testing "a suggested path without a content ID never becomes a link"
    (let [card (artifacts/card (dissoc full-artifact :content-id))]
      (is (= "缺少内容 ID" (:status-label card)))
      (is (= :incomplete (:status card)))
      (is (false? (:available? card)))
      (is (false? (:saved? card)))
      (is (nil? (:download-url card)))
      (is (= [] (mapv :action (:actions card))))))
  (testing "a malformed content ID is not turned into a blob URL"
    (let [card (artifacts/card (assoc full-artifact
                                      :content-id "sha256-not-a-real-id"))]
      (is (= "缺少内容 ID" (:status-label card)))
      (is (nil? (:download-url card)))
      (is (nil? (:open-url card)))
      (is (= [] (mapv :action (:actions card))))))
  (testing "a non-previewable binary is download-only"
    (let [card (artifacts/card (assoc full-artifact
                                      :media-type "application/zip"
                                      :kind :binary))]
      (is (= [:download :copy-content-id]
             (mapv :action (:actions card))))
      (is (false? (:openable? card)))))
  (testing "a server blob-presence failure suppresses open and download"
    (let [card (artifacts/card (assoc full-artifact :blob-present? false))]
      (is (= :missing (:status card)))
      (is (false? (:saved? card)))
      (is (nil? (:download-url card)))
      (is (nil? (:open-url card)))
      (is (= [:copy-content-id] (mapv :action (:actions card)))))))

(deftest state-projection-replaces-the-table
  (let [db {:artifact/by-id {"a" full-artifact
                             "b" (assoc full-artifact :id "b" :name "log.txt"
                                        :status :corrupt)}}
        cards (artifacts/from-state db)]
    (is (= 2 (count cards)))
    (is (= #{"报告.txt" "log.txt"} (set (map :name cards))))
    (testing "compact collections are accepted"
      (is (= 1 (count (artifacts/from-state {:artifacts [full-artifact]})))))
    (testing "a removed entity does not survive a fresh projection"
      (is (= 1 (count (artifacts/from-state
                       {:artifact/by-id {"a" full-artifact}})))))))

(deftest announcement-and-labels
  (testing "the CUSTOM event yields the artifact value"
    (is (= {:id "art-1"}
           (artifacts/announcement-artifact
            {:type "CUSTOM"
             :name "hc/artifact-available"
             :value {:id "art-1"}})))
    (is (nil? (artifacts/announcement-artifact
               {:type "CUSTOM" :name "hc/other" :value {:id "art-1"}}))))
  (testing "size labels are readable without losing the exact value"
    (is (= "0 B" (artifacts/size-label 0)))
    (is (= "999 B" (artifacts/size-label 999)))
    (is (= "1 KiB" (artifacts/size-label 1024)))
    (is (= "1.5 MiB" (artifacts/size-label (* 1024 1024 3/2)))))
  (testing "unknown states are stated, not guessed"
    (is (= "状态未知" (artifacts/status-label nil)))
    (is (= "类型未知" (artifacts/kind-label nil)))
    (is (= "环境未知" (artifacts/environment-label nil)))))

(deftest truncated-artifact-is-not-complete
  (let [card (artifacts/card (assoc full-artifact
                                    :truncated? true
                                    :truncated-reason "artifact limit"))]
    (is (true? (:truncated? card)))
    (is (false? (:complete? card)))
    (is (= "artifact limit" (:truncated-reason card)))
    (is (some (fn [[label value _]]
                (and (= "完整性" label)
                     (= "已截断：仅交付部分字节" value)))
              (:provenance card)))
    (is (some #(= :download (:action %)) (:actions card))
        "a truncated artifact still has real bytes to download")))


(deftest server-provided-urls-must-still-name-the-content-id
  (let [bad (assoc full-artifact :download-url "https://evil.example/x")
        card (artifacts/card bad)]
    (is (not (str/includes? (:download-url card) "evil.example")))
    (is (str/starts-with? (:download-url card) "/blobs/sha256-")))
  (let [bad (assoc full-artifact :open-url "javascript:alert(1)")
        card (artifacts/card bad)]
    (is (not (str/includes? (:open-url card) "javascript")))
    (is (= (str "/blobs/" (:content-id full-artifact)) (:open-url card)))))

(deftest card-carries-project-workspace-device-and-check-details
  (let [artifact (assoc full-artifact :device "laptop-1" :retractable? true)
        card (artifacts/card artifact)
        provenance (into {} (map (juxt first second) (:provenance card)))]
    (is (= "p1" (:project-id card)))
    (is (= "/work/project" (:workspace-root card)))
    (is (= "laptop-1" (:device card)))
    (is (true? (:retractable? card)))
    (is (str/includes? (get provenance "检查项") "blob-exists：通过"))))
