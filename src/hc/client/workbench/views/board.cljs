(ns hc.client.workbench.views.board
  "Mount point for the swarm-ui dashboard.

  The fleet, task tree, wait chains, and join reports are rendered by
  `hc.client.swarm.ui/SwarmDashboardFromDb`.  This namespace adds only the
  workbench header and a resync action; it does not keep a parallel board."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.swarm.ui :as swarm-ui]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.sync-bridge :as bridge]
            [hc.client.workbench.transport :as transport]
            [hc.client.workbench.views.common :as common]))

(defn- ack-call!
  "Send one operator acknowledgement for an unknown call.

  The Owner validates the call id and its current state; this function only
  carries the operator's conclusion.  It never retries the original action."
  [this session-id {:keys [call-id outcome]}]
  (let [command-id (str (random-uuid))
        epoch (bridge/command-epoch-for-session session-id)
        sent? (transport/command!
               {:command-id command-id
                :command-epoch epoch
                :payload {:kind :call/ack
                          :session-id session-id
                          :call-id call-id
                          :outcome (name outcome)
                          :expected-state "outcome-unknown"}})]
    (if sent?
      (commands/show-notice! this :info "已发送核对；等待服务端结清，动作不会重放。")
      (commands/show-notice! this :danger "核对未发送：实时通道未打开或已中断。"))
    sent?))

(defn- retry-program!
  "Ask the session to re-read the file and retry the protected write.

  The browser cannot replay a worker program; it sends one operator message.
  The Owner/model/worker path decides the next commit, and the old patch is
  never replayed automatically."
  [this session-id {:keys [path]}]
  (let [command-id (str (random-uuid))
        epoch (bridge/command-epoch-for-session session-id)
        sent? (transport/command!
               {:command-id command-id
                :command-epoch epoch
                :payload {:kind :send-message
                          :session-id session-id
                          :text (str "E2E 09 重试：请重读 "
                                     (or path "目标文件")
                                     " 的当前内容，核对原/当前 stamp 后重新提交受保护写入；不要重放旧补丁。")}})]
    (if sent?
      (commands/show-notice! this :info "已发送重读重试请求；旧补丁不会自动重放。")
      (commands/show-notice! this :danger "重试请求未发送：实时通道未打开。"))
    sent?))

(defn- selected-swarm-from-hash
  "Read `#/board/:id` or `#/swarm/:id`.  The router still owns the page
  choice; this only supplies the deep-link selection to the dashboard."
  []
  (when (exists? js/window)
    (let [parts (->> (str/split (or (.-hash js/window.location) "") #"/")
                     (remove str/blank?)
                     vec)]
      (when (and (= 2 (count parts))
                 (contains? #{"board" "swarm"} (first parts)))
        (second parts)))))

(defn board-page
  [this props]
  (let [db (application/current-state this)
        connection (:ui/connection props)
        selected-id (or (:swarm/selected-id props)
                        (:ui/board-selected-id props)
                        (selected-swarm-from-hash))
        session-id (or (:session/active-id db)
                       (model/selected-session-id db))
        resync (get (model/availability {:connection connection}) :resync)]
    (dom/div {:className "wb-content"}
             (dom/div {:className "wb-content-inner"}
                      (dom/div {:className "wb-page-title"}
                               (dom/div {}
                                        (dom/h1 {} "Swarm 看板")
                                        (dom/p {} "fleet 总览、任务树、等待链与固定运行槽。状态来自服务器当前态；此视图不提供认领或写文件操作。"))
                               (dom/div {:className "wb-row"}
                                        (common/badge (model/connection-label connection)
                                                      (:tone connection))
                                        (dom/button {:type "button"
                                                     :className "wb-button wb-button-small"
                                                     :disabled (not (:enabled? resync))
                                                     :title (when-not (:enabled? resync)
                                                              (model/availability-reason resync))
                                                     :onClick (fn [_]
                                                                (commands/request-resync! this :operator-board))}
                                                    "请求重同步")))
                      (react/createElement swarm-ui/SwarmDashboardFromDb
                                           (js-obj "db" db
                                                   "swarm/selected-id" selected-id
                                                   "on-ack-call"
                                                   (fn [payload]
                                                     (ack-call! this session-id payload))
                                                   "on-retry-program"
                                                   (fn [payload]
                                                     (retry-program! this session-id payload))))))))
