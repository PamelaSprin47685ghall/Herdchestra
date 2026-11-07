(ns hc.server.sessions.credentials-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.sessions.connections :as connections]
            [hc.server.sessions.credentials :as credentials]
            [hc.server.sessions.secrets :as secrets]))

(defn- connection []
  (get (connections/new-connection
        {:connection-id "conn-1"
         :name "Personal"
         :kind :preset
         :default-base-url "https://api.example.com"
         :now 1000})
       :connection))

(deftest multi-key-lifecycle-keeps-secrets-out-of-connection-state
  (let [store (secrets/memory-secret-store)
        base (connection)
        result (credentials/apply-credentials!
                base
                {:store store
                 :now 2000
                 :credentials [{:key-id "k1"
                                :alias "primary"
                                :secret "sk-primary-secret"
                                :enabled? true
                                :priority 0}
                               {:key-id "k2"
                                :alias "backup"
                                :secret "sk-backup-secret"
                                :enabled? true
                                :priority 1}]})
        connection (:connection result)
        printed (pr-str connection)
        resolved (credentials/resolve-credential store connection 3000)]
    (is (:ok? result))
    (is (= #{"k1" "k2"} (set (keys (:credentials connection)))))
    (is (not (str/includes? printed "sk-primary-secret")))
    (is (not (str/includes? printed "sk-backup-secret")))
    (is (not (str/includes? printed ":secret ")))
    (is (= "k1" (get-in resolved [:credential :credential-id])))
    (is (= "sk-primary-secret" (:secret resolved)))
    (is (= 1 (get-in resolved [:connection :credentials "k1" :use-count])))))

(deftest rotation-and-clear-update-the-store
  (let [store (secrets/memory-secret-store)
        base (connection)
        added (credentials/apply-credentials!
               base
               {:store store
                :now 2000
                :credentials [{:key-id "k1"
                               :alias "primary"
                               :secret "first-secret"
                               :priority 0}]})
        rotated (credentials/apply-credentials!
                 (:connection added)
                 {:store store
                  :now 3000
                  :credentials [{:key-id "k1"
                                 :alias "primary"
                                 :secret "second-secret"
                                 :priority 0}]})
        resolved (credentials/resolve-credential store
                                                  (:connection rotated)
                                                  4000)
        cleared (credentials/apply-credentials!
                 (:connection rotated)
                 {:store store
                  :now 5000
                  :clear ["k1"]})
        ref (first (:deleted-refs cleared))]
    (is (= "second-secret" (:secret resolved)))
    (is (not (contains? (:credentials (:connection cleared)) "k1")))
    (is (some? ref))
    (is (not (secrets/secret-exists? store ref)))))

(deftest a-missing-secret-store-is-refused
  (let [result (credentials/apply-credentials!
                (connection)
                {:credentials [{:key-id "k1" :secret "x"}]})]
    (is (not (:ok? result)))
    (is (= :SECRET_STORE_REQUIRED (get-in result [:error :code])))))

(deftest settings-payload-merges-metadata-with-one-time-secrets
  (let [store (secrets/memory-secret-store)
        base (connection)
        result (credentials/apply-settings-credentials!
                store
                base
                {:keys [{:key-id "k1" :alias "main" :enabled? true :priority 0
                         :auth-state :unchanged}]
                 :credentials [{:key-id "k1" :secret "sk-settings-secret"}]
                 :clear-credentials []}
                2000)
        resolved (credentials/resolve-credential store (:connection result) 3000)]
    (is (:ok? result))
    (is (not (clojure.string/includes? (pr-str (:connection result))
                                       "sk-settings-secret")))
    (is (= "sk-settings-secret" (:secret resolved)))
    (is (= "main" (get-in resolved [:credential :alias])))))

(deftest settings-payload-updates-existing-keys-without-losing-secrets
  (let [store (secrets/memory-secret-store)
        base (connection)
        first-result (credentials/apply-settings-credentials!
                      store
                      base
                      {:keys [{:key-id "k1" :alias "primary"
                               :enabled? true :priority 1}
                              {:key-id "k2" :alias "backup"
                               :enabled? true :priority 2}]
                       :credentials [{:key-id "k1" :secret "first-secret"}
                                     {:key-id "k2" :secret "backup-secret"}]
                       :clear-credentials []}
                      2000)
        updated (credentials/apply-settings-credentials!
                 store
                 (:connection first-result)
                 {:keys [{:key-id "k1" :alias "renamed"
                          :enabled? true :priority 5}
                         {:key-id "k2" :alias "backup"
                          :enabled? false :priority 2}]
                  :credentials []
                  :clear-credentials []}
                 3000)
        connection (:connection updated)
        resolved (credentials/resolve-credential store connection 4000)]
    (is (:ok? first-result))
    (is (:ok? updated))
    (is (= #{"k1" "k2"} (set (keys (:credentials connection)))))
    (is (= "renamed" (get-in connection [:credentials "k1" :alias])))
    (is (= 5 (get-in connection [:credentials "k1" :priority])))
    (is (false? (get-in connection [:credentials "k2" :enabled?])))
    (is (= "first-secret" (:secret resolved)))))

(deftest settings-payload-clear-removes-only-the-named-key
  (let [store (secrets/memory-secret-store)
        base (connection)
        added (credentials/apply-settings-credentials!
               store
               base
               {:keys [{:key-id "k1" :alias "primary" :enabled? true}]
                :credentials [{:key-id "k1" :secret "first-secret"}]
                :clear-credentials []}
               2000)
        cleared (credentials/apply-settings-credentials!
                 store
                 (:connection added)
                 {:keys []
                  :credentials []
                  :clear-credentials ["k1"]}
                 3000)
        connection (:connection cleared)
        ref (get-in (:connection added) [:credentials "k1" :secret-ref])]
    (is (:ok? added))
    (is (:ok? cleared))
    (is (empty? (:credentials connection)))
    (is (not (secrets/secret-exists? store ref)))))
