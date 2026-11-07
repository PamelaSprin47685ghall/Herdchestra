(ns hc.server.extensions.mcp.config
  "Normalization and redaction for one MCP server configuration.

  The four connection forms are distinct values, not one overloaded string.
  A local process keeps its program and arguments separate and is never run
  through a shell.  HTTP and SSE keep headers as a map.  Validation only reads
  and normalizes; it never connects and never executes a command."
  (:require [clojure.string :as str]
            [hc.server.extensions.common :as common]
            [hc.server.extensions.error :as error])
  (:import (java.net URI URISyntaxException)
           (java.nio.file Files LinkOption Path)))

(def known-fields
  #{:name :type :transport :url :headers :command :args :env :cwd
    :enabled :enabled? :tools :description :group :groups :metadata
    :oauth :auth :source :unknown-fields})

(def default-timeout-ms 5000)
(def max-timeout-ms 60000)
(def max-args 256)
(def max-env 256)
(def max-header 128)

(defn- string-key
  [k]
  (cond
    (keyword? k) (name k)
    (string? k) k
    (symbol? k) (str k)
    :else (str k)))

(defn- keywordize
  [m]
  (into {}
        (map (fn [[k v]] [(keyword (string-key k)) v]))
        (or m {})))

(defn- header-token?
  [s]
  (boolean (re-matches #"[!#$%&'*+\-.^_`|~0-9A-Za-z]+" (str s))))

(defn- invalid!
  [code message data]
  (error/fail! code message data))

(defn- normalize-type
  [value]
  (let [v (some-> value string-key str/trim str/lower-case)]
    (cond
      (nil? v) nil
      (#{"http" "http-stream" "http-streaming" "streamable-http"
         "streamable_http" "streamable"} v) :http
      (#{"sse" "server-sent-events" "server-sent-event"} v) :sse
      (#{"stdio" "process" "local" "local-process" "local_process"} v) :stdio
      :else
      (invalid! :MCP_TRANSPORT_INVALID
                "connection type must be http, sse or stdio"
                {:type value}))))

(defn- normalize-headers
  [headers]
  (when-not (or (nil? headers) (map? headers))
    (invalid! :MCP_HEADERS_INVALID "headers must be a map"
              {:class (some-> headers class .getName)}))
  (when (> (count (or headers {})) max-header)
    (invalid! :MCP_HEADERS_INVALID "too many headers"
              {:limit max-header :actual (count headers)}))
  (reduce-kv
   (fn [acc k v]
     (let [k (str k)
           v (str v)]
       (when (or (str/blank? k) (not (header-token? k)))
         (invalid! :MCP_HEADER_INVALID "invalid header name"
                   {:header k}))
       (when (or (str/includes? k "\r") (str/includes? k "\n")
                 (str/includes? v "\r") (str/includes? v "\n")
                 (str/includes? v "\u0000"))
         (invalid! :MCP_HEADER_INVALID "header contains a line break or NUL"
                   {:header k}))
       (assoc acc k v)))
   {}
   (or headers {})))

(defn- normalize-env
  [env]
  (when-not (or (nil? env) (map? env))
    (invalid! :MCP_ENV_INVALID "environment must be a map"
              {:class (some-> env class .getName)}))
  (when (> (count (or env {})) max-env)
    (invalid! :MCP_ENV_INVALID "too many environment entries"
              {:limit max-env :actual (count env)}))
  (reduce-kv
   (fn [acc k v]
     (let [k (str k)
           v (str v)]
       (when (or (str/blank? k)
                 (str/includes? k "=")
                 (str/includes? k "\u0000"))
         (invalid! :MCP_ENV_INVALID "invalid environment name" {:name k}))
       (when (or (str/includes? v "\u0000")
                 (str/includes? v "\r") (str/includes? v "\n"))
         (invalid! :MCP_ENV_INVALID "environment value contains NUL or a line break"
                   {:name k}))
       (assoc acc k v)))
   {}
   (or env {})))

(defn- normalize-url
  [value]
  (let [url (some-> value str str/trim)]
    (cond
      (str/blank? url)
      (invalid! :MCP_URL_REQUIRED "URL must not be blank" {})

      (some #(Character/isWhitespace ^char %) url)
      (invalid! :MCP_URL_INVALID "URL must not contain whitespace" {:url url})

      (some #(= \u0000 %) url)
      (invalid! :MCP_URL_INVALID "URL must not contain NUL" {:url url})

      :else
      (try
        (let [uri (URI/create url)
              scheme (some-> (.getScheme uri) str/lower-case)
              host (.getHost uri)]
          (when-not (#{"http" "https"} scheme)
            (invalid! :MCP_URL_INVALID "URL scheme must be http or https"
                      {:url url :scheme scheme}))
          (when (str/blank? (str host))
            (invalid! :MCP_URL_INVALID "URL must include a host" {:url url}))
          {:url url
           :uri uri
           :warnings (cond-> []
                      (some? (.getUserInfo uri))
                      (conj {:code :MCP_URL_CONTAINS_CREDENTIALS
                             :message "credentials in the URL are visible in the address field"})
                      (some? (.getFragment uri))
                      (conj {:code :MCP_URL_FRAGMENT_IGNORED
                             :message "URL fragments are not sent to the server"}))})
        (catch URISyntaxException t
          (invalid! :MCP_URL_INVALID "URL is not a valid absolute URI"
                    {:url url :reason (.getReason t)}))))))

(defn- normalize-args
  [args]
  (when-not (or (nil? args) (sequential? args))
    (invalid! :MCP_ARGS_INVALID "arguments must be a sequence"
              {:class (some-> args class .getName)}))
  (when (> (count (or args [])) max-args)
    (invalid! :MCP_ARGS_INVALID "too many arguments"
              {:limit max-args :actual (count args)}))
  (mapv (fn [arg]
          (let [arg (str arg)]
            (when (or (str/includes? arg "\u0000")
                      (str/includes? arg "\r") (str/includes? arg "\n"))
              (invalid! :MCP_ARGS_INVALID
                        "argument contains NUL or a line break"
                        {:argument arg}))
            arg))
        (or args [])))

(defn- normalize-cwd
  [cwd]
  (when (some? cwd)
    (let [cwd (str cwd)]
      (when (or (str/blank? cwd)
                (str/includes? cwd "\u0000")
                (str/includes? cwd "\r") (str/includes? cwd "\n"))
        (invalid! :MCP_CWD_INVALID "working directory is invalid" {:cwd cwd}))
      (try
        (let [path (Path/of cwd (make-array String 0))]
          (when-not (.isAbsolute path)
            (invalid! :MCP_CWD_INVALID
                      "working directory must be absolute"
                      {:cwd cwd}))
          {:cwd cwd
           :warnings (when-not (Files/isDirectory path (make-array LinkOption 0))
                       [{:code :MCP_CWD_MISSING
                         :message "working directory does not exist yet"}])})
        (catch java.nio.file.InvalidPathException t
          (invalid! :MCP_CWD_INVALID
                    "working directory is not a valid path"
                    {:cwd cwd :reason (.getMessage t)}))))))

(defn- transport-input
  [server]
  (let [transport (:transport server)
        transport-map (when (map? transport) (keywordize transport))
        type (or (normalize-type (:type transport-map))
                 (normalize-type (:type server))
                 (when (or (string? transport) (keyword? transport))
                   (normalize-type transport))
                 (cond
                   (some? (:command transport-map)) :stdio
                   (some? (:command server)) :stdio
                   (some? (:url transport-map)) :http
                   (some? (:url server)) :http
                   :else nil))]
    {:type type
     :map (merge (select-keys transport-map [:url :headers :command :args :env :cwd])
                 (select-keys server [:url :headers :command :args :env :cwd]))}))

(defn normalize
  "Normalize one server configuration.

  Returns `{:ok? true :server-config ... :warnings [...] :unknown-fields [...]
  :has-secrets? bool}` or a refusal.  `:enabled?` is not accepted here: the
  catalog adds it as false."
  [server]
  (try
    (let [server (keywordize server)
          name (common/safe-name (:name server))
          {:keys [type map]} (transport-input server)
          _ (when (nil? type)
              (invalid! :MCP_TRANSPORT_REQUIRED
                        "server needs a command or URL"
                        {:name name}))
          unknown (->> (keys server)
                       (remove known-fields)
                       (map string-key)
                       sort
                       vec)]
      (case type
        :http
        (let [{:keys [url uri warnings]} (normalize-url (:url map))
              headers (normalize-headers (:headers map))
              [_ header-secret?] (common/redact-map headers)
              has-secrets? (boolean (or header-secret?
                                        (some? (.getUserInfo uri))))]
          (when (str/blank? url)
            (invalid! :MCP_URL_REQUIRED "HTTP server needs a URL" {:name name}))
          {:ok? true
           :server-config {:name name
                           :transport {:type :http
                                       :url url
                                       :headers headers}}
           :warnings (vec warnings)
           :unknown-fields unknown
           :has-secrets? has-secrets?})

        :sse
        (let [{:keys [url uri warnings]} (normalize-url (:url map))
              headers (normalize-headers (:headers map))
              [_ header-secret?] (common/redact-map headers)
              has-secrets? (boolean (or header-secret?
                                        (some? (.getUserInfo uri))))]
          {:ok? true
           :server-config {:name name
                           :transport {:type :sse
                                       :url url
                                       :headers headers}}
           :warnings (vec warnings)
           :unknown-fields unknown
           :has-secrets? has-secrets?})

        :stdio
        (let [command (some-> (:command map) str str/trim)
              _ (when (str/blank? command)
                  (invalid! :MCP_COMMAND_REQUIRED
                            "local process server needs a command"
                            {:name name}))
              _ (when (or (str/includes? command "\u0000")
                          (str/includes? command "\r")
                          (str/includes? command "\n"))
                  (invalid! :MCP_COMMAND_INVALID
                            "command contains NUL or a line break"
                            {:name name}))
              args (normalize-args (:args map))
              env (normalize-env (:env map))
              cwd-result (normalize-cwd (:cwd map))
              [_ has-secrets?] (common/redact-map env)
              warnings (vec (concat
                             (when (and (empty? args)
                                        (re-find #"\s" command))
                               [{:code :MCP_COMMAND_NOT_SPLIT
                                 :message "command contains spaces; program and arguments must be separate fields"}])
                             (:warnings cwd-result)))]
          {:ok? true
           :server-config {:name name
                           :transport (cond-> {:type :stdio
                                               :command command
                                               :args args
                                               :env env}
                                         (:cwd cwd-result) (assoc :cwd (:cwd cwd-result)))}
           :warnings warnings
           :unknown-fields unknown
           :has-secrets? has-secrets?})))
    (catch clojure.lang.ExceptionInfo t
      (if (error/expected? t)
        {:ok? false :error (error/error-map t)}
        (throw t)))))

(defn identity
  "Fields that determine which server a tool list belongs to.

  Header credentials are deliberately excluded: rotating a token keeps the
  tool selection, while changing the target, command, arguments, environment
  or working directory invalidates it."
  [server-config]
  (let [t (:transport server-config)]
    (select-keys t [:type :url :command :args :env :cwd])))

(defn- redact-arg
  [arg]
  (if (re-find #"(?i)^--?(token|key|secret|password|passwd|api[-_]?key|access[-_]?token)=(.+)$"
               arg)
    (str/replace arg #"=(.+)$" "=***")
    arg))

(defn- redact-url
  [url]
  (try
    (let [uri (URI/create url)]
      (if (some? (.getUserInfo uri))
        (str/replace url (.getUserInfo uri) "***")
        url))
    (catch Exception _ url)))

(defn public-view
  "A server configuration safe to show in a settings list.

  Header and environment values whose names look secret are masked, as are
  credentials embedded in a URL.  The boolean `:has-secrets?` lets the UI say
  that a value exists without revealing it."
  [server]
  (let [transport (:transport server)
        [headers header-secret?] (common/redact-map (:headers transport))
        [env env-secret?] (common/redact-map (:env transport))
        url-secret? (try
                      (some? (.getUserInfo (URI/create (:url transport))))
                      (catch Exception _ false))
        secret-args? (boolean (some #(not= % (redact-arg %)) (:args transport)))
        public-transport (cond-> (select-keys transport [:type :url :command :args :cwd])
                           (:args transport)
                           (update :args #(mapv redact-arg %))
                           (:headers transport) (assoc :headers headers)
                           (:env transport) (assoc :env env))]
    (-> server
        (assoc :transport public-transport)
        (assoc :has-secrets? (boolean (or header-secret? env-secret?
                                          secret-args? url-secret?)))
        (update-in [:transport :url] redact-url))))

(defn describe-target
  [server-config]
  (let [t (:transport server-config)]
    (case (:type t)
      :http (:url t)
      :sse (:url t)
      :stdio (str/join " " (cons (:command t) (:args t)))
      nil)))
