(ns hc.server.history.index-test
  "Unit and property tests for the rebuildable history full-text index.

  Every test uses a real temporary Datalevin store and the real storage
  facade.  The main-store reader is explicit: a test changes that map to
  simulate an unindexed edit, and search must still drop the stale hit."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.history.index :as index]
            [hc.server.storage :as storage]
            [hc.server.storage.export :as export]
            [hc.server.storage.support :as support]))

(def ^:dynamic *store* nil)

(use-fixtures :each
  (fn [test-fn]
    (support/with-temp-store [store {}]
      (binding [*store* store]
        (test-fn)))))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- message
  [message-id role content & {:keys [created-at attachments deleted? folded?
                                     version-id versions current-version]
                              :or {created-at 1000}}]
  (let [version-id (or version-id (str message-id "-v1"))
        versions (or versions
                     [{:version-id version-id
                       :content content
                       :created-at created-at
                       :attachments (vec (or attachments []))}])]
    {:message-id message-id
     :role role
     :versions versions
     :current-version (or current-version (dec (count versions)))
     :attachments (vec (or attachments []))
     :created-at created-at
     :deleted? (boolean deleted?)
     :folded? (boolean folded?)}))

(defn- session
  [session-id messages & {:keys [revision status temporary? assistant-id title]
                          :or {revision 1
                               status :active
                               temporary? false
                               assistant-id "a1"
                               title "Session"}}]
  {:session-id session-id
   :revision revision
   :assistant-id assistant-id
   :title title
   :status status
   :temporary? temporary?
   :messages (into {} (map (juxt :message-id identity) messages))
   :message-order (mapv :message-id messages)})

(defn- add-version [message id content created-at]
  (let [version {:version-id id
                 :content content
                 :created-at created-at
                 :attachments (vec (or (:attachments message) []))}]
    (-> message
        (update :versions conj version)
        (assoc :current-version (count (:versions message))))))

(defn- reader
  ([sessions] (reader sessions nil))
  ([sessions calls]
   (fn [session-id]
     (when calls (swap! calls conj session-id))
     (if-let [found-session (get sessions session-id)]
       {:status :found :session found-session}
       {:status :missing}))))

(defn- found
  ([query sessions] (found query sessions {}))
  ([query sessions opts]
   (index/search *store* (merge {:query query} opts) (reader sessions))))

(defn- stored-session [session-id]
  (storage/read-document *store*
                         {:partition index/partition
                          :session-id session-id
                          :kind index/session-kind
                          :id session-id}))

(defn- stored-posting [term]
  (storage/read-document *store*
                         {:partition index/partition
                          :session-id nil
                          :kind index/posting-kind
                          :id term}))

(defn- search-docs []
  (get-in (storage/export-state *store* {:partition index/partition})
          [:partitions index/partition :docs]
          []))

(defn- put-meta! [doc]
  (storage/commit-migration!
   *store*
   {:migration-id (str "meta-test-" (random-uuid))
    :partition index/partition
    :put [{:kind index/meta-kind
           :id index/meta-id
           :doc doc}]}))

;; ---------------------------------------------------------------------------
;; Tokenizer

(deftest tokenizer-is-pure-and-bilingual
  (testing "ASCII words and numbers are lower-cased and length-bounded"
    (is (= ["alpha" "beta"] (index/tokenize "Alpha BETA")))
    (is (= ["a1" "b22"] (index/tokenize "A1 b22 3")))
    (is (empty? (index/tokenize "a b 1 !!")))
    (is (empty? (index/tokenize (apply str (repeat 250 "a"))))))
  (testing "Han runs produce unigrams and bigrams"
    (is (= ["上" "上海" "海"] (index/tokenize "上海")))
    (is (= ["上" "上海" "京" "北" "北京" "海" "海北"]
           (index/tokenize "上海北京"))))
  (testing "nil and non-text are empty, never an exception"
    (is (empty? (index/tokenize nil)))
    (is (empty? (index/tokenize 42))))
  (testing "the result is deterministic and sorted"
    (let [one (index/tokenize "Mixed 中文 text 42")
          two (index/tokenize "Mixed 中文 text 42")]
      (is (= one two))
      (is (= one (vec (sort one)))))))

;; ---------------------------------------------------------------------------
;; Index and search

(deftest index-and-search-use-main-store-snippets
  (let [main (session "s1"
                      [(message "m1" :user "Alpha beta 上海计划"
                                :created-at 1000)]
                      :assistant-id "a1")
        _ (index/index-session! *store* main)
        calls (atom [])
        english (index/search *store* {:query "alpha"}
                              (reader {"s1" main} calls))
        han (found "上海" {"s1" main})]
    (testing "the current message is found with an authoritative snippet"
      (is (= 1 (:count english)))
      (is (= "s1" (get-in english [:hits 0 :session-id])))
      (is (= "m1" (get-in english [:hits 0 :message-id])))
      (is (= "m1-v1" (get-in english [:hits 0 :version-id])))
      (is (false? (get-in english [:hits 0 :historical-version?])))
      (is (str/includes? (get-in english [:hits 0 :snippet]) "Alpha"))
      (is (= ["s1"] @calls))
      (is (= 1 (:count han)))
      (is (str/includes? (get-in han [:hits 0 :snippet]) "上海计划")))
    (testing "the control document is ready"
      (is (= :ready (:state (index/index-meta *store*))))
      (is (= index/schema-version (:schema-version (index/index-meta *store*)))))
    (testing "the session document stores terms, not the message body"
      (let [doc (stored-session "s1")]
        (is (= 1 (:session-revision doc)))
        (is (contains? (:messages doc) "m1"))
        (is (= "m1-v1" (get-in doc [:messages "m1" :version-id])))
        (is (nil? (get-in doc [:messages "m1" :content])))
        (is (not (str/includes? (pr-str doc) "Alpha beta 上海计划")))))
    (testing "the posting points at the version and stores no text"
      (let [posting (stored-posting "alpha")]
        (is (= "alpha" (:term posting)))
        (is (contains? (:postings posting) ["s1" "m1"]))
        (is (= "m1-v1"
               (get-in posting [:postings ["s1" "m1"] :version-id])))
        (is (not (str/includes? (pr-str posting) "Alpha beta")))))
    (testing "re-indexing an unchanged session writes nothing"
      (is (false? (:committed? (index/index-session! *store* main)))))))

(deftest editing-a-message-invalidates-old-terms-immediately
  (let [first-message (message "m1" :user "oldneedle alpha"
                               :version-id "m1-v1" :created-at 1000)
        first-session (session "s1" [first-message] :revision 1)
        _ (index/index-session! *store* first-session)
        next-message (add-version first-message "m1-v2"
                                  "newneedle beta" 2000)
        next-session (session "s1" [next-message] :revision 2)
        sessions {"s1" next-session}
        _ (index/index-session! *store* next-session)]
    (is (= 0 (:count (found "oldneedle" sessions))))
    (is (= 0 (:count (found "alpha" sessions))))
    (is (= 1 (:count (found "newneedle" sessions))))
    (is (nil? (stored-posting "oldneedle")))
    (is (nil? (stored-posting "alpha")))
    (let [doc (stored-session "s1")]
      (is (= 2 (:session-revision doc)))
      (is (= "m1-v2" (get-in doc [:messages "m1" :version-id])))
      (is (= #{"newneedle" "beta"}
             (get-in doc [:messages "m1" :terms]))))))

(deftest folded-and-deleted-messages-are-immediately-unsearchable
  (let [original (message "m1" :user "foldterm deleteterm")
        first-session (session "s1" [original] :revision 1)
        _ (index/index-session! *store* first-session)
        folded-message (assoc original :folded? true)
        folded-session (session "s1" [folded-message] :revision 2)
        _ (index/index-session! *store* folded-session)]
    (is (= 0 (:count (found "foldterm" {"s1" folded-session}))))
    (is (= 0 (:count (found "deleteterm" {"s1" folded-session}))))
    (is (nil? (stored-posting "foldterm")))
    (is (nil? (stored-posting "deleteterm")))
    (is (not (contains? (:messages (stored-session "s1")) "m1"))))

  (let [original (message "m2" :assistant "anothersecret")
        first-session (session "s2" [original] :revision 1)
        _ (index/index-session! *store* first-session)
        deleted-message (assoc original :deleted? true)
        deleted-session (session "s2" [deleted-message] :revision 2)
        _ (index/index-session! *store* deleted-session)]
    (is (= 0 (:count (found "anothersecret" {"s2" deleted-session}))))
    (is (nil? (stored-posting "anothersecret")))
    (is (not (contains? (:messages (stored-session "s2")) "m2")))))

(deftest temporary-and-deleted-sessions-never-enter-the-index
  (let [temporary (session "tmp"
                           [(message "m1" :user "tmpsecret")]
                           :temporary? true)
        deleted (session "del"
                         [(message "m2" :user "delsecret")]
                         :status :deleted)
        calls (atom [])]
    (index/index-session! *store* temporary)
    (index/index-session! *store* deleted)
    (is (nil? (stored-session "tmp")))
    (is (nil? (stored-session "del")))
    (is (nil? (stored-posting "tmpsecret")))
    (is (nil? (stored-posting "delsecret")))
    (is (empty? @calls))
    (is (= 0 (:count (index/search *store* {:query "tmpsecret"}
                                   (reader {"tmp" temporary} calls)))))
    (is (empty? @calls)))

  (testing "an indexed active session is removed when it becomes deleted"
    (let [active (session "active"
                          [(message "m1" :user "activesecret")]
                          :revision 1)
          _ (index/index-session! *store* active)
          _ (is (= 1 (:count (found "activesecret" {"active" active}))))
          removed (index/index-session! *store*
                                        (assoc active :status :deleted
                                               :revision 2))]
      (is (false? (:indexed? removed)))
      (is (true? (:skipped? removed)))
      (is (nil? (stored-session "active")))
      (is (nil? (stored-posting "activesecret")))
      (is (= 0 (:count (found "activesecret" {"active" active}))))))

  (testing "a previously indexed session that becomes temporary is evicted"
    (let [active (session "temporary-later"
                          [(message "m1" :user "latersecret")]
                          :revision 1)
          _ (index/index-session! *store* active)
          _ (is (= 1 (:count (found "latersecret"
                                    {"temporary-later" active}))))
          removed (index/index-session! *store*
                                        (assoc active :temporary? true
                                               :revision 2))]
      (is (false? (:indexed? removed)))
      (is (nil? (stored-session "temporary-later")))
      (is (nil? (stored-posting "latersecret")))
      (is (= 0 (:count (found "latersecret"
                              {"temporary-later" active})))))))

(deftest main-store-validation-drops-stale-hits
  (testing "same version identity but a changed body is rejected"
    (let [indexed-message (message "m1" :user "drift secret word"
                                   :version-id "m1-v1")
          indexed-session (session "s1" [indexed-message] :revision 1)
          _ (index/index-session! *store* indexed-session)
          drifted-version (assoc (first (:versions indexed-message))
                                 :content "public only")
          drifted-message (assoc indexed-message
                                 :versions [drifted-version])
          drifted-session (session "s1" [drifted-message] :revision 2)
          calls (atom [])
          result (index/search *store* {:query "secret"}
                              (reader {"s1" drifted-session} calls))]
      (is (= 0 (:count result)))
      (is (= 1 (:stale-dropped result)))
      (is (= ["s1"] @calls))))

  (testing "a changed selected version is stale unless history is requested"
    (let [extra-version {:version-id "m1-v2"
                         :content "same word"
                         :created-at 2000
                         :attachments []}
          current-message {:message-id "m1"
                           :role :user
                           :versions [{:version-id "m1-v1"
                                       :content "same word"
                                       :created-at 1000
                                       :attachments []}
                                      extra-version]
                           :current-version 1
                           :attachments []
                           :created-at 1000
                           :deleted? false
                           :folded? false}
          current-session (session "s2" [current-message] :revision 2)
          indexed-session (session "s2"
                                   [(assoc current-message
                                           :versions [(first (:versions current-message))]
                                           :current-version 0)]
                                   :revision 1)
          _ (index/index-session! *store* indexed-session)
          default (found "same" {"s2" current-session})
          historical (found "same" {"s2" current-session}
                            {:include-versions? true})]
      (is (= 0 (:count default)))
      (is (= 1 (:stale-dropped default)))
      (is (= 1 (:count historical)))
      (is (true? (get-in historical [:hits 0 :historical-version?])))
      (is (= "m1-v1" (get-in historical [:hits 0 :version-id])))
      (is (= "same word" (get-in historical [:hits 0 :snippet]))))))

(deftest snippet-is-read-from-the-current-main-store-body
  (let [indexed-message (message "m1" :user "old shared body"
                                 :version-id "m1-v1")
        indexed-session (session "s1" [indexed-message] :revision 1)
        _ (index/index-session! *store* indexed-session)
        current-message (assoc indexed-message
                               :versions [(assoc (first (:versions indexed-message))
                                                 :content "new shared body")])
        current-session (session "s1" [current-message] :revision 2)
        result (found "shared" {"s1" current-session})]
    (is (= 1 (:count result)))
    (is (= "new shared body" (get-in result [:hits 0 :snippet])))
    (is (not (str/includes? (get-in result [:hits 0 :snippet]) "old")))))

(deftest search-filters-use-the-main-store
  (let [with-attachment (message "m1" :user "shared filter"
                                 :created-at 100
                                 :attachments [{:attachment-id "a1"}])
        without-attachment (message "m2" :assistant "shared filter"
                                    :created-at 500)
        active (session "s1" [with-attachment]
                        :assistant-id "a1" :status :active :revision 1)
        archived (session "s2" [without-attachment]
                          :assistant-id "a2" :status :archived :revision 1)
        sessions {"s1" active "s2" archived}]
    (index/index-session! *store* active)
    (index/index-session! *store* archived)
    (is (= #{"s1" "s2"}
           (set (map :session-id (:hits (found "shared" sessions))))))
    (is (= #{"s1"}
           (set (map :session-id (:hits (found "shared" sessions
                                                {:assistant-id "a1"}))))))
    (is (= #{"s2"}
           (set (map :session-id (:hits (found "shared" sessions
                                                {:status :archived}))))))
    (is (= #{"s1"}
           (set (map :session-id (:hits (found "shared" sessions
                                                {:has-attachments? true}))))))
    (is (= #{"s2"}
           (set (map :session-id (:hits (found "shared" sessions
                                                {:has-attachments? false}))))))
    (is (= #{"s1"}
           (set (map :session-id (:hits (found "shared" sessions
                                                {:from-ms 100 :to-ms 100}))))))
    (let [limited (found "shared" sessions {:limit 1})]
      (is (= 1 (:count limited)))
      (is (= 2 (:total limited)))
      (is (= "m2" (get-in limited [:hits 0 :message-id]))))))

(deftest historical-versions-are-opt-in
  (let [version-one {:version-id "m1-v1"
                     :content "historicterm"
                     :created-at 100
                     :attachments []}
        version-two {:version-id "m1-v2"
                     :content "currentterm"
                     :created-at 200
                     :attachments []}
        historical-message {:message-id "m1"
                            :role :user
                            :versions [version-one version-two]
                            :current-version 1
                            :attachments []
                            :created-at 100
                            :deleted? false
                            :folded? false}
        main (session "s1" [historical-message] :revision 1)
        _ (index/index-session! *store* main
                                {:include-history-versions? true})
        default (found "historicterm" {"s1" main})
        historical (found "historicterm" {"s1" main}
                          {:include-versions? true})
        current (found "currentterm" {"s1" main})]
    (is (= 0 (:count default)))
    (is (= 1 (:count historical)))
    (is (true? (get-in historical [:hits 0 :historical-version?])))
    (is (= "m1-v1" (get-in historical [:hits 0 :version-id])))
    (is (= "historicterm" (get-in historical [:hits 0 :snippet])))
    (is (= 1 (:count current)))
    (is (false? (get-in current [:hits 0 :historical-version?])))
    (is (= "m1-v2" (get-in current [:hits 0 :version-id])))
    (let [doc (stored-session "s1")]
      (is (contains? (:messages doc) "m1"))
      (is (contains? (:messages doc) ["m1" "m1-v1"]))
      (is (true? (get-in doc [:messages ["m1" "m1-v1"]
                              :historical-version?]))))
    (is (contains? (:postings (stored-posting "historicterm"))
                   ["s1" "m1" "m1-v1"]))
    (is (contains? (:postings (stored-posting "currentterm"))
                   ["s1" "m1"]))
    (testing "rebuild can preserve the historical-version policy"
      (is (:ok? (index/rebuild! *store* (fn [] [main])
                                {:include-history-versions? true})))
      (is (= 1 (:count (found "historicterm" {"s1" main}
                              {:include-versions? true})))))))

(deftest remove-session-clears-every-posting
  (let [main (session "s1"
                      [(message "m1" :user "removeterm another")
                       (message "m2" :assistant "removeterm")]
                      :revision 1)
        _ (index/index-session! *store* main)
        _ (is (= 2 (:count (found "removeterm" {"s1" main}))))
        removed (index/remove-session! *store* "s1")]
    (is (true? (:removed? removed)))
    (is (pos? (:postings-removed removed)))
    (is (nil? (stored-session "s1")))
    (is (nil? (stored-posting "removeterm")))
    (is (nil? (stored-posting "another")))
    (is (= 0 (:count (found "removeterm" {"s1" main}))))
    (is (false? (:removed? (index/remove-session! *store* "s1"))))))

(deftest removing-one-session-keeps-another-sessions-postings
  (let [first-session (session "s1"
                               [(message "m1" :user "sharedterm")]
                               :revision 1)
        second-session (session "s2"
                                [(message "m2" :assistant "sharedterm")]
                                :revision 1)
        _ (index/index-session! *store* first-session)
        _ (index/index-session! *store* second-session)
        _ (index/remove-session! *store* "s1")
        posting (stored-posting "sharedterm")
        result (found "sharedterm" {"s1" first-session
                                    "s2" second-session})]
    (is (contains? (:postings posting) ["s2" "m2"]))
    (is (not (contains? (:postings posting) ["s1" "m1"])))
    (is (= 1 (:count result)))
    (is (= "s2" (get-in result [:hits 0 :session-id])))))

(deftest erased-session-is-immediately-unsearchable
  (let [main (session "s1"
                      [(message "m1" :user "eraseterm")]
                      :revision 1)
        _ (index/index-session! *store* main)
        _ (is (= 1 (:count (found "eraseterm" {"s1" main}))))
        erased (storage/erase-session-derived! *store* "s1")]
    (is (:ok? erased))
    (is (nil? (stored-session "s1")))
    (is (= 0 (:count (found "eraseterm" {}))))))

;; ---------------------------------------------------------------------------
;; Rebuild

(deftest rebuild-clears-old-generations-and-is-stable
  (let [old-a (session "a"
                       [(message "m1" :user "oldword")]
                       :revision 1)
        gone-b (session "b"
                        [(message "m2" :user "goneword")]
                        :revision 1)
        stray-c (session "c"
                         [(message "m3" :user "strayword")]
                         :revision 1)
        _ (doseq [main [old-a gone-b stray-c]]
            (index/index-session! *store* main))
        new-a (session "a"
                       [(message "m1" :user "newword"
                                 :version-id "m1-v2")]
                       :revision 2)
        rebuilt (index/rebuild! *store* (fn [] [new-a]))]
    (is (= :ready (:state (index/index-meta *store*))))
    (is (= 2 (:generation rebuilt))
        "the initial incremental index is generation 1; rebuild advances it")
    (is (= 1 (:indexed rebuilt)))
    (is (= 0 (:count (found "oldword" {"a" new-a}))))
    (is (= 1 (:count (found "newword" {"a" new-a}))))
    (is (= 0 (:count (found "goneword" {}))))
    (is (= 0 (:count (found "strayword" {}))))
    (is (nil? (stored-session "b")))
    (is (nil? (stored-session "c")))
    (is (nil? (stored-posting "goneword")))
    (is (nil? (stored-posting "strayword")))

    (testing "a second rebuild over the same input has the same hits"
      (let [second (index/rebuild! *store* (fn [] [new-a]))
            hits (found "newword" {"a" new-a})]
        (is (= 3 (:generation second)))
        (is (= 1 (:count hits)))
        (is (= "m1-v2" (get-in hits [:hits 0 :version-id])))))

    (testing "rebuilding over no sessions leaves only the control document"
      (index/rebuild! *store* (fn [] []))
      (is (= 0 (:count (found "newword" {"a" new-a}))))
      (is (every? #(= index/meta-kind (:kind %)) (search-docs)))
      (is (= :ready (:state (index/index-meta *store*)))))))

;; ---------------------------------------------------------------------------
;; Export boundary

(deftest export-and-restore-omit-the-derived-search-partition
  (let [main (session "s1"
                      [(message "m1" :user "exportsecret")]
                      :revision 1)
        _ (index/index-session! *store* main)
        package (storage/export-state *store* {})
        validated (export/validate package)
        ops (:ops (export/restore-ops validated []
                                      {:tx-version 1
                                       :at "2026-11-09T00:00:00Z"
                                       :migration-id "restore-search-test"}))
        raw-ops (:ops (export/restore-ops package []
                                          {:tx-version 1
                                           :at "2026-11-09T00:00:00Z"
                                           :migration-id "restore-search-raw-test"}))]
    (is (nil? (get-in validated [:partitions index/partition]))
        "validation removes the derived partition")
    (is (not-any? (fn [[op key]]
                    (and (= :put op)
                         (str/includes? key (str "/" index/partition "/"))))
                  ops)
        "restore never writes a search document")
    (is (not-any? (fn [[op key]]
                    (and (= :put op)
                         (str/includes? key (str "/" index/partition "/"))))
                  raw-ops)
        "restore planning removes the derived partition even from a raw package")
    (is (:ok? (storage/restore-state! *store* package
                                      {:quiescent? true
                                       :at "2026-11-09T00:00:01Z"
                                       :migration-id "restore-search-state"})))
    (is (empty? (search-docs))
        "a whole-store restore deletes the derived partition and leaves it empty")
    (is (= 0 (:count (found "exportsecret" {"s1" main})))
        "the old index generation cannot answer after restore")
    (is (:ok? (index/rebuild! *store* (fn [] [main]))))
    (is (= :ready (:state (index/index-meta *store*))) "rebuilt after restore")
    (is (= 1 (:count (found "exportsecret" {"s1" main})))
        "rebuilding after restore makes history searchable again")))

;; ---------------------------------------------------------------------------
;; Health and startup self-healing

(deftest health-missing-empty-index-and-ensure-ready
  (let [health (index/index-health *store*)]
    (is (= :missing (:state health)))
    (is (nil? (:generation health)))
    (is (= 0 (:session-count health)))
    (is (= 0 (:message-count health)))
    (is (nil? (:built-at health)))
    (is (true? (:needs-rebuild? health)))
    (is (= :no-meta (:reason health))))

  (let [rebuilt (index/ensure-ready! *store* {:list-sessions-fn (fn [] [])})]
    (is (true? (:ok? rebuilt)))
    (is (= :rebuilt (:action rebuilt)))
    (is (= 1 (:generation rebuilt)))
    (is (= :ready (get-in rebuilt [:health :state])))
    (is (false? (get-in rebuilt [:health :needs-rebuild?])))
    (is (= 0 (get-in rebuilt [:health :session-count]))))

  (testing "a ready empty index is a no-op and makes no writes"
    (let [before (index/index-meta *store*)
          calls (atom 0)]
      (with-redefs [index/rebuild!
                    (fn [& _]
                      (swap! calls inc)
                      {:generation 99})]
        (let [again (index/ensure-ready!
                     *store*
                     {:list-sessions-fn
                      (fn []
                        (throw (ex-info "must not be called" {})))})]
          (is (true? (:ok? again)))
          (is (= :none (:action again)))
          (is (= 1 (:generation again)))
          (is (= 0 @calls))))
      (is (= before (index/index-meta *store*))
          "ensure-ready! must not rewrite a healthy control document"))))

(deftest health-ready-index-does-not-rebuild
  (let [main (session "s1"
                      [(message "m1" :user "healthyterm")]
                      :revision 1)
        _ (index/index-session! *store* main)
        health (index/index-health *store*)]
    (is (= :ready (:state health)))
    (is (false? (:needs-rebuild? health)))
    (is (= 1 (:generation health)))
    (is (= 1 (:session-count health)))
    (is (= 1 (:message-count health)))
    (is (number? (:built-at health)))
    (is (nil? (:reason health)))
    (let [before (index/index-meta *store*)
          calls (atom 0)]
      (with-redefs [index/rebuild!
                    (fn [& _]
                      (swap! calls inc)
                      {:generation 99})]
        (let [result (index/ensure-ready!
                      *store*
                      {:list-sessions-fn
                       (fn []
                         (throw (ex-info "must not be called" {})))})]
          (is (true? (:ok? result)))
          (is (= :none (:action result)))
          (is (= 1 (:generation result)))
          (is (= 0 @calls))))
      (is (= before (index/index-meta *store*))))))

(deftest health-detects-building-incompatible-and-corrupt-meta
  (let [base {:schema-version index/schema-version
              :tokenizer-version index/tokenizer-version
              :state :ready
              :generation 1
              :updated-at 1000
              :session-count 0
              :message-count 0}]
    (testing "an interrupted rebuild leaves :building"
      (put-meta! (assoc base :state :building))
      (let [health (index/index-health *store*)]
        (is (= :building (:state health)))
        (is (true? (:needs-rebuild? health)))
        (is (= :building-leftover (:reason health)))))

    (testing "the old :rebuilding spelling is also a leftover"
      (put-meta! (assoc base :state :rebuilding))
      (let [health (index/index-health *store*)]
        (is (= :building (:state health)))
        (is (= :building-leftover (:reason health)))))

    (testing "schema version mismatch"
      (put-meta! (assoc base :schema-version 999))
      (let [health (index/index-health *store*)]
        (is (= :incompatible (:state health)))
        (is (true? (:needs-rebuild? health)))
        (is (= :schema-version-mismatch (:reason health)))))

    (testing "tokenizer version mismatch"
      (put-meta! (assoc base :tokenizer-version 999))
      (let [health (index/index-health *store*)]
        (is (= :incompatible (:state health)))
        (is (true? (:needs-rebuild? health)))
        (is (= :tokenizer-version-mismatch (:reason health)))))

    (testing "missing/non-positive generation"
      (doseq [generation [nil 0 -1]]
        (put-meta! (assoc base :generation generation))
        (let [health (index/index-health *store*)]
          (is (= :corrupt (:state health)))
          (is (true? (:needs-rebuild? health)))
          (is (= :generation-missing (:reason health)))
          (is (nil? (:generation health))))))))

(deftest health-detects-meta-count-with-empty-index
  (put-meta! {:schema-version index/schema-version
              :tokenizer-version index/tokenizer-version
              :state :ready
              :generation 3
              :updated-at 1000
              :session-count 5
              :message-count 5})
  (let [health (index/index-health *store*)]
    (is (= :corrupt (:state health)))
    (is (true? (:needs-rebuild? health)))
    (is (= :index-empty (:reason health)))
    (is (= 0 (:session-count health)))
    (is (= 0 (:message-count health)))))

(deftest ensure-ready-list-failure-preserves-the-existing-index
  (let [main (session "s1"
                      [(message "m1" :user "survivorterm")]
                      :revision 1)
        _ (index/index-session! *store* main)
        before (index/index-meta *store*)
        result (index/ensure-ready!
                *store*
                {:force? true
                 :list-sessions-fn
                 (fn []
                   (throw (ex-info "session list unavailable"
                                   {:code :LOWER_LIST_CODE
                                    :path "/list"
                                    :storage/expected? true})))})]
    (is (false? (:ok? result)))
    (is (= :INDEX_REBUILD_LIST_FAILED (get-in result [:error :code])))
    (is (= :LOWER_LIST_CODE (get-in result [:error :details :cause])))
    (is (= "/list" (get-in result [:error :details :cause-details :path])))
    (is (nil? (get-in result [:error :details :cause-details
                              :storage/expected?])))
    (is (= :ready (get-in result [:health :state])))
    (is (= before (index/index-meta *store*))
        "a list failure must not touch the control document")
    (is (= 1 (:count (found "survivorterm" {"s1" main})))
        "the existing index must stay searchable")
    (testing "summary rows without :messages are not a rebuild source"
      (let [summary-result
            (index/ensure-ready!
             *store*
             {:force? true
              :list-sessions-fn (fn [] [{:session-id "s1"}])})]
        (is (false? (:ok? summary-result)))
        (is (= :INDEX_REBUILD_LIST_FAILED
               (get-in summary-result [:error :code])))
        (is (= 1 (:count (found "survivorterm" {"s1" main}))))))))

(deftest rebuild-prefers-a-newer-current-revision-over-a-stale-snapshot
  (let [snapshot (session "s1" [(message "m1" :user "oldword")] :revision 1)
        current (session "s1" [(message "m1" :user "newestword")] :revision 2)]
    (is (true? (:ok? (index/index-session! *store* current)))
        "the incremental write lands before the rebuild rescans")
    (let [result (index/rebuild! *store*
                                 (fn [] [snapshot])
                                 {:current-session-fn (fn [_] current)})]
      (is (true? (:ok? result)))
      (is (= 1 (:indexed result)))
      (is (zero? (:skipped result))))
    (testing "the newest revision is searchable, with no hole left behind"
      (is (= 1 (:count (found "newestword" {"s1" current}))))
      (is (= 2 (:session-revision (stored-session "s1")))))
    (testing "the stale snapshot text never replaces the newer content"
      (is (zero? (:count (found "oldword" {"s1" current})))))))

(deftest rebuild-does-not-undo-a-later-incremental-write
  (let [snapshot (session "s1"
                          [(message "m1" :user "oldrace")]
                          :revision 1)
        updated (session "s1"
                         [(message "m1" :user "newrace"
                                   :version-id "m1-v2")]
                         :revision 2)
        other (session "s2"
                       [(message "m2" :user "otherrace")]
                       :revision 1)
        original index/index-session!
        calls (atom 0)
        before (or (:generation (index/index-meta *store*)) 0)
        result
        (with-redefs [index/index-session!
                      (fn [store session & [opts]]
                        (let [n (swap! calls inc)
                              indexed (original store session (or opts {}))]
                          ;; The rebuild has already processed s1 and is now
                          ;; at s2.  A normal incremental write updates s1.
                          (when (= 1 n)
                            (original store updated {}))
                          indexed))]
          (index/rebuild! *store* (fn [] [snapshot other])))]
    (is (= :ready (:state (index/index-meta *store*))))
    (is (= (inc before) (:generation result)))
    (is (= (:generation result) (:generation (index/index-meta *store*))))
    (is (= 1 (:count (found "newrace" {"s1" updated}))))
    (is (zero? (:count (found "oldrace" {"s1" updated}))))
    (is (= 1 (:count (found "otherrace" {"s2" other}))))
    (is (= 2 (:session-revision (stored-session "s1"))))))

(deftest rebuild-stale-snapshot-cannot-overwrite-a-newer-index
  (let [snapshot (session "s1"
                          [(message "m1" :user "oldguard")]
                          :revision 1)
        updated (session "s1"
                         [(message "m1" :user "newguard"
                                   :version-id "m1-v2")]
                         :revision 2)
        other (session "s2"
                       [(message "m2" :user "otherguard")]
                       :revision 1)
        original index/index-session!
        calls (atom 0)
        result
        (with-redefs [index/index-session!
                      (fn [store session & [opts]]
                        (let [n (swap! calls inc)]
                          ;; The incremental write lands first; the rebuild
                          ;; then asks to index the older snapshot.
                          (when (= 1 n)
                            (original store updated {}))
                          (original store session (or opts {}))))]
          (index/rebuild! *store* (fn [] [snapshot other])))]
    (is (= :ready (:state (index/index-meta *store*))))
    (is (= 1 (:indexed result)))
    (is (= 1 (:skipped result)))
    (is (= 1 (:count (found "newguard" {"s1" updated}))))
    (is (zero? (:count (found "oldguard" {"s1" updated}))))
    (is (= 1 (:count (found "otherguard" {"s2" other}))))
    (is (= 2 (:session-revision (stored-session "s1"))))
    (testing "the stale decision is made under the store lock"
      (let [direct (index/index-session! *store* snapshot)]
        (is (true? (:ok? direct)))
        (is (true? (:skipped? direct)))
        (is (= :stale-snapshot (:reason direct)))
        (is (false? (:committed? direct)))))))

(deftest rebuild-refreshes-the-session-list-after-clear
  (let [first-session (session "s1"
                               [(message "m1" :user "presnapshot")]
                               :revision 1)
        late-session (session "late"
                              [(message "m2" :user "latesession")]
                              :revision 1)
        calls (atom 0)
        result
        (index/rebuild!
         *store*
         (fn []
           (if (= 1 (swap! calls inc))
             [first-session]
             [first-session late-session])))]
    (is (= 2 @calls))
    (is (true? (:list-refreshed? result)))
    (is (= 2 (:sessions result)))
    (is (= 2 (:indexed result)))
    (is (= 1 (:count (found "presnapshot" {"s1" first-session}))))
    (is (= 1 (:count (found "latesession" {"late" late-session}))))
    (is (some? (stored-session "late")))))

(deftest rebuild-failure-leaves-building-and-earlier-postings
  (let [first-session (session "s1"
                               [(message "m1" :user "survivorterm")]
                               :revision 1)
        second-session (session "s2"
                                [(message "m2" :user "afterterm")]
                                :revision 1)
        original index/index-session!
        calls (atom 0)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"injected rebuild failure"
         (with-redefs [index/index-session!
                       (fn [store session & [opts]]
                         (if (= 2 (swap! calls inc))
                           (throw (ex-info "injected rebuild failure" {}))
                           (original store session (or opts {}))))]
           (index/rebuild! *store*
                           (fn [] [first-session second-session])))))
    (is (= :building (:state (index/index-meta *store*))))
    (is (= :building (:state (index/index-health *store*))))
    (testing "the first session's postings were not rolled back"
      (is (= 1 (:count (found "survivorterm" {"s1" first-session})))))
    (testing "a later incremental write still works while building"
      (is (true? (:ok? (index/index-session! *store* second-session))))
      (is (= 1 (:count (found "afterterm" {"s2" second-session})))))))

(deftest concurrent-rebuilds-have-unique-monotonic-generations
  (let [main (session "s1"
                      [(message "m1" :user "generationterm")]
                      :revision 1)]
    (doseq [_round (range 3)]
      (let [before (or (:generation (index/index-meta *store*)) 0)
            start (promise)
            run (fn []
                  (future
                    (try
                      @start
                      (index/rebuild! *store* (fn [] [main]))
                      (catch Throwable throwable
                        {:error throwable}))))
            one (run)
            two (run)]
        (deliver start true)
        (let [results (mapv #(deref % 15000 ::timeout) [one two])]
          (when (some #{::timeout} results)
            (future-cancel one)
            (future-cancel two))
          (is (not-any? #{::timeout} results))
          (is (not-any? :error results))
          (let [generations (sort (keep :generation results))]
            (is (= [(inc before) (+ before 2)] generations))
            (is (= (+ before 2)
                   (:generation (index/index-meta *store*))))
            (is (= :ready (:state (index/index-meta *store*)))))
          (is (= 1 (:count (found "generationterm" {"s1" main})))))))))

(deftest rebuild-stops-before-the-next-session-on-cancellation
  (let [first-session (session "s1"
                               [(message "m1" :user "firstcancel")]
                               :revision 1)
        second-session (session "s2"
                                [(message "m2" :user "secondcancel")]
                                :revision 1)
        original index/index-session!
        calls (atom 0)
        cancelled? (atom false)
        result
        (with-redefs [index/index-session!
                      (fn [store session & [opts]]
                        (let [indexed (original store session (or opts {}))]
                          (when (= 1 (swap! calls inc))
                            (reset! cancelled? true))
                          indexed))]
          (index/rebuild! *store*
                          (fn [] [first-session second-session])
                          {:cancelled? cancelled?}))]
    (is (false? (:ok? result)))
    (is (true? (:cancelled? result)))
    (is (= 1 (:indexed result)))
    (is (= 1 (:generation result)))
    (is (= :building (:state (index/index-meta *store*))))
    (is (not= :ready (:state (index/index-meta *store*))))
    (is (= 1 (:count (found "firstcancel" {"s1" first-session}))))
    (is (zero? (:count (found "secondcancel" {"s2" second-session}))))
    (is (nil? (stored-session "s2")))))

(deftest rebuild-store-closed-cancellation-is-zero-write
  (let [main (session "s1"
                      [(message "m1" :user "closedsurvivor")]
                      :revision 1)
        _ (index/index-session! *store* main)
        before-meta (index/index-meta *store*)
        before-docs (search-docs)
        list-calls (atom 0)
        result
        (index/rebuild!
         *store*
         (fn []
           (swap! list-calls inc)
           [main])
         {:store-open? (fn [] false)})]
    (is (false? (:ok? result)))
    (is (true? (:cancelled? result)))
    (is (= 0 (:indexed result)))
    (is (nil? (:generation result)))
    (is (zero? @list-calls))
    (is (= before-meta (index/index-meta *store*)))
    (is (= before-docs (search-docs)))
    (is (= 1 (:count (found "closedsurvivor" {"s1" main}))))
    (testing "ensure-ready also refuses before listing or rebuilding"
      (let [ensured (index/ensure-ready!
                     *store*
                     {:list-sessions-fn
                      (fn []
                        (swap! list-calls inc)
                        [main])
                      :store-open? (fn [] false)})]
        (is (false? (:ok? ensured)))
        (is (true? (:cancelled? ensured)))
        (is (= :INDEX_REBUILD_CANCELLED
               (get-in ensured [:error :code])))
        (is (zero? @list-calls))
        (is (= before-meta (index/index-meta *store*)))))))

(deftest ensure-ready-rebuild-failure-is-structured-and-retryable
  (put-meta! {:schema-version index/schema-version
              :tokenizer-version index/tokenizer-version
              :state :building
              :generation 1
              :updated-at 1000
              :session-count 0
              :message-count 0})
  (let [failed (with-redefs [index/rebuild!
                             (fn [& _]
                               (throw (ex-info "rebuild unavailable"
                                               {:error :LOWER_REBUILD_CODE
                                                :path "/db"
                                                :storage/expected? true})))]
                 (index/ensure-ready!
                  *store*
                  {:list-sessions-fn (fn [] [])}))]
    (is (false? (:ok? failed)))
    (is (= :INDEX_REBUILD_FAILED (get-in failed [:error :code])))
    (is (= :LOWER_REBUILD_CODE (get-in failed [:error :details :cause])))
    (is (= "/db" (get-in failed [:error :details :cause-details :path])))
    (is (nil? (get-in failed [:error :details :cause-details
                              :storage/expected?])))
    (is (= :building (get-in failed [:health :state]))
        "a failed rebuild stays visibly building for the next retry")
    (is (= :building-leftover (get-in failed [:health :reason]))))

  (let [retry (index/ensure-ready! *store* {:list-sessions-fn (fn [] [])})]
    (is (true? (:ok? retry)))
    (is (= :rebuilt (:action retry)))
    (is (= 2 (:generation retry)))
    (is (= :ready (get-in retry [:health :state])))))

(deftest ensure-ready-force-rebuilds-a-ready-index
  (let [main (session "s1"
                      [(message "m1" :user "forceterm")]
                      :revision 1)
        _ (index/index-session! *store* main)
        before (:generation (index/index-meta *store*))
        result (index/ensure-ready!
                *store*
                {:force? true
                 :list-sessions-fn (fn [] [main])})]
    (is (true? (:ok? result)))
    (is (= :rebuilt (:action result)))
    (is (= (inc before) (:generation result)))
    (is (= :ready (get-in result [:health :state])))
    (is (= 1 (get-in result [:health :session-count])))
    (is (= 1 (get-in result [:health :message-count])))
    (is (= 1 (:count (found "forceterm" {"s1" main}))))))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private property-session-id (atom 0))

(defspec tokenize-is-pure-and-case-insensitive 100
  (prop/for-all [text gen/string-ascii]
    (let [tokens (index/tokenize text)]
      (and (= tokens (index/tokenize text))
           (= tokens (index/tokenize (.toUpperCase ^String text
                                                   java.util.Locale/ROOT)))
           (every? #(and (string? %) (not (str/blank? %))) tokens)
           (every? #(<= (count %) index/max-term-chars) tokens)))))

(defspec edited-message-never-returns-the-old-term 30
  (prop/for-all [old-term (gen/elements ["zzold" "qqold" "oldneedle"])
                 new-term (gen/elements ["zznew" "qqnew" "newneedle"])
                 revision (gen/choose 0 1000)]
    (let [session-id (str "prop-edit-" (swap! property-session-id inc))
          first-message (message "m1" :user old-term
                                 :version-id "m1-v1" :created-at 1)
          first-session (session session-id [first-message]
                                 :revision revision)
          _ (index/index-session! *store* first-session)
          next-message (add-version first-message "m1-v2" new-term 2)
          next-session (session session-id [next-message]
                                :revision (inc revision))
          ;; The edited session must be re-indexed; otherwise v2 never enters
          ;; the index and the new-term assertion fails for the wrong reason.
          _ (index/index-session! *store* next-session)
          sessions {session-id next-session}]
      (and (= 0 (:count (found old-term sessions)))
           (= 1 (:count (found new-term sessions)))))))

(defspec stale-index-version-is-never-a-default-hit 25
  (prop/for-all [revision (gen/choose 1 1000)]
    (let [session-id (str "prop-stale-" (swap! property-session-id inc))
          version-one {:version-id "m1-v1"
                       :content "driftterm"
                       :created-at 1
                       :attachments []}
          version-two {:version-id "m1-v2"
                       :content "driftterm"
                       :created-at 2
                       :attachments []}
          indexed-message (message "m1" :user "driftterm"
                                   :version-id "m1-v1")
          indexed-session (session session-id [indexed-message]
                                   :revision revision)
          _ (index/index-session! *store* indexed-session)
          current-message (assoc indexed-message
                                 :versions [version-one version-two]
                                 :current-version 1)
          current-session (session session-id [current-message]
                                   :revision (inc revision))
          sessions {session-id current-session}
          default (found "driftterm" sessions)
          historical (found "driftterm" sessions
                            {:include-versions? true})]
      (and (= 0 (:count default))
           (= 1 (:count historical))
           (true? (get-in historical [:hits 0 :historical-version?]))))))
