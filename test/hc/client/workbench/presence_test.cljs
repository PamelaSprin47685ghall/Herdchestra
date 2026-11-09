(ns hc.client.workbench.presence-test
  "D1/D2 client presence regression tests.

  The Sente handshake identity must survive a reload: `sessionStorage` first,
  `localStorage` second, and a fresh UUID only when both stores refuse to
  persist.  The automatic attach must stay one-shot per
  `(session, subscription)` and must never displace a named controller."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.workbench.presence-bridge :as presence-bridge]
            [hc.client.workbench.transport :as transport]))

(defn- fake-storage
  ([] (fake-storage {} false))
  ([initial] (fake-storage initial false))
  ([initial fail?]
   (let [items (atom (or initial {}))
         fail! #(throw (js/Error. "storage denied"))]
     #js {:getItem (fn [k] (if fail? (fail!) (get @items k)))
          :setItem (fn [k v] (if fail? (fail!) (swap! items assoc k v)))
          :removeItem (fn [k] (swap! items dissoc k))
          :items items})))

(defn- set-global!
  [name value]
  (js/Object.defineProperty
   js/globalThis name
   #js {:value value :configurable true :writable true}))

(defn- delete-global!
  [name]
  (js-delete js/globalThis name))

(defn- with-storages
  "Install SESSION and LOCAL as the browser stores, restoring the previous
  globals afterwards.  Node may ship a real `localStorage`, so the test must
  not leak its fake into a later test."
  [session local f]
  (let [global js/globalThis
        had-session? (js-in "sessionStorage" global)
        had-local? (js-in "localStorage" global)
        old-session (aget global "sessionStorage")
        old-local (aget global "localStorage")]
    (reset! transport/cached-client-id nil)
    (set-global! "sessionStorage" session)
    (set-global! "localStorage" local)
    (try
      (f)
      (finally
        (reset! transport/cached-client-id nil)
        (if had-session?
          (set-global! "sessionStorage" old-session)
          (delete-global! "sessionStorage"))
        (if had-local?
          (set-global! "localStorage" old-local)
          (delete-global! "localStorage"))))))

(deftest stable-client-id-prefers-session-storage
  (let [session (fake-storage)
        local (fake-storage {transport/client-id-storage-key
                             "local-only"})]
    (with-storages
     session local
     (fn []
       (let [first-id (transport/stable-client-id)
             second-id (transport/stable-client-id)]
         (testing "the generated identity survives a reload in the same tab"
           (is (string? first-id))
           (is (not (str/blank? first-id)))
           (is (= first-id second-id)
               "one page lifetime uses one identity"))
         (testing "the id is written to sessionStorage, not localStorage"
           (is (= first-id
                  (.getItem session transport/client-id-storage-key)))
           (is (= "local-only"
                  (.getItem local transport/client-id-storage-key)))))))))

(deftest stable-client-id-reuses-an-existing-session-id
  (let [session (fake-storage {transport/client-id-storage-key
                               "session-identity"})
        local (fake-storage {transport/client-id-storage-key
                             "other-identity"})]
    (with-storages
     session local
     (fn []
       (is (= "session-identity" (transport/stable-client-id)))))))

(deftest stable-client-id-falls-back-to-local-storage
  (let [session (fake-storage {} true)
        local (fake-storage {transport/client-id-storage-key
                             "persisted-identity"})]
    (with-storages
     session local
     (fn []
       (testing "a denied sessionStorage falls through to localStorage"
         (is (= "persisted-identity" (transport/stable-client-id))))))))

(deftest stable-client-id-writes-local-storage-when-session-write-fails
  (let [session (fake-storage {} true)
        local (fake-storage)]
    (with-storages
     session local
     (fn []
       (let [id (transport/stable-client-id)]
         (is (string? id))
         (is (not (str/blank? id)))
         (is (= id (.getItem local transport/client-id-storage-key)))
         (is (= id (transport/stable-client-id))
             "the localStorage identity is stable on the next call"))))))

(deftest stable-client-id-uses-a-fresh-uuid-when-both-stores-refuse
  (let [session (fake-storage {} true)
        local (fake-storage {} true)]
    (with-storages
     session local
     (fn []
       (let [first-id (transport/stable-client-id)
             second-id (do (reset! transport/cached-client-id nil)
                           (transport/stable-client-id))]
         (is (string? first-id))
         (is (not (str/blank? first-id)))
         (is (not= first-id second-id)
             "with no persistent store a fresh page needs a distinct identity"))))))

(deftest attach-decision-is-one-shot-and-never-displaces-a-controller
  (let [base {:session-id "s1"
              :synced? true
              :controller-id nil
              :local-client-id "client-a"
              :subscription-id 7
              :attempted #{}}]
    (testing "a synced free session attaches once"
      (is (= {:attach? true
              :reason :free
              :session-id "s1"
              :key ["s1" 7]}
             (presence-bridge/attach-decision base))))
    (testing "a named controller is never displaced automatically"
      (is (false? (:attach? (presence-bridge/attach-decision
                             (assoc base :controller-id "client-b"))))))
    (testing "the same session and subscription are not asked twice"
      (is (false? (:attach? (presence-bridge/attach-decision
                             (assoc base :attempted #{["s1" 7]}))))))
    (testing "an unsynchronized baseline never sends a command"
      (is (false? (:attach? (presence-bridge/attach-decision
                             (assoc base :synced? false))))))
    (testing "a missing handshake identity cannot attach"
      (is (false? (:attach? (presence-bridge/attach-decision
                             (assoc base :local-client-id nil))))))))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.presence-test))

(set! *main-cli-fn* -main)
