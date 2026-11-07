(ns hc.server.app.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "hc-config-" (make-array FileAttribute 0))))

(deftest defaults-are-normalized
  (let [config (config/load-config {:args [] :env {}})]
    (is (= "127.0.0.1" (:host config)))
    (is (= 7575 (:port config)))
    (is (= :info (:log-level config)))
    (is (string? (:db-path config)))
    (is (.isAbsolute (io/file (:workspace-root config))))))

(deftest fleet-defaults-are-normalized-and-validated
  (let [config (config/load-config {:args [] :env {}})]
    (is (= "master" (get-in config [:fleet :master-ref])))
    (is (= (* 7 24 60 60 1000) (get-in config [:fleet :retention-ms])))
    (is (= (* 60 60 1000) (get-in config [:fleet :gc-interval-ms])))
    (is (true? (get-in config [:fleet :gc-run-immediately?])))
    (is (string? (get-in config [:fleet :state-file])))
    (is (.isAbsolute (io/file (get-in config [:fleet :state-file])))))
  (testing "a partial fleet map keeps the other defaults"
    (let [config (config/normalize {:fleet {:retention-ms 1}})]
      (is (= 1 (get-in config [:fleet :retention-ms])))
      (is (= "master" (get-in config [:fleet :master-ref])))))
  (testing "invalid fleet values are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"retention-ms"
                          (config/normalize {:fleet {:retention-ms -1}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gc-interval-ms"
                          (config/normalize {:fleet {:gc-interval-ms 0}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fleet must be a map"
                          (config/normalize {:fleet "nope"})))))

(deftest gateway-defaults-are-normalized
  (let [config (config/load-config {:args [] :env {}})]
    (is (= :all (get-in config [:gateway :allowed-origins])))
    (is (= #{} (get-in config [:gateway :trusted-hosts])))
    (is (false? (get-in config [:gateway :allow-remote?])))
    (is (= 4096 (get-in config [:gateway :max-pending])))
    (is (= (* 64 1024 1024)
           (get-in config [:gateway :max-state-object-bytes])))
    (is (= 128 (get-in config [:gateway :max-state-objects])))))

(deftest gateway-config-is-validated-and-canonicalized
  (let [config (config/normalize
                {:gateway
                 {:allowed-origins ["http://App.Example:80/"
                                    "https://cdn.example"]
                  :trusted-hosts ["App.Example:7575" "[::1]:7575"]
                  :allow-remote? false
                  :max-pending 128
                  :max-upload-bytes (* 2 1024 1024)}})]
    (is (= #{"http://app.example" "https://cdn.example"}
           (get-in config [:gateway :allowed-origins])))
    (is (= #{"app.example" "::1"}
           (get-in config [:gateway :trusted-hosts])))
    (is (false? (get-in config [:gateway :allow-remote?])))
    (is (= 128 (get-in config [:gateway :max-pending])))
    (is (= (* 2 1024 1024)
           (get-in config [:gateway :max-upload-bytes]))))
  (testing "a partial gateway map keeps the default acknowledgement window"
    (let [config (config/normalize {:gateway {:allowed-origins :all}})]
      (is (= 4096 (get-in config [:gateway :max-pending])))
      (is (= #{} (get-in config [:gateway :trusted-hosts])))))
  (testing "invalid values fail before the socket opens"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":gateway must be a map"
         (config/normalize {:gateway "nope"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"allowed-origins"
         (config/normalize
          {:gateway {:allowed-origins "http://app.example"}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"allowed-origins"
         (config/normalize
          {:gateway {:allowed-origins [:all]}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"allowed-origins"
         (config/normalize
          {:gateway {:allowed-origins ["not-an-origin"]}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"trusted-hosts"
         (config/normalize
          {:gateway {:trusted-hosts "app.example"}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"allow-remote"
         (config/normalize
          {:gateway {:allow-remote? "true"}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"allow-remote"
         (config/normalize
          {:gateway {:allow-remote? true}}))
        "remote mode is rejected because EDN cannot supply authentication")
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"max-pending"
         (config/normalize
          {:gateway {:max-pending 0}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"max-state-objects"
         (config/normalize
          {:gateway {:max-state-objects 0}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"max-state-object-bytes"
         (config/normalize
          {:gateway {:max-state-object-bytes -1}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"authorized"
         (config/normalize
          {:gateway {:authorized?-fn (fn [_] true)}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"unknown gateway"
         (config/normalize
          {:gateway {:sente-allowed-origins :all}})))))

(deftest precedence-file-env-cli
  (let [dir (temp-dir)
        file (io/file dir "config.edn")]
    (spit file (pr-str {:port 7001 :host "0.0.0.0" :log-level :debug}))
    (let [config (config/load-config
                  {:args ["--port" "7003" "--host" "127.0.0.1"]
                   :env {"HC_PORT" "7002" "HC_CONFIG" (str file)}})]
      (is (= 7003 (:port config)) "CLI wins over env and file")
      (is (= "127.0.0.1" (:host config)) "CLI wins over file")
      (is (= :debug (:log-level config)) "file value survives when not overridden"))))

(deftest ephemeral-port-is-valid
  (is (= 0 (:port (config/load-config {:args ["--port" "0"] :env {}})))))

(deftest nudge-enabled-is-on-by-default-and-env-overridable
  (is (true? (:nudge-enabled? (config/load-config {:args [] :env {}})))
      "§2.5 continuation stays on unless explicitly disabled")
  (let [off (config/load-config {:args [] :env {"HC_NUDGE_ENABLED" "false"}})
        on (config/load-config {:args [] :env {"HC_NUDGE_ENABLED" "on"}})]
    (is (false? (:nudge-enabled? off)))
    (is (true? (:nudge-enabled? on))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nudge-enabled\? must be a boolean"
                        (config/normalize {:nudge-enabled? "nope"}))))

(deftest malformed-values-are-rejected
  (testing "non-numeric port"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"port must be an integer"
                          (config/load-config {:args ["--port" "x"] :env {}}))))
  (testing "out-of-range port"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"between 0 and 65535"
                          (config/load-config {:args ["--port" "70000"] :env {}}))))
  (testing "unknown log level"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"log-level"
                          (config/load-config {:args ["--log-level" "loud"] :env {}}))))
  (testing "unknown option"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown option"
                          (config/load-config {:args ["--wat" "1"] :env {}}))))
  (testing "missing value"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a value"
                          (config/load-config {:args ["--port"] :env {}})))))

(deftest config-file-must-be-a-map
  (let [dir (temp-dir)
        file (io/file dir "bad.edn")]
    (spit file "[:not :a :map]")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must contain one EDN map"
                          (config/load-config {:args ["--config" (str file)]
                                               :env {}})))))

(deftest help-is-not-a-server-configuration
  (is (config/help-requested? (config/load-config {:args ["--help"] :env {}})))
  (is (config/help-requested? (config/load-config {:args ["-h"] :env {}}))))

(deftest describe-redacts-secrets
  (let [config (config/load-config
                {:args []
                 :env {}
                 :extra {:models {:main {:api-key "secret"}}}})
        described (config/describe (assoc config
                                          :models {:main {:api-key "secret"}}))]
    (is (not (contains? described :models)))
    (is (true? (:models-configured? described)))))
