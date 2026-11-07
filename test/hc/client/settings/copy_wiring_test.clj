(ns hc.client.settings.copy-wiring-test
  "Tests for the shared failure copy wired into settings capacity and test
  waiting surfaces."
  (:require [clojure.test :refer [deftest is]]
            [hc.client.settings.catalog :as catalog]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.model :as model]
            [hc.client.settings.support :as support]))

(defn- connected-state []
  (-> (support/state {:connections
                      {"a" (connection/normalize-connection
                            {:id "a" :name "A" :enabled? true
                             :base-url "https://a.example.com/v1"
                             :protocol :openai-compatible
                             :models []})}
                      :models {"a" []}})
      (support/apply-action {:type :settings/open})
      (support/apply-action {:type :settings/select-connection
                             :connection-id "a"})))

(deftest capacity-over-limit-blocks-and-exposes-actions
  (let [warning (catalog/capacity-warning {:context-window 1000} 1500
                                           {:saved? false})]
    (is (true? (:block? warning)))
    (is (true? (:over? warning)))
    (is (= "输入超出容量" (get-in warning [:copy :title])))
    (is (= :composer/adjust-content
           (get-in warning [:copy :primary-action :command])))
    (is (= [:compress-history :reduce-attachments :adjust-scope :switch-model]
           (:actions warning)))
    (is (re-find #"发送前会阻止" (get-in warning [:copy :body])))))

(deftest capacity-near-limit-is-a-warning-not-a-block
  (let [warning (catalog/capacity-warning {:context-window 1000} 950)]
    (is (false? (:block? warning)))
    (is (true? (:near? warning)))
    (is (re-find #"接近模型容量" (:message warning)))))

(deftest waiting-test-shows-copy-and-elapsed-not-thinking
  (let [state (assoc-in (connected-state) [:tests "a"]
                        {:status :running :started-at 1000})
        test (model/test-view state "a" 6500)]
    (is (true? (:waiting? test)))
    (is (= "等待服务返回" (:status-label test)))
    (is (= "5 秒" (:elapsed-label test)))
    (is (re-find #"服务尚未返回内容" (get-in test [:waiting-copy :body])))
    (is (re-find #"5 秒" (get-in test [:waiting-copy :body])))
    (is (= :command/stop-run
           (get-in test [:waiting-copy :primary-action :command])))
    (is (not (re-find #"正在思考" (:status-label test))))))
