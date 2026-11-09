(ns hc.server.extensions.catalog
  "The extension catalog is a plain value owned by the session server.

  It contains two independent capability families.  Neither family grants
  another family's permission, and every newly created entry starts disabled.
  The catalog is deliberately not a mutable singleton: a caller applies one
  returned value only after the surrounding domain transaction commits."
  (:refer-clojure :exclude [ensure])
  (:require [hc.server.extensions.common :as common]))

(def catalog-version 1)

(defn empty-catalog
  []
  {:version catalog-version
   :skills {}
   :mcp {:servers {}}})

(defn ensure
  "Accept a catalog value and return a complete shape without changing it."
  [catalog]
  (cond
    (nil? catalog) (empty-catalog)
    (not (map? catalog))
    (throw (ex-info "extension catalog must be a map"
                    {:error :EXTENSION_CATALOG_INVALID
                     :class (some-> catalog class .getName)}))
    :else
    (-> catalog
        (update :version #(or % catalog-version))
        (update :skills #(or % {}))
        (update :mcp #(or % {:servers {}}))
        (update-in [:mcp :servers] #(or % {})))))

(defn skills
  [catalog]
  (:skills (ensure catalog)))

(defn servers
  [catalog]
  (get-in (ensure catalog) [:mcp :servers]))

(defn skill
  [catalog id]
  (get (skills catalog) id))

(defn server
  [catalog id]
  (get (servers catalog) id))

(defn put-skill
  [catalog skill]
  (assoc-in (ensure catalog) [:skills (:id skill)] skill))

(defn put-server
  [catalog server]
  (assoc-in (ensure catalog) [:mcp :servers (:id server)] server))

(defn remove-skill
  [catalog id]
  (update (ensure catalog) :skills dissoc id))

(defn remove-server
  [catalog id]
  (update-in (ensure catalog) [:mcp :servers] dissoc id))

(defn skill-names
  [catalog]
  (map :name (vals (skills catalog))))

(defn server-names
  [catalog]
  (map :name (vals (servers catalog))))

(defn skill-by-name
  [catalog name]
  (some #(when (= name (:name %)) %) (vals (skills catalog))))

(defn server-by-name
  [catalog name]
  (some #(when (= name (:name %)) %) (vals (servers catalog))))

(defn unique-skill-name
  [catalog base]
  (common/unique-name (skill-names catalog) base))

(defn unique-server-name
  [catalog base]
  (common/unique-name (server-names catalog) base))

(defn summary
  "Small public overview for a tool panel or settings page."
  [catalog]
  {:version (:version (ensure catalog))
   :skills {:total (count (skills catalog))
            :enabled (count (filter :enabled? (vals (skills catalog))))
            :bound (count (filter #(seq (:bindings %)) (vals (skills catalog))))}
   :mcp {:total (count (servers catalog))
         :enabled (count (filter :enabled? (vals (servers catalog))))
         :connected (count (filter #(= :connected (get-in % [:connection :status]))
                                   (vals (servers catalog))))}})
