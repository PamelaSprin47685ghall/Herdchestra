(ns hc.client.files.browser
  "Fulcro controller for the workspace file browser.

  The controller owns HTTP requests and request-id guards.  The pure state
  transitions live in `hc.client.files.model`; the markup lives in
  `hc.client.files.ui`.  A stale response never replaces a newer directory
  or preview."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.mutations :as m]
            [hc.client.files.api :as api]
            [hc.client.files.model :as model]
            [hc.client.files.mutations :as mutations]
            [hc.client.files.ui :as ui]))

(defonce next-request-id
  (atom 0))

(defn- new-request-id
  []
  (swap! next-request-id inc))

(defn- files-state
  [this]
  (or (get (application/current-state this) :ui/files)
      (model/initial)))

(defn- load!
  [this path]
  (let [request-id (new-request-id)]
    (comp/transact! this [(mutations/load-start {:path path
                                                  :request-id request-id})])
    (api/list! path
               (fn [result]
                 (comp/transact! this
                                 [(mutations/load-result
                                   {:request-id request-id
                                    :result result})])))))

(defn- open!
  [this path]
  (let [request-id (new-request-id)]
    (comp/transact! this [(mutations/preview-start {:path path
                                                     :request-id request-id})])
    (api/read! path
               (fn [result]
                 (comp/transact! this
                                 [(mutations/preview-result
                                   {:path path
                                    :request-id request-id
                                    :result result})])))))

(defn- refresh!
  [this]
  (let [state (files-state this)
        path (or (:path state) "")
        selected (:selected-path state)]
    (load! this path)
    (when selected
      (open! this selected))))

(defn- navigate!
  [this path]
  (comp/transact! this [(mutations/navigate {:path path})])
  (load! this path))

(m/defmutation set-search-mode
  "Select `:name` or `:content` search.  It only changes the local switch;
  callers issue a new search when a query is present."
  [{:keys [mode]}]
  (action [env]
    (swap! (:state env) update :ui/files
           (fn [files]
             (model/set-search-mode (or files (model/initial)) mode)))))

(defn- search!
  ([this query]
   (search! this query (:search-mode (files-state this))))
  ([this query mode]
   (let [mode (model/normalize-search-mode mode)
         request-id (new-request-id)]
     (comp/transact! this [(set-search-mode {:mode mode})
                           (mutations/search-start
                            {:query query
                             :request-id request-id})])
     (api/search! query mode
                  (fn [result]
                    (comp/transact! this
                                    [(mutations/search-result
                                      {:request-id request-id
                                       :result result})]))))))

(defn- change-search-mode!
  [this mode]
  (let [state (files-state this)
        mode (model/normalize-search-mode mode)
        query (:search-query state)]
    (if (str/blank? (str query))
      (comp/transact! this [(set-search-mode {:mode mode})])
      (search! this query mode))))

(defn- clear-search!
  [this]
  (comp/transact! this [(mutations/clear-search)]))

(defn- copy-text!
  [text]
  (try
    (when (and (exists? js/navigator)
               (.-clipboard js/navigator)
               (fn? (.-writeText (.-clipboard js/navigator))))
      (.writeText (.-clipboard js/navigator) (str text)))
    (catch :default _ nil)))

(defn- ensure-loaded!
  [this]
  (let [state (files-state this)]
    (when (= :idle (:status state))
      (load! this (or (:path state) "")))))

(defn callbacks
  "Build the callback map for `hc.client.files.ui/file-browser-element`."
  [this]
  {:on-navigate (fn [path] (navigate! this path))
   :on-open (fn [path] (open! this path))
   :on-refresh (fn [] (refresh! this))
   :on-search (fn [query] (search! this query))
   :on-search-change (fn [value]
                       (comp/transact! this
                                       [(mutations/set-search-query
                                         {:value value})]))
   :on-search-mode-change (fn [mode] (change-search-mode! this mode))
   :on-clear-search (fn [] (clear-search! this))
   :on-copy copy-text!})

(defsc FilesPane
  "The `:ui/files` pane.  Mount it only on the workspace-files tab."
  [this props]
  {:query [:status :path :parent :workspace :entries :total-entries
           :truncated? :error :request-id :selected-path :preview
           :preview-status :preview-error :preview-request-id :baseline
           :diff :search-query :search-mode :search]
   :componentDidMount (fn [this]
                        (ensure-loaded! this))}
  (ui/file-browser-element props (callbacks this)))

(defonce FilesPaneFactory
  (comp/factory FilesPane))

(defn files-pane
  "Host-facing renderer.  Call inside a Fulcro parent as
  `(files-pane this (:ui/files props))`."
  [_this props]
  (FilesPaneFactory (or props (model/initial))))

(defn initial-state
  "Initial `:ui/files` value for the workbench database."
  []
  (model/initial))
