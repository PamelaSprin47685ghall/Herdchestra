(ns hc.server.model-auth.app-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.model-auth.app :as app]
            [hc.server.model-auth.support :as support])
  (:import (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse$BodyHandlers)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  []
  (Files/createTempDirectory "hc-model-auth-app-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [p (reverse (vec (iterator-seq (.iterator stream))))]
        (try (Files/deleteIfExists p) (catch Exception _ nil))))))

(defn- http-get
  [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))
        request (-> (HttpRequest/newBuilder (java.net.URI/create url))
                    (.GET)
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/discarding))))

(deftest app-adapter-local-oauth-smoke-test
  (let [dir (temp-dir)
        oauth (support/start-oauth-server)
        component (app/start! {:data-dir (str dir)
                               :model-auth
                               {:providers (support/providers oauth)
                                :device-poll? false}})]
    (try
      (let [providers (:model-auth/providers (app/snapshot component))]
        (is (some #(= :local (:id %)) providers))
        (is (not (re-find #"client-secret|access-token"
                          (pr-str providers)))))
      (let [started (app/handle! component
                                 {:kind :model-auth/begin
                                  :connection-id "conn-1"
                                  :provider-id "local"
                                  :flow-type :authorization-code})
            flow (:receipt started)
            location (support/callback-url (:authorize-url flow))]
        (is (:ok? started))
        (is (not (re-find #"access-token|refresh-token|device-code"
                          (pr-str started))))
        (http-get location)
        (let [status (app/handle! component
                                  {:kind :model-auth/poll
                                   :flow-id (:flow-id flow)})]
          (is (= :authorized (:status (:receipt status))))
          (is (= "user-1" (get-in status [:receipt :account :subject])))))
      (let [{:keys [models errors]}
            (app/inject-models component
                               {:connections [{:id "conn-1"
                                               :service-kind :account-login}]})]
        (is (empty? errors))
        (is (= "access-1" (get-in models [:connections 0 :api-key])))
        (is (= :connected (get-in models [:connections 0 :auth-status]))))
      (let [snapshot (app/snapshot component)]
        (is (= 1 (count (:model-auth/accounts snapshot))))
        (is (= "user-1"
               (get-in snapshot [:model-auth/accounts 0 :subject])))
        (is (not (re-find #"access-1|refresh-1" (pr-str snapshot))))
        (is (true? (get-in snapshot
                           [:model-auth/accounts 0 :has-refresh-token?]))))
      (finally
        (app/stop! component)
        (support/stop! oauth)
        (delete-tree! dir)))))
