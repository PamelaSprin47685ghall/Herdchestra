(ns hc.client.speech.api
  "HTTP and command transport for the speech workspace.

  Binary audio and TTS audio use independent HTTP routes.  Service settings
  use the same Sente command envelope as the rest of the workbench.  A click
  never reports success; only a server receipt or snapshot does."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [hc.client.workbench.sync-bridge :as sync]
            [hc.client.workbench.transport :as transport]))

(defonce pending
  (atom {}))

(defonce installed?
  (atom false))

(defn- parse-edn
  [text]
  (try
    (reader/read-string text)
    (catch :default _ nil)))

(defn- error-response
  [status message]
  {:ok? false
   :http-status status
   :error {:code :BAD_RESPONSE :message message}})

(defn- read-edn-response!
  [response]
  (let [status (.-status response)]
    (-> (.text response)
        (.then (fn [text]
                 (let [value (parse-edn text)]
                   (cond
                     (not (map? value))
                     (error-response status "服务器返回了无法解析的语音响应")

                     (and (>= status 200) (< status 300))
                     (assoc value :http-status status)

                     :else
                     (assoc value :http-status status))))))))

(defn- fetch!
  [url options on-result]
  (if (exists? js/fetch)
    (-> (js/fetch url (clj->js options))
        (.then read-edn-response!)
        (.then (fn [result] (when on-result (on-result result))))
        (.catch (fn [error]
                  (when on-result
                    (on-result {:ok? false
                                :error {:code :NETWORK_ERROR
                                        :message (str error)}})))))
    (when on-result
      (on-result {:ok? false
                  :error {:code :FETCH_UNAVAILABLE
                          :message "当前浏览器不提供 fetch"}}))))

(defn snapshot!
  "Fetch the speech snapshot.  Returns an EDN map with `:ok?`."
  [on-result]
  (fetch! "/speech/snapshot"
          {:method "GET"
           :cache "no-store"
           :credentials "same-origin"
           :headers {"accept" "application/edn"}}
          on-result))

(defn- header-obj
  [headers]
  (clj->js (into {} (map (fn [[k v]] [(name k) (str v)]) headers))))

(defn- headers->map
  [headers]
  (if-not headers
    {}
    (let [result (transient {})]
      (.forEach headers
                (fn [value key]
                  (assoc! result (str/lower-case (str key)) (str value))))
      (persistent! result))))

(defn post-recording!
  "Upload one recorded Blob and return the transcription receipt."
  [blob headers on-result]
  (fetch! "/speech/recordings"
          {:method "POST"
           :body blob
           :cache "no-store"
           :credentials "same-origin"
           :headers (header-obj (assoc headers :accept "application/edn"))}
          on-result))

(defn retry-recording!
  [recording-id headers on-result]
  (fetch! (str "/speech/recordings/" recording-id "/retry")
          {:method "POST"
           :cache "no-store"
           :credentials "same-origin"
           :headers (header-obj (assoc headers :accept "application/edn"))}
          on-result))

(defn delete-recording!
  [recording-id on-result]
  (fetch! (str "/speech/recordings/" recording-id)
          {:method "DELETE"
           :cache "no-store"
           :credentials "same-origin"
           :headers {"accept" "application/edn"}}
          on-result))

(defn recording-url
  [recording-id]
  (str "/speech/recordings/" recording-id))

(defn audio-url
  [audio-id]
  (str "/speech/audio/" audio-id))

(defn synthesize!
  "Request TTS audio.  Returns `{:ok? true :blob :headers :status}` or an error."
  [text opts on-result]
  (if (exists? js/fetch)
    (let [body (js/JSON.stringify
                (clj->js (cond-> {:text text}
                           (:voice opts) (assoc :voice (:voice opts))
                           (:format opts) (assoc :format (:format opts))
                           (:speed opts) (assoc :speed (:speed opts))
                           (:service-id opts) (assoc :service-id (:service-id opts))
                           (:message-id opts) (assoc :message-id (:message-id opts))
                           (:version-id opts) (assoc :version-id (:version-id opts)))))
          request (js/fetch "/speech/tts"
                            (clj->js (cond-> {:method "POST"
                                              :cache "no-store"
                                              :credentials "same-origin"
                                              :headers {"content-type" "application/json"
                                                        "accept" "audio/*"}
                                              :body body}
                                       (:signal opts) (assoc :signal (:signal opts)))))]
      (-> request
          (.then
           (fn [response]
             (let [status (.-status response)]
               (if (and (>= status 200) (< status 300))
                 (.then (.blob response)
                        (fn [blob]
                          (when on-result
                            (on-result {:ok? true
                                        :status status
                                        :blob blob
                                        :headers (headers->map
                                                  (.-headers response))}))))
                 (.then (.text response)
                        (fn [text]
                          (let [value (parse-edn text)]
                            (when on-result
                              (on-result
                               (if (map? value)
                                 (assoc value :http-status status)
                                 (error-response status
                                                 "朗读服务返回了无法解析的错误")))))))))))
          (.catch
           (fn [error]
             (when on-result
               (on-result {:ok? false
                           :error {:code :NETWORK_ERROR
                                   :message (str error)}}))))))
    (when on-result
      (on-result {:ok? false
                  :error {:code :FETCH_UNAVAILABLE
                          :message "当前浏览器不提供 fetch"}}))))
;; ---------------------------------------------------------------------------
;; Sente settings commands

(defn- receipt-value
  [event]
  (or (:value event) (:receipt event) event))

(defn- handle-event!
  [event]
  (let [receipt (receipt-value event)
        command-id (or (:command-id receipt) (:commandId receipt))]
    (when (and command-id (contains? @pending command-id))
      (let [callback (get @pending command-id)]
        (swap! pending dissoc command-id)
        (when callback (callback receipt))))))

(defn install!
  []
  (when (compare-and-set! installed? false true)
    (sync/add-event-listener! handle-event!)))

(defn submit-command!
  "Submit one speech command.  CALLBACK receives the receipt."
  [kind payload callback]
  (install!)
  (if-not (sync/command-allowed?)
    {:ok? false
     :error {:code :SPEECH_NOT_SYNCED
             :message "连接尚未完成同步；草稿已保留，同步后再提交。"}}
    (let [command-id (str (random-uuid))
          command {:command-id command-id
                   :command-epoch (sync/epoch)
                   :payload (assoc payload :kind kind)}]
      (swap! pending assoc command-id callback)
      (if (transport/command! command)
        {:ok? true :command-id command-id}
        (do
          (swap! pending dissoc command-id)
          {:ok? false
           :error {:code :SPEECH_TRANSPORT
                   :message "语音设置命令未发送：实时通道未打开。"}})))))

(defn save-service!
  [area service expected-revision callback]
  (submit-command! :speech/save-service
                   {:area area
                    :service service
                    :expected-revision expected-revision}
                   callback))

(defn delete-service!
  [area service-id expected-revision callback]
  (submit-command! :speech/delete-service
                   {:area area
                    :service-id service-id
                    :expected-revision expected-revision}
                   callback))

(defn set-default!
  [area service-id expected-revision callback]
  (submit-command! :speech/set-default
                   {:area area
                    :service-id service-id
                    :expected-revision expected-revision}
                   callback))

(defn test-service!
  [area service-id callback]
  (submit-command! :speech/test-service
                   {:area area :service-id service-id}
                   callback))

(defn reorder-services!
  [area service-ids expected-revision callback]
  (submit-command! :speech/reorder-services
                   {:area area
                    :service-ids service-ids
                    :expected-revision expected-revision}
                   callback))

(defn translate!
  [payload on-result]
  (fetch! "/speech/translate"
          {:method "POST"
           :cache "no-store"
           :credentials "same-origin"
           :headers {"content-type" "application/json"
                     "accept" "application/edn"}
           :body (js/JSON.stringify (clj->js payload))}
          on-result))
