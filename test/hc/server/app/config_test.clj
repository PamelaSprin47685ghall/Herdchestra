(ns hc.server.app.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.steward.loop :as steward-loop]
            [hc.server.app.steward-runtime :as steward-runtime]
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

(deftest steward-defaults-and-overrides
  (let [config (config/load-config {:args [] :env {}})]
    (is (= 60000 (get-in config [:steward :interval-ms])))
    (is (= 600000 (get-in config [:steward :plan-stall-ms]))))
  (testing "a partial steward map keeps the other default"
    (let [config (config/normalize {:steward {:interval-ms 5000}})]
      (is (= 5000 (get-in config [:steward :interval-ms])))
      (is (= 600000 (get-in config [:steward :plan-stall-ms])))))
  (testing "invalid steward values are structured errors with the key"
    (doseq [[value pattern]
            [[{:interval-ms 500} #"steward interval-ms"]
             [{:plan-stall-ms 0} #"steward plan-stall-ms"]
             [{:plan-stall-ms "x"} #"steward plan-stall-ms"]]]
      (let [error (try
                    (config/normalize {:steward value})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))
            key (if (contains? value :interval-ms)
                  [:steward :interval-ms]
                  [:steward :plan-stall-ms])]
        (is (some? error) (pr-str value))
        (is (re-find pattern (.getMessage ^Exception error)) (pr-str value))
        (is (= :config-invalid (:error/kind (ex-data error))) (pr-str value))
        (is (= key (:key (ex-data error))) (pr-str value))
        (is (= key (:field (ex-data error))) (pr-str value))
        (is (= (get value (second key)) (:value (ex-data error)))
            (pr-str value))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":steward must be a map"
                          (config/normalize {:steward "nope"}))))
  (testing "an unknown steward key is rejected explicitly"
    (let [error (try
                  (config/normalize {:steward {:unknown 1}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? error))
      (is (= :config-invalid (:error/kind (ex-data error))))
      (is (= :unknown-key (:code (ex-data error))))
      (is (= [:steward :unknown] (:key (ex-data error))))
      (is (= [:steward :unknown] (:field (ex-data error))))
      (is (= 1 (:value (ex-data error)))))))

(deftest steward-timer-defaults-do-not-drift
  (testing "the plan-stall default has one compose-level source"
    (is (= steward-loop/default-plan-stall-ms
           (get-in config/defaults [:steward :plan-stall-ms]))))
  (testing "the configured interval default is a sane positive timer"
    (let [interval (get-in config/defaults [:steward :interval-ms])]
      (is (and (integer? interval) (<= 1000 interval)))))
  (testing "the runtime fallback equals the configured interval default"
    (is (= steward-runtime/default-interval-ms
           (get-in config/defaults [:steward :interval-ms])))
    (is (and (integer? steward-runtime/default-interval-ms)
             (<= 1000 steward-runtime/default-interval-ms)))))

(deftest retention-defaults-are-normalized-and-validated
  (let [config (config/load-config {:args [] :env {}})]
    (is (= 30 (get-in config [:retention :session-days])))
    (is (= 7 (get-in config [:retention :blob-days])))
    (is (= (* 24 60 60 1000) (get-in config [:retention :interval-ms])))
    (is (false? (get-in config [:retention :run-immediately?]))))
  (testing "a partial retention map keeps the other defaults"
    (let [config (config/normalize {:retention {:session-days 10}})]
      (is (= 10 (get-in config [:retention :session-days])))
      (is (= 7 (get-in config [:retention :blob-days])))
      (is (= (* 24 60 60 1000)
             (get-in config [:retention :interval-ms])))))
  (testing "invalid retention values are structured configuration errors"
    (doseq [[value pattern]
            [[{:session-days 0} #"retention session-days"]
             [{:session-days "30"} #"retention session-days"]
             [{:blob-days -1} #"retention blob-days"]
             [{:blob-days 1.5} #"retention blob-days"]
             [{:interval-ms 999} #"retention interval-ms"]
             [{:interval-ms 1000.5} #"retention interval-ms"]
             [{:run-immediately? "yes"} #"retention run-immediately\?"]
             [{:retention-days 30} #"unknown retention"]]]
      (let [error (try
                    (config/normalize {:retention value})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? error) (pr-str value))
        (is (re-find pattern (.getMessage ^Exception error)) (pr-str value))
        (is (= :config-invalid (:error/kind (ex-data error))) (pr-str value))
        (is (= :retention (first (:field (ex-data error)))) (pr-str value))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":retention must be a map"
                          (config/normalize {:retention "nope"})))))

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
