(ns hc.client.backup.browser-smoke
  "Minimal browser mount for the backup panel.

  This is a test entry, not production routing.  It renders the same React
  component the workbench mounts so a headless browser can verify focusable
  controls, labels and the offline-restore notice without a server."
  (:require [hc.client.backup.model :as model]
            [hc.client.backup.react :as react-view]
            ["react-dom/client" :as react-dom-client]))

(defn- status-result []
  {:data-dir "/data"
   :default-target-id :local
   :targets [{:id :local :kind :file :configured? true :available? true
              :root "/data/backups"}
             {:id :dav :kind :webdav :configured? true :available? false
              :base-url "https://dav.example.com" :directory "hc"
              :reason "无法连接 WebDAV"}]
   :target {:id :local :kind :file :configured? true :available? true
            :root "/data/backups"}
   :restore {:online? false :offline? true :requires-quiescence? true}
   :backups [{:id "b1" :sampled-at "2026-11-03T00:00:00Z"
              :created-at "2026-11-03T00:00:01Z" :device "laptop"
              :app-version "0.0.1" :encrypted? true :format-version 1
              :size 1024 :integrity :verified}]
   :recovery-points [{:id "rp-1" :created-at "2026-11-03T00:00:02Z"
                      :reason :before-restore :scope [:conversations]}]
   :errors []})

(defn- act [state action]
  (:state (model/reduce-action state action)))

(defn- apply-result [state result]
  (let [id (-> state :outbox last :command-id)]
    (act state {:type :backup/apply-receipt
                :receipt {:command-id id :ok? true :receipt result}})))

(defn demo-state []
  (-> (model/initial-state)
      (assoc :sync {:phase :synced :command-epoch 7})
      (assoc-in [:ui :transport-available?] true)
      (assoc-in [:ui :target-id] :local)
      (assoc-in [:server :default-target-id] :local)
      (act {:type :backup/refresh})
      (apply-result (status-result))
      (act {:type :backup/select-backup :backup-id "b1"})
      (act {:type :backup/set-field :field :password
            :value "correct horse battery staple"})
      (act {:type :backup/preview})
      (apply-result {:mode :merge
                     :summary {:local 5 :imported 5 :added 1
                               :replaced 0 :removed 0 :skipped 3
                               :conflicts 1 :expired-skipped 0
                               :memory-pending 0}
                     :conflicts [{:category :assistants
                                  :id "a1" :imported-id "a1"
                                  :name "Reviewer"
                                  :reason :same-name-different-content
                                  :resolution :keep-both
                                  :choices [:keep-local :use-backup :keep-both]}]
                     :placeholders []
                     :missing-relations []
                     :relink-required []
                     :needs-review []
                     :incomplete-attachments []
                     :memory-reactivation []
                     :expired-skipped []
                     :permissions {}
                     :recovery-point {:required? true :status :will-create}
                     :no-changes? false
                     :already-imported? false
                     :replace-all? false
                     :memory-reactivation-required? false
                     :warnings []})
      (act {:type :backup/select-section :section :restore})))

(defn ^:export init []
  (let [node (js/document.getElementById "backup-root")
        state (demo-state)]
    (when node
      (.render (react-dom-client/createRoot node)
               (react-view/backup-element state (fn [_]))))
    nil))
