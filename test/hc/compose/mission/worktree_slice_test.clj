(ns hc.compose.mission.worktree-slice-test
  "End-to-end worktree slice workflow with real Git, real fleet runtimes, and
  the real protected workspace bridge.

  A slice writes through `hc.compose.mission.worktree/execute-program!`, gets
  a real Git commit in its worktree, and is joined through the fleet service.
  These tests prove that the protected write reaches master only by the same
  rebase + fast-forward path the fleet uses in production.

  Every test uses its own `mkdtemp` root; worktrees live outside the master
  repository and are deleted in `finally`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.service :as service]
            [hc.compose.fleet.support :as support]
            [hc.compose.mission.worktree :as worktree]
            [hc.compose.workspace :as ws]
            [hc.core.swarm-stm :as swarm]
            [hc.server.program.runtime :as program]))

(defn- with-real-git
  [label body]
  (if (git/available? (git/git-cli "."))
    (body)
    (println (str "SKIP hc.compose.mission.worktree-slice-test/" label
                  ": git executable unavailable"))))

(defn- slice
  [id]
  {:slice-id id
   :goal (str "goal " id)
   :inputs []
   :depends-on []
   :done-when "done"})

(defn- start-system!
  "Start one real fleet service and record every program runtime by workspace."
  [repo worktree-root opts]
  (let [programs (atom {})
        evaluate-factory
        (fn [workspace-runtime]
          (let [prt (program/runtime
                     {:workspace (ws/workspace-port workspace-runtime)
                      :control (ws/control-port workspace-runtime)
                      :clock (fn [] 0)})]
            (swap! programs assoc (str (:root workspace-runtime)) prt)
            (partial program/evaluate-program! prt)))
        started (service/start!
                 (merge {:mission-id (str "slice-e2e-" (System/nanoTime))
                         :repo repo
                         :worktree-root worktree-root
                         :launch-swarm! (fn [_] true)
                         :evaluate-factory evaluate-factory
                         :clock #(System/currentTimeMillis)
                         :retention-ms 60000}
                        opts))]
    (is (:ok? started) (pr-str started))
    (when-not (:ok? started)
      (throw (ex-info "fleet service failed to start" started)))
    {:service (:service started)
     :programs programs}))

(defn- stop-system!
  [{:keys [service programs]}]
  (when service
    (try
      (service/stop! service)
      (catch Throwable _ nil)))
  (doseq [prt (vals @programs)]
    (try
      (program/stop! prt)
      (catch Throwable _ nil))))

(defn- by-slice
  [swarms]
  (into {} (map (juxt :slice-id identity)) swarms))

(defn- fork-slices!
  [service slices]
  (let [result (service/fork! service slices)]
    (is (:ok? result) (pr-str result))
    (when-not (:ok? result)
      (throw (ex-info "fleet fork failed" result)))
    (:swarms result)))

(defn- claim-root!
  [runtime]
  (let [result (swarm/execute!
                (:kernel runtime)
                {:op :claim
                 :actor-id (:control-agent-id runtime)
                 :task-id (:root-task-id runtime)
                 :atomicity-reason "worktree slice protected write"})]
    (is (:ok? result) (pr-str result))
    {:agent-id (:control-agent-id runtime)
     :task-id (:root-task-id runtime)
     :claim-id (:claim-id result)}))

(defn- execute-slice-program!
  [{:keys [service]} swarm source intent]
  (let [runtime (service/runtime service (:swarm-id swarm))
        ids (claim-root! runtime)
        workspace (:workspace runtime)
        tenure (str "fleet-" (:swarm-id swarm))
        result (worktree/execute-program!
                {tenure runtime}
                {:agent-id (:agent-id ids)
                 :claim-id (:claim-id ids)
                 :task-id (:task-id ids)
                 :attempt-id (str (:swarm-id swarm) "-attempt")
                 :call-id (str (:swarm-id swarm) "-call")
                 :role :clj-worker
                 :program-role :clj-worker
                 :holder? false
                 :s1? false
                 :tenure-id tenure
                 :workspace workspace
                 :intent intent
                 :program source})]
    {:workspace workspace :result result}))

(defn- write-slice-file!
  [{:keys [service] :as system} swarm file content]
  (let [source (str "(defprogram slice-write []\n"
                    "  (write {:path " (pr-str file)
                    " :content " (pr-str content) "})\n"
                    "  {:ok true})")
        {:keys [workspace result]}
        (execute-slice-program! system swarm source
                                (str "write " file " in the slice worktree"))]
    (is (= :succeeded (:status result)) (pr-str result))
    {:workspace workspace :result result}))

(defn- commit-worktree-file!
  [workspace file]
  (support/git! workspace "add" file)
  (support/git! workspace "commit" "-m" (str "slice adds " file))
  (str/trim (:out (support/git! workspace "rev-parse" "HEAD"))))

(defn- complete-slice!
  [service swarm]
  (let [result (service/complete! service (:swarm-id swarm)
                                  (str "report-" (:slice-id swarm))
                                  {:board-version 1})]
    (is (:ok? result) (pr-str result))
    result))

(defn- join-result!
  [service]
  (service/join! service {:timeout-ms 15000}))

(defn- rev-count
  [repo & options]
  (Long/parseLong
   (str/trim (:out (apply support/git! repo "rev-list" "--count" options)))))

(defn- master-status
  [repo]
  (str/trim (:out (support/git! repo "status" "--porcelain"))))

(deftest protected-write-is-isolated-and-ffs-to-master
  (with-real-git
    "protected-write-is-isolated-and-ffs-to-master"
    (fn []
      (support/with-temp-dir [root "hc-worktree-slice-one-"]
        (let [repo (str root "/repo")
              worktree-root (str root "/worktrees")
              _ (.mkdirs (io/file repo))
              _ (support/init-repo! repo)
              system (start-system! repo worktree-root {})]
          (try
            (let [service (:service system)
                  swarm (first (fork-slices! service [(slice "a")]))
                  workspace (:workspace swarm)]
              (testing "the protected write lands only in the worktree"
                (write-slice-file! system swarm "slice.txt" "from-a\n")
                (is (= "from-a\n" (slurp (io/file workspace "slice.txt"))))
                (is (not (.exists (io/file repo "slice.txt"))))
                (is (str/blank? (master-status repo))))
              (commit-worktree-file! workspace "slice.txt")
              (complete-slice! service swarm)
              (let [joined (join-result! service)]
                (is (= (:swarm-id swarm) (:swarm-id joined)) (pr-str joined))
                (is (string? (:ff-commit joined))))
              (testing "the ff brings the exact bytes to master"
                (is (= "from-a\n" (slurp (io/file repo "slice.txt"))))
                (is (str/blank? (master-status repo))))
              (let [all (rev-count repo "master")
                    first-parent (rev-count repo "--first-parent" "master")
                    merges (rev-count repo "--merges" "master")]
                (is (= 2 all) "base commit plus the slice commit")
                (is (= all first-parent))
                (is (zero? merges))
                (println "worktree-slice/one evidence"
                         {:slice (:slice-id swarm)
                          :worktree workspace
                          :master-file (slurp (io/file repo "slice.txt"))
                          :rev-list all
                          :first-parent first-parent
                          :merges merges})))
            (finally
              (stop-system! system))))))))

(deftest two-disjoint-slices-both-reach-master-linearly
  (with-real-git
    "two-disjoint-slices-both-reach-master-linearly"
    (fn []
      (support/with-temp-dir [root "hc-worktree-slice-disjoint-"]
        (let [repo (str root "/repo")
              worktree-root (str root "/worktrees")
              _ (.mkdirs (io/file repo))
              _ (support/init-repo! repo)
              system (start-system! repo worktree-root {})]
          (try
            (let [service (:service system)
                  swarms (by-slice (fork-slices! service [(slice "a") (slice "b")]))
                  swarm-a (get swarms "a")
                  swarm-b (get swarms "b")]
              (write-slice-file! system swarm-a "a.txt" "from-a\n")
              (write-slice-file! system swarm-b "b.txt" "from-b\n")
              (commit-worktree-file! (:workspace swarm-a) "a.txt")
              (commit-worktree-file! (:workspace swarm-b) "b.txt")
              (complete-slice! service swarm-a)
              (complete-slice! service swarm-b)
              (let [first-result (join-result! service)
                    second-result (join-result! service)]
                (is (map? first-result) (pr-str first-result))
                (is (map? second-result) (pr-str second-result))
                (is (= #{(:swarm-id swarm-a) (:swarm-id swarm-b)}
                       (set [(:swarm-id first-result) (:swarm-id second-result)]))))
              (is (= "from-a\n" (slurp (io/file repo "a.txt"))))
              (is (= "from-b\n" (slurp (io/file repo "b.txt"))))
              (is (str/blank? (master-status repo)))
              (let [all (rev-count repo "master")
                    first-parent (rev-count repo "--first-parent" "master")
                    merges (rev-count repo "--merges" "master")]
                (is (= 3 all) "base plus two disjoint slice commits")
                (is (= all first-parent))
                (is (zero? merges))
                (println "worktree-slice/disjoint evidence"
                         {:a (slurp (io/file repo "a.txt"))
                          :b (slurp (io/file repo "b.txt"))
                          :rev-list all
                          :first-parent first-parent
                          :merges merges})))
            (finally
              (stop-system! system))))))))

(deftest same-file-conflict-abandons-the-second-slice
  (with-real-git
    "same-file-conflict-abandons-the-second-slice"
    (fn []
      (support/with-temp-dir [root "hc-worktree-slice-conflict-"]
        (let [repo (str root "/repo")
              worktree-root (str root "/worktrees")
              _ (.mkdirs (io/file repo))
              _ (support/init-repo! repo)
              resolver-calls (atom [])
              system (start-system!
                      repo worktree-root
                      {:resolve-conflict!
                       (fn [request]
                         (swap! resolver-calls conj request)
                         :abandon)})]
          (try
            (let [service (:service system)
                  swarms (by-slice (fork-slices! service [(slice "a") (slice "b")]))
                  swarm-a (get swarms "a")
                  swarm-b (get swarms "b")]
              (write-slice-file! system swarm-a "shared.txt" "from-a\n")
              (write-slice-file! system swarm-b "shared.txt" "from-b\n")
              (commit-worktree-file! (:workspace swarm-a) "shared.txt")
              (commit-worktree-file! (:workspace swarm-b) "shared.txt")
              (complete-slice! service swarm-a)
              (complete-slice! service swarm-b)
              (let [a-result (join-result! service)]
                (is (= (:swarm-id swarm-a) (:swarm-id a-result))
                    (pr-str a-result))
                (let [after-conflict (join-result! service)
                      state (service/state service)
                      b-state (get-in state [:swarms (:swarm-id swarm-b)])]
                  (is (= :none after-conflict)
                      "the only remaining result was abandoned, so join is done")
                  (is (= 1 (count @resolver-calls))
                      "the second slice entered the conflict resolver once")
                  (is (= (:swarm-id swarm-b)
                         (:swarm-id (first @resolver-calls))))
                  (is (= :abandoned (:status b-state)))
                  (is (number? (:gc-after b-state)))
                  (is (false? (boolean (:worktree-removed? b-state))))
                  (is (nil? (:ff-commit b-state)))
                  (is (not-any? #{(:swarm-id swarm-b)}
                                (:completion-queue state))))
                (is (= "from-a\n" (slurp (io/file repo "shared.txt")))
                    "master keeps the first joined version, never a mix")
                (is (= 2 (rev-count repo "--first-parent" "master")))
                (is (zero? (rev-count repo "--merges" "master")))
                (let [final-state (service/state service)
                      final-b (get-in final-state [:swarms (:swarm-id swarm-b)])]
                  (println "worktree-slice/conflict evidence"
                           {:a (:ff-commit a-result)
                            :shared (slurp (io/file repo "shared.txt"))
                            :b-status (:status final-b)
                            :b-gc-after (:gc-after final-b)
                            :b-in-queue?
                            (boolean (some #{(:swarm-id swarm-b)}
                                           (:completion-queue final-state)))
                            :resolver-calls (count @resolver-calls)
                            :master-count (rev-count repo
                                                     "--first-parent"
                                                     "master")}))))
            (finally
              (stop-system! system))))))))

(deftest protected-write-detects-an-external-change-inside-the-worktree
  (with-real-git
    "protected-write-detects-an-external-change-inside-the-worktree"
    (fn []
      (support/with-temp-dir [root "hc-worktree-slice-conflict-file-"]
        (let [repo (str root "/repo")
              worktree-root (str root "/worktrees")
              _ (.mkdirs (io/file repo))
              _ (support/init-repo! repo)
              system (start-system! repo worktree-root {})]
          (try
            (let [service (:service system)
                  swarm (first (fork-slices! service [(slice "a")]))
                  workspace (:workspace swarm)
                  prt (get @(:programs system) (str workspace))]
              (is (some? prt) "the worktree has one program runtime")
              (spit (io/file workspace "conflict.txt") "initial\n")
              (let [source (str "(defprogram slice-conflict []\n"
                                "  (cat {:path \"conflict.txt\"})\n"
                                "  (park {:payload {:stage :read}})\n"
                                "  (write {:path \"conflict.txt\" :content \"inside\\n\"})\n"
                                "  {:ok true})")
                    parked (execute-slice-program!
                            system swarm source
                            "read, park, then write the same worktree file")]
                (is (= :succeeded (:status (:result parked)))
                    (pr-str (:result parked)))
                (is (true? (:parked? (:result parked))))
                (is (= "initial\n" (slurp (io/file workspace "conflict.txt")))))
              ;; Simulate an external writer between the protected read and
              ;; the protected commit.
              (spit (io/file workspace "conflict.txt") "external\n")
              (let [resumed (program/unpark!
                             prt
                             {:call-id "conflict-unpark"
                              :attempt-id "conflict-unpark"
                              :name "slice-conflict"
                              :value {:resume true}
                              :intent "resume the parked protected write"})]
                (is (= :FILE_CONFLICT (:status resumed)) (pr-str resumed))
                (is (= :FILE_CONFLICT (get-in resumed [:error :status])))
                (is (= "external\n" (slurp (io/file workspace "conflict.txt")))
                    "the rejected protected write leaves the external bytes"))
              (println "worktree-slice/file-conflict evidence"
                       {:worktree workspace
                        :file (slurp (io/file workspace "conflict.txt"))}))
            (finally
              (stop-system! system))))))))

(deftest joined-and-abandoned-worktrees-are-retained-until-gc
  (with-real-git
    "joined-and-abandoned-worktrees-are-retained-until-gc"
    (fn []
      (support/with-temp-dir [root "hc-worktree-slice-gc-"]
        (let [repo (str root "/repo")
              worktree-root (str root "/worktrees")
              _ (.mkdirs (io/file repo))
              _ (support/init-repo! repo)
              now (atom 1000)
              system (start-system! repo worktree-root
                                    {:retention-ms 60000
                                     :clock (fn [] @now)})]
          (try
            (let [service (:service system)
                  swarms (by-slice (fork-slices! service [(slice "a") (slice "b")]))
                  swarm-a (get swarms "a")
                  swarm-b (get swarms "b")
                  ws-a (:workspace swarm-a)
                  ws-b (:workspace swarm-b)]
              (write-slice-file! system swarm-a "kept.txt" "kept\n")
              (commit-worktree-file! ws-a "kept.txt")
              (complete-slice! service swarm-a)
              (let [joined (join-result! service)]
                (is (= (:swarm-id swarm-a) (:swarm-id joined))))
              (let [abandoned (service/abandon! service (:swarm-id swarm-b)
                                                "work no longer needed")]
                (is (:ok? abandoned) (pr-str abandoned)))
              (let [state (service/state service)
                    a-state (get-in state [:swarms (:swarm-id swarm-a)])
                    b-state (get-in state [:swarms (:swarm-id swarm-b)])
                    expected (+ @now 60000)]
                (is (= expected (:gc-after a-state)))
                (is (= expected (:gc-after b-state)))
                (is (false? (boolean (:worktree-removed? a-state))))
                (is (false? (boolean (:worktree-removed? b-state))))
                (is (.exists (io/file ws-a)))
                (is (.exists (io/file ws-b))))
              (let [early (service/gc! service)]
                (testing "inside the retention window gc removes nothing"
                  (is (:ok? early) (pr-str early))
                  (is (empty? (:removed early)))
                  (is (.exists (io/file ws-a)))
                  (is (.exists (io/file ws-b))))
                (testing "after the retention deadline gc removes both"
                  (reset! now 70000)
                  (let [late (service/gc! service)
                        state (service/state service)]
                    (is (:ok? late) (pr-str late))
                    (is (= #{(:swarm-id swarm-a) (:swarm-id swarm-b)}
                           (set (:removed late))))
                    (is (false? (.exists (io/file ws-a))))
                    (is (false? (.exists (io/file ws-b))))
                    (is (true? (get-in state [:swarms (:swarm-id swarm-a)
                                              :worktree-removed?])))
                    (is (true? (get-in state [:swarms (:swarm-id swarm-b)
                                              :worktree-removed?])))
                    (println "worktree-slice/gc evidence"
                             {:gc-after (+ 1000 60000)
                              :first-gc-removed (:removed early)
                              :late-gc-removed (:removed late)
                              :worktrees-present? false})))))
            (finally
              (stop-system! system))))))))
