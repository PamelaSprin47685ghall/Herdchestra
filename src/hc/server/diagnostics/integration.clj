(ns hc.server.diagnostics.integration
  "Application shell adapter for diagnostics.

  The application shell calls `attach` once after the store opens.  The
  returned service is passed to the command router and the baseline snapshot.
  This namespace owns the concrete bridge to the managed blob area, so the
  app shell does not need to know how a diagnostic artifact is written."
  (:require [hc.server.artifacts :as artifacts]
            [hc.server.blob-store :as blob]
            [hc.version :as version]
            [hc.server.diagnostics.service :as service]))

(defn- platform
  []
  (or (System/getProperty "os.name") "unknown"))

(defn- os-version
  []
  (or (System/getProperty "os.version") "unknown"))

(defn attach
  "Build a diagnostics service from a started application system.

  Reads `:store`, `:blob-root`, and optional `:app-version`.  When
  `:blob-root` is present, exported bundles are stored through
  `hc.server.blob-store/put-bytes!` and the receipt carries only the content
  ID and filename.  No network path exists."
  [system]
  (let [blob-root (:blob-root system)
        artifact-service (when blob-root
                           (artifacts/service {:store (:store system)
                                               :blob-root blob-root}))
        artifact-writer (when blob-root
                          (fn [filename bytes]
                            (let [stored (blob/put-bytes! blob-root bytes)
                                  registered
                                  (artifacts/register!
                                   artifact-service
                                   {:session-id "diagnostics"
                                    :content-id (:id stored)
                                    :name filename
                                    :kind :diagnostic
                                    :source-task-id "diagnostics-bundle"
                                    :producer {:kind :diagnostics
                                               :name "bundle"}})]
                              (let [artifact (:artifact registered)]
                                {:id (:content-id artifact)
                                 :artifact-id (:id artifact)
                                 :size (:size artifact)}))))]
    (service/service
     {:store (:store system)
      :now-fn #(System/currentTimeMillis)
      :app-version (or (:app-version system) version/version)
      :platform (platform)
      :os-version (os-version)
      :artifact-writer artifact-writer})))

(defn dispatch
  "Handle one `:diagnostics/*` payload from the application router."
  [diagnostics payload]
  (service/handle! diagnostics payload))

(defn baseline
  "A small baseline entry for the application snapshot.

  Full statistics are on demand; the baseline carries only log status and
  record counts so a 25 ms publish loop does not rescan every request."
  [diagnostics]
  (let [snap (try
               (service/log-status diagnostics)
               (catch Throwable _ {:read-error true}))]
    {:log snap}))
