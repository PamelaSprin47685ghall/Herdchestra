(ns hc.client.speech.playback-model
  "Pure request-generation checks for the single read-aloud player.

  Every play or stop advances the module generation.  A synthesis response may
  change player state only when its generation, message and version still
  match the waiting request.")

(defn next-generation
  [current]
  (inc (long (or current 0))))

(defn request-current?
  "True only while STATE still waits for REQUEST.  A stopped player is idle
  and therefore never current, even when the ids happen to match."
  [state request]
  (and (= :loading (:status state))
       (= (:generation state) (:generation request))
       (= (:message-id state) (:message-id request))
       (= (:version-id state) (:version-id request))))
