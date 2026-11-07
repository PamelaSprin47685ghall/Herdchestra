(ns hc.core.steward-sphinx-test
  (:require [clojure.test :refer [deftest is]]
            [hc.core.steward-sphinx :as ss]))

(defn commit
  [n paths]
  {:kind :git-commit
   :commit (str "c" n)
   :parents (if (= n 1) [] [(str "c" (dec n))])
   :changed-paths paths
   :subject (str "change " n)})

(defn candidate
  [overrides]
  (merge {:candidate-id "candidate-1"
          :title "Add an asynchronous boundary"
          :consequence 0.9
          :evidence-strength 0.9
          :dependency-cost 0.1
          :reversibility 0.9
          :opportunity-cost 0.1
          :demand 1
          :evidence-ids ["git:c1"]
          :assumption-ids ["a1"]}
         overrides))

(deftest stewardship-end-to-end
  (let [c0 (ss/initial-cognition)
        c1 (:state (ss/ingest-evidence c0 (commit 1 ["src/core.clj"])))
        r1 (ss/revise-best-answer
            c1
            {:summary "Add an asynchronous boundary"
             :frontier-of-thought "The core is synchronous"
             :assumptions [{:assumption-id "a1"
                            :statement "The core is synchronous"
                            :confidence 0.7
                            :watch-paths ["src"]
                            :basis ["git:c1"]}]
             :claims [{:claim-id "k1"
                       :statement "An asynchronous boundary reduces latency"
                       :confidence 0.7
                       :evidence-ids ["git:c1"]
                       :assumption-ids ["a1"]
                       :dimension :performance}]})
        c2 (:state r1)
        c3 (:state (ss/ingest-evidence c2 (commit 2 ["docs/readme.md"])))
        f0 (ss/initial-frontier-state)
        f1 (ss/evaluate-frontier
            f0
            {:intent {:revision 1 :text "reduce latency"}
             :capacity {:slots-total 4 :slots-allocated 0 :missions []}
             :cognition c3})
        d0 (ss/initial-docket)
        decision-request {:request-id "decision-1"
                          :frontier (:frontier f1)
                          :cognition c3
                          :candidates [(candidate {})]}
        d1 (ss/decide d0 decision-request)
        d2 (ss/decide (:docket d1) decision-request)
        summary (ss/docket-summary (:docket d2))]
    (is (:ok? r1))
    (is (= 0.7 (get-in c3 [:best-answer :confidence])))
    (is (= [:operator-intent :capacity] (get-in f1 [:frontier :reasons])))
    (is (= (:best-answer c3) (get-in f1 [:frontier :best-answer])))
    (is (= :admit (get-in d1 [:receipt :outcome])))
    (is (= ["mission-1"] (get-in d1 [:receipt :admitted])))
    (is (true? (:replayed? d2)))
    (is (= 1 (count (:missions summary))))
    (is (= :admitted (get-in summary [:missions 0 :status])))
    (is (= "reduce latency" (get-in summary [:intent :text])))
    (is (empty? (:do-not-do summary)))))

(deftest stale-evidence-degrades-the-answer-and-defers-admission
  (let [c1 (:state (ss/ingest-evidence
                    (ss/initial-cognition)
                    (commit 1 ["src/core.clj"])))
        c2 (:state (ss/revise-best-answer
                    c1
                    {:summary "Add an asynchronous boundary"
                     :frontier-of-thought "The core is synchronous"
                     :assumptions [{:assumption-id "a1"
                                    :statement "The core is synchronous"
                                    :confidence 0.7
                                    :watch-paths ["src"]
                                    :basis ["git:c1"]}]
                     :claims [{:claim-id "k1"
                               :statement "An asynchronous boundary reduces latency"
                               :confidence 0.7
                               :evidence-ids ["git:c1"]
                               :assumption-ids ["a1"]}]}))
        c3 (:state (ss/ingest-evidence c2 (commit 2 ["src/core.clj"])))
        f0 (ss/initial-frontier-state)
        f1 (ss/evaluate-frontier
            f0
            {:intent {:revision 1 :text "reduce latency"}
             :capacity {:slots-total 4 :slots-allocated 0 :missions []}
             :cognition c3})
        f2 (ss/evaluate-frontier
            (:state f1)
            {:intent {:revision 2 :text "continue"}
             :capacity {:slots-total 4 :slots-allocated 0 :missions []}
             :cognition c3})
        d0 (ss/initial-docket)
        d1 (ss/decide d0
                      {:request-id "decision-stale"
                       :frontier (:frontier f2)
                       :cognition c3
                       :candidates [(candidate {})]})]
    (is (true? (get-in c3 [:best-answer :stale?])))
    (is (= [] (get-in c3 [:best-answer :claims])))
    (is (= ["a1"] (get-in c3 [:best-answer :invalidated-assumption-ids])))
    (is (true? (get-in f2 [:frontier :best-answer :stale?])))
    (is (= [:operator-intent] (get-in f2 [:frontier :reasons])))
    (is (= :defer (get-in d1 [:receipt :decisions 0 :outcome])))
    (is (= [:stale-assumption]
           (get-in d1 [:receipt :decisions 0 :reasons])))
    (is (contains? (:awaiting-evidence (:docket d1)) "candidate-1"))))

(deftest current-best-answer-alone-does-not-create-a-frontier
  (let [cognition {:cognition-version 1
                   :best-answer {:cognition-version 1 :non-final? true}}
        f0 (ss/initial-frontier-state)
        f1 (ss/evaluate-frontier
            f0
            {:intent {:revision 1 :text "hold"}
             :capacity {:slots-total 2 :slots-allocated 2 :missions []}
             :cognition cognition})
        newer {:cognition-version 99
               :best-answer {:cognition-version 99 :non-final? true}}
        f2 (ss/evaluate-frontier
            (:state f1)
            {:intent {:revision 1 :text "hold"}
             :capacity {:slots-total 2 :slots-allocated 2 :missions []}
             :cognition newer})]
    (is (= [:operator-intent] (get-in f1 [:frontier :reasons])))
    (is (nil? (:frontier f2)))
    (is (= [] (:reasons f2)))))

(deftest decision-cannot-happen-without-a-frontier
  (let [result (ss/decide (ss/initial-docket)
                          {:request-id "no-frontier"
                           :frontier nil
                           :cognition (ss/initial-cognition)
                           :candidates []})]
    (is (not (:ok? result)))
    (is (= :NO_DECISION_FRONTIER (get-in result [:error :code])))))
