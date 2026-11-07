(ns hc.server.history.compression
  "Reversible context compression.

  A compression starts as a review draft.  It changes the outbound projection
  only after `apply!`; the original messages remain untouched and readable.
  `undo!` restores the original range without deleting messages that arrived
  after the compression was applied.

  No function here calls a model or starts a run.  The caller may generate a
  summary elsewhere and pass it in; the history domain stores exactly that
  reviewed text."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.server.history.context :as context]
            [hc.server.history.message :as message])
  (:import (java.util UUID)))

(def compression-states #{:draft :applied :reverted :cancelled})

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- error
  [code text details]
  {:ok? false
   :error (cond-> {:code code :message text}
            (some? details) (assoc :details details))})

(defn compression
  [session compression-id]
  (get (context/compressions session) compression-id))

(defn draft?
  [compression]
  (= :draft (:state compression)))

(defn applied?
  [compression]
  (= :applied (:state compression)))

(defn- visible-order
  [session]
  (message/visible-message-ids session))

(defn- contiguous-range?
  [session message-ids]
  (let [order (visible-order session)
        indexes (mapv #(.indexOf ^java.util.List order %) message-ids)]
    (and (every? #(<= 0 %) indexes)
         (= indexes (vec (range (first indexes)
                                (+ (first indexes) (count indexes))))))))

(defn- overlap
  [session message-ids]
  (let [ids (set message-ids)]
    (first
     (for [[compression-id compression] (context/compressions session)
           :when (not (contains? #{:reverted :cancelled} (:state compression)))
           :when (seq (set/intersection ids
                                        (set (:message-ids compression))))]
       {:compression-id compression-id
        :message-ids (:message-ids compression)}))))

(defn- source-version-ids
  [session message-ids]
  (into {}
        (keep (fn [message-id]
                (let [message (get-in session [:messages message-id])]
                  (when message
                    [message-id (:version-id (message/current-version
                                              (message/normalize-message message)))]))))
        message-ids))

(defn prepare
  "Create a review draft for one contiguous visible range.

  The summary is supplied by the caller; this function never invents one.
  Nothing is replaced until `apply!`."
  [session {:keys [compression-id message-ids summary now]}]
  (let [message-ids (vec (or message-ids []))
        overlap (overlap session message-ids)
        visible (set (visible-order session))]
    (cond
      (not (and (vector? message-ids) (seq message-ids)))
      (error :COMPRESSION_RANGE_REQUIRED
             "compression needs a non-empty vector of message-ids"
             {:message-ids message-ids})

      (and compression-id
           (contains? (context/compressions session) compression-id))
      (error :COMPRESSION_ID_TAKEN
             "compression-id is already in use"
             {:compression-id compression-id})

      (not= (count message-ids) (count (set message-ids)))
      (error :COMPRESSION_RANGE_DUPLICATE
             "compression range cannot repeat a message-id"
             {:message-ids message-ids})

      (some #(not (contains? visible %)) message-ids)
      (error :COMPRESSION_MESSAGE_NOT_VISIBLE
             "compression can only cover visible messages"
             {:message-ids message-ids})

      (not (contiguous-range? session message-ids))
      (error :COMPRESSION_RANGE_NOT_CONTIGUOUS
             "compression range must be contiguous in the visible history"
             {:message-ids message-ids})

      (not (non-blank-string? summary))
      (error :COMPRESSION_SUMMARY_REQUIRED
             "compression needs a reviewed, non-blank summary"
             {:message-ids message-ids})

      overlap
      (error :COMPRESSION_RANGE_OVERLAP
             "compression range overlaps an existing draft or applied range"
             overlap)

      :else
      (let [compression-id (or compression-id (str "cmp-" (UUID/randomUUID)))
            compression {:compression-id compression-id
                         :state :draft
                         :message-ids message-ids
                         :range {:from-message-id (first message-ids)
                                 :to-message-id (last message-ids)}
                         :summary summary
                         :source-version-ids (source-version-ids session
                                                                  message-ids)
                         :created-at now
                         :updated-at now
                         :applied-at nil
                         :reverted-at nil}
            session' (-> session
                         (assoc-in [:context :compressions compression-id]
                                   compression))]
        {:ok? true
         :session session'
         :compression compression
         :activity? false}))))

(defn update-draft
  "Edit the review text of a draft compression.  Applied text cannot be
  changed in place; undo it first."
  [session compression-id summary {:keys [now]}]
  (let [compression (compression session compression-id)]
    (cond
      (nil? compression)
      (error :COMPRESSION_NOT_FOUND
             "compression does not exist"
             {:compression-id compression-id})

      (not (draft? compression))
      (error :COMPRESSION_NOT_DRAFT
             "only a draft compression can be edited"
             {:compression-id compression-id
              :state (:state compression)})

      (not (non-blank-string? summary))
      (error :COMPRESSION_SUMMARY_REQUIRED
             "compression needs a non-blank summary"
             {:compression-id compression-id})

      :else
      (let [compression' (assoc compression
                                :summary summary
                                :updated-at now)]
        {:ok? true
         :session (assoc-in session [:context :compressions compression-id]
                            compression')
         :compression compression'
         :activity? false}))))

(defn apply!
  "Confirm the reviewed summary and make it replace the range in the outbound
  projection.  Original messages are not deleted or rewritten."
  [session compression-id {:keys [summary now]}]
  (let [compression (compression session compression-id)]
    (cond
      (nil? compression)
      (error :COMPRESSION_NOT_FOUND
             "compression does not exist"
             {:compression-id compression-id})

      (not (draft? compression))
      (error :COMPRESSION_NOT_DRAFT
             "only a draft compression can be applied"
             {:compression-id compression-id
              :state (:state compression)})

      (and (some? summary) (not (non-blank-string? summary)))
      (error :COMPRESSION_SUMMARY_REQUIRED
             "compression needs a non-blank summary"
             {:compression-id compression-id})

      :else
      (let [summary (or summary (:summary compression))
            compression' (assoc compression
                                :state :applied
                                :summary summary
                                :updated-at now
                                :applied-at now)
            order (context/compression-order session)
            session' (-> session
                         (assoc-in [:context :compressions compression-id]
                                   compression')
                         (assoc-in [:context :compression-order]
                                   (vec (distinct (conj order compression-id)))))]
        {:ok? true
         :session session'
         :compression compression'
         :activity? false}))))

(defn undo
  "Undo one applied compression.

  The original range becomes eligible for sending again.  Messages appended
  after the compression remain in place; no message is deleted."
  [session compression-id {:keys [now]}]
  (let [compression (compression session compression-id)]
    (cond
      (nil? compression)
      (error :COMPRESSION_NOT_FOUND
             "compression does not exist"
             {:compression-id compression-id})

      (not (applied? compression))
      (error :COMPRESSION_NOT_APPLIED
             "only an applied compression can be undone"
             {:compression-id compression-id
              :state (:state compression)})

      :else
      (let [compression' (assoc compression
                                :state :reverted
                                :updated-at now
                                :reverted-at now)
            session' (-> session
                         (assoc-in [:context :compressions compression-id]
                                   compression')
                         (update-in [:context :compression-order]
                                    (fn [order]
                                      (vec (remove #{compression-id}
                                                   (or order []))))))]
        {:ok? true
         :session session'
         :compression compression'
         :activity? false}))))

(defn cancel
  "Discard a draft compression without changing the outbound projection."
  [session compression-id {:keys [now]}]
  (let [compression (compression session compression-id)]
    (cond
      (nil? compression)
      (error :COMPRESSION_NOT_FOUND
             "compression does not exist"
             {:compression-id compression-id})

      (not (draft? compression))
      (error :COMPRESSION_NOT_DRAFT
             "only a draft compression can be cancelled"
             {:compression-id compression-id
              :state (:state compression)})

      :else
      (let [compression' (assoc compression
                                :state :cancelled
                                :updated-at now
                                :cancelled-at now)]
        {:ok? true
         :session (assoc-in session [:context :compressions compression-id]
                            compression')
         :compression compression'
         :activity? false}))))

(defn review
  "Return one compression with its original range still readable."
  [session compression-id]
  (when-let [compression (compression session compression-id)]
    (let [messages (keep #(get-in session [:messages %])
                         (:message-ids compression))]
      {:compression compression
       :messages (mapv message/message-view messages)
       :current-messages (mapv (fn [message-id]
                                 (some-> (get-in session [:messages message-id])
                                         message/message-view))
                               (:message-ids compression))})))

(defn check-invariants
  [session]
  (let [compressions (context/compressions session)
        order (context/compression-order session)
        applied-ids (->> compressions
                         (keep (fn [[id compression]]
                                 (when (= :applied (:state compression)) id)))
                         set)
        order-set (set order)]
    (into
     []
     (concat
      (when (not= applied-ids order-set)
        [{:invariant :context-compression-order-mismatch
          :applied (vec applied-ids)
          :order order}])
      (mapcat
       (fn [[compression-id compression]]
         (let [ids (:message-ids compression)
               visible (set (visible-order session))]
           (cond-> []
             (not (contains? compression-states (:state compression)))
             (conj {:invariant :compression-state-invalid
                    :compression-id compression-id
                    :state (:state compression)})

             (not (and (vector? ids) (seq ids)))
             (conj {:invariant :compression-range-invalid
                    :compression-id compression-id})

             (and (contains? #{:draft :applied} (:state compression))
                  (vector? ids)
                  (not (contiguous-range? session ids)))
             (conj {:invariant :compression-range-not-contiguous
                    :compression-id compression-id
                    :message-ids ids})

             (and (contains? #{:draft :applied} (:state compression))
                  (vector? ids)
                  (some #(not (contains? visible %)) ids))
             (conj {:invariant :compression-message-not-visible
                    :compression-id compression-id
                    :message-ids ids})

             (not (string? (:summary compression)))
             (conj {:invariant :compression-summary-invalid
                    :compression-id compression-id}))))
       compressions)))))
