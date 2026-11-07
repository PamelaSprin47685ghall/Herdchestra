(ns hc.server.diagnostics.redact
  "Deterministic redaction for diagnostic previews and detailed logs.

  Two rules drive this namespace:

  1. A known credential field is replaced by a stable placeholder.  The
     field name stays so a maintainer can tell which setting is missing.
     Different values receive different placeholders, so two services remain
     distinguishable without revealing either value.
  2. Common credential shapes in free text (bearer tokens, API-key prefixes,
     private-key blocks, JWTs, URL userinfo) are replaced wherever they
     appear.

  Usage fields such as `:input-tokens`, `:output-tokens`, `:max-tokens`, and
  `:token-count` are statistics, not secrets.  They are deliberately not
  matched by the field-name rules.

  `:mask-hosts?` hides service domains; `:mask-paths?` hides home-directory
  paths.  `:rename` replaces user-selected nicknames before redaction.  The
  result contains findings with paths and placeholders only; it never carries
  the original secret value.

  Redaction is idempotent: running it again over its own output does not
  double-redact placeholders.  Placeholder numbers continue after any
  placeholders already present, so a second pass for a different field class
  cannot collide with the first pass."
  (:require [clojure.string :as str])
  (:import [java.util.regex Matcher Pattern]))

(def placeholder-pattern
  #"<redacted:(secret|host|path):(\d+)>")

(def secret-fields
  #{"api-key" "apikey" "authorization" "auth" "bearer" "cookie" "set-cookie"
    "password" "passwd" "pwd" "secret" "client-secret" "private-key"
    "access-key" "secret-key" "signing-key" "encryption-key" "credential"
    "credentials" "access-token" "refresh-token" "id-token" "auth-token"
    "api-token" "session-token" "csrf-token" "token" "key"})

(def secret-suffixes
  ["-api-key" "-apikey" "-secret" "-password" "-passwd" "-token"
   "-credential" "-credentials" "-private-key" "-access-key" "-secret-key"
   "-signing-key" "-encryption-key" "-authorization" "-cookie"])

(def host-fields
  #{"host" "hostname" "domain" "actual-host"})

(def path-fields
  #{"path" "file" "filename" "cwd" "workspace-path" "data-dir" "home"
    "directory" "dir"})

(def secret-patterns
  [{:id :private-key
    :regex #"(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"}
   {:id :url-credentials
    :regex #"(?i)\b([a-z][a-z0-9+.-]*://)([^/\s:@]+):([^/\s@]+)@"
    :replace (fn [^Matcher matcher placeholder]
               (str (.group matcher 1) placeholder "@"))}
   {:id :bearer
    :regex #"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}"}
   {:id :basic
    :regex #"(?i)\bBasic\s+[A-Za-z0-9+/=]{8,}"}
   {:id :openai-key
    :regex #"\bsk-[A-Za-z0-9_-]{16,}\b"}
   {:id :github-token
    :regex #"\bgh[pousr]_[A-Za-z0-9]{20,}\b"}
   {:id :github-fine-grained
    :regex #"\bgithub_pat_[A-Za-z0-9_]{20,}\b"}
   {:id :slack-token
    :regex #"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"}
   {:id :aws-access-key
    :regex #"\bAKIA[0-9A-Z]{16}\b"}
   {:id :google-api-key
    :regex #"\bAIza[0-9A-Za-z_-]{35}\b"}
   {:id :jwt
    :regex #"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"}
   {:id :aws-secret-key
    :regex #"(?i)\b(aws[_-]?secret[_-]?access[_-]?key)\s*[:=]\s*[\"']?([A-Za-z0-9/+=]{40})"
    :replace (fn [^Matcher matcher placeholder]
               (str (.group matcher 1) ": " placeholder))}
   {:id :key-value
    :regex #"(?i)\b(api[_-]?key|secret|password|passwd|token)\s*[:=]\s*[\"']?([A-Za-z0-9._~+/=-]{12,})"
    :replace (fn [^Matcher matcher placeholder]
               (str (.group matcher 1) ": " placeholder))}])

(def default-options
  {:rename {}
   :mask-hosts? false
   :mask-paths? false
   :extra-patterns []})

(defn- normalize-key
  [key]
  (-> (cond
        (keyword? key) (name key)
        (string? key) key
        (symbol? key) (name key)
        :else (str key))
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn sensitive-key?
  "True when KEY names a credential-bearing field.

  Token-count fields are excluded on purpose."
  [key]
  (let [normalized (normalize-key key)]
    (or (contains? secret-fields normalized)
        (some #(str/ends-with? normalized %) secret-suffixes))))

(defn- host-key?
  [key]
  (contains? host-fields (normalize-key key)))

(defn- path-key?
  [key]
  (contains? path-fields (normalize-key key)))

(defn- new-state
  [value]
  (let [counters (atom {:secret 0 :host 0 :path 0})
        placeholders (atom {})
        findings (atom [])
        finding-set (atom #{})
        visit (fn visit [node]
                (cond
                  (string? node)
                  (let [matcher (re-matcher placeholder-pattern node)]
                    (loop []
                      (when (.find matcher)
                        (let [category (keyword (.group matcher 1))
                              number (parse-long (.group matcher 2))]
                          (swap! counters update category max number))
                        (recur))))

                  (map? node) (doseq [[key value] node]
                                (visit key)
                                (visit value))
                  (coll? node) (doseq [value node] (visit value))))]
    (visit value)
    {:counters counters
     :placeholders placeholders
     :findings findings
     :finding-set finding-set}))

(defn- placeholder-for!
  [state category value]
  (let [key [category value]
        existing (get @(:placeholders state) key)]
    (if existing
      existing
      (let [number (get (swap! (:counters state) update category (fnil inc 0))
                        category)
            placeholder (str "<redacted:" (name category) ":" number ">")]
        (swap! (:placeholders state) assoc key placeholder)
        placeholder))))

(defn- record-finding!
  [state path kind category placeholder]
  (let [finding {:path (vec path)
                 :kind kind
                 :category category
                 :placeholder placeholder}
        key [(:path finding) kind placeholder]]
    (when-not (contains? @(:finding-set state) key)
      (swap! (:finding-set state) conj key)
      (swap! (:findings state) conj finding))))

(defn- rename-string
  [value rename]
  (reduce (fn [text [from to]]
            (if (and (string? from) (string? to) (not= from to))
              (str/replace text from to)
              text))
          value
          (sort-by (fn [[from _]] [(- (count from)) from]) rename)))

(defn- rename-key
  [key rename]
  (cond
    (string? key) (get rename key key)
    (keyword? key) (if-let [replacement (get rename (name key))]
                     (if-let [ns (namespace key)]
                       (keyword ns replacement)
                       (keyword replacement))
                     key)
    :else key))

(defn- placeholder? [value]
  (boolean (re-matches placeholder-pattern value)))

(defn- replace-pattern
  [text {:keys [regex replace id]} state path]
  (let [pattern (if (instance? Pattern regex) regex (re-pattern (str regex)))
        matcher (re-matcher pattern text)
        result (StringBuilder.)
        last-end (volatile! 0)]
    (loop []
      (if (.find matcher)
        (let [start (.start matcher)
              end (.end matcher)
              match (.group matcher)
              placeholder (placeholder-for! state :secret match)
              replacement (if replace
                            (replace matcher placeholder)
                            placeholder)]
          (.append result (subs text @last-end start))
          (.append result replacement)
          (vreset! last-end end)
          (record-finding! state path id :secret placeholder)
          (recur))
        (do
          (.append result (subs text @last-end))
          (str result))))))

(defn- replace-hosts
  [text state path]
  (let [matcher (re-matcher #"(?i)\b([a-z][a-z0-9+.-]*://)((?:[^/\s?#@]+@)?)([^/\s?#@]+)" text)
        result (StringBuilder.)
        last-end (volatile! 0)]
    (loop []
      (if (.find matcher)
        (let [start (.start matcher)
              end (.end matcher)
              host (.group matcher 3)]
          (if (placeholder? host)
            (.append result (subs text @last-end end))
            (let [placeholder (placeholder-for! state :host host)]
              (.append result (subs text @last-end start))
              (.append result (.group matcher 1))
              (.append result (.group matcher 2))
              (.append result placeholder)
              (record-finding! state path :host :host placeholder)))
          (vreset! last-end end)
          (recur))
        (do
          (.append result (subs text @last-end))
          (str result))))))

(defn- replace-paths
  [text state path]
  (let [patterns [#"(?i)(?:/home/|/Users/)[^/\s:]+(?:/[^\s:]*)?"
                  #"(?i)[A-Za-z]:\\Users\\[^\\\s]+(?:\\[^\s:]*)?"]]
    (reduce (fn [text pattern]
              (let [matcher (re-matcher pattern text)
                    result (StringBuilder.)
                    last-end (volatile! 0)]
                (loop []
                  (if (.find matcher)
                    (let [start (.start matcher)
                          end (.end matcher)
                          match (.group matcher)
                          placeholder (placeholder-for! state :path match)]
                      (.append result (subs text @last-end start))
                      (.append result placeholder)
                      (vreset! last-end end)
                      (record-finding! state path :path :path placeholder)
                      (recur))
                    (do
                      (.append result (subs text @last-end))
                      (str result))))))
            text
            patterns)))

(defn- redact-string
  [text state path opts]
  (let [text (rename-string text (:rename opts))
        text (reduce (fn [text pattern]
                       (replace-pattern text pattern state path))
                     text
                     (concat secret-patterns (:extra-patterns opts)))
        text (if (:mask-hosts? opts)
               (replace-hosts text state path)
               text)]
    (if (:mask-paths? opts)
      (replace-paths text state path)
      text)))

(declare redact-value)

(defn- redact-sensitive
  [value state path kind opts]
  (cond
    (nil? value) nil
    (string? value)
    (if (or (str/blank? value) (placeholder? value))
      value
      (let [placeholder (placeholder-for! state :secret value)]
        (record-finding! state path kind :secret placeholder)
        placeholder))
    (map? value)
    (reduce-kv (fn [result key value]
                 (assoc result key
                        (redact-sensitive value state (conj path key)
                                          kind opts)))
               (empty value)
               value)
    (coll? value)
    (into (empty value)
          (map-indexed (fn [index value]
                         (redact-sensitive value state (conj path index)
                                           kind opts))
                       value))
    :else
    (let [placeholder (placeholder-for! state :secret (pr-str value))]
      (record-finding! state path kind :secret placeholder)
      placeholder)))

(defn- redact-map
  [value state path opts]
  (reduce (fn [result [key value]]
            (let [source-key key
                  key (rename-key key (:rename opts))
                  path (conj path key)
                  normalized (normalize-key source-key)]
              (assoc result key
                     (cond
                       (sensitive-key? source-key)
                       (redact-sensitive value state path (keyword normalized) opts)

                       (and (:mask-hosts? opts) (host-key? source-key)
                            (string? value)
                            (not (str/blank? value))
                            (not (placeholder? value)))
                       (let [placeholder (placeholder-for! state :host value)]
                         (record-finding! state path :host :host placeholder)
                         placeholder)

                       (and (:mask-paths? opts) (path-key? source-key)
                            (string? value)
                            (not (str/blank? value))
                            (not (placeholder? value)))
                       (let [placeholder (placeholder-for! state :path value)]
                         (record-finding! state path :path :path placeholder)
                         placeholder)

                       :else
                       (redact-value value state path opts)))))
          (empty value)
          (sort-by (comp pr-str key) value)))

(defn- redact-value
  [value state path opts]
  (cond
    (nil? value) nil
    (string? value) (redact-string value state path opts)
    (map? value) (redact-map value state path opts)
    (vector? value) (mapv (fn [index value]
                            (redact-value value state (conj path index) opts))
                          (range)
                          value)
    (set? value) (into #{} (map #(redact-value % state path opts)) value)
    (seq? value) (mapv (fn [index value]
                         (redact-value value state (conj path index) opts))
                       (range)
                       value)
    :else value))

(defn- options
  [opts]
  (let [opts (merge default-options
                    (into {} (filter (comp some? val)) (or opts {})))
        rename (:rename opts)
        extra (:extra-patterns opts)]
    (when-not (map? rename)
      (throw (ex-info "redaction :rename must be a map"
                      {:error/kind :invalid-redaction-options
                       :reason :rename-invalid})))
    (when-not (every? map? extra)
      (throw (ex-info "redaction :extra-patterns must be a collection of maps"
                      {:error/kind :invalid-redaction-options
                       :reason :patterns-invalid})))
    (doseq [pattern extra]
      (when-not (or (instance? Pattern (:regex pattern))
                    (string? (:regex pattern)))
        (throw (ex-info "each redaction pattern needs a regex"
                        {:error/kind :invalid-redaction-options
                         :reason :pattern-regex-invalid
                         :pattern pattern}))))
    (assoc opts
           :rename (into {}
                         (map (fn [[from to]]
                                [(if (keyword? from) (name from) (str from))
                                 (str to)]))
                         rename)
           :extra-patterns (vec extra))))

(defn redact
  "Redact VALUE and return `{:value :findings :redacted? ...}`.

  Findings carry the path, the field or pattern kind, the category, and the
  placeholder.  They never carry the original value.  `:rename` is applied
  before secret matching; `:mask-hosts?` and `:mask-paths?` are opt-in."
  ([value] (redact value {}))
  ([value opts]
   (let [opts (options opts)
         state (new-state value)
         value' (redact-value value state [] opts)
         findings @(:findings state)]
     {:value value'
      :findings findings
      :redacted? (boolean (seq findings))
      :placeholder-count (count (distinct (map :placeholder findings)))})))

(defn placeholder-floor
  "Highest placeholder number already present for CATEGORY in VALUE."
  [value category]
  (let [counters (atom 0)
        visit (fn visit [node]
                (cond
                  (string? node)
                  (let [matcher (re-matcher placeholder-pattern node)]
                    (loop []
                      (when (.find matcher)
                        (when (= (name category) (.group matcher 1))
                          (swap! counters max (parse-long (.group matcher 2))))
                        (recur))))

                  (map? node) (doseq [[key value] node]
                                (visit key)
                                (visit value))
                  (coll? node) (doseq [value node] (visit value))))]
    (visit value)
    @counters))
