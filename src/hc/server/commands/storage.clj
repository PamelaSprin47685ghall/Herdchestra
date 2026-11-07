(ns hc.server.commands.storage
  "Storage-backed compare-and-set store for `hc.server.commands.durable`.

  This is an optional adapter.  The pure command gate does not require it.
  One partition holds one command-state document, so the gate window and its
  receipts commit atomically."
  (:require [clojure.string :as str]
            [hc.server.commands.durable :as durable]
            [hc.server.storage :as storage])
  (:import (java.util UUID)))

(defn- storage-error-code
  [t]
  (try
    (storage/error-code t)
    (catch Throwable _ nil)))

(defn- conflict-error
  [expected actual]
  {:code :VERSION_CONFLICT
   :message "stored version does not match the expected version"
   :details {:expected expected :actual actual}})

(defn state-from-view
  "Return `{:version n :state doc}` from a command-gate partition view.

  A versioned partition without its state document is corruption, not an
  empty gate.  Treating it as empty would silently drop receipts and allow a
  replayed side effect."
  [{:keys [version state] :as view}]
  (let [version (long (or version 0))]
    (if (and (pos? version) (nil? state))
      (throw (ex-info "command gate partition has a version but no state document"
                      {:code :COMMAND_STORE_CORRUPT
                       :partition (:partition view)
                       :version version}))
      {:version version :state state})))

(defn store
  "Create a durable command-gate store backed by STORAGE.

  Options:
    :partition  storage partition, default \"app-commands\"
    :id         document id, default the partition name"
  ([storage]
   (store storage {}))
  ([storage {:keys [partition id]
             :or {partition "app-commands"}
             :as opts}]
   (when-not (and (string? partition) (not (str/blank? partition)))
     (throw (ex-info "command store needs a non-blank partition"
                     {:code :INVALID_COMMAND_STORE
                      :opts opts})))
   (let [id (or id partition)]
     (reify durable/Store
       (load-state [_]
         (state-from-view
          (storage/read-session-view
           storage
           {:partition partition :session-id id})))

       (commit! [_ expected-version new-state]
         (try
           (let [result
                 (storage/commit-migration!
                  storage
                  {:migration-id (str (UUID/randomUUID))
                   :partition partition
                   :session-id id
                   :expected-version expected-version
                   :reason :command-state-commit
                   :put [{:kind :owner-state
                          :id id
                          :doc new-state}]})]
             {:ok? true :version (:version result)})
           (catch clojure.lang.ExceptionInfo t
             (if (= :STORAGE_VERSION_CONFLICT (storage-error-code t))
               (let [details (ex-data t)]
                 {:ok? false
                  :error (conflict-error expected-version
                                         (or (:actual details)
                                             (:version details)))})
               (throw t)))))

       (close-store! [_]
         ;; The storage environment is shared by the application; the
         ;; application closes it once during shutdown.
         {:ok? true})))))
