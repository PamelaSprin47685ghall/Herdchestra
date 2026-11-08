(ns hc.server.app.control-port-test
  "Tests for the application-owned program control primitives."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.git :as fleet-git]
            [hc.compose.fleet.runtime :as fleet-runtime]
            [hc.compose.fleet.support :as fleet-support]
            [hc.server.app.commands :as commands]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.app.system :as system]
            [hc.server.sessions.registry :as registry]
            [hc.server.storage.support :as support]
            [hc.core.scope.annotation :as annotation]
            [hc.core.scope.ledger :as ledger]
            [hc.core.swarm-stm :as swarm])
  (:import (java.io File)))

(defn- fake-owner
  [submitted]
  {:snapshot (fn [] {:state {:commands {:command-epoch 7}}})
   :submit-command! (fn [request]
                      (swap! submitted conj request)
                      {:status :accepted :outcome :ok})})

(deftest review-control-submits-trusted-owner-command-test
  (let [submitted (atom [])
        owner-ref (atom (fake-owner submitted))
        fallback ((var-get #'system/control-fallback) owner-ref (atom nil))
        result (fallback {:role :plan
                          :call-id "call-1"
                          :tenure-id "tenure-1"
                          :lane :plan}
                         {:primitive :review
                          :request {:findings [{:kind :gap}]}})]
    (is (= {:status :accepted :outcome :ok} result))
    (let [request (first @submitted)]
      (is (= "review-call-1" (:command-id request)))
      (is (= 7 (:command-epoch request)))
      (is (= {:kind :review/submit
              :lane :plan
              :tenure-id "tenure-1"
              :findings [{:kind :gap}]
              :draft-disk-content nil
              :call-id "call-1"}
             (:payload request)))
      (is (not (contains? request :operator?)))
      (is (not (contains? (:payload request) :operator?))))))

(deftest review-control-without-owner-is-explicit-test
  (let [fallback ((var-get #'system/control-fallback) (atom nil) (atom nil))]
    (is (= :OWNER_NOT_READY
           (get-in (fallback {:call-id "c1"}
                             {:primitive :review :request {:findings []}})
                   [:error :code])))))

(deftest devops-control-receives-the-program-tenure-and-call-id-test
  (let [captured (atom nil)
        exec {:run (fn [effect]
                     (reset! captured effect)
                     {:status :ok :report "done"})}
        fallback ((var-get #'system/control-fallback) (atom nil) (atom exec))
        result (fallback {:role :plan
                          :call-id "call-1"
                          :tenure-id "tenure-1"
                          :lane :plan}
                         {:primitive :devops
                          :request {:verbose-intent "inspect"}})]
    (is (= {:status :ok :report "done"} result))
    (is (= "call-1" (:call-id @captured)))
    (is (= "tenure-1" (get-in @captured [:request :tenure-id]))
        "the Plan worker belongs to the program's pinned tenure")
    (is (= "call-1" (get-in @captured [:request :call-id])))
    (is (= "inspect" (get-in @captured [:request :verbose-intent])))))

(deftest unsupported-control-primitive-is-explicit-test
  (let [fallback ((var-get #'system/control-fallback) (atom nil) (atom nil))]
    (is (= :UNSUPPORTED_OPERATION
           (get-in (fallback {} {:primitive :abandon :request {}})
                   [:error :code])))))

(defn- negotiate-owner
  "A fake Owner whose snapshot carries a settled Scope book."
  [submitted]
  (let [book (:book (ledger/settle-turn
                     (ledger/new-book) "turn-1"
                     (annotation/parse-annotation
                      {:kind :reset-scope :new-scope-goal "g"
                       :new-scope-retention-budget 4})
                     {:call-id "r1"}))]
    {:snapshot (fn []
                 {:state {:session-id "s-neg"
                          :scope {:book book :records [] :asks {}}}})
     :submit-command! (fn [request]
                        (swap! submitted conj request)
                        {:status :accepted :outcome :ok})}))

(deftest negotiate-returns-the-scope-book-snapshot-test
  (let [submitted (atom [])
        owner-ref (atom (negotiate-owner submitted))
        fallback ((var-get #'system/control-fallback) owner-ref (atom nil))
        result (fallback {:role :plan :call-id "call-1"}
                         {:primitive :negotiate :request {}})]
    (is (:ok? result))
    (is (= :negotiate (:kind result)))
    (is (= "s-neg" (:session-id result)))
    (is (= "g" (:goal result)))
    (is (= 4 (:budget result)))
    (is (true? (:established? result)))
    (is (false? (:must-reset result)))
    (is (= 1 (:resets result)))
    (is (empty? @submitted)
        "negotiate is a pure read; it never submits an Owner command")))

(deftest negotiate-without-owner-is-explicit-test
  (let [fallback ((var-get #'system/control-fallback) (atom nil) (atom nil))]
    (is (= :OWNER_NOT_READY
           (get-in (fallback {} {:primitive :negotiate :request {}})
                   [:error :code])))))

(deftest ask-previous-scope-submits-an-owner-receipt-command-test
  (let [submitted (atom [])
        owner-ref (atom (negotiate-owner submitted))
        fallback ((var-get #'system/control-fallback) owner-ref (atom nil))
        result (fallback {:role :plan
                          :call-id "call-9"
                          :turn-id "turn-9"
                          :tenure-id "tenure-9"
                          :lane :plan
                          :agent-id "worker-1"}
                         {:primitive :askPreviousScope
                          :request {:questions "what did we decide?"}})]
    (is (= {:status :accepted :outcome :ok} result))
    (let [request (first @submitted)]
      (is (= "scope-ask-call-9" (:command-id request)))
      (is (= {:kind :scope/ask-previous
              :call-id "call-9"
              :questions "what did we decide?"
              :member "worker-1"
              :turn-id "turn-9"
              :tenure-id "tenure-9"
              :lane :plan
              :agent-id "worker-1"}
             (:payload request))))))

(deftest ask-previous-scope-with-blank-questions-is-refused-test
  (let [submitted (atom [])
        owner-ref (atom (negotiate-owner submitted))
        fallback ((var-get #'system/control-fallback) owner-ref (atom nil))
        result (fallback {:call-id "call-1"}
                         {:primitive :askPreviousScope
                          :request {:questions "   "}})]
    (is (= :ASK_QUESTIONS_REQUIRED
           (get-in result [:error :code])))
    (is (empty? @submitted)
        "a blank question never reaches the Owner")))

(deftest holder-attempt-maps-to-clj-worker-test
  (is (= :clj-worker
         ((var-get #'system/program-role-for-attempt) {:role :holder})))
  (is (= :worker
         ((var-get #'system/program-role-for-attempt) {:role :worker}))))

(deftest fleet-cancel-forwards-the-real-handle-and-is-idempotent-test
  (let [cancelled (atom [])
        gateway {:cancel! (fn [handle]
                            (swap! cancelled conj handle)
                            nil)}
        attempts (atom {["swarm-1" "a-1"] {:handle :model-gateway-pending}})
        system-ref (atom {})
        cancel! (var-get #'system/cancel-fleet-attempt!)]
    (is (= {:ok? true :cancelled? true}
           (cancel! gateway attempts system-ref "swarm-1" "a-1")))
    (is (= [{:handle :model-gateway-pending}] @cancelled))
    (is (= {} @attempts)
        "the terminal/cancel index drops the handle after cancellation")
    (is (= {:ok? true :cancelled? false :reason :already-terminal}
           (cancel! gateway attempts system-ref "swarm-1" "a-1")))
    (is (= 1 (count @cancelled))
        "a missing handle is an idempotent no-op")))

(deftest fleet-cancel-failure-is-recorded-but-never-a-terminal-test
  (let [gateway {:cancel! (fn [_] (throw (ex-info "cancel refused" {})))}
        attempts (atom {["swarm-1" "a-1"] {:handle :h}})
        system-ref (atom {})
        result ((var-get #'system/cancel-fleet-attempt!)
                gateway attempts system-ref "swarm-1" "a-1")]
    (is (= {:ok? true :cancelled? true} result))
    (is (= {} @attempts))
    (is (= "a-1" (get-in @system-ref
                         [:fleet-cancel-errors 0 :attempt-id])))
    (is (string? (get-in @system-ref
                         [:fleet-cancel-errors 0 :error :message])))))

(deftest baseline-validates-active-session-against-visible-sessions-test
  (let [sessions [{:session-id "s1" :title "one"}
                  {:session-id "s2" :title "two"}]]
    (with-redefs [registry/list-sessions
                  (fn [_ _] {:status :ok :sessions sessions})
                  registry/get-session
                  (fn [_ session-id]
                    {:status :found :session {:session-id session-id}})
                  commands/app-command-epoch (fn [_] 1)]
      (testing "a hidden active id falls back to the first visible session"
        (with-redefs [commands/active-session-id (fn [_] "deleted")]
          (let [result ((var-get #'system/baseline)
                        {} (atom {}) {} {} {:phase :synced})]
            (is (= "s1" (:active-session result)))
            (is (= "s1" (get-in result [:snapshot :session/active-id]))))))
      (testing "a visible active id is preserved"
        (with-redefs [commands/active-session-id (fn [_] "s2")]
          (let [result ((var-get #'system/baseline)
                        {} (atom {}) {} {} {:phase :synced})]
            (is (= "s2" (:active-session result)))
            (is (= "s2" (get-in result [:snapshot :session/active-id])))))))))

(deftest baseline-merges-live-router-owners-test
  (let [sessions [{:session-id "s1" :title "one"}]
        app-snapshot {:state {:session {:session-id "s1" :title "app"
                                        :revision 1}}}
        router-snapshot {:state {:session {:session-id "s1" :title "router"
                                           :revision 2}}}
        captured (atom nil)
        app-owners (atom {"s1" {:snapshot (fn [] app-snapshot)}})]
    (with-redefs [registry/list-sessions
                  (fn [_ _] {:status :ok :sessions sessions})
                  registry/get-session
                  (fn [_ session-id]
                    {:status :found :session {:session-id session-id}})
                  commands/active-session-id (fn [_] "s1")
                  commands/app-command-epoch (fn [_] 1)
                  snapshot/baseline
                  (fn [opts] (reset! captured opts) {})]
      (testing "a router without a state atom contributes no owners"
        (doseq [router [{} {:state {}} {:state nil} {:state (atom nil)}]]
          (reset! captured nil)
          (let [result ((var-get #'system/baseline)
                        {} app-owners router {} {:phase :synced})]
            (is (= "s1" (:active-session result)))
            (is (= {"s1" app-snapshot} (:owner-snapshots @captured))
                (str "router " (pr-str router))))))
      (testing "an equal-revision router snapshot does not override the app owner"
        (reset! captured nil)
        (let [equal-router (assoc-in router-snapshot
                                     [:state :session :revision] 1)
              router {:state (atom {:owners
                                    {"s1" {:snapshot (fn [] equal-router)}}})}
              result ((var-get #'system/baseline)
                      {} app-owners router {} {:phase :synced})]
          (is (= "s1" (:active-session result)))
          (is (= {"s1" app-snapshot} (:owner-snapshots @captured)))))
      (testing "a newer live router owner overrides the stale app snapshot"
        (reset! captured nil)
        (let [router {:state (atom {:owners
                                    {"s1" {:snapshot (fn [] router-snapshot)}}})}
              result ((var-get #'system/baseline)
                      {} app-owners router {} {:phase :synced})]
          (is (= "s1" (:active-session result)))
          (is (= {"s1" router-snapshot} (:owner-snapshots @captured))))))))

(deftest sync-plan-drafts-only-for-plan-success-test
  (let [root (support/temp-dir "hc-plan-draft-")]
    (try
      (let [file (File. ^String root "plans/test.md")]
        (.mkdirs (.getParentFile file))
        (spit file "draft body")
        (let [submitted (atom [])
              system-ref (atom {})
              result {:status :succeeded
                      :effects [{:type :files/applied
                                 :changed-paths ["plans/test.md"]}]}]
          (testing "a successful :plan result submits the applied content"
            ((var-get #'system/sync-plan-drafts!)
             (fake-owner submitted) :plan root "tenure-1" result system-ref)
            (is (= 1 (count @submitted)))
            (let [request (first @submitted)]
              (is (= {:kind :plan/write-draft
                      :path "plans/test.md"
                      :content "draft body"
                      :tenure-id "tenure-1"}
                     (:payload request)))
              (is (= 7 (:command-epoch request)))))
          (testing "a non-plan role does not submit"
            (reset! submitted [])
            ((var-get #'system/sync-plan-drafts!)
             (fake-owner submitted) :execution root "tenure-1" result
             system-ref)
            (is (empty? @submitted)))
          (testing "a failed result does not submit"
            (reset! submitted [])
            ((var-get #'system/sync-plan-drafts!)
             (fake-owner submitted) :plan root "tenure-1"
             (assoc result :status :failed) system-ref)
            (is (empty? @submitted)))))
      (finally
        (support/delete-tree! root)))))

(deftest worktree-holder-call-runs-in-its-own-worktree-test
  (fleet-support/with-temp-dir [root "hc-worktree-shell-"]
    (let [repo (str root "/repo")]
      (.mkdirs (io/file repo))
      (fleet-support/init-repo! repo)
      (let [adapter (fleet-git/git-cli repo)
            workspace (str root "/swarm")
            created (fleet-git/create-worktree!
                     adapter
                     {:workspace workspace
                      :branch "hc/mission/worktree-shell-smoke"
                      :base "master"})]
        (is (= :ok (:status created)))
        (let [factory ((var-get #'system/worktree-evaluate-factory)
                       {:artifact-root (str root "/artifacts")})
              started (fleet-runtime/start!
                       {:swarm-id "swarm-smoke"
                        :slice-id "slice-smoke"
                        :workspace workspace
                        :branch "hc/mission/worktree-shell-smoke"
                        :slice {:slice-id "slice-smoke"
                                :goal "run a holder shell"
                                :done-when "done"}
                        :evaluate-factory factory})]
          (is (:ok? started) (pr-str started))
          (let [rt (:runtime started)
                kernel (:kernel rt)
                control (:control-agent-id rt)
                holder-result (swarm/execute!
                               kernel
                               {:op :register-actor
                                :agent-id "holder-smoke"
                                :slot-id :holder-smoke})
                holder (:agent-id holder-result)
                hold (swarm/execute!
                      kernel
                      {:op :create-task
                       :actor-id control
                       :ledger-key "hold-worktree-shell"
                       :goal "Hold the token"
                       :remaining-work "Wait"
                       :done-when "Done"
                       :needs-token true})
                taken (swarm/execute!
                       kernel
                       {:op :take-or-die
                        :actor-id holder
                        :confirm :i-hereby-confirm-i-die-without-regret})
                task-id (:task-id hold)
                claimed (swarm/execute!
                         kernel
                         {:op :claim
                          :actor-id holder
                          :task-id task-id
                          :atomicity-reason "worktree shell smoke"})
                claim-id (:claim-id claimed)]
            (try
              (is (some? holder) (pr-str taken))
              (is (some? task-id) (pr-str hold))
              (is (some? claim-id) (pr-str claimed))
              (let [request-base {:agent-id holder
                                  :claim-id claim-id
                                  :task-id task-id
                                  :role :clj-worker
                                  :program-role :clj-worker
                                  :holder? true
                                  :s1? false
                                  :phase :executing}
                    call-result
                    ((:evaluator rt)
                     (assoc request-base
                            :attempt-id "worktree-shell-call-attempt"
                            :call-id "worktree-shell-call"
                            :intent "run pwd in the worktree"
                            :source (str "(defprogram worktree-shell-call []\n"
                                         "  (call {:command \"pwd\"}))")))
                    actions-after-call
                    (vals (:actions (fleet-runtime/board rt)))
                    shell-action
                    (some #(when (= :shell (:kind %)) %)
                          actions-after-call)]
                (is (= :COMPLETED (:status call-result))
                    (pr-str {:call-result call-result
                             :actions actions-after-call}))
                (is (true? (get-in call-result [:value :published?]))
                    (pr-str call-result))
                (is (= :finished (:phase shell-action))
                    (pr-str shell-action))
                (let [write-result
                      ((:evaluator rt)
                       (assoc request-base
                              :attempt-id "worktree-shell-write-attempt"
                              :call-id "worktree-shell-write"
                              :intent "write a worktree file"
                              :source (str "(defprogram worktree-shell-write []\n"
                                           "  (write {:path \"smoke.txt\"\n"
                                           "          :content \"worktree\"}))")))]
                  (is (= :COMPLETED (:status write-result))
                      (pr-str {:write-result write-result
                               :actions (vals (:actions
                                               (fleet-runtime/board rt)))}))
                  (is (.exists (io/file workspace "smoke.txt")))
                  (is (not (.exists (io/file repo "smoke.txt"))))))
              (finally
                (fleet-runtime/stop! rt)))))))))

(defn- draft-owner
  [submitted draft-content disk-content]
  {:snapshot (fn []
               {:state {:commands {:command-epoch 3}
                        :mission {:relay
                                  {:lanes {:plan {:milestone :pending}}
                                   :draft {:path "plans/x.md"
                                           :content draft-content}}}}})
   :submit-command! (fn [request]
                      (swap! submitted conj request)
                      {:status :accepted :outcome :ok})
   })

(deftest review-early-check-refuses-on-disk-divergence-test
  (let [submitted (atom [])
        owner (draft-owner submitted "relay draft" nil)
        fallback ((var-get #'system/control-fallback)
                  (atom owner) (atom nil)
                  {:draft-root "/tmp"
                   :draft-reader (fn [_root _path] "user edited draft")})
        result (fallback {:role :clj-plan
                          :call-id "call-1"
                          :tenure-id "tenure-1"
                          :lane :plan}
                         {:primitive :review
                          :request {:findings []}})]
    (is (= :DRAFT_USER_EDITED
           (get-in result [:error :code])))
    (is (empty? @submitted)
        "a divergent draft must not submit :review/submit")
    (is (= "plans/x.md"
           (get-in result [:error :details :path])))))

(deftest review-early-check-passes-when-aligned-or-unreadable-test
  (let [submitted (atom [])
        owner (draft-owner submitted "same bytes" nil)
        fallback ((var-get #'system/control-fallback)
                  (atom owner) (atom nil)
                  {:draft-root "/tmp"
                   :draft-reader (fn [_root _path] "same bytes")})
        result (fallback {:role :clj-plan
                          :call-id "call-2"
                          :tenure-id "tenure-1"
                          :lane :plan}
                         {:primitive :review
                          :request {:findings []}})]
    (is (= {:status :accepted :outcome :ok} result))
    (is (= 1 (count @submitted)))
    (is (= "same bytes"
           (get-in (first @submitted) [:payload :draft-disk-content]))
        "aligned disk bytes ride the command for the Owner-side reconcile")
    (testing "an unreadable file fails open"
      (reset! submitted [])
      (let [fallback* ((var-get #'system/control-fallback)
                       (atom owner) (atom nil)
                       {:draft-root "/tmp"
                        :draft-reader (fn [_root _path] nil)})
            result* (fallback* {:role :clj-plan
                                :call-id "call-3"
                                :tenure-id "tenure-1"
                                :lane :plan}
                               {:primitive :review
                                :request {:findings []}})]
        (is (= {:status :accepted :outcome :ok} result*))
        (is (= 1 (count @submitted)))
        (is (contains? (first @submitted) :payload))
        (is (nil? (get-in (first @submitted)
                          [:payload :draft-disk-content])))))))

(deftest review-early-check-only-guards-plan-handoff-test
  (let [submitted (atom [])
        owner (draft-owner submitted "relay draft" nil)
        fallback ((var-get #'system/control-fallback)
                  (atom owner) (atom nil)
                  {:draft-root "/tmp"
                   :draft-reader (fn [_root _path] "different")})]
    (testing "non-empty findings are not the handoff and pass through"
      (let [result (fallback {:role :clj-plan
                              :call-id "call-4"
                              :tenure-id "tenure-1"
                              :lane :plan}
                             {:primitive :review
                              :request {:findings [{:key "k" :value "v"}]}})]
        (is (= {:status :accepted :outcome :ok} result))
        (is (= 1 (count @submitted)))))
    (testing "an execution-lane review is not guarded"
      (reset! submitted [])
      (let [result (fallback {:role :clj-review
                              :call-id "call-5"
                              :tenure-id "tenure-2"
                              :lane :execution}
                             {:primitive :review
                              :request {:findings []}})]
        (is (= {:status :accepted :outcome :ok} result))
        (is (= 1 (count @submitted)))))))
