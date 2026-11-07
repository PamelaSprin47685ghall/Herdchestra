(ns hc.client.speech.playback-test
  "Node tests for read-aloud stop semantics.

  The player is a module-level singleton, so these tests inject a fake audio
  element and URL implementation, then drive the same `stop!` and
  `stop-if-stale!` functions the message surface calls."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [hc.client.speech.playback :as playback]))

(defn- fake-audio
  []
  (let [calls (atom [])]
    {:calls calls
     :audio #js {:pause (fn [] (swap! calls conj :pause))
                 :removeAttribute (fn [_] (swap! calls conj :removeAttribute))
                 :load (fn [] (swap! calls conj :load))
                 :currentTime 9
                 :play (fn [] (swap! calls conj :play))}}))

(deftest stop-pauses-the-audio-and-invalidates-requests
  (let [{:keys [calls audio]} (fake-audio)
        revoked (atom [])
        original-revoke (.-revokeObjectURL js/URL)
        original-create (.-createObjectURL js/URL)
        before 4]
    (try
      (set! (.-revokeObjectURL js/URL) (fn [url] (swap! revoked conj url)))
      (set! (.-createObjectURL js/URL) (fn [_] "blob:test"))
      (reset! playback/request-seq before)
      (reset! playback/state {:status :playing
                              :speed 1.25
                              :generation before
                              :audio audio
                              :url "blob:test"
                              :message-id "m1"
                              :version-id "v1"})
      (playback/stop!)
      (is (= [:pause :removeAttribute :load] @calls))
      (is (= 0 (.-currentTime audio)))
      (is (= ["blob:test"] @revoked))
      (is (= :idle (:status @playback/state)))
      (is (= 1.25 (:speed @playback/state)))
      (is (> (:generation @playback/state) 4))
      (is (> (:generation @playback/state) before))
      (finally
        (set! (.-revokeObjectURL js/URL) original-revoke)
        (set! (.-createObjectURL js/URL) original-create)
        (reset! playback/request-seq 0)
        (reset! playback/state {:status :idle :speed 1.0 :generation 0
                                :audio nil :url nil})))))

(deftest version-switch-stops-the-old-read-aloud
  (let [{:keys [calls audio]} (fake-audio)]
    (reset! playback/request-seq 9)
    (reset! playback/state {:status :playing
                            :speed 1.0
                            :generation 9
                            :audio audio
                            :url nil
                            :message-id "m1"
                            :version-id "v1"})
    (testing "same visible version keeps playing"
      (playback/stop-if-stale! [{:id "m1" :current-version "v1"}])
      (is (= :playing (:status @playback/state)))
      (is (empty? @calls)))
    (testing "switching the visible version stops the old audio"
      (playback/stop-if-stale! [{:id "m1" :current-version "v2"}])
      (is (= :idle (:status @playback/state)))
      (is (= [:pause :removeAttribute :load] @calls)))))

(deftest missing-message-stops-the-old-read-aloud
  (let [{:keys [calls audio]} (fake-audio)]
    (reset! playback/request-seq 9)
    (reset! playback/state {:status :playing
                            :speed 1.0
                            :generation 9
                            :audio audio
                            :url nil
                            :message-id "m1"
                            :version-id "v1"})
    (playback/stop-if-stale! [{:id "m2" :current-version "v1"}])
    (is (= :idle (:status @playback/state)))
    (is (= [:pause :removeAttribute :load] @calls))))

(deftest version-prop-change-stops-the-old-read-aloud
  (let [{:keys [calls audio]} (fake-audio)]
    (reset! playback/request-seq 9)
    (reset! playback/state {:status :playing
                            :speed 1.0
                            :generation 9
                            :audio audio
                            :url nil
                            :message-id "m1"
                            :version-id "v1"})
    (testing "same version keeps playing"
      (playback/stop-if-version-changed! "m1" "v1")
      (is (= :playing (:status @playback/state)))
      (is (empty? @calls)))
    (testing "a new version prop stops the old audio"
      (playback/stop-if-version-changed! "m1" "v2")
      (is (= :idle (:status @playback/state)))
      (is (= [:pause :removeAttribute :load] @calls)))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(deftest stop-aborts-in-flight-synthesis
  ;; A stop while the TTS request is still loading must abort the fetch, not
  ;; wait for a possibly large Blob and then discard it.
  (let [aborted (atom 0)
        controller #js {:abort (fn [] (swap! aborted inc))}]
    (reset! playback/request-seq 9)
    (reset! playback/state {:status :loading
                            :speed 1.0
                            :generation 9
                            :audio nil
                            :url nil
                            :controller controller})
    (playback/stop!)
    (is (= 1 @aborted))
    (is (nil? (:controller @playback/state)))))

(defn -main
  [& _]
  (run-tests 'hc.client.speech.playback-test))

(set! *main-cli-fn* -main)
