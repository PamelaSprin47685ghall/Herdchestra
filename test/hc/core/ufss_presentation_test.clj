(ns hc.core.ufss-presentation-test
  "Outbound presentation smoke tests for AGENTS.md §6.3.

  These do not replace the app integration tests.  They pin the UFSS
  presentation contract that the request path must use: arbitrary user/tool
  text, Windows paths, multiline sources, search context, board snapshots,
  and tool-argument inputs.  The same maps are parsed back to prove the
  rendering is not merely pretty."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.core.ufss :as ufss]))

(defn- no-escape-noise?
  "True when TEXT contains no backslash-escaped quote, newline, or backslash.
  Test data deliberately avoids literal two-backslash sequences."
  [^String text]
  (and (not (str/includes? text "\\\""))
       (not (str/includes? text "\\n"))
       (not (str/includes? text "\\\\"))))

(deftest arbitrary-text-receipt-presentation
  (testing "a parked receipt with quoted source, Windows paths, and command output"
    (let [receipt {:status :PARKED
                   :name "fix-timeout"
                   :path "C:\\workspace\\src\\config.clj"
                   :source (str "1 | (defprogram fix-timeout []\n"
                                "2 |   (write {:path \"src/config.clj\"\n"
                                "3 |           :content \"a\\b\"}))")
                   :payload {:needle "(def timeout-ms 30000)"
                             :candidates []
                             :nearest {:span [812 835]
                                       :text "(def timeout-ms 30000) ; default"
                                       :distance 10}}
                   :command {:stdout (str "line 1\nline \"2\"\nC:\\tmp\\x")
                             :stderr ""}
                   :remaining-wait-ms 4200}
          text (ufss/write-str receipt)]
      (is (= receipt (ufss/read-str text)))
      (is (no-escape-noise? text))
      (is (str/includes? text "#\"C:\\workspace\\src\\config.clj\"#"))
      (is (not (str/includes? text "C:\\\\workspace"))))))

(deftest search-context-presentation
  (testing "the web-search system injection shape"
    (let [context {:query "a\"b\\c\nd"
                   :results [{:title "x\"y"
                              :snippet "line1\nline2"
                              :url "https://example.com/a\\b"}]
                   :fetched-at 1700000000000}
          text (ufss/write-str context)]
      (is (= context (ufss/read-str text)))
      (is (no-escape-noise? text)))))

(deftest board-snapshot-presentation
  (testing "task titles and actor notes with arbitrary text"
    (let [board {:board-version 7
                 :tasks [{:task-id "t1"
                          :title "fix \"timeout\"\nnow"
                          :state :ready}
                         {:task-id "t2"
                          :title "path C:\\tmp\\a"
                          :state :waiting}]
                 :actors [{:agent-id "a1"
                           :note "line1\nline2"}]}
          text (ufss/write-str board)]
      (is (= board (ufss/read-str text)))
      (is (no-escape-noise? text)))))

(deftest pr-str-is-not-the-presentation-contract
  (testing "the legacy pr-str form and the UFSS form differ exactly on escapes"
    (let [value {:text "a\"b\nc"}
          legacy (pr-str value)
          text (ufss/write-str value)]
      (is (str/includes? legacy "\\\""))
      (is (str/includes? legacy "\\n"))
      (is (not (str/includes? text "\\\"")))
      (is (not (str/includes? text "\\n")))
      (is (= value (ufss/read-str text))))))

(deftest tool-argument-input-normalization
  (testing "JSON, EDN, and UFSS tool-argument strings all normalize"
    (is (= {"path" "src/a.clj" "content" "a\nb"}
           (ufss/read-input
            "{\"path\": \"src/a.clj\", \"content\": \"a\\nb\"}")))
    (is (= {:path "src/a.clj" :content "a\nb"}
           (ufss/read-input
            (str "{:path \"src/a.clj\" :content #\"a" "\n" "b\"#}"))))
    (is (= {"path" "src/a.clj" "content" "a\nb"}
           (ufss/read-input
            (str "{\"path\": \"src/a.clj\", \"content\": #\"a" "\n" "b\"#}"))))))

(deftest tool-call-and-result-message-presentation
  (testing "assistant tool-call arguments and tool result text survive UFSS"
    (let [tool-call {:role :assistant
                     :tool-calls [{:id "tool-1"
                                   :name "shell"
                                   :arguments {:cmd "echo \"hi\""
                                               :path "C:\\tmp\\a b"}}]}
          tool-result {:role :tool
                       :tool-call-id "tool-1"
                       :name "shell"
                       :error? false
                       :text (ufss/write-str {:status :succeeded
                                              :stdout "line1\nline2"
                                              :path "C:\\tmp\\a b"})}
          call-text (ufss/write-str tool-call)
          result-text (ufss/write-str tool-result)]
      (is (= tool-call (ufss/read-str call-text)))
      (is (= tool-result (ufss/read-str result-text)))
      (is (no-escape-noise? call-text))
      (is (no-escape-noise? result-text))
      (is (= {:status :succeeded
              :stdout "line1\nline2"
              :path "C:\\tmp\\a b"}
             (ufss/read-str (:text tool-result)))))))
