(ns hc.server.commands-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.commands :as commands]
            [hc.server.commands.dispatch :as dispatch]
            [hc.server.commands.durable :as durable]
            [hc.server.commands.epoch :as epoch]
            [hc.server.commands.storage :as command-storage]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as storage-support]))

;; ---------------------------------------------------------------------------
;; Helpers and generators

(defn- fresh-state
  "A domain state with epoch 1 active."
  []
  (:state (commands/issue-epoch (commands/new-state))))

(defn- request
  ([command-id payload]
   (request command-id 1 payload))
  ([command-id command-epoch payload]
   {:command-id command-id
    :command-epoch command-epoch
    :payload payload}))

(def gen-command-id
  (gen/such-that #(not (str/blank? %)) gen/string-alphanumeric 50))

(def gen-payload
  (gen/one-of [gen/string
               gen/small-integer
               gen/boolean
               (gen/return nil)
               (gen/vector gen/small-integer 0 5)
               (gen/fmap #(into {} %)
                         (gen/vector (gen/tuple gen/keyword gen/string) 0 4))]))

(def gen-request
  (gen/fmap (fn [[command-id payload]]
              (request command-id payload))
            (gen/tuple gen-command-id gen-payload)))

;; ---------------------------------------------------------------------------
;; Epoch lifecycle

(deftest epoch-lifecycle
  (testing "new state has no active epoch"
    (let [state (commands/new-state)]
      (is (nil? (commands/active-epoch state)))
      (is (= 0 (commands/retired-through state)))
      (is (empty? (commands/check-invariants state)))))

  (testing "issuing an epoch retires the previous one"
    (let [first (commands/issue-epoch (commands/new-state))
          second (commands/issue-epoch (:state first))]
      (is (true? (:ok? first)))
      (is (= 1 (:epoch first)))
      (is (= 2 (:epoch second)))
      (is (= 1 (:retired-epoch second)))
      (is (= 1 (commands/retired-through (:state second))))
      (is (= 2 (commands/active-epoch (:state second))))
      (is (empty? (commands/check-invariants (:state second))))))

  (testing "retiring without an active epoch is idempotent"
    (let [state (commands/new-state)
          result (commands/retire-epoch state)]
      (is (true? (:ok? result)))
      (is (nil? (:retired-epoch result)))
      (is (= state (:state result)))))

  (testing "a retired epoch number is never reused"
    (let [retired (commands/retire-epoch
                   (:state (commands/issue-epoch (commands/new-state))))
          next (commands/issue-epoch (:state retired))]
      (is (= 1 (commands/retired-through (:state retired))))
      (is (= 2 (:epoch next)))
      (is (= 2 (commands/active-epoch (:state next))))
      (is (= 1 (commands/retired-through (:state next))))))

  (testing "epoch classification is total"
    (let [window {:command-epoch 3 :retired-through 2}]
      (is (= :active (epoch/status window 3)))
      (is (= :retired (epoch/status window 2)))
      (is (= :retired (epoch/status window 1)))
      (is (= :unknown (epoch/status window 4)))
      (is (= :invalid (epoch/status window 0)))
      (is (= :invalid (epoch/status window nil)))
      (is (= :invalid (epoch/status window "3"))))))

;; ---------------------------------------------------------------------------
;; Deduplication

(deftest accept-and-replay
  (let [state (fresh-state)
        req (request "cmd-1" {:text "hello"})
        first (commands/accept state req)
        second (commands/accept (:state first) req)]
    (is (true? (:ok? first)))
    (is (= {:status :accepted
            :command-id "cmd-1"
            :command-epoch 1}
           (:receipt first)))
    (is (false? (:replayed? first)))
    (is (true? (:ok? second)))
    (is (true? (:replayed? second)))
    (is (= (:receipt first) (:receipt second)))
    (is (= (:state first) (:state second)))
    (is (= (:receipt first) (commands/receipt-for (:state first) "cmd-1")))
    (is (= (:receipt first)
           (commands/receipt-for (:state first) {:command-id "cmd-1"})))
    (is (= 1 (count (get-in second [:state :receipts]))))
    (is (empty? (commands/check-invariants (:state second))))
    (let [with-metadata (commands/accept (:state first)
                                         (assoc req :client-id "window-2"))]
      (is (true? (:ok? with-metadata)))
      (is (true? (:replayed? with-metadata)))
      (is (= (:receipt first) (:receipt with-metadata)))
      (is (= (:state first) (:state with-metadata))))))

(deftest payload-mismatch-is-refused
  (let [state (fresh-state)
        req (request "cmd-1" {:text "hello"})
        first (commands/accept state req)
        mismatch (commands/accept (:state first)
                                  (assoc req :payload {:text "bye"}))]
    (is (true? (:ok? first)))
    (is (false? (:ok? mismatch)))
    (is (= :COMMAND_ID_PAYLOAD_CONFLICT
           (get-in mismatch [:error :code])))
    (is (= :payload-mismatch
           (get-in mismatch [:error :details :reason])))
    (is (= {:text "hello"}
           (get-in mismatch [:error :details :accepted-payload])))
    (is (= {:text "bye"}
           (get-in mismatch [:error :details :received-payload])))
    (is (= (:state first) (:state mismatch)))
    (is (empty? (:effects mismatch)))))

(deftest caller-receipt-is-returned-on-replay
  (let [state (fresh-state)
        req (request "cmd-1" {:text "hello"})
        receipt {:status :accepted :result {:message-id 7}}
        first (commands/accept state req receipt)
        second (commands/accept (:state first)
                                req
                                {:status :different})]
    (is (= receipt (:receipt first)))
    (is (= receipt (:receipt second)))
    (is (true? (:replayed? second)))
    (is (= (:state first) (:state second)))))

(deftest distinct-command-ids-are-independent
  (let [state (fresh-state)
        first (commands/accept state (request "cmd-a" 1 :a))
        second (commands/accept (:state first) (request "cmd-b" 1 :b))]
    (is (true? (:ok? first)))
    (is (true? (:ok? second)))
    (is (= 2 (count (get-in second [:state :receipts]))))
    (is (= 1 (:command-epoch (:receipt first))))
    (is (= 1 (:command-epoch (:receipt second))))))

(deftest complete-replaces-the-stored-receipt
  (let [state (fresh-state)
        req (request "cmd-1" {:text "hello"})
        accepted (commands/accept state req {:status :dispatching})
        completed (commands/complete (:state accepted)
                                     "cmd-1"
                                     {:status :accepted :value 42})
        replay (commands/accept (:state completed) req)]
    (is (true? (:ok? accepted)))
    (is (true? (:ok? completed)))
    (is (= {:status :accepted :value 42} (:receipt completed)))
    (is (= {:status :accepted :value 42}
           (commands/receipt-for (:state completed) "cmd-1")))
    (is (true? (:replayed? replay)))
    (is (= {:status :accepted :value 42} (:receipt replay)))
    (is (= (:state completed) (:state replay)))))

(deftest complete-refuses-unknown-and-stale-receipts
  (let [state (fresh-state)
        unknown (commands/complete state "cmd-missing" {:status :accepted})]
    (is (false? (:ok? unknown)))
    (is (= :COMMAND_ID_UNKNOWN (get-in unknown [:error :code])))
    (is (= state (:state unknown))))
  (let [state (fresh-state)
        accepted (commands/accept state (request "cmd-1" nil))
        stale (assoc (:state accepted) :command-epoch 2)
        result (commands/complete stale "cmd-1" {:status :accepted})]
    (is (false? (:ok? result)))
    (is (= :COMMAND_STATE_INVALID (get-in result [:error :code])))
    (is (= stale (:state result)))))

;; ---------------------------------------------------------------------------
;; Retired and unknown epochs

(deftest retired-epochs-are-never-executed
  (let [state (fresh-state)
        req (request "cmd-1" {:text "hello"})
        accepted (commands/accept state req)
        retired (commands/retire-epoch (:state accepted))
        replay (commands/accept (:state retired) req)
        stale-mismatch (commands/accept (:state retired)
                                        (assoc req :payload {:text "bye"}))
        unseen (commands/accept (:state retired)
                                (request "cmd-never-seen" 1 nil))]
    (is (true? (:ok? retired)))
    (is (nil? (commands/active-epoch (:state retired))))
    (is (false? (:ok? replay)))
    (is (= :COMMAND_EPOCH_RETIRED (get-in replay [:error :code])))
    (is (= :resync-state (get-in replay [:error :details :action])))
    (is (= (:state retired) (:state replay)))
    (is (false? (:ok? stale-mismatch)))
    (is (= :COMMAND_EPOCH_RETIRED
           (get-in stale-mismatch [:error :code])))
    (is (= (:state retired) (:state stale-mismatch)))
    (is (false? (:ok? unseen)))
    (is (= :COMMAND_EPOCH_RETIRED (get-in unseen [:error :code])))
    (is (= (:state retired) (:state unseen)))
    (is (nil? (commands/receipt-for (:state retired) "cmd-1")))
    (is (empty? (get-in (:state retired) [:receipts])))))

(deftest a-new-epoch-opens-a-fresh-window
  (let [state (fresh-state)
        req (request "cmd-1" {:text "hello"})
        first (commands/accept state req)
        next (commands/issue-epoch (:state first))
        second (commands/accept (:state next)
                                (request "cmd-1" 2 {:text "hello"}))]
    (is (= 2 (commands/active-epoch (:state next))))
    (is (true? (:ok? second)))
    (is (false? (:replayed? second)))
    (is (= 2 (:command-epoch (:receipt second))))
    (is (= 1 (count (get-in second [:state :receipts]))))
    (is (empty? (commands/check-invariants (:state second))))))

(deftest unknown-epochs-are-refused
  (let [state (fresh-state)
        result (commands/accept state
                                (request "cmd-1" 99 {:text "hello"}))
        accepted (commands/accept state (request "cmd-1" {:text "hello"}))
        future (commands/accept (:state accepted)
                                (request "cmd-1" 2 {:text "hello"}))]
    (is (false? (:ok? result)))
    (is (= :COMMAND_EPOCH_UNKNOWN (get-in result [:error :code])))
    (is (= :resync-state (get-in result [:error :details :action])))
    (is (= state (:state result)))
    (is (false? (:ok? future)))
    (is (= :COMMAND_EPOCH_UNKNOWN (get-in future [:error :code])))
    (is (= (:state accepted) (:state future)))))

(deftest invalid-requests-do-not-mutate-state
  (let [state (fresh-state)]
    (doseq [[req code]
            [[nil :COMMAND_REQUEST_INVALID]
             ["not-a-map" :COMMAND_REQUEST_INVALID]
             [{:command-epoch 1 :payload nil} :COMMAND_ID_REQUIRED]
             [{:command-id "  " :command-epoch 1 :payload nil}
              :COMMAND_ID_REQUIRED]
             [{:command-id :cmd :command-epoch 1 :payload nil}
              :COMMAND_ID_REQUIRED]
             [{:command-id "cmd-1" :payload nil}
              :COMMAND_EPOCH_INVALID]
             [{:command-id "cmd-1" :command-epoch 0 :payload nil}
              :COMMAND_EPOCH_INVALID]
             [{:command-id "cmd-1" :command-epoch -1 :payload nil}
              :COMMAND_EPOCH_INVALID]
             [{:command-id "cmd-1" :command-epoch "1" :payload nil}
              :COMMAND_EPOCH_INVALID]]]
      (let [result (commands/accept state req)]
        (is (false? (:ok? result)) (pr-str req))
        (is (= code (get-in result [:error :code])) (pr-str req))
        (is (= state (:state result)) (pr-str req)))))
  (testing "a corrupt receipt registry is a state error"
    (let [state (assoc (fresh-state) :receipts nil)
          result (commands/accept state (request "cmd-1" nil))]
      (is (false? (:ok? result)))
      (is (= :COMMAND_STATE_INVALID (get-in result [:error :code])))
      (is (= state (:state result))))))

(deftest invariant-check-detects-corruption
  (let [state (fresh-state)
        accepted (commands/accept state (request "cmd-1" {:text "hello"}))
        stale (assoc (:state accepted) :command-epoch 2)]
    (is (some #(= :receipt-epoch-mismatch (:invariant %))
              (commands/check-invariants stale)))
    (let [retired (commands/retire-epoch (:state accepted))
          orphan (assoc (:state retired)
                        :receipts (:receipts (:state accepted)))]
      (is (some #(= :receipts-without-active-epoch (:invariant %))
                (commands/check-invariants orphan))))))

;; ---------------------------------------------------------------------------
;; Durable gate

(defn- durable-window
  [command-epoch retired-through]
  {:command-epoch command-epoch
   :retired-through retired-through})

(deftest durable-gate-survives-restart
  (let [store (durable/memory-store)
        first-gate (durable/gate store)
        window (durable-window 1 0)
        req (request "cmd-1" {:text "hello"})
        receipt {:status :accepted :session-id "s-1"}
        first (durable/accept! first-gate window req receipt)
        second-gate (durable/gate store)
        replay (durable/accept! second-gate window req nil)]
    (is (true? (:ok? first)))
    (is (= receipt (:receipt first)))
    (is (true? (:ok? replay)))
    (is (true? (:replayed? replay)))
    (is (= receipt (:receipt replay)))
    (is (= receipt (durable/receipt-for second-gate "cmd-1")))
    (is (empty? (commands/check-invariants (durable/state second-gate))))
    (durable/close! second-gate)))

(deftest durable-gate-detects-mismatch-and-retirement
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        req (request "cmd-1" {:text "hello"})
        _ (durable/accept! gate window req {:status :accepted})
        mismatch (durable/accept! gate window
                                   (assoc req :payload {:text "bye"})
                                   nil)
        advanced (durable/sync-window! gate (durable-window 2 1))
        old (durable/accept! gate window req nil)
        current (durable/accept! gate (durable-window 2 1)
                                (assoc req :command-epoch 2)
                                nil)]
    (is (false? (:ok? mismatch)))
    (is (= :COMMAND_ID_PAYLOAD_CONFLICT (get-in mismatch [:error :code])))
    (is (true? (:ok? advanced)))
    (is (false? (:ok? old)))
    (is (= :COMMAND_EPOCH_RETIRED (get-in old [:error :code])))
    (is (true? (:ok? current)))
    (is (false? (:replayed? current)))
    (is (= 2 (:command-epoch (:receipt current))))
    (is (empty? (commands/check-invariants (durable/state gate))))
    (durable/close! gate)))

(deftest durable-gate-completes-a-placeholder
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        req (request "cmd-1" {:text "hello"})
        _ (durable/accept! gate window req {:status :dispatching})
        completed (durable/complete! gate "cmd-1"
                                     {:status :accepted :value 42})
        restarted (durable/gate store)
        replay (durable/accept! restarted window req nil)]
    (is (true? (:ok? completed)))
    (is (true? (:ok? replay)))
    (is (true? (:replayed? replay)))
    (is (= {:status :accepted :value 42} (:receipt replay)))
    (is (= {:status :accepted :value 42}
           (durable/receipt-for restarted "cmd-1")))
    (durable/close! restarted)))

(deftest durable-gate-refuses-an-invalid-window
  (let [gate (durable/gate (durable/memory-store))
        result (durable/accept! gate
                                {:command-epoch 0 :retired-through 0}
                                (request "cmd-1" nil)
                                nil)]
    (is (false? (:ok? result)))
    (is (= :COMMAND_WINDOW_INVALID (get-in result [:error :code])))
    (durable/close! gate)))

(deftest durable-gate-scopes-command-ids-by-session
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        req (request "cmd-1" {:text "hello"})
        first (durable/accept! gate window "s1" req
                                {:status :accepted :s 1})
        second (durable/accept! gate window "s2" req
                                 {:status :accepted :s 2})
        replay (durable/accept! gate window "s1" req nil)
        mismatch (durable/accept! gate window "s1"
                                   (assoc req :payload {:text "bye"})
                                   nil)
        completed (durable/complete! gate "s1" "cmd-1"
                                     {:status :accepted :s 1 :done true})]
    (is (true? (:ok? first)))
    (is (true? (:ok? second)))
    (is (false? (:replayed? second)))
    (is (true? (:replayed? replay)))
    (is (= (:receipt first) (:receipt replay)))
    (is (false? (:ok? mismatch)))
    (is (= :COMMAND_ID_PAYLOAD_CONFLICT (get-in mismatch [:error :code])))
    (is (true? (:ok? completed)))
    (is (= {:status :accepted :s 1 :done true}
           (durable/receipt-for gate "s1" "cmd-1")))
    (is (= {:status :accepted :s 1 :done true}
           (durable/receipt-for gate "cmd-1")))
    (is (= 2 (count (get-in (durable/state gate) [:receipts]))))
    (is (empty? (commands/check-invariants (durable/state gate))))
    (durable/close! gate)))

(deftest receipt-registry-supports-mixed-key-shapes
  (let [state (fresh-state)
        scoped (commands/accept
                state
                (assoc (request "cmd-scoped" {:x 1})
                       :command-key ["s1" "cmd-scoped"]))
        bare (commands/accept (:state scoped) (request "cmd-bare" {:x 2}))
        scoped-receipt (:receipt scoped)
        bare-receipt (:receipt bare)]
    (is (true? (:ok? scoped)))
    (is (true? (:ok? bare)))
    (is (= scoped-receipt
           (commands/receipt-for (:state bare) ["s1" "cmd-scoped"])))
    (is (= scoped-receipt
           (commands/receipt-for (:state bare)
                                 {:scope "s1" :command-id "cmd-scoped"})))
    (is (= bare-receipt (commands/receipt-for (:state bare) "cmd-bare")))
    (is (nil? (commands/receipt-for (:state bare)
                                    ["s2" "cmd-scoped"])))
    (is (nil? (commands/receipt-for (:state bare) "cmd-missing")))
    (is (empty? (commands/check-invariants (:state bare))))))

(deftest dispatch-wrapper-replays-without-side-effect
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        calls (atom 0)
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [_ reply]
                               (swap! calls inc)
                               (let [result {:ok? true
                                             :receipt {:status :accepted
                                                       :session-id "s-new"}}]
                                 (reply result)
                                 result))})
        request {:command-id "cmd-create"
                 :command-epoch 1
                 :payload {:kind :session/create :title "New"}}
        first-result ((:submit! wrapper) request identity)
        second-result ((:submit! wrapper) request identity)]
    (is (= 1 @calls))
    (is (true? (:ok? first-result)))
    (is (true? (:replayed? second-result)))
    (is (= (:receipt first-result) (:receipt second-result)))
    (is (= 1 (count (get-in (durable/state gate) [:receipts]))))
    (durable/close! gate)))

(deftest dispatch-wrapper-refuses-mismatch-and-retired-before-dispatch
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        calls (atom 0)
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [_ reply]
                               (swap! calls inc)
                               (let [result {:ok? true
                                             :receipt {:status :accepted}}]
                                 (reply result)
                                 result))})
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :settings/save-role :roles {}}}
        _ ((:submit! wrapper) req identity)
        mismatch ((:submit! wrapper)
                  (assoc-in req [:payload :roles] {:main "x"})
                  identity)
        advanced (durable/sync-window! gate (durable-window 2 1))
        retired ((:submit! wrapper) req identity)]
    (is (= 1 @calls))
    (is (= :COMMAND_ID_PAYLOAD_CONFLICT (get-in mismatch [:error :code])))
    (is (= 1 @calls))
    (is (true? (:ok? advanced)))
    (is (= :COMMAND_EPOCH_RETIRED (get-in retired [:error :code])))
    (is (= 1 @calls))
    (durable/close! gate)))

(deftest dispatch-wrapper-survives-restart
  (let [store (durable/memory-store)
        window (durable-window 1 0)
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :settings/save-role :roles {}}}
        first-calls (atom 0)
        first-gate (durable/gate store)
        first-wrapper (dispatch/wrap
                       {:gate first-gate
                        :window-fn (constantly window)
                        :scope-fn (constantly "s1")
                        :dispatch! (fn [_ reply]
                                     (swap! first-calls inc)
                                     (let [result {:ok? true
                                                   :receipt {:status :accepted :v 1}}]
                                       (reply result)
                                       result))})
        _ ((:submit! first-wrapper) req identity)
        second-calls (atom 0)
        second-gate (durable/gate store)
        second-wrapper (dispatch/wrap
                        {:gate second-gate
                         :window-fn (constantly window)
                         :scope-fn (constantly "s1")
                         :dispatch! (fn [_ reply]
                                      (swap! second-calls inc)
                                      (let [result {:ok? true
                                                    :receipt {:status :accepted :v 2}}]
                                        (reply result)
                                        result))})
        replay ((:submit! second-wrapper) req identity)]
    (is (= 1 @first-calls))
    (is (zero? @second-calls))
    (is (true? (:replayed? replay)))
    (is (= {:status :accepted :v 1} (:receipt replay)))
    (durable/close! second-gate)))

(deftest dispatch-wrapper-completes-async-replies-once
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        pending (atom nil)
        replies (atom [])
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [request reply]
                               (reset! pending [request reply])
                               nil)})
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :settings/save-role :roles {}}}
        result ((:submit! wrapper) req #(swap! replies conj %))]
    (is (nil? result))
    (let [[_ reply] @pending]
      (reply {:ok? true :receipt {:status :accepted :v 7}})
      (reply {:ok? true :receipt {:status :accepted :v 8}}))
    (is (= 1 (count @replies)))
    (is (= {:status :accepted :v 7}
           (get-in (first @replies) [:receipt])))
    (is (= {:status :accepted :v 7}
           (durable/receipt-for gate "s1" "cmd-1")))
    (durable/close! gate)))

(deftest dispatch-wrapper-delegates-owner-and-read-only-kinds
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        calls (atom [])
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [request reply]
                               (swap! calls conj (dispatch/kind-of request))
                               (let [result {:ok? true
                                             :receipt {:status :accepted}}]
                                 (reply result)
                                 result))})
        owner-request {:command-id "cmd-1"
                       :command-epoch 1
                       :payload {:kind :stop-run :session-id "s1"}}
        read-request {:command-id "cmd-2"
                      :command-epoch 1
                      :payload {:kind :session/list}}]
    ((:submit! wrapper) owner-request identity)
    ((:submit! wrapper) read-request identity)
    (is (= [:stop-run :session/list] @calls))
    (is (empty? (get-in (durable/state gate) [:receipts])))
    (durable/close! gate)))

(deftest dispatch-wrapper-gates-all-component-side-effects
  (doseq [kind [:session/create :session/delete :history/branch
                :send-message :user/message
                :settings/save-role :settings/test-connection
                :materials/write :diagnostics/capture :backup/create
                :scheduler/create :extensions/install-skill
                :browser/open-tab :documents/parse-pages :search/run
                :speech/transcribe]]
    (let [store (durable/memory-store)
          gate (durable/gate store)
          window (durable-window 1 0)
          calls (atom 0)
          wrapper (dispatch/wrap
                   {:gate gate
                    :window-fn (constantly window)
                    :scope-fn (constantly "s1")
                    :dispatch! (fn [_ reply]
                                 (swap! calls inc)
                                 (let [result {:ok? true
                                               :receipt {:status :accepted
                                                         :kind kind}}]
                                   (reply result)
                                   result))})
          req {:command-id (str "cmd-" (name kind))
               :command-epoch 1
               :payload {:kind kind :session-id "s1"}}
          first-result ((:submit! wrapper) req identity)
          replay ((:submit! wrapper) req identity)]
      (is (= 1 @calls) (str kind))
      (is (true? (:ok? first-result)) (str kind))
      (is (true? (:replayed? replay)) (str kind))
      (is (= (:receipt first-result) (:receipt replay)) (str kind))
      (is (= 1 (count (get-in (durable/state gate) [:receipts]))) (str kind))
      (durable/close! gate))))

(deftest storage-backed-gate-survives-store-reopen
  (let [dir (storage-support/temp-dir "hc-command-gate-")
        window (durable-window 1 0)
        req (request "cmd-1" {:text "hello"})
        receipt {:status :accepted :session-id "s-new"}]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (let [gate (durable/gate (command-storage/store store))
                first (durable/accept! gate window "s1" req receipt)]
            (is (true? (:ok? first)))
            (is (= receipt (:receipt first)))
            (durable/close! gate))
          (finally
            (storage/close-store! store))))
      (let [store (storage/open-store {:path dir})]
        (try
          (let [gate (durable/gate (command-storage/store store))
                replay (durable/accept! gate window "s1" req nil)]
            (is (true? (:ok? replay)))
            (is (true? (:replayed? replay)))
            (is (= receipt (:receipt replay)))
            (is (= receipt (durable/receipt-for gate "s1" "cmd-1")))
            (is (= receipt (durable/receipt-for gate "cmd-1")))
            (is (empty? (commands/check-invariants (durable/state gate))))
            (durable/close! gate))
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))

(deftest dispatch-wrapper-stores-refusal-without-repeating-side-effect
  (let [store (durable/memory-store)
        gate (durable/gate store)
        window (durable-window 1 0)
        calls (atom 0)
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [_ reply]
                               (swap! calls inc)
                               (let [result {:ok? false
                                             :error {:code :SETTINGS_CONFLICT
                                                     :message "conflict"}}]
                                 (reply result)
                                 result))})
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :settings/save-role :roles {}}}
        first-result ((:submit! wrapper) req identity)
        replay ((:submit! wrapper) req identity)]
    (is (= 1 @calls))
    (is (false? (:ok? first-result)))
    (is (true? (:replayed? replay)))
    (is (= :SETTINGS_CONFLICT (get-in replay [:receipt :error :code])))
    (durable/close! gate)))

;; ---------------------------------------------------------------------------
;; Properties

(defspec accepted-command-replays-without-state-change 80
  (prop/for-all [req gen-request]
    (let [state (fresh-state)
          first (commands/accept state req)
          second (commands/accept (:state first) req)]
      (and (true? (:ok? first))
           (true? (:ok? second))
           (true? (:replayed? second))
           (= (:receipt first) (:receipt second))
           (= (:state first) (:state second))
           (empty? (commands/check-invariants (:state second)))))))

(defspec payload-mismatch-never-changes-state 80
  (prop/for-all [[req other]
                 (gen/such-that (fn [[req other]]
                                  (not= (:payload req) other))
                                (gen/tuple gen-request gen-payload)
                                100)]
    (let [state (fresh-state)
          first (commands/accept state req)
          mismatch (commands/accept (:state first)
                                    (assoc req :payload other))]
      (and (true? (:ok? first))
           (false? (:ok? mismatch))
           (= :COMMAND_ID_PAYLOAD_CONFLICT
              (get-in mismatch [:error :code]))
           (= (:state first) (:state mismatch))))))

(defspec retired-epoch-never-accepts 60
  (prop/for-all [req gen-request]
    (let [state (fresh-state)
          first (commands/accept state req)
          retired (commands/retire-epoch (:state first))
          replay (commands/accept (:state retired) req)]
      (and (true? (:ok? first))
           (true? (:ok? retired))
           (false? (:ok? replay))
           (= :COMMAND_EPOCH_RETIRED (get-in replay [:error :code]))
           (= (:state retired) (:state replay))
           (empty? (get-in (:state retired) [:receipts]))))))

(defspec unknown-epoch-never-accepts 60
  (prop/for-all [req gen-request
                 delta (gen/large-integer* {:min 1 :max 100})]
    (let [state (fresh-state)
          result (commands/accept state
                                  (update req :command-epoch + delta))]
      (and (false? (:ok? result))
           (= :COMMAND_EPOCH_UNKNOWN (get-in result [:error :code]))
           (= state (:state result))))))

(defspec epoch-transitions-preserve-invariants 60
  (prop/for-all [ops (gen/vector (gen/elements [:issue :retire]) 0 40)]
    (loop [state (commands/new-state)
           ops (seq ops)
           last-retired 0]
      (if-let [op (first ops)]
        (let [result (case op
                       :issue (commands/issue-epoch state)
                       :retire (commands/retire-epoch state))
              state' (:state result)
              retired' (commands/retired-through state')
              active' (commands/active-epoch state')]
          (if (and (true? (:ok? result))
                   (>= retired' last-retired)
                   (or (nil? active') (> active' retired'))
                   (empty? (commands/check-invariants state')))
            (recur state' (next ops) retired')
            false))
        true))))

(defspec mixed-command-and-epoch-transitions-preserve-invariants 60
  (prop/for-all [ops (gen/vector (gen/one-of [gen-request
                                              (gen/return :issue)
                                              (gen/return :retire)])
                                 0 30)]
    (loop [state (commands/new-state)
           ops (seq ops)]
      (if-let [op (first ops)]
        (let [result (cond
                       (= op :issue) (commands/issue-epoch state)
                       (= op :retire) (commands/retire-epoch state)
                       :else (commands/accept
                              state
                              (assoc op :command-epoch
                                     (or (commands/active-epoch state) 1))))
              state' (:state result)]
          (if (and (or (:ok? result) (= state state'))
                   (empty? (commands/check-invariants state')))
            (recur state' (next ops))
            false))
        true))))

(defspec every-accepted-command-keeps-its-receipt 60
  (prop/for-all [payloads (gen/vector gen-payload 0 10)]
    (if (empty? payloads)
      true
      (let [state (fresh-state)
            results (rest
                     (reductions
                      (fn [s [index payload]]
                        (commands/accept
                         (or (:state s) s)
                         (request (str "cmd-" index) payload)))
                      state
                      (map-indexed vector payloads)))
            final (last results)]
        (and (every? :ok? results)
             (every? (fn [[index _]]
                       (= {:status :accepted
                           :command-id (str "cmd-" index)
                           :command-epoch 1}
                          (get-in final [:state :receipts
                                         (str "cmd-" index)
                                         :receipt])))
                     (map-indexed vector payloads))
             (empty? (commands/check-invariants (:state final))))))))

(deftest receipt-for-resolves-owner-query-maps
  (let [state (fresh-state)
        scoped (commands/accept
                state
                (assoc (request "cmd-scoped" {:text "hello"})
                       :command-key ["session-a" "cmd-scoped"]))
        receipt (:receipt scoped)]
    (is (true? (:ok? scoped)))
    (is (= receipt
           (commands/receipt-for (:state scoped)
                                 {:scope "session-a"
                                  :command-id "cmd-scoped"})))
    (is (= receipt
           (commands/receipt-for (:state scoped)
                                 {:command-key ["session-a" "cmd-scoped"]
                                  :command-id "cmd-scoped"})))
    (is (nil? (commands/receipt-for (:state scoped)
                                    {:scope "session-b"
                                     :command-id "cmd-scoped"})))))

;; ---------------------------------------------------------------------------
;; Receipt-index consistency and epoch rotation

(deftest epoch-transitions-clear-the-receipt-index
  (let [state (assoc (fresh-state)
                     :receipt-index
                     {"cmd-1" {:command-id "cmd-1"
                               :command-key "cmd-1"
                               :command-epoch 1
                               :payload nil
                               :receipt {:status :accepted}}})
        issued (commands/issue-epoch state)
        retired (commands/retire-epoch state)]
    (is (= {} (get-in issued [:state :receipt-index])))
    (is (= {} (get-in retired [:state :receipt-index])))
    (is (empty? (commands/check-invariants (:state issued))))
    (is (empty? (commands/check-invariants (:state retired))))))

(deftest invariant-check-detects-a-stale-receipt-index
  (let [state (assoc (fresh-state)
                     :receipt-index
                     {"cmd-1" {:command-id "cmd-1"
                               :command-key "cmd-1"
                               :receipt {:status :accepted}}})]
    (is (some #(= :receipt-index-orphan (:invariant %))
              (commands/check-invariants state)))
    (let [accepted (commands/accept (fresh-state)
                                    (request "cmd-1" nil))
          mismatched (assoc (:state accepted)
                            :receipt-index
                            {"cmd-1" {:command-id "cmd-1"
                                      :command-key "cmd-1"
                                      :receipt {:status :wrong}}})]
      (is (some #(= :receipt-index-receipt-mismatch (:invariant %))
                (commands/check-invariants mismatched)))))
  (testing "accept and complete keep an existing index aligned"
    (let [state (assoc (fresh-state) :receipt-index {})
          accepted (commands/accept state (request "cmd-1" nil))
          completed (commands/complete (:state accepted)
                                       "cmd-1"
                                       {:status :accepted :value 1})]
      (is (= (:receipt (get-in accepted [:state :receipts "cmd-1"]))
             (get-in accepted [:state :receipt-index "cmd-1" :receipt])))
      (is (empty? (commands/check-invariants (:state accepted))))
      (is (= {:status :accepted :value 1}
             (get-in completed
                     [:state :receipt-index "cmd-1" :receipt])))
      (is (empty? (commands/check-invariants (:state completed)))))))

(deftest durable-issue-epoch-clears-receipts-and-fences-old-windows
  (let [gate (durable/gate (durable/memory-store))
        window (durable-window 1 0)
        req (request "cmd-1" {:text "hello"})]
    (try
      (is (true? (:ok? (durable/accept! gate window "s1" req
                                        {:status :accepted}))))
      (let [issued (durable/issue-epoch! gate 1)
            state (durable/state gate)]
        (is (true? (:ok? issued)))
        (is (= 2 (:epoch issued)))
        (is (= 1 (:retired-epoch issued)))
        (is (= 2 (:command-epoch state)))
        (is (= 1 (:retired-through state)))
        (is (empty? (:receipts state)))
        (is (empty? (:receipt-index state)))
        (is (nil? (durable/receipt-for gate "s1" "cmd-1")))
        (is (nil? (durable/receipt-for gate "cmd-1")))
        (let [old (durable/accept! gate window "s1" req nil)
              current (durable/accept! gate (durable-window 2 1)
                                       "s1"
                                       (assoc req :command-epoch 2)
                                       nil)]
          (is (false? (:ok? old)))
          (is (= :COMMAND_EPOCH_RETIRED (get-in old [:error :code])))
          (is (true? (:ok? current)))
          (is (= 2 (:command-epoch (:receipt current))))))
      (finally
        (durable/close! gate)))))

(deftest durable-issue-epoch-refuses-a-stale-expected-epoch
  (let [gate (durable/gate (durable/memory-store))]
    (try
      (let [first (durable/issue-epoch! gate)
            second (durable/issue-epoch! gate 1)
            stale (durable/issue-epoch! gate 1)]
        (is (true? (:ok? first)))
        (is (= 1 (:epoch first)))
        (is (true? (:ok? second)))
        (is (= 2 (:epoch second)))
        (is (false? (:ok? stale)))
        (is (= :COMMAND_EPOCH_RETIRED (get-in stale [:error :code])))
        (is (= 2 (:command-epoch (:state stale))))
        (is (= 2 (:command-epoch (durable/state gate))))
        (let [third (durable/issue-epoch! gate 2)]
          (is (true? (:ok? third)))
          (is (= 3 (:epoch third)))))
      (finally
        (durable/close! gate)))))

(deftest durable-sync-window-never-resurrects-a-retired-epoch
  (let [gate (durable/gate (durable/memory-store))]
    (try
      (is (true? (:ok? (durable/sync-window! gate (durable-window 1 0)))))
      (is (true? (:ok? (durable/sync-window! gate
                                             {:command-epoch nil
                                              :retired-through 1}))))
      (let [stale (durable/sync-window! gate (durable-window 1 0))
            state (durable/state gate)]
        (is (true? (:ok? stale)))
        (is (nil? (:command-epoch state)))
        (is (= 1 (:retired-through state)))
        (is (empty? (commands/check-invariants state))))
      (finally
        (durable/close! gate))))
  (testing "an unissued epoch below the high-water mark is not adopted"
    (let [gate (durable/gate (durable/memory-store))]
      (try
        (durable/sync-window! gate {:command-epoch nil
                                    :retired-through 5})
        (durable/sync-window! gate (durable-window 3 2))
        (let [state (durable/state gate)]
          (is (nil? (:command-epoch state)))
          (is (= 5 (:retired-through state)))
          (is (empty? (commands/check-invariants state))))
        (finally
          (durable/close! gate))))))

(deftest durable-gate-reconciles-a-stale-dispatching-placeholder
  (let [store (durable/memory-store)
        first-gate (durable/gate store)
        window (durable-window 1 0)
        req (request "cmd-1" {:text "hello"})]
    (try
      (is (true? (:ok? (durable/accept! first-gate window "s1" req
                                        {:status :dispatching}))))
      (let [gate (durable/gate store)
            state (durable/state gate)
            replay (durable/accept! gate window "s1" req nil)]
        (is (= :outcome-unknown
               (get-in state [:receipts ["s1" "cmd-1"] :receipt :status])))
        (is (= :outcome-unknown
               (get-in state [:receipt-index "cmd-1" :receipt :status])))
        (is (true? (:ok? replay)))
        (is (true? (:replayed? replay)))
        (is (= :outcome-unknown (get-in replay [:receipt :status])))
        (is (= :resync-state (get-in replay [:receipt :error :action])))
        (durable/close! gate))
      (finally
        (durable/close! first-gate)))))

(deftest dispatch-wrapper-reports-a-receipt-commit-failure
  (let [state (atom nil)
        version (atom 0)
        commits (atom 0)
        store (reify durable/Store
                (load-state [_] {:version @version :state @state})
                (commit! [_ _ new-state]
                  (if (= 2 (swap! commits inc))
                    (throw (ex-info "disk full" {:code :DISK_FULL}))
                    (do
                      (reset! state new-state)
                      (swap! version inc)
                      {:ok? true :version @version})))
                (close-store! [_] {:ok? true}))
        gate (durable/gate store)
        window (durable-window 1 0)
        replies (atom [])
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [_ reply]
                               (let [result {:ok? true
                                             :receipt {:status :accepted
                                                       :value 1}}]
                                 (reply result)
                                 result))})
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :settings/save-role :roles {}}}
        result ((:submit! wrapper) req #(swap! replies conj %))]
    (is (= 1 (count @replies)))
    (is (true? (:ok? result))
        "the synchronous dispatch result is returned unchanged")
    (let [final (first @replies)]
      (is (false? (:ok? final)))
      (is (= :unknown (:outcome final)))
      (is (= :COMMAND_STORE_FAILED (get-in final [:error :code]))))
    (is (= :dispatching
           (get-in (durable/state gate)
                   [:receipts ["s1" "cmd-1"] :receipt :status])))
    (durable/close! gate)))

(deftest dispatch-receipt-does-not-answer-from-a-retired-epoch
  (let [gate (durable/gate (durable/memory-store))
        window (atom (durable-window 1 0))
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (fn [] @window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [_ reply]
                               (let [result {:ok? true
                                             :receipt {:status :accepted :v 1}}]
                                 (reply result)
                                 result))})
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :settings/save-role :roles {}}}]
    (is (true? (:ok? ((:submit! wrapper) req identity))))
    (is (some? ((:receipt wrapper) "s1" "cmd-1")))
    (reset! window (durable-window 2 1))
    (is (nil? ((:receipt wrapper) "s1" "cmd-1")))
    (is (= 2 (:command-epoch (durable/state gate))))
    (is (empty? (get-in (durable/state gate) [:receipts])))
    (durable/close! gate)))

(deftest dispatch-completion-cannot-finalize-a-retired-epoch
  (let [gate (durable/gate (durable/memory-store))
        window (atom (durable-window 1 0))
        pending (atom nil)
        replies (atom [])
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (fn [] @window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [request reply]
                               (reset! pending [request reply])
                               nil)})
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :settings/save-role :roles {}}}]
    (is (nil? ((:submit! wrapper) req #(swap! replies conj %))))
    (is (= :dispatching
           (get-in (durable/state gate)
                   [:receipts ["s1" "cmd-1"] :receipt :status])))
    (is (true? (:ok? (durable/sync-window! gate (durable-window 2 1)))))
    (let [[_ reply] @pending]
      (reply {:ok? true :receipt {:status :accepted :value 1}}))
    (is (= 1 (count @replies)))
    (let [final (first @replies)]
      (is (false? (:ok? final)))
      (is (= :unknown (:outcome final)))
      (is (= :COMMAND_ID_UNKNOWN (get-in final [:error :code]))))
    (is (empty? (get-in (durable/state gate) [:receipts])))
    (durable/close! gate)))

(deftest owner-window-reports-a-retired-owner
  (let [owner {:snapshot (fn []
                           {:state {:commands {:command-epoch nil
                                               :retired-through 3}}})}]
    (is (= {:command-epoch nil :retired-through 3}
           (dispatch/owner-window owner))))
  (is (nil? (dispatch/owner-window nil)))
  (is (nil? (dispatch/owner-window {:snapshot (fn [] {:state {}})}))))

(deftest dispatch-replay-resolver-completes-a-placeholder
  (let [gate (durable/gate (durable/memory-store))
        window (durable-window 1 0)
        resolved {:ok? true
                  :replayed? true
                  :receipt {:status :accepted :session-id "s-1"}}
        calls (atom 0)
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "app")
                  :resolve-replay
                  (fn [_request stored]
                    (swap! calls inc)
                    (is (= :dispatching
                           (get-in stored [:receipt :status])))
                    resolved)
                  :dispatch! (fn [_request _reply] nil)})
        req {:command-id "cmd-1"
             :command-epoch 1
             :payload {:kind :session/create :title "One"}}]
    (is (nil? ((:submit! wrapper) req identity)))
    (is (= :dispatching
           (:status (durable/receipt-for gate "app" "cmd-1"))))
    (let [replay ((:submit! wrapper) req identity)]
      (is (true? (:ok? replay)))
      (is (true? (:replayed? replay)))
      (is (= (:receipt resolved) (:receipt replay)))
      (is (= 1 @calls))
      (is (= (:receipt resolved)
             (durable/receipt-for gate "app" "cmd-1"))))
    (durable/close! gate)))
