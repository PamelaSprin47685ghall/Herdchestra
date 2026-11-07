(ns hc.server.artifacts.integration
  "Application assembly for the artifact service.

  The application owns the store and the blob root; this adapter builds the
  service once and exposes the small surface the Owner and snapshot builder
  need.  It does not start a gateway route and does not publish events: the
  caller sends `:announcement` through the existing `CUSTOM` channel.

  The adapter also keeps a small content-ID -> media-type cache.  The gateway
  accepts `:content-type-fn` but has no database access, so this is how an
  artifact's type reaches the independent blob route without teaching the
  gateway about artifact metadata.  The cache is filled by registration and
  by snapshot/list reads; a restart refills it as sessions load."
  (:require [hc.server.artifacts :as artifacts]))

(defn- remember-types!
  [cache artifacts]
  (swap! cache merge
         (into {}
               (keep (fn [artifact]
                       (when (and (:content-id artifact)
                                  (:media-type artifact))
                         [(:content-id artifact) (:media-type artifact)])))
               artifacts))
  cache)

(defn register-and-announce!
  "Register one artifact, publish its announcement, then persist
  `:announced? true`.

  `publish!` must return a truthy value only after the event was handed to
  the real channel.  A failed publish leaves the artifact unannounced so the
  caller can retry; registration itself never claims delivery."
  [component request publish!]
  (let [result (artifacts/register! (:service component) request)
        published (if publish!
                    (publish! (:announcement result))
                    true)]
    (when (false? published)
      (throw (ex-info "artifact announcement was not accepted"
                      {:code :ARTIFACT_ANNOUNCEMENT_FAILED
                       :artifact-id (get-in result [:artifact :id])})))
    (let [marked (artifacts/mark-announced! (:service component)
                                             {:session-id (:session-id request)
                                              :partition (:partition request)
                                              :artifact-id (get-in result [:artifact :id])})]
      (assoc result :artifact (:artifact marked) :announced? true))))

(defn- shell-capture
  [result]
  (or (get-in result [:output :artifact])
      (get-in result [:payload :output :artifact])))

(defn- generic-artifact
  [result]
  (or (get-in result [:artifact])
      (get-in result [:payload :artifact])))

(defn executor-result-request
  "Build an artifact request from one Owner executor result, or nil.

  Shell captures keep their truncation marker.  A generic `:artifact` map
  may carry `:path` or an already-finalized `:content-id`; the caller must
  still provide session/task/call provenance."
  [{:keys [session-id task-id call-id executor result inputs]}]
  (let [capture (shell-capture result)
        generic (generic-artifact result)
        path (or (:path capture) (:path generic))
        content-id (or (:content-id capture) (:content-id generic))]
    (when (or path content-id)
      (cond-> {:session-id session-id
               :source-task-id (or task-id call-id "executor-result")
               :source-call-id call-id
               :producer {:kind :tool
                          :name (or executor "tool")}
               :inputs (vec (or inputs []))}
        path (assoc :file path
                    :name (or (:name generic)
                              "shell-output.log")
                    :kind (or (:kind generic) :log))
        content-id (assoc :content-id content-id
                          :name (or (:name generic)
                                    "artifact")
                          :kind (or (:kind generic) :file))
        (:truncated? capture)
        (assoc :truncated? true
               :truncated-reason
               (or (:truncated-reason generic)
                   (str "shell artifact limit "
                        (or (:limit capture) "unknown")
                        " bytes")))))))

(defn register-executor-result!
  "Register and announce the artifact produced by one executor result.

  Returns nil when the result has no artifact.  This is the composition seam
  for the Owner; it does not depend on the Owner implementation."
  [component {:keys [publish!] :as request}]
  (when-let [artifact-request (executor-result-request request)]
    (register-and-announce! component artifact-request publish!)))

(defn attach
  "Build an artifact service from application components.

  `opts`:
    :store      open current-state store
    :blob-root  managed blob root
    :config     optional application config; `:limits :artifact-bytes`
                bounds one artifact and one session's distinct content

  Returns a map with `:service`, thin callables and `:content-type-fn` for the
  gateway's blob route.  A missing store or blob root is an expected artifact
  error, not a silent no-op."
  [{:keys [store blob-root config]}]
  (let [limit (or (get-in config [:limits :artifact-bytes])
                  artifacts/default-max-bytes)
        service (artifacts/service {:store store
                                    :blob-root blob-root
                                    :max-bytes limit
                                    :session-limit-bytes limit})
        types (atom {})
        list (fn [request]
               (let [result (artifacts/list-artifacts service request)]
                 (remember-types! types (:artifacts result))
                 result))
        register! (fn [request]
                    (let [result (artifacts/register! service request)]
                      (remember-types! types [(:artifact result)])
                      result))]
    {:service service
     :register! register!
     :register-shell-output! (fn [request shell-result]
                               (let [result (artifacts/register-shell-output!
                                             service request shell-result)]
                                 (remember-types! types [(:artifact result)])
                                 result))
     :retract! (fn [request] (artifacts/retract! service request))
     :mark-announced! (fn [request] (artifacts/mark-announced! service request))
     :list list
     :get (fn [request]
            (let [result (artifacts/get-artifact service request)]
              (remember-types! types [result])
              result))
     :download (fn [artifact] (artifacts/download service artifact))
     :open! (fn [artifact] (artifacts/open! service artifact))
     :verify! (fn [artifact] (artifacts/verify! service artifact))
     :content-type-fn (fn [content-id _metadata]
                        (get @types content-id))
     :snapshot (fn [session-id]
                 (let [result (list {:session-id session-id})]
                   {:artifacts (:artifacts result)
                    :version (:version result)}))}))
