(ns hc.compose.workspace-integration-test
  "End-to-end smoke: a real `defprogram` from hc.server.program, evaluated
  against the compose bridge and a real temporary workspace.

  The server program runtime is required only by this test; the compose
  namespace itself never requires the server layer."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission.proof :as proof]
            [hc.compose.workspace :as ws]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]
            [hc.server.program.runtime :as program])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root []
  (Files/createTempDirectory "hc-workspace-int-" (make-array FileAttribute 0)))

(defn- cleanup! [root]
  (doseq [f (reverse (file-seq (io/file (str root))))]
    (io/delete-file f true)))

(defn- setup [root]
  (let [gate (wg/write-gate)
        kernel (swarm/create {:verify-read-set (wg/read-set-verifier root)})
        agent (:agent-id (swarm/execute!
                          kernel {:op :register-actor :slot-id :slot-a}))
        task (:task-id (swarm/execute!
                        kernel {:op :create-task
                                :actor-id agent
                                :goal "run a real program"
                                :remaining-work "edit or prove a file"
                                :done-when "the program terminal result is published"
                                :owner-id agent}))
        claim (:claim-id (swarm/execute!
                          kernel {:op :claim
                                  :actor-id agent
                                  :task-id task
                                  :atomicity-reason "one protected program"}))]
    {:root root :gate gate :kernel kernel :agent agent :task task :claim claim}))

(defn- with-program-runtime
  "Build the bridge, then inject a real hc.server.program runtime."
  [s f]
  (let [ws-runtime (ws/make-runtime {:root (:root s)
                                     :kernel (:kernel s)
                                     :write-gate (:gate s)
                                     :clock (fn [] 0)})
        port (ws/workspace-port ws-runtime)
        control (ws/control-port ws-runtime)
        program-runtime (program/runtime {:workspace port :control control})
        ws-runtime (assoc ws-runtime
                          :evaluate (partial program/evaluate-program! program-runtime))]
    (try
      (f ws-runtime)
      (finally
        (program/stop! program-runtime)))))

(defn- payload [s attempt source]
  {:agent-id (:agent s)
   :claim-id (:claim s)
   :task-id (:task s)
   :attempt-id attempt
   :call-id (str "call-" attempt)
   :intent "a real protected program with a generous deadline"
   :program source})

(deftest real-program-writes-through-the-protected-contract
  (let [root (temp-root)]
    (try
      (spit (io/file (str root) "a.txt") "old")
      (let [s (setup root)]
        (with-program-runtime
          s
          (fn [rt]
            (let [source (str "(defprogram write-note []\n"
                              "  (let [src (cat {:path \"a.txt\"})]\n"
                              "    (write {:path \"a.txt\" :content (str src \"-new\")})\n"
                              "    {:done true :src src}))")
                  out (ws/run-program! rt (payload s "write-1" source))]
              (is (= :succeeded (:status out)) (pr-str out))
              (is (= {:done true :src "old"} (:value out)))
              (is (= "old-new" (slurp (io/file (str root) "a.txt"))))
              (is (some #(= :files/applied (:type %)) (:effects out)))
              (is (= :ready (get-in (swarm/snapshot (:kernel s))
                                    [:tasks (:task s) :state])))
              (is (empty? (swarm/check-invariants (:kernel s))))))))
      (finally (cleanup! root)))))

(defn- run-plan-context-programs
  "Run Plan read/write programs through the real bridge."
  [root]
  (let [s (setup root)
        execute-board!
        (fn [command & _]
          (case (:op command)
            :check-draft
            {:ok? true
             :result {:status :accepted :outcome :draft-checked}}

            :claim-draft
            {:ok? true
             :result {:status :accepted :outcome :draft-claimed}}

            :release-draft
            {:ok? true
             :result {:status :accepted :outcome :draft-claim-released}}

            (throw (ex-info "Plan draft must not use the swarm board"
                            {:command command}))))
        ws-runtime (ws/make-runtime
                    {:root root
                     :write-gate (:gate s)
                     :execute-board! execute-board!
                     :board-snapshot (fn [] (swarm/snapshot (:kernel s)))
                     :clock (fn [] 0)})
        port (ws/workspace-port ws-runtime)
        control (ws/control-port ws-runtime)
        program-runtime (program/runtime {:workspace port :control control})
        ws-runtime (assoc ws-runtime
                          :evaluate (partial program/evaluate-program!
                                             program-runtime))]
    (try
      (let [read-source "(defprogram plan-read []\n  (cat {:path \"a.txt\"}))"
            read-out (ws/run-program!
                      ws-runtime
                      (assoc (payload s "plan-read-1" read-source)
                             :role :clj-plan
                             :readable-paths #{"a.txt"}))
            write-source "(defprogram plan-write []\n  (write {:path \"plans/draft.md\" :content \"new\"}))"
            write-out (ws/run-program!
                       ws-runtime
                       (assoc (payload s "plan-write-1" write-source)
                              :role :clj-plan
                              :tenure-id "tenure-1"
                              :draft-path "plans/draft.md"
                              :write-unlocked? true))]
        {:read read-out :write write-out})
      (finally
        (program/stop! program-runtime)))))

(deftest run-program-carries-the-plan-context-into-the-program
  (let [root (temp-root)]
    (try
      (spit (io/file (str root) "a.txt") "attachment")
      (.mkdirs (io/file (str root) "plans"))
      (let [{:keys [read write]} (run-plan-context-programs root)]
        (is (= :succeeded (:status read)) (pr-str read))
        (is (= "attachment" (:value read)))
        (is (= :succeeded (:status write)) (pr-str write))
        (let [written (slurp (io/file (str root) "plans/draft.md"))]
          (is (= "new" written))))
      (finally (cleanup! root)))))

(deftest real-program-finishes-with-a-verified-read-set
  (let [root (temp-root)]
    (try
      (spit (io/file (str root) "a.txt") "proof")
      (let [s (setup root)]
        (with-program-runtime
          s
          (fn [rt]
            (let [source (str "(defprogram prove-note []\n"
                              "  (let [info (stat {:path \"a.txt\"})\n"
                              "        stamp (select-keys info [:mtime-sec :mtime-nsec :size])]\n"
                              "    (finish {:task-id \"" (name (:task s)) "\"\n"
                              "             :claim-id \"" (name (:claim s)) "\"\n"
                              "             :result-summary \"file observed\"\n"
                              "             :read-set [{:path \"a.txt\" :stamp stamp}]})))")
                  out (ws/run-program! rt (payload s "finish-1" source))]
              (is (= :succeeded (:status out)) (pr-str out))
              (is (= :done (get-in (swarm/snapshot (:kernel s))
                                   [:tasks (:task s) :state])))
              (is (nil? (get-in (swarm/snapshot (:kernel s))
                                [:tasks (:task s) :claim-id])))
              (is (empty? (swarm/check-invariants (:kernel s))))))))
      (finally (cleanup! root)))))

(deftest real-program-writes-then-finishes
  (let [root (temp-root)]
    (try
      (spit (io/file (str root) "a.txt") "old")
      (let [s (setup root)]
        (with-program-runtime
          s
          (fn [rt]
            (let [source (str "(defprogram write-then-finish []\n"
                              "  (write {:path \"a.txt\" :content \"new\"})\n"
                              "  (finish {:task-id \"" (name (:task s)) "\"\n"
                              "           :claim-id \"" (name (:claim s)) "\"\n"
                              "           :result-summary \"write then finish\"\n"
                              "           :read-set []}))")
                  out (ws/run-program! rt (payload s "pending-1" source))]
              (testing "finish commits immediately; the later settle fails after the claim is released"
                (is (= :failed (:status out)) (pr-str out))
                (is (= :CLAIM_CHANGED
                       (:status (some #(when (= :workspace/settle (:type %)) %)
                                      (:receipts out)))))
                (is (= "old" (slurp (io/file (str root) "a.txt"))))
                (is (= :done (get-in (swarm/snapshot (:kernel s))
                                     [:tasks (:task s) :state])))
                (is (some (fn [receipt]
                            (and (= :board/command (:type receipt))
                                 (= :finish (:op receipt))
                                 (:ok? receipt)))
                          (:receipts out)))
                (is (empty? (swarm/check-invariants (:kernel s)))))))))
      (finally (cleanup! root)))))

(deftest real-program-verifies-old-content-when-writing-then-finishing
  (let [root (temp-root)]
    (try
      (spit (io/file (str root) "a.txt") "old")
      (let [s (setup root)]
        (with-program-runtime
          s
          (fn [rt]
            (let [source (str "(defprogram write-then-finish-with-basis []\n"
                              "  (let [info (stat {:path \"a.txt\"})\n"
                              "        stamp (select-keys info [:mtime-sec :mtime-nsec :size])]\n"
                              "    (write {:path \"a.txt\" :content \"new\"})\n"
                              "    (finish {:task-id \"" (name (:task s)) "\"\n"
                              "             :claim-id \"" (name (:claim s)) "\"\n"
                              "             :result-summary \"old basis\"\n"
                              "             :read-set [{:path \"a.txt\" :stamp stamp}]})))")
                  out (ws/run-program! rt (payload s "pending-2" source))]
              (testing "finish verifies the old real stamp and commits the board; the write stays pending"
                (is (= :failed (:status out)) (pr-str out))
                (is (= :CLAIM_CHANGED
                       (:status (some #(when (= :workspace/settle (:type %)) %)
                                      (:receipts out)))))
                (is (= "old" (slurp (io/file (str root) "a.txt"))))
                (is (= :done (get-in (swarm/snapshot (:kernel s))
                                     [:tasks (:task s) :state])))
                (let [finish-receipt
                      (some (fn [receipt]
                              (when (and (= :board/command (:type receipt))
                                         (= :finish (:op receipt)))
                                receipt))
                            (:receipts out))]
                  (is (:ok? finish-receipt) (pr-str finish-receipt))
                  (is (seq (get-in finish-receipt
                                   [:result :result-ref :read-set]))))
                (is (empty? (swarm/check-invariants (:kernel s)))))))))
      (finally (cleanup! root)))))

(defn- owner-execute-board
  "Simulate an Owner mailbox that applies the bridge's under-W capability.

  The real Owner receives `[command timeout opts]`; the bridge holds W and
  passes the opaque `:write-gate-proof` capability for a verified finish
  basis.  The test translates that capability into the swarm kernel's
  under-W execution path; a bare boolean is not accepted."
  ([kernel] (owner-execute-board kernel (atom nil)))
  ([kernel draft-claim]
   (fn [command & [_timeout opts]]
     (case (:op command)
       :check-draft
       {:ok? true
        :result {:status :accepted :outcome :draft-checked}}

       :claim-draft
       (if (or (nil? @draft-claim)
               (= (:path command) @draft-claim))
         (do (reset! draft-claim (:path command))
             {:ok? true
              :result {:status :accepted :outcome :draft-claimed}})
         {:ok? false :error :DRAFT_NAME_TAKEN})

       :release-draft
       (do
         (when (= (:path command) @draft-claim)
           (reset! draft-claim nil))
         {:ok? true
          :result {:status :accepted :outcome :draft-claim-released}})

       (let [result
             (if (and (proof/valid? (:write-gate-proof opts))
                      (seq (:verified-read-set command)))
               (swarm/execute-under-write-gate!
                kernel
                {:acquire (fn [_] ::held)
                 :release (fn [_] nil)}
                :owner
                (-> command
                    (assoc :read-set (:verified-read-set command))
                    (dissoc :verified-read-set)))
               (swarm/execute! kernel command))]
         {:ok? (:ok? result)
          :board (swarm/snapshot kernel)
          :result result})))))
(defn- action-for [kernel request-id]
  (some (fn [action] (when (= request-id (:request-id action)) action))
        (vals (:actions (swarm/snapshot kernel)))))

(deftest owner-backed-runtime-routes-all-board-writes
  (let [root (temp-root)]
    (try
      (spit (io/file (str root) "a.txt") "proof")
      (let [s (setup root)
            execute-board! (owner-execute-board (:kernel s))
            snapshot (fn [] (swarm/snapshot (:kernel s)))
            ws-runtime (ws/make-runtime
                        {:root root
                         :write-gate (:gate s)
                         :execute-board! execute-board!
                         :board-snapshot snapshot
                         :clock (fn [] 0)})
            port (ws/workspace-port ws-runtime)
            control (ws/control-port ws-runtime)
            program-runtime (program/runtime {:workspace port :control control})
            ws-runtime (assoc ws-runtime
                              :evaluate (partial program/evaluate-program!
                                                 program-runtime))]
        (try
          (let [write-source (str "(defprogram owner-write []\n"
                                  "  (let [src (cat {:path \"a.txt\"})]\n"
                                  "    (write {:path \"a.txt\" :content (str src \"-owner\")})\n"
                                  "    {:done true}))")
                written (ws/run-program! ws-runtime
                                         (payload s "owner-write-1" write-source))]
            (is (= :succeeded (:status written)) (pr-str written))
            (is (= "proof-owner" (slurp (io/file (str root) "a.txt"))))
            (is (= :finished (:phase (action-for (:kernel s)
                                                 "call-owner-write-1:settle:1")))))
          (let [finish-source
                (str "(defprogram owner-finish []\n"
                     "  (let [info (stat {:path \"a.txt\"})\n"
                     "        stamp (select-keys info [:mtime-sec :mtime-nsec :size])]\n"
                     "    (finish {:task-id \"" (name (:task s)) "\"\n"
                     "             :claim-id \"" (name (:claim s)) "\"\n"
                     "             :result-summary \"owner verified\"\n"
                     "             :read-set [{:path \"a.txt\" :stamp stamp}]})))")
                finished (ws/run-program! ws-runtime
                                          (payload s "owner-finish-1" finish-source))]
            (is (= :succeeded (:status finished)) (pr-str finished))
            (is (= :done (get-in (swarm/snapshot (:kernel s))
                                 [:tasks (:task s) :state])))
            (is (seq (:verified-read-set (:value finished))))
            (is (false? (wg/write-gate-held? (:gate s))))
            (is (= (:board-version (swarm/snapshot (:kernel s)))
                   (:board-version (ws/runtime-snapshot ws-runtime)))))
          (finally
            (program/stop! program-runtime))))
      (finally (cleanup! root)))))

(deftest owner-backed-write-then-finish-does-not-drain
  (let [root (temp-root)]
    (try
      (spit (io/file (str root) "a.txt") "old")
      (let [s (setup root)
            execute-board! (owner-execute-board (:kernel s))
            snapshot (fn [] (swarm/snapshot (:kernel s)))
            ws-runtime (ws/make-runtime
                        {:root root
                         :write-gate (:gate s)
                         :execute-board! execute-board!
                         :board-snapshot snapshot
                         :clock (fn [] 0)})
            port (ws/workspace-port ws-runtime)
            control (ws/control-port ws-runtime)
            program-runtime (program/runtime {:workspace port :control control})
            ws-runtime (assoc ws-runtime
                              :evaluate (partial program/evaluate-program!
                                                 program-runtime))]
        (try
          (let [source (str "(defprogram owner-write-finish []\n"
                            "  (write {:path \"a.txt\" :content \"new\"})\n"
                            "  (finish {:task-id \"" (name (:task s)) "\"\n"
                            "           :claim-id \"" (name (:claim s)) "\"\n"
                            "           :result-summary \"owner write then finish\"\n"
                            "           :read-set []}))")
                out (ws/run-program! ws-runtime
                                     (payload s "owner-write-finish-1" source))]
            (is (= :failed (:status out)) (pr-str out))
            (is (= :CLAIM_CHANGED
                   (:status (some #(when (= :workspace/settle (:type %)) %)
                                  (:receipts out)))))
            (is (= "old" (slurp (io/file (str root) "a.txt"))))
            (is (= :done (get-in (swarm/snapshot (:kernel s))
                                 [:tasks (:task s) :state])))
            (is (some (fn [receipt]
                        (and (= :board/command (:type receipt))
                             (= :finish (:op receipt))
                             (:ok? receipt)))
                      (:receipts out))))
          (finally
            (program/stop! program-runtime))))
      (finally (cleanup! root)))))

(deftest owner-backed-first-draft-claim-blocks-another-name
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            claims (atom nil)
            execute-board! (owner-execute-board (:kernel s) claims)
            snapshot (fn [] (swarm/snapshot (:kernel s)))
            ws-runtime (ws/make-runtime
                        {:root root
                         :write-gate (:gate s)
                         :execute-board! execute-board!
                         :board-snapshot snapshot
                         :clock (fn [] 0)})
            port (ws/workspace-port ws-runtime)
            control (ws/control-port ws-runtime)
            program-runtime (program/runtime {:workspace port :control control})
            ws-runtime (assoc ws-runtime
                              :evaluate (partial program/evaluate-program!
                                                 program-runtime))]
        (try
          (let [source "(defprogram plan-first []
                          (write {:path \"plans/first.md\" :content \"one\"}))"
                out (ws/run-program! ws-runtime
                                     (assoc (payload s "plan-first-1" source)
                                            :role :clj-plan
                                            :write-unlocked? true))]
            (is (= :succeeded (:status out)) (pr-str out))
            (is (= "plans/first.md" @claims))
            (is (= "one" (slurp (io/file (str root) "plans/first.md")))))
          (let [source "(defprogram plan-second []
                          (write {:path \"plans/second.md\" :content \"two\"}))"
                out (ws/run-program! ws-runtime
                                     (assoc (payload s "plan-second-1" source)
                                            :role :clj-plan
                                            :write-unlocked? true))]
            (is (= :failed (:status out)) (pr-str out))
            (is (= :DRAFT_NAME_TAKEN (get-in out [:error :code])))
            (is (false? (.exists (io/file (str root) "plans/second.md")))))
          (finally
            (program/stop! program-runtime))))
      (finally (cleanup! root)))))

(deftest owner-backed-failed-draft-apply-releases-claim
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            claims (atom nil)
            execute-board! (owner-execute-board (:kernel s) claims)
            snapshot (fn [] (swarm/snapshot (:kernel s)))
            ws-runtime (ws/make-runtime
                        {:root root
                         :write-gate (:gate s)
                         :execute-board! execute-board!
                         :board-snapshot snapshot
                         :apply-fn (fn [_ _]
                                     (throw (ex-info "injected apply failure" {})))
                         :clock (fn [] 0)})
            port (ws/workspace-port ws-runtime)
            control (ws/control-port ws-runtime)
            program-runtime (program/runtime {:workspace port :control control})
            ws-runtime (assoc ws-runtime
                              :evaluate (partial program/evaluate-program!
                                                 program-runtime))]
        (try
          (let [source "(defprogram plan-fail-apply []
                          (write {:path \"plans/failed.md\" :content \"x\"}))"
                out (ws/run-program! ws-runtime
                                     (assoc (payload s "plan-fail-apply-1" source)
                                            :role :clj-plan
                                            :write-unlocked? true))]
            (is (= :failed (:status out)) (pr-str out))
            (is (nil? @claims))
            (is (false? (.exists (io/file (str root) "plans/failed.md")))))
          (finally
            (program/stop! program-runtime))))
      (finally (cleanup! root)))))

(deftest multi-park-unpark-removes-target-session-aliases
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            ws-runtime (ws/make-runtime {:root root
                                         :kernel (:kernel s)
                                         :write-gate (:gate s)
                                         :clock (fn [] 0)})
            port (ws/workspace-port ws-runtime)
            control (ws/control-port ws-runtime)
            program-runtime (program/runtime {:workspace port :control control})
            ws-runtime (assoc ws-runtime
                              :evaluate (partial program/evaluate-program!
                                                 program-runtime))
            source (str "(defprogram multi-park []\n"
                        "  (let [a (where {:content \"abc\" :needle \"z\"})\n"
                        "        b (where {:content \"xyz\" :needle \"q\"})]\n"
                        "    {:a a :b b}))")
            first-park (ws/run-program! ws-runtime
                                        (payload s "multi-park-1" source))]
        (try
          (is (true? (:parked? first-park)) (pr-str first-park))
          (is (= :where-ambiguous
                 (get-in first-park [:value :reason])))
          (is (contains? @(:sessions ws-runtime) "call-multi-park-1"))
          (is (contains? @(:sessions ws-runtime) "multi-park-1"))
          (is (not (contains? @(:sessions ws-runtime) "multi-park")))
          (let [second-park (program/unpark!
                             program-runtime
                             {:name "multi-park"
                              :value [0 0]
                              :agent-id (:agent s)
                              :claim-id (:claim s)
                              :task-id (:task s)
                              :call-id "unpark-1"
                              :attempt-id "unpark-attempt-1"
                              :intent "resume first where"})]
            (is (= :PARKED (:status second-park)) (pr-str second-park))
            (let [done (program/unpark!
                        program-runtime
                        {:name "multi-park"
                         :value [1 1]
                         :agent-id (:agent s)
                         :claim-id (:claim s)
                         :task-id (:task s)
                         :call-id "unpark-2"
                         :attempt-id "unpark-attempt-2"
                         :intent "resume second where"})]
              (is (= :COMPLETED (:status done)) (pr-str done))
              (is (= {:a [0 0] :b [1 1]} (:value done)))
              (is (not-any? #{"call-multi-park-1" "multi-park-1" "multi-park"}
                            (keys @(:sessions ws-runtime)))
                  (pr-str (keys @(:sessions ws-runtime))))
              (is (not-any? #(str/starts-with? (str %) "unpark-")
                            (keys @(:sessions ws-runtime)))
                  "synthetic unpark wrappers must not leave session aliases")))
          (finally
            (program/stop! program-runtime))))
      (finally (cleanup! root)))))

(deftest make-runtime-requires-a-board-writer
  (is (thrown? IllegalArgumentException
               (ws/make-runtime {:root (str (temp-root))}))))
