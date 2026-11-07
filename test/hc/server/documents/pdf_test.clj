(ns hc.server.documents.pdf-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.documents :as documents]
            [hc.server.documents.support :as support])
  (:import [java.io File]))

(defn- pdf-request
  [file & [selection extra]]
  (merge {:source {:id "pdf-1" :name (.getName ^File file)
                   :mime "application/pdf"}
          :path (.getPath ^File file)
          :selection (merge {:route :text} selection)}
         extra))

(deftest text-pages-keep-original-identity
  (let [directory (support/temp-dir)
        file (support/mixed-pdf! (File. directory "mixed.pdf"))
        result (documents/extract! (pdf-request file))]
    (is (true? (:ok? result)))
    (is (= 3 (:page-count result)))
    (is (= [1 2 3] (mapv :page (:pages result))))
    (is (= [:text :no-text :text] (mapv :status (:pages result))))
    (is (re-find #"page one" (:text result)))
    (is (re-find #"page three" (:text result)))
    (is (= [2] (-> result :warnings first :pages)))
    (is (= :PAGES_WITHOUT_TEXT (-> result :warnings first :code)))
    (is (= "pdf-1" (get-in result [:pages 1 :source :id])))))

(deftest selected-pages-do-not-rebase
  (let [directory (support/temp-dir)
        file (support/mixed-pdf! (File. directory "mixed.pdf"))
        result (documents/extract! (pdf-request file {:pages "2-3"}))]
    (is (true? (:ok? result)))
    (is (= [2 3] (mapv :page (:pages result))))
    (is (= [2 3] (:selected-pages result)))
    (is (re-find #"page three" (:text result)))
    (is (not (re-find #"page one" (:text result))))))

(deftest scanned-pdf-needs-ocr-explicitly
  (let [directory (support/temp-dir)
        file (support/scanned-pdf! (File. directory "scanned.pdf"))
        result (documents/extract! (pdf-request file))]
    (is (false? (:ok? result)))
    (is (= :NO_TEXT (get-in result [:error :code])))
    (is (= :ocr (get-in result [:error :suggested-route])))
    (is (= [:no-text :no-text] (mapv :status (:pages result))))
    (is (= [1 2] (mapv :page (:pages result))))
    (is (= "pdf-1" (get-in result [:pages 0 :source :id])))))

(deftest out-of-range-page-is-refused
  (let [directory (support/temp-dir)
        file (support/mixed-pdf! (File. directory "mixed.pdf"))
        result (documents/extract! (pdf-request file {:pages [4]}))]
    (is (false? (:ok? result)))
    (is (= :PAGE_RANGE (get-in result [:error :code])))
    (is (= [4] (get-in result [:error :invalid])))))

(deftest encrypted-pdf-needs-the-right-password
  (let [directory (support/temp-dir)
        file (support/encrypted-pdf! (File. directory "secret.pdf")
                                     "owner-secret" "user-secret")
        no-password (documents/extract! (pdf-request file))
        wrong-password (documents/extract! (pdf-request file nil {:password "wrong"}))
        right-password (documents/extract! (pdf-request file nil {:password "user-secret"}))]
    (is (false? (:ok? no-password)))
    (is (= :NEEDS_PASSWORD (get-in no-password [:error :code])))
    (is (false? (:ok? wrong-password)))
    (is (= :WRONG_PASSWORD (get-in wrong-password [:error :code])))
    (is (true? (:ok? right-password)))
    (is (re-find #"secret page" (:text right-password)))
    (is (not (re-find #"user-secret" (pr-str right-password))))
    (is (not (re-find #"owner-secret" (pr-str no-password))))))

(deftest restricted-pdf-does-not-extract
  (let [directory (support/temp-dir)
        file (support/restricted-pdf! (File. directory "restricted.pdf")
                                      "owner-secret" "user-secret")
        result (documents/extract! (pdf-request file nil {:password "user-secret"}))]
    (is (false? (:ok? result)))
    (is (= :PDF_EXTRACTION_FORBIDDEN (get-in result [:error :code])))))

(deftest corrupt-pdf-is-unreadable
  (let [directory (support/temp-dir)
        file (support/write-bytes! (File. directory "broken.pdf")
                                   (.getBytes "%PDF-1.7\nbroken" "US-ASCII"))
        result (documents/extract! (pdf-request file))]
    (is (false? (:ok? result)))
    (is (= :PDF_UNREADABLE (get-in result [:error :code])))))

(deftest ocr-renders-the-original-page-number
  (let [directory (support/temp-dir)
        file (support/mixed-pdf! (File. directory "mixed.pdf"))
        result (documents/extract! (pdf-request file {:route :ocr :pages [2]
                                                      :ocr {:languages ["eng"]}}))]
    (is (true? (:ok? result)))
    (is (= [2] (mapv :page (:pages result))))
    (is (= "Scanned Herdchestra 2048" (:text result)))
    (is (= "pdf-1" (get-in result [:pages 0 :source :id])))))
