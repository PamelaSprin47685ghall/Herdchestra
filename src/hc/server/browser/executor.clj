(ns hc.server.browser.executor
  "Owner executor and model tool surface for the managed browser.

  The executor maps one model tool call to one service operation and maps
  the service's status vocabulary to the Owner executor contract.  It never
  throws for an expected refusal and never retries an unknown outcome.

  Tool descriptions are part of the safety boundary: they tell the model
  that the browser is off by default, that sensitive values are entered by
  the operator, and that screenshots are historical."
  (:require [clojure.string :as str]
            [hc.server.browser.service :as service]))

(def tool->action
  {"browser_open" :open-tab
   "browser_navigate" :navigate
   "browser_observe" :observe
   "browser_click" :click
   "browser_type" :type
   "browser_scroll" :scroll
   "browser_press" :press
   "browser_submit" :submit
   "browser_back" :back
   "browser_forward" :forward
   "browser_reload" :reload
   "browser_takeover" :takeover
   "browser_resume" :resume
   "browser_operator_input" :operator-input
   "browser_permission" :permission
   "browser_close_tab" :close-tab
   "browser_stop" :stop
   "browser_cards" :cards
   "browser_report" :report})

(defn- target-argument
  [args]
  (or (:target args)
      (when-let [ref (:ref args)] {:ref ref})))

(defn- normalize-args
  [request]
  (let [args (dissoc (merge (or (:arguments request) {})
                            (dissoc request :arguments :tool-name :action :executor))
                     :user-authorized? :allow-paused? :allow-private?)
        target (target-argument args)]
    (cond-> (dissoc args :ref)
      target (assoc :target target))))

(defn- resolve-action
  [request]
  (or (:action request)
      (get tool->action (:tool-name request))
      (when-let [tool (:tool request)]
        (get tool->action tool))))

(def operator-actions
  "Actions that belong to the operator or the workbench, not to the model."
  #{:permission :takeover :resume :operator-input :close-tab :stop})

(defn- operator-only
  [action]
  {:status :failed
   :payload {:status :refused
             :error {:code :operator-only
                     :message "This browser action is reserved for the operator."
                     :details {:action action}
                     :retryable? false
                     :requires-operator? true}}
   :error {:kind :operator-only
           :message "This browser action is reserved for the operator."
           :details {:action action}}})

(defn- invalid-request
  [message details]
  {:status :failed
   :payload {:status :refused
             :error {:code :invalid-argument
                     :message message
                     :details details
                     :retryable? false
                     :requires-operator? false}}
   :error {:kind :invalid-argument
           :message message
           :details details}})

(defn- dispatch
  [svc action args allow-operator-actions?]
  (if (and (contains? operator-actions action)
           (not allow-operator-actions?))
    (operator-only action)
    (case action
      :open-tab (service/open-tab! svc args)
      :navigate (service/navigate! svc args)
      :observe (service/observe! svc args)
      :click (service/act! svc (assoc args :action :click))
      :type (service/act! svc (assoc args :action :type))
      :scroll (service/act! svc (assoc args :action :scroll))
      :press (service/act! svc (assoc args :action :press))
      :submit (service/act! svc (assoc args :action :submit))
      :back (service/history! svc (assoc args :direction :back))
      :forward (service/history! svc (assoc args :direction :forward))
      :reload (service/history! svc (assoc args :direction :reload))
      :takeover (service/takeover! svc args)
      :resume (service/resume! svc args)
      :operator-input (service/operator-input! svc args)
      :permission (service/permission! svc args)
      :close-tab (service/close-tab! svc args)
      :cards {:status :ok :cards (service/cards svc)}
      :report {:status :ok :report (service/report svc)}
      (invalid-request "Unknown browser action."
                       {:action action
                        :known-actions (vec (sort (keys tool->action)))}))))

(defn- executor-status
  [result]
  (case (:status result)
    :ok :succeeded
    :refused :failed
    :failed :failed
    :outcome-unknown :outcome-unknown
    :cancelled :cancelled
    :failed))

(defn executor
  "Build the Owner executor for one browser service.

  Request shape: `{:tool-name \"browser_navigate\" :arguments {...}}` or
  `{:action :navigate ...}`.  The result is
  `{:status :succeeded|:failed|:cancelled|:outcome-unknown
    :payload <service result> :error <structured error>}`.

  Operator-only actions (`permission`, `takeover`, `resume`,
  `operator-input`) are refused by default.  A workbench executor may pass
  `{:allow-operator-actions? true}`; the model executor must not."
  ([svc] (executor svc {}))
  ([svc {:keys [allow-operator-actions?] :or {allow-operator-actions? false}}]
   (fn [{:keys [call-id attempt-id request]}]
    (let [request (or request {})
          action (resolve-action request)
          args (normalize-args request)]
      (try
        (let [result (dispatch svc action args allow-operator-actions?)]
          (cond-> {:status (executor-status result)
                   :payload (assoc result :call-id call-id :attempt-id attempt-id)}
            (contains? #{:failed :outcome-unknown :cancelled} (executor-status result))
            (assoc :error (assoc (or (:error result)
                                     {:message "browser operation did not succeed"})
                                 :kind (or (get-in result [:error :kind])
                                           (get-in result [:error :code])
                                           :browser-failed)))))
        (catch Throwable t
          {:status :failed
           :payload {:status :failed
                     :error {:code :executor-error
                             :message (.getMessage t)
                             :retryable? false}}
           :error {:kind :executor-error
                   :message (.getMessage t)
                   :class (.getName (class t))}}))))))


(defn browser-request?
  "True when one tool-call payload belongs to the managed browser.

  Composition can place the browser executor under the Owner's existing
  `:tool` kind and delegate every other payload to the program runtime."
  [request]
  (let [request (or request {})
        tool-name (str (or (:tool-name request) (:tool request) ""))]
    (or (= :browser (:executor request))
        (= :browser (:kind request))
        (= :browser (:tool request))
        (str/starts-with? tool-name "browser_")
        (str/starts-with? tool-name "browser."))))

(defn routing-executor
  "Route browser tool calls to BROWSER-EXEC and everything else to FALLBACK.

  This is the non-invasive composition seam for an Owner that only admits
  `:tool`, `:shell` and `:workspace` executor kinds.  The browser executor
  itself is unchanged."
  [browser-exec fallback-exec]
  (fn [effect]
    (if (browser-request? (:request effect))
      (browser-exec effect)
      (fallback-exec effect))))

(defn tool-specs
  "JSON-schema tool definitions for the model request snapshot.

  Every definition names the permission boundary and the sensitive-input
  boundary.  The browser remains disabled until the operator enables it."
  []
  [{:name "browser_open"
    :description "Open an approved page in the managed browser. The browser is disabled until the operator enables it for this session. Returns a tab id and page metadata; call browser_observe before acting."
    :parameters {:type "object"
                 :properties {:url {:type "string"}}
                 :required ["url"]}}
   {:name "browser_navigate"
    :description "Navigate an approved tab. A site outside the current permission is refused; ask the operator to approve it instead of bypassing the browser."
    :parameters {:type "object"
                 :properties {:tab-id {:type "string"}
                              :url {:type "string"}}
                 :required ["tab-id" "url"]}}
   {:name "browser_observe"
    :description "Read the visible top document of an approved tab. Observation returns text and element references with a document generation. Iframes are not read. Private pages may require a fresh operator confirmation."
    :parameters {:type "object"
                 :properties {:tab-id {:type "string"}}
                 :required ["tab-id"]}}
   {:name "browser_click"
    :description "Click one element from the latest observation. The target is re-inspected immediately before the click; a navigation or operator edit invalidates the old target. Do not guess a name-similar element."
    :parameters {:type "object"
                 :properties {:tab-id {:type "string"}
                              :observation-id {:type "string"}
                              :target {:type "object"}}
                 :required ["tab-id" "observation-id" "target"]}}
   {:name "browser_type"
    :description "Type ordinary text into a non-sensitive field from the latest observation. Never type passwords, one-time codes, payment credentials or other sensitive values; the operator enters those in the visible page. The service refuses sensitive fields and pauses."
    :parameters {:type "object"
                 :properties {:tab-id {:type "string"}
                              :observation-id {:type "string"}
                              :target {:type "object"}
                              :text {:type "string"}}
                 :required ["tab-id" "observation-id" "target" "text"]}}
   {:name "browser_scroll"
    :description "Scroll the current page. Scrolling is an action and is refused while the operator controls the page."
    :parameters {:type "object"
                 :properties {:tab-id {:type "string"}
                              :observation-id {:type "string"}
                              :delta-y {:type "number"}}
                 :required ["tab-id" "observation-id"]}}
   {:name "browser_press"
    :description "Press one key in the current page. Pressing Enter may submit a form and can have an external effect; the result is reported as unknown if delivery cannot be confirmed."
    :parameters {:type "object"
                 :properties {:tab-id {:type "string"}
                              :observation-id {:type "string"}
                              :key {:type "string"}}
                 :required ["tab-id" "observation-id" "key"]}}
   {:name "browser_submit"
    :description "Submit a form from the latest observation. Forms containing password, one-time-code or payment fields are refused and handed to the operator. Never retry an unknown submission."
    :parameters {:type "object"
                 :properties {:tab-id {:type "string"}
                              :observation-id {:type "string"}
                              :target {:type "object"}}
                 :required ["tab-id" "observation-id" "target"]}}
   {:name "browser_back"
    :description "Go back in the page history."
    :parameters {:type "object" :properties {:tab-id {:type "string"}}
                 :required ["tab-id"]}}
   {:name "browser_forward"
    :description "Go forward in the page history."
    :parameters {:type "object" :properties {:tab-id {:type "string"}}
                 :required ["tab-id"]}}
   {:name "browser_reload"
    :description "Reload the current page."
    :parameters {:type "object" :properties {:tab-id {:type "string"}}
                 :required ["tab-id"]}}
   {:name "browser_cards"
    :description "Return the ordered page cards. Screenshots in cards are historical views, not the current page."
    :parameters {:type "object" :properties {}}}
   {:name "browser_report"
    :description "Return the managed-browser report, including takeover state, permission scope and honest limits."
    :parameters {:type "object" :properties {}}}])
