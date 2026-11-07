(ns hc.server.sessions.drafts-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.sessions.drafts :as drafts]))

(defn- updated
  [draft changes now]
  (let [result (drafts/update-draft draft changes now)]
    (is (:ok? result) (pr-str result))
    (:draft result)))

(deftest a-new-draft-is-saved-and-blank
  (let [draft (drafts/new-draft "w1" 10)]
    (is (drafts/blank? draft))
    (is (drafts/saved? draft))
    (is (= 0 (:revision draft)))
    (is (= 0 (:persisted-revision draft)))
    (is (= :saved (:save-status draft)))))

(deftest an-update-is-pending-and-not-saved
  (let [draft (-> (drafts/new-draft "w1" 10)
                  (updated {:text "hello" :cursor 5} 20))]
    (is (= "hello" (:text draft)))
    (is (= 5 (:cursor draft)))
    (is (= 1 (:revision draft)))
    (is (= 0 (:persisted-revision draft)))
    (is (= :pending (:save-status draft)))
    (is (not (drafts/saved? draft)))
    (is (drafts/dirty? draft))))

(deftest acknowledgement-only-marks-the-current-revision-saved
  (let [draft (-> (drafts/new-draft "w1" 10)
                  (updated {:text "hello"} 20))
        saved (drafts/mark-saved draft 1 30)]
    (is (:ok? saved))
    (is (false? (:stale? saved)))
    (is (drafts/saved? (:draft saved)))
    (is (= 1 (:persisted-revision (:draft saved))))
    (is (= :saved (:save-status (:draft saved))))))

(deftest an-older-ack-is-stale-and-does-not-regress
  (let [draft (-> (drafts/new-draft "w1" 10)
                  (updated {:text "one"} 20)
                  (updated {:text "two"} 30))
        stale (drafts/mark-saved draft 1 40)]
    (is (:ok? stale))
    (is (:stale? stale))
    (is (= draft (:draft stale)))
    (is (= 2 (:revision (:draft stale))))
    (is (not (drafts/saved? (:draft stale))))))

(deftest a-future-ack-is-refused
  (let [draft (drafts/new-draft "w1" 10)
        result (drafts/mark-saved draft 1 20)]
    (is (not (:ok? result)))
    (is (= :DRAFT_ACK_FUTURE (get-in result [:error :code])))
    (is (= draft (:draft result)))))

(deftest a-failed-save-keeps-the-text-and-stays-unsaved
  (let [draft (-> (drafts/new-draft "w1" 10)
                  (updated {:text "precious"} 20))
        failed (drafts/mark-failed draft 1
                                   {:code :STORAGE_FULL
                                    :message "disk full"}
                                   30)]
    (is (:ok? failed))
    (is (= "precious" (get-in failed [:draft :text])))
    (is (= :error (get-in failed [:draft :save-status])))
    (is (not (drafts/saved? (:draft failed))))
    (is (= 0 (get-in failed [:draft :persisted-revision])))))

(deftest an-old-failure-does-not-fail-a-newer-pending-revision
  (let [draft (-> (drafts/new-draft "w1" 10)
                  (updated {:text "one"} 20)
                  (updated {:text "two"} 30))
        failed (drafts/mark-failed draft 1 {:code :STORAGE_FULL} 40)]
    (is (:ok? failed))
    (is (:stale? failed))
    (is (= draft (:draft failed)))
    (is (= :pending (:save-status (:draft failed))))))

(deftest expected-revision-conflict-leaves-the-draft-untouched
  (let [draft (-> (drafts/new-draft "w1" 10)
                  (updated {:text "one"} 20))
        result (drafts/update-draft draft
                                    {:text "two"
                                     :expected-revision 0}
                                    30)]
    (is (not (:ok? result)))
    (is (= :DRAFT_REVISION_CONFLICT (get-in result [:error :code])))
    (is (= draft (:draft result)))
    (is (= 1 (get-in result [:error :details :current-revision])))))

(deftest per-client-drafts-are-independent
  (let [a (drafts/new-draft "window-a" 10)
        b (drafts/new-draft "window-b" 10)
        a' (updated a {:text "from a"} 20)
        b' (updated b {:text "from b"} 20)]
    (is (= "from a" (:text a')))
    (is (= "from b" (:text b')))
    (is (not= (:client-id a') (:client-id b')))
    (is (= "window-a" (:client-id a')))
    (is (= "window-b" (:client-id b')))))

(deftest attachment-limits-are-refused-before-mutation
  (let [draft (drafts/new-draft "w1" 10)
        too-many (vec (repeatedly (inc drafts/max-attachments)
                                  #(hash-map :attachment-id (str (random-uuid))
                                             :size 1)))
        too-large [{:attachment-id "a1"
                    :size (inc drafts/max-attachment-bytes)}]
        invalid [{:size 1}]]
    (doseq [[changes code]
            [[{:attachments too-many} :ATTACHMENT_LIMIT_EXCEEDED]
             [{:attachments too-large} :ATTACHMENT_TOO_LARGE]
             [{:attachments invalid} :ATTACHMENT_INVALID]]]
      (let [result (drafts/update-draft draft changes 20)]
        (is (not (:ok? result)))
        (is (= code (get-in result [:error :code])))
        (is (= draft (:draft result)))))))

(deftest clear-after-send-empties-content-and-remains-pending
  (let [draft (-> (drafts/new-draft "w1" 10)
                  (updated {:text "hello"
                            :attachments [{:attachment-id "a1" :size 3}]
                            :citations [{:url "https://example.com"}]
                            :cursor 5}
                           20))
        cleared (drafts/clear-after-send draft 30)
        cleared-draft (:draft cleared)]
    (is (:ok? cleared))
    (is (drafts/blank? cleared-draft))
    (is (= 0 (:cursor cleared-draft)))
    (is (= :pending (:save-status cleared-draft)))
    (is (not (drafts/saved? cleared-draft)))))

(def gen-change
  (gen/one-of
   [(gen/return {})
    (gen/fmap (fn [text] {:text text}) gen/string)
    (gen/fmap (fn [cursor] {:cursor cursor}) (gen/choose 0 100))
    (gen/fmap (fn [n] {:attachments
                       (vec (repeat n {:attachment-id (str "a" n)
                                       :size n}))})
              (gen/choose 0 3))]))

(defspec saved-implies-matching-persisted-revision
  100
  (prop/for-all [changes (gen/vector gen-change 0 20)]
    (let [draft (reduce (fn [draft change]
                          (let [result (drafts/update-draft draft change 1)]
                            (if (:ok? result) (:draft result) draft)))
                        (drafts/new-draft "w1" 0)
                        changes)]
      (or (not (drafts/saved? draft))
          (and (= (:revision draft) (:persisted-revision draft))
               (= :saved (:save-status draft)))))))

(defspec invariants-hold-for-random-draft-sequences
  100
  (prop/for-all [changes (gen/vector gen-change 0 20)
                 ack (gen/choose 0 25)]
    (let [draft (reduce (fn [draft change]
                          (let [result (drafts/update-draft draft change 1)]
                            (if (:ok? result) (:draft result) draft)))
                        (drafts/new-draft "w1" 0)
                        changes)
          saved (drafts/mark-saved draft ack 2)
          final (if (:ok? saved) (:draft saved) draft)]
      (empty? (drafts/check-invariants final)))))
