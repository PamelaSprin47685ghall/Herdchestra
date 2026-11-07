(ns hc.client.sandbox.model
  "Sandboxed HTML document construction and message validation.

  A sandboxed document is built from three independent parts:

  1. a Content-Security-Policy that blocks network access unless the user
     explicitly allows named origins;
  2. an injected bridge that exposes storage, workspace and external-action
     requests only through token-gated `postMessage` calls;
  3. the model-generated or workspace HTML itself.

  The bridge never receives a secret, never changes the parent session's
  permissions, and never opens a URL, submits a form or downloads a file by
  itself.  The parent decides each external action after showing its
  destination to the user.

  This namespace is pure CLJC so the JVM tests can inspect the exact policy,
  injected document and message gate without a browser."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Field access

(defn field
  "Read a plain key or its string form from a server/CLJS map."
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (if (keyword? k) (subs (str k) 1) (keyword k))))))

(defn now-string
  "Wall-clock timestamp for display and persistence.  The value is not a
  server stamp and is never used as a consistency check."
  []
  #?(:clj (str (java.time.Instant/now))
     :cljs (.toISOString (js/Date.))))

;; ---------------------------------------------------------------------------
;; URL and origin inspection

(defn- parse-url
  [value]
  (let [value (str value)]
    #?(:clj
       (try
         (let [uri (java.net.URI. value)
               scheme (some-> (.getScheme uri) str/lower-case)
               host (some-> (.getHost uri) str/lower-case)
               port (let [p (.getPort uri)] (when (pos? p) p))]
           (when (and scheme host)
             {:scheme scheme :host host :port port}))
         (catch Exception _ nil))
       :cljs
       (try
         (let [u (js/URL. value)
               scheme (-> (.-protocol u) (str/replace #":$" "") str/lower-case)
               host (some-> (.-hostname u) str/lower-case)
               port (let [p (.-port u)]
                      (when (and (string? p) (not (str/blank? p)))
                        (js/parseInt p 10)))]
           (when (and (seq scheme) (seq host))
             {:scheme scheme :host host :port port}))
         (catch :default _ nil)))))

(defn- default-port?
  [{:keys [scheme port]}]
  (or (and (= scheme "http") (= port 80))
      (and (= scheme "https") (= port 443))))

(defn origin-string
  "Normalize a parsed URL or origin string to `scheme://host[:port]`."
  [value]
  (when-let [{:keys [scheme host port] :as parsed}
             (if (map? value) value (parse-url value))]
    (when (and (seq scheme) (seq host))
      (str scheme "://" host
           (when (and port (not (default-port? parsed)))
             (str ":" port))))))

(defn normalize-origin
  [value]
  (origin-string value))

(defn http-url?
  [value]
  (let [parsed (parse-url value)]
    (boolean (and parsed (contains? #{"http" "https"} (:scheme parsed))))))

(defn external-origins
  "Every absolute HTTP(S) origin mentioned in HTML.

  The scan is deliberately conservative: it finds attribute values and
  ordinary text URLs, not just `<script src>`.  A false positive only adds a
  domain the user may allow; a missed domain stays blocked by the CSP.  It
  never turns a relative path into a remote origin."
  [html]
  (let [matches (re-seq #"(?i)(?:https?:)?//[^\s\"'<>()`]+" (str (or html "")))
        urls (map (fn [value]
                    (if (str/starts-with? value "//")
                      (str "https:" value)
                      value))
                  matches)
        origins (keep normalize-origin urls)]
    (vec (sort (distinct origins)))))

(defn relative-resource-count
  "Count resource-looking relative URLs.  They cannot load inside `srcdoc`;
  the UI uses this only to explain a limitation."
  [html]
  (count (re-seq #"(?i)(?:src|href|poster)\s*=\s*[\"'](?!https?:|//|data:|blob:|#|mailto:|tel:)[^\"']+"
                 (str (or html "")))))

;; ---------------------------------------------------------------------------
;; Content-Security-Policy

(defn content-security-policy
  "CSP for a sandboxed document.

  Network sources are blocked by default.  `allowed-origins` is the complete
  user-granted set; there is no implicit self, wildcard or scheme fallback."
  [allowed-origins]
  (let [origins (->> allowed-origins
                     (keep #(normalize-origin %))
                     distinct
                     sort
                     vec)
        suffix (when (seq origins) (str " " (str/join " " origins)))]
    (str "default-src 'none'; "
         "script-src 'unsafe-inline'" suffix "; "
         "style-src 'unsafe-inline'" suffix "; "
         "img-src data: blob:" suffix "; "
         "font-src data:" suffix "; "
         "media-src data: blob:" suffix "; "
         "connect-src " (if (seq origins)
                          (str/join " " origins)
                          "'none'") "; "
         "frame-src 'none'; "
         "child-src 'none'; "
         "object-src 'none'; "
         "base-uri 'none'; "
         "form-action 'none'; "
         "worker-src 'none'; "
         "manifest-src 'none'")))

;; ---------------------------------------------------------------------------
;; JSON embedding

(defn- json-escape
  [value]
  (-> (str value)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")
      (str/replace "\u2028" "\\u2028")
      (str/replace "\u2029" "\\u2029")))

(defn json
  "Minimal JSON encoder for the bridge configuration.  It supports only the
  shapes the bridge needs; unknown values become strings."
  [value]
  (cond
    (nil? value) "null"
    (string? value) (str "\"" (json-escape value) "\"")
    (keyword? value) (json (name value))
    (number? value) (str value)
    (boolean? value) (if value "true" "false")
    (map? value) (str "{"
                      (str/join ","
                                (map (fn [[k v]]
                                       (str (json (if (keyword? k) (name k) (str k)))
                                            ":" (json v)))
                                     value))
                      "}")
    (sequential? value) (str "[" (str/join "," (map json value)) "]")
    :else (json (str value))))

(defn- json-embed
  "JSON safe to place inside a `<script>` element.  `<` is escaped so a
  string containing `</script>` cannot close the element."
  [value]
  (-> (json value)
      (str/replace "<" "\\u003c")))

;; ---------------------------------------------------------------------------
;; Bridge

(def ^:private bridge-source
  "(function(){
  var TOKEN = __TOKEN__;
  var MODE = __MODE__;
  var CONFIG = __CONFIG__;
  var pending = Object.create(null);
  var seq = 0;
  var listeners = Object.create(null);
  var memory = Object.create(null);
  var initial = CONFIG.storage || {};
  Object.keys(initial).forEach(function(k){ memory[k] = String(initial[k]); });

  function post(type, payload) {
    try {
      parent.postMessage({token: TOKEN, type: type, payload: payload || {}}, '*');
    } catch (e) {}
  }

  function request(type, payload) {
    return new Promise(function(resolve, reject) {
      var id = 'r' + (++seq);
      pending[id] = {resolve: resolve, reject: reject};
      post(type, Object.assign({requestId: id}, payload || {}));
      setTimeout(function() {
        if (pending[id]) {
          delete pending[id];
          reject(new Error('sandbox request timed out'));
        }
      }, 30000);
    });
  }

  function emit(name, payload) {
    var list = listeners[name] || [];
    list.forEach(function(fn) { try { fn(payload); } catch (e) {} });
  }

  window.addEventListener('message', function(event) {
    var data = event.data;
    if (!data || data.token !== TOKEN) return;
    if (data.type === 'hc/reply') {
      var entry = pending[data.requestId];
      if (!entry) return;
      delete pending[data.requestId];
      if (data.ok) entry.resolve(data.value);
      else entry.reject(new Error(data.error || 'sandbox request failed'));
    } else if (data.type === 'hc/event') {
      emit(data.name, data.payload);
    }
  });

  function resolveUrl(value) {
    try {
      return new URL(String(value || ''), CONFIG.baseUrl || 'https://sandbox.invalid/').href;
    } catch (e) {
      return String(value || '');
    }
  }

  function persistWrite(type, payload, key) {
    request(type, payload).catch(function(error) {
      emit('storage-error', {key: key || '', message: error && error.message ? error.message : 'storage write failed'});
    });
  }

  function makeStorage(shouldPersist) {
    var api = {
      getItem: function(key) {
        key = String(key);
        return Object.prototype.hasOwnProperty.call(memory, key) ? memory[key] : null;
      },
      setItem: function(key, value) {
        key = String(key);
        value = String(value);
        memory[key] = value;
        if (shouldPersist) persistWrite('hc/storage-set', {key: key, value: value}, key);
      },
      removeItem: function(key) {
        key = String(key);
        delete memory[key];
        if (shouldPersist) persistWrite('hc/storage-remove', {key: key}, key);
      },
      clear: function() {
        Object.keys(memory).forEach(function(key) { delete memory[key]; });
        if (shouldPersist) persistWrite('hc/storage-clear', {}, '');
      },
      key: function(index) {
        var keys = Object.keys(memory);
        return index >= 0 && index < keys.length ? keys[index] : null;
      }
    };
    Object.defineProperty(api, 'length', {
      get: function() { return Object.keys(memory).length; }
    });
    return api;
  }

  try {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: makeStorage(MODE === 'app')
    });
  } catch (e) {}
  try {
    Object.defineProperty(window, 'sessionStorage', {
      configurable: true,
      value: makeStorage(false)
    });
  } catch (e) {}

  var Herdchestra = {
    version: 1,
    mode: MODE,
    info: CONFIG.app || {},
    dataVersion: CONFIG.dataVersion || 1,
    storageOmitted: !!CONFIG.storageOmitted,
    storageTooLarge: !!CONFIG.storageTooLarge,
    network: {
      online: !!CONFIG.online,
      allowedOrigins: CONFIG.allowedOrigins || []
    },
    storage: {
      get: function(key) {
        if (MODE !== 'app') {
          return Promise.resolve(Object.prototype.hasOwnProperty.call(memory, String(key))
            ? memory[String(key)] : null);
        }
        return request('hc/storage-get', {key: String(key)});
      },
      set: function(key, value) {
        key = String(key);
        value = String(value);
        memory[key] = value;
        if (MODE !== 'app') return Promise.resolve(true);
        return request('hc/storage-set', {key: key, value: value});
      },
      remove: function(key) {
        key = String(key);
        delete memory[key];
        if (MODE !== 'app') return Promise.resolve(true);
        return request('hc/storage-remove', {key: key});
      },
      keys: function() {
        if (MODE !== 'app') return Promise.resolve(Object.keys(memory));
        return request('hc/storage-keys', {});
      },
      clear: function() {
        Object.keys(memory).forEach(function(key) { delete memory[key]; });
        if (MODE !== 'app') return Promise.resolve(true);
        return request('hc/storage-clear', {});
      }
    },
    workspace: {
      request: function(path, kind, mode) {
        if (MODE !== 'app') return Promise.reject(new Error('workspace access is only available to workspace apps'));
        return request('hc/workspace-request', {
          path: String(path || ''),
          kind: kind || 'file',
          mode: mode || 'once'
        });
      },
      read: function(path) {
        if (MODE !== 'app') return Promise.reject(new Error('workspace access is only available to workspace apps'));
        return request('hc/workspace-read', {path: String(path || '')});
      },
      list: function(path) {
        if (MODE !== 'app') return Promise.reject(new Error('workspace access is only available to workspace apps'));
        return request('hc/workspace-list', {path: String(path || '')});
      }
    },
    external: {
      open: function(url) {
        post('hc/external-link', {url: resolveUrl(url)});
      },
      download: function(filename, data) {
        post('hc/download', {filename: String(filename || ''), data: String(data || '')});
      },
      submit: function(payload) {
        post('hc/external-submit', payload || {});
      }
    },
    on: function(name, fn) {
      if (typeof fn !== 'function') return;
      (listeners[name] = listeners[name] || []).push(fn);
    }
  };

  window.Herdchestra = Herdchestra;
  window.herdchestra = Herdchestra;

  function ready() {
    post('hc/ready', {title: document.title || ''});
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ready);
  } else {
    ready();
  }

  var lastInput = 0;
  function inputSignal() {
    var now = Date.now();
    if (now - lastInput < 200) return;
    lastInput = now;
    post('hc/input', {});
  }
  ['input', 'change', 'keydown'].forEach(function(type) {
    document.addEventListener(type, inputSignal, true);
  });

  document.addEventListener('click', function(event) {
    var target = event.target;
    var anchor = target && target.closest ? target.closest('a[href]') : null;
    if (!anchor) return;
    var href = anchor.getAttribute('href') || '';
    if (!href || href.charAt(0) === '#') return;
    event.preventDefault();
    if (anchor.hasAttribute('download')) {
      post('hc/download', {
        url: resolveUrl(href),
        filename: anchor.getAttribute('download') || ''
      });
    } else {
      post('hc/external-link', {url: resolveUrl(href)});
    }
  }, true);

  document.addEventListener('submit', function(event) {
    event.preventDefault();
    var form = event.target;
    var fields = [];
    var sensitive = Object.create(null);
    try {
      Array.prototype.forEach.call(form.elements || [], function(element) {
        if (!element || !element.name) return;
        var type = String(element.type || '').toLowerCase();
        var autocomplete = String(element.autocomplete || '').toLowerCase();
        if (type === 'password' || autocomplete.indexOf('cc-') === 0 ||
            autocomplete === 'current-password' || autocomplete === 'new-password' ||
            autocomplete === 'one-time-code') {
          sensitive[element.name] = true;
        }
      });
      new FormData(form).forEach(function(value, key) {
        fields.push({
          name: String(key),
          valueLength: typeof value === 'string' ? value.length : 0,
          sensitive: !!sensitive[key]
        });
      });
    } catch (e) {}
    post('hc/external-submit', {
      url: resolveUrl(form.getAttribute('action') || location.href),
      method: String(form.getAttribute('method') || 'GET').toUpperCase(),
      fields: fields
    });
  }, true);

  var realOpen = window.open;
  window.open = function(url) {
    post('hc/external-link', {url: resolveUrl(url || '')});
    return null;
  };

  window.addEventListener('error', function(event) {
    post('hc/runtime-error', {
      message: String(event.message || 'Script error'),
      source: String(event.filename || ''),
      line: event.lineno || 0
    });
  });
  window.addEventListener('unhandledrejection', function(event) {
    var reason = event.reason;
    var message = reason && reason.message ? reason.message : String(reason || 'Unhandled rejection');
    post('hc/runtime-error', {message: message, source: '', line: 0});
  });

  if (window.fetch) {
    var realFetch = window.fetch;
    window.fetch = function(input) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      post('hc/network-request', {url: resolveUrl(url)});
      return realFetch.apply(this, arguments);
    };
  }

  if (window.XMLHttpRequest) {
    var realXhrOpen = window.XMLHttpRequest.prototype.open;
    window.XMLHttpRequest.prototype.open = function(method, url) {
      post('hc/network-request', {url: resolveUrl(url)});
      return realXhrOpen.apply(this, arguments);
    };
  }

  if (window.WebSocket) {
    var RealWebSocket = window.WebSocket;
    window.WebSocket = function(url) {
      post('hc/network-request', {url: resolveUrl(url)});
      return new RealWebSocket(url);
    };
    window.WebSocket.prototype = RealWebSocket.prototype;
  }
})();")

(defn bridge-js
  "JavaScript bridge for one sandboxed document."
  [token mode config]
  (-> bridge-source
      (str/replace #"__TOKEN__" (fn [_] (json-embed token)))
      (str/replace #"__MODE__" (fn [_] (json-embed (name mode))))
      (str/replace #"__CONFIG__" (fn [_] (json-embed config)))))

(defn- attr-escape
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- injection
  [token mode config allowed-origins]
  (str "<meta charset=\"utf-8\">"
       "<meta http-equiv=\"Content-Security-Policy\" content=\""
       (attr-escape (content-security-policy allowed-origins))
       "\">"
       "<meta name=\"referrer\" content=\"no-referrer\">"
       "<meta name=\"color-scheme\" content=\"light dark\">"
       "<script data-hc-sandbox-token=\"" (attr-escape (str token)) "\">"
       (bridge-js token mode config)
       "</script>"))

(defn- blank-out
  [text]
  (apply str (repeat (count text) " ")))

(defn- mask-html-regions
  "Mask comments, script bodies and style bodies without changing length.

  Tag search must not be fooled by `<head>` inside a comment or a string.
  Offsets stay valid because every masked region is replaced by spaces of the
  same length."
  [html]
  (-> (str html)
      (str/replace #"(?is)<!--.*?-->" blank-out)
      (str/replace #"(?is)<script\b[^>]*>.*?</script>" blank-out)
      (str/replace #"(?is)<style\b[^>]*>.*?</style>" blank-out)
      ;; Attribute/text strings can also contain a fake `<head>`; mask
      ;; quoted runs so only real tag text remains.
      (str/replace #"\"[^\"]*\"" blank-out)
      (str/replace #"'[^']*'" blank-out)))

(defn- insert-at
  [text index insertion]
  (str (subs text 0 index) insertion (subs text index)))

(defn- find-tag-index
  [masked pattern]
  (when-let [tag (re-find pattern masked)]
    (str/index-of masked tag)))

(defn document-html
  "Inject the CSP and bridge into HTML and return a `srcdoc` value.

  The function is idempotent only for the exact per-instance token marker it
  injected itself; a page cannot opt out by writing a guessed marker.  Tag
  search masks comments, script bodies and style bodies, so a fake `<head>`
  in content cannot move the policy out of the real head.  A fragment without
  `<html>` is wrapped in a minimal document so the policy and bridge run
  before application scripts."
  [{:keys [html token mode config allowed-origins]}]
  (let [html (str (or html ""))
        marker (str "data-hc-sandbox-token=\"" (attr-escape (str token)) "\"")]
    (if (str/includes? html marker)
      html
      (let [html (str/replace html #"(?is)^\s*<!doctype[^>]*>" "")
            masked (mask-html-regions html)
            injection (injection token (or mode :preview)
                                 (or config {}) allowed-origins)
            head-index (find-tag-index masked #"(?is)<head[^>]*>")
            html-index (find-tag-index masked #"(?is)<html[^>]*>")]
        (cond
          head-index
          (let [head (re-find #"(?is)<head[^>]*>" masked)]
            (str "<!DOCTYPE html>"
                 (insert-at html (+ head-index (count head)) injection)))

          html-index
          (let [tag (re-find #"(?is)<html[^>]*>" masked)]
            (str "<!DOCTYPE html>"
                 (insert-at html (+ html-index (count tag))
                            (str "<head>" injection "</head>"))))

          :else
          (str "<!DOCTYPE html><html><head>" injection
               "</head><body>" html "</body></html>"))))))

;; ---------------------------------------------------------------------------
;; Message gate

(def ^:private request-types
  #{"hc/storage-get" "hc/storage-set" "hc/storage-remove" "hc/storage-keys"
    "hc/storage-clear" "hc/workspace-request" "hc/workspace-read"
    "hc/workspace-list"})

(def ^:private event-types
  #{"hc/ready" "hc/input" "hc/runtime-error" "hc/external-link" "hc/download"
    "hc/external-submit" "hc/network-request"})

(defn message-type
  [data]
  (when (map? data)
    (or (field data :type) (field data :event))))

(defn message-token
  [data]
  (when (map? data)
    (field data :token)))

(defn valid-message?
  "True only for a message from the expected frame carrying the per-instance
  token.  `event-source` and `expected-source` are WindowProxy objects; the
  caller compares them before calling this function."
  [token data]
  (let [type (message-type data)]
    (and (string? token)
         (= token (message-token data))
         (string? type)
         (or (contains? request-types type)
             (contains? event-types type)
             (= "hc/reply" type)))))

(defn request-message?
  [data]
  (contains? request-types (message-type data)))

(defn event-message?
  [data]
  (contains? event-types (message-type data)))

(defn message-kind
  [data]
  (case (message-type data)
    "hc/ready" :ready
    "hc/input" :input
    "hc/runtime-error" :runtime-error
    "hc/external-link" :external-link
    "hc/download" :download
    "hc/external-submit" :external-submit
    "hc/network-request" :network-request
    "hc/storage-get" :storage-get
    "hc/storage-set" :storage-set
    "hc/storage-remove" :storage-remove
    "hc/storage-keys" :storage-keys
    "hc/storage-clear" :storage-clear
    "hc/workspace-request" :workspace-request
    "hc/workspace-read" :workspace-read
    "hc/workspace-list" :workspace-list
    nil))

(defn message-payload
  [data]
  (when (map? data)
    (or (field data :payload) {})))
