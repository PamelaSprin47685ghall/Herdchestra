(ns hc.server.app.startup-identity-test
  "Startup identity must name the exact tree and survive a non-git directory.

  Root AGENTS.md §8.6#2: the process prints its git hash and dirty flag on
  start, and `/healthz` carries the same identity.  The version comes from
  `hc.version/build-identity`, not from a second literal in the printer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app :as app]
            [hc.server.app.health :as health]
            [hc.version :as version]))

(def ^:private identity-line
  (var-get #'app/identity-line))

(defn- version-pattern
  "The identity line for the version this tree actually declares.

  Pinning a literal here turns every release into a test edit and hides the
  real assertion (the printer uses `build-identity`) behind a number."
  []
  (re-pattern
   (str "^Herdchestra "
        (java.util.regex.Pattern/quote version/version)
        " git=([0-9a-f]{7,40}|unknown) dirty=(true|false)$")))

(defn- capture-version
  []
  (let [code (atom nil)
        output (with-out-str
                 (reset! code (app/run-main! ["--version"])))]
    {:code @code :output output :line (str/trim output)}))

(deftest startup-identity-is-observable
  (testing "--version prints the exact startup identity shape"
    (let [{:keys [code output line]} (capture-version)]
      (is (= 0 code))
      (is (str/includes? output "git="))
      (is (str/includes? output "dirty="))
      (is (re-matches (version-pattern) line)
          (str "unexpected identity line: " (pr-str line)))
      (is (not (str/includes? output "nil")))))

  (testing "the private identity-line has the same git/dirty fields"
    (let [line (identity-line)]
      (is (string? line))
      (is (str/includes? line "git="))
      (is (str/includes? line "dirty="))
      (is (not (str/includes? line "nil")))))

  (testing "health exposes the build identity"
    (let [snapshot (health/snapshot {})]
      (is (string? (:git-hash snapshot)))
      (is (not (str/blank? (:git-hash snapshot))))
      (is (boolean? (:git-dirty? snapshot))))
    (testing "a non-git tree passes through unknown/false"
      (with-redefs [version/build-identity
                    (fn [] {:version version/version
                            :git-hash "unknown"
                            :git-dirty? false})]
        (let [snapshot (health/snapshot {})]
          (is (= "unknown" (:git-hash snapshot)))
          (is (false? (:git-dirty? snapshot)))))))

  (testing "the version comes from build-identity, not a printer literal"
    (is (re-matches #"\d+\.\d+\.\d+" version/version)
        "the declared version is a plain semver triple")
    (let [code (atom nil)
          output (with-out-str
                   (with-redefs [version/build-identity
                                 (fn [] {:version "9.9.9"
                                         :git-hash "abc1234"
                                         :git-dirty? true})]
                     (reset! code (app/run-main! ["--version"]))))]
      (is (= 0 @code))
      (is (str/includes? output "Herdchestra 9.9.9"))
      (is (str/includes? output "git=abc1234"))
      (is (str/includes? output "dirty=true"))))

  (testing "nil never appears as an identity value"
    (let [real (identity-line)
          stubbed (with-redefs [version/build-identity
                                (fn [] {:version "0.0.3"
                                        :git-hash "unknown"
                                        :git-dirty? false})]
                    (identity-line))]
      (doseq [line [real stubbed]]
        (is (not (str/includes? line "nil")))
        (is (not (str/includes? line "git=nil")))
        (is (not (str/includes? line "dirty=nil")))))))
