(ns hc.server.browser-cdp-smoke-test
  "Real-browser smoke test for the managed browser.

  This is intentionally not a mock: it starts a local HTTP server, launches
  an isolated headless Chrome through CDP, and exercises navigation,
  observation, ordinary input, the sensitive-field refusal, operator pause,
  resume reread, HTTP error classification, connection failure and tab
  close.

  The test is defined only when a Chrome executable is present.  A missing
  browser is an honest skip, never a substitute fake."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.browser.cdp :as cdp]
            [hc.server.browser.service :as service])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.io File]
           [java.net InetSocketAddress ServerSocket]
           [java.nio.charset StandardCharsets]))

(def ^:private page-html
  (str "<!doctype html><html><head><meta charset=\"utf-8\">"
       "<title>Harbor Form</title>"
       "<script>window.__hcAgentInput = true;"
       "window.__hcOperatorInput = function(){};</script>"
       "</head><body>"
       "<h1 id=\"step\">Step 1</h1>"
       "<label for=\"email\">Email</label>"
       "<input id=\"email\" name=\"email\" type=\"email\" "
       "oninput=\"document.getElementById('mirror').textContent=this.value\">"
       "<div id=\"mirror\"></div>"
       "<label for=\"password\">Password</label>"
       "<input id=\"password\" name=\"password\" type=\"password\">"
       "<button id=\"advance\" type=\"button\" "
       "onclick=\"document.getElementById('step').textContent='Step 2'\">"
       "Advance</button>"
       "<a id=\"second\" href=\"/second\">Second page</a>"
       "</body></html>"))

(def ^:private second-html
  "<!doctype html><html><head><title>Second Page</title></head><body><h1>Second</h1></body></html>")

(defn- iframe-html
  [src]
  (str "<!doctype html><html><head><title>Framed</title></head><body>"
       "<button id=\"top\">Top</button>"
       "<iframe src=\"" src "\"></iframe>"
       "</body></html>"))

(defn- chrome-executable
  []
  (or (System/getenv "HC_CHROME")
      (some #(when (.exists (File. %)) %)
            ["/usr/bin/google-chrome"
             "/usr/bin/google-chrome-stable"
             "/usr/bin/chromium"
             "/usr/bin/chromium-browser"
             "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"])))

(defn- start-server!
  ([] (start-server! nil))
  ([iframe-src]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        respond (fn [exchange status body]
                  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
                    (.add (.getResponseHeaders exchange)
                          "Content-Type" "text/html; charset=utf-8")
                    (.sendResponseHeaders exchange status (alength bytes))
                    (with-open [stream (.getResponseBody exchange)]
                      (.write stream bytes))))
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (let [path (.getPath (.getRequestURI exchange))]
                      (case path
                        "/form" (respond exchange 200 page-html)
                        "/second" (respond exchange 200 second-html)
                        "/iframe" (respond exchange 200
                                          (iframe-html (or iframe-src
                                                           "about:blank")))
                        (respond exchange 404 "Not Found")))))]
    (.createContext server "/" handler)
    (.start server)
    server)))

(defn- closed-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- node-by
  [observation pred]
  (first (filter pred (:nodes observation))))

(when-not (chrome-executable)
  (println "SKIP hc.server.browser-cdp-smoke-test: no Chrome executable found"))

(when (chrome-executable)
  (deftest real-browser-smoke
    (let [frame-server (start-server!)
          frame-url (str "http://127.0.0.1:"
                         (.getPort (.getAddress frame-server))
                         "/second")
          server (start-server! frame-url)
          base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
          port (cdp/launch! {:chrome (chrome-executable)})
          svc (service/service
               {:port port
                :session-id "smoke"
                :screenshot-store
                (fn [bytes _]
                  {:status :stored
                   :blob-id (str "smoke-blob-" (count bytes))
                   :size (count bytes)})})]
      (try
        (service/permission! svc {:enabled? true
                                  :scope :site
                                  :sites #{"127.0.0.1"}})
        (service/approve-private-read! svc)
        (testing "navigation and observation of a real page"
          (let [opened (service/open-tab! svc {:url (str base-url "/form")})]
            (is (= :ok (:status opened)) (pr-str opened))
            (let [tab-id (get-in opened [:tab :tab-id])
                  observed (service/observe! svc {:tab-id tab-id})]
              (is (= :ok (:status observed)) (pr-str observed))
              (let [observation (:observation observed)
                    password (node-by observation #(= "password" (:type %)))
                    email (node-by observation #(= "email" (:type %)))
                    advance (node-by observation #(= "Advance" (:label %)))]
                (is (= "Harbor Form" (:title observation)))
                (is (str/includes? (:text observation) "Step 1"))
                (is (true? (:sensitive? password)))
                (is (false? (:sensitive? email)))
                (is (some? advance))

                (testing "ordinary text is typed and browser state is visible"
                  (let [typed (service/act! svc {:tab-id tab-id
                                                 :action :type
                                                 :observation-id (:observation-id observation)
                                                 :target email
                                                 :text "operator@example.test"})]
                    (is (= :ok (:status typed)) (pr-str typed)))
                  (let [state-observation
                        (:observation (service/observe! svc {:tab-id tab-id}))]
                    (is (str/includes? (:text state-observation)
                                       "operator@example.test"))
                    (let [refused
                          (service/act! svc {:tab-id tab-id
                                             :action :type
                                             :observation-id (:observation-id state-observation)
                                             :target (node-by state-observation
                                                              #(= "password" (:type %)))
                                             :text "hunter2"})]
                    (is (= :refused (:status refused)))
                    (is (= :sensitive-input-requires-operator
                           (get-in refused [:error :code])))
                    (is (= :sensitive-paused (:takeover refused)))
                    (is (not (str/includes? (pr-str (service/cards svc))
                                            "hunter2")))))

                (testing "resume rereads and old targets are refused"
                  (let [resumed (service/resume! svc {:tab-id tab-id})]
                    (is (= :ok (:status resumed)) (pr-str resumed))
                    (is (= :agent-active (:takeover resumed)))
                    (is (true? (:reread? resumed)))
                    (let [stale (service/act! svc {:tab-id tab-id
                                                   :action :click
                                                   :observation-id (:observation-id observation)
                                                   :target advance})]
                      (is (= :refused (:status stale)))
                      (is (= :stale-target (get-in stale [:error :code]))))
                    (let [fresh (:observation resumed)
                          scrolled (service/act! svc {:tab-id tab-id
                                                      :action :scroll
                                                      :observation-id
                                                      (:observation-id fresh)
                                                      :delta-y 50})
                          pressed (service/act! svc {:tab-id tab-id
                                                     :action :press
                                                     :key "ArrowDown"
                                                     :observation-id
                                                     (:observation-id fresh)})
                          fresh-advance (node-by fresh #(= "Advance" (:label %)))
                          clicked (service/act! svc {:tab-id tab-id
                                                     :action :click
                                                     :observation-id (:observation-id fresh)
                                                     :target fresh-advance})]
                      (is (= :ok (:status scrolled)) (pr-str scrolled))
                      (is (= :ok (:status pressed)) (pr-str pressed))
                      (is (= :ok (:status clicked)) (pr-str clicked))
                      (let [after (service/observe! svc {:tab-id tab-id})]
                        (is (= :ok (:status after)))
                        (is (str/includes? (get-in after [:observation :text])
                                           "Step 2"))))))

                (testing "operator input pauses, and resume rereads again"
                  (let [paused (service/operator-input! svc {:tab-id tab-id
                                                             :kind :input
                                                             :summary "operator typed"})]
                    (is (= :ok (:status paused)))
                    (is (= :paused-manual (:takeover paused))))
                  (let [current (get-in (service/snapshot svc)
                                        [:tabs tab-id :last-observation])
                        refused (service/act! svc {:tab-id tab-id
                                                   :action :scroll
                                                   :observation-id (:observation-id current)
                                                   :delta-y 100})]
                    (is (= :refused (:status refused)))
                    (is (= :takeover-active (get-in refused [:error :code]))))
                  (let [resumed (service/resume! svc {:tab-id tab-id})]
                    (is (= :ok (:status resumed)) (pr-str resumed))
                    (is (true? (:reread? resumed)))
                    (is (str/includes? (get-in resumed [:observation :text])
                                       "operator@example.test"))
                    (is (str/includes? (get-in resumed [:observation :text])
                                       "Step 2"))))

                (testing "explicit takeover blocks actions and resume preserves state"
                  (let [before-url (get-in (service/snapshot svc) [:tabs tab-id :url])
                        before-cards (count (:cards (service/cards svc)))
                        taken (service/takeover! svc {:tab-id tab-id})]
                    (is (= :ok (:status taken)))
                    (is (= :operator (:takeover taken)))
                    (let [refused (service/act! svc {:tab-id tab-id
                                                     :action :click
                                                     :observation-id "obs-any"
                                                     :target {:ref "hc-1"}})]
                      (is (= :refused (:status refused)))
                      (is (= :takeover-active (get-in refused [:error :code]))))
                    (let [resumed (service/resume! svc {:tab-id tab-id})]
                      (is (= :ok (:status resumed)) (pr-str resumed))
                      (is (true? (:reread? resumed)))
                      (is (= before-url
                             (get-in (service/snapshot svc) [:tabs tab-id :url])))
                      (is (>= (count (:cards (service/cards svc))) before-cards))
                      (is (str/includes? (get-in resumed [:observation :text])
                                         "operator@example.test")))))

                (testing "HTTP error pages are classified"
                  (let [result (service/navigate! svc {:tab-id tab-id
                                                       :url (str base-url "/missing")})]
                    (is (= :ok (:status result)) (pr-str result))
                    (is (= :not-found (:page-error result)))))

                (testing "a refused connection is a connection failure"
                  (let [result (service/navigate! svc {:tab-id tab-id
                                                       :url (str "http://127.0.0.1:"
                                                                 (closed-port) "/")})]
                    (is (= :failed (:status result)) (pr-str result))
                    (is (= :connection-failed (get-in result [:error :code])))))

                (testing "screenshots are historical and stored"
                  (let [{:keys [cards]} (service/cards svc)
                        screenshots (keep :screenshot cards)]
                    (is (>= (count cards) 6))
                    (is (every? #(= :historical (:kind %)) screenshots))
                    (is (every? #(false? (:live? %)) screenshots))
                    (is (some #(str/includes? (pr-str %) "smoke-blob-")
                              screenshots))))

                (testing "clicking a link reports the committed navigation"
                  (let [nav (service/navigate! svc {:tab-id tab-id
                                                    :url (str base-url "/form")})]
                    (is (= :ok (:status nav)) (pr-str nav)))
                  (let [observed (service/observe! svc {:tab-id tab-id})
                        observation (:observation observed)
                        link (node-by observation #(= "Second page" (:label %)))]
                    (is (= :ok (:status observed)) (pr-str observed))
                    (is (some? link))
                    (let [clicked (service/act! svc {:tab-id tab-id
                                                     :action :click
                                                     :observation-id
                                                     (:observation-id observation)
                                                     :target link})]
                      (is (= :ok (:status clicked)) (pr-str clicked))
                      (is (true? (get-in clicked [:result :navigated?])))
                      (is (str/includes? (str (get-in clicked [:result :url]))
                                         "/second")))))

                (testing "a framed page is fail-closed for agent input"
                  (let [nav (service/navigate! svc {:tab-id tab-id
                                                    :url (str base-url "/iframe")})]
                    (is (= :ok (:status nav)) (pr-str nav))
                    (let [observed (service/observe! svc {:tab-id tab-id})
                          observation (:observation observed)
                          top (node-by observation #(= "Top" (:label %)))]
                      (is (= :ok (:status observed)) (pr-str observed))
                      (is (= :uncovered
                             (:operator-input-coverage observation)))
                      (is (some #{:operator-input-uncovered}
                                (:limitations observation)))
                      (is (some? top))
                      (let [refused (service/act! svc {:tab-id tab-id
                                                       :action :click
                                                       :observation-id
                                                       (:observation-id observation)
                                                       :target top})]
                        (is (= :refused (:status refused)))
                        (is (= :operator-input-uncovered
                               (get-in refused [:error :code])))))))

                (testing "stop keeps pages, close keeps cards"
                  (let [stopped (service/stop! svc)]
                    (is (= :ok (:status stopped)))
                    (is (= 1 (:open-tabs stopped))))
                  (let [before (count (:cards (service/cards svc)))
                        closed (service/close-tab! svc {:tab-id tab-id})]
                    (is (= :ok (:status closed)))
                    (is (= :closed (get-in closed [:tab :status])))
                    (is (= before (count (:cards (service/cards svc)))))
                    (let [after (service/observe! svc {:tab-id tab-id})]
                      (is (= :refused (:status after)))
                      (is (= :tab-closed (get-in after [:error :code])))))))))))
        (finally
          (try (service/shutdown! svc) (catch Throwable _ nil))
          (try (.stop server 0) (catch Throwable _ nil))
          (try (.stop frame-server 0) (catch Throwable _ nil)))))))
