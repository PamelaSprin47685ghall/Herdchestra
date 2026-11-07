(ns hc.server.extensions.durable-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.extensions :as ext]
            [hc.server.extensions.app :as ext-app]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.durable :as durable]
            [hc.server.extensions.support :as support]))

(deftest catalog-round-trips-through-an-atomic-file
  (support/with-temp-dir [dir]
    (let [repository (durable/open dir)
          empty (durable/load! repository)
          added (durable/update! repository
                                 (fn [cat]
                                   {:ok? true
                                    :catalog (assoc-in cat [:skills "s1"]
                                                       {:id "s1" :name "demo"})}))
          reopened (durable/open dir)
          loaded (durable/load! reopened)]
      (is (:ok? empty))
      (is (= 0 (:version empty)))
      (is (:ok? added))
      (is (= 1 (:version added)))
      (is (= "demo" (get-in loaded [:catalog :skills "s1" :name])))
      (is (= 1 (:version loaded))))))

(deftest stale-writer-gets-a-conflict
  (support/with-temp-dir [dir]
    (let [repository (durable/open dir)
          first-save (durable/save! repository (catalog/empty-catalog) 0)
          stale (durable/save! repository (catalog/empty-catalog) 0)]
      (is (:ok? first-save))
      (is (false? (:ok? stale)))
      (is (= :EXTENSION_CATALOG_CONFLICT (get-in stale [:error :code]))))))

(deftest app-persists-skill-installation-and-default-disabled
  (support/with-temp-dir [dir]
    (let [system {:data-dir dir}
          component (ext-app/start! system)
          installed (ext-app/install-skill!
                     component
                     {:kind :text
                      :text "---\nname: durable-demo\n---\n# Durable"})
          stopped (ext-app/stop! component)
          reloaded (ext-app/start! system)
          skills (ext/list-skills (ext-app/catalog reloaded))]
      (is (:ok? installed))
      (is (:stopped? stopped))
      (is (= 1 (count skills)))
      (is (= "durable-demo" (:name (first skills))))
      (is (false? (:enabled? (first skills))))
      (ext-app/stop! reloaded))))

(deftest app-command-router-mounts-status-and-mcp-config
  (support/with-temp-dir [dir]
    (let [component (ext-app/start! {:data-dir dir})
          added (ext-app/handle! component
                                 {:kind :extensions/mcp/add
                                  :server {:name "local"
                                           :command "python3"}})
          status (ext-app/handle! component {:kind :extensions/status})
          view (ext-app/handle! component {:kind :extensions/view})
          server (first (get-in view [:mcp]))]
      (is (:ok? added))
      (is (= 1 (get-in status [:mcp :total])))
      (is (= "local" (:name server)))
      (is (false? (:enabled? server)))
      (ext-app/stop! component))))
