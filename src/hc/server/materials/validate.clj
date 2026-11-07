(ns hc.server.materials.validate
  "Whole-state validation for the materials domain.

  Loading must not turn a damaged library into an empty one. The store calls
  this before returning a snapshot; callers may also call it after building a
  candidate state."
  (:require [hc.server.materials.instruction :as instruction]
            [hc.server.materials.memory :as memory]
            [hc.server.materials.state :as state]
            [hc.server.materials.worldbook :as worldbook]))

(defn check-state
  "Return every invariant violation in STATE. An empty vector is valid."
  [state]
  (into [] (concat (state/check state)
                   (memory/check state)
                   (worldbook/check state)
                   (instruction/check state))))
