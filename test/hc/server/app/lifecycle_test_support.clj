(ns hc.server.app.lifecycle-test-support
  "Test hygiene for integration tests that start the real application.

  `system/stop!` deliberately keeps the store open when a writer did not
  confirm its stop (root AGENTS.md §5.1 and `app/AGENTS.md` §2).  A test that
  ignores that evidence leaks a Datalevin environment into every later
  namespace, where `hc.server.storage.engine-lifecycle-test` correctly
  refuses to run.  These helpers retry the documented idempotent stop path
  once and require a confirmed store close, so a slow gateway drain fails the
  owning test instead of a later one."
  (:require [clojure.test :refer [is]]
            [hc.server.app.system :as system]
            [hc.server.storage :as storage]))

(defn- store-closed?
  [started]
  (let [store (:store started)]
    (or (nil? store)
        (not (storage/store-open? store)))))

(defn- await-store-closed!
  "Wait up to two seconds for the native handle to disappear."
  [started]
  (loop [attempt 0]
    (cond
      (store-closed? started) true
      (< attempt 100) (do (Thread/sleep 20) (recur (inc attempt)))
      :else false)))

(defn stop-system!
  "Stop STARTED and require evidence that every writer stopped.

  The first call is the normal path.  A second call retries the Sente receive
  loop drain, which can complete just after the first five-second window; the
  documented operator path is to retry stop.  The test fails here, with the
  component failures, rather than in a later namespace's fixture."
  [started]
  (let [first-result (system/stop! started)
        result (if (true? (:stopped? first-result))
                 first-result
                 (system/stop! started))]
    ;; An owner that reports `:stopped? true` has no live writer even when a
    ;; late message left `:drained? false`.  The store may close in that
    ;; case; the product still reports `:stopped? false` so maintenance
    ;; never treats the stop as confirmed.  The test accepts that documented
    ;; shape only when the store really closed and every failure is a dead
    ;; writer.
    (let [writers-gone?
          (and (:store-closed? result)
               (every? (fn [failure]
                         (true? (get-in failure [:error :stopped?])))
                       (:failures result)))]
      (is (or (true? (:stopped? result)) writers-gone?)
          (str "system stop did not confirm: "
               (pr-str (select-keys result [:failures :store-closed?])))))
    (is (await-store-closed! started)
        "the store handle must be released before the next test namespace")
    result))
