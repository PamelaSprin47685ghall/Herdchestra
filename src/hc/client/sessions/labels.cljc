(ns hc.client.sessions.labels
  "Display-only labels shared by every client surface that shows a session.

  The server names its startup session \"New session\" (English) while the
  workbench UI is Chinese.  Callers use this namespace only when rendering;
  commands and persisted data keep the server's original title.")

(defn title-label
  "Return the display title for TITLE.

  Only the exact server default is translated.  Every manual or server
  supplied title passes through unchanged, and the caller must still send the
  original title when a display-only value was never edited."
  [title]
  (if (= "New session" title) "新会话" title))
