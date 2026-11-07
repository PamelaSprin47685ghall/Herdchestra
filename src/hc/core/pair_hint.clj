(ns hc.core.pair-hint
  "Public facade for the Pair Hint core.

  This namespace is deliberately thin: it names the supported operations and
  leaves the invariants in the component namespaces.  Nothing here depends on
  a host SDK, storage, or another `hc/core` module."
  (:require [hc.core.pair-hint.canon :as canon]
            [hc.core.pair-hint.hint :as hint]
            [hc.core.pair-hint.integration :as integration]
            [hc.core.pair-hint.language :as language]
            [hc.core.pair-hint.observation :as observation]
            [hc.core.pair-hint.prefix :as prefix]
            [hc.core.pair-hint.resources :as resources]))

;; Canonical identity and digests
(def canonical-string canon/canonical-string)
(def sha256-hex canon/sha256-hex)
(def digest canon/digest)

;; Semantic message identity and the same-epoch prefix law
(def message-identity prefix/message-identity)
(def message-digest prefix/message-digest)
(def chain-of prefix/chain-of)
(def verify-prefix prefix/verify-prefix)
(def verify-chain prefix/verify-chain)
(def prefix? prefix/prefix?)
(def normalize-boundary prefix/normalize-boundary)
(def allowed-boundary-kinds prefix/allowed-boundary-kinds)

;; Paired resources
(def supported-languages resources/supported-languages)
(def valid-language? resources/valid-language?)
(def placeholders resources/placeholders)
(def load-resources resources/load-pair)
(def render-template resources/render)

;; One-time language resolution and inheritance
(def language-env-var language/language-env-var)
(def normalize-language language/normalize-language)
(def locale->language language/locale->language)
(def resolve-language language/resolve-language)
(def bind-language-session language/bind-session)
(def resume-language-session language/resume-session)
(def inherit-language language/inherit-language)

;; Board and fleet observation
(def initial-board-state observation/initial-board-state)
(def initial-fleet-state observation/initial-fleet-state)
(def default-observation-thresholds observation/default-thresholds)
(def default-max-events observation/default-max-events)
(def fleet-row-fields observation/fleet-row-fields)
(def fleet-event-fields observation/fleet-event-fields)
(def observe-board observation/observe-board)
(def observe-fleet observation/observe-fleet)
(def board-cursor observation/board-cursor)
(def fleet-cursor observation/fleet-cursor)

;; Hint injection and session state
(def create-session hint/create-session)
(def resume-session hint/resume-session)
(def child-session hint/child-session)
(def build-hint hint/build-hint)
(def hint-text hint/hint-text)
(def hint-pair hint/hint-pair)
(def hint-values hint/hint-values)
(def append-hint hint/append-hint)
(def project-request hint/project-request)
(def wrap-model-gateway integration/wrap-model-gateway)
(def board-snapshot-entry integration/board-snapshot-entry)
(def scope-boundary integration/scope-boundary)
(def record-request hint/record-request)
(def reanchor hint/reanchor)
(def apply-board-observation hint/apply-board-observation)
(def apply-fleet-observation hint/apply-fleet-observation)
(def observed-cursor hint/observed-cursor)
