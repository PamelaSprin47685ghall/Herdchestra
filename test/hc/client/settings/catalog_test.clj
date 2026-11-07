(ns hc.client.settings.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.settings.catalog :as catalog]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.support :as support]))

(defn- normalized [conn]
  (connection/normalize-connection conn))

(deftest same-name-models-are-not-merged-test
  (let [a (normalized (support/connection "a" "Same" "https://a.example.com/v1"))
        b (normalized (support/connection "b" "Same" "https://b.example.com/v1"))
        groups (catalog/grouped-models
                {"a" a "b" b}
                {"a" [(support/model "gpt-x" "GPT-X")]
                 "b" [(support/model "gpt-x" "GPT-X")]}
                {"a" :listed "b" :listed})
        rows (catalog/rows groups)]
    (is (= 2 (count groups)))
    (is (= 2 (count rows)))
    (is (= #{["a" "gpt-x"] ["b" "gpt-x"]}
           (set (map (juxt :connection-id :model-id) rows))))
    (is (every? #(re-find #"example\.com" (:source-label %)) rows))
    (is (not= (-> rows first :source-label) (-> rows second :source-label)))
    (is (every? :duplicate-display-name? rows))
    (is (every? #(true? (:duplicate-name? %)) groups))))

(deftest display-name-survives-projection-and-name-fallback-test
  (let [a (normalized (support/connection "a" "Same" "https://a.example.com/v1"))
        b (normalized (support/connection "b" "Same" "https://b.example.com/v1"))
        groups (catalog/grouped-models
                {"a" a "b" b}
                {"a" [{:id "stub-model"
                       :display-name "Stub Slow Model"}]
                 "b" [{:id "legacy-model"
                       :name "Legacy Model"}]}
                {"a" :listed "b" :listed})
        rows (catalog/rows groups)]
    (is (= ["Stub Slow Model" "Legacy Model"]
           (mapv :display-name rows))
        "the explicit display name wins; the legacy :name still renders")
    (is (= #{"stub-model" "legacy-model"}
           (set (map :model-id rows))))
    (is (= 2 (count rows))
        "same-named connections keep separate rows")
    (is (every? :duplicate-name? groups))
    (is (not= (:source-label (first rows))
              (:source-label (second rows)))
        "connection source and actual host disambiguate the rows")))

(deftest capability-labels-test
  (let [badges (catalog/capability-badges {:vision :known
                                           :tools :unknown
                                           :document :unsupported})]
    (is (= 6 (count badges)))
    (is (= "视觉：已声明支持"
           (:text (first (filter #(= :vision (:capability %)) badges)))))
    (is (= "工具：尚未确认"
           (:text (first (filter #(= :tools (:capability %)) badges)))))
    (is (= "文档：不支持"
           (:text (first (filter #(= :document (:capability %)) badges)))))
    (is (every? :aria-label badges)))
  (testing "a set-shaped launch declaration is known, not unknown"
    (let [badges (catalog/capability-badges #{:tools})
          tools (first (filter #(= :tools (:capability %)) badges))]
      (is (= :known (:state tools)))
      (is (= "工具：已声明支持" (:text tools)))
      (is (= "工具：已声明支持" (:aria-label tools))
          "the visible text and the accessible name agree")
      (is (= "推理：尚未确认"
             (:text (first (filter #(= :reasoning (:capability %)) badges))))
          "the neighbouring unknown badge cannot be read as part of tools"))))

(deftest unknown-capability-survives-disconnected-connection-test
  (let [conn (normalized (support/connection "a" "A" "https://a.example.com/v1"
                                             {:status :error :enabled? false}))
        groups (catalog/grouped-models
                {"a" conn}
                {"a" [(support/model "m" "M")]}
                {"a" :listed})
        row (first (catalog/rows groups))]
    (is (= 6 (count (:capability-badges row))))
    (is (every? #(= :unknown (:state %)) (:capability-badges row)))
    (is (= "所属连接已停用" (:availability-label row)))))

(deftest pricing-label-test
  (testing "unknown price is never free"
    (is (= "未知" (catalog/pricing-label :unknown)))
    (is (not (re-find #"免费" (catalog/pricing-label :unknown))))
    (is (not (re-find #"免费" (catalog/pricing-label nil))))
    (is (not (re-find #"免费"
                      (catalog/pricing-label {:input 3 :output :unknown})))))

  (testing "explicit free and known prices"
    (is (re-find #"免费" (catalog/pricing-label {:free? true})))
    (is (re-find #"免费" (catalog/pricing-label {:input 0 :output 0})))
    (is (re-find #"USD 3" (catalog/pricing-label
                           {:input 3 :output 15 :currency "USD"
                            :unit :per-million-tokens})))
    (is (re-find #"输入 3" (catalog/pricing-label {:input 3 :output 15})))))

(deftest capacity-test
  (is (= "未知" (catalog/capacity-label {:context-window :unknown})))
  (is (re-find #"128,000" (catalog/capacity-label
                           {:context-window {:tokens 128000 :source :user}})))
  (is (re-find #"用户设置" (catalog/capacity-label
                           {:context-window {:tokens 128000 :source :user}})))
  (is (re-find #"估算" (catalog/capacity-label
                        {:context-window {:tokens 128000 :source :estimate}})))
  (let [warning (catalog/capacity-warning
                 {:context-window 1000} 1500)]
    (is (:over? warning))
    (is (re-find #"估算" (:message warning)))
    (is (re-find #"历史仍完整可读" (:message warning)))
    (is (= [:compress-history :reduce-attachments :adjust-scope :switch-model]
           (:actions warning))))
  (is (:unknown? (catalog/capacity-warning {:context-window :unknown} 100))))

(deftest reasoning-test
  (is (= "最低可用（该模型不支持关闭推理）"
         (catalog/reasoning-label
          {:reasoning {:selected :off :can-disable? false}})))
  (is (= "关闭"
         (catalog/reasoning-label
          {:reasoning {:selected :off :can-disable? true}})))
  (is (= "使用默认" (catalog/reasoning-label {})))
  (is (= "使用默认" (catalog/parameter-label :default)))
  (is (= "0" (catalog/parameter-label 0)))
  (is (= "使用默认" (catalog/parameter-label nil))))

(deftest refresh-preserves-local-model-fields-test
  (let [existing [(support/model "m1" "M1" {:enabled? false
                                            :alias "friendly"
                                            :notes "keep me"})
                  (support/model "m2" "M2" {:enabled? true})]
        merged (catalog/merge-model-list
                existing
                [{:model-id "m1" :display-name "M1 new"
                  :capabilities {:vision :known}}]
                :listed)]
    (is (= 2 (count merged)))
    (is (= false (:enabled? (first merged))))
    (is (= "friendly" (:alias (first merged))))
    (is (= "keep me" (:notes (first merged))))
    (is (= :known (get-in (first merged) [:capabilities :vision])))
    (is (= :not-returned (:availability (second merged))))
    (is (true? (:not-returned? (second merged)))))

  (testing "an unavailable listing does not erase rows"
    (let [existing [(support/model "m1" "M1")]
          merged (catalog/merge-model-list existing [] :unavailable)]
      (is (= ["m1"] (mapv :model-id merged)))
      (is (= [true] (mapv :enabled? merged)))))

  (testing "manual model ids are allowed; availability is not-returned"
    (let [models (catalog/add-manual-model [] "manual-model")
          row (first models)]
      (is (= "manual-model" (:model-id row)))
      (is (= :unknown (:pricing row)))
      ;; A typed id was not returned by a listing; it is not proven unusable.
      (is (= :not-returned (:availability row)))))

  (testing "a refresh preserves a user-declared capability"
    (let [old (assoc (support/model "m" "M")
                     :capabilities {:tools true}
                     :capability-sources {:tools :user}
                     :capability-baseline {:tools :unknown})
          row (first (catalog/merge-model-list
                      [old]
                      [{:model-id "m"
                        :display-name "M new"
                        :capabilities {:tools :unknown}}]
                      :listed))]
      (is (= "M new" (:display-name row)))
      (is (= :known (get-in row [:capabilities :tools])))
      (is (= :user (get-in row [:capability-sources :tools])))
      (is (= :unknown (get-in row [:capability-baseline :tools]))))))

(deftest selection-and-round-test
  (let [a (normalized (support/connection "a" "A" "https://a.example.com/v1"))
        groups (catalog/grouped-models
                {"a" a}
                {"a" [(support/model "m" "M")]}
                {"a" :listed})
        selection (catalog/selection "a" "m")
        row (catalog/find-row groups selection)]
    (is row)
    (is (re-find #"A · a\.example\.com"
                 (catalog/selection-label groups selection)))
    (is (re-find #"原模型已不可用"
                 (catalog/selection-label groups
                                          (catalog/selection "a" "gone"))))
    (let [snapshot (catalog/model-snapshot row "2026-11-03T10:00:00Z")]
      (is (= "a" (:connection-id snapshot)))
      (is (= "m" (:model-id snapshot)))
      (is (= "2026-11-03T10:00:00Z" (:captured-at snapshot))))
    (is (= :next-round (:mode (catalog/round-switch-plan true selection))))
    (is (re-find #"当前回复仍归属原模型"
                 (:attribution-note (catalog/round-switch-plan true selection))))
    (is (some #(= :stop-and-switch (:mode %))
              (:alternatives (catalog/round-switch-plan true selection))))
    (is (= "s1"
           (get-in (catalog/round-switch-plan true selection "s1")
                   [:alternatives 0 :effect :session-id])))))

(deftest refresh-keeps-unknown-availability-test
  (let [merged (catalog/merge-model-list
                []
                [{:model-id "m" :display-name "M"
                  :availability :unknown}]
                :listed)
        row (first merged)]
    (is (= :unknown (:availability row)))
    (is (= "尚未确认"
           (catalog/availability-label row {:enabled? true})))))

(deftest manual-model-availability-is-not-unknown
  (is (= :not-returned (:availability (catalog/manual-model "typed-model")))))
