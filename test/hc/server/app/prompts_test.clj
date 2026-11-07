(ns hc.server.app.prompts-test
  "Pure behavior for role prompts and program tool schemas."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.prompts :as prompts]
            [hc.server.program.capability :as capability]))

(defn- request
  ([role] (request role false))
  ([role holder?]
   {:snapshot {:program-role role
               :holder? holder?
               :messages [{:role :user :text "do the work"}]}}))

(defn- role-system-messages
  [request]
  (filter #(and (= :system (:role %))
                (str/starts-with? (:text %) "[[hc-role-prompt]]"))
          (get-in request [:snapshot :messages])))

(defn- one-line
  "Collapse whitespace so a prompt assertion checks its meaning, not where
  the source string happens to wrap."
  [text]
  (str/replace (or text "") #"\s+" " "))

(defn- prompt-includes?
  [text phrase]
  (str/includes? (one-line text) phrase))

(deftest plan-gets-methodology-prompt-and-tool
  (let [decorated (prompts/decorate-request (request :clj-plan))
        system (first (role-system-messages decorated))
        tools (get-in decorated [:snapshot :tools])
        text (:text system)
        tool (first tools)]
    (is (= 1 (count (role-system-messages decorated))))
    (is (prompt-includes? text "You are Plan"))
    (is (prompt-includes? text "S1 (Requirements Analysis)"))
    (is (prompt-includes? text "S2 (Implementation Skeleton)"))
    (is (prompt-includes? text "S3 (Parallel Development Methodology)"))
    (is (prompt-includes? text "待执行"))
    (is (prompt-includes? text "plans/<name>.md"))
    (is (prompt-includes? text ":WRITE_LOCKED_BEFORE_FIRST_REVIEW"))
    (is (prompt-includes? text ":NONEMPTY_FINDINGS_AT_PENDING"))
    (is (prompt-includes? text ":DRAFT_MISSING_OR_EMPTY"))
    (is (prompt-includes? text "(devops {:verbose-intent"))
    (is (prompt-includes? text "(review {:findings [...]})"))
    (is (prompt-includes? text ":SCOPE_UNINITIALIZED"))
    (is (prompt-includes? text "There is no shell"))
    (is (prompt-includes? text "`eval`"))
    (is (not (prompt-includes? text "You are Reviewer")))
    (is (not (prompt-includes? text "Swarm STM Protocol")))
    (is (= ["clj-plan"] (mapv :name tools)))
    (is (prompt-includes? (:description tool) "no shell"))
    (is (prompt-includes? (:description tool) "eval"))
    (is (prompt-includes? (:description tool) "devops"))
    (is (= ["source" "intent" "scope"]
           (get-in tool [:parameters :required])))))

(deftest plan-tool-keeps-the-direct-devops-tool
  (let [decorated (prompts/decorate-request
                   (assoc-in (request :clj-plan)
                             [:snapshot :tools]
                             [{:name "devops"
                               :description "direct devops"
                               :parameters {:type "object"}}]))
        tools (get-in decorated [:snapshot :tools])]
    (is (= ["devops" "clj-plan"] (mapv :name tools)))
    (is (= 1 (count (role-system-messages decorated))))))

(deftest tools-less-pure-chat-degrades-to-text-without-tools
  (let [request {:snapshot {:messages [{:role :user :text "hello"}]
                            :tools [{:name "devops"
                                     :description "direct devops"
                                     :parameters {:type "object"}}]
                            :tool-choice :auto}}
        decorated (prompts/decorate-request request
                                            {:tools-supported? false})]
    (is (= [{:role :user :text "hello"}]
           (get-in decorated [:snapshot :messages]))
        "pure chat gains no role prompt")
    (is (empty? (role-system-messages decorated)))
    (is (nil? (get-in decorated [:snapshot :tools])))
    (is (nil? (get-in decorated [:snapshot :tool-choice]))
        "a stale tool choice cannot survive a stripped tool surface")))

(deftest reviewer-gets-read-only-prompt-and-tool
  (let [decorated (prompts/decorate-request (request :clj-review))
        system (first (role-system-messages decorated))
        tools (get-in decorated [:snapshot :tools])]
    (is (= 1 (count (role-system-messages decorated))))
    (is (prompt-includes? (:text system) "You are Reviewer"))
    (is (prompt-includes? (:text system) "strictly read-only"))
    (is (= ["clj-review"] (mapv :name tools)))
    (is (prompt-includes? (:description (first tools)) "read-only"))
    (is (= ["source" "intent" "scope"]
           (get-in (first tools) [:parameters :required])))))

(deftest worker-gets-swarm-prompt-and-tool
  (let [decorated (prompts/decorate-request (request :clj-worker))
        system (first (role-system-messages decorated))
        tools (get-in decorated [:snapshot :tools])]
    (is (= 1 (count (role-system-messages decorated))))
    (is (prompt-includes? (:text system) "Swarm STM Protocol"))
    (is (prompt-includes? (:text system) "normal worker cannot run"))
    (is (prompt-includes? (:text system) ":TOKEN_REQUIRED"))
    (is (not (prompt-includes? (:text system) "You are the Swarm Holder")))
    (is (= ["clj-worker"] (mapv :name tools)))
    (is (prompt-includes? (:description (first tools))
                          "normal worker cannot run call"))
    (is (prompt-includes? (:description (first tools)) "abandon"))
    ;; The prompt must describe the same gate the runtime enforces; the
    ;; full program path is exercised in hc.server.program.runtime-test.
    (testing "the prompt's shell restriction matches the runtime gate"
      (is (= :TOKEN_REQUIRED
             (:status (capability/check-call :clj-worker false))))
      (is (nil? (capability/check-call :clj-worker true)))
      (is (= :TOKEN_REQUIRED
             (:status (capability/check-abandon :clj-worker false))))
      (is (nil? (capability/check-abandon :clj-worker true))))))

(deftest holder-gets-token-prompt-and-worker-tool
  (let [decorated (prompts/decorate-request (request :clj-worker true))
        system (first (role-system-messages decorated))
        tools (get-in decorated [:snapshot :tools])]
    (is (prompt-includes? (:text system) "You are the Swarm Holder"))
    (is (prompt-includes? (:text system) "take-or-die"))
    (is (prompt-includes? (:text system) "black-box managed shell"))
    (is (= ["clj-worker"] (mapv :name tools)))))

(deftest decoration-is-idempotent-and-preserves-existing-tools
  (let [request (assoc-in (request :clj-review)
                          [:snapshot :tools]
                          [{:name "browser"
                            :description "existing"
                            :parameters {:type "object"}}])
        once (prompts/decorate-request request)
        twice (prompts/decorate-request once)
        names (mapv :name (get-in twice [:snapshot :tools]))]
    (is (= 1 (count (role-system-messages twice))))
    (is (= ["browser" "clj-review"] names)
        "existing tools stay and the role tool is added once")
    (is (= 1 (count (filter #(= "clj-review" (:name %))
                            (get-in twice [:snapshot :tools]))))
        "re-decoration replaces the same role tool instead of duplicating it")))

(deftest requests-without-a-known-role-are-unchanged
  (let [request {:snapshot {:program-role :dedicated-worker
                            :messages [{:role :user :text "plan"}]}}]
    (is (= request (prompts/decorate-request request)))
    (is (empty? (role-system-messages (prompts/decorate-request request))))))

(deftest chat-turn-gets-no-role-prompt-or-tools
  (testing "an Owner-declared :chat? snapshot stays a plain text request"
    (let [request {:snapshot {:program-role :clj-plan
                              :chat? true
                              :tools [{:name "devops"
                                       :description "direct devops"
                                       :parameters {:type "object"}}]
                              :tool-choice :auto
                              :messages [{:role :user
                                          :text "what is the draft"}]}}
          decorated (prompts/decorate-request request)]
      (is (empty? (role-system-messages decorated))
          "no Plan program prompt on a chat turn")
      (is (nil? (get-in decorated [:snapshot :tools]))
          "the frozen tool surface is stripped")
      (is (nil? (get-in decorated [:snapshot :tool-choice]))))
    (testing "decoration is idempotent for a chat turn"
      (let [request {:snapshot {:program-role :clj-plan
                                :chat? true
                                :messages [{:role :user :text "hi"}]}}
            twice (prompts/decorate-request
                   (prompts/decorate-request request))]
        (is (empty? (role-system-messages twice)))))))
