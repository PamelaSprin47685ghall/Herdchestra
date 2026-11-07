(ns hc.server.storage-corruption-test
  "A versioned partition without its required state document is corruption,
  not an empty state.  Treating it as empty would drop command receipts or an
  owner's conversation, board and relay ledger."
  (:require [clojure.test :refer [deftest is]]
            [hc.server.app.owner-store :as owner-store]
            [hc.server.commands.durable :as durable]
            [hc.server.commands.storage :as command-storage]))

(defn- error-code
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:code (ex-data e)))))

(deftest command-gate-rejects-a-versioned-partition-without-state
  (is (= {:version 0 :state nil}
         (command-storage/state-from-view {:version 0 :state nil})))
  (is (= {:version 4 :state {:command-epoch 1}}
         (command-storage/state-from-view
          {:version 4 :state {:command-epoch 1}})))
  (is (= :COMMAND_STORE_CORRUPT
         (error-code
          #(command-storage/state-from-view
            {:version 4 :state nil :partition "commands/app"})))))

(deftest owner-store-rejects-a-versioned-partition-without-state
  (is (= {:version 0 :state nil}
         (owner-store/state-from-view {:version 0 :state nil})))
  (is (= {:version 7 :state {:session-id "s1"}}
         (owner-store/state-from-view
          {:version 7 :state {:session-id "s1"}})))
  (is (= :OWNER_STORE_CORRUPT
         (error-code
          #(owner-store/state-from-view
            {:version 7 :state nil :partition "session/s1"})))))

(deftest durable-gate-refuses-an-invalid-initial-state
  (let [store (durable/memory-store
               {:initial-state {:command-epoch 1
                                :retired-through 0
                                :receipts {"cmd-1"
                                           {:command-epoch 99
                                            :payload nil
                                            :receipt {:status :accepted}}}}
                :initial-version 1})]
    (is (= :COMMAND_STORE_CORRUPT
           (error-code #(durable/gate store))))))
