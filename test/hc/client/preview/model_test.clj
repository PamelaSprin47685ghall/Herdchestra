(ns hc.client.preview.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.preview.model :as model]))

(deftest initial-preview-is-closed
  (let [state (model/initial-state)]
    (is (= :idle (:status state)))
    (is (empty? (:allowed-origins state)))
    (is (nil? (:external state)))))

(deftest first-content-shows-even-while-streaming
  (let [state (model/content-arrived (model/initial-state)
                                     "<p>first</p>"
                                     true)]
    (is (= :ready (:status state)))
    (is (= "<p>first</p>" (:html state)))
    (is (nil? (:pending-version state)))))

(deftest streaming-holds-later-content
  (let [first (model/content-arrived (model/initial-state) "<p>one</p>" true)
        second (model/content-arrived first "<p>two</p>" true)]
    (is (= "<p>one</p>" (:html second)))
    (is (= "<p>two</p>" (:pending-version second)))
    (is (nil? (:notice second)))
    (testing "ending the stream installs the held document"
      (let [done (model/content-arrived second "<p>two</p>" false)]
        (is (= "<p>two</p>" (:html done)))
        (is (nil? (:pending-version done)))))))

(deftest dirty-preview-requires-explicit-switch
  (let [state (-> (model/initial-state)
                  (model/content-arrived "<p>one</p>" false)
                  model/mark-input)
        next (model/content-arrived state "<p>two</p>" false)]
    (is (= "<p>one</p>" (:html next)))
    (is (= "<p>two</p>" (:pending-version next)))
    (is (re-find #"新版本" (:notice next)))
    (let [accepted (model/accept-pending-version next)]
      (is (= "<p>two</p>" (:html accepted)))
      (is (nil? (:pending-version accepted)))
      (is (not (:dirty? accepted)))))
  (testing "dismiss keeps the old document"
    (let [state (-> (model/initial-state)
                    (model/content-arrived "<p>one</p>" false)
                    model/mark-input
                    (model/content-arrived "<p>two</p>" false)
                    model/dismiss-pending-version)]
      (is (= "<p>one</p>" (:html state)))
      (is (nil? (:pending-version state))))))

(deftest new-source-resets-network-permission
  (let [state (-> (model/initial-state)
                  (model/content-arrived "<script src=\"https://cdn.example.com/a.js\"></script>" false)
                  (model/allow-origins ["https://cdn.example.com"]))
        replaced (model/load-success state "<script src=\"https://other.example.com/a.js\"></script>")]
    (is (= ["https://cdn.example.com"] (:allowed-origins state)))
    (is (empty? (:allowed-origins replaced)))
    (is (= ["https://other.example.com"] (model/pending-origins replaced)))))

(deftest pending-origins-exclude-allowed
  (let [state (-> (model/initial-state)
                  (model/content-arrived
                   "<img src=\"https://a.example/x\"><img src=\"https://b.example/y\">"
                   false)
                  (model/allow-origins ["https://a.example"]))]
    (is (= ["https://b.example"] (model/pending-origins state)))))

(deftest external-request-policy
  (testing "http and https are the only externally openable schemes"
    (is (true? (:supported? (model/external-request :link {:url "https://example.com/a"}))))
    (is (false? (:supported? (model/external-request :link {:url "javascript:alert(1)"}))))
    (is (false? (:supported? (model/external-request :link {:url "mailto:a@b.c"})))))
  (testing "downloads keep inline data and filename"
    (let [request (model/external-request :download
                                          {:filename "a.txt"
                                           :data "data:text/plain,hi"})]
      (is (= "a.txt" (:filename request)))
      (is (= "data:text/plain,hi" (:data request)))))
  (testing "a data-URL download is confirmed as inline content, not opened"
    (let [request (model/external-request :download
                                          {:filename "a.txt"
                                           :url "data:text/plain,hi"})]
      (is (nil? (:url request)))
      (is (= "data:text/plain,hi" (:data request)))
      (is (false? (:supported? request)))))
  (testing "form submissions keep method and field names"
    (let [request (model/external-request :submit
                                          {:url "https://example.com/f"
                                           :method "post"
                                           :fields [{:name "q" :value "x"}]})]
      (is (= "POST" (:method request)))
      (is (= "q" (get-in request [:fields 0 :name])))
      (is (= 1 (get-in request [:fields 0 :value-length])))
      (is (nil? (get-in request [:fields 0 :value])))))
  (testing "model calls are refused with an explanation"
    (is (= :model (:kind (model/external-request :model {}))))
    (is (re-find #"不能直接调用模型"
                 (:reason (model/external-request :model {}))))))

(deftest source-helpers
  (is (= "/blobs/abc" (model/fetch-url {:content-id "abc"})))
  (is (= "https://example.com/a.html"
         (model/fetch-url {:url "https://example.com/a.html"
                           :content-id "abc"})))
  (is (nil? (model/fetch-url {})))
  (is (re-find #"HTTP" (model/error-summary {:code :HTTP_ERROR
                                              :message "HTTP 500"}))))
