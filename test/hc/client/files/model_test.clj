(ns hc.client.files.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.files.model :as model]))

(def ^:private list-result
  {:ok? true
   :path "sub"
   :parent ""
   :workspace {:root "/workspace" :name "workspace"}
   :entries [{:name "zeta.txt" :path "sub/zeta.txt" :kind :file
              :exists? true :previewable? true
              :size 12 :mtime-sec 1700000000 :mtime-nsec 123000000
              :stamp {:mtime-sec 1700000000 :mtime-nsec 123000000 :size 12}}
             {:name "alpha" :path "sub/alpha" :kind :directory
              :exists? true :stamp-applicable? false}
             {:name "Beta.md" :path "sub/Beta.md" :kind :file
              :exists? true :previewable? true :size 3
              :stamp {:mtime-sec 1700000001 :mtime-nsec 0 :size 3}}]
   :total-entries 3
   :truncated? false})

(deftest initial-state-is-empty-and-honest
  (let [state (model/initial)]
    (is (= :idle (:status state)))
    (is (= [] (:entries state)))
    (is (nil? (:preview state)))
    (is (= :name (:search-mode state)))
    (is (= :idle (get-in state [:search :status])))))

(deftest search-mode-defaults-and-switching
  (let [state (model/initial)]
    (is (= :name (:search-mode state)))
    (is (= :content (:search-mode (model/set-search-mode state :content))))
    (is (= :name (:search-mode (model/set-search-mode state :fuzzy))))
    (is (= :content (:search-mode (model/toggle-search-mode state))))
    (is (= :name (:search-mode (model/toggle-search-mode
                                (model/toggle-search-mode state)))))
    (is (= "按名称" (model/search-mode-label :name)))
    (is (= "按内容" (model/search-mode-label :content)))))

(deftest content-search-results-keep-the-hit-location
  (let [state (model/apply-search
               (model/initial)
               {:ok? true :query "mee" :mode :content :truncated? false
                :results [{:name "notes.txt" :path "notes.txt" :kind :file
                           :exists? true :size 41
                           :stamp {:mtime-sec 1 :mtime-nsec 0 :size 41}
                           :line 2 :column 6 :snippet "find MEE here"
                           :match-count 2}]})
        entry (first (get-in state [:search :results]))]
    (is (= :content (get-in state [:search :mode])))
    (is (= 2 (:line entry)))
    (is (= 6 (:column entry)))
    (is (= "find MEE here" (:snippet entry)))
    (is (= 2 (:match-count entry)))))

(deftest search-view-state-is-total-and-distinguishes-empty-from-idle
  (is (= :idle (model/search-view-state {:status :idle})))
  (is (= :idle (model/search-view-state {})))
  (is (= :loading (model/search-view-state {:status :loading})))
  (is (= :error (model/search-view-state {:status :error})))
  (is (= :results (model/search-view-state
                   {:status :loaded
                    :results [{:path "a.txt"}]})))
  (is (= :empty (model/search-view-state
                 {:status :loaded
                  :results []})))
  (is (= :unknown (model/search-view-state {:status :future}))
      "an unknown search status falls through to a visible fallback"))

(deftest idle-mode-switch-keeps-the-search-idle
  (let [state (model/set-search-mode (model/initial) :content)]
    (is (= :content (:search-mode state)))
    (is (= :idle (get-in state [:search :status])))
    (is (= :idle (model/search-view-state (:search state))))))

(deftest breadcrumbs-and-parent
  (is (= [{:label "工作区" :path ""}]
         (model/breadcrumbs "")))
  (is (= [{:label "工作区" :path ""}
          {:label "a" :path "a"}
          {:label "b" :path "a/b"}]
         (model/breadcrumbs "a/b")))
  (is (= "" (model/parent-path "a")))
  (is (= "a" (model/parent-path "a/b")))
  (is (nil? (model/parent-path ""))))

(deftest list-result-sorts-directories-first
  (let [state (model/apply-list (model/initial) list-result)]
    (is (= :loaded (:status state)))
    (is (= "sub" (:path state)))
    (is (= ["alpha" "Beta.md" "zeta.txt"]
           (mapv :name (:entries state))))
    (is (= 3 (:total-entries state)))
    (is (= "workspace" (get-in state [:workspace :name])))
    (testing "regular files keep the exact stamp"
      (is (= {:mtime-sec 1700000000 :mtime-nsec 123000000 :size 12}
             (:stamp (first (filter #(= "zeta.txt" (:name %))
                                    (:entries state)))))))
    (testing "directories carry no content stamp"
      (is (nil? (:stamp (first (filter #(= "alpha" (:name %))
                                       (:entries state)))))))))

(deftest failed-list-keeps-previous-listing
  (let [loaded (model/apply-list (model/initial) list-result)
        failed (model/apply-list loaded
                                 {:ok? false
                                  :error {:code :NETWORK_ERROR
                                          :message "offline"}})]
    (is (= :error (:status failed)))
    (is (= (:entries loaded) (:entries failed)))
    (is (= :NETWORK_ERROR (get-in failed [:error :error :code])))))

(deftest read-baseline-produces-a-real-diff
  (let [first-read {:ok? true :path "a.txt" :text "one\ntwo\n"
                    :size 8 :kind :file
                    :stamp {:mtime-sec 1 :mtime-nsec 0 :size 8}}
        second-read {:ok? true :path "a.txt" :text "one\nTWO\n"
                     :size 8 :kind :file
                     :stamp {:mtime-sec 2 :mtime-nsec 0 :size 8}}
        state (model/apply-read (model/initial) first-read)
        state (model/apply-read state second-read)]
    (is (= :loaded (:preview-status state)))
    (is (= "one\nTWO\n" (get-in state [:preview :text])))
    (is (:changed? (:diff state)))
    (is (= {:added 1 :removed 1} (get-in state [:diff :summary])))
    (is (= {:mtime-sec 1 :mtime-nsec 0 :size 8}
           (get-in state [:diff :base-stamp])))
    (is (= {:mtime-sec 2 :mtime-nsec 0 :size 8}
           (get-in state [:diff :current-stamp])))))

(deftest same-stamp-does-not-invent-a-diff
  (let [read {:ok? true :path "a.txt" :text "same\n" :size 5 :kind :file
              :stamp {:mtime-sec 1 :mtime-nsec 0 :size 5}}
        state (-> (model/initial)
                  (model/apply-read read)
                  (model/apply-read read))]
    (is (nil? (:diff state)))))

(deftest failed-read-keeps-the-last-good-preview
  (let [read {:ok? true :path "a.txt" :text "good\n" :size 5 :kind :file
              :stamp {:mtime-sec 1 :mtime-nsec 0 :size 5}}
        conflict {:ok? false
                  :error {:code :FILE_CONFLICT
                          :message "changed"
                          :details {:path "a.txt"
                                    :expected {:mtime-sec 1 :mtime-nsec 0 :size 5}
                                    :current {:mtime-sec 2 :mtime-nsec 0 :size 6}}}}
        state (-> (model/initial)
                  (model/apply-read read)
                  (model/apply-read conflict))]
    (is (= :error (:preview-status state)))
    (is (= "good\n" (get-in state [:preview :text])))
    (is (= :FILE_CONFLICT (get-in state [:preview-error :error :code])))
    (is (= {:mtime-sec 1 :mtime-nsec 0 :size 5}
           (get-in state [:baseline :stamp])))))

(deftest navigation-clears-the-old-preview
  (let [read {:ok? true :path "a.txt" :text "x" :size 1 :kind :file
              :stamp {:mtime-sec 1 :mtime-nsec 0 :size 1}}
        state (-> (model/initial)
                  (model/apply-read read)
                  (model/navigate "other"))]
    (is (= "other" (:path state)))
    (is (= :loading (:status state)))
    (is (nil? (:preview state)))
    (is (nil? (:baseline state)))
    (is (nil? (:diff state)))))

(deftest search-results-and-truncation
  (let [state (model/apply-search
               (model/initial)
               {:ok? true :query "a" :mode :name :truncated? true
                :results [{:name "a.txt" :path "a.txt" :kind :file
                           :exists? true :stamp {:mtime-sec 1 :mtime-nsec 0 :size 1}}]})
        search (:search state)]
    (is (= :loaded (:status search)))
    (is (= ["a.txt"] (mapv :path (:results search))))
    (is (:truncated? search))
    (is (= :name (:mode search)))
    (let [cleared (:search (model/clear-search state))]
      (is (= :idle (:status cleared)))
      (is (empty? (:results cleared))))))

(deftest display-formatting
  (is (= "—" (model/format-size nil)))
  (is (= "0 B" (model/format-size 0)))
  (is (= "1.0 KiB" (model/format-size 1024)))
  (is (= "1.5 MiB" (model/format-size (* 1024 1024 3/2))))
  (is (string? (model/format-time 1700000000 0)))
  (is (nil? (model/format-time nil)))
  (is (= "mtime 1700000000.123000000 · size 12 B"
         (model/stamp-label {:mtime-sec 1700000000
                             :mtime-nsec 123000000
                             :size 12})))
  (is (= "目录" (model/kind-label :directory)))
  (is (= "文件" (model/kind-label :file))))


(deftest read-rejects-a-different-path-than-requested
  (let [state (assoc (model/initial) :selected-path "a.txt")
        result {:ok? true :path "b.txt" :text "other" :size 5 :kind :file
                :stamp {:mtime-sec 1 :mtime-nsec 0 :size 5}}
        state (model/apply-read state result)]
    (is (= :error (:preview-status state)))
    (is (= :PATH_MISMATCH (get-in state [:preview-error :error :code])))
    (is (nil? (:preview state)))))

(deftest entry-reasons-are-preserved
  (let [entry (model/normalize-entry
               {:name "bin" :path "bin" :kind :file :exists? true
                :previewable? false :binary? true :reason-label "二进制文件"
                :error {:code :BINARY_FILE}})]
    (is (= "二进制文件" (:reason-label entry)))
    (is (= :BINARY_FILE (:error-code entry)))
    (is (true? (:binary? entry)))))
