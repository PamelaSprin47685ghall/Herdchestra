(ns hc.server.gateway.ports
  "Explicit ports through which the gateway reaches the Owner and the blob
  store.  The gateway never requires a concrete Owner or storage engine.

  `OwnerPort` is the serial command entry.  The Owner must call `reply-fn`
  exactly once with the command result.  A synchronous return value of
  `{:ok? ...}` is accepted as a convenience, but the callback form is the
  contract because the Owner may need to queue the command behind other work.

  `BlobStorePort` owns bytes only.  Content IDs are stable strings; the
  gateway never invents a second identity scheme."
  (:require [hc.server.blob-store :as blob])
  (:import [java.io InputStream]))

(defprotocol OwnerPort
  (submit-command!
    [owner request reply-fn]
    "Submit one command request to the serial Owner.

    REQUEST is `{:command-id <string> :command-epoch <int> :payload <edn>}`.
    The Owner calls REPLY-FN once with the result map returned by
    `hc.server.commands/accept` or an equivalent error map.  It may return
    nil, or return the same result synchronously.")
  (command-receipt
    [owner command-id]
    "Return the receipt for COMMAND-ID in the current epoch, or nil.

    COMMAND-ID is a command-id string or a query map
    `{:command-id id :session-id sid-or-nil :scope scope-or-nil}`.  A query
    map lets the router select one explicit receipt scope; a bare string
    queries the app scope."))
(defprotocol BlobStorePort
  (blob-metadata [store id]
    "Return `{:id :size ...}` for ID, or nil when absent.")
  (open-blob [store id]
    "Return an open InputStream for ID, or nil when absent.  The caller
    closes the stream.")
  (put-blob! [store in opts]
    "Consume IN and finalize one blob.  Return `{:id :size ...}`.")
  (blob-exists? [store id]
    "True when ID names a finalized object."))

(defn owner-port
  "Build an OwnerPort from two functions.

  `submit-fn` is `(fn [request reply-fn] ...)`; `receipt-fn` is
  `(fn [query] ...)`, where QUERY is a command-id string or a
  `{:command-id .. :session-id .. :scope ..}` map."
  [submit-fn receipt-fn]
  (reify OwnerPort
    (submit-command! [_ request reply-fn]
      (submit-fn request reply-fn))
    (command-receipt [_ command-id]
      (receipt-fn command-id))))

(defn- blob-not-found?
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (= :BLOB_NOT_FOUND (blob/error-code t))))

(defn blob-store-port
  "Adapt a `hc.server.blob-store` root directory to BlobStorePort.

  The store root stays owned by the caller; this adapter only translates
  expected missing-object failures into nil."
  [root]
  (reify BlobStorePort
    (blob-metadata [_ id]
      (try
        (blob/metadata root id)
        (catch clojure.lang.ExceptionInfo t
          (if (blob-not-found? t) nil (throw t)))))
    (open-blob [_ id]
      (try
        (:stream (blob/open-stream root id))
        (catch clojure.lang.ExceptionInfo t
          (if (blob-not-found? t) nil (throw t)))))
    (put-blob! [_ in opts]
      (blob/put-stream! root in opts))
    (blob-exists? [_ id]
      (try
        (blob/exists? root id)
        (catch clojure.lang.ExceptionInfo t
          (if (blob-not-found? t) false (throw t)))))))

(defn memory-blob-port
  "A small in-memory BlobStorePort for tests and smoke harnesses.

  `objects` is an atom mapping content ID to byte arrays.  IDs use the same
  canonical SHA-256 form as the managed store."
  [objects]
  (reify BlobStorePort
    (blob-metadata [_ id]
      (when-let [^bytes bytes (get @objects id)]
        {:id id :size (alength bytes)}))
    (open-blob [_ id]
      (when-let [^bytes bytes (get @objects id)]
        (java.io.ByteArrayInputStream. bytes)))
    (put-blob! [_ in _]
      (let [bytes (.readAllBytes ^InputStream in)
            id (blob/id-of-bytes bytes)]
        (swap! objects assoc id bytes)
        {:id id :size (alength bytes)}))
    (blob-exists? [_ id]
      (contains? @objects id))))
