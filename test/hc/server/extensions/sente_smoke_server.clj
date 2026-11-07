(ns hc.server.extensions.sente-smoke-server
  "Minimal real Sente host for the extensions smoke.

  It starts the production gateway with the extension component as Owner and a
  real local MCP process.  It is not a unit test; the Node smoke drives it."
  (:require [clojure.java.io :as io]
            [hc.server.extensions.app :as ext-app]
            [hc.server.extensions.support :as support]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.ports :as ports]
            [org.httpkit.server :as http]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit])
  (:import (java.net ServerSocket)))

(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn -main
  [& _]
  (let [dir (support/temp-dir)
        stub-dir (str dir "/stub")
        _ (.mkdirs (java.io.File. stub-dir))
        _ (support/compile-stub! stub-dir)
        command (support/stub-command stub-dir)
        component (ext-app/start! {:data-dir (str dir "/data")})
        owner (ports/owner-port
               (fn [request reply-fn]
                 (let [result (ext-app/handle! component (:payload request)
                                               {:scope (:scope request)})]
                   (reply-fn result)
                   result))
               (fn [_] nil))
        gateway (gateway/start! {:sch-adapter (sente-http-kit/get-sch-adapter)
                                 :owner owner
                                 :snapshot {:ui/connection {:phase :synced
                                                            :command-epoch 1}
                                            :smoke true}
                                 :now-fn #(System/currentTimeMillis)})
        port (free-port)
        server (http/run-server (gateway/handler gateway)
                                {:host "127.0.0.1" :port port})
        stopped (promise)]
    (println (str "READY " port " " (first command) " "
                  (pr-str (vec (rest command)))))
    (flush)
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. (fn []
                (try (http/server-stop! server {:timeout 1000}) (catch Throwable _ nil))
                (try (gateway/stop! gateway) (catch Throwable _ nil))
                (try (ext-app/stop! component) (catch Throwable _ nil))
                (support/delete-tree dir)
                (deliver stopped true))))
    (deref stopped 600000 nil)
    (shutdown-agents)))
