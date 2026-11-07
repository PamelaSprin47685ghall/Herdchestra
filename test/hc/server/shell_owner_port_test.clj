(ns hc.server.shell-owner-port-test
  "Exercises the compose Owner shell-port adapter with a real swarm board and
  real managed processes.  This namespace deliberately does not require the
  workspace-gate facade, so it can run while that shared module is edited."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.board :as owner-board]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate.gate :as gate]
            [hc.server.shell :as shell]
            [hc.server.shell.board :as shell-board])
  (:import (java.nio.file Files LinkOption Path Paths)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-root []
  (str (Files/createTempDirectory "hc-shell-owner-" (make-array FileAttribute 0))))

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

(defn- wait-until
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline) false
         :else (do (Thread/sleep 10) (recur)))))))

(defn- owner-handle [kernel]
  ;; Mimic the real Owner `:execute-board!` receipt: a successful command
  ;; result carries `:ok? true` and `:status :accepted`; a refusal carries
  ;; `:ok? false` and an `:error` map.
  {:board-snapshot #(swarm/snapshot kernel)
   :execute-board! (fn [command]
                     (let [out (swarm/execute! kernel command)]
                       (if (false? (:ok? out))
                         {:ok? false
                          :error {:code (:error out)
                                  :message "board command refused"
                                  :details (:detail out)}}
                         (assoc out :status :accepted))))
   :mutate! (fn [f]
              (swap! (:state kernel) f)
              (swap! (:state kernel) swarm/derive))})

(defn- wrapped-owner-handle [kernel]
  ;; Older app/test wrapper shape: `{:ok? true :result command-result}`.
  {:board-snapshot #(swarm/snapshot kernel)
   :execute-board! (fn [command]
                     (let [out (swarm/execute! kernel command)]
                       (if (false? (:ok? out))
                         {:ok? false :result out}
                         {:ok? true
                          :board (swarm/snapshot kernel)
                          :result out})))
   :mutate! (fn [f]
              (swap! (:state kernel) f)
              (swap! (:state kernel) swarm/derive))})

(defn- delegating-shell-board [port]
  (let [p (owner-board/shell-port port)]
    (reify shell-board/ShellBoard
      (check-eligibility! [_ request]
        ((:check-eligibility! p) request))
      (begin-run! [_ request summary]
        ((:begin-run! p) request summary))
      (publish-finished! [_ permit result]
        ((:publish-finished! p) permit result))
      (publish-uncertain! [_ permit result]
        ((:publish-uncertain! p) permit result)))))

(defn- fixture
  ([] (fixture owner-handle))
  ([handle-fn]
   (let [kernel (swarm/create)
        registered (swarm/execute! kernel {:op :register-actor :slot-id :slot-a})
        actor-id (:agent-id registered)
        task (swarm/execute! kernel
                             {:op :create-task
                              :actor-id actor-id
                              :goal "run shell"
                              :remaining-work "run shell"
                              :done-when "shell ran"
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
                                 :atomicity-reason "owner shell test"})
        handle (handle-fn kernel)
        port (owner-board/make-port handle)]
    {:kernel kernel
     :handle handle
     :port port
     :actor-id actor-id
     :task-id task-id
     :term-id term-id
     :claim-id (:claim-id claimed)})))

(defn- request [fixture id command & [opts]]
  (merge {:request-id id
          :actor-id (:actor-id fixture)
          :token-term (:term-id fixture)
          :task-id (:task-id fixture)
          :claim (:claim-id fixture)
          :command command
          :cwd "."
          :purpose "owner shell test"}
         opts))

(defn- runner [root port]
  (shell/runner {:root root
                 :gate (gate/write-gate)
                 :board (delegating-shell-board port)
                 :artifact-dir root}))

(deftest owner-port-runs-a-real-shell-and-publishes
  (with-root [root]
    (let [{:keys [kernel port] :as fx} (fixture)
          r (runner root port)
          result (shell/run! r (request fx "r1" ["sh" "-c" "printf owner"]))
          action (first (vals (:actions (swarm/snapshot kernel))))]
      (is (= :COMPLETED (:status result)))
      (is (= "owner" (get-in result [:output :stdout :inline])))
      (is (true? (:published? result)))
      (is (= :finished (:phase action)))
      (is (= :shell (:kind action)))
      (is (false? (gate/held? (:gate r)))))))

(defn- refusing-owner-handle [kernel]
  ;; Owner runtime exception shape: {:status :refused :error {...}}.
  {:board-snapshot #(swarm/snapshot kernel)
   :execute-board! (fn [_]
                     {:status :refused
                      :error {:code :ACTOR_INACTIVE
                              :message "Owner refused the board command"}})
   :mutate! (fn [f]
              (swap! (:state kernel) f)
              (swap! (:state kernel) swarm/derive))})

(deftest owner-port-refused-receipt-is-an-ordinary-rejection
  (with-root [root]
    (let [{:keys [port] :as fx} (fixture refusing-owner-handle)
          r (runner root port)
          result (shell/run! r (request fx "r1" ["sh" "-c" "printf nope"]))]
      (is (= :ACTOR_INACTIVE (:status result)))
      (is (= :not-started (:outcome result)))
      (is (false? (:file-changed? result)))
      (is (false? (gate/held? (:gate r))))
      (is (empty? (:actions (swarm/snapshot (:kernel fx))))))))

(deftest owner-port-accepts-wrapper-receipts
  (with-root [root]
    (let [{:keys [port] :as fx} (fixture wrapped-owner-handle)
          r (runner root port)
          result (shell/run! r (request fx "r1" ["sh" "-c" "printf wrapper"]))
          action (first (vals (:actions (swarm/snapshot (:kernel fx)))))]
      (is (= :COMPLETED (:status result)))
      (is (= "wrapper" (get-in result [:output :stdout :inline])))
      (is (true? (:published? result)))
      (is (= :finished (:phase action)))
      (is (false? (gate/held? (:gate r)))))))

(deftest owner-port-cancels-a-real-process-tree
  (with-root [root]
    (let [{:keys [port] :as fx} (fixture)
          r (runner root port)
          session (shell/start! r (request fx "r1"
                                          ["sh" "-c" "sleep 30 & wait"]))]
      (is (wait-until #(seq (:known-child-activities (shell/status session))) 3000))
      (let [stop (shell/request-stop! r {:request-id "r1"})
            result (shell/await session 10000)]
        (is (contains? #{:STOP_SIGNAL_SENT :SESSION_ENDED} (:status stop)))
        (is (contains? #{:COMPLETED :UNCERTAIN} (:status result)))
        (is (true? (:cancelled? result)))
        (is (false? (gate/held? (:gate r))))
        (is (= :SESSION_ENDED
               (:status (shell/request-stop! r "r1"))))))))

(deftest owner-port-refuses-term-change-while-queued
  (with-root [root]
    (let [{:keys [kernel port handle] :as fx} (fixture)
          r (runner root port)
          permit (gate/acquire! (:gate r) :other)
          session (shell/start! r (request fx "r1" ["sh" "-c" "printf nope"]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        ((:mutate! handle) #(update-in % [:token-slot :term-id] inc))
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 5000)]
        (is (= :TOKEN_REQUIRED (:status result)))
        (is (= :term-mismatch (:code result)))
        (is (= :not-started (:outcome result)))
        (is (false? (gate/held? (:gate r))))))))

(deftest owner-port-refuses-holder-change-while-queued
  (with-root [root]
    (let [{:keys [kernel port handle] :as fx} (fixture)
          r (runner root port)
          permit (gate/acquire! (:gate r) :other)
          session (shell/start! r (request fx "r1" ["sh" "-c" "printf nope"]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        ((:mutate! handle) #(assoc-in % [:token-slot :holder-id] :other))
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 5000)]
        (is (= :TOKEN_REQUIRED (:status result)))
        (is (= :not-holder (:code result)))
        (is (false? (gate/held? (:gate r))))))))

(deftest owner-port-refuses-claim-change-while-queued
  (with-root [root]
    (let [{:keys [kernel port handle] :as fx} (fixture)
          r (runner root port)
          permit (gate/acquire! (:gate r) :other)
          session (shell/start! r (request fx "r1" ["sh" "-c" "printf nope"]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        ((:mutate! handle)
         (fn [board]
           (-> board
               (assoc-in [:tasks (:task-id fx) :claim-id] nil)
               (update :claims dissoc (:claim-id fx)))))
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 5000)]
        (is (= :CLAIM_CHANGED (:status result)))
        (is (= :claim-changed (:code result)))
        (is (false? (gate/held? (:gate r))))))))

(deftest owner-port-refuses-real-holder-retirement-while-queued
  (with-root [root]
    (let [{:keys [port handle] :as fx} (fixture)
          r (runner root port)
          permit (gate/acquire! (:gate r) :other)
          session (shell/start! r (request fx "r1" ["sh" "-c" "printf nope"]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        ;; A real board transition, not a snapshot poke: finish the claimed
        ;; token task while the shell request is queued.
        ((:execute-board! handle)
         {:op :finish
          :actor-id (:actor-id fx)
          :task-id (:task-id fx)
          :claim-id (:claim-id fx)
          :result-summary "task completed before shell started"})
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 5000)
            snapshot (swarm/snapshot (:kernel fx))]
        (is (contains? #{:ACTOR_INACTIVE :CLAIM_CHANGED} (:status result)))
        (is (= :not-started (:outcome result)))
        (is (nil? (get-in snapshot [:token-slot :holder-id])))
        (is (> (get-in snapshot [:token-slot :term-id]) (:term-id fx)))
        (is (false? (gate/held? (:gate r))))))))

(deftest owner-port-refuses-task-meaning-change-while-queued
  (with-root [root]
    (let [{:keys [kernel port handle] :as fx} (fixture)
          r (runner root port)
          permit (gate/acquire! (:gate r) :other)
          session (shell/start! r (request fx "r1" ["sh" "-c" "printf nope"]))]
      (try
        (is (wait-until #(= :waiting-gate (:phase (shell/status session))) 2000))
        ((:mutate! handle) #(update-in % [:tasks (:task-id fx) :rev] inc))
        (finally
          (gate/release! permit)))
      (let [result (shell/await session 5000)]
        (is (= :CLAIM_CHANGED (:status result)))
        (is (= :task-meaning-changed (:code result)))
        (is (false? (gate/held? (:gate r))))))))
