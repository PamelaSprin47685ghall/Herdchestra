(ns hc.server.sessions.ports
  "Persistence and Owner ports for session management.

  Authority: AGENTS.md §5.1 (one serial Owner per session; I/O outside the
  Owner) and §7.1 (the storage engine is an adapter, not the domain).

  The domain returns plain EDN.  It never writes a database and never starts
  a thread.  The assembly layer supplies a `SessionPersistence` and a
  `OwnerPort`.

  Revision contract:

    - A newly created value has `:revision 0` and is not stored yet.
    - `expected-revision` is the revision the caller observed in the store.
      Use 0 when the key does not exist.
    - A successful commit returns `{:status :committed :revision n}`.
    - A stale commit returns `{:status :conflict :revision stored}` and the
      caller MUST read the stored value before deciding what to do.
    - A commit whose outcome cannot be known returns
      `{:status :unknown :error ...}`.  It is never reported as committed."
  (:require [clojure.string :as str]))

(defprotocol SessionPersistence
  (read-session
    [persistence session-id]
    "Return {:status :found :session s}, {:status :missing}, or
    {:status :error :error e}.")

  (commit-session!
    [persistence expected-revision session]
    "Compare the stored revision with EXPECTED-REVISION, then store SESSION.
    Return {:status :committed :revision n},
    {:status :conflict :revision stored},
    {:status :duplicate :session existing} when another session already owns
    the same :creation-command identity, or
    {:status :unknown :error e}.")

  (list-session-ids
    [persistence]
    "Return {:status :ok :ids [stable-id ...]} or {:status :error :error e}.
    The order is unspecified; callers sort summaries themselves.")

  (delete-session!
    [persistence expected-revision session-id]
    "Permanently remove one session record.  Return {:status :deleted},
    {:status :missing}, {:status :conflict :revision stored}, or
    {:status :unknown :error e}.  This never deletes external workspace
    files; the domain only owns its own record.")

  (read-config
    [persistence]
    "Return {:status :found :config c}, {:status :missing}, or
    {:status :error :error e}.")

  (commit-config!
    [persistence expected-revision config]
    "Compare and store the global configuration value.  Same result shape as
    `commit-session!`."))

(defprotocol SessionCommandLookup
  (find-session-by-command
    [persistence identity]
    "Return the session created by IDENTITY as
    `{:status :found :session s}`, `{:status :missing}`, or
    `{:status :error :error e}`.  IDENTITY is
    `{:scope s :command-epoch n :command-id id}`.  The identity is stored on
    the session in the same commit, so a committed session is always
    findable, and the same command-id in another epoch is a different
    command."))

(defn valid-command-identity?
  [identity]
  (and (map? identity)
       (string? (:scope identity))
       (not (str/blank? (:scope identity)))
       (integer? (:command-epoch identity))
       (pos? (:command-epoch identity))
       (string? (:command-id identity))
       (not (str/blank? (:command-id identity)))))

(defn lookup-session-by-command
  "Find a session by its creation command identity.

  Uses the adapter's index when it implements `SessionCommandLookup`.
  Otherwise it scans the session ids and compares `:creation-command`.
  The fallback keeps custom test adapters compatible; production adapters
  should provide the indexed lookup."
  [persistence identity]
  (if-not (valid-command-identity? identity)
    {:status :missing}
    (if (satisfies? SessionCommandLookup persistence)
      (find-session-by-command persistence identity)
      (let [ids (list-session-ids persistence)]
        (case (:status ids)
          :ok
          (let [results (mapv (fn [session-id]
                                (read-session persistence session-id))
                              (:ids ids))
                failure (first
                         (keep (fn [result]
                                 (when-not (contains? #{:found :missing}
                                                      (:status result))
                                   (if (= :error (:status result))
                                     result
                                     {:status :error
                                      :error
                                      (or (:error result)
                                          {:code :SESSION_READ_FAILED
                                           :message
                                           "session read did not return found or missing"
                                           :details {:status (:status result)}})})))
                               results))]
            (or failure
                (some (fn [result]
                        (when (and (= :found (:status result))
                                   (= identity
                                      (:creation-command
                                       (:session result))))
                          result))
                      results)
                {:status :missing}))
          ids)))))

(defprotocol SessionCommandClaim
  (claim-session-command!
    [persistence identity session-id]
    "Persist IDENTITY -> SESSION-ID without changing the session record.
    Return {:status :claimed} | {:status :unchanged}
         | {:status :duplicate :session existing}
         | {:status :missing} | {:status :conflict :revision n}
         | {:status :unknown :error e}.
    This is the durable business point for a blank-session reuse."))

(defprotocol SessionSummaryRead
  (read-session-summary-doc
    [persistence session-id]
    "Return `{:status :found :session summary}`, `{:status :missing}` or
    `{:status :unknown :error e}` for one committed session without its
    message bodies.

    An adapter that keeps a derived summary document must point-read that
    document and fall back to the session record only when the summary is
    absent.  The summary carries the committed record fields, a
    `:visible-message-count` and `:has-draft?`; it never carries `:messages`
    or `:message-order`."))

(defprotocol SessionHistoryIndex
  (session-committed!
    [index session]
    "Advisory notification that SESSION is durable.

    A derived history or search index implements this to update itself after
    a commit.  The call runs after the storage transaction succeeded and its
    failure never changes the commit result; a later rebuild must be possible
    from the current state alone.")

  (session-deleted!
    [index session-id]
    "Advisory notification that SESSION-ID is no longer stored.

    Same failure rule as `session-committed!`: the index must be able to
    rebuild from the current state, so a failed notification cannot make the
    deletion unproven."))

(defprotocol OwnerPort
  (enqueue!
    [owner message]
    "Enqueue one internal plain-EDN message for a session Owner.  Return
    {:status :accepted ...} or a structured refusal.  The Owner is the only
    writer of live session state; callers never mutate it directly.")

  (submit-command!
    [owner request]
    "Synchronously submit one client command envelope through the Owner
    mailbox and return its receipt.  The caller may block; the Owner thread
    never does.  The Owner's command gate is the only epoch authority."))

(defn owner-port
  "Adapt a compose Owner handle map to `OwnerPort`.

  The handle supplies `:submit-command!` and optionally `:submit!` for
  internal messages.  The adapter performs no serialization of its own; the
  Owner mailbox does."
  [{:keys [submit-command! submit!]}]
  (when-not (or (ifn? submit-command!) (ifn? submit!))
    (throw (ex-info "Owner handle needs :submit-command! or :submit!"
                    {:code :INVALID_OWNER_HANDLE})))
  (reify OwnerPort
    (enqueue! [_ message]
      (if (ifn? submit!)
        (submit! message)
        {:status :refused
         :error {:code :OWNER_ENQUEUE_UNAVAILABLE
                 :message "Owner handle has no :submit! function"}}))
    (submit-command! [_ request]
      (if (ifn? submit-command!)
        (or (submit-command! request)
            {:status :unknown
             :error {:code :OWNER_NO_RECEIPT
                     :message "Owner submit-command! returned no receipt"}
             :action :resync-state})
        {:status :refused
         :error {:code :OWNER_COMMAND_UNAVAILABLE
                 :message "Owner handle has no :submit-command! function"}}))))

(defn submit-effects!
  "Submit every effect to OWNER.  Return one receipt per effect."
  [owner effects]
  (mapv (fn [effect] (enqueue! owner effect)) effects))

(defn session-summary-view
  "Return SESSION without its message bodies, with `:visible-message-count`.

  Only a session that carries its messages can be summarized this way.
  Adapters that keep a derived summary point-read it and call this helper
  only as a per-record fallback."
  [session]
  (let [visible-count
        (count (filter (fn [id]
                         (let [m (get-in session [:messages id])]
                           (and m
                                (not (:deleted? m))
                                (not (:folded? m)))))
                       (:message-order session)))]
    (-> session
        (dissoc :messages :message-order)
        (assoc :visible-message-count visible-count))))

(defn read-session-summary
  "Read one session record without its message bodies.

  List and preflight callers need the committed session fields (status,
  revision, drafts, model binding) but never the `:messages` map or
  `:message-order`.  An adapter that implements `SessionSummaryRead`
  point-reads its derived summary; every other adapter falls back to
  `read-session` and this helper strips the bodies before the value reaches
  the caller so list scans stay small.  Returns the same result shape as
  `read-session`."
  [persistence session-id]
  (if (satisfies? SessionSummaryRead persistence)
    (read-session-summary-doc persistence session-id)
    (let [result (read-session persistence session-id)]
      (if (= :found (:status result))
        (assoc result :session (session-summary-view (:session result)))
        result))))

(defn- commit-error
  [message data]
  {:status :unknown
   :error (merge {:code :PERSISTENCE_ERROR
                  :message message}
                 data)})

(defn- revision-ok?
  "A commit may create revision 0 or advance a stored revision."
  [stored expected value]
  (let [stored-revision (:revision stored)
        value-revision (:revision value)]
    (and (integer? expected)
         (not (neg? expected))
         (integer? value-revision)
         (not (neg? value-revision))
         (= expected (or stored-revision 0))
         (if stored
           (> value-revision stored-revision)
           (= 0 value-revision)))))

(defn- cas-value!
  "Compare and set one path in an atom.  RETURNS a persistence result."
  [state path expected value]
  (loop []
    (let [current @state
          stored (get-in current path)
          stored-revision (:revision stored)]
      (cond
        (not= expected (or stored-revision 0))
        {:status :conflict :revision stored-revision}

        (not (revision-ok? stored expected value))
        {:status :conflict :revision stored-revision}

        (compare-and-set! state current (assoc-in current path value))
        {:status :committed :revision (:revision value)}

        :else
        (recur)))))

(defn- delete-value!
  [state path expected]
  (loop []
    (let [current @state
          stored (get-in current path)
          stored-revision (:revision stored)]
      (cond
        (nil? stored)
        {:status :missing}

        (not= expected stored-revision)
        {:status :conflict :revision stored-revision}

        (compare-and-set! state current
                          (update-in current (butlast path) dissoc (last path)))
        {:status :deleted :revision stored-revision}

        :else
        (recur)))))

(defrecord MemoryPersistence [state]
  SessionPersistence
  (read-session [_ session-id]
    (if-let [session (get-in @state [:sessions session-id])]
      {:status :found :session session}
      {:status :missing}))

  (commit-session! [_ expected-revision session]
    (loop []
      (let [current @state
            stored (get-in current [:sessions (:session-id session)])
            identity (:creation-command session)
            claim (when identity
                    (get-in current [:session-commands identity]))
            claim-session (when claim
                            (get-in current [:sessions (:session-id claim)]))
            duplicate
            (or (when (and claim-session
                           (not= (:session-id claim-session)
                                 (:session-id session)))
                  claim-session)
                (when identity
                  (some (fn [[session-id candidate]]
                          (when (and (= identity
                                        (:creation-command candidate))
                                     (not= session-id
                                           (:session-id session)))
                            candidate))
                        (:sessions current))))
            next (cond-> (assoc-in current
                                   [:sessions (:session-id session)]
                                   session)
                   (and claim (nil? claim-session))
                   (update :session-commands dissoc identity))]
        (cond
          duplicate
          {:status :duplicate :session duplicate}

          (not= expected-revision (or (:revision stored) 0))
          {:status :conflict :revision (:revision stored)}

          (not (revision-ok? stored expected-revision session))
          {:status :conflict :revision (:revision stored)}

          (compare-and-set! state current next)
          {:status :committed :revision (:revision session)}

          :else
          (recur)))))

  (list-session-ids [_]
    {:status :ok :ids (vec (sort (keys (:sessions @state))))})

  (delete-session! [_ expected-revision session-id]
    (loop []
      (let [current @state
            stored (get-in current [:sessions session-id])
            stored-revision (:revision stored)]
        (cond
          (nil? stored)
          {:status :missing}

          (not= (long (or expected-revision 0))
                (long (or stored-revision 0)))
          {:status :conflict :revision stored-revision}

          :else
          (let [next (-> current
                         (update :sessions dissoc session-id)
                         (update :session-commands
                                 (fn [claims]
                                   (into {}
                                         (remove (fn [[_ claim]]
                                                   (= session-id
                                                      (:session-id claim)))
                                         claims)))))]
            (if (compare-and-set! state current next)
              {:status :deleted :revision stored-revision}
              (recur)))))))

  (read-config [_]
    (if-let [config (:config @state)]
      {:status :found :config config}
      {:status :missing}))

  (commit-config! [_ expected-revision config]
    ;; The app settings service commits a config body without :revision and
    ;; expects the store to assign the next one, like the real adapter.
    (let [stored (get-in @state [:config])
          actual (long (or (:revision stored) 0))
          value (if (contains? config :revision)
                  config
                  (assoc config :revision (if stored (inc actual) 0)))]
      (cas-value! state [:config] expected-revision value)))

  SessionCommandLookup
  (find-session-by-command [_ identity]
    (if-let [claim (get-in @state [:session-commands identity])]
      (if-let [session (get-in @state [:sessions (:session-id claim)])]
        {:status :found :session session}
        {:status :missing})
      (or (some (fn [[_ session]]
                  (when (= identity (:creation-command session))
                    {:status :found :session session}))
                (:sessions @state))
          {:status :missing})))

  SessionCommandClaim
  (claim-session-command! [_ identity session-id]
    (loop []
      (let [current @state
            target (get-in current [:sessions session-id])
            existing (get-in current [:session-commands identity])
            existing-session (when existing
                               (get-in current
                                       [:sessions (:session-id existing)]))]
        (cond
          (nil? target)
          {:status :missing}

          (not= :active (:status target))
          {:status :missing}

          (and existing (= session-id (:session-id existing)))
          {:status :unchanged}

          (and existing existing-session)
          {:status :duplicate :session existing-session}

          :else
          (let [next (assoc-in current [:session-commands identity]
                               {:identity identity
                                :session-id session-id
                                :source :reuse})]
            (if (compare-and-set! state current next)
              {:status :claimed}
              (recur))))))))

(defn memory-persistence
  "A reference in-process persistence adapter.

  It is useful for tests and for the first assembly step.  It provides the
  same revision semantics as a real store; it does not pretend to survive a
  process crash."
  ([] (memory-persistence {}))
  ([{:keys [sessions config]}]
   (->MemoryPersistence
    (atom {:sessions (or sessions {})
           :config config
           :session-commands {}}))))

(defn memory-state
  "Return a snapshot of the reference adapter's stored data."
  [persistence]
  @(:state persistence))

(defrecord RecordingOwner [messages]
  OwnerPort
  (enqueue! [_ message]
    (let [index (count @messages)]
      (swap! messages conj message)
      {:status :accepted :index index}))
  (submit-command! [_ request]
    ;; A recorder has no serial state machine, so it must not claim a
    ;; business acceptance it cannot produce.
    (swap! messages conj {:kind :session/command :request request})
    {:status :unknown
     :error {:code :OWNER_NOT_RUNNING
             :message "recording owner does not execute commands"}
     :action :resync-state}))

(defn recording-owner
  "A test Owner that records every enqueued message."
  []
  (->RecordingOwner (atom [])))

(defn recorded
  "Return the messages recorded by a `recording-owner`."
  [owner]
  @(:messages owner))

(defn clear-recorded!
  "Remove recorded messages and return the owner."
  [owner]
  (reset! (:messages owner) [])
  owner)
