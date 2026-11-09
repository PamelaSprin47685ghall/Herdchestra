(ns hc.server.storage.startup-scale-test
  "Permanent JVM regression for the P-BENCH1 server-side startup path.

  The store holds 1000 sessions, each with two messages, plus the compact
  summaries and the session index.  A narrow read must never load a session
  record body: `list-session-ids`, `read-session-summary`, the registry list
  projection and the Owner-state preflight are point reads over the index,
  summary and partition-head documents.

  Every timing assertion warms up once and then takes the median of three
  runs.  The thresholds are upper bounds for a regression, not a performance
  target; an actual value is printed beside the assertion."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]
            [hc.server.storage :as storage]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.support :as support]
            [hc.server.storage.view :as view]))

(def ^:private session-count 1000)
(def ^:private messages-per-session 2)
(def ^:private batch-size 50)

(def ^:private summary-budget-ms 2000.0)
(def ^:private list-budget-ms 5000.0)
(def ^:private open-budget-ms 60000.0)

(defn- elapsed-ms
  [f]
  (let [start (System/nanoTime)]
    (f)
    (/ (double (- (System/nanoTime) start)) 1e6)))

(defn- median-ms
  "Warm up once, then return {:runs [..] :median ms} over three runs."
  [f]
  (elapsed-ms f)
  (let [runs (vec (repeatedly 3 #(elapsed-ms f)))]
    {:runs runs
     :median (nth (sort runs) 1)}))

(defn- message
  [session-id message-id role text]
  {:message-id message-id
   :session-id session-id
   :role role
   :current-version 0
   :versions [{:content text}]
   :attachments []
   :citations []})

(defn- session-record
  [session-id n]
  {:session-id session-id
   :owner-id (str session-id "-owner")
   :status :active
   :revision 0
   :title session-id
   :language :en
   :created-at n
   :updated-at n
   :activity-at n
   :message-order ["m1" "m2"]
   :messages {"m1" (message session-id "m1" :user "hello")
              "m2" (message session-id "m2" :assistant "world")}
   :drafts {}
   :visible-message-count messages-per-session})

(defn- session-summary
  [session-id n]
  {:session-id session-id
   :owner-id (str session-id "-owner")
   :status :active
   :revision 0
   :title session-id
   :language :en
   :created-at n
   :updated-at n
   :activity-at n
   :pinned? false
   :temporary? false
   :has-draft? false
   :visible-message-count messages-per-session})

(defn- seed-sessions!
  "Write the 1000 records and summaries in batches, then one session index."
  [store]
  (let [ids (mapv #(str "scale-s" %) (range session-count))]
    (doseq [[batch-index batch] (map-indexed vector (partition-all batch-size ids))]
      (let [puts (mapcat
                  (fn [offset session-id]
                    (let [n (+ (* batch-index batch-size) offset)]
                      [{:kind :session-record
                        :id session-id
                        :doc (session-record session-id n)}
                       {:kind :session-summary
                        :id session-id
                        :doc (session-summary session-id n)}]))
                  (range)
                  batch)]
        (is (:ok? (storage/commit-migration!
                   store
                   {:migration-id (str "scale-seed-" batch-index)
                    :partition "sessions"
                    :lightweight-return? true
                    :put (vec puts)})))))
    (is (:ok? (storage/commit-migration!
               store
               {:migration-id "scale-index"
                :partition "sessions"
                :lightweight-return? true
                :put [{:kind :session-index
                       :id "sessions"
                       :doc {:id "sessions" :ids ids}}]})))
    ids))

(defn- reject-full-record-reads
  "Run F while every full `:session-record` point read is counted and refused.

  Summary/index/partition-head reads still go through `storage/read-document`
  with another kind, so the normal point-read path stays available."
  [orig-read-document full-reads f]
  (with-redefs [storage/read-document
                (fn [store opts]
                  (when (= :session-record (:kind opts))
                    (swap! full-reads inc)
                    (throw (ex-info "a scale read must not load a session record"
                                    {:opts opts})))
                  (orig-read-document store opts))]
    (f)))

(defn- verify-owner-states-equivalent!
  "Mirror `hc.server.app.system/verify-owner-states!` without loading that
  namespace.

  `system.clj` is under concurrent edit, so the private function can be
  temporarily unreadable.  This copy keeps the same contract: list the
  committed ids, read each compact summary, and read the Owner partition head
  for every non-deleted session.  A positive partition version without its
  state document is corruption."
  [store persistence]
  (let [listed (ports/list-session-ids persistence)]
    (when-not (= :ok (:status listed))
      (throw (ex-info "startup session list could not be read"
                      {:listed listed})))
    (doseq [session-id (:ids listed)]
      (let [result (ports/read-session-summary persistence session-id)]
        (case (:status result)
          :missing nil
          :found
          (when (not= :deleted (get-in result [:session :status]))
            (let [{:keys [version state]}
                  (storage/read-partition-head
                   store
                   {:partition (str "session/" session-id)
                    :session-id session-id})]
              (when (and (pos? (long (or version 0)))
                         (nil? state))
                (throw (ex-info "a session Owner state is missing; refusing"
                                {:code :OWNER_STORE_CORRUPT
                                 :session-id session-id})))))
          (throw (ex-info "startup session summary could not be read"
                          {:session-id session-id
                           :result result})))))))

(deftest startup-scale-point-read-path-test
  (let [dir (support/temp-dir "hc-session-scale-")
        store (storage/open-store {:path dir :create? true})]
    (try
      (let [ids (seed-sessions! store)
            persistence (session-persistence/persistence store)]
        (testing "list-session-ids only reads the index"
          (let [view-calls (atom 0)]
            (with-redefs [view/session-view
                          (fn [& _]
                            (swap! view-calls inc)
                            {:version 0})]
              (let [listed (ports/list-session-ids persistence)]
                (is (= :ok (:status listed)))
                (is (= session-count (count (:ids listed))))
                (is (= (set ids) (set (:ids listed))))))
            (is (zero? @view-calls)
                "list-session-ids must not build a partition view")))

        (let [orig-read-document storage/read-document
              full-reads (atom 0)]
          (reject-full-record-reads
           orig-read-document full-reads
           (fn []
             (testing "read-session-summary reads one compact document per session"
               (let [results (mapv #(ports/read-session-summary persistence %)
                                   ids)]
                 (is (every? #(= :found (:status %)) results))
                 (is (every? (fn [result]
                               (not-any? (fn [key]
                                           (contains? (:session result) key))
                                         [:messages :message-order]))
                             results))
                 (let [{:keys [runs median]}
                       (median-ms
                        (fn []
                          (mapv #(ports/read-session-summary persistence %)
                                ids)))]
                   (println "startup-scale summary-read-ms"
                            {:runs runs :median median})
                   (is (< median summary-budget-ms)
                       (str "1000 summary reads exceeded " summary-budget-ms
                            " ms: " median)))))

             (testing "the registry list projection uses summaries only"
               (let [reg (registry/registry {:persistence persistence})
                     first-list (registry/list-sessions reg {})]
                 (is (= :ok (:status first-list)))
                 (is (= session-count (count (:sessions first-list))))
                 (is (every? #(= messages-per-session (:message-count %))
                             (:sessions first-list)))
                 (is (every? (fn [session]
                               (not-any? (fn [key] (contains? session key))
                                         [:messages :message-order]))
                             (:sessions first-list)))
                 (let [{:keys [runs median]}
                       (median-ms #(registry/list-sessions reg {}))]
                   (println "startup-scale registry-list-ms"
                            {:runs runs :median median})
                   (is (< median list-budget-ms)
                       (str "registry list-sessions exceeded " list-budget-ms
                            " ms: " median)))))))

          (is (zero? @full-reads)
              "a scale read path must never deserialize a session record body")

          (testing "Owner preflight uses summaries and partition heads only"
            (let [orig-summary ports/read-session-summary
                  summary-calls (atom 0)
                  session-calls (atom 0)]
              (reject-full-record-reads
               orig-read-document full-reads
               (fn []
                 (with-redefs [ports/read-session-summary
                               (fn [persistence session-id]
                                 (swap! summary-calls inc)
                                 (orig-summary persistence session-id))
                               ports/read-session
                               (fn [& _]
                                 (swap! session-calls inc)
                                 (throw
                                  (ex-info
                                   "Owner preflight must not read a full session"
                                   {})))]
                   (is (nil? (verify-owner-states-equivalent!
                              store persistence))))))
              (is (= session-count @summary-calls))
              (is (zero? @session-calls))))
          (is (zero? @full-reads))))

      (testing "the Datalevin environment opens promptly"
        (storage/close-store! store)
        (let [path (storage/store-path store)
              {:keys [runs median]}
              (median-ms
               (fn []
                 (let [open (engine/open! path {})]
                   (try
                     (finally
                       (engine/close! open))))))]
          (println "startup-scale datalevin-open-ms"
                   {:runs runs :median median})
          (is (< median open-budget-ms)
              (str "Datalevin open exceeded " open-budget-ms
                   " ms: " median))))
      (finally
        (try (storage/close-store! store) (catch Throwable _ nil))
        (support/delete-tree! dir)))))
