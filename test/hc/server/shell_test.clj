(ns hc.server.shell-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.gate :as gate]
            [hc.server.shell :as shell]
            [hc.server.shell.board :as board]
            [hc.server.shell.process :as process])
  (:import (java.nio.file Files LinkOption Path Paths)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; helpers

(defn- temp-root []
  (str (Files/createTempDirectory "hc-shell-test-" (make-array FileAttribute 0))))

(defn- delete-tree [root]
  (let [p (Paths/get (str root) (make-array String 0))]
    (when (Files/exists p (make-array LinkOption 0))
      (with-open [stream (Files/walk p (make-array java.nio.file.FileVisitOption 0))]
        (doseq [^Path child (reverse (vec (iterator-seq (.iterator stream))))]
          (Files/deleteIfExists child))))))

(defmacro with-root [[binding] & body]
  `(let [~binding (temp-root)]
     (try
       ~@body
       (finally
         (delete-tree ~binding)))))

(defn- ready-board []
  (-> (shell/memory-board)
      (shell/set-actor! :agent true)
      (shell/set-token! :agent 7)
      (shell/set-claim! {:claim-id :claim-1
                         :actor-id :agent
                         :task-id :task-1})
      (shell/set-task! :task-1 :ready {:needs-token true})))

(defn- runner
  ([root board] (runner root board {}))
  ([root board opts]
   (shell/runner (merge {:root root
                         :gate (gate/write-gate)
                         :board board
                         :artifact-dir root}
                        opts))))

(defn- request
  ([id command] (request id command {}))
  ([id command opts]
   (merge {:request-id id
           :actor-id :agent
           :token-term 7
           :task-id :task-1
           :claim :claim-1
           :command command
           :cwd "."
           :purpose "test"}
          opts)))

(defn- wait-until
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline) false
         :else (do (Thread/sleep 10) (recur)))))))

(defn- read-bytes [path]
  (Files/readAllBytes (Paths/get (str path) (make-array String 0))))

(defn- artifact-bytes [result]
  (some-> (get-in result [:output :artifact :path]) read-bytes count))

;; ---------------------------------------------------------------------------
;; real process

(deftest real-process-captures-facts-and-releases-w
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          result (shell/run! r (request "r1"
                                        ["sh" "-c"
                                         "printf out; printf err >&2; exit 7"]))]
      (is (= :COMPLETED (:status result)))
      (is (= 7 (:exit-code result)))
      (is (true? (:exit-code-known? result)))
      (is (= "out" (get-in result [:output :stdout :inline])))
      (is (= "err" (get-in result [:output :stderr :inline])))
      (is (= :unknown (:file-changed? result)))
      (is (not (contains? result :success?)))
      (is (true? (:published? result)))
      (is (false? (gate/held? g)))
      (is (= :finished (:phase (shell/board-action b "r1"))))
      (is (= 6 (artifact-bytes result)))
      (is (= result (read-string (pr-str result)))
          "public result must round-trip through EDN"))))

(deftest nonzero-exit-does-not-prove-no-file-change
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          marker (str root "/changed")
          result (shell/run! r (request "r1"
                                        ["sh" "-c"
                                         (str "printf x > " marker "; exit 9")]))]
      (is (= :COMPLETED (:status result)))
      (is (= 9 (:exit-code result)))
      (is (= :unknown (:file-changed? result)))
      (is (= "x" (slurp marker)))
      (is (false? (gate/held? g))))))

(deftest read-only-runner-runs-without-board-or-token
  (with-root [root]
    (let [g (gate/write-gate)
          r (shell/read-only-runner {:root root :gate g :artifact-dir root})
          result (shell/run! r {:request-id "ro-1"
                                :command ["sh" "-c" "printf read-only"]
                                :cwd "."
                                :purpose "read-only smoke"})]
      (is (= :COMPLETED (:status result)))
      (is (= "read-only" (get-in result [:output :stdout :inline])))
      (is (= :not-applicable (:published? result)))
      (is (false? (gate/held? g))))))

(deftest read-only-runner-rejects-obvious-writes
  (with-root [root]
    (let [g (gate/write-gate)
          r (shell/read-only-runner {:root root :gate g :artifact-dir root})
          marker (str root "/must-not-exist")
          result (shell/run! r {:request-id "ro-2"
                                :command ["sh" "-c"
                                          (str "printf x > " marker)]
                                :cwd "."
                                :purpose "obvious write"})]
      (is (= :UNSUPPORTED_OPERATION (:status result)))
      (is (= :read-only-command-rejected (:code result)))
      (is (= :not-started (:outcome result)))
      (is (not (Files/exists (Paths/get marker (make-array String 0))
                             (make-array LinkOption 0))))
      (is (false? (gate/held? g))))))

(deftest read-only-runner-stops-a-real-process
  (with-root [root]
    (let [g (gate/write-gate)
          r (shell/read-only-runner {:root root :gate g :artifact-dir root
                                     :limits {:stop-grace-ms 300}})
          session (shell/start! r {:request-id "ro-stop"
                                   :command ["sh" "-c" "sleep 30"]
                                   :cwd "."
                                   :purpose "read-only stop"})]
      (is (wait-until #(= :running (:phase (shell/status session))) 3000))
      (let [stop (shell/request-stop! r "ro-stop")
            result (shell/await session 5000)]
        (is (contains? #{:STOP_SIGNAL_SENT :SESSION_ENDED} (:status stop)))
        (is (contains? #{:COMPLETED :UNCERTAIN} (:status result)))
        (is (= :not-applicable (:published? result)))
        (is (false? (gate/held? g)))
        (is (= :SESSION_ENDED
               (:status (shell/request-stop! r "ro-stop"))))))))

;; ---------------------------------------------------------------------------
;; entry validation

(deftest entry-validation-rejects-before-start
  (with-root [root]
    (testing "dead actor"
      (let [g (gate/write-gate)
            b (-> (ready-board) (shell/set-actor! :agent false))
            r (shell/runner {:root root :gate g :board b :artifact-dir root})
            marker (str root "/dead-actor")
            result (shell/run! r (request "r1"
                                          ["sh" "-c" (str "printf x > " marker)]))]
        (is (= :ACTOR_INACTIVE (:status result)))
        (is (= :not-started (:outcome result)))
        (is (false? (:file-changed? result)))
        (is (not (Files/exists (Paths/get marker (make-array String 0))
                               (make-array LinkOption 0))))
        (is (false? (gate/held? g)))))

    (testing "old token term"
      (let [g (gate/write-gate)
            b (ready-board)
            r (shell/runner {:root root :gate g :board b :artifact-dir root})
            result (shell/run! r (request "r2" ["sh" "-c" "true"]
                                          {:token-term 6}))]
        (is (= :TOKEN_TERM_STALE (:status result)))
        (is (= :term-mismatch (:code result)))
        (is (false? (gate/held? g)))))

    (testing "claim task is no longer ready"
      (let [g (gate/write-gate)
            b (-> (ready-board) (shell/set-task! :task-1 :waiting))
            r (shell/runner {:root root :gate g :board b :artifact-dir root})
            result (shell/run! r (request "r3" ["sh" "-c" "true"]))]
        (is (= :CLAIM_CHANGED (:status result)))
        (is (= :task-not-ready (:code result)))
        (is (false? (gate/held? g)))))

    (testing "bad cwd"
      (let [g (gate/write-gate)
            b (ready-board)
            r (shell/runner {:root root :gate g :board b :artifact-dir root})
            result (shell/run! r (request "r4" ["sh" "-c" "true"]
                                          {:cwd "missing-dir"}))]
        (is (= :STRUCTURE_CONFLICT (:status result)))
        (is (= :cwd-not-directory (:code result)))
        (is (false? (gate/held? g)))))))

;; ---------------------------------------------------------------------------
;; shared W

(deftest waits-for-shared-write-gate
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          permit (gate/acquire! g :other)
          session (shell/start! r (request "r1" ["sh" "-c" "printf ran"]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        (is (= :other (gate/owner g)))
        (is (false? (:done? (shell/status session))))
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 10000)]
        (is (= :COMPLETED (:status result)))
        (is (= "ran" (get-in result [:output :stdout :inline])))
        (is (false? (gate/held? g)))))))

(deftest stop-while-waiting-for-gate
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          permit (gate/acquire! g :other)
          session (shell/start! r (request "r1" ["sh" "-c" "printf nope"]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        (is (= :STOP_REGISTERED (:status (shell/request-stop! r "r1"))))
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 5000)]
        (is (contains? #{:WAITING_FOR_WRITE_GATE :CANCELLED} (:status result)))
        (is (true? (:cancelled? result)))
        (is (= :not-started (:outcome result)))
        (is (false? (gate/held? g)))
        (is (nil? (shell/board-action b "r1")))))))

(deftest shell-and-protected-commit-share-w
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          w (wg/workspace-gate {:root root :gate g :board nil
                                :allow-boardless? true})
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          session (shell/start! r (request "r1"
                                          ["sh" "-c"
                                           "sleep 0.4; printf shell > shell.txt"]))]
      (is (wait-until #(= :running (:phase (shell/status session))) 3000))
      (let [commit (future
                     (let [sess (wg/session root)]
                       (wg/write sess "commit.txt" "commit")
                       (wg/commit! w {:request-id "c1"
                                      :actor-id "test"
                                      :batch (wg/snapshot sess)})))]
        (Thread/sleep 100)
        (is (not (realized? commit)) "protected commit must wait for shell W")
        (is (= :COMPLETED (:status (shell/await session 10000))))
        (let [commit-result (deref commit 10000 ::timeout)]
          (is (wg/applied? commit-result))
          (is (= "commit" (slurp (str root "/commit.txt"))))
          (is (= "shell" (slurp (str root "/shell.txt")))))
        (is (false? (gate/held? g)))))))

(deftest task-meaning-change-while-queued-is-refused
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          permit (gate/acquire! g :other)
          marker (str root "/must-not-run")
          session (shell/start! r (request "r1"
                                          ["sh" "-c" (str "printf x > " marker)]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        ;; The task keeps its claim but changes meaning while the shell waits.
        (shell/set-task! b :task-1 :ready {:needs-token true :rev 1})
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 5000)]
        (is (= :CLAIM_CHANGED (:status result)))
        (is (= :task-meaning-changed (:code result)))
        (is (= :not-started (:outcome result)))
        (is (false? (gate/held? g)))
        (is (not (Files/exists (Paths/get marker (make-array String 0))
                               (make-array LinkOption 0))))))))

;; ---------------------------------------------------------------------------
;; bounded output

(deftest bounded-output-and-artifact
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          result (shell/run! r (request "r1"
                                        ["sh" "-c"
                                         "printf 0123456789abcdefghij; printf 0123456789abcdefghij >&2"]
                                        {:output-mode {:inline-limit 10
                                                       :artifact-limit 20}}))
          artifact (get-in result [:output :artifact :path])]
      (is (= :COMPLETED (:status result)))
      (is (true? (:output-truncated? result)))
      (is (<= (get-in result [:output :inline-bytes]) 10))
      (is (true? (get-in result [:output :stdout :truncated?])))
      (is (true? (get-in result [:output :stderr :truncated?])))
      (is (= 20 (get-in result [:output :artifact :bytes])))
      (is (true? (get-in result [:output :artifact :truncated?])))
      (is (= 20 (count (read-bytes artifact))))
      (is (false? (gate/held? g))))))

(deftest artifact-limit-is-capped-at-64mib
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          result (shell/run! r (request "r1" ["sh" "-c" "printf small"]
                                        {:output-mode {:artifact-limit (* 128 1024 1024)}}))]
      (is (= :COMPLETED (:status result)))
      (is (= shell/default-artifact-limit
             (get-in result [:output :artifact :limit])))
      (is (= 5 (get-in result [:output :artifact :bytes])))
      (is (false? (get-in result [:output :artifact :truncated?])))
      (is (false? (gate/held? g))))))

;; ---------------------------------------------------------------------------
;; start failure and cancellation

(deftest start-failure-is-not-success
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          result (shell/run! r (request "r1" ["/definitely/not/a/program"]))]
      (is (= :SHELL_START_FAILED (:status result)))
      (is (= :not-started (:outcome result)))
      (is (false? (:file-changed? result)))
      (is (not (contains? result :success?)))
      (is (true? (:published? result)))
      (is (= :finished (:phase (shell/board-action b "r1"))))
      (is (= result (read-string (pr-str result)))
          "start-failure result must round-trip through EDN")
      (is (false? (gate/held? g))))))

(deftest stop-signals-process-tree
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root
                           :limits {:stop-grace-ms 300}})
          session (shell/start! r (request "r1"
                                          ["sh" "-c"
                                           "trap '' TERM; sleep 30"]))]
      (is (wait-until #(= :running (:phase (shell/status session))) 3000))
      (is (= :running (:status (shell/action r "r1"))))
      (let [stop (shell/request-stop! r "r1")
            result (shell/await session 10000)
            action (shell/action r "r1")]
        (is (contains? #{:STOP_SIGNAL_SENT :SESSION_ENDED} (:status stop)))
        (is (= :COMPLETED (:status result)))
        (is (true? (:cancelled? result)))
        (is (= :finished (:status action)))
        (is (true? (get-in action [:result :cancelled?])))
        (is (some? (get-in action [:result :exit-code])))
        (is (false? (gate/held? g)))
        (is (= :SESSION_ENDED (:status (shell/request-stop! r "r1"))))))))

(deftest stop-signals-known-child
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root
                           :limits {:stop-grace-ms 300}})
          session (shell/start! r (request "r1"
                                          ["sh" "-c" "sleep 30 & wait"]))]
      (is (wait-until #(seq (:known-child-activities (shell/status session))) 3000))
      (let [stop (shell/request-stop! r "r1")
            result (shell/await session 10000)]
        (is (contains? #{:STOP_SIGNAL_SENT :SESSION_ENDED} (:status stop)))
        (is (contains? #{:COMPLETED :UNCERTAIN} (:status result)))
        (is (false? (gate/held? g)))))))

(deftest publication-retry-does-not-rerun-the-process
  (with-root [root]
    (let [g (gate/write-gate)
          b (-> (ready-board) (shell/fail-publishes! 2))
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          marker (str root "/published-once")
          result (shell/run! r (request "r1"
                                        ["sh" "-c" (str "printf x >> " marker)]))]
      (is (= :COMPLETED (:status result)))
      (is (true? (:published? result)))
      (is (= 3 (:publish-attempts result)))
      (is (= "x" (slurp marker)))
      (is (false? (gate/held? g))))))

(deftest publish-failure-retains-responsibility-and-resumes
  (with-root [root]
    (let [g (gate/write-gate)
          b (-> (ready-board) (shell/fail-publishes! 10))
          r (shell/runner {:root root :gate g :board b :artifact-dir root
                           :limits {:publish-retries 1}})
          marker (str root "/published-once")
          result (shell/run! r (request "r1"
                                        ["sh" "-c" (str "printf x >> " marker)]))
          action (shell/action r "r1")]
      (is (= :COMPLETED (:status result)))
      (is (false? (:published? result)))
      (is (= :publish-only (:retry result)))
      (is (:publish-retry? action))
      (is (= :running (:phase (shell/board-action b "r1"))))
      (is (= "x" (slurp marker)))
      (is (false? (gate/held? g)))
      (shell/fail-publishes! b 0)
      (let [retried (shell/retry-publish! r "r1")
            action' (shell/action r "r1")]
        (is (true? (:published? retried)))
        (is (not (contains? retried :retry)))
        (is (= :finished (:status action')))
        (is (not (:publish-retry? action')))
        (is (= :finished (:phase (shell/board-action b "r1"))))
        (is (= "x" (slurp marker)))
        (is (false? (gate/held? g)))))))

(deftest executor-interrupt-stops-managed-process
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root
                           :limits {:stop-grace-ms 300}})
          session (shell/start! r (request "r1" ["sh" "-c" "sleep 30"]))]
      (is (wait-until #(= :running (:phase (shell/status session))) 3000))
      (.interrupt ^Thread (:thread @(:state session)))
      (let [result (shell/await session 10000)]
        (is (contains? #{:COMPLETED :UNCERTAIN} (:status result)))
        (is (true? (:interrupted? result)))
        (is (wait-until #(not (gate/held? g)) 5000))))))

;; ---------------------------------------------------------------------------
;; deduplication

(deftest request-id-replays-one-process
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          marker (str root "/count")
          req (request "r1" ["sh" "-c" (str "printf x >> " marker)])
          first (shell/run! r req)
          second (shell/run! r req)
          mismatch (shell/run! r (assoc req :purpose "different"))]
      (is (= first second))
      (is (= "x" (slurp marker)))
      (is (= :CALL_REPLAY_MISMATCH (:status mismatch)))
      (is (false? (gate/held? g))))))

;; ---------------------------------------------------------------------------
;; session boundary

(deftest known-child-activity-keeps-w
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          session (shell/start! r (request "r1"
                                          ["sh" "-c" "sleep 0.8 & wait"]))]
      (is (wait-until #(seq (:known-child-activities (shell/status session))) 3000))
      (is (true? (get-in (shell/status session) [:write-gate-held?])))
      (is (false? (:done? (shell/status session))))
      (let [result (shell/await session 10000)]
        (is (= :COMPLETED (:status result)))
        (is (false? (gate/held? g)))
        (is (every? (complement :alive?)
                    (:known-child-activities result)))))))

(deftest main-exit-with-managed-child-keeps-w
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root
                           :limits {:child-poll-ms 20}})
          session (shell/start! r (request "r1"
                                          ["sh" "-c"
                                           "sleep 0.8 >/dev/null 2>&1 & sleep 0.15; exit 0"]))]
      (is (wait-until #(seq (:known-child-activities (shell/status session))) 2000))
      (is (wait-until #(false? (get-in (shell/status session) [:process :alive?])) 2000))
      (is (true? (get-in (shell/status session) [:write-gate-held?])))
      (is (false? (:done? (shell/status session))))
      (let [result (shell/await session 5000)]
        (is (= :COMPLETED (:status result)))
        (is (false? (gate/held? g)))
        (is (some (complement :alive?) (:known-child-activities result)))))))

(deftest shell-waiting-for-file-blocks-protected-commit
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          w (wg/workspace-gate {:root root :gate g :board nil
                                :allow-boardless? true})
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          marker (str root "/never-created")
          session (shell/start! r (request "r1"
                                          ["sh" "-c"
                                           (str "while [ ! -f " marker
                                                " ]; do sleep 0.05; done")]))]
      (is (wait-until #(= :running (:phase (shell/status session))) 3000))
      (let [commit (future
                     (let [sess (wg/session root)]
                       (wg/write sess "commit.txt" "commit")
                       (wg/commit! w {:request-id "c1"
                                      :actor-id "test"
                                      :batch (wg/snapshot sess)})))]
        (is (wait-until #(not (realized? commit)) 2000))
        (is (wait-until #(pos? (gate/queued g)) 2000))
        (is (false? (Files/exists (Paths/get marker (make-array String 0))
                                  (make-array LinkOption 0))))
        (shell/request-stop! r "r1")
        (is (contains? #{:COMPLETED :UNCERTAIN}
                       (:status (shell/await session 5000))))
        (is (wg/applied? (deref commit 5000 ::timeout)))
        (is (= "commit" (slurp (str root "/commit.txt"))))
        (is (false? (gate/held? g)))))))

;; ---------------------------------------------------------------------------
;; uncertain outcome and late confirmation

(deftest uncertain-outcome-keeps-w-until-confirmed
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root
                           :limits {:stop-grace-ms 150}})
          session (shell/start! r (request "r1" ["sh" "-c" "sleep 30"]))]
      (is (wait-until #(= :running (:phase (shell/status session))) 3000))
      (let [[stop result]
            (with-redefs [process/stop-tree!
                          (fn [_ _ _]
                            {:signal-sent? false :forcibly? false
                             :alive [] :errors []})]
              [(shell/request-stop! r "r1")
               (shell/await session 5000)])]
        (is (= :STOP_UNCONFIRMED (:status stop)))
        (is (= :UNCERTAIN (:status result)))
        (is (= :unknown (:outcome result)))
        (is (= :unknown (:file-changed? result)))
        (is (true? (:write-gate-held? result)))
        (is (true? (gate/held? g))))
      ;; Restore the real stop path; the watchdog releases W only after the
      ;; process and its pipes actually end.
      (shell/request-stop! r "r1")
      (is (wait-until #(not (gate/held? g)) 5000))
      (is (wait-until #(= :COMPLETED (:status (shell/result session))) 5000)))))

;; ---------------------------------------------------------------------------
;; live output subscription

(deftest subscribe-receives-output
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          session (shell/start! r (request "r1"
                                          ["sh" "-c" "sleep 0.2; printf live"]))
          events (atom [])
          unsubscribe (shell/subscribe! session #(swap! events conj %))]
      (try
        (let [result (shell/await session 10000)]
          (is (= :COMPLETED (:status result)))
          (is (str/includes? (apply str (map :text @events)) "live")))
        (finally
          (unsubscribe))))))

;; ---------------------------------------------------------------------------
;; swarm board adapter

(defn- swarm-fixture []
  (let [kernel (swarm/create)
        registered (swarm/execute! kernel {:op :register-actor :slot-id :slot-a})
        actor-id (:agent-id registered)
        task (swarm/execute! kernel
                             {:op :create-task
                              :actor-id actor-id
                              :goal "run a shell"
                              :remaining-work "run a shell"
                              :done-when "the shell ran"
                              :needs-token true})
        task-id (:task-id task)
        won (swarm/execute! kernel
                            {:op :take-or-die
                             :actor-id actor-id
                             :confirm :i-hereby-confirm-i-die-without-regret})
        term-id (:term-id won)
        claimed (swarm/execute! kernel
                                {:op :claim
                                 :actor-id actor-id
                                 :task-id task-id
                                 :atomicity-reason "shell test"})]
    {:kernel kernel
     :actor-id actor-id
     :task-id task-id
     :term-id term-id
     :claim-id (:claim-id claimed)}))

(deftest swarm-board-validates-and-publishes
  (with-root [root]
    (let [{:keys [kernel actor-id task-id term-id claim-id]} (swarm-fixture)
          g (gate/write-gate)
          b (shell/swarm-board kernel)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          req (request "r1" ["sh" "-c" "printf swarm"]
                       {:actor-id actor-id
                        :token-term term-id
                        :task-id task-id
                        :claim claim-id})
          result (shell/run! r req)
          action (first (vals (:actions (swarm/snapshot kernel))))]
      (is (= :COMPLETED (:status result)))
      (is (true? (:published? result)))
      (is (= :finished (:phase action)))
      (is (map? (:result-ref action)))
      (is (= "swarm" (get-in result [:output :stdout :inline])))
      (is (false? (gate/held? g)))
      (let [stale (shell/run! r (assoc req :request-id "r2" :token-term 999))]
        (is (= :TOKEN_TERM_STALE (:status stale)))
        (is (= :term-mismatch (:code stale)))))))

(deftest owner-board-uses-the-owner-command-path
  (with-root [root]
    (let [{:keys [kernel actor-id task-id term-id claim-id]} (swarm-fixture)
          execute (fn [command]
                    ;; Real Owner receipt shape: command result with :ok? and
                    ;; :status :accepted, or {:ok? false :error {:code ...}}.
                    (let [out (swarm/execute! kernel command)]
                      (if (false? (:ok? out))
                        {:ok? false
                         :error {:code (:error out)
                                 :message "board command refused"
                                 :details (:detail out)}}
                        (assoc out :status :accepted))))
          snapshot #(swarm/snapshot kernel)
          g (gate/write-gate)
          b (shell/owner-board execute snapshot)
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          req (request "r1" ["sh" "-c" "printf owner"]
                       {:actor-id actor-id
                        :token-term term-id
                        :task-id task-id
                        :claim claim-id})
          result (shell/run! r req)
          action (first (vals (:actions (swarm/snapshot kernel))))]
      (is (= :COMPLETED (:status result)))
      (is (true? (:published? result)))
      (is (= "owner" (get-in result [:output :stdout :inline])))
      (is (= :finished (:phase action)))
      (is (= :shell (:kind action)))
      (is (false? (gate/held? g))))))

;; ---------------------------------------------------------------------------
;; controlled environment references

(deftest env-refs-resolve-without-leaking-values
  (with-root [root]
    (let [g (gate/write-gate)
          b (ready-board)
          r (shell/runner {:root root :gate g :board b :artifact-dir root
                           :resolve-secret (fn [ref]
                                             (when (= ref :demo-secret) "s3cr3t"))})
          result (shell/run! r (request "r1"
                                        ["sh" "-c" "printf %s \"$DEMO\""]
                                        {:env-refs {:DEMO :demo-secret}}))
          action (shell/board-action b "r1")]
      (is (= :COMPLETED (:status result)))
      (is (= "s3cr3t" (get-in result [:output :stdout :inline])))
      (is (= ["DEMO"] (:env-names result)))
      (is (not (str/includes? (pr-str action) "s3cr3t")))
      (is (false? (gate/held? g))))))

;; ---------------------------------------------------------------------------
;; residual board action and read-only policy regressions

(deftest residual-pre-run-board-action-is-published
  (with-root [root]
    (let [g (gate/write-gate)
          published (atom [])
          b (reify board/ShellBoard
              (check-eligibility! [_ _]
                {:task-rev 1 :claim-id :claim-1 :term-id 7 :actor-id :agent})
              (begin-run! [_ _ _]
                ;; The action exists, but the adapter cannot advance it to
                ;; :running.  It must hand the permit to the session.
                (throw (ex-info "cannot reach running"
                                {:code :SHELL_ACTION_NOT_RUNNING
                                 :permit {:board :shell
                                          :action-id "act-1"
                                          :actor-id :agent
                                          :request-id "r1"
                                          :phase :waiting-gate}})))
              (publish-finished! [_ permit result]
                (swap! published conj {:permit permit :result result})
                {:published true})
              (publish-uncertain! [_ permit result]
                (swap! published conj {:permit permit :result result})
                {:published true}))
          r (shell/runner {:root root :gate g :board b :artifact-dir root})
          result (shell/await (shell/start! r (request "r1" ["sh" "-c" "exit 0"]))
                              5000)]
      (is (= :HARD_FAILURE (:status result)))
      (is (= :not-started (:outcome result)))
      (is (true? (:published? result)))
      (is (= 1 (count @published)))
      (is (= "act-1" (get-in (first @published) [:permit :action-id])))
      (is (= :HARD_FAILURE (get-in (first @published) [:result :status])))
      (is (false? (gate/held? g))))))

(deftest read-only-runner-rejects-combined-flags-and-wrappers
  (with-root [root]
    (let [g (gate/write-gate)
          r (shell/read-only-runner {:root root :gate g :artifact-dir root})
          marker (str root "/must-not-exist")
          commands [["ro-lc" ["bash" "-lc" (str "printf x > " marker)]]
                    ["ro-env" ["env" "sh" "-c" (str "printf x > " marker)]]
                    ["ro-xargs" ["xargs" "-0" "rm" "-rf" marker]]]]
      (doseq [[id command] commands]
        (let [result (shell/run! r {:request-id id
                                    :command command
                                    :cwd "."
                                    :purpose "policy"})]
          (is (= :UNSUPPORTED_OPERATION (:status result)) id)
          (is (= :read-only-command-rejected (:code result)) id)
          (is (= :not-started (:outcome result)) id)))
      (is (not (Files/exists (Paths/get marker (make-array String 0))
                             (make-array LinkOption 0))))
      (is (false? (gate/held? g))))))

(deftest pre-run-action-is-advanced-before-publication
  (let [snapshot (fn []
                   {:actions {"act-1" {:action-id "act-1"
                                       :phase :waiting-gate
                                       :agent-id :agent}}})
        executed (atom [])
        execute (fn [command]
                  (swap! executed conj command)
                  {:ok? true :action-id "act-1"})]
    (is (= {:published true :phase :finished}
           ((deref #'board/publish-finished-with!)
            snapshot execute {:action-id "act-1" :actor-id :agent} {})))
    ;; The table has no :waiting-gate -> :finished edge; the helper advances
    ;; to :running first.
    (is (= [:running :finished]
           (mapv :phase @executed)))
    (is (every? #(= :advance-action (:op %)) @executed))))
