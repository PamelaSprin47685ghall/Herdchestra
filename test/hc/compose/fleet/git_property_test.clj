(ns hc.compose.fleet.git-property-test
  "Property tests for `GitPort.ff-ready?`.

  The command runner is replaced by a pure fake that answers the four Git
  probes (`HEAD`, target ref, worktree status, ancestor test).  `ff-ready?`
  itself, including its decision order and reason classification, is the real
  implementation.  A random DAG of commits supplies the ref graph."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.git :as git]))

(def ^:private node-count 8)

(defn- commit-sha
  [node]
  (str "sha-" node))

(defn- ref-name
  [node]
  (str "ref-" node))

(defn- ancestors
  "Transitive parents of NODE in PARENTS, excluding NODE itself."
  [parents node]
  (loop [seen #{}
         frontier (set (get parents node))]
    (if (empty? frontier)
      seen
      (let [parent (first frontier)
            rest-frontier (disj frontier parent)]
        (if (contains? seen parent)
          (recur seen rest-frontier)
          (recur (conj seen parent)
                 (into rest-frontier (get parents parent))))))))

(defn- fake-run-command
  "Answer the exact Git probes `ff-ready?` issues."
  [{:keys [parents head target clean? head-ok? target-ok?]}]
  (fn [argv {:keys [dir]}]
    (let [argv (mapv str argv)]
      (cond
        (and (= "/worktree" dir)
             (= ["git" "rev-parse" "--verify" "HEAD"] argv))
        (if head-ok?
          {:status :ok :exit 0 :out (commit-sha head) :err "" :argv argv}
          {:status :failed :exit 128 :out "" :err "fatal: no HEAD" :argv argv})

        (and (= "/repo" dir)
             (= "rev-parse" (second argv))
             (= (str (ref-name target) "^{commit}") (last argv)))
        (if target-ok?
          {:status :ok :exit 0 :out (commit-sha target) :err "" :argv argv}
          {:status :failed :exit 128 :out "" :err "unknown ref" :argv argv})

        (and (= "/worktree" dir)
             (= ["git" "status" "--porcelain"] argv))
        {:status :ok :exit 0
         :out (if clean? "" " M src/changed.clj\n")
         :err "" :argv argv}

        (and (= "/worktree" dir)
             (= "merge-base" (second argv))
             (= "--is-ancestor" (nth argv 2))
             (= (ref-name target) (nth argv 3))
             (= "HEAD" (nth argv 4)))
        (if (contains? (conj (ancestors parents head) head) target)
          {:status :ok :exit 0 :out "" :err "" :argv argv}
          {:status :ok :exit 1 :out "" :err "" :argv argv})

        :else
        {:status :failed :exit 127 :out ""
         :err (str "unexpected fake git command: " (pr-str argv)) :argv argv}))))

(defspec ff-ready-is-clean-and-ancestor 100
  (prop/for-all [raw-parents (gen/vector (gen/vector (gen/choose 0 (dec node-count)) 0 3)
                                         node-count)
                 head (gen/choose 0 (dec node-count))
                 target (gen/choose 0 (dec node-count))
                 clean? gen/boolean
                 head-ok? gen/boolean
                 target-ok? gen/boolean]
    (let [parents (mapv (fn [node raw]
                          (->> raw
                               (filter #(< % node))
                               distinct
                               vec))
                        (range node-count)
                        raw-parents)
          ancestor? (contains? (conj (ancestors parents head) head) target)
          result (with-redefs-fn
                   {#'git/run-command
                    (fake-run-command {:parents parents
                                       :head head
                                       :target target
                                       :clean? clean?
                                       :head-ok? head-ok?
                                       :target-ok? target-ok?})}
                   #(git/ff-ready? (git/->GitCli "/repo" nil)
                                   "/worktree"
                                   (ref-name target)))
          readable? (and head-ok? target-ok?)]
      (and
       ;; The core equivalence: with both heads readable, readiness is
       ;; exactly "worktree clean AND target is an ancestor of HEAD".
       (or (not readable?)
           (= (:ready? result) (and clean? ancestor?)))
       ;; Unreadable probes never become ready, whatever the other facts say.
       (or readable? (false? (:ready? result)))
       ;; A ready result carries the observed evidence.
       (or (not (:ready? result))
           (and (= :ready (:reason result))
                (= (commit-sha head) (:head result))
                (= (commit-sha target) (:target-head result))
                (= true (:clean? result))))
       ;; Refusals name the first failed condition in decision order.
       (cond
         (not head-ok?)
         (= :HEAD_UNREADABLE (:reason result))
         (not target-ok?)
         (= :TARGET_HEAD_UNREADABLE (:reason result))
         (not clean?)
         (= :WORKTREE_DIRTY (:reason result))
         (not ancestor?)
         (= :TARGET_NOT_ANCESTOR (:reason result))
         :else
         (= :ready (:reason result)))))))
