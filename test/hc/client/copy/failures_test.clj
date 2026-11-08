(ns hc.client.copy.failures-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.copy.failures :as failures]))

(defn- copy [code context]
  (failures/failure-copy {:code code :context context}))

(defn- body [code saved?]
  (:body (copy code {:saved? saved?})))

(deftest five-failure-categories-come-from-the-shared-module
  (doseq [code [:network-unreachable :tool-failed :receipt-lost
                :response-timeout :attachment-unsupported]]
    (testing (name code)
      (let [copy (failures/failure-copy {:code code
                                         :context {:saved? false}})]
        (is (string? (:title copy)))
        (is (string? (:body copy)))
        (is (not (str/blank? (:title copy))))
        (is (not (str/blank? (:body copy))))
        (is (not (str/includes? (:body copy) "已保留"))
            "an unconfirmed draft must not claim it was preserved")
        (is (not (str/includes? (:body copy) "已保存")))))))

(deftest saved-draft-is-the-only-case-that-says-preserved
  (is (str/includes? (body :attachment-unsupported true) "草稿已保留"))
  (is (not (str/includes? (body :attachment-unsupported false) "草稿已保留")))
  (is (str/includes? (body :attachment-unsupported false)
                     "尚未确认本地保存")))

(deftest quota-is-distinct-and-has-stop-secondary
  (let [q (copy :quota-exhausted {:saved? false})
        r (copy :rate-limited {:saved? false})
        a (copy :auth-invalid {:saved? false})
        p (copy :permission-denied {:saved? false})
        m (copy :model-missing {:saved? false})]
    (is (str/includes? (:body q) "这家服务报告额度不足，本次未继续"))
    (is (= :settings/open-connection
           (get-in q [:primary-action :command])))
    (is (= :command/stop-run (get-in q [:secondary-action :command])))
    (is (= 5 (count (distinct (map :title [q r a p m])))))
    (is (= 5 (count (distinct (map :body [q r a p m])))))))

(deftest rate-limit-saved-claim-is-conditional
  (let [saved (copy :rate-limited {:saved? true})
        unsaved (copy :rate-limited {:saved? false})]
    (is (str/includes? (:body saved) "已保留本轮内容"))
    (is (not (str/includes? (:body unsaved) "已保留")))
    (is (str/includes? (:body unsaved) "尚未确认本地保存"))))

(deftest export-cancel-never-claims-saved
  (doseq [saved? [true false]]
    (let [c (copy :export-cancelled {:saved? saved?})]
      (is (str/includes? (:body c) "没有生成最终导出文件"))
      (is (not (str/includes? (:body c) "已保存")))
      (is (not (str/includes? (:body c) "已保留"))))))

(deftest network-and-transport-are-distinct
  (let [network (copy :network-unreachable {:saved? false})
        transport (copy :transport-disconnected {:saved? false})]
    (is (not= (:title network) (:title transport)))
    (is (not= (:body network) (:body transport)))
    (is (= :app/reconnect (get-in transport [:primary-action :command])))))

(deftest all-24-kinds-have-complete-copy
  (is (= 24 (count failures/failure-kinds)))
  (doseq [code failures/failure-kinds]
    (let [c (copy code {:saved? false})]
      (is (seq (:title c)) (str code " title"))
      (is (seq (:body c)) (str code " body"))
      (is (seq (get-in c [:primary-action :label])) (str code " action"))
      (is (keyword? (get-in c [:primary-action :command])))
      (is (not (str/includes? (:body c) "已保留")))
      (is (not (str/includes? (:body c) "已保存"))))))

(deftest unknown-usage-never-renders-zero
  (let [c (copy :quota-exhausted {:saved? false :unknown-usage? true})]
    (is (str/includes? (:body c) "未知"))
    (is (not (str/includes? (:body c) "0")))))

(def ^:private expected-category
  "The AGENTS.md §7.4 第 8 段 mapping: each of the 24 contract kinds lands
  in exactly one of the ten non-delivery reason categories."
  {:no-model :no-usable-model
   :connection-disabled :no-usable-model
   :model-removed :no-usable-model
   :invalid-credentials :credentials-or-authorization
   :authorization-expired :credentials-or-authorization
   :quota-exhausted :quota-or-rate-limited
   :rate-limited :quota-or-rate-limited
   :network-unreachable :connection-or-response
   :response-timeout :connection-or-response
   :transport-disconnected :connection-or-response
   :input-too-large :content-out-of-range
   :attachment-unsupported :content-out-of-range
   :partial-materials :partial-materials
   :local-storage-full :local-storage-or-file
   :local-data-unreadable :local-storage-or-file
   :external-file-moved :local-storage-or-file
   :permission-revoked :permission-or-outcome-unknown
   :tool-failed :permission-or-outcome-unknown
   :receipt-lost :permission-or-outcome-unknown
   :schedule-missed :schedule-missed
   :backup-password-invalid :backup-export-undeliverable
   :import-format-unsupported :backup-export-undeliverable
   :restore-conflict :backup-export-undeliverable
   :format-too-new :backup-export-undeliverable
   :export-cancelled :backup-export-undeliverable})

(deftest twenty-four-kinds-map-onto-ten-reason-categories
  (is (= 10 (count failures/failure-reason-categories)))
  (is (= 10 (count (distinct (map :id failures/failure-reason-categories)))))
  (is (= #{:no-model :connection-disabled :model-removed
           :invalid-credentials :authorization-expired
           :quota-exhausted :rate-limited
           :network-unreachable :response-timeout :transport-disconnected
           :input-too-large :attachment-unsupported
           :partial-materials
           :local-storage-full :local-data-unreadable :external-file-moved
           :permission-revoked :tool-failed :receipt-lost
           :schedule-missed
           :backup-password-invalid :import-format-unsupported
           :restore-conflict :format-too-new :export-cancelled}
         (set (mapcat :kinds failures/failure-reason-categories))))
  (doseq [code failures/failure-kinds]
    (testing (name code)
      (is (= (get expected-category code)
             (failures/failure-category code)))
      (is (= (get expected-category code)
             (:reason-category (copy code {:saved? false})))))))

(deftest every-category-carries-explanation-and-member-kinds
  (doseq [{:keys [id number label explanation kinds]}
          failures/failure-reason-categories]
    (testing (name id)
      (is (string? number))
      (is (seq label))
      (is (seq explanation))
      (is (seq kinds))
      (is (every? #(contains? expected-category %) kinds)))))

(deftest transport-disconnect-rides-category-four
  (is (= :connection-or-response
         (failures/failure-category :transport-disconnected)))
  (is (= :connection-or-response
         (:reason-category (copy :transport-disconnected {:saved? false})))))

(deftest unknown-code-falls-back-to-indeterminate
  (let [c (failures/failure-copy {:code :never-heard-of-this
                                  :context {:saved? false}})]
    (is (= :indeterminate (:reason-category c)))
    (is (= :indeterminate (failures/failure-category :never-heard-of-this)))
    (is (= "无法确定具体原因"
           (:label (failures/failure-reason :never-heard-of-this))))
    (is (not (str/includes? (:body c) "已保留")))
    (is (not (str/includes? (:body c) "已保存")))))

(deftest category-lookup-accepts-aliases-and-strings
  (is (= :no-usable-model (failures/failure-category :no_model)))
  (is (= :no-usable-model (failures/failure-category "no-model")))
  (is (= :quota-or-rate-limited (failures/failure-category :search_quota_exceeded)))
  (is (= :local-storage-or-file (failures/failure-category :command_store_failed))))

(deftest every-reason-card-carries-category-and-copy
  (doseq [code (concat failures/failure-kinds [:transport-disconnected])]
    (testing (name code)
      (let [card (failures/failure-reason-card {:code code :context {:saved? false}})]
        (is (= (failures/failure-category code) (:category-id card)))
        (is (seq (:number card)))
        (is (seq (:label card)))
        (is (seq (:explanation card)))
        (is (= (:kinds (some #(when (= (:id %) (:category-id card)) %)
                              failures/failure-reason-categories))
               (:member-kinds card)))
        (is (= (:reason-category (failures/failure-copy
                                  {:code code :context {:saved? false}}))
               (:category-id card)))
        (is (= (:title (failures/failure-copy
                        {:code code :context {:saved? false}}))
               (get-in card [:copy :title])))))))

(deftest reason-card-unknown-code-uses-indeterminate
  (let [card (failures/failure-reason-card {:code :never-heard-of-this
                                            :context {:saved? false}})]
    (is (= :indeterminate (:category-id card)))
    (is (nil? (:number card)))
    (is (= "无法确定具体原因" (:label card)))
    (is (seq (:explanation card)))
    (is (= [] (:member-kinds card)))
    (is (= "无法确定具体原因" (get-in card [:copy :title])))))

(deftest copy-results-carry-the-reason-card
  (let [copy (failures/failure-copy {:code :receipt-lost
                                     :context {:saved? false}})]
    (is (= :permission-or-outcome-unknown
           (get-in copy [:reason :category-id])))
    (is (= "八" (get-in copy [:reason :number])))
    (is (= "权限被关闭，或执行结果无法确认"
           (get-in copy [:reason :label])))
    (is (seq (get-in copy [:reason :explanation]))))
  (is (nil? (failures/reason-label :never-heard-of-this {})))
  (is (= "（五）内容超出了所选模型能接收的范围 · "
         (failures/reason-label :input-too-large {}))))
