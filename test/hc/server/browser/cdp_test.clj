(ns hc.server.browser.cdp-test
  "The Chrome failure log is diagnostic, so only its tail is read."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.browser.cdp :as cdp])
  (:import (java.io File)))

(deftest log-tail-is-bounded-to-the-requested-bytes
  (let [file (File/createTempFile "hc-cdp-log-" ".log")]
    (try
      (spit file (apply str (repeat 10000 "0123456789")))
      (let [tail (#'cdp/read-log-tail file 4096)]
        (is (= 4096 (count tail)))
        (is (str/ends-with? tail "0123456789")))
      (finally
        (.delete file)))))

(deftest short-log-is-read-whole
  (let [file (File/createTempFile "hc-cdp-log-" ".log")]
    (try
      (spit file "short chrome log")
      (is (= "short chrome log"
             (#'cdp/read-log-tail file 4096)))
      (finally
        (.delete file)))))
