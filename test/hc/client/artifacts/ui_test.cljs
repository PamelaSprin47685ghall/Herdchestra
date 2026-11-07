(ns hc.client.artifacts.ui-test
  "ClojureScript render tests for the artifact workbench cards.

  The tests render the real React element tree with `react-dom/server`; they
  do not inspect source strings.  A card without a content ID or with a
  missing blob must not produce a download link."
  (:require ["react-dom/server" :as rds]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.artifacts :as artifacts]
            [hc.client.artifacts.view :as view]))

(def fixture
  {:id "art-1"
   :name "report.txt"
   :filename "report.txt"
   :kind :file
   :media-type "text/plain"
   :content-id "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :size 2048
   :status :available
   :environment :instance
   :project-id "project-1"
   :workspace-root "/work"
   :source-path "/work/out/report.txt"
   :relative-path "out/report.txt"
   :source-task-id "task-1"
   :source-call-id "call-1"
   :version 1
   :storage/version 9
   :origin {:stamp {:mtime-sec 100 :mtime-nsec 200 :size 2048}}
   :inputs [{:kind :file :path "data.csv"}]
   :checks [{:check :blob-exists :ok? true}
            {:check :encoding :ok? true}]})

(defn- html
  ([artifact] (html artifact {}))
  ([artifact extra]
   (rds/renderToStaticMarkup
    (view/artifacts-list (merge {:artifacts [(artifacts/card artifact)]
                                 :on-copy identity}
                                extra)))))

(deftest available-artifact-renders-file-identity-and-actions
  (let [markup (html fixture)]
    (testing "the card states the file identity required by §8.5"
      (is (str/includes? markup "report.txt"))
      (is (str/includes? markup "/work/out/report.txt"))
      (is (str/includes? markup "out/report.txt"))
      (is (str/includes? markup "2 KiB"))
      (is (str/includes? markup "修改时间"))
      (is (str/includes? markup "text/plain"))
      (is (str/includes? markup "实例工作区"))
      (is (str/includes? markup "来源任务 task-1"))
      (is (str/includes? markup "项目 project-1"))
      (is (str/includes? markup "工作区 /work"))
      (is (str/includes? markup "版本 1"))
      (is (str/includes? markup "可用")))
    (testing "provenance and checks are disclosed"
      (is (str/includes? markup "来源与检查"))
      (is (str/includes? markup "data.csv"))
      (is (str/includes? markup "2/2 项通过")))
    (testing "open and download point at the immutable blob URL"
      (is (str/includes?
           markup
           "/blobs/sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?filename=report.txt"))
      (is (str/includes? markup "打开"))
      (is (str/includes? markup "下载"))
      (is (str/includes? markup "复制内容 ID")))))

(deftest missing-artifact-has-no-download-link
  (let [markup (html (assoc fixture :status :missing))]
    (is (str/includes? markup "内容缺失"))
    (is (str/includes? markup "不可访问"))
    (is (not (str/includes? markup "/blobs/")))
    (is (not (str/includes? markup ">打开<")))
    (is (not (str/includes? markup ">下载<")))))

(deftest no-content-id-has-no-download-link
  (let [markup (html (dissoc fixture :content-id))]
    (is (str/includes? markup "缺少内容 ID"))
    (is (str/includes? markup "不可访问"))
    (is (not (str/includes? markup "/blobs/")))
    (is (not (str/includes? markup ">打开<")))
    (is (not (str/includes? markup ">下载<")))))

(deftest binary-is-download-only
  (let [markup (html (assoc fixture
                            :media-type "application/zip"
                            :kind :binary))]
    (is (str/includes? markup "application/zip"))
    (is (str/includes? markup "下载"))
    (is (not (str/includes? markup ">打开<")))
    (is (str/includes? markup "只提供下载"))))

(deftest truncated-artifact-is-marked-in-the-card
  (let [markup (html (assoc fixture
                            :truncated? true
                            :truncated-reason "artifact limit"))]
    (is (str/includes? markup "已截断"))
    (is (str/includes? markup "artifact limit"))
    (is (str/includes? markup "下载"))
    (is (str/includes? markup "/blobs/"))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.artifacts.ui-test))

(set! *main-cli-fn* -main)


(deftest missing-artifact-shows-real-recovery-hooks-only
  (let [markup (html (assoc fixture :status :missing)
                     {:on-relocate (fn [_] nil)
                      :on-retry (fn [_] nil)})]
    (is (str/includes? markup "重新定位"))
    (is (str/includes? markup "重新生成")))
  (let [markup (html (assoc fixture :status :missing))]
    (is (str/includes? markup "需要服务器命令面支持"))
    (is (not (str/includes? markup ">重新定位<")))))
