(ns hc.core.workspace-gate.error
  "Result classification shared by the five file-contract components.

  Every ordinary rejection is `:not-written`: the real file system has not
  been touched.  A hard failure is `:unknown`: the file system may have
  changed and the caller must inspect the site.  Never collapse these into
  a boolean."
  (:require [clojure.string :as str]))

(def not-written
  {:outcome :not-written
   :file-changed? false})

(def ^:private byte-array-class (Class/forName "[B"))

(declare throwable->data)

(defn- edn-safe
  "Turn arbitrary exception/result data into EDN-safe values.

  Byte arrays never leak their contents into a model-facing error; paths and
  throwables become strings/maps.  Unknown objects are represented by their
  printed form rather than a raw Java object."
  [x]
  (cond
    (nil? x) nil
    (or (string? x) (number? x) (boolean? x) (keyword? x) (symbol? x)) x
    (map? x) (into {} (map (fn [[k v]] [(edn-safe k) (edn-safe v)])) x)
    (vector? x) (mapv edn-safe x)
    (set? x) (into #{} (map edn-safe x))
    (sequential? x) (mapv edn-safe x)
    (instance? byte-array-class x) {:bytes (alength ^bytes x)}
    (instance? java.nio.file.Path x) (str x)
    (instance? java.util.UUID x) (str x)
    (instance? Throwable x) (throwable->data x)
    (instance? java.util.Map x)
    (into {} (map (fn [[k v]] [(edn-safe k) (edn-safe v)])) x)
    (instance? java.util.List x) (mapv edn-safe x)
    (instance? java.util.Set x) (into #{} (map edn-safe x))
    :else (pr-str x)))

(defn sanitize
  "Public wrapper for the EDN-safe conversion used by result builders."
  [x]
  (edn-safe x))

(defn failure
  "Build an ordinary rejection result."
  ([status code message]
   (failure status code message nil))
  ([status code message data]
   (merge not-written
          {:status status
           :code code
           :message message}
          (edn-safe data))))

(defn fail!
  "Throw an ordinary rejection.  Batch-building tools use this so a program
  aborts before anything reaches the real disk."
  ([status code message]
   (fail! status code message nil))
  ([status code message data]
   (throw (ex-info message
                   (merge not-written
                          {:status status
                           :code code
                           :message message}
                           (edn-safe data))))))

(def ^:private ordinary-statuses
  #{:FILE_CONFLICT :STRUCTURE_CONFLICT :PREPARE_REJECTED
    :UNSUPPORTED_OPERATION :ACTOR_INACTIVE :CLAIM_CHANGED
    :WAITING_FOR_WRITE_GATE :LIMIT_EXCEEDED
    :RECOVERY_UNRESOLVED :BOARD_EVENT_PERSIST_FAILED
    :INVALID_ARGUMENT :CALL_REPLAY_MISMATCH :UNKNOWN_REQUEST})

(defn expected-failure?
  "True for the ex-info values produced by `fail!`."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (contains? (ex-data t) :status)
       (contains? (ex-data t) :outcome)))

(defn rejection?
  [result]
  (and (map? result)
       (or (= :not-written (:outcome result))
           (contains? ordinary-statuses (:status result)))))

(defn applied?
  [result]
  (= :APPLIED (:status result)))

(defn hard-failure?
  [result]
  (= :HARD_FAILURE (:status result)))

(defn describe
  "Short human-readable text for logs; never used as a result code."
  [result]
  (str/join " " (remove nil? [(:status result) (:code result) (:message result)])))

(defn throwable->data
  "EDN-friendly description of a Throwable.  Results are presented to models
  as EDN, so raw exception objects never enter a public result."
  [^Throwable t]
  (when t
    {:class (.getName (class t))
     :message (.getMessage t)
     :data (edn-safe (ex-data t))
     :cause (when-let [cause (.getCause t)]
              {:class (.getName (class cause))
               :message (.getMessage cause)})}))
