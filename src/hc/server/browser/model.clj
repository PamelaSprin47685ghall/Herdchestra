(ns hc.server.browser.model
  "Pure managed-browser state, takeover rules and honest reports.

  Authority: root `AGENTS.md` §7.4 旅程 6.4（开浏览器）, §6.4 and E2E 14.

  Two boundaries are deliberately pure so they can be tested without a
  browser:

    * takeover is object-level: one page can be under operator control while
      another page remains agent-active;
    * a target observed on one document generation is never reused after a
      navigation, operator input, takeover or resume.  The caller must read
      the page again.

  The model does not know about Chrome, WebSockets or JSON."
  (:require [clojure.string :as str])
  (:import [java.net URI]))

(def takeover-states
  "`:agent-active` may act and read.  `:read-only` may read but not act.
  `:paused-manual`, `:operator` and `:sensitive-paused` may do neither until
  an explicit resume."
  #{:agent-active :paused-manual :operator :read-only :sensitive-paused})

(def action-kinds
  "Actions the service will send to a port.  `:observe` is not an action."
  #{:click :type :scroll :press :submit})

(def sensitive-field-kinds
  #{:password :one-time-code :payment-card :card-number :cvc :pin
    :secret-question :credential})

(def ^:private sensitive-type-pattern
  #"(?i)^(password|passwd|pwd)$")

(def ^:private sensitive-name-pattern
  #"(?i)(^|[^a-z])(pass(word|wd)?|pwd|otp|totp|one[-_]?time|captcha|verif(y|ication)[-_]?code|security[-_]?code|cvv|cvc|card[-_]?number|credit[-_]?card|ssn|social[-_]?security|secret|pin)([^a-z]|$)")

(def ^:private sensitive-autocomplete-pattern
  #"(?i)(current-password|new-password|one-time-code|cc-number|cc-csc|cc-exp|cc-name|cc-type|webauthn)")

(defn- lower
  [value]
  (when (string? value) (str/lower-case value)))

(defn sensitive-target?
  "Best-effort classification of a field that must be filled by the
  operator in the visible page.

  The check is intentionally conservative: a false positive only asks the
  operator to type; a false negative is the failure mode the product admits
  in its limits.  Detection uses type, autocomplete, field kind, and the
  name/id/label/placeholder text."
  [target]
  (let [{:keys [sensitive? field-kind type autocomplete id label placeholder
                field-name]} target
        haystack (str/join " " (remove str/blank? [field-name (:name target) id label placeholder]))]
    (boolean
     (or (true? sensitive?)
         (contains? sensitive-field-kinds
                    (if (string? field-kind) (keyword field-kind) field-kind))
         (and type (re-find sensitive-type-pattern (str type)))
         (and autocomplete
              (re-find sensitive-autocomplete-pattern (str autocomplete)))
         (and (not (str/blank? haystack))
              (re-find sensitive-name-pattern haystack))))))

(defn normalize-node
  "Add the sensitivity classification to one observed node."
  [node]
  (let [node (select-keys node
                          [:ref :tag :type :field-name :id :autocomplete :label
                           :placeholder :disabled :checked :visible? :rect])]
    (assoc node :sensitive? (sensitive-target? node))))

(defn site-of
  "The real host shown in the address bar, lower-cased, without a leading
  `www.`.  Returns nil for `about:`, `file:` and malformed addresses."
  [url]
  (try
    (let [host (some-> (URI/create (str url)) .getHost str/lower-case)]
      (when-not (str/blank? host)
        (str/replace host #"^www\." "")))
    (catch Throwable _ nil)))

(defn normalize-site
  "Normalize one approved site to the same host form `site-of` reports.

  Accepts a bare host, a host with a port, or a full URL.  Lower-cases and
  strips a leading `www.`; returns nil for a blank or malformed value."
  [value]
  (let [value (some-> value str str/trim)]
    (when-not (str/blank? value)
      (try
        (let [uri (URI/create (if (re-find #"^[A-Za-z][A-Za-z0-9+.-]*://" value)
                                value
                                (str "http://" value)))
              host (some-> (.getHost uri) str/lower-case)]
          (when-not (str/blank? host)
            (str/replace host #"^www\." "")))
        (catch Throwable _ nil)))))

(defn- public-target
  [target]
  (select-keys target [:ref :tag :type :field-name :id :label :placeholder
                       :sensitive? :field-kind :autocomplete]))

(defn redact-action
  "Describe an action without retaining the value typed into a field.

  Non-sensitive short text keeps a preview because the operator is supposed
  to review ordinary drafts.  Sensitive text is removed completely; only its
  length remains."
  [action]
  (let [target (:target action)
        sensitive? (or (sensitive-target? target)
                       (true? (:sensitive? action)))
        text (str (or (:text action) ""))
        preview? (and (not sensitive?)
                      (<= (count text) 80)
                      (not (str/blank? text)))]
    (cond-> (-> action
                (dissoc :text)
                (assoc :target (public-target target)
                       :sensitive? (boolean sensitive?)
                       :text-length (count text)
                       :text-redacted? true))
      preview? (assoc :text-preview text))))

(defn target-summary
  [target]
  (when target
    (let [label (or (:label target) (:field-name target) (:id target)
                    (:ref target))
          tag (:tag target)
          kind (or (:field-kind target) (:type target))]
      (str/join " "
                (remove str/blank?
                        [(some-> tag name)
                         (some-> kind name)
                         (some-> label str (subs 0 (min 60 (count (str label)))))])))))

(def default-limits
  "What the managed browser does not claim.  Every report carries this map."
  {:sensitive-detection :best-effort
   :screenshot :historical-not-live
   :observation :top-document-visible-text
   :iframes :not-read
   :operator-input :isolated-world-top-document-only
   :operator-input-uncovered :fail-closed
   :coordinates :resolved-against-current-dom
   :external-browser :not-controlled
   :permission :ephemeral-not-inherited
   :outcome-unknown :no-auto-retry
   :page-content :may-contain-personal-data})

(def next-steps
  {:connection-failed "Check the address and network, then ask to retry once."
   :certificate-error "Open the site manually and verify its certificate; do not bypass it."
   :login-required "Complete the login in the visible page, then resume the assistant."
   :not-found "Check the address or ask the operator for the correct page."
   :access-denied "The site refused access; open it manually or ask the operator."
   :unreadable-content "The page content could not be read; ask the operator to inspect it."
   :blocked-by-page "The page prevented the action; stop and ask the operator."
   :stale-target "Read the page again before choosing a target."
   :ambiguous-target "Ask the operator to identify the intended element."
   :sensitive-input-requires-operator "Ask the operator to enter the value in the visible page."
   :takeover-active "Wait for the operator to hand the page back."
   :read-only "The operator allowed reading only; do not act."
   :permission-required "Ask the operator to extend the browser permission."
   :site-not-approved "Ask the operator to approve this site for the session."
   :private-read-confirmation-required "Ask the operator to confirm the model may read this private page."
   :tab-closed "The page was closed; ask the operator to reopen it."
   :tab-missing "The page is not part of this browser session."
   :browser-disabled "The operator has not enabled the managed browser."
   :run-stopped "The run stopped; pages remain open for the operator."
   :outcome-unknown "Do not retry automatically; open the page and verify the result."
   :navigation-timeout "The page did not finish loading; read the current page before acting."
   :navigation-failed "The page could not be opened; check the address."
   :close-failed "Check whether the page is still open before retrying."
   :history-boundary "There is no further page in that direction."
   :invalid-argument "Correct the request and try again."
   :port-unavailable "The browser adapter is unavailable."
   :operator-input-uncovered "Ask the operator to take over; this page contains frames whose input cannot be monitored."
   :operator-input-unprotected "Stop agent actions and ask the operator to take over; input detection is not reliable on this page."
   :executor-error "The browser adapter failed; report the error instead of retrying."})

(defn browser-error
  "Build one structured browser error.  The next step is always explicit so
  a model cannot turn an unknown into a blind retry."
  ([code message]
   (browser-error code message {} nil))
  ([code message details]
   (browser-error code message details nil))
  ([code message details next-step]
   {:code code
    :message message
    :details (or details {})
    :next-step (or next-step (get next-steps code))
    :retryable? false
    :requires-operator?
    (contains? #{:sensitive-input-requires-operator :takeover-active
                 :permission-required :site-not-approved
                 :private-read-confirmation-required :login-required
                 :access-denied :tab-closed :browser-disabled
                 :certificate-error :blocked-by-page
                 :operator-input-uncovered :operator-input-unprotected}
               code)}))

(defn initial-state
  [{:keys [session-id permission limits max-cards]
    :or {max-cards 200}}]
  {:session-id session-id
   :tabs {}
   :active-tab-id nil
   :permission (merge {:enabled? false
                       :scope :current-tab
                       :sites #{}
                       :private-read-approved? false
                       :model-provider nil}
                      permission)
   :cards []
   :next-card 1
   :next-observation 1
   :cards-dropped 0
   :events []
   :stopped? false
   :limits (merge default-limits limits)
   :max-cards (max 1 (long (or max-cards 200)))})

(defn tab
  [state tab-id]
  (get-in state [:tabs tab-id]))

(defn update-tab
  [state tab-id f & args]
  (apply update-in state [:tabs tab-id] f args))

(defn add-tab
  "Install a freshly opened page.  `:agent-authorized?` is decided by the
  service from the permission scope."
  [state {:keys [tab-id url title document-id agent-authorized? created-at]
          :as tab}]
  (let [tab (merge {:tab-id tab-id
                    :url url
                    :title title
                    :site (site-of url)
                    :document-id document-id
                    :generation 0
                    :takeover :agent-active
                    :takeover-reason nil
                    :read-sharing? true
                    :status :open
                    :agent-authorized? (boolean agent-authorized?)
                    :last-observation nil
                    :created-at created-at}
                   tab)]
    (-> state
        (assoc-in [:tabs tab-id] tab)
        (update :active-tab-id #(or % tab-id)))))

(defn site-approved?
  [state site]
  (contains? (get-in state [:permission :sites]) site))

(defn tab-authorized?
  "True when the model may read this tab under the current permission."
  [state tab]
  (let [permission (:permission state)
        scope (:scope permission)]
    (boolean
     (and tab
          (:enabled? permission)
          (case scope
            :current-tab (true? (:agent-authorized? tab))
            :session-tabs (true? (:agent-authorized? tab))
            :site (and (contains? (:sites permission) (:site tab))
                       (not (:manual-only? tab)))
            false)))))

(defn grant-tab
  "Mark a tab authorized according to the current scope.  `:current-tab`
  authorizes exactly one tab; `:session-tabs` authorizes every open tab;
  `:site` authorizes a tab whose site is already approved."
  [state tab-id]
  (let [scope (get-in state [:permission :scope])]
    (case scope
      :current-tab
      (-> state
          (update :tabs
                  (fn [tabs]
                    (into {}
                          (map (fn [[id tab]]
                                 [id (assoc tab :agent-authorized? (= id tab-id))]))
                          tabs)))
          (assoc-in [:tabs tab-id :agent-authorized?] true))

      :session-tabs
      (update state :tabs
              (fn [tabs] (into {} (map (fn [[id tab]]
                                         [id (assoc tab :agent-authorized? true)]))
                               tabs)))

      :site
      (let [site (get-in state [:tabs tab-id :site])]
        (if (site-approved? state site)
          (assoc-in state [:tabs tab-id :agent-authorized?] true)
          state))

      state)))

(defn page-changed
  "Record a document change.  The generation increments and every observed
  target becomes stale.  Takeover state is unchanged: a navigation during
  agent activity is still agent activity."
  [state tab-id {:keys [url title document-id]}]
  (update-tab
   state tab-id
   (fn [tab]
     (let [next-url (or url (:url tab))
           next-site (site-of next-url)
           tab' (cond-> (assoc tab
                               :document-id document-id
                               :generation (inc (long (:generation tab 0)))
                               :last-observation nil)
                  url (assoc :url url :site next-site)
                  title (assoc :title title))
           scope (get-in state [:permission :scope])
           authorized? (cond
                         (not (site-approved? state next-site)) false
                         (= :site scope) true
                         (= :session-tabs scope) true
                         (= :current-tab scope) (= tab-id (:active-tab-id state))
                         :else (:agent-authorized? tab))]
       (assoc tab' :agent-authorized? (boolean authorized?))))))

(defn- record-event
  [state event]
  (update state :events
          (fn [events] (vec (take-last 64 (conj (or events []) event))))))

(defn record-card!
  "Append one ordered page card.  Old cards are dropped only at the configured
  bound; the dropped count is retained so a report never claims completeness."
  [state card]
  (let [n (long (:next-card state 1))
        card (merge {:card-id (str "card-" n)
                     :seq n
                     :screenshot {:kind :historical :live? false}}
                    card)
        cards (conj (vec (:cards state)) card)
        max-cards (long (:max-cards state 200))
        overflow (max 0 (- (count cards) max-cards))
        kept (if (pos? overflow) (subvec cards overflow) cards)]
    (-> state
        (assoc :cards kept
               :next-card (inc n)
               :cards-dropped (+ (long (:cards-dropped state 0)) overflow))
        (record-event {:type :card-recorded :card-id (:card-id card)
                       :action (:action card)}))))

(defn cards-view
  [state]
  {:cards (vec (:cards state))
   :dropped (long (:cards-dropped state 0))
   :truncated? (pos? (long (:cards-dropped state 0)))
   :limits (select-keys (:limits state) [:screenshot :sensitive-detection])})

(defn pause-manual
  "The operator typed, clicked or navigated in the controlled page.  The
  service stops queued work immediately; it never resumes on focus, blur or
  a timer."
  [state tab-id reason]
  (-> (update-tab
       state tab-id
       (fn [tab]
         (let [next-takeover (if (contains? #{:operator :read-only} (:takeover tab))
                               :operator
                               :paused-manual)]
           (assoc tab
                  :takeover next-takeover
                  :takeover-reason reason
                  :read-sharing? false
                  :generation (inc (long (:generation tab 0)))
                  :last-observation nil))))
      (record-event {:type :operator-input :tab-id tab-id :reason reason})))

(defn explicit-takeover
  [state tab-id reason]
  (-> (update-tab
       state tab-id
       (fn [tab]
         (assoc tab
                :takeover :operator
                :takeover-reason (or reason :operator-request)
                :read-sharing? false
                :generation (inc (long (:generation tab 0)))
                :last-observation nil)))
      (record-event {:type :takeover :tab-id tab-id :reason reason})))

(defn sensitive-pause
  [state tab-id reason]
  (-> (update-tab
       state tab-id
       (fn [tab]
         (assoc tab
                :takeover :sensitive-paused
                :takeover-reason reason
                :read-sharing? false
                :generation (inc (long (:generation tab 0)))
                :last-observation nil)))
      (record-event {:type :sensitive-pause :tab-id tab-id :reason reason})))

(defn resume
  "Hand a paused page back to the agent after a fresh observation.  MODE is
  `:agent` or `:read-only`.  The observation is stamped with the new
  generation; the old one is gone."
  [state tab-id mode observation]
  (update-tab
   state tab-id
   (fn [tab]
     (let [generation (inc (long (:generation tab 0)))
           takeover (if (= :read-only mode) :read-only :agent-active)]
       (assoc tab
              :takeover takeover
              :takeover-reason nil
              :read-sharing? true
              :generation generation
              :last-observation (when observation
                                  (assoc observation :generation generation))
              :reread? true)))))

(defn read-allowed?
  [state tab-id]
  (let [tab (tab state tab-id)]
    (boolean
     (and tab
          (not (:stopped? state))
          (= :open (:status tab))
          (contains? #{:agent-active :read-only} (:takeover tab))
          (:read-sharing? tab)))))

(defn action-allowed?
  [state tab-id]
  (let [tab (tab state tab-id)]
    (boolean
     (and tab
          (not (:stopped? state))
          (= :open (:status tab))
          (= :agent-active (:takeover tab))))))

(defn close-tab
  [state tab-id]
  (-> (update-tab state tab-id
                  (fn [tab]
                    (assoc tab
                           :status :closed
                           :takeover :operator
                           :takeover-reason :tab-closed
                           :read-sharing? false
                           :generation (inc (long (:generation tab 0)))
                           :last-observation nil)))
      (record-event {:type :tab-closed :tab-id tab-id})))

(defn stop
  "Stop agent work without closing pages or erasing typed form content."
  [state]
  (-> (assoc state :stopped? true)
      (record-event {:type :stopped})))

(defn public-permission
  [state]
  (let [permission (:permission state)]
    (-> permission
        (update :sites #(vec (sort %)))
        (assoc :persisted? false))))

(defn report
  "Structured report for the workbench.  It names every limit instead of
  implying live control or complete sensitivity detection."
  [state]
  {:session-id (:session-id state)
   :stopped? (boolean (:stopped? state))
   :permission (public-permission state)
   :operator-input-detection (:operator-input-detection state)
   :tabs (mapv (fn [[tab-id tab]]
                 (select-keys (assoc tab
                                     :tab-id tab-id
                                     :operator-input-coverage
                                     (get-in tab [:last-observation
                                                  :operator-input-coverage]))
                              [:tab-id :url :title :site :status :takeover
                               :takeover-reason :generation :reread?
                               :agent-authorized? :document-id
                               :operator-input-coverage]))
               (:tabs state))
   :cards (cards-view state)
   :limits (:limits state)
   :recent-events (vec (take-last 20 (:events state)))})

(defn report-text
  "Plain-language summary used by diagnostics and the operator.  It states
  what is historical, what is best-effort and what is unknown."
  [state]
  (let [{:keys [tabs cards limits]} (report state)
        open-count (count (filter #(= :open (:status %)) tabs))
        closed-count (count (filter #(= :closed (:status %)) tabs))
        paused (->> tabs
                    (remove #(= :agent-active (:takeover %)))
                    (map (fn [{:keys [tab-id takeover takeover-reason]}]
                           (str tab-id "=" (name takeover)
                                (when takeover-reason
                                  (str " (" (name takeover-reason) ")")))))
                    vec)]
    (str/join
     "\n"
     (concat
      [(str "Managed browser: " open-count " open, " closed-count " closed, "
            (count (:cards cards)) " page cards"
            (when (:truncated? cards)
              (str " (" (:dropped cards) " older cards dropped)"))
            ".")]
      (when (seq paused)
        [(str "Takeover: " (str/join ", " paused) ".")])
      ["Screenshots are historical views, not the current page."
       "Sensitive-field detection is best-effort; values are never stored in page cards."
       "Observation covers the visible top document only; iframes are not read."
       "Only approved tabs and sites are readable by the model."
       "Browser permission is not inherited after restart, branching or migration."
       "An unknown outcome is never retried automatically."
       (str "Limits: " (str/join ", " (map (fn [[k v]] (str (name k) "=" (str v)))
                                            (sort-by key limits)))
            ".")]))))
