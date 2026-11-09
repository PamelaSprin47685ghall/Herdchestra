(ns hc.client.settings
  "Public settings workspace.

  Use `initial-state`, feed server baselines to `apply-snapshot`, and mount
  `SettingsView` with a state map plus a dispatch callback.  `dispatch!` is
  the convenience runner for an atom-backed state map: it applies a pure
  action, persists drafts through `:on-effect`, and submits every queued
  command through `:submit!`.

  A command is never reported as saved or tested by the click itself.  Only a
  receipt changes the domain state."
  (:require [hc.client.appearance.model :as appearance-model]
            [hc.client.settings.commands :as commands]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.model :as model]
            [hc.client.settings.react :as react-view]))

(def initial-state
  "Return a disconnected settings state."
  model/initial-state)

(def reduce-action
  "Apply one action; returns `{:state ... :effects [...]}`."
  model/reduce-action)

(defn apply-snapshot
  "Apply a server baseline snapshot and return the next state."
  [state snapshot]
  (:state (model/apply-snapshot state snapshot)))

(defn apply-receipt
  "Apply one server receipt and return the next state."
  [state receipt]
  (:state (model/apply-receipt state receipt)))

(defn apply-receipt-result
  "Apply one server receipt and return `{:state ... :effects [...]}`.

  Receipt effects include `:schedule-poll`/`:cancel-poll` for model-auth
  flows.  Callers that drive effects must use this function; the
  state-only `apply-receipt` above stays compatible with older callers."
  [state receipt]
  (model/apply-receipt state receipt))

(defn apply-test-result
  "Apply one asynchronous test result and return the next state."
  [state result]
  (:state (model/reduce-action state
                               {:type :settings/receive-test-result
                                :result result})))

(defn mark-outcome-unknown
  "Record that a transport threw after handoff."
  [state command-id error]
  (:state (model/mark-outcome-unknown state command-id error)))

(defn set-transport-available
  "Tell the view whether a command transport is wired."
  [state available?]
  (:state (model/set-transport-available state available?)))

(defn set-draft-storage-status
  "Tell the view whether drafts could be persisted.  Use `:error` or
  `:unsaved` when storage fails; never claim success."
  [state status]
  (:state (model/set-draft-storage-status state status)))

(defn open-settings
  [state]
  (:state (model/reduce-action state {:type :settings/open})))

(defn close-settings
  [state]
  (:state (model/reduce-action state {:type :settings/close})))

(defn export-drafts
  "Return a serializable draft envelope for local persistence."
  [state]
  (draft/export-drafts (:drafts state)))

(defn import-drafts
  "Restore drafts from a previously exported envelope."
  [state payload]
  (assoc state :drafts (draft/import-drafts payload)))

(defn apply-appearance
  "Merge local appearance preferences into settings state without submitting
  a server command.  The workbench bridge normally supplies this value."
  [state appearance]
  (assoc state :appearance
         (appearance-model/normalize
          (merge (:appearance state) appearance))))

(defn onboarding-status
  [state]
  (model/onboarding-status state))

(defn model-groups
  [state]
  (model/model-groups state))

(defn connection-cards
  [state]
  (model/connection-cards state))

(defn draft-cards
  "Return new and orphaned drafts that have no saved connection card."
  [state]
  (model/draft-cards state))

(defn role-cards
  [state]
  (model/role-cards state))

(def SettingsView
  "React function component for route #/settings."
  react-view/SettingsView)

(def SettingsPage
  "Alias for SettingsView."
  react-view/SettingsPage)

(defn settings-element
  "Create a React element for STATE and DISPATCH!."
  ([state dispatch!]
   (react-view/settings-element state dispatch!))
  ([state dispatch! transport-available?]
   (react-view/settings-element state dispatch! transport-available?)))

(defn- promise?
  [value]
  (and (some? value) (fn? (.-then value))))

(defn- apply-result!
  [state-atom f & args]
  (swap! state-atom
         (fn [state]
           (:state (apply f state args)))))

(defn- submit-one!
  [state-atom envelope submit!]
  (swap! state-atom commands/mark-submitting (:command-id envelope))
  (try
    (let [receipt (submit! envelope)]
      (cond
        (false? receipt)
        (apply-result! state-atom model/mark-outcome-unknown
                       (:command-id envelope)
                       (ex-info "命令未送达实时通道；可核对后重试。" {}))

        (nil? receipt)
        nil

        (promise? receipt)
        (.then receipt
               (fn [value]
                 (when value
                   (apply-result! state-atom model/apply-receipt value)))
               (fn [error]
                 (apply-result! state-atom model/mark-outcome-unknown
                                (:command-id envelope) error)))

        :else
        (apply-result! state-atom model/apply-receipt receipt)))
    (catch :default error
      (apply-result! state-atom model/mark-outcome-unknown
                     (:command-id envelope) error))))

(defn flush-outbox!
  "Submit every queued command once.  A thrown transport error becomes
  `:outcome-unknown`, not success."
  [state-atom submit!]
  (when submit!
    (doseq [envelope (commands/pending-outbox @state-atom)]
      (submit-one! state-atom envelope submit!))))

(defn dispatch!
  "Apply ACTION to STATE-ATOM and run effects.

  Options:
    :submit!    (fn [envelope] -> receipt | nil | Promise)
    :on-effect  (fn [effect] ...) for :persist-drafts, :copy-secret, and
                :request-stop-run.

  A nil receipt means the transport is asynchronous; call `apply-receipt`
  later.  A Promise is handled automatically."
  [state-atom action {:keys [submit! on-effect]}]
  (let [{:keys [state effects]} (model/reduce-action @state-atom action)]
    (reset! state-atom state)
    (doseq [effect effects]
      (case (:effect effect)
        :persist-drafts (when on-effect (on-effect effect))
        :copy-secret (when on-effect (on-effect effect))
        :request-stop-run (when on-effect (on-effect effect))
        :apply-appearance (when on-effect (on-effect effect))
        :submit-command (when submit!
                          (submit-one! state-atom (:command effect) submit!))
        (when on-effect (on-effect effect))))
    (flush-outbox! state-atom submit!)
    (when on-effect
      (on-effect {:effect :persist-drafts :drafts (:drafts @state-atom)}))
    @state-atom))
