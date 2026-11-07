(ns hc.server.compression-summary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.history :as history]
            [hc.server.history.context :as context]
            [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]))

(defn- base-session
  []
  (let [result (sessions/new-session
                {:session-id "s1"
                 :owner-id "o1"
                 :title "长会话"
                 :now 1000
                 :id-source (ids/counter-source {:prefix "t"})})]
    (is (:ok? result))
    (:session result)))

(defn- add-message
  [session message-id role text opts]
  (let [result (message/new-message message-id role text
                                    (assoc opts :now (or (:now opts) 1000)))]
    (is (:ok? result))
    (-> session
        (assoc-in [:messages message-id] (:message result))
        (update :message-order (fnil conj []) message-id))))

(defn- command
  [session kind payload now]
  (history/apply-command session (assoc payload :kind kind) {:now now}))

(def ^:private reviewed-summary
  "事实：项目代号是 A。假设（未确认）：用户偏好蓝色主题。已取消要求：不要导出。")

(defn- applied-session
  []
  (let [session (-> (base-session)
                    (add-message "m1" :user "事实：项目代号是 A。" {})
                    (add-message "m2" :assistant "已记录。" {})
                    (add-message "m3" :user "继续一" {})
                    (add-message "m4" :assistant "回答一" {})
                    (add-message "m5" :user "继续二" {})
                    (add-message "m6" :assistant "回答二" {})
                    (add-message "m7" :user "继续三" {})
                    (add-message "m8" :assistant "回答三" {}))
        prepared (command session :history/prepare-compression
                          {:compression-id "c1"
                           :message-ids ["m1" "m2"]
                           :summary "草稿摘要"} 2000)
        edited (command (:session prepared) :history/update-compression-draft
                        {:compression-id "c1"
                         :summary reviewed-summary} 2500)
        applied (command (:session edited) :history/apply-compression
                         {:compression-id "c1"} 3000)]
    (is (:ok? applied))
    (:session applied)))

(deftest continued-turns-never-send-the-replaced-history-again
  (let [session (applied-session)
        out (context/outbound-context session)
        entries (:messages out)]
    (testing "the summary replaces exactly the reviewed range"
      (is (= ["m3" "m4" "m5" "m6" "m7" "m8"] (:message-ids out)))
      (is (= [:context-summary :user :assistant :user :assistant
              :user :assistant]
             (mapv #(or (:kind %) (:role %)) entries)))
      (is (= reviewed-summary (get-in entries [0 :content])))
      (is (= ["m1" "m2"] (get-in entries [0 :source-message-ids]))))
    (testing "the replaced messages appear only as explicit exclusions"
      (is (not-any? (fn [entry]
                      (contains? #{"m1" "m2"} (:message-id entry)))
                    entries))
      (is (= #{"m1" "m2"}
             (->> (:excluded out)
                  (filter #(= :replaced-by-context-summary (:reason %)))
                  (map :message-id)
                  set))))
    (testing "two more turns keep both the summary and the new messages"
      (let [later (-> session
                      (add-message "m9" :user "继续四" {})
                      (add-message "m10" :assistant "回答四" {}))
            out (context/outbound-context later)]
        (is (= ["m3" "m4" "m5" "m6" "m7" "m8" "m9" "m10"]
               (:message-ids out)))
        (is (= reviewed-summary (get-in out [:messages 0 :content])))
        (is (not-any? (fn [entry]
                        (contains? #{"m1" "m2"} (:message-id entry)))
                      (:messages out)))))))

(deftest undo-restores-the-range-without-deleting-later-answers
  (let [session (applied-session)
        undone (command session :history/undo-compression
                        {:compression-id "c1"} 4000)
        undone-session (:session undone)
        out (context/outbound-context undone-session)]
    (is (:ok? undone))
    (is (= ["m1" "m2" "m3" "m4" "m5" "m6" "m7" "m8"]
           (:message-ids out)))
    (is (= ["m1" "m2" "m3" "m4" "m5" "m6" "m7" "m8"]
           (mapv :message-id (:messages out))))
    (is (= "回答三" (:content (last (:messages out)))))
    (is (= :reverted (get-in undone-session
                             [:context :compressions "c1" :state])))
    (is (= 8 (count (get-in undone-session [:messages]))))
    (is (empty? (history/check-invariants undone-session)))))

(deftest summary-session-carries-only-the-reviewed-summary
  (let [source (applied-session)
        created (sessions/summary-session
                 source
                 {:session-id "s2"
                  :owner-id "o2"
                  :compression-id "c1"
                  :now 5000
                  :id-source (ids/counter-source {:prefix "u"})})
        summary-session (:session created)
        message-id (first (:message-order summary-session))
        content (get-in summary-session [:messages message-id :versions 0 :content])]
    (is (:ok? created))
    (testing "the source keeps every message"
      (is (= ["m1" "m2" "m3" "m4" "m5" "m6" "m7" "m8"]
             (:message-order source))))
    (testing "the new session has one system message and no copied turns"
      (is (= [message-id] (:message-order summary-session)))
      (is (= :system (get-in summary-session [:messages message-id :role])))
      (is (= {} (dissoc (:messages summary-session) message-id)))
      (is (not-any? #(contains? (:messages summary-session) %)
                    ["m1" "m2" "m3" "m4" "m5" "m6" "m7" "m8"])))
    (testing "the reviewed summary is preserved verbatim after the origin header"
      (is (str/includes? content "来源会话《长会话》"))
      (is (str/includes? content reviewed-summary))
      (is (str/includes? content "假设（未确认）")))
    (testing "the new session's outbound range carries only the summary message"
      (let [out (context/outbound-context summary-session)]
        (is (= [message-id] (:message-ids out)))
        (is (= 1 (count (:messages out))))
        (is (= :system (get-in out [:messages 0 :role])))
        (is (not-any? #(contains? #{"m1" "m2"} (:message-id %))
                      (:messages out)))))
    (testing "a draft or reverted compression cannot seed a session"
      (let [draft-source (-> source
                             (assoc-in [:context :compressions "c2"]
                                       {:compression-id "c2"
                                        :state :draft
                                        :message-ids ["m1"]
                                        :summary "draft"}))
            refused (sessions/summary-session
                     draft-source
                     {:session-id "s3"
                      :owner-id "o3"
                      :compression-id "c2"
                      :now 6000
                      :id-source (ids/counter-source {:prefix "v"})})]
        (is (false? (:ok? refused)))
        (is (= :COMPRESSION_NOT_APPLIED (get-in refused [:error :code])))))))

(deftest initial-conversation-carries-the-summary-into-a-fresh-owner
  (let [source (applied-session)
        created (sessions/summary-session
                 source
                 {:session-id "s4"
                  :owner-id "o4"
                  :compression-id "c1"
                  :now 7000
                  :id-source (ids/counter-source {:prefix "w"})})
        seeded (:session created)
        entries (sessions/initial-conversation seeded)]
    (is (:ok? created))
    (testing "exactly one conversation entry, the reviewed summary"
      (is (= 1 (count entries)))
      (is (= :system (:role (first entries))))
      (is (= (first (:message-order seeded))
             (:message-id (first entries))))
      (is (str/includes? (:text (first entries)) reviewed-summary))
      (is (str/includes? (:text (first entries)) "假设（未确认）"))
      (testing "the replaced source messages are not seeded"
        (is (not-any? #{"m1" "m2"} (map :message-id entries)))))))

(deftest initial-conversation-skips-nudge-deleted-and-folded
  (let [session (-> (base-session)
                    (add-message "u1" :user "可见" {})
                    (add-message "a1" :assistant "Nudge 回答" {})
                    (add-message "u2" :user "已删除" {})
                    (add-message "u3" :user "已折叠" {}))
        session (-> session
                    (assoc-in [:messages "a1" :nudge?] true)
                    (assoc-in [:messages "a1" :nudge-id] "n1")
                    (assoc-in [:messages "u2" :deleted?] true)
                    (assoc-in [:messages "u3" :folded?] true))
        entries (sessions/initial-conversation session)]
    (is (= ["u1"] (mapv :message-id entries)))
    (is (= "可见" (:text (first entries))))))
