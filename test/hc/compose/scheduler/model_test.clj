(ns hc.compose.scheduler.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.scheduler.model :as model]
            [hc.compose.scheduler.support :as support]))

(def shanghai "Asia/Shanghai")

(defn- new-result
  [now overrides]
  (model/new-schedule
   (merge {:id "s1"
           :name "Morning report"
           :prompt "Summarize the workspace."
           :snapshot support/base-snapshot
           :execution {:mode :new-chat}
           :recurrence {:kind :weekly
                        :days [1 3]
                        :time {:hour 8 :minute 30}}
           :timezone {:mode :fixed :zone shanghai}
           :now now}
          overrides)))

(defn- new-draft
  ([now] (new-draft now {}))
  ([now overrides]
   (let [result (new-result now overrides)]
     (is (:ok? result) (pr-str result))
     result)))

(deftest preview-before-save-shows-three-real-times
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        {:keys [schedule preview]} (new-draft now)]
    (is (= :draft (:state schedule)))
    (is (= shanghai (:zone preview)))
    (is (= :fixed (:timezone-mode preview)))
    (is (= ["2026-11-04T08:30" "2026-11-09T08:30" "2026-11-11T08:30"]
           (mapv :local-date-time (:times preview))))
    (is (= (:epoch-ms (first (:times preview))) (:next-run preview)))
    (is (re-find #"Monday" (:readable preview)))
    (is (re-find #"Asia/Shanghai" (:readable preview)))))

(deftest creation-validation
  (let [now (support/epoch shanghai "2026-11-03T09:00")]
    (testing "blank prompt cannot be saved"
      (let [result (model/new-schedule
                    {:id "s1" :name "x" :prompt "   "
                     :recurrence {:kind :daily :time {:hour 8 :minute 0}}
                     :timezone {:mode :fixed :zone shanghai}
                     :now now})]
        (is (false? (:ok? result)))
        (is (= :INVALID_SCHEDULE (get-in result [:error :code])))))
    (testing "name limit is 80 code points"
      (let [result (model/new-schedule
                    {:id "s1" :name (apply str (repeat 81 "a"))
                     :prompt "x"
                     :recurrence {:kind :daily :time {:hour 8 :minute 0}}
                     :timezone {:mode :fixed :zone shanghai}
                     :now now})]
        (is (false? (:ok? result)))
        (is (= :INVALID_SCHEDULE (get-in result [:error :code])))))
    (testing "a schedule is never created enabled"
      (let [result (model/new-schedule
                    {:id "s1" :name "x" :prompt "x" :state :enabled
                     :recurrence {:kind :daily :time {:hour 8 :minute 0}}
                     :timezone {:mode :fixed :zone shanghai}
                     :now now})]
        (is (false? (:ok? result)))
        (is (= :INVALID_SCHEDULE (get-in result [:error :code])))))
    (testing "invalid timezone and recurrence are rejected"
      (is (false? (:ok? (new-result now {:timezone {:mode :fixed :zone "Mars/Olympus"}}))))
      (is (false? (:ok? (new-result now {:recurrence {:kind :weekly :days [] :time {:hour 8 :minute 0}}}))))
      (is (false? (:ok? (new-result now {:recurrence {:kind :daily :time {:hour 24 :minute 0}}})))))))

(deftest snapshot-rejects-secrets-and-live-objects
  (let [now (support/epoch shanghai "2026-11-03T09:00")]
    (let [result (model/new-schedule
                  {:id "s1" :name "x" :prompt "x"
                   :snapshot (assoc support/base-snapshot :api-key "sk-live")
                   :recurrence {:kind :daily :time {:hour 8 :minute 0}}
                   :timezone {:mode :fixed :zone shanghai}
                   :now now})]
      (is (false? (:ok? result)))
      (is (= :SECRET_IN_SNAPSHOT (get-in result [:error :code]))))
    (let [result (model/new-schedule
                  {:id "s1" :name "x" :prompt "x"
                   :snapshot (assoc support/base-snapshot :callback (fn []))
                   :recurrence {:kind :daily :time {:hour 8 :minute 0}}
                   :timezone {:mode :fixed :zone shanghai}
                   :now now})]
      (is (false? (:ok? result)))
      (is (= :INVALID_SNAPSHOT (get-in result [:error :code]))))))

(deftest enable-requires-complete-frozen-config
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        {:keys [schedule]} (new-draft now)
        incomplete (assoc schedule :snapshot {:prompt "x"
                                              :execution {:mode :new-chat}})]
    (is (= :SCHEDULE_INCOMPLETE
           (get-in (model/enable incomplete now) [:error :code])))
    (is (= :SCHEDULE_INCOMPLETE
           (get-in (model/enable (assoc schedule :prompt "  ") now)
                   [:error :code])))
    (let [enabled (model/enable schedule now)]
      (is (:ok? enabled))
      (is (= :enabled (get-in enabled [:schedule :state])))
      (is (= ["2026-11-04T08:30" "2026-11-09T08:30" "2026-11-11T08:30"]
             (mapv :local-date-time (get-in enabled [:preview :times])))))))

(deftest pause-keeps-next-run-and-run-history-unchanged
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        {:keys [schedule]} (new-draft now)
        enabled (:schedule (model/enable schedule now))
        paused (:schedule (model/pause enabled
                                       (support/epoch shanghai
                                                      "2026-11-04T09:00")))]
    (is (= :paused (:state paused)))
    (is (= (:next-run enabled) (:next-run paused)))
    (is (= (support/epoch shanghai "2026-11-04T09:00")
           (:paused-at paused)))))

(deftest explicit-update-is-the-only-way-to-adopt-a-new-snapshot
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        {:keys [schedule]} (new-draft now)
        enabled (:schedule (model/enable schedule now))
        new-snapshot (assoc-in support/base-snapshot
                               [:model :model-id] "model-b")
        prompt-only (model/update-schedule
                     enabled {:prompt "A new task body"} now)
        adopted (model/update-schedule
                 enabled {:snapshot new-snapshot} now)]
    (is (:ok? prompt-only))
    (is (= "model-a" (get-in prompt-only [:schedule :snapshot :model :model-id])))
    (is (= "A new task body" (get-in prompt-only [:schedule :snapshot :prompt])))
    (is (= 2 (get-in prompt-only [:schedule :revision])))
    (is (:ok? adopted))
    (is (= "model-b" (get-in adopted [:schedule :snapshot :model :model-id])))
    (is (= 2 (get-in adopted [:schedule :revision])))
    (is (= (:next-run enabled) (get-in adopted [:schedule :next-run])))))

(deftest timezone-modes-and-execution-validation
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        follow (new-draft now {:timezone {:mode :follow-device}})
        fixed (new-draft now {:timezone "Europe/Paris"})]
    (is (= {:mode :follow-device :zone shanghai}
           (get-in follow [:schedule :timezone])))
    (is (= {:mode :fixed :zone "Europe/Paris"}
           (get-in fixed [:schedule :timezone])))
    (is (false? (:ok? (new-result now {:execution {:mode :continue-chat}}))))
    (is (false? (:ok? (new-result now {:execution {:mode :redo-question}}))))
    (is (:ok? (new-draft now {:execution {:mode :continue-chat
                                         :chat-id "chat-1"}})))))

(deftest timezone-edit-previews-old-and-new-times
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        {:keys [schedule]} (new-draft now)
        enabled (:schedule (model/enable schedule now))
        updated (model/update-schedule
                 enabled {:timezone {:mode :fixed :zone "Europe/Paris"}}
                 now)]
    (is (:ok? updated))
    (is (= shanghai (get-in updated [:previous-preview :zone])))
    (is (= "Europe/Paris" (get-in updated [:preview :zone])))
    (is (= "Europe/Paris" (get-in updated [:schedule :timezone :zone])))))

(deftest exhausted-once-schedule-can-still-be-edited
  (let [now (support/epoch shanghai "2026-11-03T09:00")
        once (assoc (new-draft now)
                    :recurrence {:kind :once :local-date "2026-11-05"
                                 :time {:hour 8 :minute 30}})]
    (is (:ok? once))
    (let [enabled (:schedule (model/enable (:schedule once) now))
          exhausted (assoc enabled :next-run nil :exhausted? true)
          renamed (model/update-schedule exhausted {:name "Renamed"} now)]
      (is (:ok? renamed))
      (is (= "Renamed" (get-in renamed [:schedule :name])))
      (is (nil? (get-in renamed [:schedule :next-run]))))))
