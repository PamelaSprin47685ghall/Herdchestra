(ns hc.server.model-port.failures
  "Stable client-facing failure codes for model and transport errors.

  The adapter keeps its internal `:kind` vocabulary for retry decisions.  This
  namespace is the single projection from that vocabulary (plus HTTP status)
  to the codes `hc.client.copy.failures` understands, with a Chinese message
  and the retry fact.  It never invents a cause: an unmapped failure is
  `:unknown`, not a borrowed category."
  (:require [clojure.string :as str]))

(def codes
  "Every stable code this projection may emit, with its user-facing copy."
  {:auth-invalid
   {:message "服务没有接受这组凭据；请更新凭据或改选连接。"
    :retryable? false}

   :permission-denied
   {:message "这项操作需要的权限已被拒绝；请检查连接权限或改选连接。"
    :retryable? false}

   :model-missing
   {:message "原模型已不可用；请选择替代模型，历史回答仍可阅读。"
    :retryable? false}

   :quota-exhausted
   {:message "这家服务报告额度不足，本次未继续；请查看服务或换模型。"
    :retryable? false}

   :rate-limited
   {:message "服务暂时限制请求；请等待后重试或取消。"
    :retryable? true}

   :network-unreachable
   {:message "无法连接到所选服务；请检查连接或稍后重试。"
    :retryable? true}

   :transport-disconnected
   {:message "与服务之间的传输已断开；请重新连接后重试。"
    :retryable? true}

   :response-timeout
   {:message "服务尚未返回内容；可以继续等待或停止当前生成。"
    :retryable? true}

   :context-too-long
   {:message "当前内容超过所选模型可接受的范围；请调整内容范围。"
    :retryable? false}

   :attachment-unsupported
   {:message "当前模型不能接收这类附件；请换模型或改变处理方式。"
    :retryable? false}

   :unknown
   {:message "无法确定具体原因；请查看诊断信息并按检查顺序处理。"
    :retryable? false}})

(defn- quota-status?
  [status]
  (= 402 status))

(defn- quota-message?
  [message]
  (let [message (str/lower-case (str message))]
    (or (str/includes? message "quota")
        (str/includes? message "insufficient_quota")
        (str/includes? message "credit balance")
        (str/includes? message "billing"))))

(defn stable-code
  "Map one adapter classification to a stable client code."
  [{:keys [kind http-status message]}]
  (cond
    (or (= kind :insufficient-quota) (quota-status? http-status)
        (and (= kind :unclassified) (quota-message? message)))
    :quota-exhausted

    (or (= kind :auth) (= 401 http-status)) :auth-invalid
    (or (= kind :permission) (= 403 http-status)) :permission-denied
    (or (= kind :invalid-model) (= 404 http-status)) :model-missing
    (or (= kind :rate-limited) (= 429 http-status)) :rate-limited
    (= kind :transport) :transport-disconnected
    (= kind :network) :network-unreachable
    (or (= kind :timeout) (contains? #{408 504} http-status))
    :response-timeout
    (or (= kind :input-too-long) (= 413 http-status)) :context-too-long
    (contains? #{:unsupported-feature :attachment-unsupported} kind)
    :attachment-unsupported
    :else :unknown))

(defn classify
  "Return `{:code :message :retryable?}` for one adapter classification.

  The message is the Chinese user copy; the raw provider text stays in the
  adapter's `:message`/`:detail` for diagnosis."
  [classification]
  (let [code (stable-code classification)
        spec (get codes code)]
    (assoc spec :code code)))
