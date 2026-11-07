(ns hc.core.scope.run-tests
  "Focused runner for the Scope package."
  (:require [clojure.test :as test]
            [hc.core.scope-test]))

(defn -main [& _]
  (let [result (test/run-tests 'hc.core.scope-test)
        failures (+ (:fail result) (:error result))]
    (shutdown-agents)
    (System/exit (if (zero? failures) 0 1))))
