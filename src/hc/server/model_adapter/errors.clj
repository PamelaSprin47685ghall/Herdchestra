(ns hc.server.model-adapter.errors
  "Classify LangChain4j and transport failures for the model port.

  The adapter does not retry.  It classifies one failure so the Owner can
  decide between an upstream retry and a new business attempt.  Published tool
  calls are reported separately by the port; this namespace never assumes an
  interrupted stream had no side effects."
  (:require [clojure.string :as str]
            [hc.server.model-port.failures :as failures]
            [hc.server.model-port.retry :as retry])
  (:import [dev.langchain4j.exception AuthenticationException
            ContentFilteredException HttpException InvalidRequestException
            LangChain4jException ModelNotFoundException
            NonRetriableException RateLimitException RetriableException
            ToolArgumentsException
            UnsupportedFeatureException]
           [java.io IOException]
           [java.net ConnectException SocketException UnknownHostException]
           [java.net.http HttpTimeoutException]
           [java.util.concurrent CancellationException CompletionException
            ExecutionException]))

(defn- unwrap
  [^Throwable t]
  (loop [current t depth 0]
    (if (and current (< depth 8))
      (if (or (instance? ExecutionException current)
              (instance? CompletionException current))
        (recur (.getCause current) (inc depth))
        current)
      current)))

(defn- quota?
  [^Throwable t]
  (let [message (str/lower-case (str (.getMessage t)))]
    (or (str/includes? message "insufficient_quota")
        (str/includes? message "quota")
        (str/includes? message "billing")
        (str/includes? message "credit balance"))))

(defn- transport-disconnected?
  [^Throwable t]
  (let [message (str/lower-case (str (.getMessage t)))]
    (or (str/includes? message "connection closed")
        (str/includes? message "connection reset")
        (str/includes? message "broken pipe")
        (str/includes? message "stream closed")
        (str/includes? message "disconnected"))))

(defn- too-long?
  [^Throwable t]
  (let [message (str/lower-case (str (.getMessage t)))]
    (or (str/includes? message "context length")
        (str/includes? message "too many tokens")
        (str/includes? message "maximum context")
        (str/includes? message "input is too long"))))

(defn- http-status
  [^Throwable t]
  (when (instance? HttpException t)
    (.statusCode ^HttpException t)))

(defn- redact-message
  "Remove credentials a provider may echo in an error message.

  Authentication failures often quote the rejected key.  The event keeps the
  exception class and HTTP status for diagnosis; the message must not carry a
  bearer token, an `sk-` key, or a key=value secret."
  [message]
  (when (some? message)
    (-> (str message)
        (str/replace #"(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+" "$1***")
        (str/replace #"sk-[A-Za-z0-9_-]{4,}" "sk-***")
        (str/replace
         #"(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret)\s*[=:]\s*)[^\s,;&]+"
         "$1***"))))

(defn- exception-kind
  [^Throwable t]
  (cond
    (quota? t) :insufficient-quota
    (transport-disconnected? t) :transport
    (instance? RateLimitException t) :rate-limited
    (instance? AuthenticationException t) :auth
    (instance? ModelNotFoundException t) :invalid-model
    (instance? ContentFilteredException t) :content-refused
    (instance? ToolArgumentsException t) :tool-arguments-invalid
    (instance? UnsupportedFeatureException t) :unsupported-feature
    (instance? InvalidRequestException t) (if (too-long? t)
                                           :input-too-long
                                           :input-invalid)
    (instance? dev.langchain4j.exception.TimeoutException t) :timeout
    (instance? java.util.concurrent.TimeoutException t) :timeout
    (instance? HttpTimeoutException t) :timeout
    (instance? CancellationException t) :user-cancel
    (instance? InterruptedException t) :user-cancel
    (instance? UnknownHostException t) :network
    (instance? ConnectException t) :network
    (instance? SocketException t) :network
    (instance? IOException t) :network
    (instance? RetriableException t) :server-error
    (instance? NonRetriableException t) :unclassified
    (instance? SecurityException t) :permission
    (instance? IllegalArgumentException t) :input-invalid
    (instance? LangChain4jException t) :unclassified
    :else :unclassified))

(defn classify
  "Return a plain map for one Throwable.

  `:kind` is the adapter's error kind, `:category` and `:disposition` come
  from `hc.server.model-port.retry`, and `:retryable?` is the Owner's retry
  signal.  The exception message and class stay for diagnostics; stack traces
  and credentials do not enter the event."
  [throwable]
  (let [t (unwrap throwable)
        status (http-status t)
        raw-kind (exception-kind t)
        classification (retry/classify (cond-> {:kind raw-kind}
                                         (some? status) (assoc :http-status status)))
        kind (if (and (= :unclassified raw-kind)
                      (not= :unclassified (:category classification)))
               (:category classification)
               raw-kind)
        message (redact-message (some-> t .getMessage))
        stable (failures/classify {:kind kind
                                   :http-status status
                                   :message message})
        ;; A mapped code owns the retry fact.  An unmapped upstream failure
        ;; (for example a transient 5xx) keeps the adapter's retry decision
        ;; instead of being forced to non-retryable by the `:unknown` copy.
        retryable? (if (= :unknown (:code stable))
                     (:retryable? classification)
                     (:retryable? stable))]
    {:kind kind
     :category (:category classification)
     :disposition (:disposition classification)
     :retryable? retryable?
     :code (:code stable)
     :user-message (:message stable)
     :message message
     :exception-class (some-> t class .getName)
     :http-status status}))

(defn error-kind
  "Extract a stable kind from an adapter exception map or Throwable."
  [value]
  (cond
    (instance? Throwable value) (:kind (classify value))
    (map? value) (or (:kind value) (:error/kind value) :unclassified)
    :else :unclassified))
