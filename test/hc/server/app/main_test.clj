(ns hc.server.app.main-test
  "Process entry point: help, config errors and --check exit codes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app :as app]
            [hc.server.app.restore-supervisor :as supervisor]
            [hc.server.app.system :as system])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  []
  (str (Files/createTempDirectory "hc-main-" (make-array FileAttribute 0))))

(defn- isolated-args
  []
  (let [root (temp-dir)]
    ["--check" "--port" "0"
     "--data-dir" (str root "/data")
     "--workspace-root" (str root "/work")]))

(defn- run!
  [args]
  (binding [*out* (java.io.StringWriter.)
            *err* (java.io.StringWriter.)]
    (app/run-main! args)))

(deftest help-and-config-errors
  (is (= 0 (run! ["--help"])))
  (is (= 2 (run! ["--port" "not-a-port"]))))

(deftest check-exit-code-follows-health-and-stop
  (let [args (isolated-args)
        started {:blocked? false :ok? true}]
    (testing "healthy and confirmed stop exits zero"
      (with-redefs [supervisor/prepare! (fn [_] nil)
                    system/start! (fn [_ _] started)
                    system/health (fn [_] {:ok true})
                    system/stop! (fn [_] {:stopped? true
                                          :store-closed? true})]
        (is (= 0 (run! args)))))
    (testing "unhealthy exits non-zero"
      (with-redefs [supervisor/prepare! (fn [_] nil)
                    system/start! (fn [_ _] started)
                    system/health (fn [_] {:ok false})
                    system/stop! (fn [_] {:stopped? true
                                          :store-closed? true})]
        (is (= 1 (run! args)))))
    (testing "an unconfirmed stop exits non-zero"
      (with-redefs [supervisor/prepare! (fn [_] nil)
                    system/start! (fn [_ _] started)
                    system/health (fn [_] {:ok true})
                    system/stop! (fn [_] {:stopped? false
                                          :store-closed? true})]
        (is (= 1 (run! args)))))))
