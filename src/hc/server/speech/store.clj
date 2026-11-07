(ns hc.server.speech.store
  "Durable audio artifacts for speech input and speech output.

  A recording directory is published by one rename after both its audio and
  its metadata exist.  The audio survives every transcription failure and is
  deleted only by an explicit user command.  TTS audio uses the same layout so
  a partial stream can be kept and played without pretending it is complete."
  (:require [clojure.string :as str]
            [hc.server.speech.error :as error]
            [hc.server.speech.files :as files])
  (:import (java.nio.file Files OpenOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(defn- resolve-path
  [root & segments]
  (reduce (fn [^Path value segment] (.resolve value (str segment)))
          (files/path root)
          segments))

(defn recordings-root
  [root]
  (resolve-path root "recordings"))

(defn audio-root
  [root]
  (resolve-path root "audio"))

(defn recording-dir
  [root recording-id]
  (files/assert-id! recording-id "recording")
  (resolve-path (recordings-root root) recording-id))

(defn audio-dir
  [root audio-id]
  (files/assert-id! audio-id "audio")
  (resolve-path (audio-root root) audio-id))

(defn- byte-array?
  [value]
  (instance? (Class/forName "[B") value))

(defn- safe-content-type
  [value fallback]
  (let [value (or value fallback)]
    (if (string? value)
      (str/replace value #"[\r\n]" "")
      fallback)))

(defn- check-bytes!
  [bytes max-bytes]
  (when-not (byte-array? bytes)
    (error/fail! :SPEECH_AUDIO_INVALID
                 "audio must be a byte array"
                 {:class (some-> bytes class .getName)}))
  (when (zero? (alength ^bytes bytes))
    (error/fail! :SPEECH_AUDIO_EMPTY "audio is empty" {}))
  (when (and max-bytes (> (alength ^bytes bytes) (long max-bytes)))
    (error/fail! :SPEECH_AUDIO_TOO_LARGE
                 "audio exceeds the configured limit"
                 {:bytes (alength ^bytes bytes)
                  :limit (long max-bytes)})))

(defn- now-or
  [now]
  (or now (System/currentTimeMillis)))

(defn create-recording!
  "Publish one original recording before any provider is called.

  Options: `:bytes`, `:content-type`, `:filename`, `:duration-ms`,
  `:session-id`, `:client-id`, `:language`, `:max-bytes`, `:now`.
  Returns `{:recording meta :path path}`."
  [root {:keys [bytes content-type filename duration-ms session-id client-id
                language max-bytes now]}]
  (check-bytes! bytes max-bytes)
  (let [now (now-or now)
        id (str "rec-" (UUID/randomUUID))
        content-type (safe-content-type content-type
                                        "application/octet-stream")
        extension (files/content-extension content-type)
        audio-file (str "audio." extension)
        meta {:recording-id id
              :session-id session-id
              :client-id client-id
              :content-type content-type
              :filename (when (and (string? filename)
                                   (files/safe-file-name? filename))
                          filename)
              :audio-file audio-file
              :bytes (alength ^bytes bytes)
              :sha256 (files/sha256-hex bytes)
              :duration-ms duration-ms
              :language language
              :status :recorded
              :transcript nil
              :transcript-language nil
              :service-id nil
              :attempts []
              :error nil
              :created-at now
              :updated-at now}
        parent (recordings-root root)
        _ (files/ensure-dir! parent)
        tmp (Files/createTempDirectory parent ".tmp-"
                                       (make-array FileAttribute 0))
        final (recording-dir root id)]
    (try
      (Files/write (resolve-path tmp audio-file) bytes
                   (make-array OpenOption 0))
      (files/write-edn! (resolve-path tmp "recording.edn") meta)
      (files/move-directory! tmp final)
      {:recording meta :path final}
      (catch Throwable t
        (try (files/delete-tree! parent tmp) (catch Throwable _ nil))
        (throw t)))))

(defn read-recording-meta
  "Return one recording's metadata, or nil when it does not exist."
  [root recording-id]
  (let [dir (recording-dir root recording-id)
        meta-path (resolve-path dir "recording.edn")]
    (when (files/exists? dir)
      (let [meta (files/read-edn meta-path)]
        (when-not (map? meta)
          (error/fail! :SPEECH_RECORDING_CORRUPT
                       "recording metadata is missing or invalid"
                       {:recording-id recording-id}))
        (when-not (= recording-id (:recording-id meta))
          (error/fail! :SPEECH_RECORDING_CORRUPT
                       "recording id does not match its directory"
                       {:recording-id recording-id}))
        meta))))

(defn- audio-file-path
  [dir meta]
  (let [name (:audio-file meta)]
    (when-not (files/safe-file-name? name)
      (error/fail! :SPEECH_RECORDING_CORRUPT
                   "recording audio file name is not safe"
                   {:recording-id (:recording-id meta)}))
    (resolve-path dir name)))

(defn read-recording-audio
  "Read the original audio and verify its size and digest.

  Returns `{:meta meta :bytes bytes :content-type content-type}`."
  [root recording-id {:keys [max-bytes]}]
  (let [dir (recording-dir root recording-id)
        meta (or (read-recording-meta root recording-id)
                 (error/fail! :SPEECH_RECORDING_NOT_FOUND
                              "recording does not exist"
                              {:recording-id recording-id}))
        file (audio-file-path dir meta)
        _ (when-not (files/regular-file? file)
            (error/fail! :SPEECH_RECORDING_CORRUPT
                         "recording audio is missing"
                         {:recording-id recording-id}))
        size (Files/size file)]
    (when (and max-bytes (> size (long max-bytes)))
      (error/fail! :SPEECH_AUDIO_TOO_LARGE
                   "recording exceeds the configured limit"
                   {:recording-id recording-id
                    :bytes size
                    :limit (long max-bytes)}))
    (let [bytes (Files/readAllBytes file)]
      (when (not= (alength ^bytes bytes) (:bytes meta))
        (error/fail! :SPEECH_RECORDING_CORRUPT
                     "recording size does not match its metadata"
                     {:recording-id recording-id}))
      (when (and (:sha256 meta)
                 (not= (:sha256 meta) (files/sha256-hex bytes)))
        (error/fail! :SPEECH_RECORDING_CORRUPT
                     "recording digest does not match its metadata"
                     {:recording-id recording-id}))
      {:meta meta
       :bytes bytes
       :content-type (:content-type meta)})))

(defn open-recording-audio
  [root recording-id]
  (let [dir (recording-dir root recording-id)
        meta (or (read-recording-meta root recording-id)
                 (error/fail! :SPEECH_RECORDING_NOT_FOUND
                              "recording does not exist"
                              {:recording-id recording-id}))
        file (audio-file-path dir meta)]
    (when-not (files/regular-file? file)
      (error/fail! :SPEECH_RECORDING_CORRUPT
                   "recording audio is missing"
                   {:recording-id recording-id}))
    {:recording-id recording-id
     :stream (Files/newInputStream file (make-array OpenOption 0))
     :size (Files/size file)
     :content-type (:content-type meta)}))

(defn update-recording!
  "Apply F to one recording's metadata and rewrite it atomically."
  [root recording-id f now]
  (let [meta (or (read-recording-meta root recording-id)
                 (error/fail! :SPEECH_RECORDING_NOT_FOUND
                              "recording does not exist"
                              {:recording-id recording-id}))
        next (-> (f meta)
                 (assoc :recording-id recording-id
                        :updated-at (now-or now)))]
    (files/write-edn! (resolve-path (recording-dir root recording-id)
                                    "recording.edn")
                      next)
    next))

(defn delete-recording!
  [root recording-id]
  (let [dir (recording-dir root recording-id)]
    (if (files/exists? dir)
      (do (files/delete-tree! (recordings-root root) dir)
          true)
      false)))

(defn list-recordings
  "Return metadata for existing recordings, newest first."
  [root {:keys [session-id limit]}]
  (let [parent (recordings-root root)]
    (if-not (files/exists? parent)
      []
      (with-open [stream (Files/list parent)]
        (->> (.toList stream)
             (filter (fn [^Path dir]
                       (Files/isDirectory dir
                                          (make-array java.nio.file.LinkOption 0))))
             (remove (fn [^Path dir]
                       (str/starts-with? (str (.getFileName dir)) ".tmp-")))
             (keep (fn [^Path dir]
                     (let [id (str (.getFileName dir))]
                       (try
                         (read-recording-meta root id)
                         (catch Throwable t
                           {:recording-id id
                            :status :corrupt
                            :error (if (error/expected? t)
                                     (error/error-map t)
                                     {:code :SPEECH_RECORDING_CORRUPT})})))))
             (filter #(or (nil? session-id)
                          (= session-id (:session-id %))))
             (filter #(or (nil? session-id)
                          (= session-id (:session-id %))))
             (sort-by #(or (:created-at %) 0) >)
             ((fn [items]
                (if limit (take (long limit) items) items)))
             vec)))))

;; ---------------------------------------------------------------------------
;; TTS audio

(defn create-audio!
  "Publish one synthesized audio artifact.  A partial stream is stored with
  `:partial? true` and remains playable."
  [root {:keys [bytes content-type format purpose partial? message-id
                version-id service-id session-id client-id error max-bytes
                now]}]
  (check-bytes! bytes max-bytes)
  (let [now (now-or now)
        id (str "tts-" (UUID/randomUUID))
        content-type (safe-content-type
                      content-type
                      (files/content-type-for-format format))
        extension (files/content-extension content-type)
        audio-file (str "audio." extension)
        meta {:audio-id id
              :content-type content-type
              :format format
              :audio-file audio-file
              :bytes (alength ^bytes bytes)
              :sha256 (files/sha256-hex bytes)
              :purpose (or purpose :read-aloud)
              :partial? (boolean partial?)
              :message-id message-id
              :version-id version-id
              :service-id service-id
              :session-id session-id
              :client-id client-id
              :error error
              :created-at now
              :updated-at now}
        parent (audio-root root)
        _ (files/ensure-dir! parent)
        tmp (Files/createTempDirectory parent ".tmp-"
                                       (make-array FileAttribute 0))
        final (audio-dir root id)]
    (try
      (Files/write (resolve-path tmp audio-file) bytes
                   (make-array OpenOption 0))
      (files/write-edn! (resolve-path tmp "audio.edn") meta)
      (files/move-directory! tmp final)
      {:audio meta :path final}
      (catch Throwable t
        (try (files/delete-tree! parent tmp) (catch Throwable _ nil))
        (throw t)))))

(defn read-audio-meta
  [root audio-id]
  (let [dir (audio-dir root audio-id)
        meta-path (resolve-path dir "audio.edn")]
    (when (files/exists? dir)
      (let [meta (files/read-edn meta-path)]
        (when-not (map? meta)
          (error/fail! :SPEECH_AUDIO_CORRUPT
                       "audio metadata is missing or invalid"
                       {:audio-id audio-id}))
        (when-not (= audio-id (:audio-id meta))
          (error/fail! :SPEECH_AUDIO_CORRUPT
                       "audio id does not match its directory"
                       {:audio-id audio-id}))
        meta))))

(defn read-audio-bytes
  [root audio-id]
  (let [dir (audio-dir root audio-id)
        meta (or (read-audio-meta root audio-id)
                 (error/fail! :SPEECH_AUDIO_NOT_FOUND
                              "audio does not exist"
                              {:audio-id audio-id}))
        file (audio-file-path dir meta)
        _ (when-not (files/regular-file? file)
            (error/fail! :SPEECH_AUDIO_CORRUPT
                         "audio file is missing"
                         {:audio-id audio-id}))
        bytes (Files/readAllBytes file)]
    (when (not= (alength ^bytes bytes) (:bytes meta))
      (error/fail! :SPEECH_AUDIO_CORRUPT
                   "audio size does not match its metadata"
                   {:audio-id audio-id}))
    (when (and (:sha256 meta)
               (not= (:sha256 meta) (files/sha256-hex bytes)))
      (error/fail! :SPEECH_AUDIO_CORRUPT
                   "audio digest does not match its metadata"
                   {:audio-id audio-id}))
    {:meta meta :bytes bytes :content-type (:content-type meta)}))

(defn open-audio
  [root audio-id]
  (let [dir (audio-dir root audio-id)
        meta (or (read-audio-meta root audio-id)
                 (error/fail! :SPEECH_AUDIO_NOT_FOUND
                              "audio does not exist"
                              {:audio-id audio-id}))
        file (audio-file-path dir meta)]
    (when-not (files/regular-file? file)
      (error/fail! :SPEECH_AUDIO_CORRUPT
                   "audio file is missing"
                   {:audio-id audio-id}))
    {:audio-id audio-id
     :stream (Files/newInputStream file (make-array OpenOption 0))
     :size (Files/size file)
     :content-type (:content-type meta)}))

(defn delete-audio!
  [root audio-id]
  (let [dir (audio-dir root audio-id)]
    (if (files/exists? dir)
      (do (files/delete-tree! (audio-root root) dir)
          true)
      false)))

(defn list-audio
  [root {:keys [limit session-id]}]
  (let [parent (audio-root root)]
    (if-not (files/exists? parent)
      []
      (with-open [stream (Files/list parent)]
        (->> (.toList stream)
             (filter (fn [^Path dir]
                       (Files/isDirectory dir
                                          (make-array java.nio.file.LinkOption 0))))
             (remove (fn [^Path dir]
                       (str/starts-with? (str (.getFileName dir)) ".tmp-")))
             (keep (fn [^Path dir]
                     (let [id (str (.getFileName dir))]
                       (try
                         (read-audio-meta root id)
                         (catch Throwable _
                           nil)))))
             (filter #(or (nil? session-id)
                          (= session-id (:session-id %))))
             (sort-by #(or (:created-at %) 0) >)
             ((fn [items]
                (if limit (take (long limit) items) items)))
             vec)))))
