(ns hc.server.extensions.mcp-import-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.mcp.import :as mcp-import]
            [hc.server.extensions.mcp.state :as mcp-state]
            [hc.server.extensions.support :as support]))

(deftest previews-json-and-edn
  (let [json (mcp-import/preview
              "{\"mcpServers\":{\"fs\":{\"command\":\"npx\",\"args\":[\"-y\",\"pkg\"],\"env\":{\"TOKEN\":\"x\"},\"extra\":1},\"web\":{\"type\":\"http\",\"url\":\"https://example.test/mcp\",\"headers\":{\"Authorization\":\"Bearer y\"}}}}")
        edn (mcp-import/preview
             "{:mcpServers {:local {:command \"python3\" :args [\"server.py\"]}}}")]
    (is (:ok? json))
    (is (= :json (:format json)))
    (is (= ["fs" "web"] (mapv :name (:candidates json))))
    (is (= [:extra] (:unknown-fields (first (:candidates json)))))
    (is (every? :has-secrets? (:candidates json)))
    (is (:ok? edn))
    (is (= :edn (:format edn)))
    (is (= "local" (:name (first (:candidates edn)))))))

(deftest one-bad-candidate-does-not-discard-the-rest
  (let [previewed (mcp-import/preview
                   "{\"mcpServers\":{\"good\":{\"command\":\"x\"},\"bad\":{\"url\":\"not a url\"}}}")
        imported (mcp-import/import! (catalog/empty-catalog) previewed)]
    (is (:ok? previewed))
    (is (= 1 (count (:candidates previewed))))
    (is (= 1 (count (:failed previewed))))
    (is (:ok? imported))
    (is (= ["good"] (mapv :name (:created imported))))
    (is (= 1 (count (:failed imported))))
    (is (:partial? imported))))

(deftest imported-servers-are-disabled
  (let [previewed (mcp-import/preview
                   "{\"mcpServers\":{\"fs\":{\"command\":\"npx\"}}}")
        imported (mcp-import/import! (catalog/empty-catalog) previewed)
        server (first (vals (get-in (:catalog imported) [:mcp :servers])))]
    (is (false? (:enabled? server)))
    (is (= :unconnected (get-in server [:connection :status])))
    (is (= {} (:tools server)))))

(deftest duplicate-name-policy-defaults-to-rename
  (let [first-preview (mcp-import/preview "{\"mcpServers\":{\"fs\":{\"command\":\"one\"}}}")
        first-import (mcp-import/import! (catalog/empty-catalog) first-preview)
        second-preview (mcp-import/preview "{\"mcpServers\":{\"fs\":{\"command\":\"two\"}}}")
        second-import (mcp-import/import! (:catalog first-import) second-preview)
        names (sort (map :name (vals (get-in (:catalog second-import) [:mcp :servers]))))]
    (is (= ["fs" "fs-2"] names))))

(deftest duplicate-name-can-be-skipped-or-updated
  (let [first-preview (mcp-import/preview "{\"mcpServers\":{\"fs\":{\"command\":\"one\"}}}")
        first-import (mcp-import/import! (catalog/empty-catalog) first-preview)
        second-preview (mcp-import/preview "{\"mcpServers\":{\"fs\":{\"command\":\"two\"}}}")
        skipped (mcp-import/import! (:catalog first-import) second-preview
                                    {:on-duplicate :skip})
        server-id (get-in (first (vals (get-in (:catalog first-import)
                                               [:mcp :servers]))) [:id])
        enabled (mcp-state/set-server-enabled! (:catalog first-import)
                                               server-id true)
        updated (mcp-import/import! (:catalog enabled) second-preview
                                    {:on-duplicate :update})
        skipped-server (first (vals (get-in (:catalog skipped) [:mcp :servers])))
        updated-server (first (vals (get-in (:catalog updated) [:mcp :servers])))]
    (is (= 1 (count (:skipped skipped))))
    (is (= "one" (get-in skipped-server [:transport :command])))
    (is (= 1 (count (:updated updated))))
    (is (= "two" (get-in updated-server [:transport :command])))
    (is (true? (:enabled? updated-server)))))

(deftest import-does-not-start-a-local-process
  (support/with-temp-dir [dir]
    (let [marker (str dir "/started")
          command (str "python3 -c \"open('" marker "','w').write('x')\"")
          previewed (mcp-import/preview
                     {:mcpServers {"danger" {:command command :args []}}})
          imported (mcp-import/import! (catalog/empty-catalog) previewed)]
      (is (:ok? imported))
      (Thread/sleep 100)
      (is (not (.exists (java.io.File. marker)))))))

(deftest top-level-server-vector-is-accepted
  (let [previewed (mcp-import/preview
                   "[{\"name\":\"one\",\"command\":\"x\"},{\"name\":\"two\",\"url\":\"https://example.test/mcp\"}]")]
    (is (:ok? previewed))
    (is (= ["one" "two"] (mapv :name (:candidates previewed))))))

(deftest malformed-import-is-refused
  (let [result (mcp-import/preview "{not json or edn")]
    (is (false? (:ok? result)))
    (is (= :MCP_IMPORT_PARSE (get-in result [:error :code])))))
