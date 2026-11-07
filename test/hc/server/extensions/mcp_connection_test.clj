(ns hc.server.extensions.mcp-connection-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.server.extensions :as ext]
            [hc.server.extensions.mcp.connection :as conn]
            [hc.server.extensions.support :as support]))

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

(defn- setup!
  [& extra-args]
  (let [command (apply support/stub-command @stub-dir extra-args)
        normalized (ext/normalize-mcp-server {:name "stub"
                                              :type :stdio
                                              :command (first command)
                                              :args (vec (rest command))})
        added (ext/add-mcp-server! (ext/empty-catalog) (:server-config normalized))
        id (get-in added [:server :id])
        manager (conn/manager)
        opened (conn/open! manager (:catalog added) id {:timeout-ms 4000})
        catalog (:catalog opened)
        enabled (ext/enable-mcp-server! catalog id true)
        catalog (:catalog enabled)]
    {:manager manager
     :catalog (reduce (fn [cat tool]
                        (:catalog (ext/set-mcp-tool-enabled! cat id tool true
                                                             {:approve-review? true})))
                      catalog
                      ["echo" "write_file" "fail_tool"])
     :id id
     :opened opened}))

(defn- close-setup!
  [{:keys [manager catalog id]}]
  (when (and manager id)
    (conn/close! manager catalog id)))

(defmacro ^:private with-setup
  "Run body with the context from `setup!`, closing its connection afterwards."
  [[binding & args] & body]
  `(let [setup# (setup! ~@args)]
     (try
       (let [~binding setup#]
         ~@body)
       (finally
         (close-setup! setup#)))))

(defn- scope
  [id role]
  {:role role :assistant-servers #{id}})

(deftest managed-call-executes-a-read-only-tool
  (with-setup [{:keys [manager catalog id]}]
    (let [result (conn/call! manager catalog (scope id :viewer) id "echo"
                             {"text" "hello"})]
      (is (:ok? result))
      (is (= :completed (:outcome result)))
      (is (= "ok" (get-in result [:result "content" 0 "text"]))))))

(deftest role-gates-write-tool-and-authorized-write-proceeds
  (with-setup [{:keys [manager catalog id]}]
    (let [viewer-write (conn/call! manager catalog (scope id :viewer) id "write_file"
                                   {"path" "x"})
          operator-write (conn/call! manager catalog (scope id :operator) id "write_file"
                                     {"path" "x"})
          admin-write (conn/call! manager catalog (scope id :admin) id "write_file"
                                  {"path" "x"})]
      (is (= :MCP_CAPABILITY_REQUIRED (get-in viewer-write [:error :code])))
      (is (= :MCP_CAPABILITY_REQUIRED (get-in operator-write [:error :code])))
      (is (:ok? admin-write))
      (is (= :completed (:outcome admin-write))))))

(deftest disabled-tool-is-refused
  (with-setup [{:keys [manager catalog id]}]
    (let [disabled (:catalog (ext/set-mcp-tool-enabled! catalog id "echo" false))
          result (conn/call! manager disabled (scope id :viewer) id "echo" {})]
      (is (= :MCP_TOOL_DISABLED (get-in result [:error :code]))))))

(deftest tool-error-is-not-success
  (with-setup [{:keys [manager catalog id]}]
    (let [result (conn/call! manager catalog (scope id :viewer) id "fail_tool" {})]
      (is (false? (:ok? result)))
      (is (= :tool-error (:outcome result)))
      (is (true? (get-in result [:result "isError"]))))))

(deftest cancellation-of-a-sent-write-is-unknown
  (with-setup [{:keys [manager catalog id]} "--slow-call"]
    (let [started (conn/start-call! manager catalog (scope id :admin) id
                                    "write_file" {"path" "x"}
                                    {:timeout-ms 10000})]
      (is (:ok? started))
      (Thread/sleep 200)
      (let [cancelled (conn/cancel! manager (:catalog started)
                                    (:handle started) :operator-stop)
            result (conn/await! (:handle started) 5000)]
        (is (:ok? cancelled))
        (is (true? (:connection-closed? cancelled)))
        (is (= :unknown (:outcome cancelled)))
        (is (false? (:ok? result)))
        (is (= :unknown (:outcome result)))))))
