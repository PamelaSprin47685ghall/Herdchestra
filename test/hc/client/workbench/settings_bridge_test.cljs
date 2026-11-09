(ns hc.client.workbench.settings-bridge-test
  "Persistence regression for the settings bridge draft export.

  The workbench alert `工作台启动失败：TypeError: Converting circular
  structure to JSON … at tick … JSON.stringify` came from
  `persist-drafts!`: `clj->js` preserves JavaScript object identity, so a
  draft value that ever carried a `#js` object with a reference cycle made
  `JSON.stringify` throw inside a `setTimeout` tick.  These tests pin the
  `json-safe` scrub: the export must survive cycles, shared references,
  functions, and opaque host objects, and the persisted payload must still
  round-trip through `import-drafts` without secrets."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [com.fulcrologic.fulcro.application :as application]
            [hc.client.settings :as settings-api]
            [hc.client.settings.commands :as settings-commands]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.util :as util]
            [hc.client.workbench.settings-bridge :as bridge]))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn- blank
  []
  (draft/blank-draft :cloud-key {:draft-id "d1"
                                 :key-id "k1"
                                 :auth-edit-id "e1"}))

(deftest json-safe-breaks-js-reference-cycles
  (let [cycle #js {}
        _ (aset cycle "self" cycle)
        payload (util/json-safe {:note cycle})]
    (is (= "[circular]"
           (get-in payload ["note" "self"]))
        "a self-referencing #js value must not recurse forever")))

(deftest json-safe-copies-plain-js-objects
  (let [payload (util/json-safe {:a #js {:x 1 :y "s"}})]
    (is (= 1 (get-in payload ["a" "x"])))
    (is (= "s" (get-in payload ["a" "y"])))
    ;; The copy is a fresh map, not the original identity.
    (is (not (identical? payload {:a #js {:x 1 :y "s"}})))))

(deftest json-safe-replaces-functions-and-opaque-objects
  (let [payload (util/json-safe
                 {:fn-val (fn [] 1)
                  :opaque (js/Date.)})]
    (is (= "[function]" (get payload "fn-val")))
    (is (string? (get payload "opaque")))))

(deftest export-drafts-survives-cyclic-draft-values
  ;; A draft whose field somehow carries a cyclic #js value must still
  ;; produce a payload `JSON.stringify` accepts; `clj->js` would otherwise
  ;; hand the cycle straight to the serializer.
  (let [d (blank)
        cycle #js {}
        _ (aset cycle "self" cycle)
        drafts {"d1" (assoc-in d [:fields :auth] {:nested cycle})}
        payload (draft/export-drafts drafts)
        js-payload (clj->js (util/json-safe payload))
        text (js/JSON.stringify js-payload)]
    (is (string? text) "JSON.stringify must not throw")
    (is (re-find #"circular" text)
        "the cycle survives as a marker, not a crash")))

(deftest export-drafts-still-strips-secrets
  (let [d (draft/edit-key-secret (blank) "k1" "raw-secret"
                                 {:auth-edit-id "e2"})
        payload (draft/export-drafts {"d1" d})]
    (is (not (draft/secret-leak? payload)))
    (let [restored (draft/import-drafts
                    (js->clj (js/JSON.parse
                              (js/JSON.stringify
                               (clj->js (util/json-safe payload))))
                             :keywordize-keys true))]
      (is (= #{:d1} (set (keys restored)))
          "sanitized export must round-trip through import-drafts"))))

(deftest merge-notice-replaces-identical-text-test
  (let [notices [{:id "a" :text "设置草稿已保存到本浏览器。" :tone :info}
                 {:id "b" :text "设置命令未发送：实时通道未打开。" :tone :warn}
                 {:id "a2" :text "设置草稿已保存到本浏览器。" :tone :info}]
        merged (bridge/merge-notice
                notices
                {:id "c" :text "设置草稿已保存到本浏览器。" :tone :info})]
    (is (= ["设置命令未发送：实时通道未打开。" "设置草稿已保存到本浏览器。"]
           (mapv :text merged)))
    (is (= "c" (:id (peek merged)))
        "the newest copy of the repeated text is kept")
    (is (= 1 (count (filter #(= "设置草稿已保存到本浏览器。" (:text %))
                            merged)))
        "identical text occupies one notice slot")))

(deftest merge-notice-keeps-different-text-and-errors-test
  (let [merged (bridge/merge-notice
                [{:id "a" :text "设置草稿已保存到本浏览器。" :tone :info}]
                {:id "b"
                 :text "设置草稿暂未保存：浏览器本地存储不可用。"
                 :tone :warn})]
    (is (= 2 (count merged)))
    (is (= :warn (:tone (peek merged))))
    (is (some #(= "设置草稿已保存到本浏览器。" (:text %)) merged)
        "different text stays queued next to the error")))

(defn- auth-action
  [state action]
  (:state (settings-api/reduce-action state action)))

(defn- auth-begin-state
  []
  (let [state (-> (settings-api/initial-state
                   {:sync {:phase :synced :command-epoch 7}
                    :transport-available? true})
                  (auth-action {:type :settings/new-connection
                                :path :account-login
                                :draft-id "draft-auth"
                                :key-id "auth-key"
                                :auth-edit-id "auth-edit"}))
        draft-id (get-in state [:ui :selected-draft-id])
        state (-> state
                  (auth-action {:type :settings/edit-field
                                :draft-id draft-id
                                :field :name
                                :value "Account Service"})
                  (auth-action {:type :settings/edit-field
                                :draft-id draft-id
                                :field :base-url
                                :value "https://auth.example.com/v1"})
                  (auth-action {:type :model-auth/providers
                                :providers [{:id "demo" :name "Demo"
                                             :configured? true
                                             :flows [:device-code]}]}))]
    (auth-action state {:type :model-auth/begin
                        :provider-id "demo"
                        :connection-id draft-id
                        :flow-type :device-code
                        :command-id "auth-begin"})))

(defn- begin-receipt
  [command-id]
  {:ok? true
   :command-id command-id
   :receipt {:status :pending
             :flow-id "flow-1"
             :flow-type :device-code
             :connection-id "draft-auth"
             :interval-seconds 1
             :user-code "MOCK-1"}})

(defn- authorized-receipt
  []
  {:ok? true
   :command-id "poll-1"
   :receipt {:status :authorized
             :flow-id "flow-1"
             :connection-id "draft-auth"
             :account {:provider-id "demo"
                       :issuer "https://issuer.example.com"
                       :subject "subject-1"
                       :connection-id "account-1"}}})

(deftest model-auth-receipt-schedules-and-cancels-poll
  (let [state (auth-begin-state)
        command-id (-> state :outbox last :command-id)
        {:keys [effects]} (settings-api/apply-receipt-result state
                                                             (begin-receipt
                                                              command-id))]
    (is (some #(= :schedule-poll (:effect %)) effects)
        "the begin receipt carries the poll scheduling effect"))
  (let [base (auth-begin-state)
        command-id (-> base :outbox last :command-id)
        ;; The begin command is already answered for this focused bridge test;
        ;; keep it accepted so the queued poll cannot be mistaken for it.
        base (-> base
                 (assoc-in [:commands command-id :status] :accepted)
                 (assoc :outbox [])
                 (assoc-in [:ui :model-auth :status] :pending)
                 (assoc-in [:ui :model-auth :flow]
                           {:flow-id "flow-1"
                            :flow-type :device-code
                            :status :pending}))
        queued (:state (settings-commands/queue
                        base
                        :model-auth/poll
                        {:flow-id "flow-1"}
                        {:command-id "poll-1"}))
        app (application/fulcro-app {:initial-db {:ui/settings queued}})]
    (reset! bridge/app-ref app)
    (reset! bridge/auth-timers {})
    (reset! bridge/auth-tick-timers {})
    (try
      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value (begin-receipt command-id)})
      (is (contains? @bridge/auth-timers "flow-1")
          "the bridge must execute :schedule-poll, not just keep the receipt state")

      (bridge/handle-event!
       {:type "CUSTOM"
        :name "hc/command-receipt"
        :value (authorized-receipt)})
      (is (not (contains? @bridge/auth-timers "flow-1"))
          "the authorized receipt must cancel the scheduled poll")
      (let [applied (settings-api/apply-receipt-result
                     (get-in (application/current-state app)
                             [:ui/settings])
                     (authorized-receipt))]
        (is (= :authorized
               (get-in (:state applied) [:ui :model-auth :status])))
        (is (some #(= :cancel-poll (:effect %)) (:effects applied))
            "the authorized receipt carries the cancel effect"))
      (finally
        (doseq [[_ timer] @bridge/auth-timers]
          (js/clearTimeout timer))
        (doseq [[_ timer] @bridge/auth-tick-timers]
          (js/clearInterval timer))
        (reset! bridge/auth-timers {})
        (reset! bridge/auth-tick-timers {})
        (reset! bridge/app-ref nil)))))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.settings-bridge-test))

(set! *main-cli-fn* -main)
