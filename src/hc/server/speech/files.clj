(ns hc.server.speech.files
  "Atomic filesystem primitives shared by speech configuration and artifacts.

  Speech audio is user data.  A recording is published only after its audio
  and metadata are both durable in a temporary directory and that directory
  is renamed into place.  A failed write therefore cannot leave a
  half-recording that looks complete."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hc.server.speech.error :as error])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption
                          FileAlreadyExistsException Files LinkOption
                          OpenOption Path Paths StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security MessageDigest)))

(defn path
  [value]
  (cond
    (instance? Path value) value
    :else (Paths/get (str value) (make-array String 0))))

(defn ensure-dir!
  [dir]
  (Files/createDirectories (path dir) (make-array FileAttribute 0))
  (path dir))

(defn exists?
  [value]
  (Files/exists (path value) (make-array LinkOption 0)))

(defn regular-file?
  [value]
  (Files/isRegularFile (path value) (make-array LinkOption 0)))

(defn- move!
  [from to]
  (try
    (Files/move (path from) (path to)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (path from) (path to)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(defn- move-new!
  [from to]
  (try
    (Files/move (path from) (path to)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (path from) (path to)
                  (make-array CopyOption 0)))))

(defn- owner-only!
  [value]
  (try
    (Files/setPosixFilePermissions
     (path value)
     (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)
    (catch java.io.IOException _ nil)))

(defn write-bytes!
  "Write BYTES to PATH through a sibling temporary file and one rename."
  [value ^bytes bytes]
  (let [target (path value)
        parent (.getParent target)]
    (when parent (ensure-dir! parent))
    (let [tmp (Files/createTempFile parent ".tmp-" ".part"
                                    (make-array FileAttribute 0))]
      (try
        (Files/write tmp bytes (make-array OpenOption 0))
        (owner-only! tmp)
        (move! tmp target)
        target
        (catch Throwable t
          (try (Files/deleteIfExists tmp) (catch Throwable _ nil))
          (throw t))))))

(defn write-edn!
  [value data]
  (write-bytes! value
                (.getBytes (pr-str data) StandardCharsets/UTF_8)))

(defn read-edn
  "Read one EDN value.  Returns nil when the file is absent.  A malformed
  file is an expected speech failure, not a silent nil."
  [value]
  (let [target (path value)]
    (when (regular-file? target)
      (try
        (edn/read-string {:readers {} :default tagged-literal}
                         (slurp (str target) :encoding "UTF-8"))
        (catch Throwable t
          (error/fail! :SPEECH_FILE_CORRUPT
                       "speech file is not valid EDN"
                       {:path (str target)
                        :message (.getMessage t)}))))))

(defn sha256-hex
  [^bytes bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")
        value (.digest digest bytes)
        sb (StringBuilder.)]
    (doseq [b value]
      (.append sb (format "%02x" (bit-and (int b) 0xff))))
    (str sb)))

(defn safe-id?
  "A stable identifier that can name a directory.  It excludes path
  separators and the two relative-directory names."
  [value]
  (boolean
   (and (string? value)
        (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}" value)
        (not= "." value)
        (not= ".." value)
        (not (str/includes? value "..")))))

(defn assert-id!
  [value kind]
  (when-not (safe-id? value)
    (error/fail! :SPEECH_ID_INVALID
                 (str kind " id is not safe")
                 {:id value}))
  value)

(defn safe-file-name?
  [value]
  (boolean
   (and (string? value)
        (not (str/blank? value))
        (not (str/includes? value "/"))
        (not (str/includes? value "\\"))
        (not= "." value)
        (not= ".." value))))

(defn content-extension
  [content-type]
  (let [base (some-> content-type
                     (str/split #";" 2)
                     first
                     str/trim
                     str/lower-case)]
    (case base
      "audio/webm" "webm"
      "audio/ogg" "ogg"
      "audio/wav" "wav"
      "audio/x-wav" "wav"
      "audio/wave" "wav"
      "audio/mpeg" "mp3"
      "audio/mp3" "mp3"
      "audio/mp4" "m4a"
      "audio/aac" "aac"
      "audio/flac" "flac"
      "audio/x-flac" "flac"
      "audio/pcm" "pcm"
      "audio/opus" "opus"
      "bin")))

(defn content-type-for-format
  [format]
  (case (some-> format str str/lower-case)
    "wav" "audio/wav"
    "mp3" "audio/mpeg"
    "opus" "audio/ogg"
    "aac" "audio/aac"
    "flac" "audio/flac"
    "pcm" "audio/pcm"
    "application/octet-stream"))

(defn delete-tree!
  "Delete PATH and its children.  ROOT is the only allowed ancestor.  The
  caller has already decided that the artifact is no longer referenced."
  [root value]
  (let [root (path root)
        target (path value)]
    (when-not (str/starts-with? (str (.normalize target))
                                (str (.normalize root)))
      (error/fail! :SPEECH_DELETE_OUTSIDE_ROOT
                   "refusing to delete outside the speech root"
                   {:path (str target)}))
    (when (= (str (.normalize root)) (str (.normalize target)))
      (error/fail! :SPEECH_DELETE_ROOT
                   "refusing to delete the speech root"
                   {:path (str target)}))
    (when (exists? target)
      (if (regular-file? target)
        (Files/deleteIfExists target)
        (with-open [stream (Files/walk target (make-array java.nio.file.FileVisitOption 0))]
          (doseq [entry (reverse (vec (.toList stream)))]
            (Files/deleteIfExists ^Path entry))))))
  true)

(defn move-directory!
  "Move a completed temporary directory into its final name.  A collision is
  reported rather than overwriting user data."
  [from to]
  (try
    (move-new! from to)
    (catch FileAlreadyExistsException _
      (error/fail! :SPEECH_ARTIFACT_EXISTS
                   "artifact id already exists"
                   {:path (str to)})))
  (path to))
