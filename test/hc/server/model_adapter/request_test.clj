(ns hc.server.model-adapter.request-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.attachments.model :as attachments]
            [hc.server.model-adapter.request :as request])
  (:import [dev.langchain4j.data.message AiMessage ImageContent SystemMessage
            TextContent ToolExecutionResultMessage UserMessage]
           [dev.langchain4j.model.chat.request ResponseFormatType ToolChoice]))

(def snapshot
  {:role :main
   :model "model-1"
   :messages [{:role :system :text "be terse"}
              {:role :user :text "read a.txt"}
              {:role :assistant
               :text "I will."
               :tool-calls [{:id "provider-1"
                             :name "cat"
                             :arguments {:path "a.txt"}}]}
              {:role :tool
               :tool-call-id "provider-1"
               :name "cat"
               :text "hello"}]
   :tools [{:name "cat"
            :description "read a file"
            :parameters {:type "object"
                         :properties {:path {:type "string"}}
                         :required ["path"]}}]
   :temperature 0.2
   :max-tokens 64
   :tool-choice :required
   :response-format :json
   :stream? true})

(deftest compiles-provider-neutral-snapshot
  (let [compiled (request/compile-request snapshot {:model-id "fallback"})]
    (testing "model, messages, and parameter fields reach LangChain4j"
      (is (= "model-1" (.modelName compiled)))
      (is (= 4 (count (.messages compiled))))
      (is (instance? SystemMessage (first (.messages compiled))))
      (is (instance? UserMessage (second (.messages compiled))))
      (is (instance? AiMessage (nth (.messages compiled) 2)))
      (is (instance? ToolExecutionResultMessage (nth (.messages compiled) 3)))
      (is (= 0.2 (.temperature compiled)))
      (is (= 64 (.maxOutputTokens compiled)))
      (is (= ToolChoice/REQUIRED (.toolChoice compiled)))
      (is (= ResponseFormatType/JSON (-> compiled .responseFormat .type))))
    (testing "tool schemas become real ToolSpecification values"
      (is (= 1 (count (.toolSpecifications compiled))))
      (is (= "cat" (-> compiled .toolSpecifications first .name)))
      (is (= "read a file" (-> compiled .toolSpecifications first .description))))))

(deftest rejects-unknown-fields
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"does not understand"
                        (request/compile-request
                         (assoc snapshot :mystery 1)
                         {:model-id "fallback"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"does not understand"
                        (request/compile-request
                         (update snapshot :messages
                                 conj {:role :user :text "x" :mystery 1})
                         {:model-id "fallback"}))))

(deftest rejects-duplicate-tool-names
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unique"
                        (request/compile-request
                         (assoc snapshot :tools
                                [{:name "cat"}
                                 {:name "cat"}])
                         {:model-id "fallback"}))))

(deftest computes-structural-capabilities
  (is (= #{:tools}
         (request/required-capabilities snapshot)))
  (is (= #{:tools :json-schema}
         (request/required-capabilities
          (assoc snapshot :response-format
                 {:type :json-schema
                  :name "answer"
                  :schema {:type "object"
                           :properties {:answer {:type "string"}}
                           :required ["answer"]}})))))

(deftest parses-json-tool-arguments-to-keyword-maps
  (let [parse (request/parse-arguments :json)]
    (is (= {:path "a.txt" :line 3}
           (parse "{\"path\":\"a.txt\",\"line\":3}")))
    (is (= {:path "a.txt" :line 3}
           (parse "{:path \"a.txt\" :line 3}"))
        "standard EDN input is accepted")
    (is (= {:path "a\\b.txt"}
           (parse (str "{:path #\"" "a\\b.txt" "\"#}")))
        "UFSS raw fences preserve backslashes")
    (is (= {:text "line1\nline2"}
           (parse (str "{:text #\"" "line1\nline2" "\"#}")))
        "UFSS raw fences may contain a literal newline")
    (is (= {:nested {:flag true}}
           (parse "{\"nested\":{\"flag\":true}}")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse "[1 2 3]"))
        "tool arguments must be a map")))

(deftest parses-strict-edn-and-ufss
  (let [parse (request/parse-arguments :edn)]
    (is (= {:path "a.txt"}
           (parse "{:path \"a.txt\"}")))
    (is (= {:path "a\nb"}
           (parse (str "{:path #\"" "a\nb" "\"#}"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse "{\"path\":\"a.txt\"}"))
        "strict EDN mode does not consume JSON colon separators")))

(deftest compiles-json-schema-response-format
  (let [compiled (request/compile-request
                  (assoc snapshot :response-format
                         {:type :json-schema
                          :name "answer"
                          :schema {:type "object"
                                   :properties {:answer {:type "string"}}
                                   :required ["answer"]}})
                  {:model-id "fallback"})]
    (is (= ResponseFormatType/JSON (-> compiled .responseFormat .type)))
    (is (= "answer" (-> compiled .responseFormat .jsonSchema .name)))))

(deftest compiles-text-and-native-attachments
  (let [text-snapshot
        {:messages [{:role :user
                     :text "caption"
                     :attachments [{:attachment-id "a1"
                                    :name "notes.txt"
                                    :send-route :text
                                    :send-text "confirmed text"}]}]}
        text-compiled (request/compile-request text-snapshot
                                               {:model-id "fallback"})
        text-message (first (.messages text-compiled))]
    (is (instance? UserMessage text-message))
    (is (= 2 (count (.contents text-message))))
    (is (every? #(instance? TextContent %) (.contents text-message)))
    (is (some #(re-find #"confirmed text" (.text ^TextContent %))
              (.contents text-message))))

  (let [native-snapshot
        {:messages [{:role :user
                     :text "what is this?"
                     :attachments [{:attachment-id "a2"
                                    :name "pixel.png"
                                    :type :image
                                    :send-route :native
                                    :send-blob-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                    :send-mime "image/png"}]}]}
        loader (fn [_] {:bytes (byte-array [1 2 3]) :mime "image/png"})
        compiled (request/compile-request native-snapshot
                                          {:model-id "fallback"}
                                          {:attachment-loader loader})
        message (first (.messages compiled))]
    (is (instance? UserMessage message))
    (is (some #(instance? ImageContent %) (.contents message)))
    (is (contains? (request/required-capabilities native-snapshot) :vision))))

(deftest compiles-the-descriptor-produced-by-the-attachment-policy
  (let [blob-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        url (str "/blobs/" blob-id "?filename=pixel.png")
        draft {:attachment-id "att-1"
               :id "att-1"
               :name "pixel.png"
               :mime "image/png"
               :size 70
               :blob-id blob-id
               :local-copy? true
               :status :ready
               :ready? true
               :excluded? false
               :media {:kind :image}
               :preferred-route nil
               :status-label "可发送"
               :remove-label "移除附件 pixel.png"
               :url url}
        prepared (attachments/prepare-send [draft]
                                           {:capabilities #{:vision}})
        sent (first (:sendable prepared))
        snapshot {:messages [{:role :user
                              :text "what is this?"
                              :attachments [sent]}]}
        compiled (request/compile-request
                  snapshot
                  {:model-id "fallback"}
                  {:attachment-loader (fn [_]
                                        {:bytes (byte-array [1 2 3])
                                         :mime "image/png"})})
        message (first (.messages compiled))]
    (is (:ok? prepared))
    (is (not (contains? sent :preferred-route)))
    (is (not (contains? sent :status-label)))
    (is (not (contains? sent :remove-label)))
    (is (= url (:url sent)))
    (is (instance? UserMessage message))
    (is (some #(instance? ImageContent %) (.contents message))
        "the exact map the Owner stores in history compiles to an image part")
    (is (contains? (request/required-capabilities snapshot) :vision))))

(deftest attachment-failures-are-explicit
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"prepared send route"
       (request/compile-request
        {:messages [{:role :user
                     :text "x"
                     :attachments [{:attachment-id "a1"
                                    :blob-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                    :mime "image/png"}]}]}
        {:model-id "fallback"})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"attachment-loader"
       (request/compile-request
        {:messages [{:role :user
                     :text "x"
                     :attachments [{:attachment-id "a1"
                                    :type :image
                                    :send-route :native
                                    :send-blob-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                    :send-mime "image/png"}]}]}
        {:model-id "fallback"}))))

(deftest quotes-and-citations-are-preserved-as-text
  (let [compiled (request/compile-request
                  {:messages [{:role :user
                               :text "question"
                               :quotes [{:message-id "m1"
                                         :text "quoted answer"}]
                               :citations [{:label "source one"}]}]}
                  {:model-id "fallback"})
        message (first (.messages compiled))
        text (apply str (map #(.text ^TextContent %) (.contents message)))]
    (is (re-find #"question" text))
    (is (re-find #"quoted answer" text))
    (is (re-find #"source one" text))))

(deftest accepts-pair-hint-synthetic-empty-tool-name
  (let [compiled (request/compile-request
                  {:messages
                   [{:role :assistant
                     :pair-hint true
                     :tool-calls [{:id "pair-hint-abc"
                                   :name ""
                                   :arguments {}}]}
                    {:role :tool
                     :pair-hint true
                     :tool-call-id "pair-hint-abc"
                     :name ""
                     :text "synthetic payload"}]}
                  {:model-id "fallback"})
        [assistant tool] (.messages compiled)]
    (is (= "" (-> assistant .toolExecutionRequests first .name)))
    (is (= "" (.toolName tool)))
    (is (= "synthetic payload" (.text tool))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"must not be blank"
       (request/compile-request
        {:messages [{:role :assistant
                     :tool-calls [{:id "ordinary"
                                   :name ""
                                   :arguments {}}]}]}
        {:model-id "fallback"}))))

(deftest profile-wire-name-wins-over-snapshot-selector
  (let [compiled (request/compile-request
                  (assoc snapshot :model "entry")
                  {:model-id "entry" :model-name "wire-model"})]
    (is (= "wire-model" (.modelName compiled))
        "snapshot :model selects the profile; it is not the provider wire name")))

(deftest keyword-model-id-is-normalized
  (let [compiled (request/compile-request
                  (assoc snapshot :model :model-1)
                  {:model-id "fallback"})]
    (is (= "model-1" (.modelName compiled)))))

(deftest tool-choice-required-needs-tools
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"needs at least one tool"
       (request/compile-request
        (assoc snapshot :tools [] :tool-choice :required)
        {:model-id "fallback"}))))
