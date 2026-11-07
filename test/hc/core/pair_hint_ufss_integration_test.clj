(ns hc.core.pair-hint-ufss-integration-test
  "Integration proof that pair_hint's injected structured-data renderer can be
  the sibling UFSS printer.  pair_hint source never requires another core
  module; the composition layer supplies `hc.core.ufss/write-str`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.core.pair-hint :as ph]
            [hc.core.ufss :as ufss]))

(defn- render-opts []
  {:render ufss/write-str})

(deftest board-and-fleet-data-round-trip-through-ufss
  (let [session (ph/create-session {:explicit-language :en})
        changes [{:note "a\"b\\c\nd"}]
        board (ph/apply-board-observation
               session
               {:kind :delta :board-version 1 :changes changes}
               (render-opts))
        text (:text board)]
    (is (= {:board-version 1 :changes changes} (ufss/read-str text)))
    (is (not (str/includes? text "\\\"")))
    (is (not (str/includes? text "\\n"))))
  (let [session (ph/create-session {:explicit-language :en})
        rows [{:swarm-id "a\"b" :status :running :queued-count 1}]
        fleet (ph/apply-fleet-observation
               session
               {:kind :delta :fleet-version 1 :swarms rows}
               (render-opts))
        text (:text fleet)]
    (is (= {:fleet-version 1 :swarms rows :events []}
           (ufss/read-str text)))
    (is (not (str/includes? text "\\\"")))))

(deftest parked-handles-round-trip-through-ufss
  (let [session (ph/create-session {:explicit-language :en})
        values (ph/hint-values
                session
                (merge (render-opts)
                       {:parked-handles ["a\"b\\c"]}))
        rendered (get values "parked-handles")]
    (is (= ["a\"b\\c"] (ufss/read-str rendered)))
    (is (not (str/includes? rendered "\\\"")))))

(deftest ufss-renderer-is-not-a-default
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"EDN\+UFSS renderer is required"
                        (ph/observe-board
                         ph/initial-board-state
                         {:kind :delta :board-version 1 :changes []}
                         {}))))

(deftest project-request-uses-ufss-for-observations
  (let [session (ph/create-session {:explicit-language :en})
        request {:attempt-id "attempt-1"
                 :call-id "call-1"
                 :snapshot {:role :main
                            :messages [{:role :user :text "hi"}]}}
        changes [{:note "a\"b\\c\nd"}]
        result (ph/project-request
                session
                request
                {:placement {:turn 1}
                 :context {:context-window 1000}
                 :render ufss/write-str
                 :observations
                 {:board {:observation {:kind :delta
                                        :board-version 1
                                        :changes changes}}}})
        board-text (get-in result [:session :board-text])
        messages (get-in result [:request :snapshot :messages])]
    (is (true? (:ok? result)))
    (is (= {:board-version 1 :changes changes}
           (ufss/read-str board-text)))
    (is (not (str/includes? board-text "\\\"")))
    (is (not (str/includes? board-text "\\n")))
    (is (= :assistant (:role (nth messages 1))))
    (is (= "" (get-in messages [1 :tool-calls 0 :name])))
    (is (= :tool (:role (nth messages 2))))
    (is (str/includes? (:text (nth messages 2)) "#\"\"\""))))
