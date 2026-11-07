(ns hc.core.scope.errors
  "Refusal values and the fixed vocabulary of Scope.

  A refusal is a value. Model input never throws. Only a wiring error throws.")

;; Admission refusals happen before the tool runs. The call did not run and
;; no state changed.
(def annotation-missing :SCOPE_ANNOTATION_MISSING)
(def kind-invalid :SCOPE_KIND_INVALID)
(def field-invalid :SCOPE_FIELD_INVALID)
(def field-unexpected :SCOPE_FIELD_UNEXPECTED)
(def uninitialized :SCOPE_UNINITIALIZED)
(def budget-spent :SCOPE_BUDGET_SPENT)

;; Settlement refusals happen after the tool took effect. The effect stands
;; while the annotation is not booked.
(def record-failed :SCOPE_RECORD_FAILED)
(def turn-missing :SCOPE_TURN_MISSING)

;; A per-session Scope is created twice in one process.
(def owner-taken :SCOPE_OWNER_TAKEN)

;; The session table has no Scope for the requested session.
(def not-found :SCOPE_NOT_FOUND)

(def admission-codes
  #{annotation-missing kind-invalid field-invalid field-unexpected
    uninitialized budget-spent})

(def settlement-codes #{record-failed turn-missing})

;; The four kinds are exact. The parser accepts no alias.
(def kinds #{:reset-scope :keep-last :remove-last :me-too})

;; A model turn carries at most one true annotation.
(def true-kinds #{:reset-scope :keep-last :remove-last})

;; These members spend no retention and are never refused for a spent budget.
(def default-exempt-members #{"negotiate" "askPreviousScope"})

;; These members survive history pruning. `assume` is not budget-exempt.
(def default-pinned-members #{"assume" "negotiate" "askPreviousScope"})

;; The member whose call can open a Scope.
(def reset-member "resetScope")

(defn refuse
  "Build one refusal value. `details` is optional."
  ([code message]
   {:ok false :code code :message message})
  ([code message details]
   (cond-> {:ok false :code code :message message}
     (some? details) (assoc :details details))))

(defn argument-error
  "A wiring or programming error. Unlike a refusal, this throws."
  [message]
  (ex-info message {:type ::argument-error}))

(defn scope-refusal?
  "True when `value` is a refusal value."
  [value]
  (and (map? value)
       (false? (:ok value))
       (contains? value :code)))

(defn member-name
  "Return the stable string name of a tool member, or nil for a non-name."
  [member]
  (cond
    (nil? member) nil
    (keyword? member) (name member)
    (symbol? member) (name member)
    (string? member) member
    :else nil))

(defn settled-outcome?
  "True when a settle result booked a beat."
  [result]
  (and (map? result)
       (true? (:ok result))
       (= :settled (:outcome result))))

(defn refused?
  "True when a result is a refusal value."
  [result]
  (scope-refusal? result))
