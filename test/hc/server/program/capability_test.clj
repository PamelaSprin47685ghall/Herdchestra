(ns hc.server.program.capability-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.program.capability :as cap]))

(deftest role-sets-match-the-spec-table
  (is (= #{:cat :write :where :whereAll :park :unpark :ask :devops :review}
         (cap/capabilities :clj-plan)))
  (is (= #{:cat :stat :ls :where :whereAll :unpark :review
           :askPreviousScope :assume :negotiate}
         (cap/capabilities :clj-review)))
  (is (= #{:cat :stat :ls :write :mkdir :rm :mv :where :whereAll :park
           :unpark :call :split :claim :finish :cancel :replace :release
           :reopen :comment :update :take-or-die :park-slot :abandon
           :resolve-action :acknowledge-recovery
           :askPreviousScope :assume :negotiate}
         (cap/capabilities :clj-worker)))
  (is (= #{:cat :stat :ls :where :whereAll :park :unpark
           :fork :join :fleet-state :assume}
         (cap/capabilities :clj-steward)))
  (is (= #{:cat :stat :ls :where :whereAll}
         (cap/capabilities :clj-meditator)))
  (is (= #{:cat :stat :ls :where :whereAll :park :unpark :call}
         (cap/capabilities :dedicated-worker))))

(deftest steward-and-meditator-keep-a-narrow-surface
  (is (cap/role? :clj-steward))
  (is (cap/role? :clj-meditator))
  (is (cap/capability? :clj-steward :fork))
  (is (cap/capability? :clj-steward :join))
  (is (cap/capability? :clj-steward :fleet-state))
  (is (not (cap/capability? :clj-steward :write)))
  (is (not (cap/capability? :clj-steward :call)))
  (is (not (cap/capability? :clj-steward :review)))
  (is (not (cap/capability? :clj-meditator :write)))
  (is (not (cap/capability? :clj-meditator :call)))
  (is (not (cap/capability? :clj-meditator :fork)))
  (is (= #{:cat :stat :ls}
         (cap/narrowed :clj-steward #{:cat :stat :ls :write}))
      "narrowing cannot widen the Steward"))

(deftest orchestration-primitives-are-registered
  (doseq [[function capability-keyword]
          '[[fork :fork] [join :join] [fleet-state :fleet-state]
            [negotiate :negotiate]]]
    (is (= capability-keyword (get cap/program-functions function))
        (str function " maps to " capability-keyword))
    (is (contains? cap/control-primitives capability-keyword)))
  (testing "every registered function belongs to at least one role"
    (is (every? (fn [[_ capability-keyword]]
                  (some #(cap/capability? % capability-keyword)
                        (keys cap/roles)))
                cap/program-functions))))

(deftest narrowing-cannot-widen
  (is (= #{:cat} (cap/narrowed :clj-review #{:cat :write :eval})))
  (is (= #{:cat :stat :ls :where :whereAll :unpark :review
           :askPreviousScope :assume :negotiate}
         (cap/narrowed :clj-review nil)))
  (is (empty? (cap/narrowed :clj-plan #{}))))

(deftest ask-is-s1-only
  (is (nil? (cap/check-ask :clj-plan true)))
  (is (= :ASK_FORBIDDEN_OUTSIDE_S1
         (:status (cap/check-ask :clj-plan false))))
  (is (= :CAPABILITY_DENIED
         (:status (cap/check-ask :clj-worker true)))))

(deftest call-and-abandon-need-the-token
  (is (nil? (cap/check-call :dedicated-worker false)))
  (is (nil? (cap/check-call :clj-worker true)))
  (is (= :TOKEN_REQUIRED (:status (cap/check-call :clj-worker false))))
  (is (= :CAPABILITY_DENIED (:status (cap/check-call :clj-review true))))
  (is (nil? (cap/check-abandon :clj-worker true)))
  (is (= :TOKEN_REQUIRED (:status (cap/check-abandon :clj-worker false))))
  (is (= :CAPABILITY_DENIED (:status (cap/check-abandon :clj-review true)))))

(deftest plan-draft-rules
  (is (= :WRITE_LOCKED_BEFORE_FIRST_REVIEW
         (:status (cap/check-plan-write {:draft-path "plans/x.md"} "plans/x.md"))))
  (is (= :INVALID_DRAFT_PATH
         (:status (cap/check-plan-write
                   {:draft-path "plans/x.md" :write-unlocked? true}
                   "src/x.clj"))))
  (is (nil? (cap/check-plan-write
             {:write-unlocked? true}
             "plans/x.md")))
  (is (= :INVALID_DRAFT_PATH
         (:status (cap/check-plan-write
                   {:write-unlocked? true}
                   "plans/../x.md"))))
  (is (= :DRAFT_PATH_LOCKED
         (:status (cap/check-plan-write
                   {:draft-path "plans/x.md" :write-unlocked? true}
                   "plans/y.md"))))
  (is (nil? (cap/check-plan-write
             {:draft-path "plans/x.md" :write-unlocked? true}
             "plans/x.md")))
  (is (= :CAPABILITY_DENIED
         (:status (cap/check-plan-read
                   {:readable-paths #{"plans/x.md"}}
                   "src/x.clj"))))
  (is (nil? (cap/check-plan-read
             {:readable-paths #{"plans/x.md"}}
             "plans/x.md")))
  (is (nil? (cap/check-plan-read
             {:draft-path "plans/x.md"}
             "plans/x.md")))
  (is (nil? (cap/check-plan-read
             {}
             "plans/x.md"
             "plans/x.md")))
  (is (= :CAPABILITY_DENIED
         (:status (cap/check-plan-read {} "plans/x.md")))))
