(ns hc.compose.workspace-mutual-wait-test
  "F11: a managed Shell holds W while waiting for a file condition, and an
  ordinary actor waits for W to produce that condition.

  The driver pauses a participant at :queued-for-w.  A managed Shell-wait
  action acquires the real W, records its wait condition in a marker file,
  and blocks until the target file carries the release content.  Breaking the
  ring through an external (W-free) write lets the Shell finish, then the
  queued commit either conflicts on its old stamp or succeeds after a fresh
  read."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.boundary-driver :as bd]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.gate :as gate]
            [hc.core.workspace-gate.prepare :as prepare])
  (:import (java.nio.file Paths)))

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

(defn- seam-restoration-fixture
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
(use-fixtures :each boundary-fixture)

(defn- root []
  (:root *world*))

(defn- path
  [& parts]
  (Paths/get (str (root)) (into-array String (map str parts))))

(defn- board []
  (bd/world->board *world*))

(defn- actor
  [role]
  (get-in *world* [:actors role]))

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

(deftest f11-shell-holds-w-while-a-waits-and-stamp-conflict-breaks-the-ring
  (let [marker "shell-wait.marker"
        marker-content (str "waiting:" bd/api-file "=go")
        shell-result (future
                       (bd/environment! *driver*
                                        {:op :shell-wait
                                         :path bd/api-file
                                         :content "go"
                                         :marker-path marker
                                         :timeout-ms 30000}))]
    (try
      (testing "A reads the target before the ring and then waits for W"
        (is (= :done
               (:state (bd/run-to-done! *driver* :a
                                        {:op :read :path bd/api-file})))))
      (testing "the managed Shell owns W and publishes its wait condition"
        (is (wait-until
             #(try (= marker-content (bd/file-content *world* marker))
                    (catch Throwable _ false))
             5000))
        (is (wait-until #(= :shell (wg/write-gate-owner (:gate *world*))) 5000))
        (is (true? (gate/held? (:gate *world*))))
        (is (false? (realized? shell-result))
            "the Shell is still waiting for the file content"))
      (testing "A's protected commit is visibly queued on W"
        (let [queued (bd/step! *driver* :a
                               {:op :commit
                                :write {:path bd/api-file :content "go"}})]
          (is (= :parked-at (:state queued)))
          (is (= :queued-for-w (:boundary queued)))
          (is (= :shell (get-in queued [:observation :gate :owner])))))
      (testing "the holder's waiting responsibility is visible"
        (let [fact (bd/environment! *driver* {:op :never-satisfied
                                              :participant :holder})
              wait-task (get-in fact [:waiter :task-id])
              holder (actor :holder)
              snapshot (board)
              responsibility (get-in snapshot [:derived :responsibility holder])
              parent-task (get-in snapshot
                                  [:tasks (get-in *world* [:tasks :parent])])]
          (is (:never-satisfied? fact))
          (is (:holder-alive? fact))
          (is (true? (:write-gate-held? fact)))
          (is (contains? responsibility wait-task))
          (is (some? (get-in fact [:waiter :wait-for])))
          (is (contains? (get-in fact [:waiter :wait-for])
                         (get-in *world* [:tasks :atomic])))
          (is (= :ready (:state parent-task)))
          (is (= (actor :a) (:owner-id parent-task)))
          (is (= :alive (get-in snapshot [:actors (actor :a) :life-state])))
          (is (= :alive (get-in snapshot [:actors holder :life-state])))
          (is (not-any? #(= :shell (:agent-id %))
                        (vals (:actions snapshot))))))
      (testing "neither W, the holder nor the token moves during the wait"
        (let [token-before (select-keys (:token-slot (board))
                                        [:holder-id :term-id])
              holder (actor :holder)]
          (is (= holder (:holder-id token-before)))
          (Thread/sleep 300)
          (let [snapshot (board)
                token-after (select-keys (:token-slot snapshot)
                                         [:holder-id :term-id])]
            (is (= token-before token-after))
            (is (true? (gate/held? (:gate *world*))))
            (is (= :shell (wg/write-gate-owner (:gate *world*))))
            (is (false? (realized? shell-result)))
            (is (= :alive (get-in snapshot [:actors holder :life-state])))
            (is (= :alive (get-in snapshot [:actors (actor :a) :life-state])))
            (is (not-any? #(and (= :holder-retired (:type %))
                                (= holder (:agent-id %)))
                          (:events snapshot)))
            ;; Negative: no timeout released W or transferred the token.
            (is (not (nil? (wg/write-gate-owner (:gate *world*)))))
            (is (not= :free (:state (:token-slot snapshot)))))))
      (testing "an external write breaks the ring without taking W"
        (let [external (bd/environment! *driver*
                                        {:op :external-write
                                         :path bd/api-file
                                         :content "go"})]
          (is (:external-write? external))
          (is (true? (:bypasses-write-gate? external)))
          (is (= "go" (bd/file-content *world* bd/api-file)))
          (let [result (deref shell-result 10000 ::timeout)]
            (is (= :SHELL_CONDITION_MET (:status result))
                (pr-str result)))
          (is (wait-until #(nil? (wg/write-gate-owner (:gate *world*))) 5000))
          (is (false? (gate/held? (:gate *world*))))))
      (testing "the queued old-stamp commit conflicts, then a fresh read applies"
        (let [conflict (drive-participant! :a)]
          (is (= :FILE_CONFLICT
                 (get-in conflict [:result :status])))
          (is (= "go" (bd/file-content *world* bd/api-file))))
        (is (= :done
               (:state (bd/run-to-done! *driver* :a
                                        {:op :read :path bd/api-file}))))
        (let [applied (bd/run-to-done!
                       *driver* :a
                       {:op :commit
                        :write {:path bd/api-file :content "go"}})]
          (is (= :APPLIED (get-in applied [:result :status]))))
        (is (= "go" (bd/file-content *world* bd/api-file))))
      (testing "the holder and token survive the ring"
        (let [snapshot (board)
              holder (actor :holder)
              parent-task (get-in snapshot
                                  [:tasks (get-in *world* [:tasks :parent])])]
          (is (= holder (get-in snapshot [:token-slot :holder-id])))
          (is (= :alive (get-in snapshot [:actors holder :life-state])))
          (is (= :alive (get-in snapshot [:actors (actor :a) :life-state])))
          (is (= :ready (:state parent-task)))
          (is (= (actor :a) (:owner-id parent-task)))
          (is (not= :done (:state parent-task)))
          (is (empty? (bd/world-invariants *world*)))))
      (finally
        (try
          (when-not (= "go" (bd/file-content *world* bd/api-file))
            (bd/environment! *driver* {:op :external-write
                                       :path bd/api-file
                                       :content "go"}))
          (catch Throwable _ nil))
        (deref shell-result 10000 nil)))))
