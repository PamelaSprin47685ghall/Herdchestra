(ns hc.client.conversation.export-test
  "Tests for the pure export content filter, selection, cancellation, and
  long-image plan."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.conversation.export :as export]
            [hc.client.conversation.export-plan :as plan]))

(def messages
  [{:id "u1" :kind :user :branch-id "b1" :selected-version :v2
    :text "new text"
    :versions [{:id :v1 :text "old text"} {:id :v2 :text "new text"}]}
   {:id "a1" :kind :assistant :branch-id "b1" :text "answer"}
   {:id "t1" :kind :tool :branch-id "b1" :text "tool log"}
   {:id "h1" :kind :assistant :branch-id "b1" :hidden? true :text "hidden"}
   {:id "s1" :kind :assistant :branch-id "b1" :secret? true :text "secret"}
   {:id "b2" :kind :user :branch-id "b2" :text "other branch"}])

(deftest selection-modes-and-default-version
  (testing "single uses the current selected version"
    (let [r (plan/select-messages :single messages {:selected-id "u1"})]
      (is (:ok? r))
      (is (= 1 (count (:messages r))))
      (is (= "new text" (get-in r [:messages 0 :text])))
      (is (= :v2 (get-in r [:messages 0 :version-id])))))
  (testing "all versions can be included"
    (let [r (plan/select-messages :single messages
                                  {:selected-id "u1"
                                   :include-all-versions? true})]
      (is (= 2 (count (:messages r))))
      (is (= 1 (count (filter :selected? (:messages r)))))))
  (testing "multi and branch"
    (is (= 2 (count (:messages (plan/select-messages
                                :multi messages {:selected-ids #{"u1" "a1"}})))))
    (let [r (plan/select-messages :branch messages {:branch-id "b1"})]
      (is (= 2 (count (:messages r))))
      (is (= {:tools 1 :hidden 1 :secrets 1} (:excluded r))))))

(deftest selection-errors-and-exclusions
  (is (= :NO_SELECTION
         (get-in (plan/select-messages :single messages {}) [:error :code])))
  (is (= :NO_BRANCH
         (get-in (plan/select-messages :branch messages {}) [:error :code])))
  (is (= :NO_SELECTION
         (get-in (plan/select-messages :multi messages {:selected-ids #{}}) [:error :code])))
  (let [r (plan/select-messages :branch messages
                                {:branch-id "b1" :include-tools? true
                                 :include-hidden-instructions? true})]
    (is (= 4 (count (:messages r)))))
  (let [dup (conj messages {:id "u1" :kind :user :branch-id "b1" :text "dup"})]
    (is (= :DUPLICATE_MESSAGE_ID
           (get-in (plan/select-messages :single dup {:selected-id "u1"})
                   [:error :code])))))

(deftest cancellation-state-machine
  (let [state (plan/initial {:mode :single} {:theme :light})]
    (is (= :idle (:phase state)))
    (is (false? (:ok? (plan/cancel state))))
    (let [preparing (:state (plan/begin state))
          cancelled (plan/cancel preparing)]
      (is (= :preparing (:phase preparing)))
      (is (= :cancelled (get-in cancelled [:state :phase])))
      (is (= "导出被取消" (get-in cancelled [:copy :title]))))
    (let [writing (:state (plan/start-writing (:state (plan/begin state))))
          requested (plan/cancel writing)
          done (plan/complete (:state requested))]
      (is (= :writing (get-in requested [:state :phase])))
      (is (= "已请求取消，等待当前写入边界。" (:notice requested)))
      (is (= :cancelled (get-in done [:state :phase]))))
    (let [writing (:state (plan/start-writing (:state (plan/begin state))))
          done (plan/complete writing)]
      (is (= :done (get-in done [:state :phase]))))
    (let [failed (plan/fail state {:code :DISK_FULL})]
      (is (= :failed (get-in failed [:state :phase])))
      (is (= {:mode :single} (get-in failed [:state :selection])))
      (is (= {:theme :light} (get-in failed [:state :preview]))))))

(deftest long-image-segmentation-and-errors
  (is (= :EMPTY_SELECTION
         (get-in (plan/segment-plan [] {}) [:error :code])))
  (is (= :SINGLE_MESSAGE_TOO_TALL
         (get-in (plan/segment-plan [{:id "huge" :estimated-height 5000}]
                                    {:max-height 4000}) [:error :code])))
  (is (= :DUPLICATE_MESSAGE_ID
         (get-in (plan/segment-plan [{:id "a" :estimated-height 100}
                                     {:id "a" :estimated-height 100}]
                                    {:max-height 300}) [:error :code])))
  (let [result (plan/segment-plan
                [{:id "a" :estimated-height 100}
                 {:id "b" :estimated-height 200}
                 {:id "c" :estimated-height 100}]
                {:max-height 300 :theme :dark})]
    (is (:ok? result))
    (is (= 2 (:segment-count result)))
    (is (= [["a" "b"] ["c"]]
           (mapv :message-ids (:segments result))))
    (is (= 400 (:total-height result)))
    (let [preview (plan/preview-size result {:width 800})]
      (is (= 800 (:width preview)))
      (is (= 400 (:height preview)))
      (is (= 2 (:segments preview))))))

;; ---------------------------------------------------------------------------
;; Export content filtering

(def tool-heavy-messages
  [{:id "u1" :role :user :body "图片里是什么？"}
   {:id "a1" :role :assistant
    :body "一只橘猫。"
    :sources [{:title "来源标题"
               :url "https://example.test/cat"
               :published-at "2026-01-01"}]
    :attachments [{:name "p1.png" :mime "image/png" :size 2048
                   :local-copy? true}]
    :process [{:title "files.read" :status-label "已完成"
               :summary "读取图片" :result "{:ok true}"}]}
   {:id "a2" :role :assistant :body "带工具调用"
    :tool-calls [{:name "web.search" :status :succeeded :result "2 hits"}]}
   {:id "t1" :role :tool
    :body (str "{:role :clj-plan\n"
               " :raw-source \"\"\"\n"
               " (defprogram e2e04-image-one-line []\n"
               "   (review {:findings []}))\n"
               " \"\"\"\n"
               " :tool-call-id \"call-1/tool-0\"}")}
   {:id "tc1" :role :tool-call :body ""}
   {:id "t2" :kind :tool :body "kind tool body"}
   {:id "t3" :role "tool" :body "string tool body"}
   {:id "act1" :role :activity :body "搜索进展：找到 2 条"}
   {:id "r1" :role :reasoning :body "内部推理摘要"}
   {:id "s1" :role :system :body "隐藏系统指令"}
   {:id "d1" :role :assistant
    :body "{:raw-source \"\"\"\n(defprogram hidden [])\n\"\"\"}"}])

(deftest export-omits-tool-detail-by-default
  (testing "both formats keep visible dialogue and attachment notes"
    (doseq [[label text] [["markdown" (export/markdown tool-heavy-messages)]
                          ["plain" (export/plain-text tool-heavy-messages)]]]
      (is (str/includes? text "图片里是什么？") label)
      (is (str/includes? text "一只橘猫。") label)
      (is (str/includes? text "p1.png") label)))
  (testing "tool, activity, reasoning, system, and payload text stay out"
    (doseq [[label text] [["markdown" (export/markdown tool-heavy-messages)]
                          ["plain" (export/plain-text tool-heavy-messages)]]]
      (doseq [excluded ["defprogram" ":raw-source" "搜索进展" "内部推理摘要"
                        "隐藏系统指令" "files.read" "读取图片" "tool-call"
                        "kind tool body" "string tool body" "web.search"]]
        (is (not (str/includes? text excluded))
            (str label " excludes " excluded)))))
  (testing "a set with no visible dialogue renders nothing"
    (is (= "" (export/markdown [{:id "t1" :role :tool :body "tool log"}])))
    (is (= "" (export/plain-text [{:id "t1" :role :tool :body "tool log"}]))))
  (testing "the E2E evidence shape leaves no tool headings"
    (let [text (export/markdown
                [{:id "u1" :role :user :body "问题"}
                 {:id "tc1" :role :tool-call :body ""}
                 {:id "t1" :role :tool
                  :body "{:role :clj-plan :raw-source \"\"\"\n(defprogram x [])\n\"\"\"}"}])]
      (is (str/includes? text "## 你"))
      (is (not (str/includes? text "## 工具")))
      (is (not (str/includes? text "## tool-call"))))))

(deftest export-includes-tool-detail-on-request
  (let [md (export/markdown tool-heavy-messages {:include-tools? true})
        plain (export/plain-text tool-heavy-messages {:include-tools? true})]
    (doseq [[label text] [["markdown" md] ["plain" plain]]]
      (is (str/includes? text "defprogram") label)
      (is (str/includes? text ":raw-source") label)
      (is (str/includes? text "搜索进展") label)
      (is (str/includes? text "内部推理摘要") label)
      (is (str/includes? text "files.read") label)
      (is (str/includes? text "读取图片") label)
      (is (str/includes? text "web.search") label)
      (is (str/includes? text "2 hits") label)
      (is (not (str/includes? text "隐藏系统指令"))
          (str label " still excludes hidden system instructions")))))

(deftest export-falls-back-to-run-source-cards-for-the-last-answer
  (let [cards [{:label "S1" :title "Doc One"
                :url "http://127.0.0.1:18096/doc1.html"
                :published-at-unknown? true}
               {:label "S2" :title "Missing Source"
                :url "http://127.0.0.1:18096/missing.html"
                :published-at-unknown? true}
               {:label "S3" :title "Doc Two"
                :url "http://127.0.0.1:18096/doc2.html"
                :published-at "2025-01-02T00:00:00Z"}]
        messages [{:id "u1" :role :user :body "question"}
                  {:id "a1" :role :assistant :body "ALPHA-ONE [S1]"}]
        text (export/plain-text messages {:include-sources? true
                                          :fallback-sources cards})]
    (is (str/includes? text "来源："))
    (is (str/includes? text "http://127.0.0.1:18096/doc1.html"))
    (is (str/includes? text "http://127.0.0.1:18096/missing.html"))
    (is (str/includes? text "http://127.0.0.1:18096/doc2.html"))
    (is (str/includes? text "2025-01-02T00:00:00Z"))
    (testing "sources stay opt-in"
      (is (not (str/includes? (export/plain-text messages {:fallback-sources cards})
                              "来源："))))
    (testing "the fallback applies only to the last assistant reply"
      (let [two-replies [{:id "a0" :role :assistant :body "first"}
                         {:id "a1" :role :assistant :body "second [S1]"}]
            text (export/plain-text two-replies {:include-sources? true
                                                 :fallback-sources cards})]
        (is (= 1 (count (re-seq #"来源：" text))))))))

(deftest export-sources-remain-opt-in-alongside-tool-filtering
  (let [without (export/markdown tool-heavy-messages)
        with (export/markdown tool-heavy-messages {:include-sources? true})]
    (is (not (str/includes? without "来源标题")))
    (is (str/includes? with "来源标题"))
    (is (str/includes? with "https://example.test/cat"))
    (is (str/includes? with "p1.png"))
    (is (not (str/includes? with "defprogram"))
        "including sources does not also include tool payloads")))
