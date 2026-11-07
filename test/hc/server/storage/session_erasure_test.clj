(ns hc.server.storage.session-erasure-test
  "E2E 20 local erasure: after a temporary session is discarded, its owner
  partition and derived session-scoped records are gone.

  The test uses the real SessionPersistence adapter and the real storage
  facade.  It does not contact an external model, search or speech service;
  the external-effect disclosure is asserted as data."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- session-doc
  [session-id]
  {:session-id session-id
   :revision 0
   :status :active
   :temporary? true
   :title "Temporary"
   :messages {}
   :drafts {}
   :created-at 1
   :updated-at 2})

(defn- docs-of
  [export partition]
  (get-in export [:partitions partition :docs] []))

(deftest discard-temporary-session-erases-derived-state
  (support/with-temp-store [store {}]
    (let [session-id "tmp-e2e-20"
          persistence (session-persistence/persistence store)
          committed (ports/commit-session! persistence 0 (session-doc session-id))]
      (is (= :committed (:status committed)))
      (storage/commit-migration!
       store
       {:migration-id "owner-state"
        :partition (str "session/" session-id)
        :session-id session-id
        :put [{:kind :owner-state :id session-id
               :doc {:session-id session-id
                     :messages {"m1" {:message-id "m1" :content "sensitive"}}
                     :drafts {}
                     :board {}}}]
        :obligations
        {:put [{:obligation/id "report/r1"
                :obligation/kind :report
                :open? true
                :phase :report-pending
                :report {:report-id "r1" :parent-call-id "c1"}}]}})
      (storage/commit-migration!
       store
       {:migration-id "diagnostics"
        :partition "diagnostics"
        :put [{:kind :diagnostics-request :id "req-1"
               :doc {:request-id "req-1" :session-id session-id}}
              {:kind :diagnostics-request :id "req-keep"
               :doc {:request-id "req-keep" :session-id "keep"}}]})
      (storage/commit-migration!
       store
       {:migration-id "materials"
        :partition "materials"
        :put [{:kind :materials-memory :id "mem-1"
               :doc {:id "mem-1" :status :candidate
                     :source {:session-id session-id}}}
              {:kind :materials-instruction-session :id session-id
               :doc {:session-id session-id :card-ids []}}
              {:kind :materials-memory :id "mem-keep"
               :doc {:id "mem-keep" :status :active
                     :source {:session-id "keep"}}}]})
      (let [deleted (ports/delete-session! persistence 0 session-id)
            erased (storage/erase-session-derived! store session-id)
            owner (storage/export-state store {:partition (str "session/" session-id)})
            diagnostics (storage/export-state store {:partition "diagnostics"})
            materials (storage/export-state store {:partition "materials"})
            sessions (storage/export-state store {:partition "sessions"})
            all-open (storage/list-open-obligations store {:all-partitions? true})]
        (is (= :deleted (:status deleted)))
        (is (some #(= :external-services (:scope %))
                  (:external-effects deleted))
            "the persistence delete discloses non-revocable external effects")
        (is (= :missing (:status (ports/read-session persistence session-id))))
        (testing "the owner partition and its obligations are gone"
          (is (empty? (docs-of owner (str "session/" session-id))))
          (is (empty? (get-in owner [:partitions (str "session/" session-id)
                                    :obligations]))))
        (testing "diagnostics keep only other sessions"
          (is (= ["req-keep"] (mapv :id (docs-of diagnostics "diagnostics")))))
        (testing "memory candidates and session selections are gone"
          (is (= ["mem-keep"] (mapv :id (docs-of materials "materials")))))
        (testing "the session record, command and index entry are gone"
          (is (not-any? #(= session-id (:id %)) (docs-of sessions "sessions")))
          (is (not-any? #(= session-id (:session-id (:doc %)))
                        (docs-of sessions "sessions")))
          (is (empty? (get-in sessions [:partitions "sessions" :docs 0 :doc :ids]))))
        (testing "no open obligation remains"
          (is (empty? (:obligations all-open))))
        (testing "external effects are disclosed, not revoked"
          (is (some #(= :external-services (:scope %)) (:external-effects erased)))
          (is (every? (complement :revocable?) (:external-effects erased))))))))
