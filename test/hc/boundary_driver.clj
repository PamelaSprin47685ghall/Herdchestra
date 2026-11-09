(ns hc.boundary-driver
  "Deterministic seven-boundary driver for the protected workspace protocol.

  Coverage: first layer (control state) and second layer (file entrance) of
  the AGENTS.md §10.1 three-layer model.  It does not claim anything about
  the third layer (transport adaptation).

  The driver runs each participant command on its own thread and stops it at
  one of seven named boundaries:

    :read-complete     the participant finished a lock-free read
    :queued-for-w      about to enter the single write entrance W
    :in-gate-checked   board eligibility and the under-W pre-apply hook ran
    :dry-run-complete  prepare-plan produced a Prepared value
    :applying          begin-apply! fixed the :applying qualification
    :applied           bytes are on disk, board publication is pending
    :board-published   the pure control publication finished

  The production entry points have no native pause hook.  Two boundaries are
  raised by the driver's own command or injected callbacks, three by a
  per-world wrapper around the board adapter, and two by process-global vars:

    :read-complete     the driver's :read command
    :in-gate-checked   the request's :pre-apply! callback
    :applying          the wrapped BoardAdapter (begin-apply!)
    :applied           the WorkspaceGate's :apply-fn closure
    :board-published   the wrapped BoardAdapter (publish-applied!/failure!)
    :dry-run-complete  prepare/prepare-plan, wrapped with alter-var-root
    :queued-for-w      gate/acquire!, wrapped with alter-var-root

  Environment actions are explicit facts, not hidden timers:

    :shell-write      managed shell stand-in; acquires and releases W
    :external-write   external program; bypasses W completely
    :observe          lock-free file observation
    :delay-request    store a command under a deterministic token
    :resume-delay     run a stored command now (no sleep)
    :drop-response    run a command without returning its receipt
    :hard-failure     run a commit whose :on-op throws; no rollback
    :never-satisfied  create a wait on an unfinished task

  Environment actions record one stable, stamp-free fact in the log while
  returning the raw fact (including real stamps) to the caller.

  The two process-global wrappers are installed with `alter-var-root` (the
  long-lived form of `with-redefs`) because the API must pause across several
  `step!`/`resume!` calls.  `close!` restores both original vars.  Protocol
  method vars are not a reliable cross-call-site seam, so the board adapter
  is wrapped as an object instead.  The driver is a serial test tool: only
  one driver may be open in one JVM.

  IMPORTANT: this driver mutates process-global vars with `alter-var-root`.
  It MUST be used serially.  Every caller MUST call `close!` in a `finally`.
  Every test namespace MUST install a `use-fixtures :each` that closes the
  current driver (including when an assertion fails), plus a
  `use-fixtures :once` that asserts every altered var is identical to its
  original value.  A second `new-driver` while one is still open throws
  `:DRIVER_ALREADY_OPEN`.

  No `src/` file is modified.  The only runtime mutation is the temporary
  var-root installation described above."
  (:require [clojure.string :as str]
            [hc.compose.workspace.board :as cboard]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.applier :as applier]
            [hc.core.workspace-gate.board :as gboard]
            [hc.core.workspace-gate.error :as err]
            [hc.core.workspace-gate.gate :as gate]
            [hc.core.workspace-gate.prepare :as prepare]
            [hc.core.workspace-gate.stamp :as stamp]
            [hc.core.workspace-gate.test-support :as ts])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileTime)
           (java.time Instant)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(set! *warn-on-reflection* true)

(declare park-current!)
(declare run-to-done!)

(def api-file
  "The read-only dependency in the minimal model."
  "api.txt")

(def client-file
  "The caller the ordinary participant edits."
  "client.txt")

(def fast-symbol
  "One fast-judgment symbol for several distinct contents (AGENTS.md §10.2)."
  "S1")

(def boundaries
  [:read-complete :queued-for-w :in-gate-checked :dry-run-complete
   :applying :applied :board-published])

(def token-confirm
  "The explicit confirmation required by take-or-die."
  :i-hereby-confirm-i-die-without-regret)

;; ---------------------------------------------------------------------------
;; Minimal finite world

(defn- ok!
  [result op]
  (when-not (:ok? result)
    (throw (ex-info (str "world setup failed at " (name op))
                    {:op op :result result})))
  result)

(defn- swarm!
  [kernel command]
  (ok! (swarm/execute! kernel command) (:op command)))

(defn- driver-board
  "Wrap the compose BoardAdapter so the driver sees the begin-apply and
  publication boundaries at the composition edge.

  Protocol method vars are not a reliable cross-call-site seam: a compiled
  caller may dispatch straight to the interface method.  Wrapping the adapter
  object keeps these boundaries local to the world instead of mutating a
  process-global protocol var."
  [hooks delegate]
  (let [^hc.core.workspace_gate.board.BoardAdapter d delegate]
    (reify gboard/BoardAdapter
      (check-eligibility! [_ request]
        (.check_eligibility_BANG_ d request))

      (begin-apply! [_ request summary]
        (let [permit (.begin_apply_BANG_ d request summary)]
          (when-let [driver (:driver @hooks)]
            (park-current! driver :applying))
          permit))

      (publish-applied! [_ permit result]
        (let [published (.publish_applied_BANG_ d permit result)]
          (when-let [driver (:driver @hooks)]
            (park-current! driver :board-published))
          published))

      (publish-failure! [_ permit failure]
        (let [published (.publish_failure_BANG_ d permit failure)]
          (when-let [driver (:driver @hooks)]
            (park-current! driver :board-published))
          published)))))

(defn new-world
  "Build the first-layer minimal finite model.

  Two ordinary participants (:a, :b), one token slot occupied by :holder,
  three tasks (splittable parent, atomic task, :needs-token task) and two
  files.  The file contents are discrete symbols; `fast-symbol` maps both
  contents to the same `(mtime, size)` judgment symbol.

  Options:
  :root        use an existing workspace root; otherwise a temporary one is
               created and owned by the returned world.
  :token-holder? when false, leave the token slot free and :holder alive;
               the default installs :holder through take-or-die."
  ([] (new-world {}))
  ([{:keys [root token-holder?] :or {token-holder? true}}]
   (let [owned? (nil? root)
         root (or root (ts/temp-root))]
     (ts/write! root api-file "A")
     (ts/write! root client-file "B")
     (let [kernel (swarm/create {:verify-read-set (wg/read-set-verifier root)})
           a (:agent-id (swarm! kernel {:op :register-actor :slot-id :slot-a}))
           b (:agent-id (swarm! kernel {:op :register-actor :slot-id :slot-b}))
           h (:agent-id (swarm! kernel {:op :register-actor :slot-id :slot-holder}))
           parent (:task-id
                   (swarm! kernel
                           {:op :create-task
                            :actor-id a
                            :owner-id a
                            :goal "keep the caller compatible with the dependency"
                            :remaining-work "inspect api and update the caller"
                            :done-when "the caller uses the current api contract"}))
           atomic (:task-id
                   (swarm! kernel
                           {:op :create-task
                            :actor-id b
                            :owner-id b
                            :goal "write the dependency note"
                            :remaining-work "replace the discrete content symbol"
                            :done-when "the note carries the requested symbol"}))
           token (:task-id
                  (swarm! kernel
                          {:op :create-task
                           :actor-id a
                           :goal "run the token-bound verification"
                           :remaining-work "observe the file after the edit"
                           :done-when "the observation is published"
                           :needs-token true}))
           _ (when token-holder?
               (swarm! kernel {:op :take-or-die
                               :actor-id h
                               :request-id :setup-token-competition
                               :confirm token-confirm}))
           claim-a (:claim-id
                    (swarm! kernel {:op :claim
                                    :actor-id a
                                    :task-id parent
                                    :atomicity-reason "driver setup: A owns the parent"}))
           claim-b (:claim-id
                    (swarm! kernel {:op :claim
                                    :actor-id b
                                    :task-id atomic
                                    :atomicity-reason "driver setup: B owns the atomic task"}))
           claim-h (when token-holder?
                     (:claim-id
                      (swarm! kernel {:op :claim
                                      :actor-id h
                                      :task-id token
                                      :atomicity-reason "driver setup: the holder owns the token task"})))
           hooks (atom {})
           delegate (cboard/adapter kernel)
           board (driver-board hooks delegate)
           g (gate/write-gate)
           wg* (wg/workspace-gate
                {:root root
                 :gate g
                 :board board
                 :apply-fn (fn [prepared opts]
                             (let [result (applier/apply-plan! prepared opts)]
                               (when-let [d (:driver @hooks)]
                                 (park-current! d :applied))
                               result))})
           world {:root root
                  :owned-root? owned?
                  :kernel kernel
                  :gate g
                  :wg wg*
                  :hooks hooks
                  :layout {:files {api-file {:symbol "A" :fast-symbol fast-symbol}
                                   client-file {:symbol "B" :fast-symbol fast-symbol}}
                           :tasks {:parent parent
                                   :atomic atomic
                                   :token token}}
                  :read-sets {:parent #{api-file}
                              :atomic #{client-file}}
                  :actors {:a a :b b :holder h}
                  :tasks {:parent parent :atomic atomic :token token}
                  :claims {:a {:task-id parent :claim-id claim-a}
                           :b {:task-id atomic :claim-id claim-b}
                           :holder (when claim-h
                                     {:task-id token :claim-id claim-h})}}]
       world))))

(defn cleanup-world!
  "Delete the workspace root when this world created it."
  [world]
  (when (:owned-root? world)
    (ts/delete-tree (:root world)))
  nil)

(defn world->board
  [world]
  (swarm/snapshot (:kernel world)))

(defn file-content
  [world path]
  (ts/read! (:root world) path))

(defn fast-symbol-of
  [world path]
  (get-in world [:layout :files path :fast-symbol] fast-symbol))

(defn current-stamp
  "Current `mtime+size` stamp of PATH, or nil when absent."
  [world path]
  (stamp/current-stamp (stamp/path-of (:root world) path)))

(defn- read-content-or-nil
  [world path]
  (try
    (ts/read! (:root world) path)
    (catch Throwable _ nil)))

(defn- stable-value
  "Remove absolute mtimes and workspace roots from a log value.

  Environment actions may return real stamps and absolute paths to their
  caller, but the log must stay byte-stable across runs in different
  temporary directories."
  [root x]
  (let [prefix (str (stamp/root-path root) java.io.File/separator)]
    (cond
      (map? x)
      (if (and (contains? x :mtime-sec)
               (contains? x :mtime-nsec)
               (contains? x :size))
        {:size (:size x)}
        (into {}
              (map (fn [[k v]]
                     [(stable-value root k) (stable-value root v)]))
              x))

      (vector? x) (mapv #(stable-value root %) x)
      (set? x) (into #{} (map #(stable-value root %)) x)
      (sequential? x) (mapv #(stable-value root %) x)

      (string? x)
      (if (str/starts-with? x prefix)
        (subs x (count prefix))
        x)

      :else x)))

(defn same-stamp-different-content
  "Replace PATH with NEW-CONTENT while preserving its (mtime, size) stamp.

  The two contents must have the same UTF-8 byte length.  The constructor
  throws when the file system does not preserve the exact stamp: a weaker
  construction would silently give the protocol a real content detector it
  does not have.  Returns an updated world map; the real file and the
  driver's workspace are changed in place."
  [world path new-content]
  (let [root (:root world)
        p (stamp/path-of root path)
        before (stamp/current-stamp p)
        old-content (String. (Files/readAllBytes p) StandardCharsets/UTF_8)]
    (when-not (= (alength (.getBytes ^String old-content StandardCharsets/UTF_8))
                 (alength (.getBytes ^String new-content StandardCharsets/UTF_8)))
      (throw (ex-info "same-stamp construction requires equal byte length"
                      {:path path
                       :before old-content
                       :after new-content})))
    (ts/write! root path new-content)
    (Files/setLastModifiedTime
     p
     (FileTime/from (Instant/ofEpochSecond (long (:mtime-sec before))
                                          (long (:mtime-nsec before)))))
    (let [after (stamp/current-stamp p)]
      (when-not (stamp/same-stamp? before after)
        (throw (ex-info "the file system did not preserve the stamp"
                        {:path path :before before :after after})))
      (-> world
          (assoc-in [:layout :files path :symbol] new-content)
          (assoc-in [:layout :files path :fast-symbol] fast-symbol)
          (assoc-in [:stamp-equivalence path]
                    {:before before :after after
                     :content-before old-content
                     :content-after new-content})))))

;; ---------------------------------------------------------------------------
;; Invariants and observations

(defn- gate-invariants
  [g]
  (let [held? (gate/held? g)
        owner (gate/owner g)]
    (vec (concat
          (when (and held? (nil? owner))
            [{:invariant :gate-held-without-owner}])
          (when (and (not held?) (some? owner))
            [{:invariant :gate-owner-without-permit :owner owner}])
          (when (neg? (gate/queued g))
            [{:invariant :negative-gate-queue :queued (gate/queued g)}])))))

(defn- action-invariants
  [world]
  (let [b (swarm/snapshot (:kernel world))
        actions (:actions b)
        actors (:actors b)
        phases #{:claimed :preparing :waiting-gate :applying :running
                 :uncertain :finished}
        active (remove (fn [[_ a]] (= :finished (:phase a))) actions)
        requests (frequencies (keep (comp :request-id val) actions))]
    (vec (concat
          (for [[aid a] actions
                :when (not (contains? phases (:phase a)))]
            {:invariant :action-phase :action-id aid :phase (:phase a)})
          (for [[aid a] active
                :when (nil? (get-in b [:tasks (:task-id a)]))]
            {:invariant :active-action-without-task
             :action-id aid :task-id (:task-id a)})
          (for [[rid n] requests
                :when (> n 1)]
            {:invariant :duplicate-action-request :request-id rid :count n})
          (for [[aid a] active
                :let [active-aid (get-in actors [(:agent-id a) :active-action-id])]
                :when (not= active-aid aid)]
            {:invariant :active-action-not-registered
             :action-id aid :actor-id (:agent-id a)
             :actor-active-action-id active-aid})
          (for [[id actor] actors
                :let [aid (:active-action-id actor)]
                :when (and aid (nil? (get actions aid)))]
            {:invariant :actor-points-at-missing-action
             :agent-id id :action-id aid})
          (for [[id actor] actors
                :let [aid (:active-action-id actor)]
                :when (and aid (contains? actions aid)
                           (not= id (get-in actions [aid :agent-id])))]
            {:invariant :actor-action-owner-mismatch
             :agent-id id :action-id aid})))))

(defn world-invariants
  "Control-state and board invariants for the current world.

  `hc.core.swarm-stm/check-invariants` covers the kernel records; this adds
  the workspace-gate board/action facts the file contract depends on."
  [world]
  (vec (concat (swarm/check-invariants (:kernel world))
               (gate-invariants (:gate world))
               (action-invariants world))))

(defn- file-fact
  [root path]
  (let [p (stamp/path-of root path)
        bytes (Files/readAllBytes p)]
    {:content (String. ^bytes bytes StandardCharsets/UTF_8)
     :size (alength ^bytes bytes)}))

(defn- observation*
  [driver]
  (let [world (:world @driver)
        b (swarm/snapshot (:kernel world))
        g (:gate world)]
    {:board-version (:board-version b)
     :tasks (into (sorted-map)
                  (map (fn [[k t]]
                         [k (select-keys t [:state :owner-id :claim-id
                                            :needs-token :wait-for :parent-id
                                            :child-ids :rev])]))
                  (:tasks b))
     :actors (into (sorted-map)
                   (map (fn [[k a]]
                          [k (select-keys a [:slot-id :role :life-state
                                             :active-action-id])]))
                   (:actors b))
     :claims (into (sorted-map)
                   (map (fn [[k c]]
                          [k (select-keys c [:task-id :agent-id :phase])]))
                   (:claims b))
     :actions (into (sorted-map)
                    (map (fn [[k a]]
                           [k (select-keys a [:task-id :agent-id :claim-id
                                              :request-id :kind :phase])]))
                    (:actions b))
     :token-slot (select-keys (:token-slot b) [:holder-id :term-id :state])
     :gate {:held? (gate/held? g)
            :owner (gate/owner g)
            :queued (gate/queued g)}
     :files (into (sorted-map)
                  (map (fn [path] [path (file-fact (:root world) path)]))
                  [api-file client-file])}))

(defn observation
  "Comparable world snapshot: board + file symbols/sizes + gate holder + queue.

  Absolute mtimes are deliberately absent: two runs with the same commands
  must produce byte-identical logs even though the wall clock differs."
  [driver]
  (observation* driver))

;; ---------------------------------------------------------------------------
;; Driver state, parking and event delivery

(defn- task-id
  [world x]
  (or (get-in world [:layout :tasks x]) x))

(defn- agent-id-of
  "The kernel allocates agent ids; participants are addressed by role key."
  [world participant]
  (get-in world [:actors participant] participant))

(defn- new-request-id
  [driver pid]
  (let [n (:request-counter (swap! driver update :request-counter inc))]
    (keyword (str (name pid) "-req-" n))))

(defn- command-request-id
  "Use the command id, else the armed drop id, else a fresh one."
  [driver pid command]
  (or (:request-id command)
      (get-in @driver [:drops pid :request-id])
      (new-request-id driver pid)))

(defn- park!
  [driver pid boundary]
  (let [q ^LinkedBlockingQueue (get-in @driver [:participants pid :queue])
        _ (when-not q
            (throw (ex-info "park without a participant queue"
                            {:participant pid :boundary boundary})))
        park-id (:park-counter (swap! driver update :park-counter inc))
        resume (promise)]
    (swap! driver assoc-in [:participants pid :park]
           {:park-id park-id :boundary boundary :resume resume})
    (.put q {:type :parked :participant pid :boundary boundary})
    @resume
    (swap! driver update-in [:participants pid :park]
           (fn [park]
             (when-not (= park-id (:park-id park))
               park)))
    nil))

(defn- park-current!
  "Park the participant whose command is currently advancing.  A direct
  caller with no current participant (for example a test that invokes the
  workspace gate itself) is not paused."
  [driver boundary]
  (when-not (:closed? @driver)
    (when-let [pid (:current @driver)]
      (park! driver pid boundary))))

(defn- record!
  [driver entry]
  (if (:suppress-log? @driver)
    entry
    (let [entry (assoc entry :seq (inc (count (:log @driver))))]
      (swap! driver update :log conj entry)
      entry)))

(defn- with-suppressed-log
  "Run F without adding the participant step/resume entries it triggers.
  End-to-end environment actions record one environment entry instead."
  [driver f]
  (swap! driver assoc :suppress-log? true)
  (try
    (f)
    (finally
      (swap! driver assoc :suppress-log? false))))

(defn- await-event!
  [driver pid]
  (let [q ^LinkedBlockingQueue (get-in @driver [:participants pid :queue])
        timeout (long (:resume-timeout-ms @driver))]
    (or (.poll q timeout TimeUnit/MILLISECONDS)
        {:type :waiting :participant pid})))

(defn- event->result
  [driver pid event]
  (case (:type event)
    :parked
    (do
      (swap! driver assoc-in [:participants pid :status] :parked)
      {:state :parked-at
       :participant pid
       :boundary (:boundary event)
       :world (:world @driver)
       :observation (observation* driver)})

    :done
    (let [drop (get-in @driver [:drops pid])
          board-version (swarm/board-version (:kernel (:world @driver)))
          before (:board-version-before drop)]
      (swap! driver update :drops dissoc pid)
      (swap! driver assoc-in [:participants pid :status] :done)
      (swap! driver assoc-in [:participants pid :result]
             (when-not drop (:result event)))
      (swap! driver assoc :current nil)
      (if drop
        {:state :done
         :participant pid
         :dropped? true
         :request-id (:request-id drop)
         :board-advanced? (boolean (and before (> board-version before)))
         :board-version board-version
         :result nil
         :world (:world @driver)
         :observation (observation* driver)}
        {:state :done
         :participant pid
         :result (:result event)
         :world (:world @driver)
         :observation (observation* driver)}))

    :error
    (do
      (swap! driver assoc-in [:participants pid :status] :error)
      (swap! driver assoc :current nil)
      {:state :error
       :participant pid
       :error (:error event)
       :world (:world @driver)
       :observation (observation* driver)})

    :waiting
    (do
      (swap! driver assoc-in [:participants pid :status] :running)
      {:state :waiting
       :participant pid
       :on :write-gate
       :world (:world @driver)
       :observation (observation* driver)})))

(defn- outcome-of
  [result]
  (cond-> (dissoc result :world :result :error)
    (:result result) (assoc :result-class
                            (or (get-in result [:result :status])
                                (get-in result [:result :error])
                                (get-in result [:result :ok?])))
    (:error result) (assoc :error (:error result))))

;; ---------------------------------------------------------------------------
;; Participant commands

(defn- execute-read!
  [driver pid {:keys [path]}]
  (let [world (:world @driver)
        session (wg/session (:root world))
        text (wg/cat session path)
        basis (select-keys (wg/snapshot session) [:read-set :checks])]
    (swap! driver assoc-in [:participants pid :read-basis] basis)
    (park! driver pid :read-complete)
    {:op :read :path path :text text}))

(defn- execute-commit!
  [driver pid command]
  (let [world (:world @driver)
        root (:root world)
        basis (or (:read-basis command)
                  (get-in @driver [:participants pid :read-basis]))
        session (wg/session root)
        _ (when basis
            (swap! (:state session) merge basis))
        _ (doseq [{:keys [path content]} (:writes command)]
            (wg/write session path content))
        _ (when-let [{:keys [path content]} (:write command)]
            (wg/write session path content))
        batch (wg/snapshot session)
        claim (merge (get-in world [:claims pid])
                     (select-keys command [:task-id :claim-id]))
        request-id (command-request-id driver pid command)
        hard-failure (:hard-failure command)
        on-op (when hard-failure
                (fn [index _operation]
                  (when (= index (long (get hard-failure :op-index 0)))
                    (throw (ex-info
                            "injected process-level hard failure"
                            {:injected? true
                             :boundary (:boundary hard-failure :applied)
                             :op-index index})))))]
    (wg/commit! (:wg world)
                {:request-id request-id
                 :actor-id (agent-id-of world pid)
                 :task-id (:task-id claim)
                 :claim-id (:claim-id claim)
                 :batch batch
                 :on-op on-op
                 :pre-apply! (fn [_request]
                               (park-current! driver :in-gate-checked)
                               nil)})))

(defn- execute-finish!
  [driver pid {:keys [task read-path result-summary claim-id request-id]}]
  (let [world (:world @driver)
        kernel (:kernel world)
        g (:gate world)
        port {:acquire (fn [owner]
                         ((get-in @driver [:orig :acquire!]) g owner))
              :release (fn [permit] (gate/release! permit))}
        read (when read-path
               (wg/read-file (:root world) read-path))
        claim (merge (get-in world [:claims pid])
                     (when claim-id {:claim-id claim-id}))
        request-id (or request-id
                       (get-in @driver [:drops pid :request-id]))]
    (swarm/execute-under-write-gate!
     kernel port pid
     (cond-> {:op :finish
              :actor-id (agent-id-of world pid)
              :task-id (task-id world task)
              :claim-id (:claim-id claim)
              :result-summary (or result-summary "driver finish")
              :read-set (when read
                          [{:path (:path read) :stamp (:stamp read)}])}
       request-id (assoc :request-id request-id)))))

(defn- execute-command!
  [driver pid {:keys [op] :as command}]
  (let [world (:world @driver)
        kernel (:kernel world)]
    (case op
      :read
      (execute-read! driver pid command)

      :commit
      (execute-commit! driver pid command)

      :finish
      (execute-finish! driver pid command)

      :claim
      (let [rid (or (:request-id command)
                    (get-in @driver [:drops pid :request-id]))]
        (swarm/execute! kernel
                        (cond-> {:op :claim
                                 :actor-id (agent-id-of world pid)
                                 :task-id (task-id world (:task command))
                                 :atomicity-reason (or (:atomicity-reason command)
                                                       "driver claim")}
                          rid (assoc :request-id rid))))

      :split
      (swarm/execute! kernel
                      {:op :split
                       :actor-id (agent-id-of world pid)
                       :task-id (task-id world (:task command))
                       :remaining-work (or (:remaining-work command)
                                           "child results remain")
                       :children (:children command)
                       :request-id (command-request-id driver pid command)})

      :take-token
      (swarm/execute! kernel
                      {:op :take-or-die
                       :actor-id (agent-id-of world pid)
                       :request-id (command-request-id driver pid command)
                       :confirm token-confirm})

      :park-slot
      (let [rid (or (:request-id command)
                    (get-in @driver [:drops pid :request-id]))]
        (swarm/execute! kernel
                        (cond-> {:op :park-slot
                                 :actor-id (agent-id-of world pid)
                                 :observed-cursor (swarm/board-version kernel)}
                          rid (assoc :request-id rid))))

      :resume-slot
      (swarm/execute! kernel
                      {:op :wake-all})

      (throw (ex-info "unknown driver command" {:op op :command command})))))

(defn- execute-environment!
  [driver {:keys [op] :as command}]
  (let [world (:world @driver)]
    (case op
      :shell-write
      (let [g (:gate world)
            permit ((get-in @driver [:orig :acquire!]) g :shell)]
        (try
          (ts/write! (:root world) (:path command) (:content command))
          {:status :SHELL_WRITTEN
           :path (:path command)
           :content (:content command)}
          (finally
            (gate/release! permit))))

      :shell-wait
      (let [g (:gate world)
            root (:root world)
            path (:path command)
            content (:content command)
            marker (:marker-path command)
            timeout-ms (long (or (:timeout-ms command) 30000))
            _ (when-not (and (string? path) (string? content))
                (throw (ex-info
                        "shell-wait requires string :path and :content"
                        {:error :INVALID_ENVIRONMENT_ACTION
                         :op :shell-wait
                         :path path
                         :content content})))
            permit (gate/try-acquire! g :shell)]
        (if-not permit
          {:shell-wait? true
           :status :WAITING_FOR_WRITE_GATE
           :reason :write-gate-held
           :owner (gate/owner g)}
          (try
            ;; The marker is written after W is held, so a test can read both
            ;; the owner and the Shell's wait condition without polling a
            ;; semaphore.  The wait itself is a real file-content condition.
            (when marker
              (ts/write! root marker (str "waiting:" path "=" content)))
            (let [deadline (+ (System/currentTimeMillis) timeout-ms)
                  result (loop []
                           (let [value (try
                                         (ts/read! root path)
                                         (catch Throwable _ nil))]
                             (cond
                               (= content value) :met
                               (>= (System/currentTimeMillis) deadline) :timeout
                               :else (do (Thread/sleep 20) (recur)))))]
              (if (= :met result)
                {:shell-wait? true
                 :status :SHELL_CONDITION_MET
                 :path path
                 :content content
                 :marker-path marker}
                {:shell-wait? true
                 :status :SHELL_WAIT_TIMEOUT
                 :path path
                 :content content
                 :marker-path marker}))
            (finally
              (gate/release! permit)))))

      :external-write
      (let [root (:root world)
            path (:path command)
            _ (ts/write! root path (:content command))
            stamp (stamp/current-stamp (stamp/path-of root path))]
        {:external-write? true
         :bypasses-write-gate? true
         :path path
         :content (:content command)
         :stamp stamp})

      :observe
      {:path (:path command)
       :content (file-content world (:path command))}

      :delay-request
      (let [participant (:participant command)
            token (or (:token command)
                      (keyword
                       (str "delay-"
                            (:delay-counter
                             (swap! driver update :delay-counter inc)))))
            cmd (:command command)
            _ (when-not cmd
                (throw (ex-info "delay-request requires :command"
                                {:error :INVALID_ENVIRONMENT_ACTION
                                 :op :delay-request})))
            basis (get-in @driver [:participants participant :read-basis])]
        (swap! driver assoc-in [:delayed token]
               {:participant participant
                :command cmd
                :basis basis})
        {:delayed? true
         :token token
         :participant participant
         :pending? true
         :board-version (swarm/board-version (:kernel world))})

      :resume-delay
      (let [token (:token command)
            entry (get-in @driver [:delayed token])]
        (when-not (:command entry)
          (throw (ex-info "unknown delayed request"
                          {:error :UNKNOWN_DELAY :token token})))
        (swap! driver update :delayed dissoc token)
        (let [{:keys [participant command basis]} entry
              previous (get-in @driver [:participants participant :read-basis])]
          (swap! driver assoc-in [:participants participant :read-basis] basis)
          (try
            (let [run (with-suppressed-log
                        driver
                        #(run-to-done! driver participant command))]
              {:resumed? true
               :token token
               :participant participant
               :result (:result run)
               :board-version (swarm/board-version (:kernel world))})
            (finally
              (swap! driver assoc-in [:participants participant :read-basis]
                     previous)))))

      :drop-response
      (let [participant (:participant command)
            cmd (:command command)]
        (if (nil? cmd)
          (let [rid (or (:request-id command)
                        (new-request-id driver participant))]
            (swap! driver assoc-in [:drops participant]
                   {:request-id rid
                    :board-version-before
                    (swarm/board-version (:kernel world))})
            {:dropped? true
             :armed? true
             :participant participant
             :request-id rid
             :board-advanced? false
             :board-version (swarm/board-version (:kernel world))})
          (let [rid (or (:request-id cmd)
                        (:request-id command)
                        (new-request-id driver participant))
                cmd (assoc cmd :request-id rid)
                before (swarm/board-version (:kernel world))]
            (swap! driver assoc-in [:drops participant]
                   {:request-id rid :board-version-before before})
            (try
              (let [run (with-suppressed-log
                          driver
                          #(run-to-done! driver participant cmd))]
                {:dropped? true
                 :request-id rid
                 :board-advanced? (:board-advanced? run)
                 :board-version-before before
                 :board-version-after (swarm/board-version (:kernel world))
                 :result nil})
              (finally
                (swap! driver update :drops dissoc participant))))))

      :hard-failure
      (let [participant (:participant command)
            cmd (:command command)
            _ (when-not cmd
                (throw (ex-info "hard-failure requires :command"
                                {:error :INVALID_ENVIRONMENT_ACTION
                                 :op :hard-failure})))
            chard (assoc cmd :hard-failure
                         {:boundary (:boundary command :applied)
                          :op-index (long (get command :op-index 1))})
            run (with-suppressed-log
                  driver
                  #(run-to-done! driver participant chard))
            result (:result run)]
        {:hard-failure? true
         :boundary (:boundary command :applied)
         :site (select-keys result
                            [:status :outcome :file-changed?
                             :applied-ops :failed-op :failed-index
                             :remaining-ops :observation-basis :summary])
         :files (into {}
                      (map (fn [path]
                             [path (read-content-or-nil world path)]))
                      [api-file client-file "notes.txt"])
         :result result})

      :never-satisfied
      (let [kernel (:kernel world)
            holder (agent-id-of world (:participant command :holder))
            wait-task (task-id world (or (:wait-for-task command) :atomic))
            created (swarm/execute!
                     kernel
                     {:op :create-task
                      :actor-id holder
                      :goal "wait for a condition that never becomes true"
                      :remaining-work "keep the never-satisfied wait visible"
                      :done-when "the condition is observed, which never happens"
                      :needs-token true
                      :wait-for #{wait-task}
                      :request-id (or (:request-id command)
                                      :never-satisfied-task)})]
        (when-not (:ok? created)
          (throw (ex-info "never-satisfied task creation failed"
                          {:result created})))
        (let [task-id (:task-id created)
              park (swarm/execute!
                    kernel
                    {:op :park-slot
                     :actor-id holder
                     :observed-cursor (swarm/board-version kernel)})]
          {:never-satisfied? true
           :waiter {:agent-id holder
                    :task-id task-id
                    :wait-for #{wait-task}}
           :responsibility
           (vec (sort (swarm/responsibility kernel holder)))
           :holder-alive? (= :alive
                              (:life-state (swarm/actor kernel holder)))
           :write-gate-held? (gate/held? (:gate world))
           :park-slot (select-keys park [:ok? :error :detail :board-version])
           :board-version (swarm/board-version kernel)}))

      (throw (ex-info "unknown environment action"
                      {:op op :command command})))))

;; ---------------------------------------------------------------------------
;; Seam installation

(defn- install-seams!
  [driver]
  (let [orig {:prepare-plan prepare/prepare-plan
              :acquire! gate/acquire!}]
    (swap! driver assoc :orig orig)
    (alter-var-root
     #'prepare/prepare-plan
     (constantly
      (fn
        ([batch]
         (let [result ((:prepare-plan orig) batch)]
           (when (prepare/prepared? result)
             (park-current! driver :dry-run-complete))
           result))
        ([batch opts]
         (let [result ((:prepare-plan orig) batch opts)]
           (when (prepare/prepared? result)
             (park-current! driver :dry-run-complete))
           result)))))
    (alter-var-root
     #'gate/acquire!
     (constantly
      (fn
        ([g]
         (park-current! driver :queued-for-w)
         ((:acquire! orig) g))
        ([g owner]
         (park-current! driver :queued-for-w)
         ((:acquire! orig) g owner))
        ([g owner opts]
         (park-current! driver :queued-for-w)
         ((:acquire! orig) g owner opts)))))
    driver))

(defn- restore-seams!
  [driver]
  (when-let [orig (:orig @driver)]
    (alter-var-root #'prepare/prepare-plan (constantly (:prepare-plan orig)))
    (alter-var-root #'gate/acquire! (constantly (:acquire! orig))))
  driver)

;; ---------------------------------------------------------------------------
;; Public driver API

(defonce ^:private current-driver (atom nil))

(def ^:private driver-lock (Object.))

(defn new-driver
  "Create a deterministic driver over WORLD.

  Options:
  :resume-timeout-ms how long a step/resume waits for the next boundary
                    before reporting :waiting (default 5000).

  A second driver cannot be installed while the previous one is still open:
  both would rewrite the same global seam vars.  Call `close!` first."
  ([world] (new-driver world {}))
  ([world {:keys [resume-timeout-ms] :or {resume-timeout-ms 5000}}]
   (when-not (and (map? world)
                  (:root world)
                  (:kernel world)
                  (:gate world)
                  (:wg world)
                  (:hooks world))
     (throw (ex-info "new-driver requires a world created by new-world"
                     {:error :INVALID_WORLD
                      :code :invalid-world
                      :message "new-driver requires a world created by new-world"})))
   (locking driver-lock
     (when-let [open @current-driver]
       (when-not (:closed? @open)
         (throw (ex-info
                 "Another boundary driver is still open; call close! first"
                 {:error :DRIVER_ALREADY_OPEN
                  :code :driver-already-open
                  :message "Another boundary driver is still open; call close! first"
                  :hint "close! the active driver before installing another seam set"
                  :open-driver-id (:id @open)}))))
     (let [driver (atom {:id (str (gensym "boundary-driver-"))
                         :world world
                         :participants {}
                         :current nil
                         :log []
                         :request-counter 0
                         :park-counter 0
                         :delay-counter 0
                         :drops {}
                         :delayed {}
                         :suppress-log? false
                         :resume-timeout-ms resume-timeout-ms
                         :closed? false})]
       (try
         (install-seams! driver)
         (catch Throwable t
           ;; A partial alter-var-root install must not outlive the failure.
           (restore-seams! driver)
           (throw t)))
       (swap! (:hooks world) assoc :driver driver)
       (reset! current-driver driver)
       driver))))

(defn step!
  "Start PARTICIPANT's COMMAND and stop at its next boundary.

  COMMAND is data: {:op :read|:commit|:finish|:claim|:split|:take-token|
                    :park-slot :path ... :write {:path p :content c} ...}.
  Returns {:state :parked-at|:done|:error|:waiting ...}."
  [driver participant command]
  (when (:closed? @driver)
    (throw (ex-info "driver is closed" {:participant participant})))
  (let [status (get-in @driver [:participants participant :status])]
    (when (contains? #{:running :parked} status)
      (throw (ex-info "participant already has a live command; resume it first"
                      {:participant participant :status status}))))
  (let [queue (LinkedBlockingQueue.)
        read-basis (get-in @driver [:participants participant :read-basis])]
    ;; Publish the queue and the current participant before the worker can
    ;; reach a seam.  Otherwise a fast command could call `park!` before the
    ;; queue exists, or `park-current!` could see no current participant.
    (swap! driver assoc-in [:participants participant]
           {:status :running
            :queue queue
            :command command
            :read-basis read-basis})
    (swap! driver assoc :current participant)
    (let [thread (future
                   (try
                     (let [result (execute-command! driver participant command)]
                       (.put ^LinkedBlockingQueue queue
                             {:type :done :participant participant :result result}))
                     (catch Throwable t
                       (.put ^LinkedBlockingQueue queue
                             {:type :error
                              :participant participant
                              :error (err/throwable->data t)}))))]
      (swap! driver assoc-in [:participants participant :thread] thread)
      (let [result (event->result driver participant
                                  (await-event! driver participant))]
        (record! driver {:event :step
                         :participant participant
                         :command command
                         :outcome (outcome-of result)})
        result))))

(defn resume!
  "Release PARTICIPANT from its current park and stop at the next boundary
  or completion."
  [driver participant]
  (when (:closed? @driver)
    (throw (ex-info "driver is closed" {:participant participant})))
  (let [entry (get-in @driver [:participants participant])
        park (:park entry)
        status (:status entry)]
    (cond
      (= :done status)
      {:state :done
       :participant participant
       :result (:result entry)
       :world (:world @driver)
       :observation (observation* driver)}

      (nil? entry)
      (throw (ex-info "unknown participant" {:participant participant}))

      park
      (do
        (swap! driver assoc-in [:participants participant :status] :running)
        (swap! driver assoc :current participant)
        (deliver (:resume park) :go)
        (swap! driver update-in [:participants participant :park]
               (fn [current]
                 (when-not (= (:park-id park) (:park-id current))
                   current)))
        (let [result (event->result driver participant
                                    (await-event! driver participant))]
          (record! driver {:event :resume
                           :participant participant
                           :outcome (outcome-of result)})
          result))

      :else
      (do
        (swap! driver assoc :current participant)
        (let [result (event->result driver participant
                                    (await-event! driver participant))]
          (record! driver {:event :resume
                           :participant participant
                           :outcome (outcome-of result)})
          result)))))

(defn run-until!
  "Advance the current participant until BOUNDARY or completion.

  Intermediate boundaries are recorded in the log.  A :waiting result stops
  the run and is returned."
  [driver boundary]
  (loop [result nil]
    (let [pid (:current @driver)]
      (if (nil? pid)
        result
        (let [next-result (resume! driver pid)]
          (cond
            (not= :parked-at (:state next-result)) next-result
            (= boundary (:boundary next-result)) next-result
            :else (recur next-result)))))))

(defn run-to-done!
  "Convenience: step and resume until the participant completes.  Throws on
  :waiting or :error so a test cannot silently hang."
  [driver participant command]
  (loop [result (step! driver participant command)]
    (case (:state result)
      :done result
      :parked-at (recur (resume! driver participant))
      :waiting (throw (ex-info "participant is still blocked on W"
                               {:participant participant
                                :observation (:observation result)}))
      :error (throw (ex-info "participant command failed"
                             {:participant participant
                              :error (:error result)}))
      (throw (ex-info "unexpected driver state"
                      {:result result})))))

(defn environment!
  "Run an explicit environment action outside the participant threads.

  :shell-write     managed shell stand-in: acquires and releases W, writes.
  :external-write  external program: writes without touching W.
  :observe         read a file without W.
  :delay-request   store the next command under a deterministic token; no
                   timer and no commit until `resume-delay!`.
  :resume-delay    run one delayed command with suppressed boundary entries.
  :drop-response   run the command but return no receipt; the board advances.
  :hard-failure    run a commit whose :on-op throws; there is no rollback.
  :never-satisfied create a task waiting on an unfinished task and attempt
                   park-slot for the holder."
  [driver command]
  (when (:closed? @driver)
    (throw (ex-info "driver is closed" {})))
  (let [value (execute-environment! driver command)]
    (record! driver {:event :environment
                     :command command
                     :outcome {:value (stable-value (:root (:world @driver))
                                                    value)
                               :observation (observation* driver)}})
    value))

(defn resume-delay!
  "Run the command stored by `:delay-request` under TOKEN."
  [driver token]
  (environment! driver {:op :resume-delay :token token}))

(defn close!
  "Restore every seam var and cancel remaining participant threads.

  Idempotent: a second call does not restore again or throw."
  [driver]
  (locking driver-lock
    (when-not (:closed? @driver)
      (swap! driver assoc :closed? true)
      (restore-seams! driver)
      (doseq [[_ entry] (:participants @driver)]
        (when-let [park (:park entry)]
          (deliver (:resume park) :go))
        (when-let [thread (:thread entry)]
          (future-cancel thread)
          (try
            (deref thread 1000 nil)
            (catch Throwable _ nil))))
      (swap! (:hooks (:world @driver)) dissoc :driver))
    (when (identical? driver @current-driver)
      (reset! current-driver nil)))
  nil)

;; ---------------------------------------------------------------------------
;; Failure recording and deterministic replay

(defn replay
  "Replay RECORDED-LOG on DRIVER and assert every boundary observation and
  the final world match.  Returns {:final-observation ... :log ...}."
  [driver recorded-log]
  (doseq [entry recorded-log]
    (let [result (case (:event entry)
                   :step (step! driver (:participant entry) (:command entry))
                   :resume (resume! driver (:participant entry))
                   :environment (let [value (environment! driver (:command entry))]
                                  {:state :environment
                                   :value (stable-value (:root (:world @driver))
                                                        value)
                                   :observation (observation* driver)})
                   (throw (ex-info "unknown log event"
                                   {:event (:event entry)})))
          expected (:outcome entry)]
      (when expected
        (let [expected-boundary (:boundary expected)
              expected-state (:state expected)]
          (when (and expected-state
                     (not= expected-state (:state result)))
            (throw (ex-info "replay reached a different state"
                            {:seq (:seq entry)
                             :expected expected-state
                             :actual (:state result)})))
          (when (and expected-boundary
                     (not= expected-boundary (:boundary result)))
            (throw (ex-info "replay stopped at a different boundary"
                            {:seq (:seq entry)
                             :expected expected-boundary
                             :actual (:boundary result)})))
          (when (and (= :environment expected-state)
                     (not= (:value expected) (:value result)))
            (throw (ex-info "replay environment action differs"
                            {:seq (:seq entry)})))
          (when-not (= (:observation expected) (:observation result))
            (throw (ex-info "replay observation differs"
                            {:seq (:seq entry)
                             :expected (:observation expected)
                             :actual (:observation result)})))))))
  {:final-observation (observation* driver)
   :log (:log @driver)})

(defn record-failure!
  "Serialize the current driver log as a literal `deftest` body.

  The returned string can be pasted into a test namespace that refers
  `new-world`, `new-driver`, `replay`, `close!`, `cleanup-world!` and
  `observation` from this namespace.  It contains only EDN-safe data."
  ([driver] (record-failure! driver {}))
  ([driver {:keys [name] :or {name "recorded-driver-failure"}}]
   (let [recorded (:log @driver)
         final (observation* driver)]
     (str "(deftest " name "\n"
          "  (let [world (new-world)]\n"
          "    (try\n"
          "      (let [driver (new-driver world)]\n"
          "        (try\n"
          "          (let [out (replay driver " (pr-str recorded) ")]\n"
          "            (is (= " (pr-str final) "\n"
          "                   (:final-observation out))))\n"
          "          (finally (close! driver))))\n"
          "      (finally (cleanup-world! world)))))\n"))))
