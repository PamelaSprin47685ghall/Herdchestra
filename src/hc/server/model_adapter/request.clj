(ns hc.server.model-adapter.request
  "Compile one frozen kernel snapshot into a LangChain4j ChatRequest.

  The snapshot is deliberately provider-neutral EDN.  This namespace validates
  every field before it reaches LangChain4j; unknown fields are rejected rather
  than dropped.  It also computes the capabilities the snapshot actually
  depends on, so the port can fail before a provider call."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.core.ufss :as ufss]
            [hc.server.model-adapter.json :as json])
  (:import [dev.langchain4j.agent.tool ToolExecutionRequest ToolSpecification]
           [dev.langchain4j.data.message AiMessage AudioContent Content
            ImageContent PdfFileContent TextContent VideoContent
            SystemMessage ToolExecutionResultMessage UserMessage]
           [dev.langchain4j.model.chat.request ChatRequest
            ResponseFormat ToolChoice]
           [dev.langchain4j.model.chat.request.json JsonSchema
            JsonObjectSchema]
           [java.net URI]
           [java.util Base64]))

(defn- invalid!
  [reason message data]
  (throw (ex-info message
                  (merge {:error/kind :invalid-snapshot
                          :reason reason}
                         data))))

(def top-level-keys
  #{:role :provider :connection :model :messages :tools :parameters
    :temperature :top-p :top-k :max-tokens :max-output-tokens :stop
    :frequency-penalty :presence-penalty :tool-choice :response-format
    :required-capabilities :stream? :call-id
    :attempt-id})

(def message-keys
  {:system #{:role :text}
   :user #{:role :text :attachments :quotes :citations}
   :assistant #{:role :text :thinking :tool-calls :pair-hint :pair-hint-id
                :meta}
   :tool #{:role :tool-call-id :name :text :error? :pair-hint :pair-hint-id
           :meta}})

(def tool-call-keys #{:id :name :arguments})
(def tool-keys #{:name :description :parameters :strict? :metadata})
(def parameter-keys
  #{:temperature :top-p :top-k :max-output-tokens :stop
    :frequency-penalty :presence-penalty})

(def attachment-keys
  ;; `:url` is the immutable `/blobs/<id>` link carried with a sent
  ;; attachment.  It is reference/display metadata: the adapter never fetches
  ;; it and still loads native bytes through the injected attachment-loader.
  #{:attachment-id :id :name :type :kind :mime :send-mime :blob-id
    :send-blob-id :size :send-route :send-text :media :status :excluded?
    :ready? :local-copy? :prepared :prepared-status :extraction :workspace
    :compatibility :issues :send-source :send-original? :sha256
    :declared-mime :sniffed-mime :mime-mismatch? :source :created-at
    :limits :url})

(defn- check-keys!
  [m allowed path]
  (when-not (map? m)
    (invalid! :not-a-map "expected a map" {:path path}))
  (when-let [unknown (seq (remove allowed (keys m)))]
    (invalid! :unknown-field
              "snapshot contains a field the adapter does not understand"
              {:path path :unknown (vec (sort-by str unknown))})))

(defn- normalize-key
  [key key-mode]
  (cond
    (not (string? key)) key
    (= :string key-mode) key
    (= :keyword key-mode) (if (str/blank? key) key (keyword key))
    (fn? key-mode) (key-mode key)
    :else key))

(defn- normalize-argument-keys
  [value key-mode path]
  (cond
    (map? value)
    (reduce-kv
     (fn [result key child]
       (let [key' (normalize-key key key-mode)
             child' (normalize-argument-keys child key-mode (conj path key))]
         (when (contains? result key')
           (invalid! :duplicate-argument-key
                     "tool argument keys collide after normalization"
                     {:path (conj path key) :key key'}))
         (assoc result key' child')))
     {}
     value)

    (vector? value)
    (mapv (fn [index child]
            (normalize-argument-keys child key-mode (conj path index)))
          value
          (range))

    (set? value)
    (into #{} (map #(normalize-argument-keys % key-mode path)) value)

    (sequential? value)
    (mapv (fn [index child]
            (normalize-argument-keys child key-mode (conj path index)))
          value
          (range))

    :else value))

(defn- read-arguments-with
  [reader text key-mode format]
  (when-not (string? text)
    (invalid! :arguments-not-a-string
              "tool arguments must be a string"
              {:value text}))
  (try
    (let [value (reader text)]
      (if (map? value)
        (normalize-argument-keys value key-mode [])
        (invalid! :arguments-not-a-map
                  "tool arguments must read as a map"
                  {:format format :value value})))
    (catch clojure.lang.ExceptionInfo e
      (if (= :invalid-snapshot (:error/kind (ex-data e)))
        (throw e)
        (invalid! :arguments-unparsable
                  "tool arguments are not valid JSON, EDN, or UFSS"
                  {:format format
                   :position (select-keys (ex-data e)
                                          [:offset :line :column
                                           :expected :found])
                   :error (ex-data e)})))
    (catch Throwable t
      (invalid! :arguments-unparsable
                "tool arguments could not be parsed"
                {:format format :error (.getMessage t)}))))

(defn read-arguments
  "Read one tool-argument map from JSON, standard EDN, or EDN+UFSS.

  `key-mode` is `:keyword` (default), `:string`, or a function applied to
  string keys at every nesting level.  Keyword and symbol keys stay as they
  were written.  The result must be a map; a scalar or vector is an explicit
  failure."
  ([text] (read-arguments text :keyword))
  ([text key-mode]
   (read-arguments-with ufss/read-input text key-mode :input)))

(defn- read-edn-arguments
  [text key-mode]
  (read-arguments-with ufss/read-str text key-mode :edn))

(defn- string!
  [m k path]
  (let [value (get m k)]
    (when-not (string? value)
      (invalid! :not-a-string
                "field must be a string"
                {:path (conj path k) :value value}))
    value))

(defn- optional-string!
  [m k path]
  (when (contains? m k)
    (string! m k path)))

(defn- boolean!
  [m k path]
  (let [value (get m k)]
    (when-not (boolean? value)
      (invalid! :not-a-boolean
                "field must be a boolean"
                {:path (conj path k) :value value}))
    value))

(defn- number!
  [m k path]
  (let [value (get m k)]
    (when-not (number? value)
      (invalid! :not-a-number
                "field must be a number"
                {:path (conj path k) :value value}))
    value))

(defn- non-negative-int!
  [m k path]
  (let [value (get m k)]
    (when-not (and (integer? value) (not (neg? value)))
      (invalid! :not-a-non-negative-integer
                "field must be a non-negative integer"
                {:path (conj path k) :value value}))
    value))

(defn- attachment-route
  [attachment]
  (let [route (:send-route attachment)]
    (cond
      (keyword? route) route
      (string? route) (keyword route)
      :else route)))

(defn- attachment-blob-id
  [attachment]
  (or (:send-blob-id attachment) (:blob-id attachment)))

(defn- attachment-mime
  [attachment]
  (or (:send-mime attachment) (:mime attachment)))

(defn- mime-kind
  [mime]
  (cond
    (str/starts-with? (str mime) "image/") :image
    (= "application/pdf" mime) :pdf
    (str/starts-with? (str mime) "audio/") :audio
    (str/starts-with? (str mime) "video/") :video
    :else nil))

(defn- attachment-kind
  [attachment]
  (let [declared (or (:type attachment) (:kind attachment)
                     (get-in attachment [:media :kind]))
        declared (cond
                   (keyword? declared) declared
                   (string? declared) (if (str/includes? declared "/")
                                        (mime-kind declared)
                                        (keyword declared))
                   :else declared)
        mime (attachment-mime attachment)]
    (cond
      (contains? #{:image :pdf :audio :video :text} declared) declared
      (mime-kind mime) (mime-kind mime)
      (and (string? mime) (str/starts-with? mime "text/")) :text
      :else :text)))

(defn- mime-matches-kind?
  [kind mime]
  (case kind
    :image (str/starts-with? (str mime) "image/")
    :pdf (= "application/pdf" mime)
    :audio (str/starts-with? (str mime) "audio/")
    :video (str/starts-with? (str mime) "video/")
    :text true
    false))

(defn- attachment-capability
  [attachment]
  (when (= :native (attachment-route attachment))
    (case (attachment-kind attachment)
      :image :vision
      :pdf :document
      :audio :audio
      :video :video
      nil)))

(defn- validate-attachment
  [attachment path]
  (check-keys! attachment attachment-keys path)
  (when (true? (:excluded? attachment))
    (invalid! :attachment-excluded
              "an excluded attachment must not enter a model request"
              {:path path :code :ATTACHMENT_EXCLUDED}))
  (when (and (contains? attachment :status)
             (not= :ready (keyword (:status attachment))))
    (invalid! :attachment-not-ready
              "an attachment must be ready before it enters a model request"
              {:path path
               :status (:status attachment)
               :code :ATTACHMENT_NOT_READY}))
  (let [route (attachment-route attachment)
        kind (attachment-kind attachment)
        mime (attachment-mime attachment)]
    (case route
      :text
      (let [text (:send-text attachment)]
        (when-not (and (string? text) (not (str/blank? text)))
          (invalid! :attachment-unresolved
                    "a text-route attachment needs confirmed send-text"
                    {:path path
                     :attachment-id (or (:attachment-id attachment) (:id attachment))
                     :code :ATTACHMENT_UNRESOLVED})))

      :native
      (let [blob-id (attachment-blob-id attachment)]
        (when (= :text kind)
          (invalid! :attachment-kind-unsupported
                    "a native attachment needs image, PDF, audio, or video kind"
                    {:path path :kind kind
                     :code :ATTACHMENT_NOT_SENDABLE}))
        (when-not (and (string? blob-id) (not (str/blank? blob-id)))
          (invalid! :attachment-unresolved
                    "a native attachment needs send-blob-id"
                    {:path path
                     :attachment-id (or (:attachment-id attachment) (:id attachment))
                     :code :ATTACHMENT_UNRESOLVED}))
        (when-not (and (string? mime) (not (str/blank? mime)))
          (invalid! :attachment-unresolved
                    "a native attachment needs send-mime"
                    {:path path
                     :attachment-id (or (:attachment-id attachment) (:id attachment))
                     :code :ATTACHMENT_UNRESOLVED}))
        (when-not (mime-matches-kind? kind mime)
          (invalid! :attachment-mime-mismatch
                    "attachment kind and MIME type disagree"
                    {:path path :kind kind :mime mime
                     :code :ATTACHMENT_NOT_SENDABLE})))

      (invalid! :attachment-unresolved
                "attachment has no prepared send route"
                {:path path
                 :attachment-id (or (:attachment-id attachment) (:id attachment))
                 :send-route (:send-route attachment)
                 :code :ATTACHMENT_UNRESOLVED}))
    (when (contains? attachment :size)
      (non-negative-int! attachment :size path))
    (assoc attachment
           :send-route route
           :kind kind
           :send-blob-id (attachment-blob-id attachment)
           :send-mime mime)))

(defn attachment-capabilities
  "Capabilities required by native attachments in a snapshot."
  [snapshot]
  (into #{}
        (comp (filter #(= :user (:role %)))
              (mapcat :attachments)
              (keep attachment-capability))
        (:messages snapshot)))

(defn- normalize-role
  [role path]
  (let [role (cond
               (keyword? role) role
               (string? role) (keyword role)
               :else role)]
    (when-not (contains? message-keys role)
      (invalid! :unknown-role
                "message role must be :system, :user, :assistant, or :tool"
                {:path path :role role}))
    role))

(defn- pair-hint-message?
  [message]
  (or (true? (:pair-hint message))
      (true? (get-in message [:meta :pair-hint]))))

(defn- validate-tool-call
  [call path pair-hint?]
  (check-keys! call tool-call-keys path)
  (let [name (string! call :name path)]
    (when (and (str/blank? name) (not pair-hint?))
      (invalid! :blank-tool-name "tool name must not be blank" {:path path}))
    (when (contains? call :id)
      (let [id (:id call)]
        (when-not (or (nil? id) (string? id))
          (invalid! :invalid-tool-call-id
                    "tool call id must be a string or nil"
                    {:path (conj path :id) :value id}))))
    (let [arguments (:arguments call)]
      (when-not (or (nil? arguments) (map? arguments) (string? arguments))
        (invalid! :invalid-tool-arguments
                  "tool arguments must be a map or a JSON string"
                  {:path (conj path :arguments)}))
      (when (string? arguments)
        (try
          (read-arguments arguments :keyword)
          (catch clojure.lang.ExceptionInfo e
            (invalid! :invalid-tool-arguments
                      "tool arguments string is not valid JSON, EDN, or UFSS"
                      {:path (conj path :arguments)
                       :detail (ex-data e)})))))
    call))

(defn- validate-message
  [message index]
  (let [path [:messages index]
        role (normalize-role (:role message) (conj path :role))]
    (check-keys! message (get message-keys role) path)
    (let [message
          (case role
      :system
      (do
        (string! message :text path)
        message)

      :user
      (do
        (string! message :text path)
        (let [message
              (if (contains? message :attachments)
                (do
                  (when-not (sequential? (:attachments message))
                    (invalid! :attachments-not-sequential
                              "user attachments must be sequential"
                              {:path (conj path :attachments)}))
                  (assoc message
                         :attachments
                         (mapv (fn [index attachment]
                                 (validate-attachment
                                  attachment
                                  (conj path :attachments index)))
                               (range)
                               (:attachments message))))
                message)]
          (when (contains? message :quotes)
            (when-not (and (sequential? (:quotes message))
                           (every? (fn [quote]
                                     (and (map? quote)
                                          (string? (:text quote))
                                          (not (str/blank? (:text quote)))))
                                   (:quotes message)))
              (invalid! :invalid-quotes
                        "quotes must be maps with non-blank text"
                        {:path (conj path :quotes)})))
          (when (contains? message :citations)
            (when-not (and (sequential? (:citations message))
                           (every? map? (:citations message)))
              (invalid! :invalid-citations
                        "citations must be maps"
                        {:path (conj path :citations)})))
          message))

      :assistant
      (do
        (optional-string! message :text path)
        (optional-string! message :thinking path)
        (when (contains? message :tool-calls)
          (when-not (sequential? (:tool-calls message))
            (invalid! :tool-calls-not-sequential
                      "assistant tool-calls must be sequential"
                      {:path (conj path :tool-calls)}))
          (doseq [[i call] (map-indexed vector (:tool-calls message))]
            (validate-tool-call call
                                (conj path :tool-calls i)
                                (pair-hint-message? message))))
        (when (and (nil? (:text message))
                   (empty? (:tool-calls message)))
          (invalid! :empty-assistant-message
                    "assistant message needs text or tool calls"
                    {:path path}))
        message)

      :tool
      (do
        (when-not (or (nil? (:tool-call-id message))
                      (string? (:tool-call-id message)))
          (invalid! :invalid-tool-call-id
                    "tool-call-id must be a string or nil"
                    {:path (conj path :tool-call-id)}))
        (let [name (string! message :name path)]
          (when (and (str/blank? name)
                     (not (pair-hint-message? message)))
            (invalid! :blank-tool-name
                      "tool result name must not be blank"
                      {:path (conj path :name)})))
        (string! message :text path)
        (when (contains? message :error?)
          (boolean! message :error? path))
        message))]
      (assoc message :role role))))

(defn- validate-tool
  [tool index]
  (let [path [:tools index]]
    (check-keys! tool tool-keys path)
    (let [name (string! tool :name path)]
      (when (str/blank? name)
        (invalid! :blank-tool-name "tool name must not be blank" {:path path})))
    (optional-string! tool :description path)
    (when (contains? tool :parameters)
      (when-not (map? (:parameters tool))
        (invalid! :tool-parameters-not-a-map
                  "tool parameters must be a JSON-schema map"
                  {:path (conj path :parameters)})))
    (when (contains? tool :strict?)
      (boolean! tool :strict? path))
    (when (contains? tool :metadata)
      (when-not (map? (:metadata tool))
        (invalid! :tool-metadata-not-a-map
                  "tool metadata must be a map"
                  {:path (conj path :metadata)})))
    tool))

(defn validate-snapshot
  "Validate and return the snapshot unchanged.

  Validation is structural only.  Provider semantic limits stay with the
  provider; the adapter never guesses that a model supports a field."
  [snapshot]
  (check-keys! snapshot top-level-keys [])
  (when (contains? snapshot :role)
    (when-not (or (keyword? (:role snapshot))
                  (string? (:role snapshot))
                  (nil? (:role snapshot)))
      (invalid! :invalid-role "role must be a keyword or string"
                {:path [:role]})))
  (doseq [k [:provider :connection :model]]
    (when (contains? snapshot k)
      (when-not (or (nil? (get snapshot k))
                    (keyword? (get snapshot k))
                    (string? (get snapshot k)))
        (invalid! :invalid-identifier
                  "provider, connection, and model must be keywords or strings"
                  {:path [k] :value (get snapshot k)}))))
  (when-not (sequential? (:messages snapshot))
    (invalid! :messages-not-sequential
              "snapshot messages must be a sequential collection"
              {:path [:messages]}))
  (when (empty? (:messages snapshot))
    (invalid! :messages-empty
              "snapshot needs at least one message"
              {:path [:messages]}))
  (doseq [[i message] (map-indexed vector (:messages snapshot))]
    (validate-message message i))
  (when (contains? snapshot :tools)
    (when-not (sequential? (:tools snapshot))
      (invalid! :tools-not-sequential
                "snapshot tools must be a sequential collection"
                {:path [:tools]}))
    (doseq [[i tool] (map-indexed vector (:tools snapshot))]
      (validate-tool tool i))
    (let [names (map :name (:tools snapshot))]
      (when-not (= (count names) (count (distinct names)))
        (invalid! :duplicate-tool-name
                  "tool names must be unique within one request"
                  {:tools (vec (sort-by str names))}))))
  (when (contains? snapshot :parameters)
    (check-keys! (:parameters snapshot) parameter-keys [:parameters]))
  (let [parameters (:parameters snapshot)]
    (doseq [k [:temperature :top-p :frequency-penalty :presence-penalty]]
      (when (contains? parameters k)
        (number! parameters k [:parameters])))
    (doseq [k [:top-k :max-output-tokens]]
      (when (contains? parameters k)
        (non-negative-int! parameters k [:parameters])))
    (when (contains? parameters :stop)
      (when-not (and (sequential? (:stop parameters))
                     (every? string? (:stop parameters)))
        (invalid! :invalid-stop-sequences
                  "stop sequences must be a sequence of strings"
                  {:path [:parameters :stop]}))))
  (doseq [k [:temperature :top-p :frequency-penalty :presence-penalty]]
    (when (contains? snapshot k)
      (number! snapshot k [])))
  (doseq [k [:top-k :max-tokens :max-output-tokens]]
    (when (contains? snapshot k)
      (non-negative-int! snapshot k [])))
  (when (contains? snapshot :stop)
    (when-not (and (sequential? (:stop snapshot))
                   (every? string? (:stop snapshot)))
      (invalid! :invalid-stop-sequences
                "stop sequences must be a sequence of strings"
                {:path [:stop]})))
  (when (contains? snapshot :stream?)
    (boolean! snapshot :stream? []))
  (when (contains? snapshot :tool-choice)
    (let [choice (cond
                   (keyword? (:tool-choice snapshot)) (:tool-choice snapshot)
                   (string? (:tool-choice snapshot)) (keyword (:tool-choice snapshot))
                   :else (:tool-choice snapshot))]
      (when-not (contains? #{:auto :required :none} choice)
        (invalid! :invalid-tool-choice
                  "tool-choice must be :auto, :required, or :none"
                  {:path [:tool-choice] :value choice}))
      (when (and (= :required choice) (empty? (:tools snapshot)))
        (invalid! :tool-choice-without-tools
                  "tool-choice :required needs at least one tool"
                  {:path [:tool-choice] :value choice}))))
  (when (contains? snapshot :response-format)
    (let [format (:response-format snapshot)
          format-type (when (map? format)
                        (cond
                          (keyword? (:type format)) (:type format)
                          (string? (:type format)) (keyword (:type format))
                          :else (:type format)))]
      (when-not (or (contains? #{:text :json} (cond-> format
                                                (string? format) keyword))
                    (and (map? format)
                         (= :json-schema format-type)
                         (string? (:name format))
                         (map? (:schema format))))
        (invalid! :invalid-response-format
                  "response-format must be :text, :json, or a JSON-schema map"
                  {:path [:response-format] :value format}))))
  (when (contains? snapshot :required-capabilities)
    (when-not (and (sequential? (:required-capabilities snapshot))
                   (every? keyword? (:required-capabilities snapshot)))
      (invalid! :invalid-required-capabilities
                "required-capabilities must be a sequence of keywords"
                {:path [:required-capabilities]})))
  snapshot)

(defn required-capabilities
  "Capabilities the snapshot actually depends on.

  Explicit declarations are unioned with structural requirements: tools need
  `:tools`, and a JSON-schema response format needs `:json-schema`."
  [snapshot]
  (let [explicit (set (:required-capabilities snapshot))
        tools (when (seq (:tools snapshot)) #{:tools})
        response-format (:response-format snapshot)
        format-type (when (map? response-format)
                      (cond
                        (keyword? (:type response-format)) (:type response-format)
                        (string? (:type response-format)) (keyword (:type response-format))
                        :else (:type response-format)))
        json-schema (when (and (map? response-format)
                               (= :json-schema format-type))
                      #{:json-schema})]
    (set/union explicit (or tools #{}) (or json-schema #{})
               (attachment-capabilities snapshot))))

(defn- parse-arguments-string
  [arguments]
  (cond
    (nil? arguments) {}
    (map? arguments) arguments
    (string? arguments) (if (str/blank? arguments)
                          {}
                          (read-arguments arguments :keyword))
    :else (invalid! :invalid-tool-arguments
                    "tool arguments must be a map or string"
                    {:arguments arguments})))

(defn- ->tool-request
  [call path pair-hint?]
  (validate-tool-call call path pair-hint?)
  (let [arguments (parse-arguments-string (:arguments call))]
    (-> (ToolExecutionRequest/builder)
        (cond-> (some? (:id call)) (.id (:id call)))
        (.name (:name call))
        (.arguments (json/encode arguments))
        (.build))))

(defn- byte-array?
  [value]
  (instance? (Class/forName "[B") value))

(defn- encode-base64
  [^bytes data]
  (.encodeToString (Base64/getEncoder) data))

(defn- load-attachment
  [loader attachment path]
  (when-not (fn? loader)
    (invalid! :attachment-loader-missing
              "native attachment needs a configured attachment-loader"
              {:path path
               :attachment-id (or (:attachment-id attachment) (:id attachment))
               :code :ATTACHMENT_LOADER_MISSING}))
  (try
    (let [loaded (loader attachment)]
      (cond
        (and (map? loaded)
             (string? (:base64 loaded))
             (not (str/blank? (:base64 loaded))))
        {:base64 (:base64 loaded)
         :mime (or (:mime loaded) (attachment-mime attachment))}

        (and (map? loaded) (byte-array? (:bytes loaded)))
        {:base64 (encode-base64 (:bytes loaded))
         :mime (or (:mime loaded) (attachment-mime attachment))}

        (and (map? loaded)
             (string? (:url loaded))
             (not (str/blank? (:url loaded))))
        {:url (:url loaded)
         :mime (or (:mime loaded) (attachment-mime attachment))}

        :else
        (invalid! :attachment-load-failed
                  "attachment-loader must return base64, bytes, or url"
                  {:path path
                   :attachment-id (or (:attachment-id attachment)
                                      (:id attachment))
                   :code :ATTACHMENT_LOAD_FAILED})))
    (catch clojure.lang.ExceptionInfo e
      (if (= :invalid-snapshot (:error/kind (ex-data e)))
        (throw e)
        (invalid! :attachment-load-failed
                  "attachment bytes could not be loaded"
                  {:path path
                   :attachment-id (or (:attachment-id attachment)
                                      (:id attachment))
                   :code :ATTACHMENT_LOAD_FAILED
                   :detail (ex-data e)})))
    (catch Throwable t
      (invalid! :attachment-load-failed
                "attachment bytes could not be loaded"
                {:path path
                 :attachment-id (or (:attachment-id attachment)
                                    (:id attachment))
                 :code :ATTACHMENT_LOAD_FAILED
                 :detail (.getMessage t)}))))

(defn- native-content
  [attachment loader path]
  (let [kind (:kind attachment)
        mime (:send-mime attachment)
        loaded (load-attachment loader attachment path)
        mime (or (:mime loaded) mime)
        url (:url loaded)
        base64 (:base64 loaded)]
    (case kind
      :image (if url
               (ImageContent/from (URI/create url))
               (ImageContent/from base64 mime))
      :pdf (if url
             (PdfFileContent/from (URI/create url))
             (PdfFileContent/from base64 mime))
      :audio (if url
               (AudioContent/from (URI/create url))
               (AudioContent/from base64 mime))
      :video (if url
               (VideoContent/from (URI/create url))
               (VideoContent/from base64 mime))
      (invalid! :attachment-kind-unsupported
                "native attachment kind is not supported"
                {:path path :kind kind
                 :code :ATTACHMENT_NOT_SENDABLE}))))

(defn- attachment-content
  [attachment loader path]
  (case (:send-route attachment)
    :text (TextContent/from
           (str "\n\n[Attachment: "
                (or (:name attachment)
                    (:attachment-id attachment)
                    (:id attachment)
                    "attachment")
                "]\n"
                (:send-text attachment)))
    :native (native-content attachment loader path)
    (invalid! :attachment-unresolved
              "attachment has no prepared send route"
              {:path path :code :ATTACHMENT_UNRESOLVED})))

(defn- context-text
  [message]
  (str (or (:text message) "")
       (when-let [quotes (seq (:quotes message))]
         (str "\n\n"
              (str/join "\n\n"
                        (map (fn [quote]
                               (str "[Quoted message]\n" (:text quote)))
                             quotes))))
       (when-let [citations (seq (:citations message))]
         (str "\n\n[Citations]\n"
              (str/join "\n"
                        (map (fn [citation]
                               (or (:text citation)
                                   (:label citation)
                                   (:url citation)
                                   (pr-str citation)))
                             citations))))))

(defn- ->user-message
  [message loader path]
  (let [text (context-text message)
        attachments (:attachments message)
        contents (vec
                  (concat
                   (when-not (str/blank? text)
                     [(TextContent/from text)])
                   (map-indexed
                    (fn [index attachment]
                      (attachment-content
                       attachment loader
                       (conj path :attachments index)))
                    attachments)))]
    (if (seq contents)
      (UserMessage/from (vec contents))
      (UserMessage/from (or text "")))))

(defn- ->chat-message
  [message index loader]
  (let [message (validate-message message index)
        path [:messages index]]
    (case (:role message)
      :system (SystemMessage/from (:text message))
      :user (->user-message message loader path)
      :assistant (let [builder (AiMessage/builder)
                       builder (cond-> builder
                                 (some? (:text message))
                                 (.text (:text message))

                                 (some? (:thinking message))
                                 (.thinking (:thinking message))

                                 (seq (:tool-calls message))
                                 (.toolExecutionRequests
                                  (mapv #(->tool-request
                                          % path (pair-hint-message? message))
                                        (:tool-calls message))))]
                   (.build builder))
      :tool (let [builder (ToolExecutionResultMessage/builder)]
              (-> builder
                  (cond-> (some? (:tool-call-id message))
                    (.id (:tool-call-id message)))
                  (.toolName (:name message))
                  (.text (:text message))
                  (cond-> (contains? message :error?)
                    (.isError (:error? message)))
                  (.build))))))

(defn- ->tool-specification
  [tool index]
  (let [tool (validate-tool tool index)
        spec (cond-> {:name (:name tool)}
               (some? (:description tool))
               (assoc :description (:description tool))

               (some? (:parameters tool))
               (assoc :parameters (:parameters tool))

               (contains? tool :strict?)
               (assoc :strict (:strict? tool))

               (seq (:metadata tool))
               (assoc :metadata (:metadata tool)))]
    (try
      (ToolSpecification/fromJson (json/encode spec))
      (catch Throwable t
        (invalid! :invalid-tool-specification
                  "tool specification could not be compiled"
                  {:path [:tools index]
                   :message (some-> t .getMessage)})))))

(defn- ->tool-choice
  [choice]
  (let [choice (if (string? choice) (keyword choice) choice)]
    (case choice
      :auto ToolChoice/AUTO
      :required ToolChoice/REQUIRED
      :none ToolChoice/NONE
      (invalid! :invalid-tool-choice
                "tool-choice must be :auto, :required, or :none"
                {:value choice}))))

(defn- ->json-schema-element
  [schema path]
  (try
    (clojure.lang.Reflector/invokeStaticMethod
     "dev.langchain4j.internal.JsonSchemaElementJsonUtils"
     "fromMap"
     (object-array [(json/normalize-keys schema)]))
    (catch Throwable t
      (invalid! :invalid-json-schema
                "response schema could not be compiled"
                {:path path
                 :message (some-> t .getMessage)}))))

(defn- ->response-format
  [format]
  (cond
    (nil? format) nil
    (or (= :text format) (= "text" format)) ResponseFormat/TEXT
    (or (= :json format) (= "json" format)) ResponseFormat/JSON
    (map? format)
    (let [element (->json-schema-element (:schema format) [:response-format :schema])]
      (when-not (instance? JsonObjectSchema element)
        (invalid! :response-schema-not-object
                  "response schema root must be a JSON object schema"
                  {:path [:response-format :schema]}))
      (-> (ResponseFormat/builder)
          (.type dev.langchain4j.model.chat.request.ResponseFormatType/JSON)
          (.jsonSchema (-> (JsonSchema/builder)
                           (.name (:name format))
                           (.rootElement element)
                           (.build)))
          (.build)))
    :else
    (invalid! :invalid-response-format
              "response-format must be :text, :json, or a JSON-schema map"
              {:value format})))

(defn- parameter
  [snapshot k]
  (or (get-in snapshot [:parameters k]) (get snapshot k)))

(defn- max-output-tokens
  [snapshot]
  (let [a (:max-output-tokens snapshot)
        b (:max-tokens snapshot)
        c (get-in snapshot [:parameters :max-output-tokens])]
    (when (and (some? a) (some? b) (not= a b))
      (invalid! :conflicting-max-tokens
                "max-tokens and max-output-tokens disagree"
                {:max-tokens b :max-output-tokens a}))
    (or c a b)))

(defn- id-str
  [value]
  (cond
    (string? value) value
    (keyword? value) (name value)
    (symbol? value) (name value)
    :else nil))

(defn compile-request
  "Compile a validated snapshot into a LangChain4j ChatRequest.

  The profile's `:model-name` is the provider wire name.  A snapshot `:model`
  selects the profile in the adapter; it is used as a fallback only for a
  profile that does not declare a wire name.  Compilation never calls a
  provider and never mutates the snapshot."
  ([snapshot profile] (compile-request snapshot profile {}))
  ([snapshot profile {:keys [attachment-loader]}]
   (validate-snapshot snapshot)
   (let [model-name (or (:model-name profile)
                        (id-str (:model snapshot))
                        (:model-id profile))
         _ (when-not (and (string? model-name) (not (str/blank? model-name)))
             (invalid! :model-name-missing
                       "the snapshot or provider profile must name a model"
                       {}))
         builder (ChatRequest/builder)
         builder (.messages builder (mapv #(->chat-message %1 %2 attachment-loader)
                                          (:messages snapshot)
                                          (range)))
         builder (cond-> builder
                  (seq (:tools snapshot))
                  (.toolSpecifications (mapv ->tool-specification
                                             (:tools snapshot)
                                             (range)))

                  (some? (:tool-choice snapshot))
                  (.toolChoice (->tool-choice (:tool-choice snapshot)))

                  (some? (:response-format snapshot))
                  (.responseFormat (->response-format (:response-format snapshot)))

                  true
                  (.modelName model-name)

                  (some? (parameter snapshot :temperature))
                  (.temperature (double (parameter snapshot :temperature)))

                  (some? (parameter snapshot :top-p))
                  (.topP (double (parameter snapshot :top-p)))

                  (some? (parameter snapshot :top-k))
                  (.topK (int (parameter snapshot :top-k)))

                  (some? (max-output-tokens snapshot))
                  (.maxOutputTokens (int (max-output-tokens snapshot)))

                  (seq (parameter snapshot :stop))
                  (.stopSequences (vec (parameter snapshot :stop)))

                  (some? (parameter snapshot :frequency-penalty))
                  (.frequencyPenalty (double (parameter snapshot :frequency-penalty)))

                   (some? (parameter snapshot :presence-penalty))
                   (.presencePenalty (double (parameter snapshot :presence-penalty))))]
     (.build builder))))

(defn parse-arguments
  "Build the parser used for streamed and final tool arguments.

  `mode` is `:json` (liberal JSON/EDN/UFSS input), `:ufss`, `:auto`, `:edn`
  (strict EDN+UFSS without JSON conveniences), or a function from string to
  map.  String keys are normalized by `json-key-mode` at every nesting level."
  ([mode] (parse-arguments mode :keyword))
  ([mode json-key-mode]
   (cond
     (contains? #{:json :ufss :auto} mode)
     (fn [text] (read-arguments text json-key-mode))

     (= :edn mode)
     (fn [text] (read-edn-arguments text json-key-mode))

     (fn? mode) mode
     :else (throw (ex-info "argument parser must be :json, :ufss, :auto, :edn, or a function"
                           {:error/kind :invalid-argument-parser
                            :mode mode})))))
