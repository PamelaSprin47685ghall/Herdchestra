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
  (prop/for-all [version (gen/choose 0 1000)
                 code (gen/elements [:DISK_FULL :PERMISSION_DENIED
                                     :IO_ERROR :STORE_FULL])
                 suffix (gen/choose 0 1000000)]
    (let [failing (reify store/Store
                    (load-state [_] {:version version :state nil})
                    (commit! [_ _ _]
                      {:ok? false :error {:code code}})
                    (close! [_] nil))
          data (try
                 (owner/start-owner! {:session-id (str "runtime-failure-" suffix)
                                      :store failing})
                 nil
                 (catch clojure.lang.ExceptionInfo e
                   (ex-data e)))]
      ;; No runtime may be built when the initial commit fails and no other
      ;; writer has published state; the commit error must survive verbatim.
      (and (= :INITIAL_COMMIT_FAILED (:code data))
           (= {:code code} (:error data))))))
