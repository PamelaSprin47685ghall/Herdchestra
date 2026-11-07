(ns hc.server.diagnostics.redact-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.redact :as redact]))

(def openai-key "sk-1234567890abcdefghij")
(def other-key "sk-zyxwvutsrqponmlkjihg")

(deftest credential-fields-keep-their-names
  (let [result (redact/redact {:headers {"Authorization" "Bearer abcdefghijklmnop"
                                         "X-Api-Key" openai-key}
                               :password "hunter2"})
        value (:value result)]
    (is (= "<redacted:secret:1>"
           (get-in value [:headers "Authorization"])))
    (is (= "<redacted:secret:2>" (get-in value [:headers "X-Api-Key"])))
    (is (= "<redacted:secret:3>" (:password value)))
    (is (contains? (:headers value) "Authorization"))
    (is (contains? value :password))
    (is (= 3 (count (:findings result))))))

(deftest same-secret-gets-one-placeholder
  (let [result (redact/redact {:a openai-key :b openai-key :c other-key})
        value (:value result)]
    (is (= (:a value) (:b value)))
    (is (not= (:a value) (:c value)))
    (is (= 2 (count (distinct [(:a value) (:b value) (:c value)]))))))

(deftest token-statistics-are-not-secrets
  (let [usage {:input-tokens 1000
               :output-tokens 100
               :cache-read-tokens 200
               :cache-write-tokens 0
               :max-tokens 2000
               :token-count 3
               :total-tokens 1100}
        result (redact/redact usage)]
    (is (= usage (:value result)))
    (is (false? (:redacted? result)))
    (is (not (redact/sensitive-key? :input-tokens)))
    (is (not (redact/sensitive-key? :max-tokens)))
    (is (not (redact/sensitive-key? :token-count)))
    (is (redact/sensitive-key? :authorization))
    (is (redact/sensitive-key? :x-api-key))
    (is (redact/sensitive-key? :client-secret))
    (is (redact/sensitive-key? :refresh-token))
    (is (not (redact/sensitive-key? :auth-ref)))))

(deftest free-text-credential-patterns-are-redacted
  (let [text (str "Bearer abcdefghijklmnop "
                  openai-key " "
                  "ghp_1234567890abcdefghijklmn "
                  "AKIAABCDEFGHIJKLMNOP "
                  "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature1234567890 "
                  "-----BEGIN RSA PRIVATE KEY-----\nMIIEsecret\n-----END RSA PRIVATE KEY-----")
        result (redact/redact text)
        value (:value result)]
    (is (not (str/includes? value "Bearer abcdefghijklmnop")))
    (is (not (str/includes? value openai-key)))
    (is (not (str/includes? value "ghp_1234567890abcdefghijklmn")))
    (is (not (str/includes? value "AKIAABCDEFGHIJKLMNOP")))
    (is (not (str/includes? value "eyJhbGciOiJIUzI1NiJ9")))
    (is (not (str/includes? value "MIIEsecret")))
    (is (str/includes? value "<redacted:secret:"))
    (is (>= (count (:findings result)) 6))))

(deftest url-credentials-are-redacted
  (let [value "https://alice:secretpass@api.example.com/v1"
        plain (redact/redact value)
        masked (redact/redact value {:mask-hosts? true})]
    (is (not (str/includes? (:value plain) "secretpass")))
    (is (str/includes? (:value plain) "api.example.com"))
    (is (not (str/includes? (:value masked) "api.example.com")))
    (is (str/includes? (:value masked) "<redacted:host:"))))

(deftest rename-runs-before-redaction
  (let [result (redact/redact {:name "Alice"
                               :note "Alice shared a key: sk-1234567890abcdefghij"}
                              {:rename {"Alice" "User A"}})
        value (:value result)]
    (is (= "User A" (:name value)))
    (is (not (str/includes? (:note value) "Alice")))
    (is (not (str/includes? (:note value) openai-key)))
    (is (str/includes? (:note value) "User A"))))

(deftest rename-does-not-disable-field-redaction
  (let [result (redact/redact {:password "hunter2"}
                              {:rename {"password" "pw"}})
        value (:value result)]
    (is (contains? value :pw))
    (is (not (str/includes? (pr-str value) "hunter2")))
    (is (str/includes? (pr-str value) "redacted"))))

(deftest hosts-and-paths-can-be-hidden
  (let [result (redact/redact {:base-url "https://api.example.com/v1"
                               :actual-host "api.example.com"
                               :path "/home/alice/project/src"
                               :note "read /home/alice/project/README.md"}
                              {:mask-hosts? true :mask-paths? true})
        value (:value result)]
    (is (str/starts-with? (:base-url value) "https://"))
    (is (str/includes? (:base-url value) "<redacted:host:"))
    (is (not (str/includes? (:base-url value) "api.example.com")))
    (is (str/includes? (:actual-host value) "<redacted:host:"))
    (is (str/includes? (:path value) "<redacted:path:"))
    (is (not (str/includes? (:note value) "/home/alice")))
    (is (str/includes? (:note value) "<redacted:path:"))))

(deftest redaction-is-idempotent
  (let [once (redact/redact {:api-key openai-key
                             :url "https://api.example.com/v1"}
                            {:mask-hosts? true})
        twice (redact/redact (:value once) {:mask-hosts? true})]
    (is (= (:value once) (:value twice)))
    (is (empty? (:findings twice)))))

(deftest findings-never-carry-the-secret
  (let [result (redact/redact {:api-key openai-key :password "hunter2"})
        printed (pr-str result)]
    (is (not (str/includes? printed openai-key)))
    (is (not (str/includes? printed "hunter2")))
    (is (every? (fn [finding]
                  (and (vector? (:path finding))
                       (keyword? (:kind finding))
                       (string? (:placeholder finding))))
                (:findings result)))))

(deftest placeholder-floor-continues-after-existing-placeholders
  (is (= 4 (redact/placeholder-floor {:a "<redacted:secret:4>"
                                      :b "<redacted:host:2>"}
                                     :secret)))
  (is (= 2 (redact/placeholder-floor "<redacted:host:2>" :host))))

(deftest credential-map-keeps-field-names
  (let [result (redact/redact {:credentials {:username "alice"
                                             :password "hunter2"}})
        credentials (get-in result [:value :credentials])]
    (is (contains? credentials :username))
    (is (contains? credentials :password))
    (is (not (str/includes? (pr-str credentials) "hunter2")))
    (is (str/includes? (pr-str credentials) "redacted"))))

(deftest credential-vector-redacts-every-leaf
  (let [result (redact/redact {:api-key ["hunter2" "plain-value"]})
        printed (pr-str result)]
    (is (not (str/includes? printed "hunter2")))
    (is (not (str/includes? printed "plain-value")))
    (is (str/includes? printed "redacted"))))

(deftest non-symbol-map-keys-do-not-break-redaction
  (is (= {1 "x" 2 {:nested 3}}
         (:value (redact/redact {1 "x" 2 {:nested 3}})))))
