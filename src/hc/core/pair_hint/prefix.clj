(ns hc.core.pair-hint.prefix
  "Positive semantic whitelist, per-message digests, and same-epoch prefix law.

  The chain stores only digests and a root, never a request copy.  Identity is
  defined by what a message means: its role and the type plus semantic fields
  of each content block.  Provider bookkeeping such as usage counters,
  streaming placeholders, and transport indexes is ignored because it is not
  named by the whitelist at all."
  (:require [clojure.string :as str]
            [hc.core.pair-hint.canon :as canon]))

(def allowed-boundary-kinds
  "Every reason the specification allows a new prefix boundary.

  This list is the section 6.1 list verbatim.  A convenience boundary such
  as an assistant rewrite would turn an unauthorized state change into a
  legitimate-looking new epoch, so it is not named here."
  #{:relay :scope-reset :scope-remove :compaction :board-clean-break})

(defn- fetch
  [m keys]
  (some (fn [k] (when (contains? m k) (get m k))) keys))

(defn- as-keyword
  [x]
  (cond
    (keyword? x) x
    (and (string? x) (not (str/blank? x))) (keyword (str/lower-case x))
    (symbol? x) (keyword (str x))
    :else nil))

(defn normalize-role
  "Message role as a keyword.  Missing or empty roles are malformed."
  [role]
  (let [role (as-keyword role)]
    (when-not role
      (throw (ex-info "pair-hint: message role is required"
                      {:error :PAIR_HINT_MESSAGE_INVALID
                       :field :role})))
    role))

(defn- normalize-type
  [t]
  (let [t (as-keyword t)]
    (case t
      (:tool_use :tool-use :tool_call) :tool-call
      (:tool_result :tool-result) :tool-result
      (:reasoning :reasoning-text) :thinking
      (:input_text :output_text) :text
      t)))

(defn- require-field
  [block field]
  (when-not (contains? block field)
    (throw (ex-info (str "pair-hint: content block missing " field)
                    {:error :PAIR_HINT_CONTENT_INVALID
                     :field field
                     :block-type (:type block)}))))

(declare normalize-content)

(defn- text-identity
  [block]
  (require-field block :text)
  (when (nil? (:text block))
    (throw (ex-info "pair-hint: text block has no text"
                    {:error :PAIR_HINT_CONTENT_INVALID
                     :field :text
                     :block-type :text})))
  {:type :text
   :text (:text block)})

(defn- tool-call-identity
  [block]
  (let [id (fetch block [:tool-call-id :call-id :id])
        name (fetch block [:tool-name :name])
        args (fetch block [:arguments :args :input])]
    (when (nil? id)
      (throw (ex-info "pair-hint: tool-call block missing tool-call-id"
                      {:error :PAIR_HINT_CONTENT_INVALID
                       :field :tool-call-id
                       :block-type :tool-call})))
    {:type :tool-call
     :tool-call-id id
     :tool-name name
     :arguments args}))

(defn- tool-result-identity
  [block]
  (let [id (fetch block [:tool-call-id :call-id :id])
        name (fetch block [:tool-name :name])
        content (fetch block [:content :result :output])]
    (when (nil? id)
      (throw (ex-info "pair-hint: tool-result block missing tool-call-id"
                      {:error :PAIR_HINT_CONTENT_INVALID
                       :field :tool-call-id
                       :block-type :tool-result})))
    {:type :tool-result
     :tool-call-id id
     :tool-name name
     :content (normalize-content content)
     :is-error (boolean (fetch block [:is-error :is_error :error?]))}))

(defn- thinking-identity
  [block]
  (let [text (fetch block [:text :thinking :reasoning])]
    (when (nil? text)
      (throw (ex-info "pair-hint: thinking block missing text"
                      {:error :PAIR_HINT_CONTENT_INVALID
                       :field :text
                       :block-type :thinking})))
    (cond-> {:type :thinking :text text}
      (contains? block :signature) (assoc :signature (:signature block)))))

(defn- binary-digest
  [block]
  (or (fetch block [:digest :sha256])
      (some-> (fetch block [:data :bytes :base64]) canon/digest)))

(defn- image-identity
  [block]
  {:type :image
   :media-type (fetch block [:media-type :media_type :mime-type :mime_type])
   :digest (binary-digest block)
   :url (fetch block [:url :uri])})

(defn- file-identity
  [block]
  {:type :file
   :media-type (fetch block [:media-type :media_type :mime-type :mime_type])
   :filename (fetch block [:filename :file-name :name])
   :digest (binary-digest block)
   :url (fetch block [:url :uri])})

(defn- data-identity
  [block]
  {:type :data
   :data (fetch block [:data :json :value])})

(defn block-identity
  "Semantic identity of one content block.

  Unknown block types fail closed.  A silent fallback would let volatile
  provider fields enter the digest and produce false `:PREFIX_DRIFT` reports."
  [block]
  (when-not (map? block)
    (throw (ex-info "pair-hint: content block must be a map"
                    {:error :PAIR_HINT_CONTENT_INVALID
                     :value block})))
  (let [t (normalize-type (fetch block [:type :kind]))]
    (case t
      :text (text-identity block)
      :tool-call (tool-call-identity block)
      :tool-result (tool-result-identity block)
      :thinking (thinking-identity block)
      :image (image-identity block)
      (:file :document) (file-identity block)
      (:data :json) (data-identity block)
      (throw (ex-info "pair-hint: unsupported content block type"
                      {:error :PAIR_HINT_CONTENT_TYPE_UNSUPPORTED
                       :block-type t})))))

(defn normalize-content
  "Normalize a message body to a vector of semantic block identities."
  [content]
  (cond
    (nil? content) []
    (string? content) [{:type :text :text content}]
    (map? content) [(block-identity content)]
    (sequential? content) (mapv block-identity content)
    :else (throw (ex-info "pair-hint: content must be text, a block, or a block vector"
                          {:error :PAIR_HINT_CONTENT_INVALID
                           :value content}))))

(defn- flat-tool-call-identity
  "Normalize the flat app tool-call shape `{:id :name :arguments}`."
  [call]
  (when-not (map? call)
    (throw (ex-info "pair-hint: tool call must be a map"
                    {:error :PAIR_HINT_CONTENT_INVALID
                     :value call})))
  {:type :tool-call
   :tool-call-id (fetch call [:id :tool-call-id :call-id])
   :tool-name (fetch call [:name :tool-name])
   :arguments (fetch call [:arguments :args :input])})

(defn- flat-tool-result-identity
  "Normalize the flat app tool-result message."
  [message]
  (let [content (if (contains? message :content)
                  (:content message)
                  (fetch message [:text :result :output]))]
    {:type :tool-result
     :tool-call-id (fetch message [:tool-call-id :call-id :id])
     :tool-name (fetch message [:tool-name :name])
     :content (normalize-content content)
     :is-error (boolean (fetch message [:is-error :error?]))}))

(defn- assistant-flat-blocks
  "Semantic blocks for the flat app assistant shape.

  The order is fixed as text, thinking, tool-calls.  Flat messages carry no
  intrinsic block order; a fixed order keeps digests reproducible while still
  ignoring every envelope field the whitelist does not name."
  [message]
  (let [text (when (some? (:text message))
               [{:type :text :text (:text message)}])
        thinking (when (some? (:thinking message))
                   [{:type :thinking :text (:thinking message)}])
        tool-calls (when (contains? message :tool-calls)
                     (mapv flat-tool-call-identity (:tool-calls message)))]
    (vec (concat text thinking tool-calls))))

(defn- quoted-texts
  "Semantic text of each quoted message, in order.

  The provider request builder renders only quote text into the outbound
  message; quote ids, source message ids, and selection offsets are client
  bookkeeping.  Digesting the whole quote map let a re-created quote change an
  earlier message and forced a false `:PREFIX_DRIFT`."
  [quotes]
  (when (sequential? quotes)
    (mapv (fn [quote]
            (if (map? quote)
              (or (:text quote) (:content quote) "")
              quote))
          quotes)))

(defn- rendered-citations
  "Semantic citation identity: exactly the fields the provider receives.

  `context-text` in `hc.server.model-adapter.request` renders each citation as
  `:text`, else `:label`, else `:url`, else the whole map via `pr-str`.  The
  `pr-str` tail is part of what the provider prints, so omitting it would give
  a citation carrying none of those three fields a different identity than the
  text the model actually reads.  Rendered values are kept in order so a
  citation renumbered by the client cannot move an earlier message, while a
  genuine change of displayed source stays visible to the prefix law."
  [citations]
  (when (sequential? citations)
    (mapv (fn [citation]
            (when (map? citation)
              (or (:text citation)
                  (:label citation)
                  (:url citation)
                  (pr-str citation))))
          citations)))

(defn- attachment-identity
  "Semantic identity of one uploaded attachment.

  The provider receives the attachment bytes and the client's attachment id.
  Display fields such as a filename, status, or preview URL are not identity;
  the durable pair is the id and the content digest, with the media type kept
  so two different byte streams that share an id still differ.  An attachment
  with neither an id nor a digest cannot be compared exactly and fails closed."
  [attachment]
  (when-not (map? attachment)
    (throw (ex-info "pair-hint: attachment must be a map"
                    {:error :PAIR_HINT_CONTENT_INVALID
                     :field :attachments
                     :value attachment})))
  (let [id (fetch attachment [:attachment-id :id])
        digest (fetch attachment [:digest :sha256])
        media (fetch attachment [:media :mime :media-type :mime-type])]
    (when (and (nil? id) (nil? digest))
      (throw (ex-info "pair-hint: attachment needs an id or a digest"
                      {:error :PAIR_HINT_CONTENT_INVALID
                       :field :attachments
                       :value attachment})))
    {:type :attachment
     :attachment-id id
     :digest digest
     :media media}))

(defn- user-flat-blocks
  "Semantic blocks for the flat app user shape.

  The app conversation carries `:turn-card-ids`, `:instruction-values`,
  `:draft`, and other turn bookkeeping next to `:text`.  Those fields reach
  the provider only through the material-injection step, which re-reads them
  from the newest user message on every request; they never change what an
  older user message means.  Only text plus the rendered quote and citation
  text is identity.  Attachment identity stays exact: attachment ids,
  content digests, and media type are the durable identity of uploaded bytes.
  A changed attachment therefore moves the digest even when the visible text
  is unchanged."
  [message]
  (let [text (when (some? (:text message))
               [{:type :text :text (:text message)}])
        quotes (quoted-texts (:quotes message))
        citations (rendered-citations (:citations message))
        attachments (when (seq (:attachments message))
                      (mapv attachment-identity (:attachments message)))]
    (vec (concat text
                 (when (seq quotes)
                   [{:type :quotes :quotes quotes}])
                 (when (seq citations)
                   [{:type :citations :citations citations}])
                 (when (seq attachments)
                   [{:type :attachments :attachments attachments}])))))

(defn message-identity
  "Positive semantic projection of one message.

  Both the block shape (`:content` with typed blocks) and the flat app shape
  (`:text`/`:thinking`/`:tool-calls`, and `:tool` messages with
  `:tool-call-id`/`:name`/`:text`) normalize to the same semantic blocks.
  Flat user messages project text, quotes, citations, and attachments; turn
  bookkeeping they also carry (`:turn-card-ids`, `:instruction-values`,
  `:draft`) does not reach the provider as message content, so it is not
  identity.  Usage, timestamps, streaming flags, indexes, and other envelope
  fields are not named here, so they cannot move the prefix chain."
  [message]
  (when-not (map? message)
    (throw (ex-info "pair-hint: message must be a map"
                    {:error :PAIR_HINT_MESSAGE_INVALID
                     :value message})))
  (let [role (normalize-role (:role message))
        blocks
        (case role
          :tool
          [(flat-tool-result-identity message)]

          :assistant
          (if (contains? message :content)
            (let [content (normalize-content (:content message))]
              (if (contains? message :tool-calls)
                (into content (mapv flat-tool-call-identity (:tool-calls message)))
                content))
            (assistant-flat-blocks message))

          :user
          (if (contains? message :content)
            (normalize-content (:content message))
            (user-flat-blocks message))

          (if (contains? message :content)
            (normalize-content (:content message))
            (if (some? (:text message))
              [{:type :text :text (:text message)}]
              [])))]
    {:role role :blocks blocks}))

(defn message-digest
  "SHA-256 hex digest of the semantic message identity."
  [message]
  (canon/digest (message-identity message)))

(defn normalize-boundary
  "Validate and normalize an explicit prefix boundary.

  Boundaries are data, not authority.  This function checks only that the
  kind is one the specification allows; the caller owns the durable fact that
  justifies the boundary."
  [boundary]
  (when (some? boundary)
    (when-not (map? boundary)
      (throw (ex-info "pair-hint: boundary must be a map or nil"
                      {:error :PAIR_HINT_BOUNDARY_INVALID
                       :value boundary})))
    (let [kind (as-keyword (:kind boundary))]
      (when-not (contains? allowed-boundary-kinds kind)
        (throw (ex-info "pair-hint: boundary kind is not allowed"
                        {:error :PAIR_HINT_BOUNDARY_INVALID
                         :kind (:kind boundary)
                         :allowed allowed-boundary-kinds})))
      (when (and (contains? #{:relay :compaction} kind)
                 (or (nil? (:id boundary))
                     (str/blank? (str (:id boundary)))))
        (throw (ex-info "pair-hint: this boundary needs a durable id"
                        {:error :PAIR_HINT_BOUNDARY_INVALID
                         :kind kind
                         :value boundary})))
      (cond-> {:kind kind}
        (contains? boundary :id) (assoc :id (:id boundary))
        (contains? boundary :run-id) (assoc :run-id (:run-id boundary))
        (contains? boundary :board-version) (assoc :board-version (:board-version boundary))
        (contains? boundary :call-id) (assoc :call-id (:call-id boundary))
        (contains? boundary :incumbency-id) (assoc :incumbency-id (:incumbency-id boundary))
        ;; A Scope boundary carries the raw Scope fact so the Owner cursor can
        ;; be advanced without inventing a second representation.  A merged
        ;; boundary keeps the pending fact it superseded; dropping either
        ;; would make a durable fact disappear from the chain root.
        (contains? boundary :raw) (assoc :raw (:raw boundary))
        (contains? boundary :supersedes) (assoc :supersedes (:supersedes boundary))))))

(defn chain-of
  "Build a digest chain for `messages`, optionally rooted at `boundary`."
  ([messages] (chain-of messages nil))
  ([messages boundary]
   (when-not (sequential? messages)
     (throw (ex-info "pair-hint: messages must be sequential"
                     {:error :PAIR_HINT_MESSAGES_INVALID
                      :value messages})))
   (let [boundary (normalize-boundary boundary)
         digests (mapv message-digest messages)
         root (canon/digest {:boundary boundary :digests digests})]
     {:boundary boundary
      :digests digests
      :root root
      :count (count digests)})))

(defn verify-prefix
  "Recompute the chain for `messages` and compare it with `prior`.

  Returns `{:ok? true :chain next}` when `prior` is an exact semantic prefix.
  Otherwise returns `{:ok? false :error {...}}`; the first differing index is
  reported so the caller can identify the changed message without storing it."
  [prior messages]
  (let [prior-digests (:digests prior)
        expected-root (canon/digest {:boundary (:boundary prior)
                                     :digests prior-digests})]
    (if (or (not= (:count prior) (count prior-digests))
            (not= (:root prior) expected-root))
      {:ok? false
       :error {:code :PAIR_HINT_CHAIN_INVALID
               :expected-root expected-root
               :actual-root (:root prior)
               :expected-count (count prior-digests)
               :actual-count (:count prior)}}
      (let [normalized-boundary
            (try
              (normalize-boundary (:boundary prior))
              (catch clojure.lang.ExceptionInfo exception
                ::invalid-boundary))]
        (if (= ::invalid-boundary normalized-boundary)
          {:ok? false
           :error {:code :PAIR_HINT_CHAIN_INVALID
                   :reason :invalid-boundary
                   :boundary (:boundary prior)}}
          (let [next-chain (chain-of messages normalized-boundary)
                prior-count (:count prior)
                next-count (:count next-chain)]
            (cond
              (not= normalized-boundary (:boundary next-chain))
              {:ok? false
               :error {:code :PAIR_HINT_BOUNDARY_MISMATCH
                       :prior normalized-boundary
                       :next (:boundary next-chain)}}

              (< next-count prior-count)
              {:ok? false
               :error {:code :PREFIX_TRUNCATED
                       :prior-count prior-count
                       :next-count next-count}}

              :else
              (if-let [i (first (keep-indexed
                                 (fn [i prior-digest]
                                   (when (not= prior-digest (get (:digests next-chain) i))
                                     i))
                                 prior-digests))]
                {:ok? false
                 :error {:code :PREFIX_DRIFT
                         :index i
                         :expected (get prior-digests i)
                         :actual (get (:digests next-chain) i)}}
                {:ok? true :chain next-chain}))))))))

(defn verify-chain
  "Alias for `verify-prefix`, kept for callers that speak in chain terms."
  [prior messages]
  (verify-prefix prior messages))

(defn prefix?
  "True when `messages` extend `prior` without changing an earlier message."
  [prior messages]
  (boolean (:ok? (verify-prefix prior messages))))
