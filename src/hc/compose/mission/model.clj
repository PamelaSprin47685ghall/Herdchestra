(ns hc.compose.mission.model
  "Model ownership for the Plan/Reviewer/Swarm role lifecycle.

  Root AGENTS.md §1.1 #8 and §5.4 fix the ownership:

    Steward, Plan, and the centralized Reviewer use the main model.
    The Plan dedicated worker, ordinary swarm workers, and the holder use
    the user-selected task model.

  This namespace is pure data.  A binding records the role, the expected
  model class, the concrete model id chosen by the host, and the identity
  fields that own the binding.  The mission host validates every binding
  against the configured models before it commits."
  (:require [clojure.string :as str]))

(def role->model-role
  "The model class for each task-cycle role."
  {:steward :main
   :plan :main
   :reviewer :main
   :plan-worker :task
   :dedicated-worker :task
   :worker :task
   :holder :task})

(def main-roles
  "Roles that must use the main model."
  #{:steward :plan :reviewer})

(def task-roles
  "Roles that must use the user-selected task model."
  #{:plan-worker :dedicated-worker :worker :holder})

(defn model-role-for
  "Return `:main`, `:task`, or nil for an unknown role."
  [role]
  (get role->model-role (when role (keyword role))))

(defn supports-tools?
  "True only for a declared tool capability.

  A missing, `:unknown`, or `false` value is not support.  The settings
  layer stores true/false/:unknown; older snapshots may carry a set or a
  sequence, and the client catalog may hand back `:known`."
  [capabilities]
  (cond
    (map? capabilities)
    (let [value (or (:tools capabilities) (get capabilities "tools"))]
      (or (true? value) (= :known value)))

    (set? capabilities)
    (contains? capabilities :tools)

    (sequential? capabilities)
    (boolean (some #(= :tools %) capabilities))

    :else false))

(defn role-requires-tools?
  "True for a mission role whose unattended protocol needs its program tool."
  [role]
  (contains? #{:plan :reviewer} (when role (keyword role))))

(defn program-role-requires-tools?
  "True for a program role that cannot run without a tool surface."
  [program-role]
  (contains? #{:clj-plan :clj-review} (when program-role (keyword program-role))))

(defn- invalid-models!
  [message details]
  (throw (ex-info message (assoc details :code :INVALID_MODELS))))

(defn new-models
  "Validate and return the host model configuration.

  Both ids are required and must be non-blank strings.  The host passes the
  concrete provider/model identifier it will actually use; this namespace
  never guesses a default."
  [models]
  (when-not (map? models)
    (invalid-models! "models must be a map with :main and :task ids"
                     {:models models}))
  (doseq [k [:main :task]]
    (let [id (get models k)]
      (when-not (and (string? id) (not (str/blank? id)))
        (invalid-models! (str "models " k " must be a non-blank string")
                         {:key k :value id}))))
  {:main (get models :main)
   :task (get models :task)})

(defn model-for
  "Return the configured model id for ROLE."
  [models role]
  (let [model-role (model-role-for role)]
    (when-not model-role
      (throw (ex-info "unknown mission role"
                      {:code :UNKNOWN_ROLE :role role})))
    (get models model-role)))

(defn bind-role
  "Create a role binding using the host's configured model.

  EXTRA carries identity such as `:tenure-id`, `:agent-id`, `:worker-id`,
  `:milestone`, `:status`, and `:phase`.  The returned map always records
  both `:model-role` and the concrete `:model-id`, so a later audit can
  prove which model the role actually used."
  ([models role]
   (bind-role models role {}))
  ([models role extra]
   (let [role (keyword role)
         model-role (model-role-for role)]
     (when-not model-role
       (throw (ex-info "unknown mission role"
                       {:code :UNKNOWN_ROLE :role role})))
     (merge extra
            {:role role
             :model-role model-role
             :model-id (get models model-role)}))))

(defn binding-errors
  "Return every way BINDING violates the model ownership rule."
  [models binding]
  (let [role (when binding (keyword (:role binding)))
        expected-role (model-role-for role)
        expected-id (when expected-role (get models expected-role))
        actual-role (:model-role binding)
        actual-id (:model-id binding)]
    (cond-> []
      (nil? binding)
      (conj {:code :BINDING_MISSING
             :message "role binding is missing"})

      (and binding (nil? expected-role))
      (conj {:code :UNKNOWN_ROLE
             :message "binding role has no model ownership rule"
             :role role})

      (and binding expected-role (not= expected-role actual-role))
      (conj {:code :MODEL_ROLE_MISMATCH
             :message "binding uses the wrong model class"
             :role role
             :expected expected-role
             :actual actual-role})

      (and binding expected-role (not= expected-id actual-id))
      (conj {:code :MODEL_ID_MISMATCH
             :message "binding model id differs from the configured model"
             :role role
             :expected expected-id
             :actual actual-id}))))

(defn binding-valid?
  [models binding]
  (empty? (binding-errors models binding)))

(defn check-models
  "Return structural violations for the model configuration."
  [models]
  (cond-> []
    (not (map? models))
    (conj {:code :MODELS_NOT_MAP :models models})

    (and (map? models)
         (not (and (string? (:main models))
                   (not (str/blank? (:main models))))))
    (conj {:code :MAIN_MODEL_INVALID :value (:main models)})

    (and (map? models)
         (not (and (string? (:task models))
                   (not (str/blank? (:task models))))))
    (conj {:code :TASK_MODEL_INVALID :value (:task models)})))
