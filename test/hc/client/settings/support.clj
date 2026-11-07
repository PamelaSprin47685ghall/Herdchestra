(ns hc.client.settings.support
  (:require [clojure.string :as str]
            [hc.client.settings.model :as model]))

(defn connection
  [id name base-url & [opts]]
  (merge {:connection-id id
          :name name
          :base-url base-url
          :service-kind :cloud-key
          :protocol :openai-compatible
          :status :connected
          :test-mode :minimal-request
          :auth-keys [{:key-id (str id "-key")
                       :alias "主密钥"
                       :enabled? true
                       :priority 1
                       :has-key? true
                       :auth-revision (str id "-rev")
                       :status :ready}]
          :enabled-models #{}}
         opts))

(defn model
  [id display-name & [opts]]
  (merge {:model-id id
          :display-name display-name
          :capabilities {:vision :unknown
                         :audio :unknown
                         :video :unknown
                         :document :unknown
                         :tools :unknown
                         :reasoning :unknown}
          :availability :available
          :pricing :unknown
          :context-window :unknown}
         opts))

(defn state
  [& [opts]]
  (model/initial-state
   (merge {:sync {:phase :synced :command-epoch 7}
           :transport-available? true}
          opts)))

(defn apply-action
  [state action]
  (:state (model/reduce-action state action)))

(defn open-with
  [state connection-id]
  (-> state
      (apply-action {:type :settings/open})
      (apply-action {:type :settings/select-connection
                     :connection-id connection-id})))

(defn tree-text
  "Return all text in a view tree, separated by spaces."
  [node]
  (cond
    (nil? node) ""
    (string? node) node
    (number? node) (str node)
    (and (map? node) (:tag node))
    (apply str (map tree-text (:children node)))
    (sequential? node) (apply str (map tree-text node))
    :else ""))

(defn nodes
  "Return every map node with :tag in TREE."
  [tree]
  (letfn [(walk [node]
            (cond
              (and (map? node) (:tag node))
              (cons node (mapcat walk (:children node)))
              (sequential? node) (mapcat walk node)
              :else nil))]
    (walk tree)))

(defn nodes-with-class
  [tree class]
  (filter (fn [node]
            (some-> (get-in node [:attrs :class])
                    (str/includes? class)))
          (nodes tree)))

(defn buttons
  [tree]
  (filter #(= :button (:tag %)) (nodes tree)))
