(ns hc.server.scheduler
  "Application adapter for `hc.compose.scheduler`.

  This namespace is the bridge between the pure schedule service and the
  running application:

    - durable state lives in the `scheduler` storage partition through
      `hc.server.scheduler.durable-store`;
    - `start!` opens the service, runs storage-recovery obligation
      classification, and starts one daemon scheduled executor;
    - `start-run!` creates a real result session through the session registry,
      submits a `:user/message` command to its Owner, and watches the Owner
      notification stream to close the scheduler run when the session run
      reaches a terminal state;
    - `stop-run!` submits the Owner's `:stop-run` command;
    - `notify!` broadcasts a CUSTOM `hc/schedule-notification` event through
      the gateway when one is attached.

  The application owns storage and Owners.  This adapter never opens a second
  storage handle and never closes the one it receives.  `stop!` stops intake
  and waits for the current tick before returning.

  Exact app integration hooks:

      (hc.server.scheduler/start!
        {:storage store :registry registry :config config :gateway gateway})
      ;; -> {:ok? true :scheduler runtime :recovery report} | {:ok? false :error e}

      (hc.server.scheduler/stop! runtime)
      ;; -> {:stopped? true}

  Optional overrides for tests/embedding: `:start-run!`, `:stop-run!`,
  `:notify!`, `:preflight`, `:clock`, `:tick-ms`, `:generation`."
  (:require [clojure.string :as str]
            [hc.compose.scheduler.model :as model]
            [hc.compose.scheduler.service :as service]
            [hc.compose.scheduler.time :as sched-time]
            [hc.core.relay :as relay]
            [hc.server.gateway :as gateway]
            [hc.server.scheduler.durable-store :as durable]
            [hc.server.storage :as storage])
  (:import (java.util UUID)
           (java.util.concurrent Executors ScheduledExecutorService
                                 ThreadFactory TimeUnit)))

(def terminal-session-run-states
  #{:completed :failed :cancelled :outcome-unknown})

(defn- daemon-thread-factory
  [prefix]
  (let [counter (java.util.concurrent.atomic.AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- result-title
  [schedule run]
  (str (:name schedule) " — " (:scheduled-local run)))

(defn- snapshot-model
  [snapshot]
  (let [model (:model snapshot)]
    {:connection-id (:connection-id model)
     :model-entry-id (or (:model-entry-id model) (:model-id model))}))

(defn- snapshot-assistant-id
  [snapshot]
  (or (:assistant-id snapshot)
      (get-in snapshot [:assistant :id])
      (get-in snapshot [:assistant :assistant-id])))

(defn- snapshot-capability-scope
  [snapshot]
  (:capability-scope snapshot))

(defn awaiting-user-state?
  "True when the Owner state has a Plan or execution tenure suspended on a
  human answer.  The scheduler adapter uses this fact to end the unattended
  phase without waiting for the session run timeout."
  [state]
  (let [relay-state (:relay state)
        plan (relay/current-tenure relay-state :plan)
        executions (vals (relay/execution-tenures relay-state))]
    (boolean (some :awaiting-user? (cons plan executions)))))

(defn- keyed-by
  "Return VALUE as an id->entry map.

  The app config can carry `:connections` or `:models` as either a map keyed
  by stable id or a sequence of entries carrying `:connection-id`/`:id` and
  `:model-entry-id`/`:id`.  Both shapes are real; neither is guessed."
  [value id-keys]
  (cond
    (map? value) value
    (sequential? value)
    (into {}
          (keep (fn [entry]
                  (when (map? entry)
                    (when-let [id (some entry id-keys)]
                      [id entry]))))
          value)
    :else {}))

(defn- normalize-model-config
  "Normalize the two accepted model-config shapes to the sessions shape."
  [models]
  (if-not (map? models)
    models
    (let [connections (keyed-by (:connections models)
                                [:connection-id :id])]
      (assoc models
             :connections
             (into {}
                   (map (fn [[id connection]]
                          [id (if (map? connection)
                                (let [connection
                                      (cond-> connection
                                        (and (nil? (:connection-id connection))
                                             (:id connection))
                                        (assoc :connection-id (:id connection)))
                                      models (keyed-by
                                              (:models connection)
                                              [:model-entry-id :id :model-id])
                                      models
                                      (into {}
                                            (map (fn [[model-id model]]
                                                   [model-id
                                                    (if (map? model)
                                                      (cond-> model
                                                        (nil? (:model-entry-id model))
                                                        (assoc :model-entry-id
                                                               (or (:id model)
                                                                   (:model-id model)
                                                                   model-id)))
                                                      model)]))
                                            models)]
                                  (assoc connection :models models))
                                connection)]))
                   connections)))))

(defn- resolve-config
  "Resolve CONFIG, which is a plain map or a zero-argument function.

  The application passes a function so a settings save reaches the next
  preflight and the next result-session creation without a restart.  A nil
  value is the empty configuration; this function never returns nil."
  [config]
  (let [config (if (fn? config) (config) config)]
    (or config {})))

(defn- runtime-config
  [runtime]
  (resolve-config (:config runtime)))

(defn- model-configured?
  [models {:keys [connection-id model-entry-id]}]
  (let [models (normalize-model-config models)
        connection (get-in models [:connections connection-id])
        entry (get-in connection [:models model-entry-id])]
    (boolean
     (and (string? connection-id)
          (string? model-entry-id)
          connection
          entry))))
(defn- session-active-run?
  [session]
  (boolean
   (some #(= :running (get-in session [:runs % :state]))
         (:run-order session))))

(defn- registry-create-session!
  [registry opts]
  ((requiring-resolve 'hc.server.sessions.registry/create-session!)
   registry opts))

(defn- registry-owner-for
  [registry session-id]
  ((requiring-resolve 'hc.server.sessions.registry/owner-for)
   registry session-id))

(defn- registry-get-session
  [registry session-id]
  ((requiring-resolve 'hc.server.sessions.registry/get-session)
   registry session-id))

(defn- registry-branch-session!
  [registry source-session-id opts]
  ((requiring-resolve 'hc.server.sessions.registry/branch-session!)
   registry source-session-id opts))

(defn- message-content
  [message]
  (get-in message [:versions (or (:current-version message) 0) :content]))

(defn- issue
  ([code message]
   (issue code message nil))
  ([code message details]
   {:ok? false
    :issues [(cond-> {:code code :message message}
               (some? details) (assoc :details details))]}))

(defn- default-preflight
  [{:keys [registry config]}]
  (fn [{:keys [schedule]}]
    (let [config (resolve-config config)
          execution (or (:execution schedule)
                        (get-in schedule [:snapshot :execution]))
          mode (:mode execution)]
      (cond
        (not (model/snapshot-complete? schedule))
        (issue :SCHEDULE_INCOMPLETE
               "schedule is missing its frozen model or execution configuration"
               {:missing [:snapshot :model :execution]
                :remedy :select-model
                :action
                "Open the schedule details, select a model, save, then resume the schedule."})

        (= :redo-question mode)
        (let [{:keys [chat-id message-id redo-mode]} execution
              redo-mode (or redo-mode :branch)
              loaded (when (and (string? chat-id)
                                (string? message-id)
                                registry)
                       (registry-get-session registry chat-id))
              session (:session loaded)
              message (get-in session [:messages message-id])
              content (message-content message)
              target-role (if (= :append-version redo-mode)
                            :assistant
                            :user)]
          (cond
            (not (and (string? chat-id) (string? message-id)))
            (issue :REDO_TARGET_REQUIRED
                   "redo-question needs :chat-id and :message-id"
                   {:execution execution})

            (not= :found (:status loaded))
            (issue :REDO_SOURCE_NOT_FOUND
                   "the source chat for the redo does not exist"
                   {:chat-id chat-id})

            (not= :active (:status session))
            (issue :REDO_SOURCE_NOT_ACTIVE
                   "the source chat is not active"
                   {:chat-id chat-id :status (:status session)})

            (nil? message)
            (issue :REDO_MESSAGE_NOT_FOUND
                   "the message to redo does not exist"
                   {:chat-id chat-id :message-id message-id})

            (or (:deleted? message) (:folded? message))
            (issue :REDO_MESSAGE_HIDDEN
                   "the message to redo is deleted or folded"
                   {:chat-id chat-id :message-id message-id})

            (not (map? (:model-binding session)))
            (issue :REDO_MODEL_NOT_SELECTED
                   "the source chat has no model binding"
                   {:chat-id chat-id})

            (not= target-role (:role message))
            (issue (if (= :append-version redo-mode)
                     :REDO_MESSAGE_NOT_ASSISTANT
                     :REDO_MESSAGE_NOT_USER)
                   (if (= :append-version redo-mode)
                     "append-version redo targets an assistant message"
                     "branch redo targets a user question")
                   {:chat-id chat-id :message-id message-id
                    :role (:role message)})

            (and (= :append-version redo-mode)
                 (session-active-run? session))
            {:ok? false
             :skip? true
             :reason :source-run-active
             :issues [{:code :REDO_RUN_ACTIVE
                       :message "the source chat already has an active run"
                       :details {:chat-id chat-id}}]}

            (and (= :branch redo-mode)
                 (not (and (string? content) (not (str/blank? content)))))
            (issue :REDO_MESSAGE_EMPTY
                   "the source question has no content to redo"
                   {:chat-id chat-id :message-id message-id})

            :else {:ok? true}))

        (= :continue-chat mode)
        (let [chat-id (:chat-id execution)
              loaded (when (and (string? chat-id) registry)
                       (registry-get-session registry chat-id))
              session (:session loaded)]
          (cond
            (and (= :found (:status loaded))
                 (not= :active (:status session)))
            (issue :RESULT_CHAT_NOT_ACTIVE
                   "the target chat is not active"
                   {:chat-id chat-id :status (:status session)})

            :else
            (if-let [owner (registry-owner-for registry chat-id)]
              (let [state (:state ((:snapshot owner)))]
                (if (session-active-run? state)
                  {:ok? false
                   :skip? true
                   :reason :result-chat-busy
                   :issues [{:code :RESULT_CHAT_BUSY
                             :message "the target chat already has an active run"
                             :details {:chat-id chat-id}}]}
                  {:ok? true}))
              (issue :RESULT_CHAT_UNAVAILABLE
                     "the target chat has no live Owner"
                     {:chat-id chat-id}))))

        :else
        (let [model (snapshot-model (:snapshot schedule))]
          (if (model-configured? (get config :models) model)
            {:ok? true}
            (issue :MODEL_UNAVAILABLE
                   "the frozen model binding is not configured"
                   model)))))))

(defn- accepted-receipt?
  [receipt]
  (and (map? receipt)
       (not= :refused (:status receipt))
       (not (false? (:ok? receipt)))
       (nil? (:error receipt))))

(defn- command-epoch
  [owner]
  (or (get-in ((:snapshot owner)) [:state :commands :command-epoch])
      1))

(defn- attach!
  [runtime scheduler-run-id attrs]
  (when-let [svc @(:service-ref runtime)]
    (service/attach-run! svc scheduler-run-id attrs)))

(defn- unsubscribe!
  [runtime scheduler-run-id]
  (when-let [f (get @(:run-watchers runtime) scheduler-run-id)]
    (try (f) (catch Throwable _ nil))
    (swap! (:run-watchers runtime) dissoc scheduler-run-id)))

(defn- watch-session-run!
  "Watch the Owner's committed notifications and close the scheduler run when
  the session run reaches a terminal state.

  The watcher is installed before the user command is submitted, so a fast
  synchronous model turn cannot be missed."
  [runtime owner session-id scheduler-run-id]
  (let [before-runs (try
                      (set (:run-order (:state ((:snapshot owner)))))
                      (catch Throwable _ #{}))
        last-status (atom nil)
        awaiting-sent? (atom false)
        unsubscribe
        ((:subscribe! owner)
         (fn [notification]
           (let [state (:state notification)
                 session-run-id (some (fn [id]
                                        (when-not (contains? before-runs id)
                                          id))
                                      (reverse (:run-order state)))
                 session-run (get-in state [:runs session-run-id])
                 status (:state session-run)]
             (if (and session-run
                      (= :running status)
                      (awaiting-user-state? state))
               (when-not @awaiting-sent?
                 (reset! awaiting-sent? true)
                 (when-let [svc @(:service-ref runtime)]
                   (service/finish-run!
                    svc scheduler-run-id
                    {:status :awaiting-user
                     :chat-id session-id
                     :reason :awaiting-user})))
               (reset! awaiting-sent? false))
             (when (and session-run
                        (contains? terminal-session-run-states status)
                        (not= status @last-status))
               (reset! last-status status)
               (let [mapped (case status
                              :completed :completed
                              :failed :failed
                              :cancelled :stopped
                              :outcome-unknown :outcome-unknown)]
                 (when-let [svc @(:service-ref runtime)]
                   (service/finish-run!
                    svc scheduler-run-id
                    {:status mapped
                     :chat-id session-id
                     :result-summary (str "result chat " session-id)
                     :reason (when (= :outcome-unknown status)
                               :session-outcome-unknown)})))
               (when (not= :outcome-unknown status)
                 (unsubscribe! runtime scheduler-run-id))))))]
    (swap! (:run-watchers runtime) assoc scheduler-run-id unsubscribe)
    unsubscribe))

(defn- submit-user-message!
  [owner command-id text]
  (try
    ((:submit-command! owner)
     {:command-id command-id
      :command-epoch (command-epoch owner)
      :payload {:kind :user/message :text text}})
    (catch Throwable t
      {:status :refused
       :error {:code :SCHEDULE_COMMAND_FAILED
               :message (or (.getMessage t) (str t))}})))

(defn- start-new-chat!
  [runtime {:keys [run schedule]}]
  (let [snapshot (:snapshot run)
        {:keys [connection-id model-entry-id]} (snapshot-model snapshot)
        assistant-id (snapshot-assistant-id snapshot)
        capability-scope (snapshot-capability-scope snapshot)
        create-session! (or (:create-session! runtime)
                            #(registry-create-session! (:registry runtime) %))
        created (try
                  (let [config (runtime-config runtime)]
                    (create-session! {:title (result-title schedule run)
                                      :config (normalize-model-config
                                               (:models config))
                                      :connection-id connection-id
                                      :model-entry-id model-entry-id
                                      :assistant-id assistant-id
                                      :capability-scope capability-scope
                                      :language (:language config :en)}))
                  (catch Throwable t
                    {:status :error
                     :error {:code :SCHEDULE_SESSION_FAILED
                             :message (or (.getMessage t) (str t))}}))]
    (if-not (= :created (:status created))
      {:accepted false
       :error (or (:error created)
                  {:code :SCHEDULE_SESSION_FAILED
                   :message "result session could not be created"
                   :details {:status (:status created)}})}
      (let [session (:session created)
            owner (:owner created)
            session-id (:session-id session)]
        (if-not owner
          {:accepted false
           :error {:code :SCHEDULE_OWNER_UNAVAILABLE
                   :message "result session has no live Owner"
                   :details {:session-id session-id}}}
          (do
            (watch-session-run! runtime owner session-id (:id run))
            (let [command-id (str "schedule-" (:id run))
                  receipt (submit-user-message! owner command-id
                                                (get-in run [:snapshot :prompt]))]
              (attach! runtime (:id run)
                       {:chat-id session-id
                        :owner-id (:owner-id session)
                        :command-id command-id
                        :execution-target :new-chat
                        :capability-scope capability-scope})
              (if (accepted-receipt? receipt)
                {:accepted true
                 :chat-id session-id
                 :owner-id (:owner-id session)
                 :command-id command-id}
                (do
                  (unsubscribe! runtime (:id run))
                  {:accepted false
                   :error {:code :SCHEDULE_COMMAND_REFUSED
                           :message "the result session refused the scheduled message"
                           :details {:receipt receipt
                                     :session-id session-id}}})))))))))

(defn- start-continue-chat!
  [runtime {:keys [run schedule]}]
  (let [execution (:execution run)
        chat-id (:chat-id execution)
        owner-for (or (:owner-for runtime)
                      #(registry-owner-for (:registry runtime) %))
        owner (when chat-id (owner-for chat-id))]
    (if-not owner
      {:accepted false
       :error {:code :RESULT_CHAT_UNAVAILABLE
               :message "the target chat has no live Owner"
               :details {:chat-id chat-id}}}
      (do
        (watch-session-run! runtime owner chat-id (:id run))
        (let [command-id (str "schedule-" (:id run))
              receipt (submit-user-message! owner command-id
                                            (get-in run [:snapshot :prompt]))]
          (attach! runtime (:id run)
                   {:chat-id chat-id
                    :owner-id (or (get-in ((:snapshot owner)) [:state :owner-id])
                                  (get-in ((:snapshot owner))
                                          [:state :session :owner-id]))
                    :command-id command-id
                    :execution-target :continue-chat
                    :capability-scope (snapshot-capability-scope (:snapshot run))})
          (if (accepted-receipt? receipt)
            {:accepted true
             :chat-id chat-id
             :command-id command-id}
            (do
              (unsubscribe! runtime (:id run))
              {:accepted false
               :error {:code :SCHEDULE_COMMAND_REFUSED
                       :message "the target chat refused the scheduled message"
                       :details {:receipt receipt :chat-id chat-id}}})))))))

(defn- start-redo-append-version!
  [runtime run source-id message-id source-session]
  (let [owner-for (or (:owner-for runtime)
                      #(registry-owner-for (:registry runtime) %))
        owner (owner-for source-id)]
    (if-not owner
      {:accepted false
       :error {:code :RESULT_CHAT_UNAVAILABLE
               :message "the source chat has no live Owner"
               :details {:chat-id source-id}}}
      (do
        (watch-session-run! runtime owner source-id (:id run))
        (let [command-id (str "schedule-" (:id run))
              receipt (try
                        ((:submit-command! owner)
                         {:command-id command-id
                          :command-epoch (command-epoch owner)
                          :payload {:kind :history/regenerate
                                    :session-id source-id
                                    :message-id message-id}})
                        (catch Throwable t
                          {:status :refused
                           :error {:code :SCHEDULE_COMMAND_FAILED
                                   :message (or (.getMessage t) (str t))}}))]
          (attach! runtime (:id run)
                   {:chat-id source-id
                    :owner-id (:owner-id source-session)
                    :command-id command-id
                    :execution-target :redo-question
                    :redo-mode :append-version
                    :source-session-id source-id
                    :source-message-id message-id
                    :capability-scope (snapshot-capability-scope (:snapshot run))})
          (if (accepted-receipt? receipt)
            {:accepted true
             :chat-id source-id
             :owner-id (:owner-id source-session)
             :command-id command-id
             :redo-mode :append-version
             :source-session-id source-id
             :source-message-id message-id}
            (do
              (unsubscribe! runtime (:id run))
              {:accepted false
               :error {:code :SCHEDULE_COMMAND_REFUSED
                       :message "the source chat refused regeneration"
                       :details {:receipt receipt
                                 :chat-id source-id
                                 :message-id message-id}}})))))))

(defn- start-redo-question!
  [runtime {:keys [run]}]
  (let [execution (:execution run)
        redo-mode (or (:redo-mode execution) :branch)
        source-id (:chat-id execution)
        message-id (:message-id execution)
        get-session (or (:get-session runtime)
                        #(registry-get-session (:registry runtime) %))
        branch-session! (or (:branch-session! runtime)
                            #(registry-branch-session! (:registry runtime) %1 %2))
        loaded (try
                 (get-session source-id)
                 (catch Throwable t
                   {:status :error
                    :error {:code :REDO_SOURCE_READ_FAILED
                            :message (or (.getMessage t) (str t))}}))
        session (:session loaded)
        message (get-in session [:messages message-id])
        content (message-content message)]
    (cond
      (not= :found (:status loaded))
      {:accepted false
       :error (or (:error loaded)
                  {:code :REDO_SOURCE_NOT_FOUND
                   :message "the source chat for the redo does not exist"
                   :details {:chat-id source-id}})}

      (or (:deleted? message) (:folded? message))
      {:accepted false
       :error {:code :REDO_MESSAGE_HIDDEN
               :message "the message to redo is deleted or folded"
               :details {:chat-id source-id :message-id message-id}}}

      (= :append-version redo-mode)
      (if (= :assistant (:role message))
        (start-redo-append-version! runtime run source-id message-id session)
        {:accepted false
         :error {:code :REDO_MESSAGE_NOT_ASSISTANT
                 :message "append-version redo targets an assistant message"
                 :details {:chat-id source-id :message-id message-id}}})

      (not= :user (:role message))
      {:accepted false
       :error {:code :REDO_MESSAGE_NOT_USER
               :message "branch redo targets a user question"
               :details {:chat-id source-id :message-id message-id}}}

      (not (and (string? content) (not (str/blank? content))))
      {:accepted false
       :error {:code :REDO_MESSAGE_EMPTY
               :message "the source question has no content to redo"
               :details {:chat-id source-id :message-id message-id}}}

      :else
      (let [branch (try
                     (branch-session!
                      source-id
                      {:before-message-id message-id
                       :title (str (:title session) " — redo")})
                     (catch Throwable t
                       {:status :error
                        :error {:code :REDO_BRANCH_FAILED
                                :message (or (.getMessage t) (str t))}}))]
        (if-not (= :created (:status branch))
          {:accepted false
           :error (or (:error branch)
                      {:code :REDO_BRANCH_FAILED
                       :message "the source chat could not be branched"
                       :details {:chat-id source-id
                                 :message-id message-id
                                 :status (:status branch)}})}
          (let [branch-session (:session branch)
                owner (:owner branch)
                branch-id (:session-id branch-session)]
            (if-not owner
              {:accepted false
               :error {:code :SCHEDULE_OWNER_UNAVAILABLE
                       :message "the redo branch has no live Owner"
                       :details {:branch-session-id branch-id}}}
              (do
                (watch-session-run! runtime owner branch-id (:id run))
                (let [command-id (str "schedule-" (:id run))
                      receipt (submit-user-message! owner command-id content)]
                  (attach! runtime (:id run)
                           {:chat-id branch-id
                            :owner-id (:owner-id branch-session)
                            :command-id command-id
                            :execution-target :redo-question
                            :redo-mode :branch
                            :source-session-id source-id
                            :source-message-id message-id
                            :branch-session-id branch-id
                            :capability-scope (snapshot-capability-scope
                                               (:snapshot run))})
                  (if (accepted-receipt? receipt)
                    {:accepted true
                     :chat-id branch-id
                     :owner-id (:owner-id branch-session)
                     :command-id command-id
                     :redo-mode :branch
                     :source-session-id source-id
                     :source-message-id message-id
                     :branch-session-id branch-id}
                    (do
                      (unsubscribe! runtime (:id run))
                      {:accepted false
                       :error {:code :SCHEDULE_COMMAND_REFUSED
                               :message "the redo branch refused the question"
                               :details {:receipt receipt
                                         :branch-session-id branch-id}}})))))))))))

(defn- default-start-run!
  [runtime]
  (fn [{:keys [run] :as request}]
    (case (get-in run [:execution :mode] :new-chat)
      :new-chat (start-new-chat! runtime request)
      :continue-chat (start-continue-chat! runtime request)
      :redo-question (start-redo-question! runtime request)
      {:accepted false
       :error {:code :UNSUPPORTED_EXECUTION
               :message "this execution mode is not supported by the app adapter"
               :details {:execution (:execution run)}}})))

(defn- default-stop-run!
  [runtime]
  (fn [{:keys [run-id reason]}]
    (if-let [svc @(:service-ref runtime)]
      (let [run (service/run svc run-id)
            chat-id (:chat-id run)
            owner-for (or (:owner-for runtime)
                          #(registry-owner-for (:registry runtime) %))
            owner (when chat-id (owner-for chat-id))]
        (if-not owner
          {:status :no-owner :run-id run-id}
          (try
            ((:submit-command! owner)
             {:command-id (str "stop-" run-id "-" (UUID/randomUUID))
              :command-epoch (command-epoch owner)
              :payload {:kind :stop-run
                        :reason (or reason :user-cancel)}})
            {:status :stop-requested :run-id run-id}
            (catch Throwable t
              {:status :stop-failed
               :run-id run-id
               :error {:code :STOP_COMMAND_FAILED
                       :message (or (.getMessage t) (str t))}}))))
      {:status :no-service :run-id run-id})))

(defn- default-notify!
  [runtime]
  (fn [{:keys [run-id run schedule]}]
    (if-let [gw (:gateway runtime)]
      (let [result (gateway/broadcast!
                    gw
                    {:type "CUSTOM"
                     :name "hc/schedule-notification"
                     :value {:run-id run-id
                             :schedule-id (:id schedule)
                             :schedule-name (:name schedule)
                             :status (:status run)
                             :reason (:reason run)
                             :chat-id (:chat-id run)
                             :result-files (:result-files run)}})]
        (if (:ok? result)
          :sent
          {:state :failed
           :error (:error result)}))
      :pending)))

(defn- tick-once!
  [runtime]
  (if @(:stopped? runtime)
    {:ok? false
     :error {:code :SCHEDULER_STOPPED
             :message "the scheduler is stopping"}}
    (locking (:tick-lock runtime)
      (let [at ((:clock runtime))]
        (try
          (let [result (service/tick! (:service runtime))]
            (swap! (:state runtime)
                   assoc
                   :last-tick-at at
                   :last-result result
                   :last-error (when-not (:ok? result) (:error result)))
            result)
          (catch Throwable t
            (let [error {:code :SCHEDULER_TICK_FAILED
                         :message (or (.getMessage t) (str t))}]
              (swap! (:state runtime)
                     assoc :last-tick-at at :last-error error)
              {:ok? false :error error})))))))

(defn- start-timer!
  [runtime]
  (let [executor (Executors/newSingleThreadScheduledExecutor
                  (daemon-thread-factory "hc-scheduler"))
        tick-ms (long (:tick-ms runtime))]
    (swap! (:state runtime) assoc :executor executor)
    (.scheduleWithFixedDelay
     ^ScheduledExecutorService executor
     ^Runnable (fn [] (tick-once! runtime))
     0
     tick-ms
     TimeUnit/MILLISECONDS)
    executor))

(defn start!
  "Start the scheduler adapter.

  Required: `:storage` (an open `hc.server.storage` handle) and `:registry`.
  `:config` supplies `:models` and optional `:language`; `:gateway` enables
  CUSTOM notifications.  `:config` is either a plain map or a zero-argument
  function.  A function is read at each preflight and each result-session
  creation, so a live settings save reaches the next attempt without a
  restart; a map stays fixed for the runtime's lifetime.  A throwing resolver
  fails the preflight (`:PREFLIGHT_FAILED`) and the session start
  (`:SCHEDULE_SESSION_FAILED`) instead of passing.  Returns the runtime handle
  or an error value.

  Recovery order:

    1. `hc.server.recovery/recover!` classifies scheduler obligations;
    2. `hc.compose.scheduler.service/open-service` loads durable state and
       applies scheduler recovery (interrupted runs, missed occurrences);
    3. the timer starts and performs its first tick immediately."
  [{:keys [storage registry config gateway clock tick-ms generation platform
           start-run! stop-run! notify! preflight device-zone
           grace-ms timeout-ms recover? create-session! owner-for
           get-session branch-session!]
    :or {clock #(System/currentTimeMillis)
         tick-ms 1000
         recover? true}}]
  (cond
    (not (storage/store-open? storage))
    {:ok? false
     :error {:code :SCHEDULER_STORAGE_CLOSED
             :message "scheduler needs an open storage handle"}}

    (not (and (integer? tick-ms) (pos? tick-ms)))
    {:ok? false
     :error {:code :SCHEDULER_TICK_INVALID
             :message ":tick-ms must be a positive integer"
             :details {:tick-ms tick-ms}}}

    :else
    (let [generation (or generation (str (UUID/randomUUID)))
          durable-store (durable/durable-store storage
                                               {:generation generation
                                                :clock clock})
          recovery (when recover?
                     (try
                       (durable/recover-obligations!
                        storage
                        {:now-ms (clock)})
                       (catch Throwable t
                         {:status :error
                          :error {:code :SCHEDULER_RECOVERY_FAILED
                                  :message (or (.getMessage t) (str t))}})))]
      (if (and recover? (not= :ok (:status recovery)))
        {:ok? false :error (:error recovery)}
        (let [service-ref (atom nil)
              runtime {:service-ref service-ref
                       :storage storage
                       :store durable-store
                       :registry registry
                       :config (or config {})
                       :gateway gateway
                       :clock clock
                       :tick-ms (long tick-ms)
                       :platform (or platform :server)
                       :generation generation
                       :storage-recovery recovery
                       :create-session! create-session!
                       :owner-for owner-for
                       :get-session get-session
                       :branch-session! branch-session!
                       :run-watchers (atom {})
                       :stopped? (atom false)
                       :state (atom {:last-tick-at nil
                                     :last-result nil
                                     :last-error nil
                                     :executor nil})
                       :tick-lock (Object.)}
              start-callback (or start-run! (default-start-run! runtime))
              stop-callback (or stop-run! (default-stop-run! runtime))
              notify-callback (or notify! (default-notify! runtime))
              preflight-callback (or preflight
                                     (default-preflight
                                      {:registry registry :config config}))
              opened (service/open-service
                      {:store durable-store
                       :clock clock
                       :start-run! start-callback
                       :stop-run! stop-callback
                       :notify! notify-callback
                       :preflight preflight-callback
                       :device-zone (or device-zone (sched-time/system-zone))
                       :grace-ms (or grace-ms 60000)
                       :timeout-ms (or timeout-ms 600000)
                       :recovery-reason :process-restart})]
          (if-not (:ok? opened)
            opened
            (let [svc (:service opened)
                  runtime (assoc runtime :service svc)
                  _ (reset! service-ref svc)]
              (try
                (start-timer! runtime)
                {:ok? true
                 :scheduler runtime
                 :store durable-store
                 :storage-recovery recovery
                 :recovery (:recovery opened)}
                (catch Throwable t
                  (service/close! svc)
                  {:ok? false
                   :error {:code :SCHEDULER_TIMER_FAILED
                           :message (or (.getMessage t) (str t))}})))))))))

(defn- await-stop
  [executor timeout-ms]
  (try
    (.awaitTermination ^java.util.concurrent.ExecutorService executor
                       (long timeout-ms) TimeUnit/MILLISECONDS)
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      false)))

(defn stop!
  "Stop intake, wait for the current tick, and release watchers.

  The storage handle stays open; the application owns it and must close it
  after this call.  A tick that does not terminate within the two five-second
  windows is reported as `:stopped? false`; the application then keeps the
  store open instead of closing it under a live writer.  Idempotent."
  [runtime]
  (if-not runtime
    {:stopped? true :terminated? true :timeout? false}
    (let [first-stop? (compare-and-set! (:stopped? runtime) false true)
          ^ScheduledExecutorService executor
          (:executor @(:state runtime))
          terminated?
          (if executor
            (if first-stop?
              (do
                (.shutdown executor)
                (if (await-stop executor 5000)
                  true
                  (do
                    (.shutdownNow executor)
                    (await-stop executor 5000))))
              ;; A retry must observe the real executor state; the first
              ;; call may have timed out with a tick still running.
              (or (.isTerminated executor)
                  (do
                    (.shutdownNow executor)
                    (await-stop executor 5000))))
            true)]
      (when first-stop?
        (doseq [run-id (keys @(:run-watchers runtime))]
          (unsubscribe! runtime run-id))
        (when-let [svc (:service runtime)]
          (service/close! svc)))
      {:stopped? terminated?
       :terminated? terminated?
       :timeout? (not terminated?)})))

(defn- public-result
  "Strip the whole scheduler state from a command result.

  The state belongs to the service and the snapshot endpoint; a CRUD command
  returns only its own schedule/preview/run facts."
  [result]
  (if (and (map? result) (:ok? result))
    (let [public (dissoc result :state :effects)]
      (if (contains? public :receipt)
        public
        (assoc public
               :receipt {:status :accepted
                         :result (dissoc public :ok? :receipt)})))
    result))

(defn tick-now!
  "Run one tick immediately, outside the timer schedule.  Serialized with the
  timer tick."
  [runtime]
  (public-result (tick-once! runtime)))

(defn- freeze-create-spec
  "Freeze the current main model into a new-chat schedule.

  A UI-created schedule may carry only the prompt, recurrence and timezone;
  the model selection is the live main binding.  Saving the schedule paused
  must still record the frozen model and execution, or a later manual run
  fails `SCHEDULE_INCOMPLETE`.  An explicit snapshot model always wins."
  [runtime spec]
  (let [snapshot (:snapshot spec)
        execution (or (:execution spec)
                      (get-in spec [:snapshot :execution])
                      {:mode :new-chat})]
    (if (not= :new-chat (:mode execution))
      spec
      (let [config (try (resolve-config (:config runtime))
                        (catch Throwable _ nil))
            main (get-in config [:models :roles :main])
            connection-id (or (:connection-id main) (:connection main))
            model-id (or (:model-entry-id main) (:model main))
            frozen-model (or (when (and (map? snapshot)
                                        (map? (:model snapshot)))
                               (:model snapshot))
                             (when (and (string? (str connection-id))
                                        (not (str/blank? (str connection-id)))
                                        (string? (str model-id))
                                        (not (str/blank? (str model-id))))
                               {:connection-id (str connection-id)
                                :model-entry-id (str model-id)
                                :display-name (or (:display-name main)
                                                  (:name main)
                                                  (str model-id))}))]
        (assoc spec :snapshot
               (cond-> (merge (or snapshot {})
                              {:execution execution})
                 (:prompt spec) (assoc :prompt (:prompt spec))
                 frozen-model (assoc :model frozen-model)
                 ;; A UI-shaped snapshot may already carry a model but not
                 ;; the capability ceiling.  The ceiling is required for
                 ;; enable and manual run; freeze the spec's value (nil means
                 ;; "follow the current default") so the key is present.
                 (contains? snapshot :capability-scope)
                 (assoc :capability-scope (:capability-scope snapshot))
                 (not (contains? snapshot :capability-scope))
                 (assoc :capability-scope (:capability-scope spec))))))))

(defn create-schedule!
  [runtime spec]
  (public-result (service/create-schedule!
                  (:service runtime)
                  (freeze-create-spec runtime spec))))

(defn update-schedule!
  [runtime schedule-id changes]
  (public-result (service/update-schedule! (:service runtime)
                                           schedule-id changes)))

(defn enable!
  [runtime schedule-id]
  (public-result (service/enable! (:service runtime) schedule-id)))

(defn pause!
  [runtime schedule-id]
  (public-result (service/pause! (:service runtime) schedule-id)))

(defn resume!
  [runtime schedule-id]
  (public-result (service/resume! (:service runtime) schedule-id)))

(defn repair!
  ([runtime schedule-id]
   (public-result (service/repair! (:service runtime) schedule-id)))
  ([runtime schedule-id changes]
   (public-result (service/repair! (:service runtime) schedule-id changes))))

(defn delete-schedule!
  ([runtime schedule-id]
   (public-result (service/delete-schedule! (:service runtime) schedule-id)))
  ([runtime schedule-id opts]
   (public-result (service/delete-schedule! (:service runtime)
                                            schedule-id opts))))

(defn manual-run!
  ([runtime schedule-id]
   (public-result (service/manual-run! (:service runtime) schedule-id)))
  ([runtime schedule-id opts]
   (public-result (service/manual-run! (:service runtime) schedule-id opts))))

(defn preview
  ([runtime schedule-id]
   (service/preview (:service runtime) schedule-id))
  ([runtime schedule-id opts]
   (service/preview (:service runtime) schedule-id opts)))

(defn set-device-zone!
  [runtime zone]
  (public-result (service/set-device-zone! (:service runtime) zone)))

(defn health
  [runtime]
  (let [state @(:state runtime)
        svc (:service runtime)]
    {:running? (not @(:stopped? runtime))
     :tick-ms (:tick-ms runtime)
     :platform (:platform runtime)
     :last-tick-at (:last-tick-at state)
     :last-error (:last-error state)
     :pending-notifications (if svc
                              (mapv :id (service/pending-notifications svc))
                              [])
     :run-watchers (count @(:run-watchers runtime))
     :generation (:generation runtime)}))

(defn snapshot
  [runtime]
  (when-let [svc (:service runtime)]
    (service/snapshot svc)))

(defn schedules
  [runtime]
  (when-let [svc (:service runtime)]
    (service/schedules svc)))

(defn runs
  [runtime]
  (when-let [svc (:service runtime)]
    (service/runs svc)))

(defn schedule
  [runtime schedule-id]
  (when-let [svc (:service runtime)]
    (service/schedule svc schedule-id)))

(defn run
  [runtime run-id]
  (when-let [svc (:service runtime)]
    (service/run svc run-id)))
