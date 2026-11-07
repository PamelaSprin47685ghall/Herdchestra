(ns hc.client.workbench.transport-test
  "D-26 regression: `transport/receipt!` must not require or auto-attach a
  subscription id.

  A receipt query is idempotent; the gateway answers it on the uid's
  current subscription.  Attaching a stale remembered generation makes
  `subscription-check` refuse the query with STALE_SUBSCRIPTION, and for a
  fire-and-forget send that refusal is dropped silently — the materials
  receipt sweep then re-asked the same dead question every 5 s while the
  pending lock stayed held."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hc.client.workbench.transport :as transport]
            [taoensso.sente :as sente]))

(defn- with-fake-channel
  "Run F with the transport channel replaced by an open fake and
  `sente/chsk-send!` capturing sent events."
  [channel-state f]
  (let [sent (atom [])
        original-channel @transport/channel]
    (try
      (reset! transport/channel channel-state)
      (with-redefs [sente/chsk-send!
                    (fn [chsk event]
                      (swap! sent conj event)
                      true)]
        (f sent))
      (finally
        (reset! transport/channel original-channel)))))

(deftest receipt-query-omits-subscription-id-unless-explicit
  (with-fake-channel
   {:status :open :chsk :fake-chsk :open? true
    :subscription-id 7 :client-id "c1"}
   (fn [sent]
     (testing "no remembered id is attached by default"
       (is (true? (transport/receipt!
                   {:command-id "cmd-1" :session-id "s-1"})))
       (is (= [[:hc/receipt {:command-id "cmd-1"
                             :session-id "s-1"}]]
              @sent))
       (testing "an explicit subscription id is still sent for fencing"
         (is (true? (transport/receipt!
                     {:command-id "cmd-2"
                      :subscription-id 3})))
         (is (= [[:hc/receipt {:command-id "cmd-2"
                               :subscription-id 3}]]
                (rest @sent))))))))

(deftest receipt-query-sends-without-any-known-subscription
  (with-fake-channel
   {:status :open :chsk :fake-chsk :open? true
    :subscription-id nil :client-id "c1"}
   (fn [sent]
     ;; Before the fix this returned false without touching the wire; the
     ;; gateway accepts a subscription-id-less query whenever the uid has a
     ;; current subscription, so the sweep must still ask.
     (is (true? (transport/receipt! {:command-id "cmd-9"})))
     (is (= [[:hc/receipt {:command-id "cmd-9"}]] @sent)))))

(deftest receipt-query-refuses-only-when-the-channel-is-closed
  (with-fake-channel
   {:status :closed :chsk :fake-chsk :open? false
    :subscription-id nil :client-id "c1"}
   (fn [sent]
     (is (false? (transport/receipt! {:command-id "cmd-1"})))
     (is (empty? @sent)))))
