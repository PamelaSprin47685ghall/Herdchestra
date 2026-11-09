(ns hc.compose.mission.ufss-boundary-test
  "UFSS boundary guards for the board work prompt and devops receipts.

  The prompt and every tool receipt must stay one EDN+UFSS value even when
  task goals, paths, file contents, or errors contain reserve sequences,
  quotes, backslashes, tabs, or newlines."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission.actors :as actors]
            [hc.compose.mission.devops :as devops]
            [hc.core.text-location :as text-location]
            [hc.core.ufss :as ufss]
            [hc.server.app.devops :as app-devops]
            [hc.server.files :as files])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- utf8-byte-length
  ^long [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- ready-task
  [task-id goal]
  {:task-id task-id
   :state :ready
   :goal goal
   :remaining-work goal
   :done-when goal
   :owner-id nil
   :needs-token false
   :parent-id nil
   :wait-for #{}
   :child-ids []
   :result-refs []})

(defn- mission-with-tasks
  [tasks]
  {:boards {"tenure-ufss"
            {:tasks (into {} (map (fn [task] [(:task-id task) task]) tasks))
             :actors {}
             :actions {}
             :inputs {}
             :token-slot {:holder-id nil}
             :derived {}}}})

(defn- mission-with-goal
  "Minimal mission state with one public ready task.

  `board-work-prompt` needs only the task map and the public board fields;
  this avoids a full Plan/Swarm handshake for a renderer boundary test."
  [goal]
  (mission-with-tasks [(ready-task :task-ufss goal)]))

(defn- prompt-text
  [goal]
  (:text (last (actors/board-work-prompt (mission-with-goal goal)
                                         "tenure-ufss"
                                         :worker-ufss))))

(defn- board-block
  [text]
  (let [block (second
               (re-find
                #"(?s)\nPublic board \(EDN\+UFSS\):\n(.*)\nDecide your next legal action from this board\."
                text))]
    (when-not block
      (throw (ex-info "board-work-prompt has no EDN+UFSS block" {:text text})))
    block))

(defn- parse-single
  [text]
  (is (string? text))
  (when (string? text)
    (let [values (ufss/read-all-str text)]
      (is (= 1 (count values)) "the receipt must be exactly one value")
      (first values))))

(defn- scripted-model
  "Model stub that delivers one prepared event vector per start."
  [scripts]
  (let [starts (atom 0)]
    {:start! (fn [request deliver!]
               (let [index (swap! starts inc)
                     events (nth scripts (dec index) [])]
                 (future
                   (doseq [event events]
                     (deliver! (assoc event
                                      :attempt-id (:attempt-id request)
                                      :call-id (:call-id request)))))
                 {:attempt-id (:attempt-id request)
                  :call-id (:call-id request)}))
     :cancel! (fn [_] nil)}))

;; ---------------------------------------------------------------------------
;; 1. Task goals with UFSS reserve sequences

(def dangerous-fragments
  "Every fragment is deliberately hostile to a text serializer."
  ["#\""          ; #"
   "#\"\"\""      ; #"""
   "#\"\"\"#"     ; #"""#
   "\"#"          ; "#
   "\\"           ; backslash
   "\n"           ; newline
   "\t"           ; tab
   "“引号”"        ; Chinese quotes
   "\"\"\"\""     ; four consecutive quotes
   "plain"
   "中文"
   "42"
   " "])

(def dangerous-goal
  (str/join " | " dangerous-fragments))

(deftest task-goal-with-reserve-sequences-round-trips
  (let [text (prompt-text dangerous-goal)
        value (parse-single (board-block text))]
    (is (string? text))
    (is (map? value))
    (is (= dangerous-goal (get-in value [:ready-tasks 0 :goal])))
    (is (not (str/includes? text "\\\""))
        "the prompt must not contain a backslash-quote escape")
    (is (not (str/includes? text "\\n"))
        "the prompt must not contain a backslash-n escape")))

;; ---------------------------------------------------------------------------
;; 2. Per-field and whole-block byte caps

(deftest hundred-kilobyte-goal-is-truncated-with-a-marker
  (let [goal (apply str (repeat (* 100 1024) "x"))
        text (prompt-text goal)
        value (parse-single (board-block text))
        task (first (:ready-tasks value))
        rendered (:goal task)]
    (is (string? text))
    (is (<= (utf8-byte-length rendered) 4096))
    (is (= 4096 (utf8-byte-length rendered)))
    (is (true? (:truncated? task)))
    (is (= (* 100 1024) (:full-bytes task)))
    (is (not (contains? value :board-truncated?)))))

(deftest single-field-5000-bytes-is-truncated
  (let [goal (apply str (repeat 5000 "g"))
        text (prompt-text goal)
        value (parse-single (board-block text))
        task (first (:ready-tasks value))]
    (is (<= (utf8-byte-length (:goal task)) 4096))
    (is (= 4096 (utf8-byte-length (:goal task))))
    (is (true? (:truncated? task)))
    (is (= 5000 (:full-bytes task)))
    (is (not (contains? value :board-truncated?)))))

(deftest field-byte-limit-boundary-is-inclusive
  (let [at-limit (apply str (repeat 4096 "a"))
        over-limit (apply str (repeat 4097 "a"))
        at-task (-> (prompt-text at-limit)
                    board-block
                    parse-single
                    :ready-tasks
                    first)
        over-task (-> (prompt-text over-limit)
                      board-block
                      parse-single
                      :ready-tasks
                      first)]
    (is (= at-limit (:goal at-task)))
    (is (not (contains? at-task :truncated?)))
    (is (not (contains? at-task :full-bytes)))
    (is (true? (:truncated? over-task)))
    (is (= 4097 (:full-bytes over-task)))
    (is (= 4096 (utf8-byte-length (:goal over-task))))))

(deftest surrogate-pair-truncation-keeps-valid-utf8
  (let [goal (apply str (repeat 1025 "😀"))
        text (prompt-text goal)
        value (parse-single (board-block text))
        task (first (:ready-tasks value))
        rendered (:goal task)
        bytes (.getBytes ^String rendered "UTF-8")]
    (is (true? (:truncated? task)))
    (is (= 4100 (:full-bytes task)))
    (is (= 4096 (utf8-byte-length rendered)))
    (is (= 2048 (count (seq rendered)))
        "each 4-byte emoji is two UTF-16 code units")
    (is (= rendered (String. bytes "UTF-8"))
        "the truncated string stays valid UTF-8 with no isolated surrogate")))

(deftest board-block-cap-omits-tail-tasks
  (let [goal (apply str (repeat 2048 "g"))
        tasks (mapv (fn [n]
                      (ready-task (keyword (format "task-%02d" n)) goal))
                    (range 1 21))
        mission (mission-with-tasks tasks)
        text (:text (last (actors/board-work-prompt mission
                                                    "tenure-ufss"
                                                    :worker-ufss)))
        block (board-block text)
        value (parse-single block)
        omitted (:omitted-task-count value)]
    (is (<= (utf8-byte-length block) 32768))
    (is (true? (:board-truncated? value)))
    (is (pos? omitted))
    (is (<= (count (:omitted-task-ids value)) 20))
    (is (= (count (:omitted-task-ids value)) (min 20 omitted)))
    (is (< (count (:ready-tasks value)) 20)
        "some tail tasks are omitted from the projection")
    (is (every? #(not (contains? % :truncated?)) (:ready-tasks value))
        "the remaining 2 KB goals are below the per-field cap")))

(def small-block-baseline
  "{:tenure-id \"tenure-ufss\" :agent-id :worker-ufss :responsibility [:task-owned] :global-progressable-reasons [\"broken-wait\" \"ready-task\"] :ready-tasks [{:task-id :task-ready :state :ready :goal \"small goal\" :ledger-key \"ledger-ready\"}] :waiting-tasks [{:task-id :task-waiting :state :waiting :goal \"waiting goal\" :needs-token true :ledger-key \"ledger-waiting\"}]}")

(deftest small-task-block-matches-pre-change-baseline
  (let [mission {:boards
                 {"tenure-ufss"
                  {:tasks
                   {:task-ready {:task-id :task-ready
                                 :state :ready
                                 :goal "small goal"
                                 :ledger-key "ledger-ready"
                                 :owner-id nil
                                 :needs-token false}
                    :task-waiting {:task-id :task-waiting
                                   :state :waiting
                                   :goal "waiting goal"
                                   :ledger-key "ledger-waiting"
                                   :owner-id nil
                                   :needs-token true
                                   :wait-for #{}}
                    :task-owned {:task-id :task-owned
                                 :state :ready
                                 :goal "owned goal"
                                 :owner-id :worker-ufss
                                 :needs-token false}}
                   :actors {}
                   :actions {}
                   :inputs {}
                   :token-slot {:holder-id nil}
                   :derived {}}}}
        text (:text (last (actors/board-work-prompt mission
                                                    "tenure-ufss"
                                                    :worker-ufss)))]
    (is (= small-block-baseline (board-block text)))))

(deftest snapshot-truncates-every-declared-field
  (let [full (apply str (repeat 5000 "f"))
        task {:task-id :task-fields
              :state :ready
              :goal full
              :remaining-work full
              :done-when full
              :handoff-note full
              :owner-id nil
              :needs-token false}
        mission (mission-with-tasks [task])
        snapshot (actors/public-board-snapshot mission "tenure-ufss")
        view (get-in snapshot [:tasks :task-fields])]
    (doseq [field [:goal :remaining-work :done-when :handoff-note]]
      (is (<= (utf8-byte-length (get view field)) 4096))
      (is (= 4096 (utf8-byte-length (get view field)))))
    (is (true? (:truncated? view)))
    (is (= 5000 (:full-bytes view)))))

;; ---------------------------------------------------------------------------
;; 3. Structured receipts with quoted paths and multiline contents

(deftest structured-receipts-with-quoted-paths-and-multiline-content
  (let [root (temp-dir "hc-ufss-receipts-")
        filename "a\"b.txt"
        content "line1\nsay \"hi\"\nline2"
        _ (spit (str root "/" filename) content)
        service (files/service {:root root})
        executor (:execute! (app-devops/tool-executor
                             {:files-service service}))]
    (testing "stat"
      (let [text (:text (executor {:name "stat"
                                   :arguments {:path filename}}))
            value (parse-single text)]
        (is (map? value))
        (is (= filename (:path value)))
        (is (true? (:exists? value)))
        (is (not (str/includes? text "\\\"")))))
    (testing "ls"
      (let [text (:text (executor {:name "ls"
                                   :arguments {:path ""}}))
            value (parse-single text)]
        (is (= "" (:path value)))
        (is (= filename (get-in value [:entries 0 :name])))
        (is (not (str/includes? text "\\\"")))))
    (testing "where"
      (let [needle "say \"hi\""
            expected (text-location/where {:content content
                                           :needle needle})
            text (:text (executor {:name "where"
                                   :arguments {:path filename
                                               :needle needle}}))
            value (parse-single text)]
        (is (= expected value))
        (is (not (str/includes? text "\\\"")))))
    (testing "whereAll"
      (let [needle "\""
            expected (text-location/whereAll {:content content
                                              :needle needle})
            text (:text (executor {:name "whereAll"
                                   :arguments {:path filename
                                               :needle needle}}))
            value (parse-single text)]
        (is (= expected value))
        (is (vector? value))
        (is (not (str/includes? text "\\\"")))))
    (testing "park"
      (let [payload {:path "dir/a\"b.txt"
                     :content "first line\nsecond \"quoted\" line\nthird"}
            model (scripted-model
                   [[{:event :completed
                      :text ""
                      :tool-calls [{:id "p1"
                                    :name "park"
                                    :arguments {:name "park-boundary"
                                                :payload payload}}]}]])
            runner (devops/runner {:model model})
            result ((:run! runner)
                    {:worker-id "worker-park"
                     :tenure-id "tenure-park"
                     :call-id "call-park"
                     :verbose-intent "park with a quoted path"
                     :model-role :task
                     :model-id "task-model"})
            text (:report result)
            value (parse-single text)]
        (is (= :interrupted (:status result)))
        (is (= {:name "park-boundary"
                :state :parked
                :reason :explicit
                :payload payload}
               value))
        (is (not (str/includes? text "\\\"")))
        (is (not (str/includes? text "\\n")))))))

;; ---------------------------------------------------------------------------
;; 4. Error receipt with quoted/newline code and message

(deftest error-receipt-round-trips-quoted-code-and-message
  (let [root (temp-dir "hc-ufss-error-")
        service (files/service {:root root})
        executor (:execute! (app-devops/tool-executor
                             {:files-service service}))
        error-data {:code "CODE\"\nSECOND"
                    :message "message \"quoted\"\nsecond line"}]
    (with-redefs [files/read-text (fn [_ _] {:ok? false
                                             :error error-data})]
      (let [failed (executor {:name "cat"
                              :arguments {:path "x"}})
            text (:text failed)
            value (parse-single text)]
        (is (= :failed (:status failed)))
        (is (= (:code error-data) (:code value)))
        (is (= (:message error-data) (:message value)))
        (is (= :failed (:status value)))
        (is (not (str/includes? text "\\\"")))
        (is (not (str/includes? text "\\n")))))))

;; ---------------------------------------------------------------------------
;; 5. where nearest and candidates

(deftest where-nearest-and-candidates-round-trip
  (let [root (temp-dir "hc-ufss-where-")
        service (files/service {:root root})
        executor (:execute! (app-devops/tool-executor
                             {:files-service service}))
        long-line (str "\"" (apply str (repeat 249 "a")))
        near-needle (str "b" (apply str (repeat 249 "a")))]
    (testing "nearest text is exact and truncated at 200 units"
      (let [expected (text-location/where {:content long-line
                                           :needle near-needle})
            text (:text (executor {:name "where"
                                   :arguments {:content long-line
                                               :needle near-needle}}))
            value (parse-single text)]
        (is (= expected value))
        (is (= (subs long-line 0 200)
               (get-in value [:payload :nearest :text])))
        (is (true? (get-in value [:payload :nearest :truncated])))
        (is (= 200 (count (get-in value [:payload :nearest :text]))))
        (is (not (str/includes? text "\\\"")))))
    (testing "ambiguous candidates keep quoted context"
      (let [content "say \"one\"\nsay \"one\"\nsay \"one\""
            needle "\"one\""
            expected (text-location/where {:content content
                                           :needle needle})
            text (:text (executor {:name "where"
                                   :arguments {:content content
                                               :needle needle}}))
            value (parse-single text)
            candidates (get-in value [:payload :candidates])]
        (is (= expected value))
        (is (= 3 (count candidates)))
        (is (every? #(str/includes? (:context %) "\"") candidates))
        (is (not (str/includes? text "\\\"")))
        (is (not (str/includes? text "\\n")))))))

;; ---------------------------------------------------------------------------
;; 6. Property: any dangerous goal round-trips through the prompt block

(def gen-dangerous-goal
  (gen/fmap (fn [fragments]
              (str "goal:" (apply str fragments)))
            (gen/vector (gen/elements dangerous-fragments) 0 24)))

(defspec board-work-prompt-round-trips-dangerous-goals 100
  (prop/for-all [goal gen-dangerous-goal]
    (let [text (prompt-text goal)
          value (ufss/read-str (board-block text))]
      (and (string? text)
           (= goal (get-in value [:ready-tasks 0 :goal]))))))
