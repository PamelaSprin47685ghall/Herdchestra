(ns hc.server.history.snapshot-test
  "Read-path projections: message windows, inactive context, stale summaries,
  and the storage view's partition-scope scan."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.history :as history]
            [hc.server.history.context :as context]
            [hc.server.history.message :as message]
            [hc.server.history.snapshot :as history-snapshot]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.view :as view]))

(defn- base-session
  [session-id]
  (:session (sessions/new-session
             {:session-id session-id
              :owner-id (str "owner-" session-id)
              :now 1000
              :id-source (ids/counter-source {:prefix session-id})})))

(defn- add-message
  [session message-id role text]
  (let [result (message/new-message message-id role text {:now 1000})]
    (is (:ok? result))
    (-> session
        (assoc-in [:messages message-id] (:message result))
        (update :message-order (fnil conj []) message-id))))

(defn- command
  [session kind payload now]
  (let [result (history/apply-command session (assoc payload :kind kind)
                                      {:now now})]
    (is (:ok? result) (pr-str (:error result)))
    (:session result)))

(defn- applied-session
  []
  (-> (base-session "s1")
      (add-message "m1" :user "one")
      (add-message "m2" :assistant "one answer")
      (add-message "m3" :user "two")
      (command :history/prepare-compression
               {:compression-id "c1"
                :message-ids ["m1" "m2"]
                :summary "summary"} 2000)
      (command :history/apply-compression
               {:compression-id "c1"} 3000)))

(defn- many-message-session
  [message-count]
  (reduce (fn [session index]
            (let [message-id (str "m" index)
                  result (message/new-message message-id :user
                                              (str "text " index)
                                              {:now 1000})]
              (-> session
                  (assoc-in [:messages message-id] (:message result))
                  (update :message-order (fnil conj []) message-id))))
          (base-session "s1")
          (range message-count)))

(deftest session-history-builds-entities-only-for-the-message-window
  (let [session (many-message-session 2000)
        window-ids #{"m0" "m10" "m500" "m1000" "m1999"}
        calls (atom 0)
        original history-snapshot/message-entity]
    (with-redefs [history-snapshot/message-entity
                  (fn [session message]
                    (swap! calls inc)
                    (original session message))]
      (let [history (history-snapshot/session-history
                     session
                     {:context? false
                      :messages-window {:ids window-ids}})]
        (is (= (count window-ids) @calls)
            "only window entities are constructed, not all 2000")
        (is (= 2000 (count (:message-ids history)))
            "the full visible id list stays available")
        (is (= 2000 (count (:ordered-ids history))))
        (is (= (set (mapv :id (:messages history))) window-ids))
        (is (nil? (get-in history [:context :outbound]))
            "an inactive context skips the outbound projection")))))

(deftest inactive-session-context-skips-the-outbound-projection
  (let [session (-> (base-session "s1")
                    (add-message "m1" :user "one")
                    (add-message "m2" :assistant "one answer")
                    (add-message "m3" :user "deleted"))
        session (assoc-in session [:messages "m3" :deleted?] true)
        calls (atom 0)
        original context/outbound-context]
    (with-redefs [context/outbound-context
                  (fn [session opts]
                    (swap! calls inc)
                    (original session opts))]
      (let [inactive (with-redefs [message/visible-messages
                                   (fn [_]
                                     (throw (ex-info "inactive context must not materialize message views" {})))
                                   message/deleted-messages
                                   (fn [_]
                                     (throw (ex-info "inactive context must not materialize deleted views" {})))]
                       (history-snapshot/session-history
                        session {:context? false}))
            inactive-calls @calls
            active (history-snapshot/session-history session)]
        (is (zero? inactive-calls)
            "the inactive projection never calls outbound")
        (is (nil? (get-in inactive [:context :outbound])))
        (is (= {:message-count 2 :deleted-count 1}
               (get-in inactive [:context :reading])))
        (is (= 1 @calls))
        (is (= ["m1" "m2"] (get-in active [:context :outbound :message-ids])))))))

(deftest baseline-passes-the-context-option-through
  (let [s1 (-> (base-session "s1") (add-message "s1-m1" :user "one"))
        s2 (-> (base-session "s2") (add-message "s2-m1" :user "two"))
        calls (atom [])
        original context/outbound-context]
    (with-redefs [context/outbound-context
                  (fn [session opts]
                    (swap! calls conj (:session-id session))
                    (original session opts))]
      (let [baseline (history-snapshot/baseline [s1 s2] "s2")]
        (is (= #{"s1" "s2"} (set @calls))
            "without :context? every session keeps the compatible full view")
        (is (map? (get-in baseline [:session/history "s1"
                                    :context :outbound]))))
      (reset! calls [])
      (let [inactive (history-snapshot/baseline
                      [s1 s2] "s2" {:context? false})]
        (is (empty? @calls)
            "an explicit :context? false skips the projection everywhere")
        (is (nil? (get-in inactive [:session/history "s1"
                                    :context :outbound])))
        (is (nil? (get-in inactive [:session/history "s2"
                                    :context :outbound])))))))

(defn- reading-reference
  "The previous independent projection of the two reading counts."
  [session]
  {:message-count (count (message/visible-messages session))
   :deleted-count (count (message/deleted-messages session))})

(defn- reading-count-sessions
  []
  (let [visible (-> (base-session "s-count")
                    (add-message "m1" :user "one")
                    (add-message "m2" :assistant "answer")
                    (add-message "m3" :user "two"))
        regenerated (-> (base-session "s-count-regen")
                        (add-message "r1" :user "question")
                        (add-message "r2" :assistant "old answer")
                        (update-in [:messages "r2" :versions]
                                   conj
                                   {:version-id "r2-v2"
                                    :content "regenerated answer"
                                    :attachments []
                                    :citations []
                                    :quotes []
                                    :tool-records []
                                    :created-at 2000
                                    :edited? true
                                    :source :regenerated})
                        (assoc-in [:messages "r2" :current-version] 1))]
    [(base-session "s-count-empty")
     visible
     (assoc-in visible [:messages "m2" :deleted?] true)
     (assoc-in visible [:messages "m3" :folded?] true)
     (-> visible
         (assoc-in [:messages "m2" :nudge?] true)
         (assoc-in [:messages "m2" :nudge-id] "n1"))
     (-> visible
         (assoc-in [:messages "m1" :deleted?] true)
         (assoc-in [:messages "m1" :folded?] true))
     regenerated]))

(deftest context-view-reading-counts-match-the-old-projection
  (doseq [session (reading-count-sessions)]
    (let [expected (reading-reference session)
          outbound-calls (atom 0)
          visible-calls (atom 0)
          deleted-calls (atom 0)
          original-outbound context/outbound-context
          original-visible message/visible-messages
          original-deleted message/deleted-messages
          actual (with-redefs [context/outbound-context
                               (fn [session opts]
                                 (swap! outbound-calls inc)
                                 (original-outbound session opts))
                               message/visible-messages
                               (fn [session]
                                 (swap! visible-calls inc)
                                 (original-visible session))
                               message/deleted-messages
                               (fn [session]
                                 (swap! deleted-calls inc)
                                 (original-deleted session))]
                   (get-in (history-snapshot/session-history session)
                           [:context :reading]))]
      (is (= expected actual)
          (str "reading counts changed for " (:session-id session)
               ": expected " expected ", actual " actual))
      (is (<= @outbound-calls 1)
          "the full context runs outbound-context at most once")
      (is (zero? @visible-calls)
          "the full context no longer runs visible-messages to count")
      (is (zero? @deleted-calls)
          "the full context no longer runs deleted-messages to count"))))

(deftest stale-compression-stays-visible-but-is-not-sent
  (let [session (-> (applied-session)
                    (command :history/edit-message
                             {:message-id "m1" :content "one changed"} 4000))
        history (history-snapshot/session-history session)
        compression (first (get-in history [:context :compressions]))
        panel (get-in history [:context :outbound])
        outbound (context/outbound-context session)
        model (context/model-messages outbound)]
    (testing "the compression record remains in the baseline"
      (is (= :applied (:state compression)))
      (is (true? (:stale? compression)))
      (is (= "summary" (:summary compression))
          "the reviewed text stays readable even though it is not sent"))
    (testing "the reading projection still carries every message"
      (is (= ["m1" "m2" "m3"] (:message-ids history)))
      (is (= ["m1" "m2" "m3"] (mapv :id (:messages history)))
          "the source entities are present, not a hole"))
    (testing "the panel projection carries ids and no message bodies"
      (is (= [] (:summary-ids panel)))
      (is (= ["m1" "m2" "m3"] (:message-ids panel)))
      (is (not (contains? panel :messages))
          "context-view deliberately leaves bodies in :message/by-id")
      (is (not-any? #(= :replaced-by-context-summary (:reason %))
                    (:excluded panel))))
    (testing "the real outbound projection restores the sources"
      (is (= [] (:summary-ids outbound)))
      (is (= ["m1" "m2" "m3"] (:message-ids outbound)))
      (is (= ["m1" "m2" "m3"] (mapv :message-id (:messages outbound))))
      (is (= "one changed" (get-in outbound [:messages 0 :content])))
      (is (not-any? #(= :context-summary (:kind %))
                    (:messages outbound)))
      (is (not-any? #(= :replaced-by-context-summary (:reason %))
                    (:excluded outbound))))
    (testing "not an empty hole: sources and model messages are present"
      (is (seq model))
      (is (= ["m1" "m2" "m3"] (mapv :message-id model)))
      (is (some #(= "one changed" (:text %)) model))
      (is (not-any? #(= "summary" (:text %)) model)))
    (testing "the annotated session follows the same outbound path"
      (let [annotated-out (context/outbound-context
                           (context/annotate-compressions session))]
        (is (= ["m1" "m2" "m3"] (mapv :message-id (:messages annotated-out))))
        (is (= [] (:summary-ids annotated-out)))
        (is (not-any? #(= :context-summary (:kind %))
                      (:messages annotated-out)))
        (is (not-any? #(= "summary" (:text %))
                      (context/model-messages annotated-out)))))))

(deftest session-view-scopes-known-kinds-and-partition-scope
  (let [scans (atom [])
        fake-scan (fn [_ prefix] (swap! scans conj prefix) [])
        expected-partition (into #{}
                                 (map #(keys/kind-doc-prefix "p" nil %))
                                 view/known-document-kinds)
        expected-session (into #{}
                               (map #(keys/kind-doc-prefix "p" "s" %))
                               view/known-document-kinds)]
    (with-redefs [engine/scan fake-scan
                  engine/get-value (fn [_ _] nil)]
      (view/session-view ::engine {:partition "p"
                                   :session-id "s"
                                   :include-partition-scope? false})
      (is (seq @scans))
      (is (seq expected-partition))
      (is (seq expected-session))
      (is (not-any? #(or (str/starts-with? % (keys/session-doc-prefix "p" nil))
                         (str/starts-with? % (keys/session-obligation-prefix "p" nil)))
                    @scans)
          "no partition-scope document or obligation prefix is scanned")
      (is (every? #(contains? (set @scans) %)
                  expected-session)
          "the session scope still scans every known kind")
      (testing "the default view scans every known kind in both scopes"
        (reset! scans [])
        (view/session-view ::engine {:partition "p" :session-id "s"})
        (is (every? #(contains? (set @scans) %)
                    expected-session))
        (is (every? #(contains? (set @scans) %)
                    expected-partition))
        (doseq [kind [:owner-state :task :materials-root]]
          (is (contains? (set @scans)
                         (keys/kind-doc-prefix "p" nil kind))
              (str "partition kind scanned: " kind)))
        (is (not (contains? (set @scans)
                            (keys/session-doc-prefix "p" nil)))
            "the default view does not fall back to the raw partition prefix"))
      (testing "an unknown-kind request falls back to the raw prefixes"
        (reset! scans [])
        (view/session-view ::engine {:partition "p" :session-id "s"
                                     :include-unknown-kinds? true})
        (is (contains? (set @scans) (keys/session-doc-prefix "p" "s")))
        (is (contains? (set @scans) (keys/session-doc-prefix "p" nil)))))))
