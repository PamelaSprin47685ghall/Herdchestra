(ns hc.compose.owner.runtime-property-test
  "Property tests for the Owner runtime boundary: a committed command is
  visible in the store and conversation, and an initial commit failure does
  not produce a runtime."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner :as owner]
            [hc.compose.owner.store :as store]))

(defn- wait-for [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defspec accepted-command-is-committed-before-the-conversation-effect 50
  (prop/for-all [text (gen/such-that seq gen/string-alphanumeric 100)]
    (let [mem (store/memory-store)
          handle (owner/start-owner!
                  {:session-id "runtime-property"
                   :store mem
                   :model-gateway
                   {:start! (fn [_request deliver!]
                              (deliver! {:event :completed :text "saved"})
                              {:attempt-id "attempt-1"})
                    :cancel! (fn [_] nil)}})]
      (try
        (let [receipt ((:submit-command! handle)
                       {:command-id (str "c-" text)
                        :command-epoch 1
                        :payload {:kind :send-message :text text}})
              committed (store/load-state mem)]
          (and (= :accepted (:status receipt))
               (pos? (:version committed))
               (= text (get-in committed [:state :conversation 0 :text]))
               (wait-for #(= "saved"
                             (get-in ((:snapshot handle))
                                     [:state :conversation 1 :text]))
                         2000)))
        (finally
          ((:stop! handle) {:close-store? false}))))))

(defspec failed-initial-commit-produces-no-runtime 50
  (prop/for-all [_ (gen/return nil)]
    (let [failing (reify store/Store
                    (load-state [_] {:version 0 :state nil})
                    (commit! [_ _ _]
                      {:ok? false :error {:code :DISK_FULL}})
                    (close! [_] nil))
          threw? (try
                   (owner/start-owner! {:session-id "runtime-failure"
                                        :store failing})
                   false
                   (catch clojure.lang.ExceptionInfo e
                     (= :INITIAL_COMMIT_FAILED (:code (ex-data e)))))]
      (true? threw?))))
