(ns hc.server.app.search-commands-test
  "Command-face tests for `hc.server.app.search-commands`.

  These use a real temporary Datalevin store and real `index/index-session!`
  data.  The read-session-fn is an explicit map so a stale index cannot be
  rescued by a fake hit."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.server.app.search-commands :as command]
            [hc.server.history.index :as index]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(def ^:dynamic *store* nil)

(use-fixtures :each
  (fn [test-fn]
    (support/with-temp-store [store {}]
      (binding [*store* store]
        (test-fn)))))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- message
  [message-id role content & {:keys [created-at attachments version-id
                                     versions current-version]
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
     :deleted? false
     :folded? false}))

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

(defn- reader [sessions]
  (fn [session-id]
    (if-let [found (get sessions session-id)]
      {:status :found :session found}
      {:status :missing})))

(defn- run-search
  ([sessions payload]
   (run-search sessions payload {}))
  ([sessions payload opts]
   ((command/search-handler
     (merge {:store *store*
             :read-session-fn (reader sessions)}
            opts))
    payload)))

(defn- run-status [payload]
  ((command/search-status-handler {:store *store*}) payload))

(defn- run-reindex
  [sessions payload opts]
  ((command/reindex-handler
    (merge {:store *store*
            :list-sessions-fn (fn [] sessions)}
           opts))
   payload))

(defn- hit-ids [result]
  (set (map :message-id (get-in result [:payload :hits]))))

(deftest handlers-expose-the-declared-command-kinds
  (is (= #{:history/search :history/search-status :history/reindex}
         (command/command-kinds)))
  (let [handlers (command/handlers {:store *store*
                                    :read-session-fn (reader {})
                                    :list-sessions-fn (fn [] [])})]
    (is (= (command/command-kinds) (set (keys handlers))))
    (is (every? ifn? (vals handlers)))))

;; ---------------------------------------------------------------------------
;; Search

(deftest search-command-returns-hits-count-and-truncation
  (let [main (session "s1"
                      [(message "m1" :user "sharedterm alpha" :created-at 100)
                       (message "m2" :assistant "sharedterm beta" :created-at 200)
                       (message "m3" :user "sharedterm gamma" :created-at 300)])
        _ (index/index-session! *store* main)
        sessions {"s1" main}
        all (run-search sessions {:query "sharedterm"})
        one (run-search sessions {:query "sharedterm" :limit 1})]
    (testing "all hits and the index generation"
      (is (= :ok (:status all)))
      (is (= :search (get-in all [:payload :mode])))
      (is (= "sharedterm" (get-in all [:payload :query])))
      (is (= 3 (get-in all [:payload :count])))
      (is (= 3 (count (get-in all [:payload :hits]))))
      (is (false? (get-in all [:payload :truncated?])))
      (is (= #{"m1" "m2" "m3"} (hit-ids all)))
      (is (= 1 (get-in all [:payload :index-generation]))))
    (testing "a lower limit truncates the hit list"
      (is (= 1 (get-in one [:payload :count])))
      (is (true? (get-in one [:payload :truncated?])))
      (is (= "m3" (get-in one [:payload :hits 0 :message-id]))))
    (testing "the handler option is the default when the payload has no limit"
      (let [by-option (run-search sessions {:query "sharedterm"} {:limit 2})]
        (is (= 2 (get-in by-option [:payload :count])))
        (is (true? (get-in by-option [:payload :truncated?])))))))

(deftest search-command-clamps-limit-to-the-index-maximum
  (let [main (session "s1"
                      [(message "m1" :user "clampterm")
                       (message "m2" :assistant "clampterm")
                       (message "m3" :user "clampterm")])
        _ (index/index-session! *store* main)
        sessions {"s1" main}]
    (with-redefs [index/max-limit 2]
      (let [result (run-search sessions {:query "clampterm" :limit 50})]
        (is (= :ok (:status result)))
        (is (= 2 (get-in result [:payload :count])))
        (is (true? (get-in result [:payload :truncated?])))))))

(deftest search-command-rejects-blank-query-and-invalid-limit
  (let [handler (command/search-handler
                 {:store *store*
                  :read-session-fn (reader {})})]
    (testing "blank queries carry a code"
      (doseq [query [nil "" "   "]]
        (let [result (handler {:query query})]
          (is (= :error (:status result)))
          (is (= :SEARCH_QUERY_INVALID (get-in result [:error :code]))))))
    (testing "zero, negative and non-integer limits are refused"
      (doseq [limit [0 -1 -100 1.5 "3"]]
        (let [result (handler {:query "valid" :limit limit})]
          (is (= :error (:status result)))
          (is (= :SEARCH_LIMIT_INVALID (get-in result [:error :code]))))))))

(deftest edited-message-is-invisible-through-the-command-face
  (let [first-message (message "m1" :user "oldcmdword"
                               :version-id "m1-v1" :created-at 1000)
        first-session (session "s1" [first-message] :revision 1)
        _ (index/index-session! *store* first-session)
        next-message (add-version first-message "m1-v2" "newcmdword" 2000)
        next-session (session "s1" [next-message] :revision 2)
        _ (index/index-session! *store* next-session)
        sessions {"s1" next-session}]
    (is (= 0 (get-in (run-search sessions {:query "oldcmdword"})
                     [:payload :count])))
    (is (= 1 (get-in (run-search sessions {:query "newcmdword"})
                     [:payload :count])))))

(deftest search-command-filters-by-session-id
  (let [first-session (session "s1"
                               [(message "m1" :user "sessionscope")])
        second-session (session "s2"
                                [(message "m2" :assistant "sessionscope")])
        _ (index/index-session! *store* first-session)
        _ (index/index-session! *store* second-session)
        sessions {"s1" first-session "s2" second-session}
        result (run-search sessions {:query "sessionscope"
                                     :session-id "s1"})]
    (is (= 1 (get-in result [:payload :count])))
    (is (= "s1" (get-in result [:payload :hits 0 :session-id])))
    (is (false? (get-in result [:payload :truncated?])))))

(deftest unknown-payload-keys-are-not-forwarded
  (let [handler (command/search-handler
                 {:store *store*
                  :read-session-fn (reader {})})
        captured (atom nil)]
    (with-redefs [index/search
                  (fn [_store index-request _read-session-fn]
                    (reset! captured index-request)
                    {:hits [] :count 0 :total 0})]
      (let [result (handler {:query "safe"
                             :assistant-id "a1"
                             :kind :history/search
                             :session-id "s1"
                             :inject "ignore-me"})]
        (is (= :ok (:status result)))
        (is (= #{:query :assistant-id :limit}
               (set (keys @captured)))
            "only the allow-listed query fields are forwarded")
        (is (= index/max-limit (:limit @captured)))))))

;; ---------------------------------------------------------------------------
;; Status

(deftest search-status-is-missing-on-an-empty-store
  (let [result (run-status {:unknown "ignored"})]
    (is (= :ok (:status result)))
    (is (= :missing (get-in result [:payload :state])))
    (is (= 0 (get-in result [:payload :generation])))
    (is (= 0 (get-in result [:payload :session-count])))
    (is (= 0 (get-in result [:payload :message-count])))
    (is (nil? (get-in result [:payload :built-at])))
    (is (nil? (get-in result [:payload :last-error])))))

(deftest search-status-reports-ready-and-counts
  (let [main (session "s1"
                      [(message "m1" :user "status-one")
                       (message "m2" :assistant "status-two")])
        _ (index/index-session! *store* main)
        result (run-status {})]
    (is (= :ok (:status result)))
    (is (= :ready (get-in result [:payload :state])))
    (is (= 1 (get-in result [:payload :generation])))
    (is (= 1 (get-in result [:payload :session-count])))
    (is (= 2 (get-in result [:payload :message-count])))
    (is (number? (get-in result [:payload :built-at])))
    (is (nil? (get-in result [:payload :last-error])))))

(deftest search-status-maps-rebuilding-to-building
  (storage/commit-migration!
   *store*
   {:migration-id "search-status-building"
    :partition index/partition
    :put [{:kind index/meta-kind
           :id index/meta-id
           :doc {:schema-version index/schema-version
                 :state :rebuilding
                 :generation 7
                 :updated-at 12345}}]})
  (let [result (run-status {})]
    (is (= :ok (:status result)))
    (is (= :building (get-in result [:payload :state])))
    (is (= 7 (get-in result [:payload :generation])))
    (is (= 12345 (get-in result [:payload :built-at])))
    (is (= 0 (get-in result [:payload :session-count])))
    (is (= 0 (get-in result [:payload :message-count])))))

;; ---------------------------------------------------------------------------
;; Reindex trust

(deftest reindex-requires-an-explicit-construction-time-trust-switch
  (let [main (session "s1" [(message "m1" :user "reindexterm")])
        _ (index/index-session! *store* main)
        denied (run-reindex [main] {:kind :history/reindex
                                    :allow-reindex? true}
                            {})]
    (is (= :error (:status denied)))
    (is (= :REINDEX_REQUIRES_TRUSTED_CALLER
           (get-in denied [:error :code]))))

  (let [main (session "s1" [(message "m1" :user "reindexterm")])
        _ (index/index-session! *store* main)
        first (run-reindex [main] {} {:allow-reindex? true})
        second (run-reindex [main] {} {:allow-reindex? true})]
    (is (= :ok (:status first)))
    (is (= :ok (:status second)))
    (is (= 1 (get-in first [:payload :session-count])))
    (is (= 2 (get-in first [:payload :generation])))
    (is (= 3 (get-in second [:payload :generation])))
    (is (= 1 (get-in second [:payload :session-count])))))

;; ---------------------------------------------------------------------------
;; Error conversion

(deftest index-exceptions-become-structured-errors
  (testing "search"
    (let [handler (command/search-handler
                   {:store *store*
                    :read-session-fn (reader {})})]
      (with-redefs [index/search
                    (fn [& _]
                      (throw (ex-info "boom" {:search/error true})))]
        (let [result (handler {:query "anything"})]
          (is (= :error (:status result)))
          (is (= :HISTORY_SEARCH_FAILED (get-in result [:error :code])))
          (is (= "boom" (get-in result [:error :message])))))))

  (testing "status"
    (with-redefs [index/index-meta
                  (fn [& _]
                    (throw (ex-info "meta down" {:search/error true})))]
      (let [result (run-status {})]
        (is (= :error (:status result)))
        (is (= :HISTORY_SEARCH_STATUS_FAILED
               (get-in result [:error :code])))
        (is (= "meta down" (get-in result [:error :message]))))))

  (testing "reindex"
    (with-redefs [index/rebuild!
                  (fn [& _]
                    (throw (ex-info "rebuild down" {:search/error true})))]
      (let [result (run-reindex [] {} {:allow-reindex? true})]
        (is (= :error (:status result)))
        (is (= :HISTORY_REINDEX_FAILED
               (get-in result [:error :code])))
        (is (= "rebuild down" (get-in result [:error :message])))))))
