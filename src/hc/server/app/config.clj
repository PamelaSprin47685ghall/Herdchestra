(ns hc.server.app.config
  "Launch configuration for the integrated Herdchestra server.

  Precedence, lowest to highest:

    1. `defaults`
    2. an EDN file named by `--config` or `HC_CONFIG`
    3. environment overrides (`HC_*`)
    4. command-line arguments

  The file is a plain EDN map.  Unknown top-level keys are preserved so a
  later component can read its own section without this namespace becoming a
  second schema authority.  Known scalar keys, the `:fleet` retention
  section, and the `:gateway` transport/authorization section are validated
  here because a wrong port, a blank workspace root, a nonsensical retention
  interval, an origin policy that Sente cannot accept, or a remote mode
  without authentication must fail before any component opens storage or
  binds a socket."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.server.gateway.auth :as gateway-auth]
            [hc.version :as version])
  (:import (java.io File)
           (java.nio.file Path Paths)))

(def ^:private os-name
  (str/lower-case (System/getProperty "os.name" "")))

(defn default-data-dir
  "Return the per-user data directory for this operating system."
  []
  (let [home (System/getProperty "user.home")]
    (cond
      (str/includes? os-name "win")
      (str (or (System/getenv "LOCALAPPDATA")
               (str home File/separator "AppData" File/separator "Local"))
           File/separator "Herdchestra")

      (str/includes? os-name "mac")
      (str home "/Library/Application Support/Herdchestra")

      :else
      (str home "/.local/share/herdchestra"))))

(def defaults
  "Process defaults.  Port 0 is accepted and means an ephemeral port."
  {:host "127.0.0.1"
   :port 7575
   :data-dir (default-data-dir)
   :workspace-root "."
   :blob-root nil
   :worktree-root nil
   :artifact-root nil
   :static-root "resources/public"
   :log-level :info
   :open-browser? false
   :model-stream? true
   :check? false
   :browser {:endpoint nil}
   ;; §2.5 unattended continuation is on by default.  `false` (config key or
   ;; HC_NUDGE_ENABLED=false) suppresses every enabled Nudge decision; each
   ;; suppressed stop is recorded as `:nudge-suppressed` in the Owner's
   ;; `:turn-history`, giving automation an observable idle boundary.
   :nudge-enabled? true
   ;; §2.5 unattended continuation has a host round budget: after this many
   ;; consecutive Nudge rounds for one actor the Owner records
   ;; `:round-boundary`, clears the turn, and the session can go idle
   ;; instead of looping while the Plan ledger stays open.  `nil` uses the
   ;; policy default (`hc.compose.nudge.policy/default-round-budget`).
   :nudge-round-budget nil
   ;; `:all` means "same-origin only", never a wildcard.  The remaining
   ;; defaults fail closed: no extra hosts and no remote peers.
   ;; `:allow-remote? true` is rejected by `validate-gateway`; EDN cannot
   ;; supply the authentication hook remote peers require.
   :gateway {:allowed-origins :all
             :trusted-hosts #{}
             :allow-remote? false
             :max-pending 4096
             :max-state-object-bytes (* 64 1024 1024)
             :max-state-objects 128}
   ;; Model configuration is owned by the settings document.  The launch
   ;; file may supply the same `:connections`/`:roles` shape; an empty
   ;; section means "not configured in the launch file".  The former
   ;; `{:main nil :task nil}` placeholders leaked into the model adapter's
   ;; closed schema and made every saved model look unconfigured.
   :models {}
   :storage {:engine :datalevin}
   ;; Root AGENTS.md §2.2/§5.3 retain joined/abandoned worktrees for seven
   ;; days.  The sweep is hourly: retention is measured in days, so polling
   ;; Git more often only adds load without freeing anything sooner.
   :fleet {:master-ref "master"
           :retention-ms (* 7 24 60 60 1000)
           :gc-interval-ms (* 60 60 1000)
           :gc-run-immediately? true
           ;; A workspace that is not a Git repository is reported as
           ;; `:fleet-unavailable`, not as a construction failure.  Set this
           ;; false only for isolated tests that do not need Git preflight.
           :preflight? true
           ;; `nil` derives `<data-dir>/fleet/state.edn` during normalize.
           :state-file nil}
   :limits {:inline-bytes (* 1024 1024)
            :artifact-bytes (* 64 1024 1024)}})

;; ---------------------------------------------------------------------------
;; CLI

(def ^:private cli-key-aliases
  {:open-browser :open-browser?
   :check :check?
   :help :help?})

(defn- cli-key
  [name]
  (get cli-key-aliases name name))

(def cli-spec
  "Recognized long options.  A value-taking option must be followed by a value."
  {:config :value
   :host :value
   :port :value
   :data-dir :value
   :workspace-root :value
   :blob-root :value
   :worktree-root :value
   :artifact-root :value
   :static-root :value
   :log-level :value
   :send-command :value
   :session-id :value
   :open-browser? :flag
   :check? :flag
   :help? :flag})

(defn usage
  "Human-readable usage text."
  []
  (str "Herdchestra " version/version " server\n\n"
       "  clojure -M:server -m hc.server.app [options]\n\n"
       "Options:\n"
       "  --config PATH          EDN configuration file\n"
       "  --host HOST            bind address (default 127.0.0.1)\n"
       "  --port N               TCP port, 0 selects an ephemeral port\n"
       "  --data-dir PATH        database and recovery data\n"
       "  --workspace-root PATH  workspace the agents may edit\n"
       "  --blob-root PATH       managed large-object store\n"
       "  --worktree-root PATH   parent directory for swarm worktrees\n"
       "  --artifact-root PATH   managed command output directory\n"
       "  --static-root PATH     compiled browser client directory\n"
       "  --log-level LEVEL      debug, info, warn or error\n"
       "  --open-browser         ask the desktop to open the server URL\n"
       "  --session-id ID        focus this session in the running instance\n"
       "  --send-command EDN     send one command payload to the running instance\n"
       "  --check                start, print health, then stop\n"
       "  --help                 print this text\n"))

(defn- parse-port
  [value]
  (let [n (try (Long/parseLong (str value))
               (catch NumberFormatException _
                 (throw (ex-info (str "port must be an integer, got " (pr-str value))
                                 {:error/kind :config-invalid
                                  :field :port
                                  :value value}))))]
    (when-not (<= 0 n 65535)
      (throw (ex-info "port must be between 0 and 65535"
                      {:error/kind :config-invalid
                       :field :port
                       :value value})))
    n))

(defn- parse-log-level
  [value]
  (let [level (keyword (str/lower-case (if (keyword? value)
                                         (name value)
                                         (str value))))]
    (when-not (#{:debug :info :warn :error} level)
      (throw (ex-info "log-level must be debug, info, warn or error"
                      {:error/kind :config-invalid
                       :field :log-level
                       :value value})))
    level))

(defn- take-option
  [args index spec]
  (let [arg (nth args index)
        name (keyword (subs arg 2))
        key (cli-key name)
        kind (or (get spec key) (get spec name))]
    (when-not kind
      (throw (ex-info (str "unknown option " arg)
                      {:error/kind :config-invalid
                       :field :argument
                       :value arg})))
    (if (= :flag kind)
      {:key key :value true :next (inc index)}
      (let [next-index (inc index)]
        (when (>= next-index (count args))
          (throw (ex-info (str "option " arg " needs a value")
                          {:error/kind :config-invalid
                           :field :argument
                           :value arg})))
        {:key key
         :value (nth args next-index)
         :next (inc next-index)}))))

(defn parse-args
  "Parse command-line arguments into a plain option map.

  Accepts `--key value`, `--key=value` and boolean flags.  Throws an
  `ex-info` with `:error/kind :config-invalid` on malformed input."
  [args]
  (loop [remaining (vec args)
         index 0
         options {}]
    (if (>= index (count remaining))
      options
      (let [arg (nth remaining index)]
        (cond
          (or (= arg "-h") (= arg "--help"))
          (recur remaining (inc index) (assoc options :help? true))

          (str/starts-with? arg "--")
          (if-let [[_ key value] (re-matches #"--([^=]+)=(.*)" arg)]
            (let [k (keyword key)
                  mapped (cli-key k)
                  kind (or (get cli-spec mapped) (get cli-spec k))]
              (when-not kind
                (throw (ex-info (str "unknown option " arg)
                                {:error/kind :config-invalid
                                 :field :argument
                                 :value arg})))
              (recur remaining
                     (inc index)
                     (assoc options mapped
                            (if (= :flag kind)
                              (= "true" value)
                              value))))
            (let [{:keys [key value next]} (take-option remaining index cli-spec)]
              (recur remaining next (assoc options key value))))

          :else
          (throw (ex-info (str "unexpected argument " (pr-str arg))
                          {:error/kind :config-invalid
                           :field :argument
                           :value arg})))))))

;; ---------------------------------------------------------------------------
;; Files and environment

(defn read-config-file
  "Read one EDN configuration map.  Returns an empty map when PATH is nil.

  Exactly one form is accepted.  Trailing non-whitespace is a configuration
  error instead of a silently ignored second document."
  [path]
  (if (str/blank? (str path))
    {}
    (let [file (io/file path)]
      (when-not (.isFile file)
        (throw (ex-info (str "config file does not exist: " path)
                        {:error/kind :config-invalid
                         :field :config
                         :value (str path)})))
      (let [value (try
                    (with-open [reader (java.io.PushbackReader. (io/reader file))]
                      (let [form (edn/read {:readers {}
                                            :default tagged-literal}
                                           reader)]
                        (loop [c (.read reader)]
                          (when (and (not (neg? c))
                                     (not (Character/isWhitespace (char c))))
                            (throw (ex-info
                                    "config file contains trailing EDN forms"
                                    {:error/kind :config-invalid
                                     :field :config
                                     :value (str path)})))
                          (when-not (neg? c)
                            (recur (.read reader))))
                        form))
                    (catch clojure.lang.ExceptionInfo e
                      (throw e))
                    (catch Exception e
                      (throw (ex-info (str "cannot read config file " path ": "
                                           (.getMessage e))
                                      {:error/kind :config-invalid
                                       :field :config
                                       :value (str path)}
                                      e))))]
        (when-not (map? value)
          (throw (ex-info "config file must contain one EDN map"
                          {:error/kind :config-invalid
                           :field :config
                           :value (str path)})))
        value))))

(def ^:private env-keys
  [[:host "HC_HOST"]
   [:port "HC_PORT"]
   [:data-dir "HC_DATA_DIR"]
   [:workspace-root "HC_WORKSPACE_ROOT"]
   [:blob-root "HC_BLOB_ROOT"]
   [:worktree-root "HC_WORKTREE_ROOT"]
   [:artifact-root "HC_ARTIFACT_ROOT"]
   [:static-root "HC_STATIC_ROOT"]
   [:log-level "HC_LOG_LEVEL"]
   [:open-browser? "HC_OPEN_BROWSER"]
   [:browser-endpoint "HC_BROWSER_ENDPOINT"]
   [:check? "HC_CHECK"]
   [:nudge-enabled? "HC_NUDGE_ENABLED"]])

(defn parse-env-boolean
  "Parse a strict boolean environment value.

  `true`/`1`/`yes`/`on` and `false`/`0`/`no`/`off` are accepted; anything
  else is a configuration error.  A raw string is never truthy by accident."
  [field value]
  (let [text (str/lower-case (str/trim (str value)))]
    (case text
      ("true" "1" "yes" "on") true
      ("false" "0" "no" "off") false
      (throw (ex-info (str field " must be a boolean, got " (pr-str value))
                      {:error/kind :config-invalid
                       :field field
                       :value value})))))

(defn env-options
  "Read `HC_*` overrides from ENV.  Blank values are ignored."
  [env]
  (reduce (fn [options [key env-name]]
            (let [value (get env env-name)]
              (if (str/blank? value)
                options
                (cond
                  (= key :browser-endpoint)
                  (assoc-in options [:browser :endpoint] value)

                  (contains? #{:open-browser? :check?} key)
                  (assoc options key (parse-env-boolean env-name value))

                  (= key :nudge-enabled?)
                  (assoc options key (parse-env-boolean env-name value))

                  :else
                  (assoc options key value)))))
          {}
          env-keys))

;; ---------------------------------------------------------------------------
;; Normalization

(defn- absolute-path
  [value]
  (-> (Paths/get (str value) (make-array String 0))
      (.toAbsolutePath)
      (.normalize)
      (str)))

(defn- path-under
  [root & segments]
  (str (Paths/get (str root) (into-array String segments))))

(defn- validate-fleet
  [fleet]
  (when-not (and (string? (:master-ref fleet))
                 (not (str/blank? (:master-ref fleet))))
    (throw (ex-info "fleet master-ref must not be blank"
                    {:error/kind :config-invalid
                     :field [:fleet :master-ref]
                     :value (:master-ref fleet)})))
  (when-not (and (integer? (:retention-ms fleet))
                 (not (neg? (:retention-ms fleet))))
    (throw (ex-info "fleet retention-ms must be a non-negative integer"
                    {:error/kind :config-invalid
                     :field [:fleet :retention-ms]
                     :value (:retention-ms fleet)})))
  (when-not (and (integer? (:gc-interval-ms fleet))
                 (pos? (:gc-interval-ms fleet)))
    (throw (ex-info "fleet gc-interval-ms must be a positive integer"
                    {:error/kind :config-invalid
                     :field [:fleet :gc-interval-ms]
                     :value (:gc-interval-ms fleet)})))
  (when-not (boolean? (:gc-run-immediately? fleet))
    (throw (ex-info "fleet gc-run-immediately? must be a boolean"
                    {:error/kind :config-invalid
                     :field [:fleet :gc-run-immediately?]
                     :value (:gc-run-immediately? fleet)})))
  (when-not (boolean? (:preflight? fleet))
    (throw (ex-info "fleet preflight? must be a boolean"
                    {:error/kind :config-invalid
                     :field [:fleet :preflight?]
                     :value (:preflight? fleet)})))
  (when-not (or (nil? (:state-file fleet))
                (and (string? (:state-file fleet))
                     (not (str/blank? (:state-file fleet)))))
    (throw (ex-info "fleet state-file must be a non-blank string"
                    {:error/kind :config-invalid
                     :field [:fleet :state-file]
                     :value (:state-file fleet)})))
  fleet)

;; ---------------------------------------------------------------------------
;; Gateway policy

(def ^:private gateway-data-keys
  "Data keys the application forwards from `:gateway` to the gateway and the
  application-owned HTTP routes.  Code-level hooks are deliberately absent:
  an EDN file cannot supply a function, and a keyword is also `ifn?`, so
  accepting one would turn a typo into a silent authorization change."
  #{:allowed-origins :trusted-hosts :allow-remote? :max-pending
    :max-upload-bytes :max-sente-body :max-message-bytes
    :max-sente-message-bytes :max-state-object-bytes
    :max-state-objects})

(def ^:private gateway-hook-keys
  #{:authorized?-fn :authorize-request})

(def ^:private gateway-positive-int-keys
  [:max-pending :max-upload-bytes :max-sente-body
   :max-message-bytes :max-sente-message-bytes
   :max-state-object-bytes :max-state-objects])

(defn- invalid-config!
  [message field value]
  (throw (ex-info message
                  {:error/kind :config-invalid
                   :field field
                   :value value})))

(defn- normalize-allowed-origins
  "Return `:all` or a set of canonical origins.

  A bare string is rejected because it would be a silent single-entry
  collection; a malformed URL is rejected because it could never match a
  browser Origin and would fail closed only at request time."
  [value]
  (cond
    (or (nil? value) (= :all value))
    :all

    (or (set? value) (sequential? value))
    (into #{}
          (map (fn [origin]
                 (when-not (string? origin)
                   (invalid-config!
                    (str "gateway allowed-origins entry must be a string: "
                         (pr-str origin))
                    [:gateway :allowed-origins] origin))
                 (let [canonical (gateway-auth/origin-of origin)]
                   (when-not (and canonical
                                  (not (str/includes? canonical "@"))
                                  (or (str/starts-with? canonical "http://")
                                      (str/starts-with? canonical "https://")))
                     (invalid-config!
                      (str "gateway allowed-origins entry is not an http(s) "
                           "origin: " (pr-str origin))
                      [:gateway :allowed-origins] origin))
                   canonical)))
          value)

    :else
    (invalid-config!
     "gateway allowed-origins must be :all or a collection of origins"
     [:gateway :allowed-origins] value)))

(defn- normalize-trusted-host
  "Return the literal host name for VALUE.

  Accepts `host`, `host:port`, `[ipv6]` and `[ipv6]:port`, matching the
  request-side parsing in `hc.server.gateway.auth/host-name`."
  [value]
  (when-not (string? value)
    (invalid-config!
     (str "gateway trusted-hosts entry must be a string: " (pr-str value))
     [:gateway :trusted-hosts] value))
  (let [raw (some-> value str str/trim str/lower-case)]
    (when (or (str/blank? raw)
              (str/includes? raw "/")
              (str/includes? raw "@")
              (re-find #"\s" raw))
      (invalid-config!
       (str "gateway trusted-hosts entry is not a host name: "
            (pr-str value))
       [:gateway :trusted-hosts] value))
    (let [host (cond
                 (re-matches #"\[[0-9a-f:.]+\](?::[0-9]{1,5})?" raw)
                 (second (re-matches #"\[([0-9a-f:.]+)\](?::[0-9]{1,5})?"
                                     raw))

                 (and (= 1 (count (filter #{\:} raw)))
                      (re-matches #"[^:]+:[0-9]{1,5}" raw))
                 (subs raw 0 (str/last-index-of raw ":"))

                 :else raw)]
      (when-not (or (re-matches #"[a-z0-9.-]+" host)
                    (and (str/includes? host ":")
                         (re-matches #"[0-9a-f:.]+" host)))
        (invalid-config!
         (str "gateway trusted-hosts entry is not a host name: "
              (pr-str value))
         [:gateway :trusted-hosts] value))
      host)))

(defn- normalize-trusted-hosts
  [value]
  (cond
    (nil? value) #{}
    (or (set? value) (sequential? value))
    (into #{} (map normalize-trusted-host) value)
    :else
    (invalid-config!
     "gateway trusted-hosts must be a collection of host names"
     [:gateway :trusted-hosts] value)))

(defn- normalize-allow-remote
  [value]
  (cond
    (nil? value) false
    (boolean? value) value
    :else
    (invalid-config!
     "gateway allow-remote? must be a boolean"
     [:gateway :allow-remote?] value)))

(defn- validate-gateway
  "Validate and canonicalize the `:gateway` configuration section.

  The section is a closed schema.  A hook key is rejected explicitly because
  it looks configurable but must stay code-level; any other unknown key is
  rejected so a misspelled option cannot be silently ignored.
  `:allow-remote? true` is rejected because EDN cannot supply the
  authentication hook that remote peers require; Host and Origin are not
  credentials."
  [gateway]
  (let [gateway (or gateway {})]
    (when-not (map? gateway)
      (invalid-config! ":gateway must be a map" :gateway gateway))
    (when-let [hook (first (filter gateway-hook-keys (keys gateway)))]
      (invalid-config!
       (str "gateway " (pr-str hook)
            " is code-level only and cannot be set from EDN")
       [:gateway hook] (get gateway hook)))
    (when-let [unknown (first (remove gateway-data-keys (keys gateway)))]
      (invalid-config!
       (str "unknown gateway configuration key " (pr-str unknown)
            "; accepted keys are "
            (str/join ", " (sort (map name gateway-data-keys))))
       [:gateway unknown] (get gateway unknown)))
    (when (true? (:allow-remote? gateway))
      (invalid-config!
       (str "gateway allow-remote? cannot be enabled from EDN: the gateway "
            "has no built-in authentication and Host/Origin are not "
            "credentials; terminate authentication at a local reverse "
            "proxy or supply a code-level :authorized?-fn / "
            ":authorize-request")
       [:gateway :allow-remote?] true))
    (let [gateway (merge (:gateway defaults) gateway)]
      (reduce (fn [gateway key]
                (if (contains? gateway key)
                  (update gateway key
                          (fn [value]
                            (when-not (and (integer? value) (pos? value))
                              (invalid-config!
                               (str "gateway " (name key)
                                    " must be a positive integer")
                               [:gateway key] value))
                            value))
                  gateway))
              (assoc gateway
                     :allowed-origins
                     (normalize-allowed-origins (:allowed-origins gateway))
                     :trusted-hosts
                     (normalize-trusted-hosts (:trusted-hosts gateway))
                     :allow-remote?
                     (normalize-allow-remote (:allow-remote? gateway)))
              gateway-positive-int-keys))))

(defn normalize
  "Validate and normalize a merged configuration map.

  Paths become absolute; derived paths are filled when absent.  The
  `:gateway` section is canonicalized to sets/booleans and validated as a
  closed schema.  This function performs no I/O beyond reading system
  properties, so it is safe to call in tests and in a preflight check."
  [config]
  (let [config (merge defaults config)
        gateway (validate-gateway (:gateway config))
        fleet-config (:fleet config)
        _ (when-not (or (nil? fleet-config) (map? fleet-config))
            (throw (ex-info ":fleet must be a map"
                            {:error/kind :config-invalid
                             :field :fleet
                             :value fleet-config})))
        fleet (validate-fleet (merge (:fleet defaults) fleet-config))
        port (parse-port (:port config))
        log-level (parse-log-level (:log-level config))
        host (str (:host config))]
    (when-not (boolean? (:check? config))
      (throw (ex-info "check? must be a boolean"
                      {:error/kind :config-invalid
                       :field :check?
                       :value (:check? config)})))
    (when-not (boolean? (:open-browser? config))
      (throw (ex-info "open-browser? must be a boolean"
                      {:error/kind :config-invalid
                       :field :open-browser?
                       :value (:open-browser? config)})))
    (when-not (boolean? (:nudge-enabled? config))
      (throw (ex-info "nudge-enabled? must be a boolean"
                      {:error/kind :config-invalid
                       :field :nudge-enabled?
                       :value (:nudge-enabled? config)})))
    (when (and (some? (:nudge-round-budget config))
               (not (and (integer? (:nudge-round-budget config))
                         (pos? (:nudge-round-budget config)))))
      (throw (ex-info "nudge-round-budget must be a positive integer or nil"
                      {:error/kind :config-invalid
                       :field :nudge-round-budget
                       :value (:nudge-round-budget config)})))
    (when (str/blank? host)
      (throw (ex-info "host must not be blank"
                      {:error/kind :config-invalid :field :host})))
    (when-not (and (string? (:workspace-root config))
                   (not (str/blank? (:workspace-root config))))
      (throw (ex-info "workspace-root must not be blank"
                      {:error/kind :config-invalid :field :workspace-root})))
    (let [data-dir (absolute-path (:data-dir config))
          workspace-root (absolute-path (:workspace-root config))
          blob-root (absolute-path (or (:blob-root config)
                                       (path-under data-dir "blobs")))
          worktree-root (absolute-path (or (:worktree-root config)
                                           (path-under data-dir "worktrees")))
          artifact-root (absolute-path (or (:artifact-root config)
                                           (path-under data-dir "artifacts")))
          fleet (assoc fleet
                       :state-file
                       (absolute-path
                        (or (:state-file fleet)
                            (path-under data-dir "fleet" "state.edn"))))
          static-root (absolute-path (or (:static-root config)
                                         "resources/public"))]
      (assoc config
             :host host
             :port port
             :log-level log-level
             :gateway gateway
             :fleet fleet
             :data-dir data-dir
             :workspace-root workspace-root
             :blob-root blob-root
             :worktree-root worktree-root
             :artifact-root artifact-root
             :static-root static-root
             :db-path (absolute-path (or (get-in config [:storage :path])
                                         (path-under data-dir "db")))))))

(defn load-config
  "Resolve the effective configuration.

  `:args` is a vector of command-line strings.  `:env` is a string map and
  defaults to the process environment.  Throws `ex-info` with
  `:error/kind :config-invalid` for malformed values."
  ([] (load-config {}))
  ([{:keys [args env] :or {args [] env (System/getenv)}}]
   (let [cli (parse-args args)]
     (if (:help? cli)
       {:help? true}
       (let [config-path (or (:config cli) (get env "HC_CONFIG"))
             file-config (read-config-file config-path)
             merged (merge file-config (env-options env) cli)]
         (normalize (dissoc merged :help?)))))))

(defn ensure-directories!
  "Create the directories the application owns.  Returns the config."
  [config]
  (doseq [path [(:data-dir config)
                (:blob-root config)
                (:worktree-root config)
                (:artifact-root config)]]
    (let [dir (io/file path)]
      (when-not (.isDirectory dir)
        (when-not (.mkdirs dir)
          (throw (ex-info (str "cannot create directory " path)
                          {:error/kind :config-invalid
                           :field :directory
                           :value path}))))))
  config)

(defn apply-log-level!
  "Apply the validated log level to the JVM logging root.

  The application does not install a logging backend; setting the root level
  still governs the JDK and library loggers that do use `java.util.logging`.
  Returns the level keyword."
  [config]
  (let [level (parse-log-level (:log-level config))
        jul-level (case level
                    :debug java.util.logging.Level/FINE
                    :info java.util.logging.Level/INFO
                    :warn java.util.logging.Level/WARNING
                    :error java.util.logging.Level/SEVERE)]
    (.setLevel (java.util.logging.Logger/getLogger "") jul-level)
    level))

(defn describe
  "A redacted, log-safe view of the configuration."
  [config]
  (let [models-configured? (boolean (some some? (vals (:models config))))]
    (-> config
        (dissoc :models :storage)
        (assoc :models-configured? models-configured?))))

(defn help-requested?
  [options]
  (true? (:help? options)))
