(ns hc.core.workspace-gate-property-test
  "Property tests for the protected-commit ordinary-rejection boundary.

  These tests only use the public workspace-gate facade and the shared
  filesystem test support.  They do not modify src/ and they never touch a
  user project: every case owns a temporary root."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.test-support :as ts]))

(defn- commit-batch
  [root batch]
  (let [w (wg/workspace-gate {:root root :board nil
                              :allow-boardless? true})]
    (wg/commit! w {:request-id (str (java.util.UUID/randomUUID))
                   :actor-id "workspace-property-test"
                   :batch batch})))

(defspec target-change-rejects-and-keeps-external-bytes 80
  (prop/for-all [old gen/string-alphanumeric
                 suffix (gen/vector gen/char-alphanumeric 1 12)]
    (let [new (str old (apply str suffix))]
      (ts/with-root [root]
        (ts/write! root "f" old)
        (let [s (wg/session root)]
          (wg/cat s "f")
          (wg/write s "f" "ours")
          (let [batch (wg/snapshot s)]
            (ts/write! root "f" new)
            (let [r (commit-batch root batch)]
              (and (= :FILE_CONFLICT (:status r))
                   (= new (ts/read! root "f"))))))))))

(defspec read-only-dependency-change-rejects-and-keeps-target 80
  (prop/for-all [api-old gen/string-alphanumeric
                 api-suffix (gen/vector gen/char-alphanumeric 1 8)
                 client-old gen/string-alphanumeric]
    (let [api-new (str api-old (apply str api-suffix))]
      (ts/with-root [root]
        (ts/write! root "api" api-old)
        (ts/write! root "client" client-old)
        (let [s (wg/session root)]
          (wg/cat s "api")
          (wg/cat s "client")
          (wg/write s "client" "ours")
          (let [batch (wg/snapshot s)]
            (ts/write! root "api" api-new)
            (let [r (commit-batch root batch)]
              (and (= :FILE_CONFLICT (:status r))
                   (= client-old (ts/read! root "client"))))))))))

(defspec read-set-only-change-rejects-and-keeps-disk 80
  (prop/for-all [old gen/string-alphanumeric
                 suffix (gen/vector gen/char-alphanumeric 1 8)]
    (let [new (str old (apply str suffix))]
      (ts/with-root [root]
        (ts/write! root "f" old)
        (let [s (wg/session root)]
          (wg/cat s "f")
          (let [batch (wg/snapshot s)]
            (ts/write! root "f" new)
            (let [r (commit-batch root batch)]
              (and (= :FILE_CONFLICT (:status r))
                   (= new (ts/read! root "f"))))))))))

;; ---------------------------------------------------------------------------
;; F04: batch order and missing-parent rejection

(defspec move-then-write-sees-the-moved-content 50
  (prop/for-all [old gen/string-alphanumeric
                 new gen/string-alphanumeric]
    (ts/with-root [root]
      (ts/write! root "old" old)
      (let [s (wg/session root)]
        (wg/mv s "old" "new")
        (let [seen (wg/cat s "new")]
          (wg/write s "new" new)
          (let [r (commit-batch root (wg/snapshot s))]
            (and (= old seen)
                 (wg/applied? r)
                 (= new (ts/read! root "new"))
                 (false? (ts/exists? root "old")))))))))

(defspec delete-directory-then-write-is-rejected-without-disk-change 50
  (prop/for-all [content gen/string-alphanumeric]
    (ts/with-root [root]
      (let [s (wg/session root)]
        (wg/mkdir s "d")
        (wg/rm s "d")
        (let [data (try
                     (wg/write s "d/f" content)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (and (= :STRUCTURE_CONFLICT (:status data))
               (= :missing-parent (:code data))
               (false? (ts/exists? root "d"))))))))

;; ---------------------------------------------------------------------------
;; F05: stop during :applying still finishes the fixed batch

(defspec stop-during-applying-finishes-the-batch 50
  (prop/for-all [n (gen/choose 2 6)
                 contents (gen/vector gen/string-alphanumeric 6)
                 raw-stop-at (gen/choose 0 5)]
    (let [stop-at (min raw-stop-at (dec n))]
      (ts/with-root [root]
        (let [w (wg/workspace-gate {:root root :board nil
                                    :allow-boardless? true})
              s (wg/session root)
              values (take n contents)]
          (doseq [[i content] (map-indexed vector values)]
            (wg/write s (str "f" i) content))
          (let [request-id (str (java.util.UUID/randomUUID))
                r (wg/commit! w {:request-id request-id
                                 :actor-id "workspace-property-test"
                                 :batch (wg/snapshot s)
                                 :on-op (fn [index _]
                                          (when (= index stop-at)
                                            (wg/request-stop! w request-id)))})]
            (and (wg/applied? r)
                 (true? (:cancel-after-apply? r))
                 (every? (fn [[i content]]
                           (= content (ts/read! root (str "f" i))))
                         (map-indexed vector values)))))))))

;; ---------------------------------------------------------------------------
;; F06: publication retry never replays the real apply

(defspec publication-retry-applies-once 50
  (prop/for-all [failures (gen/choose 0 5)
                 content gen/string-alphanumeric]
    (ts/with-root [root]
      (let [board (wg/memory-board)
            _actor (wg/set-actor! board "a" true)
            _claim (wg/set-claim! board "c" true)
            _fail (wg/fail-publishes! board failures)
            apply-count (atom 0)
            w (wg/workspace-gate
               {:root root
                :board board
                :apply-fn (fn [prepared opts]
                            (swap! apply-count inc)
                            (wg/apply-plan! prepared opts))})
            s (wg/session root)]
        (wg/write s "f" content)
        (let [r (wg/commit! w {:request-id (str (java.util.UUID/randomUUID))
                               :actor-id "a"
                               :claim-id "c"
                               :batch (wg/snapshot s)})]
          (and (wg/applied? r)
               (= 1 @apply-count)
               (= content (ts/read! root "f"))
               (if (:published? r)
                 (= (inc failures) (:publish-attempts r))
                 (and (= 5 (:publish-attempts r))
                      (false? (:published? r))))))))))

;; ---------------------------------------------------------------------------
;; F07: reading is lock-free while another owner holds W

(defspec reading-does-not-wait-for-write-gate 50
  (prop/for-all [old gen/string-alphanumeric
                 new gen/string-alphanumeric]
    (ts/with-root [root]
      (ts/write! root "f" old)
      (let [g (wg/write-gate)
            holder (wg/acquire-write-gate! g :shell)]
        (try
          (ts/write! root "f" new)
          (let [read (deref (future (wg/read-file root "f")) 1000 ::timeout)]
            (and (not= ::timeout read)
                 (= :OBSERVED (:status read))
                 (= new (:text read))
                 (true? (wg/write-gate-held? g))))
          (finally
            (wg/release-write-gate! holder)))))))

;; ---------------------------------------------------------------------------
;; F08: same (mtime, size) is an observation boundary, not content equality

(def ^:private same-size-distinct-pair
  (gen/bind (gen/choose 1 16)
            (fn [n]
              (gen/such-that (fn [[a b]] (not= a b))
                             (gen/tuple (gen/vector gen/char-alphanumeric n)
                                        (gen/vector gen/char-alphanumeric n))
                             100))))

(defspec same-mtime-and-size-accepts-without-content-equality 50
  (prop/for-all [[old-chars new-chars] same-size-distinct-pair]
    (let [old (apply str old-chars)
          new (apply str new-chars)]
      (ts/with-root [root]
        (ts/write! root "f" old)
        (let [r (wg/read-file root "f")
              s (:stamp r)
              ^java.nio.file.Path p (java.nio.file.Paths/get
                                     (ts/file root "f")
                                     (make-array String 0))]
          (ts/write! root "f" new)
          (java.nio.file.Files/setLastModifiedTime
           p
           (java.nio.file.attribute.FileTime/from
            (java.time.Instant/ofEpochSecond (:mtime-sec s) (:mtime-nsec s))))
          (let [batch (assoc (wg/new-batch root)
                             :read-set {(:path r) [{:path (:path r)
                                                    :kind :file
                                                    :stamp s}]}
                             :checks {(:path r) {:path (:path r)
                                                 :expect :file
                                                 :stamps #{s}}})
                result (commit-batch root batch)]
            (and (= s (wg/current-stamp root "f"))
                 (wg/applied? result)
                 (= :not-claimed (:content-equality result))
                 (= new (ts/read! root "f")))))))))

;; ---------------------------------------------------------------------------
;; F09: atime never participates in the fast predicate

(defspec atime-change-does-not-conflict 50
  (prop/for-all [content gen/string-alphanumeric
                 atime-sec (gen/choose 0 2000000000)]
    (ts/with-root [root]
      (ts/write! root "f" content)
      (let [r (wg/read-file root "f")
            s (:stamp r)
            ^java.nio.file.Path p (java.nio.file.Paths/get
                                   (ts/file root "f")
                                   (make-array String 0))]
        (try
          (java.nio.file.Files/setAttribute
           p "lastAccessTime"
           (java.nio.file.attribute.FileTime/from
            (java.time.Instant/ofEpochSecond (long atime-sec)))
           (make-array java.nio.file.LinkOption 0))
          (catch Exception _ nil))
        (let [s2 (wg/read-file root "f")
              session (wg/session root)]
          (wg/cat session "f")
          (wg/write session "f" (str content "-after"))
          (let [result (commit-batch root (wg/snapshot session))]
            (and (= s (:stamp s2))
                 (not (contains? (:stamp s2) :atime))
                 (wg/applied? result)
                 (= (str content "-after") (ts/read! root "f")))))))))

;; ---------------------------------------------------------------------------
;; F03: a later missing path rejects the whole batch before any write

(defspec later-missing-path-keeps-the-whole-batch-off-disk 50
  (prop/for-all [contents (gen/vector gen/string-alphanumeric 1 4)]
    (ts/with-root [root]
      (let [s (wg/session root)]
        (doseq [[i content] (map-indexed vector contents)]
          (wg/write s (str "f" i) content))
        (let [batch (update (wg/snapshot s)
                            :ops conj
                            {:op :write
                             :path (ts/file root "missing/c")
                             :bytes (.getBytes "late" java.nio.charset.StandardCharsets/UTF_8)
                             :create? true})
              r (commit-batch root batch)]
          (and (= :PREPARE_REJECTED (:status r))
               (every? (fn [i] (false? (ts/exists? root (str "f" i))))
                       (range (count contents)))))))))

(defspec file-as-parent-is-structure-conflict-without-disk-change 50
  (prop/for-all [content gen/string-alphanumeric]
    (ts/with-root [root]
      (ts/write! root "p" content)
      (let [s (wg/session root)
            data (try
                   (wg/write s "p/child" "x")
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (and (= :STRUCTURE_CONFLICT (:status data))
             (= :ancestor-not-directory (:code data))
             (= content (ts/read! root "p")))))))
