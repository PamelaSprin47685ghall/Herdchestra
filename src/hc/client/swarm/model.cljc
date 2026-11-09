(ns hc.client.swarm.model
  "Pure projection of fleet and per-worktree swarm board data for the
  workbench dashboard.

  The server owns the domain state.  This namespace only turns a display copy
  into the rows, trees, explanations, and labels that the view needs.  It never
  invents a terminal state and never shortens a join report.

  Two input shapes are accepted:

  1. A dashboard snapshot:
     {:swarms [{:swarm-id \"swarm-1\" ... :board {...}} ...]
      :selected-swarm-id \"swarm-1\"}
  2. A Fulcro-style database with normalized tables:
     {:swarm/by-id {...} :task/by-id {...} :action/by-id {...}
      :agent/by-id {...} :token-slot/by-id {...} :write-gate/by-id {...}}

  Per-swarm board data may be nested under `:board`, or supplied as normalized
  tables whose entities carry `:swarm-id` (or `:worktree`).  IDs cross the
  language boundary as non-blank strings; the projection normalizes them so a
  keyword ID in a test and a string ID from the wire address the same task."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Vocabulary

(def statuses
  #{:running :queued :joined :abandoned :failed})

(def task-states
  #{:ready :waiting :done})

(def action-phases
  #{:claimed :preparing :waiting-gate :applying :running :finished :uncertain})

(def active-action-phases
  #{:applying :running :uncertain})

(def normal-lifecycle-reasons
  #{:attempt-lost :competition-lost :retired-empty-holder
    :responsibility-empty :holder-retired :normal :handoff
    :slot-recycled :parked :wake :operator-cancel :cancelled :stopped})

(def failure-lifecycle-reasons
  #{:failed :crashed :error :panic :outcome-unknown})

(def status-labels
  {:running "运行中"
   :queued "排队中"
   :joined "已合入"
   :abandoned "已放弃"
   :failed "失败"
   :unknown "状态未上报"})

(def task-state-labels
  {:ready "就绪"
   :waiting "等待中"
   :done "已完成"})

(def status-details
  {:running "swarm 正在自己的 worktree 上施工。"
   :queued "swarm 已获准入但尚未开始；依赖或 worktree 尚未就绪。"
   :joined "结果已快进合入 master；worktree 保留至 GC。"
   :abandoned "结果已被放弃，未进入合入队列。"
   :failed "swarm 以错误结束；未产生合入结果。"
   :unknown "服务器未上报该行的 fleet 状态；下方看板记录为当前服务器状态。"})

(def role-labels
  {:normal "工人"
   :holder "令牌持有者"})

(def life-state-labels
  {:alive "存活"
   :dead "已退出（逻辑）"
   :terminated "已终止"})

(def recovery-reason-labels
  "Human labels for the reasons a recovery write barrier reports."
  {:uncertain-actions "存在结果未知且未结清的动作"
   :write-gate-held "上一个进程持有 W 时未给出已证实的结局"
   :process-restart "上一个进程在持有 W 时停止"})

;; ---------------------------------------------------------------------------
;; Small readers

(defn- key-string
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (string? k) k
    :else (str k)))

(defn- get*
  "Read K from M, accepting either the keyword or its full string form."
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (key-string k)))))

(defn- some-get
  [m ks]
  (some (fn [k]
          (let [v (get* m k)]
            (when (some? v) v)))
        ks))

(defn id-string
  "Return a non-blank string ID, or nil.  A keyword ID loses its leading
  colon so `:agent-1` and `\"agent-1\"` name the same entity."
  [value]
  (when (some? value)
    (let [s (str/replace (str value) #"^:" "")]
      (when-not (str/blank? s) s))))

(defn- entity-id
  [m ks]
  (some-> (some-get m ks) id-string))

(defn- keyword-key
  [value]
  (when (some? value)
    (let [s (-> (str value)
                str/lower-case
                (str/replace #"^:" "")
                (str/replace "_" "-"))]
      (when-not (str/blank? s) (keyword s)))))

(defn status-key
  "Normalize a status, task state, role, phase, or reason to a keyword."
  [value]
  (keyword-key value))

(defn recovery-reason-label
  "Describe REASON as text.  An unknown reason stays visible instead of
  disappearing from the barrier explanation."
  [reason]
  (let [k (status-key reason)]
    (or (get recovery-reason-labels k)
        (when k (str "未知恢复原因 " (name k)))
        "未知恢复原因")))

(defn- id-list
  "Normalize a collection of IDs.  A map is read by its keys, because
  `:wait-for` may arrive as {task-id reason}."
  [value]
  (cond
    (nil? value) []
    (map? value) (vec (keep id-string (keys value)))
    (set? value) (vec (keep id-string value))
    (sequential? value) (vec (keep id-string value))
    :else (cond-> [] (id-string value) (conj (id-string value)))))

(defn- collection
  "Return a vector of entity maps from a table, a sequence, or a single map."
  [value]
  (cond
    (nil? value) []
    (map? value) (if (some #(contains? value %)
                           [:id :task-id :swarm-id :agent-id :action-id
                            :slot-id :holder-id :held? :queue])
                   [value]
                   (vec (vals value)))
    (sequential? value) (vec value)
    (set? value) (vec value)
    :else [value]))

(defn- index-by
  [entities id-fn]
  (reduce (fn [m entity]
            (if-let [id (id-fn entity)]
              (assoc m id entity)
              m))
          {}
          entities))

(defn- sort-key
  [entity]
  [(or (get* entity :created-version)
       (get* entity :created-seq)
       0)
   (or (id-string (some-get entity [:id :task-id :action-id :agent-id :slot-id]))
       "")])

;; ---------------------------------------------------------------------------
;; Status and lifecycle labels

(defn status-label
  [value]
  (get status-labels (status-key value) "未知"))

(defn status-detail
  [value]
  (get status-details (status-key value) "服务器上报了未知状态。"))

(defn status-tone
  "A presentation tone.  Text is always rendered too; color is never the only
  carrier of status."
  [value]
  (case (status-key value)
    :running :positive
    :queued :pending
    :joined :positive
    :abandoned :warning
    :failed :negative
    :neutral))

(defn task-state-label
  [value]
  (get task-state-labels (status-key value) "未知"))

(defn task-state-tone
  [value]
  (case (status-key value)
    :ready :positive
    :waiting :pending
    :done :neutral
    :neutral))

(defn role-label
  [value]
  (get role-labels (status-key value) "未知角色"))

(defn life-state-label
  [value]
  (get life-state-labels (status-key value) "未知生命状态"))

(defn- actor-reason
  [actor]
  (some-get actor [:death-reason :retire-reason :exit-reason :handoff-reason]))

(defn- lifecycle
  "Explain a slot's life state without turning normal token rotation into a
  crash alert."
  [actor]
  (let [role (status-key (:role actor))
        life (status-key (:life-state actor))
        reason (status-key (actor-reason actor))]
    (cond
      (= :alive life)
      {:label (cond
                (= :holder role) "存活 — 令牌持有者"
                (nil? role) "存活 — 角色未上报"
                :else "存活 — 工人")
       :description (cond
                      (= :holder role)
                      "该个体持有 worktree 令牌。仅在运行托管 shell 或应用提交时才占用 W。"

                      (nil? role)
                      "该个体存活，但服务器未上报其工人/持有者角色。"

                      :else
                      "该个体存活，可以认领普通任务。它不持有 worktree 令牌。")
       :tone :positive
       :normal? true
       :attention? false}

      (= :dead life)
      (cond
        (contains? #{:attempt-lost :competition-lost} reason)
        {:label "已退出 — 令牌竞争落败"
         :description "败者在同一看板事务中转交剩余责任后退出。该槽位将生成新的 agent-id；这是正常的生命周期轮换。"
         :tone :neutral
         :normal? true
         :attention? false}

        (or (= :holder role)
            (contains? #{:retired-empty-holder :responsibility-empty
                         :holder-retired} reason))
        {:label "已退出 — 持有者退役"
         :description "R(holder) 变为空，持有者在同一事务中释放令牌并退出。该槽位将生成新的 agent-id；这是正常的生命周期轮换。"
         :tone :neutral
         :normal? true
         :attention? false}

        (contains? failure-lifecycle-reasons reason)
        {:label "已退出 — 失败"
         :description "该个体以意外失败结束。请查看交接备注与最近的看板事件；不要把该槽位报告为健康。"
         :tone :negative
         :normal? false
         :attention? true}

        :else
        {:label "已退出 — 正常生命周期"
         :description "逻辑退出；该槽位将生成新的 agent-id。仅凭退出状态并不意味着崩溃。"
         :tone :neutral
         :normal? true
         :attention? false})

      (= :terminated life)
      (cond
        (contains? failure-lifecycle-reasons reason)
        {:label "已终止 — 失败"
         :description "执行以意外失败结束。请查看交接备注与最近的看板事件。"
         :tone :negative
         :normal? false
         :attention? true}

        (contains? #{:operator-cancel :cancelled :stopped :normal :handoff} reason)
        {:label "已终止 — 主动终止"
         :description "执行被主动终止，例如操作员取消。这不是崩溃。"
         :tone :warning
         :normal? true
         :attention? false}

        :else
        {:label "已终止 — 原因未上报"
         :description "执行已结束，但服务器未上报这是主动终止还是失败。没有证据不要标为崩溃。"
         :tone :warning
         :normal? false
         :attention? true})

      :else
      {:label "未知生命状态"
       :description "服务器上报了本客户端无法识别的生命状态。"
       :tone :warning
       :normal? false
       :attention? true})))

;; ---------------------------------------------------------------------------
;; Task normalization and hierarchy

(defn- normalize-task
  [task]
  (when-let [id (entity-id task [:task-id :id])]
    {:task-id id
     :parent-id (entity-id task [:parent-id])
     :child-ids (id-list (get* task :child-ids))
     :wait-for (id-list (get* task :wait-for))
     :ledger-key (get* task :ledger-key)
     :goal (or (get* task :goal) "")
     :remaining-work (or (get* task :remaining-work) "")
     :done-when (or (get* task :done-when) "")
     :state (status-key (get* task :state))
     :needs-token (boolean (get* task :needs-token))
     :owner-id (entity-id task [:owner-id])
     :claim-id (entity-id task [:claim-id])
     :handoff-note (get* task :handoff-note)
     :rev (get* task :rev)
     :created-version (get* task :created-version)
     :done-at-version (get* task :done-at-version)
     :swarm-id (entity-id task [:swarm-id])
     :worktree (get* task :worktree)}))

(defn task-index
  "Normalize TASKS and index them by task-id."
  [tasks]
  (index-by (keep normalize-task (collection tasks)) :task-id))

(defn- task-children
  [tasks task]
  (let [declared (:child-ids task)
        derived (->> (vals tasks)
                     (filter #(= (:task-id task) (:parent-id %)))
                     (sort-by sort-key)
                     (map :task-id))
        ids (distinct (concat declared derived))]
    ids))

(defn- wait-nodes
  [tasks task seen depth]
  (if (>= depth 32)
    [{:task-id nil
      :goal "等待链已截断"
      :state nil
      :missing? false
      :cycle? false
      :truncated? true
      :children []}]
    (mapv (fn [wait-id]
            (let [target (get tasks wait-id)
                  cycle? (contains? seen wait-id)]
              {:task-id wait-id
               :goal (:goal target)
               :state (:state target)
               :missing? (nil? target)
               :cycle? cycle?
               :children (if (and target (not cycle?))
                           (wait-nodes tasks target (conj seen wait-id) (inc depth))
                           [])}))
          (:wait-for task))))

(defn wait-chain
  "Return the concrete :wait-for chain for TASK as nested data.

  A missing target is kept as a visible node; a cycle is marked and not
  followed again."
  ([tasks task]
   (let [index (if (map? tasks) tasks (task-index tasks))
         task (normalize-task task)]
     (wait-nodes index task #{(:task-id task)} 0))))

(declare task-state-detail)

(defn- task-node
  [tasks task seen depth]
  (let [child-ids (task-children tasks task)
        cycle? (contains? seen (:task-id task))
        children (if (or cycle? (>= depth 64))
                   []
                   (mapv (fn [child-id]
                           (if-let [child (get tasks child-id)]
                             (task-node tasks child (conj seen (:task-id task)) (inc depth))
                             {:task-id child-id
                              :missing? true
                              :children []
                              :wait-chain []}))
                         child-ids))
        node (assoc task
                    :children children
                    :wait-chain (wait-nodes tasks task (conj seen (:task-id task)) 0)
                    :cycle? cycle?
                    :truncated? (>= depth 64))]
    (assoc node :state-detail (task-state-detail node))))

(defn- all-children-done?
  [node]
  (and (seq (:children node))
       (every? #(= :done (:state %)) (:children node))))

(defn task-state-detail
  "Explain the task state, including the difference between a released wait
  and a parent that is done.

  A ready parent whose children are all done is still ready.  It is never
  reported as done; the remaining work must be completed explicitly."
  [node]
  (case (status-key (:state node))
    :waiting
    (if (seq (:wait-for node))
      (str "等待 " (count (:wait-for node)) " 个已登记任务。")
      "处于等待但没有已登记的 wait-for；请检查看板中的不一致等待。")

    :ready
    (cond
      (seq (:wait-for node))
      "就绪，但 wait-for 仍列有依赖。等待登记已过期；该任务并未处于等待。"

      (all-children-done? node)
      "等待已解除 — 子任务全部完成。该父任务就绪等待其剩余工作，不会自动标记完成。"

      :else
      "就绪，可认领或显式完成。")

    :done
    "已完成 — 显式完成。子任务完成不会自动关闭父任务。"

    "未知任务状态。"))

(defn- reachable-task-ids
  [nodes]
  (letfn [(walk [node]
            (into #{(:task-id node)}
                  (mapcat walk (:children node))))]
    (set (mapcat walk nodes))))

(defn task-tree
  "Build the complete root-task forest from TASKS.

  Roots are tasks with no parent, plus orphans whose parent is not present.
  Children are ordered by creation version and ID.  Missing children remain
  visible as placeholder nodes.  Cycles are marked and not followed again; a
  cycle that cannot be reached from a root is surfaced as its own root instead
  of disappearing from the board."
  [tasks]
  (let [index (task-index tasks)
        roots (->> (vals index)
                   (filter (fn [task]
                             (let [parent (:parent-id task)]
                               (or (nil? parent)
                                   (not (contains? index parent))))))
                   (sort-by sort-key))
        roots (mapv (fn [root]
                      (let [node (task-node index root #{} 0)]
                        (assoc node
                               :root? true
                               :orphan? (boolean (and (:parent-id root)
                                                      (not (contains? index (:parent-id root))))))))
                    roots)
        reachable (reachable-task-ids roots)
        unreachable (->> (vals index)
                         (remove #(contains? reachable (:task-id %)))
                         (sort-by sort-key)
                         (mapv (fn [task]
                                 (assoc (task-node index task #{} 0)
                                        :root? true
                                        :cycle-root? true
                                        :orphan? false))))]
    (into roots unreachable)))

(defn root-counts
  "Count root tasks by state.  The total is the number of roots, not all tasks."
  [roots]
  (let [states (frequencies (map :state roots))]
    {:ready (get states :ready 0)
     :waiting (get states :waiting 0)
     :done (get states :done 0)
     :total (count roots)}))

;; ---------------------------------------------------------------------------
;; Actions, actors, slots, holder, and W

(defn- normalize-action
  [action]
  (when-let [id (entity-id action [:action-id :id])]
    {:action-id id
     :task-id (entity-id action [:task-id])
     :agent-id (entity-id action [:agent-id :actor-id :actor])
     :claim-id (entity-id action [:claim-id])
     :kind (status-key (get* action :kind))
     :phase (status-key (get* action :phase))
     :request-id (id-string (get* action :request-id))
     :swarm-id (entity-id action [:swarm-id])
     :worktree (get* action :worktree)}))

(defn action-index
  [actions]
  (index-by (keep normalize-action (collection actions)) :action-id))

(defn- normalize-actor
  [actor]
  (when-let [id (entity-id actor [:agent-id :id])]
    {:agent-id id
     :slot-id (entity-id actor [:slot-id])
     :role (status-key (or (get* actor :role)
                           (when (get* actor :holder?) :holder)))
     :life-state (status-key (get* actor :life-state))
     :active-action-id (entity-id actor [:active-action-id])
     :handoff-note (get* actor :handoff-note)
     :generation (get* actor :generation)
     :created-version (get* actor :created-version)
     :death-reason (get* actor :death-reason)
     :retire-reason (get* actor :retire-reason)
     :exit-reason (get* actor :exit-reason)
     :handoff-reason (get* actor :handoff-reason)
     :swarm-id (entity-id actor [:swarm-id])
     :worktree (get* actor :worktree)}))

(defn actor-index
  [actors]
  (index-by (keep normalize-actor (collection actors)) :agent-id))

(defn- actor-sort
  [a b]
  (let [live-a (if (= :alive (:life-state a)) 0 1)
        live-b (if (= :alive (:life-state b)) 0 1)]
    (if (not= live-a live-b)
      (compare live-a live-b)
      (compare (or (:generation b) 0) (or (:generation a) 0)))))

(defn- normalize-slot
  [slot]
  (when-let [id (entity-id slot [:slot-id :id])]
    {:slot-id id
     :agent-id (entity-id slot [:agent-id :id])
     :role (status-key (or (get* slot :role)
                           (when (get* slot :holder?) :holder)))
     :life-state (status-key (get* slot :life-state))
     :handoff-note (get* slot :handoff-note)
     :active-action-id (entity-id slot [:active-action-id])
     :generation (get* slot :generation)}))

(defn slot-rows
  "One row per fixed slot, with the current live actor when one exists.

  Explicit slot records are shown even when no actor has occupied them yet.
  A dead actor is shown with a lifecycle explanation, not as a crash.  A token
  holder that is not present in the actor or slot tables is surfaced as an
  unknown slot rather than silently omitted."
  ([actors token-slot]
   (slot-rows actors nil token-slot))
  ([actors slots token-slot]
   (let [actors (sort actor-sort (vals (actor-index actors)))
         by-slot (group-by :slot-id actors)
         explicit (into {} (map (juxt :slot-id identity)
                                (keep normalize-slot (collection slots))))
         slot-ids (sort (distinct (concat (keys explicit) (keys by-slot))))
         holder (entity-id token-slot [:holder-id])
         rows (for [slot-id slot-ids
                    :let [slot-actors (get by-slot slot-id)
                          slot-record (get explicit slot-id)
                          current (first (filter #(= :alive (:life-state %))
                                                 slot-actors))
                          latest (first slot-actors)
                          actor (or current latest)
                          slot-agent-id (:agent-id slot-record)
                          vacant? (and (nil? actor) (nil? slot-agent-id))
                          role (:role (or actor slot-record))
                          life-state (:life-state (or actor slot-record))
                          lifecycle (cond
                                      actor
                                      (lifecycle actor)

                                      vacant?
                                      {:label "空缺"
                                       :description "当前没有个体占用该固定槽位。准入工作时运行时将生成新的 agent-id。"
                                       :tone :neutral
                                       :normal? true
                                       :attention? false}

                                      :else
                                      {:label "个体状态未上报"
                                       :description "槽位指名了一个个体，但个体表中没有其生命状态。不要假设槽位空缺或该个体存活。"
                                       :tone :warning
                                       :normal? false
                                       :attention? true})]]
                {:slot-id slot-id
                 :agent-id (or (:agent-id actor) slot-agent-id)
                 :role role
                 :role-label (role-label role)
                 :life-state life-state
                 :life-state-label (if life-state
                                     (life-state-label life-state)
                                     (if vacant? "空缺" "未上报"))
                 :lifecycle lifecycle
                 :handoff-note (:handoff-note (or actor slot-record))
                 :active-action-id (:active-action-id (or actor slot-record))
                 :generation (:generation (or actor slot-record))
                 :current? (boolean current)
                 :vacant? vacant?
                 :actors slot-actors
                 :attention? (or (:attention? lifecycle)
                                 (and (nil? actor) (some? slot-agent-id))
                                 (and (:active-action-id actor)
                                      (not= :alive (:life-state actor))))})
         holder-present? (or (some #(= holder (:agent-id %)) actors)
                             (some #(= holder (:agent-id %)) (vals explicit)))]
     (cond-> (vec rows)
       (and holder (not holder-present?))
       (conj {:slot-id nil
              :agent-id holder
              :role :holder
              :role-label (role-label :holder)
              :life-state nil
              :life-state-label "未上报"
              :lifecycle {:label "持有者不在任何已上报槽位上"
                          :description "令牌槽位指名的持有者不在个体表中。不要假设持有者存活或槽位空缺。"
                          :tone :warning
                          :normal? false
                          :attention? true}
              :handoff-note nil
              :active-action-id nil
              :generation nil
              :current? false
              :vacant? false
              :actors []
              :unknown-slot? true
              :attention? true})))))

(defn- responsibility
  "Compute R(holder) from tasks and actions when the server did not send it."
  [tasks actions holder]
  (when holder
    (let [task-values (vals tasks)
          action-values (vals actions)]
      (set (concat
            (for [task task-values
                  :when (and (not= :done (:state task))
                             (not (:needs-token task))
                             (= holder (:owner-id task)))]
              (:task-id task))
            (for [task task-values
                  :when (and (not= :done (:state task))
                             (:needs-token task))]
              (:task-id task))
            (for [action action-values
                  :when (and (= holder (:agent-id action))
                             (contains? active-action-phases (:phase action)))]
              (:action-id action)))))))

(defn- responsibility-row
  [tasks actions id]
  (if-let [task (get tasks id)]
    {:id id
     :kind :task
     :task-id id
     :goal (:goal task)
     :state (:state task)
     :needs-token (:needs-token task)
     :owner-id (:owner-id task)}
    (if-let [action (get actions id)]
      {:id id
       :kind :action
       :action-id id
       :task-id (:task-id action)
       :phase (:phase action)
       :action-kind (:kind action)}
      {:id id
       :kind :unknown
       :missing? true})))

(defn- holder-explanation
  [{:keys [holder-id responsibility responsibility-reported?
           shell-active? responsibility-rows]}]
  (cond
    (nil? holder-id)
    "没有令牌持有者。令牌槽位空闲；下一次 take-or-die 尝试可能胜出。"

    (and responsibility-reported? (empty? responsibility))
    "R(holder) = ∅。持有者应在同一事务中释放令牌并退出。若该状态持续存在，请检查看板；这是一个不一致。"

    (and (not responsibility-reported?) (empty? responsibility))
    "服务器未上报 R(holder)；看板当前显示没有未完成的责任。这不能证明持有者可以退役。"

    shell-active?
    "持有者正在执行托管 shell 动作，因此持有 W。仅持有令牌并不占用 W。"

    :else
    (str "R(holder) 不为空："
         (str/join "; "
                   (cond-> []
                     (some #(and (= :task (:kind %))
                                 (not (:needs-token %)))
                           responsibility-rows)
                     (conj "持有者名下未完成的普通任务")

                     (some #(and (= :task (:kind %))
                                 (:needs-token %))
                           responsibility-rows)
                     (conj "未完成的令牌任务")

                     (some #(= :action (:kind %)) responsibility-rows)
                     (conj "处于 applying/running/uncertain 的动作")))
         "。持有令牌不等于持有 W；在全部责任集合清空前，持有者不会退役。")))

(defn holder-view
  "Describe the worktree token slot, its holder, and the complete R(holder).

  When the server sends `:responsibility`, that set is authoritative.  When it
  does not, the projection computes it from the board and marks it computed."
  [tasks actions token-slot]
  (let [tasks (if (map? tasks) tasks (task-index tasks))
        actions (if (map? actions) actions (action-index actions))
        reported? (if (contains? token-slot :reported?)
                    (boolean (:reported? token-slot))
                    (boolean (and (map? token-slot) (seq token-slot))))
        responsibility-reported?
        (if (contains? token-slot :responsibility-reported?)
          (boolean (:responsibility-reported? token-slot))
          (boolean (and (map? token-slot)
                        (or (contains? token-slot :responsibility)
                            (contains? token-slot "responsibility")))))
        holder-id (entity-id token-slot [:holder-id])
        term-id (get* token-slot :term-id)
        state (status-key (get* token-slot :state))
        reported (id-list (get* token-slot :responsibility))
        computed (responsibility tasks actions holder-id)
        responsibility (if responsibility-reported? (set reported) computed)
        rows (mapv #(responsibility-row tasks actions %) (sort responsibility))
        shell-active? (some #(and (= :action (:kind %))
                                  (= :shell (:action-kind %))
                                  (contains? #{:running :applying} (:phase %)))
                            rows)
        ordinary-count (count (filter #(and (= :task (:kind %))
                                            (not (:needs-token %)))
                                      rows))
        token-count (count (filter #(and (= :task (:kind %))
                                         (:needs-token %))
                                   rows))
        action-count (count (filter #(= :action (:kind %)) rows))
        reasons (cond-> []
                  (pos? ordinary-count)
                  (conj (str "持有者名下有 " ordinary-count " 个未完成的普通任务"))
                  (pos? token-count)
                  (conj (str "有 " token-count " 个未完成的令牌任务"))
                  (pos? action-count)
                  (conj (str "有 " action-count " 个处于 applying/running/uncertain 的活跃动作")))]
    {:reported? reported?
     :holder-id holder-id
     :term-id term-id
     :state state
     :responsibility responsibility
     :responsibility-rows rows
     :responsibility-count (count responsibility)
     :responsibility-reasons reasons
     :responsibility-reported? responsibility-reported?
     :computed? (not responsibility-reported?)
     :empty? (and reported? responsibility-reported? (empty? responsibility))
     :shell-active? (boolean shell-active?)
     :inconsistent? (and reported? responsibility-reported?
                        holder-id (empty? responsibility))
     :explanation (if reported?
                    (holder-explanation {:holder-id holder-id
                                         :responsibility responsibility
                                         :responsibility-reported?
                                         responsibility-reported?
                                         :responsibility-rows rows
                                         :shell-active? shell-active?})
                   "服务器未上报该 worktree 的令牌槽位。持有者、term 与 R(holder) 均未知。")}))

(defn- queue-item
  [actions item]
  (cond
    (map? item) (or (normalize-action item)
                    {:action-id nil :missing? true})
    (some? item) (or (get actions (id-string item))
                     {:action-id (id-string item) :missing? true})
    :else {:action-id nil :missing? true}))

(defn- normalize-recovery-write-gate
  "Keep the old gate evidence readable whether the wire used keyword or
  string keys.  The original map is retained; normalized keys are added."
  [value]
  (when (map? value)
    (assoc value
           :held? (boolean (get* value :held?))
           :owner (id-string (get* value :owner))
           :queue (get* value :queue)
           :outcome-unknown? (boolean (get* value :outcome-unknown?))
           :queue-abandoned? (boolean (get* value :queue-abandoned?))
           :reason (some-> (get* value :reason) status-key)
           :recovered-at (get* value :recovered-at)
           :previous (get* value :previous))))

(defn- normalize-recovery-acknowledgement
  "Keep the persisted acknowledgement fields readable to the view.  The
  original map is retained; normalized keys are added."
  [value]
  (when (map? value)
    (assoc value
           :acknowledged? (boolean (get* value :acknowledged?))
           :actor-id (id-string (get* value :actor-id))
           :evidence (some-> (get* value :evidence) str)
           :write-gate-held? (let [held? (get* value :write-gate-held?)]
                               (when (some? held?) (boolean held?)))
           :at-version (get* value :at-version))))

(defn- write-gate-mode-label
  "Label for the W mode.  `mode` is produced by a closed `cond` below; the
  default is the defensive fallback for a future mode."
  [mode]
  (case mode
    :unreported "W 状态未上报"
    :recovery "W 为恢复所持 — 存在结果未知动作未结清"
    :applying "W 持有中 — 正在应用受保护批次"
    :shell "W 持有中 — 托管 shell 运行中"
    :write "W 持有中 — 受保护写入进行中"
    :idle "W 空闲"
    "W 状态未知"))

(defn write-gate-view
  "Describe W for one worktree: held or free, current action, and the FIFO
  queue.  A missing W report is shown as unreported; it is never rendered as
  free.  Recovery evidence is passed through with a text state, reason labels,
  old-gate evidence, and the persisted acknowledgement."
  [write-gate actions token-slot]
  (let [actions (if (map? actions) actions (action-index actions))
        holder (entity-id token-slot [:holder-id])
        reported? (some? write-gate)
        recovery-blocked? (boolean (get* write-gate :recovery-blocked?))
        recovery-actions (id-list (get* write-gate :recovery-actions))
        recovery-reasons (vec (keep #(some-> % status-key)
                                    (collection (get* write-gate :recovery-reasons))))
        recovery-reason-labels (mapv recovery-reason-label recovery-reasons)
        recovery-write-gate (normalize-recovery-write-gate
                             (get* write-gate :recovery-write-gate))
        recovery-acknowledgement (normalize-recovery-acknowledgement
                                  (get* write-gate :recovery-acknowledgement))
        recovery-state (cond
                         recovery-blocked? :blocked
                         (true? (:acknowledged? recovery-acknowledgement))
                         :acknowledged
                         :else :none)
        held? (boolean
               (or (true? (get* write-gate :held?))
                   (get* write-gate :held)
                   (contains? #{:held :locked :busy}
                              (status-key (get* write-gate :state)))
                   (some? (some-get write-gate [:owner :owner-agent-id]))))
        current-id (entity-id write-gate [:current-action :owner-action-id
                                          :action-id])
        current-raw (get* write-gate :current-action)
        current (cond
                  (map? current-raw) (or (normalize-action current-raw)
                                         {:missing? true})
                  current-id (or (get actions current-id)
                                 {:action-id current-id :missing? true})
                  :else nil)
        owner (or (entity-id write-gate [:owner :owner-agent-id])
                  (:agent-id current))
        queue-raw (or (get* write-gate :queue) (get* write-gate :queued))
        queue-count (if (number? queue-raw) queue-raw (count (collection queue-raw)))
        queue (if (number? queue-raw)
                []
                (mapv #(queue-item actions %) (collection queue-raw)))
        mode (cond
               (not reported?) :unreported
               recovery-blocked? :recovery
               (and held? (= :applying (:phase current))) :applying
               (and held? (= :running (:phase current))
                    (= :shell (:kind current))) :shell
               held? :write
               :else :idle)
        mode-label (write-gate-mode-label mode)
        explanation (case mode
                      :unreported
                      "服务器未上报该 worktree 的 W。动作阶段单独显示；不要推断 W 空闲。"

                      :recovery
                      (str "W 被恢复屏障持有"
                           (when (seq recovery-reason-labels)
                             (str "，原因："
                                  (str/join "; " recovery-reason-labels)))
                           (when (seq recovery-actions)
                             (str "；未结清动作："
                                  (str/join ", " (map str recovery-actions))))
                           "。新的受保护写入将被 :RECOVERY_UNRESOLVED 拒绝。请检查看板 worktree，用明确证据结清每个结果未知的动作，然后确认恢复。")

                      :idle
                      "W 空闲。当前没有受保护提交或托管 shell 在应用。"

                      :applying
                      (str "W 由 " (or owner "未知个体")
                           " 在应用受保护批次时持有"
                           (when-let [id (:action-id current)] (str "（动作 " id "）"))
                           "。看板应显示 :applying；其他写者按 FIFO 排队，W 绝不因超时转移。")

                      :shell
                      (str "W 由 " (or owner "未知个体")
                           " 为托管 shell 持有"
                           (when-let [id (:action-id current)] (str "（动作 " id "）"))
                           "。其他写者按 FIFO 排队；W 绝不因超时转移。")

                      :write
                      (str "W 由 " (or owner "未知个体")
                           " 持有"
                           (when-let [id (:action-id current)] (str "（动作 " id "）"))
                           (when-let [phase (:phase current)] (str "（阶段 " (name phase) "）"))
                           "。")
                      "W 状态未知。")
        acknowledgement-text
        (when recovery-acknowledgement
          (str (if (true? (:acknowledged? recovery-acknowledgement))
                 "恢复已被确认"
                 "已上报恢复证据但未确认")
               (when-let [actor (:actor-id recovery-acknowledgement)]
                 (str "，操作员 " actor))
               (when-let [evidence (:evidence recovery-acknowledgement)]
                 (str "，证据：" evidence))
               (when-let [version (:at-version recovery-acknowledgement)]
                 (str "，看板版本 " version))
               "."))
        queued-text (cond
                      (not reported?) "队列状态未知。"
                      (pos? queue-count)
                      (str "有 " queue-count " 个写者在 W 的 FIFO 队列中等待。")
                      :else "没有写者在 W 的 FIFO 队列中等待。")
        holder-not-writing? (and reported? holder
                                  (not (and held? (= holder owner))))
        attention? (or (and reported? held? (nil? owner) (nil? current))
                       recovery-blocked?
                       (and reported? (not held?) (pos? queue-count)))]
    {:reported? reported?
     :held? held?
     :owner owner
     :current current
     :current-action-id (:action-id current)
     :queue queue
     :queued-count queue-count
     :mode mode
     :mode-label mode-label
     :explanation (str explanation
                       (when acknowledgement-text
                         (str " " acknowledgement-text))
                       " " queued-text)
     :queued-text queued-text
     :holder-id holder
     :holder-not-writing? (boolean holder-not-writing?)
     :w-held-by-holder? (boolean (and holder held? (= holder owner)))
     :recovery-blocked? recovery-blocked?
     :recovery-actions recovery-actions
     :recovery-reasons recovery-reasons
     :recovery-reason-labels recovery-reason-labels
     :recovery-state recovery-state
     :recovery-write-gate recovery-write-gate
     :recovery-acknowledgement recovery-acknowledgement
     :attention? (boolean attention?)}))

;; ---------------------------------------------------------------------------
;; Swarm and fleet rows

(defn- normalize-swarm
  [swarm]
  (when-let [id (entity-id swarm [:swarm-id :id])]
    {:swarm-id id
     :slice-id (or (id-string (get* swarm :slice-id))
                   (id-string (get* swarm :slice))
                   id)
     :worktree (or (get* swarm :worktree) (get* swarm :workspace))
     :status (status-key (or (get* swarm :status) :unknown))
     :fleet-reported? (if (contains? swarm :fleet-reported?)
                        (boolean (:fleet-reported? swarm))
                        true)
     :root-ready (get* swarm :root-ready)
     :root-waiting (get* swarm :root-waiting)
     :root-done (get* swarm :root-done)
     :queued-count (get* swarm :queued-count)
     :last-event (get* swarm :last-event)
     :master-version (get* swarm :master-version)
     :board-version (get* swarm :board-version)
     :report (get* swarm :report)
     :ff-commit (get* swarm :ff-commit)
     :rebase-rounds (get* swarm :rebase-rounds)
     :abandon-reason (get* swarm :abandon-reason)
     :error (get* swarm :error)
     :created-seq (get* swarm :created-seq)
     :completion-seq (get* swarm :completion-seq)
     :board (get* swarm :board)
     :swarm-order (get* swarm :swarm-order)}))

(defn- normalize-token-slot
  [token-slot]
  {:reported? (boolean (and (map? token-slot) (seq token-slot)))
   :responsibility-reported? (boolean
                              (and (map? token-slot)
                                   (or (contains? token-slot :responsibility)
                                       (contains? token-slot "responsibility"))))
   :holder-id (entity-id token-slot [:holder-id])
   :term-id (get* token-slot :term-id)
   :state (status-key (get* token-slot :state))
   :responsibility (id-list (get* token-slot :responsibility))
   :last-retirement (get* token-slot :last-retirement)})

;; The dynamic board resolver is written with explicit keys because each
;; table has a different context key and a different nested board key.
(defn- nested-value
  [board k]
  (when (map? board)
    (if (contains? board k)
      (get board k)
      (get board (key-string k)))))

(defn- find-for-swarm
  [entities swarm]
  (let [entities (collection entities)
        swarm-id (:swarm-id swarm)
        worktree (:worktree swarm)]
    (or (some #(when (= swarm-id (entity-id % [:swarm-id])) %) entities)
        (some #(when (and worktree (= worktree (get* % :worktree))) %) entities)
        (when (= 1 (count entities)) (first entities)))))

(defn- table-for
  [swarm ctx nested-key ctx-key]
  (let [nested (:board swarm)
        nested (or (nested-value nested nested-key)
                   (when (= :actors nested-key)
                     (nested-value nested :agents)))]
    (if (some? nested)
      (collection nested)
      (let [swarm-id (:swarm-id swarm)
            worktree (:worktree swarm)
            only-swarm? (= 1 (:swarm-count ctx))
            matches? (fn [entity]
                       (let [entity-swarm (entity-id entity [:swarm-id])
                             entity-worktree (get* entity :worktree)]
                         (cond
                           entity-swarm (= entity-swarm swarm-id)
                           entity-worktree (and worktree (= entity-worktree worktree))
                           only-swarm? true
                           :else false)))]
        (filterv matches? (collection (get ctx ctx-key)))))))

(defn- token-slot-for
  [swarm ctx]
  (let [nested (:board swarm)
        from-nested (nested-value nested :token-slot)
        from-table (find-for-swarm (get ctx :token-slots) swarm)]
    (cond
      (some? from-nested) (normalize-token-slot from-nested)
      (some? from-table) (normalize-token-slot from-table)
      :else (normalize-token-slot nil))))

(defn- write-gate-for
  [swarm ctx]
  (let [nested (:board swarm)
        from-nested (nested-value nested :write-gate)]
    (cond
      (some? from-nested) from-nested
      :else (find-for-swarm (get ctx :write-gates) swarm))))

(defn- declared-root-counts
  [swarm]
  (let [counts {:ready (:root-ready swarm)
                :waiting (:root-waiting swarm)
                :done (:root-done swarm)}]
    (when (some some? (vals counts))
      (into {} (map (fn [[k v]] [k (or v 0)]) counts)))))

(defn- counts-mismatch?
  [computed declared]
  (boolean
   (and declared
        (not= (select-keys computed [:ready :waiting :done])
              (select-keys declared [:ready :waiting :done])))))

(defn- join-queue-text
  [swarm]
  (cond
    (false? (:fleet-reported? swarm))
    "该服务器未上报 fleet 合入队列；下方看板记录为当前状态。"

    (nil? (:queued-count swarm))
    "合入队列计数未由服务器上报。"

    (and (= :joined (:status swarm)) (:report swarm))
    "已合入；完整报告见下方。"

    (and (= :joined (:status swarm)) (nil? (:report swarm)))
    "已合入，但服务器未包含报告文本。"

    (= :abandoned (:status swarm))
    (str "已放弃；没有结果进入合入队列。"
         (when-let [reason (:abandon-reason swarm)] (str " 原因：" reason)))

    (= :failed (:status swarm))
    "失败；没有结果进入合入队列。"

    (pos? (:queued-count swarm))
    (str "有 " (:queued-count swarm) " 个已完成结果在合入队列中等待。")

    :else
    "合入队列中没有已完成结果等待。"))

(defn swarm-view
  "Project one swarm and its board.  CTX holds the global normalized tables."
  [swarm ctx]
  (let [swarm (if (:swarm-id swarm) swarm (normalize-swarm swarm))
        tasks (task-index (table-for swarm ctx :tasks :tasks))
        actions (action-index (table-for swarm ctx :actions :actions))
        actors (actor-index (table-for swarm ctx :actors :actors))
        slots (table-for swarm ctx :slots :slots)
        token-slot (token-slot-for swarm ctx)
        write-gate (write-gate-for swarm ctx)
        tree (task-tree (vals tasks))
        computed-counts (root-counts tree)
        declared (declared-root-counts swarm)
        declared-only? (and (empty? tree) (some? declared))
        displayed-counts (if declared-only? declared computed-counts)
        holder (holder-view tasks actions token-slot)
        w (write-gate-view write-gate actions token-slot)
        status (:status swarm)
        attention? (boolean
                    (or (contains? #{:failed :abandoned} status)
                        (and (not declared-only?)
                             (counts-mismatch? computed-counts declared))
                        (:inconsistent? holder)
                        (:attention? w)
                        (some :attention? tree)))
        join-report (when (or (= :joined status) (some? (:report swarm)))
                      {:swarm-id (:swarm-id swarm)
                       :slice-id (:slice-id swarm)
                       :worktree (:worktree swarm)
                       :report (:report swarm)
                       :report-present? (some? (:report swarm))
                       :ff-commit (:ff-commit swarm)
                       :rebase-rounds (:rebase-rounds swarm)
                       :joined? (= :joined status)})]
    {:swarm-id (:swarm-id swarm)
     :slice-id (:slice-id swarm)
     :worktree (:worktree swarm)
     :status status
     :status-label (status-label status)
     :status-detail (status-detail status)
     :status-tone (status-tone status)
     :fleet-reported? (:fleet-reported? swarm)
     :root-ready (:root-ready swarm)
     :root-waiting (:root-waiting swarm)
     :root-done (:root-done swarm)
     :root-counts computed-counts
     :root-counts-displayed displayed-counts
     :root-counts-source (if declared-only? :declared :computed)
     :declared-root-counts declared
     :root-count-mismatch? (and (not declared-only?)
                                (counts-mismatch? computed-counts declared))
     :master-version (:master-version swarm)
     :board-version (:board-version swarm)
     :queued-count (:queued-count swarm)
     :join-queue-text (join-queue-text swarm)
     :last-event (:last-event swarm)
     :created-seq (:created-seq swarm)
     :completion-seq (:completion-seq swarm)
     :tasks tree
     :root-tasks (filterv :root? tree)
     :slots (slot-rows actors slots token-slot)
     :token-slot token-slot
     :holder holder
     :write-gate w
     :join-report join-report
     :report (:report swarm)
     :ff-commit (:ff-commit swarm)
     :rebase-rounds (:rebase-rounds swarm)
     :abandon-reason (:abandon-reason swarm)
     :error (:error swarm)
     :attention? attention?}))

;; ---------------------------------------------------------------------------
;; Snapshot and Fulcro database adapters

(defn- swarm-sort
  [a b]
  (let [seq-a (or (:created-seq a) (:swarm-order a) 0)
        seq-b (or (:created-seq b) (:swarm-order b) 0)]
    (if (not= seq-a seq-b)
      (compare seq-a seq-b)
      (compare (:swarm-id a) (:swarm-id b)))))

(defn- snapshot-swarms
  [snapshot]
  (let [candidates [(get* snapshot :swarms)
                    (get* snapshot :fleet/swarms)
                    (get* snapshot :swarm/by-id)
                    (get* snapshot :fleet/by-id)]]
    (or (some (fn [value]
                (cond
                  (sequential? value) (vec value)
                  (map? value) (vals value)
                  :else nil))
              candidates)
        [])))

(defn from-db
  "Turn a Fulcro-style database or dashboard snapshot into the projection input.

  The returned map is deliberately plain data: `dashboard` accepts it directly."
  [db]
  (if-let [dashboard (get* db :swarm/dashboard)]
    dashboard
    (let [swarms (snapshot-swarms db)
          tables {:tasks (collection (get* db :task/by-id))
                  :actions (collection (get* db :action/by-id))
                  :actors (concat (collection (get* db :agent/by-id))
                                  (collection (get* db :actor/by-id)))
                  :slots (collection (get* db :slot/by-id))
                  :token-slots (or (get* db :token-slot/by-id)
                                   (get* db :token-slot))
                  :write-gates (or (get* db :write-gate/by-id)
                                   (get* db :write-gate))
                  :swarm-count (count swarms)}]
      (merge {:swarms swarms
              :selected-swarm-id (or (get* db :ui/selected-swarm-id)
                                     (get* db :swarm/selected-id))
              :sessions (get* db :session/by-id)
              :active-session (get* db :session/active-id)
              :master-version (get* db :fleet/master-version)
              :board-version (get* db :board/version)}
             tables))))

(defn- unresolved-call-view
  "Project one server-published unknown call.

  The server publishes identity, role, stage and acknowledgement evidence
  only; the request and program source never reach the browser.  A missing
  acknowledgement is an open check, not a failure."
  [row]
  (let [ack (when (map? (:ack row)) (:ack row))
        outcome (some-> (:outcome ack) keyword)]
    {:call-id (id-string (:call-id row))
     :kind (some-> (:kind row) name)
     :role (some-> (:role row) name)
     :lane (some-> (:lane row) name)
     :tenure-id (id-string (:tenure-id row))
     :agent-id (id-string (:agent-id row))
     :member (some-> (:member row) id-string)
     :task-id (id-string (:task-id row))
     :phase (some-> (:phase row) name)
     :last-known-stage (some-> (:last-known-stage row) name)
     :outcome-unknown? (and (nil? ack)
                            (not (false? (:outcome-unknown? row))))
     :created-at (:created-at row)
     :ack (when ack
            {:outcome outcome
             :by (some-> (:by ack) name)
             :at (:at ack)
             :note (:note ack)})
     :ack-outcome outcome
     :ack-label (case outcome
                  :succeeded "已确认成功"
                  :not-occurred "已确认未发生"
                  nil)
     :ack-note (:note ack)
     :ack-at (:at ack)}))

(defn- active-session-row
  [input]
  (let [sessions (get* input :sessions)
        active-id (id-string (or (get* input :active-session)
                                 (get* input :session/active-id)))]
    (or (when (map? sessions)
          (or (get sessions active-id)
              (get sessions (some-> active-id keyword))
              (first (vals sessions))))
        {})))

(defn- worker-program-view
  "Project one protected worker-program result for the board.

  The server publishes identity, status, conflict paths and both stamps.
  The program source and request never reach the browser."
  [row]
  (let [status (some-> (:program-status row) keyword)
        outcome (some-> (:outcome-kind row) keyword)
        conflicts (mapv (fn [c]
                          {:path (:path c)
                           :reason (some-> (:reason c) name)
                           :expected (:expected c)
                           :actual (:actual c)})
                        (or (:conflicts row) []))]
    {:job-id (id-string (:job-id row))
     :task-id (id-string (:task-id row))
     :claim-id (id-string (:claim-id row))
     :agent-id (id-string (:agent-id row))
     :state (some-> (:state row) name)
     :outcome (some-> (:outcome row) name)
     :program-status (some-> status name)
     :outcome-kind (some-> outcome name)
     :file-changed? (:file-changed? row)
     :file-path (:file-path row)
     :conflicts conflicts
     :conflict? (boolean (seq conflicts))
     :applied? (contains? #{:APPLIED :applied} status)
     :error (:error row)
     :created-at (:created-at row)
     :label (cond
              (seq conflicts) "文件冲突，未写入"
              (contains? #{:APPLIED :applied} status) "已应用"
              (contains? #{:FILE_CONFLICT :file-conflict} status)
              "文件冲突，未写入"
              (contains? #{:PREPARE_REJECTED :prepare-rejected} status)
              "准备阶段拒绝"
              (some? (:error row)) "失败"
              :else (or (some-> status name) "结果未报告"))}))

(defn- summary
  [rows]
  (let [by-status (frequencies (map :status rows))
        roots (reduce (fn [acc row]
                        (merge-with + acc (:root-counts row)))
                      {:ready 0 :waiting 0 :done 0 :total 0}
                      rows)
        queued (reduce + 0 (keep :queued-count rows))
        unknown-queue (count (filter #(and (:fleet-reported? %)
                                           (nil? (:queued-count %)))
                                     rows))
        attention (count (filter :attention? rows))
        board-only (count (filter #(false? (:fleet-reported? %)) rows))
        queue-text (cond
                     (pos? board-only)
                     "该服务器未上报 fleet 合入队列。"

                     (pos? unknown-queue)
                     "部分 swarm 未上报合入队列计数。"

                     :else
                     (str "有 " queued " 个已完成结果在合入队列中等待。"))]
    {:swarms (count rows)
     :running (get by-status :running 0)
     :queued (get by-status :queued 0)
     :joined (get by-status :joined 0)
     :abandoned (get by-status :abandoned 0)
     :failed (get by-status :failed 0)
     :unknown (get by-status :unknown 0)
     :board-only board-only
     :root-ready (:ready roots)
     :root-waiting (:waiting roots)
     :root-done (:done roots)
     :root-total (:total roots)
     :join-queue queued
     :join-queue-unknown unknown-queue
     :attention attention
     :text (str (count rows) " 个 swarm 行："
                (get by-status :running 0) " 个运行中，"
                (get by-status :queued 0) " 个排队中，"
                (get by-status :joined 0) " 个已合入，"
                (get by-status :abandoned 0) " 个已放弃，"
                (get by-status :failed 0) " 个失败，"
                (get by-status :unknown 0) " 个无 fleet 状态。"
                "根任务：" (:ready roots) " 个就绪，"
                (:waiting roots) " 个等待中，" (:done roots) " 个已完成。"
                queue-text
                (when (pos? board-only)
                  (str " 其中 " board-only " 个仅看板行显示已同步的看板记录，无 fleet 投影。"))
                (when (pos? attention)
                  (str " " attention " 个 swarm 需要关注。")))}))

(defn- board-only-swarm
  "Build a display-only row for the current server shape when the snapshot has
  board records but no fleet projection.

  The row deliberately reports `:fleet-reported? false`, `:status :unknown`,
  and no join queue.  It does not invent a running swarm, worktree, master
  version, or report; it only gives the synchronized board records a place to
  render."
  [input]
  (let [token-slots (get* input :token-slots)
        token-key (when (map? token-slots) (first (keys token-slots)))
        token-slot (first (collection token-slots))
        swarm-id (or (id-string token-key)
                     (entity-id token-slot [:swarm-id])
                     (some #(entity-id % [:swarm-id])
                           (collection (get* input :tasks)))
                     "local")]
    {:swarm-id swarm-id
     :slice-id "仅看板"
     :worktree nil
     :status :unknown
     :fleet-reported? false
     :queued-count 0
     :master-version nil
     :board-version (get* input :board-version)}))

(defn- board-records?
  [input]
  (boolean
   (or (seq (collection (get* input :tasks)))
       (seq (collection (get* input :actions)))
       (seq (collection (get* input :actors)))
       (seq (collection (get* input :token-slots))))))

(defn dashboard
  "Build the complete fleet dashboard from a snapshot or normalized database.

  The selected swarm is the requested ID when present, otherwise the first
  running swarm, otherwise the first swarm.  No row is dropped because it is
  failed or abandoned."
  [snapshot]
  (let [input (if (or (contains? snapshot :swarms)
                      (contains? snapshot :swarm/dashboard))
                snapshot
                (from-db snapshot))
        input (if-let [nested (get* input :swarm/dashboard)]
                nested
                input)
        swarms (->> (snapshot-swarms input)
                    (map normalize-swarm)
                    (remove nil?)
                    (sort swarm-sort)
                    vec)
        swarms (if (and (empty? swarms) (board-records? input))
                 [(board-only-swarm input)]
                 swarms)
        ctx (assoc input :swarm-count (count swarms))
        rows (mapv #(swarm-view % ctx) swarms)
        requested (or (id-string (:selected-swarm-id input))
                      (id-string (get* input :ui/selected-swarm-id))
                      (id-string (get* input :swarm/selected-id)))
        selected (or (some #(when (= requested (:swarm-id %)) %) rows)
                     (some #(when (= :running (:status %)) %) rows)
                     (first rows))
        session-row (active-session-row input)
        unresolved (mapv unresolved-call-view
                         (or (:unresolved-calls session-row) []))
        open-calls (remove :ack unresolved)
        ;; Protected-commit results belong to the board, not only to the
        ;; currently selected session.  A worker may finish after the operator
        ;; switched sessions, so the dashboard merges published rows.  Keep
        ;; the newest 20: the board is a working view, and an unbounded merge
        ;; over 1,000 sessions would copy every historical job into the
        ;; browser on each render.
        all-programs (->> (get* input :sessions)
                          vals
                          (mapcat #(or (:worker-programs %) []))
                          (sort-by :created-at)
                          (take-last 20)
                          vec)
        worker-programs (mapv worker-program-view all-programs)]
    {:swarms rows
     :selected-swarm-id (:swarm-id selected)
     :selected selected
     :session-id (id-string (or (:session-id session-row)
                                (get* input :active-session)))
     :unresolved-calls unresolved
     :unresolved-summary {:total (count unresolved)
                          :open (count open-calls)
                          :acknowledged (count (filter :ack unresolved))}
     :worker-programs worker-programs
     :worker-program-summary
     {:total (count worker-programs)
      :conflicts (count (filter :conflict? worker-programs))
      :applied (count (filter :applied? worker-programs))}
     :summary (summary rows)
     :master-version (or (:master-version input)
                         (some->> rows (keep :master-version) seq (apply max)))
     :empty? (empty? rows)}))

(defn dashboard-from-db
  "Convenience alias for the Fulcro adapter."
  [db]
  (dashboard (from-db db)))
