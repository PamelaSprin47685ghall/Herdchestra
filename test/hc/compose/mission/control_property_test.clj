(ns hc.compose.mission.control-property-test
  "Property tests for pure mission command adapters: context identity wins
  over model-supplied identity, and draft paths are validated before an
  Owner command is produced."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission.control :as control]))

(defspec review-command-uses-context-identity 50
  (prop/for-all [ctx-tenure gen/string-alphanumeric
                 req-tenure gen/string-alphanumeric
                 ctx-call gen/string-alphanumeric
                 req-call gen/string-alphanumeric
                 findings (gen/vector gen/any 0 3)]
    (let [ctx {:role :clj-plan :lane :plan :tenure-id ctx-tenure
               :call-id ctx-call}
          request {:tenure-id req-tenure :call-id req-call :findings findings}
          command (control/review-command ctx request)]
      (and (= :review/submit (:kind command))
           (= :plan (:lane command))
           (= ctx-tenure (:tenure-id command))
           (= ctx-call (:call-id command))
           (= findings (:findings command))))))

(defspec draft-command-rejects-invalid-path-or-missing-tenure 50
  (prop/for-all [path (gen/elements ["" "plans/x.txt" "other/x.md"
                                     "plans/a.md"])
                 content gen/string-alphanumeric
                 tenure (gen/elements [nil "" "tenure-1"])]
    (let [valid? (control/draft-path? path)
          valid-tenure? (and (string? tenure) (seq tenure))
          result (try
                   {:ok? true :value (control/draft-command path content tenure)}
                   (catch clojure.lang.ExceptionInfo e
                     {:ok? false :error (ex-data e)}))]
      (if (and valid? valid-tenure?)
        (and (:ok? result)
             (= :plan/write-draft (get-in result [:value :kind]))
             (= path (get-in result [:value :path])))
        (and (false? (:ok? result))
             (contains? #{:INVALID_DRAFT_PATH :TENURE_REQUIRED}
                        (get-in result [:error :code])))))))
