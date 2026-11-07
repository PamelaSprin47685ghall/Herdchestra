(ns hc.client.sessions.controls-test
  "Pure contract tests for session metadata controls."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hc.client.sessions.controls :as controls]))

(def active
  {:id "s1" :title "Alpha" :status :active :pinned? false
   :updated-at "2026-11-04T10:00:00Z"})

(def archived
  (assoc active :status :archived))

(def deleted
  (assoc active :status :deleted :deleted-at "2026-11-04T11:00:00Z"))

(def synced {:synced? true :phase :synced})
(def disconnected {:synced? false :phase :interrupted})

(defn- action
  [actions key]
  (some #(when (= key (:action %)) %) actions))

(deftest active-session-actions
  (let [actions (controls/actions active synced nil)]
    (is (= [:rename :pin :archive :delete]
           (mapv :action actions)))
    (is (= "重命名" (:label (action actions :rename))))
    (is (= "置顶" (:label (action actions :pin))))
    (is (= :session/rename (:kind (action actions :rename))))
    (is (every? #(false? (:disabled? %)) actions))))

(deftest archived-and-deleted-actions
  (testing "an archived session offers restore instead of archive"
    (let [actions (controls/actions archived synced nil)]
      (is (= [:rename :pin :restore :delete]
             (mapv :action actions)))
      (is (= "恢复" (:label (action actions :restore))))))
  (testing "a deleted session offers restore and immediate permanent purge"
    (let [actions (controls/actions deleted synced nil)]
      (is (= [:restore :purge]
             (mapv :action actions)))
      (is (false? (:disabled? (action actions :restore))))
      (is (false? (:disabled? (action actions :purge))))
      (is (= :session/purge (:kind (action actions :purge))))
      (is (= "永久删除" (:label (action actions :purge)))))))

(deftest gating
  (testing "connection gate disables every action"
    (let [actions (controls/actions active disconnected nil)]
      (is (every? :disabled? actions))
      (is (every? #(string? (:reason %)) actions))))
  (testing "a pending metadata command disables every action"
    (let [command {:kind :session/rename :status :submitting}
          actions (controls/actions active synced command)]
      (is (every? :disabled? actions))
      (is (every? #(str/includes? (:reason %) "回执") actions))))
  (testing "a send/stop command is not treated as a metadata command"
    (is (nil? (controls/metadata-command
               {:kind :send-message :status :submitting})))
    (is (= :submitting
           (:status (controls/metadata-command
                     {:kind :session/pin :status :submitting}))))
    (let [actions (controls/actions active synced
                                    (controls/metadata-command
                                     {:kind :send-message :status :submitting}))]
      (is (every? #(false? (:disabled? %)) actions)))))

(deftest payloads
  (is (= {:title "Renamed"}
         (controls/command-payload :rename active {:title "  Renamed  "})))
  (is (nil? (controls/command-payload :rename active {:title "   "})))
  (is (nil? (controls/command-payload :rename active {:title (apply str (repeat 201 "x"))})))
  (is (= {:pinned? true} (controls/command-payload :pin active {})))
  (is (= {:pinned? false}
         (controls/command-payload :pin (assoc active :pinned? true) {})))
  (is (= {} (controls/command-payload :archive active {})))
  (is (= {} (controls/command-payload :restore archived {})))
  (is (= {} (controls/command-payload :delete active {})))
  (is (= {:confirm? true} (controls/command-payload :purge deleted {}))))

(deftest command-requests-carry-the-exact-session
  (let [row (assoc deleted :id "deleted-1")]
    (is (= {:session-id "deleted-1"
            :kind :session/restore
            :action :restore
            :payload {}}
           (controls/command-request :restore row {})))
    (is (= {:session-id "deleted-1"
            :kind :session/purge
            :action :purge
            :payload {:confirm? true}}
           (controls/command-request :purge row {})))
    (is (nil? (controls/command-request :rename row {:title "   "})))
    (is (= "s1" (:session-id (controls/command-request :pin active {}))))))

(deftest receipt-text
  (is (= "正在提交" (controls/command-status-text {:status :submitting})))
  (is (= "服务器已接受"
         (controls/command-status-text {:status :accepted})))
  (is (= "服务器已拒绝：bad title"
         (controls/command-status-text {:status :rejected
                                        :message "bad title"})))
  (is (= :negative (controls/command-status-tone {:status :rejected})))
  (is (= :positive (controls/command-status-tone {:status :applied})))
  (is (nil? (controls/command-status-text nil))))

(deftest deleted-list-normalization
  (let [rows [{:session-id "s3" :title "Deleted newer" :status :deleted
               :deleted-at "2026-11-04T12:00:00Z"}
              {:session-id "s2" :title "Active" :status :active}
              {:session-id "s1" :title "Deleted older" :status :deleted
               :deleted-at "2026-11-04T09:00:00Z"}]]
    (is (= ["s3" "s1"]
           (mapv :id (controls/deleted-sessions rows))))
    (is (= "Deleted newer" (-> (controls/deleted-sessions rows) first :title)))))

(deftest latest-metadata-command
  (let [commands {"a" {:command-id "a" :session-id "s1"
                       :kind :send-message :status :rejected
                       :created-at "1"}
                  "b" {:command-id "b" :session-id "s1"
                       :kind :session/rename :status :rejected
                       :created-at "2"}
                  "c" {:command-id "c" :session-id "s2"
                       :kind :session/pin :status :accepted
                       :created-at "3"}}]
    (is (= "b" (:command-id (controls/latest-command commands "s1"))))
    (is (= "c" (:command-id (controls/latest-command commands "s2"))))
    (is (nil? (controls/latest-command commands "s3")))
    (is (nil? (controls/latest-command {} "s1")))
    (is (= "purge"
         (:command-id
          (controls/latest-command
           {"purge" {:command-id "purge" :session-id "s1"
                     :kind :session/purge :status :submitting
                     :created-at "4"}}
           "s1"))))))
