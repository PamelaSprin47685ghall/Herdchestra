(ns hc.server.browser.cdp
  "Chrome DevTools Protocol adapter for `hc.server.browser.port`.

  The adapter speaks CDP over the JDK WebSocket client.  It can connect to an
  operator's already-running Chrome (the service does not own that process)
  or launch an isolated headless Chrome for tests and smoke checks.

  Design choices that keep the browser boundary honest:

    * actions resolve a semantic `data-hc-ref` against the current DOM and
      dispatch trusted input at the element's current rectangle; a cached
      coordinate is never reused;
    * observation returns visible top-document text and interactive nodes,
      never field values, and never claims to read cross-origin iframes;
    * a lost connection after an input event is `:outcome-unknown`, not a
      failure that invites a retry;
    * `close!` stops only a process this adapter launched."
  (:require [clojure.string :as str]
            [hc.server.browser.json :as json]
            [hc.server.browser.model :as model]
            [hc.server.browser.port :as port])
  (:import [java.io File RandomAccessFile]
           [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers WebSocket WebSocket$Listener]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration]
           [java.util Base64 UUID]
           [java.util.concurrent TimeUnit]))

(def ^:private default-timeout-ms 15000)
(def ^:private max-log-tail-bytes (* 64 1024))

(defn- read-log-tail
  "Read at most MAX-BYTES from the end of FILE as UTF-8 text.

  A failed Chrome launch is a diagnostic path.  Reading the whole log there
  can copy an arbitrarily large file into the heap; the tail is enough to
  explain the failure."
  [^File file max-bytes]
  (try
    (let [length (.length file)
          start (max 0 (- length (long max-bytes)))
          size (int (min length (long max-bytes)))]
      (with-open [raf (RandomAccessFile. file "r")]
        (.seek raf start)
        (let [buf (byte-array size)]
          (.readFully raf buf)
          (String. buf StandardCharsets/UTF_8))))
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; JSON-RPC connection

(defn- handle-message!
  [pending listeners event-handlers text]
  (try
    (let [message (json/decode text)]
      (if-let [id (get message "id")]
        (when-let [p (get @pending id)]
          (swap! pending dissoc id)
          (deliver p message))
        (when-let [method (get message "method")]
          (doseq [handler (get @event-handlers method)]
            (try (handler message) (catch Throwable _ nil)))
          (let [entries (get @listeners method)]
            (when (seq entries)
              (swap! listeners update method
                     (fn [values]
                       (vec (remove #(realized? (:promise %)) values))))
              (doseq [{:keys [promise pred]} entries
                      :when (or (nil? pred) (pred message))]
                (deliver promise message)))))))
    (catch Throwable _ nil)))

(defn- connect-ws!
  [^HttpClient client url timeout-ms]
  (let [pending (atom {})
        listeners (atom {})
        event-handlers (atom {})
        next-id (atom 0)
        buffer (atom (StringBuilder.))
        closed (atom nil)
        listener
        (reify WebSocket$Listener
          (onOpen [_ ws]
            (.request ws 1)
            nil)
          (onText [_ ws data last?]
            (let [^StringBuilder sb (.append ^StringBuilder @buffer (str data))]
              (when last?
                (let [text (.toString sb)]
                  (reset! buffer (StringBuilder.))
                  (handle-message! pending listeners event-handlers text))))
            (.request ws 1)
            nil)
          (onBinary [_ ws _data _last?]
            (.request ws 1)
            nil)
          (onPing [_ ws _data]
            (.request ws 1)
            nil)
          (onPong [_ _ws _data] nil)
          (onClose [_ _ws status reason]
            (reset! closed {:status status :reason reason})
            (doseq [[_ p] @pending]
              (deliver p {:error {:code :connection-closed
                                  :status status
                                  :reason reason}}))
            (reset! pending {})
            nil)
          (onError [_ _ws error]
            (reset! closed {:error (.getMessage error)})
            (doseq [[_ p] @pending]
              (deliver p {:error {:code :connection-error
                                  :message (.getMessage error)}}))
            (reset! pending {})
            nil))
        ws (try
             (.join (.buildAsync (.newWebSocketBuilder client)
                                 (URI/create url)
                                 listener))
             (catch Throwable t
               (throw (ex-info "CDP WebSocket connection failed"
                               {:code :cdp-connect-failed
                                :url url
                                :message (.getMessage t)}
                               t))))]
    {:ws ws
     :pending pending
     :listeners listeners
     :event-handlers event-handlers
     :next-id next-id
     :closed closed}))

(defn- add-event-handler!
  [conn method handler]
  (swap! (:event-handlers conn) update method (fnil conj []) handler)
  (fn []
    (swap! (:event-handlers conn) update method
           (fn [handlers] (vec (remove #(identical? % handler) handlers))))))

(defn- close-ws!
  [conn]
  (when conn
    (try
      (.join (.sendClose ^WebSocket (:ws conn) 1000 "closed"))
      (catch Throwable _
        (try
          (.abort ^WebSocket (:ws conn))
          (catch Throwable _ nil))))))

(defn- request!
  ([conn method]
   (request! conn method {} default-timeout-ms))
  ([conn method params timeout-ms]
   (let [id (swap! (:next-id conn) inc)
         p (promise)]
     (swap! (:pending conn) assoc id p)
     (try
       (.join (.sendText ^WebSocket (:ws conn)
                         (json/encode {"id" id
                                       "method" method
                                       "params" (or params {})})
                         true))
       (catch Throwable t
         (swap! (:pending conn) dissoc id)
         (throw (ex-info "CDP send failed"
                         {:code :cdp-send-failed
                          :method method
                          :message (.getMessage t)}
                         t))))
     (let [response (deref p timeout-ms ::timeout)]
       (swap! (:pending conn) dissoc id)
       (cond
         (= ::timeout response)
         (throw (ex-info "CDP request timed out"
                         {:code :cdp-timeout :method method}))

         (get response "error")
         (throw (ex-info "CDP request failed"
                         {:code :cdp-error
                          :method method
                          :cdp-error (get response "error")}))

         :else
         (get response "result"))))))

(defn- event-promise!
  ([conn method] (event-promise! conn method nil))
  ([conn method pred]
   (let [p (promise)]
     (swap! (:listeners conn) update method (fnil conj [])
            {:promise p :pred pred})
     p)))

(defn- remove-event!
  [conn method p]
  (swap! (:listeners conn) update method
         (fn [entries]
           (vec (remove #(identical? p (:promise %)) entries)))))

(defn- wait-event!
  [conn method timeout-ms]
  (let [p (event-promise! conn method)
        message (deref p timeout-ms ::timeout)]
    (remove-event! conn method p)
    message))

;; ---------------------------------------------------------------------------
;; HTTP discovery

(defn- http-client
  [timeout-ms]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis timeout-ms))
      (.build)))

(defn- http-json
  [^HttpClient client method url]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis default-timeout-ms)))
        builder (case method
                  :get (.GET builder)
                  :put (.PUT builder (HttpRequest$BodyPublishers/noBody))
                  (throw (ex-info "unsupported HTTP method" {:method method})))
        response (.send client (.build builder)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/decode (.body response))}))

(defn- http-get-text
  [^HttpClient client url]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis default-timeout-ms))
                    (.GET)
                    (.build))
        response (.send client request
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn- url-encode
  [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- targets
  [^HttpClient client endpoint]
  (let [{:keys [status body]} (http-json client :get (str endpoint "/json/list"))]
    (when-not (= 200 status)
      (throw (ex-info "could not list browser targets"
                      {:code :cdp-discovery-failed :status status})))
    body))

(defn- new-target
  [^HttpClient client endpoint]
  (let [{:keys [status body]}
        (http-json client :put (str endpoint "/json/new?" (url-encode "about:blank")))]
    (when-not (<= 200 status 299)
      (throw (ex-info "could not create browser target"
                      {:code :cdp-target-failed :status status})))
    body))

;; ---------------------------------------------------------------------------
;; Page evaluation

(defn- evaluate-value
  [conn expression timeout-ms]
  (let [result (request! conn "Runtime.evaluate"
                         {"expression" expression
                          "returnByValue" true
                          "awaitPromise" true
                          "userGesture" false}
                         timeout-ms)]
    (if (get result "exceptionDetails")
      (throw (ex-info "page evaluation failed"
                      {:code :unreadable-content
                       :details (get result "exceptionDetails")}))
      (get-in result ["result" "value"]))))

(defn- evaluate-value-in-context
  [conn expression context-id timeout-ms]
  (let [result (request! conn "Runtime.evaluate"
                         {"expression" expression
                          "contextId" context-id
                          "returnByValue" true
                          "awaitPromise" true
                          "userGesture" false}
                         timeout-ms)]
    (if (get result "exceptionDetails")
      (throw (ex-info "page evaluation failed"
                      {:code :unreadable-content
                       :details (get result "exceptionDetails")}))
      (get-in result ["result" "value"]))))

(defn- page-info
  [conn]
  (let [value (evaluate-value
               conn
               "({url: location.href, title: document.title, readyState: document.readyState, documentId: String(performance.timeOrigin)+':'+location.href})"
               5000)]
    {:url (get value "url")
     :title (get value "title")
     :ready-state (get value "readyState")
     :document-id (get value "documentId")}))

(def ^:private sensitive-form-expression
  (str "!!document.querySelector("
       "'input[type=\"password\"],"
       "input[autocomplete*=\"one-time-code\"],"
       "input[autocomplete*=\"cc-\"],"
       "input[name*=\"otp\"],input[id*=\"otp\"]')"))

(defn- page-info*
  "Page identity plus a page-level sensitive-form flag for targetless actions."
  [conn]
  (let [info (page-info conn)
        sensitive? (try (boolean (evaluate-value conn sensitive-form-expression 5000))
                        (catch Throwable _ false))]
    (assoc info :sensitive-form? sensitive?)))

(defn- frame-tree
  [conn]
  (get (request! conn "Page.getFrameTree" {} 5000) "frameTree"))

(defn- child-frame-count
  [node]
  (let [children (vec (get node "childFrames"))]
    (+ (count children)
       (reduce + 0 (map child-frame-count children)))))

(defn- operator-ready?
  [conn context-id]
  (try
    (true? (evaluate-value-in-context
            conn
            (str "window.__hcOperatorReady === true && "
                 "typeof window.__hcOperatorInput === 'function'")
            context-id
            2000))
    (catch Throwable _ false)))

(defn- set-agent-input!
  [conn value?]
  (when-let [context-id @(:operator-main-context conn)]
    (try
      (evaluate-value-in-context
       conn
       (str "window.__hcAgentInput = " (if value? "true" "false") "; true")
       context-id
       2000)
      true
      (catch Throwable _ false))))

(defn- node->clj
  [node]
  (let [rect (get node "rect")]
    {:ref (get node "ref")
     :tag (get node "tag")
     :type (get node "type")
     :field-name (get node "name")
     :id (get node "id")
     :autocomplete (get node "autocomplete")
     :label (get node "label")
     :placeholder (get node "placeholder")
     :disabled (boolean (get node "disabled"))
     :checked (boolean (get node "checked"))
     :visible? true
     :rect (when rect
             {:x (get rect "x")
              :y (get rect "y")
              :width (get rect "width")
              :height (get rect "height")})}))

(def ^:private observe-expression
  (str
   "(() => {"
   "  const visible = (el) => {"
   "    const r = el.getBoundingClientRect();"
   "    const s = getComputedStyle(el);"
   "    return r.width > 0 && r.height > 0"
   "      && s.visibility !== 'hidden' && s.display !== 'none'"
   "      && el.getAttribute('aria-hidden') !== 'true';"
   "  };"
   "  const label = (el) => {"
   "    const l = el.labels && el.labels[0];"
   "    return (el.getAttribute('aria-label') || (l && l.innerText)"
   "      || el.getAttribute('placeholder') || el.innerText || '')"
   "      .trim().slice(0, 120);"
   "  };"
   "  let seq = window.__hcRefSeq || 0;"
   "  const nodes = [];"
   "  const candidates = document.querySelectorAll("
   "    'a,button,input,textarea,select,[role=\"button\"],[role=\"link\"],[contenteditable=\"true\"]');"
   "  for (const el of candidates) {"
   "    if (nodes.length >= 200) break;"
   "    if (!visible(el)) continue;"
   "    seq += 1;"
   "    const ref = 'hc-' + seq;"
   "    el.setAttribute('data-hc-ref', ref);"
   "    const r = el.getBoundingClientRect();"
   "    nodes.push({"
   "      ref: ref,"
   "      tag: el.tagName.toLowerCase(),"
   "      type: (el.getAttribute('type') || '').toLowerCase() || null,"
   "      name: el.getAttribute('name') || null,"
   "      id: el.id || null,"
   "      autocomplete: el.getAttribute('autocomplete') || null,"
   "      label: label(el),"
   "      placeholder: el.getAttribute('placeholder') || null,"
   "      disabled: !!el.disabled,"
   "      checked: !!el.checked,"
   "      rect: {x: r.x, y: r.y, width: r.width, height: r.height}"
   "    });"
   "  }"
   "  window.__hcRefSeq = seq;"
   "  return {"
   "    url: location.href,"
   "    title: document.title,"
   "    readyState: document.readyState,"
   "    documentId: String(performance.timeOrigin) + ':' + location.href,"
   "    text: (document.body ? (document.body.innerText || '') : '').slice(0, 20000),"
   "    nodes: nodes,"
   "    loginForm: !!document.querySelector('input[type=\"password\"]'),"
   "    passwordFields: document.querySelectorAll('input[type=\"password\"]').length,"
   "    iframes: document.querySelectorAll('iframe').length"
   "  };"
   "})()"))

(def ^:private operator-input-script
  "Installed in every controlled document.  It reports trusted human input
  through the CDP binding, and ignores input generated by the adapter's own
  act! calls while __hcAgentInput is set.  It runs in the isolated operator
  world, so the page cannot change either global."
  (str
   "(function () {"
   "  if (window.__hcOperatorHook) return;"
   "  window.__hcOperatorHook = true;"
   "  const send = (kind) => {"
   "    if (window.__hcAgentInput) return;"
   "    try {"
   "      if (typeof window.__hcOperatorInput === 'function')"
   "        window.__hcOperatorInput(kind);"
   "    } catch (_) {}"
   "  };"
   "  ['pointerdown','keydown','input','click','wheel'].forEach((kind) => {"
   "    window.addEventListener(kind, (event) => {"
   "      if (event.isTrusted) send(kind);"
   "    }, {capture: true, passive: true});"
   "  });"
   "  window.__hcOperatorReady = true;"
   "})();"))

(def ^:private operator-world-name "hc-operator")
(def ^:private operator-binding-name "__hcOperatorInput")
(def ^:private operator-event-kinds
  {"pointerdown" :pointerdown
   "keydown" :keydown
   "input" :input
   "click" :click
   "wheel" :wheel})

(defn- ensure-operator-hook!
  "Re-check the isolated hook immediately before an action and repair a
  missing binding or hook in that context.  Failure stays fail-closed."
  [conn]
  (let [frame-id (or @(:operator-main-frame conn)
                     (try (get-in (frame-tree conn) ["frame" "id"])
                          (catch Throwable _ nil)))
        _ (when frame-id (reset! (:operator-main-frame conn) frame-id))
        existing @(:operator-main-context conn)
        context-id
        (if (and (integer? existing) (operator-ready? conn existing))
          existing
          (when frame-id
            (try
              (let [new-context
                    (get (request! conn "Page.createIsolatedWorld"
                                   {"frameId" frame-id
                                    "worldName" operator-world-name}
                                   5000)
                         "executionContextId")]
                (when (integer? new-context)
                  (swap! (:operator-contexts conn) conj new-context)
                  (reset! (:operator-main-context conn) new-context)
                  new-context))
              (catch Throwable _ nil))))]
    (when (integer? context-id)
      (try
        (try
          (request! conn "Runtime.addBinding"
                    {"name" operator-binding-name
                     "executionContextId" context-id}
                    2000)
          (catch Throwable _ nil))
        (try
          (evaluate-value-in-context conn operator-input-script
                                     context-id 5000)
          (catch Throwable _ nil))
        (operator-ready? conn context-id)
        (catch Throwable _ false)))))

(defn- uncovered-frame-error
  "Return a fail-closed error when this page has any child frame or its frame
  tree cannot be read.  The adapter cannot observe human input in frames."
  [conn]
  (let [tree (try (frame-tree conn) (catch Throwable _ nil))
        child-frames (when tree (child-frame-count tree))]
    (when (or (nil? tree) (pos? (long (or child-frames 0))))
      (model/browser-error
       :operator-input-uncovered
       "This page contains frames whose operator input cannot be monitored."
       {:child-frames child-frames
        :frame-tree-known? (some? tree)}))))

(defn- observe-page
  [conn]
  (let [value (evaluate-value conn observe-expression 10000)
        tree (try (frame-tree conn) (catch Throwable _ nil))
        child-frames (when tree (child-frame-count tree))]
    {:status :ok
     :url (get value "url")
     :title (get value "title")
     :ready-state (get value "readyState")
     :document-id (get value "documentId")
     :text (get value "text")
     :nodes (mapv node->clj (get value "nodes"))
     :login-form? (boolean (get value "loginForm"))
     :password-fields (get value "passwordFields")
     :iframes (get value "iframes")
     :child-frames child-frames
     :frame-tree-known? (some? tree)}))

(defn- inspect-expression
  [ref]
  (str
   "(() => {"
   "  const ref = " (json/encode ref) ";"
   "  const el = document.querySelector('[data-hc-ref=\"' + ref + '\"]');"
   "  const documentId = String(performance.timeOrigin) + ':' + location.href;"
   "  if (!el) return {found: false, documentId: documentId,"
   "    url: location.href, title: document.title};"
   "  const r = el.getBoundingClientRect();"
   "  const s = getComputedStyle(el);"
   "  const form = el.closest('form');"
   "  const sensitiveForm = !!(form && form.querySelector("
   "    'input[type=\"password\"],input[autocomplete*=\"one-time-code\"],"
   "    input[autocomplete*=\"cc-\"],input[name*=\"otp\"],input[id*=\"otp\"]'));"
   "  const l = el.labels && el.labels[0];"
   "  return {"
   "    found: true,"
   "    documentId: documentId,"
   "    url: location.href,"
   "    title: document.title,"
   "    tag: el.tagName.toLowerCase(),"
   "    type: (el.getAttribute('type') || '').toLowerCase() || null,"
   "    name: el.getAttribute('name') || null,"
   "    id: el.id || null,"
   "    autocomplete: el.getAttribute('autocomplete') || null,"
   "    label: (el.getAttribute('aria-label') || (l && l.innerText)"
   "      || el.getAttribute('placeholder') || el.innerText || '')"
   "      .trim().slice(0, 120),"
   "    placeholder: el.getAttribute('placeholder') || null,"
   "    disabled: !!el.disabled,"
   "    visible: r.width > 0 && r.height > 0"
   "      && s.visibility !== 'hidden' && s.display !== 'none',"
   "    rect: {x: r.x, y: r.y, width: r.width, height: r.height},"
   "    sensitiveForm: sensitiveForm"
   "  };"
   "})()"))

(defn- inspect-target
  [conn target]
  (let [ref (:ref target)]
    (if (str/blank? (str ref))
      {:status :failed
       :error {:code :stale-target
               :message "The target has no observation reference."}}
      (try
        (let [value (evaluate-value conn (inspect-expression ref) 5000)]
          (if-not (get value "found")
            {:status :failed
             :document-id (get value "documentId")
             :url (get value "url")
             :title (get value "title")
             :error {:code :stale-target
                     :message "The observed element is no longer in the document."}}
            (let [node (node->clj (assoc value "ref" ref))]
              {:status :ok
               :document-id (get value "documentId")
               :url (get value "url")
               :title (get value "title")
               :sensitive-form? (boolean (get value "sensitiveForm"))
               :target (assoc node
                              :visible? (boolean (get value "visible"))
                              :sensitive? (model/sensitive-target? node))})))
        (catch Throwable t
          {:status :failed
           :error {:code :unreadable-content
                   :message (.getMessage t)}})))))

;; ---------------------------------------------------------------------------
;; Input dispatch

(defn- center
  [rect]
  (let [x (+ (double (or (:x rect) 0)) (/ (double (or (:width rect) 0)) 2))
        y (+ (double (or (:y rect) 0)) (/ (double (or (:height rect) 0)) 2))]
    [(long (Math/round x)) (long (Math/round y))]))

(defn- dispatch-click!
  [conn rect]
  (let [[x y] (center rect)]
    (request! conn "Input.dispatchMouseEvent"
              {"type" "mouseMoved" "x" x "y" y} 5000)
    (request! conn "Input.dispatchMouseEvent"
              {"type" "mousePressed" "x" x "y" y
               "button" "left" "clickCount" 1} 5000)
    (request! conn "Input.dispatchMouseEvent"
              {"type" "mouseReleased" "x" x "y" y
               "button" "left" "clickCount" 1} 5000)))

(defn- focus-expression
  [ref]
  (str "(() => { const ref = " (json/encode ref) ";"
       " const el = document.querySelector('[data-hc-ref=\"' + ref + '\"]');"
       " if (!el) return false; el.focus();"
       " if (el.select) el.select(); return true; })()"))

(defn- key-code
  [key]
  (case key
    "Enter" {"key" "Enter" "code" "Enter" "windowsVirtualKeyCode" 13}
    "Tab" {"key" "Tab" "code" "Tab" "windowsVirtualKeyCode" 9}
    "Escape" {"key" "Escape" "code" "Escape" "windowsVirtualKeyCode" 27}
    "Backspace" {"key" "Backspace" "code" "Backspace" "windowsVirtualKeyCode" 8}
    "ArrowUp" {"key" "ArrowUp" "code" "ArrowUp" "windowsVirtualKeyCode" 38}
    "ArrowDown" {"key" "ArrowDown" "code" "ArrowDown" "windowsVirtualKeyCode" 40}
    "ArrowLeft" {"key" "ArrowLeft" "code" "ArrowLeft" "windowsVirtualKeyCode" 37}
    "ArrowRight" {"key" "ArrowRight" "code" "ArrowRight" "windowsVirtualKeyCode" 39}
    {"key" key "code" key "windowsVirtualKeyCode" 0}))

(defn- dispatch-key!
  [conn key]
  (let [params (key-code key)]
    (request! conn "Input.dispatchKeyEvent"
              (merge {"type" "keyDown"} params) 5000)
    (request! conn "Input.dispatchKeyEvent"
              (merge {"type" "keyUp"} params) 5000)))

(defn- submit-expression
  [ref]
  (str "(() => { const ref = " (json/encode ref) ";"
       " const el = document.querySelector('[data-hc-ref=\"' + ref + '\"]');"
       " if (!el) return false;"
       " if (el.form && el.form.requestSubmit) el.form.requestSubmit();"
       " else el.click(); return true; })()"))

(defn- perform-action!
  [conn action sent?]
  (let [kind (:kind action)
        target (:target action)
        targetless? (or (= :scroll kind)
                        (and (= :press kind) (nil? target)))
        inspected (if targetless?
                    (merge {:status :ok} (page-info* conn))
                    (inspect-target conn target))]
    (if (not= :ok (:status inspected))
      inspected
      (let [{:keys [target sensitive-form?]} inspected
            rect (:rect target)]
        (cond
          (and (not targetless?)
               (or (false? (:visible? target))
                   (nil? rect)
                   (<= (double (or (:width rect) 0)) 0)
                   (<= (double (or (:height rect) 0)) 0)))
          {:status :failed
           :error {:code :blocked-by-page
                   :message "The target is not visible; the page blocked the action."}}

          (= :type kind)
          (let [focused? (try
                           (boolean
                            (evaluate-value conn
                                            (focus-expression (:ref target))
                                            5000))
                           (catch Throwable _ false))]
            (if-not focused?
              {:status :failed
               :error {:code :stale-target
                       :message "The target could not be focused; no text was typed."}}
              (do
                (reset! sent? true)
                (request! conn "Input.insertText"
                          {"text" (str (:text action))} 5000)
                {:status :ok :document-id (:document-id inspected)
                 :external-effect? false})))

          (= :click kind)
          (do (reset! sent? true)
              (dispatch-click! conn rect)
              {:status :ok :document-id (:document-id inspected)
               :external-effect? false})

          (= :submit kind)
          (do
            (reset! sent? true)
            (evaluate-value conn (submit-expression (:ref target)) 5000)
            {:status :ok :document-id (:document-id inspected)
             :external-effect? true})

          (= :press kind)
          (do (reset! sent? true)
              (dispatch-key! conn (:key action))
              {:status :ok :document-id (:document-id inspected)
               :external-effect? (= "Enter" (:key action))})

          (= :scroll kind)
          (do
            (reset! sent? true)
            ;; Scroll through the document, not a synthetic wheel event.
            ;; A trusted wheel would be indistinguishable from an operator
            ;; gesture after the dispatch command returns.
            (evaluate-value
             conn
             (str "window.scrollBy(0, "
                  (long (or (:delta-y action) 600)) "); true")
             5000)
            {:status :ok :document-id (:document-id inspected)
             :external-effect? false})

          :else
          {:status :failed
           :error {:code :invalid-argument
                   :message "Unknown browser action kind."
                   :details {:kind kind}}})))))

(defn- page-info-safe
  [conn]
  (try (page-info conn) (catch Throwable _ nil)))

(defn- await-document-change
  "Wait briefly for an action to commit a new document.  A click may return
  before the navigation commits; the old document must never be reported as
  the action's result."
  [conn before timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [info (page-info-safe conn)
            changed? (and info before
                          (:document-id before)
                          (:document-id info)
                          (not= (:document-id before) (:document-id info)))]
        (cond
          changed? info
          (> (System/currentTimeMillis) deadline) info
          :else (do (Thread/sleep 25) (recur)))))))

(defn- act-page!
  [conn action]
  (if-let [frame-error (uncovered-frame-error conn)]
    {:status :failed :error frame-error}
    (let [sent? (atom false)
          navigated? (atom false)
          clear-failed? (atom false)
          context-id @(:operator-main-context conn)
          protected? (and (integer? context-id)
                          (ensure-operator-hook! conn)
                          (set-agent-input! conn true))]
      (if-not protected?
        {:status :failed
         :error (model/browser-error
                 :operator-input-unprotected
                 "Operator input detection is unavailable; no agent input was dispatched."
                 {})}
        (let [result
              (try
                (let [before (page-info-safe conn)
                      result (perform-action! conn action sent?)
                      waits? (or (= :click (:kind action))
                                 (= :submit (:kind action))
                                 (and (= :press (:kind action))
                                      (= "Enter" (:key action))))
                      after (if (and waits? (= :ok (:status result)))
                              (await-document-change conn before 400)
                              (page-info-safe conn))
                      nav? (and before after
                                (:document-id before)
                                (:document-id after)
                                (not= (:document-id before)
                                      (:document-id after)))
                      url-changed? (and before after
                                        (:url before)
                                        (:url after)
                                        (not= (:url before) (:url after)))
                      navigated-now? (boolean (or nav? url-changed?))]
                  (reset! navigated? navigated-now?)
                  (cond-> result
                    (= :ok (:status result))
                    (merge {:url (:url after)
                            :title (:title after)
                            :document-id (:document-id after)
                            :navigated? navigated-now?})))
                (catch Throwable t
                  (if (and @sent?
                           (contains? #{:click :type :press :submit}
                                      (:kind action)))
                    {:status :outcome-unknown
                     :error (model/browser-error
                             :outcome-unknown
                             "The input may have reached the page; verify before retrying."
                             {:cause (.getMessage t)})}
                    {:status :failed
                     :error (model/browser-error
                             :blocked-by-page
                             (.getMessage t)
                             {})}))
                (finally
                  ;; Clear immediately after the dispatch command returns.
                  ;; A navigation destroys the old world, so its fresh
                  ;; document has no suppression flag to clear.
                  (when-not (or @navigated?
                                (set-agent-input! conn false))
                    (reset! clear-failed? true))))]
          (if @clear-failed?
            (assoc result
                   :status (if @sent? :outcome-unknown :failed)
                   :error (model/browser-error
                           :operator-input-unprotected
                           "Operator input detection could not be cleared; the page is paused."
                           {:outcome (if @sent? :unknown :none)}))
            result))))))

;; ---------------------------------------------------------------------------
;; History

(defn- wait-load
  [conn timeout-ms]
  (let [message (wait-event! conn "Page.loadEventFired" timeout-ms)]
    (not= ::timeout message)))

(defn- history-page!
  [conn direction]
  (case direction
    :reload
    (do
      (let [load (event-promise! conn "Page.loadEventFired")]
        (request! conn "Page.reload" {"ignoreCache" false} default-timeout-ms)
        (deref load 10000 ::timeout)
        (remove-event! conn "Page.loadEventFired" load))
      (merge {:status :ok} (page-info conn)))

    (:back :forward)
    (let [history (request! conn "Page.getNavigationHistory" {} default-timeout-ms)
          entries (vec (get history "entries"))
          current (long (get history "currentIndex"))
          index (if (= :back direction) (dec current) (inc current))]
      (if (or (neg? index) (>= index (count entries)))
        {:status :failed
         :error {:code :history-boundary
                 :message "There is no further page in that direction."}}
        (let [load (event-promise! conn "Page.loadEventFired")
              _ (request! conn "Page.navigateToHistoryEntry"
                          {"entryId" (get (nth entries index) "id")}
                          default-timeout-ms)
              loaded? (not= ::timeout (deref load 10000 ::timeout))]
          (remove-event! conn "Page.loadEventFired" load)
          (if loaded?
            (merge {:status :ok} (page-info conn))
            (merge {:status :outcome-unknown
                    :error (model/browser-error
                            :outcome-unknown
                            "History navigation did not finish loading; read the page before retrying."
                            {:direction direction})}
                   (page-info conn))))))

    {:status :failed
     :error {:code :invalid-argument
             :message "Unknown history direction."
             :details {:direction direction}}}))

;; ---------------------------------------------------------------------------
;; Port implementation

(defn- install-operator-hook!
  "Install the operator-input hook in an isolated world and verify it.

  The page's main world never owns the suppression flag or the binding.
  Any setup failure is a hard failure: a page whose human input cannot be
  observed must not become agent-active."
  [conn]
  (let [tree (frame-tree conn)
        frame-id (get-in tree ["frame" "id"])]
    (when (str/blank? (str frame-id))
      (throw (ex-info "browser main frame id is unavailable"
                      {:code :operator-input-unprotected})))
    (reset! (:operator-main-frame conn) frame-id)
    (request! conn "Runtime.addBinding"
              {"name" operator-binding-name}
              default-timeout-ms)
    (request! conn "Page.addScriptToEvaluateOnNewDocument"
              {"source" operator-input-script
               "worldName" operator-world-name}
              default-timeout-ms)
    (let [context-id
          (get (request! conn "Page.createIsolatedWorld"
                         {"frameId" frame-id
                          "worldName" operator-world-name}
                         default-timeout-ms)
               "executionContextId")]
      (when-not (integer? context-id)
        (throw (ex-info "browser operator world is unavailable"
                        {:code :operator-input-unprotected})))
      (swap! (:operator-contexts conn) conj context-id)
      (reset! (:operator-main-context conn) context-id)
      ;; `Runtime.addBinding` without a context normally covers all inspected
      ;; contexts, including isolated worlds.  Keep an explicit per-context
      ;; fallback for implementations that only attach it to the default
      ;; world; a duplicate binding is harmless because readiness is verified
      ;; below.
      (try
        (request! conn "Runtime.addBinding"
                  {"name" operator-binding-name
                   "executionContextId" context-id}
                  default-timeout-ms)
        (catch Throwable _ nil))
      (evaluate-value-in-context conn operator-input-script context-id 5000)
      (when-not (operator-ready? conn context-id)
        (throw (ex-info "browser operator hook did not become ready"
                        {:code :operator-input-unprotected})))
      true)))

(defn- tab-entry
  [port tab-id]
  (get @(:tabs port) tab-id))

(defn- open-tab*
  [port _opts]
  (let [target (new-target (:client port) (:endpoint port))
        target-id (get target "id")
        ws-url (get target "webSocketDebuggerUrl")]
    (when (or (str/blank? (str target-id)) (str/blank? (str ws-url)))
      (throw (ex-info "browser target has no debugger URL"
                      {:code :cdp-target-invalid})))
    (let [conn (-> (connect-ws! (:client port) ws-url default-timeout-ms)
                   (assoc :operator-contexts (atom #{})
                          :operator-main-context (atom nil)
                          :operator-main-frame (atom nil)))
          tab-id (str "tab-" (UUID/randomUUID))]
      (try
        (request! conn "Page.enable" {} default-timeout-ms)
        (add-event-handler!
         conn "Runtime.executionContextCreated"
         (fn [message]
           (let [context (get-in message ["params" "context"])
                 context-id (get context "id")
                 frame-id (get-in context ["auxData" "frameId"])]
             (when (and (integer? context-id)
                        (= operator-world-name (get context "name")))
               (swap! (:operator-contexts conn) conj context-id)
               (when (and frame-id
                          (= frame-id @(:operator-main-frame conn)))
                 (reset! (:operator-main-context conn) context-id))))))
        (request! conn "Runtime.enable" {} default-timeout-ms)
        (try (request! conn "Network.enable" {} default-timeout-ms)
             (catch Throwable _ nil))
        (install-operator-hook! conn)
        (add-event-handler!
         conn "Runtime.bindingCalled"
         (fn [message]
           (let [context-id (get-in message ["params" "executionContextId"])
                 payload (get-in message ["params" "payload"])
                 kind (get operator-event-kinds payload)]
             (when (and (= operator-binding-name
                           (get-in message ["params" "name"]))
                        (contains? @(:operator-contexts conn) context-id)
                        kind)
               (when-let [handler @(:operator-handler port)]
                 (try
                   (handler {:tab-id tab-id :kind kind})
                   (catch Throwable _ nil)))))))
        (swap! (:tabs port) assoc tab-id
               {:target-id target-id :conn conn :url "about:blank"})
        {:status :ok
         :tab-id tab-id
         :target-id target-id
         :url "about:blank"
         :title ""
         :document-id nil}
        (catch Throwable t
          ;; The target may have been created before hook installation
          ;; failed.  Do not leave an unmanaged page behind.
          (try
            (http-get-text (:client port)
                           (str (:endpoint port) "/json/close/"
                                (url-encode target-id)))
            (catch Throwable _ nil))
          (close-ws! conn)
          (throw t))))))

(defn- close-tab*
  [port tab-id]
  (if-let [entry (tab-entry port tab-id)]
    (let [closed? (try
                    (let [response
                          (http-get-text
                           (:client port)
                           (str (:endpoint port) "/json/close/"
                                (url-encode (:target-id entry))))]
                      (if (<= 200 (:status response) 299)
                        true
                        {:error {:code :close-failed
                                 :message (str "Chrome close returned "
                                               (:status response))}}))
                    (catch Throwable t
                      {:error {:code :close-failed
                               :message (or (.getMessage t) (str t))}}))]
      (if (true? closed?)
        (do
          (close-ws! (:conn entry))
          (swap! (:tabs port) dissoc tab-id)
          {:status :ok})
        {:status :failed :error closed?}))
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defn- document-changed?
  [url before info]
  (and info
       (or (and before
                (:document-id before)
                (:document-id info)
                (not= (:document-id before) (:document-id info)))
           (and (not (str/blank? (str url)))
                (= url (:url info))))))

(defn- await-page
  "Wait until the tab commits a different document, not the page that was
  already visible when navigation started.  A same-URL reload changes the
  document id.  A timeout is reported honestly, never as success."
  [conn url before timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [info (try (page-info conn) (catch Throwable _ nil))
            changed? (document-changed? url before info)
            loading? (= "loading" (:ready-state info))]
        (cond
          (and changed? (not loading?))
          (assoc info :document-changed? true)

          (> (System/currentTimeMillis) deadline)
          (assoc (or info {:url url})
                 :document-changed? (boolean changed?)
                 :load-timeout? true)

          :else (do (Thread/sleep 50) (recur)))))))

(defn- navigate*
  [port tab-id url]
  (if-let [entry (tab-entry port tab-id)]
    (or (when-let [frame-error (uncovered-frame-error (:conn entry))]
          {:status :failed :error frame-error})
        (let [conn (:conn entry)
              before (page-info-safe conn)
              response (event-promise! conn "Network.responseReceived"
                                       (fn [message]
                                         (= "Document"
                                            (get-in message ["params" "type"]))))]
          (try
        (let [navigation (request! conn "Page.navigate" {"url" url}
                                   default-timeout-ms)]
          (if-let [error-text (get navigation "errorText")]
            (do
              (remove-event! conn "Network.responseReceived" response)
              {:status :failed
               :error (let [error (if (re-find #"ERR_CERT|ERR_SSL" error-text)
                                    {:code :certificate-error
                                     :message error-text}
                                    (if (re-find #"ERR_NAME_NOT_RESOLVED|ERR_CONNECTION|ERR_INTERNET_DISCONNECTED|ERR_ADDRESS_UNREACHABLE|ERR_TIMED_OUT|ERR_EMPTY_RESPONSE|ERR_NETWORK"
                                                 error-text)
                                      {:code :connection-failed
                                       :message error-text}
                                      {:code :navigation-failed
                                       :message error-text
                                       :details {:error-text error-text}}))]
                        error)})
            (let [info (await-page conn url before default-timeout-ms)
                  response-message (deref response 200 ::none)
                  http-status (get-in response-message ["params" "response" "status"])]
              (remove-event! conn "Network.responseReceived" response)
              (if (:load-timeout? info)
                {:status :outcome-unknown
                 :error (model/browser-error
                         :outcome-unknown
                         "Navigation did not commit a new document before the timeout; read the page before retrying."
                         {:url url :document-changed? (:document-changed? info)})
                 :url (:url info)
                 :title (:title info)
                 :document-id (:document-id info)}
                (merge {:status :ok :http-status http-status} info)))))
        (catch Throwable t
          (remove-event! conn "Network.responseReceived" response)
          (let [message (or (.getMessage t) "navigation failed")]
            (if (re-find #"ERR_CERT|ERR_SSL" message)
              {:status :failed
               :error {:code :certificate-error :message message}}
              (if (re-find #"ERR_NAME_NOT_RESOLVED|ERR_CONNECTION|ERR_INTERNET_DISCONNECTED|ERR_ADDRESS_UNREACHABLE|ERR_TIMED_OUT|ERR_EMPTY_RESPONSE|ERR_NETWORK"
                           message)
                {:status :failed
                 :error {:code :connection-failed :message message}}
                    {:status :outcome-unknown
                     :error {:code :navigation-failed
                             :message message
                             :outcome :unknown}})))))))
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defn- observe*
  [port tab-id _opts]
  (if-let [entry (tab-entry port tab-id)]
    (try
      (observe-page (:conn entry))
      (catch Throwable t
        {:status :failed
         :error {:code :unreadable-content
                 :message (.getMessage t)}}))
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defn- inspect*
  [port tab-id target]
  (if-let [entry (tab-entry port tab-id)]
    (inspect-target (:conn entry) target)
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defn- act*
  [port tab-id action]
  (if-let [entry (tab-entry port tab-id)]
    (act-page! (:conn entry) action)
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defn- screenshot*
  [port tab-id]
  (if-let [entry (tab-entry port tab-id)]
    (try
      (let [result (request! (:conn entry) "Page.captureScreenshot"
                             {"format" "png"} 10000)]
        {:status :ok
         :bytes (.decode (Base64/getDecoder) (get result "data"))
         :mime-type "image/png"})
      (catch Throwable t
        {:status :failed
         :error {:code :capture-failed
                 :message (.getMessage t)}}))
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defn- tab-status*
  [port tab-id]
  (if-let [entry (tab-entry port tab-id)]
    (try
      (let [all (targets (:client port) (:endpoint port))
            target (first (filter #(= (:target-id entry) (get % "id")) all))]
        (if target
          {:status :open
           :url (get target "url")
           :title (get target "title")}
          {:status :closed}))
      (catch Throwable _
        {:status :unknown}))
    {:status :closed}))

(defn- history*
  [port tab-id direction]
  (if-let [entry (tab-entry port tab-id)]
    (or (when-let [frame-error (uncovered-frame-error (:conn entry))]
          {:status :failed :error frame-error})
        (history-page! (:conn entry) direction))
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defn- delete-tree!
  [^File file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (or (.listFiles file) [])]
        (delete-tree! child)))
    (try (.delete file) (catch Throwable _ nil))))

(defn- close-port!
  [port]
  (when (compare-and-set! (:closed? port) false true)
    (doseq [[_ entry] @(:tabs port)]
      (close-ws! (:conn entry)))
    (reset! (:tabs port) {})
    (when (:owned? port)
      (when-let [process (:process port)]
        (try
          (.destroy process)
          (.waitFor process 2 TimeUnit/SECONDS)
          (when (.isAlive process) (.destroyForcibly process))
          (catch Throwable _ nil)))
      (when-not (:keep-profile? port)
        (when-let [dir (:profile-dir port)]
          (delete-tree! (File. (str dir)))))))
  {:status :ok})

(defn- page-info-port
  [port tab-id]
  (if-let [entry (tab-entry port tab-id)]
    (try
      (merge {:status :ok} (page-info* (:conn entry)))
      (catch Throwable t
        {:status :failed
         :error {:code :unreadable-content
                 :message (.getMessage t)}}))
    {:status :failed
     :error {:code :tab-missing
             :message "The page is not part of this browser session."}}))

(defrecord CdpPort [endpoint client tabs owned? process profile-dir
                    keep-profile? operator-handler closed?]
  port/BrowserPort
  (open-tab! [this opts] (open-tab* this opts))
  (close-tab! [this tab-id] (close-tab* this tab-id))
  (navigate! [this tab-id url] (navigate* this tab-id url))
  (history! [this tab-id direction] (history* this tab-id direction))
  (page-info! [this tab-id] (page-info-port this tab-id))
  (observe! [this tab-id opts] (observe* this tab-id opts))
  (inspect! [this tab-id target] (inspect* this tab-id target))
  (act! [this tab-id action] (act* this tab-id action))
  (screenshot! [this tab-id] (screenshot* this tab-id))
  (tab-status [this tab-id] (tab-status* this tab-id))
  (subscribe-operator-input! [this handler]
    (reset! operator-handler handler)
    {:ok? true
     :unsubscribe (fn [] (reset! operator-handler nil))})
  (close! [this] (close-port! this)))

(defn- find-chrome
  []
  (or (System/getenv "HC_CHROME")
      (some #(when (.exists (File. %)) %)
            ["/usr/bin/google-chrome"
             "/usr/bin/google-chrome-stable"
             "/usr/bin/chromium"
             "/usr/bin/chromium-browser"
             "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"])))

(defn- read-active-port
  [file process timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond
        (.exists file)
        (let [lines (str/split-lines (slurp file))]
          (if (>= (count lines) 2)
            {:port (Long/parseLong (str/trim (first lines)))
             :path (str/trim (second lines))}
            (recur)))

        (not (.isAlive process))
        (throw (ex-info "Chrome exited before the debugger was ready"
                        {:code :chrome-launch-failed
                         :log (read-log-tail
                               (File. (.getParentFile file) "chrome.log")
                               max-log-tail-bytes)}))

        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Chrome did not open the debugger port in time"
                        {:code :chrome-launch-timeout}))

        :else
        (do (Thread/sleep 100) (recur))))))

(defn launch!
  "Launch an isolated headless Chrome.  The adapter owns this process and
  stops it in `close!`.  This is the test/smoke path, not the default way to
  attach to the operator's browser."
  [{:keys [chrome timeout-ms keep-profile?]
    :or {timeout-ms 20000}}]
  (let [chrome (or chrome (find-chrome))]
    (when-not chrome
      (throw (ex-info "no Chrome executable found; set HC_CHROME"
                      {:code :chrome-not-found})))
    (let [profile-dir (Files/createTempDirectory
                       "hc-chrome-" (make-array FileAttribute 0))
          log-file (File. (str profile-dir) "chrome.log")
          args [chrome
                "--headless=new"
                "--remote-debugging-address=127.0.0.1"
                "--remote-debugging-port=0"
                (str "--user-data-dir=" profile-dir)
                "--remote-allow-origins=*"
                "--no-first-run"
                "--no-default-browser-check"
                "--disable-background-networking"
                "--disable-component-update"
                "--disable-sync"
                "--disable-extensions"
                "--disable-gpu"
                "--hide-scrollbars"
                "--mute-audio"
                "about:blank"]
          process (try
                    (.start (doto (ProcessBuilder. (into-array String args))
                              (.redirectErrorStream true)
                              (.redirectOutput log-file)))
                    (catch Throwable t
                      (delete-tree! (File. (str profile-dir)))
                      (throw t)))]
      (try
        (let [{:keys [port]} (read-active-port
                              (File. (str profile-dir) "DevToolsActivePort")
                              process timeout-ms)
              endpoint (str "http://127.0.0.1:" port)
              client (http-client timeout-ms)]
          (http-json client :get (str endpoint "/json/version"))
          (->CdpPort endpoint client (atom {}) true process profile-dir
                     (boolean keep-profile?) (atom nil) (atom false)))
        (catch Throwable t
          (try
            (.destroy process)
            (catch Throwable _ nil))
          (delete-tree! (File. (str profile-dir)))
          (throw t))))))

(defn connect!
  "Connect to an already-running Chrome DevTools endpoint.  The operator or
  another process owns that browser; `close!` only releases this adapter's
  connections."
  [{:keys [endpoint timeout-ms]
    :or {timeout-ms 10000}}]
  (let [endpoint (str/replace (str endpoint) #"/+$" "")
        client (http-client timeout-ms)]
    (try
      (http-json client :get (str endpoint "/json/version"))
      (catch Throwable t
        (throw (ex-info "could not connect to Chrome DevTools"
                        {:code :chrome-connect-failed
                         :endpoint endpoint
                         :message (.getMessage t)}
                        t))))
    (->CdpPort endpoint client (atom {}) false nil nil true
               (atom nil) (atom false))))
