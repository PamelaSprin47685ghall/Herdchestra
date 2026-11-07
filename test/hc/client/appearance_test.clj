(ns hc.client.appearance-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.appearance.model :as model]
            [hc.client.appearance.view :as view]))

(defn- nodes [tree]
  (tree-seq map? :children tree))

(defn- node-with-id [tree id]
  (first (filter #(= id (get-in % [:attrs :id])) (nodes tree))))

(deftest normalize-and-clamp-test
  (testing "defaults are complete"
    (is (= model/default (model/normalize {}))))
  (testing "font scale is clamped to 80-200 and snapped to 10%"
    (is (= 80 (model/normalize-font-scale "10")))
    (is (= 200 (model/normalize-font-scale "250")))
    (is (= 130 (model/normalize-font-scale "126")))
    (is (= 130 (model/normalize-font-scale 126))))
  (testing "unknown values fall back"
    (is (= :system (:theme (model/normalize {:theme :neon}))))
    (is (= :normal (:contrast (model/normalize {:contrast :ultra}))))
    (is (= :system (:reduced-motion (model/normalize {:reduced-motion nil}))))
    (is (= :reduce (:reduced-motion (model/normalize {:reduced-motion? true}))))
    (is (= :system (:reduced-motion (model/normalize {:reduced-motion? false})))))
  (testing "the system theme uses the stylesheet's auto attribute"
    (is (= "auto" (model/theme-attribute :system)))
    (is (= "dark" (model/theme-attribute :dark)))))

(deftest reduce-action-test
  (let [state (model/normalize {})
        dark (model/reduce-action state {:type :appearance/set-theme :theme :dark})
        larger (model/reduce-action (:state dark) {:type :appearance/increase-font})
        high (model/reduce-action (:state larger) {:type :appearance/set-contrast
                                                    :value true})
        motion (model/reduce-action (:state high) {:type :appearance/set-reduced-motion
                                                   :value true})
        reset (model/reduce-action (:state motion) {:type :appearance/reset})]
    (is (= :dark (get-in dark [:state :theme])))
    (is (= 110 (get-in larger [:state :font-scale])))
    (is (= :high (get-in high [:state :contrast])))
    (is (= :reduce (get-in motion [:state :reduced-motion])))
    (is (= 100 (get-in (model/reduce-action (:state larger)
                                             {:type :appearance/reset-font})
                       [:state :font-scale]))
        "font reset does not reset theme or contrast")
    (is (= model/default (:state reset)))
    (is (= [{:effect :apply} {:effect :persist}] (:effects dark)))
    (is (= [] (:effects (model/reduce-action state {:type :appearance/set-theme
                                                    :theme :system}))))))

(deftest db-roundtrip-test
  (let [state {:theme :dark :font-scale 180 :contrast :high
               :reduced-motion :reduce}
        db (model/to-db state)]
    (is (= {:ui/theme :dark :ui/font-scale 180 :ui/contrast :high
            :ui/reduced-motion :reduce}
           db))
    (is (= state (model/from-db db)))))

(deftest tree-has-real-controls-test
  (let [tree (view/settings-tree {:theme :dark :font-scale 150
                                  :contrast :high :reduced-motion :reduce})
        by-id (fn [id] (node-with-id tree id))]
    (testing "theme buttons carry pressed state and actions"
      (let [buttons (filter #(= :button (get-in % [:attrs :type]))
                            (nodes tree))
            dark (first (filter #(= "深色" (first (:children %))) buttons))]
        (is (= "true" (get-in dark [:attrs :aria-pressed])))
        (is (= :appearance/set-theme (get-in dark [:attrs :on-click :action :type])))))
    (testing "font range is native and bounded"
      (let [range (by-id "hc-appearance-font-scale")]
        (is (= "range" (get-in range [:attrs :type])))
        (is (= 80 (get-in range [:attrs :min])))
        (is (= 200 (get-in range [:attrs :max])))
        (is (= 10 (get-in range [:attrs :step])))
        (is (= 150 (get-in range [:attrs :value])))))
    (testing "contrast is a checkbox and reduced motion is a discrete choice"
      (is (= true (get-in (by-id "hc-appearance-contrast") [:attrs :checked])))
      (is (= "reduce" (get-in (by-id "hc-appearance-reduced-motion")
                              [:attrs :value])))
      (is (= :target-value (get-in (by-id "hc-appearance-reduced-motion")
                                   [:attrs :on-change :value])))
      (is (= :target-checked (get-in (by-id "hc-appearance-contrast")
                                     [:attrs :on-change :value]))))
    (testing "status is live and names current values"
      (let [status (first (filter #(= "status" (get-in % [:attrs :role]))
                                  (nodes tree)))]
        (is (= "polite" (get-in status [:attrs :aria-live])))
        (is (re-find #"150%" (first (:children status))))))))
