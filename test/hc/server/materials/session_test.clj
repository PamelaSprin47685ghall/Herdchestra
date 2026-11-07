(ns hc.server.materials.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]
            [hc.server.materials.session :as session]
            [hc.server.materials.store :as store]))

(defn- new-service
  []
  (let [store (materials/memory-store)
        service (session/service store)]
    (is (true? (:ok? (session/initialize! service))))
    {:store store :service service}))

(defn- command!
  [service session payload]
  (session/handle-command! service
                           {:session session
                            :command-id "cmd-1"
                            :command-epoch 1
                            :payload payload}))

(deftest command-flow-and-snapshot
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        settings (command! service session
                           {:kind :materials/settings/update
                            :memory-read? true
                            :memory-suggest? true})
        remember (command! service session
                           {:kind :materials/memory/remember
                            :id "m1"
                            :body "User prefers verifiable steps."
                            :category :working-style
                            :scope :global
                            :epistemic :preference})
        propose (command! service session
                          {:kind :materials/memory/propose
                           :id "c1"
                           :body "User works on compiler tooling."
                           :category :identity-background
                           :scope {:kind :assistant :assistant-id "a1"}
                           :epistemic :fact
                           :source {:message-ids ["msg-1"]
                                    :excerpt "I work on compiler tooling."}})
        approve (command! service session
                          {:kind :materials/memory/approve
                           :memory-id "c1"})
        book (command! service session
                       {:kind :materials/worldbook/create
                        :id "wb1"
                        :name "Project lore"})
        _ (command! service session
                    {:kind :materials/worldbook/bind
                     :book-id "wb1"
                     :assistant-id "a1"})
        entry (command! service session
                        {:kind :materials/worldbook/entry/create
                         :id "e1"
                         :book-id "wb1"
                         :title "Compiler"
                         :body "The project builds a compiler."
                         :trigger {:kind :keyword :keywords ["compiler"]}})
        card (command! service session
                       {:kind :materials/instruction/create
                        :id "card1"
                        :name "Explain"
                        :body "Explain {{topic}}."})
        _ (command! service session
                    {:kind :materials/instruction/session/set
                     :card-ids ["card1"]})
        snap (session/snapshot service session)]
    (is (true? (:ok? settings)))
    (is (true? (:ok? remember)))
    (is (true? (:ok? propose)))
    (is (true? (:ok? approve)))
    (is (true? (:ok? book)))
    (is (true? (:ok? entry)))
    (is (true? (:ok? card)))
    (is (= :active (get-in snap [:memory/by-id "c1" :status])))
    (is (= ["card1"] (get-in snap [:instruction-session :card-ids])))
    (is (= 2 (get-in snap [:memory/counts :active])))
    (is (= "wb1" (get-in snap [:worldbook/by-id "wb1" :id])))
    (is (= "e1" (get-in snap [:worldbook-entry/by-id "e1" :id])))
    (is (true? (:materials/ready? snap)))))

(deftest context-for-turn-advances-once-and-injects
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/settings/update
                     :memory-read? true :memory-suggest? true})
        _ (command! service session
                    {:kind :materials/memory/remember
                     :id "m1"
                     :body "User prefers verifiable steps."
                     :category :working-style
                     :scope :global
                     :epistemic :preference})
        _ (command! service session
                    {:kind :materials/worldbook/create :id "wb1" :name "Project"})
        _ (command! service session
                    {:kind :materials/worldbook/bind
                     :book-id "wb1" :assistant-id "a1"})
        _ (command! service session
                    {:kind :materials/worldbook/entry/create
                     :id "e1" :book-id "wb1" :title "Compiler"
                     :body "The project builds a compiler."
                     :trigger {:kind :keyword :keywords ["compiler"]}})
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain"
                     :body "Explain {{topic}}."})
        _ (command! service session
                    {:kind :materials/instruction/session/set
                     :card-ids ["card1"]})
        request {:session session
                 :turn-id "turn-1"
                 :text "The compiler is slow."
                 :user-texts ["The compiler is slow."]
                 :memory-budget 4000
                 :worldbook-budget 4000
                 :instruction-values {:topic "the compiler"}}
        first (session/context-for-turn! service request)
        second (session/context-for-turn! service request)
        snap (session/snapshot service session)]
    (is (true? (:ok? first)))
    (is (= 1 (:turn first)))
    (is (= ["e1"] (map :entry-id (get-in first [:worldbook :selected]))))
    (is (re-find #"verifiable steps" (get-in first [:memory :text])))
    (is (re-find #"Explain the compiler" (get-in first [:instruction :text])))
    (is (re-find #"builds a compiler" (get-in first [:injection :worldbook])))
    (is (true? (:ok? second)))
    (is (= 1 (:turn second)))
    (is (= 1 (get-in snap [:worldbook-session :turn])))
    (is (= ["e1"] (map :entry-id (get-in second [:worldbook :selected]))))))

(deftest temporary-session-does-not-read-or-write-memory
  (let [{:keys [service]} (new-service)
        session {:session-id "temp" :assistant-id "a1" :temporary? true}
        _ (command! service {:session-id "s1" :assistant-id "a1"}
                    {:kind :materials/settings/update
                     :memory-read? true :memory-suggest? true})
        _ (command! service {:session-id "s1" :assistant-id "a1"}
                    {:kind :materials/memory/remember
                     :id "m1" :body "Saved memory." :category :working-style
                     :scope :global :epistemic :preference})
        write (command! service session
                        {:kind :materials/memory/remember
                         :id "m2" :body "Temporary memory." :category :working-style
                         :scope :global})
        context (session/context-for-turn! service
                                           {:session session
                                            :turn-id "temp-turn"
                                            :text "hello"
                                            :user-texts ["hello"]
                                            :memory-budget 1000
                                            :worldbook-budget 1000})]
    (is (false? (:ok? write)))
    (is (= :MEMORY_TEMPORARY_SESSION (get-in write [:error :code])))
    (is (true? (:ok? context)))
    (is (= [] (get-in context [:memory :provided])))
    (is (= :temporary-session (get-in context [:memory :reason])))))

(deftest temporary-session-memory-commands-are-refused
  (let [{:keys [service]} (new-service)
        regular {:session-id "s1" :assistant-id "a1"}
        temporary {:session-id "temp" :assistant-id "a1" :temporary? true}
        _ (command! service regular
                    {:kind :materials/settings/update
                     :memory-read? true :memory-suggest? true})
        _ (command! service regular
                    {:kind :materials/memory/remember
                     :id "m1" :body "Saved memory."
                     :category :working-style
                     :scope :global :epistemic :preference})
        effective (command! service temporary
                            {:kind :materials/memory/effective
                             :temporary? false})
        approve (command! service temporary
                          {:kind :materials/memory/approve
                           :memory-id "m1"})
        delete (command! service temporary
                         {:kind :materials/memory/delete
                          :memory-id "m1" :confirm true})
        search (command! service temporary
                         {:kind :materials/memory/search
                          :query "Saved"})
        snap (command! service temporary {:kind :materials/snapshot})]
    (is (true? (:ok? effective)))
    (is (= [] (get-in effective [:receipt :result :memories])))
    (is (= :temporary-session
           (get-in effective [:receipt :result :reason])))
    (doseq [result [approve delete search]]
      (is (false? (:ok? result)))
      (is (= :MEMORY_TEMPORARY_SESSION (get-in result [:error :code]))))
    (is (empty? (get-in snap [:receipt :result :memory/by-id])))
    (is (= [] (get-in snap [:receipt :result :memory/order])))
    (is (zero? (get-in snap [:receipt :result :memory/counts :active])))))

(deftest temporary-session-one-shot-read-override
  (let [{:keys [service]} (new-service)
        regular {:session-id "s1" :assistant-id "a1"}
        temporary {:session-id "temp" :assistant-id "a1" :temporary? true}
        _ (command! service regular
                    {:kind :materials/settings/update
                     :memory-read? true :memory-suggest? true})
        _ (command! service regular
                    {:kind :materials/memory/remember
                     :id "m1" :body "Saved memory."
                     :category :working-style
                     :scope :global :epistemic :preference})
        allowed (session/context-for-turn!
                 service
                 {:session temporary
                  :turn-id "temp-read"
                  :text "hello"
                  :user-texts ["hello"]
                  :memory-budget 1000
                  :worldbook-budget 1000
                  :temporary-read? true})
        denied (session/context-for-turn!
                service
                {:session temporary
                 :turn-id "temp-no-read"
                 :text "hello"
                 :user-texts ["hello"]
                 :memory-budget 1000
                 :worldbook-budget 1000})
        remember (command! service temporary
                           {:kind :materials/memory/remember
                            :id "m2" :body "Not saved."
                            :category :working-style
                            :scope :global})
        propose (command! service temporary
                          {:kind :materials/memory/propose
                           :id "c2" :body "Not proposed."
                           :category :working-style
                           :scope :global
                           :epistemic :fact
                           :source {:session-id "temp"
                                    :message-ids ["m1"]
                                    :excerpt "Not proposed."}})]
    (is (= ["m1"] (map :id (get-in allowed [:memory :provided]))))
    (is (= [] (get-in denied [:memory :provided])))
    (is (= :temporary-session (get-in denied [:memory :reason])))
    (is (false? (:ok? remember)))
    (is (= :MEMORY_TEMPORARY_SESSION (get-in remember [:error :code])))
    (is (false? (:ok? propose)))
    (is (= :MEMORY_TEMPORARY_SESSION (get-in propose [:error :code])))))

(deftest normal-session-ignores-one-shot-temporary-read
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/memory/remember
                     :id "m1" :body "Saved memory."
                     :category :working-style
                     :scope :global :epistemic :preference})
        context (session/context-for-turn!
                 service
                 {:session session
                  :turn-id "normal"
                  :text "hello"
                  :user-texts ["hello"]
                  :memory-budget 1000
                  :worldbook-budget 1000
                  :temporary-read? true})]
    (is (= :memory-read-disabled (get-in context [:memory :reason])))
    (is (= [] (get-in context [:memory :provided])))))

(deftest effective-memories-read-honors-one-shot-temporary-read
  (testing "the outbound read honors the per-turn override; the next read
            without it stays closed"
    (let [{:keys [service]} (new-service)
          regular {:session-id "s1" :assistant-id "a1"}
          temporary {:session-id "temp" :assistant-id "a1" :temporary? true}
          _ (command! service regular
                      {:kind :materials/settings/update
                       :memory-read? true :memory-suggest? false})
          _ (command! service regular
                      {:kind :materials/memory/remember
                       :id "m1" :body "Saved memory."
                       :category :working-style
                       :scope :global :epistemic :preference})
          denied (session/effective-memories-read service temporary {})
          allowed (session/effective-memories-read
                   service temporary {:temporary-read? true})]
      (is (= :temporary-session (:reason denied)))
      (is (= [] (:provided denied)))
      (is (= ["m1"] (mapv :id (:provided allowed))))
      (is (true? (:temporary-read? allowed))
          "the one-shot grant is reported, not persisted"))))

(deftest snapshot-reports-bounded-worldbook-omitted-entries
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/worldbook/create
                     :id "wb1" :name "Project"})
        _ (command! service session
                    {:kind :materials/worldbook/bind
                     :book-id "wb1" :assistant-id "a1"})
        _ (command! service session
                    {:kind :materials/worldbook/entry/create
                     :id "e1" :book-id "wb1" :title "Compiler"
                     :body "BODY-MUST-NOT-LEAK"
                     :trigger {:kind :constant}})
        _ (session/context-for-turn!
           service
           {:session session
            :turn-id "turn-1"
            :text "hello"
            :user-texts ["hello"]
            :memory-budget 1000
            :worldbook-budget 0})
        snap (session/snapshot service session)
        omitted (get-in snap [:worldbook-session :last-turn :omitted])]
    (is (= 1 (count omitted)))
    (is (= #{:entry-id :book-id :title :reason :chars}
           (set (keys (first omitted)))))
    (is (= "e1" (:entry-id (first omitted))))
    (is (= :budget (:reason (first omitted))))
    (is (not (contains? (first omitted) :body)))))

(deftest snapshot-bounds-worldbook-omitted-to-fifty
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/worldbook/create
                     :id "wb1" :name "Project"})
        _ (command! service session
                    {:kind :materials/worldbook/bind
                     :book-id "wb1" :assistant-id "a1"})]
    (dotimes [index 60]
      (command! service session
                {:kind :materials/worldbook/entry/create
                 :id (str "e" index)
                 :book-id "wb1"
                 :title (str "Entry " index)
                 :body "x"
                 :trigger {:kind :constant}}))
    (session/context-for-turn!
     service
     {:session session
      :turn-id "turn-many"
      :text "hello"
      :user-texts ["hello"]
      :memory-budget 1000
      :worldbook-budget 0})
    (let [snap (session/snapshot service session)
          omitted (get-in snap [:worldbook-session :last-turn :omitted])]
      (is (= 50 (count omitted)))
      (is (every? #(contains? % :entry-id) omitted)))))

(deftest snapshot-without-a-turn-does-not-invent-worldbook-omitted
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        snap (session/snapshot service session)]
    (is (nil? (get-in snap [:worldbook-session :last-turn])))))

(deftest instruction-conflict-does-not-advance-turn
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "table" :name "Table" :body "Use a table."
                     :output-format :markdown-table})
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "json" :name "JSON" :body "Use JSON."
                     :output-format :json})
        result (session/context-for-turn! service
                                          {:session session
                                           :turn-id "turn-1"
                                           :text "hello"
                                           :user-texts ["hello"]
                                           :turn-card-ids ["table" "json"]})
        snap (session/snapshot service session)]
    (is (false? (:ok? result)))
    (is (= :INSTRUCTION_FORMAT_CONFLICT (get-in result [:error :code])))
    (is (nil? (:worldbook-session snap)))))


(deftest staged-turn-cards-feed-context-and-consume
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain"
                     :body "Explain {{topic}}."})
        stage (command! service session
                        {:kind :materials/instruction/turn/stage
                         :card-ids ["card1"]
                         :instruction-values {:topic "the compiler"}})
        before (session/snapshot service session)
        context (session/context-for-turn!
                 service
                 {:session session
                  :turn-id "turn-1"
                  :text "hello"
                  :user-texts ["hello"]
                  :turn-card-ids []
                  :use-staged? true
                  :instruction-values {}})
        after (session/snapshot service session)
        second (session/context-for-turn!
                service
                {:session session
                 :turn-id "turn-2"
                 :text "again"
                 :user-texts ["again"]
                 :turn-card-ids []
                 :instruction-values {}})]
    (is (true? (:ok? stage)))
    (is (= ["card1"] (get-in before [:instruction-turn :card-ids])))
    (is (true? (:ok? context)))
    (is (re-find #"Explain the compiler" (get-in context [:instruction :text])))
    (is (nil? (:instruction-turn after)))
    (is (true? (:ok? second)))
    (is (= "" (get-in second [:instruction :text])))))

(deftest explicit-turn-cards-override-and-consume-staged-selection
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "staged" :name "Staged" :body "Staged card."})
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "explicit" :name "Explicit" :body "Explicit card."})
        _ (command! service session
                    {:kind :materials/instruction/turn/stage
                     :card-ids ["staged"]})
        context (session/context-for-turn!
                 service
                 {:session session
                  :turn-id "turn-1"
                  :text "hello"
                  :user-texts ["hello"]
                  :use-staged? true
                  :turn-card-ids ["explicit"]})
        snap (session/snapshot service session)]
    (is (re-find #"Explicit card" (get-in context [:instruction :text])))
    (is (nil? (:instruction-turn snap))
        "an explicit override still consumes the staged selection")))


(deftest second-send-without-turn-cards-does-not-reuse-the-first-turn
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "card1" :name "First" :body "First-turn card."})
        first (session/context-for-turn!
               service
               {:session session
                :turn-id "turn-1"
                :text "hello"
                :user-texts ["hello"]
                :turn-card-ids ["card1"]})
        second (session/context-for-turn!
                service
                {:session session
                 :turn-id "turn-2"
                 :text "again"
                 :user-texts ["hello" "again"]
                 :turn-card-ids []})]
    (is (re-find #"First-turn card" (get-in first [:instruction :text])))
    (is (= "" (get-in second [:instruction :text])))
    (is (= 2 (:turn second)))))


(deftest staged-and-session-cards-are-session-scoped
  (let [{:keys [service]} (new-service)
        s1 {:session-id "s1" :assistant-id "a1"}
        s2 {:session-id "s2" :assistant-id "a1"}
        _ (command! service s1
                    {:kind :materials/instruction/create
                     :id "one" :name "One" :body "Session one card."})
        _ (command! service s1
                    {:kind :materials/instruction/create
                     :id "two" :name "Two" :body "Session two card."})
        _ (command! service s1
                    {:kind :materials/instruction/session/set
                     :card-ids ["one"]})
        _ (command! service s2
                    {:kind :materials/instruction/turn/stage
                     :card-ids ["two"]})
        one (session/context-for-turn!
             service
             {:session s1
              :turn-id "turn-s1"
              :text "hello"
              :user-texts ["hello"]
              :turn-card-ids []})
        two (session/context-for-turn!
             service
             {:session s2
              :turn-id "turn-s2"
              :text "hello"
              :user-texts ["hello"]
              :use-staged? true
              :turn-card-ids []})]
    (is (re-find #"Session one card" (get-in one [:instruction :text])))
    (is (not (re-find #"Session two card" (get-in one [:instruction :text]))))
    (is (re-find #"Session two card" (get-in two [:instruction :text])))
    (is (not (re-find #"Session one card" (get-in two [:instruction :text]))))))


(deftest stage-turn-from-send-payload-helper
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain {{topic}}."})
        staged (session/stage-turn-from-send!
                service session
                {:turn-card-ids ["card1"]
                 :instruction-values {"topic" "the compiler"}})
        snap (session/snapshot service session)
        context (session/context-for-turn!
                 service
                 {:session session
                  :turn-id "turn-1"
                  :text "hello"
                  :user-texts ["hello"]
                  :use-staged? true
                  :turn-card-ids []
                  :instruction-values {}})]
    (is (true? (:ok? staged)))
    (is (= ["card1"] (get-in snap [:instruction-turn :card-ids])))
    (is (re-find #"Explain the compiler" (get-in context [:instruction :text])))))


(deftest staged-turn-cards-are-deduped-by-command-id
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain {{topic}}."})
        stage (session/stage-turn-from-send!
               service session
               {:turn-card-ids ["card1"]
                :instruction-values {:topic "compiler"}}
               {:command-id "send-1"})
        first (session/context-for-turn!
               service
               {:session session
                :turn-id "turn-1"
                :text "hello"
                :user-texts ["hello"]
                :use-staged? true
                :turn-card-ids []})
        replay (session/stage-turn-from-send!
                service session
                {:turn-card-ids ["card1"]
                 :instruction-values {:topic "compiler"}}
                {:command-id "send-1"})
        second (session/context-for-turn!
                service
                {:session session
                 :turn-id "turn-2"
                 :text "again"
                 :user-texts ["again"]
                 :turn-card-ids []})]
    (is (true? (:ok? stage)))
    (is (re-find #"Explain compiler" (get-in first [:instruction :text])))
    (is (true? (:ok? replay)))
    (is (= :already-consumed (get-in replay [:receipt :result :reason])))
    (is (= "" (get-in second [:instruction :text])))))


(deftest staged-selection-is-not-consumed-when-disabled
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        _ (command! service session
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain the card."})
        _ (command! service session
                    {:kind :materials/instruction/turn/stage
                     :card-ids ["card1"]})
        non-user (session/context-for-turn!
                  service
                  {:session session
                   :turn-id "nudge-1"
                   :text "hello"
                   :user-texts ["hello"]
                   :use-staged? false
                   :turn-card-ids []})
        snap (session/snapshot service session)
        user (session/context-for-turn!
              service
              {:session session
               :turn-id "turn-1"
               :text "hello"
               :user-texts ["hello"]
               :use-staged? true
               :turn-card-ids []})]
    (is (true? (:ok? non-user)))
    (is (= "" (get-in non-user [:instruction :text])))
    (is (= ["card1"] (get-in snap [:instruction-turn :card-ids])))
    (is (re-find #"Explain the card" (get-in user [:instruction :text])))))


(deftest user-turn-request-gate
  (is (true? (session/user-turn-request?
              {:snapshot {:messages [{:role :user :text "hello"}]}})))
  (is (false? (session/user-turn-request?
               {:snapshot {:messages [{:role :user :text "hello"}
                                      {:role :system :text "nudge"}]}})))
  (is (false? (session/user-turn-request?
               {:snapshot {:messages [{:role :user :text "hello"}]
                           :nudge-id "n1"}})))
  (testing "explicit first-user-turn marker wins over the last message role"
    (is (true? (session/user-turn-request?
                {:snapshot {:user-turn? true
                            :turn-id "turn-1"
                            :turn-command-id "turn-1"
                            :messages [{:role :user :text "hello"}
                                       {:role :tool :text "result"}]}})))
    (is (true? (session/user-turn-request?
                {:turn-command-id "turn-matching-1"
                 :snapshot {:user-turn? true
                            :turn-id "turn-matching-1"
                            :messages [{:role :user :text "hello"}
                                       {:role :tool :text "result"}]}}))
        "request-level turn-command-id and snapshot turn-id may be split")
    (is (true? (session/user-turn-request?
                {:user-turn? true
                 :turn-command-id "turn-1"
                 :snapshot {:turn-id "turn-1"
                            :messages [{:role :user :text "hello"}
                                       {:role :tool :text "result"}]}}))
        "the Owner stamps the marker and command id on the request envelope")
    (is (false? (session/user-turn-request?
                 {:snapshot {:user-turn? true
                             :turn-id "turn-1"
                             :messages [{:role :user :text "hello"}
                                        {:role :tool :text "result"}]}}))
        "a bare marker without matching turn identity is a continuation")
    (is (false? (session/user-turn-request?
                 {:user-turn? true
                  :turn-command-id "turn-1"
                  :snapshot {:messages [{:role :user :text "hello"}
                                        {:role :tool :text "result"}]}}))
        "a lone command id is a continuation")
    (is (false? (session/user-turn-request?
                 {:snapshot {:user-turn? true
                             :turn-id "turn-1"
                             :turn-command-id "turn-2"
                             :messages [{:role :user :text "hello"}
                                        {:role :tool :text "result"}]}}))
        "a mismatched command id is a continuation")
    (is (false? (session/user-turn-request?
                 {:snapshot {:user-turn? true
                             :turn-id ""
                             :turn-command-id ""
                             :messages [{:role :user :text "hello"}
                                        {:role :tool :text "result"}]}}))
        "a blank turn identity is a continuation")
    (is (false? (session/user-turn-request?
                 {:snapshot {:user-turn? false
                             :messages [{:role :user :text "hello"}]}})))
    (is (false? (session/user-turn-request?
                 {:snapshot {:user-turn? true
                             :nudge-id "n1"
                             :messages [{:role :user :text "hello"}]}})))
    (is (false? (session/user-turn-request?
                 {:snapshot {:user-turn? true
                             :regenerate? true
                             :messages [{:role :user :text "hello"}]}})))
    (is (true? (session/user-turn-request?
                {:snapshot {:messages [{:role :user :text "hello"}]}})))
    (is (false? (session/user-turn-request?
                 {:snapshot {:messages [{:role :user :text "hello"}
                                        {:role :tool :text "result"}]}})))))

(deftest unknown-command-is-refused
  (let [{:keys [service]} (new-service)
        result (command! service {:session-id "s1"}
                         {:kind :materials/nope})]
    (is (false? (:ok? result)))
    (is (= :MATERIALS_COMMAND_UNKNOWN (get-in result [:error :code])))))

(deftest first-command-initializes-an-empty-store
  (let [memory (materials/memory-store)
        service (session/service memory)
        result (session/handle-command!
                service
                {:session {:session-id "s1"}
                 :command-id "readiness-1"
                 :payload {:kind :materials/settings/update
                           :memory-read? true}})
        snapshot (session/snapshot service {:session-id "s1"})]
    (is (true? (:ok? result)))
    (is (true? (:materials/ready? snapshot)))
    (is (true? (get-in snapshot [:materials/settings :memory-read?])))))

(deftest snapshot-initializes-once-and-stays-ready
  (let [memory (materials/memory-store)
        service (session/service memory)
        first (session/snapshot service {:session-id "s1"})
        second (session/snapshot service {:session-id "s1"})]
    (is (true? (:materials/ready? first)))
    (is (true? (:materials/ready? second)))
    (is (= (:materials/revision first) (:materials/revision second)))))

(deftest older-state-without-new-keys-loads-ready
  (let [memory (materials/memory-store)
        old-state (-> (materials/new-state)
                      (assoc :revision 7)
                      (dissoc :instruction-turn-receipts
                              :instruction-turns
                              :instruction-sessions))
        _ (store/commit-snapshot! memory nil old-state)
        service (session/service memory)
        snapshot (session/snapshot service {:session-id "s1"})
        command (session/handle-command!
                 service
                 {:session {:session-id "s1"}
                  :command-id "readiness-old"
                  :payload {:kind :materials/settings/update
                            :memory-suggest? true}})]
    (is (true? (:materials/ready? snapshot)))
    (is (= 7 (:materials/revision snapshot)))
    (is (true? (:ok? command)))
    (is (= 8 (:state-revision command)))))

(defn- read-session []
  {:session-id "s1" :assistant-id "a1"})

(defn- seed-read-state!
  [service]
  (let [session (read-session)]
    (command! service session
              {:kind :materials/settings/update :memory-read? true})
    (command! service session
              {:kind :materials/memory/remember
               :id "m1"
               :body "Prefers concise answers."
               :category :expression-preference
               :scope :global
               :epistemic :preference})
    (command! service session
              {:kind :materials/memory/remember
               :id "m2"
               :body "Old preference."
               :category :expression-preference
               :scope :global
               :epistemic :preference})
    (command! service session
              {:kind :materials/memory/archive :memory-id "m2"})
    (command! service session
              {:kind :materials/worldbook/create
               :book-id "wb-1"
               :name "WB"
               :assistant-ids #{"a1"}})
    (command! service session
              {:kind :materials/worldbook/entry/create
               :book-id "wb-1"
               :entry-id "wbe-1"
               :title "Clojure"
               :body "The user works in Clojure."
               :trigger {:kind :keyword
                         :keywords ["Clojure"]
                         :match :any}})
    (command! service session
              {:kind :materials/instruction/create
               :card-id "card-session"
               :name "Session card"
               :body "Answer concisely."})
    (command! service session
              {:kind :materials/instruction/create
               :card-id "card-turn"
               :name "Turn card"
               :body "Use one paragraph."})
    (command! service session
              {:kind :materials/instruction/session/set
               :card-ids ["card-session"]})
    (command! service session
              {:kind :materials/instruction/turn/stage
               :card-ids ["card-turn"]})
    session))

(deftest read-views-do-not-change-revision-or-advance-counters
  (let [{:keys [store service]} (new-service)
        session (seed-read-state! service)
        before (:revision (materials/load-state store))
        memories (session/effective-memories-read service session)
        worldbook (session/worldbook-selection-read
                   service session {:texts ["I use Clojure"]})
        cards (session/instruction-cards-read service session)
        after-state (:state (materials/load-state store))
        after (:revision (materials/load-state store))]
    (is (true? (:ok? memories)))
    (is (true? (:ok? worldbook)))
    (is (true? (:ok? cards)))
    (is (= before after)
        "reading the outbound views does not commit a new revision")
    (is (= ["m1"] (mapv :id (:memories memories))))
    (is (= 1 (:archived-excluded memories)))
    (is (nil? (get-in memories [:memories 0 :body]))
        "the memory body never leaves the read view")
    (is (= "Prefers concise answers."
           (get-in memories [:memories 0 :summary])))
    (is (= ["wbe-1"] (mapv :entry (:provided worldbook))))
    (is (= [{:name "Turn card" :scope :turn :version 1
             :body-preview "Use one paragraph."}
            {:name "Session card" :scope :session :version 1
             :body-preview "Answer concisely."}]
           (:cards cards)))
    (is (nil? (get-in after-state [:worldbook :sessions "s1"]))
        "a read does not create a turn runtime or advance counters")))

(deftest worldbook-selection-read-keeps-hit-not-provided-reason
  (let [{:keys [service]} (new-service)
        session (seed-read-state! service)
        first-read (session/worldbook-selection-read
                    service session {:texts ["I use Clojure"]})
        second-read (session/worldbook-selection-read
                     service session {:texts ["I use Clojure"]})
        zero-budget (session/worldbook-selection-read
                     service session {:texts ["I use Clojure"] :budget 0})]
    (is (= ["wbe-1"] (mapv :entry (:provided first-read))))
    (is (= ["wbe-1"] (mapv :entry (:provided second-read)))
        "a repeated read still provides the entry; counters did not move")
    (is (= [{:entry "wbe-1" :reason :budget}]
           (:hit-not-provided zero-budget)))))

(deftest read-views-empty-library-return-empty-collections
  (let [store (materials/memory-store)
        service (session/service store)
        memories (session/effective-memories-read service {:session-id "s1"})
        worldbook (session/worldbook-selection-read
                   service {:session-id "s1"} {:texts ["x"]})
        cards (session/instruction-cards-read service {:session-id "s1"})]
    (is (= [] (:memories memories)))
    (is (= 0 (:archived-excluded memories)))
    (is (= [] (:enabled-books worldbook)))
    (is (= [] (:provided worldbook)))
    (is (= [] (:hit-not-provided worldbook)))
    (is (= [] (:cards cards)))
    (is (false? (:found? (materials/load-state store)))
        "a read never initializes an absent library")))

(deftest material-session-resolves-the-built-in-assistant
  (is (= "default"
         (:assistant-id (session/material-session {:session-id "legacy"}))))
  (is (= "a1"
         (:assistant-id (session/material-session
                         {:session-id "s1" :assistant-id "a1"}))))
  (is (= "a1"
         (:assistant-id (session/material-session
                         {:session-id "s1"
                          :materials {:assistant-id "a1"}})))
      "a blank session id does not hide the materials override"))

(deftest memory-read-publishes-disabled-and-budget-reasons
  (let [{:keys [service]} (new-service)
        session {:session-id "s1" :assistant-id "a1"}
        disabled (session/effective-memories-read service session)
        _ (command! service session
                    {:kind :materials/settings/update :memory-read? true})
        _ (command! service session
                    {:kind :materials/memory/remember
                     :id "m1"
                     :body (apply str (repeat 1800 "x"))
                     :category :working-style
                     :scope :global
                     :epistemic :preference})
        zero (session/effective-memories-read service session {:budget 0})
        full (session/effective-memories-read service session {:budget 3000})]
    (is (false? (:enabled? disabled)))
    (is (= :memory-read-disabled (:reason disabled)))
    (is (= [] (:provided disabled)))
    (is (true? (:enabled? zero)))
    (is (= [] (:provided zero)))
    (is (= :budget (get-in zero [:omitted 0 :reason])))
    (is (= 1800 (get-in zero [:omitted 0 :chars])))
    (is (= ["m1"] (mapv :id (:provided full))))
    (is (nil? (get-in full [:provided 0 :body]))
        "the read view still never publishes the body")))

(deftest legacy-session-binds-the-default-worldbook
  (let [{:keys [service]} (new-service)
        session {:session-id "legacy"}
        _ (command! service session
                    {:kind :materials/worldbook/create
                     :book-id "wb-default"
                     :name "Legacy book"
                     :assistant-ids #{"default"}})
        _ (command! service session
                    {:kind :materials/worldbook/entry/create
                     :book-id "wb-default"
                     :entry-id "wbe-default"
                     :title "Clojure"
                     :body "The user works in Clojure."
                     :trigger {:kind :keyword
                               :keywords ["Clojure"]
                               :match :any}})
        read (session/worldbook-selection-read
              service session {:texts ["I use Clojure"]})]
    (is (true? (:ok? read)))
    (is (= ["wb-default"] (:enabled-books read)))
    (is (= ["wbe-default"] (mapv :entry (:provided read)))
        "a session without :assistant-id still binds the built-in assistant")))

(deftest instruction-read-merges-explicit-turn-cards
  (let [{:keys [service]} (new-service)
        session (seed-read-state! service)
        read (session/instruction-cards-read
              service session {:turn-card-ids ["card-session"]})]
    (is (= ["Session card"]
           (mapv :name (:cards read)))
        "the explicit send selection appears once even when it is also a session card")
    (is (= :turn (:scope (first (:cards read)))))))
