(ns hc.compose.workspace-owner-integration-test
  "Real Owner mailbox + real filesystem + real program runtime.

  Unlike the proof-aware test adapter in workspace_integration_test, this
  namespace starts hc.compose.owner and uses its authoritative board and
  three-argument execute-board! proof path."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission.proof :as proof]
            [hc.compose.workspace :as ws]
            [hc.core.workspace-gate :as wg]
            [hc.server.program.runtime :as program])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root []
  (Files/createTempDirectory "hc-ws-owner-" (make-array FileAttribute 0)))

(defn- cleanup! [root]
  (doseq [f (reverse (file-seq (io/file (str root))))]
    (io/delete-file f true)))

(defn- compose-available? []
  (try
    (require 'hc.compose.owner)
    true
    (catch Throwable _
      false)))

(defn- start-owner! []
  ((requiring-resolve 'hc.compose.owner/start-owner!)
   {:session-id "ws-owner"
    :ephemeral? true
    :plan-milestone :pending
    :draft {:path "plans/demo.md" :content "# plan"}
    :clock (fn [] 0)}))

(defn- start-execution!
  [handle]
  ((:submit-command! handle)
   {:command-id "start-execution"
    :command-epoch 1
    :payload {:kind :review/submit
              :lane :plan
              :tenure-id "tenure-1"
              :findings []}})
  ((:submit-command! handle)
   {:command-id "start-swarm"
    :command-epoch 1
    :payload {:kind :review/submit
              :lane :execution
              :tenure-id "tenure-2"
              :findings [{:key "k1" :value "root work"}]}}))

(defn- start-plan-owner!
  "Start an Owner with an unreviewed Plan tenure and no fixed draft."
  []
  ((requiring-resolve 'hc.compose.owner/start-owner!)
   {:session-id "ws-owner-plan"
    :ephemeral? true
    :clock (fn [] 0)}))

(defn- submit!
  [handle command-id payload]
  ((:submit-command! handle)
   {:command-id command-id
    :command-epoch 1
    :payload payload}))

(defn- plan-draft-program
  [path content]
  (str "(defprogram plan-draft []\n"
       "  (write {:path \"" path "\" :content \"" content "\"}))"))

(defn- setup-claimed-task!
  [handle]
  (let [tenure-id "tenure-2"
        registered ((:execute-board! handle)
                    {:op :register-actor
                     :tenure-id tenure-id
                     :slot-id "w1"})
        actor-id (:agent-id registered)]
    ((:execute-board! handle)
     {:op :claim
      :tenure-id tenure-id
      :actor-id actor-id
      :task-id :task-1
      :atomicity-reason "own the parent before split"})
    ((:execute-board! handle)
     {:op :split
      :tenure-id tenure-id
      :actor-id actor-id
      :task-id :task-1
      :remaining-work "parent integration"
      :children [{:goal "g"
                  :remaining-work "r"
                  :done-when "d"
                  :needs-token false
                  :owner-id actor-id}]
      :wait-for #{}})
    ((:execute-board! handle)
     {:op :claim
      :tenure-id tenure-id
      :actor-id actor-id
      :task-id :task-2
      :atomicity-reason "atomic"})
    {:actor-id actor-id
     :tenure-id tenure-id
     :claim-id (get-in ((:board-snapshot handle))
                       [:tasks :task-2 :claim-id])}))

(defn- setup-claimed-root!
  [handle]
  (let [tenure-id "tenure-2"
        registered ((:execute-board! handle)
                    {:op :register-actor
                     :tenure-id tenure-id
                     :slot-id "w1"})
        actor-id (:agent-id registered)]
    ((:execute-board! handle)
     {:op :claim
      :tenure-id tenure-id
      :actor-id actor-id
      :task-id :task-1
      :atomicity-reason "root finish"})
    {:tenure-id tenure-id
     :actor-id actor-id
     :claim-id (get-in ((:board-snapshot handle))
                       [:tasks :task-1 :claim-id])}))

(defn- with-owner-runtime
  [root handle f]
  (let [ws-runtime (ws/make-runtime
                    {:root root
                     :kernel nil
                     :write-gate (wg/write-gate)
                     :execute-board! (:execute-board! handle)
                     :board-snapshot (:board-snapshot handle)
                     :clock (fn [] 0)})
        port (ws/workspace-port ws-runtime)
        control (ws/control-port ws-runtime)
        program-runtime (program/runtime {:workspace port :control control})
        ws-runtime (assoc ws-runtime
                          :evaluate (partial program/evaluate-program!
                                             program-runtime))]
    (try
      (f ws-runtime)
      (finally
        (program/stop! program-runtime)))))

(deftest real-owner-authorizes-a-plan-draft-by-tenure-and-claim
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
          handle (start-plan-owner!)]
      (try
        (let [reviewed (submit! handle "plan-review-1"
                                {:kind :review/submit
                                 :lane :plan
                                 :tenure-id "tenure-1"
                                 :findings [{:key "goal" :value "draft"}]})]
          (is (= :accepted (:status reviewed)) (pr-str reviewed)))
        (with-owner-runtime
          root handle
          (fn [rt]
            (let [out (ws/run-program!
                       rt
                       {:agent-id :plan
                        :role :clj-plan
                        :tenure-id "tenure-1"
                        :lane :plan
                        :write-unlocked? true
                        :attempt-id "plan-draft-1"
                        :call-id "plan-draft-1"
                        :intent "write the first Plan draft"
                        :program (plan-draft-program "plans/real.md"
                                                     "# real draft")})]
              (testing "the draft applies under W without a swarm actor"
                (is (= :succeeded (:status out)) (pr-str out))
                (is (= "# real draft"
                       (slurp (io/file (str root) "plans/real.md"))))
                (is (nil? (get-in ((:board-snapshot handle))
                                  [:actors :plan]))
                    "the Plan draft must not forge a swarm actor")
                (is (false? (wg/write-gate-held? (:gate rt))))
                (is (= "plans/real.md"
                       (get-in ((:snapshot handle))
                               [:state :mission :draft-claim :path])))
                (is (= "tenure-1"
                       (get-in ((:snapshot handle))
                               [:state :mission :draft-claim :tenure-id]))))
              (testing "the app's post-terminal draft command promotes the claim"
                (let [synced (submit!
                              handle "plan-draft-sync-1"
                              {:kind :plan/write-draft
                               :path "plans/real.md"
                               :content (slurp (io/file (str root)
                                                        "plans/real.md"))
                               :tenure-id "tenure-1"})]
                  (is (= :accepted (:status synced)) (pr-str synced))
                  (is (= "plans/real.md"
                         (get-in ((:snapshot handle))
                                 [:state :mission :relay :draft :path])))
                  (is (nil? (get-in ((:snapshot handle))
                                    [:state :mission :draft-claim]))))))))
        (finally
          ((:stop! handle))
          (cleanup! root))))))

(deftest real-owner-refuses-a-plan-draft-from-a-retired-tenure
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
          handle (start-plan-owner!)]
      (try
        (submit! handle "plan-review-unlock"
                 {:kind :review/submit
                  :lane :plan
                  :tenure-id "tenure-1"
                  :findings [{:key "goal" :value "draft"}]})
        (let [relayed (submit! handle "plan-review-relay"
                               {:kind :review/submit
                                :lane :plan
                                :tenure-id "tenure-1"
                                :findings [{:key "goal" :value nil}]})]
          (is (= :accepted (:status relayed)) (pr-str relayed))
          (is (not= "tenure-1"
                    (get-in ((:snapshot handle))
                            [:state :mission :relay :lanes :plan :tenure-id]))
              "the review must relay the Plan tenure"))
        (with-owner-runtime
          root handle
          (fn [rt]
            (let [out (ws/run-program!
                       rt
                       {:agent-id :plan
                        :role :clj-plan
                        :tenure-id "tenure-1"
                        :lane :plan
                        :write-unlocked? true
                        :attempt-id "plan-draft-stale-1"
                        :call-id "plan-draft-stale-1"
                        :intent "a late draft write"
                        :program (plan-draft-program "plans/stale.md"
                                                     "stale")})]
              (is (= :failed (:status out)) (pr-str out))
              (is (= :TENURE_RETIRED (get-in out [:error :code])))
              (is (false? (.exists (io/file (str root) "plans/stale.md")))
                  "a retired tenure must not touch the file system")
              (is (false? (wg/write-gate-held? (:gate rt)))))))
        (finally
          ((:stop! handle))
          (cleanup! root))))))

(defn- payload [s attempt source]
  {:agent-id (:actor-id s)
   :claim-id (:claim-id s)
   :task-id :task-2
   :attempt-id attempt
   :call-id (str "call-" attempt)
   :intent "real owner protected program"
   :program source})

(defn- assert-root-finish-under-w!
  [root handle {:keys [actor-id claim-id tenure-id]}]
  (let [gate (wg/write-gate)
        verify (wg/read-set-verifier root)
        verify-held? (atom nil)
        board-held? (atom nil)
        board-held-after? (atom nil)
        execute-board
        (fn
          ([command]
           ((:execute-board! handle) command))
          ([command timeout-or-opts]
           ((:execute-board! handle) command timeout-or-opts))
          ([command timeout opts]
           (reset! board-held? (wg/write-gate-held? gate))
           (let [result ((:execute-board! handle) command timeout opts)]
             (reset! board-held-after? (wg/write-gate-held? gate))
             result)))
        notifications (atom [])
        notification-seen (promise)
        unsubscribe
        ((:subscribe! handle)
         (fn [notification]
           (swap! notifications conj notification)
           (when (and (= :board/command
                        (get-in notification [:message :type]))
                      (= :finish
                         (get-in notification [:message :command :op])))
             (deliver notification-seen true))))
        ws-runtime
        (ws/make-runtime
         {:root root
          :kernel nil
          :write-gate gate
          :verify-read-set (fn [read-set]
                             (reset! verify-held?
                                     (wg/write-gate-held? gate))
                             (verify read-set))
          :execute-board! execute-board
          :board-snapshot (:board-snapshot handle)
          :clock (fn [] 0)})
        port (ws/workspace-port ws-runtime)
        control (ws/control-port ws-runtime)
        program-runtime (program/runtime {:workspace port
                                          :control control})
        ws-runtime (assoc ws-runtime
                          :evaluate
                          (partial program/evaluate-program!
                                   program-runtime))
        source (str "(defprogram owner-root-finish []\n"
                    "  (let [info (stat {:path \"a.txt\"})\n"
                    "        stamp (select-keys info [:mtime-sec :mtime-nsec :size])]\n"
                    "    (finish {:task-id \"task-1\"\n"
                    "             :claim-id \"" (name claim-id) "\"\n"
                    "             :result-summary \"root verified\"\n"
                    "             :read-set [{:path \"a.txt\" :stamp stamp}]})))")]
    (try
      (let [out (ws/run-program!
                 ws-runtime
                 {:agent-id actor-id
                  :claim-id claim-id
                  :task-id :task-1
                  :attempt-id "owner-root-finish-1"
                  :call-id "call-owner-root-finish-1"
                  :intent "root finish under W"
                  :program source})
            receipt (some #(when (and (= :board/command (:type %))
                                      (= :finish (:op %)))
                             %)
                          (:receipts out))
            result-ref (get-in receipt [:result :result-ref])]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (true? @verify-held?)
            "the read-set verifier must run while W is held")
        (is (true? @board-held?)
            "the Owner commit must run while W is held")
        (is (true? @board-held-after?)
            "W must stay held until the Owner reply")
        (is (false? (wg/write-gate-held? gate)))
        (is (some? receipt))
        (is (map? result-ref))
        (is (= (:read-set result-ref)
               (:verified-read-set result-ref)))
        (is (seq (:read-set result-ref)))
        (is (= 1 (count (get-in ((:snapshot handle))
                                [:state :relay :barriers]))))
        (is (nil? (get-in ((:snapshot handle))
                          [:state :mission :boards tenure-id])))
        (is (not-any? proof/valid? (tree-seq coll? seq out))
            "the W proof must not leak into the program result")
        (is (not-any? proof/valid?
                      (tree-seq coll? seq ((:snapshot handle))))
            "the W proof must not enter committed state")
        (deref notification-seen 1000 nil)
        (is (realized? notification-seen)
            "the Owner must publish the finish notification")
        (is (not-any? proof/valid?
                      (tree-seq coll? seq @notifications))
            "the W proof must not reach Owner subscribers")
        (is (not-any? (fn [notification]
                        (contains? (or (get-in notification [:message :env])
                                       {})
                                   :write-gate-proof))
                      @notifications)
            "notifications must not carry the proof key")
        (is (not-any? (fn [notification]
                        (contains? (or (get-in notification [:message :env])
                                       {})
                                   :verify-read-set))
                      @notifications)
            "notifications must not carry the verifier"))
      (finally
        (unsubscribe)
        (program/stop! program-runtime)))))

(defn- assert-verifier-failure!
  [root handle {:keys [actor-id claim-id]}]
  (let [gate (wg/write-gate)
        verify-held? (atom nil)
        ws-runtime
        (ws/make-runtime
         {:root root
          :kernel nil
          :write-gate gate
          :verify-read-set (fn [_]
                             (reset! verify-held? (wg/write-gate-held? gate))
                             (throw (ex-info "verifier exploded" {})))
          :execute-board! (:execute-board! handle)
          :board-snapshot (:board-snapshot handle)
          :clock (fn [] 0)})
        port (ws/workspace-port ws-runtime)
        control (ws/control-port ws-runtime)
        program-runtime (program/runtime {:workspace port
                                          :control control})
        ws-runtime (assoc ws-runtime
                          :evaluate
                          (partial program/evaluate-program!
                                   program-runtime))
        source (str "(defprogram verifier-fails []\n"
                    "  (finish {:task-id \"task-2\"\n"
                    "           :claim-id \"" (name claim-id) "\"\n"
                    "           :result-summary \"verifier fails\"\n"
                    "           :read-set\n"
                    "           [{:path \"a.txt\"\n"
                    "             :stamp {:mtime-sec 1 :mtime-nsec 0 :size 1}}]}))")]
    (try
      (let [out (ws/run-program! ws-runtime
                                 (payload {:actor-id actor-id
                                           :claim-id claim-id
                                           :task-id :task-2}
                                          "owner-verifier-fail-1"
                                          source))]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (= :FILE_CONFLICT (get-in out [:value :error])))
        (is (= :read-set-check-failed
               (get-in out [:value :detail :reason])))
        (is (true? @verify-held?))
        (is (false? (wg/write-gate-held? gate)))
        (is (= :ready
               (get-in ((:board-snapshot handle))
                       [:tasks :task-2 :state])))
        (is (= claim-id
               (get-in ((:board-snapshot handle))
                       [:tasks :task-2 :claim-id]))))
      (finally
        (program/stop! program-runtime)))))

(deftest real-owner-finish-with-pending-writes-keeps-old-file
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
        handle (start-owner!)]
    (try
      (spit (io/file (str root) "a.txt") "old")
      (start-execution! handle)
      (let [s (setup-claimed-task! handle)]
        (with-owner-runtime
          root handle
          (fn [rt]
            (let [source (str "(defprogram owner-pending []\n"
                              "  (let [info (stat {:path \"a.txt\"})\n"
                              "        stamp (select-keys info [:mtime-sec :mtime-nsec :size])]\n"
                              "    (write {:path \"a.txt\" :content \"new\"})\n"
                              "    (finish {:task-id \"task-2\"\n"
                              "             :claim-id \"" (name (:claim-id s)) "\"\n"
                              "             :result-summary \"owner pending\"\n"
                              "             :read-set [{:path \"a.txt\" :stamp stamp}]})))")
                  out (ws/run-program! rt (payload s "owner-pending-1" source))]
              (testing "finish commits on the real Owner and leaves the write pending"
                (is (= :failed (:status out)) (pr-str out))
                (is (= :CLAIM_CHANGED
                       (:status (some #(when (= :workspace/settle (:type %)) %)
                                      (:receipts out)))))
                (is (= "old" (slurp (io/file (str root) "a.txt"))))
                (is (= :done (get-in ((:board-snapshot handle))
                                     [:tasks :task-2 :state])))
                (is (nil? (get-in ((:board-snapshot handle))
                                  [:tasks :task-2 :claim-id])))
                (is (some (fn [receipt]
                            (and (= :board/command (:type receipt))
                                 (= :finish (:op receipt))
                                 (:ok? receipt)))
                          (:receipts out))))))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))

(deftest real-owner-finish-without-pending-writes-completes
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
        handle (start-owner!)]
    (try
      (spit (io/file (str root) "a.txt") "proof")
      (start-execution! handle)
      (let [s (setup-claimed-task! handle)]
        (with-owner-runtime
          root handle
          (fn [rt]
            (let [source (str "(defprogram owner-finish []\n"
                              "  (let [info (stat {:path \"a.txt\"})\n"
                              "        stamp (select-keys info [:mtime-sec :mtime-nsec :size])]\n"
                              "    (finish {:task-id \"task-2\"\n"
                              "             :claim-id \"" (name (:claim-id s)) "\"\n"
                              "             :result-summary \"owner verified\"\n"
                              "             :read-set [{:path \"a.txt\" :stamp stamp}]})))")
                  out (ws/run-program! rt (payload s "owner-finish-1" source))]
              (is (= :succeeded (:status out)) (pr-str out))
              (is (= :done (get-in ((:board-snapshot handle))
                                   [:tasks :task-2 :state])))
              (is (seq (get-in out [:value :verified-read-set])))))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))

(deftest real-owner-root-finish-keeps-verified-read-set-under-w
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
          handle (start-owner!)]
      (try
        (spit (io/file (str root) "a.txt") "root-proof")
        (start-execution! handle)
        (let [tenure-id "tenure-2"
              registered ((:execute-board! handle)
                          {:op :register-actor
                           :tenure-id tenure-id
                           :slot-id "w1"})
              actor-id (:agent-id registered)]
          ((:execute-board! handle)
           {:op :claim
            :tenure-id tenure-id
            :actor-id actor-id
            :task-id :task-1
            :atomicity-reason "root finish"})
          (let [claim-id (get-in ((:board-snapshot handle))
                                 [:tasks :task-1 :claim-id])
                gate (wg/write-gate)
                verify (wg/read-set-verifier root)
                verify-held? (atom nil)
                board-held? (atom nil)
                board-held-after? (atom nil)
                execute-board
                (fn
                  ([command]
                   ((:execute-board! handle) command))
                  ([command timeout-or-opts]
                   ((:execute-board! handle) command timeout-or-opts))
                  ([command timeout opts]
                   (reset! board-held? (wg/write-gate-held? gate))
                   (let [result ((:execute-board! handle)
                                 command timeout opts)]
                     (reset! board-held-after?
                             (wg/write-gate-held? gate))
                     result)))
                notifications (atom [])
                notification-seen (promise)
                unsubscribe ((:subscribe! handle)
                             (fn [notification]
                               (swap! notifications conj notification)
                               (when (and (= :board/command
                                            (get-in notification
                                                    [:message :type]))
                                          (= :finish
                                             (get-in notification
                                                     [:message :command :op])))
                                 (deliver notification-seen true))))
                ws-runtime
                (ws/make-runtime
                 {:root root
                  :kernel nil
                  :write-gate gate
                  :verify-read-set (fn [read-set]
                                     (reset! verify-held?
                                             (wg/write-gate-held? gate))
                                     (verify read-set))
                  :execute-board! execute-board
                  :board-snapshot (:board-snapshot handle)
                  :clock (fn [] 0)})
                port (ws/workspace-port ws-runtime)
                control (ws/control-port ws-runtime)
                program-runtime (program/runtime {:workspace port
                                                  :control control})
                ws-runtime (assoc ws-runtime
                                  :evaluate
                                  (partial program/evaluate-program!
                                           program-runtime))
                source (str "(defprogram owner-root-finish []\n"
                            "  (let [info (stat {:path \"a.txt\"})\n"
                            "        stamp (select-keys info [:mtime-sec :mtime-nsec :size])]\n"
                            "    (finish {:task-id \"task-1\"\n"
                            "             :claim-id \"" (name claim-id) "\"\n"
                            "             :result-summary \"root verified\"\n"
                            "             :read-set [{:path \"a.txt\" :stamp stamp}]})))")]
            (try
              (let [out (ws/run-program!
                         ws-runtime
                         {:agent-id actor-id
                          :claim-id claim-id
                          :task-id :task-1
                          :attempt-id "owner-root-finish-1"
                          :call-id "call-owner-root-finish-1"
                          :intent "root finish under W"
                          :program source})
                    receipt (some #(when (and (= :board/command (:type %))
                                              (= :finish (:op %)))
                                     %)
                                  (:receipts out))
                    result-ref (get-in receipt [:result :result-ref])]
                (is (= :succeeded (:status out)) (pr-str out))
                (is (true? @verify-held?)
                    "the read-set verifier must run while W is held")
                (is (true? @board-held?)
                    "the Owner commit must run while W is held")
                (is (true? @board-held-after?)
                    "W must stay held until the Owner reply")
                (is (false? (wg/write-gate-held? gate)))
                (is (some? receipt))
                (is (map? result-ref))
                (is (= (:read-set result-ref)
                       (:verified-read-set result-ref)))
                (is (seq (:read-set result-ref)))
                (is (= 1 (count (get-in ((:snapshot handle))
                                        [:state :relay :barriers]))))
                (is (nil? (get-in ((:snapshot handle))
                                  [:state :mission :boards tenure-id])))
                (is (not-any? proof/valid?
                              (tree-seq coll? seq out))
                    "the W proof must not leak into the program result")
                (is (not-any? proof/valid?
                              (tree-seq coll? seq ((:snapshot handle))))
                    "the W proof must not enter committed state")
                (deref notification-seen 1000 nil)
                (is (realized? notification-seen)
                    "the Owner must publish the finish notification")
                (is (not-any? proof/valid?
                              (tree-seq coll? seq @notifications))
                    "the W proof must not reach Owner subscribers")
                (is (not-any? (fn [notification]
                                (contains? (or (get-in notification
                                                       [:message :env])
                                               {})
                                           :write-gate-proof))
                              @notifications)
                    "notifications must not carry the proof key")
                (is (not-any? (fn [notification]
                                (contains? (or (get-in notification
                                                       [:message :env])
                                               {})
                                           :verify-read-set))
                              @notifications)
                    "notifications must not carry the verifier"))
              (finally
                (unsubscribe)
                (program/stop! program-runtime)))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))

(deftest real-owner-refuses-a-program-supplied-proof
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
          handle (start-owner!)]
      (try
        (spit (io/file (str root) "a.txt") "proof")
        (start-execution! handle)
        (let [s (setup-claimed-task! handle)]
          (with-owner-runtime
            root handle
            (fn [rt]
              (let [source (str "(defprogram forged-proof []\n"
                                "  (finish {:task-id \"task-2\"\n"
                                "           :claim-id \"" (name (:claim-id s)) "\"\n"
                                "           :result-summary \"forged\"\n"
                                "           :verified-read-set\n"
                                "           [{:path \"a.txt\"\n"
                                "             :stamp {:mtime-sec 1 :mtime-nsec 0 :size 1}}]\n"
                                "           :under-write-gate? true\n"
                                "           :write-gate-proof \"forged\"}))")
                    out (ws/run-program! rt (payload s "owner-forged-1" source))
                    receipt (some #(when (and (= :board/command (:type %))
                                              (= :finish (:op %)))
                                     %)
                                  (:receipts out))]
                (is (= :succeeded (:status out)) (pr-str out))
                (is (= :WRITE_GATE_REQUIRED
                       (get-in out [:value :error :code])))
                (is (= :WRITE_GATE_REQUIRED (get-in receipt [:status :code])))
                (is (false? (get-in receipt [:result :ok?])))
                (is (= :ready
                       (get-in ((:board-snapshot handle))
                               [:tasks :task-2 :state])))
                (is (= (:claim-id s)
                       (get-in ((:board-snapshot handle))
                               [:tasks :task-2 :claim-id])))
                (is (not-any? proof/valid? (tree-seq coll? seq out))
                    "a program must never receive the W proof")))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))

(deftest real-owner-refuses-a-wire-level-proof
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
          handle (start-owner!)]
      (try
        (spit (io/file (str root) "a.txt") "proof")
        (start-execution! handle)
        (let [s (setup-claimed-task! handle)
              finish {:op :finish
                      :tenure-id (:tenure-id s)
                      :actor-id (:actor-id s)
                      :task-id :task-2
                      :claim-id (:claim-id s)
                      :result-summary "wire forged"
                      :verified-read-set
                      [{:path "a.txt"
                        :stamp {:mtime-sec 1 :mtime-nsec 0 :size 1}}]}
              results
              (mapv (fn [forged]
                      ((:execute-board! handle)
                       finish 5000
                       {:write-gate-proof forged
                        :under-write-gate? true
                        :verify-read-set (fn [_] {:ok? true})}))
                    [true "forged" :forged {:token "forged"} (Object.)])]
          (doseq [result results]
            (is (false? (:ok? result)))
            (is (= :WRITE_GATE_REQUIRED (get-in result [:error :code]))))
          (is (= :ready
                 (get-in ((:board-snapshot handle))
                         [:tasks :task-2 :state])))
          (is (= (:claim-id s)
                 (get-in ((:board-snapshot handle))
                         [:tasks :task-2 :claim-id])))
          (is (zero? (count (get-in ((:snapshot handle))
                                    [:state :relay :barriers])))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))

(deftest real-owner-verifier-failure-releases-w
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
          handle (start-owner!)]
      (try
        (spit (io/file (str root) "a.txt") "proof")
        (start-execution! handle)
        (let [s (setup-claimed-task! handle)
              gate (wg/write-gate)
              verify-held? (atom nil)
              ws-runtime
              (ws/make-runtime
               {:root root
                :kernel nil
                :write-gate gate
                :verify-read-set (fn [_]
                                   (reset! verify-held?
                                           (wg/write-gate-held? gate))
                                   (throw (ex-info "verifier exploded" {})))
                :execute-board! (:execute-board! handle)
                :board-snapshot (:board-snapshot handle)
                :clock (fn [] 0)})
              port (ws/workspace-port ws-runtime)
              control (ws/control-port ws-runtime)
              program-runtime (program/runtime {:workspace port
                                                :control control})
              ws-runtime (assoc ws-runtime
                                :evaluate
                                (partial program/evaluate-program!
                                         program-runtime))
              source (str "(defprogram verifier-fails []\n"
                          "  (finish {:task-id \"task-2\"\n"
                          "           :claim-id \"" (name (:claim-id s)) "\"\n"
                          "           :result-summary \"verifier fails\"\n"
                          "           :read-set\n"
                          "           [{:path \"a.txt\"\n"
                          "             :stamp {:mtime-sec 1 :mtime-nsec 0 :size 1}}]}))")]
          (try
            (let [out (ws/run-program! ws-runtime
                                       (payload s "owner-verifier-fail-1"
                                                source))]
              (is (= :succeeded (:status out)) (pr-str out))
              (is (= :FILE_CONFLICT (get-in out [:value :error])))
              (is (= :read-set-check-failed
                     (get-in out [:value :detail :reason])))
              (is (true? @verify-held?))
              (is (false? (wg/write-gate-held? gate)))
              (is (= :ready
                     (get-in ((:board-snapshot handle))
                             [:tasks :task-2 :state])))
              (is (= (:claim-id s)
                     (get-in ((:board-snapshot handle))
                             [:tasks :task-2 :claim-id]))))
            (finally
              (program/stop! program-runtime))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))

(deftest real-owner-applied-request-has-no-pending-publish
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
        handle (start-owner!)]
    (try
      (spit (io/file (str root) "a.txt") "old")
      (start-execution! handle)
      (let [s (setup-claimed-task! handle)]
        (with-owner-runtime
          root handle
          (fn [rt]
            (let [out (ws/execute!
                       rt
                       {:op :write
                        :agent-id (:actor-id s)
                        :claim-id (:claim-id s)
                        :task-id :task-2
                        :attempt-id "owner-publish-1"
                        :request-id "owner-publish-1"
                        :path "a.txt"
                        :content "new"})]
              (is (= :succeeded (:status out)) (pr-str out))
              (is (= "new" (slurp (io/file (str root) "a.txt"))))
              (testing "the applied request has no pending publication"
                (is (= :UNSUPPORTED_OPERATION
                       (:status (wg/retry-publish! (:wg rt)
                                                  "owner-publish-1")))))))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))

(deftest real-owner-retry-publish-does-not-reapply
  (if-not (compose-available?)
    (is true "skipped: run with -M:test:compose")
    (let [root (temp-root)
        handle (start-owner!)]
    (try
      (spit (io/file (str root) "a.txt") "old")
      (start-execution! handle)
      (let [s (setup-claimed-task! handle)
            failures (atom 6)
            applies (atom 0)
            execute-board
            (fn [command & [timeout opts]]
              (when (and (= :advance-action (:op command))
                         (= :finished (:phase command))
                         (pos? @failures))
                (swap! failures dec)
                (throw (ex-info "simulated publication conflict"
                                {:code :STM_CONFLICT})))
              (if (some? timeout)
                ((:execute-board! handle) command timeout opts)
                ((:execute-board! handle) command)))
            ws-runtime (ws/make-runtime
                        {:root root
                         :kernel nil
                         :write-gate (wg/write-gate)
                         :execute-board! execute-board
                         :board-snapshot (:board-snapshot handle)
                         :clock (fn [] 0)
                         :apply-fn (fn [prepared opts]
                                     (swap! applies inc)
                                     (wg/apply-plan! prepared opts))})
            out (ws/execute! ws-runtime
                             {:op :write
                              :agent-id (:actor-id s)
                              :claim-id (:claim-id s)
                              :task-id :task-2
                              :attempt-id "owner-retry-1"
                              :request-id "owner-retry-1"
                              :path "a.txt"
                              :content "new"})]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (false? (get-in out [:value :published?])))
        (is (= :publish-only (get-in out [:value :retry])))
        (is (some #(= :files/publish-pending (:type %)) (:effects out)))
        (is (= 1 @applies))
        (is (= "new" (slurp (io/file (str root) "a.txt"))))
        (let [retried (ws/retry-publish! ws-runtime "owner-retry-1")]
          (is (= :succeeded (:status retried)) (pr-str retried))
          (is (true? (get-in retried [:value :published?])))
          (is (= 1 @applies)
              "retry-publish must not reapply the file batch")
          (is (= "new" (slurp (io/file (str root) "a.txt"))))
          (is (= :finished
                 (get-in ((:board-snapshot handle)) [:actions :action-1 :phase])))))
      (finally
        ((:stop! handle))
        (cleanup! root))))))
