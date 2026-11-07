(ns hc.server.browser.service
  "Managed-browser service: navigation, observation, actions and takeover.

  The service is the authority for one browser session's pages, permission
  scope, page generations, page cards and takeover state.  It never talks to
  Chrome directly; every browser effect goes through
  `hc.server.browser.port/BrowserPort`.

  Safety rules that are not configurable:

    * an action is allowed only while the page is `:agent-active`;
    * an action must name the observation it was planned from;
    * the port re-inspects the current DOM before acting, so a navigation,
      popup or operator edit invalidates the old target;
    * a sensitive field is never filled by the agent;
    * an `:outcome-unknown` is reported and never retried;
    * stopping a run does not close pages or erase typed form content.

  Authority: root `AGENTS.md` §7.4 旅程 6.4（开浏览器）, §6.4, E2E 14."
  (:require [clojure.string :as str]
            [hc.server.browser.model :as model]
            [hc.server.browser.port :as port]))

(declare navigate! observe! resume!)

(defn- now
  [svc]
  ((:clock svc)))

(defn- as-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- emit!
  [svc event]
  (when-let [publish (:publish! svc)]
    (try
      (publish (assoc event
                      :type (or (:type event) :browser/event)
                      :session-id (get-in @(:state svc) [:session-id])
                      :at (now svc)))
      (catch Throwable _ nil))))

(defn- failure
  [svc status code message details & [next-step]]
  (let [error (model/browser-error code message details next-step)]
    (emit! svc {:type :error :error error})
    {:status status :error error}))

(defn- port-error
  "Preserve a port's structured error code and a bounded stack.  A bare
  `:port-unavailable` hides which adapter call failed; the stack is capped so
  it stays safe to return in an executor payload."
  [t]
  (let [data (ex-data t)]
    (merge {:code (or (:code data) :port-unavailable)
            :message (or (:message data) (.getMessage t) (str t))
            :class (.getName (class t))
            :stack (mapv str (take 8 (.getStackTrace t)))}
           (select-keys data [:details :outcome]))))

(defn- refusal
  [svc code message details & [next-step]]
  (failure svc :refused code message details next-step))

(defn- tab-check
  "Return `{:tab ... :state ...}` or `{:error ...}`.  A closed tab is a
  normal outcome, not an exception."
  [svc tab-id]
  (let [state @(:state svc)
        tab (model/tab state tab-id)]
    (cond
      (nil? tab)
      {:error (model/browser-error :tab-missing
                                   "The page is not part of this browser session."
                                   {:tab-id tab-id})}

      (= :closed (:status tab))
      {:error (model/browser-error :tab-closed
                                   "The page was closed; its cards remain."
                                   {:tab-id tab-id})}

      (:stopped? state)
      {:error (model/browser-error :run-stopped
                                   "The run stopped; pages remain open for the operator."
                                   {:tab-id tab-id})}

      :else
      {:tab tab :state state})))

(defn- ensure-enabled
  [svc]
  (when-not (get-in @(:state svc) [:permission :enabled?])
    (refusal svc :browser-disabled
             "The operator has not enabled the managed browser for this session."
             {})))

(defn- page-error
  [status]
  (when (integer? status)
    (case (int status)
      401 :login-required
      403 :access-denied
      404 :not-found
      410 :not-found
      nil)))

(defn- append-card!
  [svc card]
  (let [card (assoc card :at (now svc))
        _ (swap! (:state svc) model/record-card! card)]
    (peek (:cards @(:state svc)))))

(defn- capture!
  "Capture a historical view.  A missing screenshot never fails the action;
  the card states why the image is unavailable."
  [svc tab-id]
  (let [captured-at (now svc)]
    (try
      (let [result (port/screenshot! (:port svc) tab-id)]
        (if (= :ok (:status result))
          (if-let [store (:screenshot-store svc)]
            (let [stored (try
                           (store (:bytes result)
                                  {:tab-id tab-id
                                   :mime-type (:mime-type result)
                                   :captured-at captured-at})
                           (catch Throwable t
                             {:status :failed
                              :reason :screenshot-store-error
                              :message (.getMessage t)}))]
              (merge {:kind :historical
                      :live? false
                      :captured-at captured-at}
                     (if (map? stored)
                       stored
                       {:status :stored :blob-id stored})))
            {:kind :historical
             :live? false
             :captured-at captured-at
             :status :unavailable
             :reason :no-screenshot-store})
          {:kind :historical
           :live? false
           :captured-at captured-at
           :status :unavailable
           :reason (or (get-in result [:error :code]) :capture-failed)}))
      (catch Throwable t
        {:kind :historical
         :live? false
         :captured-at captured-at
         :status :unavailable
         :reason :capture-error
         :message (.getMessage t)}))))

(defn- operator-input-coverage
  "Only the top document with no child frames has a complete isolated-world
  operator-input hook.  Any frame, or an unknown frame tree, is uncovered."
  [observation]
  (if (and (true? (:frame-tree-known? observation))
           (zero? (long (or (:child-frames observation) 0)))
           (zero? (long (or (:iframes observation) 0))))
    :top-document-isolated-world
    :uncovered))

(defn- card-limitations
  [observation]
  (let [iframes (:iframes observation 0)
        coverage (operator-input-coverage observation)]
    (cond-> [:screenshot-historical
             :sensitive-detection-best-effort
             :visible-text-only
             :page-content-may-contain-personal-data]
      (pos? (long (or iframes 0))) (conj :iframes-not-read)
      (not= :top-document-isolated-world coverage)
      (conj :operator-input-uncovered))))

(defn- observation-limitations
  [observation]
  (let [iframes (:iframes observation 0)
        coverage (operator-input-coverage observation)]
    (cond-> [:top-document-only
             :sensitive-detection-best-effort
             :visible-text-only
             :page-content-may-contain-personal-data]
      (pos? (long (or iframes 0))) (conj :iframes-not-read)
      (not= :top-document-isolated-world coverage)
      (conj :operator-input-uncovered))))

(defn- reread-result
  "A stale target is a request to read again, not permission to click a
  similar element.  Repeated staleness hands the page to the operator."
  [svc tab-id action target]
  (let [state @(:state svc)
        tab (model/tab state tab-id)
        attempts (inc (long (:reread-count tab 0)))
        max-rereads (long (or (:max-rereads svc) 2))
        repeated? (>= attempts max-rereads)
        error (model/browser-error
               :stale-target
               (if repeated?
                 "The page kept changing; the operator must take over."
                 "The page changed since it was observed; read it again before acting.")
               {:tab-id tab-id :reread-count attempts :max-rereads max-rereads}
               (if repeated?
                 "Ask the operator to take over and point out the intended element."
                 "Read the current page again, then choose a target from that observation."))
        error (assoc error :requires-operator? repeated?)]
    (swap! (:state svc) model/update-tab tab-id assoc :reread-count attempts)
    (when repeated?
      (swap! (:state svc) model/explicit-takeover tab-id :repeated-stale-target))
    (append-card! svc {:action action
                       :tab-id tab-id
                       :site (:site tab)
                       :url (:url tab)
                       :target-summary (model/target-summary target)
                       :result error
                       :reread-required? true
                       :generation (:generation tab)})
    (emit! svc {:type :reread-required :tab-id tab-id
                :attempts attempts :operator-required? repeated?})
    {:status :refused :error error :reread-required? true}))

(defn- sensitive-refusal
  [svc tab-id action target text reason]
  (let [tab (model/tab @(:state svc) tab-id)]
    (swap! (:state svc) model/sensitive-pause tab-id reason)
    (let [error (model/browser-error
                 :sensitive-input-requires-operator
                 "Passwords, one-time codes and payment credentials must be entered by the operator in the visible page."
                 {:tab-id tab-id :reason reason}
                 "Ask the operator to enter the value in the visible page, then resume.")
          card (append-card! svc {:action action
                                  :tab-id tab-id
                                  :site (:site tab)
                                  :url (:url tab)
                                  :target-summary (model/target-summary target)
                                  :action-detail (model/redact-action
                                                  {:target target
                                                   :text text
                                                   :sensitive? true})
                                  :result error
                                  :generation (:generation tab)})]
      (emit! svc {:type :sensitive-pause :tab-id tab-id :reason reason})
      {:status :refused :error error :card card :takeover :sensitive-paused})))

;; ---------------------------------------------------------------------------
;; Permission

(defn permission!
  "Set the session-level browser permission.  Enabling the tool approves
  reading the visible page; changing the receiving model provider clears the
  private-page approval until the operator confirms again."
  [svc {:keys [enabled? scope sites private-read-approved? model-provider]
        :as request}]
  (let [scope (as-keyword scope)]
    (when (and (contains? request :scope)
               (not (contains? #{:current-tab :session-tabs :site} scope)))
      (throw (ex-info "unknown browser permission scope"
                      {:code :INVALID_BROWSER_SCOPE :scope scope})))
    (let [request (if (contains? request :scope) (assoc request :scope scope) request)
          state @(:state svc)
        old (:permission state)
        next (merge old
                    (select-keys request
                                 [:enabled? :scope :private-read-approved?
                                  :model-provider]))
        next (if (contains? request :sites)
               (assoc next :sites
                      (into #{}
                            (keep model/normalize-site)
                            (if (string? sites) [sites] sites)))
               next)
        provider-changed? (and (contains? request :model-provider)
                               (not= (:model-provider old) (:model-provider next)))
        next (cond-> next
               (and provider-changed?
                    (some? (:model-provider next))
                    (not (contains? request :private-read-approved?)))
               (assoc :private-read-approved? false))
        _ (swap! (:state svc) assoc :permission next)
        scope (:scope next)
        active (:active-tab-id @(:state svc))
        _ (if (= :current-tab scope)
            (when active
              (swap! (:state svc) model/grant-tab active))
            (doseq [tab-id (keys (:tabs @(:state svc)))]
              (swap! (:state svc) model/grant-tab tab-id)))]
    (emit! svc {:type :permission-changed
                :permission (model/public-permission @(:state svc))})
    {:status :ok
     :permission (model/public-permission @(:state svc))
     :provider-changed? (boolean provider-changed?)})))

(defn approve-private-read!
  [svc]
  (permission! svc {:private-read-approved? true}))

(defn set-model-provider!
  [svc provider]
  (permission! svc {:model-provider provider}))

;; ---------------------------------------------------------------------------
;; Tabs and navigation

(defn open-tab!
  "Open a page.  A new tab is part of the session only when the browser tool
  is enabled; a URL must already be approved unless `:user-authorized?` is
  true (the operator explicitly asked for it)."
  [svc {:keys [url user-authorized?]}]
  (or (ensure-enabled svc)
      (let [state @(:state svc)
            site (model/site-of url)]
        (cond
          (:stopped? state)
          (refusal svc :run-stopped
                   "The run stopped; ask the operator to reopen the page." {})

          (not= :ready (:operator-input-detection state))
          (refusal svc :operator-input-uncovered
                   "Operator input detection is unavailable; the browser tool is fail-closed."
                   {:operator-input-detection (:operator-input-detection state)})

          (and url (not= "about:blank" url)
               (not user-authorized?)
               (not (model/site-approved? state site)))
          (refusal svc :site-not-approved
                   "The site is not in the browser permission."
                   {:site site :approved-sites (vec (sort (get-in state [:permission :sites])))})

          :else
          (let [result (try
                         (port/open-tab! (:port svc) {:url "about:blank"})
                         (catch Throwable t
                           {:status :failed
                            :error (port-error t)}))]
            (if (= :ok (:status result))
              (let [tab-id (:tab-id result)
                    authorized? (case (get-in state [:permission :scope])
                                  :site (model/site-approved? state site)
                                  true)
                    _ (swap! (:state svc)
                             model/add-tab
                             (merge (select-keys result
                                                 [:tab-id :target-id :url
                                                  :title :document-id])
                                    {:url "about:blank"
                                     :title ""
                                     :agent-authorized? authorized?
                                     :created-at (now svc)}))
                    _ (swap! (:state svc) assoc :active-tab-id tab-id)
                    _ (swap! (:state svc) model/grant-tab tab-id)
                    navigation (when (and url (not= "about:blank" url))
                                 (navigate! svc {:tab-id tab-id
                                                 :url url
                                                 :user-authorized? user-authorized?}))]
                (if (and navigation (not= :ok (:status navigation)))
                  navigation
                  {:status :ok
                   :tab (model/tab @(:state svc) tab-id)
                   :navigation navigation}))
              (failure svc :failed
                       (or (get-in result [:error :code]) :port-unavailable)
                       "The browser adapter could not open a page."
                       {:adapter-error (:error result)})))))))

(defn navigate!
  "Navigate one tab.  The target site must be approved before the agent may
  navigate there; an unapproved jump is refused before any request is sent."
  [svc {:keys [tab-id url user-authorized? screenshot?]
        :or {screenshot? true}}]
  (let [{:keys [tab state error]} (tab-check svc tab-id)]
    (if error
      {:status :refused :error error}
      (let [site (model/site-of url)]
        (cond
          (not (get-in state [:permission :enabled?]))
          (refusal svc :browser-disabled
                   "The operator has not enabled the managed browser." {})

          (not= :ready (:operator-input-detection state))
          (refusal svc :operator-input-uncovered
                   "Operator input detection is unavailable; navigation is refused."
                   {:operator-input-detection (:operator-input-detection state)})

          (and (not user-authorized?)
               (not (model/action-allowed? state tab-id)))
          (refusal svc :takeover-active
                   "The operator controls this page; navigation waits for an explicit resume."
                   {:takeover (:takeover tab)})

          (and (:last-observation tab)
               (not= :top-document-isolated-world
                     (get-in tab [:last-observation :operator-input-coverage])))
          (refusal svc :operator-input-uncovered
                   "This page contains frames whose operator input cannot be monitored; navigation is refused."
                   {:operator-input-coverage
                    (get-in tab [:last-observation :operator-input-coverage])})

          (and (not user-authorized?)
               (not (model/site-approved? state site)))
          (let [error (model/browser-error
                       :site-not-approved
                       "The site is not in the browser permission."
                       {:site site
                        :approved-sites (vec (sort (get-in state [:permission :sites])))})
                card (append-card! svc {:action :navigate
                                        :tab-id tab-id
                                        :site (:site tab)
                                        :url url
                                        :target-summary "page"
                                        :result error
                                        :generation (:generation tab)})]
            {:status :refused :error error :card card})

          :else
          (let [result (try
                         (port/navigate! (:port svc) tab-id url)
                         (catch Throwable t
                           {:status :outcome-unknown
                            :error {:code :navigation-failed
                                    :message (.getMessage t)
                                    :outcome :unknown}}))]
            (case (:status result)
              :ok
              (let [_ (swap! (:state svc)
                             model/page-changed tab-id
                             (select-keys result [:url :title :document-id]))
                    _ (when (model/site-approved? @(:state svc) site)
                        (swap! (:state svc) model/grant-tab tab-id))
                    shot (when screenshot? (capture! svc tab-id))
                    http-error (page-error (:http-status result))
                    _ (when (= :login-required http-error)
                        (swap! (:state svc) model/pause-manual tab-id :login-required))
                    tab' (model/tab @(:state svc) tab-id)
                    card (append-card! svc {:action :navigate
                                            :tab-id tab-id
                                            :site (:site tab')
                                            :url (:url result)
                                            :target-summary "page"
                                            :result (cond-> {:status :ok
                                                             :http-status (:http-status result)}
                                                      http-error (assoc :page-error http-error))
                                            :screenshot shot
                                            :generation (:generation tab')})]
                {:status :ok
                 :tab tab'
                 :navigation (select-keys result [:url :title :document-id
                                                  :http-status :page-error])
                 :page-error http-error
                 :card card})

              :outcome-unknown
              (let [error (or (:error result)
                              (model/browser-error :outcome-unknown
                                                   "Navigation may have started; read the page before retrying."
                                                   {:url url}))
                    card (append-card! svc {:action :navigate
                                            :tab-id tab-id
                                            :site (:site tab)
                                            :url url
                                            :target-summary "page"
                                            :result error
                                            :generation (:generation tab)})]
                {:status :outcome-unknown :error error :card card})

              (let [error (or (:error result)
                              (model/browser-error :navigation-failed
                                                   "The page could not be opened."
                                                   {:url url}))
                    card (append-card! svc {:action :navigate
                                            :tab-id tab-id
                                            :site (:site tab)
                                            :url url
                                            :target-summary "page"
                                            :result error
                                            :generation (:generation tab)})]
                {:status :failed :error error :card card}))))))))

(defn history!
  [svc {:keys [tab-id direction]}]
  (let [direction (as-keyword direction)
        {:keys [tab state error]} (tab-check svc tab-id)]
    (cond
      error
      {:status :refused :error error}

      (not (get-in state [:permission :enabled?]))
      (refusal svc :browser-disabled
               "The operator has not enabled the managed browser." {})

      (not= :ready (:operator-input-detection state))
      (refusal svc :operator-input-uncovered
               "Operator input detection is unavailable; agent actions are refused."
               {:operator-input-detection (:operator-input-detection state)})

      (not (model/action-allowed? state tab-id))
      (refusal svc :takeover-active
               "The operator controls this page; history navigation waits for an explicit resume."
               {:takeover (:takeover tab)})

      (and (:last-observation tab)
           (not= :top-document-isolated-world
                 (get-in tab [:last-observation :operator-input-coverage])))
      (refusal svc :operator-input-uncovered
               "This page contains frames whose operator input cannot be monitored; history is refused."
               {:operator-input-coverage
                (get-in tab [:last-observation :operator-input-coverage])})

      (not (model/tab-authorized? state tab))
      (refusal svc :site-not-approved
               "This tab is outside the current browser permission."
               {:site (:site tab)})

      :else
      (let [result (try
                     (port/history! (:port svc) tab-id direction)
                     (catch Throwable t
                       {:status :failed
                        :error (port-error t)}))]
        (case (:status result)
          :ok
          (let [_ (swap! (:state svc)
                         model/page-changed tab-id
                         (select-keys result [:url :title :document-id]))
                shot (capture! svc tab-id)
                tab' (model/tab @(:state svc) tab-id)
                card (append-card! svc {:action direction
                                        :tab-id tab-id
                                        :site (:site tab')
                                        :url (:url result)
                                        :target-summary "page"
                                        :result {:status :ok}
                                        :screenshot shot
                                        :generation (:generation tab')})]
            {:status :ok :tab tab' :navigation result :card card})

          :outcome-unknown
          (let [error (or (:error result)
                          (model/browser-error
                           :outcome-unknown
                           "History navigation may have started; read the page before retrying."
                           {:direction direction}))
                card (append-card! svc {:action direction
                                        :tab-id tab-id
                                        :site (:site tab)
                                        :url (:url tab)
                                        :target-summary "page"
                                        :result error
                                        :generation (:generation tab)})]
            {:status :outcome-unknown :error error :card card})

          (let [error (or (:error result)
                          (model/browser-error :navigation-failed
                                               "History navigation failed." {}))]
            {:status :failed :error error :card (append-card!
                                                 svc {:action direction
                                                      :tab-id tab-id
                                                      :site (:site tab)
                                                      :url (:url tab)
                                                      :target-summary "page"
                                                      :result error
                                                      :generation (:generation tab)})}))))))

;; ---------------------------------------------------------------------------
;; Observation

(defn- observe*
  [svc {:keys [tab-id record-card? screenshot? allow-paused? allow-private?]
        :or {record-card? true screenshot? true}}]
  (let [{:keys [tab state error]} (tab-check svc tab-id)]
    (cond
      error
      {:status :refused :error error}

      (not (get-in state [:permission :enabled?]))
      (refusal svc :browser-disabled
               "The operator has not enabled the managed browser." {})

      (and (not allow-paused?)
           (not (model/read-allowed? state tab-id)))
      (refusal svc (if (= :read-only (:takeover tab))
                     :read-only
                     :takeover-active)
               "The operator controls this page; reading waits for an explicit resume."
               {:takeover (:takeover tab)
                :takeover-reason (:takeover-reason tab)})

      (not (model/tab-authorized? state tab))
      (refusal svc :site-not-approved
               "This tab is outside the current browser permission."
               {:site (:site tab)})

      :else
      (let [result (try
                     (port/observe! (:port svc) tab-id {})
                     (catch Throwable t
                       {:status :failed
                        :error {:code :unreadable-content
                                :message (.getMessage t)}}))]
        (if (= :ok (:status result))
          (let [nodes (mapv model/normalize-node (:nodes result))
                private? (boolean (or (:login-form? result)
                                      (some :sensitive? nodes)))
                approved? (get-in state [:permission :private-read-approved?])]
            (if (and private? (not approved?) (not allow-private?))
              (let [error (model/browser-error
                           :private-read-confirmation-required
                           "This page looks private; confirm that the model may read it."
                           {:url (:url result)
                            :title (:title result)
                            :login-form? (boolean (:login-form? result))}
                           "Ask the operator to confirm the receiving model may read this page.")
                    card (when record-card?
                           (append-card! svc {:action :observe
                                              :tab-id tab-id
                                              :site (model/site-of (:url result))
                                              :url (:url result)
                                              :target-summary "page"
                                              :result error
                                              :private? true
                                              :generation (:generation tab)}))]
                {:status :refused
                 :error error
                 :observation {:url (:url result)
                               :title (:title result)
                               :document-id (:document-id result)
                               :private? true}
                 :card card})
              (let [generation (if (and (:document-id result)
                                        (not= (:document-id result)
                                              (:document-id tab)))
                                 (do
                                   (swap! (:state svc)
                                          model/page-changed tab-id
                                          (select-keys result [:url :title :document-id]))
                                   (:generation (model/tab @(:state svc) tab-id)))
                                 (:generation tab))
                    observation-id (str "obs-" (:next-observation state 1))
                    coverage (operator-input-coverage result)
                    base-observation {:observation-id observation-id
                                      :tab-id tab-id
                                      :url (:url result)
                                      :title (:title result)
                                      :document-id (:document-id result)
                                      :generation generation
                                      :text (:text result)
                                      :nodes nodes
                                      :login-form? (boolean (:login-form? result))
                                      :private? private?
                                      :iframes (:iframes result 0)
                                      :child-frames (:child-frames result)
                                      :frame-tree-known? (boolean
                                                          (:frame-tree-known? result))
                                      :operator-input-coverage coverage
                                      :http-status (:http-status result)
                                      :captured-at (now svc)}
                    observation (assoc base-observation
                                       :limitations
                                       (observation-limitations base-observation))
                    _ (swap! (:state svc)
                             (fn [s]
                               (-> s
                                   (update :next-observation inc)
                                   (assoc-in [:tabs tab-id :last-observation] observation)
                                   (assoc-in [:tabs tab-id :url] (:url result))
                                   (assoc-in [:tabs tab-id :title] (:title result))
                                   (assoc-in [:tabs tab-id :document-id] (:document-id result))
                                   (assoc-in [:tabs tab-id :generation] generation)
                                   (assoc-in [:tabs tab-id :reread-count] 0))))
                    shot (when screenshot? (capture! svc tab-id))
                    card (when record-card?
                           (append-card! svc {:action :observe
                                              :tab-id tab-id
                                              :site (model/site-of (:url result))
                                              :url (:url result)
                                              :target-summary "page"
                                              :result {:status :ok
                                                       :title (:title result)
                                                       :node-count (count nodes)
                                                       :private? private?}
                                              :screenshot shot
                                              :observation-id observation-id
                                              :generation generation
                                              :limitations (card-limitations observation)}))]
                {:status :ok :observation observation :card card})))
          (let [error (or (:error result)
                          (model/browser-error :unreadable-content
                                               "The page content could not be read." {}))
                card (when record-card?
                       (append-card! svc {:action :observe
                                          :tab-id tab-id
                                          :site (:site tab)
                                          :url (:url tab)
                                          :target-summary "page"
                                          :result error
                                          :generation (:generation tab)}))]
            {:status :failed :error error :card card}))))))

(defn observe!
  "Read the current top document.  The returned observation is the only
  source of targets the agent may later act on.  Internal resume overrides
  are not accepted from a public caller."
  [svc opts]
  (observe* svc (dissoc opts :allow-paused? :allow-private?)))

;; ---------------------------------------------------------------------------
;; Actions

(defn- action-card
  [svc tab action target text result shot observation-id]
  (append-card! svc (cond-> {:action action
                             :tab-id (:tab-id tab)
                             :site (:site tab)
                             :url (:url tab)
                             :target-summary (model/target-summary target)
                             :action-detail (model/redact-action
                                             {:target target :text text})
                             :result result
                             :observation-id observation-id
                             :generation (:generation tab)}
                      shot (assoc :screenshot shot))))

(defn act!
  "Perform one action against a target from the latest observation.  The
  adapter re-inspects the DOM immediately before acting.  A stale target is
  refused; the service does not click a name-similar element."
  [svc {:keys [tab-id action target text observation-id external-effect? screenshot?
               key delta-y]
        :or {screenshot? true}}]
  (let [action (keyword action)
        {:keys [tab state error]} (tab-check svc tab-id)]
    (cond
      error
      {:status :refused :error error}

      (not (get-in state [:permission :enabled?]))
      (refusal svc :browser-disabled
               "The operator has not enabled the managed browser." {})

      (not= :ready (:operator-input-detection state))
      (refusal svc :operator-input-uncovered
               "Operator input detection is unavailable; agent actions are refused."
               {:operator-input-detection (:operator-input-detection state)})

      (not (model/action-allowed? state tab-id))
      (let [code (if (= :read-only (:takeover tab)) :read-only :takeover-active)
            error (model/browser-error
                   code
                   "The operator controls this page; no agent action will run."
                   {:takeover (:takeover tab)
                    :takeover-reason (:takeover-reason tab)})]
        (append-card! svc {:action action
                           :tab-id tab-id
                           :site (:site tab)
                           :url (:url tab)
                           :target-summary (model/target-summary target)
                           :action-detail (model/redact-action
                                           {:target target :text text})
                           :result error
                           :generation (:generation tab)})
        {:status :refused :error error})

      (not (model/tab-authorized? state tab))
      (refusal svc :site-not-approved
               "This tab is outside the current browser permission."
               {:site (:site tab)})

      (not (contains? model/action-kinds action))
      (refusal svc :invalid-argument
               "Unknown browser action."
               {:action action :allowed (vec (sort model/action-kinds))})

      (nil? observation-id)
      (reread-result svc tab-id action target)

      (not= observation-id (get-in tab [:last-observation :observation-id]))
      (reread-result svc tab-id action target)

      (not= :top-document-isolated-world
            (get-in tab [:last-observation :operator-input-coverage]))
      (refusal svc :operator-input-uncovered
               "This page contains frames whose operator input cannot be monitored; agent actions are refused."
               {:operator-input-coverage
                (get-in tab [:last-observation :operator-input-coverage])})

      :else
      (let [targetless? (or (= action :scroll)
                            (and (= action :press) (nil? target)))
            inspect (try
                      (if targetless?
                        (port/page-info! (:port svc) tab-id)
                        (port/inspect! (:port svc) tab-id target))
                      (catch Throwable t
                        {:status :failed
                         :error {:code :unreadable-content
                                 :message (.getMessage t)}}))]
        (cond
          (not= :ok (:status inspect))
          (let [code (or (get-in inspect [:error :code]) :stale-target)]
            (if (= :stale-target code)
              (reread-result svc tab-id action target)
              (let [error (or (:error inspect)
                              (model/browser-error :unreadable-content
                                                   "The page could not be inspected." {}))
                    card (append-card! svc {:action action
                                            :tab-id tab-id
                                            :site (:site tab)
                                            :url (:url tab)
                                            :target-summary (model/target-summary target)
                                            :result error
                                            :generation (:generation tab)})]
                {:status :failed :error error :card card})))

          (not= (:document-id inspect)
                (get-in tab [:last-observation :document-id]))
          (reread-result svc tab-id action target)

          (and (= action :type)
               (or (model/sensitive-target? target)
                   (model/sensitive-target? (:target inspect))))
          (sensitive-refusal svc tab-id action target text
                             :sensitive-field)

          (and (= action :press)
               (= "Enter" (str key))
               (or (model/sensitive-target? (:target inspect))
                   (:sensitive-form? inspect)))
          (sensitive-refusal svc tab-id action target text
                             :sensitive-form-submit)

          (and (= action :submit) (:sensitive-form? inspect))
          (sensitive-refusal svc tab-id action target text
                             :sensitive-form)

          (and (= action :click)
               (:sensitive-form? inspect)
               (let [inspected-target (:target inspect)]
                 (or (= "submit" (:type inspected-target))
                     (and (= "button" (:tag inspected-target))
                          (nil? (:type inspected-target))))))
          (sensitive-refusal svc tab-id action target text
                             :sensitive-form-submit)

          :else
          (let [external-effect? (or (boolean external-effect?)
                                     (= action :submit)
                                     (and (= action :press) (= "Enter" (str key)))
                                     (= "submit" (:type target))
                                     (= "submit" (:type (:target inspect))))
                port-action (cond-> {:kind action :target target}
                              (some? text) (assoc :text text)
                              (some? key) (assoc :key key)
                              (some? delta-y) (assoc :delta-y delta-y)
                              external-effect? (assoc :external-effect? true))
                result (try
                         (port/act! (:port svc) tab-id port-action)
                         (catch Throwable t
                           {:status (if (#{:click :type :press :submit} action)
                                      :outcome-unknown
                                      :failed)
                            :error (assoc (port-error t)
                                          :outcome
                                          (if (#{:click :type :press :submit} action)
                                            :unknown
                                            :none))}))
                error-code (get-in result [:error :code])
                unprotected? (contains? #{:operator-input-unprotected
                                          :operator-input-uncovered}
                                        error-code)
                _ (when unprotected?
                    (swap! (:state svc)
                           model/pause-manual tab-id error-code)
                    (emit! svc {:type :operator-input-uncovered
                                :tab-id tab-id
                                :reason error-code}))
                current-tab (model/tab @(:state svc) tab-id)]
            (case (:status result)
              :ok
              (if (not= :agent-active (:takeover current-tab))
                (let [error (model/browser-error
                             :takeover-active
                             "The operator took the page during the action; verify the page before continuing."
                             {:takeover (:takeover current-tab)})
                      shot (when screenshot? (capture! svc tab-id))
                      card (action-card svc current-tab action target text
                                        error shot observation-id)]
                  {:status :outcome-unknown
                   :error error
                   :card card
                   :tab current-tab})
                (let [_ (when (and (:navigated? result)
                                   (:document-id result)
                                   (not= (:document-id result)
                                         (:document-id current-tab)))
                          (swap! (:state svc)
                                 model/page-changed tab-id
                                 (select-keys result [:url :title :document-id])))
                      tab' (model/tab @(:state svc) tab-id)
                      shot (when screenshot? (capture! svc tab-id))
                      card (action-card svc tab' action target text
                                        {:status :ok
                                         :navigated? (boolean (:navigated? result))
                                         :external-effect? (boolean
                                                            (or external-effect?
                                                                (:external-effect? result)))
                                         :document-id (:document-id result)}
                                        shot observation-id)]
                  {:status :ok
                   :result (select-keys result [:document-id :navigated? :url :title])
                   :card card
                   :tab tab'}))

              :outcome-unknown
              (let [error (or (:error result)
                              (model/browser-error
                               :outcome-unknown
                               "The action may have reached the page; verify before retrying."
                               {:action action}))
                    shot (when screenshot? (capture! svc tab-id))
                    card (action-card svc current-tab action target text
                                      error shot observation-id)]
                {:status :outcome-unknown :error error :card card})

              (let [error (or (:error result)
                              (model/browser-error
                               (if (= :blocked-by-page (:status result))
                                 :blocked-by-page
                                 :navigation-failed)
                               "The action did not complete."
                               {:action action}))
                    shot (when screenshot? (capture! svc tab-id))
                    card (action-card svc current-tab action target text
                                      error shot observation-id)]
                {:status :failed :error error :card card}))))))))

;; ---------------------------------------------------------------------------
;; Takeover

(defn takeover!
  "The operator explicitly takes the page.  All agent input stops at once;
  the page, typed content and cards remain."
  [svc {:keys [tab-id]}]
  (let [state @(:state svc)
        tab (model/tab state tab-id)]
    (cond
      (nil? tab)
      (refusal svc :tab-missing "The page is not part of this browser session."
               {:tab-id tab-id})

      (= :closed (:status tab))
      (refusal svc :tab-closed "The page was closed; its cards remain."
               {:tab-id tab-id})

      :else
      (do
        (swap! (:state svc) model/explicit-takeover tab-id :operator-request)
        (emit! svc {:type :takeover :tab-id tab-id})
        {:status :ok
         :takeover :operator
         :tab (model/tab @(:state svc) tab-id)
         :note "Agent input is blocked until an explicit resume."}))))

(defn operator-input!
  "The operator typed, clicked or navigated in the controlled page.  The
  agent pauses immediately; no queued action is allowed through."
  [svc {:keys [tab-id kind summary url]}]
  (let [kind (as-keyword kind)
        state @(:state svc)
        tab (model/tab state tab-id)]
    (cond
      (nil? tab)
      (refusal svc :tab-missing "The page is not part of this browser session."
               {:tab-id tab-id})

      (= :closed (:status tab))
      (refusal svc :tab-closed "The page was closed; its cards remain."
               {:tab-id tab-id})

      :else
      (let [_ (swap! (:state svc) model/pause-manual tab-id (or kind :input))
            _ (when (and (= kind :navigation) url)
                (swap! (:state svc)
                       model/page-changed tab-id
                       {:url url}))
            tab' (model/tab @(:state svc) tab-id)]
        (emit! svc {:type :operator-input
                    :tab-id tab-id
                    :kind kind
                    :summary summary})
        {:status :ok
         :takeover (:takeover tab')
         :tab tab'
         :note "Detected manual operation; waiting for an explicit resume."}))))

(defn resume!
  "Hand the page back after an explicit operator decision.  The service
  reads the current page first and returns that observation.  Old targets
  are invalid even if the URL looks unchanged."
  [svc {:keys [tab-id mode] :or {mode :agent}}]
  (let [mode (as-keyword mode)
        {:keys [tab error]} (tab-check svc tab-id)]
    (cond
      error
      {:status :refused :error error}

      (not (contains? #{:paused-manual :operator :sensitive-paused :read-only}
                      (:takeover tab)))
      (refusal svc :invalid-argument
               "The page is not paused; there is nothing to resume."
               {:takeover (:takeover tab)})

      :else
      (let [observed (observe* svc {:tab-id tab-id
                                    :record-card? false
                                    :screenshot? false
                                    :allow-paused? true})]
        (if (= :ok (:status observed))
          (let [observation (:observation observed)
                safe-mode (if (and (= mode :agent)
                                   (or (not= :ready
                                             (:operator-input-detection
                                              @(:state svc)))
                                       (not= :top-document-isolated-world
                                             (:operator-input-coverage
                                              observation))))
                            :read-only
                            mode)
                _ (swap! (:state svc) model/resume tab-id safe-mode observation)
                tab' (model/tab @(:state svc) tab-id)
                ;; `model/resume` stamps the observation with the new page
                ;; generation.  Return that stamped value, not the pre-resume
                ;; copy, so the client never displays an old generation.
                observation' (:last-observation tab')
                card (append-card! svc {:action :resume
                                        :tab-id tab-id
                                        :site (:site tab')
                                        :url (:url tab')
                                        :target-summary "page"
                                        :result {:status :ok
                                                 :mode safe-mode
                                                 :reread? true}
                                        :observation-id (:observation-id observation')
                                        :generation (:generation tab')})]
            (emit! svc {:type :resume :tab-id tab-id :mode safe-mode})
            {:status :ok
             :takeover (:takeover tab')
             :observation observation'
             :mode safe-mode
             :downgraded? (not= mode safe-mode)
             :card card
             :reread? true})
          (do
            (append-card! svc {:action :resume
                               :tab-id tab-id
                               :site (:site tab)
                               :url (:url tab)
                               :target-summary "page"
                               :result (:error observed)
                               :generation (:generation tab)})
            {:status :failed
             :error (:error observed)
             :takeover (:takeover (model/tab @(:state svc) tab-id))}))))))

;; ---------------------------------------------------------------------------
;; Tabs, stop and reports

(defn close-tab!
  [svc {:keys [tab-id]}]
  (let [state @(:state svc)
        tab (model/tab state tab-id)]
    (if (or (nil? tab) (= :closed (:status tab)))
      (refusal svc :tab-missing "The page is not open."
               {:tab-id tab-id})
      (let [result (try
                     (port/close-tab! (:port svc) tab-id)
                     (catch Throwable t
                       {:status :failed
                        :error (port-error t)}))]
        (if (= :ok (:status result))
          (do
            (swap! (:state svc) model/close-tab tab-id)
            (emit! svc {:type :tab-closed :tab-id tab-id})
            {:status :ok
             :tab (model/tab @(:state svc) tab-id)
             :note "Closing the page does not stop the session or erase its cards."})
          (failure svc :failed
                   (or (get-in result [:error :code]) :close-failed)
                   "The page could not be confirmed closed; it may still be open."
                   {:adapter-error (:error result)}))))))

(defn stop!
  "Stop agent work.  This never closes pages and never clears unsent form
  content."
  [svc]
  (swap! (:state svc) model/stop)
  (emit! svc {:type :stopped})
  (let [state @(:state svc)]
    {:status :ok
     :stopped? true
     :open-tabs (count (filter #(= :open (:status %)) (vals (:tabs state))))
     :note "Stop does not close pages or clear unsent form content."}))

(defn shutdown!
  "Release adapter connections.  A port connected to the operator's shared
  browser does not close it."
  [svc]
  (when-let [unsubscribe (:operator-unsubscribe svc)]
    (try (unsubscribe) (catch Throwable _ nil)))
  (swap! (:state svc) assoc :shutdown? true)
  (try
    (port/close! (:port svc))
    (catch Throwable _ nil))
  (emit! svc {:type :shutdown})
  {:status :ok})

(defn health
  "Small, serializable browser health projection for `/healthz` and
  diagnostics.  It never claims a live connection after `shutdown!`."
  [svc]
  (let [state @(:state svc)
        tabs (vals (:tabs state))
        open-tabs (count (filter #(= :open (:status %)) tabs))
        closed-tabs (count (filter #(= :closed (:status %)) tabs))]
    {:connected? (and (not (boolean (:offline? svc)))
                      (not (boolean (:shutdown? state))))
     :configured? (not (boolean (:offline? svc)))
     :offline? (boolean (:offline? svc))
     :stopped? (boolean (:stopped? state))
     :permission (model/public-permission state)
     :tabs {:total (count tabs) :open open-tabs :closed closed-tabs}
     :takeover (frequencies (map :takeover tabs))
     :operator-input
     {:detection (:operator-input-detection state)
      :uncovered-tabs
      (count (filter #(not= :top-document-isolated-world
                            (get-in % [:last-observation
                                       :operator-input-coverage]))
                     tabs))}
     :cards {:count (count (:cards state))
             :dropped (long (:cards-dropped state 0))
             :truncated? (pos? (long (:cards-dropped state 0)))}
     :limits (:limits state)}))

(defn snapshot
  [svc]
  (cond-> @(:state svc)
    (:offline? svc) (assoc :offline? true)))

(defn cards
  [svc]
  (model/cards-view @(:state svc)))

(defn report
  [svc]
  (model/report @(:state svc)))

(defn report-text
  [svc]
  (model/report-text @(:state svc)))

(defn- handle-detected-operator-input!
  "A real trusted input event arrived from the controlled page.  Pause only
  when the page is still agent-active or read-only; an already paused or
  operator-controlled page is left alone."
  [svc {:keys [tab-id kind]}]
  (let [state @(:state svc)
        tab (model/tab state tab-id)]
    (when (and tab
               (= :open (:status tab))
               (contains? #{:agent-active :read-only} (:takeover tab)))
      (operator-input! svc {:tab-id tab-id
                            :kind (or kind :input)
                            :summary "检测到页面手动操作"}))))

(defn service
  "Build one managed-browser service.

  Required: `:port`, a `BrowserPort`.

  Optional:
  - `:clock` (fn [] epoch-ms)
  - `:publish!` (fn [event]) for the Owner/UI event sink
  - `:screenshot-store` (fn [bytes opts] -> blob-id | {:blob-id ...})
  - `:limits` extra honest-limit entries
  - `:session-id`
  - `:permission` initial permission map
  - `:max-cards` default 200
  - `:max-rereads` default 2 before the operator must take over
  - `:offline?` true for a permission-only service without a CDP port"
  [{:keys [port clock publish! screenshot-store limits session-id permission
           max-cards max-rereads offline?]
    :or {clock #(System/currentTimeMillis) max-cards 200 max-rereads 2}}]
  (when-not port
    (throw (ex-info "managed browser service requires a :port"
                    {:code :BROWSER_PORT_REQUIRED})))
  (let [svc {:port port
             :clock clock
             :publish! publish!
             :screenshot-store screenshot-store
             :offline? (boolean offline?)
             :max-rereads (max 1 (long max-rereads))
             :state (atom (model/initial-state
                           {:session-id session-id
                            :permission permission
                            :limits limits
                            :max-cards max-cards}))}
        _ (when offline?
            (swap! (:state svc) assoc :offline? true))]
    (let [subscription
          (try
            (port/subscribe-operator-input!
             port
             (fn [event] (handle-detected-operator-input! svc event)))
            (catch Throwable t
              {:ok? false
               :error {:code :operator-input-unavailable
                       :message (or (.getMessage t) (str t))}}))
          ready? (true? (:ok? subscription))]
      (swap! (:state svc) assoc
             :operator-input-detection (if ready? :ready :unavailable))
      (assoc svc
             :operator-unsubscribe (:unsubscribe subscription)
             :operator-input-detection (if ready? :ready :unavailable)))))
