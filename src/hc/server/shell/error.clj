(ns hc.server.shell.error
  "Ordinary rejections and EDN-safe failure descriptions for the managed
  shell path.

  A shell request that has not started a process may be rejected before any
  side effect.  Such a rejection is `:not-started` and `:file-changed? false`.
  Once a process has started, the shell result is either `:COMPLETED` with
  observed facts or `:UNCERTAIN` with `:file-changed? :unknown`; it is never
  collapsed into a boolean success."
  (:require [clojure.string :as str]))

(def not-started
  {:outcome :not-started
   :file-changed? false})

(defn rejection
  "Build an ordinary rejection result.  Callers must only use this before a
  process has started."
  ([status code message]
   (rejection status code message nil))
  ([status code message data]
   (merge not-started
          {:status status
           :code code
           :message message}
          data)))

(defn fail!
  "Throw an ordinary rejection.  Used by request validation and the board
  adapter so the session worker aborts before a process starts."
  ([status code message]
   (fail! status code message nil))
  ([status code message data]
   (throw (ex-info message
                   (rejection status code message data)))))

(defn expected?
  "True for the ex-info values produced by `fail!`."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (contains? (ex-data t) :status)
       (contains? (ex-data t) :outcome)))

(defn throwable->data
  "EDN-friendly description of a Throwable.  Public shell results never carry
  a raw exception object."
  [^Throwable t]
  (when t
    {:class (.getName (class t))
     :message (.getMessage t)
     :data (ex-data t)
     :cause (when-let [cause (.getCause t)]
              {:class (.getName (class cause))
               :message (.getMessage cause)})}))

(defn describe
  "Short text for logs; never a result code."
  [result]
  (str/join " " (remove nil? [(:status result) (:code result) (:message result)])))
