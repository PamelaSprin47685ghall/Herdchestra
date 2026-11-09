(ns hc.core.workspace-gate.prepare-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.test-support :as ts])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(defn- commit-batch
  ([root batch] (commit-batch root batch {} {}))
  ([root batch wg-opts request]
   (let [w (wg/workspace-gate (merge {:root root :board nil
                                      :allow-boardless? true}
                                     wg-opts))]
     (wg/commit! w (merge {:request-id (str (java.util.UUID/randomUUID))
                           :actor-id "prepare-test"
                           :batch batch}
                          request)))))

(deftest batch-view-is-ordered-and-lock-free
  (ts/with-root [root]
    (ts/write! root "old" "one")
    (let [s (wg/session root)]
      (is (= "one" (wg/cat s "old")))
      (wg/mv s "old" "new")
      (is (= "one" (wg/cat s "new")) "a move is visible to later reads")
      (wg/write s "new" "two")
      (is (false? (ts/exists? root "new")) "the real disk is untouched before commit")
      (let [p (wg/preview (wg/snapshot s))]
        (is (wg/preview? p))
        (is (not (wg/prepared? p))))
      (let [r (commit-batch root (wg/snapshot s))]
        (is (wg/applied? r))
        (is (= "two" (ts/read! root "new")))
        (is (false? (ts/exists? root "old")))))))

(deftest mkdir-write-move-and-list
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/mkdir s "d")
      (wg/write s "d/f" "x")
      (wg/mv s "d" "e")
      (wg/write s "e/g" "y")
      (is (= ["f" "g"] (wg/ls s "e")))
      (let [r (commit-batch root (wg/snapshot s))]
        (is (wg/applied? r))
        (is (= "x" (ts/read! root "e/f")))
        (is (= "y" (ts/read! root "e/g")))
        (is (false? (ts/exists? root "d")))))))

(deftest delete-then-read-is-rejected-and-recreate-works
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (is (= "x" (ts/read! root "f")))
    (let [s (wg/session root)]
      (wg/rm s "f")
      (is (= :PREPARE_REJECTED (:status (ts/thrown-data #(wg/cat s "f")))))
      (is (= :PREPARE_REJECTED (:status (ts/thrown-data #(wg/stat s "f")))))
      (is (= "x" (ts/read! root "f"))
          "the staged delete did not touch the real file")
      (wg/write s "f" "y")
      (let [r (commit-batch root (wg/snapshot s))]
        (is (wg/applied? r))
        (is (= "y" (ts/read! root "f")))))))

(deftest missing-parent-and-deleted-directory-rejected
  (ts/with-root [root]
    (let [s (wg/session root)]
      (is (= :PREPARE_REJECTED (:status (ts/thrown-data #(wg/write s "no/dir/f" "x")))))
      (wg/mkdir s "d")
      (wg/rm s "d")
      ;; F04: the batch deleted the parent and did not recreate it; that is a
      ;; structure condition, not an ordinary prepare refusal.
      (is (= :STRUCTURE_CONFLICT (:status (ts/thrown-data #(wg/write s "d/f" "x"))))))
    (is (= [] (vec (.listFiles (java.io.File. ^String root)))))))

(deftest non-empty-directory-delete-needs-shell
  (ts/with-root [root]
    (Files/createDirectory (java.nio.file.Paths/get (ts/file root "d")
                                                    (make-array String 0))
                           (make-array FileAttribute 0))
    (ts/write! root "d/f" "x")
    (is (= "x" (ts/read! root "d/f")))
    (let [s (wg/session root)
          data (ts/thrown-data #(wg/rm s "d"))]
      (is (= :UNSUPPORTED_OPERATION (:status data)))
      (is (= :non-empty-directory (:code data)))
      (is (ts/exists? root "d/f"))
      (is (= "x" (ts/read! root "d/f"))))))

(deftest limits-are-rejected-before-any-write
  (ts/with-root [root]
    (let [s (wg/session root {:max-file-bytes 4})
          data (ts/thrown-data #(wg/write s "f" "12345"))]
      (is (= :LIMIT_EXCEEDED (:status data)))
      (is (= :file-too-large (:code data))))
    (let [s (wg/session root {:max-batch-bytes 5})]
      (wg/write s "a" "123")
      (let [data (ts/thrown-data #(wg/write s "b" "456"))]
        (is (= :LIMIT_EXCEEDED (:status data)))
        (is (= :batch-too-large (:code data)))))
    (let [s (wg/session root {:max-path-ops 1})]
      (wg/write s "a" "1")
      (let [data (ts/thrown-data #(wg/write s "b" "2"))]
        (is (= :LIMIT_EXCEEDED (:status data)))
        (is (= :too-many-path-operations (:code data)))))
    (is (= [] (vec (.listFiles (java.io.File. ^String root)))))))

(deftest read-limit-is-enforced-before-decoding
  (ts/with-root [root]
    (ts/write! root "big" "12345")
    (let [s (wg/session root {:max-file-bytes 4})
          data (ts/thrown-data #(wg/cat s "big"))]
      (is (= :LIMIT_EXCEEDED (:status data)))
      (is (= :file-too-large (:code data)))
      (is (empty? (:read-set (wg/snapshot s))))
      (is (empty? (:checks (wg/snapshot s)))))))

(deftest invalid-text-is-rejected
  (ts/with-root [root]
    (let [s (wg/session root)
          bad (str "a" (char 0xD800))
          data (ts/thrown-data #(wg/write s "f" bad))]
      (is (= :UNSUPPORTED_OPERATION (:status data)))
      (is (= :invalid-text (:code data))))
    (ts/write-bytes! root "bin" (byte-array (map unchecked-byte [0xff 0xfe 0x00])))
    (let [s (wg/session root)
          data (ts/thrown-data #(wg/cat s "bin"))]
      (is (= :UNSUPPORTED_OPERATION (:status data)))
      (is (= :binary-file (:code data))))))

(deftest preview-and-prepared-are-distinct
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/write s "f" "x")
      (let [p (wg/preview (wg/snapshot s))]
        (is (wg/preview? p))
        (is (not (wg/prepared? p)))
        (is (not-any? #(contains? % :bytes) (:ops p)))
        (is (every? #(or (not= :write (:op %)) (contains? % :size)) (:ops p)))
        (let [r (wg/apply-plan! p)]
          (is (= :UNSUPPORTED_OPERATION (:status r)))
          (is (false? (ts/exists? root "f"))))))))

(deftest failed-batch-leaves-disk-untouched
  (ts/with-root [root]
    (ts/write! root "a" "A")
    (ts/write! root "b" "B")
    (let [s (wg/session root)]
      (wg/cat s "a")
      (wg/cat s "b")
      (wg/write s "a" "A2")
      (wg/write s "b" "B2")
      (is (= :PREPARE_REJECTED
             (:status (ts/thrown-data #(wg/write s "missing/c" "C")))))
      (is (= "A" (ts/read! root "a")))
      (is (= "B" (ts/read! root "b")))
      (is (false? (ts/exists? root "missing/c"))))))

(deftest move-overwrite-requires-declaration
  (ts/with-root [root]
    (ts/write! root "a" "A")
    (ts/write! root "b" "B")
    (let [s (wg/session root)]
      (is (= :PREPARE_REJECTED (:status (ts/thrown-data #(wg/mv s "a" "b")))))
      (wg/mv s "a" "b" {:overwrite? true})
      (let [r (commit-batch root (wg/snapshot s))]
        (is (wg/applied? r))
        (is (= "A" (ts/read! root "b")))
        (is (false? (ts/exists? root "a")))))))

(deftest move-cycle-and-self-move-rejected
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/mkdir s "d")
      (is (= :PREPARE_REJECTED (:status (ts/thrown-data #(wg/mv s "d" "d/sub")))))
      (wg/write s "f" "x")
      (is (= :PREPARE_REJECTED (:status (ts/thrown-data #(wg/mv s "f" "f"))))))))

(deftest virtual-directory-can-be-emptied-then-removed
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/mkdir s "d")
      (wg/write s "d/f" "x")
      (wg/rm s "d/f")
      (wg/rm s "d")
      (let [r (commit-batch root (wg/snapshot s))]
        (is (wg/applied? r))
        (is (false? (ts/exists? root "d")))))))

(deftest real-directory-delete-after-child-delete-is-a-separate-commit
  (ts/with-root [root]
    (Files/createDirectory (java.nio.file.Paths/get (ts/file root "d")
                                                    (make-array String 0))
                           (make-array FileAttribute 0))
    (ts/write! root "d/f" "x")
    (is (= "x" (ts/read! root "d/f")))
    (let [s (wg/session root)]
      (wg/rm s "d/f")
      (let [data (ts/thrown-data #(wg/rm s "d"))]
        (is (= :UNSUPPORTED_OPERATION (:status data)))
        (is (= :non-empty-directory (:code data)))
        (is (ts/exists? root "d/f"))
        (is (= "x" (ts/read! root "d/f"))
            "the staged child delete did not touch the real file")))))

(deftest stat-missing-is-a-structure-basis
  (ts/with-root [root]
    (let [s (wg/session root)]
      (is (= {:exists false} (wg/stat s "new")))
      (let [batch (wg/snapshot s)]
        (ts/write! root "new" "external")
        (let [r (commit-batch root batch)]
          (is (= :STRUCTURE_CONFLICT (:status r)))
          (is (= :expected-missing (:code r)))
          (is (= "external" (ts/read! root "new"))))))))

(deftest ls-is-a-pure-probe
  (ts/with-root [root]
    (ts/write! root "f" "x")
    (let [s (wg/session root)]
      (is (= ["f"] (wg/ls s "")))
      (let [batch (wg/snapshot s)]
        (is (empty? (:read-set batch)))
        (is (empty? (:checks batch)))))))

(deftest directory-mtime-is-not-a-subtree-version
  (ts/with-root [root]
    (Files/createDirectory (java.nio.file.Paths/get (ts/file root "d")
                                                    (make-array String 0))
                           (make-array FileAttribute 0))
    (let [s (wg/session root)]
      (wg/stat s "d")
      (let [batch (wg/snapshot s)]
        ;; A new child changes the directory's mtime.  The contract records
        ;; existence/type, not a subtree version, so this still publishes.
        (ts/write! root "d/new" "x")
        (let [r (commit-batch root batch)]
          (is (wg/applied? r))
          (is (false? (:file-changed? r))))))))

(deftest empty-directory-delete-rechecks-emptiness
  (ts/with-root [root]
    (Files/createDirectory (java.nio.file.Paths/get (ts/file root "d")
                                                    (make-array String 0))
                           (make-array FileAttribute 0))
    (let [s (wg/session root)]
      (wg/rm s "d")
      (let [batch (wg/snapshot s)]
        (ts/write! root "d/f" "external")
        (let [r (commit-batch root batch)]
          (is (= :STRUCTURE_CONFLICT (:status r)))
          (is (= :directory-not-empty (:code r)))
          (is (ts/exists? root "d/f"))
          (is (= "external" (ts/read! root "d/f"))
              "the rejected directory delete did not remove the new child"))))))

(deftest missing-target-rechecks-before-apply
  (ts/with-root [root]
    (let [s (wg/session root)]
      (wg/write s "new" "ours")
      (let [batch (wg/snapshot s)]
        (ts/write! root "new" "external")
        (let [r (commit-batch root batch)]
          (is (= :STRUCTURE_CONFLICT (:status r)))
          (is (= :expected-missing (:code r)))
          (is (= "external" (ts/read! root "new"))))))))

(deftest property-sequential-writes-reach-final-bytes
  (let [result
        (tc/quick-check
         40
         (prop/for-all
          [contents (gen/vector gen/string-alphanumeric 1 8)
           names (gen/vector (gen/elements ["a" "b" "c"]) 1 8)]
          (ts/with-root [root]
            (let [s (wg/session root)
                  expected (atom {})]
              (doseq [[name content] (map vector names contents)]
                (wg/write s name content)
                (swap! expected assoc name content))
              (let [r (commit-batch root (wg/snapshot s))]
                (and (wg/applied? r)
                     (every? (fn [[name content]]
                               (= content (ts/read! root name)))
                             @expected)))))))]
    (is (:pass? result) (pr-str result))))

(deftest property-ordinary-rejection-leaves-bytes-unchanged
  (let [result
        (tc/quick-check
         40
         (prop/for-all
          [content gen/string-alphanumeric]
          (ts/with-root [root]
            (ts/write! root "a" "base")
            (let [s (wg/session root)]
              (wg/cat s "a")
              (wg/write s "a" content)
              (let [before (ts/read! root "a")
                    data (ts/ex-data-of #(wg/write s "missing/f" content))]
                (and (= :PREPARE_REJECTED (:status data))
                     (= before (ts/read! root "a"))))))))]
    (is (:pass? result) (pr-str result))))

(deftest list-reflects-real-moves
  (ts/with-root [root]
    (Files/createDirectory (java.nio.file.Paths/get (ts/file root "d")
                                                    (make-array String 0))
                           (make-array FileAttribute 0))
    (ts/write! root "d/f" "x")
    (is (= "x" (ts/read! root "d/f")))
    (let [s (wg/session root)]
      (wg/mv s "d/f" "g")
      (is (= [] (wg/ls s "d")) "the moved-away real child disappears")
      (is (= ["d/" "g"] (wg/ls s "")) "the moved-in real child appears")
      (wg/mv s "d" "e")
      (is (= ["e/" "g"] (wg/ls s ""))
          "the moved real directory changes name in the parent view")
      (is (= [] (wg/ls s "e"))))
    (is (ts/exists? root "d/f") "the real disk is untouched before commit")
    (is (= "x" (ts/read! root "d/f")))
    (is (false? (ts/exists? root "g")))))

(deftest hand-built-dry-run-rejects-move-cycle-and-non-empty-rm
  (ts/with-root [root]
    (Files/createDirectory (java.nio.file.Paths/get (ts/file root "d")
                                                    (make-array String 0))
                           (make-array FileAttribute 0))
    (is (Files/isDirectory (java.nio.file.Paths/get (ts/file root "d")
                                                    (make-array String 0))
                           (make-array java.nio.file.LinkOption 0)))
    (let [root-path (str (:root (wg/new-batch root)))
          d (ts/file root "d")
          batch (assoc (wg/new-batch root)
                       :checks {root-path {:path root-path :expect :directory}
                                d {:path d :expect :directory}}
                       :ops [{:op :mv :source d :target (ts/file root "d/sub")}])]
      (let [r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :move-cycle (:code r)))
        (is (ts/exists? root "d"))))
    (let [root-path (str (:root (wg/new-batch root)))
          d (ts/file root "d")
          batch (assoc (wg/new-batch root)
                       :checks {root-path {:path root-path :expect :directory}
                                d {:path d :expect :directory}}
                       :ops [{:op :mv :source d :target d}])]
      (let [r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :same-path (:code r)))
        (is (ts/exists? root "d"))))
    (ts/write! root "d/f" "x")
    (is (= "x" (ts/read! root "d/f")))
    (let [root-path (str (:root (wg/new-batch root)))
          d (ts/file root "d")
          f (ts/file root "d/f")
          stamp (wg/current-stamp root "d/f")
          batch (assoc (wg/new-batch root)
                       :checks {root-path {:path root-path :expect :directory}
                                d {:path d :expect :directory}
                                f {:path f :expect :file :stamps #{stamp}}}
                       :ops [{:op :rm :path d}])]
      (let [r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :non-empty-directory (:code r)))
        (is (ts/exists? root "d/f"))
        (is (= "x" (ts/read! root "d/f"))
            "the rejected directory delete left the real child intact")))
    (Files/createDirectory (java.nio.file.Paths/get (ts/file root "e")
                                                    (make-array String 0))
                           (make-array FileAttribute 0))
    (is (Files/isDirectory (java.nio.file.Paths/get (ts/file root "e")
                                                    (make-array String 0))
                           (make-array java.nio.file.LinkOption 0)))
    (let [root-path (str (:root (wg/new-batch root)))
          e (ts/file root "e")
          batch (assoc (wg/new-batch root)
                       :checks {root-path {:path root-path :expect :directory}
                                e {:path e :expect :directory}}
                       :ops [{:op :rm :path e}])]
      (let [r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :directory-emptiness-unverified (:code r)))
        (is (ts/exists? root "e"))))))

(deftest hand-built-dry-run-rejects-invalid-write-bytes
  (ts/with-root [root]
    (let [root-path (str (:root (wg/new-batch root)))
          batch (assoc (wg/new-batch root)
                       :checks {root-path {:path root-path :expect :directory}}
                       :ops [{:op :write :path (ts/file root "x") :create? true}])]
      (let [r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :invalid-write-bytes (:code r)))
        (is (not (contains? (:op r) :bytes)))
        (is (not (clojure.string/includes? (pr-str r) "#object")))
        (is (false? (ts/exists? root "x")))))
    (let [root-path (str (:root (wg/new-batch root)))
          batch (assoc (wg/new-batch root)
                       :checks {root-path {:path root-path :expect :directory}}
                       :ops [{:op :write
                              :path (ts/file root "y")
                              :bytes (byte-array [0xff])
                              :create? true}])]
      (let [r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :non-canonical-utf8 (:code r)))
        (is (false? (ts/exists? root "y")))))
    (let [root-path (str (:root (wg/new-batch root)))
          batch (assoc (wg/new-batch root)
                       :checks {root-path {:path root-path :expect :directory}}
                       :ops [{:op :write
                              :path (ts/file root "z")
                              :bytes "not-bytes"
                              :create? true}])]
      (let [r (commit-batch root batch)]
        (is (= :PREPARE_REJECTED (:status r)))
        (is (= :invalid-write-bytes (:code r)))
        (is (false? (ts/exists? root "z")))))))

(deftest nested-path-under-file-is-not-dir
  (ts/with-root [root]
    (ts/write! root "a" "x")
    (let [s (wg/session root)
          data (ts/thrown-data #(wg/cat s "a/b"))]
      (is (= :STRUCTURE_CONFLICT (:status data)))
      (is (= :ancestor-not-directory (:code data)))
      (is (= {:exists false :ancestor-not-directory true}
             (wg/stat s "a/b"))))))

(deftest conflict-detail-preserves-observation-order
  (ts/with-root [root]
    (ts/write! root "f" "one")
    (let [s (wg/session root)]
      (wg/cat s "f")
      (ts/write! root "f" "two")
      (wg/cat s "f")
      (let [batch (wg/snapshot s)
            check (get-in batch [:checks (ts/file root "f")])]
        (is (= 2 (count (:observations check))))
        (let [r (commit-batch root batch)]
          (is (= :FILE_CONFLICT (:status r)))
          (is (= 2 (count (:observations r)))))))))

(deftest mkdir-does-not-replace-broken-symlink
  (ts/with-root [root]
    (let [link (java.nio.file.Paths/get (ts/file root "link")
                                        (make-array String 0))
          target (java.nio.file.Paths/get (ts/file root "target")
                                          (make-array String 0))]
      (Files/createSymbolicLink link target (make-array FileAttribute 0))
      (let [s (wg/session root)]
        (is (= ["link"] (wg/ls s ""))
            "a broken symlink remains a visible directory entry")
        (wg/mkdir s "link")
        (let [r (commit-batch root (wg/snapshot s))]
          (is (= :HARD_FAILURE (:status r)))
          (is (Files/isSymbolicLink link))
          (is (false? (ts/exists? root "target"))))))))
