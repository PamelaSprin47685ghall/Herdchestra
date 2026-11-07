(ns hc.compose.fleet.e2e-conflict-test
  "End-to-end fleet chain with real Git and a scripted holder actor.

  Real worktrees, boards, token holder, supervisor tool loop, holder conflict
  task, rebase continue, and fast-forward are exercised.  The model/tool
  gateway is scripted; it is not a real LLM."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.compose.fleet.actors :as actors]
            [hc.compose.fleet.conflict :as conflict]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.runtime :as runtime]
            [hc.compose.fleet.service :as service]
            [hc.compose.fleet.support :as support]
            [hc.compose.fleet.supervisor :as supervisor]
            [hc.core.swarm-stm :as swarm]))

(defn- slice
  [id]
  {:slice-id id
   :goal (str "goal " id)
   :inputs []
   :depends-on []
   :done-when "done"})

(defn- conflict-task
  [board]
  (->> (:tasks board)
       vals
       (filter (fn [task]
                 (and (:needs-token task)
                      (str/starts-with? (str (:ledger-key task))
                                        "fleet-conflict-")
                      (not= :done (:state task)))))
       first))

(defn- install-holder!
  [rt]
  (let [board (runtime/board rt)
        control (:control-agent-id rt)
        holder (->> (:actors board)
                    vals
                    (remove #(= control (:agent-id %)))
                    first
                    :agent-id)
        kernel (:kernel rt)
        hold (swarm/execute!
              kernel
              {:op :create-task
               :actor-id control
               :ledger-key (str "hold-" (:swarm-id rt))
               :goal "Hold the token until the fleet join"
               :remaining-work "Wait for the join"
               :done-when "The fleet join completes"
               :needs-token true})
        taken (swarm/execute!
               kernel
               {:op :take-or-die
                :actor-id holder
                :confirm :i-hereby-confirm-i-die-without-regret})]
    (when (or (nil? holder) (:error hold) (:error taken))
      (throw (ex-info "E2E holder setup failed"
                      {:holder holder :hold hold :taken taken})))
    holder))

(defn- finish-conflict!
  [rt]
  (let [board (runtime/board rt)
        task (conflict-task board)
        holder (get-in board [:token-slot :holder-id])
        kernel (:kernel rt)
        existing-claim (get-in board [:tasks (:task-id task) :claim-id])
        claimed (when-not existing-claim
                  (swarm/execute!
                   kernel
                   {:op :claim
                    :actor-id holder
                    :task-id (:task-id task)
                    :atomicity-reason "fleet holder resolves rebase conflict"}))
        claim-id (or existing-claim (:claim-id claimed))
        finished (when claim-id
                   (swarm/execute!
                    kernel
                    {:op :finish
                     :actor-id holder
                     :task-id (:task-id task)
                     :claim-id claim-id
                     :read-set []
                     :result-summary "rebase conflict resolved"}))]
    (when (or (nil? task) (nil? claim-id) (:error finished)
              (and claimed (:error claimed)))
      (throw (ex-info "E2E conflict finish failed"
                      {:task task :claimed claimed :claim-id claim-id
                       :finished finished})))
    finished))

(defn- shell-call-leg!
  "Exercise the holder `(call ...)` boundary on the real worktree.

  The test supplies the shell-call port the app injects into the worktree
  evaluator: it must run against this runtime's root/gate kernel, write only
  in this worktree, and publish a `:shell` action on this worktree's board.
  It deliberately does not spawn an OS process; the app covers the real
  process behind the same port."
  [rt master-repo]
  (let [ws (:workspace-runtime rt)
        board (runtime/board rt)
        task (conflict-task board)
        holder (get-in board [:token-slot :holder-id])
        kernel (:kernel rt)
        claimed (swarm/execute!
                 kernel
                 {:op :claim
                  :actor-id holder
                  :task-id (:task-id task)
                  :atomicity-reason "fleet holder conflict shell"})
        claim-id (:claim-id claimed)
        action (when claim-id
                 (swarm/execute!
                  kernel
                  {:op :begin-action
                   :actor-id holder
                   :task-id (:task-id task)
                   :claim-id claim-id
                   :kind :shell
                   :request-id (str "fleet-shell-" (:swarm-id rt))}))
        action-id (:action-id action)]
    (when (or (nil? (:root ws))
              (not= (:root ws) (:workspace rt))
              (not (identical? (:gate ws) (:write-gate rt)))
              ;; make-runtime assoc-in's the verifier, so the kernel map is a
              ;; fresh map over the same state atom; the board is what matters.
              (not (identical? (:state (:kernel ws)) (:state kernel)))
              (nil? claim-id)
              (nil? action-id))
      (throw (ex-info "E2E shell-call boundary failed"
                      {:root (:root ws)
                       :workspace (:workspace rt)
                       :gate-same? (identical? (:gate ws) (:write-gate rt))
                       :kernel-state-same? (identical? (:state (:kernel ws))
                                                       (:state kernel))
                       :claimed claimed :action action})))
    (spit (io/file (:root ws) "shell-call.txt") "worktree-only\n")
    ;; Observe master at execution time, before any staging or join.  After
    ;; the conflict is staged and fast-forwarded this file legitimately
    ;; becomes part of master through the slice commit.
    (when (.exists (io/file master-repo "shell-call.txt"))
      (throw (ex-info "shell call leaked into master at execution time"
                      {:worktree (:root ws) :master master-repo})))
    (let [finished (swarm/execute!
                    kernel
                    {:op :advance-action
                     :actor-id holder
                     :action-id action-id
                     :phase :running})]
      (when (:error finished)
        (throw (ex-info "E2E shell action start failed" finished))))
    (let [finished (swarm/execute!
                    kernel
                    {:op :advance-action
                     :actor-id holder
                     :action-id action-id
                     :phase :finished
                     :result-ref {:request-id (str "fleet-shell-" (:swarm-id rt))
                                  :status :APPLIED
                                  :summary "shell call ran in worktree"}})]
      (when (:error finished)
        (throw (ex-info "E2E shell action finish failed" finished))))
    {:claim-id claim-id
     :action-id action-id
     :root (:root ws)
     :file (.getPath (io/file (:root ws) "shell-call.txt"))}))

(defn- start-effect!
  [sup-ref diag]
  (fn [effect]
    (let [request (:request effect)
          board (:board-snapshot request)
          task (conflict-task board)
          role (supervisor/mission-role request)
          holder? (= :holder role)
          file (io/file (:workspace request) "base.txt")
          shell-file (io/file (:workspace request) "shell-call.txt")
          resolved? (and (.exists file)
                         (str/includes? (slurp file) "resolved"))]
      (swap! diag conj
             {:kind :effect
              :swarm-id (:swarm-id effect)
              :attempt-id (:attempt-id effect)
              :role role
              :request-role (:role request)
              :task-id (:task-id task)
              :workspace (:workspace request)
              :file (.getPath file)
              :file-exists? (.exists file)
              :resolved? resolved?
              :holder? holder?})
      (when (and holder? task)
        (future
          (Thread/sleep 5)
          (let [event (cond
                        (not resolved?)
                        {:event :tool-call
                         :attempt-id (:attempt-id effect)
                         :call-id (str "write-" (:attempt-id effect))
                         :name "write"
                         :arguments {:path "base.txt"
                                     :content "resolved\n"}}

                        (not (.exists shell-file))
                        {:event :tool-call
                         :attempt-id (:attempt-id effect)
                         :call-id (str "call-" (:attempt-id effect))
                         :name "call"
                         :arguments {:purpose :holder-conflict-shell}}

                        :else
                        {:event :tool-call
                         :attempt-id (:attempt-id effect)
                         :call-id (str "finish-" (:attempt-id effect))
                         :name "finish"
                         :arguments {:task-id (:task-id task)}})]
            (swap! diag conj
                   {:kind :event-sent
                    :swarm-id (:swarm-id effect)
                    :attempt-id (:attempt-id effect)
                    :name (:name event)})
            (let [result ((:handle-event! @sup-ref) (:swarm-id effect) event)]
              (swap! diag conj
                     {:kind :event-result
                      :attempt-id (:attempt-id effect)
                      :name (:name event)
                      :ok? (:ok? result)
                      :error (:error result)
                      :tool-result (:tool-result result)
                      :continued-ok? (get-in result [:continued :ok?])
                      :continued-error (get-in result [:continued :error])})))))
      {:ok? true})))

(defn- execute-tool!
  [{:keys [runtime event]} diag master-repo]
  (let [result
        (case (:name event)
          "write"
          (do
            (spit (io/file (:workspace runtime)
                           (get-in event [:arguments :path]))
                  (get-in event [:arguments :content]))
            {:status :succeeded
             :value {:path (get-in event [:arguments :path])}})

          "call"
          {:status :succeeded
           :value (shell-call-leg! runtime master-repo)}

          "finish"
          {:status :succeeded
           :value (finish-conflict! runtime)}

          {:status :failed
           :error {:code :UNKNOWN_E2E_TOOL
                   :name (:name event)}})]
    (swap! diag conj
           {:kind :tool
            :name (:name event)
            :swarm-id (:swarm-id runtime)
            :workspace (:workspace runtime)
            :result (select-keys result [:status :error])})
    result))

(defn- run-e2e!
  [repo worktree-root]
  (let [sup-ref (atom nil)
        service-ref (atom nil)
        diag (atom [])
        supervisor (supervisor/make
                    {:service-ref service-ref
                     :start-effect! (start-effect! sup-ref diag)
                     :execute-tool! (fn [request]
                                      (execute-tool! request diag repo))})
        _ (reset! sup-ref supervisor)
        holder-resolve (conflict/holder-resolver
                        {:service-ref service-ref
                         :start-effects! (:start-effects! supervisor)
                         :timeout-ms 5000
                         :poll-ms 5})
        resolver (conflict/make-resolver
                  {:git (git/git-cli repo)
                   :holder-resolve! holder-resolve})
        launch (fn [{:keys [swarm-id workspace runtime actor]}]
                 (support/commit-file!
                  workspace
                  (if (= "swarm-1" swarm-id) "base.txt" "other.txt")
                  (if (= "swarm-1" swarm-id) "swarm-1\n" "swarm-2\n")
                  (str "work " swarm-id))
                 (when (= "swarm-1" swarm-id)
                   (install-holder! runtime))
                 ;; This scripted launcher does not dispatch the initial
                 ;; model requests.  Close those attempts through the real
                 ;; actor transition so the slots are idle when the conflict
                 ;; resolver later wakes the holder.
                 (let [state (reduce
                              (fn [state effect]
                                (if (= :model/start (:type effect))
                                  (let [accepted (actors/accept-event
                                                  state (:attempt-id effect)
                                                  {:event :completed :text "idle"})]
                                    (if (:ok? accepted)
                                      (:state accepted)
                                      (throw (ex-info "E2E initial actor close failed"
                                                      accepted))))
                                  state))
                              (:state actor) (:effects actor))]
                   {:ok? true :actor (assoc actor :state state)}))
        started (service/start!
                 {:mission-id "e2e"
                  :repo repo
                  :worktree-root worktree-root
                  :master-ref "master"
                  :models {:main "main" :task "task"}
                  :slot-ids [:slot-a :slot-b]
                  :launch-swarm! launch
                  :resolve-conflict! resolver
                  :retention-ms 60000})]
    (when-not (:ok? started)
      (throw (ex-info "E2E service start failed" started)))
    (let [svc (:service started)]
      (reset! service-ref svc)
      (when-not (:ok? (service/fork! svc [(slice "a") (slice "b")]))
        (throw (ex-info "E2E fork failed" (service/state svc))))
      (let [actor (service/actor-state svc "swarm-1")
            slots (get-in actor [:state :actors :tenures "fleet-swarm-1" :slots])
            running (filter #(= :running (:status %)) (vals slots))]
        (when (seq running)
          (throw (ex-info "E2E launch left actor slots running"
                          {:slots slots :actor actor}))))
      (support/commit-file! repo "base.txt" "master\n" "master moves")
      (let [c1 (service/complete! svc "swarm-1" "report-a" {:board-version 1})
            c2 (service/complete! svc "swarm-2" "report-b" {:board-version 1})]
        (when-not (:ok? c1)
          (throw (ex-info "E2E complete swarm-1 failed"
                          {:result c1 :state (service/state svc)})))
        (when-not (:ok? c2)
          (throw (ex-info "E2E complete swarm-2 failed"
                          {:result c2 :state (service/state svc)}))))
      (let [first-result (service/join! svc)
            rows (service/fleet-rows svc)
            state (service/state svc)]
        (when-not (= "swarm-1" (:swarm-id first-result))
          (throw (ex-info "E2E first join was not swarm-1"
                          {:first first-result
                           :rows rows
                           :state state
                           :diag @diag
                           :actor
                           (let [actor (service/actor-state svc "swarm-1")
                                 actors (:actors (:state actor))]
                             {:slots (get-in actors
                                             [:tenures "fleet-swarm-1" :slots])
                              :calls
                              (into {}
                                    (map (fn [[attempt-id call]]
                                           [attempt-id
                                            (select-keys call
                                                         [:slot-id :tenure-id
                                                          :status :call-id])]))
                                    (:calls actors))})
                           :swarm-1 (first (filter #(= "swarm-1" (:swarm-id %))
                                                   rows))})))
        {:service svc
         :first first-result
         :second (service/join! svc)}))))

(deftest real-fork-join-with-holder-conflict-resolution
  (support/with-temp-dir [root "hc-fleet-e2e-"]
    (let [repo (str root "/repo")]
      (.mkdirs (io/file repo))
      (support/init-repo! repo)
      (let [{:keys [service] first-join :first second-join :second}
            (run-e2e! repo (str root "/worktrees"))]
        (is (= "swarm-1" (:swarm-id first-join)))
        (is (string? (:ff-commit first-join)))
        (is (= "swarm-2" (:swarm-id second-join)))
        (let [rows (service/fleet-rows service)
              by-id (into {} (map (juxt :swarm-id identity)) rows)
              row (get by-id "swarm-1")
              shell-file (io/file (:worktree row) "shell-call.txt")
              shell-actions (->> (get-in row [:board :actions])
                                 vals
                                 (filter #(and (= :shell (:kind %))
                                               (= :finished (:phase %)))))]
          (is (= :none (service/join! service)))
          (is (= "resolved\n" (slurp (io/file repo "base.txt"))))
          (is (= "swarm-2\n" (slurp (io/file repo "other.txt"))))
          (is (.exists shell-file)
              "holder call wrote into its own worktree")
          ;; The call leg asserted master was untouched at execution time.
          ;; The file now appears in master because the holder staged it and
          ;; the slice was fast-forwarded; that is the intended integration.
          (is (.exists (io/file repo "shell-call.txt"))
              "joined slice commit carries the worktree shell marker")
          (is (= 1 (count shell-actions))
              "holder shell action finished on its own board")
          (is (= :joined (:status row))
              (pr-str {:rows (mapv #(select-keys % [:swarm-id :status
                                                    :last-event])
                                   rows)
                       :state-keys (keys (:swarms (service/state service)))
                       :row row})))))))
