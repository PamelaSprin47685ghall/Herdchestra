(ns hc.core.workspace-gate
  "Public facade for the protected file contract.

  Composition order: reader (lock-free observations) -> prepare (in-memory
  batch view and dry-run) -> gate (single FIFO write entrance W) -> applier
  (deterministic installation) -> board adapter (pure control publication).
  Shell sessions share `write-gate` with commits but do not call the
  preparer."
  (:refer-clojure :exclude [cat])
  (:require [hc.core.workspace-gate.applier :as applier]
            [hc.core.workspace-gate.board :as board]
            [hc.core.workspace-gate.commit :as commit]
            [hc.core.workspace-gate.error :as err]
            [hc.core.workspace-gate.gate :as gate]
            [hc.core.workspace-gate.prepare :as prepare]
            [hc.core.workspace-gate.reader :as reader]
            [hc.core.workspace-gate.stamp :as stamp]))

;; Reading ------------------------------------------------------------------

(defn read-file
  "Read a UTF-8 file without W.  Returns content, full-file stamp and
  `:changed-during-read?`."
  ([root path] (read-file root path {}))
  ([root path opts] (reader/read-text root path opts)))

(defn stat-path
  "Return the public stat shape.  Missing paths are not errors."
  [root path]
  (reader/stat-path root path))

(defn list-dir
  "Non-recursive pure probe.  Directory entries end with `/`."
  [root path]
  (reader/list-dir root path))

;; Batch view ---------------------------------------------------------------

(defn new-batch [root & [opts]] (prepare/new-batch root opts))
(defn session [root & [opts]] (prepare/session root opts))
(defn snapshot [session] (prepare/snapshot session))

(defn cat [session path] (prepare/cat session path))
(defn stat [session path] (prepare/stat session path))
(defn ls [session path] (prepare/ls session path))
(defn write [session path content] (prepare/write session path content))
(defn mkdir [session path] (prepare/mkdir session path))
(defn rm [session path] (prepare/rm session path))
(defn mv
  ([session source target] (prepare/mv session source target))
  ([session source target opts] (prepare/mv session source target opts)))

(defn preview
  "Lock-free dry-run.  Returns a `Preview` or an ordinary rejection."
  [batch]
  (prepare/preview batch))

(defn prepared? [x] (prepare/prepared? x))
(defn preview? [x] (prepare/preview? x))

;; Gate ---------------------------------------------------------------------

(defn write-gate [] (gate/write-gate))
(defn acquire-write-gate! [gate & [owner opts]] (gate/acquire! gate owner (or opts {})))
(defn try-acquire-write-gate!
  ([gate owner] (gate/try-acquire! gate owner))
  ([gate owner timeout-ms] (gate/try-acquire! gate owner timeout-ms)))
(defn release-write-gate! [permit] (gate/release! permit))
(defn write-gate-held? [gate] (gate/held? gate))
(defn write-gate-owner [gate] (gate/owner gate))
(defn write-gate-queued [gate] (gate/queued gate))
(defn set-write-gate-admission! [gate f] (gate/set-admission! gate f))
(defn clear-write-gate-admission! [gate] (gate/clear-admission! gate))
(defn write-gate-admission [gate] (gate/admission gate))

(defmacro with-write-gate
  "Evaluate body while holding W, releasing it on every exit path."
  [binding & body]
  `(gate/with-write-gate ~binding ~@body))

;; Commit -------------------------------------------------------------------

(defn workspace-gate [opts] (commit/workspace-gate opts))
(defn commit! [wg request] (commit/commit! wg request))
(defn action [wg request-id] (commit/action wg request-id))
(defn retry-publish! [wg request-id] (commit/retry-publish! wg request-id))
(defn request-stop! [wg request-id] (commit/request-stop! wg request-id))
(defn clear-stop! [wg request-id] (commit/clear-stop! wg request-id))
(defn apply-plan!
  ([prepared] (applier/apply-plan! prepared))
  ([prepared opts] (applier/apply-plan! prepared opts)))

;; Reference board ----------------------------------------------------------

(defn memory-board [] (board/memory-board))
(defn set-actor! [b actor-id alive?] (board/set-actor! b actor-id alive?))
(defn set-claim! [b claim-id valid?] (board/set-claim! b claim-id valid?))
(defn fail-publishes! [b n] (board/fail-publishes! b n))
(defn fail-failure-publishes! [b n] (board/fail-failure-publishes! b n))
(defn board-action [b request-id] (board/board-action b request-id))

;; Result classification ----------------------------------------------------

(defn rejection? [result] (err/rejection? result))
(defn applied? [result] (err/applied? result))
(defn hard-failure? [result] (err/hard-failure? result))
(defn describe-result [result] (err/describe result))

(defn current-stamp
  "Current `{:mtime-sec :mtime-nsec :size}` of a regular file, or nil."
  [root path]
  (stamp/current-stamp (stamp/path-of root path)))

(defn verify-read-set
  "Verify a swarm-finish read-set against the real workspace.

  Accepted entries are `{:path p :stamp s}`, `{:path p :kind :file}`,
  `{:path p :kind :missing}` or `{:path p :kind :directory}`; a map of path
  to stamp is also accepted.  Returns `{:ok? true}` or
  `{:ok? false :reason :FILE_CONFLICT|:STRUCTURE_CONFLICT ...}`; a kind
  mismatch is a structure conflict.  This is the phase-two
  `:verify-read-set` seam for `hc.core.swarm-stm`; the caller must hold the
  shared write gate around the finish transition when the contract requires
  it."
  [root read-set]
  (let [entries (cond
                  (and (map? read-set) (contains? read-set :path))
                  [read-set]

                  (map? read-set)
                  (mapv (fn [[p s]] {:path p :stamp s}) read-set)

                  (sequential? read-set) (vec read-set)

                  (nil? read-set) []

                  :else (err/failure :UNSUPPORTED_OPERATION :bad-read-set
                                     "Read-set must be a sequence or map"
                                     {:actual (some-> read-set class str)}))]
    (if-not (sequential? entries)
      entries
      (let [conflicts
            (vec
             (keep
              (fn [{:keys [path stamp kind unstable? changed-during-read?]}]
                (if (nil? path)
                  {:path nil :reason :missing-path}
                  (let [path-obj (stamp/path-of root path)
                        p (str path-obj)
                        actual-kind (stamp/kind-of path-obj)
                        actual-stamp (when (= :file actual-kind)
                                       (stamp/current-stamp path-obj))]
                    (cond
                      (or unstable? changed-during-read?)
                      {:path p :reason :changed-during-read
                       :expected stamp :actual actual-stamp}

                      (and kind (not= kind actual-kind))
                      {:path p :reason :kind-mismatch
                       :structure? true
                       :expected kind :actual actual-kind}

                      (and stamp (not (stamp/same-stamp? stamp actual-stamp)))
                      {:path p :reason :stamp-mismatch
                       :expected stamp :actual actual-stamp}

                      (and (nil? stamp) (nil? kind))
                      {:path p :reason :missing-basis}

                      :else nil))))
              entries))]
        (if (empty? conflicts)
          {:ok? true :observation-basis :mtime+size}
          (let [structure? (boolean (some #(= :kind-mismatch (:reason %))
                                          conflicts))]
            {:ok? false
             :reason (if structure? :STRUCTURE_CONFLICT :FILE_CONFLICT)
             :observation-basis :mtime+size
             :message "Read-set no longer matches the real workspace"
             :conflicts conflicts}))))))

(defn read-set-verifier
  "Return a `read-set -> verdict` closure for the swarm kernel seam."
  [root]
  (fn [read-set] (verify-read-set root read-set)))
