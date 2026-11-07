(ns hc.client.miniapps.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.miniapps.model :as model]))

(deftest path-rules
  (is (model/html-path? "apps/reading.html"))
  (is (model/html-path? "apps/reading.HTM"))
  (is (model/html-path? "apps/reading.xhtml"))
  (is (not (model/html-path? "apps/reading.txt")))
  (is (model/safe-workspace-path? "apps/reading.html"))
  (is (model/safe-workspace-path? "apps/中文.html"))
  (is (not (model/safe-workspace-path? "")))
  (is (not (model/safe-workspace-path? "/etc/passwd")))
  (is (not (model/safe-workspace-path? "../secret.html")))
  (is (not (model/safe-workspace-path? "apps/../../secret.html")))
  (is (not (model/safe-workspace-path? "C:/Windows/secret.html")))
  (is (not (model/safe-workspace-path? "C:secret.html")))
  (is (= "apps/reading.html" (model/normalize-path "./apps/reading.html")))
  (is (= "apps/reading.html" (model/normalize-path "/apps/reading.html")))
  (is (= "reading.html" (model/app-name-from-path "apps/reading.html"))))

(deftest content-fingerprint
  (let [a (model/content-digest "<p>hello</p>")
        b (model/content-digest "<p>hello</p>")
        c (model/content-digest "<p>hello!</p>")]
    (is (= a b))
    (is (not= a c))
    (is (= 8 (count a)))
    (is (re-matches #"[0-9a-f]{8}" a))))

(deftest declared-data-version
  (is (= 2 (model/declared-data-version
            "<meta name=\"herdchestra-data-version\" content=\"2\">")))
  (is (= 2 (model/declared-data-version
            "<meta content=\"2\" name=\"herdchestra-data-version\">")))
  (is (= 1 (model/declared-data-version "<p>no declaration</p>")))
  (is (= 1 (model/declared-data-version
            "<meta name=\"herdchestra-data-version\" content=\"two\">"))))

(deftest upgrade-decision
  (let [app {:data-version 2 :source-digest (model/content-digest "old")}]
    (testing "unchanged"
      (let [info (model/upgrade-info app "old")]
        (is (false? (:changed? info)))
        (is (= :unchanged (:compatibility info)))))
    (testing "same declared version is compatible"
      (let [info (model/upgrade-info
                  app
                  "<meta name=\"herdchestra-data-version\" content=\"2\">new")]
        (is (true? (:changed? info)))
        (is (= :compatible (:compatibility info)))))
    (testing "higher declared version is unknown, not auto-migrated"
      (let [info (model/upgrade-info
                  app
                  "<meta name=\"herdchestra-data-version\" content=\"3\">new")]
        (is (= :unknown (:compatibility info)))
        (is (= 3 (:declared-version info)))
        (is (= 2 (:previous-version info)))))
    (testing "lower declared version is a downgrade"
      (let [info (model/upgrade-info
                  app
                  "<meta name=\"herdchestra-data-version\" content=\"1\">new")]
        (is (= :downgrade (:compatibility info)))))))

(deftest grant-scopes
  (let [grants [{:kind :file :path "notes/a.txt"}
                {:kind :directory :path "notes/dir"}]]
    (is (model/grant-allows? grants "notes/a.txt"))
    (is (not (model/grant-allows? grants "notes/b.txt")))
    (is (model/grant-allows? grants "notes/dir"))
    (is (model/grant-allows? grants "notes/dir/a.txt"))
    (is (not (model/grant-allows? grants "notes/dir2/a.txt")))
    (is (not (model/grant-allows? grants "../notes/a.txt")))))

(deftest grant-merge-and-revoke
  (let [grants (model/merge-grants []
                                   [{:kind :file :path "a.txt"}
                                    {:kind :file :path "a.txt"}
                                    {:kind :directory :path "dir"}])]
    (is (= 2 (count grants)))
    (let [revoked (model/revoke-grant grants "a.txt" :file)]
      (is (= 1 (count revoked)))
      (is (= :directory (:kind (first revoked)))))))

(deftest data-summary-and-snapshot
  (let [entries [{:key "a" :value "hello" :updated-at "2026-01-01T00:00:00Z"}
                 {:key "b" :value "你好" :updated-at "2026-01-02T00:00:00Z"}]
        summary (model/data-summary entries)]
    (is (= 2 (:keys summary)))
    (is (= (+ 1 5 1 6) (:bytes summary)))
    (is (= "2026-01-02T00:00:00Z" (:last-updated-at summary))))
  (testing "large snapshots are omitted, never truncated"
    (let [entries [{:key "a" :value (apply str (repeat 600 "x"))}]
          snapshot (model/storage-snapshot entries 100)]
      (is (true? (:omitted? snapshot)))
      (is (true? (:too-large? snapshot)))
      (is (= {} (:data snapshot)))))
  (testing "data above the old 512 KiB warning is still injected in full"
    (let [value (apply str (repeat (* 600 1024) "x"))
          snapshot (model/storage-snapshot [{:key "big" :value value}])]
      (is (false? (:omitted? snapshot)))
      (is (false? (:too-large? snapshot)))
      (is (true? (:large? snapshot)))
      (is (= value (get-in snapshot [:data "big"])))))
  (testing "small snapshots keep every key"
    (let [snapshot (model/storage-snapshot [{:key "a" :value "1"}] 1000)]
      (is (false? (:omitted? snapshot)))
      (is (false? (:too-large? snapshot)))
      (is (= {"a" "1"} (:data snapshot))))))

(deftest export-document
  (let [app {:app-id "app-1" :name "reading.html" :workspace-root "/w"
             :path "apps/reading.html" :data-version 2}
        doc (model/export-document app [{:key "a" :value "1"}])]
    (is (= "herdchestra-miniapp-data" (:format doc)))
    (is (= {"a" "1"} (:data doc)))
    (is (= "app-1" (get-in doc [:app :app-id])))
    (is (re-find #"reading-data\.json" (model/export-filename app)))))

(deftest network-state
  (let [app {:network-allowed ["https://a.example"]}
        state (model/network-state app
                                   "<script src=\"https://a.example/a.js\"></script>
                                    <script src=\"https://b.example/b.js\"></script>")]
    (is (= ["https://a.example"] (:allowed-origins state)))
    (is (= ["https://b.example"] (:pending-origins state))))
  (let [app (-> {:network-allowed []}
                (model/allow-origins ["https://b.example" "https://a.example"]))
        app (model/revoke-origin app "https://a.example")]
    (is (= ["https://b.example"] (:network-allowed app)))))

(deftest new-app-identity
  (let [app (model/new-app "/workspace" "apps/reading.html"
                           "<meta name=\"herdchestra-data-version\" content=\"3\">")]
    (is (re-find #"^app-" (:app-id app)))
    (is (= "apps/reading.html" (:path app)))
    (is (= "reading.html" (:name app)))
    (is (= 3 (:data-version app)))
    (is (= 1 (:source-version app)))
    (is (= :available (:status app)))))


(deftest workspace-and-session-grant-isolation
  (let [apps [{:app-id "a" :workspace-root "/w1"}
              {:app-id "b" :workspace-root "/w2"}
              {:app-id "c" :workspace-root "/w1"}]
        grants [{:app-id "a" :kind :file :path "x" :mode :session}
                {:app-id "b" :kind :file :path "x" :mode :session}]]
    (is (= ["a" "c"] (mapv :app-id (model/apps-in-workspace apps "/w1"))))
    (is (= [] (model/apps-in-workspace apps nil)))
    (is (= ["a"] (mapv :app-id (model/grants-for-app grants "a"))))
    (is (= 2 (count (model/merge-grants [] grants))))
    (is (= #{"a" "b"}
           (set (map :app-id (model/merge-grants [] grants)))))))

(deftest submit-request-redacts-values
  (let [request (model/external-request
                 :submit
                 {:url "https://example.com/f"
                  :fields [{:name "password" :value "secret" :sensitive? true}
                           {:name "q" :value "hello"}]})]
    (is (= "password" (get-in request [:fields 0 :name])))
    (is (true? (get-in request [:fields 0 :sensitive?])))
    (is (nil? (get-in request [:fields 0 :value])))
    (is (= 5 (get-in request [:fields 1 :value-length])))
    (is (nil? (get-in request [:fields 1 :value])))))
