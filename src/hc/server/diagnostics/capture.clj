(ns hc.server.diagnostics.capture
  "Model-port decorator that records terminal attempts for statistics.

  Provider callbacks run on transport threads.  The decorator never writes
  storage there: it hands the terminal event to a single-thread executor and
  forwards the original event unchanged.  The application owns the returned
  `:stop!` function and calls it during shutdown."
  (:require [hc.server.model-port.contract :as contract])
  (:import (java.util.concurrent Executors ThreadFactory)
           (java.util.concurrent.atomic AtomicLong)))

(defn- thread-factory []
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable
                       (str "hc-diagnostics-capture-"
                            (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- default-identity
  [request _event]
  (let [snapshot (:snapshot request)]
    (select-keys snapshot
                 [:session-id :assistant-id :model :service-id :service-name
                  :purpose :temporary? :occurred-zone])))

(defn- result-of
  [event]
  (case (:event event)
    :completed :succeeded
    :failed :failed
    :cancelled :cancelled
    :succeeded))

(defn- id-string
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    :else (str value)))

(defn- request-structure
  "Describe the request shape without copying prompts or tool arguments.
  Detailed logs can then show what was sent without becoming a chat dump."
  [request]
  (let [snapshot (:snapshot request)
        messages (vec (:messages snapshot))]
    {:message-count (count messages)
     :roles (frequencies (keep :role messages))
     :tool-count (count (:tools snapshot))
     :model (:model snapshot)
     :stream? (boolean (:stream? snapshot))}))

(defn- terminal-request
  [request event identity-fn now]
  (merge {:request-id (id-string (:attempt-id request))
          :turn-id (id-string (:call-id request))
          :at now
          :result (result-of event)
          :usage (:usage event)
          :finish-reason (:finish-reason event)
          :error (:error event)
          :request-structure (request-structure request)}
         (identity-fn request event)))

(defn recording-port
  "Wrap PORT so terminal events call RECORD-FN with a normalized request.

  RECORD-FN runs on one owned daemon thread.  The original receiver still
  receives the event on the transport thread.  Returns

      {:port wrapped-port :stop! stop-fn}

  `:identity-fn` maps `[request event]` to request metadata such as
  `:session-id`, `:assistant-id`, `:model`, and `:service-id`.  `:now-fn`
  supplies epoch milliseconds."
  ([port record-fn]
   (recording-port port record-fn {}))
  ([port record-fn {:keys [identity-fn now-fn]}]
   (let [executor (Executors/newSingleThreadExecutor (thread-factory))
         identity-fn (or identity-fn default-identity)
         now-fn (or now-fn #(System/currentTimeMillis))
         wrapped
         (reify contract/ModelPort
           (start-request! [_ request receiver]
             (let [forwarding
                   (reify contract/Receiver
                     (deliver! [_ event]
                       (when (contains? #{:completed :failed :cancelled}
                                        (:event event))
                         (let [recorded (terminal-request request event
                                                          identity-fn
                                                          (now-fn))]
                           (.execute executor
                                     ^Runnable
                                     (fn []
                                       (try
                                         (record-fn recorded)
                                         (catch Throwable _ nil))))))
                       (contract/deliver! receiver event)))]
               (contract/start-request! port request forwarding)))
           (cancel-request! [_ handle]
             (contract/cancel-request! port handle)))]
     {:port wrapped
      :stop! (fn []
               (.shutdown executor)
               (try
                 (.awaitTermination executor 2
                                    java.util.concurrent.TimeUnit/SECONDS)
                 (catch InterruptedException _ nil))
               (when-not (.isTerminated executor)
                 (.shutdownNow executor))
               true)})))
