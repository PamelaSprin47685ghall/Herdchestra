(ns hc.server.speech.audio-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.speech.audio :as audio]
            [hc.server.speech.support :as support]))

(deftest wav-duration-is-derived-from-bytes
  (let [bytes (support/wav-bytes 8000 8000)]
    (is (= 1000 (audio/duration-ms bytes "audio/wav")))
    (is (true? (audio/verifiable? bytes "audio/wav"))))
  (testing "unknown containers are not guessed"
    (is (nil? (audio/duration-ms (.getBytes "not audio" "UTF-8")
                                 "audio/webm")))
    (is (false? (audio/verifiable? (.getBytes "not audio" "UTF-8")
                                   "audio/webm")))))
