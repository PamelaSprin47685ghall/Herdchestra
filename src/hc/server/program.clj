(ns hc.server.program
  "Public facade for the Clojure program sandbox.

  Authority: root `AGENTS.md` §4.1–§4.3.  The runtime executes one
  `(defprogram ...)` form, enforces the role capability set, parks and
  resumes real JVM continuations, applies the intent deadline, replays
  physical calls by `:call-id`, and routes protected file work through the
  injected `:workspace` port."
  (:require [hc.server.program.capability :as capability]
            [hc.server.program.receipt :as receipt]
            [hc.server.program.runtime :as runtime]
            [hc.server.program.sandbox :as sandbox]
            [hc.server.program.shell-port :as shell-port]
            [hc.server.program.workspace-port :as workspace-port]))

(defn runtime
  "Compose one program runtime.  See `hc.server.program.runtime/runtime`."
  [opts]
  (runtime/runtime opts))

(defn evaluate-program!
  "Submit one physical program call.  Returns at the first park or terminal
  state.  Same `:call-id` and payload replays the original receipt."
  [rt request]
  (runtime/evaluate-program! rt request))

(defn execute!
  "Alias for `evaluate-program!`."
  [rt request]
  (runtime/execute! rt request))

(defn unpark!
  "Resume a parked program by name through a synthetic program."
  [rt request]
  (runtime/unpark! rt request))

(defn cancel!
  "Cancel by exact name, exact call-id, or a unique attempt-id.

  A shared attempt-id returns `:AMBIGUOUS_TARGET` instead of guessing."
  [rt key]
  (runtime/cancel! rt key))

(defn stop!
  "Terminate every active continuation and the deadline executor.

  Returns `:active` descriptors for programs whose in-flight shell deferred
  the terminal."
  [rt]
  (runtime/stop! rt))

(defn program-state
  "Current state/receipt for a name, exact call-id, or unique attempt-id."
  [rt name]
  (runtime/program-state rt name))

(defn name-active?
  "True when a program name is in an active lifecycle state."
  [rt name]
  (runtime/name-active? rt name))

(defn active-programs
  "Live program descriptors for an operator cancellation sweep."
  [rt]
  (runtime/active-programs rt))

(defn render
  "Render a receipt as EDN+UFSS text.  A transport result unwraps its
  `:receipt` first, so the model does not see a duplicate nested copy."
  [result]
  (runtime/render result))

(defn render-tool-result
  "Render an executor/bridge tool payload as EDN+UFSS text for the model."
  [payload]
  (runtime/render-tool-result payload))

(defn intent->deadline-ms
  "The §4.3 deadline for one `:intent` string."
  [intent]
  (runtime/intent->deadline-ms intent))

(defn role-capabilities
  "Static capability set for a role."
  [role]
  (capability/capabilities role))

(defn workspace-gate-port
  "Reference `:workspace` port over `hc.core.workspace-gate`."
  [opts]
  (workspace-port/workspace-gate-port opts))

(defn shell-call-port
  "Reference `:call` port over `hc.server.shell`, with live command views."
  [opts]
  (shell-port/shell-call-port opts))

(defn compile-program
  "Parse, validate, and macro-expand a source string without running it.
  Useful for tooling and tests."
  ([source] (sandbox/compile-program source))
  ([source opts] (sandbox/compile-program source opts)))
