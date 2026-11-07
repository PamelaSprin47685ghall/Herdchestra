(ns hc.server.speech.usage
  "Provider-reported non-token usage for speech and translation services.

  The adapters never estimate usage from local duration, byte count, text
  length, or elapsed time.  A quantity exists only when the provider response
  or an explicit provider header contains it.  The diagnostics hook receives
  the exact unit and quantity; a missing value stays absent so statistics can
  report `:unknown`."
  (:require [clojure.string :as str]
            [hc.server.diagnostics.service-usage :as service-usage]))

(defn- number-value
  [value]
  (cond
    (and (number? value) (not (neg? (double value)))) (double value)
    (and (string? value)
         (re-matches #"[0-9]+(?:\.[0-9]+)?" (str/trim value)))
    (double (Double/parseDouble (str/trim value)))
    :else nil))

(defn- usage-map
  [unit quantity provider-usage]
  (when (and unit (number? quantity) (not (neg? (double quantity))))
    {:unit unit
     :quantity quantity
     :quantity-source :provider-reported
     :provider-usage provider-usage}))

(defn stt-usage
  "Extract explicit non-token usage from a transcription JSON response.

  Supports the common shapes `usage {:type \"duration\" :seconds n}`,
  `{:duration n}`, `{:seconds n}` and `{:audio_seconds n}`.  Token-only usage
  is not a service quantity and is returned as nil."
  [value]
  (let [usage (:usage value)]
    (when (map? usage)
      (or (usage-map :per-second
                     (or (number-value (:seconds usage))
                         (number-value (:duration usage))
                         (number-value (:audio_seconds usage))
                         (number-value (:audio-seconds usage)))
                     usage)
          (when (and (= "duration" (some-> (:type usage) name))
                     (number-value (:value usage)))
            (usage-map :per-second (number-value (:value usage)) usage))))))

(defn translation-usage
  "Extract explicit non-token usage from a translation response.

  Token usage is returned separately by the caller; it is not a service
  quantity.  A provider that reports characters, seconds or calls can be
  priced per unit."
  [value]
  (let [usage (:usage value)]
    (when (map? usage)
      (or (usage-map :per-second
                     (or (number-value (:seconds usage))
                         (number-value (:duration usage)))
                     usage)
          (usage-map :per-character
                     (or (number-value (:characters usage))
                         (number-value (:input_characters usage)))
                     usage)
          (usage-map :per-call
                     (or (number-value (:calls usage))
                         (number-value (:requests usage)))
                     usage)))))

(defn token-usage
  "Return explicit token usage from a provider JSON object, or nil."
  [value]
  (let [usage (:usage value)
        input (or (number-value (:prompt_tokens usage))
                  (number-value (:input_tokens usage)))
        output (or (number-value (:completion_tokens usage))
                   (number-value (:output_tokens usage)))
        total (or (number-value (:total_tokens usage))
                  (when (and input output) (+ input output)))]
    (when (or input output total)
      (cond-> {}
        input (assoc :input-tokens (long input))
        output (assoc :output-tokens (long output))
        total (assoc :total-tokens (long total))))))

(defn- header
  [headers names]
  (some (fn [name]
          (some-> (get headers (str/lower-case name)) first))
        names))

(defn- header-number
  [headers names]
  (number-value (header headers names)))

(defn tts-usage
  "Extract explicit usage from TTS response headers.

  Only explicit provider headers count.  The adapter never derives seconds
  from audio bytes or characters from the input text."
  [headers]
  (let [unit-name (some-> (header headers ["x-usage-unit" "x-billing-unit"])
                          str
                          str/lower-case)
        quantity (header-number headers ["x-usage-quantity" "x-billing-quantity"])
        unit (case unit-name
               ("second" "seconds" "s") :per-second
               ("character" "characters" "char") :per-character
               ("call" "calls" "request" "requests") :per-call
               nil)
        explicit (or (usage-map unit quantity
                                {:x-usage-unit unit-name
                                 :x-usage-quantity quantity})
                     (usage-map :per-second
                                (header-number headers ["x-usage-seconds"
                                                        "x-audio-seconds"])
                                {:headers (select-keys headers
                                                       ["x-usage-seconds"
                                                        "x-audio-seconds"])})
                     (usage-map :per-character
                                (header-number headers ["x-usage-characters"])
                                {:headers (select-keys headers
                                                       ["x-usage-characters"])})
                     (usage-map :per-call
                                (header-number headers ["x-usage-calls"])
                                {:headers (select-keys headers
                                                       ["x-usage-calls"])}))]
    explicit))

(defn billed
  "Return an explicit provider-reported charge from headers, or nil."
  [headers]
  (let [amount (header-number headers ["x-billed-amount" "x-charge-amount"])
        currency (some-> (header headers ["x-billed-currency" "x-charge-currency"])
                         str
                         str/trim
                         str/upper-case)]
    (when (and amount currency (not (str/blank? currency)))
      {:amount amount :currency currency :source "provider-reported"})))

(defn diagnostics-record
  "Build one diagnostics service-usage record.

  `:usage` is a map from `stt-usage`, `tts-usage` or `translation-usage`.
  Only provider-reported quantities reach this function."
  [{:keys [area service usage usage-id session-id turn-id at billed]}]
  (when (and usage (:unit usage) (number? (:quantity usage)))
    (cond-> {:usage-id usage-id
             :service-id (:id service)
             :service-name (or (:label service) (:id service))
             :name (name area)
             :unit (:unit usage)
             :quantity (:quantity usage)
             :quantity-source :provider-reported
             :at at}
      session-id (assoc :session-id session-id)
      turn-id (assoc :turn-id turn-id)
      billed (assoc :billed billed))))

(defn stt-record
  "Build a diagnostics record from a transcription response.

  Uses the diagnostics extractor first, then falls back to an explicit
  `usage.audio_seconds` quantity.  Returns nil when the provider reported no
  non-token usage."
  [response meta]
  (or (service-usage/stt-duration response meta)
      (when-let [usage (stt-usage response)]
        (service-usage/provider-reported
         (merge meta
                (select-keys usage [:unit :quantity :quantity-source]))))))

(defn tts-record
  "Build a diagnostics record from explicit TTS response headers."
  [headers meta]
  (when-let [usage (tts-usage headers)]
    (service-usage/provider-reported
     (merge meta
            (select-keys usage [:unit :quantity :quantity-source])
            (when-let [charge (billed headers)]
              {:billed charge})))))

(defn translation-record
  "Build a diagnostics record from explicit non-token translation usage."
  [response meta]
  (when-let [usage (translation-usage response)]
    (service-usage/provider-reported
     (merge meta
            (select-keys usage [:unit :quantity :quantity-source])))))

(defn record!
  "Send one record to the optional usage port.  Usage recording is secondary:
  a diagnostics failure must not turn a successful speech request into an
  error.  Returns true only when the port accepted the record."
  [component record]
  (if-let [port (:usage-port component)]
    (try
      (port record)
      true
      (catch Throwable _ false))
    false))
