(ns hc.compose.reports.protocol-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.reports.acceptance :as acceptance]
            [hc.compose.reports.delivery :as delivery]
            [hc.compose.reports.support :as support]))

(defn- initial-state
  [reports]
  {:child (reduce (fn [state r]
                    (:state (delivery/register state r)))
                  delivery/empty-state
                  reports)
   :parent acceptance/empty-state
   :originals (into {} (map (juxt :report-id identity)) reports)
   :appends []
   :last-ack nil})

(defn- deliver-next
  [state]
  (let [action (delivery/next-action (:child state))]
    (if (= :deliver (:action action))
      (let [r (:report action)
            accepted (acceptance/accept-report (:parent state) r)]
        (if (:ok? accepted)
          (cond-> (-> state
                      (assoc :parent (:state accepted))
                      (assoc :last-ack (:ack accepted)))
            (:append? accepted)
            (update :appends conj {:report-id (:report-id r)
                                   :payload (:payload r)}))
          (assoc state :protocol-error (:error accepted))))
      state)))

(defn- ack-last
  [state]
  (if-let [ack (:last-ack state)]
    (assoc state :child (:state (delivery/apply-ack (:child state) ack)))
    state))

(defn- redeliver
  [state]
  (let [entries (sort-by key (acceptance/accepted-entries (:parent state)))]
    (if-let [[_ entry] (first entries)]
      (let [r (get (:originals state) (:report-id entry))
            accepted (acceptance/accept-report (:parent state) r)]
        (if (:ok? accepted)
          (cond-> (-> state
                      (assoc :parent (:state accepted))
                      (assoc :child (:state (delivery/apply-ack
                                             (:child state)
                                             (:ack accepted)))))
            (:append? accepted)
            (update :appends conj {:report-id (:report-id r)
                                   :payload (:payload r)}))
          (assoc state :protocol-error (:error accepted))))
      state)))

(defn- reclaim-next
  [state]
  (let [action (delivery/next-action (:child state))]
    (if (= :reclaim (:action action))
      (assoc state :child (:state (delivery/mark-reclaimed
                                   (:child state)
                                   (:report-id action))))
      state)))

(defn- apply-op
  [state op]
  (case op
    :deliver (deliver-next state)
    :ack (ack-last state)
    :redeliver (redeliver state)
    :reclaim (reclaim-next state)
    :terminate (assoc state :parent (acceptance/terminate (:parent state)))
    :noop state))

(defn- drain
  [state]
  (loop [state state
         guard 0]
    (if (> guard 1000)
      (assoc state :protocol-error {:code :DRAIN_DID_NOT_TERMINATE})
      (let [action (delivery/next-action (:child state))]
        (case (:action action)
          :deliver (recur (ack-last (deliver-next state)) (inc guard))
          :reclaim (recur (reclaim-next state) (inc guard))
          :none state)))))

(defn- project-all
  [state]
  (loop [parent (:parent state)]
    (if-let [entry (first (acceptance/pending-projections parent))]
      (recur (:state (acceptance/mark-projected
                      parent
                      (:parent-call-id entry))))
      (assoc state :parent parent))))

(defn- invariant
  [state]
  (let [{:keys [child parent originals appends]} state
        entries (vals (acceptance/accepted-entries parent))]
    (and (nil? (:protocol-error state))
         (every? (fn [[report-id entry]]
                   (= (get-in originals [report-id :payload])
                      (:payload entry)))
                 (delivery/reports child))
         (every? (fn [entry]
                   (let [accepted (acceptance/accepted-report
                                   parent
                                   (:parent-call-id entry))]
                     (and accepted (= (:ack accepted) (:ack entry)))))
                 (delivery/acknowledged-reports child))
         (every? (fn [{:keys [report-id]}]
                   (some #(= report-id (:report-id %)) entries))
                 appends)
         (= (count appends)
            (count (distinct (map :report-id appends))))
         (every? (fn [entry]
                   (= (get-in originals [(:report-id entry) :payload])
                      (:payload entry)))
                 entries))))

(deftest ack-loss-redelivers-and-appends-once
  (let [r (support/report-for "r1" "c1" "full report")
        initial (initial-state [r])
        delivered (deliver-next initial)
        redelivered (deliver-next delivered)
        acked (ack-last redelivered)
        reclaimed (reclaim-next acked)]
    (is (= 1 (count (:appends delivered))))
    (is (= 1 (count (:appends redelivered))))
    (is (= :deliver (:action (delivery/next-action (:child delivered)))))
    (is (delivery/closed? (:child reclaimed)))
    (is (= "full report"
           (get-in (acceptance/accepted-report (:parent reclaimed) "c1")
                   [:payload :text])))))

(deftest a-terminated-parent-lets-the-child-close
  (let [r (support/report-for "r1" "c1" "full")
        initial (initial-state [r])
        terminated (apply-op initial :terminate)
        delivered (deliver-next terminated)
        acked (ack-last delivered)
        reclaimed (reclaim-next acked)]
    (is (empty? (:appends delivered)))
    (is (delivery/closed? (:child reclaimed)))
    (is (= :parent-terminated
           (get-in (acceptance/accepted-report (:parent reclaimed) "c1")
                   [:ack :status])))))

(def gen-op
  (gen/elements [:deliver :ack :redeliver :reclaim :terminate :noop]))

(defspec report-protocol-loses-nothing 60
  (prop/for-all [texts (gen/vector support/gen-text 1 4)
                 ops (gen/vector gen-op 0 40)]
    (let [reports (mapv (fn [i text]
                          (support/report-for (str "r-" i) (str "c-" i) text))
                        (range (count texts))
                        texts)
          report-count (count reports)
          initial (initial-state reports)
          final (project-all (drain (reduce apply-op initial ops)))
          entries (vals (acceptance/accepted-entries (:parent final)))
          active (filter #(= :accepted (get-in % [:ack :status])) entries)
          terminated (filter #(= :parent-terminated
                                  (get-in % [:ack :status]))
                             entries)
          append-ids (frequencies (map :report-id (:appends final)))
          active-ids (frequencies (map :report-id active))]
      (and (invariant final)
           (delivery/closed? (:child final))
           (= report-count (count entries))
           (= append-ids active-ids)
           (every? (fn [entry]
                     (not (contains? append-ids (:report-id entry))))
                   terminated)
           (every? (fn [r]
                     (= (get-in r [:payload :text])
                        (get-in (acceptance/accepted-report
                                 (:parent final)
                                 (:parent-call-id r))
                                [:payload :text])))
                   reports)
           (= (:parent final) (edn/read-string (pr-str (:parent final))))
           (= (:child final) (edn/read-string (pr-str (:child final))))))))
