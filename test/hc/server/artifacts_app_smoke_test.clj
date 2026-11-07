(ns hc.server.artifacts-app-smoke-test
  "Full-application artifact smoke.

  Starts the integrated system, registers a file generated in the application
  workspace through the system's own artifact component, checks the snapshot
  entry, and downloads the bytes from the bound HTTP port.  This is the only
  test here that exercises app assembly rather than a hand-built service."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.system :as system]
            [hc.server.sessions.registry :as registry])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write-file! [path content]
  (let [file (java.io.File. ^String path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content)))

(defn- test-config []
  (let [root (temp-dir "hc-app-artifacts-root-")
        data (temp-dir "hc-app-artifacts-data-")
        static (temp-dir "hc-app-artifacts-static-")]
    (write-file! (str static "/index.html") "<!doctype html><title>hc</title>")
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root root
      :static-root static
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :models {}})))

(defn- http-get-bytes [url]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode response)
     :body (.body response)}))

(deftest app-assembly-registers-and-delivers-generated-file
  (let [config (test-config)
        started (system/start! config)]
    (try
      (is (false? (:blocked? started)))
      (let [artifacts (:artifacts started)
            session-id (-> (registry/list-sessions (:registry started)
                                                   {:include-deleted? false})
                           :sessions
                           first
                           :session-id)
            generated (str (:workspace-root started)
                           "/out/generated-by-app.txt")]
        (is (some? artifacts) "the app assembles the artifact component")
        (is (string? session-id))
        (write-file! generated "app-generated artifact\n")
        (let [result ((:register! artifacts)
                      {:session-id session-id
                       :file generated
                       :name "generated-by-app.txt"
                       :kind :file
                       :source-task-id "app-smoke-task"
                       :source-call-id "app-smoke-call"
                       :producer {:kind :tool :name "shell"}})
              artifact (:artifact result)
              snapshot ((:snapshot artifacts) session-id)
              download-url (str (:url started)
                                (subs (:url ((:download artifacts) artifact))
                                      1))
              response (http-get-bytes download-url)]
          (is (:ok? result))
          (is (= :available (:status artifact)))
          (is (= "app-generated artifact\n"
                 (String. ^bytes (:body response) StandardCharsets/UTF_8)))
          (is (= 200 (:status response)))
          (is (= 1 (count (:artifacts snapshot))))
          (is (= (:content-id artifact)
                 (:content-id (first (:artifacts snapshot)))))
          (is (= "app-smoke-task"
                 (:source-task-id (first (:artifacts snapshot)))))
          (is (string? download-url))))
      (finally
        (system/stop! started)))))
