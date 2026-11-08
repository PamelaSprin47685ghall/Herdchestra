(ns hc.core.scope.runtime
  "Pure adapters between the live runtime shapes and the Scope core.

  The Owner stores tool calls as `:model/event :tool-call` maps, builds model
  snapshots from `:conversation` messages, and uses `:role :user` / `:role
  :tool` instead of the Scope item vocabulary.  This namespace normalizes
  those shapes without importing `hc.compose` or `hc.server`.

  The functions are pure.  A caller that owns a mutable Scope can use the
  `scope-*` wrappers; a caller that keeps a Scope book in pure state can use
  the book functions and commit the returned book and records in the same
  transaction."
  (:require [clojure.string :as string]
            [hc.core.scope.admission :as admission]
            [hc.core.scope.annotation :as annotation]
            [hc.core.scope.errors :as errors]
            [hc.core.scope.ledger :as ledger]
            [hc.core.scope.projection :as projection]
            [hc.core.scope.records :as records]
            [hc.core.scope.scope :as scope]))

(defn scope-annotation
  "Return the raw `:scope` annotation from a tool event, call, or request."
  [tool]
  (when (map? tool)
    (first
     (filter some?
             [(:scope tool)
              (get-in tool [:arguments :scope])
              (get-in tool [:payload :scope])
              (get-in tool [:request :scope])
              (get-in tool [:request :arguments :scope])
              (get-in tool [:request :payload :scope])]))))

(defn tool-member
  "Return the tool name from a model event, call record, or request."
  [tool]
  (when (map? tool)
    (or (:tool-name tool)
        (:name tool)
        (:member tool)
        (get-in tool [:payload :name])
        (get-in tool [:payload :member])
        (get-in tool [:request :name])
        (get-in tool [:request :member]))))

(defn tool-call-id
  "Return the physical call id from a model event, call record, or request."
  [tool]
  (when (map? tool)
    (or (:call-id tool)
        (:tool-call-id tool)
        (:id tool)
        (get-in tool [:payload :call-id])
        (get-in tool [:request :call-id]))))

(defn message-role
  "Normalize a runtime message role to a keyword."
  [message]
  (let [role (:role message)]
    (cond
      (keyword? role) role
      (string? role) (keyword role)
      :else nil)))

(defn message-text
  "Return the text of a runtime message.  Owner messages use `:text`;
  history views use `:content`."
  [message]
  (or (:text message) (:content message) ""))

(defn- assistant-items [message index role]
  (let [text (message-text message)
        thinking (:thinking message)
        items (cond-> []
                (not (string/blank? (str text)))
                (conj {:kind :message
                       :role :assistant
                       :text text
                       :source-index index
                       :source-role role})

                (not (string/blank? (str thinking)))
                (conj {:kind :thinking
                       :text thinking
                       :source-index index
                       :source-role role}))]
    (into items
          (map (fn [call]
                 (cond-> {:kind :tool-call
                          :member (tool-member call)
                          :call-id (tool-call-id call)
                          :source-index index
                          :source-role role}
                   (some? (scope-annotation call))
                   (assoc :scope (scope-annotation call))

                   (some? (:arguments call))
                   (assoc :arguments (:arguments call)))))
          (or (:tool-calls message) []))))

(defn- tool-result-item [message index role]
  (cond-> {:kind :tool-result
           :member (tool-member message)
           :call-id (tool-call-id message)
           :text (message-text message)
           :source-index index
           :source-role role}
    (some? (scope-annotation message))
    (assoc :scope (scope-annotation message))

    (boolean? (:error? message))
    (assoc :error? (:error? message))))

(defn message->items
  "Normalize one runtime message into a vector of Scope items.

  `index` is the message's position in the conversation; it is carried as
  `:source-index` so `items->messages` can rebuild the original message
  boundaries."
  [message index]
  (let [role (message-role message)
        text (message-text message)]
    (case role
      (:user :operator)
      [(cond-> {:kind :message
                :role :operator
                :text text
                :source-index index
                :source-role role}
         (contains? message :attachments)
         (assoc :attachments (:attachments message))
         (contains? message :quotes)
         (assoc :quotes (:quotes message))
         (contains? message :citations)
         (assoc :citations (:citations message)))]

      :system
      [{:kind :message
        :role :system
        :text text
        :source-index index
        :source-role role}]

      :assistant
      (assistant-items message index role)

      :tool
      [(tool-result-item message index role)]

      :tool-call
      [(cond-> {:kind :tool-call
                :member (tool-member message)
                :call-id (tool-call-id message)
                :source-index index
                :source-role role}
         (some? (scope-annotation message))
         (assoc :scope (scope-annotation message))

         (some? (:arguments message))
         (assoc :arguments (:arguments message)))]

      :provider
      [{:kind :provider
        :text text
        :source-index index
        :source-role role}]

      [{:kind :message
        :role (or role :user)
        :text text
        :source-index index
        :source-role role}])))

(defn conversation->items
  "Normalize a conversation vector into Scope items."
  [conversation]
  (when-not (or (nil? conversation) (sequential? conversation))
    (throw (errors/argument-error "conversation must be a sequence")))
  (vec (mapcat (fn [[index message]] (message->items message index))
               (map-indexed vector (or conversation [])))))

(defn- item-role->model-role [item]
  (case (:role item)
    :operator :user
    :assistant :assistant
    :system :system
    (:user :tool) (:role item)
    (or (:role item) :user)))

(defn- tool-call->model [item]
  (cond-> {:id (:call-id item)
           :name (:member item)}
    (contains? item :arguments)
    (assoc :arguments (:arguments item))))

(defn- group->messages [items]
  (let [messages (filter #(= :message (:kind %)) items)
        thinking (filter #(= :thinking (:kind %)) items)
        calls (filter #(= :tool-call (:kind %)) items)
        results (filter #(= :tool-result (:kind %)) items)]
    (cond
      (seq results)
      (mapv (fn [item]
              (let [call-id (:call-id item)
                    name (:member item)]
                (cond-> {:role :tool
                         :tool-call-id call-id
                         :name name
                         :text (or (:text item) "")}
                  (nil? call-id) (dissoc :tool-call-id)
                  (nil? name) (dissoc :name)
                  (boolean? (:error? item)) (assoc :error? (:error? item)))))
            results)

      (or (seq messages) (seq thinking) (seq calls))
      [(let [first-message (first messages)
             base (cond-> {}
                    (seq messages)
                    (assoc :role (item-role->model-role first-message)
                           :text (or (:text first-message) ""))

                    (seq thinking)
                    (assoc :thinking (:text (first thinking)))

                    (seq calls)
                    (assoc :tool-calls (mapv tool-call->model calls))

                    (and (empty? messages) (seq thinking))
                    (assoc :role :assistant :text "")

                    (and (empty? messages) (empty? thinking) (seq calls))
                    (assoc :role :assistant))]
         (if (and (seq messages) (= :operator (:role first-message)))
           (merge base
                  (cond-> {}
                    (contains? first-message :attachments)
                    (assoc :attachments (:attachments first-message))
                    (contains? first-message :quotes)
                    (assoc :quotes (:quotes first-message))
                    (contains? first-message :citations)
                    (assoc :citations (:citations first-message))))
           base))]

      :else [])))

(defn- provider-message
  "The model-visible shape of an observed provider original.

  The model adapter accepts only system/user/assistant/tool roles, so the
  original travels as an assistant message. `:meta` marks its origin without
  changing the text."
  [item]
  {:role :assistant
   :text (or (:text item) "")
   :meta {:provider true}})

(defn- standalone-message [item]
  (case (:kind item)
    :message
    (cond-> {:role (item-role->model-role item)
             :text (or (:text item) "")}
      (contains? item :attachments)
      (assoc :attachments (:attachments item))
      (contains? item :quotes)
      (assoc :quotes (:quotes item))
      (contains? item :citations)
      (assoc :citations (:citations item)))

    :thinking
    {:role :assistant
     :text ""
     :thinking (or (:text item) "")}

    :provider
    (provider-message item)

    :tool-call
    {:role :assistant
     :tool-calls [(tool-call->model item)]}

    :tool-result
    (let [call-id (:call-id item)
          name (:member item)]
      (cond-> {:role :tool
               :tool-call-id call-id
               :name name
               :text (or (:text item) "")}
        (nil? call-id) (dissoc :tool-call-id)
        (nil? name) (dissoc :name)
        (boolean? (:error? item)) (assoc :error? (:error? item))))

    nil))

(defn items->messages
  "Rebuild runtime model messages from projected Scope items.

  Items sharing a `:source-index` become one message again.  A replacement
  message produced by `removeLast` has no source index and is emitted in
  place.  A `:provider` item becomes an assistant message with
  `:meta {:provider true}` so an observed provider original is never
  silently dropped.  Unknown items are skipped."
  [items]
  (when-not (or (nil? items) (sequential? items))
    (throw (errors/argument-error "items must be a sequence")))
  (let [items (vec (or items []))]
    (loop [remaining (seq items)
           seen #{}
           output []]
      (if-let [item (first remaining)]
        (if (= :provider (:kind item))
          (recur (next remaining) seen (conj output (provider-message item)))
          (if-let [source-index (:source-index item)]
            (if (contains? seen source-index)
              (recur (next remaining) seen output)
              (let [group (filter #(= source-index (:source-index %)) items)]
                (recur (next remaining)
                       (conj seen source-index)
                       (into output (group->messages group)))))
            (if-let [message (standalone-message item)]
              (recur (next remaining) seen (conj output message))
              (recur (next remaining) seen output))))
        output))))

(defn project-items
  "Project Scope items with a pure book.  Returns a vector or a refusal."
  ([book items] (project-items book items {}))
  ([book items options]
   (projection/project
    items
    (merge {:erase (ledger/erases book)
            :reset-call-id (ledger/last-reset-call-id book)
            :settled? true}
           options))))

(defn project-scope-items
  "Project Scope items with a stateful Scope."
  ([scope items] (scope/project-request scope items))
  ([scope items options] (scope/project-request scope items options)))

(defn project-conversation
  "Project a runtime conversation with a pure book.

  Returns a vector of model messages or a refusal.  User messages are kept
  as operator messages; assistant text/thinking/tool calls and tool results
  are kept or dropped by Scope rules."
  ([book conversation] (project-conversation book conversation {}))
  ([book conversation options]
   (let [projected (project-items book (conversation->items conversation) options)]
     (if (vector? projected)
       (items->messages projected)
       projected))))

(defn project-scope-conversation
  "Project a runtime conversation with a stateful Scope."
  ([scope conversation] (project-scope-conversation scope conversation {}))
  ([scope conversation options]
   (let [projected (project-scope-items scope (conversation->items conversation)
                                        options)]
     (if (vector? projected)
       (items->messages projected)
       projected))))

(defn admission-check
  "Check one tool event/request against a pure book.

  Returns `{:ok true :exempt bool :annotation parsed}` or a refusal.  The
  book is not changed."
  [book admission-map tool]
  (admission/check {:member (tool-member tool)
                    :annotation (scope-annotation tool)
                    :retention (ledger/retention book)
                    :admission admission-map}))

(defn settle-tool
  "Settle one tool event/request against a pure book.

  Admission happens before the tool runs; settlement only decides exemption,
  parses the annotation, and lets the ledger enforce turn and budget rules.
  Returns `{:book next-book :result result}`.  The result is a receipt, an
  exempt receipt, or a refusal.  The caller commits the book and the result's
  `:records` (the full batch) together; `:current-records` and
  `:prior-records` split this turn from a prior staged default."
  [book admission-map tool turn-id call-id]
  (let [member (tool-member tool)
        annotation (scope-annotation tool)
        exempt? (admission/exempt? member admission-map)]
    (if exempt?
      {:book book
       :result {:ok true
                :outcome :exempt
                :kind nil
                :call-id call-id
                :remaining (ledger/remaining book)
                :retention (ledger/retention book)
                :records []}}
      (let [parsed (annotation/parse-annotation annotation)]
        (if (false? (:ok parsed))
          {:book book :result (assoc parsed :phase :settle)}
          (let [{next-book :book result :result}
                (ledger/settle-turn book turn-id parsed {:call-id call-id})]
            {:book next-book
             :result (if (false? (:ok result))
                       (assoc result :phase :settle)
                       result)}))))))

(defn close-turn
  "Close one model turn against a pure book."
  [book]
  (ledger/close-turn book))

(defn cold-start-book
  "Build a pure book from persisted Scope records.

  The budget stays unknown until a reset settles in this process."
  [records]
  (ledger/restore-book (records/read-cold-state records)))

(defn cold-start-state
  "Cold-start an Owner `{:book ... :records ...}` scope state.

  Records are preserved.  The budget and any in-memory turn are not.  When
  no records survive (for example an older persisted state), history is
  copied from the persisted book but the budget is still cleared."
  [scope-state]
  (let [records (or (:records scope-state) [])
        old (:book scope-state)
        book (if (seq records)
               (cold-start-book records)
               (-> (ledger/new-book)
                   (assoc :last-reset (:last-reset old)
                          :reset-beats (vec (:reset-beats old))
                          :spin (or (:spin old) 0)
                          :erases (vec (:erases old)))))
        book (cond-> book
               (and (nil? (:last-reset book)) (some? (:last-reset old)))
               (assoc :last-reset (:last-reset old)
                      :reset-beats (vec (:reset-beats old)))

               (and (empty? (:erases book)) (seq (:erases old)))
               (assoc :erases (vec (:erases old)))

               (and (zero? (:spin book)) (pos? (or (:spin old) 0)))
               (assoc :spin (:spin old)))]
    {:book book :records records}))

(defn latest-boundary
  "Return the latest settled reset/remove fact from Owner scope state.

  `scope-state` is `{:book <book> :records [...]}`.  The newest `:reset` or
  `:remove` record wins; `:keep` records do not move the boundary.  If the
  record window has no boundary (for example after a cap), fall back to the
  book's `:last-reset` and then the newest erase.

  Returns `{:kind :reset-scope :call-id id :turn-id id}` or
  `{:kind :remove-last :call-id id}`, or nil.  A reset fact carries
  `:turn-id` when available (Pair Hint uses it as a fallback id when
  `:call-id` is nil); a remove fact always has a call-id and does not add
  turn metadata to the erase shape.  This is the raw Scope fact; call
  `hc.core.pair-hint/scope-boundary` to translate it to a Pair Hint
  boundary."
  [scope-state]
  (when (map? scope-state)
    (let [records (:records scope-state)
          boundary-record (last (filter #(contains? #{:reset :remove} (:type %))
                                        records))]
      (cond
        (= :reset (:type boundary-record))
        (cond-> {:kind :reset-scope
                 :call-id (:call-id boundary-record)}
          (some? (:turn-id boundary-record))
          (assoc :turn-id (:turn-id boundary-record)))

        (= :remove (:type boundary-record))
        {:kind :remove-last
         :call-id (:call-id boundary-record)}

        :else
        (let [book (:book scope-state)
              erases (:erases book)
              reset (:last-reset book)]
          (cond
            (some? reset) (cond-> {:kind :reset-scope
                                   :call-id (:call-id reset)}
                            (some? (:turn-id reset))
                            (assoc :turn-id (:turn-id reset)))
            (seq erases) {:kind :remove-last
                          :call-id (:call-id (last erases))}
            :else nil))))))

(defn pending-boundary
  "Return the raw latest boundary when it differs from `seen`, else nil.

  The app keeps `seen` as a dedicated cursor (normally the raw boundary it
  last emitted).  Do not compare against Pair Hint's `:chain-boundary`: a
  relay or compaction boundary would make an old Scope boundary look new.
  A fresh session with `seen` nil emits the current boundary once."
  [scope-state seen]
  (let [latest (latest-boundary scope-state)]
    (when (and (some? latest) (not= latest seen))
      latest)))

(defn scope-admit!
  "Check one tool event/request against a stateful Scope."
  [scope tool]
  (scope/admit! scope {:member (tool-member tool)
                       :annotation (scope-annotation tool)}))

(defn scope-settle!
  "Settle one tool event/request against a stateful Scope."
  [scope tool turn-id call-id]
  (scope/settle! scope {:member (tool-member tool)
                        :annotation (scope-annotation tool)
                        :turn-id turn-id
                        :call-id call-id}))
