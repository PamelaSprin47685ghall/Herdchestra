(ns hc.core.pair-hint-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.pair-hint :as ph]
            [hc.core.pair-hint.canon :as canon]
            [hc.core.pair-hint.observation :as observation]
            [hc.core.pair-hint.prefix :as prefix]
            [hc.core.pair-hint.resources :as resources]))

;; ---------------------------------------------------------------------------
;; Generators

(def gen-text
  (gen/such-that #(not (str/blank? %)) gen/string-alphanumeric 50))

(def gen-role
  (gen/elements [:user :assistant :tool :system]))

(def gen-tool-args
  (gen/map gen/keyword gen/any-printable))

(def gen-block
  (gen/one-of
   [(gen/fmap (fn [text] {:type :text :text text}) gen-text)
    (gen/fmap (fn [[id name args]]
                {:type :tool-call
                 :tool-call-id id
                 :tool-name name
                 :arguments args})
              (gen/tuple gen-text gen-text gen-tool-args))
    (gen/fmap (fn [[id text]]
                {:type :tool-result
                 :tool-call-id id
                 :tool-name "read"
                 :content [{:type :text :text text}]})
              (gen/tuple gen-text gen-text))
    (gen/fmap (fn [text] {:type :thinking :text text}) gen-text)]))

(def gen-message
  (gen/fmap (fn [[role blocks]]
              {:role role :content blocks})
            (gen/tuple gen-role (gen/vector gen-block 0 3))))

(defn- test-render
  "A deterministic injected renderer for behavioral tests.  The real
  composition layer injects `hc.core.ufss/write-str`; keeping that dependency
  out of this namespace lets pair_hint tests run while a sibling core module is
  being edited."
  [value]
  (pr-str value))

(def render-opts
  {:render test-render})

;; ---------------------------------------------------------------------------
;; Canonical identity

(defspec canonical-map-order-is-irrelevant 60
  (prop/for-all [m (gen/map gen/keyword (gen/one-of [gen/string gen/small-integer gen/boolean]))]
    (= (canon/canonical-string m)
       (canon/canonical-string (into (sorted-map) m)))))

(defspec canonical-set-order-is-irrelevant 60
  (prop/for-all [s (gen/set gen/small-integer)]
    (= (canon/canonical-string s)
       (canon/canonical-string (into (sorted-set) s)))))

(defspec digest-is-insertion-order-independent 60
  (prop/for-all [m (gen/map gen/keyword gen/string)]
    (= (canon/digest m)
       (canon/digest (into (array-map) (reverse (seq m)))))))

(deftest canonical-rejects-unknown-values
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"no deterministic canonical form"
                        (canon/canonical-string (Object.)))))

;; ---------------------------------------------------------------------------
;; Semantic whitelist and prefix law

(defspec envelope-noise-does-not-move-the-chain 60
  (prop/for-all [text gen-text
                 usage (gen/map gen/keyword gen/any-printable)
                 timestamp gen/small-integer
                 index gen/small-integer]
    (let [message {:role :assistant
                   :content [{:type :text :text text}]}
          noisy (assoc message
                       :usage usage
                       :timestamp timestamp
                       :index index
                       :streaming {:partial true}
                       :provider-metadata {:cache false})]
      (= (prefix/message-digest message)
         (prefix/message-digest noisy)))))

(defspec text-change-changes-the-message-digest 60
  (prop/for-all [[a b] (gen/such-that (fn [[a b]] (not= a b))
                                      (gen/tuple gen-text gen-text))]
    (not= (prefix/message-digest {:role :user :content [{:type :text :text a}]})
          (prefix/message-digest {:role :user :content [{:type :text :text b}]}))))

(defspec prefix-extension-always-holds 80
  (prop/for-all [messages (gen/vector gen-message 0 8)
                 suffix (gen/vector gen-message 0 8)]
    (let [prior (prefix/chain-of messages)
          extended (into (vec messages) suffix)]
      (true? (:ok? (prefix/verify-prefix prior extended))))))

(deftest prefix-detects-drift-truncation-and-boundaries
  (let [messages [{:role :user :content [{:type :text :text "a"}]}
                  {:role :assistant :content [{:type :text :text "b"}]}]
        prior (prefix/chain-of messages)]
    (testing "exact extension"
      (let [result (prefix/verify-prefix
                    prior
                    (conj messages {:role :user :content [{:type :text :text "c"}]}))]
        (is (true? (:ok? result)))
        (is (= 3 (get-in result [:chain :count])))))
    (testing "changed earlier message"
      (let [changed (assoc-in messages [0 :content 0 :text] "changed")
            result (prefix/verify-prefix prior changed)]
        (is (false? (:ok? result)))
        (is (= :PREFIX_DRIFT (get-in result [:error :code])))
        (is (= 0 (get-in result [:error :index])))))
    (testing "truncated request"
      (let [result (prefix/verify-prefix prior [(first messages)])]
        (is (false? (:ok? result)))
        (is (= :PREFIX_TRUNCATED (get-in result [:error :code])))))
    (testing "boundary changes the root"
      (is (not= (:root (prefix/chain-of messages))
                (:root (prefix/chain-of messages {:kind :relay :id "r1"})))))
    (testing "a relay or compaction boundary needs a durable id"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"needs a durable id"
                            (prefix/chain-of messages {:kind :relay})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"needs a durable id"
                            (prefix/chain-of messages {:kind :compaction}))))
    (testing "a raw Scope fact is preserved in the chain boundary"
      (let [raw {:kind :reset-scope :call-id "reset-1"
                 :new-scope-goal "goal"}
            chain (prefix/chain-of messages
                                   {:kind :scope-reset :call-id "reset-1"
                                    :raw raw})]
        (is (= raw (get-in chain [:boundary :raw])))
        (is (= "reset-1" (get-in chain [:boundary :call-id])))))
    (testing "tampered chain is rejected"
      (let [tampered (assoc prior :root "deadbeef")
            result (prefix/verify-prefix tampered messages)]
        (is (false? (:ok? result)))
        (is (= :PAIR_HINT_CHAIN_INVALID (get-in result [:error :code])))))
    (testing "an internally consistent but invalid prior boundary fails closed"
      (let [bad-boundary {:kind :made-up}
            bad-root (canon/digest {:boundary bad-boundary
                                    :digests (:digests prior)})
            bad-prior (assoc prior :boundary bad-boundary :root bad-root)
            result (prefix/verify-prefix bad-prior messages)]
        (is (false? (:ok? result)))
        (is (= :PAIR_HINT_CHAIN_INVALID (get-in result [:error :code])))))
    (testing "unsupported boundary"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"boundary kind is not allowed"
                            (prefix/chain-of messages {:kind :made-up}))))))

(deftest top-level-tool-identity-is-semantic
  (is (not= (prefix/message-digest
             {:role :tool :tool-call-id "a" :content "ok"})
            (prefix/message-digest
             {:role :tool :tool-call-id "b" :content "ok"}))))

(deftest unsupported-content-type-fails-closed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported content block type"
                        (prefix/message-digest
                         {:role :user :content [{:type :video :data "x"}]}))))

(deftest flat-user-envelope-does-not-move-the-prefix
  (let [prompt {:role :user
                :message-id "user-c1"
                :text "first"
                :turn-card-ids ["card-1"]
                :instruction-values {"tone" "terse"}
                :draft {:draft-id "d1" :text "first"}
                :quotes [{:message-id "user-a" :text "quoted body"
                          :start 0 :end 11}]
                :citations [{:citation-id "c-1" :url "https://a.example"
                             :title "A" :index 0}]
                :attachments [{:attachment-id "att-1"
                               :sha256 "digest-1"
                               :media "image/png"
                               :name "one.png"
                               :status :ready}]}
        digest #(prefix/message-digest %)]
    (testing "turn bookkeeping and transport ids are not message identity"
      (is (= (digest prompt)
             (digest (assoc prompt
                            :turn-card-ids []
                            :instruction-values {}
                            :draft nil))
             (digest (assoc prompt :turn-command-id "cmd-1" :at 42
                            :streaming? false))
             (digest (update prompt :quotes assoc-in [0 :start] 5))
             (digest (update-in prompt [:citations 0]
                                assoc :citation-id "c-2" :index 3)))))
    (testing "what the provider renders still moves identity"
      (is (not= (digest prompt) (digest (assoc prompt :text "second"))))
      (is (not= (digest prompt)
                (digest (assoc-in prompt [:quotes 0 :text] "other body"))))
      (is (not= (digest prompt)
                (digest (assoc-in prompt [:citations 0 :text] "A shown")))
          "a newly rendered citation text is identity")
      ;; `context-text` renders `:text`, else `:label`, else `:url`; the
      ;; fixture carries only `:url` (`:title` is not rendered), so `:url`
      ;; is the winning field and moving it must move identity.
      (is (not= (digest prompt)
                (digest (assoc-in prompt [:citations 0 :url]
                                  "https://b.example"))))
      (testing "a shadowing field wins over :url"
        (let [labelled (assoc-in prompt [:citations 0 :label] "A")]
          (is (not= (digest prompt) (digest labelled)))
          (is (= (digest labelled)
                 (digest (assoc-in labelled [:citations 0 :url]
                                   "https://b.example")))))))
    (testing "attachment id, digest, and media are exact identity"
      (is (= (digest prompt)
             (digest (update-in prompt [:attachments 0]
                                assoc :name "renamed.png" :status :sent
                                :url "/blobs/att-1")))
          "display and transport fields are not attachment identity")
      (is (not= (digest prompt)
                (digest (assoc-in prompt [:attachments 0 :attachment-id]
                                  "att-2")))
          "a replaced attachment id is a real identity change")
      (is (not= (digest prompt)
                (digest (assoc-in prompt [:attachments 0 :sha256]
                                  "digest-2")))
          "re-uploaded bytes under the same id are a real identity change")
      (is (not= (digest prompt)
                (digest (assoc-in prompt [:attachments 0 :media]
                                  "image/jpeg")))))
    (testing "an attachment with neither id nor digest fails closed"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"attachment needs an id or a digest"
                            (digest (assoc prompt :attachments
                                           [{:name "unknown.png"}])))))))

(declare en-session zh-session)

(defn- observed-request
  "One observed outbound request in the flat app shape."
  [& messages]
  {:attempt-id "attempt-1"
   :call-id "call-1"
   :snapshot {:role :main
              :messages (vec messages)}})

(defn- en-session []
  (ph/create-session {:explicit-language :en}))

(defn- zh-session []
  (ph/create-session {:explicit-language :zh-CN}))

;; ---------------------------------------------------------------------------
;; Language resources and resolution

(deftest bundled-resources-are-paired
  (let [pair (ph/load-resources)
        expected #{"context-window" "turns-used" "turns-remaining"
                   "wall-clock" "parked-handles" "board-observation"
                   "fleet-summary"}]
    (is (= expected (set (ph/placeholders (:en pair)))))
    (is (= expected (set (ph/placeholders (:zh-CN pair)))))
    (is (str/starts-with? (:zh-CN pair) "思考过程与全部对外输出，一律用简体中文。"))))

(deftest resource-pairing-fails-closed
  (testing "missing half"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"both language resources are required"
                          (resources/load-pair {:resources {:en "hello"}}))))
  (testing "placeholder mismatch"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"do not pair"
                          (resources/load-pair
                           {:resources {:en "{{a}}" :zh-CN "{{b}}"} }))))
  (testing "a complete pair without the fixed zh-CN sentence is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must start with the fixed sentence"
                          (resources/load-pair
                           {:resources {:en "hello" :zh-CN "你好"}})))
    (is (false? (resources/resource-pair?
                 {:en "hello" :zh-CN "你好"}))))
  (testing "missing template value"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"template value missing"
                          (resources/render "{{a}} {{b}}" {"a" "x"}))))
  (testing "binding validates the pair"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"both language resources are required"
                          (ph/bind-language-session
                           {:explicit-language :en
                            :resources {:en "hello"}}))))
  (testing "resume validates persisted resources"
    (let [session (ph/create-session {:explicit-language :en})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"both language resources are required"
                            (ph/resume-session
                             (assoc session :resources {:en "hello"})
                             {}))))))

(deftest language-resolution-ladder
  (testing "explicit beats env, config, and locale"
    (is (= :zh-CN
           (:language (ph/resolve-language
                       {:explicit-language :zh-CN
                        :env {"HERDCHESTRA_LANGUAGE" "en"}
                        :config {:language :en}
                        :locale "en_US.UTF-8"})))))
  (testing "env beats config and locale"
    (is (= :en
           (:language (ph/resolve-language
                       {:env {"HERDCHESTRA_LANGUAGE" "en"}
                        :config {:language :zh-CN}
                        :locale "zh_CN.UTF-8"})))))
  (testing "config beats locale"
    (is (= :zh-CN
           (:language (ph/resolve-language
                       {:config {:language :zh-CN}
                        :locale "en_US.UTF-8"})))))
  (testing "IDE locale beats system locale"
    (is (= :en
           (:language (ph/resolve-language
                       {:ide-locale "en-US" :locale "zh_CN.UTF-8"})))))
  (testing "locale matching"
    (is (= :zh-CN (:language (ph/resolve-language {:locale "zh_CN.UTF-8"}))))
    (is (= :en (:language (ph/resolve-language {:locale "en_US.UTF-8"}))))
    (is (= :en (:language (ph/resolve-language {:locale "C"}))))
    (is (true? (:recorded? (ph/resolve-language {:locale "C"})))))
  (testing "invalid explicit values fail, never fall back"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"language value is not supported"
                          (ph/resolve-language {:env {"HERDCHESTRA_LANGUAGE" "fr"}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"language value is not supported"
                          (ph/resolve-language {:config {:language :zh}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"language value is not supported"
                          (ph/resolve-language {:env {"HERDCHESTRA_LANGUAGE" ""}})))))

(deftest language-binding-is-lifetime-bound
  (let [session (ph/create-session {:explicit-language :zh-CN})]
    (is (= :zh-CN (:language session)))
    (is (= :explicit (:source session)))
    (is (= :explicit (:language-source session)))
    (testing "resume ignores the environment"
      (is (= :zh-CN
             (:language (ph/resume-session
                         session
                         {:env {"HERDCHESTRA_LANGUAGE" "en"}})))))
    (testing "child inherits"
      (let [child (ph/child-session session :zh-CN)]
        (is (= :zh-CN (:language child)))
        (is (= :inherited (:source child)))
        (is (= :inherited (:language-source child))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must inherit"
                            (ph/child-session session :en))))
    (testing "invalid persisted language fails"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"language value is not supported"
                            (ph/resume-session (assoc session :language :fr) {})))))
  (testing "the default is recorded as a source"
    (is (= :default (:language-source (ph/create-session {}))))))

;; ---------------------------------------------------------------------------
;; Hint injection

(deftest hint-has-seven-sections-and-dynamic-facts
  (let [session (en-session)
        hint (ph/build-hint session
                            {:context-window 128000
                             :turns-used 3
                             :turns-remaining 17
                             :wall-clock-seconds 125
                             :parked-handles ["b" "a"]
                             :render test-render
                             :placement {:turn 1}})]
    (is (= :assistant (get-in hint [:call :role])))
    (is (= :tool (get-in hint [:result :role])))
    (is (= "" (get-in hint [:call :content 0 :tool-name])))
    (is (= "" (get-in hint [:result :tool-name])))
    (is (= (get-in hint [:call :content 0 :tool-call-id])
           (get-in hint [:result :tool-call-id])))
    (doseq [section ["Pair programming discipline"
                     "Token economy"
                     "Wait cost"
                     "Context limit"
                     "Budget calibration"
                     "Idle hint"
                     "Board observation"
                     "Fleet observation"]]
      (is (str/includes? (:text hint) section) section))
    (is (str/includes? (:text hint) "128000 tokens"))
    (is (str/includes? (:text hint) "2m 5s"))
    (is (str/includes? (:text hint) "[\"a\" \"b\"]"))
    (is (not (str/includes? (:text hint) "{{")))))

(deftest chinese-hint-starts-with-the-fixed-sentence
  (let [hint (ph/build-hint (zh-session) {:placement {:turn 1}})]
    (is (str/starts-with? (:text hint)
                          "思考过程与全部对外输出，一律用简体中文。"))
    (is (str/includes? (:text hint) "未知"))))

(deftest hint-placement-replays-without-duplication
  (let [session (en-session)
        first (ph/append-hint session
                              {:messages [{:role :user :content [{:type :text :text "hi"}]}]
                               :placement {:turn 1}})
        second (ph/append-hint (:state first)
                               {:messages (:rendered first)
                                :placement {:turn 2}})
        replay (ph/append-hint (:state second)
                               {:messages (:rendered first)
                                :placement {:turn 2}})]
    (is (true? (:ok? first)))
    (is (true? (:ok? second)))
    (is (true? (:replay? replay)))
    (is (= (:pair second) (:pair replay)))
    (is (= (:state second) (:state replay)))
    (is (= 5 (get-in second [:state :chain :count])))
    (is (= 2 (:hint-count (:state second))))))

(deftest placement-is-required-for-recorded-hints
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"placement is required"
                        (ph/append-hint (en-session) {:messages []})))
  (let [request (observed-request {:role :user :text "hi"})
        first (ph/project-request (en-session) request
                                  {:context {:context-window 1000}
                                   :render test-render})
        second (ph/project-request (:session first) request
                                   {:context {:context-window 1000}
                                    :render test-render})]
    (is (true? (:ok? first)))
    (is (true? (:ok? second)))
    (is (= 1 (:hint-count (:session second))))
    (is (= (:pair (:projection first)) (:pair (:projection second))))))

(deftest placement-history-keeps-every-pair-in-the-epoch
  (let [turns (range 10)
        {:keys [session last]}
        (reduce
         (fn [{:keys [session base]} i]
           (let [message {:message-id (str "m" i)
                          :role :user
                          :text (str "u" i)}
                 base (conj base message)
                 result (ph/project-request
                         session
                         (apply observed-request base)
                         {:placement {:turn i}
                          :context {:context-window 1000}
                          :render test-render})]
             (is (true? (:ok? result))
                 (str "turn " i " " (pr-str (:error result))))
             {:session (:session result) :base base :last result}))
         {:session (en-session) :base [] :last nil}
         turns)]
    (is (= 10 (:hint-count session)))
    (is (= 10 (count (:placements session))))
    (is (= 30 (get-in session [:chain :count])))
    (is (true? (:ok? (ph/verify-prefix
                      (get-in session [:chain])
                      (get-in last [:request :snapshot :messages])))))))

(deftest hint-drift-is-reported-not-absorbed
  (let [session (en-session)
        first (ph/append-hint session
                              {:messages [{:role :user :content [{:type :text :text "hi"}]}]
                               :placement {:turn 1}})
        changed (assoc-in (:rendered first) [0 :content 0 :text] "changed")
        result (ph/append-hint (:state first)
                               {:messages changed
                                :placement {:turn 2}})]
    (is (false? (:ok? result)))
    (is (= :PREFIX_DRIFT (get-in result [:error :code])))
    (is (= (:state first) (:state result)))))

(deftest ephemeral-turn-does-not-move-state
  (let [session (en-session)
        result (ph/append-hint session
                               {:messages [{:role :user :content [{:type :text :text "title"}]}]
                                :placement {:ephemeral 1}
                                :ephemeral? true})]
    (is (true? (:ok? result)))
    (is (true? (:ephemeral? result)))
    (is (= session (:state result)))
    (is (nil? (get-in result [:state :chain])))
    (is (= 0 (:hint-count (:state result))))))

(deftest record-request-and-reanchor
  (let [session (en-session)
        first (ph/record-request
               session
               [{:role :user :content [{:type :text :text "a"}]}]
               nil)
        second (ph/record-request
                (:state first)
                [{:role :user :content [{:type :text :text "a"}]}
                 {:role :assistant :content [{:type :text :text "b"}]}]
                nil)
        reanchored (ph/reanchor (:state second)
                                {:kind :scope-reset :id "scope-1"})
        third (ph/record-request reanchored [] nil)]
    (is (true? (:ok? first)))
    (is (true? (:ok? second)))
    (is (true? (:ok? third)))
    (is (= :scope-reset (get-in third [:state :chain :boundary :kind])))
    (is (= 1 (:epoch-id (:state third))))))

(deftest record-request-with-boundary-clears-placements
  (let [first (ph/append-hint
               (en-session)
               {:messages [{:role :user :content [{:type :text :text "a"}]}]
                :placement {:turn 1}})
        recorded (ph/record-request (:state first)
                                    (:rendered first)
                                    {:kind :relay :id "relay-recorded"})]
    (is (true? (:ok? recorded)))
    (is (= [] (get-in recorded [:state :placements])))
    (is (= :relay (get-in recorded [:state :chain :boundary :kind])))
    (is (= 1 (:epoch-id (:state recorded))))))

(deftest hint-can-be-inserted-at-a-durable-position
  (let [session (en-session)
        first-message {:role :user :content [{:type :text :text "a"}]}
        second-message {:role :assistant :content [{:type :text :text "b"}]}
        result (ph/append-hint session
                               {:messages [first-message second-message]
                                :placement {:gap "after-first"}
                                :insert-at 1
                                :boundary {:kind :relay :id "relay-position"}})]
    (is (true? (:ok? result)))
    (is (= 4 (count (:rendered result))))
    (is (= :assistant (get-in result [:rendered 1 :role])))
    (is (= second-message (get-in result [:rendered 3])))
    (is (= 4 (get-in result [:state :chain :count])))))

(deftest explicit-boundary-opens-a-new-epoch
  (let [session (en-session)
        first (ph/append-hint session
                              {:messages [{:role :user :content [{:type :text :text "old"}]}]
                               :placement {:turn 1}
                               :context-window 1000})
        relayed (ph/append-hint (:state first)
                                {:messages [{:role :user :content [{:type :text :text "new epoch"}]}]
                                 :placement {:turn 1}
                                 :boundary {:kind :relay :id "relay-1"}
                                 :context-window 2000})]
    (is (true? (:ok? relayed)))
    (is (= :relay (get-in relayed [:state :chain :boundary :kind])))
    (is (= 1 (:epoch-id (:state relayed))))
    (is (= 3 (get-in relayed [:state :chain :count])))
    (testing "old epoch placements do not replay after the boundary"
      (is (not= (:pair first) (:pair relayed)))
      (let [replay (ph/append-hint (:state relayed)
                                   {:messages (:rendered relayed)
                                    :placement {:turn 1}})]
        (is (true? (:replay? replay)))
        (is (= (:pair relayed) (:pair replay)))))))

;; ---------------------------------------------------------------------------
;; Board and fleet observation

(deftest board-delta-append-replay-and-stale
  (let [session (en-session)
        first (ph/apply-board-observation
               session
               {:kind :delta :board-version 1 :changes [{:op :add :id "t1"}]}
               render-opts)
        replay (ph/apply-board-observation
                (:session first)
                {:kind :delta :board-version 1 :changes [{:op :add :id "t1"}]}
                render-opts)
        stale (ph/apply-board-observation
               (:session first)
               {:kind :delta :board-version 0 :changes []}
               render-opts)
        divergent (ph/apply-board-observation
                   (:session first)
                   {:kind :delta :board-version 1 :changes [{:op :remove}]}
                   render-opts)]
    (is (= :append (:action first)))
    (is (= 1 (ph/observed-cursor (:session first))))
    (is (= :replay (:action replay)))
    (is (= (:session first) (:session replay)))
    (is (= :PAIR_HINT_BOARD_STALE (get-in stale [:error :code])))
    (is (= :PAIR_HINT_OBSERVATION_INVALID (get-in divergent [:error :code])))
    (is (= :same-version-different-changes
           (get-in divergent [:error :reason])))))

(deftest board-writer-clean-break-clears-deltas
  (let [session (en-session)
        delta (ph/apply-board-observation
               session
               {:kind :delta :board-version 1 :changes [{:op :add}]}
               render-opts)
        writer (ph/apply-board-observation
                (:session delta)
                {:kind :delta
                 :board-version 2
                 :changes []
                 :snapshot {:tasks [{:id "t1"}]}}
                (merge render-opts {:writer? true}))
        next-session (:session writer)]
    (is (= :clean-break (:action writer)))
    (is (= [] (get-in next-session [:board :deltas])))
    (is (= {:kind :board-clean-break
            :id "board-2"
            :board-version 2}
           (:pending-boundary next-session)))
    (testing "a late delta at the clean-break version is a replay"
      (let [replay (ph/apply-board-observation
                    next-session
                    {:kind :delta :board-version 2 :changes []}
                    render-opts)]
        (is (= :replay (:action replay)))
        (is (= next-session (:session replay)))))
    (testing "the same writer snapshot replays"
      (let [replay (ph/apply-board-observation
                    next-session
                    {:kind :snapshot
                     :board-version 2
                     :snapshot {:tasks [{:id "t1"}]}}
                    (merge render-opts {:writer? true}))]
        (is (= :replay (:action replay)))
        (is (= next-session (:session replay)))))
    (testing "the next hint starts the new prefix boundary"
      (let [hint (ph/append-hint next-session
                                 {:messages []
                                  :placement {:turn 1}})]
        (is (true? (:ok? hint)))
        (is (= :board-clean-break (get-in hint [:state :chain :boundary :kind])))
        (is (= 2 (get-in hint [:state :chain :boundary :board-version])))))))

(deftest reader-threshold-requests-a-snapshot
  (let [opts (merge render-opts {:thresholds {:count 1 :version-span 99}})
        session (en-session)
        first (ph/apply-board-observation
               session {:kind :delta :board-version 1 :changes []} opts)
        second (ph/apply-board-observation
                (:session first) {:kind :delta :board-version 2 :changes []} opts)
        clean (ph/apply-board-observation
               (:session second)
               {:kind :snapshot :board-version 2 :snapshot {:tasks []}}
               opts)]
    (is (= :append (:action first)))
    (is (= :append (:action second)))
    (is (true? (:needs-snapshot second)))
    (is (= :clean-break (:action clean)))
    (is (= [] (get-in (:session clean) [:board :deltas])))
    (is (= {:kind :board-clean-break
            :id "board-2"
            :board-version 2}
           (:pending-boundary (:session clean))))))

(deftest board-threshold-never-reuses-an-old-snapshot
  (let [opts (merge render-opts {:thresholds {:count 2 :version-span 99}})
        writer (ph/apply-board-observation
                (en-session)
                {:kind :delta
                 :board-version 1
                 :changes []
                 :snapshot {:board-version 1 :tasks []}}
                (merge opts {:writer? true}))
        after (reduce (fn [{:keys [session]} version]
                        (let [result (ph/apply-board-observation
                                      session
                                      {:kind :delta
                                       :board-version version
                                       :changes []}
                                      opts)]
                          {:session (:session result)
                           :result result}))
                      {:session (:session writer)}
                      (range 2 6))
        state (:session after)
        needs-snapshot (:result after)]
    (is (true? (:needs-snapshot needs-snapshot)))
    (is (= :append (:action needs-snapshot)))
    (is (= 1 (get-in needs-snapshot [:state :snapshot-version])))
    (is (= 5 (get-in needs-snapshot [:state :board-version])))
    (testing "a snapshot for the current version is accepted"
      (let [clean (ph/apply-board-observation
                   state
                   {:kind :delta :board-version 6 :changes []}
                   (assoc opts :snapshot {:board-version 6 :tasks []}))]
        (is (= :clean-break (:action clean)))
        (is (= 6 (get-in clean [:state :snapshot-version])))))
    (testing "a lower snapshot is stale"
      (let [stale (ph/apply-board-observation
                   state
                   {:kind :snapshot :board-version 4
                    :snapshot {:board-version 4 :tasks []}}
                   opts)]
        (is (= :PAIR_HINT_BOARD_STALE (get-in stale [:error :code])))))))

(deftest fleet-summary-is-bounded-and-merged
  (let [session (en-session)
        first (ph/apply-fleet-observation
               session
               {:kind :delta
                :fleet-version 1
                :swarms [{:swarm-id "a"
                          :slice-id "s1"
                          :status :running
                          :root-ready 1
                          :queued-count 2
                          :private-detail "drop"}]
                :events [{:type :result-queued
                          :swarm-id "a"
                          :private-detail "drop"}
                         {:type :status :swarm-id "a" :detail "drop"}]}
               render-opts)
        second (ph/apply-fleet-observation
                (:session first)
                {:kind :delta
                 :fleet-version 2
                 :swarms [{:swarm-id "a"
                           :slice-id "s1"
                           :status :joined
                           :root-ready 0
                           :queued-count 0}]}
                render-opts)
        state (:fleet (:session second))]
    (is (= :append (:action first)))
    (is (= 1 (count (:swarms state))))
    (is (= :joined (get-in state [:swarms 0 :status])))
    (is (nil? (get-in state [:swarms 0 :private-detail])))
    (is (= 2 (ph/fleet-cursor state)))
    (is (= [{:type :result-queued :swarm-id "a"}] (:events state)))
    (testing "the same version with different rows is rejected"
      (let [divergent (ph/apply-fleet-observation
                       (:session first)
                       {:kind :delta
                        :fleet-version 1
                        :swarms [{:swarm-id "a" :status :joined}]}
                       render-opts)]
        (is (= :PAIR_HINT_OBSERVATION_INVALID
               (get-in divergent [:error :code])))
        (is (= :same-version-different-content
               (get-in divergent [:error :reason])))))))

(deftest fleet-joined-report-is-kept-verbatim
  (let [report "line one\nline two \"quoted\"\n- [ ] item"
        result (ph/apply-fleet-observation
                (en-session)
                {:kind :delta
                 :fleet-version 1
                 :swarms [{:swarm-id "a"
                           :status :joined
                           :report report
                           :private-detail "drop"}
                          {:swarm-id "b"
                           :status :running
                           :report "not kept"}]}
                render-opts)
        rows (get-in result [:state :swarms])]
    (is (= :append (:action result)))
    (is (= report (get-in rows [0 :report])))
    (is (nil? (get-in rows [1 :report])))
    (is (= report (get-in result [:state :last-delta :swarms 0 :report])))
    (is (str/includes? (:text result) "line one"))))

(deftest fleet-delta-events-are-bounded
  (let [events (mapv (fn [i] {:type :result-queued
                              :swarm-id "a"
                              :at i})
                     (range 5))
        result (ph/apply-fleet-observation
                (en-session)
                {:kind :delta
                 :fleet-version 1
                 :swarms []
                 :events events}
                (merge render-opts {:max-events 2}))
        decoded (edn/read-string (:text result))]
    (is (= :append (:action result)))
    (is (= 2 (count (:events decoded))))
    (is (= [3 4] (mapv :at (:events decoded))))
    (is (= 2 (count (get-in result [:state :events]))))
    (is (= 2 (count (get-in result [:state :last-delta :events]))))))

(deftest fleet-clean-break-opens-a-boundary
  (let [session (en-session)
        opts (merge render-opts {:thresholds {:count 1 :version-span 99}})
        first (ph/apply-fleet-observation
               session {:kind :delta :fleet-version 1 :swarms []} opts)
        second (ph/apply-fleet-observation
                (:session first) {:kind :delta :fleet-version 2 :swarms []} opts)]
    (is (= :clean-break (:action second)))
    (is (= {:kind :board-clean-break :id "fleet-2" :board-version 2}
           (:boundary second)))
    (is (= 2 (get-in second [:state :fleet-version])))))

(deftest fleet-snapshot-replays
  (let [session (en-session)
        observation {:kind :snapshot
                     :fleet-version 1
                     :swarms [{:swarm-id "a" :status :running :queued-count 0}]
                     :events [{:type :result-queued :swarm-id "a"}]}
        first (ph/apply-fleet-observation session observation render-opts)
        replay (ph/apply-fleet-observation (:session first) observation render-opts)]
    (is (= :clean-break (:action first)))
    (is (= :replay (:action replay)))
    (is (= (:session first) (:session replay))))
  (testing "a lower fleet snapshot is stale"
    (let [session (-> (en-session)
                      (ph/apply-fleet-observation
                       {:kind :delta :fleet-version 2 :swarms []}
                       render-opts)
                      :session)
          stale (ph/apply-fleet-observation
                 session
                 {:kind :snapshot :fleet-version 1 :swarms []}
                 render-opts)]
      (is (= :PAIR_HINT_FLEET_STALE (get-in stale [:error :code]))))))

(deftest llm-facing-structured-data-requires-an-explicit-ufss-renderer
  (let [session (en-session)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"EDN\+UFSS renderer is required"
                          (ph/observe-board
                           observation/initial-board-state
                           {:kind :delta :board-version 1 :changes []}
                           {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"EDN\+UFSS renderer is required"
                          (ph/observe-fleet
                           observation/initial-fleet-state
                           {:kind :delta :fleet-version 1 :swarms []}
                           {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"EDN\+UFSS renderer is required"
                          (ph/build-hint
                           session
                           {:placement {:turn 1}
                            :parked-handles ["build"]})))))

(deftest injected-renderer-is-actually-used
  (let [session (en-session)
        calls (atom [])
        render (fn [value]
                 (swap! calls conj value)
                 (str "R:" (pr-str value)))
        board (ph/apply-board-observation
               session
               {:kind :delta :board-version 1 :changes [{:note "a"}]}
               {:render render})]
    (is (= 1 (count @calls)))
    (is (= (str "R:" (pr-str (first @calls))) (:text board))))
  (let [session (en-session)
        values (ph/hint-values
                session
                {:render (fn [value] (str "R:" (pr-str value)))
                 :parked-handles ["a"]})]
    (is (= "R:[\"a\"]" (get values "parked-handles"))))
  (let [session (en-session)
        values (ph/hint-values
                session
                {:render (fn [value] (str "R:" (pr-str value)))
                 :board-observation {:note "a"}})]
    (is (= "R:{:note \"a\"}" (get values "board-observation"))))
  (let [session (en-session)
        values (ph/hint-values
                session
                {:render (fn [value] (str "R:" (pr-str value)))
                 :context-window {:tokens 128000}})]
    (is (= "R:{:tokens 128000}" (get values "context-window"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"EDN\+UFSS renderer is required"
                        (ph/hint-values (en-session)
                                        {:context-window {:tokens 128000}})))
  (let [result (ph/project-request
                (en-session)
                (observed-request {:role :user :text "hi"})
                {:placement {:turn 1}
                 :context {:context-window 1000}
                 :render (fn [value] (str "wrapper:" (pr-str value)))
                 :observations
                 {:board {:observation {:kind :delta
                                        :board-version 1
                                        :changes []}
                          :opts {:render (fn [_] "entry")}}}})]
    (is (true? (:ok? result)))
    (is (str/starts-with? (get-in result [:session :board-text])
                          "wrapper:"))))

(deftest flat-and-block-messages-share-semantic-digests
  (let [block-pair (ph/hint-pair {:text "hint" :placement {:p 1}})
        flat-pair (ph/hint-pair {:text "hint" :placement {:p 1} :format :flat})]
    (is (= (ph/message-digest (:call block-pair))
           (ph/message-digest (:call flat-pair))))
    (is (= (ph/message-digest (:result block-pair))
           (ph/message-digest (:result flat-pair))))
    (is (= (ph/message-digest
            {:role :assistant
             :text "a"
             :tool-calls [{:id "x" :name "" :arguments {}}]})
           (ph/message-digest
            {:role :assistant
             :content [{:type :text :text "a"}
                       {:type :tool-call
                        :tool-call-id "x"
                        :tool-name ""
                        :arguments {}}]}))))
  (let [pair (ph/hint-pair {:text "hint" :placement {:p 1} :format :flat})]
    (is (= :flat (:format pair)))
    (is (true? (:pair-hint (:call pair))))
    (is (true? (:pair-hint (:result pair))))
    (is (= "" (get-in pair [:call :tool-calls 0 :name])))
    (is (= "" (:name (:result pair))))
    (is (string? (:text (:result pair))))
    (is (= :flat (get-in pair [:call :meta :format])))
    (is (= :flat (get-in pair [:result :meta :format])))
    (is (nil? (:content (:call pair)))))
  (let [pair (ph/hint-pair {:text "hint" :placement {:p 1}
                            :format :flat :ephemeral? true})]
    (is (true? (get-in pair [:call :meta :ephemeral])))
    (is (true? (get-in pair [:result :meta :ephemeral])))))

(deftest project-request-injects-flat-pair-and-extends-prefix
  (let [session (en-session)
        request (observed-request {:role :system :text "sys"}
                                  {:role :user :text "hi"})
        first (ph/project-request
               session
               request
               {:placement {:turn 1}
                :context {:context-window 128000
                          :turns-used 1
                          :turns-remaining 9
                          :wall-clock-seconds 5
                          :parked-handles ["build"]}
                :render test-render})
        messages (get-in first [:request :snapshot :messages])
        call (nth messages 2)
        tool (nth messages 3)]
    (is (true? (:ok? first)))
    (is (= 4 (count messages)))
    (is (= :assistant (:role call)))
    (is (= "" (get-in call [:tool-calls 0 :name])))
    (is (= :tool (:role tool)))
    (is (= "" (:name tool)))
    (is (str/includes? (:text tool) "Context limit"))
    (is (= 4 (get-in first [:session :chain :count])))
    (is (= 1 (:hint-count (:session first))))
    (testing "the next observed request extends the same chain"
      (let [next-request (observed-request
                          (assoc (nth messages 0) :message-id "m1")
                          (assoc (nth messages 1) :message-id "m2")
                          (assoc call :message-id "m3")
                          (assoc tool :message-id "m4")
                          {:role :user :text "next"})
            second (ph/project-request
                    (:session first)
                    next-request
                    {:placement {:turn 2}
                     :context {:context-window 128000}
                     :render test-render})]
        (is (true? (:ok? second)))
        (is (= 7 (get-in second [:session :chain :count])))
        (is (= 2 (:hint-count (:session second))))))
    (testing "mutating an earlier message is prefix drift"
      (let [mutated (assoc-in (:request first) [:snapshot :messages 0 :text]
                              "changed")
            drift (ph/project-request
                   (:session first)
                   mutated
                   {:placement {:turn 2}
                    :context {:context-window 128000}
                    :render test-render})]
        (is (false? (:ok? drift)))
        (is (= :PREFIX_DRIFT (get-in drift [:error :code])))
        (is (= (:session first) (:session drift)))
        (is (= mutated (:request drift)))))))

(deftest project-request-ephemeral-changes-no-state
  (let [session (en-session)
        first (ph/project-request
               session
               (observed-request {:role :user :text "hi"})
               {:placement {:turn 1}
                :context {:context-window 1000}
                :render test-render})
        ephemeral (ph/project-request
                   (:session first)
                   (observed-request {:role :user :text "title"})
                   {:placement {:ephemeral 1}
                    :ephemeral? true
                    :context {:context-window 1000}
                    :render test-render})]
    (is (true? (:ok? ephemeral)))
    (is (true? (:ephemeral? (:projection ephemeral))))
    (is (= (:session first) (:session ephemeral)))
    (is (= 3 (count (get-in ephemeral [:request :snapshot :messages]))))
    (is (true? (get-in ephemeral [:projection :pair :ephemeral?])))))

(deftest project-request-applies-board-delta-and-writer-clean-break
  (let [session (en-session)
        delta (ph/project-request
               session
               (observed-request {:role :user :text "hi"})
               {:placement {:turn 1}
                :context {:context-window 1000}
                :render test-render
                :observations
                {:board {:observation {:kind :delta
                                       :board-version 1
                                       :changes [{:op :add :id "t1"}]}}}})]
    (is (true? (:ok? delta)))
    (is (= :append (get-in delta [:projection :board-action])))
    (is (= 1 (ph/observed-cursor (:session delta))))
    (is (str/includes? (get-in delta [:request :snapshot :messages 2 :text])
                       ":board-version 1"))
    (testing "writer clean-break clears deltas and opens a new epoch"
      (let [writer (ph/project-request
                    (:session delta)
                    (observed-request {:role :user :text "new"})
                    {:placement {:turn 2}
                     :context {:context-window 1000}
                     :render test-render
                     :observations
                     {:board {:observation {:kind :delta
                                            :board-version 2
                                            :changes []
                                            :snapshot {:tasks [{:id "t1"}]}}
                              :opts {:writer? true}}}})]
        (is (true? (:ok? writer)))
        (is (= :clean-break (get-in writer [:projection :board-action])))
        (is (= [] (get-in writer [:session :board :deltas])))
        (is (= :board-clean-break
               (get-in writer [:session :chain :boundary :kind])))
        (is (= 1 (:epoch-id (:session writer))))))))

(deftest project-request-exposes-a-required-snapshot
  (let [result (ph/project-request
                (en-session)
                (observed-request {:role :user :text "hi"})
                {:placement {:turn 1}
                 :context {:context-window 1000}
                 :render test-render
                 :observations
                 {:board {:observation {:kind :delta
                                        :board-version 1
                                        :changes []}
                          :opts {:thresholds {:count 0
                                              :version-span 99}}}}})]
    (is (true? (:ok? result)))
    (is (true? (get-in result [:projection :needs-snapshot])))
    (is (= :append (get-in result [:projection :board-action])))))

(deftest project-request-reports-an-invalid-current-pair-base
  (let [first (ph/append-hint
               (en-session)
               {:messages [{:role :user
                            :content [{:type :text :text "hi"}]}]
                :placement {:turn 1}})
        request {:attempt-id "a"
                 :call-id "c"
                 :snapshot {:messages
                            (conj (:rendered first)
                                  {:role :user
                                   :content [{:type :video :data "x"}]})}}
        result (ph/project-request
                (:state first)
                request
                {:placement {:turn 1}
                 :context {:context-window 1000}
                 :render test-render})]
    (is (false? (:ok? result)))
    (is (= :PAIR_HINT_CONTENT_TYPE_UNSUPPORTED
           (get-in result [:error :code])))
    (is (= (:state first) (:session result)))
    (is (= request (:request result)))))

(deftest project-request-applies-fleet-and-rolls-back-on-error
  (let [session (en-session)
        fleet (ph/project-request
               session
               (observed-request {:role :user :text "hi"})
               {:placement {:turn 1}
                :context {:context-window 1000}
                :render test-render
                :observations
                {:fleet {:observation {:kind :delta
                                       :fleet-version 1
                                       :swarms [{:swarm-id "a"
                                                 :status :running
                                                 :queued-count 2}]
                                       :events [{:type :result-queued
                                                 :swarm-id "a"}]}}}})]
    (is (true? (:ok? fleet)))
    (is (= :append (get-in fleet [:projection :fleet-action])))
    (is (= 1 (ph/fleet-cursor (:fleet (:session fleet)))))
    (is (str/includes? (get-in fleet [:request :snapshot :messages 2 :text])
                       ":fleet-version 1")))
  (testing "a later observation failure leaves the original session untouched"
    (let [session (en-session)
          stale-session (-> session
                            (ph/apply-fleet-observation
                             {:kind :delta :fleet-version 5 :swarms []}
                             render-opts)
                            :session)
          result (ph/project-request
                  stale-session
                  (observed-request {:role :user :text "hi"})
                  {:placement {:turn 1}
                   :context {:context-window 1000}
                   :render test-render
                   :observations
                   {:board {:observation {:kind :delta
                                          :board-version 1
                                          :changes []}}
                    :fleet {:observation {:kind :delta
                                          :fleet-version 1
                                          :swarms []}}}})]
      (is (false? (:ok? result)))
      (is (= :PAIR_HINT_FLEET_STALE (get-in result [:error :code])))
      (is (= stale-session (:session result)))
      (is (nil? (get-in result [:session :board :board-version]))))))

(deftest project-request-requires-a-renderer-and-valid-request
  (let [session (en-session)
        request (observed-request {:role :user :text "hi"})]
    (is (false? (:ok? (ph/project-request session {} {}))))
    (is (= :PAIR_HINT_REQUEST_INVALID
           (get-in (ph/project-request session {} {}) [:error :code])))
    (let [missing (ph/project-request
                   session
                   request
                   {:placement {:turn 1}
                    :context {:context-window 1000
                              :parked-handles ["build"]}})]
      (is (false? (:ok? missing)))
      (is (= :PAIR_HINT_RENDERER_REQUIRED (get-in missing [:error :code])))
      (is (= session (:session missing)))
      (is (= request (:request missing))))))

(deftest wrapped-gateway-projects-replays-and-delegates
  (let [captured (atom [])
        gateway {:start! (fn [request _deliver]
                           (swap! captured conj request)
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [handle]
                            (swap! captured conj [:cancel handle]))}
        state (atom {:session (en-session)})
        wrapped (ph/wrap-model-gateway
                 gateway
                 {:state state
                  :render test-render
                  :on-session (fn [session _request]
                                (swap! captured conj [:session session]))})
        request (observed-request {:role :user :text "hi"})]
    (is (= {:attempt-id "attempt-1"}
           ((:start! wrapped) request (fn [_]))))
    (let [projected (first (filter map? @captured))]
      (is (= 3 (count (get-in projected [:snapshot :messages]))))
      (is (= :assistant (get-in projected [:snapshot :messages 1 :role])))
      (is (= :tool (get-in projected [:snapshot :messages 2 :role]))))
    (is (= 1 (get-in @state [:session :hint-count])))
    (is (= 1 (count (filter #(= :session (first %)) @captured))))
    (testing "the same business call replays the same pair"
      (reset! captured [])
      ((:start! wrapped) request (fn [_]))
      (is (= 1 (get-in @state [:session :hint-count])))
      (is (= 1 (count (filter map? @captured))))
      (is (= (:request (ph/project-request
                        (:session @state)
                        request
                        {:placement {:call-id "call-1"}
                         :render test-render}))
             (first (filter map? @captured)))))
    (testing "cancel delegates"
      ((:cancel! wrapped) {:attempt-id "a"})
      (is (= [:cancel {:attempt-id "a"}] (last @captured))))))

(deftest wrapped-gateway-ephemeral-and-error-paths
  (let [captured (atom [])
        gateway {:start! (fn [request _deliver]
                           (swap! captured conj request)
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        state (atom {:session (en-session)})
        wrapped (ph/wrap-model-gateway
                 gateway
                 {:state state
                  :render test-render
                  :ephemeral? (fn [request _session]
                                (:ephemeral? request))})
        request (observed-request {:role :user :text "title"})
        before @state]
    ((:start! wrapped) (assoc request :ephemeral? true) (fn [_]))
    (is (= before @state))
    (is (= 3 (count (get-in (first @captured) [:snapshot :messages])))))
  (let [captured (atom [])
        gateway {:start! (fn [request _deliver]
                           (swap! captured conj request)
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        state (atom {:session (en-session)})
        errors (atom [])
        wrapped (ph/wrap-model-gateway
                 gateway
                 {:state state
                  :request-opts {:context {:parked-handles ["build"]}}
                  :on-error (fn [error request]
                              (swap! errors conj [error request]))})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"request projection failed"
                          ((:start! wrapped)
                           (observed-request {:role :user :text "hi"})
                           (fn [_]))))
    (is (empty? @captured))
    (is (= 1 (count @errors)))
    (is (= :PAIR_HINT_RENDERER_REQUIRED (get-in (ffirst @errors) [:code])))
    (is (= 0 (get-in @state [:session :hint-count])))))

(deftest wrapped-gateway-loads-and-persists-session
  (let [persisted (atom nil)
        captured (atom [])
        gateway {:start! (fn [request _]
                           (swap! captured conj request)
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        opts {:render test-render
              :load-session #(deref persisted)
              :persist-session (fn [session _] (reset! persisted session))}
        request (observed-request {:role :user :text "hi"})]
    (let [wrapped (ph/wrap-model-gateway gateway (assoc opts :state (atom nil)))]
      ((:start! wrapped) request (fn [_]))
      (is (map? @persisted))
      (is (= 1 (:hint-count @persisted))))
    (reset! captured [])
    (let [wrapped (ph/wrap-model-gateway gateway (assoc opts :state (atom nil)))]
      ((:start! wrapped) request (fn [_]))
      (is (= 1 (:hint-count @persisted)))
      (is (= 3 (get-in @persisted [:chain :count])))
      (is (= 1 (count @captured)))
      (is (= :assistant (get-in (first @captured) [:snapshot :messages 1 :role]))))))

(deftest scope-boundary-discards-old-placements
  (let [session (en-session)
        first (ph/project-request
               session
               (observed-request {:role :user :text "old"})
               {:placement {:call-id "call-1"}
                :context {:context-window 1000}
                :render test-render})
        reset (ph/project-request
               (:session first)
               (observed-request {:role :user :text "new scope"})
               {:placement {:call-id "call-2"}
                :boundary {:kind :scope-reset :call-id "reset-1"}
                :context {:context-window 1000}
                :render test-render})]
    (is (true? (:ok? reset)))
    (is (= :scope-reset (get-in reset [:session :chain :boundary :kind])))
    (is (= 1 (:epoch-id (:session reset))))
    (is (= 3 (get-in reset [:session :chain :count])))
    (is (= 1 (count (get-in reset [:session :placements]))))
    (is (str/includes? (get-in reset [:session :placements 0 :key]) "call-2")
        "the old placement key must be gone")
    (is (= {:kind :reset-scope :call-id "reset-1"}
           (:scope-boundary (:session reset))))))

(deftest reanchor-preserves-the-raw-scope-fact
  (let [raw {:kind :reset-scope :call-id "r-raw" :new-scope-goal "goal"}
        session (ph/reanchor (en-session)
                             {:kind :scope-reset :call-id "r-raw" :raw raw})
        result (ph/project-request
                session
                (observed-request {:role :user :text "hi"})
                {:placement {:call-id "c-raw"}
                 :context {:context-window 1000}
                 :render test-render})]
    (is (true? (:ok? result)))
    (is (= raw (:scope-boundary (:session result))))
    (is (= raw (get-in result [:session :chain :boundary :raw])))))

(deftest explicit-boundary-supersedes-a-pending-board-fact
  (let [board (ph/apply-board-observation
               (en-session)
               {:kind :delta
                :board-version 1
                :changes []
                :snapshot {:board-version 1 :tasks []}}
               (merge render-opts {:writer? true}))
        result (ph/project-request
                (:session board)
                (observed-request {:role :user :text "hi"})
                {:placement {:call-id "c-merge"}
                 :boundary {:kind :scope-reset :call-id "r-merge"}
                 :context {:context-window 1000}
                 :render test-render})]
    (is (true? (:ok? result)))
    (is (= :scope-reset (get-in result [:session :chain :boundary :kind])))
    (is (= :board-clean-break
           (get-in result [:session :chain :boundary :supersedes :kind])))
    (is (= 1 (:epoch-id (:session result))))))

(deftest compaction-boundary-reopens-the-epoch-after-anchor-loss
  (let [session (en-session)
        messages [{:role :user :text "question" :message-id "m1"}
                  {:role :assistant :text "answer" :message-id "m2"}]
        placed (ph/append-hint session
                               {:messages messages
                                :placement {:call-id "call-1"}
                                :anchor {:after-message-id "m1"}
                                :render test-render})
        ;; Compaction replaced the anchored range with a summary message;
        ;; `m1` no longer appears in the outbound request.
        compacted (observed-request {:role :user :text "[summary]"}
                                    {:role :user :text "later"
                                     :message-id "m9"})
        missing (ph/project-request (:state placed) compacted
                                     {:call-id "call-2"
                                      :placement {:call-id "call-2"}
                                      :render test-render})]
    (is (false? (:ok? missing)))
    (is (= :PAIR_HINT_ANCHOR_MISSING
           (get-in missing [:error :code])))
    (testing "a :compaction boundary opens a new epoch instead"
      (let [reopened (ph/project-request
                      (:state placed) compacted
                      {:call-id "call-2"
                       :placement {:call-id "call-2"}
                       :boundary {:kind :compaction :id "cmp-1"}
                       :render test-render})]
        (is (true? (:ok? reopened)) (pr-str (:error reopened)))
        (is (= :compaction
               (get-in reopened [:session :chain :boundary :kind])))
        (is (= "cmp-1" (:compaction-cursor (:session reopened))))
        (is (= 1 (:epoch-id (:session reopened))))
        ;; The boundary drops the lost call-1 placement; only the current
        ;; call-2 pair is recorded in the new epoch.
        (is (= 1 (count (:placements (:session reopened)))))
        (is (str/includes? (get-in reopened [:session :placements 0 :key])
                           "call-2"))
        (testing "the next request replays the new epoch normally"
          (let [next (ph/project-request
                      (:session reopened)
                      (observed-request {:role :user :text "[summary]"}
                                        {:role :user :text "later"
                                         :message-id "m9"}
                                        {:role :assistant :text "reply"})
                      {:call-id "call-3"
                       :placement {:call-id "call-3"}
                       :render test-render})]
            (is (true? (:ok? next)) (pr-str (:error next)))
            (is (= "cmp-1" (:compaction-cursor (:session next))))))))))

(deftest boundary-supersedes-chain-writes-every-cursor
  (let [session (en-session)
        result (ph/project-request
                session
                (observed-request {:role :user :text "hi"})
                {:call-id "call-1"
                 :placement {:call-id "call-1"}
                 :boundary {:kind :scope-reset
                            :call-id "reset-1"
                            :supersedes {:kind :compaction :id "cmp-9"}}
                 :render test-render})]
    (is (true? (:ok? result)) (pr-str (:error result)))
    (is (= :scope-reset
           (get-in result [:session :chain :boundary :kind])))
    (is (= :compaction
           (get-in result [:session :chain :boundary :supersedes :kind])))
    (is (= "cmp-9" (:compaction-cursor (:session result))))
    (is (= {:kind :reset-scope :call-id "reset-1"}
           (:scope-boundary (:session result))))))

(deftest ephemeral-request-does-not-consume-boundary-cursors
  (let [session (assoc (en-session)
                       :compaction-cursor "cmp-1"
                       :scope-boundary {:kind :reset-scope :call-id "r0"})
        result (ph/project-request
                session
                (observed-request {:role :user :text "side question"})
                {:call-id "call-side"
                 :placement {:call-id "call-side"}
                 :boundary {:kind :compaction :id "cmp-2"}
                 :ephemeral? true
                 :render test-render})]
    (is (true? (:ok? result)) (pr-str (:error result)))
    (is (= "cmp-1" (:compaction-cursor (:session result))))
    (is (= {:kind :reset-scope :call-id "r0"}
           (:scope-boundary (:session result))))
    (is (empty? (:placements (:session result))))))

(deftest board-and-scope-integration-helpers
  (let [entry (ph/board-snapshot-entry
               {:board-version 4 :tasks [{:id "t1"}]}
               {:writer? true})]
    (is (= {:kind :snapshot
            :board-version 4
            :snapshot {:board-version 4 :tasks [{:id "t1"}]}}
           (:observation entry)))
    (is (true? (get-in entry [:opts :writer?]))))
  (is (= {:kind :scope-reset :call-id "r1"}
         (select-keys (ph/scope-boundary
                       {:kind :reset-scope :call-id "r1"})
                      [:kind :call-id])))
  (is (= {:kind :scope-remove :call-id "r2"}
         (select-keys (ph/scope-boundary
                       {:kind :remove-last :call-id "r2"})
                      [:kind :call-id])))
  (is (nil? (ph/scope-boundary {:kind :keep-last :call-id "r3"}))))

(deftest wrapped-gateway-treats-nudge-and-agent-attribution-as-ephemeral
  (let [captured (atom [])
        gateway {:start! (fn [request _]
                           (swap! captured conj request)
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        state (atom {:session (en-session)})
        wrapped (ph/wrap-model-gateway gateway {:state state :render test-render})
        before @state]
    ((:start! wrapped)
     {:attempt-id "n1" :call-id "n1"
      :snapshot {:nudge-id "nudge-1"
                 :messages [{:role :system :text "continue"}]}}
     (fn [_]))
    (is (= before @state))
    (is (= 3 (count (get-in (first @captured) [:snapshot :messages]))))
    ((:start! wrapped)
     {:attempt-id "a1" :call-id "a1"
      :snapshot {:attribution "agent"
                 :messages [{:role :user :text "title"}]}}
     (fn [_]))
    (is (= before @state))
    (is (= 3 (count (get-in (second @captured) [:snapshot :messages]))))))

(deftest unchanged-base-messages-append-after-prior-pairs
  (let [session (en-session)
        request (observed-request {:role :user :text "hi"})
        first (ph/project-request
               session
               request
               {:placement {:call-id "call-1"}
                :context {:context-window 1000}
                :render test-render})
        second (ph/project-request
                (:session first)
                request
                {:placement {:call-id "call-2"}
                 :context {:context-window 1000}
                 :render test-render})
        messages (get-in second [:request :snapshot :messages])]
    (is (true? (:ok? second)))
    (testing "one prior pair is replayed, then the new pair follows it"
      (is (= 5 (count messages)))
      (is (= :assistant (get-in messages [1 :role])))
      (is (= :tool (get-in messages [2 :role])))
      (is (= :assistant (get-in messages [3 :role])))
      (is (= :tool (get-in messages [4 :role]))))
    (is (true? (:ok? (ph/verify-prefix
                      (get-in first [:session :chain])
                      messages))))))

(deftest an-incompatible-chain-fails-instead-of-inventing-a-boundary
  ;; A persisted chain that no longer matches this build must fail loudly.
  ;; Pair Hint may not open a synthetic `:relay` epoch to make it "work":
  ;; the chain is pre-1.0 and may be discarded, but a legal-looking prefix
  ;; boundary that no durable fact justifies is a lie about history.
  (let [request (observed-request {:role :user :text "hi"})
        first (ph/project-request
               (en-session)
               request
               {:placement {:call-id "call-1"}
                :context {:context-window 1000}
                :render test-render})
        ;; A well-formed chain whose root does not match its own digests is
        ;; exactly what an older/foreign identity rule leaves behind.
        corrupt-chain (assoc (get-in first [:session :chain])
                             :root (apply str (repeat 64 "0")))
        session (assoc-in (:session first) [:chain] corrupt-chain)
        result (ph/project-request
                session
                (observed-request {:role :user :text "hi"})
                {:placement {:call-id "call-2"}
                 :context {:context-window 1000}
                 :render test-render})]
    (is (true? (:ok? first)))
    (is (false? (:ok? result)))
    (is (= :PAIR_HINT_CHAIN_INVALID (get-in result [:error :code])))
    (testing "no boundary was invented and no state was committed"
      (is (= session (:session result)))
      (is (nil? (get-in result [:session :pending-boundary])))
      (is (= corrupt-chain (get-in result [:session :chain])))
      (is (= 0 (:epoch-id (:session result)))))))

(deftest board-clean-break-opens-and-extends-a-new-epoch
  (let [message (fn [id role text]
                  {:message-id id :role role :text text})
        board-observation (fn [version]
                            {:board {:observation
                                     {:kind :snapshot
                                      :board-version version
                                      :snapshot {:board-version version
                                                 :tasks []}}
                                     :opts {:writer? true}}})
        session (en-session)
        first (ph/project-request
               session
               (observed-request (message "u1" :user "one"))
               {:placement {:call-id "call-1"}
                :context {:context-window 1000}
                :render test-render
                :observations (board-observation 1)})
        second (ph/project-request
                (:session first)
                (observed-request
                 (message "u1" :user "one")
                 (message "a1" :assistant "reply")
                 (message "u2" :user "two"))
                {:placement {:call-id "call-2"}
                 :context {:context-window 1000}
                 :render test-render
                 :observations (board-observation 1)})
        third (ph/project-request
               (:session second)
               (observed-request
                (message "u1" :user "one")
                (message "a1" :assistant "reply")
                (message "u2" :user "two")
                (message "a2" :assistant "reply2")
                (message "u3" :user "three"))
               {:placement {:call-id "call-3"}
                :context {:context-window 1000}
                :render test-render
                :observations (board-observation 2)})
        fourth (ph/project-request
                (:session third)
                (observed-request
                 (message "u1" :user "one")
                 (message "a1" :assistant "reply")
                 (message "u2" :user "two")
                 (message "a2" :assistant "reply2")
                 (message "u3" :user "three")
                 (message "a3" :assistant "reply3")
                 (message "u4" :user "four"))
                {:placement {:call-id "call-4"}
                 :context {:context-window 1000}
                 :render test-render
                 :observations (board-observation 2)})]
    (is (true? (:ok? first)))
    (is (= :board-clean-break
           (get-in first [:session :chain :boundary :kind])))
    (is (= 1 (:epoch-id (:session first))))
    (is (true? (:ok? second)))
    (is (= :board-clean-break
           (get-in second [:session :chain :boundary :kind])))
    (is (= 1 (:epoch-id (:session second))))
    (is (= 2 (:hint-count (:session second))))
    (is (true? (:ok? (ph/verify-prefix
                      (get-in first [:session :chain])
                      (get-in second [:request :snapshot :messages])))))
    (is (true? (:ok? third)))
    (is (= :board-clean-break
           (get-in third [:session :chain :boundary :kind])))
    (is (= 2 (get-in third [:session :chain :boundary :board-version])))
    (is (= 2 (:epoch-id (:session third))))
    (is (= 1 (count (get-in third [:session :placements]))))
    (is (= 7 (count (get-in third [:request :snapshot :messages]))))
    (is (true? (:ok? fourth)))
    (is (= 2 (:epoch-id (:session fourth))))
    (is (= 4 (:hint-count (:session fourth))))
    (is (true? (:ok? (ph/verify-prefix
                      (get-in third [:session :chain])
                      (get-in fourth [:request :snapshot :messages])))))))

(deftest recorded-assistant-change-is-reported-as-drift
  ;; A change to the text of an assistant message already in the chain is a
  ;; real identity change.  Pair Hint reports `:PREFIX_DRIFT` at that index
  ;; and commits nothing; the section 6.1 boundary list has no
  ;; `:assistant-rewrite` kind, so no API may invent one to absorb it.
  (let [message (fn [id role text & [extra]]
                  (merge {:message-id id :role role :text text} extra))
        assistant (fn [text] {:message-id "m-0042" :role :assistant :text text})
        recorded (ph/project-request
                  (en-session)
                  (observed-request (message "u0" :user "before")
                                    (assistant "Stub "))
                  {:placement {:call-id "call-1"}
                   :context {:context-window 1000}
                   :render test-render})
        changed (ph/project-request
                 (:session recorded)
                 (observed-request (message "u0" :user "before")
                                   (assistant "Stopped.")
                                   (message "u1" :user "next"))
                 {:placement {:call-id "call-2"}
                  :context {:context-window 1000}
                  :render test-render})]
    (is (true? (:ok? recorded)))
    (is (true? (:ok? (ph/verify-prefix
                      (get-in recorded [:session :chain])
                      (get-in recorded [:request :snapshot :messages])))))
    (is (false? (:ok? changed)))
    (is (= :PREFIX_DRIFT (get-in changed [:error :code])))
    (is (= 1 (get-in changed [:error :index])))
    (testing "a failed projection commits no state and returns the request"
      (is (= (:session recorded) (:session changed)))
      (is (= (get-in recorded [:session :chain])
             (get-in changed [:session :chain]))))
    (testing "a user text change is drift at the user index"
      (let [user-drift (ph/project-request
                        (:session recorded)
                        (observed-request (message "u0" :user "changed")
                                          (assistant "Stub "))
                        {:placement {:call-id "call-3"}
                         :context {:context-window 1000}
                         :render test-render})]
        (is (false? (:ok? user-drift)))
        (is (= :PREFIX_DRIFT (get-in user-drift [:error :code])))
        (is (= 0 (get-in user-drift [:error :index])))))
    (testing "a citation change on the user message is drift"
      (let [citation-drift (ph/project-request
                            (:session recorded)
                            (observed-request
                             (message "u0" :user "before"
                                      {:citations [{:text "source-a"}]})
                             (assistant "Stub "))
                            {:placement {:call-id "call-4"}
                             :context {:context-window 1000}
                             :render test-render})]
        (is (false? (:ok? citation-drift)))
        (is (= :PREFIX_DRIFT (get-in citation-drift [:error :code])))))))

;; ---------------------------------------------------------------------------
;; Tools-less models never receive a tool envelope

(deftest a-tools-less-model-receives-notes-not-a-tool-envelope
  ;; A model whose profile declares `:tools false` cannot use a tool call, so
  ;; sending one is noise at best and a provider error at worst.  The hint
  ;; content must still arrive: it degrades to one plain assistant message,
  ;; and the degradation is chosen by the caller's capability fact.
  (let [captured (atom [])
        gateway {:start! (fn [request _deliver]
                           (swap! captured conj request)
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        state (atom {:session (en-session)})
        wrapped (ph/wrap-model-gateway
                 gateway
                 {:state state
                  :render test-render
                  :tools-supported? false})
        request (observed-request {:role :user :text "hi"})]
    (is (map? ((:start! wrapped) request (fn [_]))))
    (let [messages (get-in (first @captured) [:snapshot :messages])]
      (is (= [:user :assistant] (mapv :role messages))
          "no :tool message and no tool-call assistant message")
      (is (not-any? #(seq (:tool-calls %)) messages))
      (is (not-any? #(= :tool (:role %)) messages))
      (is (= :notes (get-in messages [1 :meta :format])))
      (is (true? (get-in messages [1 :pair-hint])))
      (is (string? (get-in messages [1 :text])))
      (is (pos? (count (get-in messages [1 :text])))))
    (testing "the hint is recorded once and replayed, not duplicated"
      (is (= 1 (get-in @state [:session :hint-count])))
      (reset! captured [])
      ((:start! wrapped) request (fn [_]))
      (is (= 1 (get-in @state [:session :hint-count])))
      (is (= 2 (count (get-in (first @captured) [:snapshot :messages])))))))

(deftest a-capability-stated-only-through-request-opts-is-honoured
  ;; `:request-opts` is the documented pass-through channel.  A caller that
  ;; states only the capability fact there must still get notes: otherwise
  ;; the wrapper's own computed `:format` pre-empts it, and the tools-less
  ;; model silently receives the envelope this feature exists to prevent.
  (let [run (fn [opts]
              (let [captured (atom [])
                    gateway {:start! (fn [request _deliver]
                                       (swap! captured conj request)
                                       {:attempt-id (:attempt-id request)})
                             :cancel! (fn [_] nil)}
                    wrapped (ph/wrap-model-gateway
                             gateway
                             (merge {:state (atom {:session (en-session)})
                                     :render test-render}
                                    opts))]
                ((:start! wrapped) (observed-request {:role :user :text "hi"})
                 (fn [_]))
                (get-in (first @captured) [:snapshot :messages])))]
    (testing "capability alone degrades to notes"
      (let [messages (run {:request-opts (fn [_ _] {:tools-supported? false})})]
        (is (= [:user :assistant] (mapv :role messages)))
        (is (not-any? #(seq (:tool-calls %)) messages))
        (is (= :notes (get-in messages [1 :meta :format])))))
    (testing "an explicit :format through the same channel still wins"
      (is (= [:user :assistant :tool]
             (mapv :role (run {:request-opts (fn [_ _]
                                               {:tools-supported? false
                                                :format :flat})})))))
    (testing "a direct wrapper statement outranks the pass-through one"
      (is (= [:user :assistant :tool]
             (mapv :role (run {:tools-supported? true
                               :request-opts (fn [_ _]
                                               {:tools-supported? false})})))))))

(deftest the-capability-fact-defaults-to-the-tool-pair
  ;; Saying nothing must not silently degrade: an unstated capability keeps
  ;; the specified paired tool-call form.
  (let [captured (atom [])
        gateway {:start! (fn [request _deliver]
                           (swap! captured conj request)
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        wrapped (ph/wrap-model-gateway
                 gateway
                 {:state (atom {:session (en-session)})
                  :render test-render})]
    ((:start! wrapped) (observed-request {:role :user :text "hi"}) (fn [_]))
    (let [messages (get-in (first @captured) [:snapshot :messages])]
      (is (= [:user :assistant :tool] (mapv :role messages)))
      (is (= "" (get-in messages [1 :tool-calls 0 :name]))))))

(deftest notes-and-tool-forms-agree-on-the-payload
  (let [note (ph/hint-pair {:text "payload" :placement {:p 1} :format :notes})
        flat (ph/hint-pair {:text "payload" :placement {:p 1} :format :flat})]
    (is (= "payload" (:text note)))
    (is (= "payload" (:text flat)))
    (is (nil? (:result note))
        "a notes hint has no tool result to pair with")
    (is (some? (:result flat)))
    (is (= (:tool-call-id note) (:tool-call-id flat))
        "placement identity does not depend on the rendering format")))

