(ns hc.core.swarm-stm
  "Public facade for the phase-one swarm STM control kernel.

  The board is an immutable EDN value; the kernel publishes commands with a
  linearizable compare-and-set.  See src/hc/core/swarm_stm/AGENTS.md for the
  command preconditions, responsibility formula and phase boundaries."
  (:refer-clojure :exclude [derive])
  (:require [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.commands :as commands]
            [hc.core.swarm-stm.kernel :as kernel]))

(def empty-board
  "Return a fresh immutable board value."
  board/empty-board)

(def create
  "Create a kernel; see hc.core.swarm-stm.kernel/create."
  kernel/create)

(def execute!
  "Apply one command atomically."
  kernel/execute!)

(def execute-under-write-gate!
  "Apply one command while holding the injected W port; required for a finish
  with a non-empty read-set."
  kernel/execute-under-write-gate!)

(def execute-batch!
  "Apply several commands in one board transition."
  kernel/execute-batch!)

(def execute-batch-under-write-gate!
  "Batch form of execute-under-write-gate!."
  kernel/execute-batch-under-write-gate!)

(def requires-write-gate?
  "True when the command contains a file-dependent finish."
  kernel/requires-write-gate?)

(def snapshot
  "Current immutable board value."
  kernel/snapshot)

(def board-version
  kernel/board-version)

(def task
  kernel/task)

(def actor
  kernel/actor)

(def responsibility
  "R(a) for an agent id."
  kernel/responsibility)

(def global-progressable?
  kernel/global-progressable?)

(def waiters
  kernel/waiters)

(def events
  kernel/events)

(def check-invariants
  kernel/check-invariants)

(def replace-board!
  "Atomically replace the kernel board when it still equals EXPECTED.
  See `hc.core.swarm-stm.kernel/replace-board!`."
  kernel/replace-board!)

(def reset-board!
  "Test/recovery helper; not a protocol participant action."
  kernel/reset-board!)

(def execute-pure
  "Pure command entry for deterministic drivers; returns the transaction
  outcome without publishing it."
  commands/execute)

(def derive
  "Recompute derived indexes from authoritative records."
  board/derive)

(def check-board-invariants
  "Structural invariant check over an immutable board value."
  board/check-invariants)
