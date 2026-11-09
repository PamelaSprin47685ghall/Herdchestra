(ns hc.server.app.lazy-owner-test
  "Lazy Owner creation and history windowing (P-SESSION)."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.commands :as app-commands]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.commands.durable :as durable]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]))

(defn- fake-owner
  [epoch]
  {:snapshot (fn []
               {:state {:commands {:command-epoch epoch
                                   :retired-through 0}}})
   :stop! (fn [] {:ok? true :stopped? true :drained? true})
   :submit-command! (fn [_]
                      {:status :accepted
                       :receipt {:status :accepted}})})

(def ^:private owner-epoch 7)

(defn- make-registry
  [persistence]
  (registry/registry
   {:persistence persistence
    :id-source (ids/counter-source)
    :now-fn (constantly 1000)
    :owner-factory (fn [_] (fake-owner owner-epoch))}))

(defn- make-router
  [persistence]
  (let [reg (make-registry persistence)
        router (app-commands/router
                {:registry reg
                 :settings nil
                 :session-config nil
                 :dedup-store (durable/memory-store)})]
    ;; The production router installs this factory at startup
    ;; (system.clj); without it ensure-live-owner! cannot lazily start an
    ;; Owner for a committed session.
    (swap! (:state router)
           assoc :session-owner-factory
           (fn [session]
             (registry/ensure-owner-for-session! reg session)))
    {:registry reg :router router}))

(defn- submit!
  [router request]
  (gateway-ports/submit-command! router request nil))

(defn- submit-capture!
  [router request]
  (let [reply (promise)]
    (gateway-ports/submit-command! router request #(deliver reply %))
    (deref reply 2000 ::timeout)))

(defn- commit-session!
  "Commit one session record with N visible messages into persistence."
  [persistence session-id message-count]
  (let [created (sessions/new-session
                 {:session-id session-id
                  :owner-id (str session-id "-owner")
                  :now 1000
                  :id-source (ids/counter-source
                              {:prefix (str session-id "-")})})]
    (is (:ok? created) (pr-str (:error created)))
    (let [session (reduce
                   (fn [session index]
                     (let [message-id (str session-id "-m" index)
                           result (message/new-message
                                   message-id
                                   (if (even? index) :user :assistant)
                                   (str "message " index)
                                   {:now (+ 1000 index)})]
                       (is (:ok? result) (pr-str (:error result)))
                       (-> session
                           (assoc-in [:messages message-id] (:message result))
                           (update :message-order (fnil conj []) message-id))))
                   (:session created)
                   (range message-count))]
      (let [commit (ports/commit-session! persistence 0 session)]
        (is (= :committed (:status commit)) (pr-str commit))
        session))))

(deftest session-ensure-creates-the-owner-on-first-access
  (let [persistence (ports/memory-persistence)
        {:keys [registry router]} (make-router persistence)
        created (registry/create-session! registry
                                          {:title "Lazy"
                                           :defer-owner? true})
        session-id (get-in created [:session :session-id])]
    (is (nil? (#'app-commands/owner-of router session-id))
        "startup does not register an Owner for every committed session")
    (let [result (submit-capture!
                  router
                  {:command-id "ensure-1"
                   :command-epoch 1
                   :payload {:kind :session/ensure
                             :session-id session-id}})]
      (is (true? (:ok? result)) (pr-str result))
      (is (= :accepted (get-in result [:receipt :status])))
      (is (= session-id (get-in result [:receipt :session-id])))
      (is (= owner-epoch (get-in result [:receipt :command-epoch]))
          "the receipt carries the lazily-created Owner's epoch")
      (is (some? (get-in @(:state router) [:owners session-id]))
          "the router registers the new Owner for dispatch and baseline"))
    (testing "a second ensure reuses the live Owner"
      (let [result (submit-capture!
                    router
                    {:command-id "ensure-2"
                     :command-epoch 1
                     :payload {:kind :session/ensure
                               :session-id session-id}})]
        (is (true? (:ok? result)))
        (is (= owner-epoch (get-in result [:receipt :command-epoch])))))
    (testing "missing and deleted sessions are refused without an Owner"
      (let [missing (submit-capture!
                     router
                     {:command-id "ensure-missing"
                      :command-epoch 1
                      :payload {:kind :session/ensure
                                :session-id "no-such-session"}})]
        (is (false? (:ok? missing)) (pr-str missing))
        (is (= :SESSION_OWNER_UNAVAILABLE
               (get-in missing [:error :code])))
        (let [deleted (registry/create-session! registry
                                                {:title "Gone"
                                                 :defer-owner? true})
              deleted-id (get-in deleted [:session :session-id])
              _ (registry/close-session! registry deleted-id {:delete? true})
              result (submit-capture!
                      router
                      {:command-id "ensure-deleted"
                       :command-epoch 1
                       :payload {:kind :session/ensure
                                 :session-id deleted-id}})]
          (is (false? (:ok? result)) (pr-str result))
          (is (= :SESSION_OWNER_UNAVAILABLE
                 (get-in result [:error :code])))
          (is (nil? (get-in @(:state router) [:owners deleted-id]))))))))

(deftest history-messages-pages-the-visible-window
  (let [persistence (ports/memory-persistence)
        {:keys [router]} (make-router persistence)
        session (commit-session! persistence "s1" 10)
        session-id (:session-id session)
        ids (vec (:message-order session))]
    (is (= 10 (count ids)))
    (let [first (submit-capture!
                 router
                 {:command-id "hist-1"
                  :command-epoch 1
                  :payload {:kind :history/messages
                            :session-id session-id
                            :limit 4}})]
      (is (true? (:ok? first)) (pr-str first))
      (let [receipt (:receipt first)]
        (is (= session-id (:session-id receipt)))
        (is (= ids (:message-ids receipt))
            ":message-ids always carries the full visible list")
        (is (= ["s1-m6" "s1-m7" "s1-m8" "s1-m9"] (:message-order receipt))
            "the default page is the newest :limit messages")
        (is (= 4 (count (:messages receipt))))
        (is (true? (:has-older? receipt)))
        (is (= owner-epoch (:command-epoch receipt))
            "the lazy Owner supplies the command epoch")
        (testing "paging backwards with :before is exclusive"
          (let [second (submit-capture!
                        router
                        {:command-id "hist-2"
                         :command-epoch 1
                         :payload {:kind :history/messages
                                   :session-id session-id
                                   :limit 4
                                   :before "s1-m6"}})
                receipt2 (:receipt second)]
            (is (true? (:ok? second)) (pr-str second))
            (is (= ["s1-m2" "s1-m3" "s1-m4" "s1-m5"]
                   (:message-order receipt2)))
            (is (= ids (:message-ids receipt2)))
            (is (true? (:has-older? receipt2))))
          (let [last (submit-capture!
                      router
                      {:command-id "hist-3"
                       :command-epoch 1
                       :payload {:kind :history/messages
                                 :session-id session-id
                                 :limit 4
                                 :before "s1-m2"}})
                receipt3 (:receipt last)]
            (is (true? (:ok? last)) (pr-str last))
            (is (= ["s1-m0" "s1-m1"] (:message-order receipt3)))
            (is (false? (:has-older? receipt3))))))
      (testing "fetched windows are recorded for the baseline publisher"
        (is (= (set ids)
               (get @(get-in @(:state router) [:published-windows]) "s1"))
            "every fetched page id is tracked for the baseline window")))))

(deftest baseline-publishes-the-active-window-and-no-inactive-entities
  (let [persistence (ports/memory-persistence)
        {:keys [registry]} (make-router persistence)
        s1 (commit-session! persistence "s1" 10)
        s2 (commit-session! persistence "s2" 10)
        s1-ids (mapv #(str "s1-m" %) (range 10))
        s2-ids (mapv #(str "s2-m" %) (range 10))
        published-windows (atom {})
        history-window-size (atom 3)
        opts (fn [extra]
               (merge {:sessions [s1 s2]
                       :session-records [s1 s2]
                       :owner-snapshots {}
                       :active-session "s1"
                       :published-windows published-windows
                       :history-window-size @history-window-size}
                      extra))
        baseline (snapshot/baseline
                  (opts nil))]
    (testing "the active session keeps its newest 3 message entities"
      (is (= #{"s1-m7" "s1-m8" "s1-m9"}
             (set (keys (:message/by-id baseline))))
          "only the active session's window enters :message/by-id"))
    (testing "the inactive session publishes no message entity"
      (is (= #{"s1"}
             (set (map :session-id (vals (:message/by-id baseline)))))
          "a non-active session defaults to a zero window"))
    (testing "the inactive default is zero even without a window option"
      (let [plain (snapshot/baseline
                   {:sessions [s1 s2]
                    :session-records [s1 s2]
                    :owner-snapshots {}
                    :active-session "s1"})]
        (is (= (set s1-ids) (set (keys (:message/by-id plain))))
            "the active session keeps its full history")
        (is (= #{"s1"}
               (set (map :session-id (vals (:message/by-id plain)))))
            "the inactive session still publishes no entity")))
    (testing ":session/history keeps the full visible id list"
      (is (= s1-ids
             (get-in baseline [:session/history "s1" :message-ids])))
      (is (= s2-ids
             (get-in baseline [:session/history "s2" :message-ids]))))
    (testing "a fetched window unions with the active tail"
      (swap! published-windows assoc "s1" #{"s1-m0" "s1-m1"})
      (let [baseline' (snapshot/baseline (opts nil))]
        (is (= #{"s1-m0" "s1-m1" "s1-m7" "s1-m8" "s1-m9"}
               (set (keys (:message/by-id baseline')))))
        (is (= s1-ids
               (get-in baseline' [:session/history "s1" :message-ids])))))
    (testing "an explicit inactive window publishes that tail"
      (let [expanded (snapshot/baseline
                      (opts {:inactive-history-window-size 2}))]
        (is (= #{"s1-m0" "s1-m1" "s1-m7" "s1-m8" "s1-m9"
                 "s2-m8" "s2-m9"}
               (set (keys (:message/by-id expanded)))))))))
