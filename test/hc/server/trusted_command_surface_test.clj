(ns hc.server.trusted-command-surface-test
  "Cross-module guard for the trusted-only command surface.

  A trusted-only command must be forbidden at the browser gateway, routed to
  the app scope instead of a session Owner, and gated again at its own
  handler.  Both sides are derived from the public constants so a new
  steward, fleet, program or history-maintenance command cannot open a
  browser backdoor without failing here."
  (:require [clojure.set :as set]
            [clojure.test :refer [are deftest is testing]]
            [hc.compose.fleet.router :as fleet-router]
            [hc.server.app.commands :as app-commands]
            [hc.server.app.search-commands :as history-search]
            [hc.server.gateway :as gateway]))

(def ^:private forbidden
  gateway/default-forbidden-browser-kinds)

(defn- forbidden?
  [kind]
  (contains? forbidden kind))

(defn- missing-from-forbidden
  [kinds]
  (set/difference (set kinds) forbidden))

(def ^:private steward-kinds
  app-commands/steward-command-kinds)

(def ^:private fleet-kinds
  fleet-router/command-kinds)

(defn- program-kind?
  [kind]
  (and (keyword? kind) (= "program" (namespace kind))))

(def ^:private program-kinds
  "The program plane accepts only :program/abandon today.  One concrete
  instance keeps the namespace guard honest until a public enumeration
  exists."
  #{:program/abandon})

(def ^:private history-trusted-kinds
  #{:history/reindex})

(def ^:private trusted-kinds
  (set/union (set steward-kinds)
             (set fleet-kinds)
             program-kinds
             history-trusted-kinds))

;; These sets are private in the router because only it decides scopes.
(def ^:private conversation-kinds
  @#'app-commands/conversation-kinds)

(def ^:private operator-kinds
  @#'app-commands/operator-kinds)

(def ^:private metadata-kinds
  @#'app-commands/metadata-kinds)

(def ^:private app-scoped-kind?
  @#'app-commands/app-scoped-kind?)

(def ^:private session-scoped-kind?
  @#'app-commands/session-scoped-kind?)

(deftest gateway-forbids-every-trusted-only-kind
  (testing "the browser deny set covers every trusted-only group"
    (doseq [[label kinds] [["steward" steward-kinds]
                           ["fleet" fleet-kinds]
                           ["program" program-kinds]
                           ["history" history-trusted-kinds]]]
      (let [missing (missing-from-forbidden kinds)]
        (is (empty? missing)
            (str label " kinds missing from "
                 "gateway/default-forbidden-browser-kinds: "
                 (pr-str (vec (sort-by str missing))))))))
  (testing "the whole trusted union is denied"
    (is (every? forbidden? trusted-kinds)
        (str "trusted kinds missing from the browser deny set: "
             (pr-str (vec (sort-by str (missing-from-forbidden trusted-kinds)))))))
  (testing "program kinds are namespace-valid"
    (is (seq program-kinds))
    (is (every? program-kind? program-kinds))))

(deftest browser-originated-kinds-stay-open
  (testing "history read paths are not blocked"
    (are [kind] (not (forbidden? kind))
      :history/search
      :history/search-status)
    (is (= #{:history/reindex}
           (set/intersection (set (history-search/command-kinds)) forbidden))
        "only reindex is trusted among the history command adapter kinds"))
  (testing "operator kinds are not blocked"
    (let [blocked (filter forbidden? operator-kinds)]
      (is (empty? blocked)
          (str "browser operator kinds blocked by the gateway: "
               (pr-str (vec (sort-by str blocked)))))))
  (testing "metadata kinds are not blocked"
    (let [blocked (filter forbidden? metadata-kinds)]
      (is (empty? blocked)
          (str "browser metadata kinds blocked by the gateway: "
               (pr-str (vec (sort-by str blocked)))))))
  (testing "ordinary conversation kinds are not blocked"
    ;; conversation-kinds also names trusted planes (plan, report, review,
    ;; board, worker) that the gateway must deny; the browser-originated
    ;; subset is exactly the operator/metadata vocabulary.
    (is (set/subset? (set operator-kinds) (set conversation-kinds)))
    (let [ordinary (set/intersection (set conversation-kinds)
                                     (set/union (set operator-kinds)
                                                (set metadata-kinds)))
          blocked (filter forbidden? ordinary)]
      (is (empty? blocked)
          (str "ordinary conversation kinds blocked by the gateway: "
               (pr-str (vec (sort-by str blocked))))))))

(deftest trusted-only-kinds-never-route-to-a-session-owner
  (testing "every trusted-only kind takes the app scope"
    (doseq [kind trusted-kinds]
      (is (true? (app-scoped-kind? kind))
          (str kind " must use the app scope"))))
  (testing "history/branch keeps its session routing"
    (is (false? (app-scoped-kind? :history/branch)))
    (is (true? (session-scoped-kind? :history/branch))))
  (testing "browser conversation commands stay session scoped"
    (are [kind] (false? (app-scoped-kind? kind))
      :send-message
      :stop-run
      :answer-ask)))

(deftest reindex-trust-is-captured-at-construction
  (let [opts {:store ::unused-store
              :list-sessions-fn (fn [] [])}
        handler (history-search/reindex-handler opts)
        handlers (history-search/handlers opts)]
    (is (= {:status :error
            :error {:code :REINDEX_REQUIRES_TRUSTED_CALLER
                    :message "reindex requires a trusted server-side caller"}}
           (handler nil)))
    (testing "a payload field cannot enable the rebuild"
      (are [payload] (= :REINDEX_REQUIRES_TRUSTED_CALLER
                         (get-in (handler payload) [:error :code]))
        {}
        {:allow-reindex? true}
        {:allow-reindex? false}))
    (testing "the handler map installs the same gate"
      (is (= :REINDEX_REQUIRES_TRUSTED_CALLER
             (get-in ((:history/reindex handlers) {:allow-reindex? true})
                     [:error :code]))))
    (testing "an explicit false switch still refuses"
      (is (= :REINDEX_REQUIRES_TRUSTED_CALLER
             (get-in ((history-search/reindex-handler
                       (assoc opts :allow-reindex? false))
                      {})
                     [:error :code]))))))
