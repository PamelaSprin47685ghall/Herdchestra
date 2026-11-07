(ns hc.server.sessions.ids
  "Stable string identifiers for sessions, owners, runs, calls, and attempts.

  Authority: AGENTS.md §5.1 (four-layer lifecycle and multi-client tmux
  semantics) and §7.3 (cross-language IDs are non-blank strings).

  An ID is an opaque non-blank string.  It is never reused.  Callers that
  need deterministic tests inject `counter-source`; production injects
  `uuid-source`.  This namespace performs no I/O and keeps no global mutable
  state.  A source owns only its own allocation counter."
  (:require [clojure.string :as str]))

(defprotocol IdSource
  (next-id!
    [source kind]
    "Return a new non-blank string ID for KIND.
    A source MUST NOT return the same ID twice for one kind."))

(def kinds
  "Known identity kinds.  New kinds may be added, but they still receive a
  distinct textual prefix from `counter-source`."
  #{:session :owner :run :call :attempt :message :message-version
    :connection :credential :model :assistant :command :tool-call :event})

(defn valid-id?
  "True for an opaque non-blank string ID."
  [id]
  (and (string? id)
       (not (str/blank? id))))

(defn require-id
  "Return ID when it is a valid stable ID, otherwise nil."
  [id]
  (when (valid-id? id) id))

(defn- kind-label
  [kind]
  (cond
    (keyword? kind) (name kind)
    (symbol? kind) (name kind)
    (string? kind) (if (str/blank? kind) "id" kind)
    :else
    (throw (ex-info "ID kind must be a keyword, symbol, or string"
                    {:error/kind :invalid-id-kind
                     :kind kind}))))

(defn counter-source
  "A deterministic source.  Each kind has its own monotonic counter.

  Two independently constructed sources can allocate the same IDs; construct
  one source per process and share it through the Owner.  The optional
  `:counters` map lets a test or recovery path continue from observed values."
  ([] (counter-source {}))
  ([{:keys [prefix counters] :or {prefix "hc"}}]
   (let [counters (atom (or counters {}))]
     (reify IdSource
       (next-id! [_ kind]
         (let [label (kind-label kind)
               value (get (swap! counters update label (fnil inc 0)) label)]
           (str prefix "-" label "-" value)))))))

(defn uuid-source
  "A process-unique source backed by random UUIDs.  The prefix is only a
  readability aid; uniqueness comes from the UUID."
  ([] (uuid-source "hc"))
  ([prefix]
   (let [prefix (if (str/blank? (str prefix)) "hc" (str prefix))]
     (reify IdSource
       (next-id! [_ kind]
         (str prefix "-" (kind-label kind) "-" (str (java.util.UUID/randomUUID))))))))
