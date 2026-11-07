# Swarm / Materials 跨目录 patch 计划

状态：待应用。本文只记录精确 patch，不表示源码已修改。

归属约定：

- `client-workbench`：`src/hc/client/workbench/**` 与客户端路由/会话发送路径。
- `server-content`：`src/hc/server/materials/**` 与 `src/hc/server/app/system.clj` 的材料上下文接线。

共 3 个顶层项、4 个 patch：

1. `session.cljs` P1-2（client-workbench）
2. `server materials` P1-1（server-content）
3. `server materials` P1-3（server-content）
4. `workbench/model.cljc` P1-6 路由别名（client-workbench）

---

## 1. `session.cljs` P1-2：发送前检查指令卡冲突与必填占位符

**目标文件:行**

- `src/hc/client/workbench/views/session.cljs:17`（require）
- `src/hc/client/workbench/views/session.cljs:267-268`（`session-page` let 绑定）
- `src/hc/client/workbench/views/session.cljs:284-293`（`:send!`）

**现状**

`session-page` 的 `:send!` 只检查搜索选择错误；材料页的
`selection-format-conflict` 与 `missing-required-placeholders` 警告只在
“材料与扩展”页签可见。用户在会话输入区直接发送时，互斥指令卡或缺少必填占位符的
卡片仍会进入 `:send-message`，只能等服务端拒绝或产生非预期输出。

**修改**

在 `session-page` 读取当前 `:ui/materials`，发送前用已启用卡计算冲突与缺失占位符；
命中时 `show-notice!` 并停止，不改变草稿、附件或 `:ui/materials`。搜索检查保持第一优先级。

```diff
*** Update File: src/hc/client/workbench/views/session.cljs
@@
             [hc.client.conversation.stream :as stream]
             [hc.client.materials :as materials]
+            [hc.client.materials.model :as materials-model]
             [hc.client.search.model :as search-model]
@@
         draft (get (:ui/draft props) selected-id)
+        materials-state (or (:ui/materials db) (materials/initial-state))
         connection (:ui/connection props)
@@
         callbacks {:send! (fn [{:keys [session-id snapshot]}]
-                            (if (and (not= :off (:mode search-state)) search-error)
-                              (commands/show-notice! this :warn search-error)
-                              (commands/send-snapshot!
-                               this session-id
-                               (assoc (materials/attach-turn-selection
-                                       (or (:ui/materials db) (materials/initial-state))
-                                       snapshot)
-                                      :search (search-model/command-payload
-                                               search-state (:text snapshot))))))
+                            (let [conflict (materials-model/selection-format-conflict
+                                            materials-state)
+                                  missing (materials-model/missing-required-placeholders
+                                           materials-state)]
+                              (cond
+                                (and (not= :off (:mode search-state)) search-error)
+                                (commands/show-notice! this :warn search-error)
+
+                                (seq conflict)
+                                (commands/show-notice!
+                                 this :warn
+                                 (str "指令卡格式冲突："
+                                      (str/join " / " (map name conflict))
+                                      "。请只保留一种输出格式。"))
+
+                                (seq missing)
+                                (commands/show-notice!
+                                 this :warn
+                                 (str "必填占位符尚未提供："
+                                      (str/join
+                                       "；"
+                                       (map (fn [{:keys [card-name key]}]
+                                              (str card-name " / " key))
+                                            missing))
+                                      "。"))
+
+                                :else
+                                (commands/send-snapshot!
+                                 this session-id
+                                 (assoc (materials/attach-turn-selection
+                                         materials-state snapshot)
+                                        :search (search-model/command-payload
+                                                 search-state
+                                                 (:text snapshot)))))))
                    :stop! (fn [{:keys [session-id]}]
```

**测试**

- 应用后新增 `test/hc/client/workbench/session_send_guard_test.cljs`（或现有会话 DOM 测试）：
  1. 在 `:ui/materials` 放两张启用且输出格式为 `:text`/`:json` 的卡，点击发送，断言
     `show-notice!` 被调用且 `send-snapshot!` 未被调用。
  2. 放一张有必填占位符但无值的卡，同样断言发送被拦截。
  3. 修复后再次发送，断言 `send-snapshot!` 收到原始草稿与附件，且 `:ui/materials`
     未被清空。
- 现有 `clojure -M:test -n hc.client.materials.model-test` 已覆盖
  `selection-format-conflict`、`missing-required-placeholders` 的纯逻辑。
- 建议把条件抽成纯函数 `send-blocker` 再单测，避免只能通过 Fulcro 组件测试覆盖。

**归属**：`client-workbench`。

---

## 2. `server materials` P1-1：快照携带 `worldbook-session` 的有界 `:omitted`

**目标文件:行**

- `src/hc/server/materials/session.clj:285-333`（`snapshot`）
- 新增 helper 放在 `snapshot` 之前，建议在 `session.clj:284` 附近。

**现状**

`snapshot` 对 `:worldbook-session` 只投影 `[:turn :runtime]`：

```clojure
(assoc :worldbook-session
       (select-keys (get-in state [:worldbook :sessions session-id])
                    [:turn :runtime]))
```

`worldbook/run-turn` 实际把最近一轮的 `:last-turn`（含 `:result :omitted`）写入
`:worldbook :sessions <id>`，但快照丢弃了它。客户端新的
`worldbook-omitted-rows` 因此只能显示“服务器未报告”，无法展示预算/冷却导致未注入的条目。

**修改**

新增两个纯 helper，并在 `snapshot` 中用 `worldbook-session-view` 替换
`select-keys`。只输出有界（最多 50 条）的审查字段，不输出正文或整轮结果。

```diff
*** Update File: src/hc/server/materials/session.clj
@@
-(defn snapshot
+(defn- omitted-entry-view
+  [item]
+  (select-keys item [:entry-id :book-id :title :reason :chars]))
+
+(defn- worldbook-session-view
+  [state session-id]
+  (let [wb-session (get-in state [:worldbook :sessions session-id])
+        last-turn (:last-turn wb-session)
+        omitted (or (:omitted last-turn)
+                    (get-in last-turn [:result :omitted]))]
+    (cond-> (select-keys wb-session [:turn :runtime])
+      last-turn
+      (assoc :last-turn
+             {:id (:id last-turn)
+              :omitted (->> (or omitted [])
+                            (take 50)
+                            (mapv omitted-entry-view))}))))
+
+(defn snapshot
@@
           (get-in state [:worldbook :sessions session-id])
           (assoc :worldbook-session
-                 (select-keys (get-in state [:worldbook :sessions session-id])
-                              [:turn :runtime]))))))))
+                 (worldbook-session-view state session-id))))))))
*** End Patch
```

注意：上面的末尾括号数量以当前 `snapshot` 的 `cond->`/`let`/`if-not` 层级为准；
应用后先跑 `clojure -M:test -n hc.server.materials.session-test` 验证读取。

**测试**

- `test/hc/server/materials/session_test.clj`：
  1. 写入一条预算不足的 `:last-turn :result :omitted`，断言 `snapshot` 的
     `:worldbook-session :last-turn :omitted` 含 `:entry-id :book-id :title
     :reason :chars`，且不含 `:body`。
  2. 写入 60 条 omitted，断言最多返回 50 条。
  3. 无 `:last-turn` 时断言 `:worldbook-session` 不含 `:last-turn`（客户端据此显示
     “服务器未报告”）。
- 客户端侧 `clojure -M:test -n hc.client.materials.model-test` 已覆盖
  `worldbook-omitted-rows` 的三种输入路径、空列表与无键文案。

**归属**：`server-content`。

---

## 3. `server materials` P1-3：一次性临时会话只读覆盖

**目标文件:行**

- `src/hc/server/materials/session.clj:145-160`（`context-for-turn!` REQUEST 文档）
- `src/hc/server/materials/session.clj:216-219`（`context-for-turn!` 的 `:else` let）
- `src/hc/server/app/system.clj:348-351`（`material-injection` 请求构造）

**现状**

客户端 P1-3 已在 `attach-turn-selection` 中把 `:temporary-read? true` 附加到发送
快照；`material-injection` 不读取该字段，`context-for-turn!` 也直接用原始 session。
服务端只在 `:materials/memory/effective` 命令中读取 payload 的 `:temporary-read?`，
发送路径不生效。临时会话因此始终不读记忆。

**修改**

1. `system.clj` 把请求或快照里的 `:temporary-read?` 传入 `context-for-turn!`。
2. `session.clj` 在临时会话中把该值合并进本轮 `session`，供 `memory-context` 的
   `material-session` 读取。非临时会话忽略该覆盖；所有写入口仍由
   `materials/remember` / `propose` 拒绝。

```diff
*** Update File: src/hc/server/app/system.clj
@@
                     :instruction-values (or (:instruction-values request)
                                             (get-in request [:snapshot :instruction-values])
                                             (:instruction-values last-user)
-                                            {})})]
+                                            {})
+                    :temporary-read? (or (:temporary-read? request)
+                                         (get-in request [:snapshot :temporary-read?]))})]
*** Update File: src/hc/server/materials/session.clj
@@
      :include-session-cards? default true
      :turn-card-ids     instruction cards selected for this turn only
-     :instruction-values placeholder values}
+     :instruction-values placeholder values
+     :temporary-read?   optional one-shot read override for a temporary session}
@@
           :else
           (let [state (:state loaded)
+                session (if (and (true? (:temporary? (material-session session)))
+                                 (true? (:temporary-read? request)))
+                          (assoc session :temporary-read? true)
+                          session)
                 staged (materials/instruction-turn-card-selection
                         state (:session-id session))
*** End Patch
```

**测试**

- `test/hc/server/materials/session_test.clj` 或 `memory_test.clj`：
  1. 临时会话 + 已保存 active 记忆 + `:temporary-read? true`，断言
     `context-for-turn!` 的 `:memory :provided` 非空。
  2. 同一请求不带 `:temporary-read?`，断言 `:memory` 不提供且原因仍为
     `:temporary-session`。
  3. 临时会话带 `:temporary-read? true` 调用 `materials/remember` / `propose`，
     断言仍返回 `:temporary-session` 拒绝。
  4. 普通会话带 `:temporary-read? true`，断言不改变原有读取开关行为。
- `test/hc/server/materials/send_context_smoke_test.clj` 可覆盖
  `material-injection` 从 `:snapshot :temporary-read?` 透传的端到端路径。
- 客户端 `clojure -M:test -n hc.client.materials.model-test` 已覆盖一次性状态、
  范围、发送载荷、发送后/切换会话复位。

**归属**：`server-content`。

---

## 4. `workbench/model.cljc` P1-6：`#/swarm/:id` 路由别名

**目标文件:行**

- `src/hc/client/workbench/model.cljc:112-122`（`parse-hash`）
- 关联已改文件：`src/hc/client/workbench/views/board.cljs:16-26,32-55`
  （已解析 `#/board/:id` 与 `#/swarm/:id`，并传 `"swarm/selected-id"`）

**现状**

`parse-hash` 用 `route` 归一第一段；`route` 只接受五条已知路由，`"swarm"` 会回退到
`:sessions`。因此 `#/swarm/s-1` 会挂载会话页，`board.cljs` 的 hash 解析没有机会运行。
`#/board/:id` 已可用；`#/swarm/:id` 只差页面路由别名。

**修改**

只把 `"swarm"` 第一段映射为 `:board`；第二段仍由 `board.cljs` 读取，不需要给
`parse-hash` 增加新的返回字段。

```diff
*** Update File: src/hc/client/workbench/model.cljc
@@
-        route (route (first parts))
+        route (if (= "swarm" (first parts))
+                :board
+                (route (first parts)))
         session-id (when (= :sessions route) (second parts))]
*** End Patch
```

**测试**

- `test/hc/client/workbench/model_test.clj:15-17` 增加：
  `(is (= {:route :board :session-id nil} (model/parse-hash "#/swarm/s-1")))`
  以及 `#/swarm` 无 id 时仍为 `:board`。
- 现有 `test/hc/client/swarm/ui_test.cljs` 已覆盖 selected id 的
  `open`/`data-selected`/`autofocus`；应用路由 patch 后再加一条
  `#/swarm/s-1` 的 board 挂载冒烟（可用 hash 解析 + `board-page` props 组合测试）。
- `clojure -M:test -n hc.client.workbench.model-test`。

**归属**：`client-workbench`。

---

## 应用顺序与验收

1. 先应用 server P1-1 与 P1-3，跑服务端 materials 测试。
2. 再应用 session P1-2 与 routing P1-6，跑客户端 JVM/CLJS 测试。
3. 最后跑：
   - `clojure -M:test -n hc.server.materials.session-test`
   - `clojure -M:test -n hc.server.materials.memory-test`
   - `clojure -M:test -n hc.client.materials.model-test`
   - `clojure -M:test -n hc.client.workbench.model-test`
   - `test/js/verify_swarm_ui.sh`
4. 若任一 patch 的末尾括号与当前文件层级不一致，以 `clojure -M:dev -e
   "(require 'hc.server.materials.session)"` / 对应客户端命名空间加载失败为准，
   先修括号再跑行为测试。

## 未纳入本文件的事项

- 技能仓库候选列表仍只显示 `:SKILL_SOURCE_NEEDS_SELECTION` 专用文案；没有服务端
  candidate preview 命令，不在本次跨目录 patch 内。
- MCP `:session-available?` / `:session-scope` 的真实数据需要 server view 提供；
  客户端在缺失时显示“当前会话可用性未报告”。
