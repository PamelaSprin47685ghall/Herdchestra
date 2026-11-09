(ns hc.server.app.retention-gc-test
  "Retention scheduler tests.

  Session retention uses an in-memory persistence with fixed timestamps.
  Blob retention uses a real Datalevin store, a real blob root, and the
  default coordinated cleanup wrapper so age, reference, and read-failure
  behavior is observable without a JVM-wide service."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.retention-gc :as retention]
            [hc.server.blob-store :as blob]
            [hc.server.blob-store.layout :as layout]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileTime)))

(def ^:private day-ms retention/day-ms)

(defn- deleted-session
  [id deleted-at & {:keys [temporary?]}]
  {:session-id id
   :owner-id (str "owner-" id)
   :revision 1
   :status :deleted
   :deleted-at deleted-at
   :temporary? (boolean temporary?)
   :messages {"m1" {:message-id "m1"
                    :role :user
                    :versions [{:version-id "m1-v1" :content "hello"}]
                    :current-version 0}}
   :message-order ["m1"]})

(defn- active-session
  [id]
  {:session-id id
   :owner-id (str "owner-" id)
   :revision 1
   :status :active
   :deleted-at nil
   :temporary? false
   :messages {}
   :message-order []})

(defn- registry-with
  [now sessions]
  (registry/registry
   {:persistence
    (ports/memory-persistence
     {:sessions (into {} (map (juxt :session-id identity)) sessions)})
    :now-fn (fn [] now)}))

(defn- list-failing-registry
  []
  (let [backing (ports/memory-persistence)]
    (registry/registry
     {:persistence
      (reify ports/SessionPersistence
        (read-session [_ session-id]
          (ports/read-session backing session-id))
        (commit-session! [_ expected session]
          (ports/commit-session! backing expected session))
        (list-session-ids [_]
          {:status :error
           :error {:code :STORAGE_READ_FAILED
                   :message "session list read failed"}})
        (delete-session! [_ expected session-id]
          (ports/delete-session! backing expected session-id))
        (read-config [_]
          (ports/read-config backing))
        (commit-config! [_ expected config]
          (ports/commit-config! backing expected config)))})))

(defn- start-service!
  [reg opts]
  (let [result
        (retention/start!
         (merge {:registry reg
                 :clock (constantly 0)
                 :session-interval-ms (* 24 60 60 1000)
                 :blob-interval-ms (* 24 60 60 1000)
                 :session-days 30
                 :blob-days 7
                 :run-immediately? false
                 :purge-session!
                 (fn [session-id]
                   (registry/purge-deleted-session! reg session-id))
                 :blob-cleanup!
                 (fn [_]
                   {:outcome :completed :deleted [] :errors []})}
                opts))]
    (when-not (:ok? result)
      (throw (ex-info "retention start failed" result)))
    (:service result)))

(defn- utf8
  ^bytes [text]
  (.getBytes ^String text "UTF-8"))

(defn- set-mtime!
  [blob-root id millis]
  (Files/setLastModifiedTime
   (layout/path-of (layout/object-path blob-root id))
   (FileTime/fromMillis millis)))

(defn- eventually
  [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(deftest session-retention-keeps-29-days-and-purges-30-days
  (let [now (* 40 day-ms)
        sessions [(deleted-session "expired" (- now (* 30 day-ms)))
                  (deleted-session "retained" (- now (* 29 day-ms)))
                  (deleted-session "temporary" (- now (* 40 day-ms))
                                   :temporary? true)
                  (active-session "active")
                  ;; A restored record: the business transition clears
                  ;; `:deleted-at`, so the old deletion never becomes eligible.
                  (assoc (active-session "restored") :deleted-at nil)]
        reg (registry-with now sessions)
        svc (start-service! reg {:clock (constantly now)})]
    (try
      (let [result (retention/purge-expired-deleted! svc)]
        (is (:ok? result))
        (is (= :completed (:outcome result)))
        (is (= ["expired"] (:purged result)))
        (is (empty? (:failed result)))
        (is (= :missing (:status (registry/get-session reg "expired"))))
        (doseq [id ["retained" "temporary" "active" "restored"]]
          (is (= :found (:status (registry/get-session reg id)))
              (str id " must survive the sweep"))))
      (let [status (retention/status svc)]
        (is (= 1 (:session-runs status)))
        (is (= now (:last-run-at status)))
        (is (nil? (:session-last-error status)))
        (is (= :completed (get-in status [:session-last-result :outcome]))))
      (finally
        (retention/stop! svc)))))

(deftest purge-failure-does-not-stop-other-sessions-and-is-retried
  (let [now (* 40 day-ms)
        sessions [(deleted-session "bad" (- now (* 31 day-ms)))
                  (deleted-session "ok-1" (- now (* 31 day-ms)))
                  (deleted-session "ok-2" (- now (* 31 day-ms)))]
        reg (registry-with now sessions)
        svc (start-service!
             reg
             {:clock (constantly now)
              :purge-session!
              (fn [session-id]
                (if (= "bad" session-id)
                  (throw (ex-info "purge exploded"
                                  {:code :PURGE_EXPLODED
                                   :session-id session-id}))
                  (registry/purge-deleted-session! reg session-id)))})]
    (try
      (let [first-result (retention/purge-expired-deleted! svc)]
        (is (false? (:ok? first-result)))
        (is (= :partial (:outcome first-result)))
        (is (= #{"ok-1" "ok-2"} (set (:purged first-result))))
        (is (= 1 (count (:failed first-result))))
        (is (= "bad" (get-in first-result [:failed 0 :session-id])))
        (is (= :PURGE_EXPLODED
               (get-in first-result [:failed 0 :error :code])))
        (is (= :missing (:status (registry/get-session reg "ok-1"))))
        (is (= :missing (:status (registry/get-session reg "ok-2"))))
        (is (= :found (:status (registry/get-session reg "bad"))))
        (is (= [] (:retried first-result)))
        (testing "the failure stays visible in the scheduler status"
          (let [status (retention/status svc)]
            (is (= 1 (:session-runs status)))
            (is (= :RETENTION_SESSION_PURGE_FAILED
                   (get-in status [:session-last-error :code])))
            (is (= 1 (get-in status [:session-last-error
                                     :details :failed-count])))
            (is (= 2 (get-in status [:session-last-error
                                     :details :purged])))
            (is (= 0 (get-in status [:session-last-error
                                     :details :skipped])))
            (is (= 3 (get-in status [:session-last-error
                                     :details :eligible])))))
        (testing "the next sweep retries the failed session"
          (let [second-result (retention/purge-expired-deleted! svc)
                status (retention/status svc)]
            (is (= [] (:purged second-result)))
            (is (= 1 (count (:failed second-result))))
            (is (= ["bad"] (:retried second-result)))
            (is (= ["bad"] (get-in status [:session-last-result :retried])))
            (is (= 1 (get-in status [:session-last-result :retried-count])))
            (is (= 2 (:session-runs status)))
            (is (= :RETENTION_SESSION_PURGE_FAILED
                   (get-in status [:session-last-error :code])))
            (is (= 1 (get-in status [:session-last-error
                                     :details :failed-count]))))))
      (finally
        (retention/stop! svc)))))

(deftest session-purge-dry-run-deletes-nothing
  (let [now (* 40 day-ms)
        sessions [(deleted-session "expired" (- now (* 31 day-ms)))]
        reg (registry-with now sessions)
        svc (start-service! reg {:clock (constantly now)})]
    (try
      (let [result (retention/purge-expired-deleted! svc {:dry-run? true})]
        (is (:ok? result))
        (is (= :dry-run (:outcome result)))
        (is (true? (:dry-run? result)))
        (is (= ["expired"] (:planned result)))
        (is (= [] (:purged result)))
        (is (= :found (:status (registry/get-session reg "expired"))))
        (is (true? (get-in (retention/status svc)
                           [:session-last-result :dry-run?]))))
      (finally
        (retention/stop! svc)))))

(deftest fatal-sweep-errors-do-not-look-like-partial-purge-failures
  (let [svc (start-service!
             (list-failing-registry)
             {:blob-cleanup!
              (fn [_] {:outcome :completed :deleted [] :errors []})})]
    (try
      (let [result (retention/purge-expired-deleted! svc)
            status (retention/status svc)]
        (is (false? (:ok? result)))
        (is (= :error (:outcome result)))
        (is (= :RETENTION_SESSION_LIST_FAILED
               (get-in result [:error :code])))
        (is (= :RETENTION_SESSION_SWEEP_FAILED
               (get-in status [:session-last-error :code])))
        (is (= :RETENTION_SESSION_LIST_FAILED
               (get-in status [:session-last-error :details :cause])))
        (is (nil? (get-in status [:session-last-error
                                  :details :failed-count])))
        (is (= 1 (:session-runs status)))
        (is (= :error (get-in status [:session-last-result :outcome]))))
      (finally
        (retention/stop! svc)))))

(deftest blob-gc-keeps-young-and-referenced-old-objects
  (let [root (support/temp-dir "hc-retention-blob-")
        blob-root (str root "/blobs")
        now (* 40 day-ms)
        store (storage/open-store {:path (str root "/store")
                                   :create? true
                                   :durability :sync
                                   :mapsize-mib 32})]
    (try
      (let [old (blob/put-bytes! blob-root (utf8 "old-unreferenced"))
            young (blob/put-bytes! blob-root (utf8 "young-unreferenced"))
            referenced (blob/put-bytes! blob-root (utf8 "old-referenced"))
            old-ms (- now (* 8 day-ms))
            young-ms (- now day-ms)]
        (set-mtime! blob-root (:id old) old-ms)
        (set-mtime! blob-root (:id referenced) old-ms)
        (set-mtime! blob-root (:id young) young-ms)
        (storage/manage-blob-refs! store
                                   {:partition "retention-test"
                                    :op :add
                                    :refs [{:id (:id referenced)
                                            :kind :attachment}]})
        (let [reg (registry-with now [])
              svc (start-service! reg {:store store
                                       :blob-root blob-root
                                       :clock (constantly now)
                                       :blob-days 7
                                       :blob-cleanup! nil})]
          (try
            (let [result (retention/gc-blobs! svc)]
              (is (:ok? result))
              (is (= :completed (:outcome result)))
              (is (= 7 (quot (:safety-window-ms result) day-ms)))
              (is (= #{(:id old)} (set (:deleted result))))
              (is (false? (blob/exists? blob-root (:id old))))
              (is (true? (blob/exists? blob-root (:id young))))
              (is (true? (blob/exists? blob-root (:id referenced))))
              (is (some #(= (:id referenced) (:id %))
                        (:retained result))))
            (let [status (retention/status svc)]
              (is (= 1 (:blob-runs status)))
              (is (nil? (:blob-last-error status)))
              (is (= 1 (get-in status [:blob-last-result :deleted-count]))))
            (finally
              (retention/stop! svc)))))
      (finally
        (try
          (storage/close-store! store)
          (catch Throwable _ nil))
        (support/delete-tree! root)))))

(deftest blob-gc-reference-read-failure-deletes-nothing
  (let [root (support/temp-dir "hc-retention-blob-failure-")
        blob-root (str root "/blobs")
        now (* 40 day-ms)
        store (storage/open-store {:path (str root "/store")
                                   :create? true
                                   :durability :sync
                                   :mapsize-mib 32})]
    (try
      (let [old (blob/put-bytes! blob-root (utf8 "old-unreferenced"))]
        (set-mtime! blob-root (:id old) (- now (* 8 day-ms)))
        ;; A closed store makes the reference read fail before any deletion.
        (storage/close-store! store)
        (let [reg (registry-with now [])
              svc (start-service! reg {:store store
                                       :blob-root blob-root
                                       :clock (constantly now)
                                       :blob-cleanup! nil})]
          (try
            (let [result (retention/gc-blobs! svc)]
              (is (false? (:ok? result)))
              (is (= :error (:outcome result)))
              (is (= :STORAGE_CLOSED (get-in result [:error :code])))
              (is (true? (blob/exists? blob-root (:id old)))
                  "a failed reference read must delete nothing")
              (let [status (retention/status svc)]
                (is (= 1 (:blob-runs status)))
                (is (= :STORAGE_CLOSED
                       (get-in status [:blob-last-error :code])))
                (is (= :error
                       (get-in status [:blob-last-result :outcome])))
                (is (false? (get-in status [:blob-last-result :ok?])))))
            (finally
              (retention/stop! svc)))))
      (finally
        (support/delete-tree! root)))))

(deftest partial-blob-cleanup-is-not-reported-as-a-fatal-sweep
  (let [svc (start-service!
             (registry-with 0 [])
             {:blob-cleanup!
              (fn [_]
                {:outcome :partial
                 :deleted ["old-object"]
                 :errors [{:id "failed-object"
                           :error :BLOB_IO
                           :message "could not delete"}]})})]
    (try
      (let [result (retention/gc-blobs! svc)
            status (retention/status svc)
            error (:blob-last-error status)]
        (is (false? (:ok? result)))
        (is (= :partial (:outcome result)))
        (is (= ["old-object"] (:deleted result)))
        (is (= :RETENTION_BLOB_PARTIAL (:code error)))
        (is (not= :RETENTION_BLOB_SWEEP_FAILED (:code error)))
        (is (= 1 (get-in error [:details :error-count])))
        (is (= :BLOB_IO (get-in error [:details :errors 0 :error]))))
      (finally
        (retention/stop! svc)))))

(deftest stop-is-idempotent-and-bounded
  (let [now (* 40 day-ms)
        sessions [(deleted-session "expired" (- now (* 30 day-ms)))]
        reg (registry-with now sessions)
        started (promise)
        svc (start-service!
             reg
             {:clock (constantly now)
              :run-immediately? true
              :stop-timeout-ms 100
              :purge-session!
              (fn [_]
                (deliver started true)
                (Thread/sleep 10000)
                {:status :purged})})
        began (deref started 3000 false)
        t0 (System/nanoTime)
        first-stop (retention/stop! svc)
        elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)
        second-stop (retention/stop! svc)]
    (is (true? began) "the immediate sweep must start before stop!")
    (is (< elapsed-ms 2000.0)
        "stop! interrupts an in-flight sweep after its bounded wait")
    (is (true? (:stopped? first-stop)))
    (is (true? (:terminated? first-stop)))
    (is (= first-stop second-stop) "stop! is idempotent")
    (is (= :RETENTION_GC_STOPPED
           (get-in (retention/purge-expired-deleted! svc) [:error :code])))
    (is (= :RETENTION_GC_STOPPED
           (get-in (retention/gc-blobs! svc) [:error :code])))))

(deftest stop-does-not-wait-for-a-delayed-first-tick
  (let [svc (start-service! (registry-with 0 []) {:run-immediately? false})
        t0 (System/nanoTime)
        stopped (retention/stop! svc)
        elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)
        status (retention/status svc)]
    (is (< elapsed-ms 1000.0)
        "cancelling the delayed tasks keeps shutdown immediate")
    (is (= {:stopped? true :terminated? true} stopped))
    (is (false? (:running? status)))
    (is (true? (:stopped? status)))
    (is (true? (:terminated? status)))))

(deftest timer-runs-the-blob-sweep-with-the-configured-window
  (let [now (* 40 day-ms)
        seen (promise)
        svc (start-service!
             (registry-with now [])
             {:clock (constantly now)
              :run-immediately? true
              :blob-days 7
              :blob-cleanup!
              (fn [opts]
                (deliver seen opts)
                {:outcome :completed :deleted [] :errors []})})
        opts (deref seen 3000 nil)]
    (try
      (is (some? opts) "the immediate tick reaches the blob sweep")
      (is (= (* 7 day-ms) (:safety-window-ms opts)))
      (is (= now (:now opts)))
      (is (false? (:dry-run? opts)))
      (is (eventually 2000
                      #(pos? (:blob-runs (retention/status svc))))
          "the scheduler records the completed blob sweep")
      (finally
        (retention/stop! svc)))))

(deftest start-validates-required-options
  (let [reg (registry-with 0 [])]
    (is (= :RETENTION_REGISTRY_REQUIRED
           (get-in (retention/start! {:registry nil
                                      :blob-cleanup! (fn [_] {})})
                   [:error :code])))
    (is (= :RETENTION_BLOB_CLEANUP_REQUIRED
           (get-in (retention/start! {:registry reg})
                   [:error :code])))
    (is (= :RETENTION_CLOCK_INVALID
           (get-in (retention/start! {:registry reg
                                      :clock :not-a-function
                                      :blob-cleanup! (fn [_] {})})
                   [:error :code])))
    (is (= :RETENTION_SESSION_INTERVAL_INVALID
           (get-in (retention/start! {:registry reg
                                      :session-interval-ms 0
                                      :blob-cleanup! (fn [_] {})})
                   [:error :code])))
    (is (= :RETENTION_BLOB_INTERVAL_INVALID
           (get-in (retention/start! {:registry reg
                                      :blob-interval-ms -1
                                      :blob-cleanup! (fn [_] {})})
                   [:error :code])))
    (is (= :RETENTION_SESSION_DAYS_INVALID
           (get-in (retention/start! {:registry reg
                                      :session-days 0
                                      :blob-cleanup! (fn [_] {})})
                   [:error :code])))
    (is (= :RETENTION_BLOB_DAYS_INVALID
           (get-in (retention/start! {:registry reg
                                      :blob-days 0
                                      :blob-cleanup! (fn [_] {})})
                   [:error :code])))
    (is (= :RETENTION_RUN_IMMEDIATELY_INVALID
           (get-in (retention/start! {:registry reg
                                      :run-immediately? :yes
                                      :blob-cleanup! (fn [_] {})})
                   [:error :code])))))
