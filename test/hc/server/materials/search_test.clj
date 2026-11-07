(ns hc.server.materials.search-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]))

(defn- refs-with
  []
  (let [result (materials/add-search-results
                (materials/new-reference-set)
                {:query-id "q1"
                 :fetched-at "2026-11-03T10:00:00Z"
                 :results [{:url "https://Example.com/a#section"
                            :title "A"
                            :published-at "2026-10-01"
                            :snippet "first"}
                           {:url "https://example.com/b"
                            :title "B"}]})]
    (:refs result)))

(deftest canonical-url-normalization
  (is (= "https://example.com/a?x=1"
         (materials/canonical-search-url
          "HTTPS://Example.COM:443/a?x=1#fragment")))
  (is (= "http://example.com:8080/a"
         (materials/canonical-search-url "http://example.com:8080/a")))
  (is (nil? (materials/canonical-search-url "ftp://example.com/a")))
  (is (nil? (materials/canonical-search-url "https://user:pass@example.com/a")))
  (is (nil? (materials/canonical-search-url "not a url"))))

(deftest duplicate-urls-merge-across-queries
  (let [refs (refs-with)
        merged (:refs (materials/add-search-results
                       refs
                       {:query-id "q2"
                        :fetched-at "2026-11-03T10:01:00Z"
                        :results [{:url "https://example.com/a"
                                   :title "A"
                                   :published-at "2026-10-02"}]}))
        source (get-in merged [:sources "https://example.com/a"])
        listed (materials/search-source-list merged)]
    (is (= 2 (count (:sources merged))))
    (is (= 2 (count (:order merged))))
    (is (= ["q1" "q2"] (:queries source)))
    (is (= "2026-11-03T10:00:00Z" (:fetched-at source)))
    (is (= "2026-11-03T10:01:00Z" (:last-fetched-at source)))
    (is (= "2026-10-01" (:published-at source)))
    (is (= ["2026-10-02"] (:published-at-alternatives source)))
    (is (false? (:published-at-unknown? (first (:other listed)))))
    (is (= 2 (count (:other listed))))
    (is (= [] (:cited listed)))
    (is (empty? (materials/check-search-references merged)))))

(deftest citations-are-real-and-stable
  (let [refs (refs-with)
        unknown (materials/cite-search-source refs "https://example.com/missing")
        first-cite (materials/cite-search-source refs "https://example.com/a")
        second-cite (materials/cite-search-source (:refs first-cite)
                                                  "https://example.com/a")
        refs2 (:refs second-cite)
        b-cite (materials/cite-search-source refs2 "https://example.com/b")
        listed (materials/search-source-list (:refs b-cite))]
    (is (false? (:ok? unknown)))
    (is (= :SEARCH_SOURCE_UNKNOWN (get-in unknown [:error :code])))
    (is (true? (:ok? first-cite)))
    (is (= 1 (:number first-cite)))
    (is (false? (:replayed? first-cite)))
    (is (true? (:replayed? second-cite)))
    (is (= 1 (:number second-cite)))
    (is (= "[1]" (materials/search-citation-marker (:refs b-cite)
                                                   "https://example.com/a")))
    (is (= [1 2] (map :citation-number (:cited listed))))
    (is (= [] (:other listed)))
    (is (= "https://Example.com/a#section"
           (:url (first (:cited listed)))))))

(deftest published-date-is-not-fetch-time
  (let [refs (:refs (materials/add-search-results
                     (materials/new-reference-set)
                     {:query-id "q1"
                      :fetched-at "2026-11-03T10:00:00Z"
                      :results [{:url "https://example.com/no-date"
                                 :title "No date"}]}))
        source (get-in refs [:sources "https://example.com/no-date"])
        text (materials/search-plain-text refs)]
    (is (nil? (:published-at source)))
    (is (true? (:published-at-unknown? (first (:other
                                               (materials/search-source-list refs))))))
    (is (re-find #"published: unknown" text))
    (is (re-find #"fetched: 2026-11-03T10:00:00Z" text))
    (is (not (re-find #"published: 2026-11-03" text)))))

(deftest a-real-title-replaces-a-derived-title
  (let [refs (:refs (materials/add-search-results
                     (materials/new-reference-set)
                     {:query-id "q1"
                      :fetched-at "2026-11-03T10:00:00Z"
                      :results [{:url "https://example.com/a"
                                 :title nil}]}))
        derived (get-in refs [:sources "https://example.com/a"])
        merged (:refs (materials/add-search-results
                       refs
                       {:query-id "q2"
                        :fetched-at "2026-11-03T10:01:00Z"
                        :results [{:url "https://example.com/a"
                                   :title "Real title"}]}))
        source (get-in merged [:sources "https://example.com/a"])]
    (is (= "a" (:title derived)))
    (is (true? (:title-derived? derived)))
    (is (= "Real title" (:title source)))
    (is (false? (:title-derived? source)))))

(deftest read-status-is-explicit
  (let [refs (refs-with)
        read (:refs (materials/record-search-read
                     refs
                     "https://example.com/b"
                     {:status :full
                      :read-at "2026-11-03T10:05:00Z"}))
        source (get-in read [:sources "https://example.com/b"])]
    (is (= :full (:content-status source)))
    (is (= "2026-11-03T10:05:00Z" (:read-at source)))
    (is (= :summary (:content-status (get-in refs [:sources "https://example.com/b"]))))
    (is (= :SEARCH_SOURCE_UNKNOWN
           (get-in (materials/record-search-read
                    refs "https://example.com/missing"
                    {:status :full :read-at "2026-11-03T10:05:00Z"})
                   [:error :code])))))

(deftest result-validation-is-all-or-nothing
  (let [refs (materials/new-reference-set)
        result (materials/add-search-results
                refs
                {:query-id "q1"
                 :fetched-at "2026-11-03T10:00:00Z"
                 :results [{:url "https://example.com/ok" :title "OK"}
                           {:url "ftp://example.com/bad" :title "Bad"}]})]
    (is (false? (:ok? result)))
    (is (= :SEARCH_URL_INVALID (get-in result [:error :code])))
    (is (= refs (:refs result)))))
