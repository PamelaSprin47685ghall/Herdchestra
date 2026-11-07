(ns hc.compose.mission.proof
  "Opaque in-process capability for the W-holding workspace bridge.

  Model/tool payloads are EDN data and cannot construct this object.  Only
  the bridge that actually acquired W attaches it; a bare
  `:under-write-gate? true` from request data is never trusted.  The
  program sandbox denies this namespace, and the token is never stored in
  committed state, receipts, or program results.")

(def ^:private the-token
  (Object.))

(defn token
  "Return the internal W proof token.  Intended only for the workspace
  session bridge and focused host tests; never for wire payloads.  Callers
  must attach it only while they hold W and must not retain it."
  []
  the-token)

(defn valid?
  [value]
  (identical? the-token value))
