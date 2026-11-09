(ns hc.boundary-driver-test
  "Acceptance tests for the seven-boundary deterministic driver.

  Coverage note (AGENTS.md L28-01): these tests exercise the first layer
  (control state) and the second layer (file entrance).  They say nothing
  about transport adaptation or model judgement."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.boundary-driver :as bd]
            [hc.core.workspace-gate.gate :as gate]
            [hc.core.workspace-gate.prepare :as prepare]))

(def ^:dynamic *driver*
  "The driver opened by the current test, so the :each fixture can close it
  even when the test body throws before its own finally."
  nil)

(defn- track-driver!
  [driver]
  (when (thread-bound? #'*driver*)
    (set! *driver* driver))
  driver)

(defn- clear-tracked-driver!
  []
  (when (thread-bound? #'*driver*)
    (set! *driver* nil))
  nil)

(defn- open-driver
  ([world] (open-driver world {}))
  ([world opts] (track-driver! (bd/new-driver world opts))))

(defmacro with-driver
  [[world driver] & body]
  `(let [w# (bd/new-world)]
     (try
       (let [d# (open-driver w#)]
         (try
           (let [~world w# ~driver d#]
             ~@body)
           (finally
             (bd/close! d#)
             (clear-tracked-driver!))))
       (finally
         (bd/cleanup-world! w#)))))

;; Only two boundaries pause inside a process-global var:
;;   :queued-for-w     gate/acquire!
;;   :dry-run-complete prepare/prepare-plan
;; The other five are local seams and need no global restoration:
;;   :read-complete    raised inside the driver's :read command
;;   :in-gate-checked  the request's injected :pre-apply! callback
;;   :applying         the wrapped BoardAdapter object
;;   :applied          the WorkspaceGate's injected :apply-fn closure
;;   :board-published  the wrapped BoardAdapter object
(def ^:private seam-vars
  {:prepare-plan #'prepare/prepare-plan
   :acquire! #'gate/acquire!})

(defn- seam-values
  []
  (into {} (map (fn [[k v]] [k @v])) seam-vars))

(defn- assert-seams-identical
  [expected]
  (doseq [[k v] seam-vars]
    (is (identical? (get expected k) @v)
        (str (name k) " seam was not restored to its original value"))))

(defn- close-current-driver-fixture
  [f]
  (binding [*driver* nil]
    (try
      (f)
      (finally
        (when-let [driver *driver*]
          (bd/close! driver))
        (clear-tracked-driver!)))))

(defn- seam-restoration-fixture
  [f]
  (let [before (seam-values)]
    (try
      (f)
      (finally
        (assert-seams-identical before)))))

(use-fixtures :each close-current-driver-fixture)
(use-fixtures :once seam-restoration-fixture)

(defn- action-phase
  [observation agent-id]
  (->> (vals (:actions observation))
       (filter (fn [action] (= agent-id (:agent-id action))))
       (map :phase)
       first))

(defn- wait-until
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline) false
         :else (do (Thread/sleep 10) (recur)))))))

(deftest minimal-model-has-two-ordinary-actors-one-holder-three-tasks-two-files
  (with-driver [w d]
    (let [board (bd/world->board w)
          actors (vals (:actors board))
          tasks (vals (:tasks board))]
      (is (= 2 (count (filter #(= :normal (:role %)) actors))))
      (is (= 1 (count (filter #(= :holder (:role %)) actors))))
      (is (= 3 (count tasks)))
      (is (= 1 (count (filter :needs-token tasks))))
      (is (= 3 (count (:tasks w))))
      (is (= 2 (count (:files (:layout w)))))
      (is (= "A" (bd/file-content w bd/api-file)))
      (is (= "B" (bd/file-content w bd/client-file)))
      (is (= bd/fast-symbol (bd/fast-symbol-of w bd/api-file)))
      (is (= bd/fast-symbol (bd/fast-symbol-of w bd/client-file)))
      (is (= #{bd/api-file} (get-in w [:read-sets :parent])))
      (is (= #{bd/client-file} (get-in w [:read-sets :atomic])))
      (is (= (get-in w [:actors :holder])
             (get-in board [:token-slot :holder-id])))
      (is (= :held (get-in board [:token-slot :state])))
      (is (empty? (bd/world-invariants w))
          (pr-str (bd/world-invariants w)))
      (is (= :ready (get-in board [:tasks (get-in w [:tasks :parent]) :state])))
      (is (= :ready (get-in board [:tasks (get-in w [:tasks :atomic]) :state])))
      (is (= :ready (get-in board [:tasks (get-in w [:tasks :token]) :state])))
      (is (empty? (get-in board [:tasks (get-in w [:tasks :parent]) :child-ids]))
          "the parent is still splittable"))))

(deftest seven-boundaries-stop-at-exact-points
  (with-driver [w d]
    (testing "read complete"
      (let [result (bd/step! d :a {:op :read :path bd/api-file})]
        (is (= :parked-at (:state result)))
        (is (= :read-complete (:boundary result)))
        (is (empty? (bd/world-invariants w)))
        (is (= :done (:state (bd/resume! d :a))))))
    (testing "write gate queue, under-W check, dry-run, applying, applied, publish"
      (let [queued (bd/step! d :a
                             {:op :commit
                              :write {:path bd/client-file :content "A1"}})]
        (is (= :parked-at (:state queued)))
        (is (= :queued-for-w (:boundary queued)))
        (is (false? (get-in queued [:observation :gate :held?]))
            "the participant is queued, not holding W")
        (is (empty? (bd/world-invariants w)))

        (let [checked (bd/resume! d :a)]
          (is (= :in-gate-checked (:boundary checked)))
          (is (true? (get-in checked [:observation :gate :held?])))
          (is (some? (get-in checked [:observation :gate :owner]))
              "W is owned by the commit request")
          (is (empty? (bd/world-invariants w))))

        (let [dried (bd/resume! d :a)]
          (is (= :dry-run-complete (:boundary dried)))
          (is (some? (get-in dried [:observation :gate :owner])))
          (is (empty? (bd/world-invariants w))))

        (let [applying (bd/resume! d :a)]
          (is (= :applying (:boundary applying)))
          (is (= :applying
                 (action-phase (:observation applying) (get-in w [:actors :a]))))
          (is (empty? (bd/world-invariants w))))

        (let [applied (bd/resume! d :a)]
          (is (= :applied (:boundary applied)))
          (is (= "A1" (bd/file-content w bd/client-file)))
          (is (= :applying
                 (action-phase (:observation applied) (get-in w [:actors :a])))
              "bytes are installed but the board result is not published yet")
          (is (empty? (bd/world-invariants w))))

        (let [published (bd/resume! d :a)]
          (is (= :board-published (:boundary published)))
          (is (= :finished
                 (action-phase (:observation published) (get-in w [:actors :a]))))
          (is (empty? (bd/world-invariants w))))

        (let [done (bd/resume! d :a)
              result (:result done)]
          (is (= :done (:state done)))
          (is (= :APPLIED (:status result)))
          (is (true? (:published? result)))
          (is (= :mtime+size (:observation-basis result)))
          (is (= :not-claimed (:content-equality result)))
          (is (false? (get-in done [:observation :gate :held?]))
              "the commit released W")
          (is (empty? (bd/world-invariants w))))))))

(deftest run-until-advances-the-current-participant
  (with-driver [w d]
    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (let [queued (bd/step! d :a
                           {:op :commit
                            :write {:path bd/client-file :content "A1"}})]
      (is (= :queued-for-w (:boundary queued)))
      (let [applying (bd/run-until! d :applying)]
        (is (= :applying (:boundary applying))
            "intermediate boundaries are crossed and logged")
        (is (empty? (bd/world-invariants w))))
      (let [published (bd/run-until! d :board-published)]
        (is (= :board-published (:boundary published))))
      (let [done (bd/resume! d :a)]
        (is (= :done (:state done)))
        (is (= :APPLIED (get-in done [:result :status])))
        (is (empty? (bd/world-invariants w)))))))

(deftest observation-has-no-absolute-mtimes
  (with-driver [w d]
    (let [observation (bd/observation d)
          fact (get-in observation [:files bd/api-file])]
      (is (= 7 (count bd/boundaries)))
      (is (= #{:content :size} (set (keys fact)))
          "logs stay byte-stable across runs with different wall clocks")
      (is (= "A" (:content fact)))
      (is (= 1 (:size fact))))))

(deftest interleaved-conflict-then-reread
  (with-driver [w d]
    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (bd/run-to-done! d :b {:op :commit
                           :write {:path bd/api-file :content "A2"}})
    (is (= "A2" (bd/file-content w bd/api-file)))
    (is (= "B" (bd/file-content w bd/client-file)))

    (let [conflict (bd/run-to-done! d :a
                                    {:op :commit
                                     :write {:path bd/client-file :content "A1"}})
          result (:result conflict)]
      (is (= :FILE_CONFLICT (:status result))
          "the stale read basis is rejected under W")
      (is (= "B" (bd/file-content w bd/client-file))
          "an ordinary rejection leaves the write target untouched")
      (is (= "A2" (bd/file-content w bd/api-file))
          "the other participant's applied result stays")
      (is (empty? (bd/world-invariants w)))
      (is (some? (get-in (bd/world->board w)
                         [:tasks (get-in w [:tasks :parent]) :claim-id]))
          "A keeps its claim and does not die on a normal conflict"))

    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (let [applied (bd/run-to-done! d :a
                                   {:op :commit
                                    :write {:path bd/client-file :content "A1"}})]
      (is (= :APPLIED (get-in applied [:result :status])))
      (is (= "A1" (bd/file-content w bd/client-file))))))

(deftest same-stamp-different-content-is-accepted
  (with-driver [w d]
    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (let [before (get-in (bd/observation d) [:files bd/api-file])
          world' (bd/same-stamp-different-content w bd/api-file "B")
          after (get-in (bd/observation d) [:files bd/api-file])]
      (is (= "B" (bd/file-content world' bd/api-file)))
      (is (= "S1" (bd/fast-symbol-of world' bd/api-file)))
      (is (= (:size before) (:size after))
          "distinct contents share the same fast-judgment size")
      (is (contains? (:stamp-equivalence world') bd/api-file)
          "the constructor proved that mtime and size were preserved")
      (let [applied (bd/run-to-done! d :a
                                     {:op :commit
                                      :write {:path bd/client-file
                                              :content "A1"}})
            result (:result applied)]
        (is (= :APPLIED (:status result))
            "the protocol only uses mtime+size; it cannot see the content change")
        (is (= :not-claimed (:content-equality result))
            "the result does not claim content equality")
        (is (= "B" (bd/file-content w bd/api-file)))))))

(deftest replay-reproduces-every-boundary-and-the-final-world
  (let [world (bd/new-world)
        driver (open-driver world)]
    (try
      (bd/run-to-done! driver :a {:op :read :path bd/api-file})
      (bd/run-to-done! driver :b {:op :commit
                                  :write {:path bd/api-file :content "A2"}})
      (bd/run-to-done! driver :a {:op :commit
                                  :write {:path bd/client-file :content "A1"}})
      (let [recorded (:log @driver)
            expected (bd/observation driver)]
        ;; Only one driver may own the global seam vars; close the first
        ;; before opening the replay driver.
        (bd/close! driver)
        (let [world' (bd/new-world)
              driver' (open-driver world')]
          (try
            (let [out (bd/replay driver' recorded)]
              (is (= expected (:final-observation out)))
              (is (= (mapv #(get-in % [:outcome :boundary]) recorded)
                     (mapv #(get-in % [:outcome :boundary]) (:log @driver')))
                  "the same command sequence stops at the same boundaries"))
            (finally
              (bd/close! driver')
              (bd/cleanup-world! world')))))
      (finally
        (bd/close! driver)
        (bd/cleanup-world! world)))))

(deftest record-failure-emits-a-compilable-deftest
  (with-driver [w d]
    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (bd/run-to-done! d :a {:op :commit
                           :write {:path bd/client-file :content "A1"}})
    (let [source (bd/record-failure!
                  d {:name "recorded-seven-boundary-scenario"})]
      (is (re-find #"\(deftest recorded-seven-boundary-scenario" source))
      (is (re-find #"\(replay driver " source))
      (is (re-find #"new-world" source))
      (is (some? (read-string (str "(do " source ")")))
          "the emitted form is readable Clojure data"))))

(deftest driver-close-restores-every-altered-var
  (let [before (seam-values)
        world (bd/new-world)
        driver (open-driver world)]
    (try
      (doseq [[k v] seam-vars]
        (is (not (identical? (get before k) @v))
            (str (name k) " seam was not installed")))
      (is (nil? (bd/close! driver)))
      (doseq [[k v] seam-vars]
        (is (identical? (get before k) @v)
            (str (name k) " seam was not restored")))
      (testing "a second close is a no-op, not a second restore"
        (let [sentinels (into {} (map (fn [[k _]] [k (fn [& _] k)]))
                              seam-vars)
              originals (seam-values)]
          (try
            (doseq [[k v] seam-vars]
              (alter-var-root v (constantly (get sentinels k))))
            (is (nil? (bd/close! driver)) "close! is idempotent")
            (doseq [[k v] seam-vars]
              (is (identical? (get sentinels k) @v)
                  (str (name k) " was restored by a second close!")))
            (finally
              (doseq [[k v] seam-vars]
                (alter-var-root v (constantly (get originals k))))))))
      (finally
        (bd/close! driver)
        (bd/cleanup-world! world)))))

(deftest driver-reentry-is-refused
  (with-driver [w d]
    (is (some? d))
    (let [other-world (bd/new-world)]
      (try
        (let [outcome (try
                        (let [unexpected (bd/new-driver other-world)]
                          ;; The guard failed.  Close the unexpected owner
                          ;; before reporting so the failure cannot pollute
                          ;; subsequent tests.
                          (bd/close! unexpected)
                          {:unexpected? true})
                        (catch clojure.lang.ExceptionInfo e
                          {:data (ex-data e)}))]
          (is (nil? (:unexpected? outcome))
              "new-driver must refuse a second open driver")
          (let [data (:data outcome)]
            (is (= :DRIVER_ALREADY_OPEN (:error data)))
            (is (= :driver-already-open (:code data)))
            (is (string? (:hint data)))
            (is (string? (:open-driver-id data)))))
        (finally
          (bd/cleanup-world! other-world))))))

(deftest external-write-bypasses-w-and-invalidates-the-stamp
  (with-driver [w d]
    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (let [before (bd/current-stamp w bd/api-file)
          external (bd/environment! d {:op :external-write
                                       :path bd/api-file
                                       :content "A2"})
          after (bd/current-stamp w bd/api-file)]
      (is (:external-write? external))
      (is (:bypasses-write-gate? external))
      (is (= bd/api-file (:path external)))
      (is (= 2 (get-in external [:stamp :size])))
      (is (not= before after)
          "the external write changed the mtime+size stamp")
      (is (false? (get-in (bd/observation d) [:gate :held?]))
          "the external write did not acquire W")
      (let [conflict (bd/run-to-done!
                      d :a
                      {:op :commit
                       :write {:path bd/client-file :content "A1"}})]
        (is (= :FILE_CONFLICT (get-in conflict [:result :status])))
        (is (= "A2" (bd/file-content w bd/api-file)))
        (is (= "B" (bd/file-content w bd/client-file))
            "the conflicting commit wrote nothing"))
      (is (empty? (bd/world-invariants w))))))

(deftest delay-request-is-explicit-and-composes-with-external-write
  (with-driver [w d]
    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (let [delayed (bd/environment!
                   d {:op :delay-request
                      :participant :a
                      :token :delay-1
                      :command {:op :commit
                                :request-id :delayed-commit-1
                                :write {:path bd/client-file
                                        :content "A1"}}})]
      (is (:delayed? delayed))
      (is (:pending? delayed))
      (is (= :delay-1 (:token delayed)))
      (is (= "B" (bd/file-content w bd/client-file))
          "the delayed command has not committed")
      (let [external (bd/environment!
                      d {:op :external-write
                         :path bd/api-file
                         :content "A2"})
            resumed (bd/resume-delay! d :delay-1)
            result (:result resumed)]
        (is (:external-write? external))
        (is (:resumed? resumed))
        (is (= :FILE_CONFLICT (:status result))
            "the delayed read basis is rejected after the external write")
        (is (= "A2" (bd/file-content w bd/api-file))
            "the external content stays")
        (is (= "B" (bd/file-content w bd/client-file))
            "the delayed commit wrote nothing"))
      (is (empty? (bd/world-invariants w))))))

(deftest dropped-response-replays-the-same-conclusion-without-board-advance
  (with-driver [w d]
    (let [command {:op :take-token :request-id :dropped-token-1}
          dropped (bd/environment!
                   d {:op :drop-response
                      :participant :a
                      :command command})
          board-after-drop (bd/world->board w)
          version-after-drop (:board-version board-after-drop)]
      (is (:dropped? dropped))
      (is (= :dropped-token-1 (:request-id dropped)))
      (is (true? (:board-advanced? dropped)))
      (is (nil? (:result dropped)) "the caller got no receipt")
      (is (= :dead
             (get-in board-after-drop
                     [:actors (get-in w [:actors :a]) :life-state]))
          "the losing competition was committed")
      (let [replayed (bd/run-to-done! d :a command)
            result (:result replayed)]
        (is (= :attempt-lost (:outcome result)))
        (is (= version-after-drop
               (:board-version (bd/world->board w)))
            "a replayed request-id does not advance the board")
        (is (= board-after-drop (bd/world->board w))
            "the whole board is unchanged by the replay"))
      (is (empty? (bd/world-invariants w))))))

(deftest dropped-response-arms-the-next-command
  (with-driver [w d]
    (let [armed (bd/environment! d {:op :drop-response
                                    :participant :a
                                    :request-id :drop-arm-1})]
      (is (:dropped? armed))
      (is (:armed? armed))
      (is (false? (:board-advanced? armed)))
      (let [done (bd/run-to-done!
                  d :a
                  {:op :take-token :request-id :drop-arm-1})]
        (is (:dropped? done))
        (is (= :drop-arm-1 (:request-id done)))
        (is (true? (:board-advanced? done)))
        (is (nil? (:result done))))
      (is (= :dead
             (get-in (bd/world->board w)
                     [:actors (get-in w [:actors :a]) :life-state])))
      (is (empty? (bd/world-invariants w))))))

(deftest hard-failure-preserves-the-partial-site
  (with-driver [w d]
    (bd/run-to-done! d :a {:op :read :path bd/api-file})
    (let [failure (bd/environment!
                   d {:op :hard-failure
                      :participant :a
                      :boundary :applied
                      :op-index 1
                      :command {:op :commit
                                :request-id :hard-failure-1
                                :writes [{:path bd/api-file :content "A1"}
                                         {:path bd/client-file :content "C1"}
                                         {:path "notes.txt" :content "N1"}]}})
          site (:site failure)
          result (:result failure)]
      (is (:hard-failure? failure))
      (is (= :applied (:boundary failure)))
      (is (= :HARD_FAILURE (:status site)))
      (is (= :unknown (:outcome site)))
      (is (= :unknown (:file-changed? site)))
      (is (= 1 (count (:applied-ops site))))
      (is (= 3 (get-in site [:summary :op-count]))
          "the test batch contains three writes")
      (is (= 1 (:failed-index site)))
      (is (str/ends-with? (get-in site [:failed-op :path]) bd/client-file)
          "the failed operation is the current, uncertain file")
      (is (= 1 (count (:remaining-ops site)))
          "commit.clj normalizes remaining-ops to start after the failed op")
      (is (str/ends-with? (get-in site [:remaining-ops 0 :path]) "notes.txt")
          "the later file remains untouched")
      (is (= "A1" (bd/file-content w bd/api-file))
          "the first operation stays applied; no rollback")
      (is (= "B" (bd/file-content w bd/client-file))
          "the failed operation was not applied")
      (is (nil? (get-in failure [:files "notes.txt"]))
          "the remaining operation was not applied")
      (is (true? (:published? result)))
      (is (= :uncertain
             (->> (vals (:actions (bd/observation d))) first :phase))
          "the published action is uncertain, not finished")
      (is (empty? (bd/world-invariants w))))))

(deftest never-satisfied-does-not-retire-the-holder
  (testing "the wait itself does not occupy W"
    (with-driver [w d]
      (let [fact (bd/environment! d {:op :never-satisfied
                                    :participant :holder})
            task-id (get-in fact [:waiter :task-id])
            holder (get-in w [:actors :holder])]
        (is (:never-satisfied? fact))
        (is (:holder-alive? fact))
        (is (false? (:write-gate-held? fact))
            "W is not occupied by the waiting condition")
        (is (seq (:responsibility fact)))
        (is (some #{task-id} (:responsibility fact)))
        (is (contains? (get-in fact [:waiter :wait-for])
                       (get-in w [:tasks :atomic])))
        (is (= holder (get-in (bd/world->board w) [:token-slot :holder-id])))
        (is (false? (get-in fact [:park-slot :ok?])))
        (is (= :PROGRESS_AVAILABLE (get-in fact [:park-slot :error])))
        (is (= :waiting
               (get-in (bd/world->board w) [:tasks task-id :state])))
        (is (empty? (bd/world-invariants w))))))
  (testing "an in-flight commit keeps W; the wait does not release it"
    (with-driver [w d]
      (bd/run-to-done! d :a {:op :read :path bd/api-file})
      (let [queued (bd/step! d :a {:op :commit
                                   :write {:path bd/client-file
                                           :content "A1"}})]
        (is (= :queued-for-w (:boundary queued)))
        (let [checked (bd/resume! d :a)
              owner (get-in checked [:observation :gate :owner])]
          (is (= :in-gate-checked (:boundary checked)))
          (is (true? (get-in checked [:observation :gate :held?])))
          (let [fact (bd/environment! d {:op :never-satisfied
                                        :participant :holder})
                task-id (get-in fact [:waiter :task-id])
                holder (get-in w [:actors :holder])]
            (is (:never-satisfied? fact))
            (is (:holder-alive? fact))
            (is (true? (:write-gate-held? fact))
                "the waiting condition did not release W")
            (is (= owner (get-in (bd/observation d) [:gate :owner]))
                "W remains owned by the in-flight commit")
            (is (seq (:responsibility fact)))
            (is (some #{task-id} (:responsibility fact))
                "the never-satisfied task remains the holder's responsibility")
            (is (contains? (get-in fact [:waiter :wait-for])
                           (get-in w [:tasks :atomic]))
                "the task waits on an unfinished task")
            (is (= holder
                   (get-in (bd/world->board w) [:token-slot :holder-id])))
            (is (false? (get-in fact [:park-slot :ok?]))
                "park-slot is rejected while global progress exists")
            (is (= :PROGRESS_AVAILABLE (get-in fact [:park-slot :error])))
            (is (= :waiting
                   (get-in (bd/world->board w) [:tasks task-id :state])))
            (is (empty? (bd/world-invariants w))))
          (let [published (bd/run-until! d :board-published)
                done (bd/resume! d :a)]
            (is (= :board-published (:boundary published)))
            (is (= :done (:state done)))
            (is (false? (get-in (bd/observation d) [:gate :held?]))
                "the commit, not the waiting condition, released W")
            (is (empty? (bd/world-invariants w)))))))))

(deftest shell-wait-timeout-releases-w
  (with-driver [w d]
    (let [fact (bd/environment! d {:op :shell-wait
                                   :path "missing.txt"
                                   :content "go"
                                   :timeout-ms 30})]
      (is (:shell-wait? fact))
      (is (= :SHELL_WAIT_TIMEOUT (:status fact))
          "a missing target is an ordinary timeout, not a throw")
      (is (false? (gate/held? (:gate w))))
      (is (nil? (gate/owner (:gate w))))
      (is (empty? (bd/world-invariants w))))))

(deftest shell-wait-success-releases-w
  (with-driver [w d]
    (let [fact (bd/environment! d {:op :shell-wait
                                   :path bd/client-file
                                   :content "B"
                                   :timeout-ms 500})]
      (is (= :SHELL_CONDITION_MET (:status fact)))
      (is (false? (gate/held? (:gate w))))
      (is (nil? (gate/owner (:gate w))))
      (is (empty? (bd/world-invariants w))))))

(deftest shell-wait-holds-w-while-a-commit-queues
  (with-driver [w d]
    (let [marker "shell-wait.marker"
          marker-content (str "waiting:" bd/api-file "=go")
          shell (future
                  (bd/environment! d {:op :shell-wait
                                      :path bd/api-file
                                      :content "go"
                                      :marker-path marker
                                      :timeout-ms 30000}))]
      (try
        (is (wait-until
             #(try (= marker-content (bd/file-content w marker))
                   (catch Throwable _ false)))
            "the marker appears only after W is held")
        (is (wait-until #(= :shell (gate/owner (:gate w)))))
        (is (true? (gate/held? (:gate w))))
        (let [queued (bd/step! d :a
                               {:op :commit
                                :write {:path bd/api-file :content "go"}})]
          (is (= :parked-at (:state queued)))
          (is (= :queued-for-w (:boundary queued))
              "the protected commit queues while Shell holds W")
          (is (= :shell (get-in queued [:observation :gate :owner]))))
        (let [external (bd/environment! d {:op :external-write
                                           :path bd/api-file
                                           :content "go"})]
          (is (:external-write? external))
          (is (true? (:bypasses-write-gate? external))
              "the external write breaks the condition without W"))
        (let [shell-result (deref shell 10000 ::timeout)]
          (is (= :SHELL_CONDITION_MET (:status shell-result))
              (pr-str shell-result)))
        (is (wait-until #(nil? (gate/owner (:gate w)))))
        (is (false? (gate/held? (:gate w))))
        (let [done (loop [result (bd/resume! d :a)]
                     (if (= :done (:state result))
                       result
                       (recur (bd/resume! d :a))))]
          (is (= :done (:state done)))
          (is (= :FILE_CONFLICT (get-in done [:result :status]))
              "the queued commit staged the old stamp before the external write"))
        (is (= "go" (bd/file-content w bd/api-file)))
        (bd/run-to-done! d :a {:op :read :path bd/api-file})
        (let [applied (bd/run-to-done!
                       d :a
                       {:op :commit
                        :write {:path bd/api-file :content "go"}})]
          (is (= :APPLIED (get-in applied [:result :status]))))
        (is (= "go" (bd/file-content w bd/api-file)))
        (is (empty? (bd/world-invariants w)))
        (finally
          (try
            (when-not (= "go" (bd/file-content w bd/api-file))
              (bd/environment! d {:op :external-write
                                  :path bd/api-file
                                  :content "go"}))
            (catch Throwable _ nil))
          (deref shell 10000 nil))))))

(deftest token-competition-has-only-win-or-lose-outcomes
  (testing "a free token slot can be won"
    (let [world (bd/new-world {:token-holder? false})
          driver (open-driver world)]
      (try
        (let [won (bd/run-to-done! driver :a {:op :take-token})]
          (is (= :attempt-won (get-in won [:result :outcome])))
          (is (= :holder
                 (get-in (bd/world->board world)
                         [:actors (get-in world [:actors :a]) :role])))
          (is (= (get-in world [:actors :a])
                 (get-in (bd/world->board world) [:token-slot :holder-id])))
          (is (empty? (bd/world-invariants world))))
        (finally
          (bd/close! driver)
          (bd/cleanup-world! world)))))
  (testing "a held token makes the competitor lose and releases its claims"
    (with-driver [w d]
      (let [lost (bd/run-to-done! d :a {:op :take-token})
            board (bd/world->board w)]
        (is (= :attempt-lost (get-in lost [:result :outcome])))
        (is (= :dead (get-in board [:actors (get-in w [:actors :a]) :life-state])))
        (is (nil? (get-in board [:tasks (get-in w [:tasks :parent]) :owner-id]))
            "the loser's ordinary responsibility is published")
        (is (empty? (bd/world-invariants w)))))))

(deftest split-turns-a-ready-parent-into-a-waiting-parent
  (with-driver [w d]
    (let [split (bd/run-to-done!
                 d :a
                 {:op :split
                  :task :parent
                  :remaining-work "both children remain"
                  :children [{:goal "first child"
                              :remaining-work "do the first child"
                              :done-when "the first child is done"}
                             {:goal "second child"
                              :remaining-work "do the second child"
                              :done-when "the second child is done"}]})
          board (bd/world->board w)
          parent (get-in board [:tasks (get-in w [:tasks :parent])])]
      (is (true? (:ok? (:result split))))
      (is (= :waiting (:state parent)))
      (is (= 2 (count (:child-ids parent))))
      (is (every? #(= :ready (get-in board [:tasks % :state]))
                  (:child-ids parent)))
      (is (empty? (bd/world-invariants w))))))

;; ---------------------------------------------------------------------------
;; Property tests

(def gen-path
  (gen/elements [bd/api-file bd/client-file]))

(def gen-content
  (gen/elements ["A1" "B2" "C3" "D4" "E5"]))

(def gen-command
  (gen/one-of
   [(gen/fmap (fn [path] {:op :read :path path}) gen-path)
    (gen/fmap (fn [[path content]]
                {:op :commit :write {:path path :content content}})
              (gen/tuple gen-path gen-content))
    (gen/fmap (fn [task] {:op :claim :task task})
              (gen/elements [:parent :atomic :token]))
    (gen/fmap (fn [task]
                {:op :split
                 :task task
                 :remaining-work "children remain"
                 :children [{:goal "generated child"
                             :remaining-work "do the generated child"
                             :done-when "the generated child is done"}]})
              (gen/elements [:parent :atomic :token]))
    (gen/fmap (fn [task]
                {:op :finish :task task :read-path bd/api-file})
              (gen/elements [:parent :atomic :token]))]))

(defspec random-command-sequences-keep-control-invariants 30
  (prop/for-all [commands (gen/vector gen-command 1 10)]
    (let [world (bd/new-world)
          driver (open-driver world)]
      (try
        (doseq [[index command] (map-indexed vector commands)]
          (let [participant (if (even? index) :a :b)]
            (bd/run-to-done! driver participant command)
            (let [violations (bd/world-invariants world)]
              (when (seq violations)
                (throw (ex-info "control invariant violation"
                                {:command command
                                 :violations violations}))))))
        true
        (finally
          (bd/close! driver)
          (bd/cleanup-world! world))))))

(def gen-step
  (gen/one-of
   [(gen/fmap (fn [[participant path]]
                {:kind :read :actor participant :path path})
              (gen/tuple (gen/elements [:a :b]) gen-path))
    (gen/fmap (fn [[path content]]
                {:kind :external :path path :content content})
              (gen/tuple gen-path gen-content))
    (gen/fmap (fn [[participant path content]]
                {:kind :commit :actor participant
                 :path path :content content})
              (gen/tuple (gen/elements [:a :b]) gen-path gen-content))]))

(defspec applied-commits-match-the-disk-model 30
  (prop/for-all [steps (gen/vector gen-step 1 8)]
    (let [world (bd/new-world)
          driver (open-driver world)
          initial {bd/api-file "A" bd/client-file "B"}]
      (try
        (loop [remaining steps
               disk initial]
          (if-let [step (first remaining)]
            (let [disk'
                  (case (:kind step)
                    :read
                    (do
                      (bd/run-to-done! driver (:actor step)
                                       {:op :read :path (:path step)})
                      disk)

                    :external
                    (do
                      (bd/environment! driver {:op :shell-write
                                               :path (:path step)
                                               :content (:content step)})
                      (assoc disk (:path step) (:content step)))

                    :commit
                    (let [run (bd/run-to-done!
                               driver (:actor step)
                               {:op :commit
                                :write {:path (:path step)
                                        :content (:content step)}})
                          result (:result run)]
                      (cond
                        (= :APPLIED (:status result))
                        (do
                          (is (= 1 (count (:applied-ops result)))
                              "one prepared operation is applied exactly once")
                          (assoc disk (:path step) (:content step)))

                        (= :FILE_CONFLICT (:status result))
                        disk

                        :else
                        (throw (ex-info "unexpected commit result"
                                        {:step step :result result})))))]
              (let [violations (bd/world-invariants world)]
                (when (seq violations)
                  (throw (ex-info "file/control invariant violation"
                                  {:step step :violations violations}))))
              (recur (rest remaining) disk'))
            (do
              (doseq [[path content] disk]
                (is (= content (bd/file-content world path))))
              true)))
        (finally
          (bd/close! driver)
          (bd/cleanup-world! world))))))
