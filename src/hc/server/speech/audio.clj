(ns hc.server.speech.audio
  "Small, dependency-free audio metadata checks.

  The server must not trust a client-supplied duration for the five-minute
  voice-input cap.  A WAV container carries enough information to compute the
  duration from the bytes themselves.  Other containers return nil; the
  speech service then decides whether the selected provider may receive an
  unverifiable recording.

  This namespace does not decode or play audio."
  (:require [clojure.string :as str]))

(defn- ascii
  [^bytes bytes offset length]
  (String. bytes offset length "US-ASCII"))

(defn- u16
  [^bytes bytes offset]
  (bit-or (bit-and (int (aget bytes offset)) 0xff)
          (bit-shift-left (bit-and (int (aget bytes (inc offset))) 0xff) 8)))

(defn- u32
  [^bytes bytes offset]
  (bit-or (bit-and (int (aget bytes offset)) 0xff)
          (bit-shift-left (bit-and (int (aget bytes (inc offset))) 0xff) 8)
          (bit-shift-left (bit-and (int (aget bytes (+ offset 2))) 0xff) 16)
          (bit-shift-left (bit-and (int (aget bytes (+ offset 3))) 0xff) 24)))

(defn- wav-duration-ms
  [^bytes bytes]
  (when (and (>= (alength bytes) 44)
             (= "RIFF" (ascii bytes 0 4))
             (= "WAVE" (ascii bytes 8 4)))
    (loop [offset 12
           byte-rate nil
           data-size nil]
      (if (> (+ offset 8) (alength bytes))
        (when (and byte-rate data-size (pos? (long byte-rate)))
          (long (Math/round (* 1000.0 (/ (double data-size)
                                         (double byte-rate))))))
        (let [id (ascii bytes offset 4)
              size (u32 bytes (+ offset 4))
              body (+ offset 8)
              next-offset (+ body size (bit-and size 1))]
          (cond
            (and (= "fmt " id) (>= (+ body 12) (alength bytes)))
            nil

            (= "fmt " id)
            (recur next-offset (u32 bytes (+ body 8)) data-size)

            (and (= "data" id) (<= (+ body size) (alength bytes)))
            (recur next-offset byte-rate size)

            (<= next-offset (alength bytes))
            (recur next-offset byte-rate data-size)

            :else
            (when (and byte-rate data-size (pos? (long byte-rate)))
              (long (Math/round (* 1000.0 (/ (double data-size)
                                             (double byte-rate))))))))))))

(defn duration-ms
  "Return the duration of BYTES in milliseconds when it is verifiable from
  the container, or nil when this namespace cannot prove it.

  `content-type` is a hint; the RIFF/WAVE signature is authoritative."
  [^bytes bytes content-type]
  (when (and bytes (pos? (alength bytes)))
    (let [content-type (some-> content-type str str/lower-case)]
      (when (or (nil? content-type)
                (re-find #"wav|wave|riff" content-type)
                (and (>= (alength bytes) 4)
                     (= "RIFF" (ascii bytes 0 4))))
        (wav-duration-ms bytes)))))

(defn verifiable?
  [bytes content-type]
  (some? (duration-ms bytes content-type)))
