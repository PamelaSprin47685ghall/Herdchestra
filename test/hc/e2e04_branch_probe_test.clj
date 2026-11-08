(ns hc.e2e04-branch-probe-test
  "Temporary probe for the E2E-04 branch projection failure."
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
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]
            [hc.server.sessions.ports :as session-ports]))

(def ^:private session-id "probe-session")

(defn- test-config [data static]
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

(defn- seed-session! [db-path]
  (let [store (storage/open-store {:path db-path :create? true})
        persistence (session-persistence/persistence store)
        created (sessions/new-session
                 {:session-id session-id
                  :owner-id "probe-owner"
                  :now 1000
                  :id-source (ids/counter-source {:prefix "probe"})})
        session (:session created)
        m1 (:message (message/new-message "m1" :user "first question"
                                          {:now 1100}))
        m2 (:message (message/new-message "m2" :assistant "first answer"
                                          {:now 1200}))
        m3 (:message (message/new-message "m3" :user "second question"
                                          {:now 1300}))
        m4 (:message (message/new-message "m4" :assistant "second answer"
                                          {:now 1400}))
        m5 (:message (message/new-message "m5" :user "third question"
                                          {:now 1500}))
        m6 (:message (message/new-message "m6" :assistant "third answer"
                                          {:now 1600}))
        session (-> session
                    (assoc-in [:messages "m1"] m1)
                    (assoc-in [:messages "m2"] m2)
                    (assoc-in [:messages "m3"] m3)
                    (assoc-in [:messages "m4"] m4)
                    (assoc-in [:messages "m5"] m5)
                    (assoc-in [:messages "m6"] m6)
                    (assoc :message-order ["m1" "m2" "m3" "m4" "m5" "m6"])
                    (assoc :model-binding {:connection-id "probe-conn"
                                           :model-entry-id "probe-model"})
                    (assoc :activity-at 1600))
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

(deftest branch-baseline-probe
  (let [data (support/temp-dir "hc-e2e04-probe-")
        static (support/temp-dir "hc-e2e04-probe-static-")]
    (spit (str static "/index.html") "<!doctype html><title>hc</title>")
    (try
      (seed-session! (str data "/db"))
      (let [system (system/start! (test-config data static))]
        (try
          (is (false? (:blocked? system)))
          ;; Branch from the first user message: before-message-id = m2
          ;; copies only m1 (the clicked message stays in the branch).
          (let [branch (command! system "probe-c1"
                                 {:kind :history/branch
                                  :session-id session-id
                                  :before-message-id "m2"})]
            (is (:ok? branch) (pr-str branch))
            (let [branch-id (get-in branch [:receipt :session-id])]
              (is (string? branch-id))
              ;; Raw committed record from the running system's persistence.
              (let [persistence (:persistence system)
                    raw (session-ports/read-session persistence branch-id)]
                (println "RAW-STATUS" (:status raw))
                (println "RAW-ORDER" (pr-str (:message-order (:session raw))))
                (println "RAW-MSG-KEYS" (pr-str (sort (keys (:messages (:session raw))))))
                (println "RAW-REV" (:revision (:session raw)))
                nil)
              ;; Live owner snapshot session.
              (let [owner (get @(:owners system) branch-id)
                    snap-owner (when owner ((:snapshot owner)))
                    live-session (get-in snap-owner [:state :session])]
                (println "OWNER-SESSION?" (some? live-session))
                (println "OWNER-ORDER" (pr-str (:message-order live-session)))
                (println "OWNER-MSG-KEYS" (pr-str (sort (keys (:messages live-session)))))
                (println "OWNER-REV" (:revision live-session)))
              ;; Force a publish and inspect the baseline tree.
              ((:publish-now! (:publisher system)))
              (let [snap (:snapshot (gateway/snapshot (:gateway system)))
                    summary (get-in snap [:session/by-id branch-id])
                    history (get-in snap [:session/history branch-id])
                    record (get-in snap [:message/by-id])]
                (println "BRANCH-ID" branch-id)
                (println "SUMMARY" (pr-str (select-keys summary [:message-ids :branch-of :title])))
                (println "HISTORY-IDS" (pr-str (:message-ids history)))
                (println "BY-ID-KEYS" (pr-str (sort (keys record))))
                (println "ORDER" (pr-str (:message/order snap)))
                ;; Also inspect the committed branch record directly.
                (let [listed (registry/list-sessions
                              (:registry system) {:include-deleted? false})
                      branch-record (some #(when (= branch-id (:session-id %)) %)
                                           (:sessions listed))]
                  (println "RECORD-ORDER" (pr-str (:message-order branch-record)))
                  (println "RECORD-MSG-KEYS" (pr-str (sort (keys (:messages branch-record)))))
                  (println "RECORD-COUNT" (count (:message-order branch-record)))))))
          (finally (system/stop! system))))
      (finally (support/delete-tree! data)
               (support/delete-tree! static)))))
