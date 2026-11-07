(ns hc.server.browser-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.browser :as browser]
            [hc.server.browser.executor :as executor]
            [hc.server.browser.fake-port :as fake]
            [hc.server.browser.model :as model]
            [hc.server.browser.service :as service]
            [hc.server.model-adapter.request :as model-request]))

(def pages
  {"https://example.test/form"
   {:url "https://example.test/form"
    :title "Example Form"
    :text "Step 1"
    :http-status 200
    :sensitive-form? true
    :nodes [{:ref "hc-1" :tag "input" :type "email" :field-name "email"
             :label "Email" :rect {:x 10 :y 10 :width 200 :height 30}}
            {:ref "hc-2" :tag "input" :type "password" :field-name "password"
             :label "Password" :rect {:x 10 :y 50 :width 200 :height 30}}
            {:ref "hc-3" :tag "button" :type "button" :label "Advance"
             :rect {:x 10 :y 90 :width 100 :height 30}}
            {:ref "hc-4" :tag "button" :type "submit" :label "Submit"
             :rect {:x 10 :y 130 :width 100 :height 30}}]
    :click-url nil}

   "https://example.test/step2"
   {:url "https://example.test/step2"
    :title "Example Step 2"
    :text "Done"
    :http-status 200
    :nodes [{:ref "hc-9" :tag "button" :type "button" :label "Finish"
             :rect {:x 10 :y 10 :width 100 :height 30}}]}

   "https://example.test/login-required"
   {:url "https://example.test/login-required"
    :title "Sign in"
    :text "Sign in"
    :http-status 401
    :nodes []}

   "https://example.test/private"
   {:url "https://example.test/private"
    :title "Private"
    :text "Account"
    :http-status 200
    :login-form? true
    :nodes [{:ref "hc-20" :tag "input" :type "password"
             :field-name "password" :label "Password"
             :rect {:x 10 :y 10 :width 200 :height 30}}]}})

(defn- advance-page
  [pages]
  (assoc-in pages ["https://example.test/form" :nodes 2 :click-url]
            "https://example.test/step2"))

(defn- new-service
  [& [{:keys [page-map fail-mode store? max-cards max-rereads]
       :or {store? true max-cards 50 max-rereads 2}}]]
  (let [page-map (or page-map pages)
        fake-port (fake/fake-port page-map {:fail-mode fail-mode})
        svc (service/service
             {:port (:port fake-port)
              :session-id "s1"
              :clock (let [t (atom 1000)] #(swap! t inc))
              :max-cards max-cards
              :max-rereads max-rereads
              :screenshot-store (when store?
                                  (fn [bytes _]
                                    {:status :stored
                                     :blob-id (str "blob-" (count bytes))
                                     :size (count bytes)}))})]
    (service/permission! svc {:enabled? true
                              :scope :site
                              :sites #{"example.test"}})
    ;; The default is now fail-closed: reading a page that looks private
    ;; needs an explicit operator approval.
    (service/approve-private-read! svc)
    {:svc svc :fake fake-port}))

(defn- open-form!
  [{:keys [svc]}]
  (let [opened (service/open-tab! svc {:url "https://example.test/form"})]
    (is (= :ok (:status opened)))
    (:tab opened)))

(defn- observe!
  [svc tab-id]
  (let [result (service/observe! svc {:tab-id tab-id})]
    (is (= :ok (:status result)) (pr-str result))
    (:observation result)))

(defn- node
  [observation ref]
  (first (filter #(= ref (:ref %)) (:nodes observation))))

(deftest tool-specs-are-valid-model-tools
  (let [snapshot {:messages [{:role :user :text "open the form"}]
                  :tools (browser/tool-specs)}
        validated (model-request/validate-snapshot snapshot)]
    (is (= (count (browser/tool-specs)) (count (:tools validated))))
    (is (= (count (browser/tool-specs))
           (count (distinct (map :name (:tools validated))))))
    (is (every? #(string? (:name %)) (:tools validated)))))

(deftest endpoint-resolution
  (is (= "http://127.0.0.1:9222"
         (browser/endpoint {:browser {:endpoint "http://127.0.0.1:9222"}})))
  (is (nil? (browser/endpoint {}))))

(deftest screenshot-store-adapter
  (let [seen (atom nil)
        store (browser/blob-screenshot-store
               (fn [in opts]
                 (reset! seen [(String. (.readAllBytes in) "UTF-8") opts])
                 {:id "blob-x" :size 3}))]
    (is (= {:id "blob-x" :size 3}
           (store (.getBytes "png" "UTF-8") {:tab-id "t"})))
    (is (= "png" (first @seen)))
    (is (= "image/png" (get-in @seen [1 :mime-type])))))

(deftest sensitive-field-classification
  (testing "password, one-time code and payment fields are sensitive"
    (is (model/sensitive-target? {:type "password"}))
    (is (model/sensitive-target? {:autocomplete "one-time-code"}))
    (is (model/sensitive-target? {:field-name "card_number"}))
    (is (model/sensitive-target? {:label "CVV"}))
    (is (model/sensitive-target? {:field-kind :payment-card})))
  (testing "ordinary text fields are not sensitive"
    (is (not (model/sensitive-target? {:type "email" :field-name "email"})))
    (is (not (model/sensitive-target? {:type "text" :label "Search"}))))
  (testing "redaction removes the typed value"
    (let [redacted (model/redact-action
                    {:kind :type
                     :target {:type "password" :field-name "password"}
                     :text "hunter2"})]
      (is (not (str/includes? (pr-str redacted) "hunter2")))
      (is (= 7 (:text-length redacted)))
      (is (:text-redacted? redacted)))))

(deftest site-and-report-limits
  (is (= "example.test" (model/site-of "https://www.example.test/a")))
  (is (nil? (model/site-of "about:blank")))
  (let [{:keys [svc]} (new-service)
        text (service/report-text svc)]
    (is (str/includes? text "historical"))
    (is (str/includes? text "best-effort"))
    (is (str/includes? text "not inherited"))
    (is (str/includes? text "never retried automatically"))))

(deftest navigation-observation-and-action-cards
  (let [{:keys [svc fake]} (new-service {:page-map (advance-page pages)})
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        email (node observation "hc-1")
        advance (node observation "hc-3")]
    (is (= "Example Form" (:title observation)))
    (is (= "Step 1" (:text observation)))
    (is (true? (:sensitive? (node observation "hc-2"))))
    (is (= 4 (count (:nodes observation))))
    (is (string? (:observation-id observation)))
    (is (pos? (:generation observation)))

    (testing "ordinary text is typed"
      (let [result (service/act! svc {:tab-id tab-id
                                      :action :type
                                      :observation-id (:observation-id observation)
                                      :target email
                                      :text "operator@example.test"})]
        (is (= :ok (:status result)) (pr-str result))
        (is (false? (:navigated? (:result result))))))

    (testing "click navigates and records an ordered card"
      (let [result (service/act! svc {:tab-id tab-id
                                      :action :click
                                      :observation-id (:observation-id observation)
                                      :target advance})]
        (is (= :ok (:status result)) (pr-str result))
        (is (true? (:navigated? (:result result))))))

    (testing "cards are ordered and screenshots are historical"
      (let [{:keys [cards]} (service/cards svc)
            screenshots (map :screenshot cards)]
        (is (>= (count cards) 4))
        (is (apply < (map :seq cards)))
        (is (every? #(= :historical (:kind %)) screenshots))
        (is (every? #(false? (:live? %)) screenshots))
        (is (some #(str/includes? (pr-str %) "blob-") cards))))

    (testing "observation after navigation reads the new document"
      (let [next-observation (observe! svc tab-id)]
        (is (= "Done" (:text next-observation)))
        (is (> (:generation next-observation) (:generation observation)))
        (is (nil? (node next-observation "hc-1")))))))

(deftest sensitive-input-is-refused-and-pauses
  (let [{:keys [svc fake]} (new-service)
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        password (node observation "hc-2")
        result (service/act! svc {:tab-id tab-id
                                  :action :type
                                  :observation-id (:observation-id observation)
                                  :target password
                                  :text "hunter2"})]
    (is (= :refused (:status result)))
    (is (= :sensitive-input-requires-operator (get-in result [:error :code])))
    (is (= :sensitive-paused (:takeover result)))
    (is (empty? (:actions @(:state fake))))
    (is (not (str/includes? (pr-str (service/cards svc)) "hunter2")))
    (testing "no action runs while sensitive pause is active"
      (let [next (service/act! svc {:tab-id tab-id
                                    :action :click
                                    :observation-id (:observation-id observation)
                                    :target (node observation "hc-3")})]
        (is (= :refused (:status next)))
        (is (= :takeover-active (get-in next [:error :code])))))
    (testing "Enter in a sensitive form is refused before submission"
      (let [resumed (service/resume! svc {:tab-id tab-id})
            fresh (:observation resumed)]
        (is (= :ok (:status resumed)))
        (let [result (service/act! svc {:tab-id tab-id
                                        :action :press
                                        :key "Enter"
                                        :observation-id (:observation-id fresh)
                                        :target (node fresh "hc-3")})]
          (is (= :refused (:status result)))
          (is (= :sensitive-input-requires-operator
                 (get-in result [:error :code]))))))))

(deftest targetless-scroll-and-press-are-supported
  (let [{:keys [svc]} (new-service)
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        scroll (service/act! svc {:tab-id tab-id
                                  :action :scroll
                                  :observation-id (:observation-id observation)
                                  :delta-y 100})
        press (service/act! svc {:tab-id tab-id
                                 :action :press
                                 :key "ArrowDown"
                                 :observation-id (:observation-id observation)})
        submit (service/act! svc {:tab-id tab-id
                                  :action :click
                                  :observation-id (:observation-id observation)
                                  :target (node observation "hc-4")})
        enter (service/act! svc {:tab-id tab-id
                                 :action :press
                                 :key "Enter"
                                 :observation-id (:observation-id observation)})]
    (is (= :ok (:status scroll)) (pr-str scroll))
    (is (= :ok (:status press)) (pr-str press))
    (is (= :refused (:status submit)))
    (is (= :sensitive-input-requires-operator
           (get-in submit [:error :code])))
    (is (= :refused (:status enter)))
    (is (= :takeover-active (get-in enter [:error :code])))))

(deftest private-read-needs-explicit-approval
  (let [{:keys [svc]} (new-service)
        _ (service/permission! svc {:private-read-approved? false})
        opened (service/open-tab! svc {:url "https://example.test/private"})
        tab-id (get-in opened [:tab :tab-id])
        refused (service/observe! svc {:tab-id tab-id})]
    (is (= :refused (:status refused)))
    (is (= :private-read-confirmation-required
           (get-in refused [:error :code])))
    (service/approve-private-read! svc)
    (is (= :ok (:status (service/observe! svc {:tab-id tab-id}))))))

(deftest approved-sites-are-normalized
  (let [{:keys [svc]} (new-service)
        _ (service/permission! svc {:scope :site
                                    :sites #{"WWW.Example.Test"}})
        opened (service/open-tab! svc {:url "https://example.test/form"})]
    (is (= :ok (:status opened)) (pr-str opened))
    (is (= #{"example.test"}
           (set (get-in (service/snapshot svc) [:permission :sites]))))))

(deftest operator-input-pauses-and-resume-rereads
  (let [{:keys [svc fake]} (new-service {:page-map (advance-page pages)})
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        advance (node observation "hc-3")]
    (testing "operator input pauses immediately"
      (let [paused (service/operator-input! svc {:tab-id tab-id
                                                 :kind :input
                                                 :summary "operator typed"})]
        (is (= :ok (:status paused)))
        (is (= :paused-manual (:takeover paused)))))
    (testing "an agent action is refused while paused"
      (let [result (service/act! svc {:tab-id tab-id
                                      :action :click
                                      :observation-id (:observation-id observation)
                                      :target advance})]
        (is (= :refused (:status result)))
        (is (= :takeover-active (get-in result [:error :code])))
        (is (empty? (:actions @(:state fake)))))
      (let [result (service/navigate! svc {:tab-id tab-id
                                           :url "https://example.test/step2"})]
        (is (= :refused (:status result)))
        (is (= :takeover-active (get-in result [:error :code])))))
    (testing "resume reads the current page and invalidates the old target"
      (let [resumed (service/resume! svc {:tab-id tab-id})]
        (is (= :ok (:status resumed)) (pr-str resumed))
        (is (= :agent-active (:takeover resumed)))
        (is (true? (:reread? resumed)))
        (let [result (service/act! svc {:tab-id tab-id
                                        :action :click
                                        :observation-id (:observation-id observation)
                                        :target advance})]
          (is (= :refused (:status result)))
          (is (= :stale-target (get-in result [:error :code]))))
        (let [fresh (:observation resumed)]
          (is (= "Step 1" (:text fresh)))
          (is (= (:generation fresh)
                 (get-in (service/snapshot svc)
                         [:tabs tab-id :generation])))
          (is (> (:generation fresh) (:generation observation)))
          (is (= :ok (:status (service/act! svc {:tab-id tab-id
                                                 :action :click
                                                 :observation-id (:observation-id fresh)
                                                 :target (node fresh "hc-3")})))))))
    (testing "read-only resume allows reading but refuses actions"
      (service/takeover! svc {:tab-id tab-id})
      (let [resumed (service/resume! svc {:tab-id tab-id :mode :read-only})]
        (is (= :ok (:status resumed)))
        (is (= :read-only (:takeover resumed)))
        (is (= :ok (:status (service/observe! svc {:tab-id tab-id}))))
        (let [result (service/act! svc {:tab-id tab-id
                                        :action :click
                                        :observation-id (get-in resumed [:observation :observation-id])
                                        :target (node (:observation resumed) "hc-9")})]
          (is (= :refused (:status result)))
          (is (= :read-only (get-in result [:error :code]))))
        (testing "operator input during read-only handoff becomes explicit control"
          (let [paused (service/operator-input! svc {:tab-id tab-id
                                                     :kind :input})]
            (is (= :ok (:status paused)))
            (is (= :operator (:takeover paused)))
            (is (= :refused
                   (:status (service/observe! svc {:tab-id tab-id}))))))))))

(deftest failed-resume-reread-stays-paused
  (let [{:keys [svc fake]} (new-service)
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)]
    (observe! svc tab-id)
    (service/operator-input! svc {:tab-id tab-id :kind :input})
    (reset! (:fail-mode fake) :observe-unreadable)
    (let [resumed (service/resume! svc {:tab-id tab-id})
          snapshot (service/snapshot svc)]
      (is (= :failed (:status resumed)))
      (is (= :paused-manual
             (get-in snapshot [:tabs tab-id :takeover])))
      (is (nil? (get-in snapshot [:tabs tab-id :last-observation])))
      (is (= :refused
             (:status (service/act! svc {:tab-id tab-id
                                         :action :click
                                         :observation-id "obs-any"
                                         :target {:ref "hc-1"}})))))))

(deftest detected-page-input-pauses-and-resume-rereads
  (let [{:keys [svc fake]} (new-service {:page-map (advance-page pages)})
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        advance (node observation "hc-3")]
    (testing "a trusted page event pauses without the manual button"
      ((:emit-operator-input! fake)
       {:tab-id tab-id :kind :pointerdown})
      (let [snapshot (service/snapshot svc)]
        (is (= :paused-manual
               (get-in snapshot [:tabs tab-id :takeover])))
        (is (= :pointerdown
               (get-in snapshot [:tabs tab-id :takeover-reason])))))
    (testing "the old target is refused and resume rereads"
      (let [refused (service/act! svc {:tab-id tab-id
                                       :action :click
                                       :observation-id (:observation-id observation)
                                       :target advance})]
        (is (= :refused (:status refused)))
        (is (= :takeover-active (get-in refused [:error :code]))))
      (let [resumed (service/resume! svc {:tab-id tab-id})]
        (is (= :ok (:status resumed)))
        (is (= :agent-active (:takeover resumed)))
        (is (true? (:reread? resumed)))
        (is (= "Step 1" (get-in resumed [:observation :text])))
        (is (> (get-in resumed [:observation :generation])
               (:generation observation)))))))

(deftest repeated-stale-targets-hand-control-to-operator
  (let [{:keys [svc]} (new-service {:max-rereads 2})
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        target (node observation "hc-3")]
    (dotimes [_ 2]
      (let [result (service/act! svc {:tab-id tab-id
                                      :action :click
                                      :observation-id "obs-stale"
                                      :target target})]
        (is (= :refused (:status result)))
        (is (= :stale-target (get-in result [:error :code])))))
    (is (= :operator (:takeover (model/tab (service/snapshot svc) tab-id))))
    (let [result (service/act! svc {:tab-id tab-id
                                    :action :click
                                    :observation-id (:observation-id observation)
                                    :target target})]
      (is (= :refused (:status result)))
      (is (= :takeover-active (get-in result [:error :code]))))))

(deftest permission-and-private-page-boundaries
  (let [{:keys [svc fake]} (new-service)
        disabled (service/service {:port (:port fake)})]
    (testing "disabled browser refuses opening"
      (let [result (service/open-tab! disabled {:url "https://example.test/form"})]
        (is (= :refused (:status result)))
        (is (= :browser-disabled (get-in result [:error :code])))))
    (testing "unapproved site is refused before navigation"
      (let [result (service/open-tab! svc {:url "https://other.test/form"})]
        (is (= :refused (:status result)))
        (is (= :site-not-approved (get-in result [:error :code])))))
    (testing "a user-authorized jump to an unapproved site clears model reading"
      (let [tab (open-form! {:svc svc})
            tab-id (:tab-id tab)
            nav (service/navigate! svc {:tab-id tab-id
                                        :url "https://other.test/page"
                                        :user-authorized? true})]
        (is (= :ok (:status nav)) (pr-str nav))
        (let [result (service/observe! svc {:tab-id tab-id})]
          (is (= :refused (:status result)))
          (is (= :site-not-approved (get-in result [:error :code]))))))
    (testing "private page requires confirmation after provider change"
      (service/permission! svc {:private-read-approved? true})
      (let [opened (service/open-tab! svc {:url "https://example.test/private"})
            tab-id (get-in opened [:tab :tab-id])]
        (is (= :ok (:status (service/observe! svc {:tab-id tab-id}))))
        (service/set-model-provider! svc "provider-b")
        (let [result (service/observe! svc {:tab-id tab-id})]
          (is (= :refused (:status result)))
          (is (= :private-read-confirmation-required
                 (get-in result [:error :code])))
          (is (nil? (:text (:observation result)))))
        (service/approve-private-read! svc)
        (is (= :ok (:status (service/observe! svc {:tab-id tab-id}))))))))

(deftest close-stop-and-outcome-unknown
  (let [{:keys [svc fake]} (new-service {:page-map (advance-page pages)})
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        advance (node observation "hc-3")]
    (testing "unknown action outcome is reported, not retried"
      (reset! (:fail-mode fake) :act-outcome-unknown)
      (let [result (service/act! svc {:tab-id tab-id
                                      :action :click
                                      :observation-id (:observation-id observation)
                                      :target advance})]
        (is (= :outcome-unknown (:status result)))
        (is (= :outcome-unknown (get-in result [:error :code])))
        (is (= 1 (count (filter #(= :click (:kind %))
                                (:actions @(:state fake))))))))
    (testing "stop does not close pages"
      (let [stopped (service/stop! svc)]
        (is (= :ok (:status stopped)))
        (is (= 1 (:open-tabs stopped)))
        (let [result (service/observe! svc {:tab-id tab-id})]
          (is (= :refused (:status result)))
          (is (= :run-stopped (get-in result [:error :code]))))))
    (testing "a failed close is not reported as closed"
      (reset! (:fail-mode fake) :close-failed)
      (let [failed (service/close-tab! svc {:tab-id tab-id})]
        (is (= :failed (:status failed)))
        (is (= :open (get-in (service/snapshot svc) [:tabs tab-id :status]))))
      (reset! (:fail-mode fake) nil))
    (testing "closing a tab keeps its cards and reports a closed tab"
      (let [before (count (:cards (service/cards svc)))
            closed (service/close-tab! svc {:tab-id tab-id})]
        (is (= :ok (:status closed)))
        (is (= :closed (get-in closed [:tab :status])))
        (is (= before (count (:cards (service/cards svc)))))
        (let [result (service/act! svc {:tab-id tab-id
                                        :action :click
                                        :observation-id (:observation-id observation)
                                        :target advance})]
          (is (= :refused (:status result)))
          (is (= :tab-closed (get-in result [:error :code]))))))))

(deftest current-tab-scope-authorizes-only-the-active-tab
  (let [{:keys [fake]} (new-service {:page-map (advance-page pages)})
        svc (service/service {:port (:port fake) :session-id "scope"})]
    (service/permission! svc {:enabled? true
                              :scope :current-tab
                              :sites #{"example.test"}})
    (let [first-tab (get-in (service/open-tab! svc {:url "https://example.test/form"})
                            [:tab :tab-id])
          second-tab (get-in (service/open-tab! svc {:url "https://example.test/step2"})
                             [:tab :tab-id])
          state (service/snapshot svc)]
      (is (= second-tab (:active-tab-id state)))
      (is (true? (get-in state [:tabs second-tab :agent-authorized?])))
      (is (false? (get-in state [:tabs first-tab :agent-authorized?]))))))

(deftest login-required-page-pauses-agent
  (let [{:keys [svc]} (new-service)
        opened (service/open-tab! svc {:url "https://example.test/login-required"})
        tab-id (get-in opened [:tab :tab-id])]
    (is (= :ok (:status opened)))
    (is (= :login-required (get-in opened [:navigation :page-error])))
    (is (= :paused-manual
           (get-in (service/snapshot svc) [:tabs tab-id :takeover])))
    (is (= :login-required
           (get-in (service/snapshot svc) [:tabs tab-id :takeover-reason])))
    (is (= :refused (:status (service/observe! svc {:tab-id tab-id}))))
    (is (= :ok (:status (service/resume! svc {:tab-id tab-id}))))))

(deftest workbench-command-adapter
  (let [{:keys [svc fake]} (new-service {:page-map (advance-page pages)})
        permission (browser/command! svc {:kind :browser/permission
                                          :enabled? true
                                          :scope "site"
                                          :sites ["example.test"]})
        opened (browser/command! svc {:kind :browser/open-tab
                                      :url "https://example.test/form"})
        tab-id (get-in opened [:receipt :result :tab :tab-id])
        observed (browser/command! svc {:kind :browser/observe
                                        :tab-id tab-id})
        observation (get-in observed [:receipt :result :observation])
        advance (node observation "hc-3")]
    (is (:ok? permission))
    (is (:ok? opened))
    (is (= :ok (get-in opened [:receipt :result :status])))
    (is (:ok? observed))
    (testing "browser/act preserves the concrete action"
      (let [clicked (browser/command! svc {:kind :browser/act
                                           :action :click
                                           :tab-id tab-id
                                           :observation-id (:observation-id observation)
                                           :target advance})]
        (is (:ok? clicked))
        (is (= :ok (get-in clicked [:receipt :result :status])))
        (is (= "Done" (get-in (browser/command! svc {:kind :browser/observe
                                                     :tab-id tab-id})
                              [:receipt :result :observation :text])))))
    (testing "a missing act action is an explicit command error"
      (let [result (browser/command! svc {:kind :browser/act
                                          :tab-id tab-id})]
        (is (false? (:ok? result)))
        (is (= :BROWSER_ACTION_REQUIRED (get-in result [:error :code])))))
    (testing "the workbench can take over and resume"
      (let [taken (browser/command! svc {:kind :browser/takeover
                                         :tab-id tab-id})
            resumed (browser/command! svc {:kind :browser/resume
                                           :tab-id tab-id
                                           :mode "read-only"})]
        (is (:ok? taken))
        (is (:ok? resumed))
        (is (= :read-only (get-in resumed [:receipt :result :takeover])))))
    (testing "history commands reach the service port"
      (browser/command! svc {:kind :browser/resume
                             :tab-id tab-id
                             :mode "agent"})
      (let [reloaded (browser/command! svc {:kind :browser/reload
                                            :tab-id tab-id})]
        (is (:ok? reloaded))
        (is (= :ok (get-in reloaded [:receipt :result :status])))))
    (testing "snapshot and cards are available"
      (is (:ok? (browser/command! svc {:kind :browser/snapshot})))
      (is (:ok? (browser/command! svc {:kind :browser/cards}))))
    (testing "unknown kinds are refused"
      (let [result (browser/command! svc {:kind :browser/nope})]
        (is (false? (:ok? result)))
        (is (= :BROWSER_KIND_UNKNOWN (get-in result [:error :code])))))
    (testing "health reports connection and shutdown honestly"
      (is (true? (:connected? (browser/health svc))))
      (browser/shutdown! svc)
      (is (false? (:connected? (browser/health svc)))))))

(deftest card-bound-is-reported-honestly
  (let [{:keys [svc]} (new-service {:max-cards 2})
        tab (open-form! {:svc svc})
        _ (dotimes [_ 4]
            (service/observe! svc {:tab-id (:tab-id tab)}))
        {:keys [cards dropped truncated?]} (service/cards svc)]
    (is (= 2 (count cards)))
    (is (pos? dropped))
    (is (true? truncated?))
    (is (str/includes? (service/report-text svc) "older cards dropped"))))

(deftest executor-contract
  (let [{:keys [svc]} (new-service)
        exec (executor/executor svc)
        ui-exec (executor/executor svc {:allow-operator-actions? true})
        opened (exec {:call-id "c1" :attempt-id "a1"
                      :request {:tool-name "browser_open"
                                :arguments {:url "https://example.test/form"}}})
        tab-id (get-in opened [:payload :tab :tab-id])]
    (is (= :succeeded (:status opened)))
    (is (string? tab-id))
    (let [observed (exec {:call-id "c2" :attempt-id "a1"
                          :request {:tool-name "browser_observe"
                                    :arguments {:tab-id tab-id}}})]
      (is (= :succeeded (:status observed)))
      (is (= "Example Form" (get-in observed [:payload :observation :title]))))
    (let [refused (exec {:call-id "c3" :attempt-id "a1"
                         :request {:tool-name "browser_navigate"
                                   :arguments {:tab-id tab-id
                                               :url "https://other.test/"}}})]
      (is (= :failed (:status refused)))
      (is (= :site-not-approved (get-in refused [:error :kind]))))
    (is (= 13 (count (executor/tool-specs))))
    (is (some #(str/includes? (str/lower-case (:description %))
                              "never type passwords")
              (executor/tool-specs)))
    (testing "the routing seam sends browser calls to the browser executor"
      (let [browser-called (atom [])
            fallback-called (atom [])
            routed (executor/routing-executor
                    (fn [effect] (swap! browser-called conj effect) :browser)
                    (fn [effect] (swap! fallback-called conj effect) :fallback))]
        (is (= :browser (routed {:request {:tool-name "browser_observe"}})))
        (is (= :fallback (routed {:request {:tool-name "cat"}})))
        (is (= 1 (count @browser-called)))
        (is (= 1 (count @fallback-called)))))
    (testing "the model executor cannot bypass site permission"
      (let [bypass (exec {:call-id "c3b" :attempt-id "a1"
                          :request {:tool-name "browser_navigate"
                                    :arguments {:tab-id tab-id
                                                :url "https://other.test/"
                                                :user-authorized? true}}})]
        (is (= :failed (:status bypass)))
        (is (= :site-not-approved (get-in bypass [:error :kind])))))
    (testing "the model executor refuses operator-only actions"
      (let [blocked (exec {:call-id "c4" :attempt-id "a1"
                           :request {:tool-name "browser_resume"
                                     :arguments {:tab-id tab-id}}})
            close-blocked (exec {:call-id "c4b" :attempt-id "a1"
                                 :request {:tool-name "browser_close_tab"
                                           :arguments {:tab-id tab-id}}})
            stop-blocked (exec {:call-id "c4c" :attempt-id "a1"
                                :request {:tool-name "browser_stop"
                                          :arguments {}}})]
        (is (= :failed (:status blocked)))
        (is (= :operator-only (get-in blocked [:error :kind])))
        (is (= :operator-only (get-in close-blocked [:error :kind])))
        (is (= :operator-only (get-in stop-blocked [:error :kind])))))
    (let [paused (ui-exec {:call-id "c5" :attempt-id "a1"
                           :request {:tool-name "browser_takeover"
                                     :arguments {:tab-id tab-id}}})
          resumed (ui-exec {:call-id "c6" :attempt-id "a1"
                            :request {:tool-name "browser_resume"
                                      :arguments {:tab-id tab-id
                                                  :mode "read-only"}}})]
      (is (= :succeeded (:status paused)))
      (is (= :succeeded (:status resumed)))
      (is (= :read-only (get-in resumed [:payload :takeover]))))
    (let [cards-result (exec {:call-id "c7" :attempt-id "a1"
                              :request {:tool-name "browser_cards"}})
          report-result (exec {:call-id "c8" :attempt-id "a1"
                               :request {:tool-name "browser_report"}})]
      (is (= :succeeded (:status cards-result)))
      (is (= :succeeded (:status report-result))))))

(deftest uncovered-frames-refuse-agent-actions
  (doseq [[label overrides] [[:dom-iframe {:iframes 1}]
                             [:child-frame {:child-frames 1}]
                             [:unknown-frame-tree {:frame-tree-known? false}]]]
    (testing (name label)
      (let [page-map (update pages "https://example.test/form" merge overrides)
            {:keys [svc fake]} (new-service {:page-map page-map})
            tab (open-form! {:svc svc})
            tab-id (:tab-id tab)
            observation (observe! svc tab-id)]
        (is (= :uncovered (:operator-input-coverage observation)))
        (is (some #{:operator-input-uncovered} (:limitations observation)))
        (let [result (service/act! svc {:tab-id tab-id
                                        :action :click
                                        :observation-id (:observation-id observation)
                                        :target (node observation "hc-3")})]
          (is (= :refused (:status result)))
          (is (= :operator-input-uncovered (get-in result [:error :code])))
          (is (empty? (:actions @(:state fake)))))
        (let [nav (service/navigate! svc {:tab-id tab-id
                                          :url "https://example.test/step2"})]
          (is (= :refused (:status nav)))
          (is (= :operator-input-uncovered (get-in nav [:error :code]))))
        (let [hist (service/history! svc {:tab-id tab-id :direction :reload})]
          (is (= :refused (:status hist)))
          (is (= :operator-input-uncovered (get-in hist [:error :code]))))
        (service/takeover! svc {:tab-id tab-id})
        (let [resumed (service/resume! svc {:tab-id tab-id})]
          (is (= :ok (:status resumed)) (pr-str resumed))
          (is (= :read-only (:takeover resumed)))
          (is (true? (:downgraded? resumed))))))))

(deftest unavailable-operator-detection-refuses-agent-actions
  (let [{:keys [svc fake]} (new-service {:fail-mode :operator-input-unavailable})
        opened (service/open-tab! svc {:url "https://example.test/form"})]
    (is (= :unavailable
           (:operator-input-detection (service/snapshot svc))))
    (is (= :refused (:status opened)))
    (is (= :operator-input-uncovered (get-in opened [:error :code])))
    (is (empty? (:actions @(:state fake))))))

(deftest unprotected-port-results-pause-the-page
  (doseq [[mode code] [[:act-operator-input-uncovered :operator-input-uncovered]
                       [:act-operator-input-unprotected :operator-input-unprotected]]]
    (testing (name mode)
      (let [{:keys [svc fake]} (new-service {:fail-mode mode})
            tab (open-form! {:svc svc})
            tab-id (:tab-id tab)
            observation (observe! svc tab-id)
            result (service/act! svc {:tab-id tab-id
                                      :action :click
                                      :observation-id (:observation-id observation)
                                      :target (node observation "hc-3")})]
        (is (= code (get-in result [:error :code])))
        (is (= :paused-manual
               (get-in (service/snapshot svc) [:tabs tab-id :takeover])))
        (is (= :refused
               (:status (service/act! svc {:tab-id tab-id
                                           :action :click
                                           :observation-id (:observation-id observation)
                                           :target (node observation "hc-3")}))))
        (is (empty? (:actions @(:state fake))))))))

(deftest report-exposes-operator-coverage
  (let [{:keys [svc]} (new-service
                       {:page-map
                        (update pages "https://example.test/form"
                                assoc :iframes 1)})
        tab (open-form! {:svc svc})
        _ (observe! svc (:tab-id tab))
        report (service/report svc)]
    (is (= :ready (:operator-input-detection report)))
    (is (= :uncovered (get-in report [:tabs 0 :operator-input-coverage])))))

(deftest operator-input-during-action-is-not-reported-as-success
  (let [{:keys [svc fake]} (new-service {:fail-mode :act-operator-pause})
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        observation (observe! svc tab-id)
        result (service/act! svc {:tab-id tab-id
                                  :action :click
                                  :observation-id (:observation-id observation)
                                  :target (node observation "hc-3")})]
    (is (= :outcome-unknown (:status result)))
    (is (= :takeover-active (get-in result [:error :code])))
    (is (= :paused-manual
           (get-in (service/snapshot svc) [:tabs tab-id :takeover])))
    (is (= 1 (count (:actions @(:state fake)))))))

(deftest history-unknown-outcome-is-not-reported-as-success
  (let [{:keys [svc fake]} (new-service)
        tab (open-form! {:svc svc})
        tab-id (:tab-id tab)
        _ (observe! svc tab-id)
        _ (reset! (:fail-mode fake) :history-outcome-unknown)
        result (service/history! svc {:tab-id tab-id :direction :back})]
    (is (= :outcome-unknown (:status result)))
    (is (= :outcome-unknown (get-in result [:error :code])))
    (is (= :ok (:status (service/observe! svc {:tab-id tab-id}))))))

(deftest referenced-screenshot-store-adds-a-ref
  (let [refs (atom [])
        store (browser/ref-screenshot-store
               (fn [_in _opts]
                 {:id "sha256-screenshot" :size 3 :sha256 "abc"})
               (fn [id opts] (swap! refs conj [id opts])))
        result (store (.getBytes "png" "UTF-8") {:tab-id "t1"})]
    (is (= "sha256-screenshot" (:blob-id result)))
    (is (= 1 (count @refs)))
    (is (= "sha256-screenshot" (ffirst @refs)))))
