(ns hc.server.materials
  "Materials and memory domain facade.

  Authority: root AGENTS.md §5.3, §5.4, §5.5 and §7.4 旅程 5.6 / 6.1（记忆与搜索）.

  The domain is pure: memory review, scope selection, worldbook turn
  counters and search references are state transitions over plain EDN. A
  caller supplies the Store implementation; this namespace never opens a
  database, calls a clock or contacts a search service.

  Typical flow:

    (def store (store/memory-store))          ; test only
    (def loaded (store/initialize! store))
    (def state (:state loaded))

    ;; manual memory
    (def saved (memory/remember state {...}))
    (def state (:state saved))

    ;; automatic review
    (def proposed (memory/propose state {...}))
    (def approved (memory/approve (:state proposed) \"candidate-id\" {}))

    ;; worldbook turn
    (def turn (worldbook/run-turn (:state approved) {...}))

    ;; search references belong to the message, not this state
    (def refs (search/add-results (search/new-set) {...}))
    (def cited (search/cite refs \"https://example.com/a\"))

  See `src/hc/server/materials/AGENTS.md` for the full contract."
  (:require [hc.server.materials.errors :as errors]
            [hc.server.materials.instruction :as instruction]
            [hc.server.materials.memory :as memory]
            [hc.server.materials.search :as search]
            [hc.server.materials.state :as state]
            [hc.server.materials.store :as store]
            [hc.server.materials.validate :as validate]
            [hc.server.materials.worldbook :as worldbook]))

;; State
(def state-version state/state-version)
(def default-settings state/default-settings)
(def new-state state/new-state)
(def update-settings state/update-settings)
(def memory-read-enabled? state/memory-read-enabled?)
(def memory-suggest-enabled? state/memory-suggest-enabled?)

(defn check-invariants
  "Return every materials invariant violation. An empty vector is valid."
  [state]
  (validate/check-state state))

;; Persistence
(def Store store/Store)
(def memory-store store/memory-store)
(def load-state store/load-state)
(def initialize! store/initialize!)
(def commit! store/commit!)
(def transact! store/transact!)

;; Memory
(def max-memory-body-chars memory/max-body-chars)
(def memory-categories memory/categories)
(def memory-statuses memory/statuses)
(def memory-epistemic-kinds memory/epistemic-kinds)
(def normalize-memory-scope memory/normalize-scope)
(def memory-scope-key memory/scope-key)
(def memory-scope-label memory/scope-label)
(def default-memory-scope-chain memory/default-scope-chain)
(def memory-sensitive-kinds memory/sensitive-kinds)
(def remember memory/remember)
(def propose-memory memory/propose)
(def approve-memory memory/approve)
(def reject-memory memory/reject)
(def replace-memory memory/replace)
(def archive-memory memory/archive)
(def restore-memory memory/restore)
(def delete-memory memory/delete)
(def search-memories memory/search)
(def effective-memories memory/effective)
(def select-memories memory/select-for-injection)
(def render-memory-injection memory/render-injection)
(def merge-memory-backup memory/merge-backup)

;; Worldbook
(def worldbook-trigger-kinds worldbook/trigger-kinds)
(def worldbook-position-kinds worldbook/position-kinds)
(def validate-worldbook-expression worldbook/validate-expression)
(def eval-worldbook-expression worldbook/eval-expression)
(def normalize-worldbook-trigger worldbook/normalize-trigger)
(def create-worldbook worldbook/create-book)
(def update-worldbook worldbook/update-book)
(def bind-worldbook worldbook/bind-book)
(def unbind-worldbook worldbook/unbind-book)
(def delete-worldbook worldbook/delete-book)
(def create-worldbook-entry worldbook/create-entry)
(def update-worldbook-entry worldbook/update-entry)
(def move-worldbook-entries worldbook/move-entries)
(def delete-worldbook-entry worldbook/delete-entry)
(def worldbook-keyword-match? worldbook/keyword-match?)
(def run-worldbook-turn worldbook/run-turn)
(def preview-worldbook-turn worldbook/preview-turn)

;; Instruction cards
(def instruction-card-max-name-chars instruction/max-name-chars)
(def create-instruction-card instruction/create-card)
(def update-instruction-card instruction/update-card)
(def delete-instruction-card instruction/delete-card)
(def set-instruction-session-cards instruction/set-session-cards)
(def clear-instruction-session-cards instruction/clear-session-cards)
(def instruction-session-card-ids instruction/session-card-ids)
(def select-instruction-cards instruction/select-cards)
(def instruction-turn-card-selection instruction/turn-card-selection)
(def stage-instruction-turn-cards instruction/stage-turn-cards)
(def clear-instruction-turn-cards instruction/clear-turn-cards)
(def instruction-remove-turn-selection instruction/remove-turn-selection)
(def instruction-consume-turn-selection instruction/consume-turn-selection)
(def instruction-turn-card-receipt instruction/turn-card-receipt)

;; Search references
(def new-reference-set search/new-set)
(def canonical-search-url search/canonical-url)
(def search-site search/site-of)
(def add-search-results search/add-results)
(def cite-search-source search/cite)
(def search-citation-number search/citation-number)
(def search-citation-marker search/citation-marker)
(def search-source-list search/source-list)
(def record-search-read search/record-read)
(def search-plain-text search/plain-text)
(def check-search-references search/check)

;; Errors, for callers that want to pattern-match
(def non-blank-string? errors/non-blank-string?)
(def refusal-error errors/error)
