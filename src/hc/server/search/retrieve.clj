(ns hc.server.search.retrieve
  "On-demand full-text retrieval for a URL that a search result returned.

  Three boundaries matter:

  * A URL is fetched only when it is already a source in the reference set.
    The model cannot turn arbitrary prose into a network request.
  * No search credential is sent to the target site.  Retrieval uses an
    anonymous GET with no authorization header.
  * Redirects are not followed and private network addresses are refused by
    default, so a search result cannot steer the server into its own network.

  A blocked or partial read updates the source status but never removes the
  title or snippet that the search result already provided."
  (:require [clojure.string :as str]
            [hc.server.materials.search :as refs]
            [hc.server.search.errors :as errors]
            [hc.server.search.http :as http])
  (:import (java.net InetAddress URI)
           (java.nio.charset Charset StandardCharsets)))

(def default-max-bytes (* 2 1024 1024))
(def hard-max-bytes (* 8 1024 1024))
(def default-max-chars 200000)
(def hard-max-chars 1000000)
(def default-timeout-ms 10000)

(def ^:private supported-content-types
  #{"text/html" "application/xhtml+xml" "text/plain" "text/markdown"
    "application/json" "application/ld+json" "application/xml" "text/xml"
    "text/csv" "text/tab-separated-values"})

(def ^:private named-entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " "
   "hellip" "…" "mdash" "—" "ndash" "–" "copy" "©" "reg" "®"
   "laquo" "«" "raquo" "»" "ldquo" "“" "rdquo" "”" "lsquo" "‘"
   "rsquo" "’" "middot" "·" "bull" "•"})

(defn- decode-entity
  [match]
  (let [body (subs match 1 (dec (count match)))]
    (cond
      (str/starts-with? body "#x")
      (try
        (let [code (Integer/parseInt (subs body 2) 16)]
          (if (Character/isValidCodePoint code)
            (String. (Character/toChars code))
            match))
        (catch Exception _ match))

      (str/starts-with? body "#")
      (try
        (let [code (Integer/parseInt (subs body 1))]
          (if (Character/isValidCodePoint code)
            (String. (Character/toChars code))
            match))
        (catch Exception _ match))

      :else
      (get named-entities (str/lower-case body) match))))

(defn decode-entities
  [text]
  (str/replace text #"&(?:#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z][A-Za-z0-9]+);"
               decode-entity))

(defn- html-title
  [html]
  (or (some-> (re-find #"(?is)<title[^>]*>(.*?)</title>" html)
              second
              (str/replace #"(?s)<[^>]+>" " ")
              decode-entities
              str/trim
              errors/trim-to-nil)
      (some-> (re-find #"(?is)<meta[^>]+property\s*=\s*[\"']og:title[\"'][^>]*content\s*=\s*[\"']([^\"']*)[\"']" html)
              second
              decode-entities
              str/trim
              errors/trim-to-nil)))

(defn html-text
  "A deliberately small HTML-to-text extractor.

  It removes non-content elements, inserts line breaks at block boundaries,
  decodes common entities and collapses whitespace.  It does not execute
  scripts, follow frames or claim to reproduce a dynamic page."
  [html]
  (-> html
      (str/replace #"(?is)<!--.*?-->" " ")
      (str/replace #"(?is)<head\b[^>]*>.*?</head>" " ")
      (str/replace #"(?is)<script\b[^>]*>.*?</script>" " ")
      (str/replace #"(?is)<style\b[^>]*>.*?</style>" " ")
      (str/replace #"(?is)<noscript\b[^>]*>.*?</noscript>" " ")
      (str/replace #"(?is)<svg\b[^>]*>.*?</svg>" " ")
      (str/replace #"(?is)<iframe\b[^>]*>.*?</iframe>" " ")
      (str/replace #"(?is)<br\s*/?>" "\n")
      (str/replace #"(?is)</(?:p|div|li|h[1-6]|tr|section|article|header|footer|blockquote|pre|table|ul|ol|dl|dt|dd)>" "\n")
      (str/replace #"(?is)<[^>]+>" " ")
      decode-entities
      (str/replace #"[ \t\f\v\r]+" " ")
      (str/replace #" *\n *" "\n")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- parse-uri
  [url]
  (try
    (URI. url)
    (catch Exception _ nil)))

(defn- private-ipv4?
  [^bytes address]
  (let [b (fn [i] (bit-and 0xff (aget address i)))]
    (or (= 0 (b 0))
        (= 10 (b 0))
        (and (= 100 (b 0)) (<= 64 (b 1) 127))
        (= 127 (b 0))
        (and (= 169 (b 0)) (= 254 (b 1)))
        (and (= 172 (b 0)) (<= 16 (b 1) 31))
        (and (= 192 (b 0)) (= 168 (b 1)))
        (and (= 192 (b 0)) (= 0 (b 1)) (= 0 (b 2)))
        (and (= 198 (b 0)) (<= 18 (b 1) 19))
        (and (= 198 (b 0)) (= 51 (b 1)) (= 100 (b 2)))
        (and (= 203 (b 0)) (= 0 (b 1)) (= 113 (b 2)))
        (>= (b 0) 224))))

(defn- private-ipv6?
  [^bytes address]
  (let [b (fn [i] (bit-and 0xff (aget address i)))]
    (or (and (= 0 (b 0)) (= 0 (b 1)) (= 0 (b 2)) (= 0 (b 3))
             (= 0 (b 4)) (= 0 (b 5)) (= 0 (b 6)) (= 1 (b 7)))
        (and (= 0 (b 0)) (= 0 (b 1)) (= 0 (b 2)) (= 0 (b 3))
             (= 0 (b 4)) (= 0 (b 5)) (= 0 (b 6)) (= 0 (b 7)))
        (= 0xfc (bit-and (b 0) 0xfe))
        (and (= 0xfe (b 0)) (= 0x80 (bit-and (b 1) 0xc0)))
        (= 0xff (b 0)))))

(defn private-address?
  [^InetAddress address]
  (let [bytes (.getAddress address)]
    (or (.isAnyLocalAddress address)
        (.isLoopbackAddress address)
        (.isLinkLocalAddress address)
        (.isSiteLocalAddress address)
        (.isMulticastAddress address)
        (case (alength bytes)
          4 (private-ipv4? bytes)
          16 (private-ipv6? bytes)
          false))))

(defn private-host?
  "True when any resolved address is private, loopback or link-local.

  An unresolved host is not called private; the HTTP request reports the
  reachability failure instead."
  [host]
  (try
    (boolean (some private-address? (InetAddress/getAllByName host)))
    (catch Exception _ false)))

(defn- response-charset
  [headers]
  (let [header (some-> (get headers "content-type") first)
        charset-name (when header
                       (some-> (re-find #"(?i)charset\s*=\s*\"?([^\";]+)\"?"
                                        header)
                               second
                               str/trim))]
    (if (str/blank? charset-name)
      StandardCharsets/UTF_8
      (try
        (Charset/forName charset-name)
        (catch Exception _ StandardCharsets/UTF_8)))))

(defn- content-kind
  [content-type body]
  (let [content-type (some-> content-type str/lower-case)]
    (cond
      (nil? content-type)
      (if (str/starts-with? (str/trim (String. ^bytes body
                                               StandardCharsets/UTF_8))
                            "<")
        :html
        :text)

      (contains? supported-content-types content-type)
      (if (or (= content-type "text/html")
              (= content-type "application/xhtml+xml"))
        :html
        :text)

      (str/starts-with? content-type "text/")
      :text

      :else
      :unsupported)))

(defn- read-at
  [request clock]
  (or (:read-at request)
      (let [value (clock)]
        (if (string? value) value (str value)))))

(defn- blocked
  [refs canonical reason read-at]
  (let [updated (refs/record-read refs canonical
                                  {:status :blocked :read-at read-at})]
    (if (:ok? updated)
      {:ok? true
       :status :blocked
       :reason reason
       :url canonical
       :read-at read-at
       :refs (:refs updated)
       :source (:source updated)
       :documents nil
       :notice (case reason
                 :access-denied "the page refused access; saved title and snippet are kept"
                 :not-found "the page no longer exists; saved title and snippet are kept"
                 :rate-limited "the page rate-limited this read; saved title and snippet are kept"
                 "the page could not be read; saved title and snippet are kept")}
      updated)))

(defn- failure
  [error]
  {:ok? false :error error})

(defn- with-url
  [result canonical]
  (if (and (map? result) (map? (:error result)))
    (update-in result [:error :details] assoc :url canonical)
    result))

(defn fetch!
  "Fetch one source that a search result returned.

  REQUEST:

    {:url \"https://example.com/a\"
     :refs reference-set
     :documents {canonical-url {...}}   ; optional prior documents
     :read-at \"...\"                   ; optional, else the service clock
     :refetch? false                    ; optional, skip the already-read check
     :max-bytes 2097152                 ; optional
     :max-chars 200000                  ; optional
     :cancelled? atom}                  ; optional

  SERVICE carries `:client`, `:clock` and `:allow-private-hosts?`.

  Returns `{:ok? true :status :full|:partial|:blocked ...}` or a refusal.
  A refusal leaves REFS unchanged."
  [service request]
  (let [url (:url request)
        refs (:refs request)
        documents (or (:documents request) {})
        canonical (refs/canonical-url url)]
    (cond
      (nil? canonical)
      (failure (errors/error :SEARCH_URL_INVALID
                             "a fetch needs a usable http(s) URL"
                             {:url url}))

      (not (map? refs))
      (failure (errors/error :SEARCH_SET_INVALID
                             "reference set must be a map"
                             {:url canonical}))

      (nil? (get-in refs [:sources canonical]))
      (failure (errors/error :SEARCH_SOURCE_UNKNOWN
                             "cannot fetch a URL that no search result returned"
                             {:url canonical}))

      :else
      (let [existing (get documents canonical)
            already-full? (and existing
                              (= :full (:status existing))
                              (not (:truncated? existing))
                              (errors/non-blank-string? (:text existing)))
            refetch? (true? (:refetch? request))]
        (if (and already-full? (not refetch?))
          {:ok? true
           :status :full
           :already-read? true
           :url canonical
           :document existing
           :documents documents
           :refs refs}
          (let [uri (parse-uri canonical)
                host (some-> uri .getHost)
                allow-private? (true? (:allow-private-hosts? service))
                private? (and (not allow-private?) (private-host? host))
                max-bytes (min hard-max-bytes
                               (max 1 (long (or (:max-bytes request)
                                                default-max-bytes))))
                max-chars (min hard-max-chars
                               (max 1 (long (or (:max-chars request)
                                                default-max-chars))))
                read-at (read-at request (:clock service))
                response (when-not private?
                           (http/send!
                            {:client (:client service)
                             :method "GET"
                             :uri uri
                             :headers {"accept"
                                       (str "text/html,application/xhtml+xml,"
                                            "text/plain,application/json;q=0.8,"
                                            "*/*;q=0.1")
                                       "user-agent" "Herdchestra/0.1 reader"}
                             :timeout-ms (or (:timeout-ms request)
                                             default-timeout-ms)
                             :max-bytes max-bytes
                             :allow-truncated? true
                             :cancelled? (:cancelled? request)}))]
            (cond
              private?
              (failure (errors/error :SEARCH_FETCH_PRIVATE_HOST
                                     "the page resolves to a private network address"
                                     {:url canonical}))

              (not (:ok? response))
              (with-url response canonical)

              (= :cancelled (:code (:error response)))
              (with-url response canonical)

              (<= 300 (:status response) 399)
              (failure (errors/error :SEARCH_FETCH_REDIRECT_REFUSED
                                     "the page redirected; redirects are not followed"
                                     {:url canonical
                                      :http-status (:status response)}))

              (= 401 (:status response))
              (blocked refs canonical :access-denied read-at)

              (= 403 (:status response))
              (blocked refs canonical :access-denied read-at)

              (= 407 (:status response))
              (blocked refs canonical :access-denied read-at)

              (= 404 (:status response))
              (blocked refs canonical :not-found read-at)

              (= 410 (:status response))
              (blocked refs canonical :not-found read-at)

              (= 429 (:status response))
              (blocked refs canonical :rate-limited read-at)

              (<= 500 (:status response) 599)
              (failure (errors/error :SEARCH_FETCH_FAILED
                                     "the page server reported an error"
                                     {:url canonical
                                      :http-status (:status response)}))

              (not (<= 200 (:status response) 299))
              (failure (errors/error :SEARCH_FETCH_FAILED
                                     "the page returned an unsuccessful status"
                                     {:url canonical
                                      :http-status (:status response)}))

              :else
              (let [content-type (http/content-type (:headers response))
                    kind (content-kind content-type (:body response))
                    body-text (String. ^bytes (:body response)
                                       (response-charset (:headers response)))
                    title (when (= kind :html) (html-title body-text))
                    text (case kind
                           :html (html-text body-text)
                           :text (str/trim body-text)
                           nil)
                    text-truncated? (and text (> (.length ^String text)
                                                 max-chars))
                    text (when text (errors/truncate text max-chars))
                    truncated? (boolean (or (:truncated? response)
                                            text-truncated?))
                    empty? (str/blank? text)
                    server-partial? (= 206 (:status response))
                    status (if (or truncated? empty? server-partial?)
                             :partial
                             :full)]
                (if (= kind :unsupported)
                  (failure (errors/error :SEARCH_FETCH_UNSUPPORTED
                                         "the page is not a text document"
                                         {:url canonical
                                          :content-type content-type}))
                  (let [updated (refs/record-read
                                 refs canonical
                                 {:status status :read-at read-at})]
                    (if-not (:ok? updated)
                      (failure (:error updated))
                      (let [document {:text text
                                      :title title
                                      :url canonical
                                      :site (some-> (parse-uri canonical)
                                                    .getHost)
                                      :content-type content-type
                                      :http-status (:status response)
                                      :read-at read-at
                                      :retrieval :direct-fetch
                                      :extraction (if (= kind :html)
                                                    :basic-html
                                                    :verbatim)
                                      :truncated? truncated?
                                      :status status
                                      :length (count text)}]
                        {:ok? true
                         :status status
                         :url canonical
                         :read-at read-at
                         :document document
                         :documents (assoc documents canonical document)
                         :refs (:refs updated)
                         :source (:source updated)
                         :notice (when empty?
                                   "the page produced no readable text")
                         :truncated? truncated?}))))))))))))
