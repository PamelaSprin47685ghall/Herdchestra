(ns hc.client.workbench.draft-storage-test
  "Consumer-path regression for browser-local workbench drafts.

  E2E01's \"quit and reopen\" step is a same-browser-profile check.  The
  workbench persists drafts in `localStorage`, not in the server session
  record, so a new browser context is a new empty store.  These tests pin both
  halves of that boundary: a same-profile reload restores each session's own
  draft, and a fresh profile loads as a successful empty store rather than a
  corrupt or failed one.  They also prove JSON keywordized session keys are
  normalized back to the string IDs the runtime uses."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [hc.client.conversation.draft :as draft]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.storage :as storage]))

(defn- memory-local-storage
  "A minimal localStorage with the three methods the workbench uses."
  []
  (let [items (atom {})]
    #js {:getItem (fn [key] (get @items key))
         :setItem (fn [key value] (swap! items assoc key (str value)))
         :removeItem (fn [key] (swap! items dissoc key))}))

(defn- install-storage!
  []
  (let [store (memory-local-storage)]
    (set! (.-localStorage js/global) store)
    store))

(defn- sample-draft
  [text version saved-version]
  (-> draft/empty-draft
      (assoc :text text
             :cursor (count text)
             :selection {:start 0 :end (count text)}
             :attachments [{:id "a1" :name "note.txt" :status :ready}]
             :quotes [{:id "q1" :text "quoted"}]
             :version version
             :saved-version saved-version
             :save-state (if (< saved-version version) :unsaved :saved))))

(deftest same-profile-reload-restores-each-session-draft
  (install-storage!)
  (let [drafts {"hc-session-1" (sample-draft "第一条" 3 2)
                "hc-session-2" (sample-draft "second" 1 1)}
        saved (storage/save! drafts {})]
    (is (true? (:ok? saved)))
    (let [loaded (storage/load!)]
      (is (= :available (:status loaded)))
      (is (= #{"hc-session-1" "hc-session-2"}
             (set (keys (:drafts loaded)))))
      (testing "the selected session reads only its own text"
        (is (= "第一条" (:text (draft/draft (:drafts loaded)
                                            "hc-session-1"))))
        (is (= "second" (:text (draft/draft (:drafts loaded)
                                            "hc-session-2")))))
      (testing "a saved-version gap stays visibly unsaved"
        (is (= 3 (get-in loaded [:drafts "hc-session-1" :version])))
        (is (= 2 (get-in loaded [:drafts "hc-session-1" :saved-version])))
        (is (= :unsaved (get-in loaded [:drafts "hc-session-1" :save-state]))))
      (testing "attachments, quotes, cursor, and selection survive"
        (is (= 1 (count (get-in loaded [:drafts "hc-session-1"
                                         :attachments]))))
        (is (= 1 (count (get-in loaded [:drafts "hc-session-1" :quotes]))))
        (is (= 3 (get-in loaded [:drafts "hc-session-1" :cursor])))
        (is (= {:start 0 :end 3}
               (get-in loaded [:drafts "hc-session-1" :selection])))))))

(deftest a-new-browser-profile-is-an-empty-store-not-a-failure
  (install-storage!)
  (let [loaded (storage/load!)]
    (is (= :available (:status loaded))
        "a missing key is a successful empty load")
    (is (= {} (:drafts loaded)))
    (is (= {} (:settings loaded)))
    (is (= "" (:text (draft/draft (:drafts loaded) "hc-session-1")))
        "a new profile starts with an empty composer, not a stale draft")))

(deftest the-selected-session-reads-its-own-restored-draft
  (install-storage!)
  (is (true? (:ok? (storage/save!
                    {"s1" (sample-draft "alpha" 1 1)
                     "s2" (sample-draft "beta" 1 1)} {}))))
  (let [loaded (storage/load!)
        db {:ui/selected-session-id "s2"
            :session/active-id "s1"
            :session/by-id {"s1" {:session-id "s1" :updated-at 2}
                            "s2" {:session-id "s2" :updated-at 1}}}
        selected (model/selected-session-id db)]
    (is (= "s2" selected)
        "the local selection wins over the server presentation pointer")
    (is (= "beta" (:text (draft/draft (:drafts loaded) selected)))
        "the selected session restores its own draft, not the first row's")))

(deftest json-keywordized-session-ids-are-normalized
  (install-storage!)
  (is (true? (:ok? (storage/save!
                    {"hc-session-1" (sample-draft "draft" 1 1)} {}))))
  (let [loaded (storage/load!)]
    (testing "JSON reads object keys as keywords; the draft still restores"
      (is (string? (first (keys (:drafts loaded)))))
      (is (= "draft" (get-in loaded [:drafts "hc-session-1" :text])))
      (is (= :saved (get-in loaded [:drafts "hc-session-1" :save-state]))))))

(deftest signature-tracks-every-persisted-field
  (let [base (sample-draft "x" 1 1)
        sig #(storage/draft-signature {"s1" %})]
    (testing "text, cursor, selection, attachments, quotes, and version"
      (is (not= (sig base) (sig (assoc base :text "y"))))
      (is (not= (sig base) (sig (assoc base :cursor 0))))
      (is (not= (sig base) (sig (assoc base :selection {:start 0 :end 0}))))
      (is (not= (sig base) (sig (assoc base :attachments []))))
      (is (not= (sig base) (sig (assoc base :quotes []))))
      (is (not= (sig base) (sig (assoc base :version 2)))))
    (testing "the session id is part of the signature"
      (is (not= (storage/draft-signature {"s1" base})
                (storage/draft-signature {"s2" base}))))
    (testing "derived and transient fields do not schedule another save"
      (is (= (sig base)
             (sig (assoc base :saved-version 0
                         :save-state :saved
                         :composing? true
                         :undo {:draft base}
                         :updated-at "later")))))))

(deftest write-failure-is-reported-honestly
  (let [store (memory-local-storage)]
    (set! (.-setItem store) (fn [_ _] (throw (js/Error. "quota"))))
    (set! (.-localStorage js/global) store)
    (let [result (storage/save! {"s1" (sample-draft "x" 1 1)} {})]
      (is (false? (:ok? result)))
      (is (= :storage-unavailable (:reason result))))))

(deftest unavailable-storage-is-not-a-false-empty-success
  (set! (.-localStorage js/global) nil)
  (let [loaded (storage/load!)]
    (is (= :unavailable (:status loaded)))
    (is (= {} (:drafts loaded)))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.draft-storage-test))

(set! *main-cli-fn* -main)
