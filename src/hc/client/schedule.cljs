(ns hc.client.schedule
  "Public schedule workspace.

  Use `initial-state`, feed a scheduler snapshot through `apply-snapshot`,
  feed receipts through `apply-receipt`, and mount `schedule-element` with a
  dispatch callback.  `dispatch!` is the bridge convenience runner for an
  atom-backed state map.

  The client never reports a schedule as enabled, paused, or run by itself.
  Only a server receipt or a refreshed snapshot changes the confirmed state."
  (:require [hc.client.schedule.model :as model]
            [hc.client.schedule.platform :as platform]
            [hc.client.schedule.react :as react-view]
            [hc.client.schedule.time :as time]
            [hc.client.schedule.view :as view]))

(def initial-state
  "Return a disconnected schedule state."
  model/initial-state)

(def reduce-action
  "Apply one action; returns `{:state ... :effects [...]}`."
  model/reduce-action)

(defn apply-snapshot
  "Apply a scheduler snapshot and return the next state."
  [state snapshot]
  (model/apply-snapshot state snapshot))

(defn apply-schedules
  [state schedules]
  (model/apply-schedules state schedules))

(defn apply-runs
  [state runs]
  (model/apply-runs state runs))

(defn apply-receipt
  "Apply one server receipt and return the next state."
  [state receipt]
  (:state (model/apply-receipt state receipt)))

(defn mark-load-error
  [state message]
  (model/mark-load-error state message))

(defn set-platform
  [state platform]
  (:state (model/reduce-action state {:type :schedule/set-platform
                                      :platform platform})))

(defn export-form
  [state]
  (model/export-form state))

(defn import-form
  [state payload]
  (model/import-form state payload))

(defn validate-form
  [state form]
  (model/validate-form state form))

(defn schedule-cards
  [state]
  (model/schedule-cards state))

(defn runs-for
  [state schedule-id]
  (model/runs-for state schedule-id))

(defn active-run
  [state schedule-id]
  (model/active-run state schedule-id))

(defn schedule-tree
  [state]
  (view/schedule-tree state))

(def SchedulePage
  "React function component for a caller-owned state map."
  react-view/SchedulePage)

(def ScheduleView
  "Alias for SchedulePage."
  react-view/SchedulePage)

(defn schedule-element
  "Create a React element for STATE and DISPATCH!."
  [state dispatch!]
  (react-view/schedule-element state dispatch!))

(defn preview
  "Client-side recurrence preview for an unsaved form."
  ([recurrence timezone now-ms]
   (time/preview recurrence timezone now-ms))
  ([recurrence timezone now-ms opts]
   (time/preview recurrence timezone now-ms opts)))

(def detect-platform
  platform/detect)

(def device-zone
  time/device-zone)

(def format-epoch
  time/format-epoch)
