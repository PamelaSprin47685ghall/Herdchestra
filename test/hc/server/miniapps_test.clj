(ns hc.server.miniapps-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.miniapps :as miniapps]
            [hc.server.model-port.contract :as model-contract])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path Paths)
           (java.nio.file.attribute FileAttribute)))

(def ^:private html-doc
  (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
       "<title>Reading Log</title></head><body>"
       "<h1>读书条目</h1><ul id=\"list\"></ul>"
       "<script>localStorage.setItem('reading-log', '[]');</script>"
       "</body></html>"))

(defn- temp-root []
  (-> (Files/createTempDirectory "hc-miniapps-test"
                                 (make-array FileAttribute 0))
      (.toRealPath (make-array LinkOption 0))))

(defn- delete-tree! [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path child (reverse (vec (iterator-seq (.iterator stream))))]
        (Files/deleteIfExists child)))))

(defn- write-file! [^Path root relative content]
  (let [path (.resolve root relative)]
    (Files/createDirectories (.getParent path) (make-array FileAttribute 0))
    (Files/write path
                 (.getBytes ^String (str content) StandardCharsets/UTF_8)
                 (make-array java.nio.file.OpenOption 0))
    path))

(defn- read-file! [^Path root relative]
  (String. (Files/readAllBytes (.resolve root relative))
           StandardCharsets/UTF_8))

(defn- exists? [^Path root relative]
  (Files/exists (.resolve root relative)
                (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- svc
  ([root data-dir] (svc root data-dir {}))
  ([^Path root data-dir opts]
   (miniapps/service (merge {:root (str root)
                             :data-dir (str data-dir)
                             :timeout-ms 5000}
                            opts))))

(defn- generate!
  [service filename]
  ((:generate! service) {:prompt "生成一个记录读书条目的小应用"
                         :filename filename}))

(defn- http-request
  ([method uri] (http-request method uri nil))
  ([method uri body]
   {:request-method method
    :uri uri
    :headers {"content-type" "application/json"}
    :body (when body (json/write-str body))}))

(defn- parse-body [response]
  (edn/read-string (:body response)))

(deftest timeout-resolution-defaults-caps-and-rejects-invalid-values
  (testing "the default attempt deadline is 300 s"
    (is (= 300000 (miniapps/resolve-timeout-ms nil)))
    (is (= 180000 (miniapps/resolve-timeout-ms nil 180000))))
  (testing "a request override wins over the service deadline"
    (is (= 5000 (miniapps/resolve-timeout-ms 5000 300000))))
  (testing "an explicit service deadline is honored and the cap applies"
    (is (= 120000 (miniapps/resolve-timeout-ms nil 120000)))
    (is (= 600000 (miniapps/resolve-timeout-ms 900000)))
    (is (= 600000 (miniapps/resolve-timeout-ms nil 900000))))
  (testing "non-positive and non-integer values are explicit"
    (is (= :invalid (miniapps/resolve-timeout-ms 0)))
    (is (= :invalid (miniapps/resolve-timeout-ms -1)))
    (is (= :invalid (miniapps/resolve-timeout-ms "5000")))))

(deftest the-service-default-deadline-is-300-seconds
  (let [root (temp-root)
        data (temp-root)]
    (try
      (let [service (miniapps/service {:root (str root)
                                       :data-dir (str data)})]
        (is (= 300000 (:timeout-ms service)))
        (is (= 300000 (:timeout-ms (miniapps/service
                                    {:root (str root)
                                     :data-dir (str data)
                                     :provider-timeout-ms 60000}))))
        (is (= 420000 (:timeout-ms (miniapps/service
                                    {:root (str root)
                                     :data-dir (str data)
                                     :provider-timeout-ms 420000}))))
        (is (= 600000 (:timeout-ms (miniapps/service
                                    {:root (str root)
                                     :data-dir (str data)
                                     :provider-timeout-ms 900000})))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest generate-writes-one-html-file-and-registers-it
  (let [root (temp-root)
        data (temp-root)]
    (try
      (let [calls (atom 0)
            service (svc root data
                         {:generate-fn (fn [{:keys [prompt]}]
                                         (swap! calls inc)
                                         (is (str/includes? prompt "读书"))
                                         {:ok? true :text html-doc})})
            result (generate! service "reading-log")]
        (testing "the route result carries the app record"
          (is (:ok? result) (pr-str result))
          (is (= :ok (:status result)))
          (let [app (:app result)]
            (is (str/starts-with? (:app-id app) "app-"))
            (is (= "reading-log.html" (:name app)))
            (is (= "miniapps/reading-log.html" (:path app)))
            (is (= (str root) (:workspace-root app)))
            (is (= (str "miniapp:" (:app-id app)) (:storage-namespace app)))
            (is (= :available (:status app)))
            (is (= 1 (:data-version app)))
            (is (= 1 (:source-version app)))
            (is (= [] (:network-allowed app)))
            (is (= [] (:grants app)))))
        (testing "the file is a complete HTML document"
          (is (exists? root "miniapps/reading-log.html"))
          (let [written (read-file! root "miniapps/reading-log.html")]
            (is (str/starts-with? written "<!DOCTYPE html>"))
            (is (str/includes? written "</html>"))
            (is (str/includes? written miniapps/storage-marker))
            (is (str/includes? written (str "miniapp:" (:app-id (:app result)) ":")))
            (is (str/includes? written "window.miniappAppId"))
            (is (str/includes? written "window.miniappStorage"))))
        (testing "the app is listed"
          (let [listed ((:list-apps service))]
            (is (:ok? listed))
            (is (= ["reading-log.html"]
                   (mapv :name (:apps listed))))))
        (testing "the registry survives a new service instance"
          (let [again (svc root data)]
            (is (= ["reading-log.html"]
                   (mapv :name (:apps ((:list-apps again))))))))
        (testing "the HTTP route returns the same shape"
          (let [handler (miniapps/ring-handler
                         (svc root data
                              {:generate-fn
                               (fn [_] {:ok? true :text html-doc})}))
                response (handler (http-request
                                   :post miniapps/generate-uri
                                   {:prompt "记录读书条目"
                                    :filename "http-reading"}))]
            (is (= 200 (:status response)))
            (let [body (parse-body response)]
              (is (:ok? body))
              (is (= "miniapps/http-reading.html"
                     (get-in body [:app :path]))))
            (is (exists? root "miniapps/http-reading.html"))))
        (is (= 1 @calls)))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest a-declared-data-version-is-mirrored-for-the-client
  (let [root (temp-root)
        data (temp-root)]
    (try
      (let [html (str "<!DOCTYPE html><html><head>"
                      "<meta name=\"herdchestra-data-version\" content=\"3\">"
                      "</head><body>v3</body></html>")
            service (svc root data
                         {:generate-fn (fn [_] {:ok? true :text html})})
            result (generate! service "versioned")]
        (is (:ok? result) (pr-str result))
        (is (= 3 (get-in result [:app :data-version])))
        (is (= 3 (get-in result [:app :source-data-version])))
        (is (re-matches #"[0-9a-f]{8}" (get-in result [:app :source-digest]))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest invalid-html-and-external-resources-are-rejected-without-a-file
  (let [root (temp-root)
        data (temp-root)]
    (try
      (testing "plain text is not an HTML document"
        (let [service (svc root data
                           {:generate-fn (fn [_] {:ok? true :text "not html"})})
              result (generate! service "plain")]
          (is (= :INVALID_HTML (get-in result [:error :code])))
          (is (false? (exists? root "miniapps/plain.html")))))
      (testing "an external script source is rejected"
        (let [external (str "<!DOCTYPE html><html><head>"
                            "<script src=\"https://evil.example/x.js\"></script>"
                            "</head><body></body></html>")
              service (svc root data
                           {:generate-fn (fn [_] {:ok? true :text external})})
              result (generate! service "script")]
          (is (= :EXTERNAL_RESOURCE (get-in result [:error :code])))
          (is (false? (exists? root "miniapps/script.html")))))
      (testing "an external form action is rejected"
        (let [external (str "<!DOCTYPE html><html><body>"
                            "<form action=\"https://evil.example/collect\">"
                            "<input name=\"x\"></form></body></html>")
              service (svc root data
                           {:generate-fn (fn [_] {:ok? true :text external})})
              result (generate! service "form")]
          (is (= :EXTERNAL_RESOURCE (get-in result [:error :code])))
          (is (false? (exists? root "miniapps/form.html")))))
      (testing "a Markdown fence around a valid document is removed"
        (let [service (svc root data
                           {:generate-fn
                            (fn [_]
                              {:ok? true :text (str "```html\n" html-doc "\n```")})})
              result (generate! service "fenced")]
          (is (:ok? result) (pr-str result))
          (let [written (read-file! root "miniapps/fenced.html")]
            (is (str/includes? written "<h1>读书条目</h1>"))
            (is (str/includes? written miniapps/storage-marker)))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest filename-and-workspace-escapes-are-rejected
  (let [root (temp-root)
        data (temp-root)
        other (temp-root)]
    (try
      (let [service (svc root data
                         {:generate-fn (fn [_] {:ok? true :text html-doc})})]
        (testing "parent traversal in the filename"
          (let [result (generate! service "../escape")]
            (is (= :INVALID_FILENAME (get-in result [:error :code])))
            (is (false? (exists? root "../escape.html")))))
        (testing "a path separator in the filename"
          (let [result (generate! service "nested/app")]
            (is (= :INVALID_FILENAME (get-in result [:error :code])))
            (is (false? (exists? root "miniapps/nested/app.html")))))
        (testing "a blank filename"
          (let [result (generate! service "   ")]
            (is (= :INVALID_FILENAME (get-in result [:error :code])))))
        (testing "a requested workspace root other than the configured root"
          (let [result ((:generate! service)
                        {:prompt "x" :filename "outside"
                         :workspace-root (str other)})]
            (is (= :WORKSPACE_MISMATCH (get-in result [:error :code])))
            (is (false? (exists? other "miniapps/outside.html"))))))
      (testing "a miniapps symlink that resolves outside the workspace"
        (let [outside (temp-root)]
          (try
            (try
              (Files/createSymbolicLink (.resolve ^Path root "miniapps")
                                        outside
                                        (make-array FileAttribute 0))
              (let [service (svc root data
                                 {:generate-fn
                                  (fn [_] {:ok? true :text html-doc})})
                    result (generate! service "linked")]
                (is (= :PATH_ESCAPE (get-in result [:error :code])))
                (is (false? (exists? outside "linked.html"))))
              (catch UnsupportedOperationException _
                (is true "symlinks are unavailable on this file system")))
            (finally
              (delete-tree! outside)))))
      (finally
        (delete-tree! root)
        (delete-tree! data)
        (delete-tree! other)))))

(deftest an-existing-file-is-never-replaced
  (let [root (temp-root)
        data (temp-root)]
    (try
      (write-file! root "miniapps/taken.html" "ORIGINAL")
      (let [calls (atom 0)
            service (svc root data
                         {:generate-fn (fn [_]
                                         (swap! calls inc)
                                         {:ok? true :text html-doc})})]
        (testing "the early check refuses before a model call"
          (let [result (generate! service "taken")]
            (is (= :APP_EXISTS (get-in result [:error :code])))
            (is (zero? @calls))
            (is (= "ORIGINAL" (read-file! root "miniapps/taken.html"))))))
      (testing "a file created by the model call is still a conflict"
        (let [service (svc root data
                           {:generate-fn
                            (fn [_]
                              (write-file! root "miniapps/race.html" "RACE")
                              {:ok? true :text html-doc})})
              result (generate! service "race")]
          (is (= :APP_EXISTS (get-in result [:error :code])))
          (is (= "RACE" (read-file! root "miniapps/race.html")))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest model-failures-leave-no-file
  (let [root (temp-root)
        data (temp-root)]
    (try
      (testing "the model is not configured"
        (let [service (svc root data {:model-port-ref (atom nil)})
              result (generate! service "unconfigured")]
          (is (= :MODEL_NOT_CONFIGURED (get-in result [:error :code])))
          (is (false? (exists? root "miniapps/unconfigured.html")))))
      (testing "the generator reports a failure"
        (let [service (svc root data
                           {:generate-fn
                            (fn [_]
                              {:ok? false
                               :error {:code :MODEL_FAILED :message "boom"}})})
              result (generate! service "failed")]
          (is (= :MODEL_FAILED (get-in result [:error :code])))
          (is (false? (exists? root "miniapps/failed.html")))))
      (testing "the real port path delivers a failed event"
        (let [port (reify model-contract/ModelPort
                     (start-request! [_ request receiver]
                       (model-contract/deliver-event!
                        receiver
                        (model-contract/event
                         {:kind :failed
                          :attempt-id (:attempt-id request)
                          :call-id (:call-id request)
                          :error {:kind :transport :message "port failed"}}))
                       {:attempt-id (:attempt-id request)
                        :call-id (:call-id request)})
                     (cancel-request! [_ _] nil))
              service (svc root data {:model-port-ref (atom port)})
              result (generate! service "port-failed")]
          (is (= :MODEL_FAILED (get-in result [:error :code])))
          (is (= :model-failed (get-in result [:error :kind])))
          (is (= :retry (get-in result [:error :remedy])))
          (is (false? (exists? root "miniapps/port-failed.html")))))
      (testing "a provider timeout is a timeout receipt, not model-failed"
        (let [port (reify model-contract/ModelPort
                     (start-request! [_ request receiver]
                       (model-contract/deliver-event!
                        receiver
                        (model-contract/event
                         {:kind :failed
                          :attempt-id (:attempt-id request)
                          :call-id (:call-id request)
                          :error {:kind :timeout
                                  :category :timeout
                                  :retryable? true
                                  :message "request timed out"}}))
                       {:attempt-id (:attempt-id request)
                        :call-id (:call-id request)})
                     (cancel-request! [_ _] nil))
              service (svc root data {:model-port-ref (atom port)})
              result (generate! service "provider-timeout")]
          (is (= :MODEL_TIMEOUT (get-in result [:error :code])))
          (is (= :timeout (get-in result [:error :kind])))
          (is (= :retry (get-in result [:error :remedy])))
          (is (true? (get-in result [:error :details :provider-timeout?])))
          (is (false? (exists? root "miniapps/provider-timeout.html")))))
      (testing "a request timeout override cancels the port and leaves no file"
        (let [cancelled (atom false)
              port (reify model-contract/ModelPort
                     (start-request! [_ request _receiver]
                       {:attempt-id (:attempt-id request)
                        :call-id (:call-id request)})
                     (cancel-request! [_ _]
                       (reset! cancelled true)))
              service (svc root data {:model-port-ref (atom port)})
              started (System/currentTimeMillis)
              result ((:generate! service)
                      {:prompt "x" :filename "timeout" :timeout-ms 50})
              elapsed (- (System/currentTimeMillis) started)]
          (is (= :MODEL_TIMEOUT (get-in result [:error :code])))
          (is (= :timeout (get-in result [:error :kind])))
          (is (= :retry (get-in result [:error :remedy])))
          (is (= 50 (get-in result [:error :details :timeout-ms])))
          (is (< elapsed 2000))
          (is (true? @cancelled))
          (is (false? (exists? root "miniapps/timeout.html")))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest storage-injection-is-idempotent-and-namespaced
  (let [a (miniapps/inject-storage html-doc "app-a")
        b (miniapps/inject-storage html-doc "app-b")
        twice (miniapps/inject-storage a "app-a")]
    (testing "one marker and one app id, even after a second injection"
      (is (= 1 (count (re-seq (re-pattern miniapps/storage-marker) a))))
      (is (= 1 (count (re-seq #"window\.miniappAppId" a))))
      (is (= a twice)))
    (testing "each app gets its own prefix"
      (is (str/includes? a "miniapp:app-a:"))
      (is (str/includes? b "miniapp:app-b:"))
      (is (not (str/includes? a "miniapp:app-b:"))))
    (testing "clear only removes keys under the current prefix"
      (is (str/includes? a "key.indexOf(PREFIX) === 0"))
      (is (str/includes? a "removeItem(PREFIX + key)")))
    (testing "validation alone does not inject anything"
      (let [validated (miniapps/validate-html html-doc)]
        (is (:ok? validated))
        (is (not (str/includes? (:html validated) miniapps/storage-marker)))))))

(deftest injected-storage-isolates-two-apps-in-node
  (let [root (temp-root)
        probe (io/file "test/hc/server/miniapps_storage_probe.cjs")]
    (try
      (write-file! root "a.html" (miniapps/inject-storage html-doc "app-a"))
      (write-file! root "b.html" (miniapps/inject-storage html-doc "app-b"))
      (if (zero? (:exit (try (sh/sh "node" "--version")
                             (catch Throwable _ {:exit 1}))))
        (let [result (sh/sh "node" (str probe)
                            (str (.resolve ^Path root "a.html"))
                            (str (.resolve ^Path root "b.html")))]
          (is (zero? (:exit result)) (str (:err result)))
          (is (str/includes? (:out result) "\"ok\":true") (:out result)))
        (is true "node is unavailable; the browser evidence covers this case"))
      (finally
        (delete-tree! root)))))

(deftest an-output-over-two-mib-is-rejected-without-a-file
  (let [root (temp-root)
        data (temp-root)]
    (try
      (let [huge (str "<!DOCTYPE html><html><body>"
                      (apply str (repeat (* 2 1024 1024) "x"))
                      "</body></html>")
            service (svc root data
                         {:generate-fn (fn [_] {:ok? true :text huge})})
            result (generate! service "huge")]
        (is (= :OUTPUT_TOO_LARGE (get-in result [:error :code])))
        (is (false? (exists? root "miniapps/huge.html"))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest a-length-truncated-port-result-is-not-accepted
  (let [root (temp-root)
        data (temp-root)]
    (try
      (let [port (reify model-contract/ModelPort
                   (start-request! [_ request receiver]
                     (model-contract/deliver-event!
                      receiver
                      (model-contract/event
                       {:kind :completed
                        :attempt-id (:attempt-id request)
                        :call-id (:call-id request)
                        :finish-reason :length
                        :text "<!DOCTYPE html><html><body>partial"}))
                     {:attempt-id (:attempt-id request)
                      :call-id (:call-id request)})
                   (cancel-request! [_ _] nil))
            service (svc root data {:model-port-ref (atom port)})
            result (generate! service "truncated")]
        (is (= :OUTPUT_TRUNCATED (get-in result [:error :code])))
        (is (false? (exists? root "miniapps/truncated.html"))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest the-low-level-port-path-builds-one-frozen-request
  (let [root (temp-root)
        data (temp-root)
        seen (atom nil)]
    (try
      (let [port (reify model-contract/ModelPort
                   (start-request! [_ request receiver]
                     (reset! seen request)
                     (model-contract/deliver-event!
                      receiver
                      (model-contract/event
                       {:kind :completed
                        :attempt-id (:attempt-id request)
                        :call-id (:call-id request)
                        :text html-doc}))
                     {:attempt-id (:attempt-id request)
                      :call-id (:call-id request)})
                   (cancel-request! [_ _] nil))
            service (svc root data {:model-port-ref (atom port)})
            result (generate! service "ported")]
        (is (:ok? result) (pr-str result))
        (is (= :task (get-in @seen [:snapshot :role])))
        (is (= 1024 (get-in @seen [:snapshot :max-tokens])))
        (is (= false (get-in @seen [:snapshot :stream?])))
        (is (= 2 (count (get-in @seen [:snapshot :messages]))))
        (is (= "miniapps/ported.html" (get-in result [:app :path])))
        (is (exists? root "miniapps/ported.html")))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest a-registry-write-failure-removes-the-new-file
  (let [root (temp-root)
        data (temp-root)]
    (try
      ;; Make the registry target a directory so the atomic write fails.
      (Files/createDirectories (.resolve ^Path data "miniapps/registry.edn")
                               (make-array FileAttribute 0))
      (let [service (svc root data
                         {:generate-fn (fn [_] {:ok? true :text html-doc})})
            result (generate! service "registry-failure")]
        (is (= :REGISTRY_FAILED (get-in result [:error :code])))
        (is (true? (get-in result [:error :details :file-removed?])))
        (is (false? (exists? root "miniapps/registry-failure.html"))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest the-http-timeout-receipt-is-structured-and-504
  (let [root (temp-root)
        data (temp-root)
        port (reify model-contract/ModelPort
               (start-request! [_ request _receiver]
                 {:attempt-id (:attempt-id request)
                  :call-id (:call-id request)})
               (cancel-request! [_ _] nil))]
    (try
      (let [handler (miniapps/ring-handler
                     (svc root data {:model-port-ref (atom port)}))
            response (handler
                      (http-request :post miniapps/generate-uri
                                    {:prompt "x" :filename "http-timeout"
                                     :timeout-ms 50}))
            body (parse-body response)]
        (is (= 504 (:status response)))
        (is (= :MODEL_TIMEOUT (get-in body [:error :code])))
        (is (= :timeout (get-in body [:error :kind])))
        (is (= :retry (get-in body [:error :remedy])))
        (is (= 50 (get-in body [:error :details :timeout-ms])))
        (is (false? (exists? root "miniapps/http-timeout.html"))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))

(deftest routes-reject-wrong-methods-and-unknown-paths
  (let [root (temp-root)
        data (temp-root)]
    (try
      (let [handler (miniapps/ring-handler (svc root data))]
        (testing "generate is POST-only"
          (let [response (handler (http-request :get miniapps/generate-uri))]
            (is (= 405 (:status response)))
            (is (= "POST" (get-in response [:headers "allow"])))))
        (testing "list is GET-only"
          (let [response (handler (http-request :post miniapps/list-uri {}))]
            (is (= 405 (:status response)))
            (is (= "GET, HEAD" (get-in response [:headers "allow"])))))
        (testing "unknown paths are not claimed"
          (is (nil? (handler (http-request :get "/api/workspace/other")))))
        (testing "invalid JSON is a structured 400"
          (let [response (handler {:request-method :post
                                   :uri miniapps/generate-uri
                                   :headers {"content-type" "application/json"}
                                   :body "{not json"})]
            (is (= 400 (:status response)))
            (is (= :INVALID_JSON
                   (get-in (parse-body response) [:error :code]))))))
      (finally
        (delete-tree! root)
        (delete-tree! data)))))
