(ns hc.server.artifacts-smoke-test
  "Real HTTP delivery smoke for a registered artifact.

  The test starts the JDK HTTP host, serves the gateway's `/blobs/<id>` route
  through a real socket, and compares the downloaded bytes with the file that
  was registered.  It also exercises HEAD, a byte range and a missing object.
  Nothing is mocked."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.workspace-gate.gate :as gate]
            [hc.server.artifacts :as artifacts]
            [hc.server.gateway.http :as gateway-http]
            [hc.server.gateway.jdk-http :as jdk-http]
            [hc.server.gateway.ports :as ports]
            [hc.server.shell :as shell]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                           HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration)))

(defn- temp-dir []
  (str (Files/createTempDirectory "hc-artifacts-smoke-"
                                  (make-array FileAttribute 0))))

(defn- ready-board []
  (-> (shell/memory-board)
      (shell/set-actor! :agent true)
      (shell/set-token! :agent 7)
      (shell/set-claim! {:claim-id :claim-1
                         :actor-id :agent
                         :task-id :task-1})
      (shell/set-task! :task-1 :ready {:needs-token true})))

(defn- shell-request [id command]
  {:request-id id
   :actor-id :agent
   :token-term 7
   :task-id :task-1
   :claim :claim-1
   :command command
   :cwd "."
   :purpose "artifact generation smoke"})

(defn- request
  [client method url headers]
  (let [builder (HttpRequest/newBuilder (URI/create url))
        _ (doseq [[k v] headers] (.header builder (name k) (str v)))
        builder (case method
                  :get (.GET builder)
                  :head (.method builder "HEAD" (HttpRequest$BodyPublishers/noBody)))]
    (.build builder)))

(defn- http-send
  ([client method url] (http-send client method url {}))
  ([client method url headers]
   (let [response (.send client (request client method url headers)
                         (HttpResponse$BodyHandlers/ofByteArray))]
     {:status (.statusCode response)
      :body (.body response)
      :headers (into {} (map (fn [[k v]] [(keyword (.toLowerCase k)) (first v)]))
                     (.map (.headers response)))})))

(deftest real-http-file-delivery-smoke
  (let [dir (temp-dir)
        store (storage/open-store {:path (str dir "/db") :create? true})
        blob-root (str dir "/blobs")
        svc (artifacts/service {:store store :blob-root blob-root})
        file (str dir "/delivered.txt")]
    (try
      (spit file "交付内容\nsecond line\n")
      (let [artifact (:artifact
                      (artifacts/register!
                       svc {:session-id "smoke"
                            :file file
                            :name "delivered.txt"
                            :source-task-id "smoke-task"}))
            download (artifacts/download svc artifact)
            content-id (:content-id artifact)
            content-types {content-id "text/plain; charset=utf-8"}
            gw {:opts {:content-type-fn (fn [id _] (get content-types id))}
                :stopped? (atom false)
                :store (ports/blob-store-port blob-root)}
            server (jdk-http/start! {:handler (gateway-http/ring-handler gw)})
            base (str "http://127.0.0.1:" (:port server))
            client (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds 5))
                       (.build))]
        (try
          (testing "the independent blob route delivers the registered bytes"
            (let [response (http-send client :get (str base (:url download)))]
              (is (= 200 (:status response)))
              (is (= "交付内容\nsecond line\n"
                     (String. ^bytes (:body response)
                              StandardCharsets/UTF_8)))
              (is (= "text/plain; charset=utf-8"
                     (get-in response [:headers :content-type])))
              (is (= (str "\"" content-id "\"")
                     (get-in response [:headers :etag])))
              (is (re-find #"attachment; filename=\"delivered.txt\""
                           (get-in response [:headers :content-disposition])))))
          (testing "the inline open URL does not force a download"
            (let [response (http-send client :get (str base (:open-url artifact)))]
              (is (= 200 (:status response)))
              (is (= "交付内容\nsecond line\n"
                     (String. ^bytes (:body response)
                              StandardCharsets/UTF_8)))
              (is (nil? (get-in response [:headers :content-disposition]))
                  "no filename query means the gateway must not add attachment")
              (is (= "text/plain; charset=utf-8"
                     (get-in response [:headers :content-type])))))
          (testing "HEAD and a single byte range use the same immutable object"
            (let [head (http-send client :head (str base (:url download)))]
              (is (= 200 (:status head)))
              (is (= (str (:size artifact))
                     (get-in head [:headers :content-length]))))
            (let [range (http-send client :get
                              (str base "/blobs/" content-id)
                              {:range "bytes=0-5"})]
              (is (= 206 (:status range)))
              (is (= 6 (count (:body range))))
              (is (= "交付"
                     (String. ^bytes (:body range) StandardCharsets/UTF_8)))))
          (testing "a missing content ID is a real 404"
            (let [response (http-send client :get
                                 (str base "/blobs/sha256-"
                                      (apply str (repeat 64 "a"))))]
              (is (= 404 (:status response)))))
          (finally
            ((:stop! server)))))
      (finally
        (try (storage/close-store! store) (catch Throwable _ nil))
        (support/delete-tree! dir)))))

(deftest generated-file-and-shell-output-delivery-smoke
  (let [dir (temp-dir)
        store (storage/open-store {:path (str dir "/db") :create? true})
        blob-root (str dir "/blobs")
        svc (artifacts/service {:store store :blob-root blob-root})]
    (try
      (let [board (ready-board)
            runner (shell/runner {:root dir
                                  :gate (gate/write-gate)
                                  :board board
                                  :artifact-dir (str dir "/shell-artifacts")})
            result (shell/run!
                    runner
                    (shell-request
                     "gen-1"
                     ["sh" "-c"
                      (str "mkdir -p out && "
                           "printf 'generated artifact\\n' > out/report.txt && "
                           "printf 'stdout line\\n'")]))
            generated (str dir "/out/report.txt")
            output-path (get-in result [:output :artifact :path])]
        (is (= :COMPLETED (:status result)))
        (is (= "generated artifact\n" (slurp generated)))
        (is (string? output-path))
        (let [file-result
              (artifacts/register!
               svc {:session-id "gen"
                    :file generated
                    :name "report.txt"
                    :kind :file
                    :source-task-id "task-1"
                    :source-call-id "gen-1"
                    :producer {:kind :tool :name "shell"}
                    :inputs [{:kind :task :id "task-1"}
                             {:kind :call :id "gen-1"}]})
              output-result
              (artifacts/register-shell-output!
               svc {:session-id "gen"
                    :source-task-id "task-1"
                    :source-call-id "gen-1"}
               result)
              file-artifact (:artifact file-result)
              output-artifact (:artifact output-result)
              content-types {(:content-id file-artifact) "text/plain; charset=utf-8"
                             (:content-id output-artifact) "text/plain; charset=utf-8"}
              gw {:opts {:content-type-fn (fn [id _] (get content-types id))}
                  :stopped? (atom false)
                  :store (ports/blob-store-port blob-root)}
              server (jdk-http/start! {:handler (gateway-http/ring-handler gw)})
              base (str "http://127.0.0.1:" (:port server))
              client (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofSeconds 5))
                         (.build))]
          (try
            (testing "a file generated by a real shell process is delivered"
              (let [response (http-send client :get
                                        (str base (:url (artifacts/download
                                                         svc file-artifact))))]
                (is (= 200 (:status response)))
                (is (= "generated artifact\n"
                       (String. ^bytes (:body response)
                                StandardCharsets/UTF_8)))
                (is (= "task-1" (:source-task-id file-artifact)))
                (is (= "gen-1" (:source-call-id file-artifact)))))
            (testing "the shell capture itself is a registered log artifact"
              (let [response (http-send client :get
                                        (str base (:url (artifacts/download
                                                         svc output-artifact))))]
                (is (= 200 (:status response)))
                (is (= (slurp output-path)
                       (String. ^bytes (:body response)
                                StandardCharsets/UTF_8)))
                (is (= :log (:kind output-artifact)))
                (is (false? (:truncated? output-artifact)))
                (is (true? (:complete? output-artifact)))))
            (finally
              ((:stop! server))))))
      (finally
        (try (storage/close-store! store) (catch Throwable _ nil))
        (support/delete-tree! dir)))))
