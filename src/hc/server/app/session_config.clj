(ns hc.server.app.session-config
  "Convert the application's provider configuration into the normalized
  session-config shape used by `hc.server.sessions`.

  The app config is intentionally provider-oriented (`:connections` with
  `:kind :openai-compatible` and a vector of models).  Session management
  stores stable connection/model entry IDs and immutable binding snapshots.
  This adapter is a pure conversion: the app calls it per command over the
  committed settings configuration, so a live save is visible without a
  restart.  It never invents a model.  Model pricing is normalized to the
  session shape, so the converted value satisfies
  `hc.server.sessions.config/check-invariants`."
  (:require [clojure.string :as str]
            [hc.server.sessions.connections :as connections]))

(defn- connection-kind
  [kind]
  (case (keyword kind)
    :preset :preset
    :oauth :oauth
    (:custom :openai :openai-compatible :ollama) :custom
    :custom))

(defn- capability-map
  [capabilities]
  (cond
    (map? capabilities) capabilities
    (set? capabilities) (into {} (map (fn [k] [k true]) capabilities))
    (sequential? capabilities) (into {} (map (fn [k] [k true]) capabilities))
    :else {}))

(defn- model-entry
  [model]
  (let [id (str (if (map? model)
                  (or (:id model) (:model-id model))
                  model))
        provider (str (or (:model-name model) (:provider-model-id model) id))]
    {:model-entry-id id
     :provider-model-id provider
     ;; An explicit display name is the operator-facing label.  `:name` is
     ;; the legacy fallback used by older configs and stored snapshots.
     :display-name (or (:display-name model) (:name model) provider)
     :alias nil
     :enabled? (not (false? (:enabled? model)))
     :source :manual
     :availability :unconfirmed
     :capabilities (capability-map (:capabilities model))
     :pricing (connections/normalize-pricing (:pricing model))
     :notes nil
     :created-at 0
     :updated-at 0
     :last-seen-at 0}))

(defn- role-binding
  "Normalize one app role binding to the sessions shape.

  The settings UI writes `:connection`/`:model`, while
  `hc.server.sessions.config` resolves `:connection-id`/`:model-entry-id`.
  Without this conversion a configured main model never reaches a new
  session."
  [role value]
  (when (map? value)
    (let [connection-id (or (:connection-id value) (:connection value))
          model-entry-id (or (:model-entry-id value) (:model value))]
      (when (and (string? (str connection-id))
                 (not (str/blank? (str connection-id)))
                 (string? (str model-entry-id))
                 (not (str/blank? (str model-entry-id))))
        {:connection-id (str connection-id)
         :model-entry-id (str model-entry-id)
         :role role
         :mode (or (:mode value) :explicit)
         :confirmed? (not (false? (:confirmed? value)))
         :set-at 0}))))

(defn- role-bindings
  [models]
  (into {}
        (keep (fn [[role value]]
                (when-let [binding (role-binding role value)]
                  [role binding])))
        (or (:roles models) {})))

(def default-assistant
  "The built-in assistant used when the settings document has none.

  It is configuration data only; no user assistant record is fabricated."
  {:id "default" :assistant-id "default"
   :name "通用助手" :display-name "通用助手" :revision 1})

(defn- normalize-assistants
  [assistants]
  (let [entries (into {}
                      (keep (fn [[id assistant]]
                              (when (map? assistant)
                                (let [id (str id)]
                                  [id (assoc assistant
                                             :id id
                                             :assistant-id
                                             (str (or (:assistant-id assistant)
                                                      id)))]))))
                      (or assistants {}))]
    (if (seq entries)
      entries
      {"default" default-assistant})))

(defn session-config
  "Build a `hc.server.sessions.config` value from app `:models` config.

  ASSISTANTS is the settings document's assistant map (optional).  An empty
  document still exposes the built-in `default` assistant so a new session
  and its worldbook bindings always have a stable assistant identity."
  ([models] (session-config models nil))
  ([models assistants]
  (let [connections (or (:connections models) [])
        entries (mapv (fn [connection]
                        (let [id (str (:id connection))
                              base (or (:base-url connection)
                                       (:custom-base-url connection))
                              model-map (into {}
                                              (map (fn [model]
                                                     (let [entry (model-entry model)]
                                                       [(:model-entry-id entry) entry])))
                                              (:models connection))]
                          [id {:connection-id id
                               :name (or (:name connection) id)
                               :kind (connection-kind (:kind connection))
                               :enabled? (not (false? (:enabled? connection)))
                               :auth-status :unknown
                               :rotation-policy :priority
                               :default-base-url base
                               :custom-base-url base
                               :models model-map
                               :model-order (vec (keys model-map))}]))
                      connections)]
    {:revision 0
     :connections (into {} entries)
     :connection-order (mapv first entries)
     :assistants (normalize-assistants assistants)
     :role-bindings (role-bindings models)
     :created-at 0
     :updated-at 0})))
