(ns hc.server.app.session-command-index-test
  "The creation-command index must commit and delete with the session record
  in the same storage partition, survive a store reopen, include the command
  epoch in its identity, and refuse a second session for the same identity."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as storage-support]
            [hc.server.storage.view :as view]))

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

(def ^:private shared-blob-id
  "Canonical content ID; the blob store rejects test shortcuts such as
  `sha256-shared`, so the reference-count assertions must use the real form."
  "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- sample-session
  [session-id owner-id title]
  (let [attachment {:attachment-id "att-1"
                    :blob-id shared-blob-id
                    :size 3}
        message {:message-id "m-1"
                 :session-id session-id
                 :role :user
                 :current-version "m-1-v1"
                 :versions {"m-1-v1" {:content "hello"
                                       :attachments [attachment]}}
                 :attachments [attachment]}]
    {:session-id session-id
     :owner-id owner-id
     :revision 0
     :status :active
     :language :en
     :title title
     :created-at 1
     :updated-at 1
     :messages {"m-1" message}
     :message-order ["m-1"]
     :drafts {"client" {:text "draft" :attachments [] :citations []}}
     :visible-message-count 1}))

(defn- ref-ids
  [store]
  (->> (storage/manage-blob-refs! store {:partition "sessions" :op :list})
       :refs
       (map :id)
       set))

(deftest summary-and-blob-counts-are-derived-point-documents-test
  (let [dir (storage-support/temp-dir "hc-session-summary-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (let [p (session-persistence/persistence store)
                s1 (sample-session "s-1" "o-1" "one")
                s2 (sample-session "s-2" "o-2" "two")]
            (is (= :committed (:status (ports/commit-session! p 0 s1))))
            (testing "the summary document carries no message bodies"
              (let [summary-doc (storage/read-document
                                 store
                                 {:partition "sessions"
                                  :session-id nil
                                  :kind :session-summary
                                  :id "s-1"})
                    summary (-> (ports/read-session-summary p "s-1")
                                :session)]
                (is (some? summary-doc))
                (is (not (contains? summary-doc :messages)))
                (is (not (contains? summary-doc :message-order)))
                (is (= :found (:status (ports/read-session-summary p "s-1"))))
                (is (not (contains? summary :messages)))
                (is (= 1 (:visible-message-count summary)))
                (is (true? (:has-draft? summary)))
                (is (= "hello"
                       (get-in summary [:last-visible-message :text])))))
            (testing "the reference count index follows one session"
              (is (= {shared-blob-id 1}
                     (:counts (storage/read-document
                               store
                               {:partition "sessions"
                                :session-id nil
                                :kind :session-ref-index
                                :id "sessions"})))))
            (testing "a second owner raises the count without a second reference"
              (is (= :committed (:status (ports/commit-session! p 0 s2))))
              (is (= {shared-blob-id 2}
                     (:counts (storage/read-document
                               store
                               {:partition "sessions"
                                :session-id nil
                                :kind :session-ref-index
                                :id "sessions"})))))
            (testing "deleting one owner keeps the shared reference"
              (let [stored (-> (ports/read-session p "s-1") :session)]
                (is (= :deleted
                       (:status (ports/delete-session! p
                                                       (:revision stored)
                                                       "s-1")))))
              (is (= #{shared-blob-id} (ref-ids store)))
              (is (nil? (storage/read-document
                         store
                         {:partition "sessions"
                          :session-id nil
                          :kind :session-summary
                          :id "s-1"})))
              (is (= ["s-2"] (:ids (ports/list-session-ids p)))))
            (testing "deleting the last owner releases the reference"
              (let [stored (-> (ports/read-session p "s-2") :session)]
                (is (= :deleted
                       (:status (ports/delete-session! p
                                                       (:revision stored)
                                                       "s-2")))))
              (is (empty? (ref-ids store)))
              (is (= {:counts {}}
                     (select-keys (storage/read-document
                                   store
                                   {:partition "sessions"
                                    :session-id nil
                                    :kind :session-ref-index
                                    :id "sessions"})
                                  [:counts])))))
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))

(deftest summary-reads-fall-back-to-one-record-point-read-test
  (let [dir (storage-support/temp-dir "hc-session-summary-fallback-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (storage/commit-migration!
           store
           {:migration-id "legacy-session"
            :partition "sessions"
            :put [{:kind :session-record
                   :id "legacy-1"
                   :doc {:session-id "legacy-1"
                         :revision 0
                         :status :active
                         :messages {"m-1" {:message-id "m-1"
                                           :deleted? false}}
                         :message-order ["m-1"]}}]})
          (let [p (session-persistence/persistence store)
                result (ports/read-session-summary p "legacy-1")]
            (is (= :found (:status result)))
            (is (= 1 (:visible-message-count (:session result))))
            (is (not (contains? (:session result) :messages)))
            (is (not (contains? (:session result) :message-order)))
            (is (= ["legacy-1"] (:ids (ports/list-session-ids p)))))
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))

(deftest persistence-notifies-advisory-history-hooks-test
  (storage-support/with-temp-store [store {}]
    (let [seen (atom [])
          index (reify ports/SessionHistoryIndex
                  (session-committed! [_ session]
                    (swap! seen conj [:protocol-commit (:session-id session)]))
                  (session-deleted! [_ session-id]
                    (swap! seen conj [:protocol-delete session-id])))
          p (session-persistence/persistence
             store
             {:on-session-committed
              (fn [session]
                (swap! seen conj [:commit (:session-id session)]))
              :on-session-deleted
              (fn [session-id]
                (swap! seen conj [:delete session-id]))
              :history-index index})
          s (sample-session "s-hook" "o-hook" "hook")]
      (is (= :committed (:status (ports/commit-session! p 0 s))))
      (is (= :deleted (:status (ports/delete-session! p 0 "s-hook"))))
      (is (= [[:commit "s-hook"] [:delete "s-hook"]] @seen))
      (testing "the protocol object is used when a callback is absent"
        (reset! seen [])
        (let [protocol-only
              (session-persistence/persistence store
                                               {:history-index index})
              again (sample-session "s-hook-2" "o-hook-2" "hook two")]
          (is (= :committed (:status (ports/commit-session! protocol-only
                                                           0 again))))
          (is (= :deleted
                 (:status (ports/delete-session! protocol-only
                                                 0 "s-hook-2"))))
          (is (= [[:protocol-commit "s-hook-2"]
                  [:protocol-delete "s-hook-2"]]
                 @seen)))))))

(deftest steady-state-session-paths-never-scan-the-partition-view-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)
          s1 (sample-session "s-point-1" "o-point-1" "one")
          s2 (sample-session "s-point-2" "o-point-2" "two")]
      (is (= :committed (:status (ports/commit-session! p 0 s1))))
      (let [views (atom [])]
        (with-redefs [storage/read-session-view
                      (fn [& args]
                        (swap! views conj args)
                        (throw (ex-info "steady state must use point reads"
                                        {:args args})))]
          (is (= :committed (:status (ports/commit-session! p 0 s2))))
          (is (= :found (:status (ports/read-session-summary p "s-point-2"))))
          (is (= :ok (:status (ports/list-session-ids p))))
          (is (= :deleted
                 (:status (ports/delete-session! p 0 "s-point-1")))))
        (is (empty? @views)
            "no commit, summary, list or delete may read the full partition view")))))

(deftest app-config-round-trip-uses-point-reads-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)
          views (atom [])]
      (with-redefs [view/session-view
                    (fn [& args]
                      (swap! views conj args)
                      (throw (ex-info "app config must use point reads"
                                      {:args args})))]
        (is (= :missing (:status (ports/read-config p))))
        (is (= :committed
               (:status (ports/commit-config!
                         p 0 {:connections {} :assistants {}}))))
        (let [read (ports/read-config p)]
          (is (= :found (:status read)))
          (is (= 1 (get-in read [:config :revision])))
          (is (= {} (get-in read [:config :connections]))))
        (is (= :conflict
               (:status (ports/commit-config!
                         p 0 {:connections {} :assistants {}}))))
        (is (= :committed
               (:status (ports/commit-config!
                         p 1 {:connections {} :assistants {}}))))
        (is (= 2 (:revision (:config (ports/read-config p))))))
      (is (empty? @views)
          "config reads and writes must not build a partition view")
      (is (= 2
             (:revision
              (storage/read-document
               store
               {:partition "config"
                :session-id nil
                :kind :app-config
                :id "config"})))))))
