(ns hc.server.materials.send-context-smoke-test
  "Focused smoke for the actual model-request path.

  These tests call the private `hc.server.app.system/material-injection`
  directly.  They prove that direct user-turn fields reach the model request,
  that explicit `:user-turn? false` continuations pass through unchanged, and
  that an explicit `:user-turn? true` user turn still injects when a tool
  result arrived after the user message.  The matching `:turn-command-id` and
  `:turn-id` may arrive on the request envelope or its snapshot; lone,
  blank or mismatched ids stay continuations and advance no worldbook turn."
  (:require [clojure.test :refer [deftest is]]
            [hc.server.app.system :as app-system]
            [hc.server.materials :as materials]
            [hc.server.materials.session :as session]
            [hc.server.sessions.registry :as registry]))

(defn- command!
  [service session payload]
  (session/handle-command! service
                           {:session session
                            :command-id "cmd-1"
                            :command-epoch 1
                            :payload payload}))

(deftest material-injection-uses-direct-user-turn-fields
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        _ (command! service session-record
                    {:kind :materials/instruction/create
                     :id "card1"
                     :name "Explain"
                     :body "Explain {{topic}}."})
        request {:call-id "call-direct"
                 :snapshot {:messages
                            [{:role :user
                              :text "hello"
                              :turn-card-ids ["card1"]
                              :instruction-values {:topic "compiler"}}]}}
        injected (with-redefs [registry/get-session
                               (fn [_ _] {:status :found :session session-record})]
                   (#'app-system/material-injection
                    service (atom {}) "s1" request))
        messages (get-in injected [:snapshot :messages])]
    (is (= :system (:role (first messages))))
    (is (re-find #"Explain compiler" (:text (first messages))))
    (is (= "hello" (:text (second messages))))))

(deftest material-injection-skips-non-user-turn
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        request {:call-id "call-nudge"
                 :snapshot {:nudge-id "n1"
                            :messages
                            [{:role :user
                              :text "hello"
                              :turn-card-ids ["missing-card"]}
                             {:role :system :text "nudge"}]}}
        result (with-redefs [registry/get-session
                             (fn [_ _] {:status :found :session session-record})]
                 (#'app-system/material-injection
                  service (atom {}) "s1" request))]
    (is (= request result))))


(deftest material-injection-skips-tool-last-continuation
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        _ (command! service session-record
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain the card."})
        request {:call-id "call-marker"
                 :snapshot {:user-turn? false
                            :turn-id "send-1"
                            :messages
                            [{:role :user
                              :text "hello"
                              :turn-card-ids ["card1"]}
                             {:role :tool :text "tool result"}]}}
        result (with-redefs [registry/get-session
                               (fn [_ _] {:status :found :session session-record})]
                   (#'app-system/material-injection
                  service (atom {}) "s1" request))]
    (is (= request result))))

(deftest material-injection-tool-last-user-turn-marker-injects
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        _ (command! service session-record
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain the card."})
        request {:call-id "call-tool-last-user"
                 :snapshot {:user-turn? true
                            :turn-id "send-tool-last-user"
                            :turn-command-id "send-tool-last-user"
                            :messages
                            [{:role :user
                              :text "hello"
                              :turn-card-ids ["card1"]}
                             {:role :tool :text "tool result"}]}}
        injected (with-redefs [registry/get-session
                               (fn [_ _] {:status :found :session session-record})]
                   (#'app-system/material-injection
                    service (atom {}) "s1" request))]
    (is (re-find #"Explain the card"
                 (:text (first (get-in injected [:snapshot :messages])))))
    (is (= 1 (get-in (session/snapshot service session-record)
                     [:worldbook-session :turn])))))

(deftest material-injection-uses-owner-request-identity
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        _ (command! service session-record
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain the card."})
        request {:call-id "call-owner-identity"
                 :user-turn? true
                 :turn-command-id "cmd-owner-1"
                 :turn-card-ids ["card1"]
                 :snapshot {:turn-id "cmd-owner-1"
                            :messages
                            [{:role :user
                              :text "hello"
                              :turn-card-ids ["card1"]}
                             {:role :tool :text "tool result"}]}}
        injected (with-redefs [registry/get-session
                               (fn [_ _] {:status :found :session session-record})]
                   (#'app-system/material-injection
                    service (atom {}) "s1" request))]
    (is (re-find #"Explain the card"
                 (:text (first (get-in injected [:snapshot :messages])))))
    (is (= 1 (get-in (session/snapshot service session-record)
                     [:worldbook-session :turn])))))

(deftest material-injection-skips-lone-turn-id
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        _ (command! service session-record
                    {:kind :materials/settings/update :memory-read? true})
        _ (command! service session-record
                    {:kind :materials/memory/remember
                     :body "MUST-NOT-LEAK-LONE-ID"
                     :category :identity-background
                     :scope :global})
        _ (command! service session-record
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain the card."})
        request {:call-id "call-lone-id"
                 :snapshot {:user-turn? true
                            :turn-id "turn-1"
                            :messages
                            [{:role :user
                              :text "hello"
                              :turn-card-ids ["card1"]}
                             {:role :tool :text "tool result"}]}}
        result (with-redefs [registry/get-session
                             (fn [_ _] {:status :found :session session-record})]
                 (#'app-system/material-injection
                  service (atom {}) "s1" request))
        snapshot (session/snapshot service session-record)]
    (is (= request result))
    (is (nil? (get-in snapshot [:worldbook-session :turn])))
    (is (not-any? #(re-find #"MUST-NOT-LEAK-LONE-ID" (str (:text %)))
                  (get-in result [:snapshot :messages])))))

(deftest material-injection-skips-unmarked-tool-last-continuation
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        request {:call-id "call-ordinary-continuation"
                 :snapshot {:messages
                            [{:role :user :text "hello"}
                             {:role :assistant
                              :tool-calls [{:id "call-1" :name "cat"}]}
                             {:role :tool :tool-call-id "call-1"
                              :text "file body"}]}}
        result (with-redefs [registry/get-session
                             (fn [_ _] {:status :found :session session-record})]
                 (#'app-system/material-injection
                  service (atom {}) "s1" request))]
    (is (= request result))
    (is (nil? (get-in (session/snapshot service session-record)
                      [:worldbook-session :turn])))))

(deftest material-injection-skips-regenerate-last-user
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        request {:call-id "call-regenerate"
                 :snapshot {:user-turn? false
                            :regenerate? true
                            :turn-id "regen-1"
                            :messages [{:role :user :text "hello"}]}}
        result (with-redefs [registry/get-session
                             (fn [_ _] {:status :found :session session-record})]
                 (#'app-system/material-injection
                  service (atom {}) "s1" request))]
    (is (= request result))))

(deftest material-injection-marker-with-last-user-injects
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        _ (command! service session-record
                    {:kind :materials/instruction/create
                     :id "card1" :name "Explain" :body "Explain the card."})
        request {:call-id "call-marker-user"
                 :snapshot {:user-turn? true
                            :turn-id "send-1"
                            :messages
                            [{:role :user
                              :text "hello"
                              :turn-card-ids ["card1"]}]}}
        injected (with-redefs [registry/get-session
                               (fn [_ _] {:status :found :session session-record})]
                   (#'app-system/material-injection
                    service (atom {}) "s1" request))]
    (is (re-find #"Explain the card"
                 (:text (first (get-in injected [:snapshot :messages])))))
    (is (= "hello"
           (:text (last (get-in injected [:snapshot :messages])))))))

(deftest tool-last-continuation-does-not-inject-memory-or-advance-worldbook
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        _ (command! service session-record
                    {:kind :materials/settings/update :memory-read? true})
        _ (command! service session-record
                    {:kind :materials/memory/remember
                     :body "MUST-NOT-LEAK-MEMORY"
                     :category :identity-background
                     :scope :global})
        request {:call-id "call-tool-memory"
                 :snapshot {:user-turn? false
                            :turn-id "send-tool"
                            :messages
                            [{:role :user :text "hello"}
                             {:role :assistant
                              :tool-calls [{:id "call-1" :name "cat"}]}
                             {:role :tool :tool-call-id "call-1"
                              :text "file body"}]}}
        result (with-redefs [registry/get-session
                              (fn [_ _] {:status :found :session session-record})]
                  (#'app-system/material-injection
                    service (atom {}) "s1" request))
        snapshot (session/snapshot service session-record)]
    (is (= request result))
    (is (nil? (get-in snapshot [:worldbook-session :turn])))
    (is (not-any? #(re-find #"MUST-NOT-LEAK-MEMORY" (str (:text %)))
                  (get-in result [:snapshot :messages])))))

(deftest material-injection-preserves-user-attachments
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        session-record {:session-id "s1" :assistant-id "a1"}
        attachment {:attachment-id "att-1"
                    :blob-id (str "sha256-" (apply str (repeat 64 "a")))
                    :name "note.txt"
                    :size 4
                    :mime "text/plain"}
        request {:call-id "call-attachment"
                 :snapshot {:messages
                            [{:role :user
                              :text "read this"
                              :attachments [attachment]
                              :turn-card-ids []}]}}
        injected (with-redefs [registry/get-session
                               (fn [_ _] {:status :found :session session-record})]
                   (#'app-system/material-injection
                    service (atom {}) "s1" request))
        user-message (last (get-in injected [:snapshot :messages]))]
    (is (= [attachment] (:attachments user-message)))
    (is (= "read this" (:text user-message)))))
