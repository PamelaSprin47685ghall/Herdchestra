(ns hc.server.app.owner-report-restart-test
  "Durable report-pending restart test.

  The test uses the real Datalevin store and the real Owner runtime.  It
  stops the Owner after the parent accepted and projected a report but before
  the child applied the acknowledgement.  A second Owner starts from the same
  store, recovers the pending report, redelivers it, receives the stored
  idempotent acknowledgement, and reclaims the child resource.  The report
  text appears exactly once."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]
            [hc.server.app.owner-store :as owner-store]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- snapshot
  [handle]
  ((:snapshot handle)))

(defn- conversation-texts
  [handle]
  (mapv :text (get-in (snapshot handle) [:state :conversation])))

(defn- report-obligations
  [store session-id]
  (->> (storage/list-open-obligations
        store
        {:partition (str "session/" session-id) :session-id session-id})
       :obligations
       (filter #(= :report-pending (:phase %)))
       vec))

(defn- start-owner!
  ([session-id store self-ref ack-enabled?]
   (start-owner! session-id store self-ref ack-enabled? (fn [_] nil)))
  ([session-id store self-ref ack-enabled? owner-for]
   (let [bridge (owner/report-bridge (fn [] @self-ref) owner-for)
         handle (owner/start-owner! {:session-id session-id
                                     :store store
                                     :model-gateway
                                     {:start! (fn [request _deliver!]
                                                {:attempt-id (:attempt-id request)})
                                      :cancel! (fn [_] nil)}
                                     :report-sink! (:report-sink! bridge)
                                     :on-effect! (fn [event]
                                                   (when @ack-enabled?
                                                     ((:on-effect! bridge) event)))
                                     :close-store? false})]
     (reset! self-ref handle)
     handle)))

(defn- submit!
  [handle command-id payload]
  ((:submit-command! handle)
   {:command-id command-id :command-epoch 1 :payload payload}))

(defn- parent-call-started?
  [handle]
  (contains? #{:dispatching :running}
             (get-in (snapshot handle) [:state :calls "call-1" :state])))

(deftest pending-report-survives-parent-accepted-child-unsure-restart
  (let [dir (support/temp-dir "hc-report-restart-")
        text "full report\nline two \"quoted\" # hash — 世界"]
    (try
      (testing "the first process accepts and projects the report, then stops
                before the child acknowledgement is applied"
        (let [store (storage/open-store {:path dir :create? true
                                         :durability :sync})
              self-ref (atom nil)
              ack-enabled? (atom false)
              handle (start-owner! "s1"
                                   (owner-store/owner-store store "s1")
                                   self-ref ack-enabled?)]
          (try
            (is (= :accepted
                   (:status (submit! handle "c1"
                                     {:kind :send-message
                                      :text "start a parent call"}))))
            (is (wait-for #(parent-call-started? handle) 5000))
            (is (= :accepted
                   (:status (submit! handle "c2"
                                     {:kind :report/register
                                      :report
                                      {:report-id "rep-1"
                                       :parent-call-id "call-1"
                                       :payload {:kind :llm-report
                                                 :text text}
                                       :resource-ref
                                       {:attempt-id "a-1"}}}))))
            (is (wait-for #(some #{text} (conversation-texts handle)) 5000))
            (is (wait-for #(= :delivered
                              (get-in (snapshot handle)
                                      [:state :report-claims "rep-1" :state]))
                          5000))
            (is (= :pending
                   (get-in (snapshot handle)
                           [:state :reports :reports "rep-1" :phase])))
            (let [obligations (report-obligations store "s1")]
              (is (= 1 (count obligations)))
              (is (= :report-pending (:phase (first obligations))))
              (is (= text (get-in (first obligations)
                                  [:report :payload :text]))))
            (finally
              ((:stop! handle {:close-store? false}))
              (storage/close-store! store)))))

      (testing "the second process recovers the obligation, redelivers, and
                clears it without appending twice"
        (let [store (storage/open-store {:path dir})
              self-ref (atom nil)
              ack-enabled? (atom true)
              handle (start-owner! "s1"
                                   (owner-store/owner-store store "s1")
                                   self-ref ack-enabled?)]
          (try
            (is (wait-for #(and (empty? (get-in (snapshot handle)
                                                [:state :reports :reports]))
                                (nil? (get-in (snapshot handle)
                                              [:state :report-claims "rep-1"])))
                          10000))
            (is (= 1 (count (filter #{text} (conversation-texts handle)))))
            (is (empty? (report-obligations store "s1")))
            (finally
              ((:stop! handle {:close-store? false}))
              (storage/close-store! store)))))

      (finally
        (support/delete-tree! dir)))))

(deftest report-crosses-two-owners-with-idempotent-ack
  (let [dir (support/temp-dir "hc-report-cross-owner-")
        text "cross-owner report\nwith full text"]
    (try
      (let [store (storage/open-store {:path dir :create? true
                                       :durability :sync})
            owners (atom {})
            parent-ref (atom nil)
            child-ref (atom nil)
            owner-for (fn [session-id] (get @owners session-id))
            parent (start-owner! "s2"
                                 (owner-store/owner-store store "s2")
                                 parent-ref
                                 (atom true)
                                 owner-for)
            _ (swap! owners assoc "s2" parent)
            child (start-owner! "s1"
                                (owner-store/owner-store store "s1")
                                child-ref
                                (atom true)
                                owner-for)]
        (swap! owners assoc "s1" child)
        (try
          (is (= :accepted
                 (:status (submit! parent "p1"
                                   {:kind :send-message
                                    :text "start the parent call"}))))
          (is (wait-for #(parent-call-started? parent) 5000))
          (is (= :accepted
                 (:status (submit! child "c1"
                                   {:kind :report/register
                                    :report
                                    {:report-id "rep-x"
                                     :parent-call-id "call-1"
                                     :parent-session-id "s2"
                                     :payload {:kind :llm-report
                                               :text text}
                                     :resource-ref
                                     {:attempt-id "a-x"}}}))))
          (is (wait-for #(and (empty? (get-in (snapshot child)
                                              [:state :reports :reports]))
                              (nil? (get-in (snapshot child)
                                            [:state :report-claims "rep-x"])))
                        10000))
          (is (wait-for #(= 1 (count (filter #{text}
                                             (conversation-texts parent))))
                        10000))
          (is (= :accepted
                 (get-in (snapshot parent)
                         [:state :acceptance :accepted "call-1"
                          :ack :status])))
          (is (= "s1"
                 (get-in (snapshot parent)
                         [:state :acceptance :accepted "call-1"
                          :child-session-id])))
          (is (empty? (report-obligations store "s1")))
          (is (empty? (report-obligations store "s2")))
          (finally
            ((:stop! child {:close-store? false}))
            ((:stop! parent {:close-store? false}))
            (storage/close-store! store))))
      (finally
        (support/delete-tree! dir)))))
