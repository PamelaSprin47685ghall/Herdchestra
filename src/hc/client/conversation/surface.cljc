(ns hc.client.conversation.surface
  "Accessible, responsive view model for the conversation surface.

  The view model is pure data.  It decides labels, disabled reasons, repair
  paths, live-region text, and layout bands; the React layer only renders it.
  Workbench may consume this map directly or mount the provided component."
  (:require [clojure.string :as str]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.conversation.ask :as ask]
            [hc.client.conversation.composer :as composer]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.timeline :as timeline]
            [hc.client.copy.failures :as failures]
            [hc.client.search.model :as search-model]))

;; ---------------------------------------------------------------------------
;; Small formatting helpers

(defn format-bytes
  [bytes]
  (when (number? bytes)
    (let [units ["B" "KiB" "MiB" "GiB"]
          [value unit] (loop [value (double bytes) unit 0]
                         (if (or (< value 1024) (>= unit (dec (count units))))
                           [value (nth units unit)]
                           (recur (/ value 1024) (inc unit))))]
      (let [rounded #?(:clj (Math/round (* value 10.0))
                       :cljs (js/Math.round (* value 10.0)))]
        (str (if (zero? (mod rounded 10))
               (long (/ rounded 10))
               (/ rounded 10.0))
             " " unit)))))

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn- label-of
  [value]
  (cond
    (nil? value) nil
    (string? value) value
    (map? value) (or (:display-name value) (:name value) (:label value) (:id value))
    :else (str value)))

(defn connection-label
  [connection]
  (cond
    (nil? connection) nil
    (true? connection) nil
    (map? connection)
    (if (and (not (contains? connection :phase))
             (or (true? (:synced? connection))
                 (true? (:command-allowed? connection))))
      nil
      (case (keywordize (:phase connection))
        :disconnected "未连接"
        :connecting "正在连接"
        :connected-awaiting-sync "已连接，等待同步"
        :synced nil
        :interrupted "连接已中断"
        (or (:label connection) "连接状态未知")))
    :else nil))

(defn presence-view
  "Project the Owner's one-controller/many-observers presence into display
  state.

  `:controller-id` is the one client allowed to submit session work;
  `:observer-ids` names the other attached clients, and `:local-client-id`
  is this tab's stable handshake identity.  A client that cannot prove it is
  the controller is shown as an observer: the label names the other client
  instead of pretending the local draft will be delivered, and `:takeover?`
  tells the view to offer the explicit takeover control."
  [{:keys [controller-id local-client-id]}]
  (let [controller-id (when (and (string? controller-id)
                                 (not (str/blank? controller-id)))
                        controller-id)
        local-client-id (when (and (string? local-client-id)
                                   (not (str/blank? local-client-id)))
                          local-client-id)]
    (cond
      (and controller-id local-client-id (= controller-id local-client-id))
      {:role :controller
       :takeover? false
       :label "你正在控制本会话"}

      controller-id
      {:role :observer
       :takeover? true
       :label "另一客户端正在控制本会话"}

      :else
      {:role :free
       :takeover? false
       :label "本会话暂无控制客户端"})))

(defn- in-flight-command?
  [command]
  (contains? #{:pending :submitting} (keywordize (:status command))))

(declare command-failure)

(defn command-label
  [command]
  (when command
    (let [status (keywordize (:status command))
          status (if (= :refused status) :rejected status)]
    (case status
      :pending (case (keywordize (:kind command))
                 :stop "已请求停止"
                 :unpark "正在提交回答"
                 "正在提交")
      :submitting (case (keywordize (:kind command))
                    :stop "已请求停止"
                    :unpark "正在提交回答"
                    "正在提交")
      :accepted nil
      :unconfirmed (let [{:keys [copy]} (command-failure command)]
                     (when copy
                       (str (:title copy) "：" (:body copy))))
      :rejected (case (keywordize (:kind command))
                  :send (let [search-label (search-model/command-error-label command)
                              code (get-in command [:error :code])
                              {:keys [copy]}
                              (command-failure command)]
                          (or search-label
                              (when (and copy
                                         (not= "无法确定具体原因" (:title copy)))
                                (str (:title copy) "：" (:body copy)))
                              (let [message (or (:message command)
                                                (get-in command [:error :message]))]
                                (when (and (string? message)
                                           (not (str/blank? message)))
                                  message))
                              "发送未送达，草稿已恢复"))
                  :stop "停止请求未送达"
                  :unpark "回答未送达，可再次提交"
                  "命令未送达")
      nil))))

(defn- command-failure
  "Return `{:copy :reason}` for the failure copy a command status maps to.

  `:unconfirmed` always reports the lost receipt; a rejected `:send` uses
  the server error code when one exists and the shared copy module knows
  it.  Other statuses return nils."
  [command]
  (let [status (keywordize (:status command))
        status (if (= :refused status) :rejected status)]
    (case status
      :unconfirmed {:copy (failures/failure-copy
                           {:code :receipt-lost :context {:saved? false}})
                    :reason :receipt-lost}
      :rejected (when (= :send (keywordize (:kind command)))
                  (let [code (get-in command [:error :code])
                        copy (when code
                               (failures/failure-copy
                                {:code code :context {:saved? false}}))]
                    {:copy (when (and copy
                                      (not= "无法确定具体原因" (:title copy)))
                             copy)
                     :reason code}))
      {:copy nil :reason nil})))

(defn command-reason
  "The ten-category reason id behind a command's failure copy, or nil when
  the command has no contract-kind failure.  Views render this category
  before the concrete kind title."
  [command]
  (let [{:keys [copy reason]} (command-failure command)]
    (when copy
      (failures/failure-category reason))))

(defn- save-view
  [draft-state]
  {:state (keywordize (:save-state draft-state))
   :label (draft/save-label draft-state)
   :actions (draft/save-actions draft-state)
   :saved? (draft/saved? draft-state)})

(defn- session-field
  [session k]
  (when (map? session)
    (let [n (name k)]
      (or (get session k)
          (get session (keyword "session" n))
          (get session (keyword n))))))

;; ---------------------------------------------------------------------------
;; Composer

(defn- attachment-view
  [attachment]
  (let [status (keywordize (:status attachment))
        ready? (draft/attachment-ready? attachment)]
    {:id (or (:id attachment) (:name attachment))
     :name (or (:name attachment) (:filename attachment) "附件")
     :size-label (format-bytes (:size attachment))
     :status status
     :status-label (cond
                     ready? "可发送"
                     (= :preparing status) "准备中"
                     (= :needs-choice status) "需要选择处理方式"
                     (contains? #{:unsupported :failed} status)
                     (let [copy (failures/failure-copy
                                 {:code :attachment-unsupported
                                  :context {:saved? false}})]
                       (str (or (failures/reason-label
                                 {:code :attachment-unsupported
                                  :context {:saved? false}})
                                "")
                            (:title copy) "：" (:body copy)))
                     :else "准备中")
     :ready? ready?
     :excluded? (boolean (:excluded? attachment))
     :removable? true
     :remove-label (str "移除附件 " (or (:name attachment) "附件"))}))

(defn- quote-view
  [quote]
  {:id (or (:id quote) (:message-id quote))
   :message-id (:message-id quote)
   :text (:text quote)
   :version (:version quote)
   :remove-label "移除引用"})

(defn- send-view
  [state session-id layout callbacks]
  (let [model (if (contains? state :model)
                (:model state)
                (composer/session-model-gate (:session state)))
        context {:draft (draft/draft (:drafts state) session-id)
                 :stream (or (get (:streams state) session-id) (stream/initial))
                 :command-pending? (boolean
                                    (some (fn [candidate]
                                            (and (= session-id (:session-id candidate))
                                                 (= :send (:kind candidate))
                                                 (in-flight-command? candidate)))
                                          (vals (:commands state))))
                 :model model
                 :model-availability (composer/model-availability
                                      model (:session state))
                 :connection (:connection state)
                 :run-active? (boolean (:run-active? state))}
        blocker (composer/send-blocker context)
        intent (composer/send-intent blocker)
        decision (composer/send-decision context)
        has-send? (fn? (:send! callbacks))
        disabled? (or (contains? #{:disabled :duplicate :unattended
                                  :select-model}
                                intent)
                      (and (not has-send?) (not (contains? #{:disabled :duplicate} intent))))
        reason (cond
                 (and (not has-send?) (not (contains? #{:disabled :duplicate} intent)))
                 "发送通道未就绪"
                 (= :empty blocker) "输入为空"
                 (= :command-pending blocker) "正在提交，请稍候"
                 (= :stopping blocker) "正在停止当前轮"
                 (= :generating blocker) "当前轮仍在生成"
                 (= :unattended blocker) "无人值守运行中，可停止当前轮后再发送"
                 (= :attachment-blocking blocker) "仍有附件未准备好"
                 (= :no-model blocker) "先选择一个可用模型，草稿已保留"
                 (= :model-unavailable blocker)
                 (composer/model-unavailable-message
                  (:model-availability context))
                 (= :connection-not-ready blocker) "网络尚未连接，草稿已保留"
                 :else nil)]
    {:label (composer/send-label {:stream (:stream context)})
     :intent intent
     :disabled? disabled?
     :reason reason
     :options (:options decision)
     :fix-intent (when (= :fix (:action decision)) (:intent decision))
     ;; R-05/B-03: an unbound model is a hard send gate, but the operator
     ;; still needs a visible way forward.  "连接模型" uses the same repair
     ;; path as the banner (`:fix!` with `:select-model`, falling back to
     ;; `:settings!`); "稍后设置" only opens settings.  With no callback at
     ;; all the entry stays visible but disabled, per the surface contract.
     :model-entry (when (= :no-model blocker)
                    (let [has-fix? (fn? (:fix! callbacks))
                          has-settings? (fn? (:settings! callbacks))]
                      {:visible? true
                       :primary-label "连接模型"
                       :disabled? (not (or has-fix? has-settings?))
                       :reason (when-not (or has-fix? has-settings?)
                                 "模型设置通道未就绪")
                       :defer-label "稍后设置"
                       :defer? has-settings?}))
     :target-size (a11y/target-size layout :primary)
     :aria-label "发送消息"}))

(defn- stop-view
  [stream-state callbacks layout session-id run-active?]
  (let [visible? (composer/stop-reachable? stream-state session-id)
        active? (composer/stop-visible? stream-state)
        stopping? (stream/stopping? stream-state)
        has-stop? (ifn? (:stop! callbacks))]
    {:visible? visible?
     ;; A selected session keeps this control mounted even while the visible
     ;; stream is idle.  The server may still be running unattended Nudge
     ;; turns, and only the Owner can decide whether a stop has work to do.
     :label (cond
              (and run-active? (not (stream/streaming? stream-state)))
              "停止无人值守运行"
              active? (composer/stop-label stream-state)
              :else "停止")
     :disabled? (or stopping? (not has-stop?))
     :reason (cond
               stopping? "正在停止当前轮"
               (not has-stop?) "停止通道未就绪"
               :else nil)
     :target-size (a11y/target-size layout :primary)
     :aria-label (if run-active? "停止无人值守运行" "停止当前生成")}))

(defn composer-view
  [state session-id layout callbacks]
  (let [draft-state (draft/draft (:drafts state) session-id)
        stream-state (or (get (:streams state) session-id) (stream/initial))
        pending-send (:pending-send stream-state)
        decision (:decision stream-state)
        command (some (fn [candidate]
                        (when (and (= session-id (:session-id candidate))
                                   (in-flight-command? candidate))
                          candidate))
                      (vals (:commands state)))
        send (send-view state session-id layout callbacks)
        run-active? (boolean (:run-active? state))
        stop (stop-view stream-state callbacks layout session-id run-active?)]
    {:value (:text draft-state)
     :default-value (:text draft-state)
     :cursor (:cursor draft-state)
     :selection (:selection draft-state)
     :draft-version (:version draft-state)
     :composing? (:composing? draft-state)
     :attachments (mapv attachment-view (:attachments draft-state))
     :quotes (mapv quote-view (:quotes draft-state))
     :send send
     :stop stop
     :hint (composer/hint {:platform (:platform state)
                           :send-key (:send-key state)})
     :save (save-view draft-state)
     :pending-send (when pending-send
                     {:visible? true
                      :label "停止后将发送本条"
                      :cancel-label "取消待发送内容"})
     :decision (when decision
                 {:visible? true
                  :reason "当前轮仍在生成"
                  :options (:options decision)})
     :command (when command
                {:command-id (:command-id command)
                 :kind (:kind command)
                 :status (:status command)
                 :label (command-label command)
                 :reason (command-reason command)})
     :run-active? run-active?
     :target-size (a11y/target-size layout :primary)
     :aria {:label "消息输入"
            :describedby "hc-composer-hint"
            :keyshortcuts (if (= :mobile (:platform state))
                            "Enter"
                            (case (or (:send-key state) :enter)
                              :mod-enter "Control+Enter Meta+Enter"
                              "Enter Shift+Enter"))}}))

(defn translation-context
  "Snapshot the composer selection for a message-derived translation panel.

  The selection is the request-time range the operator may replace.  A
  collapsed selection uses the cursor as a zero-width range.  `:draft-version`
  lets a consumer distinguish a later draft from the captured one."
  [{:keys [composer draft]}]
  (let [value (or (:value composer) "")
        selection (:selection composer)
        start (or (:start selection) (:cursor composer) 0)
        start (max 0 (min (count value) (long start)))
        end (or (:end selection) start)
        end (max start (min (count value) (long end)))
        version (or (:draft-version composer) (:version draft))]
    {:draft-selection {:start start
                       :end end
                       :text (subs value start end)
                       :draft-version version}
     :draft-text value}))

(defn translation-replace-action
  "Decide how a translated fragment reaches the composer draft.

  `request` is the panel's `{:start :end :expected-text :text}` payload;
  `current` is the latest `translation-context` selection.  A matching range
  and source text may replace; anything else falls back to an insert at the
  current caret, or to a copy when no insert path exists.  A stale range is
  never overwritten."
  [{:keys [request current replace? insert?]}]
  (let [request-text (or (:expected-text request) (:text request))
        same? (and (= (:start request) (:start current))
                   (= (:end request) (:end current))
                   (= request-text (:text current)))]
    (cond
      (and same? replace?)
      {:action :replace
       :payload {:start (:start current)
                 :end (:end current)
                 :expected-text request-text
                 :text (:text request)}}

      insert?
      {:action :insert
       :payload {:insert-at (:start current)
                 :text (:text request)}}

      :else
      {:action :copy :text (:text request)})))

;; ---------------------------------------------------------------------------
;; Ask cards

(defn- answer-record-view
  [record]
  {:record-id (:record-id record)
   :value (:value record)
   ;; Older records may predate the label field; never hide the record just
   ;; because it has no display copy.
   :label (or (:label record) (pr-str (:value record)))
   :skipped? (boolean (:skipped? record))
   :status (keywordize (:status record))
   :status-label (case (keywordize (:status record))
                   :submitting "正在提交"
                   :confirmed "已提交"
                   :rejected "未送达"
                   "已记录")})

(defn- ask-status-label
  [card]
  (case (keywordize (:status card))
    :pending "等待你回答"
    :submitting "正在提交回答"
    :answered "已回答"
    :skipped "已跳过"
    :rejected "回答未送达，请重试"
    "等待你回答"))

(defn- ask-view
  [card callbacks layout]
  (let [status (keywordize (:status card))
        selected (get-in card [:draft :selected])
        answers (mapv answer-record-view (ask/answer-records card))
        can-submit? (ask/can-submit? card)
        has-submit? (fn? (:submit-ask! callbacks))
        submit-label (case status
                       :submitting "正在提交回答"
                       :answered "提交新回答并恢复"
                       :skipped "提交新回答并恢复"
                       "提交回答并恢复")]
    {:ask-id (:ask-id card)
     :name (:name card)
     :question (:question card)
     :context (:context card)
     :status status
     :status-label (ask-status-label card)
     :input? (boolean (:input? card))
     :text (get-in card [:draft :text])
     :selected (vec (or selected []))
     :draft-version (get-in card [:draft :version])
     :multiple? (boolean (:multiple? card))
     :options (mapv (fn [option]
                      {:value (:value option)
                       :label (or (:label option) (str (:value option)))
                       :selected? (contains? (set selected) (:value option))})
                    (:options card))
     :answers answers
     :submit {:label submit-label
              :disabled? (or (not can-submit?)
                             (= :submitting status)
                             (not has-submit?))
              :reason (cond
                        (= :submitting status) "正在提交回答"
                        (not can-submit?) "请先输入或选择回答"
                        (not has-submit?) "回答通道未就绪"
                        :else nil)
              :target-size (a11y/target-size layout :primary)
              :aria-label "提交回答并恢复挂起的程序"}
     :skip (let [has-skip? (fn? (:skip-ask! callbacks))
                 skippable? (ask/skippable? card)]
             {:label (if (ask/skipped? card) "已跳过" "跳过")
              :disabled? (or (not skippable?) (not has-skip?))
              :reason (cond
                        (ask/skipped? card) "已跳过"
                        (ask/answered? card) "已回答"
                        (ask/submitting? card) "正在提交"
                        (not has-skip?) "跳过通道未就绪"
                        :else nil)
              :target-size (a11y/target-size layout :secondary)
              :aria-label "跳过这个问题"})
     :aria {:label (str "提问：" (:question card))
            :describedby (str "hc-ask-" (:ask-id card) "-status")}}))

(defn ask-views
  [state session-id layout callbacks]
  (->> (get-in state [:asks session-id] {})
       vals
       (sort-by (juxt #(or (:created-at %) "") :ask-id))
       (mapv #(ask-view % callbacks layout))))

;; ---------------------------------------------------------------------------
;; Header, banners, live region

(defn- session-run-active?
  "Server-side run activity, including unattended Nudge turns that have no
  visible stream.  The local stream is not evidence either way."
  [session]
  (let [run-id (or (session-field session :current-run-id)
                   (session-field session :run-id))
        status (keywordize (or (session-field session :run-status)
                               (session-field session :runStatus)))]
    (boolean
     (cond
       ;; The server explicitly publishes :finished when no live call or Nudge
       ;; remains; a retained run id must not override that.
       ;; A stopped/cancelled run is not active; keeping it in the fallback
       ;; run-id branch would freeze the header and block the next send.
       (contains? #{:finished :idle :completed :cancelled :canceled
                    :stopped}
                  status) false
       (contains? #{:running :streaming :starting :dispatching
                    :applying :stopping}
                  status) true
       :else (or (session-field session :unattended?)
                 (session-field session :nudge-active?)
                 (session-field session :nudge-pending?)
                 (and (string? run-id) (not (str/blank? run-id))))))))

(defn- header-view
  [session connection run-active? model model-supplied?]
  (let [model (if model-supplied?
                model
                (composer/session-model session))
        availability (composer/model-availability model session)
        unavailable? (and (not (composer/model-missing? model))
                          (composer/model-unavailable? availability))
        unknown? (and (not (composer/model-missing? model))
                      (= :unknown availability))
        title (or (session-field session :title)
                  (session-field session :name)
                  "未命名会话")
        assistant (or (session-field session :assistant)
                      (session-field session :assistant-name))
        workspace (or (session-field session :workspace)
                      (session-field session :workspace-name))
        temporary? (boolean (session-field session :temporary?))
        connection-label (connection-label connection)]
    {:title title
     :temporary? temporary?
     :temporary-label (when temporary? "临时")
     :model-label (str (or (label-of model) "尚未选择模型")
                       (when unavailable?
                         (str "（"
                              (composer/model-availability-label availability)
                              "）"))
                       (when unknown? "（尚未确认）"))
     :model-availability availability
     :model-unavailable? unavailable?
     :assistant-label (label-of assistant)
     :workspace-label (label-of workspace)
     :connection-label connection-label
     :status (session-field session :status)
     :run-active? run-active?
     :run-label (when run-active? "无人值守 · 自动继续")
     :actions [{:id :open-settings
                :label "设置"
                :aria-label "打开会话设置"}
               {:id :open-aux
                :label "辅助面板"
                :aria-label "打开文件、来源或话题面板"}]}))

(defn- latest-rejected-command
  [state session-id]
  (->> (vals (:commands state))
       (filter #(and (= session-id (:session-id %))
                     (= :rejected (:status %))))
       (sort-by #(or (:acked-at %) ""))
       last))

(defn- latest-command-message
  [state session-id]
  (when-let [command (latest-rejected-command state session-id)]
    (if (and (= :send (:kind command))
             (:rejected-submission (draft/draft (:drafts state) session-id)))
      "发送未送达，较新草稿已保留，可恢复上一条"
      (command-label command))))

(defn- banners
  [state session-id callbacks]
  (let [draft-state (draft/draft (:drafts state) session-id)
        stream-state (or (get (:streams state) session-id) (stream/initial))
        run-active? (session-run-active? (:session state))
        save (save-view draft-state)
        rejected (latest-rejected-command state session-id)
        command-message (latest-command-message state session-id)
        pending-send (:pending-send stream-state)
        model (if (contains? state :model)
                (:model state)
                (composer/session-model-gate (:session state)))
        availability (composer/model-availability model (:session state))]
    (cond-> []
      (and run-active? (not (stream/streaming? stream-state)))
      (conj {:kind :info
             :text "可见输出已暂停，服务器仍在无人值守自动继续；可停止当前轮"
             :actions []})

      (contains? #{:failed :storage-unavailable} (:state save))
      (conj {:kind :warning
             :text "草稿暂未保存"
             :actions [{:id :copy-draft :label "复制全部文字"}
                       {:id :export-draft :label "另存草稿"}]})

      (not (composer/connection-ready? (:connection state)))
      (conj {:kind :warning
             :text "网络尚未连接，草稿已保留"
             :actions [{:id :check-connection :label "检查连接"}]})

      (and (not (composer/model-missing? model))
           (composer/model-unavailable? availability))
      (conj (cond-> {:kind :warning
                     :text (composer/model-unavailable-message availability)
                     :actions []}
              (or (fn? (:fix! callbacks))
                  (fn? (:settings! callbacks)))
              (assoc :actions [{:id :select-model :label "选择其他模型"}])))

      ;; R-12: an unbound model needs a primary repair entry in the message
      ;; area, not only a hint under the composer.  `:no-model` is checked
      ;; last so a bound-but-unavailable model keeps its more specific
      ;; explanation above.
      (composer/model-missing? model)
      (conj {:kind :info
             :text "还没有可用模型；草稿与历史仍保留。连接模型后即可发送。"
             :actions [{:id :select-model :label "连接模型"}]})

      command-message
      (conj (cond-> {:kind :error
                     :text command-message
                     :reason (command-reason rejected)
                     :actions []}
              ;; Only a rejected send owns a draft snapshot.  A rejected stop
              ;; or answer has nothing to restore in the composer.
              (= :send (:kind rejected))
              (assoc :actions [{:id :restore-rejected :label "恢复草稿"}])))

      pending-send
      (conj {:kind :info
             :text "停止后将发送本条"
             :actions [{:id :cancel-pending-send :label "取消待发送内容"}]}))))

(defn- live-text
  [state session-id]
  (let [stream-state (or (get (:streams state) session-id) (stream/initial))
        draft-state (draft/draft (:drafts state) session-id)
        run-active? (session-run-active? (:session state))
        command-message (latest-command-message state session-id)]
    (cond
      command-message command-message
      (and run-active? (not (stream/streaming? stream-state)))
      "无人值守 · 自动继续"
      (stream/stopping? stream-state) "正在停止"
      (stream/streaming? stream-state) "正在生成"
      (contains? #{:failed :storage-unavailable} (:save-state draft-state))
      "草稿暂未保存"
      (some #(= :pending (:status %)) (vals (get-in state [:asks session-id] {})))
      "等待你回答"
      :else nil)))

;; ---------------------------------------------------------------------------
;; Surface

(defn- state-from-input
  [{:keys [conversation session draft stream connection layout platform
           send-key ask command run-active? blocked] :as _input}]
  (let [session-id (or (:session-id conversation)
                       (:session-id _input)
                       (:id session)
                       (:session/id session))
        draft (or draft
                  (get-in conversation [:drafts session-id])
                  draft/empty-draft)
        stream (or stream
                   (get-in conversation [:streams session-id])
                   (stream/initial))
        ask (cond
              (and (map? ask) (:ask-id ask)) {(:ask-id ask) ask}
              (map? ask) ask
              :else (get-in conversation [:asks session-id] {}))
        commands (cond
                   (and (map? command) (:command-id command))
                   {(:command-id command) command}
                   (map? command) command
                   :else (:commands conversation {}))
        run-active? (boolean (or run-active?
                                 (session-run-active? session)))]
    (merge conversation
           {:session-id session-id
            :session session
            :draft draft
            :stream stream
            :connection connection
            :layout (keyword (or layout (:layout conversation) :wide))
            :platform (keyword (or platform (:platform conversation) :desktop))
            :send-key (keyword (or send-key (:send-key conversation) :enter))
            :run-active? run-active?
            :blocked blocked
            :drafts {session-id draft}
            :streams {session-id stream}
            :asks {session-id ask}
            :commands commands})))

(defn surface
  "Build the complete accessible view model.

  Input accepts either a full `:conversation` state or the workbench's split
  props (`:draft`, `:stream`, `:ask`, `:command`, `:connection`, `:layout`).
  `:callbacks` is optional; absent callbacks become visibly disabled controls,
  never silent dead buttons."
  [{:keys [messages session connection layout callbacks] :as input}]
  (let [state (state-from-input input)
        session (or session (:session state) {})
        session-id (:session-id state)
        layout (keyword (or layout (:layout state) :wide))
        layout-map (a11y/layout layout)
        messages (timeline/ordered-messages (or messages []))
        stream-state (or (get (:streams state) session-id) (stream/initial))
        asks (ask-views state session-id layout callbacks)
        live (live-text state session-id)
        error-text (latest-command-message state session-id)
        run-active? (boolean (:run-active? state))
        model-supplied? (contains? state :model)
        model (when model-supplied? (:model state))]
    {:session-id session-id
     :layout layout
     :layout-map layout-map
     :class (str "hc-conversation hc-layout-" (name layout))
     :header (header-view session connection run-active? model model-supplied?)
     :run {:active? run-active?
           :label (when run-active? "无人值守 · 自动继续")
           :stop-visible? (boolean (composer/stop-reachable?
                                    stream-state session-id))}
     :messages (mapv (fn [message]
                       (timeline/message-view
                        message
                        {:streaming? (and (= (:id message) (:active-message-id stream-state))
                                          (stream/streaming? stream-state))
                         :late-tool-results
                         (filterv (fn [late]
                                    (let [message-id (or (:message-id late)
                                                         (:active-message-id stream-state))]
                                      (or (nil? message-id)
                                          (= message-id (:id message)))))
                                  (:late-tool-results stream-state))}))
                     messages)
     :composer (composer-view state session-id layout callbacks)
     :asks asks
     :banners (banners state session-id callbacks)
     :blocked (when (map? (:blocked state))
                (assoc (:blocked state)
                       :reason (let [id (failures/failure-category
                                         (or (:last-code (:blocked state))
                                             (:code (:blocked state))))]
                                (when (not= :indeterminate id) id))))
     :jump {:visible? (boolean (:show-jump? stream-state))
            :label "返回最新内容"
            :target-size (a11y/target-size layout :primary)}
     :stream {:status (keywordize (:stop-state stream-state))
              :status-label (stream/status-label stream-state)
              :streaming? (stream/streaming? stream-state)
              :stopping? (stream/stopping? stream-state)
              :stopped? (stream/stopped? stream-state)
              :follow? (not (false? (:follow? stream-state)))
              :at-bottom? (not (false? (:at-bottom? stream-state)))
              :show-jump? (boolean (:show-jump? stream-state))
              :selection (:selection stream-state)
              :selection-locked? (:selection-locked? stream-state)
              :scroll-request (:scroll-request stream-state)
              :scroll-top (:scroll-top stream-state)
              :content-version (:content-version stream-state)
              :late-tool-results (:late-tool-results stream-state)}
     :live-region (a11y/live-region live)
     :alert (when error-text (a11y/alert-region error-text))
     :focus (:focus state)
     :a11y {:target-size (a11y/target-size layout :primary)
            :secondary-target (a11y/target-size layout :secondary)
            :sidebar (:sidebar layout-map)
            :aux-panel (:aux-panel layout-map)
            :show-labels? (:show-labels? layout-map)}
     :callbacks callbacks}))
