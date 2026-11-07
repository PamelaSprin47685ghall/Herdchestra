(ns hc.compose.reports.report
  "Report-pending records and full-inline payload rules.

  A report crosses an Owner boundary as immutable data.  An LLM-written
  report is carried verbatim: no truncation, no summary, no artifact
  indirection.  Raw tool output may be bounded, but the truncation is
  explicit and carries byte counts.

  Validation returns a value.  It never throws.  `make-report` and
  `make-ack` throw only at a wiring boundary where a caller already holds
  valid data."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)))

(def payload-kinds
  "The two payload kinds that may cross the report boundary."
  #{:llm-report :raw-tool-output})

(def llm-payload-keys
  "The only fields an LLM report payload may carry."
  #{:kind :text})

(def raw-payload-keys
  "The only fields a raw tool-output payload may carry."
  #{:kind :text :truncated? :original-bytes :delivered-bytes})

(def llm-indirection-keys
  "Fields that would turn a full LLM report into a reference or summary."
  #{:artifact-ref :artifact :artifact-id :artifact-uri :summary})

(def ack-statuses
  "`accepted` means the parent accepted the report.  `parent-terminated`
  means the parent responsibility is gone; the child must still clear its
  pending record."
  #{:accepted :parent-terminated})

(defn non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn non-negative-integer?
  [value]
  (and (integer? value) (not (neg? value))))

(defn stable-data?
  "True when VALUE is immutable EDN-shaped data that survives persistence.

  Functions, threads, sockets, atoms, and other live handles are refused.
  Dates and UUIDs are allowed because EDN carries them as tagged literals."
  [value]
  (cond
    (nil? value) true
    (or (boolean? value) (string? value) (keyword? value) (symbol? value)
        (number? value) (char? value) (uuid? value) (inst? value))
    true

    (map? value)
    (every? (fn [[k v]] (and (stable-data? k) (stable-data? v))) value)

    (or (vector? value) (set? value) (list? value))
    (every? stable-data? value)

    :else false))

(defn- utf8-byte-count
  [^String text]
  (alength (.getBytes text StandardCharsets/UTF_8)))

(defn- failure
  ([code message]
   (failure code message nil))
  ([code message details]
   (cond-> {:ok? false
            :error {:code code :message message}}
     (some? details) (assoc-in [:error :details] details))))

(defn validate-payload
  "Validate a terminal report payload.

  `:llm-report` must carry its full text.  Any truthy truncation marker or
  any artifact/summary indirection is refused.  `:raw-tool-output` may be
  truncated, but then it must state `:original-bytes` and `:delivered-bytes`;
  the delivered count must equal the UTF-8 byte length of `:text` and may
  not exceed the original count.

  The returned payload keeps only the protocol fields, so unrelated metadata
  (including non-EDN values) cannot enter the durable report record."
  [payload]
  (cond
    (not (map? payload))
    (failure :REPORT_PAYLOAD_INVALID "payload must be a map" {:payload payload})

    (not (contains? payload-kinds (:kind payload)))
    (failure :REPORT_PAYLOAD_INVALID
             "payload :kind must be :llm-report or :raw-tool-output"
             {:kind (:kind payload)})

    (not (string? (:text payload)))
    (failure :REPORT_PAYLOAD_INVALID
             "payload :text must be a string"
             {:text (:text payload)})

    (and (= :llm-report (:kind payload))
         (contains? payload :truncated?)
         (not (false? (:truncated? payload))))
    (failure :REPORT_TRUNCATED
             "an LLM-written report must be delivered in full"
             {:truncated? (:truncated? payload)})

    (and (= :llm-report (:kind payload))
         (some #(contains? payload %) llm-indirection-keys))
    (failure :REPORT_ARTIFACT
             "an LLM-written report must be inlined, not referenced as an artifact"
             (select-keys payload llm-indirection-keys))

    (and (= :raw-tool-output (:kind payload))
         (contains? payload :truncated?)
         (not (boolean? (:truncated? payload))))
    (failure :REPORT_PAYLOAD_INVALID
             ":truncated? must be a boolean"
             {:truncated? (:truncated? payload)})

    (and (= :raw-tool-output (:kind payload))
         (or (contains? payload :original-bytes)
             (contains? payload :delivered-bytes))
         (not (and (non-negative-integer? (:original-bytes payload))
                   (non-negative-integer? (:delivered-bytes payload)))))
    (failure :REPORT_TRUNCATION_INVALID
             "raw output byte counts must be non-negative integers"
             (select-keys payload [:original-bytes :delivered-bytes]))

    (and (= :raw-tool-output (:kind payload))
         (true? (:truncated? payload))
         (not (and (= (:delivered-bytes payload)
                      (utf8-byte-count (:text payload)))
                   (<= (:delivered-bytes payload)
                       (:original-bytes payload)))))
    (failure :REPORT_TRUNCATION_INVALID
             "a truncated raw output must state valid :original-bytes and :delivered-bytes; :delivered-bytes must equal the UTF-8 byte length of :text"
             (select-keys payload [:original-bytes :delivered-bytes]))

    :else
    {:ok? true
     :payload (select-keys payload
                           (if (= :llm-report (:kind payload))
                             llm-payload-keys
                             raw-payload-keys))}))

(defn validate-report
  "Validate a report-pending record.

  The result is `{:ok? true :report record}` or a failure value.  The record
  keeps only the protocol fields, so unrelated metadata cannot leak into the
  durable pending obligation.

  The protocol deliberately carries no live resource handle.  The child
  fixes its terminal state and releases runtime resources before it
  registers the report; post-ack reclamation removes the durable record.
  A legacy `:resource-ref` is ignored, not stored."
  [report]
  (cond
    (not (map? report))
    (failure :REPORT_INVALID "report must be a map" {:report report})

    (not (non-blank-string? (:report-id report)))
    (failure :REPORT_INVALID
             ":report-id must be a non-blank string"
             {:report-id (:report-id report)})

    (not (non-blank-string? (:parent-call-id report)))
    (failure :REPORT_INVALID
             ":parent-call-id must be a non-blank string"
             {:parent-call-id (:parent-call-id report)})

    (and (contains? report :terminal)
         (not (and (map? (:terminal report))
                   (keyword? (get-in report [:terminal :status]))
                   (stable-data? (:terminal report)))))
    (failure :REPORT_INVALID
             ":terminal must be a map with a keyword :status and stable data"
             {:terminal (:terminal report)})

    (and (contains? report :parent-session-id)
         (not (non-blank-string? (:parent-session-id report))))
    (failure :REPORT_INVALID
             ":parent-session-id must be a non-blank string when present"
             {:parent-session-id (:parent-session-id report)})

    (and (contains? report :child-session-id)
         (not (non-blank-string? (:child-session-id report))))
    (failure :REPORT_INVALID
             ":child-session-id must be a non-blank string when present"
             {:child-session-id (:child-session-id report)})

    :else
    (let [validated (validate-payload (:payload report))]
      (if (:ok? validated)
        {:ok? true
         :report (cond-> {:report-id (:report-id report)
                          :parent-call-id (:parent-call-id report)
                          :payload (:payload validated)}
                   (contains? report :parent-session-id)
                   (assoc :parent-session-id (:parent-session-id report))
                   (contains? report :child-session-id)
                   (assoc :child-session-id (:child-session-id report))
                   (contains? report :terminal)
                   (assoc :terminal (:terminal report)))}
        validated))))

(defn make-report
  "Build a validated report record.  Throws on invalid input.

  Use `validate-report` at a boundary that must return a value instead of
  throwing."
  [report]
  (let [validated (validate-report report)]
    (if (:ok? validated)
      (:report validated)
      (throw (ex-info (get-in validated [:error :message])
                      (:error validated))))))

(defn validate-ack
  "Validate an acknowledgement from the parent.

  The result is `{:ok? true :ack ack}` or a failure value.  The protocol
  fields survive validation; `:child-session-id` is optional routing
  metadata."
  [ack]
  (cond
    (not (map? ack))
    (failure :ACK_INVALID "acknowledgement must be a map" {:ack ack})

    (not (non-blank-string? (:report-id ack)))
    (failure :ACK_INVALID
             "acknowledgement :report-id must be a non-blank string"
             {:report-id (:report-id ack)})

    (not (non-blank-string? (:parent-call-id ack)))
    (failure :ACK_INVALID
             "acknowledgement :parent-call-id must be a non-blank string"
             {:parent-call-id (:parent-call-id ack)})

    (not (contains? ack-statuses (:status ack)))
    (failure :ACK_INVALID
             "acknowledgement :status must be :accepted or :parent-terminated"
             {:status (:status ack)})

    (and (contains? ack :child-session-id)
         (not (non-blank-string? (:child-session-id ack))))
    (failure :ACK_INVALID
             "acknowledgement :child-session-id must be a non-blank string when present"
             {:child-session-id (:child-session-id ack)})

    :else
    {:ok? true
     :ack (cond-> (select-keys ack [:report-id :parent-call-id :status])
            (contains? ack :child-session-id)
            (assoc :child-session-id (:child-session-id ack)))}))

(defn make-ack
  "Build a validated acknowledgement.  Throws on invalid input."
  [report-id parent-call-id status]
  (let [validated (validate-ack {:report-id report-id
                                 :parent-call-id parent-call-id
                                 :status status})]
    (if (:ok? validated)
      (:ack validated)
      (throw (ex-info (get-in validated [:error :message])
                      (:error validated))))))
