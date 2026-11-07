(ns hc.server.history.context-test
  "Outbound context snapshot: what this turn actually carries.

  The snapshot must show the send range without pretending it is the whole
  visible history.  Memory bodies stay on the server; a missing estimate is
  `:unknown`, never zero."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.history.context :as context]))

(defn- message
  ([id role text] (message id role text []))
  ([id role text attachments]
   {:message-id id
    :role role
    :versions [{:version-id (str id "-v0")
                :content text
                :attachments (vec attachments)
                :citations []
                :quotes []
                :tool-records []
                :created-at 1000
                :edited? false
                :source :model}]
    :current-version 0
    :attachments (vec attachments)
    :citations []
    :quotes []
    :created-at 1000
    :updated-at 1000
    :deleted? false
    :folded? false}))

(defn- session
  [messages]
  {:session-id "s1"
   :message-order (mapv :message-id messages)
   :messages (into {} (map (juxt :message-id identity)) messages)})

(defn- base-session []
  (session [(message "m1" :user "hello")]))

(deftest memory-snapshot-omits-bodies-and-counts-archived
  (let [memory {:enabled? true
                :provided [{:id "mem-1"
                            :body "Prefers concise Chinese answers."
                            :scope {:kind :global}
                            :versions []}
                           {:id "mem-2"
                            :body "Uses tabs in Clojure files."
                            :scope {:kind :project :project-id "p1"}
                            :versions [{}]}]
                :memories {"mem-1" {:status :active}
                           "mem-2" {:status :active}
                           "mem-3" {:status :archived}
                           "mem-4" {:status :archived}}}
        out (context/outbound-context (base-session) {:memory memory})]
    (is (true? (get-in out [:memory :enabled?])))
    (is (= ["mem-1" "mem-2"]
           (mapv :id (get-in out [:memory :items]))))
    (is (= 2 (get-in out [:memory :archived-excluded])))
    (testing "the snapshot publishes a summary and version, never the body"
      (is (nil? (get-in out [:memory :items 0 :body])))
      (is (= "Prefers concise Chinese answers."
             (get-in out [:memory :items 0 :summary])))
      (is (= 1 (get-in out [:memory :items 0 :version])))
      (is (= 2 (get-in out [:memory :items 1 :version])))
      (is (= {:kind :global} (get-in out [:memory :items 0 :scope]))))))

(deftest memory-switch-and-archive-count-are-not-guessed
  (let [out (context/outbound-context (base-session))]
    (is (false? (get-in out [:memory :enabled?])))
    (is (= [] (get-in out [:memory :items])))
    (is (= :unknown (get-in out [:memory :archived-excluded]))
        "a missing archive projection is unknown, not zero")))

(deftest worldbook-snapshot-keeps-hit-and-not-provided-reasons
  (let [out (context/outbound-context
             (base-session)
             {:worldbook {:enabled-books ["wb-1"]
                          :selected [{:entry-id "e1"
                                      :book-id "wb-1"
                                      :reason :keyword}]
                          :omitted [{:entry-id "e2"
                                     :book-id "wb-1"
                                     :reason :budget}
                                    {:entry-id "e3"
                                     :book-id "wb-1"
                                     :reason :cooldown}]}})]
    (is (= ["wb-1"] (get-in out [:worldbook :enabled-books])))
    (is (= [{:book "wb-1" :entry "e1" :reason :keyword}]
           (get-in out [:worldbook :provided])))
    (is (= [{:entry "e2" :reason :budget}
            {:entry "e3" :reason :cooldown}]
           (get-in out [:worldbook :hit-not-provided]))
        "an entry that hit but did not fit keeps its exact reason")))

(deftest instruction-cards-belong-to-the-current-turn
  (let [turn-out (context/outbound-context
                  (base-session)
                  {:instruction {:scope :turn
                                 :cards [{:id "c1"
                                          :name "Answer format"
                                          :version 3}]}})
        session-out (context/outbound-context
                     (base-session)
                     {:instruction {:scope :session
                                    :cards [{:id "c2"
                                             :name "House style"}]}})
        next-out (context/outbound-context (base-session))]
    (is (= [{:name "Answer format" :scope :turn :version 3}]
           (:instruction-cards turn-out)))
    (is (= [{:name "House style" :scope :session :version :unknown}]
           (:instruction-cards session-out)))
    (is (= [] (:instruction-cards next-out))
        "a turn-only card does not appear in the next snapshot")))

(deftest tool-snapshot-keeps-read-write-kind
  (let [out (context/outbound-context
             (base-session)
             {:tools [{:name "read_file"
                       :source :builtin
                       :read-write :read
                       :enabled? true}
                      {:name "write_file"
                       :source :builtin
                       :read-write :write
                       :enabled? false}
                      {:name "unknown_capability"
                       :source :extension
                       :read-write :admin}]})]
    (is (= [{:name "read_file" :source :builtin
             :read-write :read :enabled? true}
            {:name "write_file" :source :builtin
             :read-write :write :enabled? false}
            {:name "unknown_capability" :source :extension
             :read-write :unknown :enabled? true}]
           (:tools out))
        "read, write, and unknown capability stay distinct")))

(deftest attachment-snapshot-uses-the-sent-version-page-range
  (let [session (session
                 [(message "m1" :user "see the paper"
                           [{:attachment-id "a1"
                             :name "paper.pdf"
                             :kind :pdf
                             :extraction {:pages [4 2 3]
                                          :page-count 9}}])])
        out (context/outbound-context session)]
    (is (= [{:attachment-id "a1"
             :name "paper.pdf"
             :kind :pdf
             :pages [2 3 4]
             :page-count 9}]
           (:attachments out))
        "page order is normalized and the original page numbers stay")))

(deftest history-range-is-the-send-range-not-the-visible-history
  (let [session (-> (session [(message "m1" :user "one")
                              (message "m2" :user "two")
                              (message "m3" :assistant "answer")])
                    (assoc :context {:recent-rounds 1}))
        out (context/outbound-context session)]
    (is (= 3 (get-in out [:history :visible-count])))
    (is (= 1 (get-in out [:history :excluded-count])))
    (is (= ["m2" "m3"] (get-in out [:history :range :message-ids])))
    (is (= 1 (get-in out [:history :range :rounds])))
    (is (= "m2" (get-in out [:history :range :first-message-id])))
    (is (= "m3" (get-in out [:history :range :last-message-id])))
    (is (false? (get-in out [:history :compressed?])))))

(deftest unknown-estimates-stay-unknown
  (let [missing (context/outbound-context (base-session))
        estimated (context/outbound-context
                   (base-session)
                   {:estimates {:tokens 42 :kind :estimated}})
        contradictory (context/outbound-context
                       (base-session)
                       {:estimates {:tokens 42 :kind :unknown}})]
    (is (= {:tokens :unknown :kind :unknown} (:estimates missing)))
    (is (= {:tokens 42 :kind :estimated} (:estimates estimated)))
    (is (= {:tokens :unknown :kind :unknown} (:estimates contradictory))
        "a contradictory estimate is unknown, never a zero")))

(deftest system-assistant-and-instruction-previews-are-bounded
  (let [out (context/outbound-context
             (base-session)
             {:system {:text "abc" :modules [:core :tools]}
              :assistant {:name "Steward" :revision 7}
              :instructions {:session-override? true
                             :text (apply str (repeat 300 "x"))}})]
    (is (= 3 (get-in out [:system :prompt-bytes])))
    (is (= ["core" "tools"] (get-in out [:system :modules])))
    (is (= {:id :unknown :name "Steward" :revision 7} (:assistant out)))
    (is (true? (get-in out [:instructions :session-override?])))
    (is (= 200 (count (get-in out [:instructions :text-preview]))))))

(deftest context-snapshot-can-be-called-without-a-session
  (let [snapshot (context/context-snapshot
                  {:memory {:provided [{:id "m" :body "b" :scope :global}]}
                   :history {:range {:message-ids ["x"]}
                             :visible-count 4
                             :excluded-count 3}})]
    (is (= ["m"] (mapv :id (get-in snapshot [:memory :items]))))
    (is (= 4 (get-in snapshot [:history :visible-count])))
    (is (= 3 (get-in snapshot [:history :excluded-count])))
    (is (= :unknown (get-in snapshot [:system :prompt-bytes])))
    (is (= :unknown (get-in snapshot [:assistant :name])))
    (is (= {:tokens :unknown :kind :unknown} (:estimates snapshot)))))

(deftest worldbook-enabled-books-are-inferred-from-bindings
  (let [books {"wb-1" {:id "wb-1" :enabled? true :assistant-ids #{"a1"}}
               "wb-2" {:id "wb-2" :enabled? true :assistant-ids #{"a2"}}
               "wb-3" {:id "wb-3" :enabled? false :assistant-ids #{"a1"}}}
        bound (context/outbound-context
               (base-session)
               {:assistant-id "a1"
                :materials {:books books}
                :worldbook {:selected [{:entry-id "e1"
                                        :book-id "wb-1"
                                        :reason :keyword}]}})
        explicit (context/outbound-context
                  (base-session)
                  {:assistant-id "a1"
                   :materials {:books books}
                   :worldbook {:enabled-books ["wb-9"]
                               :selected [{:entry-id "e1"
                                           :book-id "wb-1"
                                           :reason :keyword}]}})
        participating (context/outbound-context
                       (base-session)
                       {:worldbook {:selected [{:entry-id "e1"
                                                :book-id "wb-1"
                                                :reason :keyword}]
                                    :omitted [{:entry-id "e2"
                                               :book-id "wb-2"
                                               :reason :budget}]}})
        empty-out (context/outbound-context (base-session))]
    (is (= ["wb-1"] (get-in bound [:worldbook :enabled-books]))
        "enabled books bound to the current assistant are inferred")
    (is (= ["wb-9"] (get-in explicit [:worldbook :enabled-books]))
        "an explicit list wins over inference")
    (is (= ["wb-1" "wb-2"]
           (get-in participating [:worldbook :enabled-books]))
        "books that participated in this turn are the last inference")
    (is (= [] (get-in empty-out [:worldbook :enabled-books]))
        "no data means no invented book")))

(deftest prompt-modules-calculate-bytes-and-names
  (let [out (context/outbound-context
             (base-session)
             {:system {:modules [{:name "COMMON" :text "abc"}
                                 {:name "PLAN_PROMPT" :text "de"}
                                 {:name "EMPTY" :text ""}]}})]
    (is (= 5 (get-in out [:system :prompt-bytes])))
    (is (= ["COMMON" "PLAN_PROMPT"] (get-in out [:system :modules]))
        "an empty module is not listed")))

(deftest assistant-entity-uses-name-and-revision
  (let [out (context/outbound-context
             (base-session)
             {:assistant {:id "a1" :name "Steward" :v 3}})
        missing (context/outbound-context (base-session) {:assistant nil})]
    (is (= {:id "a1" :name "Steward" :revision 3} (:assistant out)))
    (is (= {:id :unknown :name :unknown :revision :unknown}
           (:assistant missing)))))

(deftest role-tools-map-read-write-capabilities
  (let [out (context/outbound-context
             (base-session)
             {:tools [{:name "cat" :source :builtin}
                      {:name "write_file" :source :builtin}
                      {:name "clj-plan" :source :role}
                      {:name "clj-review" :source :role}
                      {:name "devops" :source :role}
                      {:name "mystery" :source :extension}
                      {:name "annotated-read"
                       :annotations {:readOnlyHint true}}
                      {:name "caps" :capabilities #{:read :write}}]})]
    (is (= [:read :write :read-write :read :read :unknown :read :read-write]
           (mapv :read-write (:tools out))))
    (is (= [:builtin :builtin :role :role :role :extension
            :unknown :unknown]
           (mapv :source (:tools out))))))

(deftest character-estimate-marks-estimated
  (let [estimated (context/outbound-context
                   (base-session)
                   {:estimates {:text (apply str (repeat 400 "a"))}})
        small (context/outbound-context
               (base-session)
               {:estimates {:chars 3}})
        empty (context/outbound-context
               (base-session)
               {:estimates {:text ""}})]
    (is (= {:tokens 100 :kind :estimated} (:estimates estimated)))
    (is (= {:tokens 1 :kind :estimated} (:estimates small))
        "a positive character count never becomes zero tokens")
    (is (= {:tokens :unknown :kind :unknown} (:estimates empty))
        "an empty estimate is unknown, not zero")))

(deftest instruction-preview-truncates-and-redacts
  (let [long (context/outbound-context
              (base-session)
              {:instructions {:text (apply str (repeat 300 "x"))}})
        sensitive (context/outbound-context
                   (base-session)
                   {:instructions {:text "password = hunter2 and more"}})]
    (is (= 200 (count (get-in long [:instructions :text-preview]))))
    (is (false? (get-in long [:instructions :redacted?])))
    (is (nil? (get-in sensitive [:instructions :text-preview])))
    (is (true? (get-in sensitive [:instructions :redacted?])))))

(deftest memory-snapshot-publishes-disabled-and-omitted-reasons
  (let [out (context/outbound-context
             (base-session)
             {:memory {:enabled? false
                       :reason :memory-read-disabled
                       :provided []
                       :omitted [{:id "m1"
                                  :summary "Long preference"
                                  :reason :budget
                                  :chars 2400}]
                       :archived-excluded 1}})]
    (is (false? (get-in out [:memory :enabled?])))
    (is (= :memory-read-disabled (get-in out [:memory :reason])))
    (is (= [{:id "m1" :summary "Long preference" :reason :budget
             :chars 2400}]
           (get-in out [:memory :omitted]))
        "a memory that hit but did not fit keeps its reason and size")
    (is (= 1 (get-in out [:memory :archived-excluded])))))

(deftest instruction-card-preview-and-tool-description-are-published
  (let [out (context/outbound-context
             (base-session)
             {:instruction-cards [{:name "Answer format"
                                   :scope :turn
                                   :version 3
                                   :body-preview "List assumptions first."}]
              :tools [{:name "devops"
                       :source :plan
                       :read-write :read
                       :description "Investigate repository facts read-only."}]})]
    (is (= "List assumptions first."
           (get-in out [:instruction-cards 0 :body-preview])))
    (is (= "Investigate repository facts read-only."
           (get-in out [:tools 0 :description])))))
