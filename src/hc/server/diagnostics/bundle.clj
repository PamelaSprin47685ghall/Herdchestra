(ns hc.server.diagnostics.bundle
  "Local-only diagnostic bundle preview.

  A bundle is prepared locally, shown to the user, and exported only after the
  user chooses a destination.  This namespace contains no network code and no
  upload entry point.  `:transfer` is always `:local-only`; there is no
  background submission path.

  The default preview contains application version, platform, time range,
  error category, and redacted service metadata.  It does not contain chat
  bodies, attachments, secrets, private paths, or a full request.  Extra
  materials are listed but excluded by default; the user must select each one
  and each carries a purpose.  Forbidden material kinds are rejected even if a
  caller asks for them.

  Error detail and the raw provider error are opt-in as well.  The default
  error section states that each one is not included.

  Secrets are redacted before the preview is returned.  The findings show the
  path, kind, and placeholder; they never carry the original value.  The user
  can remove individual lines, rename a nickname, or hide hosts and paths
  before exporting."
  (:require [clojure.string :as str]
            [hc.server.diagnostics.redact :as redact])
  (:import (java.io OutputStream)
           (java.nio.charset StandardCharsets)))

(def error-categories
  {:no-model {:label "No model selected"
              :action "Choose an available model; the draft remains."
              :retryable? false}
   :connection-disabled {:label "Connection disabled"
                         :action "Open connection settings or choose another connection."
                         :retryable? false}
   :model-removed {:label "Model no longer available"
                   :action "Choose a replacement model; history remains readable."
                   :retryable? false}
   :invalid-credentials {:label "Credentials rejected"
                         :action "Update the credential or choose another connection."
                         :retryable? false}
   :authorization-expired {:label "Account authorization expired"
                           :action "Reconnect the account."
                           :retryable? false}
   :quota-exhausted {:label "Quota exhausted"
                     :action "Check the service quota or choose another model."
                     :retryable? false}
   :rate-limited {:label "Service rate limit"
                  :action "Wait and retry, or cancel."
                  :retryable? true}
   :network-unreachable {:label "Network unreachable"
                         :action "Check the connection and retry later."
                         :retryable? true}
   :response-timeout {:label "Service did not return content"
                      :action "Continue waiting, stop, or retry."
                      :retryable? true}
   :input-too-large {:label "Input exceeds the model window"
                     :action "Reduce the context range or choose another model."
                     :retryable? false}
   :attachment-unsupported {:label "Attachment type unsupported"
                            :action "Choose another model or convert the material."
                            :retryable? false}
   :partial-materials {:label "Some materials were not processed"
                       :action "Review the missing items."
                       :retryable? false}
   :local-storage-full {:label "Local storage is full"
                        :action "Free space or export the current content."
                        :retryable? false}
   :local-data-unreadable {:label "Local data cannot be opened"
                           :action "Open recovery options; the original file was not replaced."
                           :retryable? false}
   :external-file-moved {:label "External file not found"
                         :action "Relocate or remove the link."
                         :retryable? false}
   :permission-revoked {:label "Permission revoked"
                        :action "Restore the permission or choose another method."
                        :retryable? false}
   :tool-failed {:label "Tool step did not complete"
                 :action "Review the cause; completed steps remain."
                 :retryable? false}
   :receipt-lost {:label "Operation result unknown"
                  :action "Check the target state before retrying."
                  :retryable? false}
   :schedule-missed {:label "Scheduled time missed"
                     :action "Run manually or view the next time."
                     :retryable? false}
   :backup-password-invalid {:label "Backup password rejected"
                             :action "Re-enter the password; current data is unchanged."
                             :retryable? false}
   :import-format-unsupported {:label "Import format not recognized"
                               :action "Review the supported formats."
                               :retryable? false}
   :restore-conflict {:label "Restore conflict"
                      :action "Review the differences and choose."
                      :retryable? false}
   :format-too-new {:label "Format is newer than this application"
                    :action "Update the application or keep the original file."
                    :retryable? false}
   :export-cancelled {:label "Export cancelled"
                      :action "Return or export again."
                      :retryable? false}
   :unclassified {:label "Specific cause could not be determined"
                  :action "Check connection, credentials, quota, and input size in that order."
                  :retryable? false}})

(def error-category-order
  [:no-model :connection-disabled :model-removed :invalid-credentials
   :authorization-expired :quota-exhausted :rate-limited :network-unreachable
   :response-timeout :input-too-large :attachment-unsupported
   :partial-materials :local-storage-full :local-data-unreadable
   :external-file-moved :permission-revoked :tool-failed :receipt-lost
   :schedule-missed :backup-password-invalid :import-format-unsupported
   :restore-conflict :format-too-new :export-cancelled :unclassified])

(def default-exclusions
  ["chat body"
   "attachments"
   "secrets"
   "private paths"
   "full request"])

(def forbidden-material-kinds
  #{:chat-body :chat :attachment :secret :private-path :full-request
    :request-body :prompt :response})

(defn- category
  [value]
  (if (contains? error-categories value)
    value
    :unclassified))

(defn- error-info
  [reported reported-category]
  (let [category (category reported)
        info (get error-categories category)]
    (cond-> {:category category
             :label (:label info)
             :action (:action info)
             :retryable? (:retryable? info)}
      (and (some? reported-category)
           (not= category reported-category))
      (assoc :reported-category reported-category))))

(defn- line
  [id label value]
  {:id id :label label :value value})

(defn- app-section
  [{:keys [app-version platform os-version locale]}]
  {:id :app
   :title "Application"
   :lines (cond-> [(line "app-version" "Version" (or app-version "unknown"))
                   (line "app-platform" "Platform" (or platform "unknown"))
                   (line "app-os" "OS" (or os-version "unknown"))]
            locale (conj (line "app-locale" "Locale" locale)))})

(defn- range-section
  [{:keys [time-range]}]
  {:id :time-range
   :title "Time range"
   :lines [(line "range-from" "From" (or (:from time-range) "unknown"))
           (line "range-to" "To" (or (:to time-range) "unknown"))
           (line "range-zone" "Timezone" (or (:zone time-range) "unknown"))]})

(defn- error-section
  [{:keys [error-category error-detail raw-error
           include-error-detail? include-raw-error?]}]
  (let [info (error-info error-category error-category)]
    {:id :error
     :title "Error"
     :lines (cond-> [(line "error-category" "Category" (:category info))
                     (line "error-label" "Meaning" (:label info))
                     (line "error-action" "Next step" (:action info))
                     (line "error-retryable" "Retryable" (:retryable? info))]
              (:reported-category info)
              (conj (line "error-reported-category"
                          "Reported category"
                          (:reported-category info)))

              (and include-error-detail? (some? error-detail))
              (conj (line "error-detail" "Detail" error-detail))

              (not (and include-error-detail? (some? error-detail)))
              (conj (line "error-detail" "Detail" "not included"))

              (and include-raw-error? (some? raw-error))
              (conj (line "error-raw" "Provider error" raw-error))

              (not (and include-raw-error? (some? raw-error)))
              (conj (line "error-raw" "Provider error"
                          "not included")))}))

(defn- service-lines
  [services]
  (mapv (fn [service index]
          (line (str "service-" index)
                (or (:display-name service)
                    (:connection-id service)
                    (str "service-" index))
                (select-keys service
                             [:connection-id :display-name :protocol
                              :actual-host :account-label])))
        services
        (range)))

(defn- services-section
  [services]
  {:id :services
   :title "Services"
   :lines (if (seq services)
            (service-lines services)
            [(line "services-none" "Services" "none supplied")])})

(defn- redaction-section
  [{:keys [placeholder-count finding-count]}]
  {:id :redaction
   :title "Redaction"
   :lines [(line "redaction-placeholders" "Placeholders" (or placeholder-count 0))
           (line "redaction-findings" "Redacted fields" (or finding-count 0))
           (line "redaction-review" "Review"
                 "Confirm this preview contains nothing you do not want to share.")]})

(defn- material-view
  [material]
  (let [kind (:kind material)
        forbidden? (contains? forbidden-material-kinds kind)
        purpose? (not (str/blank? (str (:purpose material))))
        requested? (true? (:include? material))]
    (cond-> {:id (:id material)
             :title (:title material)
             :purpose (:purpose material)
             :kind kind
             :included? (and requested? (not forbidden?) purpose?)}
      forbidden? (assoc :rejected? true
                        :rejected-reason :forbidden-kind)

      (and requested? (not forbidden?) (not purpose?))
      (assoc :rejected? true
             :rejected-reason :purpose-missing))))

(defn- included-materials
  [materials]
  (into []
        (keep (fn [material]
                (when (and (true? (:include? material))
                           (not (contains? forbidden-material-kinds
                                           (:kind material)))
                           (not (str/blank? (str (:purpose material)))))
                  {:id (:id material)
                   :title (:title material)
                   :purpose (:purpose material)
                   :kind (:kind material)
                   :content (:content material)})))
        materials))

(defn- extra-section
  [materials]
  {:id :extra-materials
   :title "Extra materials"
   :lines (if (seq materials)
            (mapv (fn [material index]
                    (line (str "material-" (or (:id material) index))
                          (str (:title material)
                               (when (:purpose material)
                                 (str " — " (:purpose material))))
                          (:content material)))
                  materials
                  (range))
            [(line "materials-none" "Extra materials" "none selected")])})

(defn- body
  [request]
  (let [materials (included-materials (:extra-materials request))]
    {:app (select-keys request [:app-version :platform :os-version :locale])
     :time-range (:time-range request)
     :error (error-info (:error-category request)
                        (:reported-category request))
     :services (:services request)
     :sections (cond-> [(app-section request)
                        (range-section request)
                        (error-section request)
                        (services-section (:services request))]
                 (seq materials)
                 (conj (extra-section materials)))}))

(defn- merge-findings
  [a b]
  (vec (distinct (concat a b))))

(defn- redaction-summary
  [findings]
  {:findings (vec findings)
   :finding-count (count findings)
   :placeholder-count (count (distinct (map :placeholder findings)))
   :redacted? (boolean (seq findings))})

(defn- with-redaction-section
  [content summary]
  (let [sections (:sections content)
        replaced? (some #(= :redaction (:id %)) sections)
        sections (if replaced?
                   (mapv (fn [section]
                           (if (= :redaction (:id section))
                             (redaction-section summary)
                             section))
                         sections)
                   (conj (vec sections) (redaction-section summary)))]
    (assoc content :sections sections)))

(defn prepare
  "Prepare a local diagnostic preview.

  The returned map is data only.  `:transfer` states that nothing was
  uploaded and nothing is automatic.  The caller must show `:sections` to the
  user and write `local-artifact` only after the user chooses a destination."
  [request]
  (let [request (or request {})
        options {:rename (:rename request)
                 :mask-hosts? (:mask-hosts? request)
                 :mask-paths? (:mask-paths? request)}
        materials (:extra-materials request)
        rejected (into []
                       (keep (fn [material]
                               (when (or (contains? forbidden-material-kinds
                                                    (:kind material))
                                         (and (true? (:include? material))
                                              (str/blank? (str (:purpose material)))))
                                 (select-keys material [:id :title :kind]))))
                       materials)
        redaction (redact/redact (body request) options)
        summary (redaction-summary (:findings redaction))
        content (with-redaction-section (:value redaction) summary)
        generated-at (:generated-at request)
        bundle-id (or (:bundle-id request)
                      (str "diag-" (or generated-at "unknown")))]
    (with-meta
      {:bundle-id bundle-id
       :generated-at generated-at
       :content content
       :sections (:sections content)
       :extra-materials (mapv material-view materials)
       :rejected-materials rejected
       :redaction summary
       :excluded default-exclusions
       :ignored-fields (vec (filter #(contains? request %)
                                    [:chat :chat-body :attachments
                                     :request-body :prompt :response]))
       :review-required? true
       :warning "Review this preview before sharing. Remove lines or hide domains as needed."
       :transfer {:mode :local-only
                  :automatic-upload? false
                  :network? false
                  :requires-user-action? true
                  :destination :user-selected}}
      {::redaction-options options})))

(def ^:private app-line-keys
  {"app-version" :app-version
   "app-platform" :platform
   "app-os" :os-version
   "app-locale" :locale})

(def ^:private range-line-keys
  {"range-from" :from
   "range-to" :to
   "range-zone" :zone})

(def ^:private error-line-keys
  {"error-category" :category
   "error-label" :label
   "error-action" :action
   "error-retryable" :retryable?
   "error-reported-category" :reported-category})

(defn- prune-map
  [value line-ids line->key]
  (reduce (fn [value [line-id key]]
            (if (contains? line-ids line-id)
              (dissoc value key)
              value))
          (or value {})
          line->key))

(defn- prune-services
  [services line-ids]
  (vec (keep-indexed (fn [index service]
                       (when-not (contains? line-ids
                                            (str "service-" index))
                         service))
                     services)))

(defn remove-lines
  "Remove preview lines by id and remove their values from the exported
  content.  The section view and the serialized `:content` must agree; a
  removed line must not survive in a top-level map that export-view includes."
  [bundle line-ids]
  (let [line-ids (set line-ids)
        sections (mapv (fn [section]
                         (update section :lines
                                 (fn [lines]
                                   (filterv #(not (contains? line-ids (:id %)))
                                            lines))))
                       (get-in bundle [:content :sections]))
        content (:content bundle)
        content (-> content
                    (update :app prune-map line-ids app-line-keys)
                    (update :time-range prune-map line-ids range-line-keys)
                    (update :error prune-map line-ids error-line-keys)
                    (update :services prune-services line-ids)
                    (assoc :sections sections))]
    (-> bundle
        (assoc :content content)
        (assoc :sections sections)
        (update :removed-line-ids (fnil into #{}) line-ids))))

(defn- re-redact
  [bundle options]
  (let [options (merge (::redaction-options (meta bundle)) options)
        redaction (redact/redact (:content bundle) options)
        old-findings (get-in bundle [:redaction :findings])
        findings (merge-findings old-findings (:findings redaction))
        summary (redaction-summary findings)
        content (with-redaction-section (:value redaction) summary)]
    (with-meta
      (-> bundle
          (assoc :content content)
          (assoc :sections (:sections content))
          (assoc :redaction summary))
      {::redaction-options options})))

(defn rename
  "Apply user-selected nickname replacements to the preview content."
  [bundle mapping]
  (re-redact bundle {:rename mapping}))

(defn hide-hosts
  "Hide service domains in the preview content."
  [bundle]
  (re-redact bundle {:mask-hosts? true}))

(defn hide-paths
  "Hide home-directory paths in the preview content."
  [bundle]
  (re-redact bundle {:mask-paths? true}))

(defn export-view
  "The exact map a caller may serialize to a user-selected local file."
  [bundle]
  (-> bundle
      (dissoc :removed-line-ids)
      (assoc-in [:transfer :uploaded?] false)))

(defn- artifact-filename
  [bundle]
  (let [generated (or (:generated-at bundle) "unknown")]
    (str "herdchestra-diagnostics-"
         (str/replace (str generated) #"[^A-Za-z0-9._-]+" "-")
         ".edn")))

(defn write-local-artifact!
  "Write the artifact EDN directly to OUT, without building a full string.

  Returns the same local-only metadata as `local-artifact` plus `:size` in
  UTF-8 bytes.  The map is written one top-level entry at a time and the
  `:sections` vector one section at a time, so the largest temporary string is
  one section rather than the whole bundle."
  [bundle ^OutputStream out]
  (let [view (export-view bundle)
        size (volatile! 0)
        write! (fn [^String text]
                 (let [bytes (.getBytes text StandardCharsets/UTF_8)]
                   (.write out bytes)
                   (vswap! size + (alength bytes))))]
    (write! "{")
    (doseq [[k v] view]
      (write! (pr-str k))
      (write! " ")
      (if (and (= k :sections) (sequential? v))
        (do
          (write! "[")
          (doseq [section v]
            (write! (pr-str section))
            (write! " "))
          (write! "]"))
        (write! (pr-str v)))
      (write! " "))
    (write! "}")
    {:format :edn
     :filename (artifact-filename bundle)
     :size (long @size)
     :destination :user-selected
     :uploaded? false
     :automatic-upload? false
     :requires-user-action? true}))

(defn local-artifact
  "Serialize a bundle for a user-selected local destination.

  The result contains no URL and no upload action.  `:uploaded?` is false by
  construction; a caller cannot obtain an automatic transfer from this API."
  [bundle]
  {:format :edn
   :filename (artifact-filename bundle)
   :content (pr-str (export-view bundle))
   :destination :user-selected
   :uploaded? false
   :automatic-upload? false
   :requires-user-action? true})
