(ns hc.server.history-live-smoke-test
  "Live application path for history controls.

  Seeds a real store, starts the integrated application, submits history
  commands through the gateway router to the per-session Owner, publishes a
  real baseline, then restarts and verifies the live session survived."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.app.system :as system]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.registry :as registry]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(def ^:private session-id "live-session")
(def ^:private a1 {:attachment-id "live-a1" :name "live.png" :size 12})

(defn- test-config
  [data static]
  (config/normalize
   {:host "127.0.0.1"
    :port 0
    :data-dir data
    :workspace-root (str data "/workspace")
    :static-root static
    :blob-root (str data "/blobs")
    :worktree-root (str data "/worktrees")
    :artifact-root (str data "/artifacts")
    :storage {:path (str data "/db")}
    :log-level :warn
    :models {}}))

(defn- seed-session!
  [db-path]
  (let [store (storage/open-store {:path db-path :create? true})
        persistence (session-persistence/persistence store)
        created (sessions/new-session
                 {:session-id session-id
                  :owner-id "live-owner"
                  :now 1000
                  :id-source (ids/counter-source {:prefix "live"})})
        session (:session created)
        m1 (:message (message/new-message "m1" :user "original question"
                                          {:attachments [a1] :now 1100}))
        m2 (:message (message/new-message "m2" :assistant "original answer"
                                          {:now 1200}))
        m3 (:message (message/new-message "m3" :user "second question"
                                          {:now 1300}))
        m4 (:message (message/new-message "m4" :assistant "second answer"
                                          {:now 1400}))
        session (-> session
                    (assoc-in [:messages "m1"] m1)
                    (assoc-in [:messages "m2"] m2)
                    (assoc-in [:messages "m3"] m3)
                    (assoc-in [:messages "m4"] m4)
                    (assoc :message-order ["m1" "m2" "m3" "m4"])
                    (assoc :model-binding {:connection-id "live-conn"
                                           :model-entry-id "live-model"})
                    (assoc :activity-at 1400))
        commit (sessions/commit-session! persistence session)]
    (is (= :committed (:status commit)))
    (storage/close-store! store)))

(defn- command!
  [system command-id payload]
  (let [owner (get @(:owners system) session-id)
        epoch (get-in ((:snapshot owner)) [:state :commands :command-epoch])]
    (gateway-ports/submit-command!
     (:router system)
     {:command-id command-id
      :command-epoch epoch
      :payload (assoc payload :session-id session-id)}
     nil)))

(deftest live-history-edit-compress-branch-survives-restart
  (let [data (support/temp-dir "hc-history-live-")
        static (support/temp-dir "hc-history-live-static-")]
    (spit (str static "/index.html") "<!doctype html><title>hc</title>")
    (try
      (seed-session! (str data "/db"))
      (let [system (system/start! (test-config data static))]
        (try
          (is (false? (:blocked? system)))
          (let [edited (command! system "live-c1"
                                 {:kind :history/edit-message
                                  :message-id "m1"
                                  :content "edited question"
                                  :attachments []})
                prepared (command! system "live-c2"
                                   {:kind :history/prepare-compression
                                    :compression-id "live-cmp"
                                    :message-ids ["m1" "m2"]
                                    :summary "reviewed live summary"})
                applied (command! system "live-c3"
                                  {:kind :history/apply-compression
                                   :compression-id "live-cmp"})
                branch (command! system "live-c4"
                                 {:kind :history/branch
                                  :session-id session-id
                                  :before-message-id "m3"})
                regenerate (command! system "live-c5"
                                     {:kind :history/regenerate
                                      :message-id "m4"})]
            (is (:ok? edited) (pr-str edited))
            (is (:ok? prepared) (pr-str prepared))
            (is (:ok? applied) (pr-str applied))
            (is (:ok? branch) (pr-str branch))
            (is (string? (get-in branch [:receipt :session-id])))
            (is (:ok? regenerate) (pr-str regenerate))
            (is (= "m4" (get-in regenerate [:receipt :message-id])))
            (is (= :append-version
                   (get-in regenerate [:receipt :mode])))
            (testing "the live Owner session has the new version and compression"
              (let [owner (get @(:owners system) session-id)
                    live (get-in ((:snapshot owner)) [:state :session])]
                (is (= 2 (count (get-in live [:messages "m1" :versions]))))
                (is (= "edited question"
                       (get-in live [:messages "m1" :versions 1 :content])))
                (is (= [] (get-in live [:messages "m1" :versions 1 :attachments])))
                (is (= [a1] (get-in live [:messages "m1" :versions 0 :attachments])))
                (is (= :applied (get-in live [:context :compressions
                                              "live-cmp" :state])))
                (let [run-id (get-in regenerate [:receipt :run-id])]
                  (is (= "m4" (get-in live [:runs run-id :input-snapshot
                                            :target-message-id])))
                  (is (= :append-version (get-in live [:runs run-id
                                                       :input-snapshot
                                                       :target-mode]))))))
            (testing "the published baseline carries versions and context"
              ((:publish-now! (:publisher system)))
              (let [snap (:snapshot (gateway/snapshot (:gateway system)))
                    entity (get-in snap [:message/by-id "m1"])
                    summary (get-in snap [:session/by-id session-id])]
                (is (= "edited question" (:content entity)))
                (is (= 2 (count (:versions entity))))
                (is (= [] (:attachments (get-in entity [:versions 1]))))
                (is (= [a1] (:attachments (get-in entity [:versions 0]))))
                (is (= ["m1" "m2" "m3" "m4"] (:message-ids summary)))
                (is (= ["m3" "m4"] (get-in summary [:context :outbound :message-ids])))
                (is (= :applied (get-in summary
                                        [:context :compressions 0 :state])))
                (is (= ["live-cmp"]
                       (get-in summary [:context :outbound :summary-ids])))))
            (testing "the branch is a real committed session"
              (let [listed (registry/list-sessions
                            (:registry system) {:include-deleted? false})]
                (is (= 2 (count (:sessions listed)))))))
          (finally (system/stop! system))))
      (testing "restart reloads the live Owner session history"
        (let [system (system/start! (test-config data static))]
          (try
            (is (false? (:blocked? system)))
            ;; Startup creates Owners lazily: only the presentation active
            ;; session gets one eagerly.  The live session here is not the
            ;; first listed after the branch exists, so ensure its Owner
            ;; through the same recovery seam a command would use.
            (let [loaded (registry/get-session (:registry system) session-id)
                  ensured (registry/ensure-owner-for-session!
                           (:registry system) (:session loaded))
                  owner (or (:owner ensured)
                            (get @(:owners system) session-id))
                  live (get-in ((:snapshot owner)) [:state :session])]
              (is (:ok? ensured))
              (is (some? live))
              (is (= 2 (count (get-in live [:messages "m1" :versions]))))
              (is (= "edited question"
                     (get-in live [:messages "m1" :versions 1 :content])))
              (is (= :applied (get-in live [:context :compressions
                                            "live-cmp" :state]))))
            (finally (system/stop! system)))))
      (finally (support/delete-tree! data)
               (support/delete-tree! static)))))
