(ns hc.core.workspace-gate-boundary-test
  "F04, F12, F13 and F15 acceptance scenarios on the seven-boundary driver.

  The driver supplies the control-state and file-entrance layers.  F12 keeps
  the managed activity at the `:applied` boundary (bytes installed, board
  publication and W still pending), then contrasts `:external-write`
  (bypasses W; a stale read is found only by the stamp) with `:shell-write`
  (holds W while an ordinary commit queues).  No source namespace is
  modified."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.boundary-driver :as bd]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.gate :as gate]
            [hc.core.workspace-gate.prepare :as prepare])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption OpenOption Paths)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; Fixture: every test owns one world and one driver, and every exit closes
;; the driver before deleting the world.  The driver mutates global var roots,
;; so a failed assertion must not leave those seams installed.

(def ^:dynamic *world* nil)
(def ^:dynamic *driver* nil)

(defn- boundary-fixture
  [f]
  (let [world (bd/new-world)]
    (try
      (let [driver (bd/new-driver world {:resume-timeout-ms 1000})]
        (try
          (binding [*world* world *driver* driver]
            (f))
          (finally
            (bd/close! driver))))
      (finally
        (bd/cleanup-world! world)))))

(use-fixtures :each boundary-fixture)

(defn- seam-restoration-fixture
  "Snapshot the two process-global seams once and prove every driver closed.

  A leftover driver is visible as a wrapper function still installed in
  `prepare/prepare-plan` or `gate/acquire!`; the failure message names the
  unclosed driver rather than silently passing."
  [f]
  (let [prepare-original prepare/prepare-plan
        acquire-original gate/acquire!]
    (try
      (f)
      (finally
        (is (identical? prepare-original prepare/prepare-plan)
            "a driver was left open: prepare/prepare-plan still has a seam wrapper")
        (is (identical? acquire-original gate/acquire!)
            "a driver was left open: gate/acquire! still has a seam wrapper")))))

(use-fixtures :once seam-restoration-fixture)

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- root [] (:root *world*))

(defn- path
  [& parts]
  (Paths/get (str (root)) (into-array String (map str parts))))

(defn- exists?
  [p]
  (Files/exists p (make-array LinkOption 0)))

(defn- board []
  (bd/world->board *world*))

(defn- actor
  [role]
  (get-in *world* [:actors role]))

(defn- action-for
  [snapshot actor-id]
  (last
   (sort-by (comp str :action-id)
            (filter #(= actor-id (:agent-id %))
                    (vals (:actions snapshot))))))

(defn- action-by-request
  [snapshot request-id]
  (some #(when (= request-id (:request-id %)) %)
        (vals (:actions snapshot))))

(defn- wait-until
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline) false
         :else (do (Thread/sleep 10) (recur)))))))

(defn- drive-participant!
  "Resume one participant through every remaining boundary to completion."
  [participant]
  (loop []
    (let [result (bd/resume! *driver* participant)]
      (case (:state result)
        :done result
        :parked-at (recur)
        :waiting (throw (ex-info "participant is still blocked on W"
                                 {:participant participant
                                  :observation (:observation result)}))
        :error (throw (ex-info "participant command failed"
                               {:participant participant
                                :error (:error result)}))
        (throw (ex-info "unexpected driver state"
                        {:participant participant :result result}))))))

(defn- mkfifo!
  [fifo]
  (Files/deleteIfExists fifo)
  (let [process (.start (ProcessBuilder.
                         (into-array String ["mkfifo" (str fifo)])))]
    (when-not (zero? (.waitFor process))
      (throw (ex-info "mkfifo failed" {:path (str fifo)})))))

(defn- start-fifo-reader!
  "Read one chunk so the managed writer can start, then wait for DRAIN
  before reading the rest.  This keeps the writer blocked while the test
  checks that W is held."
  [fifo drain]
  (future
    (with-open [in (Files/newInputStream fifo (make-array OpenOption 0))]
      (let [buffer (byte-array 65536)]
        (when (pos? (.read in buffer))
          @drain
          (loop []
            (when (pos? (.read in buffer))
              (recur))))))))

;; ---------------------------------------------------------------------------
;; F04: batch-internal view order

(deftest f04-batch-view-sees-rename-before-edit
  (testing "rename then edit inside one batch uses the batch view, not the disk"
    (bd/environment! *driver* {:op :shell-write
                               :path "old.txt"
                               :content "old"})
    (let [s (wg/session (root))
          _ (wg/cat s "old.txt")
          _ (wg/mv s "old.txt" "new.txt")
          _ (wg/write s "new.txt" "new")
          batch (wg/snapshot s)
          preview (wg/preview batch)]
      (is (wg/preview? preview) (pr-str preview))
      (is (= [:mv :write] (mapv :op (:ops preview)))
          "the later write is planned against the renamed path")
      (let [result (wg/commit! (:wg *world*)
                               {:request-id :f04-rename-then-edit
                                :actor-id (actor :a)
                                :task-id (get-in *world* [:tasks :parent])
                                :claim-id (get-in *world* [:claims :a :claim-id])
                                :batch batch})]
        (is (= :APPLIED (:status result)))
        (is (not= :FILE_CONFLICT (:status result))
            "the new path is resolved through the batch view, not only on disk")
        (is (= 2 (count (:applied-ops result))))
        (is (false? (exists? (path "old.txt"))))
        (is (= "new" (bd/file-content *world* "new.txt")))
        (let [action (action-by-request (board) :f04-rename-then-edit)
              result-ref (:result-ref action)]
          (is (some? action))
          (is (not (contains? result :last-known-stage))
              "an APPLIED result keeps its existing shape")
          (is (not (contains? result-ref :last-known-stage)))
          (is (not (contains? result-ref :expected-action)))
          (is (not (contains? result-ref :failed-at)))))))
  (testing "writing below a directory deleted in the same batch is a structure conflict"
    (let [directory (path "gone")]
      (Files/createDirectory directory (make-array FileAttribute 0))
      (let [s (wg/session (root))
            _ (wg/rm s "gone")
            failure (try
                      (wg/write s "gone/child.txt" "child")
                      nil
                      (catch clojure.lang.ExceptionInfo e
                        (ex-data e)))]
        (is (= :STRUCTURE_CONFLICT (:status failure)))
        (is (= :missing-parent (:reason failure)))
        (is (false? (:file-changed? failure)))
        (is (Files/isDirectory directory (make-array LinkOption 0))
            "the batch must not recreate the deleted directory during apply")
        (is (false? (exists? (path "gone" "child.txt")))))))
  (is (empty? (bd/world-invariants *world*))))

;; ---------------------------------------------------------------------------
;; F12: managed child versus detached activity

(deftest f12-managed-child-keeps-w-and-detached-is-external
  (testing "a managed publication activity keeps the session, W and responsibility"
    (let [queued (bd/step! *driver* :a
                           {:op :commit
                            :write {:path bd/client-file :content "A1"}})]
      (is (= :queued-for-w (:boundary queued)))
      (let [applied (bd/run-until! *driver* :applied)]
        (is (= :applied (:boundary applied)))
        (is (= "A1" (bd/file-content *world* bd/client-file))
            "the bytes are installed but the session is not settled"))
      (let [observation (bd/observation *driver*)
            snapshot (board)
            running (action-for snapshot (actor :a))]
        (is (true? (get-in observation [:gate :held?]))
            "W is still held by the managed activity")
        (is (= (:request-id running) (get-in observation [:gate :owner])))
        (is (= :applying (:phase running)))
        (is (= (:action-id running)
               (get-in snapshot [:actors (actor :a) :active-action-id]))
            "the running responsibility stays registered")
        (let [stored (wg/action (:wg *world*) (:request-id running))]
          (is (= :pending (:status stored))
              "the commit request is still open, not settled")
          (is (= :applying (:phase stored))))
        (let [queued-b (bd/step! *driver* :b
                                 {:op :commit
                                  :write {:path bd/api-file :content "B1"}})]
          (is (= :parked-at (:state queued-b)))
          (is (= :queued-for-w (:boundary queued-b)))
          (is (= "A"
                 (:content (bd/environment!
                            *driver* {:op :observe :path bd/api-file})))
              "the ordinary commit must not write while the activity runs")))
      (let [published (bd/resume! *driver* :a)]
        (is (= :board-published (:boundary published))))
      (let [done (drive-participant! :a)]
        (is (= :APPLIED (get-in done [:result :status]))))
      (let [done (drive-participant! :b)]
        (is (= :APPLIED (get-in done [:result :status])))
        (is (= "B1" (bd/file-content *world* bd/api-file))))))
  (testing "an external write bypasses W and is found only through the stale stamp"
    (let [target "external-target.txt"
          target-path (path target)
          go (promise)
          external (future
                     @go
                     (bd/environment!
                      *driver*
                      {:op :external-write
                       :path target
                       :content "changed!"}))]
      (try
        (bd/environment! *driver* {:op :shell-write
                                   :path target
                                   :content "initial"})
        ;; The read basis is taken before the external write.
        (bd/run-to-done! *driver* :a {:op :read :path target})
        (let [applied (bd/run-to-done!
                       *driver* :b
                       {:op :commit
                        :write {:path bd/client-file :content "A3"}})]
          (is (= :APPLIED (get-in applied [:result :status]))
              "a protected commit runs while the external activity is pending")
          (is (= "A3" (bd/file-content *world* bd/client-file)))
          (is (nil? (wg/write-gate-owner (:gate *world*)))
              "the pending external activity owns no W"))
        (let [grants-before (gate/grants (:gate *world*))]
          (deliver go true)
          (is (wait-until
               #(and (exists? target-path)
                     (= "changed!" (bd/file-content *world* target)))
               3000)
              "the external write changes the real file")
          (is (= grants-before (gate/grants (:gate *world*)))
              "the external write does not acquire W")
          (is (nil? (wg/write-gate-owner (:gate *world*))))
          (let [result (deref external 5000 ::timeout)]
            (is (map? result))
            (is (true? (:external-write? result)))
            (is (true? (:bypasses-write-gate? result)))))
        (let [conflict (bd/run-to-done!
                        *driver* :a
                        {:op :commit
                         :write {:path bd/client-file :content "A4"}})]
          (is (= :FILE_CONFLICT (get-in conflict [:result :status]))
              "the stale read basis is rejected under W")
          (is (= "changed!" (bd/file-content *world* target))
              "the external result stays on disk")
          (is (= "A3" (bd/file-content *world* bd/client-file))
              "the rejected commit did not write"))
        (bd/run-to-done! *driver* :a {:op :read :path target})
        (let [applied (bd/run-to-done!
                       *driver* :a
                       {:op :commit
                        :write {:path bd/client-file :content "A4"}})]
          (is (= :APPLIED (get-in applied [:result :status])))
          (is (= "A4" (bd/file-content *world* bd/client-file))))
        (finally
          (deliver go true)
          (try (deref external 5000 nil) (catch Throwable _ nil))))))
  (testing "the managed shell-write path holds W while a protected commit queues"
    (let [fifo (path "shell-fifo")
          drain (promise)
          reader (atom nil)
          writer (atom nil)
          grants-before (gate/grants (:gate *world*))
          payload (String. (byte-array 2000000 (byte 120))
                           StandardCharsets/UTF_8)]
      (try
        (mkfifo! fifo)
        (reset! reader (start-fifo-reader! fifo drain))
        (reset! writer
                (future
                  (bd/environment! *driver*
                                   {:op :shell-write
                                    :path "shell-fifo"
                                    :content payload})))
        (is (wait-until #(= :shell (wg/write-gate-owner (:gate *world*)))
                        3000)
            "the managed shell path owns W")
        (is (true? (gate/held? (:gate *world*))))
        (let [queued (bd/step! *driver* :b
                               {:op :commit
                                :write {:path bd/client-file :content "A5"}})]
          (is (= :parked-at (:state queued)))
          (is (= :queued-for-w (:boundary queued)))
          (is (= :shell (wg/write-gate-owner (:gate *world*))))
          (is (true? (gate/held? (:gate *world*))))
          (is (= "A4" (bd/file-content *world* bd/client-file))
              "the queued commit has not written"))
        (deliver drain true)
        (let [writer-result (deref @writer 10000 ::timeout)]
          (is (map? writer-result))
          (is (= :SHELL_WRITTEN (:status writer-result)))
          (is (= (inc grants-before) (gate/grants (:gate *world*)))
              "the managed shell path acquired W exactly once"))
        (is (nil? (wg/write-gate-owner (:gate *world*)))
            "the managed shell write released W")
        (deref @reader 5000 nil)
        (let [done (drive-participant! :b)]
          (is (= :APPLIED (get-in done [:result :status])))
          (is (= "A5" (bd/file-content *world* bd/client-file))))
        (finally
          (deliver drain true)
          (when-let [writer @writer]
            (try (deref writer 10000 nil) (catch Throwable _ nil)))
          (when-let [reader @reader]
            (try (deref reader 10000 nil) (catch Throwable _ nil)))
          (Files/deleteIfExists fifo)))))
  (is (empty? (bd/world-invariants *world*))))

;; ---------------------------------------------------------------------------
;; F13: hard failure at the second file

(deftest f13-hard-failure-at-second-file-keeps-uncertain-site
  (doseq [[p content] [["one.txt" "one"]
                       ["two.txt" "two"]
                       ["three.txt" "three"]]]
    (bd/environment! *driver* {:op :shell-write :path p :content content}))
  (let [s (wg/session (root))
        _ (wg/write s "one.txt" "ONE")
        _ (wg/write s "two.txt" "TWO")
        _ (wg/write s "three.txt" "THREE")
        batch (wg/snapshot s)
        calls (atom [])
        request-id :f13-second-file-crash
        request {:request-id request-id
                 :actor-id (actor :a)
                 :task-id (get-in *world* [:tasks :parent])
                 :claim-id (get-in *world* [:claims :a :claim-id])
                 :batch batch
                 :on-op (fn [index _op]
                          (swap! calls conj index)
                          (when (= 1 index)
                            (throw (ex-info "injected crash at the second file"
                                            {:injected :second-file}))))}
        result (wg/commit! (:wg *world*) request)]
    (is (= :HARD_FAILURE (:status result)))
    (is (= :unknown (:outcome result)))
    (is (= :unknown (:file-changed? result)))
    (is (= :unknown (:content-equality result)))
    (is (= :mtime+size (:observation-basis result)))
    (is (= 1 (:failed-index result)))
    (is (= 1 (count (:applied-ops result))))
    (is (= 1 (count (:remaining-ops result))))
    (is (= [0 1] @calls) "apply stops at the failing operation")
    (is (= :applying (:last-known-stage result)))
    (is (= {:op-index 1} (:failed-at result)))
    (is (string? (:expected-action result)))
    (is (<= (count (:expected-action result)) 300))
    (is (not (re-find #"ONE|TWO|THREE" (:expected-action result)))
        "the expected action carries operation kinds and paths, never content")
    (is (= "ONE" (bd/file-content *world* "one.txt")))
    (is (= "two" (bd/file-content *world* "two.txt")))
    (is (= "three" (bd/file-content *world* "three.txt")))
    (is (not= false (:file-changed? result))
        "the site must stay unknown, not be reported as untouched")
    (let [snapshot (board)
          action (action-by-request snapshot request-id)]
      (is (some? action))
      (is (some? (:action-id action)))
      ;; The action stays :uncertain; the fine last-known stage and failure
      ;; location live in result-ref and the registry for the successor.
      (is (= :uncertain (:phase action))
          "the action's last known stage, action id and failure remain readable")
      (is (= :HARD_FAILURE (get-in action [:result-ref :status])))
      (is (= "injected crash at the second file"
             (get-in action [:result-ref :message])))
      (let [result-ref (:result-ref action)]
        (is (= :applying (:phase result-ref))
            "the coarse phase keeps the existing field")
        (is (= :applying (:last-known-stage result-ref)))
        (is (= {:op-index 1} (:failed-at result-ref)))
        (is (string? (:expected-action result-ref)))
        (is (<= (count (:expected-action result-ref)) 300))
        (is (not (re-find #"ONE|TWO|THREE" (:expected-action result-ref))))
        (is (= result-ref (edn/read-string (pr-str result-ref)))
            "the action result-ref is EDN round-trippable"))
      (is (= (:action-id action)
             (get-in snapshot [:actors (actor :a) :active-action-id]))
          "the uncertain action keeps its responsibility"))
    (let [stored (wg/action (:wg *world*) request-id)]
      (is (= :finished (:status stored)))
      (is (= :uncertain (:phase stored)))
      (is (= 1 (get-in stored [:result :failed-index])))
      (is (= 1 (count (get-in stored [:result :applied-ops]))))
      (is (= 1 (count (get-in stored [:result :remaining-ops]))))
      (is (= :applying (get-in stored [:result :last-known-stage])))
      (is (= {:op-index 1} (get-in stored [:result :failed-at])))
      (is (string? (get-in stored [:result :expected-action]))))
    (let [replayed (wg/commit! (:wg *world*) request)]
      (is (= result replayed))
      (is (= (select-keys result [:applied-ops :remaining-ops :failed-at
                                  :last-known-stage :expected-action :failed-op])
             (select-keys replayed [:applied-ops :remaining-ops :failed-at
                                    :last-known-stage :expected-action :failed-op]))
          "replay returns the identical failure facts")
      (is (= [0 1] @calls)
          "a replayed request-id must not reapply the batch"))
    (let [mismatch (wg/commit!
                    (:wg *world*)
                    (assoc request
                           :batch
                           (assoc batch
                                  :limits
                                  (assoc (:limits batch) :max-path-ops 255))))]
      (is (= :CALL_REPLAY_MISMATCH (:status mismatch)))
      (is (= [0 1] @calls)))
    (is (= "ONE" (bd/file-content *world* "one.txt")))
    (is (= "two" (bd/file-content *world* "two.txt")))
    (is (= "three" (bd/file-content *world* "three.txt")))
    (is (empty? (bd/world-invariants *world*)))))

;; ---------------------------------------------------------------------------
;; finish: its own W port, not the commit queue seam

(deftest finish-waits-for-w-and-verifies-after-acquisition
  (testing "a finish with a read-set waits for W and verifies after acquiring it"
    (let [permit (wg/acquire-write-gate! (:gate *world*) :test-shell)]
      (try
        (let [queued (bd/step! *driver* :a
                               {:op :finish
                                :task :parent
                                :read-path bd/api-file})]
          (is (= :parked-at (:state queued)))
          (is (= :queued-for-w (:boundary queued))
              "the current driver does raise the W-queue boundary for a finish with a read-set")
          (let [waiting (bd/resume! *driver* :a)]
            (is (= :waiting (:state waiting)))
            (is (= :write-gate (:on waiting))
                "finish blocks in its W port after the queue boundary"))
          (is (= :test-shell (wg/write-gate-owner (:gate *world*))))
          (is (true? (gate/held? (:gate *world*))))
          (is (= :ready
                 (get-in (board) [:tasks (get-in *world* [:tasks :parent])
                                  :state]))
              "the task is untouched while finish waits")
          (bd/environment! *driver* {:op :external-write
                                     :path bd/api-file
                                     :content "A2"})
          (is (= "A2" (bd/file-content *world* bd/api-file))
              "the read-set's file changed while finish waited for W"))
        (finally
          (wg/release-write-gate! permit)))
      (let [finished (bd/resume! *driver* :a)]
        (is (= :done (:state finished)))
        (is (= :FILE_CONFLICT (get-in finished [:result :error]))
            "the read-set is verified only after the finish obtains W")
        (is (= :ready
               (get-in (board) [:tasks (get-in *world* [:tasks :parent])
                                :state]))
            "a rejected finish does not complete the task")
        (is (= "A2" (bd/file-content *world* bd/api-file)))))
    (let [done (bd/run-to-done! *driver* :a
                                {:op :finish
                                 :task :parent
                                 :read-path bd/api-file})]
      (is (true? (get-in done [:result :ok?])))
      (is (= :done
             (get-in (board) [:tasks (get-in *world* [:tasks :parent])
                              :state])))))
  (testing "a finish without file facts does not take W"
    (let [permit (wg/acquire-write-gate! (:gate *world*) :test-shell)]
      (try
        (let [done (bd/run-to-done! *driver* :b
                                    {:op :finish
                                     :task :atomic})]
          (is (= :done (:state done)))
          (is (true? (get-in done [:result :ok?])))
          (is (= :done
                 (get-in (board) [:tasks (get-in *world* [:tasks :atomic])
                                  :state])))
          (is (= :test-shell (wg/write-gate-owner (:gate *world*)))
              "the pure board finish never touched W"))
        (finally
          (wg/release-write-gate! permit)))))
  (is (empty? (bd/world-invariants *world*))))

;; ---------------------------------------------------------------------------
;; F15: input/wake/park ordering and a tool call blocked on W

(deftest f15-wake-all-covers-parked-slots-without-releasing-w
  (let [wake-input (atom nil)]
    (testing "a wake notification does not release W or cancel a blocked tool call"
      (let [permit (wg/acquire-write-gate! (:gate *world*) :test-shell)
            queued (bd/step! *driver* :b
                             {:op :commit
                              :write {:path bd/api-file :content "B1"}})]
        (try
          (is (= :parked-at (:state queued)))
          (is (= :queued-for-w (:boundary queued)))
          (let [waiting (bd/resume! *driver* :b)]
            (is (= :waiting (:state waiting)))
            (is (= :write-gate (:on waiting))))
          (let [input (swarm/execute!
                       (:kernel *world*)
                       {:op :user-input
                        :text "wake while the tool call waits"
                        :actor-id (actor :a)
                        :message-id "f15-wake-while-waiting"})
                wake (some #(when (= :wake-all (:type %)) %)
                           (:events input))]
            (is (true? (:ok? input)))
            (is (some? wake))
            (is (contains? (:reasons wake) :user-input))
            (is (empty? (:woken wake))
                "a blocked commit is not a parked slot, so it is not woken as one")
            (is (true? (get-in (bd/observation *driver*) [:gate :held?])))
            (is (= :test-shell
                   (get-in (bd/observation *driver*) [:gate :owner])))
            (is (not-any? #(= (actor :b) (:agent-id %))
                          (vals (:actions (board))))
                "the blocked call has no apply action yet")
            (is (not (contains? (swarm/waiters (:kernel *world*))
                                (actor :b))))
            (reset! wake-input (:input-id input)))
          (let [still-waiting (bd/resume! *driver* :b)]
            (is (= :waiting (:state still-waiting))
                "the wake notification does not promise an immediate W release"))
          (finally
            (wg/release-write-gate! permit)))
        (let [done (drive-participant! :b)]
          (is (= :APPLIED (get-in done [:result :status])))
          (is (= "B1" (bd/file-content *world* bd/api-file))))))
    (testing "all tasks finish while the wake input is still unhandled"
      (doseq [[participant task read-path]
              [[:a :parent bd/api-file]
               [:b :atomic bd/client-file]
               [:holder :token bd/api-file]]]
        (let [done (bd/run-to-done! *driver* participant
                                    {:op :finish
                                     :task task
                                     :read-path read-path})]
          (is (= :done (:state done)))
          (is (= :finish (get-in done [:result :op])))
          (is (true? (get-in done [:result :ok?])))))
      (is (every? #(= :done (:state %)) (vals (:tasks (board)))))
      (is (true? (swarm/global-progressable? (:kernel *world*)))
          "the unhandled wake input is the only remaining non-blocking work"))
    (testing "an input committed before park-slot refuses the sleep"
      (let [input-id @wake-input
            refused (bd/run-to-done! *driver* :a {:op :park-slot})
            detail (get-in refused [:result :detail])]
        (is (= false (get-in refused [:result :ok?])))
        (is (= :PROGRESS_AVAILABLE (get-in refused [:result :error])))
        (is (contains? (set (:reasons detail)) :unhandled-input))
        (is (not (contains? (set (:reasons detail)) :ready-task))
            "only the unhandled input blocks the sleep")
        (is (contains? (set (:unhandled-inputs detail)) input-id))
        (is (string? (:summary detail)))
        (is (not (contains? (swarm/waiters (:kernel *world*)) (actor :a)))
            "a refused sleep leaves no registration behind")
        (let [resolved (swarm/execute!
                        (:kernel *world*)
                        {:op :resolve-input
                         :actor-id (actor :a)
                         :input-id input-id
                         :summary "the test consumed the wake input"})]
          (is (true? (:ok? resolved)))
          (is (false? (swarm/global-progressable? (:kernel *world*)))))))
    (testing "park-slot followed by input wakes the parked individual"
      (let [parked (bd/run-to-done! *driver* :a {:op :park-slot})]
        (is (true? (get-in parked [:result :ok?])))
        (is (contains? (swarm/waiters (:kernel *world*)) (actor :a)))
        (let [input (swarm/execute!
                     (:kernel *world*)
                     {:op :user-input
                      :text "wake the parked slot"
                      :actor-id (actor :b)
                      :message-id "f15-after-park"})
              wake (some #(when (= :wake-all (:type %)) %)
                         (:events input))]
          (is (true? (:ok? input)))
          (is (contains? (:woken input) (actor :a))
              "the input wake-all must cover the already parked individual")
          (is (some? wake))
          (is (contains? (:woken wake) (actor :a)))
          (is (contains? (:reasons wake) :user-input))
          (is (empty? (swarm/waiters (:kernel *world*)))
              "waiters are cleared in the same commit as the wake"))))
    (is (empty? (bd/world-invariants *world*)))))
