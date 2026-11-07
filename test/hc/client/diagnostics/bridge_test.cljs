(ns hc.client.diagnostics.bridge-test
  "Receipt-path tests for the diagnostics bridge.

  These tests use the real Fulcro app state and a real gateway-shaped CUSTOM
  receipt.  Only the Sente send function is replaced; the receipt still has to
  pass the bridge's pending-command check and the model's receipt reducer."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.diagnostics.bridge :as bridge]
            [hc.client.diagnostics.fixture :as fixture]
            [hc.client.diagnostics.model :as model]
            [hc.client.workbench.sync-bridge :as sync-bridge]
            [hc.client.workbench.transport :as transport]))

(comp/defsc FauxRoot
  [_this _props]
  {:query [:ui/diagnostics :ui/command]
   :ident (fn [] [:root/id :root])}
  nil)

(defn- test-app
  []
  (application/headless-synchronous-app
   FauxRoot
   {:initial-db {:ui/diagnostics (model/initial-state)
                 :ui/command {}}}))

(defn- setup!
  []
  (let [app (test-app)]
    (reset! sync-bridge/adapter
            {:phase :synced :subscription 1 :epoch 7 :seq 3
             :server {:sync {:command-epoch 9}}})
    (reset! bridge/app-ref app)
    (reset! bridge/installed? false)
    (bridge/install! app)
    app))

(defn- settle!
  [app]
  (try
    (application/render! app)
    (catch :default _ nil))
  app)

(defn- state-of
  [app]
  (:ui/diagnostics (application/current-state app)))

(deftest statistics-command-and-receipt-test
  (let [app (setup!)
        sent (atom nil)]
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/refresh})
      (settle! app))
    (testing "the bridge sends the statistics command with the current gate"
      (is (= :diagnostics/statistics (get-in @sent [:payload :kind])))
      (is (= 9 (:command-epoch @sent)))
      (is (string? (:command-id @sent)))
      (is (= :query (get-in @sent [:payload :query :zone-mode]))))
    (testing "the pending command is visible before the receipt"
      (is (contains? (:pending (state-of app)) (:command-id @sent))))
    (testing "a real CUSTOM receipt applies the statistics"
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? true
                :command-id (:command-id @sent)
                :receipt {:status :complete
                          :statistics fixture/stats}}})
      (settle! app)
      (let [state (state-of app)]
        (is (= fixture/stats (:stats state)))
        (is (= :ok (:status state)))
        (is (empty? (:pending state)))
        (is (= :applied (get-in (application/current-state app)
                                [:ui/command (:command-id @sent) :status])))
        (is (= ["gpt-x" "m-1"]
               (mapv :model (get-in state [:filter-options :models]))))))))

(deftest unknown-usage-receipt-is-not-zero-test
  (let [app (setup!)
        sent (atom nil)
        unknown-stats (-> fixture/stats
                          (assoc :tokens {:request-count 1
                                          :input-tokens :unknown
                                          :output-tokens :unknown
                                          :cache-read-tokens :unknown
                                          :cache-write-tokens :unknown
                                          :total-tokens :unknown
                                          :provenance {:input-tokens :unknown
                                                       :output-tokens :unknown
                                                       :cache-read-tokens :unknown
                                                       :cache-write-tokens :unknown}
                                          :total-provenance :unknown
                                          :reported-requests 0
                                          :estimated-requests 0
                                          :unknown-requests 1
                                          :unknown-requests-by-field
                                          {:input-tokens ["r1"]
                                           :output-tokens ["r1"]}})
                          (assoc :cost nil))]
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/refresh})
      (settle! app))
    (bridge/handle-event!
     {:type "CUSTOM"
      :name "hc/receipt"
      :value {:ok? true
              :command-id (:command-id @sent)
              :receipt {:status :complete :statistics unknown-stats}}})
    (settle! app)
    (let [state (state-of app)
          token (model/token-rows (:stats state))]
      (testing "unknown usage stays unknown"
        (is (= :unknown (get-in token [:rows 4 :value])))
        (is (= 1 (:unknown-requests token))))
      (testing "no cost is invented"
        (is (nil? (model/cost-rows (:stats state))))
        (is (nil? (model/cost-coverage (:stats state))))))))

(deftest failed-receipt-is-explicit-test
  (let [app (setup!)
        sent (atom nil)]
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/refresh})
      (settle! app))
    (bridge/handle-event!
     {:type "CUSTOM"
      :name "hc/command-receipt"
      :value {:ok? false
              :command-id (:command-id @sent)
              :error {:code :DIAGNOSTICS_UNAVAILABLE
                      :message "diagnostics service is not available"}}})
    (settle! app)
    (let [state (state-of app)]
      (is (= :error (:status state)))
      (is (= "diagnostics service is not available"
             (get-in state [:error :message])))
      (is (empty? (:pending state)))
      (is (nil? (:stats state))))))

(deftest failed-log-receipt-does-not-poison-statistics-test
  (let [app (setup!)
        sent (atom [])]
    (with-redefs [transport/command! (fn [envelope]
                                      (swap! sent conj envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/refresh})
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? true
                :command-id (:command-id (first @sent))
                :receipt {:status :complete
                          :statistics fixture/stats}}})
      (bridge/dispatch! {:type :diagnostics/log-enable
                         :mode :next-request})
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? false
                :command-id (:command-id (second @sent))
                :error {:message "log capture failed"}}}))
    (let [state (state-of app)]
      (testing "a log failure leaves the loaded statistics intact"
        (is (= :ok (:status state)))
        (is (nil? (:error state)))
        (is (= fixture/stats (:stats state))))
      (testing "the failure is still visible as a message"
        (is (= "log capture failed" (:message state)))))))

(deftest failed-bundle-receipt-does-not-poison-statistics-test
  (let [app (setup!)
        sent (atom [])]
    (with-redefs [transport/command! (fn [envelope]
                                      (swap! sent conj envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/refresh})
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? true
                :command-id (:command-id (first @sent))
                :receipt {:status :complete
                          :statistics fixture/stats}}})
      (bridge/dispatch! {:type :diagnostics/bundle-preview})
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? false
                :command-id (:command-id (second @sent))
                :error {:message "preview failed"}}}))
    (let [state (state-of app)]
      (is (= :ok (:status state)))
      (is (= fixture/stats (:stats state)))
      (is (= :error (get-in state [:bundle :status])))
      (is (nil? (model/bundle-value state)))
      (is (= "preview failed" (get-in state [:bundle :error :message]))))))

(deftest not-found-receipt-keeps-pending-and-marks-unknown-test
  (let [app (setup!)
        sent (atom nil)]
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/refresh})
      (settle! app))
    (bridge/handle-event!
     {:type "CUSTOM"
      :name "hc/command-receipt"
      :value {:ok? false
              :command-id (:command-id @sent)
              :receipt {:status :not-found}}})
    (settle! app)
    (let [state (state-of app)
          command-id (:command-id @sent)]
      (testing "the pending entry is kept for a later receipt"
        (is (contains? (:pending state) command-id))
        (is (= :unknown (get-in state [:pending command-id :status]))))
      (testing "the command is marked unknown, not rejected"
        (is (= :unknown
               (get-in (application/current-state app)
                       [:ui/command command-id :status]))))
      (testing "no domain result is applied and nothing is downloaded"
        (is (nil? (:stats state)))
        (is (= :idle (:status state)))
        (is (nil? (get-in state [:bundle :export])))
        (is (= "未找到回执，状态未知。" (:message state)))))))

(deftest unsent-statistics-command-restores-idle-test
  (let [app (setup!)]
    (with-redefs [transport/command! (fn [_envelope] false)]
      (bridge/dispatch! {:type :diagnostics/refresh})
      (settle! app))
    (let [state (state-of app)]
      (is (= :idle (:status state)))
      (is (str/includes? (:message state) "未发送"))
      (is (empty? (:pending state))))))

(deftest unsent-first-preview-restores-error-test
  (let [app (setup!)]
    (with-redefs [transport/command! (fn [_envelope] false)]
      (bridge/dispatch! {:type :diagnostics/bundle-preview})
      (settle! app))
    (let [state (state-of app)]
      (is (= :error (get-in state [:bundle :status])))
      (is (str/includes? (get-in state [:bundle :error :message])
                         "未发送"))
      (is (nil? (model/bundle-value state))))))

(deftest unsent-bundle-and-price-commands-restore-status-test
  (let [app (setup!)
        sent (atom nil)]
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/bundle-preview})
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? true
                :command-id (:command-id @sent)
                :receipt {:status :complete
                          :bundle fixture/bundle}}}))
    (testing "a preview that cannot be sent returns to the previous preview"
      (with-redefs [transport/command! (fn [_envelope] false)]
        (bridge/dispatch! {:type :diagnostics/bundle-preview})
        (settle! app))
      (let [state (state-of app)]
        (is (= :preview (get-in state [:bundle :status])))
        (is (str/includes? (get-in state [:bundle :error :message])
                           "未发送"))
        (is (some? (model/bundle-value state)))))
    (testing "an export that cannot be sent returns to the preview"
      (with-redefs [transport/command! (fn [_envelope] false)]
        (bridge/dispatch! {:type :diagnostics/bundle-export})
        (settle! app))
      (let [state (state-of app)]
        (is (= :preview (get-in state [:bundle :status])))
        (is (str/includes? (get-in state [:bundle :error :message])
                           "未发送"))))
    (testing "a price save that cannot be sent leaves :saving"
      (doseq [[field value] [[:service-id "openai"]
                             [:model "gpt-x"]
                             [:currency "USD"]
                             [:input "1"]]]
        (bridge/dispatch! {:type :diagnostics/price-set
                           :field field
                           :value value}))
      (with-redefs [transport/command! (fn [_envelope] false)]
        (bridge/dispatch! {:type :diagnostics/save-price})
        (settle! app))
      (let [state (state-of app)]
        (is (= :error (get-in state [:price :status])))
        (is (str/includes? (get-in state [:price :error :message])
                           "未发送"))))))

(deftest failed-line-removal-restores-previous-set-test
  (let [app (setup!)
        sent (atom [])]
    (with-redefs [transport/command! (fn [envelope]
                                      (swap! sent conj envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/bundle-preview})
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? true
                :command-id (:command-id (first @sent))
                :receipt {:status :complete
                          :bundle fixture/bundle}}})
      (bridge/dispatch! {:type :diagnostics/bundle-remove-line
                         :line-id "app-os"})
      (let [remove-command (second @sent)
            optimistic (state-of app)]
        (testing "the optimistic removal is visible before the receipt"
          (is (contains? (get-in optimistic [:bundle :removed-lines])
                         "app-os")))
        (bridge/handle-event!
         {:type "CUSTOM"
          :name "hc/command-receipt"
          :value {:ok? false
                  :command-id (:command-id remove-command)
                  :error {:message "remove failed"}}})
        (settle! app)
        (let [state (state-of app)]
          (testing "the failure restores the set from before the click"
            (is (empty? (get-in state [:bundle :removed-lines])))
            (is (some #(= "app-os" (:id %))
                      (get-in (model/bundle-sections state) [0 :lines]))))
          (testing "the preview stays visible under the error"
            (is (= :preview (get-in state [:bundle :status])))
            (is (= "remove failed"
                   (get-in state [:bundle :error :message])))))))))

(deftest unsent-line-removal-restores-previous-set-test
  (let [app (setup!)
        sent (atom nil)]
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (bridge/dispatch! {:type :diagnostics/bundle-preview})
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value {:ok? true
                :command-id (:command-id @sent)
                :receipt {:status :complete
                          :bundle fixture/bundle}}}))
    (with-redefs [transport/command! (fn [_envelope] false)]
      (bridge/dispatch! {:type :diagnostics/bundle-remove-line
                         :line-id "app-os"})
      (settle! app))
    (let [state (state-of app)]
      (is (empty? (get-in state [:bundle :removed-lines])))
      (is (= :preview (get-in state [:bundle :status]))))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.diagnostics.bridge-test))

(set! *main-cli-fn* -main)
