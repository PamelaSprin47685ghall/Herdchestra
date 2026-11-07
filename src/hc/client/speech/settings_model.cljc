(ns hc.client.speech.settings-model
  "Pure command-result decisions for the speech settings page.

  A settings action may fail before any receipt exists: the sync bridge is not
  ready or the realtime transport is closed.  The page must then clear its
  pending marker, show the reason and keep the form open instead of waiting
  for a callback that will never arrive.")

(defn failure-notice
  [result fallback]
  (when-not (:ok? result)
    {:tone :error
     :text (or (get-in result [:error :message])
               fallback
               "语音设置命令未发送。")}))

(defn submission-failure
  "State patch for a command that never reached the server.  Nil when the
  command was accepted.  The patch clears only the pending marker and shows
  the reason, so the open form and loaded snapshot stay intact."
  [_state result fallback]
  (when-let [notice (failure-notice result fallback)]
    {:pending nil
     :notice notice}))
