(ns hc.server.extensions.app
  "Concrete application wiring for skills and MCP.

  `start!` attaches one durable catalog and one process-local connection
  manager to the application system map.  Every mutation loads the current
  version, applies the operation, then saves with that expected version.  A
  concurrent writer gets a conflict instead of a lost update.  `stop!` closes
  live MCP connections; it never closes a user's shared service or a process
  it did not start.

  `handle!` is the command-router entry point.  It is intentionally narrow:
  the application router can delegate `:extensions/*` commands here without
  learning skill or MCP state shapes."
  (:require [clojure.string :as str]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.durable :as durable]
            [hc.server.extensions.error :as error]
            [hc.server.extensions.mcp :as mcp]
            [hc.server.extensions.mcp.connection :as connection]
            [hc.server.extensions.mcp.state :as mcp-state]
            [hc.server.extensions.skill :as skill])
  (:import (java.util Base64)))

(def ^:private max-base64-chars (* 28 1024 1024))

(defn- source-from-payload
  "Turn the client's base64 archive envelope into the server's byte source.

  The archive limits are enforced again by `hc.server.extensions.skill.archive`.
  A malformed or oversized base64 value is refused before any file write."
  [source]
  (if (= :archive-base64 (:kind source))
    (let [encoded (str (:base64 source))
          encoded (if (str/starts-with? encoded "data:")
                    (or (second (str/split encoded #"," 2)) "")
                    encoded)]
      (when (> (count encoded) max-base64-chars)
        (error/fail! :SKILL_ARCHIVE_TOO_LARGE
                     "archive base64 exceeds the size limit"
                     {:limit max-base64-chars}))
      (try
        (assoc source
               :kind :archive
               :bytes (.decode (Base64/getDecoder) encoded))
        (catch IllegalArgumentException t
          (error/fail! :SKILL_ARCHIVE_INVALID
                       "archive base64 is invalid"
                       {:reason (.getMessage t)}))))
    source))

(def ^:private browser-source-kinds
  #{:text :files :archive-base64 :url})

(defn- browser-source
  "Browser commands may install from text, an in-memory file map, a base64
  archive or a direct link.  Local directory reads, fetchers and private
  address switches are server-only capabilities."
  [source]
  (let [source (dissoc (or source {}) :allow-private? :fetcher :directory)
        kind (or (:kind source)
                 (cond
                   (some? (:text source)) :text
                   (some? (:files source)) :files
                   (some? (:url source)) :url
                   :else nil))]
    (when-not (contains? browser-source-kinds kind)
      (error/fail! :SKILL_SOURCE_INVALID
                   "browser skill source must be text, files, archive-base64 or url"
                   {:kind kind}))
    (assoc source :kind kind)))

(defn- decoded-source
  "Return `{:ok? true :source ...}` or an expected error map."
  [source]
  (try
    {:ok? true :source (source-from-payload source)}
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        (throw t)))))

(defn- with-browser-source
  "Apply the browser source policy, then call F with the decoded source."
  [source f]
  (let [decoded (try
                  {:ok? true :source (source-from-payload
                                      (browser-source source))}
                  (catch clojure.lang.ExceptionInfo t
                    (if (error/expected? t)
                      {:ok? false :error (error/error-map t)}
                      (throw t))))]
    (if (:ok? decoded)
      (f (:source decoded))
      decoded)))

(defn- default-root
  [system]
  (or (:extensions-root system)
      (when-let [data-dir (:data-dir system)]
        (str data-dir "/extensions"))
      (str (System/getProperty "user.home")
           "/.local/share/herdchestra/extensions")))

(defn- load-and-reset
  [repository]
  (loop [attempt 0]
    (let [loaded (durable/load! repository)]
      (if-not (:ok? loaded)
        loaded
        (let [reset (mcp-state/reset-connections (:catalog loaded))
              saved (durable/save! repository reset (:version loaded))]
          (cond
            (:ok? saved) saved
            (and (= :EXTENSION_CATALOG_CONFLICT (get-in saved [:error :code]))
                 (< attempt 3))
            (recur (inc attempt))
            :else
            (assoc saved :catalog reset :version (:version loaded))))))))

(defn start!
  "Attach the extension component to a system map.  Returns the component.

  A new process has no live transports, so persisted `:connected` observations
  are reset to `:unconnected` before the component is published."
  [system]
  (let [root (default-root system)
        repository (durable/open root)
        loaded (load-and-reset repository)]
    (when-not (:ok? loaded)
      (throw (ex-info "extension catalog could not be opened"
                      (merge {:error :EXTENSION_CATALOG_OPEN_FAILED}
                             (:error loaded)))))
    {:root root
     :repository repository
     :manager (connection/manager)
     :catalog (atom (:catalog loaded))
     :version (atom (:version loaded))
     :started-at (System/currentTimeMillis)}))

(defn stop!
  "Close live MCP connections and persist their disconnected state."
  [component]
  (when component
    (let [loaded (durable/load! (:repository component))
          current (if (:ok? loaded) (:catalog loaded) @(:catalog component))
          updated (connection/shutdown! (:manager component) current)
          saved (when (:ok? loaded)
                  (durable/save! (:repository component) updated (:version loaded)))]
      (reset! (:catalog component) updated)
      (when (:ok? saved)
        (reset! (:version component) (:version saved)))))
  {:stopped? true})

(defn attach!
  [system]
  (assoc system :extensions (start! system)))

(defn detach!
  [system]
  (when-let [component (:extensions system)]
    (stop! component))
  (dissoc system :extensions))

(defn catalog
  [component]
  @(:catalog component))

(defn- apply!
  "Persist one catalog operation without replaying its side effects.

  Skill install/update/delete touch the filesystem, so a version conflict is
  reported to the caller instead of rerunning the operation.  The caller can
  re-read and decide whether to retry."
  [component f]
  (let [loaded (durable/load! (:repository component))]
    (if-not (:ok? loaded)
      loaded
      (let [out (try
                  (f (:catalog loaded))
                  (catch clojure.lang.ExceptionInfo t
                    {:ok? false :error (error/error-map t)}))]
        (if-not (:ok? out)
          (assoc out :catalog (:catalog loaded) :version (:version loaded))
          (let [saved (durable/save! (:repository component)
                                     (:catalog out)
                                     (:version loaded))]
            (when (:ok? saved)
              (reset! (:catalog component) (:catalog saved))
              (reset! (:version component) (:version saved)))
            (if (:ok? saved)
              (merge out saved)
              (merge saved
                     (select-keys out [:applied :deleted :skill :diff :root
                                       :renamed? :name-conflict?])
                     {:catalog (:catalog loaded)
                      :version (:version loaded)
                      :filesystem-applied? true}))))))))

;; Skills -------------------------------------------------------------------

(defn install-skill!
  ([component source] (install-skill! component source {}))
  ([component source opts]
   (let [decoded (decoded-source source)]
     (if-not (:ok? decoded)
       decoded
       (apply! component
               (fn [current]
                 (skill/install-source! current (:root component)
                                        (:source decoded) opts)))))))

(defn preview-skill!
  "Preview one source without writing or changing the catalog."
  [component source]
  (let [decoded (decoded-source source)]
    (if-not (:ok? decoded)
      decoded
      (let [preview (skill/preview (:source decoded))]
        (if-not (:ok? preview)
          preview
          {:ok? true
           :candidate (skill/public-candidate (:candidate preview))})))))

(defn preview-skill-candidates!
  "Preview every skill candidate in a source."
  [component source]
  (let [decoded (decoded-source source)]
    (if-not (:ok? decoded)
      decoded
      (let [preview (skill/preview-candidates (:source decoded))]
        (if-not (:ok? preview)
          preview
          {:ok? true
           :candidates (mapv skill/public-candidate (:candidates preview))})))))

(defn enable-skill!
  [component id enabled?]
  (apply! component #(skill/set-enabled! % id enabled?)))

(defn bind-skill!
  [component id assistant-id]
  (apply! component #(skill/bind! % id assistant-id)))

(defn unbind-skill!
  [component id assistant-id]
  (apply! component #(skill/unbind! % id assistant-id)))

(defn delete-skill!
  [component id]
  (apply! component #(skill/delete! % (:root component) id)))

(defn copy-skill!
  ([component id] (copy-skill! component id {}))
  ([component id opts]
   (apply! component #(skill/copy! % (:root component) id opts))))

(defn read-skill-file!
  [component id path]
  (skill/read-file (catalog component) id path))

(defn- wire-diff
  "Keep only reviewable three-way metadata on the wire.

  The raw diff also carries upstream bytes and deletion sets; those stay on
  the server.  The client needs paths, statuses, conflicts and counts."
  [diff]
  (when diff
    (select-keys diff [:paths :conflicts :summary])))

(defn compare-skill-update!
  "Preview SOURCE and compare it with the installed skill without writing."
  [component id source]
  (let [decoded (decoded-source source)]
    (if-not (:ok? decoded)
      decoded
      (let [preview (skill/preview (:source decoded))]
        (if-not (:ok? preview)
          preview
          (let [result (skill/compare-update (catalog component) id
                                             (:candidate preview))]
            (if (:ok? result)
              {:ok? true
               :diff (wire-diff (:diff result))
               :skill (select-keys (:skill result)
                                   [:id :name :description :version :enabled?
                                    :bindings :local-modified? :review-required?])}
              result)))))))

(defn apply-skill-update!
  ([component id candidate]
   (apply-skill-update! component id candidate {}))
  ([component id candidate opts]
   (let [validated (skill/validate-candidate candidate)]
     (if-not (:ok? validated)
       validated
       (let [result (apply! component
                            #(skill/apply-update! % id (:candidate validated) opts))]
         (if (:ok? result)
           (update result :diff wire-diff)
           result))))))

(defn update-skill-from-source!
  "Preview SOURCE and apply it with the requested conflict strategy."
  ([component id source] (update-skill-from-source! component id source {}))
  ([component id source opts]
   (let [decoded (decoded-source source)]
     (if-not (:ok? decoded)
       decoded
       (let [preview (skill/preview (:source decoded))]
         (if-not (:ok? preview)
           preview
           (apply-skill-update! component id (:candidate preview) opts)))))))

;; MCP configuration --------------------------------------------------------

(defn add-mcp-server!
  [component server]
  (apply! component #(mcp/add! % server)))

(declare close-mcp!)

(defn update-mcp-server!
  [component id server]
  (close-mcp! component id)
  (apply! component #(mcp/update! % id server)))

(defn preview-mcp-import!
  "Parse an import without exposing raw secret headers or command lines."
  [component text]
  (let [preview (mcp/import-preview text)]
    (if-not (:ok? preview)
      preview
      {:ok? true
       :format (:format preview)
       :candidates (mapv #(select-keys %
                                       [:name :transport-type :target
                                        :has-secrets? :unknown-fields :warnings])
                         (:candidates preview))
       :failed (:failed preview)
       :warnings (:warnings preview)})))

(defn import-mcp-text!
  "Preview and import a configuration text.  Every new server stays disabled."
  ([component text] (import-mcp-text! component text {}))
  ([component text opts]
   (let [preview (mcp/import-preview text)]
     (if-not (:ok? preview)
       preview
       (let [selected (some-> (:selected opts) seq set)
             selected (when selected (set (map str selected)))
             preview (if selected
                       (update preview :candidates
                               (fn [candidates]
                                 (filterv #(contains? selected (str (:name %)))
                                          candidates)))
                       preview)]
         (apply! component #(mcp/import! % preview
                                         (dissoc opts :selected))))))))

(defn remove-mcp-server!
  [component id]
  (close-mcp! component id)
  (apply! component #(mcp-state/remove-server! % id)))

(defn disconnect-mcp-server!
  [component id]
  (close-mcp! component id)
  (apply! component #(mcp-state/disconnect! % id)))

(defn enable-mcp-server!
  [component id enabled?]
  (when-not enabled? (close-mcp! component id))
  (apply! component #(mcp-state/set-server-enabled! % id enabled?)))

(defn set-mcp-tool-enabled!
  ([component id tool-name enabled?]
   (set-mcp-tool-enabled! component id tool-name enabled? {}))
  ([component id tool-name enabled? opts]
   (apply! component #(mcp-state/set-tool-enabled! % id tool-name enabled? opts))))

(defn approve-mcp-tool!
  [component id tool-name]
  (apply! component #(mcp-state/approve-tool! % id tool-name)))

(defn begin-mcp-login!
  [component id]
  (close-mcp! component id)
  (apply! component #(mcp-state/begin-login! % id)))

(defn complete-mcp-login!
  [component id token account]
  (close-mcp! component id)
  (apply! component #(mcp-state/complete-login! % id token account)))

(defn logout-mcp!
  [component id]
  (close-mcp! component id)
  (apply! component #(mcp-state/logout! % id)))

(defn- persist-result!
  [component loaded result]
  (if (= (:catalog result) (:catalog loaded))
    (assoc result :catalog (:catalog loaded) :version (:version loaded))
    (let [saved (durable/save! (:repository component)
                               (:catalog result)
                               (:version loaded))]
      (if (:ok? saved)
        (do (reset! (:catalog component) (:catalog saved))
            (reset! (:version component) (:version saved))
            (assoc result :catalog (:catalog saved)))
        (assoc saved :catalog (:catalog loaded) :version (:version loaded))))))

(defn probe-mcp!
  "Force a fresh handshake and tool list, then persist the observation."
  ([component id] (probe-mcp! component id {}))
  ([component id opts]
   (let [loaded (durable/load! (:repository component))
         current (if (:ok? loaded) (:catalog loaded) (catalog component))
         server (catalog/server current id)]
     (cond
       (not (:ok? loaded))
       loaded

       (nil? server)
       {:ok? false :catalog current
        :error {:code :MCP_SERVER_NOT_FOUND
                :message "server is not installed" :details {:id id}}}

       :else
       (let [result (connection/refresh! (:manager component) current id opts)]
         (persist-result! component loaded result))))))

(defn open-mcp!
  "Reuse a live connection or open one; persist success or failure."
  ([component id] (open-mcp! component id {}))
  ([component id opts]
   (let [loaded (durable/load! (:repository component))
         current (if (:ok? loaded) (:catalog loaded) (catalog component))
         server (catalog/server current id)]
     (cond
       (not (:ok? loaded))
       loaded

       (nil? server)
       {:ok? false :catalog current
        :error {:code :MCP_SERVER_NOT_FOUND
                :message "server is not installed" :details {:id id}}}

       :else
       (let [result (connection/open! (:manager component) current id opts)]
         (persist-result! component loaded result))))))

(defn close-mcp!
  [component id]
  (let [loaded (durable/load! (:repository component))]
    (if-not (:ok? loaded)
      loaded
      (let [result (connection/close! (:manager component) (:catalog loaded) id)]
        (if (:ok? result)
          (let [saved (durable/save! (:repository component) (:catalog result)
                                     (:version loaded))]
            (if (:ok? saved)
              (do (reset! (:catalog component) (:catalog saved))
                  (reset! (:version component) (:version saved))
                  {:ok? true :catalog (:catalog saved)})
              saved))
          result)))))

(defn refresh-mcp!
  [component id]
  (probe-mcp! component id {}))

(defn call-mcp-tool!
  "Call a tool.  Authorization comes only from the configured scope and role."
  ([component scope server-id tool-name arguments]
   (call-mcp-tool! component scope server-id tool-name arguments {}))
  ([component scope server-id tool-name arguments opts]
   (let [loaded (durable/load! (:repository component))]
     (if-not (:ok? loaded)
       loaded
       (let [current (:catalog loaded)
             opened (if (connection/live? (:manager component) server-id)
                      {:ok? true :catalog current}
                      (connection/open! (:manager component) current server-id opts))]
         (if-not (:ok? opened)
           (persist-result! component loaded opened)
           (let [saved (if (= (:catalog opened) current)
                         {:ok? true :catalog current :version (:version loaded)}
                         (durable/save! (:repository component)
                                        (:catalog opened)
                                        (:version loaded)))]
             (if-not (:ok? saved)
               saved
               (let [started (connection/start-call! (:manager component)
                                                     (:catalog saved) scope
                                                     server-id tool-name arguments opts)]
                 (if-not (:ok? started)
                   started
                   (let [result (connection/await! (:handle started)
                                                   (or (:await-ms opts) 10000))]
                     (reset! (:catalog component) (:catalog saved))
                     (reset! (:version component) (:version saved))
                     (assoc result :catalog (:catalog saved)))))))))))))

(defn cancel-mcp-call!
  [component handle reason]
  (let [loaded (durable/load! (:repository component))]
    (if-not (:ok? loaded)
      loaded
      (let [result (connection/cancel! (:manager component) (:catalog loaded)
                                       handle reason)]
        (if (:ok? result)
          (let [saved (durable/save! (:repository component) (:catalog result)
                                     (:version loaded))]
            (if (:ok? saved)
              (do (reset! (:catalog component) (:catalog saved))
                  (reset! (:version component) (:version saved))
                  (assoc result :catalog (:catalog saved)))
              saved))
          result)))))

(defn status
  [component]
  (let [cat (catalog component)]
    (assoc (catalog/summary cat)
           :version @(:version component)
           :connections (vec (connection/connection-ids (:manager component)))
           :calls (vec (connection/calls (:manager component)))
           :root (:root component))))

(defn view
  ([component] (view component {}))
  ([component scope]
   (let [cat (catalog component)
         scope (or scope {})]
     {:version @(:version component)
      :skills (skill/list-skills cat)
      :mcp (mcp-state/catalog-view cat)
      :connections (vec (connection/connection-ids (:manager component)))
      :session-id (:session-id scope)
      :session-scope (or (:session scope) {:mode :follow})
      :resolved-skills (skill/public-resolution
                        (skill/resolve-skills cat scope))})))

(defn- capability-limit
  [value]
  (cond
    (set? value) value
    (sequential? value) (set value)
    (map? value) (set (or (:capabilities value)
                          (:mcp value)
                          (:mcp-capabilities value)
                          []))
    :else nil))

(defn- request-scope
  "Use a server-derived scope when the caller supplies one.  A browser
  payload may choose the assistant/session selection, but it cannot assert a
  role or capability set."
  [payload opts]
  (let [trusted (:scope opts)
        client (or (:scope payload) {})
        scope (or trusted
                  (-> (select-keys client [:assistant-servers :session
                                           :assistant-id])
                      (assoc :role :viewer)))
        limit (capability-limit (:capability-scope opts))]
    (cond-> scope
      (some? limit) (assoc :capability-limit limit))))

(defn handle!
  "Command-router entry point for `:extensions/*` payloads.

  The payload carries `:kind` plus operation fields.  Tool calls receive
  `:scope` with the session selection and role/capabilities.  A trusted
  caller may pass `{:scope ... :capability-scope ...}` as the third argument;
  a browser payload's role and capability claims are ignored.  There is no
  per-tool approval in always-authorized mode."
  ([component payload]
   (handle! component payload {}))
  ([component payload opts]
  (let [kind (:kind payload)
        scope (request-scope payload opts)
        result (case kind
      :extensions/status (status component)
      :extensions/view (view component scope)
      :extensions/skill/preview
      (with-browser-source (:source payload)
        #(preview-skill! component %))
      :extensions/skill/candidates
      (with-browser-source (:source payload)
        #(preview-skill-candidates! component %))
      :extensions/skill/install
      (with-browser-source (:source payload)
        #(install-skill! component % (:opts payload)))
      :extensions/skill/enable
      (enable-skill! component (:id payload) (:enabled? payload))
      :extensions/skill/bind
      (bind-skill! component (:id payload) (:assistant-id payload))
      :extensions/skill/unbind
      (unbind-skill! component (:id payload) (:assistant-id payload))
      :extensions/skill/delete
      (delete-skill! component (:id payload))
      :extensions/skill/copy
      (copy-skill! component (:id payload) (:opts payload))
      :extensions/skill/file
      (read-skill-file! component (:id payload) (:path payload))
      :extensions/skill/compare
      (with-browser-source (:source payload)
        #(compare-skill-update! component (:id payload) %))
      :extensions/skill/update
      (if (contains? payload :candidate)
        (apply-skill-update! component (:id payload) (:candidate payload)
                             (select-keys payload [:strategy]))
        (with-browser-source (:source payload)
          #(update-skill-from-source! component (:id payload) %
                                      (select-keys payload [:strategy]))))
      :extensions/skills/resolve
      (skill/resolve-skills (catalog component) scope)
      :extensions/mcp/add
      (add-mcp-server! component (:server payload))
      :extensions/mcp/update
      (update-mcp-server! component (:id payload) (:server payload))
      :extensions/mcp/import-preview
      (preview-mcp-import! component (:text payload))
      :extensions/mcp/import
      (import-mcp-text! component (:text payload) (:opts payload))
      :extensions/mcp/remove
      (remove-mcp-server! component (:id payload))
      :extensions/mcp/disconnect
      (disconnect-mcp-server! component (:id payload))
      :extensions/mcp/enable
      (enable-mcp-server! component (:id payload) (:enabled? payload))
      :extensions/mcp/login
      (begin-mcp-login! component (:id payload))
      :extensions/mcp/login-complete
      (complete-mcp-login! component (:id payload) (:token payload)
                           (:account payload))
      :extensions/mcp/logout
      (logout-mcp! component (:id payload))
      :extensions/mcp/tool-enable
      (set-mcp-tool-enabled! component (:id payload) (:tool payload)
                             (:enabled? payload) (:opts payload))
      :extensions/mcp/tool-approve
      (approve-mcp-tool! component (:id payload) (:tool payload))
      :extensions/mcp/open
      (open-mcp! component (:id payload) (:opts payload))
      :extensions/mcp/refresh
      (refresh-mcp! component (:id payload))
      :extensions/mcp/probe
      (probe-mcp! component (:id payload) (:opts payload))
      :extensions/mcp/close
      (close-mcp! component (:id payload))
      :extensions/mcp/tools
      (mcp-state/resolve-tools (catalog component) scope)
      :extensions/mcp/call
      (call-mcp-tool! component scope (:id payload) (:tool payload)
                      (:arguments payload) (:opts payload))
      :extensions/mcp/cancel
      (cancel-mcp-call! component (:handle payload) (:reason payload))
      {:ok? false
       :error {:code :EXTENSION_COMMAND_UNKNOWN
               :message "unknown extension command"
               :details {:kind kind}}})]
    (if (map? result)
      (let [ok? (if (contains? result :ok?) (:ok? result) true)
            result (if (contains? result :ok?) result (assoc result :ok? ok?))]
        (assoc result
               :receipt {:status (if ok? :accepted :rejected)
                         :result (select-keys result
                                              [:ok? :version :skills :mcp
                                               :connections :calls :root
                                               :server :skill :diff :applied
                                               :deleted :renamed? :name-conflict?
                                               :format :candidates :created
                                               :updated :skipped :warnings
                                               :partial? :candidate :path :text
                                               :size :sha256
                                               :tools :excluded :active :blocked
                                               :outcome :result :call-id :tool
                                               :server-id :sent? :duration-ms
                                               :requested? :connection-closed?
                                               :note :reason
                                               :error])}))
      result))))
