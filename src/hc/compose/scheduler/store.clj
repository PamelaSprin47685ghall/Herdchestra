(ns hc.compose.scheduler.store
  "Persistence boundary for the schedule service.

  The service persists the whole immutable scheduler state after every
  accepted transition.  That is deliberately coarse: schedules, runs, and the
  id sequence commit together, so a restart cannot observe a run without its
  schedule or reuse an id.  A real server adapter can map this protocol to the
  storage engine from AGENTS.md §7.1; this package supplies only the protocol
  and an in-memory implementation for tests.

  `load-state` must return `nil` for a store that has never been written and
  must throw for a store that exists but cannot be read.  The service treats a
  throw as corruption and refuses to overwrite it with an empty state."
  (:refer-clojure :exclude [load]))

(defprotocol Store
  (load-state [store]
    "Return the persisted scheduler state map, or nil when absent.")
  (save-state! [store state]
    "Persist STATE as one atomic replacement.  Return nil."))

(defn memory-store
  "An in-memory store.  `stored-state` exposes the last saved value."
  ([] (memory-store nil))
  ([initial]
   (let [value (atom initial)]
     (reify Store
       (load-state [_] @value)
       (save-state! [_ state] (reset! value state) nil)))))

(defn stored-state
  "The last value saved to a memory store."
  [store]
  (load-state store))
