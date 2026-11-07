(ns hc.client.settings.connection-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.support :as support]))

(deftest url-parts-test
  (testing "scheme, userinfo, port, and IPv6 authority"
    (is (= {:scheme "https" :host "api.example.com" :port "8443"
            :authority "api.example.com:8443"}
           (connection/url-parts "https://user:pass@api.example.com:8443/v1")))
    (is (= {:scheme "http" :host "[::1]" :port "11434"
            :authority "[::1]:11434"}
           (connection/url-parts "http://[::1]:11434/v1")))
    (is (= {:scheme nil :host "localhost" :port "11434"
            :authority "localhost:11434"}
           (connection/url-parts "localhost:11434")))
    (is (= "未知主机" (connection/authority-from-url "")))
    (is (= "::1" (connection/host-from-url "http://[::1]:11434")))))

(deftest official-label-test
  (testing "an official host is official only when the actual host matches"
    (let [official (connection/normalize-connection
                    {:connection-id "a" :name "OpenAI"
                     :base-url "https://api.openai.com/v1"
                     :official? true
                     :official-hosts #{"openai.com"}})]
      (is (= "官方" (connection/official-label official)))
      (is (= "api.openai.com" (:actual-host official)))
      (is (nil? (:host-warning official)))))

  (testing "a familiar name on a custom gateway is not official"
    (let [custom (connection/normalize-connection
                  {:connection-id "b" :name "OpenAI"
                   :base-url "https://gateway.example.com/v1"
                   :official? true
                   :official-hosts #{"openai.com"}})]
      (is (= "自定义主机（与官方地址不符）"
             (connection/official-label custom)))
      (is (not (:official? custom)))
      (is (re-find #"不符" (:host-warning custom)))
      (is (= "gateway.example.com" (:actual-host custom)))))

  (testing "a server that claims official without a host list is not trusted"
    (let [unverified (connection/normalize-connection
                      {:connection-id "c" :name "Familiar"
                       :base-url "https://api.example.com"
                       :official? true})]
      (is (= "声明官方（地址未核对）"
             (connection/official-label unverified))))))

(deftest edited-base-url-recomputes-host-and-official-label-test
  (let [saved (connection/normalize-connection
               {:connection-id "a" :name "OpenAI"
                :base-url "https://api.openai.com/v1"
                :actual-host "api.openai.com"
                :official? true
                :official-hosts #{"openai.com"}})
        edited (connection/normalize-connection
                (assoc saved :base-url "https://gateway.example.com/v1"))]
    (is (= "gateway.example.com" (:actual-host edited))
        "the current base-url wins over a stale saved actual-host")
    (is (not (:official? edited)))
    (is (re-find #"不符" (:host-warning edited)))))

(deftest local-location-test
  (testing "local service location is stated, not inferred from the name"
    (let [instance (connection/normalize-connection
                    {:connection-id "l1" :name "Ollama"
                     :base-url "http://127.0.0.1:11434"
                     :service-kind :local-service
                     :location :instance})
          lan (connection/normalize-connection
               {:connection-id "l2" :name "Ollama"
                :base-url "http://192.168.1.20:11434"
                :service-kind :local-service
                :location :lan-device})
          remote (connection/normalize-connection
                  {:connection-id "l3" :name "Ollama"
                   :base-url "https://models.example.com"
                   :service-kind :local-service
                   :location :remote})]
      (is (re-find #"不出本机" (connection/location-note instance)))
      (is (re-find #"不能称为不出本机" (connection/location-note lan)))
      (is (re-find #"发送到该主机" (connection/location-note remote)))
      (is (= "局域网另一台设备" (connection/location-label lan))))))

(deftest key-status-test
  (let [cooldown (connection/normalize-key
                  {:key-id "k1" :status :cooldown} 0)
        auth-failed (connection/normalize-key
                     {:key-id "k2" :status :auth-failed} 1)]
    (is (= "限流冷却中" (connection/key-status-label cooldown)))
    (is (= "认证失败" (connection/key-status-label auth-failed)))
    (is (not= (connection/key-status-label cooldown)
              (connection/key-status-label auth-failed)))
    (is (re-find #"下次可尝试"
                 (connection/key-status-detail
                  (assoc cooldown :rate-limited-until "2026-11-06T00:00:00Z"))))
    (is (re-find #"同时存在认证失败"
                 (connection/key-status-detail
                  (assoc cooldown :auth-failed? true))))))

(deftest server-credential-vector-test
  (testing "the server's credential vector keeps every saved key"
    (let [conn (connection/normalize-connection
                {:connection-id "a" :name "A"
                 :base-url "https://api.example.com/v1"
                 :has-key? true
                 :credentials
                 [{:credential-id "k1" :alias "one" :enabled? true
                   :priority 1 :masked-key "sk-...1" :use-count 2}
                  {:credential-id "k2" :alias "two" :enabled? false
                   :priority 2 :masked-key "sk-...2"
                   :secret-ref "secret/k2"}]})
          draft (draft/draft-from-connection conn)
          payload (connection/draft-payload draft)]
      (is (= ["k1" "k2"] (mapv :key-id (:auth-keys conn))))
      (is (= ["one" "two"] (mapv :alias (:auth-keys conn))))
      (is (= [true false] (mapv :enabled? (:auth-keys conn))))
      (is (= ["k1" "k2"] (mapv :key-id (:keys draft))))
      (is (= #{"k1" "k2"} (set (keep :key-id (:keys payload)))))
      (is (= ["k1" "k2"] (mapv :auth-edit-id (:keys payload))))
      (is (every? #(= :unchanged (:auth-state %)) (:keys payload)))
      (is (empty? (:clear-credentials payload))
          "unchanged saved keys must not be sent as clears")
      (is (not-any? #(contains? % :secret-ref)
                    (:credentials (:saved-connection conn)))
          "client-safe must strip secret refs from a credential vector"))))

(deftest config-digest-test
  (testing "base URL canonicalization and auth revisions are part of the digest"
    (let [a (support/connection "a" "A" "https://api.example.com/v1/")
          b (support/connection "a" "A" "https://api.example.com/v1")
          c (support/connection "a" "A" "https://api.example.com/v1"
                                {:auth-keys [{:key-id "a-key" :alias "主密钥"
                                              :has-key? true
                                              :auth-revision "different"}]})]
      (is (= (connection/connection-digest
              (connection/normalize-connection a))
             (connection/connection-digest
              (connection/normalize-connection b))))
      (is (not= (connection/connection-digest
                 (connection/normalize-connection a))
                (connection/connection-digest
                 (connection/normalize-connection c))))))

  (testing "a typed secret travels only in the credential slot"
    (let [base (draft/draft-from-connection
                (connection/normalize-connection
                 (support/connection "a" "A" "https://api.example.com/v1")))
          unchanged (connection/draft-payload base)
          changed-draft (-> base
                            (draft/edit-key-secret "a-key" "secret-value"
                                                   {:auth-edit-id "edit-1"}))
          changed (connection/draft-payload changed-draft)
          redacted (connection/redact-payload changed)]
      (is (not (contains? (first (:keys unchanged)) :secret)))
      (is (not (contains? (first (:keys changed)) :secret)))
      (is (= :set (:auth-state (first (:keys changed)))))
      (is (= "edit-1" (:auth-edit-id (first (:keys changed)))))
      (is (= "secret-value" (:secret (first (:credentials changed)))))
      (is (= "***" (:secret (first (:credentials redacted)))))
      (is (not (re-find #"secret-value" (pr-str (:fields changed)))))
      (is (not= (:config-digest unchanged) (:config-digest changed))))))

(deftest explicit-empty-enabled-set-is-not-missing-test
  (let [conn (connection/normalize-connection
              {:connection-id "a" :name "A"
               :base-url "https://api.example.com/v1"
               :enabled-models []
               :models [{:id "m" :enabled? true}]})]
    (is (empty? (:enabled-models conn)))))

(deftest draft-validation-test
  (let [invalid (draft/blank-draft :cloud-key
                                   {:draft-id "d" :key-id "k" :auth-edit-id "e"})
        errors (connection/draft-validation-errors invalid)]
    (is (some #(= [:fields :name] (:field %)) errors))
    (is (some #(= [:fields :base-url] (:field %)) errors)))

  (let [local (-> (draft/blank-draft :local-service
                                     {:draft-id "d" :key-id "k" :auth-edit-id "e"})
                  (draft/edit-field :name "Local")
                  (draft/edit-field :base-url "http://127.0.0.1:11434")
                  (draft/edit-field :location :unknown))
        errors (connection/draft-validation-errors local)]
    (is (some #(= [:fields :location] (:field %)) errors))
    (let [located (draft/edit-field local :location :instance {})]
      (is (empty? (connection/draft-validation-errors located)))))

  (testing "a cloud-key connection needs a usable secret"
    (let [base (-> (draft/blank-draft :cloud-key
                                      {:draft-id "d" :key-id "k"
                                       :auth-edit-id "e"})
                   (draft/edit-field :name "A")
                   (draft/edit-field :base-url "https://api.example.com/v1"))
          missing (connection/draft-validation-errors base)
          filled (draft/edit-key-secret base "k" "raw-secret"
                                        {:auth-edit-id "e2"})]
      (is (some #(= [:keys] (:field %)) missing))
      (is (empty? (connection/draft-validation-errors filled)))))

  (testing "local-service and account-login do not require a key"
    (let [local (-> (draft/blank-draft :local-service
                                       {:draft-id "l"})
                    (draft/edit-field :name "Local")
                    (draft/edit-field :base-url "http://127.0.0.1:11434")
                    (draft/edit-field :location :instance))
          account (-> (draft/blank-draft :account-login
                                         {:draft-id "o"})
                      (draft/edit-field :name "Account")
                      (draft/edit-field :base-url
                                        "https://auth.example.com/v1"))]
      (is (empty? (connection/draft-validation-errors local)))
      (is (empty? (connection/draft-validation-errors account)))))

  (testing "model detail values are validated"
    (let [base (-> (draft/blank-draft :cloud-key
                                      {:draft-id "m" :key-id "k"
                                       :auth-edit-id "e"})
                   (draft/edit-field :name "A")
                   (draft/edit-field :base-url "https://api.example.com/v1")
                   (draft/edit-key-secret "k" "raw" {:auth-edit-id "e2"})
                   (draft/add-manual-model "model-a" {}))
          bad-top-p (draft/edit-model-field base "model-a" :top-p "2" {})
          bad-price (draft/edit-model-field base "model-a"
                                            :pricing-input "1" {})]
      (is (some #(= [:models 0 :top-p] (:field %))
                (connection/draft-validation-errors bad-top-p)))
      (is (some #(= [:models 0 :pricing] (:field %))
                (connection/draft-validation-errors bad-price))))))
