(ns hc.server.extensions.mcp-state-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.extensions.catalog :as catalog]
            [hc.server.extensions.mcp.config :as config]
            [hc.server.extensions.mcp.state :as state]))

(defn- add! [catalog & {:keys [name command url type]}]
  (let [raw (cond-> {:name (or name "server")}
              command (assoc :command command)
              url (assoc :url url)
              type (assoc :type type))
        normalized (config/normalize raw)]
    (state/add-server! catalog (:server-config normalized))))

(defn- connected!
  [catalog id tools]
  (state/apply-probe-result! catalog id
                             {:status :connected
                              :tools tools
                              :server-info {"name" "stub"}
                              :protocol-version "2024-11-05"
                              :duration-ms 12}))

(deftest connection-does-not-enable-tools
  (let [added (add! (catalog/empty-catalog) :command "stub")
        id (get-in added [:server :id])
        probed (connected! (:catalog added) id
                           [{"name" "read" "description" "Read"
                             "inputSchema" {"type" "object"}}])
        server (get-in probed [:catalog :mcp :servers id])
        tool (get-in server [:tools "read"])]
    (is (false? (:enabled? server)))
    (is (= :connected-not-enabled (state/server-state server)))
    (is (false? (:enabled? tool)))
    (is (true? (:review-required? tool)))
    (is (true? (:available? tool)))))

(deftest tool-enable-requires-review-and-availability
  (let [added (add! (catalog/empty-catalog) :command "stub")
        id (get-in added [:server :id])
        probed (connected! (:catalog added) id
                           [{"name" "write" "description" "Write"
                             "inputSchema" {"type" "object"}}])
        enabled (state/set-server-enabled! (:catalog probed) id true)
        refused (state/set-tool-enabled! (:catalog enabled) id "write" true)
        approved (state/set-tool-enabled! (:catalog enabled) id "write" true
                                          {:approve-review? true})
        server (get-in approved [:catalog :mcp :servers id])]
    (is (= :MCP_TOOL_REVIEW_REQUIRED (get-in refused [:error :code])))
    (is (:ok? approved))
    (is (= :available (state/server-state server)))
    (is (state/tool-callable? (:catalog approved)
                              {:assistant-servers #{id}}
                              id "write"))
    (testing "a removed tool is not callable even if it was enabled"
      (let [removed (connected! (:catalog approved) id [])
            server (get-in removed [:catalog :mcp :servers id])]
        (is (false? (get-in server [:tools "write" :available?])))
        (is (not (state/tool-callable? (:catalog removed)
                                       {:assistant-servers #{id}}
                                       id "write")))))))

(deftest refresh-preserves-selection-and-disables-new-tools
  (let [added (add! (catalog/empty-catalog) :command "stub")
        id (get-in added [:server :id])
        first-probe (connected! (:catalog added) id
                                [{"name" "read" "description" "Read v1"
                                  "inputSchema" {"type" "object"}}])
        enabled (state/set-server-enabled! (:catalog first-probe) id true)
        read-enabled (state/set-tool-enabled! (:catalog enabled) id "read" true
                                              {:approve-review? true})
        refreshed (connected! (:catalog read-enabled) id
                              [{"name" "read" "description" "Read v2"
                                "inputSchema" {"type" "object"}}
                               {"name" "new-write" "description" "New write"
                                "inputSchema" {"type" "object"}}])
        server (get-in refreshed [:catalog :mcp :servers id])]
    (is (true? (get-in server [:tools "read" :enabled?])))
    (is (true? (get-in server [:tools "read" :review-required?])))
    (is (true? (get-in server [:tools "read" :description-changed?])))
    (is (false? (get-in server [:tools "new-write" :enabled?])))
    (is (true? (get-in server [:tools "new-write" :review-required?])))
    (is (= ["read"]
           (mapv :name (:tools (state/resolve-tools (:catalog refreshed)
                                                    {:assistant-servers #{id}})))))))

(deftest session-scope-narrows-but-cannot-override-global-disable
  (let [a (add! (catalog/empty-catalog) :name "a" :command "a")
        aid (get-in a [:server :id])
        b (add! (:catalog a) :name "b" :command "b")
        bid (get-in b [:server :id])
        with-tools (-> (:catalog b)
                       (connected! aid [{"name" "t" "description" "T"
                                         "inputSchema" {"type" "object"}}])
                       :catalog
                       (connected! bid [{"name" "u" "description" "U"
                                         "inputSchema" {"type" "object"}}])
                       :catalog)
        enabled-a (state/set-server-enabled! with-tools aid true)
        enabled-b (state/set-server-enabled! (:catalog enabled-a) bid true)
        tool-a (state/set-tool-enabled! (:catalog enabled-b) aid "t" true
                                        {:approve-review? true})
        tool-b (state/set-tool-enabled! (:catalog tool-a) bid "u" true
                                        {:approve-review? true})]
    (testing "custom session replaces the assistant list"
      (is (= ["t"]
             (mapv :name (:tools (state/resolve-tools (:catalog tool-b)
                                                      {:assistant-servers #{bid}
                                                       :session {:mode :custom
                                                                 :servers #{aid}}}))))))
    (testing "global disable wins over a custom session"
      (let [disabled (state/set-server-enabled! (:catalog tool-b) aid false)
            result (state/resolve-tools (:catalog disabled)
                                        {:assistant-servers #{bid}
                                         :session {:mode :custom :servers #{aid}}})]
        (is (= [] (:tools result)))
        (is (= :globally-disabled
               (:reason (first (filter #(= aid (:server-id %)) (:excluded result))))))))))

(deftest connection-failure-and-login-states
  (let [added (add! (catalog/empty-catalog) :command "stub")
        id (get-in added [:server :id])
        failed (state/apply-probe-result! (:catalog added) id
                                          {:status :failed
                                           :stage :initialize
                                           :error {:code :MCP_TIMEOUT
                                                   :message "timeout"}})
        login (state/apply-probe-result! (:catalog failed) id
                                         {:status :needs-login
                                          :error {:code :MCP_AUTH_REQUIRED
                                                  :message "login"}})]
    (is (= :connection-failed
           (state/server-state (get-in failed [:catalog :mcp :servers id]))))
    (is (= :needs-login
           (state/server-state (get-in login [:catalog :mcp :servers id]))))))

(deftest oauth-callback-is-bound-to-one-attempt
  (let [added (add! (catalog/empty-catalog) :command "stub")
        id (get-in added [:server :id])
        begun (state/begin-login! (:catalog added) id)
        token (:token begun)
        other (add! (:catalog begun) :name "other" :command "other")
        other-id (get-in other [:server :id])
        other-begun (state/begin-login! (:catalog other) other-id)
        wrong-server (state/complete-login! (:catalog other-begun) other-id token "acct")
        wrong-token (state/complete-login! (:catalog begun) id "old-token" "acct")
        completed (state/complete-login! (:catalog begun) id token "acct")]
    (is (= :MCP_OAUTH_CALLBACK_MISMATCH (get-in wrong-server [:error :code])))
    (is (= :MCP_OAUTH_CALLBACK_MISMATCH (get-in wrong-token [:error :code])))
    (is (:ok? completed))
    (is (= :authenticated (get-in completed [:server :auth :state])))))

(deftest target-change-clears-tool-selection
  (let [added (add! (catalog/empty-catalog) :name "x" :command "old")
        id (get-in added [:server :id])
        probed (connected! (:catalog added) id
                           [{"name" "t" "description" "T"
                             "inputSchema" {"type" "object"}}])
        updated (state/update-server! (:catalog probed) id
                                      {:name "x"
                                       :transport {:type :stdio
                                                   :command "new"
                                                   :args []
                                                   :env {}}}
                                      {})]
    (is (:ok? updated))
    (is (true? (get-in updated [:diff :identity-changed?])))
    (is (= {} (get-in updated [:server :tools])))
    (is (= :unconnected (get-in updated [:server :connection :status])))))

(deftest credential-change-keeps-tool-selection
  (let [added (add! (catalog/empty-catalog) :name "x" :url "https://example.test/mcp")
        id (get-in added [:server :id])
        probed (connected! (:catalog added) id
                           [{"name" "t" "description" "T"
                             "inputSchema" {"type" "object"}}])
        updated (state/update-server! (:catalog probed) id
                                      {:name "x"
                                       :transport {:type :http
                                                   :url "https://example.test/mcp"
                                                   :headers {"Authorization" "new"}}}
                                      {})]
    (is (:ok? updated))
    (is (false? (get-in updated [:diff :identity-changed?])))
    (is (true? (get-in updated [:diff :credentials-changed?])))
    (is (contains? (get-in updated [:server :tools]) "t"))
    (is (= :unconnected (get-in updated [:server :connection :status])))))

(deftest logout-removes-secret-credentials-only
  (let [added (add! (catalog/empty-catalog)
                    :name "x"
                    :url "https://example.test/mcp")
        id (get-in added [:server :id])
        with-headers (state/update-server! (:catalog added) id
                                           {:name "x"
                                            :transport {:type :http
                                                        :url "https://example.test/mcp"
                                                        :headers {"Authorization" "secret"
                                                                  "X-Plain" "visible"}}}
                                           {})
        logged-out (state/logout! (:catalog with-headers) id)
        headers (get-in logged-out [:server :transport :headers])]
    (is (not (contains? headers "Authorization")))
    (is (= "visible" (get headers "X-Plain")))
    (is (= :unconnected (get-in logged-out [:server :connection :status])))))

(deftest disconnect-keeps-tool-selection
  (let [added (add! (catalog/empty-catalog) :command "stub")
        id (get-in added [:server :id])
        probed (connected! (:catalog added) id
                           [{"name" "t" "description" "T"
                             "inputSchema" {"type" "object"}}])
        disconnected (state/disconnect! (:catalog probed) id)]
    (is (= :unconnected (get-in disconnected [:server :connection :status])))
    (is (contains? (get-in disconnected [:server :tools]) "t"))))
