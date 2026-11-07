(ns hc.server.app.session-command-index-test
  "The creation-command index must commit and delete with the session record
  in the same storage partition, survive a store reopen, include the command
  epoch in its identity, and refuse a second session for the same identity."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as storage-support]))

(defn- creation-identity
  [epoch]
  {:scope "app" :command-epoch epoch :command-id "cmd-1"})

(defn- new-session
  [session-id owner-id command-identity now]
  (:session (sessions/new-session
             {:session-id session-id
              :owner-id owner-id
              :creation-command command-identity
              :now now})))

(deftest creation-command-index-is-epoch-scoped-and-unique
  (let [dir (storage-support/temp-dir "hc-session-command-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (let [p1 (session-persistence/persistence store)
                p2 (session-persistence/persistence store)
                epoch-1 (creation-identity 1)
                epoch-2 (creation-identity 2)
                reuse-identity {:scope "app"
                                :command-epoch 3
                                :command-id "cmd-reuse"}
                s1 (new-session "s-1" "o-1" epoch-1 1000)
                committed (sessions/commit-session! p1 s1)]
            (is (= :committed (:status committed)))
            (is (= epoch-1 (:creation-command s1)))
            (testing "the same command-id in another epoch is not the same command"
              (is (= :found (:status (ports/lookup-session-by-command
                                      p1 epoch-1))))
              (is (= :missing (:status (ports/lookup-session-by-command
                                        p1 epoch-2)))))
            (testing "a reuse claim persists identity -> existing session"
              (is (= :claimed
                     (:status (ports/claim-session-command!
                               p1 reuse-identity "s-1"))))
              (is (= :unchanged
                     (:status (ports/claim-session-command!
                               p2 reuse-identity "s-1"))))
              (is (= "s-1"
                     (:session-id
                      (:session (ports/lookup-session-by-command
                                 p1 reuse-identity))))))
            (testing "a second session for the same identity is refused at commit"
              (let [s-b (new-session "s-b" "o-b" epoch-1 2000)
                    duplicate (sessions/commit-session! p2 s-b)]
                (is (= :duplicate (:status duplicate)))
                (is (= "s-1" (:session-id (:session duplicate))))
                (is (= :missing (:status (ports/read-session p2 "s-b"))))
                (is (= "s-1" (:session-id
                              (:session (ports/lookup-session-by-command
                                         p2 epoch-1)))))))
            (testing "a new epoch may reuse the command-id for a new session"
              (let [s2 (new-session "s-2" "o-2" epoch-2 3000)
                    committed-2 (sessions/commit-session! p2 s2)]
                (is (= :committed (:status committed-2)))
                (is (= "s-1" (:session-id
                              (:session (ports/lookup-session-by-command
                                         p2 epoch-1)))))
                (is (= "s-2" (:session-id
                              (:session (ports/lookup-session-by-command
                                         p2 epoch-2)))))
                (is (= :duplicate
                       (:status (ports/claim-session-command!
                                 p2 reuse-identity "s-2"))))
                (is (= "s-1"
                       (:session-id
                        (:session (ports/lookup-session-by-command
                                   p2 reuse-identity))))))))
          (finally
            (storage/close-store! store))))
      (let [store (storage/open-store {:path dir})]
        (try
          (let [persistence (session-persistence/persistence store)
                found-1 (ports/lookup-session-by-command
                         persistence (creation-identity 1))
                found-2 (ports/lookup-session-by-command
                         persistence (creation-identity 2))
                found-reuse (ports/lookup-session-by-command
                             persistence {:scope "app"
                                          :command-epoch 3
                                          :command-id "cmd-reuse"})]
            (is (= :found (:status found-1)))
            (is (= "s-1" (:session-id (:session found-1))))
            (is (= :found (:status found-2)))
            (is (= "s-2" (:session-id (:session found-2))))
            (is (= :found (:status found-reuse)))
            (is (= "s-1" (:session-id (:session found-reuse))))
            (let [stored (ports/read-session persistence "s-1")
                  revision (:revision (:session stored))
                  deleted (ports/delete-session! persistence revision "s-1")]
              (is (= :deleted (:status deleted)))
              (is (= :missing
                     (:status (ports/lookup-session-by-command
                               persistence (creation-identity 1)))))
              (is (= :missing
                     (:status (ports/lookup-session-by-command
                               persistence {:scope "app"
                                            :command-epoch 3
                                            :command-id "cmd-reuse"}))))
              (is (= :found
                     (:status (ports/lookup-session-by-command
                               persistence (creation-identity 2)))))))
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))
