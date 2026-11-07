(ns hc.core.pair-hint.canon
  "Deterministic EDN-shaped canonical bytes and SHA-256 digests.

  Pair Hint stores digests instead of request copies.  A digest is only useful
  when the same semantic value always produces the same bytes, so this
  namespace sorts map entries and set members and rejects values whose
  printed form depends on object identity or locale."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util Date UUID)))

(declare canonical-string)

(defn- sort-key
  "Canonical text plus a type tag, so distinct values with the same printed
  form still sort deterministically."
  [x]
  [(canonical-string x) (some-> x class .getName)])

(defn- canonical-map
  [m]
  (let [entries (sort-by (fn [[k _]] (sort-key k)) m)]
    (str "{"
         (str/join " " (map (fn [[k v]]
                              (str (canonical-string k) " " (canonical-string v)))
                            entries))
         "}")))

(defn- canonical-set
  [s]
  (str "#{" (str/join " " (map canonical-string (sort-by sort-key s))) "}"))

(defn canonical-string
  "Return one stable UTF-8 string for an EDN-shaped value.

  Maps are key-sorted, sets are member-sorted, and sequential collections are
  rendered in order.  Strings use `pr-str` escaping so quotes, backslashes and
  newlines have exactly one representation.  Unknown object types fail closed:
  a digest that cannot be reproduced is worse than a loud refusal."
  [x]
  (cond
    (nil? x) "nil"
    (string? x) (pr-str x)
    (keyword? x) (str x)
    (symbol? x) (str "#symbol " (pr-str (str x)))
    (number? x) (pr-str x)
    (boolean? x) (str x)
    (char? x) (pr-str x)
    (map? x) (canonical-map x)
    (set? x) (canonical-set x)
    (sequential? x) (str "[" (str/join " " (map canonical-string x)) "]")
    (instance? UUID x) (str "#uuid " (pr-str (str x)))
    (instance? Instant x) (str "#inst " (pr-str (str x)))
    (instance? Date x) (str "#inst " (pr-str (str (.toInstant ^Date x))))
    :else (throw (ex-info "pair-hint: value has no deterministic canonical form"
                          {:error :PAIR_HINT_CANONICAL_UNSUPPORTED
                           :type (some-> x class str)}))))

(defn utf8-bytes
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn sha256-hex
  "Lower-case SHA-256 hex digest of a string."
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (utf8-bytes s))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn digest
  "SHA-256 hex digest of the canonical form of `x`."
  [x]
  (sha256-hex (canonical-string x)))

(defn short-digest
  "First `n` hex characters of a digest; used for stable synthetic call ids."
  ([x] (short-digest x 16))
  ([x n] (subs (digest x) 0 (min n 64))))
