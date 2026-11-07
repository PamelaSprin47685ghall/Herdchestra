(ns hc.compose.owner.compact-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.owner.compact :as compact]
            [hc.compose.owner.state :as state]))

(deftest large-call-content-is-referenced-and-restorable
  (let [store (compact/memory-blob-store)
        source (apply str (repeat 20000 "x"))
        state (-> (state/initial-state {:session-id "compact"})
                  (assoc-in [:calls "call-1"]
                            {:kind :tool
                             :state :succeeded
                             :result {:status :succeeded
                                      :value {:state :succeeded
                                              :raw-source source
                                              :source source
                                              :numbered-source source
                                              :receipts [{:raw-source source}]}}})
                  (assoc-in [:cancel :nodes "call-1"]
                            {:kind :call
                             :late-results [{:raw-source source}]})
                  (assoc-in [:runs "call-1#a1"]
                            {:text "assistant text"
                             :receipts [{:source source}]}))
        [compacted stats] (compact/compact-state state store)
        result (get-in compacted [:calls "call-1" :result :value])
        ref (get-in result [:raw-source :source-ref])]
    (is (< (:after stats) (:before stats)))
    (is (map? result))
    (is (string? (get-in result [:raw-source :source-preview])))
    (is (<= (count (get-in result [:raw-source :source-preview])) 1024))
    (is (= source ((:get store) ref))
        "the blob ref restores the original source")
    (is (= ref (get-in result [:source :source-ref]))
        "every large source field is referenced")
    (is (= ref (get-in compacted
                       [:cancel :nodes "call-1" :late-results 0
                        :raw-source :source-ref])))
    (is (= ref (get-in compacted
                       [:runs "call-1#a1" :receipts 0 :source :source-ref])))
    (is (= "assistant text" (get-in compacted [:runs "call-1#a1" :text]))
        "ordinary run text stays inline")
    (let [[again stats2] (compact/compact-state compacted store)]
      (is (= compacted again)
          "compaction is idempotent")
      (is (zero? (:saved stats2))))
    (let [restored (compact/restore-state compacted store)]
      (is (= source (get-in restored [:calls "call-1" :result :value
                                      :raw-source]))
          "restore expands a source reference")
      (is (= source (get-in restored [:runs "call-1#a1" :receipts 0
                                      :source])))
      (is (= "assistant text" (get-in restored [:runs "call-1#a1" :text]))))))

(deftest terminal-call-retry-error-and-request-are-bounded
  (let [store (compact/memory-blob-store)
        error {:code :MODEL_FAILED
               :message (apply str (repeat 20000 "e"))
               :details {:body (apply str (repeat 20000 "d"))}}
        request {:snapshot {:messages (vec (repeat 200 {:role :user
                                                         :text "hello"}))}}
        state (-> (state/initial-state {:session-id "compact-terminal"})
                  (assoc-in [:calls "call-t"]
                            {:kind :model
                             :state :failed
                             :retry {:attempts 3
                                     :max-attempts 4
                                     :request request
                                     :retry-at 10}
                             :last-error error
                             :last-failure error
                             :error error
                             :request request}))
        [compacted stats] (compact/compact-state state store)
        call (get-in compacted [:calls "call-t"])
        request-ref (get-in call [:request :request-ref])]
    (is (< (:after stats) (:before stats)))
    (is (string? request-ref))
    (is (string? (get-in call [:request :request-preview])))
    (is (<= (count (get-in call [:request :request-preview])) 1024))
    (is (= (pr-str (get-in state [:calls "call-t" :request])) ((:get store) request-ref))
        "the request blob restores the original snapshot")
    (is (= (get-in state [:calls "call-t" :request])
           (get-in (compact/restore-state compacted store)
                   [:calls "call-t" :request]))
        "restore parses the request EDN back into a map")
    (is (not (contains? (:retry call) :request))
        "a terminal retry keeps only budget fields")
    (is (= 3 (:attempts (:retry call))))
    (is (= :MODEL_FAILED (:code (:last-error call))))
    (is (<= (count (:message (:last-error call))) 4096))
    (is (not (contains? (:last-error call) :details)))))
