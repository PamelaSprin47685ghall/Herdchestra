(ns hc.server.search.retrieve-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.search :as search]
            [hc.server.search.http :as http]
            [hc.server.search.support :as support]))

(defn- reference-set
  [urls]
  (let [result (search/add-search-results
                (search/new-reference-set)
                {:query-id "q1"
                 :fetched-at "2026-11-03T09:00:00Z"
                 :results (mapv (fn [url]
                                  {:url url
                                   :title "Saved title"
                                   :snippet "Saved snippet"
                                   :content-status :summary})
                                urls)})]
    (when-not (:ok? result)
      (throw (ex-info "could not build reference set" result)))
    (:refs result)))

(defn- fetch
  [service refs url & [extra]]
  (search/fetch! service
                 (merge {:url url
                         :refs refs
                         :documents {}
                         :read-at "2026-11-03T10:30:00Z"}
                        extra)))

(def html-page
  (str "<html><head><title>Alpha &amp; Beta</title>"
       "<script>evil()</script><style>.hidden{}</style></head>"
       "<body><h1>Heading</h1>"
       "<p>Hello&nbsp;world &#169; 2026.</p>"
       "<div>Second line</div></body></html>"))

(defn- routes
  []
  {"/page" (fn [exchange _]
             (support/response! exchange 200 html-page "text/html"))
   "/big" (fn [exchange _]
            (support/response! exchange 200
                               (str "<html><body>"
                                    (apply str (repeat 5000 "x"))
                                    "</body></html>")
                               "text/html"))
   "/blocked" (fn [exchange _]
                (support/response! exchange 403 "forbidden" "text/plain"))
   "/missing" (fn [exchange _]
                (support/response! exchange 404 "missing" "text/plain"))
   "/redirect" (fn [exchange _]
                 (.set (.getResponseHeaders exchange) "location" "/page")
                 (support/response! exchange 302 "" "text/plain"))
   "/binary" (fn [exchange _]
               (support/response! exchange 200 "pdf" "application/pdf"))
   "/plain" (fn [exchange _]
              (support/response! exchange 200 "Plain text body." "text/plain"))
   "/error" (fn [exchange _]
              (support/response! exchange 500 "boom" "text/plain"))})

(deftest full-html-read-keeps-dates-separate-and-sends-no-credential
  (let [server (support/start-server (routes))
        page (support/url server "/page")
        refs (reference-set [page])
        service (support/search-service
                 {:services [(support/brave-service (:base server))]})]
    (try
      (let [result (fetch service refs page)
            document (:document result)
            request (first (support/requests server))
            source (get-in result [:refs :sources page])]
        (is (:ok? result))
        (is (= :full (:status result)))
        (is (= "Alpha & Beta" (:title document)))
        (is (str/includes? (:text document) "Heading"))
        (is (str/includes? (:text document) "Hello world © 2026."))
        (is (str/includes? (:text document) "Second line"))
        (is (not (str/includes? (:text document) "evil()")))
        (is (= :full (:content-status source)))
        (is (= "2026-11-03T09:00:00Z" (:fetched-at source)))
        (is (= "2026-11-03T10:30:00Z" (:read-at source)))
        (is (nil? (:published-at source)))
        (is (nil? (get-in request [:headers "authorization"])))
        (is (nil? (get-in request [:headers "x-subscription-token"])))
        (is (= 1 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest truncated-read-is-marked-partial
  (let [server (support/start-server (routes))
        page (support/url server "/big")
        refs (reference-set [page])
        service (support/search-service {})]
    (try
      (let [result (fetch service refs page {:max-chars 100})]
        (is (:ok? result))
        (is (= :partial (:status result)))
        (is (true? (:truncated? result)))
        (is (= 100 (get-in result [:document :length])))
        (is (= :partial
               (get-in result [:refs :sources page :content-status]))))
      (finally
        (support/stop! server)))))

(deftest blocked-read-keeps-saved-title-and-snippet
  (let [server (support/start-server (routes))
        page (support/url server "/blocked")
        refs (reference-set [page])
        service (support/search-service {})]
    (try
      (let [result (fetch service refs page)
            source (get-in result [:refs :sources page])]
        (is (:ok? result))
        (is (= :blocked (:status result)))
        (is (= :access-denied (:reason result)))
        (is (nil? (:document result)))
        (is (= :summary (:content-status source))
            "a snippet remains a summary; a blocked read does not erase it")
        (is (= "2026-11-03T10:30:00Z" (:read-at source)))
        (is (= "Saved title" (:title source)))
        (is (= "Saved snippet" (:snippet source))))
      (finally
        (support/stop! server)))))

(deftest unknown-url-is-refused-before-any-request
  (let [server (support/start-server (routes))
        page (support/url server "/page")
        refs (reference-set [page])
        service (support/search-service {})]
    (try
      (let [result (fetch service refs (support/url server "/other"))]
        (is (false? (:ok? result)))
        (is (= :SEARCH_SOURCE_UNKNOWN (get-in result [:error :code])))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest private-address-is-refused-by-default
  (let [server (support/start-server (routes))
        page (support/url server "/page")
        refs (reference-set [page])
        service (support/search-service {} {:allow-private-hosts? false})]
    (try
      (let [result (fetch service refs page)]
        (is (false? (:ok? result)))
        (is (= :SEARCH_FETCH_PRIVATE_HOST (get-in result [:error :code])))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest an-already-full-document-is-not-fetched-again
  (let [server (support/start-server (routes))
        page (support/url server "/page")
        refs (reference-set [page])
        service (support/search-service {})
        documents {page {:text "Saved full text"
                         :length 15
                         :truncated? false
                         :status :full}}]
    (try
      (let [result (fetch service refs page {:documents documents})]
        (is (:ok? result))
        (is (= :full (:status result)))
        (is (true? (:already-read? result)))
        (is (= 0 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest a-partial-document-is-refetched
  (let [server (support/start-server (routes))
        page (support/url server "/page")
        refs (reference-set [page])
        service (support/search-service {})
        documents {page {:text "partial text"
                         :length 12
                         :truncated? false
                         :status :partial}}]
    (try
      (let [result (fetch service refs page {:documents documents})]
        (is (:ok? result))
        (is (not (true? (:already-read? result))))
        (is (= 1 (support/request-count server))))
      (finally
        (support/stop! server)))))

(deftest redirect-is-refused
  (let [server (support/start-server (routes))
        page (support/url server "/redirect")
        refs (reference-set [page])
        service (support/search-service {})]
    (try
      (let [result (fetch service refs page)]
        (is (false? (:ok? result)))
        (is (= :SEARCH_FETCH_REDIRECT_REFUSED (get-in result [:error :code])))
        (is (= page (get-in result [:error :details :url])))
        (is (= :summary (get-in refs [:sources page :content-status])))
        (is (= "Saved title" (get-in refs [:sources page :title]))))
      (finally
        (support/stop! server)))))

(deftest unsupported-content-type-keeps-the-source-unchanged
  (let [server (support/start-server (routes))
        page (support/url server "/binary")
        refs (reference-set [page])
        service (support/search-service {})]
    (try
      (let [result (fetch service refs page)]
        (is (false? (:ok? result)))
        (is (= :SEARCH_FETCH_UNSUPPORTED (get-in result [:error :code])))
        (is (= :summary (get-in refs [:sources page :content-status]))))
      (finally
        (support/stop! server)))))

(deftest server-error-leaves-the-source-unchanged
  (let [server (support/start-server (routes))
        page (support/url server "/error")
        refs (reference-set [page])
        service (support/search-service {})]
    (try
      (let [result (fetch service refs page)]
        (is (false? (:ok? result)))
        (is (= :SEARCH_FETCH_FAILED (get-in result [:error :code])))
        (is (= :summary (get-in refs [:sources page :content-status]))))
      (finally
        (support/stop! server)))))

(deftest plain-text-is-read-verbatim
  (let [server (support/start-server (routes))
        page (support/url server "/plain")
        refs (reference-set [page])
        service (support/search-service {})]
    (try
      (let [result (fetch service refs page)]
        (is (:ok? result))
        (is (= :full (:status result)))
        (is (= "Plain text body." (get-in result [:document :text])))
        (is (= :verbatim (get-in result [:document :extraction]))))
      (finally
        (support/stop! server)))))

(deftest response-body-read-is-bounded-by-deadline
  (let [server (java.net.ServerSocket. 0)
        port (.getLocalPort server)
        worker (future
                 (try
                   (with-open [socket (.accept server)]
                     (let [out (.getOutputStream socket)]
                       (.write out (.getBytes
                                    (str "HTTP/1.1 200 OK\r\n"
                                         "Content-Type: text/plain\r\n"
                                         "Content-Length: 100\r\n\r\n")
                                    "US-ASCII"))
                       (.flush out)
                       (Thread/sleep 3000)))
                   (catch Throwable _ nil)))]
    (try
      (let [started (System/nanoTime)
            result (http/send! {:client (http/default-client)
                                :uri (str "http://127.0.0.1:" port "/")
                                :timeout-ms 150
                                :max-bytes 10})]
        (is (= :SEARCH_TIMEOUT (get-in result [:error :code])))
        (is (< (/ (- (System/nanoTime) started) 1000000.0) 2000.0)))
      (finally
        (.close server)
        (future-cancel worker)))))
