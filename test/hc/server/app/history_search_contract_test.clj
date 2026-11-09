(ns hc.server.app.history-search-contract-test
  "Cross-layer contract guards for history search.

  The browser, the command adapter and the rebuildable index must agree on
  payload keys, status values, receipt shape and the trusted-only reindex
  boundary.  Sets are derived from the published constants/functions and the
  actual implementation sources; this test does not keep a second hand-written
  copy of either side's list."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hc.client.search.history :as history]
            [hc.server.app.search-commands :as command]
            [hc.server.gateway :as gateway]
            [hc.server.history.index :as index]
            [hc.server.storage.support :as support])
  (:import (java.io File)))

(def ^:dynamic *store* nil)

(use-fixtures :each
  (fn [test-fn]
    (support/with-temp-store [store {}]
      (binding [*store* store]
        (test-fn)))))

;; ---------------------------------------------------------------------------
;; Source/constant derivation

(def ^:private commands-source "src/hc/client/workbench/commands.cljs")
(def ^:private history-source "src/hc/client/search/history.cljc")
(def ^:private history-view-source "src/hc/client/search/history_view.cljs")
(def ^:private client-source-root "src/hc/client")

(defn- repo-source [path]
  (let [file (io/file path)]
    (when-not (.isFile ^File file)
      (throw (ex-info (str "contract test needs the repository source at "
                           (.getAbsolutePath ^File file))
                      {:path path})))
    (slurp file)))

(defn- function-source
  [path name next-name]
  (let [source (repo-source path)
        start (str/index-of source (str "(defn " name))]
    (when-not start
      (throw (ex-info (str "source function not found: " name) {:path path})))
    (let [end (str/index-of source (str "(defn " next-name) (inc start))]
      (subs source start (or end (count source))))))

(defn- client-sent-payload-keys
  "Keys the client command builder actually puts into :history/search.

  The query key is read from `(cond-> {:query ...})`; filter keys are the
  `assoc` calls in the same function.  `:kind` is command envelope metadata and
  is deliberately not part of this set."
  []
  (let [body (function-source commands-source
                              "search-history!"
                              "search-history-status!")
        query-key (some-> (re-find #"cond->\s*\{\s*:([A-Za-z0-9?!-]+)" body)
                          second
                          keyword)
        assoc-keys (mapv (comp keyword second)
                         (re-seq #"\(assoc\s+:([A-Za-z0-9?!-]+)" body))]
    (when-not query-key
      (throw (ex-info "could not derive the client query payload key"
                      {:path commands-source})))
    (set (cons query-key assoc-keys))))

(defn- resolved-var
  [namespace symbol]
  (or (ns-resolve namespace symbol)
      (throw (ex-info (str "required var is missing: "
                           namespace "/" symbol)
                      {:namespace namespace :var symbol}))))

(defn- server-filter-keys
  "The command adapter's allow-list, derived from its private implementation
  vector rather than a duplicated literal."
  []
  (set @(resolved-var 'hc.server.app.search-commands 'search-payload-keys)))

(def ^:private command-level-payload-keys
  "Payload fields the handler accepts outside the filter allow-list.
  `:limit` is validated/clamped; `:session-id` is a command-side post-filter."
  #{:limit :session-id})

(defn- client-status-options
  "Status values offered by the history view's `<option>` elements."
  []
  (->> (re-seq #"\(dom/option\s+\{:value\s+\"([^\"]*)\""
               (repo-source history-view-source))
       (map second)
       (remove str/blank?)
       (map keyword)
       set))

(defn- client-required-payload-keys
  "Payload keys read by the client's `apply-result`, extracted from its
  implementation.  This catches a server payload that drops a key the state
  projection still needs."
  []
  (let [body (function-source history-source "apply-result" "normalize-error")]
    (->> (re-seq #"\(:([A-Za-z0-9?!-]+)\s+payload\)" body)
         (map (comp keyword second))
         set)))

;; ---------------------------------------------------------------------------
;; Tiny index fixtures

(defn- probe-message [content]
  {:message-id "m1"
   :role :user
   :versions [{:version-id "m1-v1"
               :content content
               :created-at 1
               :attachments []}]
   :current-version 0
   :attachments []
   :created-at 1
   :deleted? false
   :folded? false})

(defn- probe-session
  [session-id status content & {:keys [temporary?] :or {temporary? false}}]
  (let [message (probe-message content)]
    {:session-id session-id
     :revision 1
     :assistant-id "a1"
     :title "contract probe"
     :status status
     :temporary? temporary?
     :messages {"m1" message}
     :message-order ["m1"]}))

(defn- reader [sessions]
  (fn [session-id]
    (if-let [found (get sessions session-id)]
      {:status :found :session found}
      {:status :missing})))

;; ---------------------------------------------------------------------------
;; 1. Payload key set: client sends a subset of the command allow-list

(deftest client-payload-keys-are-a-subset-of-server-accepted-keys
  (let [client-sent (client-sent-payload-keys)
        client-filters (set history/filter-keys)
        query-key (first (set/difference client-sent client-filters))
        server-filters (server-filter-keys)
        server-accepted (set/union server-filters command-level-payload-keys)
        missing (set/difference client-sent server-accepted)
        server-only (set/difference server-accepted client-sent)]
    (testing "the command builder matches the published filter keys"
      (is (keyword? query-key))
      (is (= (conj client-filters query-key) client-sent)))
    (testing "every client payload key is accepted by the command face"
      (is (empty? missing)
          (str "client sends keys the server does not accept: "
               (pr-str missing))))
    (testing (str "server-only payload keys: " (pr-str server-only))
      (is (set? server-only)))))

;; ---------------------------------------------------------------------------
;; 2. Status enum: every offered option is index-recognized; deleted/temporary
;;    are never offered.

(deftest client-status-options-are-index-recognized
  (let [options (client-status-options)]
    (is (seq options) "the history view must expose at least one status option")
    (doseq [status options]
      (let [session-id (str "status-" (name status))
            term (str "statusprobe" (name status))
            session (probe-session session-id status term)
            _ (index/index-session! *store* session)
            result (index/search *store*
                                 {:query term :status status}
                                 (reader {session-id session}))]
        (is (= 1 (get-in result [:count]))
            (str "client status option is not recognized by index/search: "
                 status))))
    (is (not (contains? options :deleted))
        "deleted sessions must not be offered as a search status")
    (is (not (contains? options :temporary))
        "temporary sessions must not be offered as a search status")))

(deftest deleted-and-temporary-sessions-never-produce-hits
  (let [deleted (probe-session "deleted-probe" :deleted "deletedprobe")
        temporary (probe-session "temporary-probe" :active "tempprobe"
                                 :temporary? true)
        _ (index/index-session! *store* deleted)
        _ (index/index-session! *store* temporary)]
    (is (zero? (get-in (index/search *store*
                                     {:query "deletedprobe"}
                                     (reader {"deleted-probe" deleted}))
                      [:count])))
    (is (zero? (get-in (index/search *store*
                                     {:query "tempprobe"}
                                     (reader {"temporary-probe" temporary}))
                      [:count])))))

;; ---------------------------------------------------------------------------
;; 3. Receipt envelope: gateway projection keeps the command payload, and the
;;    payload contains every key the client projection reads.

(defn- gateway-public-command-result [request result]
  ((resolved-var 'hc.server.gateway 'public-command-result) request result))

(deftest receipt-envelope-preserves-the-client-consumed-payload
  (let [session-id "contract-search"
        term "contractterm"
        session (probe-session session-id :active term)
        _ (index/index-session! *store* session)
        handler (command/search-handler
                 {:store *store*
                  :read-session-fn (reader {session-id session})})
        handler-result (handler {:query term})
        payload (get-in handler-result [:payload])
        envelope {:ok? true
                  :receipt {:status :accepted
                            :result handler-result}}
        projected (gateway-public-command-result
                   {:command-id "contract-1" :command-epoch 1}
                   envelope)
        forwarded (get-in projected [:receipt :result :payload])
        required (client-required-payload-keys)
        missing (set/difference required (set (keys payload)))]
    (is (= :ok (:status handler-result)))
    (is (map? payload))
    (testing "the gateway projection keeps the nested handler payload"
      (is (= payload forwarded))
      (is (= payload (history/receipt-payload projected))))
    (testing "the payload contains the keys apply-result reads"
      (is (seq required))
      (is (empty? missing)
          (str "missing client-required search payload keys: "
               (pr-str missing)))
      (is (contains? payload :index-generation)
          "the search payload must expose :index-generation"))
    (testing "the payload really projects through the client state machine"
      (let [state (history/apply-result history/initial-state payload)]
        (is (= :loaded (:status state)))
        (is (= 1 (:count state)))
        (is (= 1 (count (:hits state))))))))

;; ---------------------------------------------------------------------------
;; 4. Reindex is trusted-only and unreachable from browser/client code.

(defn- clojure-client-files []
  (->> (file-seq (io/file client-source-root))
       (filter #(.isFile ^File %))
       (filter #(re-find #"\.(clj|cljs|cljc)$" (.getName ^File %)))))

(defn- executable-clojure-text
  "Remove comments and string literals so documentation may explain a
  forbidden command without being mistaken for executable usage."
  [source]
  (let [n (count source)
        out (StringBuilder.)]
    (loop [i 0 state :code]
      (if (>= i n)
        (str out)
        (let [c (.charAt ^String source i)]
          (case state
            :comment
            (if (= c \newline)
              (do (.append out c) (recur (inc i) :code))
              (recur (inc i) :comment))

            :string
            (cond
              (= c \\) (recur (min n (+ i 2)) :string)
              (= c \") (recur (inc i) :code)
              (= c \newline) (do (.append out c) (recur (inc i) :string))
              :else (recur (inc i) :string))

            :code
            (cond
              (= c \;) (recur (inc i) :comment)
              (= c \") (recur (inc i) :string)
              (= c \\) (recur (min n (+ i 2)) :code)
              :else (do (.append out c) (recur (inc i) :code)))))))))

(defn- line-at [text index]
  (inc (count (re-seq #"\n" (subs text 0 index)))))

(defn- forbidden-client-references []
  (vec
   (mapcat
    (fn [file]
      (let [clean (executable-clojure-text (slurp file))
            needle ":history/reindex"]
        (loop [from 0 hits []]
          (if-let [index (str/index-of clean needle from)]
            (recur (+ index (count needle))
                   (conj hits {:file (str file)
                               :line (line-at clean index)
                               :needle needle}))
            hits))))
    (clojure-client-files))))

(deftest reindex-is-browser-forbidden
  (is (contains? gateway/default-forbidden-browser-kinds :history/reindex))
  (let [handler (command/reindex-handler
                 {:store *store*
                  :list-sessions-fn (fn [] [])})
        result (handler {:allow-reindex? true})]
    (is (= :error (:status result))
        "a payload field must not enable reindex")
    (is (= :REINDEX_REQUIRES_TRUSTED_CALLER
           (get-in result [:error :code]))))
  (is (.isDirectory ^File (io/file client-source-root)))
  (let [references (forbidden-client-references)]
    (is (empty? references)
        (str "client executable code references :history/reindex: "
             (pr-str references)))))
