(ns hc.client.browser.model
  "Pure client state for the managed browser workbench.

  The server is the authority for tabs, permission, takeover and page cards.
  This namespace projects confirmed snapshots, keeps command envelopes in an
  outbox until the Sente transport accepts them, and never marks a browser
  action complete from a local click.

  UI rules that are not configurable:

    * an action needs the latest observation id from the server;
    * a page under operator control disables agent actions;
    * a sensitive field is shown as operator-only, never filled by the UI;
    * page cards say their screenshots are historical.

  No I/O, Fulcro, React or timers live here."
  (:require [clojure.string :as str]))

(def browser-kinds
  #{:browser/permission :browser/snapshot :browser/cards
    :browser/open-tab :browser/navigate :browser/back :browser/forward
    :browser/reload :browser/observe :browser/act
    :browser/takeover :browser/operator-input :browser/resume
    :browser/close-tab :browser/stop})

(def takeover-labels
  {:agent-active "代理可操作"
   :read-only "只读交接"
   :paused-manual "检测到手动操作"
   :operator "操作员控制"
   :sensitive-paused "敏感输入等待操作员"})

(def action-labels
  {:click "点击" :type "输入" :scroll "滚动" :press "按键" :submit "提交"
   :observe "观察" :navigate "导航" :open-tab "打开"
   :back "后退" :forward "前进" :reload "刷新" :takeover "接管"
   :operator-input "手动操作" :resume "恢复" :close-tab "关闭页面"
   :stop "停止代理" :permission "权限"})

(def status-labels
  {:queued "待发送" :submitting "发送中" :accepted "已接受"
   :failed "失败" :refused "被拒绝"})

(defn now-string
  []
  #?(:cljs (str (js/Date.))
     :clj (str (java.time.Instant/now))))

(defn- new-id
  []
  #?(:cljs (str (random-uuid))
     :clj (str (java.util.UUID/randomUUID))))

(defn initial-state
  []
  {:phase :idle
   :session-id nil
   :permission {:enabled? false :scope :current-tab :sites []
                :private-read-approved? false :persisted? false}
   :permission-form {:scope :current-tab :site-text ""}
   :tab-filter ""
   :tabs {}
   :tab-order []
   :active-tab-id nil
   :observation nil
   :selected-ref nil
   :selected-node nil
   :type-text ""
   :resume-mode :agent
   :address ""
   :address-edited? false
   :cards []
   :cards-meta {:dropped 0 :truncated? false}
   :limits {}
   :stopped? false
   :commands {}
   :outbox []
   :last-error nil
   :last-command-id nil
   :sync {:phase :disconnected :command-epoch nil}})

(defn command-allowed?
  "Transport/session readiness for any command, including operator actions
  such as closing a tab after the agent was stopped."
  [state]
  (and (= :synced (get-in state [:sync :phase]))
       (not (str/blank? (str (:session-id state))))))

(defn takeover-state
  [state]
  (or (get-in state [:tabs (:active-tab-id state) :takeover])
      :agent-active))

(declare active-tab)

(defn tab-authorized-for?
  "True when TAB is approved for model reading under the current permission.
  The server independently enforces the same rule."
  [state tab]
  (let [permission (:permission state)
        scope (:scope permission)]
    (boolean
     (and tab
          (:enabled? permission)
          (case scope
            :current-tab (true? (:agent-authorized? tab))
            :session-tabs (true? (:agent-authorized? tab))
            :site (and (contains? (set (:sites permission)) (:site tab))
                       (not (:manual-only? tab)))
            false)))))

(defn tab-authorized?
  "True when the active tab is approved for model reading."
  [state]
  (tab-authorized-for? state (active-tab state)))

(defn read-allowed?
  [state]
  (and (command-allowed? state)
       (not (:stopped? state))
       (contains? #{:agent-active :read-only} (takeover-state state))
       (tab-authorized? state)))

(defn agent-action-allowed?
  [state]
  (and (command-allowed? state)
       (not (:stopped? state))
       (= :agent-active (takeover-state state))
       (tab-authorized? state)))

(defn busy?
  [state]
  (boolean
   (some (fn [[_ command]]
           (contains? #{:queued :submitting} (:status command)))
         (:commands state))))

(defn active-tab
  [state]
  (get-in state [:tabs (:active-tab-id state)]))

(defn selected-node
  [state]
  (or (when-let [node (:selected-node state)]
        (when (= (:selected-ref state) (:ref node)) node))
      (when-let [ref (:selected-ref state)]
        (some #(when (= ref (:ref %)) %)
              (get-in state [:observation :nodes])))))

(defn- tab-order
  [tabs]
  (vec (sort-by (fn [id]
                  (let [tab (get tabs id)]
                    [(or (:created-at tab) 0) (str id)]))
                (keys tabs))))

(defn- card-vector
  [cards]
  (cond
    (nil? cards) []
    (map? cards) (vec (:cards cards))
    :else (vec cards)))

(defn apply-snapshot
  "Project one raw `browser/snapshot` value.  Local address/type drafts are
  preserved unless the active tab changed.  A selection survives a snapshot
  that carries the same observation; a new observation clears it."
  [state snapshot]
  (if-not (and (map? snapshot)
               (or (contains? snapshot :tabs)
                   (contains? snapshot :active-tab-id)))
    state
    (let [tabs (or (:tabs snapshot) {})
          order (tab-order tabs)
          active (or (:active-tab-id snapshot)
                     (first order))
          previous-active (:active-tab-id state)
          tab (get tabs active)
          observation (:last-observation tab)
          same-observation? (= (get-in state [:observation :observation-id])
                               (:observation-id observation))
          cards (card-vector (:cards snapshot))
          dropped (or (:cards-dropped snapshot)
                      (get-in state [:cards-meta :dropped])
                      0)
          address-changed? (not= previous-active active)
          state (-> state
                    (assoc :phase :ready
                           :tabs tabs
                           :tab-order order
                           :active-tab-id active
                           :observation observation
                           :cards cards
                           :offline? (boolean (:offline? snapshot))
                           :cards-meta {:dropped (long (or dropped 0))
                                        :truncated? (pos? (long (or dropped 0)))}
                           :limits (or (:limits snapshot) {})
                           :stopped? (boolean (:stopped? snapshot))
                           :permission (or (:permission snapshot)
                                           (:permission state))))
          state (if same-observation?
                  state
                  (assoc state :selected-ref nil :selected-node nil))]
      (cond-> state
        (or address-changed? (not (:address-edited? state)))
        (assoc :address (or (:url tab) "")
               :address-edited? false)))))

(defn apply-cards
  [state cards]
  (if-not (and (map? cards) (contains? cards :cards))
    state
    (assoc state
           :cards (vec (:cards cards))
           :cards-meta {:dropped (long (or (:dropped cards) 0))
                        :truncated? (boolean (:truncated? cards))})))

(defn apply-observation
  [state observation]
  (if-not (map? observation)
    state
    (let [tab-id (:tab-id observation)
          same-observation? (= (get-in state [:observation :observation-id])
                               (:observation-id observation))
          state (if (and tab-id (get-in state [:tabs tab-id]))
                  (assoc-in state [:tabs tab-id :last-observation] observation)
                  state)
          state (assoc state :observation observation)
          state (if same-observation?
                  state
                  (assoc state :selected-ref nil :selected-node nil))]
      (cond-> state
        (not (:address-edited? state))
        (assoc :address (or (:url observation) (:address state)))))))

(defn- receipt-map
  [receipt]
  (cond
    (not (map? receipt)) {}
    (map? (:hc/receipt receipt)) (:hc/receipt receipt)
    (and (map? (:receipt receipt)) (nil? (:command-id receipt))) (:receipt receipt)
    :else receipt))

(defn receipt-command-id
  [receipt]
  (let [receipt (receipt-map receipt)]
    (or (:command-id receipt)
        (get-in receipt [:receipt :command-id]))))

(defn receipt-status
  [receipt]
  (let [receipt (receipt-map receipt)
        result (or (:result receipt)
                   (get-in receipt [:receipt :result]))
        result-status (or (:status result)
                          (get-in result [:result :status]))]
    (cond
      (false? (:ok? receipt)) :refused
      (some? (:error receipt)) :refused
      (some? (:error result)) :refused
      (contains? #{:refused :failed} result-status) :refused
      :else (or (:status receipt)
                (get-in receipt [:receipt :status])
                :accepted))))

(defn receipt-result
  [receipt]
  (let [receipt (receipt-map receipt)]
    (or (:result receipt)
        (get-in receipt [:receipt :result]))))

(defn receipt-error
  [receipt]
  (let [receipt (receipt-map receipt)]
    (or (:error receipt)
        (get-in receipt [:receipt :error])
        (get-in receipt [:result :error]))))

(defn- apply-command-result
  [state command receipt]
  (let [result (receipt-result receipt)
        result-error (or (:error result) (receipt-error receipt))]
    (case (:kind command)
      ;; The server returns the new permission under the receipt's `:state`.
      ;; Project it immediately so an accepted enable click is visible without
      ;; waiting for the follow-up snapshot.  The snapshot remains the
      ;; authority; this only reflects the confirmed receipt.
      :browser/permission
      (let [state-result (or (:state receipt)
                             (get-in receipt [:receipt :state])
                             (:state result))
            permission (or (:permission state-result)
                           (:permission result))]
        (cond-> state
          (map? permission) (assoc :permission permission)
          result-error (assoc :last-error result-error)))

      :browser/snapshot
      (let [snapshot (or (:snapshot receipt)
                         (get-in receipt [:receipt :snapshot])
                         result)]
        (if (and (map? snapshot) (contains? snapshot :tabs))
          (apply-snapshot state snapshot)
          (assoc state :last-error
                 {:message "服务器未返回完整浏览器快照；保留当前页面状态。"})))

      :browser/cards
      (let [cards (or (:cards receipt)
                      (get-in receipt [:receipt :cards])
                      result)]
        (if (and (map? cards) (contains? cards :cards))
          (apply-cards state cards)
          (assoc state :last-error
                 {:message "服务器未返回完整页面卡；保留当前列表。"})))

      :browser/observe
      (let [observation (or (:observation result)
                            (get-in result [:result :observation]))]
        (cond-> (if observation
                  (apply-observation state observation)
                  state)
          result-error (assoc :last-error result-error)))

      (:browser/resume :browser/act)
      (let [observation (or (:observation result)
                            (get-in result [:result :observation]))
            tab (or (:tab result) (get-in result [:result :tab]))
            takeover (or (:takeover result) (get-in result [:result :takeover])
                         (:takeover tab))
            state (if observation (apply-observation state observation) state)
            state (if (and tab (:tab-id tab))
                    (assoc-in state [:tabs (:tab-id tab)] tab)
                    state)]
        (cond-> (assoc state :selected-ref nil :selected-node nil)
          (= :browser/resume (:kind command))
          (-> (assoc :resume-mode :agent)
              (assoc-in [:tabs (:active-tab-id state) :takeover]
                        (or takeover :agent-active)))
          (= :browser/act (:kind command)) (assoc :type-text "")
          result-error (assoc :last-error result-error)))

      :browser/stop
      (assoc state :stopped? true)

      :browser/open-tab
      (let [tab (or (:tab result) (get-in result [:result :tab]))
            state (if (and tab (:tab-id tab))
                    (-> state
                        (assoc-in [:tabs (:tab-id tab)] tab)
                        (assoc :active-tab-id (:tab-id tab))
                        (update :tab-order
                                (fn [order]
                                  (vec (distinct (conj (or order []) (:tab-id tab)))))))
                    state)]
        (assoc state
               :address (or (:url tab) (:address state))
               :address-edited? false))

      (:browser/navigate :browser/back :browser/forward :browser/reload)
      (let [tab (or (:tab result) (get-in result [:result :tab]))
            page-changed? (boolean (or (:navigation result) (:tab result)))]
        (cond-> state
          page-changed? (assoc :observation nil
                               :selected-ref nil
                               :selected-node nil
                               :address (or (:url tab) (:address state)))
          (and tab (:tab-id tab)) (assoc-in [:tabs (:tab-id tab)] tab)
          true (assoc :address-edited? false)))

      :browser/close-tab
      (let [tab (or (:tab result) (get-in result [:result :tab]))]
        (cond-> state
          (and tab (:tab-id tab)) (assoc-in [:tabs (:tab-id tab)] tab)
          (and tab (= :closed (:status tab))) (assoc :selected-ref nil)))

      state)))

(defn apply-receipt
  "Attach one gateway receipt to its command.  Only an accepted receipt may
  project a result into the UI."
  [state receipt]
  (let [command-id (receipt-command-id receipt)
        command (get-in state [:commands command-id])]
    (if-not command
      state
      (let [status (receipt-status receipt)
            error (receipt-error receipt)
            state (update-in state [:commands command-id]
                             assoc
                             :status (if (= :refused status) :refused :accepted)
                             :receipt receipt
                             :updated-at (now-string))]
        (cond
          error
          (-> state
              (assoc :last-error error)
              (assoc-in [:commands command-id :status] :refused)
              (assoc-in [:commands command-id :message] (:message error)))

          (= :refused status)
          (-> state
              (assoc :last-error (or error {:message "服务器拒绝了浏览器命令"}))
              (assoc-in [:commands command-id :message]
                        (or (:message error) "服务器拒绝了浏览器命令")))

          :else
          (apply-command-result state command receipt))))))

;; ---------------------------------------------------------------------------
;; Command queue

(defn- pending-kind?
  [state kind]
  (boolean
   (some (fn [[_ command]]
           (and (= kind (:kind command))
                (contains? #{:queued :submitting} (:status command))))
         (:commands state))))

(def single-flight-kinds
  "Kinds that must not be queued twice while one is still in flight."
  #{:browser/snapshot :browser/observe :browser/permission
    :browser/takeover :browser/resume :browser/stop})

(defn queue-command
  "Queue one browser command.  Returns `{:ok? :state :command :error}`."
  ([state kind payload]
   (queue-command state kind payload {}))
  ([state kind payload opts]
   (cond
     (not (contains? browser-kinds kind))
     {:ok? false :state state :error "未知浏览器命令。"}

     (not (command-allowed? state))
     {:ok? false :state state
      :error "连接或会话尚未就绪；浏览器命令未发送。"}

     (and (contains? single-flight-kinds kind)
          (pending-kind? state kind))
     {:ok? false :state state
      :error "同类浏览器命令仍在途；不重复发送。"}

     :else
     (let [command-id (or (:command-id opts) (new-id))
           epoch (get-in state [:sync :command-epoch])
           payload (cond-> (assoc payload :kind kind)
                     (:session-id state) (assoc :session-id (:session-id state)))
           command {:command-id command-id
                    :command-epoch epoch
                    :kind kind
                    :payload payload
                    :status :queued
                    :queued-at (now-string)}]
       {:ok? true
        :command command
        :state (-> state
                   (assoc-in [:commands command-id] command)
                   (assoc :last-command-id command-id)
                   (update :outbox (fnil conj [])
                           {:command-id command-id
                            :command-epoch epoch
                            :payload payload}))}))))

(defn pending-outbox
  [state]
  (vec (:outbox state)))

(defn mark-submitting
  [state command-id]
  (-> state
      (update-in [:commands command-id] assoc :status :submitting)
      (update :outbox
              (fn [outbox]
                (into [] (remove #(= command-id (:command-id %))) outbox)))))

(defn mark-send-failed
  [state command-id message]
  (-> state
      (update-in [:commands command-id]
                 assoc :status :failed :message message)
      (update :outbox
              (fn [outbox]
                (into [] (remove #(= command-id (:command-id %))) outbox)))
      (assoc :last-error {:message message})))

(defn- queue-or-error
  [state kind payload]
  (let [{:keys [ok? state error command]} (queue-command state kind payload)]
    {:state (if error (assoc state :last-error {:message error}) state)
     :ok? (boolean ok?)
     :command command}))

(defn- tab-payload
  [state extra]
  (merge {:tab-id (:active-tab-id state)} extra))

(defn- parse-sites
  [text]
  (->> (str/split (str text) #"[\s,]+")
       (remove str/blank?)
       (map str/lower-case)
       (distinct)
       vec))

(defn- observation-id
  [state]
  (get-in state [:observation :observation-id]))

(defn- require-observation
  [state]
  (or (observation-id state)
      {:error "请先观察页面；动作必须引用最新观察。"}))

(defn- action-block
  [state message]
  (when-not (agent-action-allowed? state)
    {:state (assoc state :last-error {:message message}) :ok? false}))

(defn- read-block
  [state message]
  (when-not (read-allowed? state)
    {:state (assoc state :last-error {:message message}) :ok? false}))

(defn- observation-block
  [state]
  (when (nil? (observation-id state))
    {:state (assoc state :last-error
                   {:message "请先观察页面；动作必须引用最新观察。"}) :ok? false}))

(defn reduce-action
  "Apply one UI action.  Server-bound actions append one command envelope to
  the outbox; local actions only change display state."
  [state action]
  (let [type (:type action)]
    (case type
      :browser/set-address
      {:state (assoc state :address (str (or (:value action) ""))
                     :address-edited? true)}

      :browser/set-tab-filter
      {:state (assoc state :tab-filter (str (or (:value action) "")))}

      :browser/reopen-tab
      (let [tab-id (:tab-id action)
            tab (get-in state [:tabs tab-id])
            url (str (or (:url tab) ""))]
        (if (str/blank? url)
          {:state (assoc state :last-error {:message "该页面没有可重开的地址。"}) :ok? false}
          (queue-or-error state :browser/open-tab {:url url})))

      :browser/select-tab
      (let [tab-id (:tab-id action)
            tab (get-in state [:tabs tab-id])]
        {:state (cond-> (assoc state :active-tab-id tab-id
                              :selected-ref nil
                              :observation (:last-observation tab))
                  true (assoc :address (or (:url tab) (:address state))
                              :address-edited? false))})

      :browser/select-node
      (let [ref (:ref action)
            node (some #(when (= ref (:ref %)) %)
                       (get-in state [:observation :nodes]))]
        {:state (assoc state :selected-ref ref :selected-node node)})

      :browser/set-type-text
      {:state (assoc state :type-text (str (or (:value action) (:text action) "")))}

      :browser/set-resume-mode
      {:state (assoc state :resume-mode
                     (keyword (or (:value action) (:mode action) :agent)))}

      :browser/set-permission-scope
      {:state (assoc-in state [:permission-form :scope]
                        (keyword (or (:value action) (:scope action) :site)))}

      :browser/set-permission-site
      {:state (assoc-in state [:permission-form :site-text]
                        (str (or (:value action) "")))}

      :browser/dismiss-error
      {:state (assoc state :last-error nil)}

      :browser/apply-snapshot
      {:state (apply-snapshot state (:snapshot action))}

      :browser/apply-cards
      {:state (apply-cards state (:cards action))}

      :browser/apply-observation
      {:state (apply-observation state (:observation action))}

      :browser/receive-receipt
      {:state (apply-receipt state (:receipt action))}

      :browser/refresh
      (queue-or-error state :browser/snapshot {})

      :browser/open
      (let [url (str (or (:url action) (:address state) ""))]
        (if (str/blank? url)
          {:state (assoc state :last-error {:message "请输入地址。"}) :ok? false}
          (queue-or-error state :browser/open-tab {:url url})))

      :browser/navigate
      (or (action-block state "当前接管或权限状态禁止导航。")
          (let [url (str (or (:url action) (:address state) ""))]
            (cond
              (str/blank? url)
              {:state (assoc state :last-error {:message "请输入地址。"}) :ok? false}

              (nil? (:active-tab-id state))
              (queue-or-error state :browser/open-tab {:url url})

              :else
              (queue-or-error state :browser/navigate
                              (tab-payload state {:url url})))))

      :browser/back
      (or (action-block state "当前接管或权限状态禁止后退。")
          (queue-or-error state :browser/back (tab-payload state {})))

      :browser/forward
      (or (action-block state "当前接管或权限状态禁止前进。")
          (queue-or-error state :browser/forward (tab-payload state {})))

      :browser/reload
      (or (action-block state "当前接管或权限状态禁止刷新。")
          (queue-or-error state :browser/reload (tab-payload state {})))

      :browser/observe
      (or (read-block state "当前标签页未授权模型读取，或接管状态禁止观察。")
          (queue-or-error state :browser/observe (tab-payload state {})))

      :browser/click
      (or (action-block state "当前接管或权限状态禁止代理点击。")
          (observation-block state)
          (let [node (selected-node state)]
            (if-not node
              {:state (assoc state :last-error {:message "请选择一个页面元素。"}) :ok? false}
              (queue-or-error state :browser/act
                              (tab-payload state
                                           {:action :click
                                            :observation-id (observation-id state)
                                            :target node})))))

      :browser/type
      (or (action-block state "当前接管或权限状态禁止代理输入。")
          (observation-block state)
          (let [node (selected-node state)
                text (str (or (:text action) (:type-text state) ""))]
            (cond
              (nil? node)
              {:state (assoc state :last-error {:message "请选择一个输入字段。"}) :ok? false}

              (true? (:sensitive? node))
              {:state (assoc state :last-error
                             {:message "敏感字段必须由操作员在可见页面输入。"}) :ok? false}

              (str/blank? text)
              {:state (assoc state :last-error {:message "请输入普通文本。"}) :ok? false}

              :else
              (queue-or-error state :browser/act
                              (tab-payload state
                                           {:action :type
                                            :observation-id (observation-id state)
                                            :target node
                                            :text text})))))

      :browser/press
      (or (action-block state "当前接管或权限状态禁止代理按键。")
          (observation-block state)
          (let [key-name (str (or (:key action) "Enter"))]
            (queue-or-error state :browser/act
                            (tab-payload state
                                         {:action :press
                                          :observation-id (observation-id state)
                                          :target (selected-node state)
                                          :key key-name}))))

      :browser/submit
      (or (action-block state "当前接管或权限状态禁止代理提交。")
          (observation-block state)
          (let [node (selected-node state)]
            (if-not node
              {:state (assoc state :last-error {:message "请选择一个提交目标。"}) :ok? false}
              (queue-or-error state :browser/act
                              (tab-payload state
                                           {:action :submit
                                            :observation-id (observation-id state)
                                            :target node})))))

      :browser/scroll
      (or (action-block state "当前接管或权限状态禁止代理滚动。")
          (observation-block state)
          (queue-or-error state :browser/act
                          (tab-payload state
                                       {:action :scroll
                                        :observation-id (observation-id state)
                                        :delta-y 600})))

      :browser/takeover
      (queue-or-error state :browser/takeover (tab-payload state {}))

      :browser/operator-input
      (queue-or-error state :browser/operator-input
                      (tab-payload state {:kind :input
                                          :summary "操作员在页面中操作"}))

      :browser/resume
      (queue-or-error state :browser/resume
                      (tab-payload state {:mode (:resume-mode state)}))

      :browser/close-tab
      (if-let [tab-id (or (:tab-id action) (:active-tab-id state))]
        (queue-or-error state :browser/close-tab {:tab-id tab-id})
        {:state (assoc state :last-error {:message "没有可关闭的页面。"}) :ok? false})

      :browser/stop
      (queue-or-error state :browser/stop {})

      :browser/permission-enable
      (let [form (:permission-form state)
            scope (keyword (or (:scope form) :site))
            sites (parse-sites (:site-text form))]
        (cond
          (and (= :site scope) (empty? sites))
          {:state (assoc state :last-error
                         {:message "站点范围需要至少一个域名。"}) :ok? false}

          :else
          (queue-or-error state :browser/permission
                          {:enabled? true
                           :scope scope
                           :sites sites})))

      :browser/permission-disable
      (queue-or-error state :browser/permission {:enabled? false})

      :browser/approve-private-read
      (queue-or-error state :browser/permission {:private-read-approved? true})

      {:state (assoc state :last-error {:message (str "未知浏览器操作：" type)})
       :ok? false})))

(defn apply-connection
  [state phase epoch session-id]
  (assoc state
         :session-id session-id
         :sync {:phase phase :command-epoch epoch}))
