(ns hc.client.extensions.sente-smoke
  "Real Sente smoke for the extensions command path.

  Starts from the same gateway events a browser receives, sends
  `extensions/*` commands, and verifies a real local MCP process through
  add -> enable -> open -> tool-enable -> tools -> call."
  (:require [cljs.core.async :as async]
            [cljs.reader :as reader]
            [taoensso.sente :as sente]
            [taoensso.sente.packers.transit :as sente-transit]))

(def add-kind (keyword "extensions" "mcp/add"))
(def enable-kind (keyword "extensions" "mcp/enable"))
(def open-kind (keyword "extensions" "mcp/open"))
(def tool-enable-kind (keyword "extensions" "mcp/tool-enable"))
(def tools-kind (keyword "extensions" "mcp/tools"))
(def call-kind (keyword "extensions" "mcp/call"))

(def state
  (atom {:phase :connecting
         :subscription 1
         :epoch 1
         :server-id nil
         :command nil
         :args []
         :expected nil
         :step :subscribe
         :log []}))

(defn- exit! [code]
  (.exit js/process code))

(defn- fail! [message]
  (swap! state update :log conj [:fail message])
  (println "SMOKE FAIL" message)
  (exit! 1))

(defn- pass! []
  (println "SMOKE OK")
  (doseq [entry (:log @state)] (println " " (pr-str entry)))
  (exit! 0))

(defn- command-id []
  (str (random-uuid)))

(defn- send-command! [chsk payload]
  (let [id (command-id)]
    (swap! state assoc :expected id)
    (sente/chsk-send! chsk
                      [:hc/command {:command-id id
                                    :command-epoch (:epoch @state)
                                    :subscription-id (:subscription @state)
                                    :payload payload}])
    (swap! state update :log conj [:send payload])
    id))

(defn- next-payload [step server-id]
  (case step
    :add {:kind add-kind
          :server {:name "smoke"
                   :type :stdio
                   :command (:command @state)
                   :args (:args @state)}}
    :enable {:kind enable-kind :id server-id :enabled? true}
    :open {:kind open-kind :id server-id}
    :tool-enable {:kind tool-enable-kind :id server-id :tool "echo"
                  :enabled? true :opts {:approve-review? true}}
    :tool-enable-write {:kind tool-enable-kind :id server-id :tool "write_file"
                        :enabled? true :opts {:approve-review? true}}
    :tools {:kind tools-kind
            :scope {:role :viewer :assistant-servers #{server-id}}}
    :call-read {:kind call-kind :id server-id :tool "echo"
                :arguments {"text" "hello"}
                :scope {:role :viewer :assistant-servers #{server-id}}}
    :call-write-viewer {:kind call-kind :id server-id :tool "write_file"
                        :arguments {"path" "x"}
                        :scope {:role :viewer :assistant-servers #{server-id}}}
    :call-write-admin {:kind call-kind :id server-id :tool "write_file"
                       :arguments {"path" "x"}
                       :scope {:role :admin :assistant-servers #{server-id}}}
    nil))

(defn- advance! [chsk step server-id]
  (let [payload (next-payload step server-id)]
    (when payload
      (swap! state assoc :step step)
      (send-command! chsk payload))))

(defn- handle-receipt! [chsk value]
  (let [id (:command-id value)
        expected (:expected @state)
        result (get-in value [:receipt :result])
        step (:step @state)
        server-id (:server-id @state)]
    (when-not (= id expected)
      (fail! (str "unexpected receipt " id " expected " expected)))
    (swap! state update :log conj [:receipt step (:ok? value) result])
    (case step
      :add
      (let [sid (get-in result [:server :id])]
        (if (and (:ok? value) (string? sid))
          (do (swap! state assoc :server-id sid)
              (advance! chsk :enable sid))
          (fail! "add did not return a server id")))

      :enable
      (if (:ok? value)
        (advance! chsk :open server-id)
        (fail! "enable failed"))

      :open
      (if (:ok? value)
        (advance! chsk :tool-enable server-id)
        (fail! "open failed"))

      :tool-enable
      (if (:ok? value)
        (advance! chsk :tool-enable-write server-id)
        (fail! "tool-enable failed"))

      :tool-enable-write
      (if (:ok? value)
        (advance! chsk :tools server-id)
        (fail! "write tool enable failed"))

      :tools
      (if (some #(= "echo" (:name %)) (:tools result))
        (advance! chsk :call-read server-id)
        (fail! "echo not present in scoped tools"))

      :call-read
      (if (= :completed (:outcome result))
        (advance! chsk :call-write-viewer server-id)
        (fail! "read call did not complete"))

      :call-write-viewer
      (if (= :MCP_CAPABILITY_REQUIRED (get-in value [:error :code]))
        (advance! chsk :call-write-admin server-id)
        (fail! "viewer write was not rejected"))

      :call-write-admin
      (if (= :completed (:outcome result))
        (pass!)
        (fail! "admin write did not complete"))

      (fail! (str "receipt for unknown step " step)))))

(defn- handle-event! [chsk event]
  (let [data (or (:?data event) event)
        type (:type data)
        name (:name data)]
    (cond
      (and (= :hc/event (:id event)) (= "STATE_SNAPSHOT" type))
      (when (= :subscribe (:step @state))
        (advance! chsk :add nil))

      (and (= "CUSTOM" type) (= "hc/command-receipt" name))
      (handle-receipt! chsk (:value data))

      :else nil)))

(defn- listen! [chsk ch-recv]
  (async/take! ch-recv
               (fn [event]
                 (when event
                   (cond
                     (= :chsk/state (:id event))
                     (let [new-state (second (:?data event))]
                       (when (:open? new-state)
                         (swap! state assoc :phase :open)
                         (sente/chsk-send! chsk
                                           [:hc/subscribe
                                            {:subscription-id (:subscription @state)}])))

                     (= :chsk/recv (:id event))
                     (let [data (:?data event)]
                       (if (and (vector? data) (= :hc/event (first data)))
                         (handle-event! chsk {:id :hc/event :?data (second data)})
                         (handle-event! chsk event)))

                     :else (handle-event! chsk event))
                   (listen! chsk ch-recv)))))

(defn -main
  [port command args-json]
  (swap! state assoc :command command :args (reader/read-string args-json))
  (let [port (js/parseInt port 10)
        result (sente/make-channel-socket-client!
                "/chsk"
                nil
                {:type :ws
                 :host "127.0.0.1"
                 :port port
                 :protocol :http
                 :packer (sente-transit/get-transit-packer :json)
                 :client-id (str (random-uuid))
                 :wrap-recv-evs? false})]
    (if-not result
      (fail! "Sente client construction failed")
      (let [{:keys [chsk ch-recv]} result]
        (listen! chsk ch-recv)
        (js/setTimeout #(fail! "smoke timed out") 30000)))))
