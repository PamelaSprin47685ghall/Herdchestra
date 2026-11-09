(ns hc.server.storage-test
  "Contract tests for the embedded current-state store.

  The tests cover the five §7.1 responsibilities, the atomic commit and read
  barrier, idempotency, the non-destructive open rules, export/restore and the
  ordered close.  They use real Datalevin environments on the real filesystem
  in a temporary directory; nothing is mocked."

  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.storage :as storage]
            [hc.server.storage.engine.datalevin :as engine]
            [hc.server.storage.keys :as keys]
            [hc.server.storage.layout :as layout]
            [hc.server.storage.support :as support]
            [hc.server.storage.view :as view]))

(defn- error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (storage/error-code e))))

;; ---------------------------------------------------------------------------
;; Committed-kind audit
;;
;; Every document kind a production namespace commits must be registered in
;; `view/known-document-kinds`, otherwise a narrowed `session-view` silently
;; hides it.  This audit is deliberately conservative: it scans every source
;; file that calls `commit-migration!`, removes strings and comments, extracts
;; `:kind` literals, follows `(def ...kind... :keyword)` constants (the
;; variable-built puts), and parses the diagnostics `kinds` map whose values
;; are passed as `:kind` variables.

(def ^:private kind-literal-pattern
  #":kind\s+(:[A-Za-z0-9_][A-Za-z0-9_./?!<>+*=-]*)")

(def ^:private kind-definition-pattern
  #"(?i)\(def\s+(?:\^[^\s()]+\s+)*[^\s()]*kind[^\s()]*\s+(:[A-Za-z0-9_][A-Za-z0-9_./?!<>+*=-]*)")

(def ^:private kinds-map-pair-pattern
  #"(:[A-Za-z0-9_-]+)\s+(:[A-Za-z0-9_][A-Za-z0-9_./?!<>+*=-]*)")

(defn- code-only
  "Return TEXT with Clojure string literals and `;` comments removed.

  The audit must inspect executable forms only: a `:kind` named in a docstring
  or a comment is documentation, not a committed document."
  [^String text]
  (let [n (.length text)
        ^StringBuilder out (StringBuilder.)]
    (loop [i 0 in-string? false in-comment? false]
      (if (>= i n)
        (.toString out)
        (let [c (.charAt text i)]
          (cond
            in-comment?
            (if (= c \newline)
              (do (.append out c) (recur (inc i) false false))
              (recur (inc i) false true))

            in-string?
            (if (= c \\)
              (recur (+ i 2) true false)
              (if (= c \")
                (recur (inc i) false false)
                (recur (inc i) true false)))

            (= c \;)
            (recur (inc i) false true)

            (= c \")
            (recur (inc i) true false)

            :else
            (do (.append out c) (recur (inc i) false false))))))))

(defn- balanced-braces
  "Return the balanced `{...}` block starting at START in TEXT."
  [^String text start]
  (let [n (.length text)]
    (loop [i start depth 0]
      (if (>= i n)
        (subs text start n)
        (let [c (.charAt text i)]
          (cond
            (= c \{) (recur (inc i) (inc depth))
            (= c \})
            (let [next-depth (dec depth)]
              (if (zero? next-depth)
                (subs text start (inc i))
                (recur (inc i) next-depth)))
            :else (recur (inc i) depth)))))))

(defn- kinds-map-values
  "Return the values of `(def kinds {...})`, whose values become `:kind` variables."
  [^String code]
  (or
   (when-let [def-index (str/index-of code "(def kinds")]
     (when-let [brace (str/index-of code "{" def-index)]
       (->> (re-seq kinds-map-pair-pattern (balanced-braces code brace))
            (keep #(nth % 2 nil))
            (map #(keyword (subs % 1)))
            set)))
   #{}))

(defn- committed-kind-candidates
  "Return every kind literal or traced kind constant a migration can commit."
  []
  (let [root (io/file "src")]
    (when-not (.isDirectory ^java.io.File root)
      (throw (ex-info "the committed-kind audit needs the source tree"
                      {:path (str root)})))
    (reduce
     (fn [kinds ^java.io.File file]
       (let [raw (slurp file)]
         (if (str/includes? raw "commit-migration!")
           (let [code (code-only raw)]
             (into kinds
                   (concat
                    (map (comp #(keyword (subs % 1)) second)
                         (re-seq kind-literal-pattern code))
                    (map (comp #(keyword (subs % 1)) second)
                         (re-seq kind-definition-pattern code))
                    (kinds-map-values code))))
           kinds)))
     #{}
     (->> (file-seq root)
          (filter (fn [^java.io.File f] (.isFile f)))
          (filter (fn [^java.io.File f]
                    (boolean (re-find #"\.clj[c]?$" (.getName f)))))))))

(def ^:private exempt-committed-kinds
  "Kinds the conservative audit sees in migration-writing files that are not
  current-state document kinds.  Each entry states why no `session-view` can
  return it.  Keep this list short and explicit; it is a review surface, not a
  second registry."
  {:file
   "artifact descriptor :inputs/:media field kind, embedded in a document"

   :tool
   "artifact :producer field kind, embedded in a document"

   :log
   "artifact request kind for a generated log file; storage writes an :artifact document"

   :managed-blob-store
   "artifact :save-location field kind, embedded in a document"

   :id
   "keyword selection [:kind :id] and artifact input reference kind, not a document kind"

   :role-bindings
   "backup export item kind, materialized as the :app-config document"

   :instruction-card
   "backup export item kind, materialized as :materials-instruction-card"

   :instruction-session
   "backup export item kind, materialized as :materials-instruction-session"

   :worldbook-entry
   "backup export item kind, materialized as :materials-worldbook-entry"

   :worldbook-session
   "backup export item kind, materialized as :materials-worldbook-session"})

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

(deftest lightweight-commit-return-skips-the-partition-view-test
  (support/with-temp-store [store {}]
    (is (= 1 (:version (storage/commit-migration!
                        store
                        {:migration-id "seed" :partition "p"
                         :put [{:kind :task :id "t1" :doc {}}]}))))
    (let [light (storage/commit-migration!
                 store
                 {:migration-id "light" :partition "p"
                  :lightweight-return? true
                  :put [{:kind :task :id "t2" :doc {}}]})
          replay (storage/commit-migration!
                  store
                  {:migration-id "light" :partition "p"
                   :lightweight-return? true
                   :put [{:kind :task :id "t2" :doc {}}]})
          scoped (storage/commit-migration!
                  store
                  {:migration-id "scoped" :partition "p"
                   :include-partition-scope? false
                   :put [{:kind :task :id "t3" :doc {}}]})
          full (storage/commit-migration!
                store
                {:migration-id "full" :partition "p"
                 :put [{:kind :task :id "t4" :doc {}}]})]
      (is (:ok? light))
      (is (= 2 (:version light)))
      (is (not (contains? light :view)))
      (is (:replayed? replay))
      (is (= 2 (:version replay)))
      (is (not (contains? replay :view)))
      (is (= 3 (:version scoped)))
      (is (not (contains? scoped :view)))
      (is (= 4 (:version full)))
      (is (= ["t1" "t2" "t3" "t4"]
             (mapv :storage/id (:tasks (:board (:view full)))))))))

(deftest read-session-view-passes-the-scan-options-test
  (support/with-temp-store [store {}]
    (let [seen (atom [])]
      (with-redefs [view/session-view
                    (fn [_engine opts]
                      (swap! seen conj opts)
                      {:version 0})]
        (storage/read-session-view store {:partition "p"
                                          :include-partition-scope? false
                                          :include-unknown-kinds? true})
        (storage/read-session-view store {:partition "p"})
        (storage/read-session-view store "s1"))
      (is (= [{:partition "p"
               :session-id nil
               :include-partition-scope? false
               :include-unknown-kinds? true}
              {:partition "p"
               :session-id nil
               :include-partition-scope? true
               :include-unknown-kinds? false}
              {:partition "master"
               :session-id "s1"
               :include-partition-scope? true
               :include-unknown-kinds? false}]
             @seen)))))

(deftest session-view-narrows-known-kinds-and-honors-the-escape-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "narrow"
      :partition "p"
      :put [{:kind :task :id "t1" :doc {:value 1}}
            {:kind :unlisted-kind :id "u1" :doc {:value 2}}]})
    (let [default (storage/read-session-view store {:partition "p"})
          escaped (storage/read-session-view
                   store
                   {:partition "p" :include-unknown-kinds? true})]
      (is (= ["t1"] (mapv :storage/id (:tasks (:board default)))))
      (is (nil? (get-in default [:records :unlisted-kind])))
      (is (= ["u1"]
             (mapv :storage/id
                   (get-in escaped [:records :unlisted-kind])))))))

(deftest commit-view-honors-include-unknown-kinds-test
  (support/with-temp-store [store {}]
    (let [result (storage/commit-migration!
                  store
                  {:migration-id "unknown-view"
                   :partition "p"
                   :include-unknown-kinds? true
                   :put [{:kind :unlisted-kind :id "u1" :doc {:value 1}}]})]
      (is (= ["u1"]
             (mapv :storage/id
                   (get-in result [:view :records :unlisted-kind])))))))

(deftest commit-migration-passes-the-scan-options-to-its-view-test
  (support/with-temp-store [store {}]
    (let [seen (atom nil)]
      (with-redefs [view/session-view
                    (fn [_engine opts]
                      (reset! seen opts)
                      {:version 1})]
        (is (:ok? (storage/commit-migration!
                   store
                   {:migration-id "scan-options"
                    :partition "p"
                    :include-partition-scope? true
                    :include-unknown-kinds? true
                    :put [{:kind :task :id "t1" :doc {}}]}))))
      (is (= {:partition "p"
              :session-id nil
              :include-partition-scope? true
              :include-unknown-kinds? true}
             @seen)))))

(deftest session-view-include-partition-scope-false-skips-partition-docs-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "partition-task"
      :partition "p"
      :put [{:kind :task :id "part-task" :doc {:value 1}}]})
    (storage/commit-migration!
     store
     {:migration-id "session-state"
      :partition "p"
      :session-id "s1"
      :put [{:kind :owner-state :id "s1" :doc {:value 2}}]})
    (let [narrow (storage/read-session-view
                  store
                  {:partition "p"
                   :session-id "s1"
                   :include-partition-scope? false})
          wide (storage/read-session-view
                store
                {:partition "p" :session-id "s1"})]
      (is (= "s1" (:storage/id (:state narrow))))
      (is (empty? (:tasks (:board narrow))))
      (is (= "s1" (:storage/id (:state wide))))
      (is (= ["part-task"]
             (mapv :storage/id (:tasks (:board wide))))))))

(deftest include-partition-scope-false-skips-the-partition-scan-test
  (support/with-temp-store [store {}]
    (let [scans (atom [])]
      (with-redefs [engine/scan
                    (fn [_engine prefix]
                      (swap! scans conj prefix)
                      [])]
        (view/session-view
         (:engine store)
         {:partition "p"
          :session-id "s1"
          :include-partition-scope? false
          :include-unknown-kinds? true}))
      (is (some #(= (keys/session-doc-prefix "p" "s1") %) @scans))
      (is (not-any? #(= (keys/session-doc-prefix "p" nil) %) @scans))
      (is (not-any? #(= (keys/partition-doc-prefix "p") %) @scans)))))

(deftest known-document-kinds-covers-every-committed-kind-test
  (let [candidates (committed-kind-candidates)
        registered (set/union (set view/known-document-kinds)
                              (set (keys exempt-committed-kinds)))
        missing (sort (set/difference candidates registered))]
    (is (empty? missing)
        (str "known-document-kinds is missing committed document kinds: "
             (pr-str (vec missing))
             ". Add each to hc.server.storage.view/known-document-kinds, or "
             "add a reviewed entry with a reason to exempt-committed-kinds."))))

(deftest search-document-kinds-are-registered-in-session-view-test
  (support/with-temp-store [store {}]
    (is (:ok? (storage/commit-migration!
               store
               {:migration-id "search-kind-registration"
                :partition "p"
                :put [{:kind :search-index :id "meta" :doc {:v 1}}
                      {:kind :search-posting :id "term" :doc {:v 2}}
                      {:kind :search-session
                       :id "s1"
                       :doc {:session-id "s1"}}]})))
    (let [view (storage/read-session-view store {:partition "p"})]
      (is (= ["meta"]
             (mapv :storage/id (get-in view [:records :search-index]))))
      (is (= ["term"]
             (mapv :storage/id (get-in view [:records :search-posting]))))
      (is (= ["s1"]
             (mapv :storage/id (get-in view [:records :search-session])))))))

(def ^:private non-storage-module-roots
  ["src/hc/server/extensions"
   "src/hc/server/speech"
   "src/hc/server/attachments"
   "src/hc/server/model_auth"
   "src/hc/server/browser"
   "src/hc/server/program"
   "src/hc/server/search"])

(deftest non-storage-modules-carry-no-current-state-document-writes-test
  (doseq [root non-storage-module-roots]
    (doseq [^java.io.File file (->> (io/file root)
                                    file-seq
                                    (filter (fn [^java.io.File f]
                                              (.isFile f)))
                                    (filter (fn [^java.io.File f]
                                              (boolean
                                               (re-find #"\.clj[c]?$"
                                                        (.getName f))))))]
      (let [text (slurp file)]
        (is (not (str/includes? text "commit-migration!"))
            (str file " writes current-state storage; register its kinds "
                 "and extend the committed-kind audit"))
        (is (not (str/includes? text "read-session-view"))
            (str file " reads a storage view; register its kinds "
                 "and extend the committed-kind audit"))))))

(deftest restore-drops-rebuilt-session-documents-test
  (support/with-temp-store [store {}]
    (storage/commit-migration!
     store
     {:migration-id "seed-derived"
      :partition "sessions"
      :put [{:kind :session-record
             :id "s1"
             :doc {:session-id "s1" :revision 0}}
            {:kind :session-summary
             :id "s1"
             :doc {:session-id "s1" :revision 0}}
            {:kind :session-ref-index
             :id "sessions"
             :doc {:id "sessions" :counts {"sha256-x" 1}}}]})
    (let [export (storage/export-state store {})]
      (is (= 3 (count (get-in export [:partitions "sessions" :docs]))))
      (is (:ok? (storage/restore-state! store export
                                        {:migration-id "restore-derived"
                                         :quiescent? true})))
      (is (= [:session-record]
             (mapv :kind (get-in (storage/export-state
                                  store {:partition "sessions"})
                                 [:partitions "sessions" :docs])))))))

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
