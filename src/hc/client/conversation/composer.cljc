(ns hc.client.conversation.composer
  "Composer policy: IME-safe Enter, platform defaults, send gating, and the
  stop-and-send decision.

  This namespace decides what an input event means.  It never mutates a
  draft, creates a command, or touches the DOM.  The reducer in
  `hc.client.conversation` applies the decision.

  The IME rule is absolute: while a composition or candidate confirmation is
  active, Enter belongs to the input method.  The decision is `:ime-candidate`
  with `:prevent-default? false`, so the browser can confirm the candidate and
  the draft is never truncated or sent."
  (:require [clojure.string :as str]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]))

;; ---------------------------------------------------------------------------
;; Event interpretation

(defn ime-event?
  "True when the event belongs to an active input-method composition.

  `isComposing` is authoritative where the browser provides it.  Key code 229
  and the `Process`/`Unidentified` keys are fallbacks used by browsers and
  embedded webviews that do not expose `isComposing` reliably."
  [event]
  (boolean
   (or (:is-composing? event)
       (:composing? event)
       (= 229 (:key-code event))
       (= 229 (:which event))
       (contains? #{"Process" "Unidentified"} (:key event)))))

(defn enter-event?
  [event]
  (or (contains? #{"Enter" "Return"} (:key event))
      (= 13 (:key-code event))
      (= 13 (:which event))))

(defn- modifier?
  [event]
  (boolean (or (:ctrl? event) (:meta? event))))

(defn keydown
  "Translate one keydown event into a composer decision.

  Returned `:action` values:

    :send             submit the draft
    :newline          let the platform insert a newline
    :ime-candidate    leave the key to the input method
    :ignore           no composer action

  `:prevent-default?` tells the view whether it must suppress the browser
  default.  Newline and IME decisions deliberately leave the default alone."
  [{:keys [platform send-key] :as _context} event]
  (let [platform (keyword platform)
        send-key (keyword send-key)]
  (cond
    (ime-event? event)
    {:action :ime-candidate :prevent-default? false}

    (not (enter-event? event))
    {:action :ignore}

    (or (:repeat? event) (:repeat event))
    {:action :ignore :reason :key-repeat}

    (= :mobile platform)
    {:action :newline :prevent-default? false}

    (= :mod-enter (or send-key :enter))
    (if (modifier? event)
      {:action :send :prevent-default? true}
      {:action :newline :prevent-default? false})

    (:shift? event)
    {:action :newline :prevent-default? false}

    :else
    {:action :send :prevent-default? true})))

;; ---------------------------------------------------------------------------
;; Send gating

(defn connection-ready?
  "A missing connection map means the caller did not provide transport state;
  do not invent a block.  A present map must be in the synchronized phase."
  [connection]
  (or (nil? connection)
      (true? connection)
      (and (map? connection)
           (if (contains? connection :phase)
             (= :synced (:phase connection))
             (or (true? (:synced? connection))
                 (true? (:command-allowed? connection)))))))

(def ^:private model-identity-keys
  "Fields that identify a bound model.  A map that has none of them is a
  projection of an unbound binding, not a selected model."
  [:id :model-id :model-entry-id :model-name :display-name :label :name])

(defn- meaningful-value?
  [value]
  (cond
    (nil? value) false
    (string? value) (not (str/blank? value))
    :else true))

(defn model-missing?
  "True when no usable model is bound.

  A nil model is missing.  So is an empty map, and so is a map whose identity
  fields are all nil or blank.  Session projections commonly publish `:model
  {}` when the binding is unbound; treating that truthy map as a model would
  enable send while the header still says no model is selected.  Callers that
  truly do not know model state must supply a non-nil placeholder."
  [model]
  (cond
    (nil? model) true
    (string? model) (str/blank? model)
    (map? model) (or (true? (:missing? model))
                     (not-any? (fn [k] (meaningful-value? (get model k)))
                               model-identity-keys))
    :else false))

(defn usable-model
  "Return the first candidate that identifies a bound model, or nil.

  Callers pass the candidates in preference order (`:model`, `:model-id`,
  `:model-label`).  An unusable candidate, such as the empty map that an
  unbound binding projects, must not mask a later usable one."
  [& candidates]
  (some (fn [candidate] (when-not (model-missing? candidate) candidate))
        candidates))

(defn session-model
  "Resolve a session's model for display from `:model`, `:model-id`, or
  `:model-label`.

  A projection may carry only a display label.  That label is useful for the
  header but is not a binding identity; send gating must use
  `session-model-identity` instead.  Both plain and `:session/*` namespaced
  keys are accepted."
  [session]
  (when (map? session)
    (apply usable-model
           (mapcat (fn [k]
                     [(get session k)
                      (get session (keyword "session" (name k)))])
                   [:model :model-id :model-label]))))

(defn- model-identity-value
  [model]
  (cond
    (string? model) (when-not (str/blank? model) model)
    (map? model) (some (fn [k]
                         (let [value (get model k)]
                           (when (meaningful-value? value) value)))
                       [:id :model-id :model-entry-id])
    :else nil))

(defn model-identity?
  "True only for a real binding identity, not a display-only projection."
  [model]
  (boolean (model-identity-value model)))

(defn session-model-identity
  "Resolve a session's bound model identity for command gating.

  A map containing only display fields, or a bare `:model-label`, is unbound
  for send purposes even though `session-model` can render its label."
  [session]
  (when (map? session)
    (some model-identity-value
          (mapcat (fn [k]
                    [(get session k)
                     (get session (keyword "session" (name k)))])
                  [:model :model-id]))))

(defn session-model-gate
  "Resolve a session's bound model for send gating.

  Unlike `session-model`, a display-only label is rejected.  The returned
  value keeps the model map (including `:availability`) when the server
  published one, so a stored binding can be distinguished from a usable one."
  [session]
  (when (map? session)
    (some (fn [candidate]
            (when (model-identity? candidate)
              candidate))
          (mapcat (fn [k]
                    [(get session k)
                     (get session (keyword "session" (name k)))])
                  [:model :model-id]))))

(defn model-availability
  "Return the live availability of a bound model, or nil when unknown.

  The server publishes the current fact for a stored binding; callers that
  only have a bare identity must not invent an unavailable state."
  [model session]
  (let [availability
        (or (when (map? model) (:availability model))
            (when (map? session)
              (or (:model-availability session)
                  (:session/model-availability session)
                  (get-in session [:model :availability])
                  (get-in session [:session/model :availability]))))]
    (keyword availability)))

(defn model-unavailable?
  "True when the model is bound but the live configuration cannot use it.

  `:not-returned`, `:unconfirmed` and `:unknown` remain usable: a manual
  model id cannot be listed, and an unconfirmed capability is not a disabled
  binding.  The header shows `尚未确认` instead of disabling the composer."
  [availability]
  (and (some? availability)
       (not (contains? #{:available :not-returned :unconfirmed :unknown}
                       availability))))

(defn model-availability-label
  "Short header label for an unusable bound model."
  [availability]
  (case (keyword availability)
    :connection-removed "连接已移除"
    :connection-disabled "连接已停用"
    :auth-expired "连接认证失效"
    :disabled "模型已停用"
    :model-removed "模型已移除"
    :unknown "尚未确认"
    "不可用"))

(defn model-unavailable-message
  "Explain why a bound model cannot send and name the repair path."
  [availability]
  (case (keyword availability)
    :connection-removed
    "所选连接的配置已被移除；请选择其他模型，草稿已保留"
    :connection-disabled
    "所选连接已停用；请启用连接或选择其他模型，草稿已保留"
    :auth-expired
    "所选连接的认证已失效；请修复连接或选择其他模型，草稿已保留"
    :disabled
    "所选模型已停用；请启用模型或选择其他模型，草稿已保留"
    :model-removed
    "所选模型已被移除；请选择其他模型，草稿已保留"
    :unknown
    "尚未确认该模型是否可用；可直接发送，服务会返回实际结果"
    "所选连接或模型当前不可用；请选择其他模型，草稿已保留"))

(defn- effective-availability
  [model model-availability]
  (or model-availability
      (when (map? model) (:availability model))))

(defn send-blocker
  "Return the first reason the draft cannot be sent now, or nil.

  `:generating` is not a dead end: it asks the operator whether to stop the
  current round and send, or continue editing."
  [{:keys [draft stream command-pending? model model-availability
           connection run-active?]}]
  (let [model-availability (effective-availability model model-availability)]
    (cond
      (not (connection-ready? connection)) :connection-not-ready
      (model-missing? model) :no-model
      (model-unavailable? model-availability) :model-unavailable
      (and (not (draft/meaningful-text? (:text draft)))
           (empty? (:attachments draft))
           (empty? (:quotes draft))) :empty
      (seq (draft/blocking-attachments draft)) :attachment-blocking
      (stream/stopping? stream) :stopping
      (stream/streaming? stream) :generating
      (and run-active? (not (stream/stopped? stream))) :unattended
      ;; `command-pending?` is already scoped to send commands; a pending
      ;; stop must not be read as a duplicate send.
      command-pending? :command-pending
      :else nil)))

(defn send-intent
  "Map a blocker to the operator-facing intent.

  `:disabled`, `:duplicate`, `:unattended`, and `:select-model` disable the
  send button.  No model is a hard precondition: the header's model control is
  the repair path, so an enabled send button would promise an action the gate
  cannot perform.  The remaining intents open a concrete repair or decision
  path instead of doing nothing."
  [blocker]
  (case blocker
    :empty :disabled
    :command-pending :duplicate
    :stopping :wait
    :generating :needs-decision
    :unattended :unattended
    :attachment-blocking :fix-attachments
    :no-model :select-model
    :model-unavailable :select-model
    :connection-not-ready :check-connection
    :send))

(defn send-decision
  "Return the action for a send request.  This is the single place that turns
  a blocker into either a command, a repair path, or the stop/continue
  decision."
  [{:keys [draft stream command-pending? model model-availability
           connection run-active?] :as context}]
  (let [model-availability (effective-availability model model-availability)
        blocker (send-blocker context)
        intent (send-intent blocker)]
    (case intent
      :send {:action :send}
      :disabled {:action :blocked :intent :disabled :reason :empty
                 :message "输入为空，草稿已保留"}
      :duplicate {:action :blocked :intent :duplicate :reason :command-pending
                  :message "正在提交，请稍候"}
      :wait {:action :blocked :intent :wait :reason :stopping
             :message "正在停止当前轮"}
      :unattended {:action :blocked :intent :unattended :reason :unattended
                   :message "无人值守运行中，可停止当前轮后再发送"}
      :needs-decision {:action :needs-decision
                       :intent :needs-decision
                       :reason :generating
                       :options [{:id :stop-and-send
                                  :label "停止当前轮并发送"}
                                 {:id :continue-editing
                                  :label "继续编辑"}]}
      :fix-attachments {:action :fix :intent :fix-attachments
                        :reason :attachment-blocking
                        :message "仍有附件未准备好"}
      :select-model {:action :fix :intent :select-model
                     :reason (if (= blocker :model-unavailable)
                               :model-unavailable
                               :no-model)
                     :message (if (= blocker :model-unavailable)
                                (model-unavailable-message model-availability)
                                "先选择一个可用模型，草稿已保留")}
      :check-connection {:action :fix :intent :check-connection
                         :reason :connection-not-ready
                         :message "网络尚未连接，草稿已保留"})))

;; ---------------------------------------------------------------------------
;; Operator-facing labels

(defn hint
  "Describe the current Enter behaviour without hiding the IME exception."
  [{:keys [platform send-key]}]
  (case (keyword platform)
    :mobile "回车换行 · 点击发送"
    (case (keyword (or send-key :enter))
      :mod-enter "Ctrl/Cmd+Enter 发送 · Enter 换行"
      "Enter 发送 · Shift+Enter 换行")))

(defn send-label
  "The button keeps one short label at every width.  The disabled reason
  (for example \"当前轮仍在生成\") stays in the hint and the button title,
  so a long parenthetical cannot squeeze the 360px composer."
  [_]
  "发送")

(defn stop-label
  [stream]
  (stream/status-label stream))

(defn stop-visible?
  [stream]
  (or (stream/streaming? stream)
      (stream/stopping? stream)))

(defn stop-reachable?
  "True when an operator stop can be requested for SESSION-ID.

  Local stream state is not evidence that the server has no work.  Unattended
  Nudge turns can run while the visible answer is idle, so a selected session
  always keeps the stop control mounted.  The Owner decides whether the stop
  has anything to cancel."
  [stream session-id]
  (or (stop-visible? stream)
      (and (string? session-id)
           (not (str/blank? session-id)))))

(defn decision-visible?
  [stream]
  (contains? #{:send-while-streaming} (get-in stream [:decision :kind])))

(defn pending-send?
  [stream]
  (boolean (:pending-send stream)))
