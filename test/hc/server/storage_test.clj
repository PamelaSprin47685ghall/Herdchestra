(ns hc.server.storage-test
  "Contract tests for the embedded current-state store.

  The tests cover the five §7.1 responsibilities, the atomic commit and read
  barrier, idempotency, the non-destructive open rules, export/restore and the
  ordered close.  They use real Datalevin environments on the real filesystem
  in a temporary directory; nothing is mocked."

  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.storage :as storage]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.layout :as layout]
            [hc.server.storage.support :as support]))

(defn- error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (storage/error-code e))))

(deftest create-commit-reopen-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})
            result (storage/commit-migration!
                    store
                    {:migration-id "m-1"
                     :partition "session/s1"
                     :session-id "s1"
                     :put [{:kind :session :id "s1" :doc {:title "hello"}}
                           {:kind :message :id "msg-2" :doc {:seq 2 :message-id "msg-2" :text "b"}}
                           {:kind :message :id "msg-1" :doc {:seq 1 :message-id "msg-1" :text "a"}}]
                     :meta {:milestone :plan}})]
        (is (:ok? result))
        (is (= 1 (:version result)))
        (is (= [{:seq 1 :message-id "msg-1" :text "a"}
                {:seq 2 :message-id "msg-2" :text "b"}]
               (mapv #(select-keys % [:seq :message-id :text])
                     (:messages (:view result)))))
        (is (= {:title "hello"}
               (select-keys (:session (:view result)) [:title])))
        (storage/close-store! store))
      (let [store (storage/open-store {:path dir})]
        (is (= {:title "hello"}
               (select-keys (:session (storage/read-session-view
                                       store {:partition "session/s1"
                                              :session-id "s1"}))
                            [:title])))
        (is (= 1 (:version (storage/read-session-view store {:partition "session/s1"
                                                             :session-id "s1"}))))
        (storage/close-store! store))
      (finally (support/delete-tree! dir)))))

(deftest no-create-does-not-create-test
  (let [dir (str (support/temp-dir "hc-store-") "/missing")]
    (try
      (is (= :STORAGE_NOT_FOUND
             (error-code #(storage/open-store {:path dir}))))
      (is (not (.exists (io/file dir))))
      (finally (support/delete-tree! (.getParent (io/file dir)))))))

(deftest empty-directory-requires-create-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (is (= :STORAGE_NOT_INITIALIZED
             (error-code #(storage/open-store {:path dir}))))
      (is (empty? (seq (.listFiles (io/file dir)))))
      (let [store (storage/open-store {:path dir :create? true})]
        (is (storage/store-open? store))
        (storage/close-store! store))
      (finally (support/delete-tree! dir)))))

(deftest foreign-directory-is-never-adopted-test
  (let [dir (support/temp-dir "hc-store-")
        f   (io/file dir "user.txt")]
    (try
      (spit f "keep me")
      (is (= :STORAGE_NOT_A_STORE
             (error-code #(storage/open-store {:path dir :create? true}))))
      (is (= "keep me" (slurp f)))
      (is (= #{"user.txt"} (set (map #(.getName %) (.listFiles (io/file dir))))))
      (finally (support/delete-tree! dir)))))

(deftest marker-without-environment-is-corrupt-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (spit (io/file dir layout/marker-name)
            (pr-str {:store/format-version 1 :store/id "x"}))
      (is (= :STORAGE_CORRUPT
             (error-code #(storage/open-store {:path dir :create? true}))))
      (is (= #{layout/marker-name}
             (set (map #(.getName %) (.listFiles (io/file dir))))))
      (finally (support/delete-tree! dir)))))

(deftest format-mismatch-is-refused-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (storage/close-store! store))
      (spit (io/file dir layout/marker-name)
            (pr-str {:store/format-version 2 :store/id "x" :store/engine :datalevin-kv}))
      (is (= :STORAGE_FORMAT_MISMATCH
             (error-code #(storage/open-store {:path dir :create? true}))))
      (finally (support/delete-tree! dir)))))

(deftest corrupt-environment-is-not-replaced-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (storage/close-store! store))
      (let [data (io/file dir layout/datalevin-data-name)
            before (.length data)]
        (with-open [raf (java.io.RandomAccessFile. data "rw")]
          (.setLength raf 32))
        (is (contains? #{:STORAGE_OPEN_FAILED :STORAGE_CORRUPT}
                       (error-code #(storage/open-store {:path dir :create? true}))))
        (is (= 32 (.length data)))
        (is (pos? before)))
      (is (not (.exists (io/file dir (str layout/marker-name ".tmp")))))
      (finally (support/delete-tree! dir)))))

(deftest interrupted-initialization-test
  (testing "a crash after the init transaction but before the marker is recoverable"
    (let [dir (support/temp-dir "hc-store-")]
      (try
        (let [store (storage/open-store {:path dir :create? true})]
          (storage/close-store! store))
        (io/delete-file (io/file dir layout/marker-name))
        (is (= :STORAGE_NOT_INITIALIZED
               (error-code #(storage/open-store {:path dir}))))
        (let [store (storage/open-store {:path dir :create? true})]
          (is (storage/store-open? store))
          (storage/close-store! store))
        (finally (support/delete-tree! dir)))))
  (testing "an environment with user data but no marker is never adopted"
    (let [dir (support/temp-dir "hc-store-")]
      (try
        (let [store (storage/open-store {:path dir :create? true})]
          (storage/commit-migration! store {:migration-id "x" :partition "p"
                                            :put [{:kind :task :id "t" :doc {}}]})
          (storage/close-store! store))
        (io/delete-file (io/file dir layout/marker-name))
        (is (= :STORAGE_NOT_A_STORE
               (error-code #(storage/open-store {:path dir :create? true}))))
        (is (not (.exists (io/file dir layout/marker-name))))
        (finally (support/delete-tree! dir))))))

(deftest lock-conflict-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (is (= :STORAGE_LOCKED
               (error-code #(storage/open-store {:path dir}))))
        (storage/close-store! store))
      (let [store (storage/open-store {:path dir})]
        (is (storage/store-open? store))
        (storage/close-store! store))
      (finally (support/delete-tree! dir)))))

(deftest idempotency-and-conflict-test
  (support/with-temp-store [store {}]
    (let [m {:migration-id "same-id"
             :partition "p"
             :put [{:kind :task :id "t1" :doc {:state :ready}}]}
          a (storage/commit-migration! store m)
          b (storage/commit-migration! store m)]
      (is (:ok? a))
      (is (:replayed? b))
      (is (= (:commit a) (:commit b)))
      (is (= (:version a) (:version b)))
      (is (= :STORAGE_MIGRATION_CONFLICT
             (error-code #(storage/commit-migration!
                           store
                           (assoc m :put [{:kind :task :id "t1" :doc {:state :done}}]))))))))

(deftest expected-version-test
  (support/with-temp-store [store {}]
    (let [m {:migration-id "v1" :partition "p"
             :expected-version 0
             :put [{:kind :task :id "t1" :doc {:state :ready}}]}]
      (is (= 1 (:version (storage/commit-migration! store m))))
      (is (= :STORAGE_VERSION_CONFLICT
             (error-code #(storage/commit-migration!
                           store
                           {:migration-id "v2" :partition "p" :expected-version 0
                            :put [{:kind :task :id "t2" :doc {}}]}))))
      (is (= 2 (:version (storage/commit-migration!
                          store
                          {:migration-id "v3" :partition "p" :expected-version 1
                           :put [{:kind :task :id "t3" :doc {}}]})))))))

(deftest delete-and-meta-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "add" :partition "p"
      :put [{:kind :task :id "t1" :doc {:state :ready}}
            {:kind :task :id "t2" :doc {:state :ready}}]
      :meta {:owner "alice"}})
    (let [r (storage/commit-migration!
             store
             {:migration-id "del" :partition "p"
              :delete [{:kind :task :id "t1"}]
              :meta {:owner "bob"}})]
      (is (= ["t2"] (mapv :storage/id (:tasks (:board (:view r)))))))
    (is (= {:owner "bob"}
           (get-in (storage/export-state store {:partition "p"})
                   [:partitions "p" :meta])))))

(deftest obligations-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "obs" :partition "p" :session-id "s"
      :obligations {:put [{:obligation/id "call-1" :open? true :phase :ready :due-at 100}
                          {:obligation/id "call-2" :open? true :phase :running :due-at 500}
                          {:obligation/id "call-3" :open? false :phase :closed}]}})
    (let [all (storage/list-open-obligations store {:partition "p" :session-id "s"})
          due (storage/list-open-obligations store {:partition "p" :session-id "s"
                                                    :now-ms 200 :due-only? true})]
      (is (= 2 (:count all)))
      (is (= ["call-1" "call-2"] (mapv :obligation/id (:obligations all))))
      (is (= ["call-1"] (mapv :obligation/id (:obligations due))))
      (is (= "s" (:storage/session-id (first (:obligations all))))))))

(deftest blob-refs-test
  (support/with-temp-store [store {}]
    (let [added (storage/manage-blob-refs!
                 store
                 {:partition "p" :op :add
                  :refs [{:id "sha256-a" :size 3}
                         {:id "sha256-b" :size 4}]})]
      (is (:ok? added))
      (is (= 2 (count (:refs (storage/manage-blob-refs! store {:partition "p" :op :list})))))
      (storage/manage-blob-refs! store {:partition "p" :op :release
                                        :refs [{:id "sha256-a"}]})
      (is (= ["sha256-b"]
             (mapv :id (:refs (storage/manage-blob-refs! store {:partition "p" :op :list}))))))
    (let [r (storage/commit-migration!
             store
             {:migration-id "doc-with-blob" :partition "p"
              :put [{:kind :artifact :id "a1" :doc {:blob "sha256-c"}}]
              :blob-refs {:add [{:id "sha256-c" :size 5}]}})]
      (is (:ok? r))
      (is (= ["sha256-b" "sha256-c"]
             (mapv :id (:refs (storage/manage-blob-refs! store {:partition "p" :op :list}))))))))

(deftest export-restore-roundtrip-test
  (let [dir-a (support/temp-dir "hc-store-a-")
        dir-b (support/temp-dir "hc-store-b-")]
    (try
      (let [a (storage/open-store {:path dir-a :create? true})]
        (storage/commit-migration!
         a
         {:migration-id "seed" :partition "session/s1" :session-id "s1"
          :put [{:kind :session :id "s1" :doc {:title "t"}}
                {:kind :message :id "m1" :doc {:seq 1 :message-id "m1" :text "hello"}}
                {:kind :task :id "t1" :doc {:state :ready}}]
          :obligations {:put [{:obligation/id "o1" :open? true :phase :ready}]}
          :blob-refs {:add [{:id "sha256-x" :size 1}]}})
        (let [export (storage/export-state a {})]
          (is (:complete? export))
          (is (= 1 (:format-version export)))
          (storage/close-store! a)
          (let [b (storage/open-store {:path dir-b :create? true})]
            (try
              (is (:ok? (storage/restore-state! b export {:migration-id "r1"
                                                          :quiescent? true})))
              (let [v (storage/read-session-view b {:partition "session/s1"
                                                    :session-id "s1"})]
                (is (= {:title "t"} (select-keys (:session v) [:title])))
                (is (= ["hello"] (mapv :text (:messages v))))
                (is (= ["t1"] (mapv :storage/id (:tasks (:board v)))))
                (is (= ["o1"] (mapv :obligation/id (:open-obligations v)))))
              (is (= ["sha256-x"]
                     (mapv :id (:refs (storage/manage-blob-refs! b {:partition "session/s1"
                                                                     :op :list})))))
              (is (:ok? (storage/verify-integrity b)))
              (finally (storage/close-store! b))))))
      (finally
        (support/delete-tree! dir-a)
        (support/delete-tree! dir-b)))))

(deftest restore-requires-complete-export-and-quiescence-test
  (support/with-temp-store [store {}]
    (storage/commit-migration! store {:migration-id "seed" :partition "p"
                                      :put [{:kind :task :id "t1" :doc {}}]})
    (let [partial (storage/export-state store {:partition "p"})]
      (is (false? (:complete? partial)))
      (is (= :STORAGE_INVALID_EXPORT
             (error-code #(storage/restore-state! store partial
                                                   {:migration-id "r"
                                                    :quiescent? true})))))
    (is (= :STORAGE_RESTORE_REQUIRES_QUIESCENCE
           (error-code #(storage/restore-state! store
                                                 (storage/export-state store {})
                                                 {:migration-id "r"}))))))

(deftest close-refuses-new-work-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (storage/close-store! store)
        (storage/close-store! store)
        (is (not (storage/store-open? store)))
        (is (= :STORAGE_CLOSED
               (error-code #(storage/commit-migration!
                             store {:migration-id "x" :partition "p"}))))
        (is (= :STORAGE_CLOSED
               (error-code #(storage/read-session-view store {:partition "p"})))))
      (finally (support/delete-tree! dir)))))

(deftest ordered-close-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})
            drained (atom false)
            result (storage/open-close-store!
                    store
                    {:drain-fn (fn [] (reset! drained true) true)
                     :handoff-fn (fn [_] [{:obligation/id "o1"}])})]
        (is @drained)
        (is (:closed? result))
        (is (= [{:obligation/id "o1"}] (:handed-off result))))
      (finally (support/delete-tree! dir)))))

(deftest validation-test
  (support/with-temp-store [store {}]
    (is (= :STORAGE_INVALID_MIGRATION
           (error-code #(storage/commit-migration!
                         store {:migration-id "x" :partition "p" :typo true}))))
    (is (= :STORAGE_INVALID_MIGRATION
           (error-code #(storage/commit-migration!
                         store {:migration-id "x" :partition "p"
                                :put [{:kind :task :id "t" :doc {}}]
                                :delete [{:kind :task :id "t"}]}))))
    (is (= :STORAGE_INVALID_MIGRATION
           (error-code #(storage/commit-migration!
                         store {:migration-id "x" :partition "p"
                                :put [{:kind :obligation :id "o" :doc {}}]}))))
    (is (= :STORAGE_INVALID_MIGRATION
           (error-code #(storage/commit-migration!
                         store {:migration-id "x" :partition "p"
                                :blob-refs {:add [{:size 1}]}})))))
  (support/with-temp-store [small {:max-doc-chars 20}]
    (is (= :STORAGE_INVALID_MIGRATION
           (error-code #(storage/commit-migration!
                         small {:migration-id "x" :partition "p"
                                :put [{:kind :task :id "t"
                                       :doc {:text (apply str (repeat 50 "x"))}}]}))))))

(deftest read-only-store-test
  (let [dir (support/temp-dir "hc-store-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (storage/commit-migration! store {:migration-id "x" :partition "p"
                                          :put [{:kind :task :id "t" :doc {:a 1}}]})
        (storage/close-store! store))
      (let [store (storage/open-store {:path dir :read-only? true})]
        (is (= 1 (:version (storage/read-session-view store {:partition "p"}))))
        (is (= :STORAGE_INVALID_OPTIONS
               (error-code #(storage/commit-migration!
                             store {:migration-id "y" :partition "p"}))))
        (storage/close-store! store))
      (finally (support/delete-tree! dir)))))

;; Property: a random sequence of put/delete migrations is replayed into a
;; model; the final view must match the model.
(def ^:private op-gen
  (gen/tuple (gen/elements [:task :action :actor])
             (gen/choose 0 5)
             (gen/elements [:put :delete])))

(defspec random-migration-model 30
  (prop/for-all [ops (gen/vector op-gen 1 30)]
    (let [dir (support/temp-dir "hc-store-prop-")]
      (try
        (let [store (storage/open-store {:path dir :create? true :durability :nosync})
              model (atom {})]
          (doseq [[idx [kind id op]] (map-indexed vector ops)]
            (storage/commit-migration!
             store
             {:migration-id (str "m-" idx)
              :partition "p"
              :put (when (= op :put)
                     [{:kind kind :id (str kind "-" id) :doc {:n idx}}])
              :delete (when (= op :delete)
                        [{:kind kind :id (str kind "-" id)}])})
            (if (= op :put)
              (swap! model assoc [kind (str kind "-" id)] {:n idx})
              (swap! model dissoc [kind (str kind "-" id)])))
          (let [view (storage/read-session-view store {:partition "p"})
                actual (into {}
                             (for [[kind docs] (merge {:task (:tasks (:board view))
                                                       :action (:actions (:board view))
                                                       :actor (:actors (:board view))}
                                                      (:records view))
                                   doc docs]
                               [[kind (:storage/id doc)] (select-keys doc [:n])]))]
            (storage/close-store! store)
            (= @model actual)))
        (finally (support/delete-tree! dir))))))


(deftest all-partitions-obligations-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "sess"
      :partition "session/s1"
      :session-id "s1"
      :obligations {:put [{:obligation/id "sess" :open? true :phase :ready}]}})
    (storage/commit-migration!
     store
     {:migration-id "part"
      :partition "session/s1"
      :obligations {:put [{:obligation/id "part" :open? true :phase :report-pending}]}})
    (storage/commit-migration!
     store
     {:migration-id "master"
      :partition "master"
      :obligations {:put [{:obligation/id "master" :open? true :phase :running}]}})
    (let [all          (storage/list-open-obligations store {:all-partitions? true})
          session      (storage/list-open-obligations store {:partition "session/s1"
                                                            :session-id "s1"})
          session-only (storage/list-open-obligations store {:partition "session/s1"
                                                            :session-id "s1"
                                                            :include-partition-scope? false})
          master       (storage/list-open-obligations store {:partition "master"})]
      (is (= #{"sess" "part" "master"}
             (set (map :obligation/id (:obligations all)))))
      (is (= #{"session/s1" "master"} (set (:partitions all))))
      (is (= #{"sess" "part"}
             (set (map :obligation/id (:obligations session)))))
      (is (= #{"sess"}
             (set (map :obligation/id (:obligations session-only)))))
      (is (= #{"master"}
             (set (map :obligation/id (:obligations master)))))
      (is (= "master" (:storage/partition (first (:obligations master))))))))

(deftest integrity-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "seed"
      :partition "p"
      :session-id "s"
      :put [{:kind :task :id "t" :doc {:a 1}}]
      :obligations {:put [{:obligation/id "o" :open? true :phase :ready}]}})
    (let [result (storage/verify-integrity store)]
      (is (:ok? result))
      (is (= 1 (:documents result)))
      (is (= 1 (:obligations result)))
      (is (pos? (:receipts result))))
    (engine/transact!
     (:engine store)
     [[:put (keys/doc-key "p" "s" :task "bad")
       {:storage/kind :wrong
        :storage/id "bad"
        :storage/partition "p"
        :storage/session-id "s"
        :storage/version 1
        :storage/updated-at "x"
        :doc {}}]])
    (let [data (try
                 (storage/verify-integrity store)
                 nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :STORAGE_CORRUPT (:error data)))
      (is (seq (:errors data))))))


(deftest restore-keeps-partition-version-and-global-monotonic-test
  (let [dir-a (support/temp-dir "hc-store-a-")
        dir-b (support/temp-dir "hc-store-b-")]
    (try
      (let [a (storage/open-store {:path dir-a :create? true})]
        (dotimes [i 3]
          (storage/commit-migration!
           a
           {:migration-id (str "m-" i)
            :partition "p"
            :put [{:kind :task :id (str "t-" i) :doc {:i i}}]}))
        (let [export (storage/export-state a {})]
          (is (= 3 (get-in export [:partitions "p" :version])))
          (storage/close-store! a)
          (let [b (storage/open-store {:path dir-b :create? true})]
            (try
              (storage/restore-state! b export {:migration-id "restore" :quiescent? true})
              (is (= 3 (:version (storage/read-session-view b {:partition "p"}))))
              (is (>= (:version (storage/store-info b)) 3))
              (is (:ok? (storage/verify-integrity b)))
              (finally (storage/close-store! b))))))
      (finally
        (support/delete-tree! dir-a)
        (support/delete-tree! dir-b)))))

(deftest ordered-close-refuses-an-incomplete-drain-test
  (support/with-temp-store [store {}]
    (let [error (try
                  (storage/open-close-store!
                   store {:drain-fn (fn [] false)})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? error))
      (is (= :STORAGE_DRAIN_INCOMPLETE (storage/error-code error)))
      (is (storage/store-open? store))
      (is (:ok? (storage/commit-migration!
                 store {:migration-id "after-drain"
                        :partition "p"
                        :put [{:kind :task :id "t1" :doc {:ok true}}]}))))))

(deftest due-only-includes-retry-at-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "retry-due"
      :partition "p"
      :session-id "s"
      :obligations
      {:put [{:obligation/id "retry-1"
              :obligation/kind :retry-call
              :open? true
              :phase :retry-wait
              :retry-at 100}]}})
    (let [result (storage/list-open-obligations
                  store {:partition "p" :session-id "s"
                         :now-ms 200 :due-only? true})]
      (is (= ["retry-1"] (mapv :obligation/id (:obligations result)))))))

(deftest erase-session-derived-removes-session-scoped-state-test
  (support/with-temp-store [store {}]
    (let [session-id "tmp-1"
          owner-partition (str "session/" session-id)]
      (storage/commit-migration!
       store
       {:migration-id "seed-session"
        :partition "sessions"
        :put [{:kind :session-record :id session-id
               :doc {:session-id session-id :temporary? true}}
              {:kind :session-index :id "sessions"
               :doc {:id "sessions" :ids [session-id "keep"]}}
              {:kind :session-command :id "cmd-1"
               :doc {:id "cmd-1" :session-id session-id}}]})
      (storage/commit-migration!
       store
       {:migration-id "seed-owner"
        :partition owner-partition
        :session-id session-id
        :put [{:kind :owner-state :id session-id
               :doc {:session-id session-id
                     :messages {"m1" {:message-id "m1"}}
                     :temporary? true}}
              {:kind :task :id "t1" :doc {:task-id "t1"}}]
        :obligations {:put [{:obligation/id "ob-1"
                             :open? true :phase :ready}]}
        :blob-refs {:add [{:id "sha256-session" :size 1}]}})
      (storage/commit-migration!
       store
       {:migration-id "seed-diagnostics"
        :partition "diagnostics"
        :put [{:kind :diagnostics-request :id "r1"
               :doc {:request-id "r1" :session-id session-id}}
              {:kind :diagnostics-request :id "r2"
               :doc {:request-id "r2" :session-id "keep"}}]})
      (storage/commit-migration!
       store
       {:migration-id "seed-materials"
        :partition "materials"
        :put [{:kind :materials-memory :id "mem-1"
               :doc {:id "mem-1" :status :candidate
                     :source {:session-id session-id}}}
              {:kind :materials-worldbook-session :id session-id
               :doc {:session-id session-id :turn 1}}
              {:kind :materials-instruction-session :id session-id
               :doc {:session-id session-id :card-ids []}}
              {:kind :materials-memory :id "mem-keep"
               :doc {:id "mem-keep" :status :active
                     :source {:session-id "keep"}}}]})
      (let [result (storage/erase-session-derived! store session-id)
            owner (storage/export-state store {:partition owner-partition})
            diagnostics (storage/export-state store {:partition "diagnostics"})
            materials (storage/export-state store {:partition "materials"})
            sessions (storage/export-state store {:partition "sessions"})]
        (is (:ok? result))
        (is (empty? (get-in owner [:partitions owner-partition :docs])))
        (is (empty? (get-in owner [:partitions owner-partition :obligations])))
        (is (empty? (get-in owner [:partitions owner-partition :blob-refs])))
        (is (= ["r2"] (mapv :id (get-in diagnostics [:partitions "diagnostics" :docs]))))
        (is (= ["mem-keep"]
               (mapv :id (get-in materials [:partitions "materials" :docs]))))
        (is (= ["keep"]
               (get-in sessions [:partitions "sessions" :docs 1 :doc :ids])))
        (is (some #(= session-id (:id %))
                  (get-in sessions [:partitions "sessions" :docs])))
        (is (some #(= :external-services (:scope %)) (:external-effects result)))))))
