(ns hc.server.browser.fake-port
  "Deterministic in-process `BrowserPort` for browser service tests.

  The fake models pages, document identities, interactive nodes and the
  four failure classes the service must distinguish.  It does not pretend to
  be a browser: the real adapter is exercised by the CDP smoke test."
  (:require [hc.server.browser.model :as model]
            [hc.server.browser.port :as port]))

(defn- page
  [state url]
  (or (get (:pages state) url)
      {:url url
       :title "Not Found"
       :text "Not Found"
       :http-status 404
       :nodes []}))

(defn- tab
  [state tab-id]
  (get-in state [:tabs tab-id]))

(defn- next-document-id!
  [state]
  (str "doc-" (:next-doc (swap! state update :next-doc inc))))

(defn- current-fail-mode
  [fake]
  @(:fail-mode fake))

(defn fake-port
  "Create a fake port.

  PAGES is a map from URL to
  `{:url :title :text :http-status :login-form? :iframes :sensitive-form?
    :nodes [...] :click-url :submit-url}`.
  A node is `{:ref :tag :type :field-name :id :label :autocomplete :rect}`.

  Options:
  - `:fail-mode` atom; one of `:navigate-connection`,
    `:observe-unreadable`, `:act-outcome-unknown`, `:act-blocked`,
    `:screenshot-failed`, `:operator-input-unavailable`,
    `:act-operator-input-uncovered`, `:act-operator-input-unprotected`,
    `:act-operator-pause`."
  [pages & [{:keys [fail-mode]}]]
  (let [state (atom {:pages pages
                     :tabs {}
                     :next-doc 0
                     :actions []
                     :screenshots []})
        mode (atom fail-mode)
        operator-handler (atom nil)
        fake {:state state :fail-mode mode}]
    (letfn [(find-node [tab-id ref]
              (let [t (tab @state tab-id)
                    p (page @state (:url t))]
                (first (filter #(= ref (:ref %)) (:nodes p)))))
            (navigate-tab! [tab-id url]
              (let [p (page @state url)
                    doc (next-document-id! state)]
                (swap! state update-in [:tabs tab-id]
                       assoc :url url :document-id doc :title (:title p))
                {:status :ok
                 :url url
                 :title (:title p)
                 :document-id doc
                 :http-status (:http-status p)}))]
      {:port
       (reify port/BrowserPort
        (open-tab! [_ _opts]
          (let [tab-id (str "fake-tab-" (count (:tabs @state)))]
            (swap! state assoc-in [:tabs tab-id]
                   {:url "about:blank"
                    :title ""
                    :document-id (next-document-id! state)
                    :closed? false
                    :values {}
                    :clicks []})
            {:status :ok
             :tab-id tab-id
             :target-id (str "target-" tab-id)
             :url "about:blank"
             :title ""
             :document-id (get-in @state [:tabs tab-id :document-id])}))

        (close-tab! [_ tab-id]
          (if (= :close-failed (current-fail-mode fake))
            {:status :failed
             :error {:code :close-failed
                     :message "fake close failure"}}
            (do
              (swap! state assoc-in [:tabs tab-id :closed?] true)
              {:status :ok})))

        (navigate! [_ tab-id url]
          (if (= :navigate-connection (current-fail-mode fake))
            {:status :failed
             :error {:code :connection-failed
                     :message "fake connection failure"}}
            (navigate-tab! tab-id url)))

        (history! [_ _tab-id direction]
          (cond
            (= :history-outcome-unknown (current-fail-mode fake))
            {:status :outcome-unknown
             :error {:code :outcome-unknown
                     :message "fake history delivery may have happened"}}

            (= :reload direction)
            {:status :ok}

            :else
            {:status :failed
             :error {:code :history-boundary
                     :message "no further history"}}))

        (observe! [_ tab-id _opts]
          (cond
            (= :observe-unreadable (current-fail-mode fake))
            {:status :failed
             :error {:code :unreadable-content
                     :message "fake unreadable page"}}

            (get-in @state [:tabs tab-id :closed?])
            {:status :failed
             :error {:code :tab-closed
                     :message "fake tab closed"}}

            :else
            (let [t (tab @state tab-id)
                  p (page @state (:url t))]
              {:status :ok
               :url (:url p)
               :title (:title p)
               :document-id (:document-id t)
               :text (:text p)
               :nodes (:nodes p)
               :login-form? (boolean (:login-form? p))
               :iframes (:iframes p 0)
               :child-frames (:child-frames p 0)
               :frame-tree-known? (not (false? (:frame-tree-known? p true)))
               :http-status (:http-status p)})))

        (page-info! [_ tab-id]
          (let [t (tab @state tab-id)
                p (page @state (:url t))]
            (if (get-in @state [:tabs tab-id :closed?])
              {:status :failed
               :error {:code :tab-closed
                       :message "fake tab closed"}}
              {:status :ok
               :document-id (:document-id t)
               :url (:url p)
               :title (:title p)
               :sensitive-form? (boolean (:sensitive-form? p))})))

        (inspect! [_ tab-id target]
          (let [t (tab @state tab-id)
                p (page @state (:url t))
                node (find-node tab-id (:ref target))]
            (if node
              {:status :ok
               :document-id (:document-id t)
               :url (:url p)
               :title (:title p)
               :sensitive-form? (boolean (:sensitive-form? p))
               :target (assoc node
                              :sensitive? (model/sensitive-target? node)
                              :visible? true)}
              {:status :failed
               :document-id (:document-id t)
               :error {:code :stale-target
                       :message "fake node is gone"}})))

        (act! [_ tab-id action]
          (cond
            (= :act-operator-pause (current-fail-mode fake))
            (do
              (swap! state update :actions conj action)
              (when-let [handler @operator-handler]
                (handler {:tab-id tab-id :kind :pointerdown}))
              {:status :ok
               :document-id (get-in @state [:tabs tab-id :document-id])
               :navigated? false})

            (= :act-operator-input-uncovered (current-fail-mode fake))
            {:status :failed
             :error {:code :operator-input-uncovered
                     :message "fake frame coverage unavailable"}}

            (= :act-operator-input-unprotected (current-fail-mode fake))
            {:status :failed
             :error {:code :operator-input-unprotected
                     :message "fake operator suppression unavailable"}}

            (= :act-outcome-unknown (current-fail-mode fake))
            (do
              (swap! state update :actions conj action)
              {:status :outcome-unknown
               :error {:code :outcome-unknown
                       :message "fake delivery may have happened"}})

            (= :act-blocked (current-fail-mode fake))
            {:status :failed
             :error {:code :blocked-by-page
                     :message "fake page blocked the action"}}

            :else
            (let [t (tab @state tab-id)
                  p (page @state (:url t))
                  node (find-node tab-id (get-in action [:target :ref]))]
              (swap! state update :actions conj action)
              (case (:kind action)
                :click
                (if-let [next-url (or (:click-url node) (:click-url p))]
                  (assoc (navigate-tab! tab-id next-url)
                         :navigated? true
                         :external-effect? false)
                  {:status :ok
                   :document-id (:document-id t)
                   :navigated? false})

                :type
                (do
                  (swap! state assoc-in [:tabs tab-id :values (:ref node)]
                         (:text action))
                  {:status :ok
                   :document-id (:document-id t)
                   :navigated? false})

                :submit
                (if-let [next-url (:submit-url p)]
                  (assoc (navigate-tab! tab-id next-url)
                         :navigated? true
                         :external-effect? true)
                  {:status :ok
                   :document-id (:document-id t)
                   :navigated? false
                   :external-effect? true})

                :press
                (if (and (= "Enter" (:key action)) (:submit-url p))
                  (assoc (navigate-tab! tab-id (:submit-url p))
                         :navigated? true
                         :external-effect? true)
                  {:status :ok
                   :document-id (:document-id t)
                   :navigated? false
                   :external-effect? (= "Enter" (:key action))})

                {:status :ok
                 :document-id (:document-id t)
                 :navigated? false}))))

        (screenshot! [_ _tab-id]
          (if (= :screenshot-failed (current-fail-mode fake))
            {:status :failed
             :error {:code :capture-failed
                     :message "fake screenshot failure"}}
            (let [bytes (.getBytes "fake-png")]
              (swap! state update :screenshots conj bytes)
              {:status :ok :bytes bytes :mime-type "image/png"})))

        (tab-status [_ tab-id]
          (if (get-in @state [:tabs tab-id :closed?])
            {:status :closed}
            {:status :open}))

        (subscribe-operator-input! [_ handler]
          (reset! operator-handler handler)
          (if (= :operator-input-unavailable (current-fail-mode fake))
            {:ok? false
             :error {:code :operator-input-unavailable
                     :message "fake operator detection unavailable"}}
            {:ok? true
             :unsubscribe (fn [] (reset! operator-handler nil))}))

        (close! [_]
          {:status :ok}))
       :state state
       :fail-mode mode
       :emit-operator-input!
       (fn [event]
         (when-let [handler @operator-handler]
           (handler event)))})))
