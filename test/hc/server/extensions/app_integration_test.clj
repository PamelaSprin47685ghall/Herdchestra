(ns hc.server.extensions.app-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hc.server.extensions :as extensions]
            [hc.server.extensions.app :as ext-app]
            [hc.server.extensions.durable :as ext-durable]
            [hc.server.extensions.mcp.connection :as connection]
            [hc.server.extensions.support :as support])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(def ^:private stub-dir (atom nil))

(defn- once-fixture
  [f]
  (let [dir (support/temp-dir)]
    (try
      (support/compile-stub! dir)
      (reset! stub-dir dir)
      (f)
      (finally
        (support/delete-tree dir)
        (reset! stub-dir nil)))))

(use-fixtures :once once-fixture)

(deftest app-component-executes-a-real-tool-call
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          command (support/stub-command @stub-dir)
          added (ext-app/handle! component
                                 {:kind :extensions/mcp/add
                                  :server {:name "stub"
                                           :type :stdio
                                           :command (first command)
                                           :args (vec (rest command))}})
          id (get-in added [:server :id])
          _ (ext-app/enable-mcp-server! component id true)
          opened (ext-app/open-mcp! component id)
          _ (ext-app/handle! component
                             {:kind :extensions/mcp/tool-enable
                              :id id :tool "echo" :enabled? true
                              :opts {:approve-review? true}})
          result (ext-app/handle! component
                                  {:kind :extensions/mcp/call
                                   :id id
                                   :tool "echo"
                                   :arguments {"text" "hello"}
                                   :scope {:role :viewer
                                           :assistant-servers #{id}}})
          _ (ext-app/handle! component
                             {:kind :extensions/mcp/tool-enable
                              :id id :tool "write_file" :enabled? true
                              :opts {:approve-review? true}})
          viewer-write (ext-app/handle! component
                                        {:kind :extensions/mcp/call
                                         :id id :tool "write_file"
                                         :arguments {"path" "x"}
                                         :scope {:role :viewer
                                                 :assistant-servers #{id}}})
          spoofed-write (ext-app/handle! component
                                         {:kind :extensions/mcp/call
                                          :id id :tool "write_file"
                                          :arguments {"path" "x"}
                                          :scope {:role :admin
                                                  :assistant-servers #{id}}})
          write-result (ext-app/handle! component
                                        {:kind :extensions/mcp/call
                                         :id id :tool "write_file"
                                         :arguments {"path" "x"}
                                         :scope {:role :admin
                                                 :assistant-servers #{id}}}
                                       {:scope {:role :admin
                                                :assistant-servers #{id}}})
          limited-write (ext-app/handle! component
                                         {:kind :extensions/mcp/call
                                          :id id :tool "write_file"
                                          :arguments {"path" "x"}}
                                         {:scope {:role :admin
                                                  :assistant-servers #{id}}
                                          :capability-scope #{:mcp/call}})]
      (is (:ok? added))
      (is (= :accepted (get-in added [:receipt :status])))
      (is (:ok? opened))
      (is (:ok? result))
      (is (= :completed (:outcome result)))
      (is (= "ok" (get-in result [:result "content" 0 "text"])))
      (is (= :MCP_CAPABILITY_REQUIRED (get-in viewer-write [:error :code])))
      (is (= :MCP_CAPABILITY_REQUIRED (get-in spoofed-write [:error :code])))
      (is (= :MCP_CAPABILITY_REQUIRED (get-in limited-write [:error :code])))
      (is (some #(= (str id "/echo") (:name %))
                (extensions/mcp-tool-definitions
                 (ext-app/catalog component)
                 {:assistant-servers #{id}})))
      (is (:ok? write-result))
      (is (= :completed (:outcome write-result)))
      (ext-app/stop! component))))

(deftest app-start-resets-stale-connected-state
  (support/with-temp-dir [data-dir]
    (let [first-component (ext-app/start! {:data-dir data-dir})
          command (support/stub-command @stub-dir)
          added (ext-app/handle! first-component
                                 {:kind :extensions/mcp/add
                                  :server {:name "stub"
                                           :type :stdio
                                           :command (first command)
                                           :args (vec (rest command))}})
          id (get-in added [:server :id])]
      (ext-app/enable-mcp-server! first-component id true)
      (ext-app/open-mcp! first-component id)
      (is (connection/live? (:manager first-component) id))
      (let [second (ext-app/start! {:data-dir data-dir})
            server (get-in (ext-app/catalog second) [:mcp :servers id])]
        (is (= :unconnected (get-in server [:connection :status])))
        (ext-app/stop! second))
      (ext-app/stop! first-component))))

(deftest app-update-closes-the-old-live-connection
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          command (support/stub-command @stub-dir)
          added (ext-app/handle! component
                                 {:kind :extensions/mcp/add
                                  :server {:name "stub"
                                           :type :stdio
                                           :command (first command)
                                           :args (vec (rest command))}})
          id (get-in added [:server :id])]
      (ext-app/enable-mcp-server! component id true)
      (ext-app/open-mcp! component id)
      (is (connection/live? (:manager component) id))
      (is (:ok? (ext-app/update-mcp-server! component id
                                            {:name "stub"
                                             :type :stdio
                                             :command (first command)
                                             :args (vec (rest command))})))
      (is (false? (connection/live? (:manager component) id)))
      (ext-app/stop! component))))

(deftest app-persists-a-failed-connection-observation
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          added (ext-app/handle! component
                                 {:kind :extensions/mcp/add
                                  :server {:name "missing"
                                           :type :stdio
                                           :command "/definitely/not/a/program"}})
          id (get-in added [:server :id])
          _ (ext-app/enable-mcp-server! component id true)
          probed (ext-app/probe-mcp! component id)
          server (get-in (ext-app/catalog component) [:mcp :servers id])]
      (is (false? (:ok? probed)))
      (is (= :connection-failed (get-in server [:connection :status])))
      (is (= :connection-failed (get-in (ext-app/view component)
                                        [:mcp 0 :state])))
      (ext-app/stop! component))))

(deftest app-resolves-scoped-mcp-tools-and-skills
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          command (support/stub-command @stub-dir)
          added (ext-app/handle! component
                                 {:kind :extensions/mcp/add
                                  :server {:name "stub"
                                           :type :stdio
                                           :command (first command)
                                           :args (vec (rest command))}})
          id (get-in added [:server :id])]
      (ext-app/enable-mcp-server! component id true)
      (ext-app/open-mcp! component id)
      (ext-app/handle! component {:kind :extensions/mcp/tool-enable
                                  :id id :tool "echo" :enabled? true
                                  :opts {:approve-review? true}})
      (let [tools (ext-app/handle! component
                                   {:kind :extensions/mcp/tools
                                    :scope {:role :viewer
                                            :assistant-servers #{id}}})
            installed (ext-app/handle! component
                                       {:kind :extensions/skill/install
                                        :source {:kind :text
                                                 :text "---\nname: app-skill\n---\n# App"}})
            skill-id (get-in installed [:skill :id])
            _ (ext-app/enable-skill! component skill-id true)
            resolved (ext-app/handle! component
                                      {:kind :extensions/skills/resolve
                                       :scope {:assistant-id "a1"
                                               :session {:mode :custom
                                                         :skills #{skill-id}}
                                               :available {}}})]
        (is (= ["echo"] (mapv :name (:tools tools))))
        (is (= [skill-id] (mapv :id (:active resolved)))))
      (ext-app/stop! component))))

(deftest app-compares-and-applies-skill-updates
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          v1 {:kind :text
              :text "---\nname: demo\n---\n# Demo\nversion one"}
          installed (ext-app/install-skill! component v1)
          id (get-in installed [:skill :id])
          root (get-in installed [:skill :root])
          _ (spit (str root "/SKILL.md")
                  "---\nname: demo\n---\n# Demo\nlocal edit")
          v2 {:kind :text
              :text "---\nname: demo\n---\n# Demo\nversion two"}
          compared (ext-app/compare-skill-update! component id v2)
          kept (ext-app/update-skill-from-source! component id v2
                                                   {:strategy :keep-local})]
      (try
        (is (:ok? compared))
        (is (= :conflict (get-in compared [:diff :paths 0 :status])))
        (is (:ok? kept))
        (is (re-find #"local edit" (slurp (str root "/SKILL.md"))))
        (let [compared-again (ext-app/compare-skill-update! component id v2)
              taken (ext-app/update-skill-from-source! component id v2
                                                        {:strategy :take-upstream})]
          (is (= :upstream-changed
                 (get-in compared-again [:diff :paths 0 :status])))
          (is (:ok? taken))
          (is (re-find #"version two" (slurp (str root "/SKILL.md")))))
        (finally
          (ext-app/stop! component))))))

(deftest app-import-preview-hides-secrets-and-imports-disabled
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          text "{\"mcpServers\":{\"remote\":{\"type\":\"http\",\"url\":\"https://example.test/mcp\",\"headers\":{\"Authorization\":\"Bearer secret\"}}}}"
          preview (ext-app/preview-mcp-import! component text)
          candidate (first (:candidates preview))
          imported (ext-app/import-mcp-text! component text)
          server (first (vals (get-in (ext-app/catalog component) [:mcp :servers])))]
      (try
        (is (:ok? preview))
        (is (= "remote" (:name candidate)))
        (is (true? (:has-secrets? candidate)))
        (is (not (contains? candidate :server-config)))
        (is (not (re-find #"Bearer secret" (pr-str preview))))
        (is (:ok? imported))
        (is (= 1 (count (:created imported))))
        (is (false? (:enabled? server)))
        (is (= :unconnected (get-in server [:connection :status])))
        (finally
          (ext-app/stop! component))))))

(deftest app-installs-skill-from-archive-and-url
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          archive (support/zip-bytes
                   [["SKILL.md" "---\nname: archive-demo\n---\n# Archive"]])
          encoded (.encodeToString (Base64/getEncoder) archive)
          installed (ext-app/install-skill! component
                                            {:kind :archive-base64
                                             :base64 encoded
                                             :filename "skill.zip"})
          server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
          _ (.createContext server "/skill.zip"
                            (reify HttpHandler
                              (handle [_ exchange]
                                (.set (.getResponseHeaders exchange)
                                      "Content-Type" "application/zip")
                                (.sendResponseHeaders exchange 200 (alength archive))
                                (with-open [out (.getResponseBody exchange)]
                                  (.write out archive)))))
          _ (.createContext server "/redirect"
                            (reify HttpHandler
                              (handle [_ exchange]
                                (.set (.getResponseHeaders exchange)
                                      "Location" "/skill.zip")
                                (.sendResponseHeaders exchange 302 -1)
                                (.close exchange))))
          _ (.start server)
          port (.getPort (.getAddress server))
          url-installed (ext-app/install-skill! component
                                                {:kind :url
                                                 :allow-private? true
                                                 :url (str "http://127.0.0.1:" port "/skill.zip")})]
      (try
        (is (:ok? installed))
        (is (false? (get-in installed [:skill :enabled?])))
        (is (= #{} (get-in installed [:skill :bindings])))
        (is (= "archive-demo" (get-in installed [:skill :name])))
        (is (:ok? url-installed) (pr-str url-installed))
        (is (false? (get-in url-installed [:skill :enabled?])))
        (let [blocked (ext-app/install-skill! component
                                              {:kind :url
                                               :url (str "http://127.0.0.1:" port "/skill.zip")})]
          (is (false? (:ok? blocked)))
          (is (= :SKILL_SOURCE_PRIVATE_ADDRESS
                 (get-in blocked [:error :code]))))
        (let [redirected (ext-app/install-skill! component
                                                  {:kind :url
                                                   :allow-private? true
                                                   :url (str "http://127.0.0.1:" port "/redirect")})]
          (is (false? (:ok? redirected)))
          (is (= :SKILL_SOURCE_REDIRECT_REFUSED
                 (get-in redirected [:error :code]))))
        (let [browser-attempt
              (ext-app/handle! component
                               {:kind :extensions/skill/install
                                :source {:kind :url
                                         :allow-private? true
                                         :url (str "http://127.0.0.1:" port "/skill.zip")}})]
          (is (false? (:ok? browser-attempt)))
          (is (= :SKILL_SOURCE_PRIVATE_ADDRESS
                 (get-in browser-attempt [:error :code]))))
        (finally
          (.stop server 0)
          (ext-app/stop! component))))))

(deftest app-preview-copy-file-and-source-policy
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          text "---\nname: preview\n---\n# Preview\nbody"
          preview (ext-app/handle! component
                                   {:kind :extensions/skill/preview
                                    :source {:kind :text :text text}})
          installed (ext-app/handle! component
                                     {:kind :extensions/skill/install
                                      :source {:kind :text :text text}})
          id (get-in installed [:skill :id])
          file (ext-app/handle! component
                                {:kind :extensions/skill/file
                                 :id id :path "SKILL.md"})
          copied (ext-app/handle! component
                                  {:kind :extensions/skill/copy
                                   :id id :opts {:name "copied"}})
          directory (support/temp-dir)
          _ (spit (str directory "/SKILL.md")
                  "---\nname: directory\n---\n# Directory")
          directory-preview (ext-app/handle!
                             component
                             {:kind :extensions/skill/preview
                              :source {:kind :directory :directory directory}})
          dotted (ext-app/handle! component
                                  {:kind :extensions/skill/install
                                   :source {:kind :text :text text}
                                   :opts {:id "."}})
          view (ext-app/handle! component {:kind :extensions/view})]
      (try
        (is (:ok? preview))
        (is (re-find #"# Preview" (get-in preview [:candidate :entry-text])))
        (is (:ok? installed))
        (is (:ok? file))
        (is (re-find #"# Preview" (:text file)))
        (is (:ok? copied))
        (is (= "copied" (get-in copied [:skill :name])))
        (ext-app/enable-skill! component id true)
        (let [context (extensions/active-skill-context
                       (ext-app/catalog component)
                       {:assistant-id "a1"
                        :session {:mode :custom :skills #{id}}})]
          (is (= 1 (count context)))
          (is (re-find #"# Preview" (:text (first context)))))
        (is (false? (:ok? directory-preview)))
        (is (= :SKILL_SOURCE_INVALID (get-in directory-preview [:error :code])))
        (is (false? (:ok? dotted)))
        (is (= :SKILL_ID_INVALID (get-in dotted [:error :code])))
        (is (some #(= id (:id %)) (get-in view [:skills])))
        (let [scoped (ext-app/view component
                                   {:assistant-id "a1"
                                    :session {:mode :custom :skills #{id}}})]
          (is (= :custom (get-in scoped [:session-scope :mode])))
          (is (= [id] (mapv :id (get-in scoped [:resolved-skills :active])))))
        (finally
          (ext-app/stop! component))))))

(deftest app-skill-update-removes-upstream-deleted-local-changed
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          v1 {:kind :files
              :files {"SKILL.md" "---\nname: update\n---\n# Update"
                      "extra.txt" "base"}}
          v2 {:kind :files
              :files {"SKILL.md" "---\nname: update\n---\n# Update"}}
          installed (ext-app/install-skill! component v1)
          id (get-in installed [:skill :id])
          root (get-in installed [:skill :root])
          _ (spit (str root "/extra.txt") "local edit")
          taken (ext-app/update-skill-from-source! component id v2
                                                   {:strategy :take-upstream})
          invalid (ext-app/apply-skill-update! component id
                                               {:files {"extra.txt" "x"}})]
      (try
        (is (:ok? taken) (pr-str taken))
        (is (not (.exists (java.io.File. (str root "/extra.txt")))))
        (is (.exists (java.io.File. (str root "/SKILL.md"))))
        (is (false? (:ok? invalid)))
        (is (= :SKILL_ENTRY_MISSING (get-in invalid [:error :code])))
        (is (.exists (java.io.File. (str root "/SKILL.md"))))
        (finally
          (ext-app/stop! component))))))

(deftest app-catalog-conflict-reports-applied-files
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          result (with-redefs [ext-durable/save!
                               (fn [_ _ _]
                                 {:ok? false
                                  :error {:code :EXTENSION_CATALOG_CONFLICT}})]
                   (ext-app/install-skill!
                    component
                    {:kind :text
                     :text "---\nname: conflict\n---\n# Conflict"}))]
      (try
        (is (false? (:ok? result)))
        (is (= :EXTENSION_CATALOG_CONFLICT (get-in result [:error :code])))
        (is (:filesystem-applied? result))
        (is (string? (get-in result [:skill :root])))
        (is (.exists (java.io.File.
                      (str (get-in result [:skill :root]) "/SKILL.md"))))
        (finally
          (ext-app/stop! component))))))

(deftest app-mcp-import-selection-and-login-commands
  (support/with-temp-dir [data-dir]
    (let [component (ext-app/start! {:data-dir data-dir})
          text "{:mcpServers {:one {:command \"one\"} :two {:command \"two\"}}}"
          imported (ext-app/import-mcp-text! component text {:selected ["one"]})
          servers (vals (get-in (:catalog imported) [:mcp :servers]))
          added (ext-app/handle! component
                                 {:kind :extensions/mcp/add
                                  :server {:name "login-test"
                                           :command "true"}})
          id (get-in added [:server :id])
          login (ext-app/handle! component
                                 {:kind :extensions/mcp/login :id id})
          logout (ext-app/handle! component
                                  {:kind :extensions/mcp/logout :id id})]
      (try
        (is (:ok? imported))
        (is (= 1 (count servers)))
        (is (= "one" (:name (first servers))))
        (is (= :pending-login (get-in login [:server :auth :state])))
        (is (= :none (get-in logout [:server :auth :state])))
        (finally
          (ext-app/stop! component))))))
