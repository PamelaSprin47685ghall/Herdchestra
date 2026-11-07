(ns hc.compose.mission
  "Plan/Reviewer/Swarm role lifecycle at the host boundary.

  The mission host binds `hc.core.relay` (review ledgers, tenures, barriers),
  `hc.core.swarm-stm` (per-tenure boards, root tasks, actors, token), and the
  Plan dedicated worker contract into one atomic committed value.  It also
  enforces the §1.1 #8 model ownership rule: Plan and Reviewer use the main
  model; the Plan dedicated worker, swarm workers, and the holder use the
  task model.

  This facade exposes the host commands plus the Nudge decision bridge.  See
  `hc.compose.mission.host` for the transition contract and
  `src/hc/compose/mission/AGENTS.md` for the local specification."
  (:require [hc.compose.mission.host :as host]
            [hc.compose.mission.model :as model]
            [hc.compose.mission.worker :as worker]
            [hc.compose.nudge.policy :as nudge]))

(def initial-state host/initial-state)
(def create host/create)
(def restore host/restore)
(def restore-host host/restore-host)
(def apply-command host/apply-command)
(def apply-command! host/apply-command!)
(def snapshot host/snapshot)
(def relay-of host/relay-of)
(def board-of host/board-of)
(def current-board host/current-board)
(def tenure host/tenure)
(def relay-state host/relay-state)
(def models host/models)
(def model-for-role host/model-for-role)
(def role-binding host/role-binding)
(def review-state host/review-state)
(def resume-state host/resume-state)
(def set-draft-state host/set-draft-state)
(def maybe-relay-state host/maybe-relay-state)
(def register-swarm-actor-state host/register-swarm-actor-state)
(def claim-task-state host/claim-task-state)
(def take-or-die-state host/take-or-die-state)
(def finish-root-task-state host/finish-root-task-state)
(def cancel-root-task-state host/cancel-root-task-state)
(def replace-root-task-state host/replace-root-task-state)
(def reopen-root-task-state host/reopen-root-task-state)
(def reopen-state host/reopen-state)
(def board-command-state host/board-command-state)
(def devops-request-state host/devops-request-state)
(def devops-complete-state host/devops-complete-state)
(def review! host/review!)
(def resume! host/resume!)
(def set-draft! host/set-draft!)
(def maybe-relay! host/maybe-relay!)
(def register-swarm-actor! host/register-swarm-actor!)
(def claim-task! host/claim-task!)
(def take-or-die! host/take-or-die!)
(def finish-root-task! host/finish-root-task!)
(def cancel-root-task! host/cancel-root-task!)
(def replace-root-task! host/replace-root-task!)
(def reopen-root-task! host/reopen-root-task!)
(def reopen! host/reopen!)
(def devops-request! host/devops-request!)
(def devops-complete! host/devops-complete!)
(def devops! host/devops!)
(def board-snapshot host/board-snapshot)
(def board-summary host/board-summary)
(def nudge-facts host/nudge-facts)
(def check-invariants host/check-invariants)

(def role->model-role
  "Alias for the pure ownership table."
  model/role->model-role)

(def plan-worker-contract
  "Declared Plan dedicated worker contract."
  worker/contract)

(defn decide-nudge-state
  "Evaluate the §2.5 Nudge guard against a committed mission state.

  This is the pure Owner-facing form.  FACTS selects the role identity, for
  example `{:role :plan}`, `{:role :reviewer :tenure-id \"tenure-2\"}`, or
  `{:role :worker :tenure-id \"tenure-2\" :agent-id :agent-1}`."
  [state facts]
  (let [observation (nudge/observation (:relay state)
                                       (nudge-facts state facts))]
    (nudge/decide observation)))

(defn decide-nudge
  "Evaluate the §2.5 Nudge guard against a committed mission host.

  FACTS selects the role identity, for example:

    {:role :plan}
    {:role :reviewer :tenure-id \"tenure-2\"}
    {:role :worker :tenure-id \"tenure-2\" :agent-id :agent-1}

  The returned decision is exactly `hc.compose.nudge.policy/decide` output.
  The caller commits `:prepare` before dispatching its effects."
  [mission-host facts]
  (decide-nudge-state (snapshot mission-host) facts))
