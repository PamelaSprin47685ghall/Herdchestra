(ns hc.server.model-auth.app
  "Application adapter for model account login.

  The integrated application supplies its launch config.  This namespace
  reads only the `:model-auth` section, constructs the service, and exposes
  the four calls the application shell needs: command handling, callback
  routing, model-config token injection, and shutdown.

  It performs no policy of its own.  Provider validation, PKCE, device
  polling, identity checks, and encrypted storage stay in
  `hc.server.model-auth.service` and its support namespaces."
  (:require [hc.server.model-auth.provider :as provider]
            [hc.server.model-auth.service :as service]))

(defn start!
  "Start the model-auth component from an application config map.

  Options under `:model-auth`:
    :providers, :callback-mode, :external-redirect-uri, :callback-path,
    :authorization-timeout-seconds, :device-poll?, :env."
  [config]
  (let [options (or (:model-auth config) {})]
    (service/start!
     {:data-dir (:data-dir config)
      :providers (or (:providers options) {})
      :env (or (:env options) (System/getenv))
      :callback-mode (or (:callback-mode options) :loopback)
      :external-redirect-uri (:external-redirect-uri options)
      :callback-path (or (:callback-path options) "/model-auth/callback")
      :authorization-timeout-seconds
      (long (or (:authorization-timeout-seconds options) 600))
      :device-poll? (not (false? (:device-poll? options)))})))

(defn stop!
  [component]
  (service/stop! component))

(defn handle!
  "Handle one `:model-auth/*` command payload."
  [component payload]
  (service/handle! component payload))

(defn ring-handler
  "Ring handler for an application-mounted external callback route."
  [component]
  (service/ring-handler component))

(defn inject-models
  "Return `{:models config :errors {connection-id error}}`.

  The models value is ephemeral: it contains live bearer tokens and must
  never be persisted.  The durable connection record keeps only provider
  identity and the credential reference."
  [component models]
  (let [result (service/inject-access-tokens component models)]
    {:models (:config result)
     :errors (:errors result)}))

(defn snapshot
  "Return the browser-safe model-auth tables."
  [component]
  (let [accounts (:receipt (service/list-credentials component))]
    {:model-auth/providers (provider/public-providers (:providers component))
     :model-auth/accounts (:credentials accounts)}))
