(ns hc.client.speech.translation-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.speech.translation-model :as model]))

(defn- result
  [& {:as opts}]
  (merge {:translated-text "译文"
          :fragment? true
          :request-start 6
          :request-end 11
          :request-text "world"
          :source-at-request "Hello world!"}
         opts))

(deftest replace-only-when-source-and-selection-match
  (let [source "Hello world!"
        selection {:start 6 :end 11}
        result (result)]
    (is (= {:action :replace
            :text "Hello 译文!"
            :cursor 8}
           (model/replace-plan {:source source
                                :selection selection
                                :result result})))
    (testing "source edits block replacement"
      (is (= :blocked
             (:action (model/replace-plan
                       {:source "Hello brave world!"
                        :selection selection
                        :result result})))))
    (testing "selection movement blocks replacement"
      (is (= :blocked
             (:action (model/replace-plan
                       {:source source
                        :selection {:start 0 :end 5}
                        :result result})))))
    (testing "a full-text translation has no replace target"
      (is (= :blocked
             (:action (model/replace-plan
                       {:source source
                        :selection selection
                        :result (result :fragment? false)})))))))

(deftest insert-at-cursor-is-always-safe
  (is (= {:action :insert
          :text "Hello world!"
          :cursor 6}
         (model/insert-plan "world!" {:start 0} "Hello ")))
  (is (= {:action :insert
          :text "Hello world!"
          :cursor 12}
         (model/insert-plan "Hello " {:start 99} "world!"))))

(deftest enabled-service-selection
  (let [services [{:id "disabled" :enabled? false}
                  {:id "first" :enabled? true}
                  {:id "second" :enabled? true}]]
    (testing "an enabled default is kept"
      (is (= "second"
             (model/select-service-id services "second"))))
    (testing "a disabled default falls back to the first enabled service"
      (is (= "first"
             (model/select-service-id services "disabled"))))
    (testing "no enabled service yields nil"
      (is (nil? (model/select-service-id
                 [{:id "off" :enabled? false}] "off"))))))

(deftest late-results-do-not-overwrite-other-languages
  (let [empty {}
        english (model/remember-result empty "en" {:translated-text "one"})
        both (model/remember-result english "ja" {:translated-text "two"})]
    (testing "each target keeps its own result"
      (is (= "one" (:translated-text (model/result-for both "en"))))
      (is (= "two" (:translated-text (model/result-for both "ja"))))
      (is (nil? (model/result-for empty "en"))))
    (testing "a second result for the same target replaces only that target"
      (let [replaced (model/remember-result both "ja"
                                            {:translated-text "two-b"})]
        (is (= "two-b" (:translated-text (model/result-for replaced "ja"))))
        (is (= "one" (:translated-text (model/result-for replaced "en"))))))))

(deftest replace-request-is-only-produced-for-a-safe-selection
  (let [source "Hello world!"
        selection {:start 6 :end 11 :text "world"}
        safe (result)]
    (is (= {:start 6
            :end 11
            :expected-text "world"
            :text "译文"}
           (model/replace-request source selection safe)))
    (testing "moving the selection blocks the replace request"
      (is (nil? (model/replace-request
                 source
                 {:start 0 :end 5 :text "Hello"}
                 safe))))
    (testing "editing the source blocks the replace request"
      (is (nil? (model/replace-request
                 "Hello brave world!"
                 selection
                 safe))))
    (testing "a whole-message translation has no replace target"
      (is (nil? (model/replace-request
                 source selection (result :fragment? false)))))))
