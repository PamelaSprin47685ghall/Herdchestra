(ns hc.core.pair-hint.integration-property-test
  "Property tests for the stateful Pair Hint integration adapters.

  The wrapper must project a real assistant/tool pair for every request and
  must not consume a chain position for an ephemeral side turn.  A board
  snapshot entry is a durable observation: applying the same version twice
  replays it instead of opening another clean break."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.pair-hint.hint :as hint]
            [hc.core.pair-hint.integration :as integration]))

(def ^:private gen-messages
  (gen/fmap (fn [texts]
              (mapv (fn [index text]
                      {:message-id (str "m" index)
                       :role :user
                       :text text})
                    (range)
                    texts))
            (gen/vector gen/string-alphanumeric 1 3)))

(def ^:private gen-optional-text
  (gen/fmap (fn [[present? text]]
              (when present? text))
            (gen/tuple gen/boolean gen/string-alphanumeric)))

(defn- wrapped
  [session ephemeral?]
  (let [seen (atom nil)
        commits (atom 0)
        gateway {:start! (fn [request _deliver]
                           (reset! seen request)
                           {:status :started})
                 :cancel! (fn [_handle] nil)}
        wrapper (integration/wrap-model-gateway
                 gateway
                 {:state (atom session)
                  :ephemeral? ephemeral?
                  :on-session (fn [_session _request]
                                (swap! commits inc))})]
    {:state (:state wrapper)
     :start! (:start! wrapper)
     :seen seen
     :commits commits}))

(defspec wrapper-projects-one-pair-and-ephemeral-turns-do-not-move 60
  (prop/for-all [messages gen-messages
                 ephemeral? gen/boolean]
    (let [session (hint/create-session {:explicit-language :en})
          {:keys [state start! seen commits]} (wrapped session ephemeral?)
          before @state
          request {:call-id "call-prop"
                   :snapshot {:messages messages}}
          _response (start! request (fn [_handle] nil))
          projected @seen
          after @state
          projected-messages (get-in projected [:snapshot :messages])
          call (nth projected-messages
                    (- (count projected-messages) 2))
          result (nth projected-messages
                      (dec (count projected-messages)))]
      (and
       (= :assistant (:role call))
       (= :tool (:role result))
       (true? (:pair-hint call))
       (true? (:pair-hint result))
       (= (:tool-call-id result)
          (get-in call [:tool-calls 0 :id]))
       (= "" (get-in call [:tool-calls 0 :name]))
       (= "" (:name result))
       (if ephemeral?
         (and (= before after)
              (= 0 @commits)
              (= (:chain before) (:chain after))
              (= (:hint-count before) (:hint-count after))
              (= (:placements before) (:placements after)))
         (and (not= before after)
              (= 1 @commits)
              (= (inc (:hint-count before))
                 (:hint-count after))
              (= (count projected-messages)
                 (get-in after [:chain :count]))))))))

(defn- board
  [version text]
  {:board-version version
   :task-id "task-1"
   :note text
   :items [version]})

(defspec board-snapshot-entry-replays-the-same-version 60
  (prop/for-all [version (gen/choose 0 1000)
                 text gen/string-alphanumeric
                 writer? gen/boolean]
    (let [snapshot (board version text)
          entry (integration/board-snapshot-entry snapshot {:writer? writer?})
          session (hint/create-session {:explicit-language :en})
          request {:call-id "call-1"
                   :snapshot {:messages [{:message-id "m0"
                                          :role :user
                                          :text "hello"}]}}
          first-projection
          (hint/project-request session request
                                {:observations {:board entry}
                                 :render pr-str
                                 :placement {:call-id "call-1"}})
          first-messages (get-in first-projection
                                 [:request :snapshot :messages])
          second-projection
          (hint/project-request
           (:session first-projection)
           (assoc-in request [:snapshot :messages] first-messages)
           {:observations {:board entry}
            :render pr-str
            :placement {:call-id "call-2"}})]
      (and
       (= version (get-in entry [:observation :board-version]))
       (= snapshot (get-in entry [:observation :snapshot]))
       (= writer? (boolean (get-in entry [:opts :writer?])))
       (:ok? first-projection)
       (:ok? second-projection)
       (= :replay (get-in second-projection [:projection :board-action]))
       (= (get-in first-projection [:session :board])
          (get-in second-projection [:session :board]))
       (= (hint/observed-cursor (:session first-projection))
          (hint/observed-cursor (:session second-projection)))
       (= version
          (hint/observed-cursor (:session second-projection)))))))

(defspec scope-boundary-maps-only-settled-scope-kinds 80
  (prop/for-all [kind (gen/elements [:reset-scope :remove-last :keep
                                     :other nil])
                 call-id gen-optional-text
                 turn-id gen-optional-text]
    (let [annotation {:kind kind
                      :call-id call-id
                      :turn-id turn-id
                      :payload {:note "scope"}}
          boundary (integration/scope-boundary annotation)]
      (if (contains? #{:reset-scope :remove-last} kind)
        (and (= (if (= :reset-scope kind) :scope-reset :scope-remove)
                (:kind boundary))
             (= (or call-id turn-id) (:call-id boundary))
             (= annotation (:raw boundary)))
        (nil? boundary)))))
