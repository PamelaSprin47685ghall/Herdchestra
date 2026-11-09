(ns hc.server.app.prompts-test
  "Pure behavior for role prompts and program tool schemas."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission.model :as model]
            [hc.server.app.prompts :as prompts]
            [hc.server.program.capability :as capability]))

(defn- request
  ([role] (request role false))
  ([role holder?]
   {:snapshot {:program-role role
               :holder? holder?
               :messages [{:role :user :text "do the work"}]}}))

(defn- role-system-message?
  [message]
  (let [text (:text message)
        marker prompts/role-prompt-marker]
    (and (= :system (:role message))
         (string? text)
         (or (= text marker)
             (str/starts-with? text (str marker "\n"))
             (str/starts-with? text (str marker "\r\n"))))))

(defn- role-system-messages
  [request]
  (filter role-system-message? (get-in request [:snapshot :messages])))

(defn- all-system-messages
  [request]
  (filter #(= :system (:role %))
          (get-in request [:snapshot :messages])))

(defn- occurrences
  [text phrase]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote phrase))
                 (or text ""))))

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

(deftest common-carries-the-full-scope-contract
  (let [text (:text (first (role-system-messages
                            (prompts/decorate-request (request :clj-review)))))]
    (testing "the repo-aware and engineering principles are all present"
      (is (prompt-includes? text "Fei Xiaotong's plain precision"))
      (is (prompt-includes? text "Kunihiko Kodaira's quiet clarity"))
      (is (prompt-includes? text "ASD-STE100"))
      (is (prompt-includes? text "mise en place (in keeping with Gall's Law)"))
      (is (prompt-includes? text "Smalltalk and the Actor Model"))
      (is (prompt-includes? text "Chesterton's Fence"))
      (is (prompt-includes? text "poka-yoke"))
      (is (prompt-includes? text "Goodhart's Law"))
      (is (prompt-includes? text "Bayesian spirit"))
      (is (prompt-includes? text "three gates")))
    (testing "the full Scope contract lives only here"
      (is (prompt-includes? text "# Scope"))
      (is (prompt-includes? text ":new-scope-goal"))
      (is (prompt-includes? text ":new-scope-retention-budget"))
      (is (prompt-includes? text ":keep-last"))
      (is (prompt-includes? text ":remove-last"))
      (is (prompt-includes? text ":me-too"))
      (is (prompt-includes? text "at most one true annotation"))
      (is (prompt-includes? text
            "a turn with only `:me-too` defaults to `:keep-last` and spends one"))
      (is (prompt-includes? text "only `:reset-scope` is admitted"))
      (is (prompt-includes? text "Exempt tools: `negotiate` and `askPreviousScope`"))
      (is (prompt-includes? text
            "`assume` is retained across resets but is **not** budget-exempt"))
      (is (prompt-includes? text
            "a `:keep-last` on `assume` still spends one"))
      (is (prompt-includes? text "Before the newest settled `:reset-scope` boundary"))
      (is (prompt-includes? text ":SCOPE_UNINITIALIZED"))
      (is (prompt-includes? text ":SCOPE_ANNOTATION_MISSING"))
      (is (prompt-includes? text ":SCOPE_KIND_INVALID"))
      (is (prompt-includes? text ":SCOPE_FIELD_INVALID"))
      (is (prompt-includes? text ":SCOPE_FIELD_UNEXPECTED"))
      (is (prompt-includes? text ":SCOPE_BUDGET_SPENT"))
      (is (prompt-includes? text ":SCOPE_RECORD_FAILED"))
      (is (prompt-includes? text ":SCOPE_TURN_MISSING")))))

(deftest plan-keeps-only-the-compact-scope-form
  (let [text (:text (first (role-system-messages
                            (prompts/decorate-request (request :clj-plan)))))]
    (is (prompt-includes? text "# Scope"))
    (is (prompt-includes? text "Every tool call carries flat `:scope`"))
    (is (prompt-includes? text
          "an all-`:me-too` turn defaults to `:keep-last`"))
    (is (prompt-includes? text ":SCOPE_UNINITIALIZED"))
    (is (not (prompt-includes? text "Exempt tools:"))
        "the long-form Scope contract is not repeated in PLAN_PROMPT")
    (is (not (prompt-includes? text ":SCOPE_RECORD_FAILED")))
    (is (not (prompt-includes? text "Chesterton's Fence"))
        "Plan does not receive COMMON")))

(deftest scope-schema-points-at-the-system-scope-section
  (let [tool (first (get-in (prompts/decorate-request (request :clj-plan))
                            [:snapshot :tools]))
        scope (get-in tool [:parameters :properties :scope])]
    (is (= "Flat Scope annotation for this turn; see # Scope in the system prompt."
           (:description scope)))
    (is (= ["kind"] (:required scope)))
    (is (= ["reset-scope" "keep-last" "remove-last" "me-too"]
           (get-in scope [:properties :kind :enum])))
    (is (= 1 (get-in scope [:properties :new-scope-retention-budget :minimum])))
    (is (contains? (:properties scope) :new-scope-goal))
    (is (contains? (:properties scope) :replacement))))

(deftest reviewer-and-worker-know-the-scope-primitives
  (doseq [role [:clj-review :clj-worker]]
    (let [text (:text (first (role-system-messages
                              (prompts/decorate-request (request role)))))]
      (is (prompt-includes? text "(askPreviousScope {:questions \"...\"})")
          (str role " knows askPreviousScope"))
      (is (prompt-includes? text "spends no retention"))
      (is (prompt-includes? text "(assume {:assumption \"...\"})"))
      (is (prompt-includes? text "a `:keep-last` on `assume` still spends one point"))
      (is (prompt-includes? text "(negotiate {})"))
      (is (prompt-includes? text "(abandon {:reason \"...\"})"))
      (is (prompt-includes? text "holder-only")))))

(deftest steward-gets-stewardship-prompt-and-tool
  (doseq [role [:steward :clj-steward]]
    (let [decorated (prompts/decorate-request (request role))
          system (first (role-system-messages decorated))
          text (:text system)
          tools (get-in decorated [:snapshot :tools])
          tool (first tools)]
      (is (= 1 (count (role-system-messages decorated))))
      (is (prompt-includes? text "<Stewardship>"))
      (is (prompt-includes? text "You hold the Docket"))
      (is (prompt-includes? text ":INSUFFICIENT_WORKTREES"))
      (is (prompt-includes? text "Join is a queue, not a race"))
      (is (prompt-includes? text "(fleet-state {})"))
      (is (prompt-includes? text "Chesterton's Fence")
          "the Steward shares COMMON")
      (is (= ["clj-steward"] (mapv :name tools)))
      (is (prompt-includes? (:description tool) "fork"))
      (is (prompt-includes? (:description tool) "fleet-state"))
      (is (= ["source" "intent" "scope"]
             (get-in tool [:parameters :required]))))))

(deftest meditator-gets-meditation-prompt-without-tool
  ;; The earlier contract attached a `clj-meditator` schema.  The Meditator
  ;; publishes text and has no runtime primitive beyond read-only inspection,
  ;; so a schema would only invite calls that end in `:CAPABILITY_DENIED`.
  ;; The prompt stays; the tool surface does not.
  (doseq [role [:meditator :clj-meditator]]
    (let [decorated (prompts/decorate-request (request role))
          system (first (role-system-messages decorated))
          text (:text system)
          tools (get-in decorated [:snapshot :tools])]
      (is (= 1 (count (role-system-messages decorated))))
      (is (prompt-includes? text "<Meditation>"))
      (is (prompt-includes? text "You are the Standing Sphinx"))
      (is (prompt-includes? text "How can this be better?"))
      (is (prompt-includes? text "Pull-only"))
      (is (prompt-includes? text "Current Best Answer"))
      (is (prompt-includes? text "Chesterton's Fence")
          "the Meditator shares COMMON")
      (is (nil? tools)
          "a text-only Meditator must not receive a program schema"))))

(deftest role-prompt-context-covers-the-stewardship-roles
  (is (pos? (:prompt-bytes (prompts/role-prompt-context :clj-steward))))
  (is (pos? (:prompt-bytes (prompts/role-prompt-context :clj-meditator))))
  (is (= ["program-role"]
         (:modules (prompts/role-prompt-context :clj-steward))))
  (is (= :unknown (:prompt-bytes (prompts/role-prompt-context :dedicated-worker)))))

(deftest tool-required-classification-matches-decoration
  (testing "the model ownership table is the single classification"
    (is (true? (model/role-requires-tools? :steward)))
    (is (false? (model/role-requires-tools? :meditator)))
    (is (true? (model/program-role-requires-tools? :clj-steward)))
    (is (false? (model/program-role-requires-tools? :clj-meditator))))
  (testing "a Steward decoration carries the tool when the model supports it"
    (let [decorated (prompts/decorate-request (request :clj-steward))]
      (is (= ["clj-steward"]
             (mapv :name (get-in decorated [:snapshot :tools]))))))
  (testing "a tools-less Steward request degrades to text with its prompt"
    (let [decorated (prompts/decorate-request (request :clj-steward)
                                              {:tools-supported? false})]
      (is (nil? (get-in decorated [:snapshot :tools])))
      (is (prompt-includes? (:text (first (role-system-messages decorated)))
                            "<Stewardship>"))))
  (testing "the Meditator keeps its prompt but gets no program schema"
    (let [decorated (prompts/decorate-request (request :clj-meditator))]
      (is (nil? (get-in decorated [:snapshot :tools])))
      (is (prompt-includes? (:text (first (role-system-messages decorated)))
                            "<Meditation>")))))

(deftest role-prompt-decoration-is-idempotent-per-role
  (doseq [[role phrase] [[:clj-steward "<Stewardship>"]
                         [:clj-meditator "<Meditation>"]
                         [:clj-plan "You are Plan"]
                         [:clj-review "You are Reviewer"]
                         [:clj-worker "You are a Swarm worker"]]]
    (testing (str role)
      (let [once (prompts/decorate-request (request role))
            twice (prompts/decorate-request once)
            systems-once (role-system-messages once)
            systems-twice (role-system-messages twice)
            text-once (apply str (map :text systems-once))
            text-twice (apply str (map :text systems-twice))]
        (is (= 1 (count systems-once))
            (str role " must carry exactly one role system message"))
        (is (= 1 (count systems-twice)))
        (is (= 1 (occurrences text-once phrase))
            (str role " prompt must appear exactly once"))
        (is (= 1 (occurrences text-twice phrase))
            (str role " prompt must stay exactly once after re-decoration"))
        (is (= (get-in once [:snapshot :messages])
               (get-in twice [:snapshot :messages]))
            "re-decoration must not change system message count or content")))))

(deftest marked-role-prompt-skips-prompt-but-keeps-tool
  (testing "preferred path: a marked system message already carries the role"
    (let [seed {:snapshot {:program-role :clj-steward
                           :messages [{:role :system
                                       :text (str prompts/role-prompt-marker
                                                  "\n"
                                                  prompts/steward-prompt)}
                                      {:role :user :text "go"}]}}
          decorated (prompts/decorate-request seed)
          systems (role-system-messages decorated)
          text (apply str (map :text systems))]
      (is (= 1 (count systems)))
      (is (= 1 (occurrences text "<Stewardship>")))
      (is (= ["clj-steward"]
             (mapv :name (get-in decorated [:snapshot :tools])))
          "tool injection continues when the prompt is already present")))
  (testing "fallback path: an unmarked system message carries the role text"
    (let [seed {:snapshot {:program-role :clj-steward
                           :messages [{:role :system
                                       :text prompts/steward-prompt}
                                      {:role :user :text "go"}]}}
          decorated (prompts/decorate-request seed)]
      (is (= 1 (count (all-system-messages decorated)))
          "the fallback fingerprint suppresses a duplicate prompt")
      (is (= ["clj-steward"]
             (mapv :name (get-in decorated [:snapshot :tools])))))))

(deftest different-role-prompt-does-not-suppress-injection
  (let [seed {:snapshot {:program-role :clj-steward
                         :messages [{:role :system
                                     :text (str prompts/role-prompt-marker
                                                "\n"
                                                prompts/meditator-prompt)}
                                    {:role :user :text "go"}]}}
        decorated (prompts/decorate-request seed)
        systems (role-system-messages decorated)
        text (apply str (map :text systems))]
    (is (= 2 (count systems))
        "the meditator marker must not hide the steward prompt")
    (is (= 1 (occurrences text "<Meditation>")))
    (is (= 1 (occurrences text "<Stewardship>")))
    (is (= ["clj-steward"]
           (mapv :name (get-in decorated [:snapshot :tools]))))))

(deftest missing-program-role-neither-injects-nor-crashes
  (let [request {:snapshot {:messages [{:role :user :text "hi"}]
                            :tools [{:name "browser"
                                     :description "existing"
                                     :parameters {:type "object"}}]}}
        decorated (prompts/decorate-request request)]
    (is (= request decorated)
        "the raw-model-port path returns the request unchanged")
    (is (empty? (all-system-messages decorated))
        "no role prompt is invented without :program-role")
    (is (= ["browser"]
           (mapv :name (get-in decorated [:snapshot :tools])))
        "existing tools are untouched")))

(deftest role-prompt-marker-is-a-public-single-source
  (is (string? prompts/role-prompt-marker))
  (is (not (str/blank? prompts/role-prompt-marker)))
  (is (= "[[hc-role-prompt]]" prompts/role-prompt-marker)
      "the wire token stays stable while writers reference the constant"))

(deftest marker-must-match-exactly
  (let [seed {:snapshot {:program-role :clj-steward
                         :messages [{:role :system
                                     :text (str prompts/role-prompt-marker
                                                "X\nCUSTOM ROLE PROMPT")}
                                    {:role :user :text "go"}]}}
        decorated (prompts/decorate-request seed)
        systems (all-system-messages decorated)
        text (apply str (map :text systems))]
    (is (= 2 (count systems))
        "a marker with one extra character must not suppress injection")
    (is (= 1 (occurrences text "<Stewardship>"))
        "the Steward prompt is still injected")
    (is (= ["clj-steward"]
           (mapv :name (get-in decorated [:snapshot :tools]))))))
