(ns hc.core.scope
  "Public entry point of the Scope package.

  The package is independent: it requires only Clojure and its own
  namespaces. The composition layer wires it to a host and a store."
  (:require [hc.core.scope.admission :as admission]
            [hc.core.scope.annotation :as annotation]
            [hc.core.scope.errors :as errors]
            [hc.core.scope.ledger :as ledger]
            [hc.core.scope.projection :as projection]
            [hc.core.scope.records :as records]
            [hc.core.scope.runtime :as runtime]
            [hc.core.scope.scope :as scope]))

;; Refusal codes.
(def annotation-missing errors/annotation-missing)
(def kind-invalid errors/kind-invalid)
(def field-invalid errors/field-invalid)
(def field-unexpected errors/field-unexpected)
(def uninitialized errors/uninitialized)
(def budget-spent errors/budget-spent)
(def record-failed errors/record-failed)
(def turn-missing errors/turn-missing)
(def owner-taken errors/owner-taken)
(def not-found errors/not-found)
(def admission-codes errors/admission-codes)
(def settlement-codes errors/settlement-codes)
(def kinds errors/kinds)
(def true-kinds errors/true-kinds)
(def default-exempt-members errors/default-exempt-members)
(def default-pinned-members errors/default-pinned-members)
(def reset-member errors/reset-member)

;; Refusal and argument values.
(def refuse errors/refuse)
(def argument-error errors/argument-error)
(def scope-refusal? errors/scope-refusal?)
(def refused? errors/refused?)
(def member-name errors/member-name)
(def settled-outcome? errors/settled-outcome?)

;; Annotation.
(def parse-annotation annotation/parse-annotation)
(def reset? annotation/reset?)
(def keep? annotation/keep?)
(def remove? annotation/remove?)
(def me-too? annotation/me-too?)
(def true-annotation? annotation/true-annotation?)

;; Admission.
(def admission admission/admission)
(def check-admission admission/check)

;; Ledger.
(def new-book ledger/new-book)
(def restore-book ledger/restore-book)
(def settle-turn ledger/settle-turn)
(def close-turn ledger/close-turn)
(def book-established? ledger/established?)
(def book-retention ledger/retention)
(def book-remaining ledger/remaining)
(def book-spent? ledger/spent?)
(def last-reset ledger/last-reset)
(def last-reset-call-id ledger/last-reset-call-id)
(def reset-beats ledger/reset-beats)
(def erased-beats ledger/erases)
(def spin ledger/spin)
(def settlements ledger/settlements)

;; Records.
(def record-version records/record-version)
(def record-types records/record-types)
(def reset-record records/reset-record)
(def keep-record records/keep-record)
(def remove-record records/remove-record)
(def read-cold-state records/read-cold-state)
(def cold-start-notice records/cold-start-notice)

;; Projection.
(def project-copy projection/project)
(def newest-reset-index projection/newest-reset-index)
(def spin-line projection/spin-line)

;; Stateful Scope.
(def memory-store scope/memory-store)
(def make-scope scope/make-scope)
(def cold-start scope/cold-start)
(def session-id scope/session-id)
(def budget-known? scope/budget-known?)
(def retention scope/retention)
(def remaining scope/remaining)
(def spent? scope/spent?)
(def spin-count scope/spin-count)
(def erased scope/erased)
(def reset-history scope/reset-beats)
(def reset-count scope/reset-count)
(def must-reset? scope/must-reset?)
(def notice scope/notice)
(def admit! scope/admit!)
(def settle! scope/settle!)
(def close-turn! scope/close-turn!)
(def project-request scope/project-request)
(def project scope/project-request)
(def snapshot scope/snapshot)
(def previous-scopes scope/previous-scopes)
(def preceding-scope scope/preceding-scope)
(def table scope/table)
(def create-scope! scope/create-scope!)
(def cold-start-scope! scope/cold-start-scope!)
(def find-scope scope/find-scope)
(def require-scope scope/require-scope)
(def drop-scope! scope/drop-scope!)
;; Runtime adapters.
(def scope-annotation runtime/scope-annotation)
(def tool-member runtime/tool-member)
(def tool-call-id runtime/tool-call-id)
(def message-role runtime/message-role)
(def message-text runtime/message-text)
(def conversation->items runtime/conversation->items)
(def items->messages runtime/items->messages)
(def project-items runtime/project-items)
(def project-scope-items runtime/project-scope-items)
(def project-conversation runtime/project-conversation)
(def project-scope-conversation runtime/project-scope-conversation)
(def check-tool-admission runtime/admission-check)
(def settle-tool runtime/settle-tool)
(def cold-start-book runtime/cold-start-book)
(def cold-start-state runtime/cold-start-state)
(def latest-boundary runtime/latest-boundary)
(def pending-boundary runtime/pending-boundary)
(def scope-admit! runtime/scope-admit!)
(def scope-settle! runtime/scope-settle!)

