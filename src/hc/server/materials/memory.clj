(ns hc.server.materials.memory
  "Long-term memory records, candidate review, scope and archival.

  Authority: root AGENTS.md §5.3 and §7.4 旅程 5.6（记忆）.

  This namespace is a pure state machine. It reads no clock, performs no I/O
  and never decides that a model's prose has become a fact. Automatic
  candidates must carry an explicit source excerpt and an epistemic label;
  only an explicit approval makes a candidate effective.

  A memory record is one of:

    :candidate  awaiting review; never injected
    :active     eligible for injection inside its scope
    :archived   retained for history; never injected
    :rejected   a refused candidate; suppresses an identical re-suggestion
    :deleted    tombstone with no body; prevents backup resurrection

  Scope is explicit. The domain does not infer a scope from prose, and it
  does not merge similar text from different scopes."
  (:refer-clojure :exclude [replace])
  (:require [clojure.string :as str]
            [hc.server.materials.errors :as errors]
            [hc.server.materials.state :as state])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat UUID)))

(def max-body-chars 2000)
(def max-excerpt-chars 2000)

(def categories
  "The four user-visible memory categories. Classification changes management,
  not authorization."
  #{:identity-background :working-style :expression-preference :long-term-requirement})

(def category-order
  [:identity-background :working-style :expression-preference :long-term-requirement])

(def category-label
  {:identity-background "identity/background"
   :working-style "working style"
   :expression-preference "expression preference"
   :long-term-requirement "long-term requirement"})

(def statuses
  #{:candidate :active :archived :rejected :deleted})

(def epistemic-kinds
  "How the record is offered. Assumptions stay visibly unconfirmed."
  #{:fact :preference :requirement :assumption})

(def scope-kinds
  #{:global :assistant :project})

;; ---------------------------------------------------------------------------
;; Identity and validation helpers

(defn- sha256
  ^String [^String value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of)
                (.digest digest (.getBytes value StandardCharsets/UTF_8)))))

(defn body-hash
  "Stable content hash of the trimmed body. Used for duplicate review only;
  it is not a claim that two memories mean the same thing."
  [body]
  (when (string? body)
    (sha256 (str/trim body))))

(defn- exact-keys?
  [m expected]
  (and (map? m)
       (= (set (clojure.core/keys m)) (set expected))))

(defn normalize-scope
  "Return a canonical scope map, or nil when the value is not a valid scope.

  Accepted forms:

    :global
    {:kind :global}
    {:kind :assistant :assistant-id \"a1\"}
    {:kind :project :project-id \"p1\"}

  Unknown keys are rejected so a typo cannot silently widen a memory's scope."
  [scope]
  (cond
    (= :global scope)
    {:kind :global}

    (and (map? scope) (= :global (:kind scope)) (exact-keys? scope [:kind]))
    {:kind :global}

    (and (map? scope)
         (= :assistant (:kind scope))
         (exact-keys? scope [:kind :assistant-id])
         (errors/non-blank-string? (:assistant-id scope)))
    {:kind :assistant :assistant-id (:assistant-id scope)}

    (and (map? scope)
         (= :project (:kind scope))
         (exact-keys? scope [:kind :project-id])
         (errors/non-blank-string? (:project-id scope)))
    {:kind :project :project-id (:project-id scope)}

    :else nil))

(defn valid-scope?
  [scope]
  (some? (normalize-scope scope)))

(defn scope-key
  "A stable comparison key for one scope."
  [scope]
  (when-let [scope (normalize-scope scope)]
    (case (:kind scope)
      :global [:global]
      :assistant [:assistant (:assistant-id scope)]
      :project [:project (:project-id scope)])))

(defn scope-label
  [scope]
  (when-let [scope (normalize-scope scope)]
    (case (:kind scope)
      :global "global"
      :assistant (str "assistant " (:assistant-id scope))
      :project (str "project " (:project-id scope)))))

(defn default-scope-chain
  "The scope order used when the caller does not supply one.

  Assistant-specific records come first, then project records, then global
  records. The chain explains precedence; it does not merge records."
  [{:keys [assistant-id project-id]}]
  (cond-> []
    (errors/non-blank-string? assistant-id)
    (conj {:kind :assistant :assistant-id assistant-id})

    (errors/non-blank-string? project-id)
    (conj {:kind :project :project-id project-id})

    true
    (conj {:kind :global})))

(defn- normalize-scope-chain
  [chain]
  (when (sequential? chain)
    (let [scopes (map normalize-scope chain)]
      (when (every? some? scopes)
        (vec (distinct scopes))))))

(defn scope-rank
  "Position in the effective chain. Lower is more specific."
  [chain scope]
  (let [key (scope-key scope)
        index (first (keep-indexed
                      (fn [i candidate]
                        (when (= key (scope-key candidate)) i))
                      chain))]
    (or index (count chain))))

;; ---------------------------------------------------------------------------
;; Sensitive-content scan

(def ^:private sensitive-patterns
  [[:private-key #"-----BEGIN [A-Z ]*PRIVATE KEY-----"]
   [:credential #"(?i)\b(?:password|passwd|pwd|api[_-]?key|secret|client[_-]?secret|access[_-]?token|refresh[_-]?token|auth[_-]?token)\s*[:=]\s*\S+"]
   [:bearer-token #"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{16,}"]
   [:known-key #"\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}\b"]])

(def ^:private digit-run-pattern #"\d[\d -]{11,25}\d")

(defn- luhn-valid?
  [^String digits]
  (let [numbers (mapv #(- (int %) (int \0)) (reverse digits))]
    (zero? (mod (reduce +
                        (map-indexed
                         (fn [index digit]
                           (if (odd? index)
                             (let [doubled (* 2 digit)]
                               (if (> doubled 9) (- doubled 9) doubled))
                             digit))
                         numbers))
                10))))

(defn- financial-account?
  [body]
  (boolean
   (some (fn [^String match]
           (let [digits (str/replace match #"[ -]" "")]
             (and (<= 13 (count digits) 19)
                  (every? #(Character/isDigit ^char %) digits)
                  (luhn-valid? digits))))
         (re-seq digit-run-pattern body))))

(defn sensitive-kinds
  "Conservative secret detector for automatic review.

  It reports the kind of match, never the matched value. A false positive is
  cheaper than silently storing a credential in a model-visible memory."
  [body]
  (if-not (string? body)
    []
    (cond-> (into []
                  (keep (fn [[kind pattern]]
                          (when (re-find pattern body) kind)))
                  sensitive-patterns)
      (financial-account? body) (conj :financial-account))))

;; ---------------------------------------------------------------------------
;; Field normalization

(defn- invalid-field
  [code message details]
  {:ok? false
   :error (errors/error code message details)})

(defn- validate-source
  [source]
  (cond
    (not (map? source))
    (invalid-field :MEMORY_SOURCE_INVALID
                   "automatic candidates need a source map"
                   {:source source})

    (not (errors/non-blank-string? (:session-id source)))
    (invalid-field :MEMORY_SOURCE_INVALID
                   "automatic candidates need a source session-id"
                   {:source source})

    (not (vector? (:message-ids source)))
    (invalid-field :MEMORY_SOURCE_INVALID
                   "source message-ids must be a vector"
                   {:source source})

    (some (complement errors/non-blank-string?) (:message-ids source))
    (invalid-field :MEMORY_SOURCE_INVALID
                   "source message-ids must be non-blank strings"
                   {:source source})

    (not (errors/non-blank-string? (:excerpt source)))
    (invalid-field :MEMORY_SOURCE_INVALID
                   "automatic candidates need a source excerpt"
                   {:source source})

    (> (errors/unicode-length (:excerpt source)) max-excerpt-chars)
    (invalid-field :MEMORY_SOURCE_INVALID
                   "source excerpt is too long"
                   {:length (errors/unicode-length (:excerpt source))
                    :limit max-excerpt-chars})

    :else
    {:ok? true
     :source {:session-id (:session-id source)
              :message-ids (vec (:message-ids source))
              :excerpt (:excerpt source)}}))

(defn- validate-fields
  "Validate the fields shared by creation, approval and replacement.

  Options:

    :require-source?     automatic candidates must name their conversation
    :require-epistemic?  automatic candidates must label the claim

  Returns {:ok? true :fields {...}} or {:ok? false :error ...}."
  [request {:keys [require-source? require-epistemic?]}]
  (let [body (errors/trimmed-body (:body request))
        body-error (errors/invalid-body body max-body-chars)
        category (:category request)
        scope (normalize-scope (:scope request))
        epistemic-present? (contains? request :epistemic)
        epistemic (or (:epistemic request) :fact)
        always? (get request :always? false)]
    (cond
      body-error
      {:ok? false :error body-error}

      (not (contains? categories category))
      (invalid-field :MEMORY_CATEGORY_INVALID
                     "memory category is not one of the four known categories"
                     {:category category
                      :allowed (vec category-order)})

      (nil? scope)
      (invalid-field :MEMORY_SCOPE_INVALID
                     "memory scope must be global, assistant or project"
                     {:scope (:scope request)})

      (and require-epistemic?
           (not epistemic-present?))
      (invalid-field :MEMORY_EPISTEMIC_INVALID
                     "automatic candidates need an explicit epistemic label"
                     {:epistemic (:epistemic request)
                      :allowed (vec epistemic-kinds)})

      (and require-epistemic?
           (not (contains? epistemic-kinds epistemic)))
      (invalid-field :MEMORY_EPISTEMIC_INVALID
                     "automatic candidate epistemic label is not known"
                     {:epistemic (:epistemic request)
                      :allowed (vec epistemic-kinds)})

      (not (contains? epistemic-kinds epistemic))
      (invalid-field :MEMORY_EPISTEMIC_INVALID
                     "memory epistemic label is not known"
                     {:epistemic epistemic
                      :allowed (vec epistemic-kinds)})

      (not (boolean? always?))
      (invalid-field :MEMORY_ALWAYS_INVALID
                     "always-provide must be a boolean"
                     {:always? always?})

      :else
      (let [source-result (cond
                            require-source?
                            (validate-source (:source request))

                            (some? (:source request))
                            (validate-source (:source request))

                            :else
                            {:ok? true :source nil})]
        (if (:ok? source-result)
          {:ok? true
           :fields {:body body
                    :category category
                    :scope scope
                    :epistemic epistemic
                    :always? always?
                    :source (:source source-result)
                    :sensitive-kinds (sensitive-kinds body)}}
          source-result)))))

(defn- valid-id?
  [id]
  (errors/non-blank-string? id))

(defn- record-id
  [request]
  (or (:id request)
      (str "mem-" (UUID/randomUUID))))

(defn- duplicate-active
  "Find an active record with the same content and scope, excluding SELF-ID."
  [memories body category scope self-id]
  (let [hash (body-hash body)
        key (scope-key scope)]
    (some (fn [[id memory]]
            (when (and (not= id self-id)
                       (= :active (:status memory))
                       (= hash (:body-hash memory))
                       (= category (:category memory))
                       (= key (scope-key (:scope memory))))
              id))
          memories)))

;; ---------------------------------------------------------------------------
;; Creation and review

(defn- create-record
  [state request status source-kind]
  (let [id (record-id request)]
    (if-not (valid-id? id)
      {:ok? false
       :state state
       :error (errors/error :MEMORY_ID_INVALID
                            "memory id must be a non-blank string"
                            {:id id})
       :effects []}
      (if (contains? (:memories state) id)
        {:ok? false
         :state state
         :error (errors/error :MEMORY_ID_TAKEN
                              "memory id already exists"
                              {:id id})
         :effects []}
        (let [validated (validate-fields
                         request
                         {:require-source? (= source-kind :automatic)
                          :require-epistemic? (= source-kind :automatic)})]
          (if-not (:ok? validated)
            {:ok? false
             :state state
             :error (:error validated)
             :effects []}
            (let [{:keys [body category scope epistemic always? source sensitive-kinds]}
                  (:fields validated)
                  [state seq] (state/prepare state)
                  memory (cond-> {:id id
                                  :v 1
                                  :status status
                                  :body body
                                  :body-hash (body-hash body)
                                  :category category
                                  :scope scope
                                  :epistemic epistemic
                                  :always? always?
                                  :assumption? (= :assumption epistemic)
                                  :source source
                                  :source-kind source-kind
                                  :sensitive? (boolean (not-empty sensitive-kinds))
                                  :sensitive-kinds sensitive-kinds
                                  :created-seq seq
                                  :updated-seq seq
                                  :versions []}
                           (true? (:truncated? request))
                           (assoc :truncated? true)
                           (:reason request) (assoc :reason (:reason request))
                           (:conflict-of request) (assoc :conflict-of (:conflict-of request)))]
              {:ok? true
               :state (assoc-in state [:memories id] memory)
               :memory memory
               :effects []})))))))

(defn remember
  "Create an active memory from an explicit user save.

  Manual save does not change either memory switch and is allowed while the
  read switch is off. A temporary session still refuses every write."
  [state request]
  (cond
    (not (map? request))
    (errors/refuse state :MEMORY_REQUEST_INVALID
                   "memory request must be a map" {:request request})

    (:temporary? request)
    (errors/refuse state :MEMORY_TEMPORARY_SESSION
                   "temporary sessions do not create memories"
                   {:action :temporary-session})

    :else
    (create-record state request :active :manual)))

(defn propose
  "Record an automatic candidate for review.

  A candidate is never injected before approval. The caller must supply an
  explicit source excerpt and epistemic label; this package cannot verify
  prose and does not pretend that a model's guess is a fact.

  Reusing the same :id makes an interrupted review retry idempotent. A
  candidate identical to a previously rejected record is suppressed instead
  of being proposed again."
  [state request]
  (cond
    (not (map? request))
    (errors/refuse state :MEMORY_REQUEST_INVALID
                   "memory request must be a map" {:request request})

    (:temporary? request)
    (errors/refuse state :MEMORY_TEMPORARY_SESSION
                   "temporary sessions do not create candidates"
                   {:action :temporary-session})

    (not (state/memory-suggest-enabled? state))
    (errors/refuse state :MEMORY_SUGGEST_DISABLED
                   "automatic memory suggestions are disabled"
                   {:setting :memory-suggest?})

    :else
    (let [id (record-id request)
          existing (get-in state [:memories id])]
      (cond
        (and existing
             (= :candidate (:status existing))
             (= (body-hash (errors/trimmed-body (:body request)))
                (:body-hash existing))
             (= (:category existing) (:category request))
             (= (scope-key (:scope existing))
                (scope-key (normalize-scope (:scope request)))))
        {:ok? true
         :state state
         :memory existing
         :replayed? true
         :effects []}

        existing
        (errors/refuse state :MEMORY_ID_TAKEN
                       "memory id already exists with different content"
                       {:id id})

        :else
        (let [validated (validate-fields
                         request
                         {:require-source? true
                          :require-epistemic? true})]
          (if-not (:ok? validated)
            (errors/refuse state (:code (:error validated))
                           (:message (:error validated))
                           (:details (:error validated)))
            (let [{:keys [body category scope source]} (:fields validated)
                  body-sensitive-kinds (:sensitive-kinds (:fields validated))
                  all-sensitive (vec (distinct (concat body-sensitive-kinds
                                                       (sensitive-kinds (:excerpt source)))))
                  hash (body-hash body)
                  key (scope-key scope)]
              (cond
                (seq all-sensitive)
                (errors/refuse state :MEMORY_SENSITIVE_CONTENT
                               "automatic review filtered sensitive content"
                               {:kinds all-sensitive
                                :action :review-manually})

                (some (fn [[other-id memory]]
                        (when (and (= :rejected (:status memory))
                                   (= hash (:body-hash memory))
                                   (= category (:category memory))
                                   (= key (scope-key (:scope memory))))
                          other-id))
                      (:memories state))
                {:ok? true
                 :state state
                 :suppressed? true
                 :reason :previously-rejected
                 :effects []}

                (duplicate-active (:memories state) body category scope nil)
                {:ok? true
                 :state state
                 :suppressed? true
                 :reason :already-active
                 :memory-id (duplicate-active (:memories state) body category scope nil)
                 :effects []}

                :else
                (create-record state request :candidate :automatic)))))))))

(defn approve
  "Promote a candidate (or a previously rejected record) to active.

  REQUEST may change :body, :category, :scope, :epistemic and :always?. A
  changed body is re-validated and rescanned. Approval of an already active
  record is an idempotent replay."
  [state id request]
  (let [request (or request {})
        memory (get-in state [:memories id])]
    (cond
      (not (valid-id? id))
      (errors/refuse state :MEMORY_ID_INVALID
                     "memory id must be a non-blank string" {:id id})

      (nil? memory)
      (errors/refuse state :MEMORY_NOT_FOUND
                     "memory record does not exist" {:id id})

      (= :active (:status memory))
      {:ok? true :state state :memory memory :replayed? true :effects []}

      (not (#{:candidate :rejected} (:status memory)))
      (errors/refuse state :MEMORY_STATUS_INVALID
                     "only a candidate or rejected record can be approved"
                     {:id id :status (:status memory)})

      :else
      (let [merged (merge (select-keys memory
                                       [:body :category :scope :epistemic :always?])
                          (select-keys request
                                       [:body :category :scope :epistemic :always?]))
            validated (validate-fields merged {})]
        (if-not (:ok? validated)
          (errors/refuse state (:code (:error validated))
                         (:message (:error validated))
                         (:details (:error validated)))
          (let [{:keys [body category scope epistemic always? sensitive-kinds]}
                (:fields validated)
                duplicate (duplicate-active (:memories state)
                                            body category scope id)]
            (if duplicate
              (errors/refuse state :MEMORY_DUPLICATE
                             "an active memory with the same content and scope already exists"
                             {:id id :duplicate-id duplicate})
              (let [[state seq] (state/prepare state)
                    updated (assoc memory
                                   :status :active
                                   :body body
                                   :body-hash (body-hash body)
                                   :category category
                                   :scope scope
                                   :epistemic epistemic
                                   :always? always?
                                   :sensitive? (boolean (not-empty sensitive-kinds))
                                   :sensitive-kinds sensitive-kinds
                                   :updated-seq seq)]
                {:ok? true
                 :state (assoc-in state [:memories id] updated)
                 :memory updated
                 :effects []}))))))))

(defn reject
  "Refuse a candidate. The record remains for review history and suppresses
  an identical future suggestion."
  [state id request]
  (let [memory (get-in state [:memories id])
        reason (:reason (or request {}))]
    (cond
      (not (valid-id? id))
      (errors/refuse state :MEMORY_ID_INVALID
                     "memory id must be a non-blank string" {:id id})

      (nil? memory)
      (errors/refuse state :MEMORY_NOT_FOUND
                     "memory record does not exist" {:id id})

      (= :rejected (:status memory))
      {:ok? true :state state :memory memory :replayed? true :effects []}

      (= :candidate (:status memory))
      (let [[state seq] (state/prepare state)
            updated (cond-> (assoc memory
                                   :status :rejected
                                   :updated-seq seq)
                      (some? reason) (assoc :rejection-reason reason))]
        {:ok? true
         :state (assoc-in state [:memories id] updated)
         :memory updated
         :effects []})

      :else
      (errors/refuse state :MEMORY_STATUS_INVALID
                     "only a candidate can be rejected; archive an active record"
                     {:id id :status (:status memory)}))))

(defn replace
  "Replace an active memory while keeping the previous version in history.

  This is the explicit path for a corrected fact or preference. The package
  never merges two active records just because their prose looks similar."
  [state id request]
  (let [memory (get-in state [:memories id])
        request (or request {})]
    (cond
      (not (valid-id? id))
      (errors/refuse state :MEMORY_ID_INVALID
                     "memory id must be a non-blank string" {:id id})

      (nil? memory)
      (errors/refuse state :MEMORY_NOT_FOUND
                     "memory record does not exist" {:id id})

      (not= :active (:status memory))
      (errors/refuse state :MEMORY_STATUS_INVALID
                     "only an active memory can be replaced"
                     {:id id :status (:status memory)})

      :else
      (let [merged (merge (select-keys memory
                                       [:body :category :scope :epistemic :always?])
                          (select-keys request
                                       [:body :category :scope :epistemic :always?]))
            validated (validate-fields merged {})]
        (if-not (:ok? validated)
          (errors/refuse state (:code (:error validated))
                         (:message (:error validated))
                         (:details (:error validated)))
          (let [{:keys [body category scope epistemic always? sensitive-kinds]}
                (:fields validated)]
            (if (and (= (:body-hash memory) (body-hash body))
                     (= (:category memory) category)
                     (= (scope-key (:scope memory)) (scope-key scope))
                     (= (:epistemic memory) epistemic)
                     (= (:always? memory) always?))
              {:ok? true :state state :memory memory :replayed? true :effects []}
              (let [duplicate (duplicate-active (:memories state)
                                                body category scope id)]
                (if duplicate
                  (errors/refuse state :MEMORY_DUPLICATE
                                 "another active memory already has this content and scope"
                                 {:id id :duplicate-id duplicate})
                  (let [[state seq] (state/prepare state)
                        previous (select-keys memory
                                              [:body :category :scope :epistemic
                                               :always? :updated-seq])
                        updated (assoc memory
                                       :body body
                                       :body-hash (body-hash body)
                                       :category category
                                       :scope scope
                                       :epistemic epistemic
                                       :always? always?
                                       :sensitive? (boolean (not-empty sensitive-kinds))
                                       :sensitive-kinds sensitive-kinds
                                       :updated-seq seq
                                       :versions (conj (vec (:versions memory))
                                                       (assoc previous
                                                              :replaced-seq seq
                                                              :reason (:reason request))))]
                    {:ok? true
                     :state (assoc-in state [:memories id] updated)
                     :memory updated
                     :previous previous
                     :effects []}))))))))))

;; ---------------------------------------------------------------------------
;; Archival, restore and deletion

(defn archive
  "Remove an active memory from future injection immediately.

  The record and its history remain readable in the archive view."
  [state id request]
  (let [memory (get-in state [:memories id])]
    (cond
      (not (valid-id? id))
      (errors/refuse state :MEMORY_ID_INVALID
                     "memory id must be a non-blank string" {:id id})

      (nil? memory)
      (errors/refuse state :MEMORY_NOT_FOUND
                     "memory record does not exist" {:id id})

      (= :archived (:status memory))
      {:ok? true :state state :memory memory :replayed? true :effects []}

      (not= :active (:status memory))
      (errors/refuse state :MEMORY_STATUS_INVALID
                     "only an active memory can be archived"
                     {:id id :status (:status memory)})

      :else
      (let [[state seq] (state/prepare state)
            updated (cond-> (assoc memory
                                   :status :archived
                                   :updated-seq seq)
                      (:reason (or request {}))
                      (assoc :archive-reason (:reason request)))]
        {:ok? true
         :state (assoc-in state [:memories id] updated)
         :memory updated
         :effects []}))))

(defn restore
  "Restore an archived memory to active, unless that would duplicate an
  active record with the same content and scope."
  [state id]
  (let [memory (get-in state [:memories id])]
    (cond
      (not (valid-id? id))
      (errors/refuse state :MEMORY_ID_INVALID
                     "memory id must be a non-blank string" {:id id})

      (nil? memory)
      (errors/refuse state :MEMORY_NOT_FOUND
                     "memory record does not exist" {:id id})

      (= :active (:status memory))
      {:ok? true :state state :memory memory :replayed? true :effects []}

      (not= :archived (:status memory))
      (errors/refuse state :MEMORY_STATUS_INVALID
                     "only an archived memory can be restored"
                     {:id id :status (:status memory)})

      :else
      (let [duplicate (duplicate-active (:memories state)
                                        (:body memory)
                                        (:category memory)
                                        (:scope memory)
                                        id)]
        (if duplicate
          (errors/refuse state :MEMORY_DUPLICATE
                         "an active memory with the same content and scope already exists"
                         {:id id :duplicate-id duplicate})
          (let [[state seq] (state/prepare state)
                updated (assoc memory :status :active :updated-seq seq)]
            {:ok? true
             :state (assoc-in state [:memories id] updated)
             :memory updated
             :effects []}))))))

(defn delete
  "Permanently delete an archived memory inside this application.

  The body and source excerpt are removed. A tombstone keeps the scope and
  content hash so a later backup merge cannot silently resurrect the record.
  External backups, prior model requests and the source conversation are not
  claimed to be erased."
  [state id request]
  (let [memory (get-in state [:memories id])
        request (or request {})]
    (cond
      (not (valid-id? id))
      (errors/refuse state :MEMORY_ID_INVALID
                     "memory id must be a non-blank string" {:id id})

      (nil? memory)
      (errors/refuse state :MEMORY_NOT_FOUND
                     "memory record does not exist" {:id id})

      (= :deleted (:status memory))
      {:ok? true :state state :memory memory :replayed? true :effects []}

      (not= :archived (:status memory))
      (errors/refuse state :MEMORY_STATUS_INVALID
                     "archive a memory before permanent deletion"
                     {:id id :status (:status memory)})

      (not (true? (:confirm request)))
      (errors/refuse state :MEMORY_CONFIRM_REQUIRED
                     "permanent deletion needs an explicit confirmation"
                     {:id id :action :archive-first-then-delete})

      :else
      (let [[state seq] (state/prepare state)
            tombstone {:id id
                       :v 1
                       :status :deleted
                       :body nil
                       :body-hash (:body-hash memory)
                       :category (:category memory)
                       :scope (:scope memory)
                       :epistemic (:epistemic memory)
                       :always? false
                       :source nil
                       :source-kind (:source-kind memory)
                       :sensitive? (:sensitive? memory)
                       :sensitive-kinds (:sensitive-kinds memory)
                       :created-seq (:created-seq memory)
                       :updated-seq seq
                       :deleted-seq seq
                       :versions []}]
        {:ok? true
         :state (assoc-in state [:memories id] tombstone)
         :memory tombstone
         :effects []}))))

;; ---------------------------------------------------------------------------
;; Reading and injection

(defn- memory-matches?
  [memory {:keys [query category scope statuses]}]
  (let [body (:body memory)
        wanted-scope (some-> scope scope-key)
        memory-scope (some-> (:scope memory) scope-key)]
    (and (contains? statuses (:status memory))
         (or (nil? category) (= category (:category memory)))
         (or (nil? wanted-scope) (= wanted-scope memory-scope))
         (or (not (errors/non-blank-string? query))
             (and (string? body)
                  (str/includes? (str/lower-case body)
                                 (str/lower-case query)))))))

(defn search
  "Search memory records for management. Active records are searched by
  default; archived records require :include-archived? true. Deleted
  tombstones have no body and are never returned."
  [state request]
  (let [request (or request {})
        include-archived? (true? (:include-archived? request))
        wanted-statuses (or (:statuses request)
                            (if include-archived?
                              #{:active :archived}
                              #{:active}))
        query (:query request)]
    (cond
      (not (map? request))
      (errors/refuse state :MEMORY_REQUEST_INVALID
                     "search request must be a map" {:request request})

      (and (some? query) (not (string? query)))
      (errors/refuse state :MEMORY_QUERY_INVALID
                     "memory query must be a string" {:query query})

      (and (some? (:category request))
           (not (contains? categories (:category request))))
      (errors/refuse state :MEMORY_CATEGORY_INVALID
                     "memory category is not one of the four known categories"
                     {:category (:category request)
                      :allowed (vec category-order)})

      (not (and (set? wanted-statuses)
                (every? #(contains? statuses %) wanted-statuses)
                (not (contains? wanted-statuses :deleted))))
      (errors/refuse state :MEMORY_STATUS_INVALID
                     "search statuses must be known non-deleted statuses"
                     {:statuses wanted-statuses})

      :else
      (let [scope (:scope request)
            normalized-scope (when (some? scope) (normalize-scope scope))]
        (if (and (some? scope) (nil? normalized-scope))
          (errors/refuse state :MEMORY_SCOPE_INVALID
                         "memory scope is invalid" {:scope scope})
          (let [matches (->> (:memories state)
                             (keep (fn [[_ memory]]
                                     (when (memory-matches?
                                            memory
                                            {:query query
                                             :category (:category request)
                                             :scope normalized-scope
                                             :statuses wanted-statuses})
                                       memory)))
                             (sort-by (juxt (comp - :updated-seq) :id))
                             vec)]
            {:ok? true
             :state state
             :memories matches
             :count (count matches)
             :effects []}))))))

(defn effective
  "Return active memories visible to one request, in precedence order.

  - Both switches start closed. The read switch gates injection.
  - A temporary session reads nothing unless the user grants this session a
    read-only override; writes remain refused by every write entry point.
  - The scope chain defaults to assistant, project, global. Callers may pass
    an explicit :scope-chain; records outside it are never returned.
  - No relevance score is invented. Ordering is scope specificity, then
    always-provide, then most recently updated."
  [state request]
  (let [request (or request {})]
    (if-not (map? request)
      (errors/refuse state :MEMORY_REQUEST_INVALID
                     "effective request must be a map" {:request request})
      (let [temporary? (true? (:temporary? request))
            temporary-read? (true? (:temporary-read? request))
            chain (if (contains? request :scope-chain)
                    (normalize-scope-chain (:scope-chain request))
                    (default-scope-chain request))]
        (cond
          (and (contains? request :assistant-id)
               (not (errors/non-blank-string? (:assistant-id request))))
          (errors/refuse state :MEMORY_SCOPE_INVALID
                         "assistant-id must be a non-blank string"
                         {:assistant-id (:assistant-id request)})

          (and (contains? request :project-id)
               (not (errors/non-blank-string? (:project-id request))))
          (errors/refuse state :MEMORY_SCOPE_INVALID
                         "project-id must be a non-blank string"
                         {:project-id (:project-id request)})

          (and (contains? request :scope-chain) (nil? chain))
          (errors/refuse state :MEMORY_SCOPE_INVALID
                         "scope chain contains an invalid scope"
                         {:scope-chain (:scope-chain request)})

          (not (state/memory-read-enabled? state))
          {:ok? true
           :state state
           :memories []
           :reason :memory-read-disabled
           :effects []}

          (and temporary? (not temporary-read?))
          {:ok? true
           :state state
           :memories []
           :reason :temporary-session
           :temporary? true
           :effects []}

          :else
          (let [chain (vec chain)
                chain-keys (set (map scope-key chain))
                memories (->> (:memories state)
                              (keep (fn [[_ memory]]
                                      (when (and (= :active (:status memory))
                                                 (contains? chain-keys
                                                            (scope-key (:scope memory))))
                                        (assoc memory
                                               :scope-rank (scope-rank chain (:scope memory))))))
                              (sort-by (juxt :scope-rank
                                             (comp - :updated-seq)
                                             :id))
                              vec)]
            {:ok? true
             :state state
             :memories memories
             :scope-chain chain
             :temporary? temporary?
             :temporary-read? (and temporary? temporary-read?)
             :effects []}))))))

(defn select-for-injection
  "Choose whole memories that fit BUDGET characters.

  Always-provide records are tried first, then scope precedence and recency.
  Bodies are never truncated: a record that does not fit is omitted with a
  reason. The caller can show the omitted list as \"hit but not provided\".
  A nil budget means no limit."
  [memories request]
  (let [budget (:budget (or request {}))]
    (cond
      (not (and (sequential? memories) (every? map? memories)))
      {:ok? false
       :error (errors/error :MEMORY_SELECTION_INVALID
                            "memories must be a sequence of maps"
                            {:memories memories})}

      (not (or (nil? budget) (state/non-negative-integer? budget)))
      {:ok? false
       :error (errors/error :MEMORY_BUDGET_INVALID
                            "memory budget must be a non-negative integer or nil"
                            {:budget budget})}

      :else
      (let [ordered (sort-by (juxt (fn [memory] (if (:always? memory) 0 1))
                                   (fn [memory] (or (:scope-rank memory) 0))
                                   (fn [memory] (- (long (or (:updated-seq memory) 0))))
                                   :id)
                             memories)]
        (loop [remaining ordered
               used 0
               provided []
               omitted []]
          (if-let [memory (first remaining)]
            (let [active? (= :active (:status memory))
                  chars (errors/unicode-length (:body memory))
                  fits? (and active?
                             (or (nil? budget)
                                 (<= (+ used chars) budget)))]
              (if fits?
                (recur (next remaining)
                       (+ used chars)
                       (conj provided memory)
                       omitted)
                (recur (next remaining)
                       used
                       provided
                       (conj omitted {:memory-id (:id memory)
                                      :reason (if active? :budget :inactive)
                                      :chars chars
                                      :always? (boolean (:always? memory))}))))
            {:ok? true
             :provided provided
             :omitted omitted
             :used-chars used
             :budget budget}))))))

(defn render-injection
  "Render provided memories as one deterministic text block.

  This is a projection, not evidence that the model followed the memory. The
  caller keeps the original records and the request's exact provided set."
  [memories]
  (->> memories
       (map (fn [memory]
              (let [category (get category-label (:category memory)
                                  (name (:category memory)))
                    scope (scope-label (:scope memory))
                    epistemic (if (= :assumption (:epistemic memory))
                                "assumption, not confirmed"
                                (name (or (:epistemic memory) :fact)))]
                (str "- [" category "; " scope "; " epistemic "] "
                     (:body memory)))))
       (str/join "\n")))

;; ---------------------------------------------------------------------------
;; Backup merge

(defn- incoming-status
  [memory]
  (or (:status memory) :active))

(defn- backup-conflict-id
  [import-id memory-id]
  (str "merge-" import-id "-" memory-id))

(defn- add-backup-conflict
  [state import-id incoming existing-status]
  (let [id (backup-conflict-id import-id (:id incoming))
        existing (get-in state [:memories id])]
    (if existing
      {:state state :skipped true :id id}
      (let [request {:id id
                     :body (:body incoming)
                     :category (:category incoming)
                     :scope (:scope incoming)
                     :epistemic (:epistemic incoming)
                     :always? false
                     :reason (keyword (str "current-status-" (name existing-status)))
                     :conflict-of (:id incoming)}
            result (create-record state request :candidate :backup-merge)]
        (if (:ok? result)
          {:state (:state result)
           :memory (:memory result)
           :id id}
          {:state state :error (:error result) :id id})))))

(defn merge-backup
  "Merge memory records from an explicit backup import.

  Current decisions win:

  - An archived or deleted record is never reactivated by an old backup.
  - A differing active record is not overwritten. A pending candidate with a
    deterministic id is created for review instead.
  - Re-importing the same backup skips records whose content already matches
    and reuses the same conflict ids.

  This function does not choose between \"merge\" and \"replace\" categories;
  the caller decides which records to submit."
  [state request]
  (let [{:keys [import-id memories]} (or request {})]
    (cond
      (not (errors/non-blank-string? import-id))
      (errors/refuse state :MEMORY_IMPORT_INVALID
                     "backup merge needs a non-blank import-id"
                     {:import-id import-id})

      (not (sequential? memories))
      (errors/refuse state :MEMORY_IMPORT_INVALID
                     "backup merge needs a sequence of memories"
                     {:memories memories})

      :else
      (loop [remaining memories
             state state
             added []
             skipped []
             conflicts []]
        (if-let [incoming (first remaining)]
          (let [id (:id incoming)
                validated (validate-fields incoming {})
                existing (get-in state [:memories id])]
            (cond
              (not (valid-id? id))
              (recur (next remaining) state added
                     (conj skipped {:id id :reason :invalid-id}) conflicts)

              (not (:ok? validated))
              (recur (next remaining) state added
                     (conj skipped {:id id
                                    :reason :invalid-record
                                    :error (:error validated)})
                     conflicts)

              (= :deleted (:status incoming))
              (recur (next remaining) state added
                     (conj skipped {:id id :reason :deleted-in-backup})
                     conflicts)

              (not (contains? #{:candidate :active :archived :rejected}
                              (incoming-status incoming)))
              (recur (next remaining) state added
                     (conj skipped {:id id :reason :invalid-status
                                    :status (incoming-status incoming)})
                     conflicts)

              (nil? existing)
              (let [status (incoming-status incoming)
                    request (assoc (select-keys incoming
                                                [:id :body :category :scope
                                                 :epistemic :always?])
                                   :reason (:reason incoming))
                    result (create-record state request status :backup-merge)]
                (if (:ok? result)
                  (recur (next remaining)
                         (:state result)
                         (conj added (:memory result))
                         skipped
                         conflicts)
                  (recur (next remaining) state added
                         (conj skipped {:id id
                                        :reason :create-failed
                                        :error (:error result)})
                         conflicts)))

              (or (= :archived (:status existing))
                  (= :deleted (:status existing)))
              (let [result (add-backup-conflict state import-id incoming
                                                (:status existing))]
                (recur (next remaining)
                       (:state result)
                       added
                       (cond-> skipped
                         (:skipped result) (conj {:id id :reason :conflict-exists})
                         (:error result) (conj {:id id
                                                :reason :conflict-failed
                                                :error (:error result)}))
                       (cond-> conflicts
                         (:memory result) (conj {:memory-id (:id result)
                                                 :conflict-of id
                                                 :reason :current-status
                                                 :current-status (:status existing)}))))

              :else
              (let [same? (and (= (:body-hash existing)
                                  (body-hash (errors/trimmed-body (:body incoming))))
                               (= (:category existing) (:category incoming))
                               (= (scope-key (:scope existing))
                                  (scope-key (normalize-scope (:scope incoming)))))]
                (if same?
                  (recur (next remaining) state added
                         (conj skipped {:id id :reason :unchanged})
                         conflicts)
                  (let [result (add-backup-conflict state import-id incoming
                                                    (:status existing))]
                    (recur (next remaining)
                           (:state result)
                           added
                           (cond-> skipped
                             (:skipped result) (conj {:id id :reason :conflict-exists})
                             (:error result) (conj {:id id
                                                    :reason :conflict-failed
                                                    :error (:error result)}))
                           (cond-> conflicts
                             (:memory result) (conj {:memory-id (:id result)
                                                     :conflict-of id
                                                     :reason :content-differs}))))))))
          {:ok? true
           :state state
           :added added
           :skipped skipped
           :conflicts conflicts
           :effects []})))))

;; ---------------------------------------------------------------------------
;; Invariants

(defn check
  "Return memory-record invariant violations."
  [state]
  (let [memories (:memories state)]
    (if-not (map? memories)
      [{:invariant :memories-not-a-map :memories memories}]
      (into []
            (mapcat
             (fn [[id memory]]
               (let [deleted? (= :deleted (:status memory))
                     versions (:versions memory)]
                 (cond-> []
                   (not (valid-id? id))
                   (conj {:invariant :memory-id-invalid :id id})

                   (not= id (:id memory))
                   (conj {:invariant :memory-id-key-mismatch
                          :key id :id (:id memory)})

                   (not (contains? statuses (:status memory)))
                   (conj {:invariant :memory-status-invalid
                          :id id :status (:status memory)})

                   (nil? (normalize-scope (:scope memory)))
                   (conj {:invariant :memory-scope-invalid
                          :id id :scope (:scope memory)})

                   (not (contains? categories (:category memory)))
                   (conj {:invariant :memory-category-invalid
                          :id id :category (:category memory)})

                   (not (contains? epistemic-kinds (:epistemic memory)))
                   (conj {:invariant :memory-epistemic-invalid
                          :id id :epistemic (:epistemic memory)})

                   (not (contains? #{:manual :automatic :backup-merge}
                                   (:source-kind memory)))
                   (conj {:invariant :memory-source-kind-invalid
                          :id id :source-kind (:source-kind memory)})

                   (not (boolean? (:always? memory)))
                   (conj {:invariant :memory-always-invalid
                          :id id :always? (:always? memory)})

                   (and (some? (:sensitive-kinds memory))
                        (not (vector? (:sensitive-kinds memory))))
                   (conj {:invariant :memory-sensitive-kinds-invalid
                          :id id})

                   (and (not deleted?)
                        (= :automatic (:source-kind memory))
                        (not (map? (:source memory))))
                   (conj {:invariant :memory-source-missing
                          :id id})

                   (not (state/non-negative-integer? (:created-seq memory)))
                   (conj {:invariant :memory-created-seq-invalid
                          :id id :created-seq (:created-seq memory)})

                   (not (state/non-negative-integer? (:updated-seq memory)))
                   (conj {:invariant :memory-updated-seq-invalid
                          :id id :updated-seq (:updated-seq memory)})

                   (and (not deleted?)
                        (not (string? (:body memory))))
                   (conj {:invariant :memory-body-missing :id id})

                   (and (not deleted?)
                        (string? (:body memory))
                        (let [error (errors/invalid-body (:body memory) max-body-chars)]
                          (some? error)))
                   (conj {:invariant :memory-body-invalid :id id})

                   (and (not deleted?)
                        (not= (:body-hash memory) (body-hash (:body memory))))
                   (conj {:invariant :memory-body-hash-mismatch :id id})

                   (and deleted? (some? (:body memory)))
                   (conj {:invariant :memory-deleted-body-present :id id})

                   (and deleted? (not (string? (:body-hash memory))))
                   (conj {:invariant :memory-deleted-body-hash-missing :id id})

                   (not (vector? versions))
                   (conj {:invariant :memory-versions-invalid
                          :id id :versions versions})

                   (and (vector? versions)
                        (some (fn [version]
                                (or (not (string? (:body version)))
                                    (not (contains? categories (:category version)))
                                    (nil? (normalize-scope (:scope version)))
                                    (not (contains? epistemic-kinds (:epistemic version)))))
                              versions))
                   (conj {:invariant :memory-version-invalid :id id})

                   )))
             memories)))))
