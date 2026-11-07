(ns hc.client.workbench.scale-bench
  "Pure projection benchmark for the §8.5 client scale gates.

  This measures the workbench model over synthetic server state; it does not
  mount React.  The DOM/scroll side lives in `scale_dom_test.cljs`.  Run with:

    clojure -M:dev -m hc.client.workbench.scale-bench

  The output is one EDN map per scenario so the report can quote exact
  numbers."
  (:require [clojure.string :as str]
            [hc.client.conversation.draft :as draft]
            [hc.client.workbench.model :as model]))

(defn- now-ms []
  (/ (double (System/nanoTime)) 1e6))

(defn- used-mb []
  (let [rt (Runtime/getRuntime)]
    (/ (- (.totalMemory rt) (.freeMemory rt)) 1048576.0)))

(defn- measure
  "Run F ITERATIONS times, return {:min :median :max :result :used-mb}."
  [iterations f]
  (let [times (vec (for [_ (range iterations)]
                     (let [start (now-ms)
                           result (f)]
                       {:ms (- (now-ms) start) :result result})))
        sorted (sort (map :ms times))]
    {:min (first sorted)
     :median (nth sorted (quot (count sorted) 2))
     :max (last sorted)
     :result (:result (last times))
     :used-mb (used-mb)}))

(defn- session
  [i]
  {:session-id (str "s" i)
   :title (str "会话 " i " 配置改造与发布计划")
   :status (if (even? i) :idle :finished)
   :updated-at (str "2026-11-05T08:" (format "%02d" (mod i 60)) ":00Z")
   :model-label (if (even? i) "model-a" "model-b")
   :message-ids []})

(defn- message
  [i]
  (let [kind (mod i 5)
        content (case kind
                  0 (str "普通消息 " i " 中文与 English 混排。")
                  1 (str "```clojure\n(defn f" i " [x] (+ x " i "))\n```")
                  2 "| 列 A | 列 B |\n|---|---|\n| 1 | 2 |"
                  3 (str "工具结果 " i)
                  4 (str "引用与附件消息 " i))]
    {:message-id (str "m" i)
     :session-id "s0"
     :role (if (even? i) :user :assistant)
     :content content
     :content-version (mod i 3)
     :citations (when (= kind 4)
                  [{:title (str "来源 " i) :url (str "https://example.test/" i)}])
     :attachments (when (= kind 4)
                    [{:id (str "a" i) :name "note.txt" :status :ready}])
     :tool-calls (when (= kind 3)
                   [{:tool-call-id (str "t" i) :status :succeeded}])}))

(defn- build-db
  [session-count message-count]
  (let [sessions (into {} (map (fn [i] [(str "s" i) (session i)]))
                       (range session-count))
        messages (into {} (map (fn [i] [(str "m" i) (message i)]))
                       (range message-count))
        order (mapv #(str "m" %) (range message-count))]
    {:ui/selected-session-id "s0"
     :ui/draft {"s0" (assoc draft/empty-draft
                            :text (apply str (repeat 20000 "中a")))}
     :ui/stream {}
     :ui/command {}
     :ui/ask {}
     :ui/connection {:phase :synced :command-allowed? true}
     :ui/viewport-width 1280
     :session/by-id (assoc sessions "s0"
                           (assoc (session 0) :message-ids order))
     :message/by-id messages
     :message/order order}))

(defn- run-scenario
  [label iterations f]
  (let [r (measure iterations f)]
    (println (pr-str (assoc r :scenario label)))
    (flush)
    r))

(defn -main
  [& _]
  (let [sessions (Long/parseLong (or (System/getenv "HC_SCALE_SESSIONS") "1000"))
        messages (Long/parseLong (or (System/getenv "HC_SCALE_MESSAGES") "2000"))
        db (build-db sessions messages)]
    (println (pr-str {:env {:java (System/getProperty "java.version")
                            :os (System/getProperty "os.name")
                            :arch (System/getProperty "os.arch")
                            :cpus (.availableProcessors (Runtime/getRuntime))
                            :sessions sessions
                            :messages messages
                            :used-mb (used-mb)}}))
    (run-scenario :sessions-list 10 #(count (model/sessions db)))
    (run-scenario :sessions-filter 10 #(count (model/filter-sessions
                                               (model/sessions db) "发布")))
    (run-scenario :message-order 10 #(count (model/message-order db "s0")))
    (run-scenario :messages-for-session 10 #(count (model/messages-for-session db "s0")))
    (run-scenario :session-sources 10 #(count (model/session-sources db "s0")))
    (run-scenario :selected-session 10 #(model/selected-session-id db))))
