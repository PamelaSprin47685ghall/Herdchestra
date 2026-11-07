(ns hc.server.app.static-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.static :as static])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (io/file (str (Files/createTempDirectory "hc-static-" (make-array FileAttribute 0)))))

(defn- write-file! [root relative content]
  (let [file (io/file root relative)]
    (io/make-parents file)
    (spit file content)
    file))

(defn- request
  ([uri] (request uri :get))
  ([uri method] {:request-method method :uri uri :headers {}}))

(deftest serves-a-file-with-type
  (let [root (temp-dir)]
    (write-file! root "js/main.js" "(js)")
    (let [response ((static/handler root) (request "/js/main.js"))]
      (is (= 200 (:status response)))
      (is (= "text/javascript; charset=utf-8"
             (get-in response [:headers "Content-Type"])))
      (is (= "(js)" (String. ^bytes (:body response) "UTF-8"))))))

(deftest head-has-no-body
  (let [root (temp-dir)]
    (write-file! root "index.html" "<html></html>")
    (let [response ((static/handler root) (request "/index.html" :head))]
      (is (= 200 (:status response)))
      (is (nil? (:body response)))
      (is (= "13" (get-in response [:headers "Content-Length"]))))))

(deftest missing-file-is-404
  (let [root (temp-dir)]
    (is (= 404 (:status ((static/handler root) (request "/nope.txt")))))))

(deftest spa-fallback-serves-index-for-extensionless-route
  (let [root (temp-dir)]
    (write-file! root "index.html" "<app>")
    (is (= "<app>"
           (String. ^bytes (:body ((static/handler root) (request "/sessions/abc")))
                    "UTF-8")))
    (is (= 404 (:status ((static/handler root) (request "/missing.js")))))))

(deftest traversal-is-refused
  (let [root (temp-dir)]
    (write-file! root "index.html" "<app>")
    (doseq [uri ["/../secret" "/%2e%2e/secret" "/a/../../secret"]]
      (testing uri
        (let [response ((static/handler root) (request uri))]
          (is (not= 200 (:status response))))))))

(deftest unsupported-method-is-405
  (let [root (temp-dir)]
    (is (= 405 (:status ((static/handler root)
                         (request "/index.html" :post)))))))
