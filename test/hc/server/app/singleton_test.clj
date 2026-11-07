(ns hc.server.app.singleton-test
  "Machine-wide instance lock and loopback control channel."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.singleton :as singleton])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  []
  (str (Files/createTempDirectory "hc-singleton-" (make-array FileAttribute 0))))

(defn- delete-tree!
  [root]
  (doseq [file (reverse (file-seq (clojure.java.io/file root)))]
    (try (.delete ^java.io.File file) (catch Exception _ nil))))

(deftest lock-is-exclusive-and-release-reopens-it
  (let [dir (temp-dir)]
    (try
      (let [first (singleton/acquire! {:dir dir})
            second (singleton/acquire! {:dir dir})]
        (is (true? (:acquired? first)))
        (is (false? (:acquired? second))
            "a second process cannot take the same machine lock")
        (singleton/release! first)
        (let [third (singleton/acquire! {:dir dir})]
          (is (true? (:acquired? third)))
          (singleton/release! third)))
      (finally (delete-tree! dir)))))

(deftest control-server-round-trip-and-token
  (let [dir (temp-dir)
        handler (fn [request]
                  (case (:op request)
                    :ping {:ok? true :pong true}
                    {:ok? true :echo request}))]
    (try
      (let [server (singleton/start-server! handler {:dir dir})
            state (:state server)]
        (is (true? (:ok? server)))
        (is (= {:ok? true :pong true}
               (singleton/send! state {:op :ping})))
        (testing "a wrong token is refused"
          (is (= :SINGLETON_UNAUTHORIZED
                 (get-in (singleton/send! (assoc state :token "wrong")
                                          {:op :ping})
                         [:error :code]))))
        (testing "the state file names the live endpoint"
          (is (= (:port state) (:port (singleton/read-state dir)))))
        (singleton/stop-server! server)
        (is (nil? (singleton/read-state dir))
            "stopping the primary removes its endpoint state"))
      (finally (delete-tree! dir)))))

(deftest send-until-ready-waits-for-a-starting-instance
  (let [dir (temp-dir)]
    (try
      (future
        (Thread/sleep 150)
        (singleton/start-server! (fn [_] {:ok? true :ready true}) {:dir dir}))
      (is (= {:ok? true :ready true}
             (singleton/send-until-ready! dir {:op :ping} 5000)))
      (finally (delete-tree! dir)))))

(deftest published-endpoint-is-atomic-and-has-no-temp-residue
  (let [dir (temp-dir)]
    (try
      (let [server (singleton/start-server! (fn [_] {:ok? true}) {:dir dir})
            state (singleton/read-state dir)
            residues (->> (clojure.java.io/file dir)
                          file-seq
                          (map #(.getName ^java.io.File %))
                          (filter #(clojure.string/starts-with? % ".instance-"))
                          vec)]
        (is (= (:port (:state server)) (:port state)))
        (is (string? (:token state)))
        (is (integer? (:pid state)))
        (is (empty? residues)
            "a successful publish leaves no half-written temp file")
        (singleton/stop-server! server)
        (is (nil? (singleton/read-state dir))))
      (finally (delete-tree! dir)))))
