(ns hc.client.workbench.transport
  "Sente channel transport for the internal AG-UI protocol.

  The transport owns only the channel.  It does not interpret snapshots or
  commands; callers receive raw events and decide whether they belong to
  `hc.client.sync` or to the run-event reducer.

  Sente's `:auto` type keeps WebSocket with Ajax long-polling fallback.  The
  workbench treats channel reconnection, command receipt, and server-side
  model/tool retry as three separate concerns; no shared retry counter exists."
  (:require [clojure.string :as str]
            [taoensso.sente :as sente]
            [taoensso.sente.packers.transit :as sente-transit]
            [taoensso.timbre :as log]))

(defonce channel
  (atom {:status :idle
         :chsk nil
         :stop-router nil
         :open? false
         :subscription-id nil
         :client-id nil
         :last-error nil
         :started-at nil}))

(def client-id-storage-key
  "Local-storage key that keeps the Sente handshake identity stable across a
  page reload.  The Owner fences commands by this identity, so a fresh random
  ID after reload would turn the reloaded tab into an observer."
  "hc.client-id")

(defonce cached-client-id
  ;; One identity per page lifetime.  The shell may render before `start!`
  ;; reads the handshake id, and a browser that denies both stores must still
  ;; use the same UUID for the UI, the attach command, and the Sente
  ;; handshake.
  (atom nil))

(defn- browser-storage
  "Return the named Web Storage object when it exposes both accessors.

  Access itself may throw (for example when a browser blocks site storage);
  a missing or half-implemented store is simply absent, never an error."
  [name]
  (try
    (let [global (when (exists? js/globalThis) js/globalThis)
          store (when global (aget global name))]
      (when (and store
                 (fn? (.-getItem store))
                 (fn? (.-setItem store)))
        store))
    (catch :default _
      nil)))

(defn- stored-client-id
  "Read a non-blank stored id.  A blank, absent, or unreadable value is nil."
  [store]
  (try
    (let [value (.getItem store client-id-storage-key)]
      (when (and (string? value) (not (str/blank? value)))
        value))
    (catch :default _
      nil)))

(defn- store-client-id!
  "Return the id persisted in STORE, writing a fresh one when none exists.

  Write failures stay silent: the caller falls through to the next storage
  tier.  The read-back proves the value survived, so a store that silently
  discards writes cannot masquerade as stable."
  [store]
  (or (stored-client-id store)
      (let [id (str (random-uuid))]
        (try
          (.setItem store client-id-storage-key id)
          (stored-client-id store)
          (catch :default _
            nil)))))

(defn stable-client-id
  "Stable Sente handshake identity for this browser tab.

  `sessionStorage` survives a reload in the same tab, so an Owner that still
  lists this tab as controller keeps controlling it.  `localStorage` is the
  fallback when session storage is unavailable, and a fresh UUID is the last
  resort when neither store accepts writes."
  []
  (or @cached-client-id
      (let [id (or (some-> (browser-storage "sessionStorage") store-client-id!)
                   (some-> (browser-storage "localStorage") store-client-id!)
                   (str (random-uuid)))]
        (reset! cached-client-id id)
        id)))

(defn status
  []
  (:status @channel))

(defn open?
  []
  (boolean (:open? @channel)))

(defn- set-status!
  [status & [details]]
  (swap! channel merge {:status status} details))

(defn- dispatch-event!
  [on-event {:keys [id ?data] :as message}]
  (cond
    (contains? #{:chsk/ws-ping :chsk/ws-pong} id)
    nil

    (= :chsk/recv id)
    (dispatch-event! on-event ?data)

    (= :hc/event id)
    (on-event ?data)

    (and (sequential? ?data)
         (some #(and (vector? %) (= :hc/event (first %))) ?data))
    (doseq [event ?data]
      (if (and (vector? event) (= :hc/event (first event)))
        (on-event (second event))
        (dispatch-event! on-event event)))

    (and (vector? ?data) (= :hc/event (first ?data)))
    (on-event (second ?data))

    (and (map? ?data)
         (or (:type ?data) (:event ?data)))
    (on-event ?data)

    (map? ?data)
    (on-event (assoc ?data :type id))

    (and (keyword? id)
         (or (= "hc" (namespace id))
             (contains? #{:state-snapshot :state-delta :messages-snapshot
                          :run-started :run-finished :run-error
                          :text-message-start :text-message-content
                          :tool-call-start :tool-call-args :tool-call-end
                          :tool-call-result :custom}
                        id)))
    (on-event (assoc (if (map? ?data) ?data {:value ?data}) :type id))

    :else
    (log/debug "Ignoring Sente event" id)))

(defn- router-handler
  [on-event on-state on-error]
  (fn [{:keys [id ?data] :as message}]
    (case id
      :chsk/state
      (let [[old-state new-state open-change?] ?data
            state (if (map? new-state) new-state {})
            ;; Sente always publishes the complete new state.  A close carries
            ;; `:open? false` together with `open-change? true`; OR-ing the
            ;; transition flag would misread that close as open.
            open? (boolean (if (contains? state :open?)
                             (:open? state)
                             open-change?))]
        ;; Diagnostic: Sente swaps its state atom for every open/close
        ;; transition.  Record the raw transition so a phase change without
        ;; a physical WebSocket close can be told apart from one caused by a
        ;; misread state payload.
        (log/debug "HC-WS-STATE"
                   {:old-open? (:open? old-state)
                    :new-open? (:open? new-state)
                    :map? (map? new-state)
                    :open-change? open-change?
                    :last-close (:last-close state)
                    :closed? (:closed? state)})
        (swap! channel assoc
               :open? open?
               :status (if open? :open :closed)
               :last-state state
               :subscription-id (if open? (:subscription-id @channel) nil))
        (when on-state
          (on-state (assoc state :open? open? :reason (:reason state)))))

      :chsk/handshake
      (let [[uid _ handshake-data first-handshake?] ?data
            state (or (some-> (:state-atom @channel) deref) {})
            state (assoc state
                         :open? true
                         :uid uid
                         :handshake-data handshake-data
                         :first-handshake? (boolean first-handshake?))]
        ;; A handshake is the authoritative "channel is usable" signal.  It is
        ;; especially important after an auto-reconnect: the state atom can
        ;; already be open while the sync adapter still holds an interrupted
        ;; phase.  Do not drop it as an unknown event.
        (swap! channel assoc
               :open? true
               :status :open
               :last-state state)
        (when on-state
          (on-state (assoc state
                           :open? true
                           :handshake? true
                           :first-handshake? (boolean first-handshake?)
                           :reason :handshake))))

      :chsk/closed
      (do
        (set-status! :closed {:subscription-id nil})
        (when on-error (on-error {:reason :channel-closed}))
        (when on-state (on-state {:open? false :reason :channel-closed})))

      :chsk/error
      (do
        (set-status! :error {:last-error ?data :subscription-id nil})
        (when on-error (on-error {:reason :channel-error :error ?data}))
        (when on-state (on-state {:open? false :reason :channel-error})))

      (dispatch-event! on-event message))))

(defn start!
  "Open the Sente client.  Returns `{:ok? bool :reason keyword}`.

  Options:

    :on-event  (fn [event])       raw AG-UI or project event
    :on-state  (fn [sente-state]) channel open/closed changes
    :on-error  (fn [error])
    :uri       Sente path, default `/chsk`
    :type      `:auto` (default), `:ws`, or `:ajax`
    :packer    a Sente packer; default Transit/JSON, matching
               `hc.server.gateway.sente`"
  [{:keys [on-event on-state on-error uri type packer]
    :or {uri "/chsk" type :auto}}]
  (if (contains? #{:connecting :open} (:status @channel))
    {:ok? true :reason :already-started}
    (try
      (let [client-id (stable-client-id)
            result (sente/make-channel-socket-client!
                    uri
                    nil
                    {:type type
                     :packer (or packer (sente-transit/get-transit-packer :json))
                     :client-id client-id
                     :wrap-recv-evs? false
                     :backoff-ms-fn (fn [attempt]
                                      (min 30000 (* 1000 (max 1 attempt))))})]
        (if-not result
          (do
            (set-status! :error {:last-error :client-construction-failed})
            (when on-error (on-error {:reason :client-construction-failed}))
            {:ok? false :reason :client-construction-failed})
          (let [{:keys [ch-recv chsk]} result
                stop-router (sente/start-client-chsk-router!
                             ch-recv
                             (router-handler on-event on-state on-error))]
            (swap! channel merge
                   {:status :connecting
                    :chsk chsk
                    :state-atom (:state_ chsk)
                    :stop-router stop-router
                    :client-id client-id
                    :last-error nil
                    :started-at (js/Date.)})
            {:ok? true :reason nil})))
      (catch :default e
        (log/error e "Unable to start Sente client")
        (set-status! :error {:last-error (str e)})
        (when on-error (on-error {:reason :client-construction-failed
                                  :error (str e)}))
        {:ok? false :reason :client-construction-failed}))))

(defn send!
  "Send one Sente event.  Returns true only when the channel accepted it for
  transmission; a server receipt is still required before claiming success."
  [event]
  (let [{:keys [chsk open?]} @channel]
    (if (and chsk open?)
      (try
        (boolean (sente/chsk-send! chsk event))
        (catch :default e
          (log/error e "Sente send failed")
          (set-status! :error {:last-error (str e)})
          false))
      false)))

(defn- current-subscription []
  (:subscription-id @channel))

(defn subscribe!
  "Send the subscription handshake.  The server may also auto-subscribe the
  connection; this is idempotent at the gateway."
  [{:keys [subscription-id epoch seq reason]}]
  (swap! channel assoc :subscription-id subscription-id)
  (send! [:hc/subscribe
          (cond-> {:subscription-id subscription-id}
            (some? epoch) (assoc :epoch epoch)
            (some? seq) (assoc :seq seq)
            reason (assoc :reason reason))]))

(defn request-resync!
  [{:keys [subscription-id epoch seq reason]}]
  (let [subscription-id (or subscription-id (current-subscription))]
    (if (nil? subscription-id)
      false
      (send! [:hc/resync
          (cond-> {:subscription-id subscription-id}
            (some? epoch) (assoc :epoch epoch)
            (some? seq) (assoc :seq seq)
            reason (assoc :reason reason))]))))

(defn command!
  "Send a command envelope.  The subscription ID defaults to the currently
  subscribed generation so every bridge gets stale-event filtering."
  [{:keys [command-id command-epoch payload subscription-id]}]
  (let [subscription-id (or subscription-id (current-subscription))]
    (if (nil? subscription-id)
      false
      (send! [:hc/command
              (cond-> {:command-id command-id
                       :command-epoch command-epoch
                       :payload payload}
                subscription-id (assoc :subscription-id subscription-id))]))))

(defn receipt!
  "Ask the gateway for a command receipt after reconnect/resync.

  `:subscription-id` is sent only when the caller supplies one.  A receipt
  query is idempotent and the gateway answers it on the uid's current
  subscription, so attaching a possibly stale remembered generation is
  strictly worse than omitting it: an explicit old id fails
  `subscription-check` with STALE_SUBSCRIPTION, and for a fire-and-forget
  send that refusal is dropped silently.  Callers that need generation
  fencing pass `:subscription-id` explicitly."
  [{:keys [command-id session-id subscription-id]}]
  (send! [:hc/receipt
          (cond-> {:command-id command-id}
            subscription-id (assoc :subscription-id subscription-id)
            session-id (assoc :session-id session-id))]))

(defn ack!
  "Acknowledge the last applied state sequence so the server can detect a
  slow client without blocking the session."
  [{:keys [subscription-id seq]}]
  (let [subscription-id (or subscription-id (current-subscription))]
    (when (and (some? subscription-id) (integer? seq))
      (send! [:hc/ack {:subscription-id subscription-id :seq seq}]))))

(defn reconnect!
  "Cycle the channel.  Sente will emit a fresh `:chsk/state`; the sync bridge
  starts a new subscription generation when that state opens."
  []
  (let [{:keys [chsk]} @channel]
    (if chsk
      (try
        (set-status! :connecting)
        (sente/chsk-reconnect! chsk)
        {:ok? true :reason nil}
        (catch :default e
          (log/error e "Sente reconnect failed")
          (set-status! :error {:last-error (str e)})
          {:ok? false :reason :reconnect-failed}))
      {:ok? false :reason :not-started})))

(defn stop!
  "Close the channel deliberately.  This releases the observation transport;
  it never cancels a server-side task."
  []
  (let [{:keys [stop-router chsk]} @channel]
    (when stop-router
      (try (stop-router) (catch :default _ nil)))
    (when chsk
      (try (sente/chsk-disconnect! chsk) (catch :default _ nil)))
    (set-status! :idle {:chsk nil
                        :stop-router nil
                        :open? false
                        :subscription-id nil})
    nil))
