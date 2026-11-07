(ns hc.server.backup.model
  "Category vocabulary and pure helpers shared by snapshot, preview and restore.

  The backup module deliberately does not know a database.  A domain state is
  a plain map:

      {:categories {:conversations {:items [entity ...]}
                    :assistants    {:items [...]}
                    ...}
       :attachments {attachment-id {:id ... :size ... :sha256 ... :bytes-base64 ...}}
       :imports     {backup-id {:digest ... :imported-at ...}}
       :permissions {...}}

  Every entity has a stable non-blank string `:id`.  A category may be written
  as `{:items [...]}` or as a bare vector; `items` accepts both.  The storage
  port owns the real representation and converts it to this neutral shape."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err])
  (:import (java.time Duration Instant ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def category-order
  "Stable order used by manifests and the preview.  The names follow
  AGENTS.md §7.1-2 and §7.4 旅程 7（导出、备份与迁移）."
  [:conversations
   :assistants
   :connections
   :tools
   :skills
   :workspaces
   :worldbooks
   :memories
   :appearance
   :schedules])

(def category-info
  "Per-category merge and safety facts.

  `:name-identified?` means a same-name/different-content pair is a visible
  conflict even when the IDs differ.  `:memory?` applies the no-revival rule.
  `:permission?` means imported items must start paused or in review."
  {:conversations {:label "对话与附件"
                   :name-identified? false
                   :attachments? true}
   :assistants {:label "助手配置"
                :name-identified? true}
   :connections {:label "模型服务配置"
                 :name-identified? true
                 :secret? true
                 :permission? true}
   :tools {:label "工具与 MCP 配置"
           :name-identified? true
           :secret? true
           :permission? true}
   :skills {:label "技能"
            :name-identified? true
            :permission? true}
   :workspaces {:label "工作区"
                :name-identified? true
                :external? true
                :permission? true}
   :worldbooks {:label "世界书"
                :name-identified? true}
   :memories {:label "记忆与画像"
              :name-identified? false
              :memory? true}
   :appearance {:label "外观偏好"
                :name-identified? false
                :singleton? true}
   :schedules {:label "任务配置"
               :name-identified? true
               :permission? true}})

(def category-set
  (set category-order))

(def default-selection
  "Every category is selected by default.  Secrets and external files remain
  opt-in, per AGENTS.md §7.1-3 and §7.1-4."
  (zipmap category-order (repeat true)))

(def default-policy
  {:encrypt? true
   :include-secrets? false
   :include-deleted? false
   :include-temp? false
   :include-cache? false
   :include-external-files? false
   :confirm-plaintext-secrets? false
   :confirm-memory-reactivation? false
   :include-expired? false
   :retention-days 30
   :max-attachment-bytes (* 100 1024 1024)
   :max-attachment-total-bytes (* 512 1024 1024)})

(defn category?
  [k]
  (contains? category-set k))

(defn category-label
  [k]
  (get-in category-info [k :label] (name k)))

(defn normalize-category
  [k]
  (cond
    (keyword? k) k
    (string? k) (keyword k)
    (symbol? k) (keyword (name k))
    :else k))

(defn normalize-selection
  "Return a map category -> boolean.

  Accepts nil (all default categories), a keyword/string, a collection of
  category names, or a map.  Unknown category names are refused instead of
  silently ignored."
  [selection]
  (let [base (zipmap category-order (repeat false))
        selected
        (cond
          (nil? selection)
          default-selection

          (map? selection)
          (reduce-kv (fn [m k v]
                       (let [k (normalize-category k)]
                         (when-not (category? k)
                           (err/fail! :BACKUP_CATEGORY_UNKNOWN
                                      "Unknown backup category"
                                      {:category k}))
                         (assoc m k (boolean v))))
                     base
                     selection)

          (or (keyword? selection) (string? selection) (symbol? selection))
          (let [k (normalize-category selection)]
            (when-not (category? k)
              (err/fail! :BACKUP_CATEGORY_UNKNOWN
                         "Unknown backup category"
                         {:category k}))
            (assoc base k true))

          (coll? selection)
          (reduce (fn [m k]
                    (let [k (normalize-category k)]
                      (when-not (category? k)
                        (err/fail! :BACKUP_CATEGORY_UNKNOWN
                                   "Unknown backup category"
                                   {:category k}))
                      (assoc m k true)))
                  base
                  selection)

          :else
          (err/fail! :BACKUP_CATEGORY_INVALID
                     "Backup selection must be a category, a collection, or a map"
                     {:selection selection}))]
    (into (sorted-map)
          (filter (comp true? val))
          selected)))

(defn selected-categories
  [selection]
  (vec (sort-by #(.indexOf ^java.util.List category-order %) (keys (normalize-selection selection)))))

(defn items
  "Read a category as a vector of entity maps."
  [state category]
  (let [raw (get-in state [:categories category])]
    (cond
      (nil? raw) []
      (vector? raw) raw
      (sequential? raw) (vec raw)
      (and (map? raw) (vector? (:items raw))) (:items raw)
      (and (map? raw) (sequential? (:items raw))) (vec (:items raw))
      :else (err/fail! :BACKUP_STATE_INVALID
                       "Category state must be a vector or an :items map"
                       {:category category :value raw}))))

(defn normalize-state
  "Fill absent collections so callers can treat every state uniformly."
  [state]
  (let [state (or state {})]
    {:categories (into {}
                       (map (fn [c] [c (items state c)]))
                       category-order)
     :attachments (or (:attachments state) {})
     :imports (or (:imports state) {})
     :permissions (or (:permissions state) {})}))

(defn item-id
  [item]
  (let [id (and (map? item) (:id item))]
    (when (and (string? id) (not (str/blank? id)))
      id)))

(defn item-name
  "A display name used only for same-name conflict detection.  Names are
  compared exactly; leading and trailing spaces are meaningful user input.
  Category adapters wrap some records under `:schedule`, `:entry`, `:card`,
  `:session` or `:run`; the nested display name is part of the item identity."
  [item]
  (when (map? item)
    (let [n (or (:name item) (:title item)
                (get-in item [:schedule :name])
                (get-in item [:schedule :title])
                (get-in item [:entry :name])
                (get-in item [:entry :title])
                (get-in item [:card :name])
                (get-in item [:card :title])
                (get-in item [:session :name])
                (get-in item [:run :name]))]
      (when (and (string? n) (not (str/blank? n))) n))))

;; ---------------------------------------------------------------------------
;; Secret classification and redaction

(def ^:private secret-key-names
  "Normalized key names that carry a credential.  Normalization removes case
  and separators, so `:api-key`, `:apiKey` and `:api_key` are one name."
  #{"apikey" "apikeys" "secret" "secrets" "password" "passwords"
    "token" "tokens" "accesstoken" "refreshtoken" "idtoken" "sessiontoken"
    "authtoken" "credential" "credentials" "authorization" "authheader"
    "bearer" "cookie" "cookies" "sessioncookie" "privatekey" "clientsecret"
    "webdavpassword" "s3secretkey" "accesskeysecret" "secretkey"
    "encryptionkey" "signingkey"})

(def ^:private never-export-key-names
  "Not portable credentials even when the user asks to include API keys."
  #{"loginsession" "loginsessions" "sessioncookie" "cookies"
    "browserstorage" "systemauthorization" "deviceauthorization"
    "oauthsession" "hostauthorization" "ospermission" "sandboxpermission"
    "sessiontoken"})

(defn normalize-key-name
  "Normalize a map key to lower-case letters and digits.

  The namespace is dropped.  Separators are removed so `:api-key`,
  `:apiKey`, `:api_key` and `\"API-KEY\"` all compare equal."
  [k]
  (let [s (cond
            (keyword? k) (name k)
            (symbol? k) (name k)
            (string? k) k
            :else (str k))]
    (-> s str/lower-case (str/replace #"[^a-z0-9]" ""))))

(defn secret-key?
  [k]
  (contains? secret-key-names (normalize-key-name k)))

(defn never-export-key?
  [k]
  (contains? never-export-key-names (normalize-key-name k)))

(defn strip-secrets
  "Recursively remove secret keys from an EDN value.

  Returns `{:value v :removed {:secret n :non-exportable n}}`.  With
  `include-secrets?` true, ordinary API keys remain but non-portable login
  and system-authorization values are still removed."
  [value include-secrets?]
  (let [removed (atom {:secret 0 :non-exportable 0})]
    (letfn [(walk [v]
              (cond
                (map? v)
                (into (empty v)
                      (keep (fn [[k val]]
                              (cond
                                (never-export-key? k)
                                (do (swap! removed update :non-exportable inc)
                                    nil)

                                (and (not include-secrets?) (secret-key? k))
                                (do (swap! removed update :secret inc)
                                    nil)

                                :else
                                [k (walk val)])))
                      v)

                (vector? v)
                (mapv walk v)

                (set? v)
                (into #{} (map walk) v)

                (seq? v)
                (doall (map walk v))

                :else v))]
      {:value (walk value)
       :removed @removed})))

(defn assert-item!
  [category item]
  (when-not (map? item)
    (err/fail! :BACKUP_FORMAT_INVALID
               "Category item must be a map"
               {:category category :item item}))
  (when-not (item-id item)
    (err/fail! :BACKUP_FORMAT_INVALID
               "Category item needs a non-blank string :id"
               {:category category :item item}))
  item)

(defn duplicate-ids
  [items]
  (->> items
       (keep item-id)
       frequencies
       (keep (fn [[id n]] (when (> n 1) id)))
       vec))

(defn assert-unique-ids!
  [category items]
  (let [dups (duplicate-ids items)]
    (when (seq dups)
      (err/fail! :BACKUP_FORMAT_INVALID
                 "Category contains duplicate entity IDs"
                 {:category category :ids dups}))))

;; ---------------------------------------------------------------------------
;; Identity and equality

(def ^:private volatile-keys
  "Fields that a restore legitimately changes.  They never decide whether the
  source content is the same, so a second import of the same backup remains
  idempotent even though the first import paused permissions."
  #{:imported-from
    :imported-at
    :restored?
    :restored-at
    :restored-pending-review?
    :needs-review?
    :pending-review?
    :needs-verification?
    :verified?
    :relink-required?
    :authorized?
    :enabled?
    :paused?
    :next-run
    :write-enabled?
    :installed?
    :needs-configuration?
    :last-test
    :last-tested-at
    :display-name
    :source-note
    :conflict
    :conflict-resolution
    :assistant-placeholder?
    :workspace-relink-required?
    :restored-from-backup?
    :secrets-present?
    :permissions
    :granted-permissions
    :secrets
    :api-key
    :api-keys
    :password
    :token
    :access-token
    :refresh-token
    :credential
    :credentials})

(defn source-view
  "The part of an entity that identifies its content for conflict and
  idempotence checks."
  [item]
  (if (map? item)
    (apply dissoc item volatile-keys)
    item))

(defn same-source?
  [a b]
  (= (source-view a) (source-view b)))

(defn provenance
  [item]
  (when (map? item)
    (:imported-from item)))

(defn imported-from?
  [item backup-id source-id]
  (let [p (provenance item)]
    (and (map? p)
         (= backup-id (:backup-id p))
         (= source-id (:source-id p)))))

(defn copy-id
  "A deterministic copy ID.  Determinism is what makes a repeated merge of
  the same backup skip instead of growing the category."
  [id backup-id]
  (str id "@backup-" (subs (codec/sha256-string (str backup-id ":" id)) 0 10)))

(defn- suffix-name
  [name date]
  (str name "（来自备份 " date "）"))

(defn display-name-with-suffix
  [item date]
  (cond
    (and (string? (:name item)) (not (str/blank? (:name item))))
    (assoc item :name (suffix-name (:name item) date))

    (and (string? (:title item)) (not (str/blank? (:title item))))
    (assoc item :title (suffix-name (:title item) date))

    :else
    (assoc item :display-name (str "来自备份 " date))))

(defn memory-disabled?
  "True when the local copy carries an explicit user decision to stay out of
  retrieval.  The memory domain's authority is `:status`; `:archived?`,
  `:enabled?` and `:user-disabled?` are accepted for older neutral fixtures.
  A restored-but-pending item also stays disabled."
  [item]
  (boolean
   (or (= :archived (:status item))
       (= :deleted (:status item))
       (:archived? item)
       (:deleted-at item)
       (false? (:enabled? item))
       (:user-disabled? item))))

(defn deleted?
  [item]
  (or (= :deleted (:status item))
      (:deleted-at item)
      (true? (:deleted? item))))

(declare parse-instant)

(defn expired-deleted?
  "True when a deleted record is older than the 30-day local retention window."
  [item now retention-days]
  (let [deleted-at (:deleted-at item)]
    (and (string? deleted-at)
         (let [t (parse-instant deleted-at)
               n (parse-instant now)]
           (and t n
                (pos? (.compareTo ^Instant n
                                  (.plus ^Instant t (Duration/ofDays (long retention-days))))))))))

(defn temporary?
  [item]
  (boolean (:temporary? item)))

(defn cache?
  [item]
  (boolean (or (:cache? item) (:rebuildable? item))))

(defn external?
  [item]
  (boolean (or (:external? item) (:external-path item))))

;; ---------------------------------------------------------------------------
;; Time

(defn now
  []
  (str (Instant/now)))

(defn instant?
  [x]
  (instance? Instant x))

(defn parse-instant
  "Parse an ISO-8601 timestamp.  Returns nil instead of throwing for old or
  malformed values; callers decide whether that is a format failure."
  [s]
  (when (string? s)
    (try
      (Instant/parse s)
      (catch Exception _ nil))))

(defn format-instant
  [^Instant instant]
  (str instant))

(defn date-suffix
  [timestamp]
  (let [instant (or (parse-instant timestamp) (Instant/now))]
    (.format (ZonedDateTime/ofInstant instant ZoneOffset/UTC)
             (DateTimeFormatter/ofPattern "yyyy-MM-dd"))))

;; ---------------------------------------------------------------------------
;; Empty state and safe defaults

(def empty-state
  {:categories (zipmap category-order (repeat []))
   :attachments {}
   :imports {}
   :permissions {}})

(def safe-permission-defaults
  "Restored data never receives execution authority implicitly."
  {:schedules :paused
   :auto-backup :disabled
   :device-control :disabled
   :full-access :disabled
   :external-write :disabled
   :background-run :disabled
   :browser-login :relogin-required
   :system-authorization :regrant-required
   :skills :installed-needs-review
   :tools :disabled-needs-review
   :connections :needs-verification
   :external-workspaces :relink-required
   :memories :pending-review})

(defn safe-permission-map
  [source-permissions]
  (merge safe-permission-defaults
         {:source (or source-permissions {})
          :restored-from-backup? true
          :implicit-authority? false}))
