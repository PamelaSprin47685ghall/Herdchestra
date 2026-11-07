(ns hc.server.diagnostics.bundle-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.bundle :as bundle]))

(def openai-key "sk-1234567890abcdefghij")

(def base
  {:app-version "0.0.1"
   :platform "linux"
   :os-version "7.3"
   :generated-at "2026-11-03T12:00:00Z"
   :time-range {:from "2026-11-01" :to "2026-11-03" :zone "UTC"}
   :error-category :network-unreachable
   :error-detail "connection refused"
   :services [{:connection-id "c1"
               :display-name "OpenAI"
               :protocol "https"
               :actual-host "api.example.com"}]})

(defn- line-ids
  [preview]
  (mapcat (fn [section] (map :id (:lines section))) (:sections preview)))

(deftest default-preview-excludes-private-content
  (let [preview (bundle/prepare
                 (merge base
                        {:chat "private chat"
                         :attachments [{:name "secret.pdf"}]
                         :request-body {:prompt "private prompt"}}))]
    (is (= [:app :time-range :error :services :redaction]
           (mapv :id (:sections preview))))
    (is (= [:chat :attachments :request-body] (:ignored-fields preview)))
    (is (some #{"chat body" "attachments" "secrets" "private paths"
                "full request"}
              (:excluded preview)))
    (is (not (str/includes? (pr-str preview) "private chat")))
    (is (not (str/includes? (pr-str preview) "private prompt")))
    (is (true? (:review-required? preview)))))

(deftest forbidden-material-is-rejected-even-when-selected
  (let [preview (bundle/prepare
                 (merge base
                        {:extra-materials
                         [{:id :chat
                           :title "Chat"
                           :purpose "none"
                           :kind :chat-body
                           :include? true
                           :content "private chat"}]}))]
    (is (= [{:id :chat :title "Chat" :kind :chat-body}]
           (:rejected-materials preview)))
    (is (false? (get-in preview [:extra-materials 0 :included?])))
    (is (= :forbidden-kind (get-in preview [:extra-materials 0
                                            :rejected-reason])))
    (is (not (str/includes? (pr-str preview) "private chat")))
    (is (not (some #{:extra-materials} (mapv :id (:sections preview)))))))

(deftest extra-material-requires-explicit-selection-and-purpose
  (let [material {:id :log
                  :title "Request log"
                  :purpose "show the failing request metadata"
                  :kind :log
                  :content {:api-key openai-key
                            :request-id "r1"}}
        off (bundle/prepare (merge base {:extra-materials
                                        [(assoc material :include? false)]}))
        on (bundle/prepare (merge base {:extra-materials
                                       [(assoc material :include? true)]}))
        no-purpose (bundle/prepare
                    (merge base {:extra-materials
                                 [(assoc material
                                         :include? true
                                         :purpose "")]}))]
    (is (not (some #{:extra-materials} (mapv :id (:sections off)))))
    (is (some #{:extra-materials} (mapv :id (:sections on))))
    (is (not (str/includes? (pr-str on) openai-key)))
    (is (= :purpose-missing
           (get-in no-purpose [:extra-materials 0 :rejected-reason])))
    (is (not (some #{:extra-materials} (mapv :id (:sections no-purpose)))))))

(deftest service-hosts-can-be-masked
  (let [preview (bundle/prepare (assoc base :mask-hosts? true))]
    (is (not (str/includes? (pr-str preview) "api.example.com")))
    (is (str/includes? (pr-str preview) "<redacted:host:"))
    (is (some #(= :host (:category %)) (get-in preview [:redaction :findings])))))

(deftest preview-lines-can-be-removed
  (let [preview (bundle/prepare base)
        ids (set (line-ids preview))
        removed (bundle/remove-lines preview ["app-version" "error-raw"])
        remaining (set (line-ids removed))]
    (is (contains? ids "app-version"))
    (is (contains? ids "error-raw"))
    (is (not (contains? remaining "app-version")))
    (is (not (contains? remaining "error-raw")))
    (is (contains? remaining "app-platform"))
    (is (contains? (:removed-line-ids removed) "app-version"))))

(deftest removed-lines-do-not-survive-in-export
  (let [preview (bundle/prepare base)
        removed (bundle/remove-lines preview ["app-version" "service-0"])
        exported (bundle/export-view removed)]
    (is (not (str/includes? (pr-str exported) "0.0.1")))
    (is (not (str/includes? (pr-str exported) "api.example.com")))
    (is (= "linux" (get-in exported [:content :app :platform])))
    (is (empty? (get-in exported [:content :services])))))

(deftest nickname-rename-runs-over-preview-content
  (let [preview (bundle/prepare
                 (merge base
                        {:error-detail "Alice could not connect"
                         :include-error-detail? true
                         :rename {"Alice" "User A"}}))]
    (is (not (str/includes? (pr-str preview) "Alice")))
    (is (str/includes? (pr-str preview) "User A"))))

(deftest hide-hosts-after-preparation
  (let [preview (bundle/prepare base)
        hidden (bundle/hide-hosts preview)]
    (is (str/includes? (pr-str preview) "api.example.com"))
    (is (not (str/includes? (pr-str hidden) "api.example.com")))
    (is (str/includes? (pr-str hidden) "<redacted:host:"))))

(deftest bundle-is-local-only
  (let [preview (bundle/prepare base)
        artifact (bundle/local-artifact preview)]
    (is (= :local-only (get-in preview [:transfer :mode])))
    (is (false? (get-in preview [:transfer :automatic-upload?])))
    (is (false? (get-in preview [:transfer :network?])))
    (is (true? (get-in preview [:transfer :requires-user-action?])))
    (is (false? (:uploaded? artifact)))
    (is (false? (:automatic-upload? artifact)))
    (is (= :user-selected (:destination artifact)))
    (is (not (contains? artifact :url)))
    (is (not (str/includes? (str artifact) "http://")))
    (is (not (str/includes? (str artifact) "https://")))))

(deftest streaming-artifact-matches-the-string-artifact
  (let [preview (bundle/prepare base)
        out (java.io.ByteArrayOutputStream.)
        streamed (bundle/write-local-artifact! preview out)
        stringed (bundle/local-artifact preview)]
    (is (= (:filename stringed) (:filename streamed)))
    (is (= (edn/read-string (:content stringed))
           (edn/read-string
            (String. (.toByteArray out)
                     java.nio.charset.StandardCharsets/UTF_8))))
    (is (= (alength (.toByteArray out)) (:size streamed)))
    (is (false? (:uploaded? streamed)))
    (is (false? (:automatic-upload? streamed)))))

(deftest unclassified-error-does-not-invent-a-cause
  (let [preview (bundle/prepare
                 (merge base
                        {:error-category :something-new
                         :reported-category :something-new}))]
    (is (= :unclassified (get-in preview [:content :error :category])))
    (is (= :something-new
           (get-in preview [:content :error :reported-category])))
    (is (= "Specific cause could not be determined"
           (get-in preview [:content :error :label])))))

(deftest raw-provider-error-is-opt-in-and-redacted
  (let [off (bundle/prepare (merge base
                                   {:raw-error (str "token=" openai-key)}))
        on (bundle/prepare (merge base
                                  {:raw-error (str "token=" openai-key)
                                   :include-raw-error? true}))
        off-line (some #(when (= "error-raw" (:id %)) %)
                       (mapcat :lines (:sections off)))
        on-line (some #(when (= "error-raw" (:id %)) %)
                      (mapcat :lines (:sections on)))]
    (is (= "not included" (:value off-line)))
    (is (not (str/includes? (pr-str off) openai-key)))
    (is (not (str/includes? (pr-str on-line) openai-key)))
    (is (str/includes? (pr-str on-line) "redacted"))))

(deftest same-secret-gets-one-placeholder-across-sections
  (let [preview (bundle/prepare
                 (merge base
                        {:error-detail (str "key " openai-key)
                         :include-error-detail? true
                         :services [{:connection-id "c1"
                                     :display-name "OpenAI"
                                     :protocol "https"
                                     :account-label openai-key}]}))
        placeholders (set (map :placeholder
                               (get-in preview [:redaction :findings])))]
    (is (= 1 (count placeholders)))
    (is (not (str/includes? (pr-str preview) openai-key)))))

(deftest error-detail-is-opt-in
  (let [off (bundle/prepare (assoc base :error-detail "Alice failed"))
        on (bundle/prepare (assoc base
                                  :error-detail "Alice failed"
                                  :include-error-detail? true))]
    (is (not (str/includes? (pr-str off) "Alice failed")))
    (is (str/includes? (pr-str on) "Alice failed"))))
