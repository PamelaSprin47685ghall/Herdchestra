(ns hc.client.miniapps.api-test
  "Node tests for the mini-app generation HTTP client.

  These tests exercise the real `generate!` path with a stubbed fetch: one
  success response, one client deadline that aborts a stalled request.  They
  do not replace the browser E2E; they pin the contract the panel relies on."
  (:require [cljs.test :refer-macros [deftest is testing async run-tests]]
            [hc.client.miniapps.api :as api]))

(defn- response
  [status text]
  #js {:status status
       :text (fn [] (js/Promise.resolve text))})

(deftest generate-success-reads-the-edn-receipt
  (async done
         (let [original (.-fetch js/globalThis)
               seen (atom nil)
               result (atom nil)]
           (try
             (set! (.-fetch js/globalThis)
                   (fn [url options]
                     (reset! seen {:url url :body (.-body options)})
                     (js/Promise.resolve
                      (response 200 "{:ok? true :app {:app-id \"app-1\"}}"))))
             (-> (api/generate! "记录读书条目" "miniapps/reading.html"
                                {:timeout-ms 2000}
                                #(reset! result %))
                 (.then
                  (fn [_]
                    (is (= "/api/workspace/miniapp-generate" (:url @seen)))
                    (is (re-find #"记录读书条目" (:body @seen)))
                    (is (true? (:ok? @result)))
                    (is (= "app-1" (get-in @result [:app :app-id])))
                    (is (= 200 (:http-status @result)))))
                 (.finally
                  (fn []
                    (set! (.-fetch js/globalThis) original)
                    (done))))
             (catch :default error
               (set! (.-fetch js/globalThis) original)
               (is false (str error))
               (done))))))

(deftest generate-deadline-aborts-a-stalled-request
  (async done
         (let [original (.-fetch js/globalThis)
               result (atom nil)]
           (try
             (set! (.-fetch js/globalThis)
                   (fn [_url options]
                     (js/Promise.
                      (fn [_resolve reject]
                        (.addEventListener
                         (.-signal options) "abort"
                         (fn []
                           (let [error (if (exists? js/DOMException)
                                         (js/DOMException. "aborted" "AbortError")
                                         (js/Error. "aborted"))]
                             (reject error))))))))
             (-> (api/generate! "慢请求" "miniapps/slow.html"
                                {:timeout-ms 30}
                                #(reset! result %))
                 (.then
                  (fn [_]
                    (is (false? (:ok? @result)))
                    (is (= :GENERATE_TIMEOUT (get-in @result [:error :code])))
                    (is (re-find #"已有文件仍可查看"
                                 (get-in @result [:error :message])))))
                 (.finally
                  (fn []
                    (set! (.-fetch js/globalThis) original)
                    (done))))
             (catch :default error
               (set! (.-fetch js/globalThis) original)
               (is false (str error))
               (done))))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.miniapps.api-test))

(set! *main-cli-fn* -main)
