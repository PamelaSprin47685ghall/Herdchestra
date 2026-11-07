(ns hc.client.files.ui-smoke
  "Node smoke renderer for the workspace file browser.

  Renders the real React element tree with `react-dom/server` and prints
  FOUND/MISSING checks.  This is a verification runner, not a production
  namespace."
  (:require ["react-dom/server" :as rds]
            [clojure.string :as str]
            [hc.client.files.model :as model]
            [hc.client.files.ui :as ui]))

(def fixture
  {:status :loaded
   :path "sub"
   :parent ""
   :workspace {:root "/workspace" :name "workspace"}
   :entries [{:name "nested" :path "sub/nested" :kind :directory
              :exists? true :stamp-applicable? false
              :mtime-sec 1700000000 :mtime-nsec 0}
             {:name "alpha.txt" :path "sub/alpha.txt" :kind :file
              :exists? true :previewable? true :stamp-applicable? true
              :size 12 :mtime-sec 1700000001 :mtime-nsec 123000000
              :stamp {:mtime-sec 1700000001 :mtime-nsec 123000000 :size 12}}
             {:name "outside-link" :path "sub/outside-link" :kind :symlink-outside
              :exists? true :outside? true :symlink? true
              :stamp-applicable? false}
             {:name "blob.bin" :path "sub/blob.bin" :kind :file
              :exists? true :previewable? false :binary? true
              :reason-label "二进制文件"
              :stamp-applicable? true}]
   :total-entries 3
   :truncated? false
   :selected-path "sub/alpha.txt"
   :preview {:path "sub/alpha.txt"
             :text "one\nTWO\n"
             :size 8
             :kind :file
             :stamp {:mtime-sec 1700000002 :mtime-nsec 0 :size 8}}
   :preview-status :loaded
   :diff {:changed? true
          :coarse? false
          :too-large? false
          :summary {:added 1 :removed 1}
          :base-stamp {:mtime-sec 1700000001 :mtime-nsec 0 :size 8}
          :current-stamp {:mtime-sec 1700000002 :mtime-nsec 0 :size 8}
          :hunks [{:header {:old-start 1 :old-count 3 :new-start 1 :new-count 3}
                   :lines [{:op :equal :text "one" :old-line 1 :new-line 1}
                           {:op :remove :text "two" :old-line 2 :new-line nil}
                           {:op :add :text "TWO" :old-line nil :new-line 2}
                           {:op :equal :text "" :old-line 3 :new-line 3}]}]}
   :search-query "alpha"
   :search {:status :loaded
            :query "alpha"
            :mode :name
            :results [{:name "alpha.txt" :path "sub/alpha.txt" :kind :file
                       :exists? true :stamp {:mtime-sec 1 :mtime-nsec 0 :size 1}}]
            :truncated? false}})

(defn- check [label present?]
  (println (if present? "FOUND" "MISSING") label)
  (when-not present? (reset! (.-failed js/globalThis) true)))

(defn- has? [text needle]
  (str/includes? text needle))

(defn -main [& _]
  (set! (.-failed js/globalThis) false)
  (let [html (rds/renderToStaticMarkup
              (ui/file-browser-element fixture {}))]
    (check "title" (has? html "工作区文件"))
    (check "breadcrumb" (has? html "sub"))
    (check "directory row" (has? html "nested"))
    (check "file row" (has? html "alpha.txt"))
    (check "directory has no content stamp" (has? html "目录无内容 stamp"))
    (check "exact stamp" (has? html "mtime 1700000001.123000000"))
    (check "outside symlink label" (has? html "链接（工作区外）"))
    (check "diff heading" (has? html "与上次读取的差异"))
    (check "added marker" (has? html "+"))
    (check "removed marker" (has? html "−"))
    (check "accessible added label" (has? html "新增行"))
    (check "accessible removed label" (has? html "删除行"))
    (check "stamp before/after" (has? html "旧 mtime"))
    (check "search input label" (has? html "按名称搜索工作区"))
    (check "no fake delete action" (not (has? html "删除文件")))
    (check "no fake new action" (not (has? html "新建文件")))
    (check "read-only note" (has? html "本视图只读"))
  (check "binary reason" (has? html "二进制文件")))
  (let [conflict-html
        (rds/renderToStaticMarkup
         (ui/file-browser-element
          (-> fixture
              (assoc :preview-status :error
                     :preview-error
                     {:ok? false
                      :error {:code :FILE_CONFLICT
                              :message "changed"
                              :details {:expected {:mtime-sec 1 :mtime-nsec 0 :size 4}
                                        :current {:mtime-sec 2 :mtime-nsec 0 :size 5}}}}))
          {}))]
    (check "conflict alert" (has? conflict-html "文件在读取期间发生变化"))
    (check "conflict expected stamp" (has? conflict-html "原 stamp"))
    (check "conflict current stamp" (has? conflict-html "当前 stamp"))
    (check "conflict retry" (has? conflict-html "重新读取")))
  (let [stamp-html (rds/renderToStaticMarkup
                    (ui/file-browser-element
                     (assoc fixture
                            :diff {:changed? false
                                   :base-stamp {:mtime-sec 1 :mtime-nsec 0 :size 8}
                                   :current-stamp {:mtime-sec 2 :mtime-nsec 0 :size 8}
                                   :hunks []})
                     {}))]
    (check "stamp-only diff" (has? stamp-html "文本未变化"))
    (check "stamp-only old stamp" (has? stamp-html "旧 mtime")))
  (let [error-html (rds/renderToStaticMarkup
                    (ui/file-browser-element
                     (assoc fixture
                            :preview-status :error
                            :preview-error {:ok? false
                                            :error {:code :FILE_CONFLICT
                                                    :message "changed"
                                                    :details {:expected {:mtime-sec 1 :mtime-nsec 0 :size 8}
                                                              :current {:mtime-sec 2 :mtime-nsec 0 :size 8}}}})
                     {}))]
    (check "error keeps previous preview" (has? error-html "上次成功读取的内容"))
    (check "error keeps previous text" (has? error-html "one")))
  (let [empty-html (rds/renderToStaticMarkup
                    (ui/file-browser-element (model/initial) {}))]
    (check "empty listing" (has? empty-html "此目录为空")))
  (when (.-failed js/globalThis)
    (throw (js/Error. "file browser smoke checks failed")))
  (println "FILES-UI-SMOKE-OK"))

(set! *main-cli-fn* -main)
