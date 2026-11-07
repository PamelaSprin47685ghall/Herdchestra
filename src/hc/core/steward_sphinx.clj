(ns hc.core.steward-sphinx
  "Public facade for the Stewardship core.

  The package implements the Standing Sphinx's evidence-coupled cognition,
  the pull-only Stewardship Decision Frontier, and the Steward's Docket and
  admission decision.  It is independent of storage, model ports, and every
  other `hc/core` package.  See `AGENTS.md` in this directory for the local
  specification."
  (:require [hc.core.steward-sphinx.cognition :as cognition]
            [hc.core.steward-sphinx.docket :as docket]
            [hc.core.steward-sphinx.evidence :as evidence]
            [hc.core.steward-sphinx.frontier :as frontier]))

;; Constants
(def root-question evidence/root-question)
(def evidence-kinds evidence/evidence-kinds)
(def known-dimensions cognition/known-dimensions)
(def default-policy docket/default-policy)

;; Evidence
(def normalize-evidence evidence/normalize-evidence)
(def normalize-path evidence/normalize-path)
(def normalize-paths evidence/normalize-paths)
(def path-prefix? evidence/path-prefix?)
(def repo-wide-path? evidence/repo-wide-path?)
(def affects-assumption? evidence/affects-assumption?)
(def evidence-summary evidence/evidence-summary)

;; Standing Sphinx cognition
(def initial-cognition cognition/initial-cognition)
(def ingest-evidence cognition/ingest-evidence)
(def revise-best-answer cognition/revise-best-answer)
(def open-question cognition/open-question)
(def resolve-question cognition/resolve-question)
(def best-answer cognition/best-answer)
(def rebuild-best-answer cognition/rebuild-best-answer)
(def assumption-status cognition/assumption-status)
(def assumption-view cognition/assumption-view)
(def claim-evaluation cognition/claim-evaluation)
(def claim-view cognition/claim-view)
(def cognition-version cognition/cognition-version)
(def evidence-count cognition/evidence-count)

;; Decision Frontier
(def initial-frontier-state frontier/initial-frontier-state)
(def normalize-intent frontier/normalize-intent)
(def normalize-capacity frontier/normalize-capacity)
(def capacity-frontier? frontier/capacity-frontier?)
(def capacity-signature frontier/capacity-signature)
(def evaluate-frontier frontier/evaluate-frontier)

;; Docket and admission
(def initial-docket docket/initial-docket)
(def normalize-policy docket/normalize-policy)
(def normalize-candidate docket/normalize-candidate)
(def score-candidate docket/score-candidate)
(def decide docket/decide)
(def transition-mission docket/transition-mission)
(def docket-summary docket/docket-summary)
