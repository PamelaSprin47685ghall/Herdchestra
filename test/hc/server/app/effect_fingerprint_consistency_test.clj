(ns hc.server.app.effect-fingerprint-consistency-test
  "Cross-check the two Steward effect fingerprints.

  `hc.compose.steward.loop/effect-fingerprint` is the host's bounded
  at-least-once de-duplication identity.  `hc.server.app.steward-model/
  effect-fingerprint` is the model-result LRU key.  The shared identifying
  fields must agree; the model key is allowed to be strictly finer because it
  also has to invalidate cached results on prompt and request changes.

  The model side is a **model-only** key: a non-model effect is outside its
  domain and must throw `:EFFECT_OUTSIDE_MODEL_DOMAIN` instead of folding
  into a generic bucket.  `:report/emit` is a host effect, so the report
  regression checks the loop side and asserts the model guard explicitly."
  (:refer-clojure :exclude [ensure])
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.steward.loop :as loop]
            [hc.server.app.steward-model :as model]))

(defn- ensure
  [pred message data]
  (when-not pred
    (throw (ex-info message data))))

(defn- fingerprint
  [side effect]
  (case side
    :loop (loop/effect-fingerprint effect)
    :model (model/effect-fingerprint effect)))

(defn- other-side
  [side]
  (if (= :loop side) :model :loop))

(defn- observed-fingerprint
  "Fingerprint for a failure message; out-of-domain model calls return the
  guard evidence instead of throwing inside the reporter."
  [side effect]
  (try
    (fingerprint side effect)
    (catch clojure.lang.ExceptionInfo e
      {:guard (:code (ex-data e))
       :type (:type effect)})))

(def ^:private model-domain
  #{:model/meditate :model/decide})

(defn- model-domain-effect?
  [effect]
  (contains? model-domain (:type effect)))

(defn- assert-model-domain-error
  "Assert that the model fingerprint rejects a non-model effect.

  The message check is the `thrown-with-msg?` contract; the ex-data checks
  pin the machine-readable code and the exact allowed set."
  [label effect]
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"only accepts Steward model effects"
                        (model/effect-fingerprint effect))
      (str label ": expected the model domain guard"))
  (let [error (try
                (model/effect-fingerprint effect)
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :EFFECT_OUTSIDE_MODEL_DOMAIN (:code (ex-data error)))
        (str label ": wrong guard code"))
    (is (= model-domain (get-in (ex-data error) [:details :allowed]))
        (str label ": wrong allowed set"))
    (is (= (:type effect) (get-in (ex-data error) [:details :type]))
        (str label ": wrong rejected type"))))

(def ^:private base-meditate
  {:type :model/meditate
   :role :meditator
   :evidence-seq 7
   :evidence-count 2
   :evidence-ids ["e1" "e2"]
   :prompt-version 3
   :at 1000
   :frontier {:frontier-id "ignored-large-payload"}
   :cba {:best-answer "ignored"}
   :candidates-hint [{:candidate-id "c1"}]})

(def ^:private base-decide
  {:type :model/decide
   :role :steward
   :frontier-id "f1"
   :request-id "r1"
   :prompt-version 3
   :at 1000
   :frontier {:frontier-id "f1"}
   :cba {:best-answer "ignored"}
   :candidates-hint []})

(def ^:private base-report
  {:type :report/emit
   :mission-id "m1"
   :outcome :joined
   :swarm-id "a"
   :ff-commit "ff1"
   :status :done
   :report "large report text"
   :at 1000})

(def ^:private base-diagnostic
  {:type :diagnostic
   :code :MODEL_FAILED
   :mission-id "m1"
   :request-id "r1"
   :message "boom"
   :at 1000})

(def ^:private base-plan
  {:type :plan/start
   :mission-id "m1"
   :intent-version 2
   :at 1000})

(def ^:private base-fork
  {:type :fleet/fork
   :mission-id "m1"
   :slices [{:slice-id :s1} {:slice-id :s2}]
   :at 1000})

(def ^:private base-persist
  {:type :persist
   :state {:any "payload"}
   :at 1000})

(def ^:private base-both-model-fields
  ;; Carries both model shapes so a :type change keeps every key field.
  {:type :model/meditate
   :role :steward
   :evidence-seq 1
   :evidence-count 1
   :evidence-ids ["e1"]
   :frontier-id "f1"
   :request-id "r1"
   :prompt-version 1
   :at 1000})

(def ^:private all-effects
  [["meditate" base-meditate]
   ["decide" base-decide]
   ["report" base-report]
   ["diagnostic" base-diagnostic]
   ["plan" base-plan]
   ["fork" base-fork]
   ["persist" base-persist]])

(defn- reordered
  "Same map with the top-level entry order reversed."
  [m]
  (apply array-map (mapcat identity (reverse (seq m)))))

(deftest fingerprints-are-idempotent
  (doseq [[label effect] all-effects]
    (testing (str label " / loop")
      (is (= (loop/effect-fingerprint effect)
             (loop/effect-fingerprint effect))
          (str label " loop is not idempotent")))
    (testing (str label " / model")
      (if (model-domain-effect? effect)
        (let [first-result (model/effect-fingerprint effect)
              second-result (model/effect-fingerprint effect)]
          (is (= first-result second-result)
              (str label " model is not idempotent: "
                   (pr-str first-result) " vs " (pr-str second-result))))
        (assert-model-domain-error (str label " / model") effect)))))

(deftest key-order-does-not-change-the-identity
  (doseq [[label effect] all-effects]
    (testing (str label " / loop")
      (let [forward (loop/effect-fingerprint effect)
            reversed (loop/effect-fingerprint (reordered effect))]
        (is (= forward reversed)
            (str label " loop depends on map order: "
                 (pr-str forward) " vs " (pr-str reversed)))))
    (testing (str label " / model")
      (if (model-domain-effect? effect)
        (let [forward (model/effect-fingerprint effect)
              reversed (model/effect-fingerprint (reordered effect))]
          (is (= forward reversed)
              (str label " model depends on map order: "
                   (pr-str forward) " vs " (pr-str reversed))))
        (assert-model-domain-error (str label " / model") effect)))))

;; Fields that must change the identity.
(def ^:private different-cases
  [;; `:report/emit` regression: different swarms and ff commits are distinct.
   [:loop base-report :swarm-id "b"]
   [:loop base-report :ff-commit "ff2"]
   [:loop base-report :mission-id "m2"]
   [:loop base-report :outcome :failed]
   ;; Other host effects.
   [:loop base-plan :mission-id "m2"]
   [:loop base-diagnostic :request-id "r2"]
   [:loop base-fork :mission-id "m2"]
   ;; Shared model identifying fields.
   [:loop base-meditate :role :steward]
   [:model base-meditate :role :steward]
   [:loop base-meditate :evidence-seq 8]
   [:model base-meditate :evidence-seq 8]
   [:loop base-decide :frontier-id "f2"]
   [:model base-decide :frontier-id "f2"]
   ;; Model-only refinement fields.
   [:model base-meditate :evidence-ids ["e3"]]
   [:model base-meditate :evidence-count 3]
   [:model base-meditate :prompt-version 4]
   [:model base-decide :request-id "r2"]
   [:model base-decide :prompt-version 4]
   ;; The type itself is part of both keys.
   [:loop base-both-model-fields :type :model/decide]
   [:model base-both-model-fields :type :model/decide]])

(deftest distinct-fields-change-the-identity
  (doseq [[side effect field value] different-cases]
    (let [changed (assoc effect field value)
          first-result (fingerprint side effect)
          second-result (fingerprint side changed)
          other (other-side side)
          other-first (observed-fingerprint other effect)
          other-second (observed-fingerprint other changed)]
      (testing (str (name side) " / " (name (:type effect)) " / " (name field))
        (is (not= first-result second-result)
            (str (name side) " fingerprint ignored " field ": "
                 (pr-str first-result) " vs " (pr-str second-result)
                 "; " (name other) ": "
                 (pr-str other-first) " vs " (pr-str other-second)))))))

;; Fields that both sides intentionally ignore, or that are out of one side's
;; documented domain.  Each row carries its reason into the failure message.
(def ^:private same-cases
  [;; Both sides ignore volatile observation time.
   [:loop base-meditate :at 2000
    "volatile :at is excluded by the loop docstring"]
   [:model base-meditate :at 2000
    "volatile :at is excluded by the model key"]
   [:loop base-report :at 2000
    "volatile :at is excluded by the loop docstring"]
   [:model base-report :at 2000
    "volatile :at is excluded; report effects are also out of model domain"]
   ;; Documented loop exclusions.
   [:loop base-meditate :evidence-ids ["e3"]
    "loop identity uses :evidence-seq; ids/count are payload"]
   [:loop base-meditate :evidence-count 3
    "loop identity uses :evidence-seq; ids/count are payload"]
   [:loop base-meditate :prompt-version 4
    "prompt-version is constant for a process; the host window is process-local"]
   [:loop base-decide :prompt-version 4
    "prompt-version is constant for a process; the host window is process-local"]
   [:loop base-decide :request-id "r2"
    "host window dedup must remain stable across a retry request identity"]
   [:loop base-report :report "different report text"
    "loop docstring excludes the large :report payload; outcome/swarm/ff-commit carry identity"]
   ;; The model fingerprint only defines model effects.
   [:model base-report :swarm-id "b"
    "report effects are out of the model fingerprint's domain"]
   [:model base-report :ff-commit "ff2"
    "report effects are out of the model fingerprint's domain"]
   [:model base-report :mission-id "m2"
    "report effects are out of the model fingerprint's domain"]
   [:model base-report :report "different report text"
    "report effects are out of the model fingerprint's domain"]
   [:model base-plan :mission-id "m2"
    "plan effects are out of the model fingerprint's domain"]
   [:model base-meditate :request-id "r2"
    "request-id is not part of the meditate key on either side"]])

(deftest documented-ignores-stay-ignored
  (doseq [[side effect field value reason] same-cases]
    (testing (str (name side) " / " (name (:type effect)) " / " (name field))
      (if (and (= side :model) (not (model-domain-effect? effect)))
        (assert-model-domain-error
         (str (name side) " / " (name (:type effect)) " / " (name field)
              "; reason=" reason)
         effect)
        (let [changed (assoc effect field value)
              first-result (fingerprint side effect)
              second-result (fingerprint side changed)
              other (other-side side)
              other-first (observed-fingerprint other effect)
              other-second (observed-fingerprint other changed)]
          (is (= first-result second-result)
              (str (name side) " unexpectedly changed on " field ": "
                   (pr-str first-result) " vs " (pr-str second-result)
                   "; reason=" reason "; " (name other) ": "
                   (pr-str other-first) " vs " (pr-str other-second))))))))

(deftest model-fingerprint-rejects-every-non-model-effect
  (doseq [[label effect] all-effects
          :when (not (model-domain-effect? effect))]
    (assert-model-domain-error label effect)))

(deftest report-swarm-and-ff-commit-are-distinct-on-the-host
  (let [a base-report
        b (assoc base-report :swarm-id "b")
        c (assoc base-report :ff-commit "ff2")]
    (testing "loop/effect-fingerprint"
      (is (= [:report/emit "m1" :joined "a" "ff1"]
             (loop/effect-fingerprint a)))
      (is (not= (loop/effect-fingerprint a) (loop/effect-fingerprint b))
          (str "different swarms collided: "
               (pr-str (loop/effect-fingerprint a)) " vs "
               (pr-str (loop/effect-fingerprint b))))
      (is (not= (loop/effect-fingerprint a) (loop/effect-fingerprint c))
          (str "different ff commits collided: "
               (pr-str (loop/effect-fingerprint a)) " vs "
               (pr-str (loop/effect-fingerprint c)))))
    (testing "model/effect-fingerprint rejects report effects"
      (assert-model-domain-error "model / report a" a)
      (assert-model-domain-error "model / report b" b))))

(deftest shared-model-identity-is-a-prefix
  (doseq [[label effect extra-fields]
          [["meditate" base-meditate
            [:evidence-count :evidence-ids :prompt-version]]
           ["decide" base-decide
            [:request-id :prompt-version]]]]
    (testing label
      (let [loop-key (loop/effect-fingerprint effect)
            model-key (model/effect-fingerprint effect)
            prefix (subvec model-key 0 (count loop-key))
            tail (subvec model-key (count loop-key))]
        (is (= loop-key prefix)
            (str label " shared fields drifted; loop=" (pr-str loop-key)
                 " model=" (pr-str model-key)))
        (is (= (mapv #(get effect %) extra-fields) tail)
            (str label " model-only tail drifted; expected="
                 (pr-str (mapv #(get effect %) extra-fields))
                 " actual=" (pr-str tail)))))))

(deftest model-refinement-splits-but-never-merges
  (testing "different prompt versions: loop merges, model splits"
    (let [a base-meditate
          b (assoc base-meditate :prompt-version 99)]
      (is (= (loop/effect-fingerprint a) (loop/effect-fingerprint b))
          "loop must ignore prompt-version")
      (is (not= (model/effect-fingerprint a) (model/effect-fingerprint b))
          (str "model must split prompt versions: "
               (pr-str (model/effect-fingerprint a)) " vs "
               (pr-str (model/effect-fingerprint b))))))
  (testing "different request ids: loop merges, model splits"
    (let [a base-decide
          b (assoc base-decide :request-id "r2")]
      (is (= (loop/effect-fingerprint a) (loop/effect-fingerprint b))
          "loop decide key must remain stable across request ids")
      (is (not= (model/effect-fingerprint a) (model/effect-fingerprint b))
          (str "model must split request ids: "
               (pr-str (model/effect-fingerprint a)) " vs "
               (pr-str (model/effect-fingerprint b)))))))

(def ^:private gen-meditate-effect
  (gen/hash-map
   :type (gen/elements [:model/meditate])
   :role (gen/elements [:meditator :steward])
   :evidence-seq (gen/choose 0 8)
   :evidence-count (gen/choose 0 6)
   :evidence-ids (gen/vector (gen/elements ["e1" "e2" "e3"]) 0 4)
   :prompt-version (gen/choose 0 4)
   :at (gen/choose 0 100000)))

(def ^:private gen-decide-effect
  (gen/hash-map
   :type (gen/elements [:model/decide])
   :role (gen/elements [:steward :meditator])
   :frontier-id (gen/elements ["f1" "f2"])
   :request-id (gen/elements ["r1" "r2" nil])
   :prompt-version (gen/choose 0 4)
   :at (gen/choose 0 100000)))

(defspec model-key-refines-loop-key 200
  (prop/for-all [left (gen/one-of [gen-meditate-effect gen-decide-effect])
                 right (gen/one-of [gen-meditate-effect gen-decide-effect])]
    (let [left-loop (loop/effect-fingerprint left)
          right-loop (loop/effect-fingerprint right)
          left-model (model/effect-fingerprint left)
          right-model (model/effect-fingerprint right)]
      (ensure (or (not= left-model right-model)
                  (= left-loop right-loop))
              "model key merged two effects that the host keeps distinct"
              {:left left :right right
               :left-loop left-loop :right-loop right-loop
               :left-model left-model :right-model right-model})
      (doseq [effect [left right]]
        (ensure (= (loop/effect-fingerprint effect)
                   (loop/effect-fingerprint (assoc effect :at (inc (:at effect)))))
                "loop fingerprint changed only by :at"
                {:effect effect})
        (ensure (= (model/effect-fingerprint effect)
                   (model/effect-fingerprint (assoc effect :at (inc (:at effect)))))
                "model fingerprint changed only by :at"
                {:effect effect}))
      true)))
