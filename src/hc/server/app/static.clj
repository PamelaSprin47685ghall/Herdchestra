(ns hc.server.app.static
  "Static file fallback for the browser workbench.

  The gateway owns the protocol routes and delegates every unknown path to
  this handler.  This namespace only serves bytes below one root.  It never
  lists directories, never follows a path above the root, and never invents
  a file.  A missing asset with no extension falls back to `index.html` so a
  single-page client can own its own routes."
  (:require [clojure.string :as str])
  (:import (java.io File)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path Paths)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

(def ^:private mime-types
  {".html" "text/html; charset=utf-8"
   ".htm" "text/html; charset=utf-8"
   ".css" "text/css; charset=utf-8"
   ".js" "text/javascript; charset=utf-8"
   ".mjs" "text/javascript; charset=utf-8"
   ".json" "application/json; charset=utf-8"
   ".edn" "application/edn; charset=utf-8"
   ".txt" "text/plain; charset=utf-8"
   ".md" "text/markdown; charset=utf-8"
   ".svg" "image/svg+xml"
   ".png" "image/png"
   ".jpg" "image/jpeg"
   ".jpeg" "image/jpeg"
   ".gif" "image/gif"
   ".webp" "image/webp"
   ".ico" "image/x-icon"
   ".woff" "font/woff"
   ".woff2" "font/woff2"
   ".ttf" "font/ttf"
   ".map" "application/json; charset=utf-8"
   ".wasm" "application/wasm"
   ".webmanifest" "application/manifest+json"})

(def ^:private http-date
  (.withZone DateTimeFormatter/RFC_1123_DATE_TIME ZoneOffset/UTC))

(defn- percent-decode
  [text]
  (URLDecoder/decode (str/replace text "+" "%2B") "UTF-8"))

(defn- response
  ([status headers] (response status headers nil))
  ([status headers body]
   {:status status
    :headers (merge {"X-Content-Type-Options" "nosniff"
                     "Referrer-Policy" "no-referrer"}
                    headers)
    :body body}))

(defn- not-found []
  (response 404 {"Content-Type" "text/plain; charset=utf-8"
                 "Cache-Control" "no-store"}
            "not found\n"))

(defn- method-not-allowed []
  (response 405 {"Content-Type" "text/plain; charset=utf-8"
                 "Allow" "GET, HEAD"
                 "Cache-Control" "no-store"}
            "method not allowed\n"))

(defn- safe-relative-path
  "Return a normalized relative path, or nil when the request escapes ROOT."
  [uri]
  (let [decoded (try (percent-decode (str uri))
                     (catch Exception _ nil))]
    (when (and decoded
               (str/starts-with? decoded "/")
               (not (str/includes? decoded "\u0000")))
      (let [segments (->> (str/split decoded #"/")
                          (remove #(or (str/blank? %) (= "." %))))
            unsafe? (some #(or (= ".." %)
                               (str/includes? % File/separator))
                          segments)]
        (when-not unsafe?
          (str/join File/separator segments))))))

(defn- content-type
  [path]
  (let [name (str/lower-case (.getName (File. (str path))))
        dot (.lastIndexOf name ".")]
    (if (neg? dot)
      "application/octet-stream"
      (or (get mime-types (subs name dot))
          "application/octet-stream"))))

(defn- regular-file?
  [^Path path]
  (Files/isRegularFile path (make-array LinkOption 0)))

(defn- read-file-response
  [^Path path request]
  (let [size (Files/size path)
        modified (Files/getLastModifiedTime path (make-array LinkOption 0))
        last-modified (.format http-date (.toInstant modified))
        etag (str "\"" (Long/toHexString (.toMillis modified)) "-"
                  (Long/toHexString size) "\"")
        headers {"Content-Type" (content-type path)
                 "Content-Length" (str size)
                 "Last-Modified" last-modified
                 "ETag" etag
                 "Cache-Control" "no-cache"}
        if-none-match (get-in request [:headers "if-none-match"])]
    (if (= if-none-match etag)
      (response 304 (dissoc headers "Content-Length" "Content-Type"))
      (if (= :head (:request-method request))
        (response 200 headers nil)
        (response 200 headers (Files/readAllBytes path))))))

(defn handler
  "Create a Ring handler that serves files under ROOT.

  A request for a directory serves its `index.html`.  A missing path without
  a file extension serves the root `index.html` when present.  Everything
  else is 404.  The handler is pure with respect to the filesystem except for
  reading the requested file."
  ([root] (handler root {}))
  ([root {:keys [spa-fallback? index-file]
          :or {spa-fallback? true index-file "index.html"}}]
   (let [root-path (-> (Paths/get (str root) (make-array String 0))
                       (.toAbsolutePath)
                       (.normalize))
         index-path (.resolve root-path index-file)]
     (fn [request]
       (if-not (#{:get :head} (:request-method request))
         (method-not-allowed)
         (if-let [relative (safe-relative-path (:uri request))]
           (let [candidate (if (str/blank? relative)
                             root-path
                             (.resolve root-path relative))
                 candidate (if (and (Files/exists candidate (make-array LinkOption 0))
                                    (Files/isDirectory candidate (make-array LinkOption 0)))
                             (.resolve candidate index-file)
                             candidate)]
             (cond
               (and (regular-file? candidate)
                    (str/starts-with? (str (.normalize candidate))
                                      (str root-path)))
               (read-file-response candidate request)

               (and spa-fallback?
                    (not (str/includes? relative "."))
                    (regular-file? index-path))
               (read-file-response index-path request)

               :else (not-found)))
           (not-found)))))))

(defn index-exists?
  [root]
  (Files/isRegularFile
   (-> (Paths/get (str root) (make-array String 0))
       (.toAbsolutePath)
       (.normalize)
       (.resolve "index.html"))
   (make-array LinkOption 0)))
