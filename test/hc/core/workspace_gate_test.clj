(ns hc.core.workspace-gate-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.board :as gate-board]
            [hc.core.workspace-gate.commit :as commit]
            [hc.core.workspace-gate.error :as gate-error]
            [hc.core.workspace-gate.prepare :as prep]
            [hc.core.workspace-gate.stamp :as stamp]
            [hc.core.workspace-gate.test-support :as ts])
  (:import (java.nio.file Files LinkOption Path Paths)
           (java.nio.file.attribute BasicFileAttributes FileAttribute FileTime)
           (java.time Instant)))

(defn- commit-batch
  ([root batch] (commit-batch root batch {} {}))
  ([root batch wg-opts request]
   (let [w (wg/workspace-gate (merge {:root root :board nil
                                      :allow-boardless? true}
                                     wg-opts))]
     (wg/commit! w (merge {:request-id (str (java.util.UUID/randomUUID))
                           :actor-id "workspace-test"
                           :batch batch}
                          request)))))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest read-stamp-shape-and-precision
  (ts/with-root [root]
    (ts/write! root "f" "abc")
    (let [r (wg/read-file root "f")]
      (is (= :OBSERVED (:status r)))
      (is (= :mtime+size (:observation-basis r)))
      (is (not (contains? r :bytes)))
      (is (= "abc" (:text r)))
      (is (= 3 (:size r)))
      (is (= 3 (get-in r [:stamp :size])))
      (is (integer? (get-in r [:stamp :mtime-sec])))
      (is (integer? (get-in r [:stamp :mtime-nsec])))
      (is (<= 0 (get-in r [:stamp :mtime-nsec]) 999999999))
      (is (false? (:changed-during-read? r)))
      (is (not (contains? (:stamp r) :atime))))
    (let [^Path p (Paths/get (ts/file root "f") (make-array String 0))]
      (Files/setLastModifiedTime p (FileTime/from (Instant/ofEpochSecond 123 456789123)))
      (let [actual (.toInstant (Files/getLastModifiedTime
                                p
                                (make-array LinkOption 0)))]
        (is (= {:mtime-sec (.getEpochSecond actual)
                :mtime-nsec (.getNano actual)
                :size 3}
               (wg/current-stamp root "f")))
        (is (<= 0 (.getNano actual) 999999999))))
    (is (= {:exists false} (wg/stat-path root "missing")))
    (Files/createDirectory (Paths/get (ts/file root "d") (make-array String 0))
                           (make-array FileAttribute 0))
    (is (= ["d/" "f"] (wg/list-dir root "")))))

(deftest stamp-conflict-then-recovery
  (ts/with-root [root]
    (ts/write! root "f" "old")
    (let [s (wg/session root)]
      (wg/cat s "f")
      (let [batch (wg/snapshot s)]
        (ts/write! root "f" "external")
        (let [r (commit-batch root batch)]
          (is (= :FILE_CONFLICT (:status r)))
          (is (= :stamp-mismatch (:code r)))
          (is (= :mtime+size (:observation-basis r)))
          (is (= "external" (ts/read! root "f")))
          (is (false? (:file-changed? r)))
          (is (vector? (:expected r)))
          (is (map? (:actual r)))))
      (let [s2 (wg/session root)]
        (wg/cat s2 "f")
        (wg/write s2 "f" "ours")
        (let [r (commit-batch root (wg/snapshot s2))]
          (is (wg/applied? r))
          (is (= :not-claimed (:content-equality r)))
          (is (= "ours" (ts/read! root "f"))))))))

(deftest read-only-dependency-conflict
  (ts/with-root [root]
    (ts/write! root "api" "v1")
    (ts/write! root "client" "call v1")
    (let [s (wg/session root)]
      (wg/cat s "api")
      (wg/cat s "client")
      (wg/write s "client" "call v2")
      (let [batch (wg/snapshot s)]
        (ts/write! root "api" "v2")
        (let [r (commit-batch root batch)]
          (is (= :FILE_CONFLICT (:status r)))
          (is (clojure.string/ends-with? (:path r) "api"))
          (is (= "call v1" (ts/read! root "client"))))))))

(deftest empty-changes-still-verify-file-facts
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (let [s (wg/session root)]
      (wg/cat s "f")
      (let [batch (wg/snapshot s)
            r (commit-batch root batch)]
        (is (wg/applied? r))
        (is (false? (:file-changed? r)))
        (is (zero? (:op-count r)))
        (ts/write! root "f" "external")
        (let [r2 (commit-batch root batch)]
          (is (= :FILE_CONFLICT (:status r2)))))))
  (ts/with-root [root]
    (let [r (commit-batch root (wg/new-batch root))]
      (is (= :UNSUPPORTED_OPERATION (:status r)))
      (is (= :no-file-facts (:code r))))))

(deftest read-set-is-verified-even-without-checks
  (ts/with-root [root]
    (ts/write! root "f" "old")
    (let [read (wg/read-file root "f")
          batch (assoc (wg/new-batch root)
                       :read-set {(:path read)
                                  [{:path (:path read)
                                    :kind :file
                                    :stamp (:stamp read)}]}
                       :checks {})]
      (ts/write! root "f" "external")
      (let [r (commit-batch root batch)]
        (is (= :FILE_CONFLICT (:status r)))
        (is (= :stamp-mismatch (:code r)))
        (is (= "external" (ts/read! root "f")))))))

(deftest structure-conflict-when-file-becomes-directory
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (let [s (wg/session root)]
      (wg/cat s "f")
      (wg/write s "f" "y")
      (let [p (Paths/get (ts/file root "f") (make-array String 0))]
        (Files/delete p)
        (Files/createDirectory p (make-array FileAttribute 0))
        (let [r (commit-batch root (wg/snapshot s))]
          (is (= :STRUCTURE_CONFLICT (:status r)))
          (is (= :expected-file (:code r)))
          (is (Files/isDirectory p (make-array LinkOption 0))))))))

(deftest same-mtime-and-size-is-an-observation-boundary
  (ts/with-root [root]
    (ts/write! root "f" "AAAA")
    (let [r (wg/read-file root "f")
          s (:stamp r)
          ^Path p (Paths/get (ts/file root "f") (make-array String 0))]
      (ts/write! root "f" "BBBB")
      (Files/setLastModifiedTime p (FileTime/from (Instant/ofEpochSecond
                                                   (:mtime-sec s) (:mtime-nsec s))))
      (is (= s (wg/current-stamp root "f"))
          "the fast predicate is restored to the old observation")
      (let [batch (assoc (wg/new-batch root)
                         :read-set {(:path r) [{:path (:path r) :kind :file :stamp s}]}
                         :checks {(:path r) {:path (:path r) :expect :file :stamps #{s}}})
            result (commit-batch root batch)]
        (is (wg/applied? result))
        (is (= "BBBB" (ts/read! root "f"))
            "the contract does not claim content equality")))))

(deftest atime-is-never-part-of-the-stamp
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (let [r (wg/read-file root "f")
          ^Path p (Paths/get (ts/file root "f") (make-array String 0))
          atime (fn []
                  (try
                    (Files/getAttribute p "lastAccessTime"
                                        (make-array LinkOption 0))
                    (catch Exception _ nil)))
          before (atime)]
      (try
        (Files/setAttribute p "lastAccessTime"
                            (FileTime/from (Instant/now))
                            (make-array LinkOption 0))
        (catch Exception _ nil))
      (is (not (contains? (:stamp r) :atime)))
      (let [after (atime)]
        (when (and before after (not= before after))
          (is (not (contains? (:stamp (wg/read-file root "f")) :atime))
              "an actual atime change does not enter the stamp")))
      (let [s (wg/session root)]
        (wg/cat s "f")
        (wg/write s "f" "y")
        (let [result (commit-batch root (wg/snapshot s))]
          (is (wg/applied? result))
          (is (= "y" (ts/read! root "f"))))))))

(deftest changed-during-read-is-not-a-valid-basis
  (ts/with-root [root]
    (ts/write! root "f" "abc")
    (with-redefs [stamp/current-stamp
                  (fn [_] {:mtime-sec 1 :mtime-nsec 1 :size 3})]
      (let [r (wg/read-file root "f")]
        (is (= :FILE_CONFLICT (:status r)))
        (is (true? (:changed-during-read? r))))
      (let [s (wg/session root)]
        (wg/cat s "f")
        (let [r (commit-batch root (wg/snapshot s))]
          (is (= :FILE_CONFLICT (:status r)))
          (is (= :changed-during-read (:code r))))))))

(deftest repeated-read-cannot-hide-an-older-basis
  (ts/with-root [root]
    (ts/write! root "f" "old")
    (let [s (wg/session root)]
      (wg/cat s "f")
      (ts/write! root "f" "external")
      (wg/cat s "f")
      (wg/write s "f" "new")
      (let [r (commit-batch root (wg/snapshot s))]
        (is (= :FILE_CONFLICT (:status r)))
        (is (= "external" (ts/read! root "f")))))))

(deftest preview-sees-conflicts-but-is-not-a-permit
  (ts/with-root [root]
    (ts/write! root "f" "old")
    (let [s (wg/session root)]
      (wg/cat s "f")
      (wg/write s "f" "new")
      (let [batch (wg/snapshot s)]
        (ts/write! root "f" "external")
        (let [p (wg/preview batch)]
          (is (= :FILE_CONFLICT (:status p)))
          (is (not (wg/preview? p))))))))

(deftest reading-does-not-wait-for-the-gate
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (let [g (wg/write-gate)
          permit (wg/acquire-write-gate! g :holder)]
      (try
        (let [result (deref (future (wg/read-file root "f")) 1000 ::timeout)]
          (is (not= ::timeout result))
          (is (= "x" (:text result))))
        (finally
          (wg/release-write-gate! permit))))))

(deftest write-gate-is-fifo-and-mutually-exclusive
  (let [g (wg/write-gate)
        order (atom [])
        holder (wg/acquire-write-gate! g :main)
        start-one (fn [id expected-queued]
                    (let [t (Thread. (fn []
                                       (let [permit (wg/acquire-write-gate! g id)]
                                         (swap! order conj id)
                                         (Thread/sleep 15)
                                         (wg/release-write-gate! permit))))]
                      (.start t)
                      (is (wait-until #(>= (wg/write-gate-queued g) expected-queued) 2000)
                          (str "thread " id " should be queued"))
                      t))]
    (is (nil? (wg/try-acquire-write-gate! g :probe 30)))
    (is (= :main (wg/write-gate-owner g)))
    (let [t1 (start-one :a 1)
          t2 (start-one :b 2)
          t3 (start-one :c 3)]
      (wg/release-write-gate! holder)
      (doseq [t [t1 t2 t3]] (.join ^Thread t 5000))
      (is (= [:a :b :c] @order)))
    (is (false? (wg/write-gate-held? g)))
    (let [p (wg/try-acquire-write-gate! g :next 100)]
      (is (some? p))
      (when p (wg/release-write-gate! p)))))

(deftest write-gate-releases-on-exception
  (let [g (wg/write-gate)]
    (is (thrown? RuntimeException
                 (wg/with-write-gate [permit g :x]
                   (throw (RuntimeException. "boom")))))
    (is (false? (wg/write-gate-held? g)))
    (let [p (wg/try-acquire-write-gate! g :next 100)]
      (is (some? p))
      (when p (wg/release-write-gate! p)))))

(deftest write-gate-rejects-double-release
  (let [g (wg/write-gate)
        permit (wg/acquire-write-gate! g :once)]
    (wg/release-write-gate! permit)
    (is (false? (wg/write-gate-held? g)))
    (is (thrown? IllegalStateException
                 (wg/release-write-gate! permit)))
    (is (false? (wg/write-gate-held? g)))))

(deftest actor-and-claim-eligibility
  (ts/with-root [root]
    (let [board (wg/memory-board)
          w (wg/workspace-gate {:root root :board board})
          s (wg/session root)]
      (wg/set-actor! board "a" false)
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "actor"
                             :actor-id "a"
                             :batch (wg/snapshot s)})]
        (is (= :ACTOR_INACTIVE (:status r)))
        (is (false? (ts/exists? root "f"))))
      (wg/set-actor! board "a" true)
      (wg/set-claim! board "c" false)
      (let [r (wg/commit! w {:request-id "claim"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :CLAIM_CHANGED (:status r)))
        (is (false? (ts/exists? root "f")))))))

(deftest publish-conflict-retries-only-publication
  (ts/with-root [root]
    (let [board (wg/memory-board)
          _actor (wg/set-actor! board "a" true)
          _claim (wg/set-claim! board "c" true)
          _fail (wg/fail-publishes! board 2)
          apply-count (atom 0)
          w (wg/workspace-gate
             {:root root
              :board board
              :apply-fn (fn [prepared opts]
                          (swap! apply-count inc)
                          (wg/apply-plan! prepared opts))})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "publish"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (wg/applied? r))
        (is (true? (:published? r)))
        (is (map? (:summary r)))
        (is (= 3 (:publish-attempts r)))
        (is (= 1 @apply-count) "file apply happened exactly once")
        (is (= "x" (ts/read! root "f")))
        (is (= :finished (:phase (wg/board-action board "publish"))))))))

(deftest hard-failure-leaves-the-partial-site
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/write s "a" "A")
      (wg/write s "b" "B")
      (wg/write s "c" "C")
      (let [r (commit-batch root (wg/snapshot s)
                            {}
                            {:request-id "hard"
                             :on-op (fn [index _op]
                                      (when (= 1 index)
                                        (throw (ex-info "injected" {:injected true}))))})]
        (is (= :HARD_FAILURE (:status r)))
        (is (= :unknown (:outcome r)))
        (is (= :unknown (:file-changed? r)))
        (is (= :mtime+size (:observation-basis r)))
        (is (= :unknown (:content-equality r)))
        (is (= 1 (count (:applied-ops r))))
        (is (= 1 (:failed-index r)))
        (is (map? (:exception r)))
        (is (= "clojure.lang.ExceptionInfo" (:class (:exception r))))
        (is (not-any? #(contains? % :bytes) (:applied-ops r)))
        (is (not-any? #(contains? % :bytes) (:remaining-ops r)))
        (is (= "A" (ts/read! root "a")))
        (is (false? (ts/exists? root "b")))
        (is (false? (ts/exists? root "c")))))))

(deftest request-replay-is-idempotent-and-payload-checked
  (ts/with-root [root]
    (let [apply-count (atom 0)
          w (wg/workspace-gate
             {:root root
              :board nil
              :allow-boardless? true
              :apply-fn (fn [prepared opts]
                          (swap! apply-count inc)
                          (wg/apply-plan! prepared opts))})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [request {:request-id "same"
                     :actor-id "a"
                     :batch (wg/snapshot s)}
            r1 (wg/commit! w request)
            r2 (wg/commit! w request)]
        (is (wg/applied? r1))
        (is (= r1 r2))
        (is (= 1 @apply-count))
        (let [r3 (wg/commit! w (assoc request :actor-id "b"))]
          (is (= :CALL_REPLAY_MISMATCH (:status r3)))
          (is (= 1 @apply-count)))))))

(deftest concurrent-duplicate-request-applies-once
  (ts/with-root [root]
    (let [apply-count (atom 0)
          w (wg/workspace-gate
             {:root root
              :board nil
              :allow-boardless? true
              :apply-fn (fn [prepared opts]
                          (swap! apply-count inc)
                          (wg/apply-plan! prepared opts))})
          s (wg/session root)
          _ (wg/write s "f" "x")
          request {:request-id "concurrent"
                   :actor-id "a"
                   :batch (wg/snapshot s)}
          start (promise)
          f1 (future @start (wg/commit! w request))
          f2 (future @start (wg/commit! w request))]
      (deliver start true)
      (let [r1 (deref f1 5000 ::timeout)
            r2 (deref f2 5000 ::timeout)]
        (is (not= ::timeout r1))
        (is (not= ::timeout r2))
        (is (wg/applied? r1))
        (is (= r1 r2))
        (is (= 1 @apply-count))))))

(deftest request-stop-leaves-the-queue-without-writing
  (ts/with-root [root]
    (let [g (wg/write-gate)
          w (wg/workspace-gate {:root root :gate g :board nil
                                :allow-boardless? true})
          holder (wg/acquire-write-gate! g :holder)
          s (wg/session root)
          _ (wg/write s "f" "x")
          f (future (wg/commit! w {:request-id "stopped"
                                   :actor-id "a"
                                   :batch (wg/snapshot s)}))]
      (try
        (is (wait-until #(>= (wg/write-gate-queued g) 1) 2000))
        (is (= :waiting-gate (:phase (wg/action w "stopped"))))
        (wg/request-stop! w "stopped")
        (let [r (deref f 3000 ::timeout)]
          (is (not= ::timeout r))
          (is (= :WAITING_FOR_WRITE_GATE (:status r)))
          (is (true? (:cancelled? r)))
          (is (= :cancelled (:phase (wg/action w "stopped"))))
          (is (false? (ts/exists? root "f"))))
        (finally
          (wg/release-write-gate! holder))))))

(deftest commit-waits-for-w-without-timeout-transfer
  (ts/with-root [root]
    (let [g (wg/write-gate)
          w (wg/workspace-gate {:root root :gate g :board nil
                                :allow-boardless? true})
          holder (wg/acquire-write-gate! g :shell)
          s (wg/session root)
          _ (wg/write s "f" "x")
          f (future (wg/commit! w {:request-id "wait"
                                   :actor-id "a"
                                   :batch (wg/snapshot s)}))]
      (try
        (is (wait-until #(>= (wg/write-gate-queued g) 1) 2000))
        (is (= :waiting-gate (:phase (wg/action w "wait"))))
        (is (= :shell (wg/write-gate-owner g))
            "W is not transferred while a commit waits")
        (is (false? (ts/exists? root "f")))
        (wg/release-write-gate! holder)
        (let [r (deref f 3000 ::timeout)]
          (is (not= ::timeout r))
          (is (wg/applied? r))
          (is (= "x" (ts/read! root "f"))))
        (finally
          (when (= :shell (wg/write-gate-owner g))
            (wg/release-write-gate! holder)))))))

(deftest stop-after-applying-completes-the-batch
  (ts/with-root [root]
    (let [holder (atom nil)
          w (wg/workspace-gate
             {:root root
              :board nil
              :allow-boardless? true
              :apply-fn (fn [prepared opts]
                          (let [r (wg/apply-plan! prepared opts)]
                            (wg/request-stop! @holder "stop-mid")
                            r))})
          _ (reset! holder w)
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "stop-mid"
                             :actor-id "a"
                             :batch (wg/snapshot s)})]
        (is (wg/applied? r))
        (is (true? (:cancel-after-apply? r)))
        (is (= "x" (ts/read! root "f")))))))

(deftest create-does-not-replace-a-file-that-appears-after-the-check
  (ts/with-root [root]
    (let [s (wg/session root)
          _ (wg/write s "new" "ours")
          batch (wg/snapshot s)
          w (wg/workspace-gate {:root root :board nil
                                :allow-boardless? true})
          r (wg/commit! w {:request-id "create-race"
                           :actor-id "a"
                           :batch batch
                           :on-op (fn [index op]
                                    (when (and (zero? index)
                                               (= :write (:op op))
                                               (:create? op))
                                      (ts/write! root "new" "external")))})]
      (is (= :HARD_FAILURE (:status r)))
      (is (= 0 (count (:applied-ops r))))
      (is (= "external" (ts/read! root "new"))))))

(deftest external-write-after-the-check-is-best-effort
  (ts/with-root [root]
    (ts/write! root "f" "old")
    (let [s (wg/session root)]
      (wg/cat s "f")
      (wg/write s "f" "ours")
      (let [w (wg/workspace-gate {:root root :board nil
                                  :allow-boardless? true})
            r (wg/commit! w {:request-id "external-after-check"
                             :actor-id "a"
                             :batch (wg/snapshot s)
                             :on-op (fn [_ _]
                                      (ts/write! root "f" "external"))})]
        (is (wg/applied? r))
        (is (= "ours" (ts/read! root "f")))
        (is (= :mtime+size (:observation-basis r)))
        (is (= :not-claimed (:content-equality r)))))))

(deftest broken-symlink-create-is-not-replaced
  (ts/with-root [root]
    (let [link (Paths/get (ts/file root "link") (make-array String 0))
          target (Paths/get (ts/file root "target") (make-array String 0))]
      (Files/createSymbolicLink link target (make-array FileAttribute 0))
      (let [s (wg/session root)]
        (wg/write s "link" "ours")
        (let [r (commit-batch root (wg/snapshot s))]
          (is (= :HARD_FAILURE (:status r)))
          (is (Files/isSymbolicLink link))
          (is (false? (ts/exists? root "target"))))))))

(deftest publish-retry-after-exhaustion-does-not-reapply
  (ts/with-root [root]
    (let [board (wg/memory-board)
          _actor (wg/set-actor! board "a" true)
          _claim (wg/set-claim! board "c" true)
          _fail (wg/fail-publishes! board 10)
          apply-count (atom 0)
          w (wg/workspace-gate
             {:root root
              :board board
              :publish-retries 2
              :apply-fn (fn [prepared opts]
                          (swap! apply-count inc)
                          (wg/apply-plan! prepared opts))})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r1 (wg/commit! w {:request-id "retry"
                              :actor-id "a"
                              :claim-id "c"
                              :batch (wg/snapshot s)})]
        (is (wg/applied? r1))
        (is (= "retry" (:request-id r1)))
        (is (false? (:published? r1)))
        (is (= 2 (:publish-attempts r1)))
        (is (= 1 @apply-count))
        (is (:publish-pending? (wg/action w "retry")))
        (is (= :publish-pending (:phase (wg/action w "retry"))))
        (is (= :applying (:phase (wg/board-action board "retry"))))
        (wg/fail-publishes! board 0)
        (let [r2 (wg/retry-publish! w "retry")]
          (is (wg/applied? r2))
          (is (true? (:published? r2)))
          (is (= 1 @apply-count) "publication retry never reapplies files")
          (is (= :finished (:phase (wg/board-action board "retry"))))
          (is (= :finished (:phase (wg/action w "retry"))))
          (is (false? (:publish-pending? (wg/action w "retry")))))))))

(deftest commit-requires-an-actor-id
  (ts/with-root [root]
    (let [s (wg/session root)
          _ (wg/write s "f" "x")
          w (wg/workspace-gate {:root root :board nil
                                :allow-boardless? true})
          r (wg/commit! w {:request-id "no-actor"
                           :batch (wg/snapshot s)})]
      (is (= :INVALID_ARGUMENT (:status r)))
      (is (= :missing-actor-id (:code r)))
      (is (false? (ts/exists? root "f"))))))

(deftest request-fingerprint-includes-batch-limits
  (ts/with-root [root]
    (let [s (wg/session root)
          _ (wg/write s "f" "x")
          batch (wg/snapshot s)
          w (wg/workspace-gate {:root root :board nil
                                :allow-boardless? true})
          r1 (wg/commit! w {:request-id "limits"
                            :actor-id "a"
                            :batch batch})
          r2 (wg/commit! w {:request-id "limits"
                            :actor-id "a"
                            :batch (assoc batch :limits
                                          (assoc (:limits batch)
                                                 :max-file-bytes 1))})]
      (is (wg/applied? r1))
      (is (= :CALL_REPLAY_MISMATCH (:status r2)))
      (is (= "x" (ts/read! root "f"))))))

(deftest atomic-write-leaves-no-temporary-files
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/write s "f" "content")
      (let [r (commit-batch root (wg/snapshot s))]
        (is (wg/applied? r))
        (is (= "content" (ts/read! root "f")))
        (is (= [] (vec (filter #(clojure.string/starts-with? (.getName ^java.io.File %)
                                                              ".hc-write-")
                               (.listFiles (java.io.File. ^String root))))))))))

(deftest prepared-is-bound-to-its-gate-permit
  (ts/with-root [root]
    (let [s (wg/session root)
          g (wg/write-gate)]
      (wg/write s "f" "x")
      (let [batch (wg/snapshot s)
            token (wg/acquire-write-gate! g :prepared-test)
            prepared (prep/prepare-plan batch {:gate-token token})]
        (is (prep/prepared? prepared))
        (is (= :UNSUPPORTED_OPERATION
               (:status (wg/apply-plan! prepared {:gate-token (Object.)}))))
        (is (false? (ts/exists? root "f")))
        (let [unbound (prep/prepare-plan batch)]
          (is (prep/prepared? unbound))
          (is (= :UNSUPPORTED_OPERATION
                 (:status (wg/apply-plan! unbound {:gate-token token})))))
        (let [r (wg/apply-plan! prepared {:gate-token token})]
          (is (wg/applied? r))
          (is (= "x" (ts/read! root "f"))))
        (wg/release-write-gate! token)
        (let [next-token (wg/acquire-write-gate! g :prepared-test)]
          (try
            (let [r (wg/apply-plan! prepared {:gate-token token})]
              (is (= :UNSUPPORTED_OPERATION (:status r)))
              (is (= :write-gate-not-held (:code r))))
            (finally
              (wg/release-write-gate! next-token))))))))

(deftest read-set-verifier-seam
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (let [stamp (wg/current-stamp root "f")
          verify (wg/read-set-verifier root)]
      (is (:ok? (verify [{:path "f" :stamp stamp}])))
      (is (:ok? (verify {:path "f" :stamp stamp})))
      (is (:ok? (verify {"f" stamp})))
      (is (:ok? (verify [])))
      (is (= :mtime+size (:observation-basis (verify []))))
      (let [r (verify [{:path "f" :stamp stamp :unstable? true}])]
        (is (false? (:ok? r)))
        (is (= :changed-during-read (-> r :conflicts first :reason))))
      (let [r (verify [{:path "f" :stamp stamp :changed-during-read? true}])]
        (is (false? (:ok? r)))
        (is (= :changed-during-read (-> r :conflicts first :reason))))
      (ts/write! root "f" "external")
      (let [r (verify [{:path "f" :stamp stamp}])]
        (is (false? (:ok? r)))
        (is (= :FILE_CONFLICT (:reason r)))
        (is (= :stamp-mismatch (-> r :conflicts first :reason))))
      (is (:ok? (verify [{:path "missing" :kind :missing}])))
      (ts/write! root "missing" "x")
      (let [r (verify [{:path "missing" :kind :missing}])]
        (is (false? (:ok? r)))
        (is (= :STRUCTURE_CONFLICT (:reason r)))
        (is (true? (-> r :conflicts first :structure?)))
        (is (= :kind-mismatch (-> r :conflicts first :reason)))))))

(deftest batch-root-must-match-the-gate-root
  (ts/with-root [root]
    (ts/with-root [other]
      (let [s (wg/session other)]
        (wg/write s "f" "x")
        (let [w (wg/workspace-gate {:root root :board nil
                                    :allow-boardless? true})
              r (wg/commit! w {:request-id "root-mismatch"
                               :actor-id "a"
                               :batch (wg/snapshot s)})]
          (is (= :STRUCTURE_CONFLICT (:status r)))
          (is (= :root-mismatch (:code r)))
          (is (false? (ts/exists? other "f"))))))))

(deftest preview-cannot-be-committed
  (ts/with-root [root]
    (let [s (wg/session root)
          _ (wg/write s "f" "x")
          p (wg/preview (wg/snapshot s))
          w (wg/workspace-gate {:root root :board nil
                                :allow-boardless? true})
          r (wg/commit! w {:request-id "preview"
                           :actor-id "a"
                           :batch p})]
      (is (= :INVALID_ARGUMENT (:status r)))
      (is (= :invalid-batch (:code r)))
      (is (false? (ts/exists? root "f"))))))

(deftest boardless-gate-requires-an-explicit-test-flag
  (ts/with-root [root]
    (is (thrown? IllegalArgumentException
                 (wg/workspace-gate {:root root :board nil})))
    (let [w (wg/workspace-gate {:root root :board nil
                                :allow-boardless? true})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "boardless"
                             :actor-id "a"
                             :batch (wg/snapshot s)})]
        (is (wg/applied? r))
        (is (= "x" (ts/read! root "f")))))))

(deftest memory-board-rejects-unknown-actor-and-missing-claim
  (ts/with-root [root]
    (let [board (wg/memory-board)
          w (wg/workspace-gate {:root root :board board})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "unknown-actor"
                             :actor-id "ghost"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :ACTOR_INACTIVE (:status r)))
        (is (false? (ts/exists? root "f"))))
      (wg/set-actor! board "a" true)
      (let [r (wg/commit! w {:request-id "missing-claim"
                             :actor-id "a"
                             :batch (wg/snapshot s)})]
        (is (= :CLAIM_CHANGED (:status r)))
        (is (= :missing-claim (:code r)))
        (is (false? (ts/exists? root "f")))))))

(deftest stop-registered-before-applying-is-honored
  (ts/with-root [root]
    (let [wg-ref (atom nil)
          board (reify gate-board/BoardAdapter
                  (check-eligibility! [_ request]
                    (wg/request-stop! @wg-ref (:request-id request))
                    nil)
                  (begin-apply! [_ _ _]
                    (throw (AssertionError. "begin-apply must not run")))
                  (publish-applied! [_ _ _] {:published true})
                  (publish-failure! [_ _ _] {:published true}))
          w (wg/workspace-gate {:root root :board board})
          _ (reset! wg-ref w)
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "stop-before"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :WAITING_FOR_WRITE_GATE (:status r)))
        (is (true? (:cancelled? r)))
        (is (false? (ts/exists? root "f")))))))

(deftest request-stop-after-begin-apply-reports-too-late
  (ts/with-root [root]
    (let [board (reify gate-board/BoardAdapter
                  (check-eligibility! [_ _] nil)
                  (begin-apply! [_ request _]
                    {:request-id (:request-id request)})
                  (publish-applied! [_ _ _] {:published true})
                  (publish-failure! [_ _ _] {:published true}))
          wg-ref (atom nil)
          stop-result (atom nil)
          w (wg/workspace-gate
             {:root root
              :board board
              :apply-fn (fn [prepared opts]
                          (reset! stop-result
                                  (wg/request-stop! @wg-ref "stop-after"))
                          (wg/apply-plan! prepared opts))})
          _ (reset! wg-ref w)
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "stop-after"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :STOP_AFTER_APPLYING (:status @stop-result)))
        (is (wg/applied? r))
        (is (true? (:cancel-after-apply? r)))
        (is (= "x" (ts/read! root "f")))))))

(deftest begin-apply-refusal-uses-final-check-phase
  (ts/with-root [root]
    (let [board (reify gate-board/BoardAdapter
                  (check-eligibility! [_ _] nil)
                  (begin-apply! [_ _ _]
                    (gate-error/fail! :CLAIM_CHANGED :claim-changed
                                      "claim changed"))
                  (publish-applied! [_ _ _] {:published true})
                  (publish-failure! [_ _ _] {:published true}))
          w (wg/workspace-gate {:root root :board board})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "begin-refusal"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :CLAIM_CHANGED (:status r)))
        (is (= :final-check (:phase r)))
        (is (false? (:file-changed? r)))
        (is (false? (ts/exists? root "f")))))))

(deftest apply-exception-publishes-hard-failure
  (ts/with-root [root]
    (let [board (wg/memory-board)
          _actor (wg/set-actor! board "a" true)
          _claim (wg/set-claim! board "c" true)
          w (wg/workspace-gate
             {:root root
              :board board
              :apply-fn (fn [_ _]
                          (throw (ex-info "apply boom" {})))})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "apply-boom"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :HARD_FAILURE (:status r)))
        (is (true? (:published? r)))
        (is (= :uncertain (:phase (wg/board-action board "apply-boom"))))
        (is (false? (ts/exists? root "f")))))))

(deftest hard-failure-publication-retries
  (ts/with-root [root]
    (let [board (wg/memory-board)
          _actor (wg/set-actor! board "a" true)
          _claim (wg/set-claim! board "c" true)
          _fail (wg/fail-failure-publishes! board 1)
          apply-count (atom 0)
          w (wg/workspace-gate
             {:root root
              :board board
              :publish-retries 2
              :apply-fn (fn [_ _]
                          (swap! apply-count inc)
                          {:status :HARD_FAILURE
                           :outcome :unknown
                           :file-changed? :unknown
                           :message "injected"})})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "failure-publish"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :HARD_FAILURE (:status r)))
        (is (true? (:published? r)))
        (is (= :uncertain (:phase r)))
        (is (= 2 (:publish-attempts r)))
        (is (= 1 @apply-count))
        (is (= :uncertain
               (:phase (wg/board-action board "failure-publish"))))))))

(deftest hard-failure-publication-retry-after-exhaustion
  (ts/with-root [root]
    (let [board (wg/memory-board)
          _actor (wg/set-actor! board "a" true)
          _claim (wg/set-claim! board "c" true)
          _fail (wg/fail-failure-publishes! board 10)
          apply-count (atom 0)
          w (wg/workspace-gate
             {:root root
              :board board
              :publish-retries 2
              :apply-fn (fn [_ _]
                          (swap! apply-count inc)
                          {:status :HARD_FAILURE
                           :outcome :unknown
                           :file-changed? :unknown
                           :message "injected"})})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r1 (wg/commit! w {:request-id "failure-retry"
                              :actor-id "a"
                              :claim-id "c"
                              :batch (wg/snapshot s)})]
        (is (= :HARD_FAILURE (:status r1)))
        (is (false? (:published? r1)))
        (is (= 2 (:publish-attempts r1)))
        (is (:publish-pending? (wg/action w "failure-retry")))
        (is (= :applying (:phase (wg/board-action board "failure-retry"))))
        (wg/fail-failure-publishes! board 0)
        (let [r2 (wg/retry-publish! w "failure-retry")]
          (is (= :HARD_FAILURE (:status r2)))
          (is (true? (:published? r2)))
          (is (= 1 @apply-count))
          (is (= :uncertain
                 (:phase (wg/board-action board "failure-retry"))))
          (is (= :uncertain (:phase (wg/action w "failure-retry"))))
          (is (false? (:publish-pending? (wg/action w "failure-retry")))))))))

(deftest hand-built-plan-validates-parents
  (ts/with-root [root]
    (let [batch (assoc (wg/new-batch root)
                       :ops [{:op :mkdir :path "a/b"}])
          r (commit-batch root batch)]
      (is (= :PREPARE_REJECTED (:status r)))
      (is (false? (:file-changed? r)))
      (is (false? (ts/exists? root "a"))))
    (ts/write! root "a" "x")
    (let [stamp (wg/current-stamp root "a")
          batch (assoc (wg/new-batch root)
                       :ops [{:op :mv :source "a" :target "missing/b"}]
                       :checks {"a" {:path "a"
                                     :expect :file
                                     :stamps #{stamp}}})
          r (commit-batch root batch)]
      (is (= :PREPARE_REJECTED (:status r)))
      (is (false? (ts/exists? root "missing"))))))

(deftest f01-shell-write-while-queued-conflicts
  (ts/with-root [root]
    (ts/write! root "f" "old")
    (let [g (wg/write-gate)
          w (wg/workspace-gate {:root root :gate g :board nil
                                :allow-boardless? true})
          s (wg/session root)]
      (wg/cat s "f")
      (let [holder (wg/acquire-write-gate! g :shell)
            f (future (wg/commit! w {:request-id "f01"
                                     :actor-id "a"
                                     :batch (wg/snapshot s)}))]
        (try
          (is (wait-until #(>= (wg/write-gate-queued g) 1) 2000))
          (ts/write! root "f" "external")
          (let [read (deref (future (wg/read-file root "f")) 1000 ::timeout)]
            (is (not= ::timeout read))
            (is (= "external" (:text read))
                "reading does not wait for W and observes the shell result"))
          (wg/release-write-gate! holder)
          (let [r (deref f 3000 ::timeout)]
            (is (not= ::timeout r))
            (is (= :FILE_CONFLICT (:status r)))
            (is (= "external" (ts/read! root "f"))))
          (finally
            (when (= :shell (wg/write-gate-owner g))
              (wg/release-write-gate! holder))))))))

(deftest f03-third-file-failure-keeps-disk-untouched
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/write s "a" "A")
      (wg/write s "b" "B")
      (let [batch (update (wg/snapshot s) :ops conj
                          {:op :write
                           :path (ts/file root "missing/c")
                           :bytes (.getBytes "C" java.nio.charset.StandardCharsets/UTF_8)
                           :create? true})
            r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :inconsistent-plan (:code r)))
        (is (= :preparing (:phase r)))
        (is (false? (ts/exists? root "a")))
        (is (false? (ts/exists? root "b")))
        (is (false? (ts/exists? root "missing/c")))))))

(deftest f05-stop-after-first-op-completes-batch
  (ts/with-root [root]
    (let [w (wg/workspace-gate {:root root :board nil
                                :allow-boardless? true})
          s (wg/session root)]
      (wg/write s "a" "A")
      (wg/write s "b" "B")
      (let [r (wg/commit! w {:request-id "f05"
                             :actor-id "a"
                             :batch (wg/snapshot s)
                             :on-op (fn [index _]
                                      (when (= index 1)
                                        (wg/request-stop! w "f05")))})]
        (is (wg/applied? r))
        (is (true? (:cancel-after-apply? r)))
        (is (= "A" (ts/read! root "a")))
        (is (= "B" (ts/read! root "b")))))))

(deftest f02-read-only-dependency-structure-change-conflicts
  (ts/with-root [root]
    (ts/write! root "api" "v1")
    (ts/write! root "client" "call v1")
    (let [s (wg/session root)]
      (wg/cat s "api")
      (wg/cat s "client")
      (wg/write s "client" "call v2")
      (let [batch (wg/snapshot s)
            ^Path api (Paths/get (ts/file root "api") (make-array String 0))]
        (Files/delete api)
        (Files/createDirectory api (make-array FileAttribute 0))
        (let [r (commit-batch root batch)]
          (is (= :STRUCTURE_CONFLICT (:status r)))
          (is (= :expected-file (:code r)))
          (is (= "call v1" (ts/read! root "client"))))))))

(deftest retry-publish-during-applying-reports-in-progress
  (ts/with-root [root]
    (let [started (promise)
          release (promise)
          w (wg/workspace-gate
             {:root root
              :board nil
              :allow-boardless? true
              :apply-fn (fn [prepared opts]
                          (deliver started true)
                          @release
                          (wg/apply-plan! prepared opts))})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [f (future (wg/commit! w {:request-id "retry-applying"
                                     :actor-id "a"
                                     :batch (wg/snapshot s)}))]
        (is (true? (deref started 2000 false)))
        (let [r (wg/retry-publish! w "retry-applying")]
          (is (= :ACTION_IN_PROGRESS (:status r)))
          (is (= :in-progress (:outcome r)))
          (is (= :unknown (:file-changed? r)))
          (is (false? (wg/rejection? r))))
        (deliver release true)
        (let [r (deref f 3000 ::timeout)]
          (is (not= ::timeout r))
          (is (wg/applied? r))
          (is (= "x" (ts/read! root "f"))))))))

(deftest rejection-classification-covers-ordinary-results
  (is (wg/rejection? {:status :INVALID_ARGUMENT
                      :outcome :not-written
                      :file-changed? false}))
  (is (wg/rejection? {:status :CALL_REPLAY_MISMATCH
                      :outcome :not-written
                      :file-changed? false}))
  (is (wg/rejection? {:status :UNKNOWN_REQUEST
                      :outcome :not-written
                      :file-changed? false}))
  (is (not (wg/rejection? {:status :ACTION_IN_PROGRESS
                           :outcome :in-progress
                           :file-changed? :unknown})))
  (is (not (wg/rejection? {:status :HARD_FAILURE
                           :outcome :unknown
                           :file-changed? :unknown}))))

(deftest board-ok-false-refuses-apply
  (ts/with-root [root]
    (let [board (reify gate-board/BoardAdapter
                  (check-eligibility! [_ _] nil)
                  (begin-apply! [_ _ _] {:ok? false :message "no write-ahead"})
                  (publish-applied! [_ _ _] {:published true})
                  (publish-failure! [_ _ _] {:published true}))
          w (wg/workspace-gate {:root root :board board})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "board-false"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :BOARD_EVENT_PERSIST_FAILED (:status r)))
        (is (false? (:file-changed? r)))
        (is (false? (ts/exists? root "f")))))))

(deftest apply-fn-ordinary-rejection-is-not-published
  (ts/with-root [root]
    (let [w (wg/workspace-gate
             {:root root
              :board nil
              :allow-boardless? true
              :apply-fn (fn [_ _]
                          (gate-error/failure :UNSUPPORTED_OPERATION
                                              :stale-prepared
                                              "injected"))})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "apply-reject"
                             :actor-id "a"
                             :batch (wg/snapshot s)})]
        (is (= :UNSUPPORTED_OPERATION (:status r)))
        (is (= :stale-prepared (:code r)))
        (is (false? (ts/exists? root "f")))
        (is (not (contains? r :published?)))))))

(deftest apply-fn-nil-is-hard-failure
  (ts/with-root [root]
    (let [w (wg/workspace-gate {:root root
                                :board nil
                                :allow-boardless? true
                                :apply-fn (fn [_ _] nil)})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "apply-nil"
                             :actor-id "a"
                             :batch (wg/snapshot s)})]
        (is (= :HARD_FAILURE (:status r)))
        (is (= :unknown (:outcome r)))
        (is (= :uncertain (:phase r)))
        (is (false? (ts/exists? root "f")))))))

(deftest read-limit-rejects-growth-without-reading-whole
  (ts/with-root [root]
    (ts/write! root "one" "x")
    (ts/write! root "big" "12345")
    (let [^Path one (Paths/get (ts/file root "one") (make-array String 0))
          attrs (Files/readAttributes one
                                      BasicFileAttributes
                                      (make-array LinkOption 0))]
      (with-redefs [stamp/attrs (constantly attrs)]
        (let [data (ts/thrown-data #(wg/read-file root "big"
                                                  {:max-file-bytes 4}))]
          (is (= :LIMIT_EXCEEDED (:status data)))
          (is (= :file-too-large (:code data))))))))

(deftest read-io-error-is-classified
  (ts/with-root [root]
    (ts/write! root "one" "x")
    (Files/createDirectory (Paths/get (ts/file root "d") (make-array String 0))
                           (make-array FileAttribute 0))
    (let [^Path one (Paths/get (ts/file root "one") (make-array String 0))
          attrs (Files/readAttributes one
                                      BasicFileAttributes
                                      (make-array LinkOption 0))]
      (with-redefs [stamp/attrs (constantly attrs)]
        (let [data (ts/thrown-data #(wg/read-file root "d"
                                                  {:max-file-bytes 10}))]
          (is (= :FILE_CONFLICT (:status data)))
          (is (= :changed-during-read (:code data))))))))

(deftest memory-board-publish-requires-a-begun-action
  (let [board (wg/memory-board)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (gate-board/publish-applied! board
                                              {:request-id "x"}
                                              {})))
    (wg/set-actor! board "a" false)
    (let [data (try
                 (gate-board/begin-apply! board
                                          {:request-id "y"
                                           :actor-id "a"
                                           :claim-id "c"}
                                          {})
                 (catch clojure.lang.ExceptionInfo e
                   (ex-data e)))]
      (is (= :ACTOR_INACTIVE (:status data)))
      (is (nil? (wg/board-action board "y"))))))

(deftest finish-request-adds-cancel-marker-under-stop
  (ts/with-root [root]
    (let [w (wg/workspace-gate {:root root :board nil
                                :allow-boardless? true})
          p (promise)]
      (swap! (:registry w) assoc "r" {:status :pending :phase :applying})
      (swap! (:stops w) conj "r")
      (let [r (#'commit/finish-request! w "r" p
                                        {:status :APPLIED
                                         :published? true
                                         :file-changed? true})]
        (is (true? (:cancel-after-apply? r)))
        (is (= r @p))
        (is (= :finished (:phase (wg/action w "r"))))
        (is (= :STOP_ALREADY_FINISHED
               (:status (wg/request-stop! w "r"))))))))

(deftest checks-only-empty-write-set-is-verified
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (let [stamp (wg/current-stamp root "f")
          batch (assoc (wg/new-batch root)
                       :checks {(ts/file root "f")
                                {:path (ts/file root "f")
                                 :expect :file
                                 :stamps #{stamp}}})]
      (let [r (commit-batch root batch)]
        (is (wg/applied? r))
        (is (false? (:file-changed? r)))
        (is (zero? (:op-count r)))))))

(deftest throwable-data-is-edn-safe
  (let [p (Paths/get (ts/file (System/getProperty "java.io.tmpdir") "x")
                     (make-array String 0))
        data (gate-error/throwable->data
              (ex-info "boom" {:path p :bytes (byte-array [1 2])}))]
    (is (string? (get-in data [:data :path])))
    (is (map? (get-in data [:data :bytes])))
    (is (not (clojure.string/includes? (pr-str data) "#object")))))

(deftest apply-catch-all-publishes-hard-failure
  (ts/with-root [root]
    (let [board (wg/memory-board)
          _actor (wg/set-actor! board "a" true)
          _claim (wg/set-claim! board "c" true)
          w (wg/workspace-gate {:root root :board board})
          s (wg/session root)]
      (wg/write s "f" "x")
      (with-redefs-fn {#'commit/publish-applied
                       (fn [& _]
                         (throw (RuntimeException. "publish boom")))}
        (fn []
          (let [r (wg/commit! w {:request-id "catch-all"
                                 :actor-id "a"
                                 :claim-id "c"
                                 :batch (wg/snapshot s)})]
            (is (= :HARD_FAILURE (:status r)))
            (is (= :uncertain (:phase r)))
            (is (= :uncertain
                   (:phase (wg/board-action board "catch-all"))))
            (is (= "x" (ts/read! root "f")))))))))

(deftest board-empty-permit-refuses-apply
  (ts/with-root [root]
    (let [board (reify gate-board/BoardAdapter
                  (check-eligibility! [_ _] nil)
                  (begin-apply! [_ _ _] {})
                  (publish-applied! [_ _ _] {:published true})
                  (publish-failure! [_ _ _] {:published true}))
          w (wg/workspace-gate {:root root :board board})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "board-empty-permit"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :invalid-board-permit (:code r)))
        (is (false? (ts/exists? root "f")))))))

(deftest board-returned-eligibility-status-refuses-apply
  (ts/with-root [root]
    (let [board (reify gate-board/BoardAdapter
                  (check-eligibility! [_ _] {:status :ACTOR_INACTIVE
                                              :code :actor-inactive})
                  (begin-apply! [_ _ _] (throw (AssertionError. "begin must not run")))
                  (publish-applied! [_ _ _] {:published true})
                  (publish-failure! [_ _ _] {:published true}))
          w (wg/workspace-gate {:root root :board board})
          s (wg/session root)]
      (wg/write s "f" "x")
      (let [r (wg/commit! w {:request-id "board-eligibility-status"
                             :actor-id "a"
                             :claim-id "c"
                             :batch (wg/snapshot s)})]
        (is (= :ACTOR_INACTIVE (:status r)))
        (is (= :final-check (:phase r)))
        (is (false? (ts/exists? root "f")))))))
