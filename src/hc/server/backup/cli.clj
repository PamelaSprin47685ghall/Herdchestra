(ns hc.server.backup.cli
  "Offline backup/restore maintenance entry point.

  The live server refuses whole-store restore because it cannot prove that
  Owners and writers stopped.  This CLI is the explicit offline path:

      clojure -M:server -m hc.server.backup.cli restore \\
        --db-path /data/db --data-dir /data \\
        --backup-id <id> --mode merge

  A failed restore that may have reached storage keeps the next application
  start blocked.  After comparing the journal with the store, record that
  comparison; it never turns the failure into a success:

      clojure -M:server -m hc.server.backup.cli acknowledge \\
        --data-dir /data --maintenance-id <id> --operator <name> \\
        --acknowledge inspect-and-accept-partial \\
        --state-digest <journal digest> --restore-digest <plan digest> \\
        --store-changed true

  Passwords are read from `HC_BACKUP_PASSWORD` or `--password-file`; a password
  is never accepted in argv.  The password file is read exactly (no trimming).
  The command prints one EDN result and exits 0 on success, 1 on refusal or a
  restore that did not complete safely."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hc.server.backup.error :as err]
            [hc.server.backup.maintenance-journal :as journal]
            [hc.server.backup.offline :as offline]))

(defn usage
  []
  (str "Herdchestra offline backup\n\n"
       "  backup|preview|restore|restore-recovery-point|list-backups|list-recovery-points\n"
       "  acknowledge|status\n\n"
       "Options:\n"
       "  --db-path PATH             current-state store path\n"
       "  --data-dir PATH            data directory (default target/recovery roots)\n"
       "  --target-root PATH         backup target directory\n"
       "  --recovery-root PATH       recovery-point directory\n"
       "  --extensions-root PATH     extension catalog/skill root\n"
       "  --blob-root PATH           managed blob root\n"
       "  --workspace-store-root PATH workspace registry/root\n"
       "  --workspace-root PATH      register one linked external workspace\n"
       "  --backup-id ID             backup object ID\n"
       "  --recovery-point-id ID     recovery point ID\n"
       "  --password-env NAME        environment variable (default HC_BACKUP_PASSWORD)\n"
       "  --password-file PATH       read the password exactly from a file\n"
       "  --mode merge|replace       restore mode (default merge)\n"
       "  --categories a,b,c         category keywords\n"
       "  --no-prior-app-commands true|false  assert no prior app commands\n"
       "  --now ISO-8601             fixed sampling time for backup\n"
       "  --encrypt true|false       encrypt a new backup (default true; a\n"
       "                             password is required unless this is false)\n"
       "  --include-secrets true|false  include secrets (encrypted backups only)\n"
       "  --include-deleted true|false  include deleted sessions (default false)\n"
       "  --maintenance-id ID        maintenance record for status/acknowledge\n"
       "  --operator NAME            operator recording an acknowledgement\n"
       "  --acknowledge CODE         inspect-and-accept-partial|inspect-accept-unknown-commit|restore-another-backup\n"
       "  --state-digest HEX         journal digest the operator inspected\n"
       "  --restore-digest HEX       restore-plan digest the operator inspected\n"
       "  --store-changed true|false acknowledge: assert the store no longer matches\n"
       "  --note TEXT                free-form comparison note\n"
       "  --help\n"))

(defn- parse-bool
  [value]
  (case (str/lower-case (str value))
    "true" true
    "false" false
    (throw (ex-info "expected true or false" {:value value}))))

(defn- parse-categories
  [value]
  (mapv keyword (remove str/blank? (str/split (str value) #","))))

(defn- parse-args
  [args]
  (loop [remaining (vec args)
         command nil
         options {}]
    (if (empty? remaining)
      {:command command :options options}
      (let [arg (first remaining)]
        (cond
          (or (= arg "-h") (= arg "--help"))
          (recur (subvec remaining 1) :help options)

          (str/starts-with? arg "--")
          (let [[k v] (if-let [i (str/index-of arg "=")]
                        [(subs arg 2 i) (subs arg (inc i))]
                        [(subs arg 2) nil])
                k (keyword k)]
            (if v
              (recur (subvec remaining 1) command (assoc options k v))
              (if (empty? (subvec remaining 1))
                (throw (ex-info "option needs a value" {:option arg}))
                (recur (subvec remaining 2) command
                       (assoc options k (second remaining))))))

          (nil? command)
          (recur (subvec remaining 1) (keyword arg) options)

          :else
          (throw (ex-info "unexpected argument" {:argument arg})))))))

(defn- password
  [{:keys [password-env password-file]}]
  (cond
    password-file (slurp password-file)
    :else (or (System/getenv (or password-env "HC_BACKUP_PASSWORD"))
              (throw (ex-info "set HC_BACKUP_PASSWORD or pass --password-file"
                              {:error :BACKUP_PASSWORD_REQUIRED})))))

(defn- basename
  [path]
  (some-> (java.nio.file.Path/of (str path) (make-array String 0))
          .getFileName
          str))

(defn- options
  [raw]
  (cond-> (select-keys raw [:db-path :data-dir :target-root :recovery-root
                            :extensions-root :blob-root :workspace-store-root
                            :workspace-root :backup-id :recovery-point-id
                            :now :mode :categories
                            :maintenance-id :operator :acknowledge
                            :state-digest :restore-digest :store-changed :note
                            ;; A programmatic caller may already speak the
                            ;; offline key; keep it instead of silently
                            ;; re-defaulting a restore.
                            :no-prior-app-commands?])
    (and (:workspace-root raw) (not (:workspaces raw)))
    (assoc :workspaces [{:id "app-workspace"
                         :name (or (basename (:workspace-root raw)) "app-workspace")
                         :kind :external
                         :path (str (:workspace-root raw))}])
    (:mode raw) (update :mode keyword)
    (:categories raw) (update :categories parse-categories)
    (contains? raw :no-prior-app-commands)
    (assoc :no-prior-app-commands?
           (parse-bool (:no-prior-app-commands raw)))
    (contains? raw :encrypt)
    (assoc :encrypt? (parse-bool (:encrypt raw)))
    (contains? raw :include-secrets)
    (assoc :include-secrets? (parse-bool (:include-secrets raw)))
    (contains? raw :include-deleted)
    (assoc :include-deleted? (parse-bool (:include-deleted raw)))
    (contains? raw :store-changed) (update :store-changed parse-bool)))

(def ^:private successful-restore-statuses
  #{:complete :no-changes :already-imported :applied})

(defn- journal-config
  [opts]
  (if-let [data-dir (:data-dir opts)]
    {:data-dir (str data-dir)}
    (err/fail! :BACKUP_MAINTENANCE_UNAVAILABLE
               "Journal commands need --data-dir"
               {:data-dir (:data-dir opts)})))

(defn- status
  "Report the durable maintenance journal without changing it.  `:blocks-start?`
  tells an operator whether the next application start will be refused."
  [opts]
  (let [config (journal-config opts)
        loaded (journal/read-consistent config)]
    (if-not (:ok? loaded)
      {:ok? false :error (:error loaded)}
      (let [state (:state loaded)
            blocked (journal/blocking-state state)]
        {:ok? true
         :receipt
         {:path (:path loaded)
          :maintenance-id (:maintenance-id state)
          :status (:status state)
          :needs-recovery? (true? (:needs-recovery? state))
          :commit-unproven? (true? (:commit-unproven? state))
          :restore-attempted? (:restore-attempted? state)
          :acknowledged? (true? (:acknowledged? state))
          :state-digest (when state (journal/state-digest state))
          :restore-digest (journal/restore-plan-digest state)
          :storage (:storage-identity state)
          :storage-now (journal/storage-drift (:db-path opts))
          :error (:error state)
          :blocks-start? (boolean blocked)}}))))

(defn- acknowledge
  "Record an operator's comparison of a failed restore with the current store.
  The journal keeps the failure; only `:acknowledged?` changes, and only when
  the digests the operator read are still current.  A second acknowledgment is
  refused by the journal, not treated as an idempotent retry.

  `--store-changed true` is the operator's assertion that the store no longer
  matches the failure evidence (another backup was restored, or the store was
  repaired).  The journal requires the empty drift vector that this flag
  supplies; without the assertion the CLI refuses before writing."
  [opts]
  (when-not (true? (:store-changed opts))
    (err/fail! :BACKUP_MAINTENANCE_STORE_UNCHANGED
               "Compare the store first; pass --store-changed true only after it no longer matches the failure evidence"
               {:storage (journal/storage-drift (:db-path opts))}))
  (let [config (journal-config opts)
        state (journal/acknowledge!
               config
               {:maintenance-id (some-> (:maintenance-id opts) str)
                :expected-state-digest (:state-digest opts)
                :expected-restore-digest (:restore-digest opts)
                :expected-storage-drift []
                :code (:acknowledge opts)
                :operator (:operator opts)
                :note (:note opts)})]
    {:ok? true
     :receipt {:maintenance-id (:maintenance-id state)
               :status (:status state)
               :acknowledged? (true? (:acknowledged? state))
               :acknowledgment (:acknowledgment state)
               :needs-recovery? (true? (:needs-recovery? state))
               :commit-unproven? (true? (:commit-unproven? state))
               :error (:error state)}}))

(defn- restore-result
  "Map one offline restore receipt to the CLI result.

  Only a terminal success status without an attention/recovery flag is
  `:ok? true`.  A failed restore or a failed command-epoch fence must exit 1
  instead of claiming that the restore completed safely."
  [receipt]
  (if (and (map? receipt)
           (contains? successful-restore-statuses (:status receipt))
           (nil? (:error receipt))
           (not (true? (:needs-attention? receipt)))
           (not (true? (:needs-recovery? receipt))))
    {:ok? true :receipt receipt}
    {:ok? false
     :receipt receipt
     :error (or (:error receipt)
                {:code :BACKUP_RESTORE_FAILED
                 :message (str "offline restore did not complete"
                               (when-let [status (:status receipt)]
                                 (str ": "
                                      (if (keyword? status)
                                        (name status)
                                        status))))})}))

(defn run
  "Run one offline command.  Returns a result map; expected failures are
  returned as `{:ok? false :error ...}`."
  [command raw-options]
  (try
    (let [opts (options raw-options)
          ;; An offline restore is the operator's explicit recovery path.
          ;; A fresh target has no application command gate, so the CLI
          ;; asserts the no-prior-commands fact by default and still allows
          ;; an operator to require a gate with `--no-prior-app-commands
          ;; false`.  A present gate is always fenced from its own
          ;; high-water, so this default never lowers an existing window.
          opts (cond-> opts
                 (and (= command :restore)
                      (not (contains? opts :no-prior-app-commands?)))
                 (assoc :no-prior-app-commands? true))
          need-password? (or (= command :preview) (= command :restore)
                             (and (= command :backup)
                                  (not (false? (:encrypt? opts)))))
          opts (if need-password?
                 (assoc opts :password (password raw-options))
                 opts)]
      (case command
        :backup {:ok? true :receipt (offline/backup! opts)}
        :preview {:ok? true :receipt (offline/preview! opts)}
        :restore (restore-result (offline/restore! opts))
        :restore-recovery-point
        (restore-result (offline/restore-recovery-point! opts))
        :list-backups {:ok? true :receipt (offline/list-backups opts)}
        :list-recovery-points
        {:ok? true :receipt (offline/list-recovery-points opts)}
        :status (status opts)
        :acknowledge (acknowledge opts)
        {:ok? false
         :error {:code :BACKUP_COMMAND_UNKNOWN
                 :message "unknown offline backup command"
                 :details {:command command}}}))
    (catch clojure.lang.ExceptionInfo e
      {:ok? false
       :error (cond-> {:code (or (err/code e) :BACKUP_FAILED)
                       :message (or (ex-message e) "offline backup failed")}
                (ex-data e) (assoc :details (dissoc (ex-data e) :error)))})
    (catch Throwable e
      {:ok? false
       :error {:code :BACKUP_FAILED
               :message (or (ex-message e) (str e))}})))

(defn -main
  [& args]
  (let [parsed (try
                 (assoc (parse-args args) :ok? true)
                 (catch clojure.lang.ExceptionInfo e
                   {:ok? false :error e}))
        command (:command parsed)]
    (cond
      (or (= command :help) (nil? command))
      (do (println (usage))
          (System/exit 0))

      (not (:ok? parsed))
      (do (println (pr-str {:ok? false
                            :error {:code :BACKUP_COMMAND_INVALID
                                    :message (or (ex-message (:error parsed))
                                                 "invalid arguments")
                                    :details (ex-data (:error parsed))}}))
          (System/exit 1))

      :else
      (let [result (run command (:options parsed))]
        (println (pr-str result))
        (System/exit (if (:ok? result) 0 1))))))
