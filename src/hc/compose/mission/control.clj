(ns hc.compose.mission.control
  "Adapters from program control primitives to Owner mission commands.

  `clj-review` calls `(review {:findings ...})` and `clj-plan` writes the
  draft through the workspace.  The program runtime and workspace bridge do
  not know Owner command envelopes.  These helpers produce the exact payload
  the app adapter must submit:

  - review -> Owner `:review/submit` (or the internal `:review/submit`
    message);
  - draft write -> Owner `:plan/write-draft`.

  The helpers are pure.  They do not submit, commit, or write files."
  (:require [clojure.string :as str]))

(def draft-path-pattern
  #"plans/[a-z0-9-]{1,80}\.md")

(defn draft-path?
  [path]
  (boolean (and (string? path) (re-matches draft-path-pattern path))))

(defn review-lane
  "Infer the review lane from the program context role.

  The program runtime names roles `:clj-plan`/`:clj-review`; the mission
  names them `:plan`/`:reviewer`.  Accept both so a context without an
  explicit `:lane` cannot send a Plan review to the execution lane."
  [ctx]
  (if (contains? #{:plan :clj-plan} (:role ctx)) :plan :execution))

(defn review-command
  "Return an Owner `:review/submit` command payload.

  CTX is the program control context; it should carry `:role`,
  `:tenure-id`, `:lane`, and `:call-id`.  REQUEST is the primitive request
  with `:findings`.  The context identity wins over anything the model put
  in the primitive request: the model may describe what it found, but it
  does not choose which tenure or lane reviews it.  The app must wrap the
  result with its command-id and command-epoch, then call `submit-command!`."
  [ctx request]
  (let [call-id (or (:call-id ctx) (:call-id request))]
    (cond-> {:kind :review/submit
             :lane (or (:lane ctx) (:lane request) (review-lane ctx))
             :tenure-id (or (:tenure-id ctx) (:tenure-id request))
             :findings (:findings request)}
      call-id (assoc :call-id call-id))))

(defn review-message
  "Return an internal Owner `:review/submit` message.

  The internal message path bypasses the browser-originated review refusal
  and is the trusted Reviewer tool path."
  [ctx request]
  (assoc (review-command ctx request)
         :type :review/submit))

(defn draft-command
  "Return an Owner `:plan/write-draft` command payload.

  TENURE-ID is the Plan tenure pinned on the tool call that wrote the draft.
  The mission refuses a draft whose tenure is no longer current, so a late
  program cannot replace a successor's draft."
  [path content tenure-id]
  (when-not (draft-path? path)
    (throw (ex-info "not a Plan draft path"
                    {:code :INVALID_DRAFT_PATH :path path})))
  (when-not (string? content)
    (throw (ex-info "draft content must be a string"
                    {:code :INVALID_DRAFT_CONTENT :path path})))
  (when-not (and (string? tenure-id) (not (str/blank? tenure-id)))
    (throw (ex-info "draft write requires the pinned Plan tenure"
                    {:code :TENURE_REQUIRED
                     :path path
                     :tenure-id tenure-id})))
  {:kind :plan/write-draft
   :path path
   :content content
   :tenure-id tenure-id})

(defn applied-draft-paths
  "Extract draft paths from a `hc.compose.workspace/run-program!` result.

  The result may contain `:effects` or `:receipts` entries of type
  `:files/applied` with `:changed-paths`.  Only `plans/<name>.md` paths are
  returned, in stable order.  The app reads the final content from the
  worktree and submits `draft-command`."
  [result]
  (->> (concat (:effects result) (:receipts result))
       (keep (fn [entry]
               (when (= :files/applied (:type entry))
                 (:changed-paths entry))))
       (mapcat identity)
       (filter draft-path?)
       distinct
       vec))
