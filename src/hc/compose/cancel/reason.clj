(ns hc.compose.cancel.reason
  "Fixed cancellation vocabulary from AGENTS.md §1.1-6 and §5.3.

  A cancellation is a control event, not an error and not a task state.
  Exactly one reason travels with each signal.  Operator Esc and operator
  Stop are the only operator origins.  Park, ask, relay retirement and slot
  reincarnation are programmatic suspends; treating one as an operator
  cancellation would destroy work the operator never asked to stop.")

(def reasons
  "Every cancellation signal carries exactly one of these categories."
  #{:user-cancel
    :parent-terminated
    :sibling-accepted
    :deadline-exceeded
    :executor-shutdown})

(def operator-origins
  "Real operator controls.  A programmatic suspend is not in this set."
  #{:operator-esc :operator-stop})

(def programmatic-origins
  "Legitimate suspends that must never enter the cancellation tree."
  #{:park :ask :relay-retirement :slot-reincarnation})

(def internal-origins
  "Non-operator cancellations produced by the composition layer."
  #{:required-step-failure :first-accepted :deadline :executor-shutdown})

(defn reason?
  "True when REASON is one of the fixed categories."
  [reason]
  (contains? reasons reason))

(defn operator-origin?
  "True when ORIGIN is a real operator control."
  [origin]
  (contains? operator-origins origin))

(defn programmatic-origin?
  "True when ORIGIN is a programmatic suspend, not a cancellation."
  [origin]
  (contains? programmatic-origins origin))

(defn operator-cancel?
  "True only for a real operator stop signal."
  [{:keys [origin reason]}]
  (and (operator-origin? origin)
       (= :user-cancel reason)))

(defn descendant-reason
  "The signal a child receives when its parent is cancelled.

  The root keeps the caller's reason.  Descendants are terminated because
  their parent ended, so they carry `:parent-terminated`; the root reason is
  still recorded separately on every node.  This keeps an operator stop from
  being misread as an upstream failure."
  [_root-reason]
  :parent-terminated)
