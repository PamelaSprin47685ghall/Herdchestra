(ns hc.core.steward-sphinx.cognition-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.steward-sphinx.cognition :as cognition]))

(defn commit
  ([n paths]
   (commit n paths []))
  ([n paths parents]
   {:kind :git-commit
    :commit (str "c" n)
    :parents parents
    :changed-paths paths
    :subject (str "change " n)}))

(defn evidence-id
  [n]
  (str "git:c" n))

(defn ingest
  ([state n paths]
   (ingest state n paths []))
  ([state n paths parents]
   (cognition/ingest-evidence state (commit n paths parents))))

(defn revise
  ([state summary]
   (revise state summary [] []))
  ([state summary assumptions claims]
   (cognition/revise-best-answer
    state
    {:summary summary
     :frontier-of-thought "test frontier"
     :assumptions assumptions
     :claims claims})))

(defn state-after
  [result]
  (:state result))

(defn claim
  [id confidence evidence-ids]
  {:claim-id id
   :statement (str "claim " id)
   :confidence confidence
   :evidence-ids evidence-ids})

(deftest initial-cognition-holds-an-open-no-answer
  (let [state (cognition/initial-cognition)
        answer (:best-answer state)]
    (is (= "How can this be better?" (:root-question state)))
    (is (= :open (get-in state [:questions :root :status])))
    (is (= [:root] (:open-question-ids answer)))
    (is (= [] (:claims answer)))
    (is (= 0.0 (:confidence answer)))
    (is (true? (:non-final? answer)))
    (is (= :open (:root-question-status answer)))
    (is (false? (:stale? answer)))))

(deftest git-ingestion-tracks-continuity
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r2 (ingest c1 2 ["src/b.clj"] ["c1"])
        c2 (state-after r2)
        r3 (ingest c2 3 ["docs/x.md"] ["other"])
        c3 (state-after r3)]
    (is (= "c1" (get-in c1 [:git :head])))
    (is (= :initial (get-in c1 [:git :history 0 :continuity])))
    (is (= :linear (get-in r2 [:continuity :continuity])))
    (is (= :discontinuous (get-in r3 [:continuity :continuity])))
    (is (= ["c1" "c2" "c3"] (get-in c3 [:git :observed-commits])))
    (is (= 3 (cognition/evidence-count c3)))
    (is (= "c3" (get-in c3 [:best-answer :latest-evidence :commit])))
    (is (= 3 (:cognition-version c3)))))

(deftest out-of-order-git-evidence-does-not-regress-head
  (let [c2 (state-after (ingest (cognition/initial-cognition)
                                2 ["src/b.clj"] ["c1"]))
        c1 (state-after (ingest c2 1 ["src/a.clj"]))]
    (is (= "c2" (get-in c1 [:git :head])))
    (is (= "c1" (get-in c1 [:git :last-observed-commit])))
    (is (= "c2" (get-in c1 [:best-answer :based-on-commit])))
    (is (= "c1" (get-in c1 [:best-answer :latest-evidence :commit])))
    (is (false? (get-in c1 [:git :history 1 :head-advanced?])))
    (is (= "c2" (get-in c1 [:git :history 1 :head])))))

(deftest duplicate-evidence-is-a-no-op
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r2 (ingest c1 1 ["src/a.clj"])]
    (is (:ok? r2))
    (is (false? (:new? r2)))
    (is (= c1 (state-after r2)))
    (is (= [] (:newly-invalidated-assumption-ids r2)))))

(deftest conflicting-evidence-is-refused
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r2 (cognition/ingest-evidence
            c1
            (assoc (commit 1 ["src/a.clj"]) :subject "different"))]
    (is (not (:ok? r2)))
    (is (= :EVIDENCE_CONFLICT (get-in r2 [:error :code])))
    (is (= c1 (:state r2)))))

(deftest revision-requires-new-evidence
  (let [initial (cognition/initial-cognition)
        r0 (revise initial "nothing new")
        c1 (state-after (ingest initial 1 ["src/a.clj"]))
        r1 (revise c1 "first" [] [(claim "k1" 0.8 ["git:c1"])])
        c2 (state-after r1)
        r2 (revise c2 "repeated" [] [(claim "k1" 0.7 ["git:c1"])])]
    (is (not (:ok? r0)))
    (is (= :NO_NEW_EVIDENCE (get-in r0 [:error :code])))
    (is (:ok? r1))
    (is (not (:ok? r2)))
    (is (= :NO_NEW_EVIDENCE (get-in r2 [:error :code])))
    (is (= c2 (:state r2)))))

(deftest claims-need-known-and-non-empty-evidence
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        empty-evidence (revise c1 "empty" [] [(claim "k1" 0.8 [])])
        unknown-evidence (revise c1 "unknown" [] [(claim "k1" 0.8 ["git:nope"])])]
    (is (not (:ok? empty-evidence)))
    (is (= :INVALID_CLAIM (get-in empty-evidence [:error :code])))
    (is (not (:ok? unknown-evidence)))
    (is (= :INVALID_CLAIM (get-in unknown-evidence [:error :code])))))

(deftest claims-need-known-dimensions-and-finite-weights
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        unknown-dimension (revise c1 "dimension" []
                                  [(assoc (claim "k1" 0.8 ["git:c1"])
                                          :dimension :not-a-dimension)])
        infinite-weight (revise c1 "weight" []
                               [(assoc (claim "k1" 0.8 ["git:c1"])
                                       :weight ##Inf)])]
    (is (not (:ok? unknown-dimension)))
    (is (= :INVALID_CLAIM (get-in unknown-dimension [:error :code])))
    (is (not (:ok? infinite-weight)))
    (is (= :INVALID_CLAIM (get-in infinite-weight [:error :code])))))

(deftest duplicate-ids-in-one-revision-are-refused
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        assumption {:assumption-id "a1"
                    :statement "same id"
                    :confidence 0.5
                    :watch-paths ["src"]
                    :basis ["git:c1"]}
        duplicate-assumptions (revise c1 "dup" [assumption assumption] [])
        duplicate-claims (revise c1 "dup" [] [(claim "k1" 0.8 ["git:c1"])
                                              (claim "k1" 0.7 ["git:c1"])])]
    (is (not (:ok? duplicate-assumptions)))
    (is (= :INVALID_REVISION (get-in duplicate-assumptions [:error :code])))
    (is (not (:ok? duplicate-claims)))
    (is (= :INVALID_REVISION (get-in duplicate-claims [:error :code])))))

(deftest assumptions-invalidate-on-watched-paths
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r1 (revise c1 "cache"
                   [{:assumption-id "a1"
                     :statement "src is synchronous"
                     :confidence 0.8
                     :watch-paths ["src"]
                     :basis ["git:c1"]}]
                   [(assoc (claim "k1" 0.8 ["git:c1"]) :assumption-ids ["a1"])])
        c2 (state-after r1)
        r2 (ingest c2 2 ["src/b.clj"])
        c3 (state-after r2)
        r3 (ingest c3 3 ["docs/x.md"])
        c4 (state-after r3)]
    (is (:ok? r1))
    (is (= :active (get-in (cognition/assumption-status
                            c2
                            (get-in c2 [:assumptions "a1"]))
                           [:status])))
    (is (= ["a1"] (:newly-invalidated-assumption-ids r2)))
    (is (= :invalidated (get-in (cognition/assumption-status
                                 c3
                                 (get-in c3 [:assumptions "a1"]))
                                [:status])))
    (is (true? (get-in c3 [:best-answer :stale?])))
    (is (= [] (get-in c3 [:best-answer :claims])))
    (is (= "k1" (get-in c3 [:best-answer :excluded-claims 0 :claim-id])))
    (is (= :invalidated
           (->> (get-in c3 [:best-answer :assumptions])
                (filter #(= "a1" (:assumption-id %)))
                first
                :status)))
    (is (= :invalidated (get-in (cognition/assumption-status
                                 c4
                                 (get-in c4 [:assumptions "a1"]))
                                [:status])))))

(deftest unrelated-paths-do-not-invalidate
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r1 (revise c1 "cache"
                   [{:assumption-id "a1"
                     :statement "src is synchronous"
                     :confidence 0.8
                     :watch-paths ["src"]
                     :basis ["git:c1"]}]
                   [(assoc (claim "k1" 0.8 ["git:c1"]) :assumption-ids ["a1"])])
        c2 (state-after r1)
        c3 (state-after (ingest c2 2 ["docs/x.md"]))]
    (is (= :active (get-in (cognition/assumption-status
                            c3
                            (get-in c3 [:assumptions "a1"]))
                           [:status])))
    (is (false? (get-in c3 [:best-answer :stale?])))
    (is (= 1 (count (get-in c3 [:best-answer :claims]))))))

(deftest retired-assumption-remains-visible-with-excluded-claim
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r1 (revise c1 "cache"
                   [{:assumption-id "a1"
                     :statement "src is synchronous"
                     :confidence 0.8
                     :watch-paths ["src"]
                     :basis ["git:c1"]}]
                   [(assoc (claim "k1" 0.8 ["git:c1"]) :assumption-ids ["a1"])])
        c2 (state-after r1)
        c3 (state-after (ingest c2 2 ["docs/x.md"]))
        r4 (revise c3 "retire"
                   [{:assumption-id "a1"
                     :statement "src is synchronous"
                     :confidence 0.8
                     :watch-paths ["src"]
                     :basis ["git:c1"]
                     :retired? true}]
                   [])
        answer (get-in (state-after r4) [:best-answer])]
    (is (:ok? r4))
    (is (= "k1" (get-in answer [:excluded-claims 0 :claim-id])))
    (is (= :retired-assumption
           (get-in answer [:excluded-claims 0 :reason])))
    (is (true? (:stale? answer)))
    (is (= :retired
           (->> (:assumptions answer)
                (filter #(= "a1" (:assumption-id %)))
                first
                :status)))))

(deftest invalidated-assumptions-can-be-revalidated
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r1 (revise c1 "cache"
                   [{:assumption-id "a1"
                     :statement "src is synchronous"
                     :confidence 0.8
                     :watch-paths ["src"]
                     :basis ["git:c1"]}]
                   [(assoc (claim "k1" 0.8 ["git:c1"]) :assumption-ids ["a1"])])
        c2 (state-after r1)
        c3 (state-after (ingest c2 2 ["src/b.clj"]))
        r4 (revise c3 "cache revised"
                   [{:assumption-id "a1"
                     :statement "src is synchronous after the change"
                     :confidence 0.6
                     :watch-paths ["src"]
                     :basis ["git:c2"]}]
                   [(assoc (claim "k1" 0.6 ["git:c2"]) :assumption-ids ["a1"])])
        c4 (state-after r4)]
    (is (true? (get-in c3 [:best-answer :stale?])))
    (is (:ok? r4))
    (is (= :active (get-in (cognition/assumption-status
                            c4
                            (get-in c4 [:assumptions "a1"]))
                           [:status])))
    (is (false? (get-in c4 [:best-answer :stale?])))
    (is (= 0.6 (get-in c4 [:best-answer :confidence])))))

(deftest confidence-is-not-monotonic
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r1 (revise c1 "high" [] [(claim "k1" 0.9 ["git:c1"])])
        c2 (state-after r1)
        c3 (state-after (ingest c2 2 ["docs/x.md"]))
        r2 (revise c3 "low" [] [(claim "k1" 0.2 ["git:c1"])])
        c4 (state-after r2)]
    (is (= 0.9 (get-in c2 [:best-answer :confidence])))
    (is (= 0.2 (get-in c4 [:best-answer :confidence])))
    (is (= 2 (get-in c4 [:best-answer :revision])))))

(deftest withdrawing-a-claim-removes-it-without-staleness
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r1 (revise c1 "claim" [] [(claim "k1" 0.8 ["git:c1"])])
        c2 (state-after r1)
        c3 (state-after (ingest c2 2 ["docs/x.md"]))
        r2 (cognition/revise-best-answer
            c3
            {:summary "withdrawn"
             :frontier-of-thought "test frontier"
             :withdraw-claim-ids ["k1"]})
        c4 (state-after r2)]
    (is (:ok? r2))
    (is (= [] (get-in c4 [:best-answer :claims])))
    (is (= ["k1"] (get-in c4 [:best-answer :withdrawn-claim-ids])))
    (is (false? (get-in c4 [:best-answer :stale?])))))

(deftest root-question-never-resolves
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        result (cognition/resolve-question
                c1
                {:question-id :root
                 :answer "done"
                 :evidence-ids ["git:c1"]})]
    (is (not (:ok? result)))
    (is (= :ROOT_QUESTION_NEVER_CLOSES (get-in result [:error :code])))
    (is (= :open (get-in (:state result) [:questions :root :status])))))

(deftest local-questions-open-and-resolve
  (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
        r1 (cognition/open-question c1 {:question-id "q1"
                                        :question "Is the cache safe?"})
        c2 (state-after r1)
        r2 (cognition/resolve-question
            c2
            {:question-id "q1"
             :answer "yes, under the observed evidence"
             :evidence-ids ["git:c1"]})
        c3 (state-after r2)
        r3 (cognition/resolve-question
            c3
            {:question-id "q1"
             :answer "again"
             :evidence-ids ["git:c1"]})]
    (is (:ok? r1))
    (is (= [:root "q1"] (get-in c2 [:best-answer :open-question-ids])))
    (is (:ok? r2))
    (is (= [:root] (get-in c3 [:best-answer :open-question-ids])))
    (is (= ["q1"] (get-in c3 [:best-answer :resolved-question-ids])))
    (is (= "yes, under the observed evidence"
           (->> (get-in c3 [:best-answer :questions])
                (filter #(= "q1" (:question-id %)))
                first
                :answer)))
    (is (not (:ok? r3)))
    (is (= :QUESTION_ALREADY_RESOLVED (get-in r3 [:error :code])))))

(def gen-path
  (gen/elements ["src/a.clj" "src/b.clj" "docs/a.md" "test/a.clj"]))

(def gen-commits
  (gen/fmap (fn [paths]
              (mapv (fn [index path]
                      {:kind :git-commit
                       :commit (str "c" index)
                       :parents (if (zero? index) [] [(str "c" (dec index))])
                       :changed-paths [path]
                       :subject (str "change " index)})
                    (range)
                    paths))
            (gen/vector gen-path 1 8)))

(defspec duplicate-ingestion-is-idempotent-property 40
  (prop/for-all [commits gen-commits]
    (let [ingest-all (fn [state evidences]
                       (reduce (fn [state evidence]
                                 (:state (cognition/ingest-evidence state evidence)))
                               state
                               evidences))
          once (ingest-all (cognition/initial-cognition) commits)
          twice (ingest-all once commits)]
      (and (= once twice)
           (= (count commits) (cognition/evidence-count once))))))

(def gen-confidence
  (gen/fmap #(/ % 100.0) (gen/choose 0 100)))

(defspec confidence-stays-in-unit-interval-property 40
  (prop/for-all [confidences (gen/vector gen-confidence 0 6)]
    (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["src/a.clj"]))
          claims (mapv (fn [index confidence]
                         (claim (str "k" index) confidence ["git:c1"]))
                       (range)
                       confidences)
          result (revise c1 "weighted" [] claims)]
      (and (:ok? result)
           (<= 0.0 (get-in result [:best-answer :confidence]) 1.0)))))

(deftest non-finite-claim-weight-cannot-poison-confidence
  (let [state (assoc-in (cognition/initial-cognition)
                        [:claims "k1"]
                        {:claim-id "k1"
                         :statement "corrupt weight"
                         :confidence 1.0
                         :weight ##Inf
                         :evidence-ids []
                         :assumption-ids []
                         :dimension :open
                         :status :active})
        answer (cognition/rebuild-best-answer state)]
    (is (<= 0.0 (:confidence answer) 1.0))))

(defspec invalidation-is-monotonic-property 40
  (prop/for-all [watch gen-path
                 first-path gen-path
                 second-path gen-path]
    (let [c1 (state-after (ingest (cognition/initial-cognition) 1 ["base"]))
          r1 (revise c1 "assume"
                     [{:assumption-id "a1"
                       :statement "watched"
                       :confidence 0.7
                       :watch-paths [watch]
                       :basis ["git:c1"]}]
                     [])
          c2 (state-after r1)
          c3 (state-after (ingest c2 2 [first-path]))
          c4 (state-after (ingest c3 3 [second-path]))
          invalidated? (fn [state]
                         (= :invalidated
                            (get-in (cognition/assumption-status
                                     state
                                     (get-in state [:assumptions "a1"]))
                                    [:status])))]
      (or (not (invalidated? c3))
          (invalidated? c4)))))
