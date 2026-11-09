(ns hc.client.workbench.sync-bridge
  "Bridge between the pure AG-UI sync adapter, the Sente transport, and Fulcro.

  The adapter state stays outside the Fulcro database.  After every accepted
  boundary the bridge transacts the adapter's normalized view into Fulcro;
  local `:ui/*` state is never overwritten.  State events and run events are
  routed separately: a text chunk cannot be mistaken for a baseline, and a
  baseline cannot be appended to a stopped message."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.sync :as sync]
            [hc.client.sync.object :as sync-object]
            [hc.client.workbench.events :as events]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce adapter
  (atom (sync/initial-state)))

(defonce app-ref
  (atom nil))

(defonce subscribed?
  (atom false))

(defonce event-listeners
  (atom []))

;; The state-object token currently being fetched, or nil.
(defonce fetching-token
  (atom nil))

;; Consecutive failed large-object fetches.  Reset by any success, resync or
;; reconnect.  A small cap prevents a fetch/resync loop.
(defonce object-failures
  (atom 0))

;; Delayed baseline request after repeated object failures.  The bridge keeps
;; asking for a fresh boundary with exponential backoff instead of parking in
;; `:connected-awaiting-sync` forever.
(defonce resync-retry-timer
  (atom nil))

(defonce ^:private event-log (atom []))

(defn- record-event!
  [event]
  (when (exists? js/window)
    (let [value (or (:value event) (:data event) event)]
      (swap! event-log
             (fn [log]
               (-> log
                   (conj {:at (js/Date.now)
                          :type (or (:type event) (:event event)
                                    (:name event))
                          :session-id (or (:session-id event)
                                          (:session-id value))
                          :run-id (or (:run-id event) (:run-id value))
                          :status (or (:status event) (:status value))})
                   (subvec (max 0 (- (count log) 199)))))))))

(defn- install-event-log-probe!
  []
  (when (exists? js/window)
    (set! (.-__HC_EVENTS__ js/window)
          (fn [] (clj->js @event-log)))))

(defn- cancel-resync-retry!
  []
  (when-let [timer @resync-retry-timer]
    (js/clearTimeout timer))
  (reset! resync-retry-timer nil))

(declare resync!)

(defn add-event-listener!
  "Register a callback for non-state AG-UI/CUSTOM events.  Used by the
  settings bridge for receipts; the conversation surface consumes run events
  through the Fulcro transaction."
  [f]
  (swap! event-listeners conj f)
  nil)

(defn ensure-event-listener!
  "Register F once per namespace load.

  Bridges call this from `install!`, which runs again on hot reload and after
  a transport restart.  An unconditional registration would deliver every
  later event several times, and a duplicated materials or backup listener
  would reduce one receipt twice."
  [k f]
  (swap! event-listeners (fn [listeners]
                           (if (some #(and (map? %) (= k (:listen-key %)))
                                     listeners)
                             listeners
                             (conj listeners {:listen-key k :callback f}))))
  nil)

(defn set-app!
  [app]
  (reset! app-ref app))

(defn adapter-state
  []
  @adapter)

(defn connection
  []
  (model/connection-view @adapter))

(defn synced?
  []
  (sync/synced? @adapter))

(defn command-allowed?
  []
  (sync/command-allowed? @adapter))

(defn epoch
  []
  (:epoch @adapter))

(defn command-epoch
  "The Owner-issued command epoch from the confirmed server snapshot, not the
  AG-UI feed epoch."
  []
  (or (get-in @adapter [:db :sync :command-epoch])
      (get-in @adapter [:server :sync :command-epoch])))

(defn command-epoch-for-session
  "Per-session Owner epoch when published; the global epoch is only valid for
  the app scope.  Deleted sessions are absent from the adapter's confirmed
  server view; their rows live only in the Fulcro database's
  `:ui/session-list`.  Look up the exact session id there, then in the
  adapter view, and never substitute another session's or the app epoch."
  [session-id]
  (let [display-db (when-let [app @app-ref]
                     (application/current-state app))]
    (model/resolve-session-command-epoch display-db (:db @adapter)
                                         session-id)))

(defn seq-number
  []
  (:seq @adapter))

(defn subscription
  []
  (:subscription @adapter))

(defn install!
  "Push the adapter's current confirmed view into Fulcro."
  []
  (when-let [app @app-ref]
    (comp/transact! app [(mutations/install-sync {:adapter @adapter})])))

(defn- notify!
  [tone text]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/notify
                      {:notice {:id (str "wb-" (random-uuid))
                                :tone tone
                                :text text
                                :at (str (js/Date.))}})])))

(defn- resync-needed?
  [state]
  (= :resync-required (get-in state [:last-outcome :result])))

(defn- send-resync!
  [state reason]
  (transport/request-resync!
   {:subscription-id (:subscription state)
    :epoch (:epoch state)
    :seq (:seq state)
    :reason reason}))

(defn- subscribe!
  [state]
  (let [sent? (transport/subscribe!
               {:subscription-id (:subscription state)
                :epoch (:epoch state)
                :seq (:seq state)})]
    (reset! subscribed? (boolean sent?))
    sent?))

(defn recover-pending-commands!
  "After a baseline/reconnect, ask the gateway for receipts of commands that
  were already submitted.  This never re-sends the command itself.

  `:accepted` is intentionally included: a reconnect may have missed the
  receipt that carries a created session/branch id.  Receipt reducers must
  therefore be idempotent, and selection-changing receipts must respect the
  `:selection-seq` captured when the command was submitted."
  []
  (when-let [app @app-ref]
    (let [db (application/current-state app)
          pending #{:submitting :sent :accepted :unconfirmed}]
      (doseq [[_ command] (:ui/command db)
              :when (contains? pending (:status command))]
        (transport/receipt! {:command-id (:command-id command)
                             :session-id (or (:session-id command)
                                             (get-in command
                                                     [:payload :session-id]))
                             :subscription-id (:subscription @adapter)})))))

(defn- ack-applied!
  [state]
  (when (and (= :synced (:phase state))
             (integer? (:seq state)))
    (transport/ack! {:subscription-id (:subscription state)
                     :seq (:seq state)})))

(defn- hex-buffer
  [^js buffer]
  (let [view (js/Uint8Array. buffer)]
    (loop [i 0
           out ""]
      (if (< i (.-length view))
        (recur (inc i)
               (str out (.padStart (.toString (aget view i) 16) 2 "0")))
        out))))

(defn- state-object-digest
  "Return a Promise of the domain-separated digest for BUFFER."
  [buffer]
  (if (and (exists? js/crypto)
           (some? (aget js/crypto "subtle")))
    (let [tag (.encode (js/TextEncoder.) sync-object/object-tag)
          payload (js/Uint8Array. buffer)
          combined (js/Uint8Array.
                    (+ (.-length tag) (.-length payload)))]
      (.set combined tag 0)
      (.set combined payload (.-length tag))
      (.then (js/crypto.subtle.digest "SHA-256" combined)
             (fn [digest]
               (str "sha256-" (hex-buffer digest)))))
    (throw (ex-info "Web Crypto is unavailable; the state object cannot be verified"
                    {:code :STATE_OBJECT_CRYPTO_UNAVAILABLE}))))

(declare fetch-object!)

(defn- apply-object-result!
  [result]
  (let [before @adapter
        token (:token result)
        current-token (get-in before [:pending-object :ref :token])]
    (if-not (= token current-token)
      ;; The token is a capability; do not copy it into logs.
      (log/debug "Ignoring a superseded state object")
      (let [kind (get-in before [:pending-object :kind])
            after (sync/complete-object before result)]
        (reset! adapter after)
        (reset! object-failures 0)
        (cancel-resync-retry!)
        (install!)
        (ack-applied! after)
        (when (and (= :state-snapshot kind)
                   (= :applied (get-in after [:last-outcome :result])))
          (recover-pending-commands!))
        (cond
          (resync-needed? after)
          (send-resync! after (get-in after [:last-outcome :reason]))

          (:pending-object after)
          (fetch-object! after))
        after))))

(defn- object-failure!
  [token error]
  (let [current-token (get-in @adapter [:pending-object :ref :token])]
    (when (= token current-token)
      (let [data (ex-data error)
            reason (or (:code data) (:reason data) :state-object-failed)
            failures (swap! object-failures inc)
            after (sync/object-failed @adapter reason)]
        (reset! adapter after)
        (install!)
        ;; Always ask for a fresh baseline.  After repeated failures the
        ;; request repeats with exponential backoff, so a transient object
        ;; outage heals without leaving the client stuck in
        ;; `:connected-awaiting-sync`.
        (send-resync! after :state-object-failed)
        (when (>= failures 3)
          (notify! :danger
                   (str "无法获取大状态对象（"
                        (or (:message data) reason)
                        "）；界面保留最后确认副本，正在按退避重试基线。"))
          (cancel-resync-retry!)
          (let [delay (min 30000
                           (* 1000 (bit-shift-left
                                    1 (min 4 (- failures 3)))))]
            (reset! resync-retry-timer
                    (js/setTimeout
                     (fn []
                       (reset! resync-retry-timer nil)
                       (when (= :connected-awaiting-sync
                                (sync/phase @adapter))
                         (send-resync! @adapter :state-object-retry)))
                     delay))))
        after))))

(defn- fetch-object!
  [state]
  (let [pending (:pending-object state)
        ref (:ref pending)
        token (:token ref)
        subscription (:subscription state)]
    (when (and pending token (not= token @fetching-token))
      (reset! fetching-token token)
      (let [finish!
            (fn []
              (when (= token @fetching-token)
                (reset! fetching-token nil)))
            fail!
            (fn [error]
              (finish!)
              (when (= subscription (:subscription @adapter))
                (object-failure! token error)))
            deliver!
            (fn [buffer]
              (let [bytes (js/Uint8Array. buffer)]
                (if (not= (.-byteLength bytes) (:size ref))
                  (throw (ex-info "state object size mismatch"
                                  {:code :STATE_OBJECT_SIZE_MISMATCH
                                   :expected (:size ref)
                                   :actual (.-byteLength bytes)}))
                  (-> (state-object-digest buffer)
                      (.then
                       (fn [digest]
                         (if (not= digest (:digest ref))
                           (throw (ex-info "state object digest mismatch"
                                           {:code :STATE_OBJECT_DIGEST_MISMATCH
                                            :expected (:digest ref)
                                            :actual digest}))
                           (let [text (.decode (js/TextDecoder. "utf-8") bytes)
                                 decoded (sync/decode-object-text text)]
                             (if-not (:ok? decoded)
                               (throw (ex-info "state object decode failed"
                                               (:error decoded)))
                               {:token token
                                :digest digest
                                :size (.-byteLength bytes)
                                :kind (:kind pending)
                                :verified? true
                                :value (:value decoded)})))))))))]
        (-> (js/fetch (sync/object-url token)
                      #js {:method "GET"
                           :cache "no-store"
                           :credentials "same-origin"})
            (.then
             (fn [response]
               (if (.-ok response)
                 (.arrayBuffer response)
                 (throw (ex-info "state object request failed"
                                 {:code :STATE_OBJECT_HTTP_ERROR
                                  :status (.-status response)})))))
            (.then deliver!)
            (.then
             (fn [result]
               (finish!)
               (when (= subscription (:subscription @adapter))
                 (apply-object-result! result))))
            (.catch fail!))))))

(declare resync!)

(defn- event-name-key
  [event]
  (let [value (or (:name event) (:event event))]
    (cond
      (keyword? value) value
      (string? value) (-> value str/lower-case keyword)
      :else nil)))

(defn- resync-required-event?
  [event]
  (= :hc/resync-required (event-name-key event)))

(defn- sync-heartbeat-event?
  [event]
  (= :hc/sync-heartbeat (event-name-key event)))

(defn- normalize-event
  [event]
  (let [type (or (:type event) (:event event))]
    (if (and (nil? (:type event))
             (keyword? type)
             (= "hc" (namespace type)))
      (assoc event :type "CUSTOM" :name type)
      event)))

(defn- on-event!
  [event]
  (let [event (normalize-event event)]
    (cond
      (resync-required-event? event)
      (resync! (or (get-in event [:value :reason]) :server-resync-required))

      (sync-heartbeat-event? event)
      (recover-pending-commands!)

      (events/state-event? event)
      (let [before @adapter
            after (sync/receive before event)]
        (reset! adapter after)
        (install!)
        (when (:pending-object after)
          (fetch-object! after))
        ;; Any baseline that is not stale answers the retry request, even
        ;; when it carries a new large-object reference.
        (when (and (= :state-snapshot (events/event-type event))
                   (not= :dropped (get-in after [:last-outcome :result])))
          (cancel-resync-retry!))
        (when (and (= :state-snapshot (events/event-type event))
                   (= :applied (get-in after [:last-outcome :result])))
          (recover-pending-commands!)
          ;; RUN_ERROR may be missed across reconnect.  Recover the draft from
          ;; the snapshot once the install transaction has landed.
          (js/setTimeout
           (fn []
             (when-let [app @app-ref]
               (let [db (application/current-state app)
                     session-id (model/selected-session-id db)
                     active-session-id (model/field db :session/active-id)
                     ;; `:run-error` belongs to the server's active session.
                     ;; A local selection may differ, so only reuse it when
                     ;; the identities agree; otherwise the session's own
                     ;; `:last-error` is the only safe source.
                     error (if (= active-session-id session-id)
                             (or (model/field db :run-error)
                                 (get-in db [:session/by-id session-id :last-error]))
                             (get-in db [:session/by-id session-id :last-error]))]
                 (when (and session-id error)
                   (comp/transact! app
                                   [(mutations/recover-snapshot-error
                                     {:session-id session-id :error error})])))))
           0))
        (ack-applied! after)
        (when (and (resync-needed? after)
                   (not (resync-needed? before)))
          (send-resync! after (get-in after [:last-outcome :reason]))))

      :else
      (do
        (record-event! event)
        (when-let [app @app-ref]
          (comp/transact! app [(mutations/apply-run-event {:event event})]))
        (when (events/epoch-error? (or (:value event) event))
          (resync! :command-epoch))
        (doseq [listener @event-listeners]
          (try
            ((if (map? listener) (:callback listener) listener) event)
            (catch :default e
              (log/error e "Workbench event listener failed"))))))))

(defn- on-transport-state!
  [state]
  (let [open? (boolean (:open? state))
        current @adapter
        phase (:phase current)]
    (if open?
      (do
        (cancel-resync-retry!)
        (reset! fetching-token nil)
        (reset! object-failures 0)
        (let [handshake? (boolean (:handshake? state))
              first-handshake? (boolean (:first-handshake? state))
              ;; A non-first handshake is a real reconnect on an already open
              ;; state atom.  Start a new subscription generation even when
              ;; the adapter still says :synced; that is how an interrupted
              ;; phase recovers without waiting for another state transition.
              new-connection? (or (contains? #{:disconnected :interrupted} phase)
                                  (and handshake?
                                       (not first-handshake?)
                                       (not= :connecting phase)))
              current (if new-connection?
                        (sync/connect current)
                        current)
              current (if (= :connecting (:phase current))
                        (sync/channel-opened current)
                        current)]
          (reset! adapter current)
          (install!)
          (when (contains? #{:connected-awaiting-sync :synced}
                           (:phase current))
            ;; The :chsk/state open event already subscribes on the initial
            ;; handshake.  The following first handshake must not send a
            ;; duplicate subscribe; a reconnect handshake must.
            (when (or (not handshake?)
                      new-connection?
                      (not @subscribed?))
              (subscribe! current)))))
      (do
        (cancel-resync-retry!)
        (reset! subscribed? false)
        (reset! fetching-token nil)
        ;; Diagnostic: record the physical WebSocket close (code, reason,
        ;; clean flag) once per close.  `:last-ws-close` lives on the Sente
        ;; channel state atom, which `transport/start!` exposes; the
        ;; `:chsk/state` payload carries only the open/closed transition.
        ;; A missing `:last-ws-close` is itself evidence: Sente reported a
        ;; closed state without a physical close (or the connection never
        ;; reached the close callback), so record the transition fields too
        ;; instead of staying silent.
        (let [close-info (some-> (:state-atom @transport/channel)
                                 deref :last-ws-close)]
          (log/error "HC-WS-CLOSE"
                     (or close-info
                         {:missing? true
                          :phase phase
                          :reason (:reason state)
                          :closed? (:closed? state)
                          :open-change? (:open-changed? state)
                          :last-close (:last-close state)})))
        (when-not (= :disconnected (:phase current))
          (let [reason (or (:reason state) :channel-closed)
                next-state (sync/channel-interrupted current reason)]
            (reset! adapter next-state)
            (install!)))))))

(defn- on-error!
  [error]
  (log/warn "Workbench transport error" error)
  (cancel-resync-retry!)
  (reset! fetching-token nil)
  (notify! :danger (str "实时通道错误：" (or (:reason error) "未知原因")))
  (let [current @adapter]
    (when-not (= :disconnected (:phase current))
      (reset! adapter (sync/channel-interrupted current (:reason error)))
      (install!))))

(defn start!
  "Start the workbench connection.  Idempotent: repeated calls only ensure the
  Sente client exists."
  []
  ;; Read-only E2E probe: the adapter phase, transport state and last
  ;; outcome are not observable from the DOM, and a failed command can be
  ;; caused by either a stale adapter phase or a closed transport.  The
  ;; function returns state only, never drafts or message content.
  (when (exists? js/window)
    (set! (.-__HC_SYNC__ js/window)
          (fn []
            (let [state @adapter
                  ch @transport/channel]
              (clj->js {:phase (:phase state)
                        :reason (:reason state)
                        :seq (:seq state)
                        :epoch (:epoch state)
                        :last-outcome (:last-outcome state)
                        :transport-status (:status ch)
                        :transport-open (:open? ch)
                        :subscription-id (:subscription-id ch)
                        :transport-last-error (some-> (:last-error ch)
                                                      str)})))))
  (install-event-log-probe!)
  (cancel-resync-retry!)
  (reset! fetching-token nil)
  (reset! object-failures 0)
  (let [current @adapter
        current (if (= :disconnected (:phase current))
                  (sync/connect current)
                  current)]
    (reset! adapter current)
    (install!)
    (let [result (transport/start! {:on-event on-event!
                                    :on-state on-transport-state!
                                    :on-error on-error!})]
      (when-not (:ok? result)
        (reset! adapter (sync/channel-interrupted @adapter (:reason result)))
        (install!)
        (notify! :danger "无法建立 Sente 通道；界面保留最后确认副本，命令不可用。"))
      result)))

(defn reconnect!
  "Start a new subscription generation and cycle the channel."
  []
  (cancel-resync-retry!)
  (reset! fetching-token nil)
  (reset! object-failures 0)
  (let [current (sync/connect @adapter)]
    (reset! adapter current)
    (reset! subscribed? false)
    (install!)
    (let [result (transport/reconnect!)]
      (when-not (:ok? result)
        ;; The transport may not have been started yet (for example the first
        ;; connection attempt failed).  Fall back to a fresh start.
        (start!))
      result)))

(defn resync!
  [reason]
  (cancel-resync-retry!)
  (reset! fetching-token nil)
  (reset! object-failures 0)
  (let [current (sync/request-resync @adapter (or reason :client-requested))]
    (reset! adapter current)
    (install!)
    (let [sent? (send-resync! current reason)]
      (when-not sent?
        (notify! :warn "通道未打开，无法请求重同步。"))
      {:ok? sent? :reason (when-not sent? :channel-not-open)})))

(defn stop!
  []
  (transport/stop!)
  (cancel-resync-retry!)
  (reset! fetching-token nil)
  (reset! object-failures 0)
  (reset! adapter (sync/disconnect @adapter))
  (reset! subscribed? false)
  (install!)
  nil)
