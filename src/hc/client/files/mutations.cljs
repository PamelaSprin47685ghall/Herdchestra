(ns hc.client.files.mutations
  "Fulcro mutations for `:ui/files`.

  Each mutation changes only the local display copy.  A late HTTP response is
  applied only when its request id still matches the current request, so an
  older directory or file cannot overwrite a newer selection."
  (:require [com.fulcrologic.fulcro.mutations :as m]
            [hc.client.files.model :as model]))

(defn- update-files
  [state f]
  (swap! state update :ui/files
         (fn [files]
           (f (or files (model/initial))))))

(m/defmutation load-start
  [{:keys [path request-id]}]
  (action [env]
    (update-files (:state env)
                  #(assoc % :status :loading
                            :path (str (or path ""))
                            :request-id request-id
                            :error nil))))

(m/defmutation load-result
  [{:keys [request-id result]}]
  (action [env]
    (update-files (:state env)
                  (fn [files]
                    (if (= request-id (:request-id files))
                      (model/apply-list files result)
                      files)))))

(m/defmutation navigate
  [{:keys [path]}]
  (action [env]
    (update-files (:state env) #(model/navigate % path))))

(m/defmutation preview-start
  [{:keys [path request-id]}]
  (action [env]
    (update-files (:state env)
                  #(assoc % :selected-path path
                            :preview-status :loading
                            :preview-request-id request-id
                            :preview-error nil))))

(m/defmutation preview-result
  [{:keys [path request-id result]}]
  (action [env]
    (update-files (:state env)
                  (fn [files]
                    (if (and (= request-id (:preview-request-id files))
                             (= path (:selected-path files)))
                      (model/apply-read files result)
                      files)))))

(m/defmutation set-search-query
  [{:keys [value]}]
  (action [env]
    (update-files (:state env) #(assoc % :search-query (str (or value ""))))))

(m/defmutation search-start
  [{:keys [query request-id]}]
  (action [env]
    (update-files (:state env)
                  #(assoc % :search
                          (assoc (:search %)
                                 :status :loading
                                 :query (str (or query ""))
                                 :request-id request-id
                                 :error nil)))))

(m/defmutation search-result
  [{:keys [request-id result]}]
  (action [env]
    (update-files (:state env)
                  (fn [files]
                    (if (= request-id (get-in files [:search :request-id]))
                      (model/apply-search files result)
                      files)))))

(m/defmutation clear-search
  [_]
  (action [env]
    (update-files (:state env) model/clear-search)))
