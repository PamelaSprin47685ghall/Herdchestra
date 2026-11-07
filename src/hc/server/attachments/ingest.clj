(ns hc.server.attachments.ingest
  "Real attachment ingestion into the managed blob store.

  An imported attachment becomes an application-owned local copy before it
  can be sent.  The original operator file is never modified.  The managed
  blob store supplies a stable SHA-256 content ID; this namespace supplies
  the attachment identity, display name, effective media type, image send
  copy, extraction result and workspace provenance.

  Every refusal is a value.  Expected errors do not escape as exceptions:
  callers receive `{:ok? false :error {...}}` and no descriptor is claimed.
  The byte stream is consumed exactly once by the blob store, and the
  100 MiB ceiling is enforced while reading, not after a large object has
  already been published."
  (:require [clojure.string :as str]
            [hc.server.attachments.media :as media]
            [hc.server.attachments.model :as model]
            [hc.server.gateway.ports :as ports])
  (:import (java.io ByteArrayInputStream FilterInputStream InputStream)
           (java.net URLEncoder)
           (java.nio.file Files LinkOption OpenOption Path)
           (java.nio.file.attribute BasicFileAttributes)
           (java.security MessageDigest)
           (java.util HexFormat UUID)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Bounded input

(defn- too-large!
  [limit]
  (throw (ex-info "attachment exceeds the configured size limit"
                  {:code :ATTACHMENT_TOO_LARGE
                   :limit limit
                   :message "a single attachment may be at most 100 MiB"})))

(defn bounded-stream
  "Wrap IN so that reading more than LIMIT bytes fails with
  `:ATTACHMENT_TOO_LARGE`.  Exactly LIMIT bytes is allowed."
  ^InputStream [^InputStream in limit]
  (let [source in
        limit (long limit)
        remaining (volatile! limit)]
    (proxy [FilterInputStream] [source]
      (read
        ([]
         (let [value (.read ^InputStream source)]
           (when-not (neg? value)
             (when (zero? @remaining)
               (too-large! limit))
             (vswap! remaining dec))
           value))
        ([buffer]
         (let [allowed (min (long (alength ^bytes buffer))
                            (inc (long @remaining)))
               value (.read ^InputStream source ^bytes buffer 0 (int allowed))]
           (when (pos? value)
             (when (> value @remaining)
               (too-large! limit))
             (vswap! remaining - value))
           value))
        ([buffer offset length]
         (let [allowed (min (long length) (inc (long @remaining)))
               value (.read ^InputStream source ^bytes buffer (int offset) (int allowed))]
           (when (pos? value)
             (when (> value @remaining)
               (too-large! limit))
             (vswap! remaining - value))
           value))))))

;; ---------------------------------------------------------------------------
;; Service

(defn service
  "Build an attachment service.

  Options:
  - `:blob-store` a `hc.server.gateway.ports/BlobStorePort`;
  - `:extractor` optional real extraction adapter, either a function or a map
    with `:extract!`.  It receives
    `{:source {:id :name :mime :size} :stream InputStream}` and returns the
    document contract result;
  - `:limits` optional stricter defaults;
  - `:prepare-images?` default true;
  - `:max-image-edge` default 2048;
  - `:text-limit` default 2 MiB for a local preview;
  - `:image-prepare-limit` default 64 MiB, above which the original is kept;
  - `:now-fn`, `:id-fn` for deterministic tests."
  [{:keys [blob-store extractor limits prepare-images? max-image-edge
           text-limit image-prepare-limit now-fn id-fn workspace-root]
    :or {prepare-images? true
         max-image-edge 2048
         text-limit (* 2 1024 1024)
         image-prepare-limit (* 64 1024 1024)}}]
  {:blob-store blob-store
   :extractor extractor
   :limits (model/service-limits nil limits)
   :prepare-images? (boolean prepare-images?)
   :max-image-edge (long max-image-edge)
   :text-limit (long text-limit)
   :image-prepare-limit (long image-prepare-limit)
   :workspace-root workspace-root
   :now-fn (or now-fn #(System/currentTimeMillis))
   :id-fn (or id-fn #(str "att-" (UUID/randomUUID)))})

(defn- service-error
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- limits-for
  [service request]
  (model/service-limits (:model request) (:limits service)))

;; ---------------------------------------------------------------------------
;; Media inspection

(defn- blob-stream
  [service blob-id]
  (ports/open-blob (:blob-store service) blob-id))

(defn- inspect-media
  [service {:keys [blob-id mime name size]}]
  (try
    (let [kind (model/mime->kind mime)
          head (with-open [^InputStream in (blob-stream service blob-id)]
                 (media/read-up-to in (* 128 1024)))]
      (merge (media/basic-facts mime head)
             (when (= kind :image)
               (with-open [^InputStream in (blob-stream service blob-id)]
                 (media/image-info in)))
             (when (= kind :text)
               {:preview (with-open [^InputStream in (blob-stream service blob-id)]
                           (media/text-preview in (:text-limit service)))})
             {:size size}))
    (catch Throwable error
      {:kind (model/mime->kind mime)
       :readable? :unknown
       :reason :inspection-failed
       :message (.getMessage ^Throwable error)})))

(defn- inspect-file-media
  [service {:keys [path mime name size]}]
  (try
    (let [head (with-open [^InputStream in (Files/newInputStream path (make-array OpenOption 0))]
                 (media/read-up-to in (* 128 1024)))
          resolved (media/resolve-mime mime (media/sniff-mime head) name)
          mime (:mime resolved)
          kind (model/mime->kind mime)]
      (merge (media/basic-facts mime head)
             (when (= kind :image)
               (with-open [^InputStream in (Files/newInputStream path (make-array OpenOption 0))]
                 (media/image-info in)))
             (when (= kind :text)
               {:preview (with-open [^InputStream in (Files/newInputStream path (make-array OpenOption 0))]
                           (media/text-preview in (:text-limit service)))})
             {:size size
              :mime mime
              :declared-mime (:declared-mime resolved)
              :mime-mismatch? (boolean (:mismatch? resolved))}))
    (catch Throwable error
      {:kind (model/mime->kind mime)
       :readable? :unknown
       :reason :inspection-failed
       :message (.getMessage ^Throwable error)})))

(defn- prepare-image-copy
  [service {:keys [blob-id mime size]}]
  (if (and (:prepare-images? service)
           (= :image (model/mime->kind mime))
           (<= (long size) (:image-prepare-limit service)))
    (try
      (let [head (with-open [^InputStream in (blob-stream service blob-id)]
                   (media/read-up-to in (* 128 1024)))
            orientation (media/jpeg-orientation head)
            result (with-open [^InputStream in (blob-stream service blob-id)]
                     (media/prepare-image in {:max-edge (:max-image-edge service)
                                              :orientation orientation
                                              :head-bytes head}))]
        (if (:ok? result)
          (if (:unchanged? result)
            {:status :original
             :orientation orientation
             :width (:width result)
             :height (:height result)
             :animated? (:animated? result)}
            (let [metadata (ports/put-blob! (:blob-store service)
                                            (ByteArrayInputStream. ^bytes (:bytes result))
                                            {})
                  max-bytes (get-in service [:limits :max-bytes])]
              (if (> (long (:size metadata)) (long max-bytes))
                {:status :skipped
                 :reason :prepared-copy-too-large
                 :size (:size metadata)}
                {:status :prepared
                 :blob-id (:id metadata)
                 :sha256 (:sha256 metadata)
                 :size (:size metadata)
                 :mime (:mime result)
                 :width (:width result)
                 :height (:height result)
                 :original-width (:original-width result)
                 :original-height (:original-height result)
                 :orientation orientation
                 :resized? (:resized? result)
                 :animated? (:animated? result)
                 :first-frame? (:first-frame? result)
                 :alpha? (:alpha? result)})))
          {:status :unavailable
           :reason (:reason result)
           :message (:message result)}))
      (catch Throwable error
        {:status :unavailable
         :reason :prepare-failed
         :message (.getMessage ^Throwable error)}))
    {:status :skipped
     :reason (if (not (:prepare-images? service))
               :disabled
               :image-too-large-to-prepare)}))

;; ---------------------------------------------------------------------------
;; Extraction

(defn- extractor-fn
  [extractor]
  (cond
    (fn? extractor) extractor
    (and (map? extractor) (fn? (:extract! extractor))) (:extract! extractor)
    :else nil))

(defn- extraction-result
  [result]
  (cond
    (and (map? result) (true? (:ok? result)))
    {:status (if (and (string? (:text result))
                      (not (str/blank? (:text result))))
               :ready
               :empty)
     :text (:text result)
     :page-count (:page-count result)
     :pages (:pages result)
     :selected-pages (:selected-pages result)
     :route (or (:route result) :text)
     :encoding (:encoding result)
     :line-count (:line-count result)
     :source (:source result)
     :warnings (vec (or (:warnings result) []))
     :editable? true}

    (and (map? result) (false? (:ok? result)))
    (let [code (get-in result [:error :code])
          needs-service? (contains? #{:OCR_UNAVAILABLE :ROUTE_UNAVAILABLE
                                      :NEEDS_PASSWORD :WRONG_PASSWORD
                                      :UNSUPPORTED_MIME}
                                    code)]
      {:status (if needs-service? :needs-service :failed)
       :route (:route result)
       :suggested-route (or (:suggested-route result)
                            (get-in result [:error :suggested-route]))
       :error (:error result)
       :pages (:pages result)
       :selected-pages (:selected-pages result)
       :warnings (vec (or (:warnings result) []))
       :editable? false})

    :else
    {:status :failed
     :error {:code :EXTRACTOR_CONTRACT_INVALID
             :message "extractor did not return the document contract"}
     :editable? false}))

(defn- default-selection
  "Documents refuses to guess.  Text extraction is the only implicit route:
  it is a real extraction, not OCR.  Images, audio and video require an
  explicit operator selection."
  [mime]
  (case (model/mime->kind mime)
    (:text :pdf) {:route :text}
    nil))

(defn- extract
  [service {:keys [blob-id name mime size selection password]}]
  (let [extractor (extractor-fn (:extractor service))
        kind (model/mime->kind mime)
        selection (or selection (default-selection mime))
        local-text? (and (nil? extractor)
                         (model/text-extractable? mime name))
        max-bytes (get-in service [:limits :max-bytes])]
    (cond
      (and extractor selection)
      (try
        (with-open [^InputStream in (blob-stream service blob-id)]
          (extraction-result
           (extractor (cond-> {:source {:id blob-id :name name :mime mime :size size}
                               :stream in
                               :selection selection
                               :max-bytes max-bytes}
                        password (assoc :password password)))))
        (catch Throwable error
          {:status :failed
           :error {:code :EXTRACTOR_FAILED
                   :message "the extractor failed"
                   :details {:class (.getName (class error))}}
           :editable? false}))

      extractor
      {:status :not-attempted
       :reason :needs-operator-choice
       :suggested-route (case kind
                          :image :ocr
                          :audio :transcription
                          :video :frame
                          nil)
       :editable? false}

      local-text?
      (let [result (with-open [^InputStream in (blob-stream service blob-id)]
                     (media/read-text in (:text-limit service)))]
        (if (:ok? result)
          {:status (if (str/blank? (:text result)) :empty :ready)
           :text (:text result)
           :page-count nil
           :pages []
           :route :text
           :encoding "utf-8"
           :source {:kind :local-utf8 :id blob-id}
           :truncated? (boolean (:truncated? result))
           :editable? true
           :warnings (when (:truncated? result)
                       [{:code :TEXT_TRUNCATED
                         :message "preview stopped at the configured text limit"}])}
          {:status :failed
           :error {:code :ENCODING_UNKNOWN
                   :message "the file is not valid UTF-8"
                   :details {:reason (:reason result)}}
           :editable? false}))

      :else
      {:status :not-attempted
       :reason :no-extractor
       :editable? false})))

(defn extract-attachment!
  "Run an explicit operator-chosen extraction on an existing local copy.

  This is the only path that may request OCR.  Ingest never auto-OCRs an
  image, and a failed or empty OCR result never becomes text.

  Options: `:password` (never stored or returned) and `:max-bytes`."
  ([service attachment selection]
   (extract-attachment! service attachment selection {}))
  ([service attachment selection {:keys [password max-bytes]}]
   (let [extractor (extractor-fn (:extractor service))
         selection (when (map? selection) selection)
         extraction (cond
                      (nil? extractor)
                      {:status :failed
                       :error {:code :EXTRACTOR_UNAVAILABLE
                               :message "no document extractor is configured"}
                       :editable? false}

                      (nil? selection)
                      {:status :failed
                       :error {:code :SELECTION_REQUIRED
                               :message "explicit extraction selection is required"}
                       :editable? false}

                      (not (model/local-copy? attachment))
                      {:status :failed
                       :error {:code :ATTACHMENT_NOT_LOCAL
                               :message "an associated workspace file has no application copy"}
                       :editable? false}

                      :else
                      (try
                        (with-open [^InputStream in (blob-stream service (:blob-id attachment))]
                          (extraction-result
                           (extractor (cond-> {:source {:id (:blob-id attachment)
                                                        :name (:name attachment)
                                                        :mime (:mime attachment)
                                                        :size (:size attachment)}
                                               :stream in
                                               :selection selection
                                               :max-bytes (or max-bytes
                                                              (get-in service [:limits :max-bytes]))}
                                        password (assoc :password password)))))
                        (catch Throwable error
                          {:status :failed
                           :error {:code :EXTRACTOR_FAILED
                                   :message "the extractor failed"
                                   :details {:class (.getName (class error))}}
                           :editable? false})))
         attachment' (assoc attachment :extraction extraction)]
     {:ok? (= :ready (:status extraction))
      :attachment attachment'
      :extraction extraction
      :error (when-not (= :ready (:status extraction))
               (:error extraction))})))

;; ---------------------------------------------------------------------------
;; Descriptor

(defn- blob-url
  [blob-id name]
  (str "/blobs/" blob-id "?filename="
       (URLEncoder/encode (str (or name "attachment")) "UTF-8")))

(defn- descriptor
  [service request metadata resolved media-info prepared extraction]
  (let [id ((:id-fn service))
        now ((:now-fn service))
        name (:name request)
        limits (:limits request)]
    {:attachment-id id
     :id id
     :name name
     :mime (:mime resolved)
     :declared-mime (:declared-mime resolved)
     :sniffed-mime (:sniffed-mime resolved)
     :mime-mismatch? (boolean (:mismatch? resolved))
     :size (:size metadata)
     :blob-id (:id metadata)
     :sha256 (:sha256 metadata)
     :source (or (:source request) :local-import)
     :local-copy? true
     :status :ready
     :ready? true
     :excluded? false
     :media media-info
     :prepared (when (= :prepared (:status prepared)) prepared)
     :prepared-status prepared
     :extraction extraction
     :extractable? (boolean (or (model/text-extractable? (:mime resolved) name)
                                (:suggested-route extraction)))
     :workspace (:workspace request)
     :created-at now
     :limits limits
     :url (blob-url (:id metadata) name)}))

;; ---------------------------------------------------------------------------
;; Ingestion

(defn ingest-stream!
  "Copy STREAM into the managed blob store and return an attachment
  descriptor.  The caller owns and should close STREAM.

  Request keys:
  - `:name` display name (required);
  - `:mime` declared media type (optional; sniffed when absent);
  - `:size` declared byte count (optional; early refusal when too large);
  - `:stream` bytes (required);
  - `:source` `:local-import`, `:clipboard`, `:camera`, `:workspace`, etc.;
  - `:draft-count` number of items already in the draft;
  - `:model` optional model map carrying stricter service limits;
  - `:workspace` optional provenance map;
  - `:limits` optional stricter limits."
  [service request]
  (let [{:keys [stream name mime size draft-count]} request
        name (model/normalize-name name)
        limits (limits-for service request)
        max-bytes (:max-bytes limits)]
    (cond
      (nil? (:blob-store service))
      (service-error :ATTACHMENT_STORE_UNAVAILABLE
                     "no managed blob store is attached")

      (nil? name)
      (service-error :ATTACHMENT_NAME_INVALID
                     "attachment needs a usable display name")

      (nil? stream)
      (service-error :ATTACHMENT_BODY_REQUIRED
                     "attachment ingestion needs a byte stream")

      (and (integer? size) (neg? size))
      (service-error :ATTACHMENT_SIZE_INVALID
                     "attachment size cannot be negative")

      (and (integer? size) (> size max-bytes))
      (service-error :ATTACHMENT_TOO_LARGE
                     "a single attachment may be at most 100 MiB"
                     {:size size :limit max-bytes})

      (and (integer? draft-count) (>= draft-count (:max-items limits)))
      (service-error :ATTACHMENT_LIMIT_EXCEEDED
                     "a draft supports at most 20 attachments"
                     {:count draft-count :limit (:max-items limits)})

      :else
      ;; Sniffing must not read past a stricter service limit.  The product
      ;; limit is 100 MiB, but a model or connection may set less.
      (let [head-limit (max 1 (min (* 128 1024) (long max-bytes)))
            [pushback head] (media/pushback-head stream head-limit)
            resolved (media/resolve-mime mime (media/sniff-mime head) name)
            bounded (bounded-stream pushback max-bytes)
            stored (try
                     {:ok? true
                      :attachment (ports/put-blob! (:blob-store service) bounded {})}
                       (catch clojure.lang.ExceptionInfo error
                         (let [{:keys [code] :as data} (ex-data error)]
                           (case code
                             :ATTACHMENT_TOO_LARGE
                             (service-error :ATTACHMENT_TOO_LARGE
                                            "a single attachment may be at most 100 MiB"
                                            (select-keys data [:limit]))
                             (service-error :ATTACHMENT_STORE_FAILED
                                            (or (.getMessage ^Throwable error)
                                                "blob store refused the attachment")
                                            {:code code}))))
                       (catch Throwable error
                         (service-error :ATTACHMENT_STORE_FAILED
                                        (or (.getMessage ^Throwable error)
                                            "blob store failed while copying the attachment")
                                        {:class (.getName (class error))})))]
        (if-not (:ok? stored)
          stored
          (let [metadata (:attachment stored)
                actual-size (:size metadata)]
            (cond
              (nil? (:id metadata))
              (service-error :ATTACHMENT_STORE_FAILED
                             "blob store returned no content ID")

              (> (long actual-size) max-bytes)
              (service-error :ATTACHMENT_TOO_LARGE
                             "a single attachment may be at most 100 MiB"
                             {:size actual-size :limit max-bytes})

              :else
              (let [media-info (inspect-media service {:blob-id (:id metadata)
                                                       :mime (:mime resolved)
                                                       :name name
                                                       :size actual-size})
                    prepared (prepare-image-copy service {:blob-id (:id metadata)
                                                          :mime (:mime resolved)
                                                          :size actual-size})
                    extraction (extract service {:blob-id (:id metadata)
                                                 :name name
                                                 :mime (:mime resolved)
                                                 :size actual-size})
                    descriptor (descriptor service
                                           (assoc request :name name :limits limits)
                                           metadata resolved media-info prepared extraction)]
                {:ok? true
                 :attachment descriptor
                 :effects [{:kind :attachment-ingested
                            :attachment-id (:attachment-id descriptor)
                            :blob-id (:blob-id descriptor)
                            :size (:size descriptor)
                            :draft-count draft-count}]}))))))))

(defn ingest-file!
  "Ingest a real file from disk.  The source file is opened read-only and is
  never modified.  Size is checked before any bytes are read.

  A missing file, directory, permission failure or read failure is returned as
  an `:ATTACHMENT_SOURCE_UNREADABLE` value, never as an escaping exception."
  [service request]
  (try
    (let [value (:path request)
          path (when-not (str/blank? (str value))
                 (.toAbsolutePath (Path/of (str value) (make-array String 0))))]
      (cond
        (nil? path)
        (service-error :ATTACHMENT_SOURCE_UNREADABLE
                       "attachment source path is required")

        (not (Files/isRegularFile path ^"[Ljava.nio.file.LinkOption;" (make-array LinkOption 0)))
        (service-error :ATTACHMENT_SOURCE_UNREADABLE
                       "attachment source is not a regular file"
                       {:path (str path)})

        :else
        (let [size (Files/size path)]
          (with-open [^InputStream in (Files/newInputStream path (make-array OpenOption 0))]
            (ingest-stream! service (assoc request
                                           :name (or (:name request)
                                                     (str (.getFileName path)))
                                           :size size
                                           :stream in))))))
    (catch clojure.lang.ExceptionInfo error
      (service-error (or (:code (ex-data error)) :ATTACHMENT_SOURCE_UNREADABLE)
                     (or (.getMessage ^Throwable error)
                         "cannot read attachment source file")
                     (ex-data error)))
    (catch Throwable error
      (service-error :ATTACHMENT_SOURCE_UNREADABLE
                     (or (.getMessage ^Throwable error)
                         "cannot read attachment source file")
                     {:class (.getName (class error))}))))

(defn ingest-bytes!
  [service request ^bytes bytes]
  (with-open [in (ByteArrayInputStream. bytes)]
    (ingest-stream! service (assoc request :stream in :size (alength bytes)))))

;; ---------------------------------------------------------------------------
;; Workspace files

(defn- workspace-root
  ^Path [root]
  (let [path (.toAbsolutePath (Path/of (str root) (make-array String 0)))]
    (when-not (Files/isDirectory path (make-array LinkOption 0))
      (throw (ex-info "workspace root is not a directory"
                      {:code :WORKSPACE_ROOT_INVALID
                       :root (str path)})))
    (.toRealPath path (make-array LinkOption 0))))

(defn resolve-workspace-path
  "Resolve a relative path inside ROOT, refusing traversal and symlink
  escapes.  Returns a real absolute path."
  ^Path [root relative-path]
  (let [root (workspace-root root)
        relative (Path/of (str relative-path) (make-array String 0))]
    (when (.isAbsolute relative)
      (throw (ex-info "workspace attachment path must be relative"
                      {:code :WORKSPACE_PATH_ABSOLUTE
                       :path (str relative-path)})))
    (let [target (.normalize (.resolve root relative))]
      (when-not (.startsWith target root)
        (throw (ex-info "workspace attachment path escapes the workspace"
                        {:code :WORKSPACE_PATH_ESCAPE
                         :path (str relative-path)})))
      (let [real (.toRealPath target (make-array LinkOption 0))]
        (when-not (.startsWith real root)
          (throw (ex-info "workspace attachment resolves outside the workspace"
                          {:code :WORKSPACE_PATH_ESCAPE
                           :path (str relative-path)})))
        real))))

(defn- file-stamp
  [^Path path]
  (let [^BasicFileAttributes attrs
        (Files/readAttributes path BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" (make-array LinkOption 0))
        ^java.time.Instant instant
        (.toInstant ^java.nio.file.attribute.FileTime (.lastModifiedTime attrs))]
    {:mtime-sec (.getEpochSecond instant)
     :mtime-nsec (long (.getNano instant))
     :size (.size attrs)}))

(defn workspace-attachment!
  "Attach a workspace file either as a real local snapshot (default) or as a
  verified association.

  `:snapshot?` defaults to true, matching §6.7: the operator gets an
  application copy unless they explicitly choose to associate the live file."
  [service request]
  (try
    (let [root (:workspace-root request)
          relative (:relative-path request)
          path (resolve-workspace-path root relative)
          stamp (file-stamp path)
          name (or (:name request) (str (.getFileName path)))
          mime (:mime request)
          workspace {:root-id (or (:root-id request) (str root))
                     :root-path (str root)
                     :relative-path (str relative)
                     :stamp stamp}
          snapshot? (not (false? (:snapshot? request)))
          limits (limits-for service request)]
      (cond
        (> (long (:size stamp)) (long (:max-bytes limits)))
        (service-error :ATTACHMENT_TOO_LARGE
                       "a single attachment may be at most 100 MiB"
                       {:size (:size stamp) :limit (:max-bytes limits)})

        snapshot?
        (let [result (ingest-file! service (assoc request
                                                  :path path
                                                  :name name
                                                  :mime mime
                                                  :source :workspace
                                                  :workspace workspace))]
          (if (:ok? result)
            (update-in result [:attachment :workspace] merge workspace)
            result))
        :else
        (let [media-info (inspect-file-media service {:path path
                                                      :mime mime
                                                      :name name
                                                      :size (:size stamp)})
              id ((:id-fn service))]
          {:ok? true
           :attachment {:attachment-id id
                        :id id
                        :name name
                        :mime (or (:mime media-info) mime "application/octet-stream")
                        :size (:size stamp)
                        :blob-id nil
                        :local-copy? false
                        :source :workspace
                        :status :ready
                        :ready? true
                        :excluded? false
                        :media (assoc media-info :workspace-file? true)
                        :workspace workspace
                        :created-at ((:now-fn service))
                        :limits limits}
           :effects [{:kind :attachment-associated
                      :relative-path (str relative)
                      :stamp stamp}]})))
    (catch clojure.lang.ExceptionInfo error
      (service-error (or (:code (ex-data error)) :WORKSPACE_ATTACHMENT_FAILED)
                     (or (.getMessage ^Throwable error)
                         "workspace attachment failed")
                     (ex-data error)))
    (catch Throwable error
      (service-error :WORKSPACE_ATTACHMENT_FAILED
                     (or (.getMessage ^Throwable error)
                         "workspace attachment failed")
                     {:class (.getName (class error))}))))

(defn refresh-workspace!
  "Compare an associated workspace attachment with the current file stamp.

  A changed file is never silently substituted.  The caller must ask the
  operator to use the latest content or keep the previously previewed copy."
  [attachment]
  (let [workspace (:workspace attachment)]
    (if-not (model/valid-workspace? workspace)
      (service-error :ATTACHMENT_SOURCE_INVALID
                     "attachment has no verified workspace reference")
      (try
        (let [path (resolve-workspace-path (or (:root-path workspace)
                                               (:root-id workspace))
                                           (:relative-path workspace))
              current (file-stamp path)
              expected (get-in workspace [:stamp])]
          (if (= current expected)
            {:ok? true
             :changed? false
             :attachment (assoc attachment
                                :status :ready
                                :workspace-verified? true
                                :ready? true)
             :stamp current}
            {:ok? false
             :changed? true
             :stamp current
             :attachment (assoc attachment
                                :status :needs-choice
                                :ready? false
                                :detail "文件已更新，使用最新内容或保留已预览版本")
             :error {:code :WORKSPACE_FILE_CHANGED
                     :message "workspace file changed since it was attached"
                     :details {:expected expected :current current}}}))
        (catch clojure.lang.ExceptionInfo error
          (service-error :WORKSPACE_FILE_UNREADABLE
                         (or (.getMessage ^Throwable error)
                             "workspace file is no longer readable")
                         (ex-data error)))))))

(defn snapshot-workspace!
  "Turn an association into a real local copy before sending.  This is the
  explicit '保留已预览版本' action."
  [service attachment]
  (let [workspace (:workspace attachment)]
    (if-not (model/valid-workspace? workspace)
      (service-error :ATTACHMENT_SOURCE_INVALID
                     "attachment has no verified workspace reference")
      ;; Without a byte snapshot taken at association time, a changed file
      ;; cannot be restored to the previewed version.  Refuse rather than
      ;; silently snapshot the newer bytes and call them the old version.
      (let [current (refresh-workspace! attachment)]
        (if (:changed? current)
          (service-error :WORKSPACE_FILE_CHANGED
                         "workspace file changed; the previewed version is no longer available"
                         {:expected (get-in workspace [:stamp])
                          :current (:stamp current)})
          (workspace-attachment! service {:workspace-root (or (:root-path workspace)
                                                              (:root-id workspace))
                                          :relative-path (:relative-path workspace)
                                          :name (:name attachment)
                                          :mime (:mime attachment)
                                          :snapshot? true
                                          :source :workspace}))))))

;; ---------------------------------------------------------------------------
;; Verification and reads

(defn- sha256-stream
  [^InputStream in]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (loop []
      (let [n (.read in buffer)]
        (if (neg? n)
          (.formatHex (HexFormat/of) (.digest digest))
          (do
            (when (pos? n)
              (.update digest buffer 0 n))
            (recur)))))))

(defn verify!
  "Verify that an attachment still has the bytes it claims.

  A local copy is checked against its recorded digest and size.  An
  association is checked against its recorded stamp.  Missing bytes are an
  explicit `:ATTACHMENT_MISSING`, never an empty successful read."
  [service attachment]
  (if (:local-copy? attachment)
    (let [id (:blob-id attachment)
          expected-sha (or (:sha256 attachment)
                           (when (model/canonical-blob-id? id)
                             (subs id 7)))]
      (cond
        (not (model/canonical-blob-id? id))
        (service-error :ATTACHMENT_BLOB_ID_INVALID
                       "local copy has no canonical blob ID")

        (nil? (:blob-store service))
        (service-error :ATTACHMENT_STORE_UNAVAILABLE
                       "no managed blob store is attached")

        :else
        (let [metadata (try
                         (ports/blob-metadata (:blob-store service) id)
                         (catch Throwable error
                           (service-error :ATTACHMENT_STORE_FAILED
                                          (.getMessage ^Throwable error))))]
          (cond
            (and (map? metadata) (:error metadata)) metadata
            (nil? metadata)
            (service-error :ATTACHMENT_MISSING
                           "the application copy is missing"
                           {:blob-id id})
            (not= (long (:size metadata)) (long (:size attachment)))
            (service-error :ATTACHMENT_INTEGRITY
                           "the application copy does not match its recorded size"
                           {:blob-id id
                            :expected-size (:size attachment)
                            :actual-size (:size metadata)
                            :expected-sha256 expected-sha})
            :else
            (let [actual (try
                           (when-let [in (ports/open-blob (:blob-store service) id)]
                             (with-open [stream in]
                               (sha256-stream stream)))
                           (catch Throwable error
                             (service-error :ATTACHMENT_STORE_FAILED
                                            (or (.getMessage ^Throwable error)
                                                "cannot read attachment bytes"))))]
              (cond
                (and (map? actual) (:error actual)) actual
                (nil? actual)
                (service-error :ATTACHMENT_MISSING
                               "the application copy is missing"
                               {:blob-id id})
                (not= expected-sha actual)
                (service-error :ATTACHMENT_INTEGRITY
                               "the application copy does not match its recorded digest"
                               {:blob-id id
                                :expected-sha256 expected-sha
                                :actual-sha256 actual})
                :else
                {:ok? true
                 :attachment attachment
                 :blob metadata}))))))
    (refresh-workspace! attachment)))

(defn open-attachment
  "Open the original local copy for reading.  Returns `{:ok? true :stream ...}`
  or an explicit missing-store refusal."
  [service attachment]
  (cond
    (not (:local-copy? attachment))
    (service-error :ATTACHMENT_NOT_LOCAL
                   "an associated workspace file has no application copy")

    (nil? (:blob-store service))
    (service-error :ATTACHMENT_STORE_UNAVAILABLE
                   "no managed blob store is attached")

    :else
    (let [stream (try
                   (ports/open-blob (:blob-store service) (:blob-id attachment))
                   (catch Throwable error
                     (service-error :ATTACHMENT_STORE_FAILED
                                    (.getMessage ^Throwable error))))]
      (cond
        (nil? stream)
        (service-error :ATTACHMENT_MISSING
                       "the application copy is missing"
                       {:blob-id (:blob-id attachment)})

        (and (map? stream) (:error stream))
        stream

        :else
        {:ok? true :stream stream :attachment attachment}))))
