(ns hc.core.pair-hint.hint-property-test
  "Property tests for the durable Pair Hint request chain.

  The generated texts are real user messages.  Each trial starts from a fresh
  session and exercises one of three laws: a same-epoch render extends the
  previous render byte-for-byte, recording the same rendered request twice
  adds nothing, and reanchoring cannot move the observed board cursor
  backwards."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.pair-hint.hint :as hint]
            [hc.core.pair-hint.prefix :as prefix]))

(def ^:private gen-text
  (gen/fmap (fn [text] (str "turn-" text))
            gen/string-alphanumeric))

(def ^:private gen-turn-texts
  (gen/vector gen-text 1 4))

(defn- observed-request
  [messages]
  {:call-id "call-prop"
   :snapshot {:messages (vec messages)}})

(defn- prefix-of?
  [prefix messages]
  (and (<= (count prefix) (count messages))
       (= (vec prefix) (subvec (vec messages) 0 (count prefix)))))

(defspec project-request-extends-the-chain-without-drift 50
  (prop/for-all [texts gen-turn-texts]
    (let [session (hint/create-session {:explicit-language :en})
          first-message {:message-id "m0"
                         :role :user
                         :text (first texts)}
          appended (hint/append-hint session
                                     {:messages [first-message]
                                      :placement {:turn 0}})]
      (and
       (:ok? appended)
       (loop [turn 1
              current (:state appended)
              rendered (:rendered appended)]
         (if (>= turn (count texts))
           true
           (let [message {:message-id (str "m" turn)
                          :role :user
                          :text (nth texts turn)}
                 result (hint/project-request
                         current
                         (observed-request (conj rendered message))
                         {:placement {:turn turn}})]
             (if-not (:ok? result)
               false
               (let [next-rendered (get-in result
                                           [:request :snapshot :messages])
                     next-session (:session result)]
                 (if (and (prefix-of? rendered next-rendered)
                          (= (count next-rendered)
                             (get-in next-session [:chain :count]))
                          (= (:epoch-id current)
                             (:epoch-id next-session))
                          (boolean
                           (prefix/verify-prefix
                            (get-in next-session [:chain])
                            next-rendered)))
                   (recur (inc turn) next-session next-rendered)
                   false))))))))))

(def ^:private gen-record-messages
  (gen/vector
   (gen/fmap (fn [[role text]]
               {:role role :text text})
             (gen/tuple (gen/elements [:user :assistant])
                        gen/string-alphanumeric))
   0 4))

(defspec record-request-is-idempotent-at-the-same-position 60
  (prop/for-all [generated gen-record-messages
                 seeded? gen/boolean]
    (let [session (hint/create-session {:explicit-language :en})
          seeded (when seeded?
                   (hint/append-hint
                    session
                    {:messages [{:message-id "seed"
                                 :role :user
                                 :text "seed"}]
                     :placement {:turn 0}}))
          current (if seeded? (:state seeded) session)
          messages (if seeded?
                     (:rendered seeded)
                     generated)
          first-record (hint/record-request current messages nil)
          second-record (hint/record-request (:state first-record)
                                             messages
                                             nil)]
      (and
       (:ok? first-record)
       (:ok? second-record)
       (= (:state first-record) (:state second-record))
       (= (count messages)
          (get-in first-record [:chain :count]))
       (= (:hint-count current)
          (:hint-count (:state first-record)))
       (= (:placements current)
          (:placements (:state first-record)))
       (= (:epoch-id current)
          (:epoch-id (:state first-record)))
       (if seeded?
         (= (get-in current [:chain])
            (get-in first-record [:chain]))
         true)))))

(defspec reanchor-keeps-the-observed-cursor-monotone 60
  (prop/for-all [versions (gen/fmap (fn [versions]
                                      (vec (sort (distinct versions))))
                                    (gen/vector (gen/choose 0 1000) 1 6))
                 final-version (gen/choose 1001 2000)]
    (let [session (hint/create-session {:explicit-language :en})
          applied
          (reduce
           (fn [{:keys [session cursor ok?] :as acc} version]
             (if-not ok?
               (reduced acc)
               (let [result (hint/apply-board-observation
                             session
                             {:kind :delta
                              :board-version version
                              :changes [{:version version}]}
                             {:render pr-str})]
                 (if-not (:ok? result)
                   (reduced {:ok? false
                             :session session
                             :cursor cursor})
                   (let [next-session (:session result)
                         next-cursor (hint/observed-cursor next-session)]
                     (if (>= next-cursor cursor)
                       {:ok? true
                        :session next-session
                        :cursor next-cursor}
                       (reduced {:ok? false
                                 :session next-session
                                 :cursor next-cursor})))))))
           {:ok? true :session session :cursor -1}
           versions)
          reanchored (hint/reanchor (:session applied)
                                    {:kind :scope-reset
                                     :id "reanchor-prop"})
          after-reanchor (hint/observed-cursor reanchored)
          final-result (hint/apply-board-observation
                        reanchored
                        {:kind :delta
                         :board-version final-version
                         :changes [{:version final-version}]}
                        {:render pr-str})]
      (and (:ok? applied)
           (>= after-reanchor (:cursor applied))
           (:ok? final-result)
           (>= (hint/observed-cursor (:session final-result))
               after-reanchor)))))
