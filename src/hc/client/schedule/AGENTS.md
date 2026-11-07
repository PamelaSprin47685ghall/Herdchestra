# hc.client.schedule — 定时任务工作台

> 权威顺序：根 `AGENTS.md` §7.4 旅程 6.8（定时任务）、§8.5 E2E 16 > `src/AGENTS.md` >
> `src/hc/client/AGENTS.md` > 本文件。
> 本包只负责浏览器端的定时任务显示、表单草稿与命令封装；服务器
> `hc.compose.scheduler` / `hc.server.scheduler` 拥有重复规则、运行事实与
> 调度器状态。

## 1 责任

1. **纯模型**：`model.cljc` 保存服务器确认的 schedule/run 副本、表单草稿、
   命令 outbox 与回执应用。点击本身从不把任务标成已启用、已暂停或已运行。
2. **真实命令**：`commands.cljc` 构造 `command-id` + `command-epoch` 信封。
   `workbench/schedule_bridge.cljs` 通过既有 Sente 通道提交：
   `:scheduler/snapshot`、`:scheduler/create`、`:scheduler/update`、
   `:scheduler/enable`、`:scheduler/pause`、`:scheduler/resume`、
   `:scheduler/delete`、`:scheduler/manual-run`、`:scheduler/set-device-zone`。
   `create`/`update` 在用户选择助手时携带顶层与快照 `:assistant-id`。
   “立即运行”先写只读确认摘要，只有 `:schedule/confirm-manual-run` 才提交
   `:scheduler/manual-run`；失败运行的重试在 `:opts` 里带递增的 `:attempt`。
   未保存表单的下三次运行由客户端 `time.cljs` 计算，不伪造服务器预览。
   正在运行的停止使用会话命令
   `:stop-run`（`session-id` 为该 run 的 `:chat-id`）。
3. **客户端预览**：`time.cljs` 用 `Intl` 计算未保存表单的下三次真实运行；
   固定/跟随设备时区、DST 空档与重叠按服务器同一语义展示。
4. **诚实平台边界**：`platform.cljs` 只读浏览器 UA 判断 iOS/Android/桌面；
   策略文本只来自 `model/platform-policies`，未知平台回退到 `:unknown`；
   iOS 固定显示“仅支持前台手动运行”，不承诺后台唤醒、不自动启用。

## 2 文件

| 文件 | 责任 |
|---|---|
| `time.cljs` | IANA 时区解析、`resolve-local`、`next-occurrences`、预览、本地化描述。 |
| `platform.cljs` | 平台检测与后台能力说明。 |
| `model.cljc` | 状态、表单校验、create/update 载荷、快照/回执应用、运行投影。 |
| `commands.cljc` | 命令队列、去重、提交态、回执记录、未知结果。 |
| `view.cljs` | 纯 hiccup 风格树：列表、详情、运行记录、表单、预览、删除/停止确认。 |
| `react.cljs` | React 适配器；`SchedulePage` 与外部 store 订阅版本。 |
| `schedule.cljs` | 公共门面。 |

工作台挂载：`hc.client.workbench.schedule-bridge/schedule-element [db]`；
`install! [app]` 注册回执/通知监听并投影 catalog、sync、平台与时区。设置页的
“定时任务”标签由 `views/settings.cljs` 挂载，不新增 Fulcro `:ui/*` 业务键。

## 3 必须保持的用户可见语义

1. **时区**：每个时间都带 IANA 时区与偏移；固定时区显示设备本地换算；
   跟随设备时区在设备时区变化后显示“时区已变更，下次将于……运行”。
   绝不显示裸 `08:00`。
2. **错过不补跑**：`tick`/重启错过的计划只记录 `:missed` 事实，状态文案为
   “未运行”并带原因。手动运行是独立的 `:manual` 记录，不改变 `:next-run`，
   也不把暂停任务重新启用。
3. **不重叠**：上次运行未结束时到点的下一次记为 `:skipped`，文案
   “已跳过：上次仍在运行”，不排队、不并发生成。
4. **暂停与停止**：暂停只阻止未来开始；正在运行的条目保持可见，并提供
   独立的“停止当前运行”。停止保留已生成正文、文件与工具记录。
5. **预览**：新建/编辑表单在保存前显示下三次真实日期、时区与 DST 空档；
   修改重复规则立即更新，不触发服务器请求。
6. **平台**：iOS 只提供前台手动运行；保存并启用被禁用并说明原因。
   Android/桌面显示后台限制，不承诺“绝不漏跑”。
7. **回执**：只有服务器回执或刷新后的 `:scheduler/snapshot` 改变确认态；
   错误保留表单并显示 code/message；发送失败不当作成功。
8. **运行记录**：每条运行对应一条事实；`chat-id` 存在时可打开结果对话；
   `:missed`/`:skipped` 原因可见；任务列表不展示完整提示词，详情才展开。
9. **助手**：列表与详情显示助手名；catalog 没有名称时显示助手 id；两者都
   没有时写“未指定助手”。表单有助手列表时提供选择，没有列表时写
   “未提供助手列表”并禁用选择，不崩溃、不猜测。
10. **模型来源**：新建对话默认“跟随助手默认”，`new-form` 的 `:model` 为
    `nil`；只有 `:model-mode :fixed` 且存在模型时才写入 `:snapshot :model`。
    继续/重做仍继承目标会话模型；详情对没有固定模型的新建对话显示
    “跟随助手默认”。保存并启用不再以“客户端未选模型”为由拒绝。
11. **立即运行确认**：点击“立即运行”只打开只读摘要（任务名、模型、执行
    方式、结果去向、下次时间），确认后才提交命令；取消不产生命令。失败运行
    的“重试”走同一确认，摘要和载荷标为新的 `:attempt`，不自动重发可能已
    完成的外部写入。
12. **继续/重做目标**：继续模式的会话选项显示标题、助手名与最后消息摘要；
    重做模式在 catalog 提供 `:messages` 时用选择器显示角色与正文摘要，否则
    保留消息 id 文本输入；详情与表单都显示所选问题正文。
13. **启用摘要**：表单预览列出工具范围（`form`/`snapshot` 的 `:tool-scope`
    或 `:capability-scope`，都没有时写“未声明”）与结果去向（新建对话、
    目标会话、独立分支或新版本）。

## 4 服务器命令形状

```clojure
{:kind :scheduler/create :schedule spec}
{:kind :scheduler/update :schedule-id id :changes changes}
{:kind :scheduler/pause  :schedule-id id}
{:kind :scheduler/manual-run :schedule-id id :opts {}}
;; 失败运行的重试：{:kind :scheduler/manual-run :schedule-id id
;;                    :opts {:attempt n}}
{:kind :scheduler/delete :schedule-id id
 :opts {:delete-runs? false :force? false}}
{:kind :scheduler/set-device-zone :zone "Area/City"}
{:kind :stop-run :session-id "<run chat-id>" :reason :user-cancel}
```

成功回执可能同时携带顶层字段和 `:receipt {:status :accepted :result ...}`；
客户端两种都接受。错误形状为 `{:ok? false :error {:code ... :message ...}}`。

## 5 构建与测试

JVM 纯模型测试：

```bash
clojure -M:dev -e "(require 'hc.client.schedule.model-test :reload-all) \
  (let [r (clojure.test/run-tests 'hc.client.schedule.model-test)] \
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

CLJS 时区/预览测试（Node 自带完整 ICU）：

```bash
clojure -M:dev:cljs:client -co '{:target :nodejs
  :output-to "target/schedule-time-test/main.js"
  :output-dir "target/schedule-time-test"
  :optimizations :none
  :main hc.client.schedule.time-test/-main
  :language-in :ecmascript-2017
  :rewrite-polyfills false
  :npm-deps false}' -c hc.client.schedule.time-test
printf '{"type":"commonjs"}\n' > target/schedule-time-test/package.json
node target/schedule-time-test/main.js
```

真实 React 元素树渲染测试：

```bash
test/js/verify_schedule_ui.sh
```

该脚本用 `react-dom/server` 渲染 `SchedulePage`/纯树，检查时区、错过/跳过、
预览更新、iOS 具体 radio 禁用、助手选择、继续/重做目标、立即运行确认、
失败重试与删除确认。仅检查源码字符串不算通过。

浏览器构建：

```bash
clojure -Sdeps '{:aliases {:shadow {:extra-deps {thheller/shadow-cljs {:mvn/version "3.5.11"}} :main-opts ["-m" "shadow.cljs.devtools.cli"]}}}' \
  -M:client:shadow compile :app
```

## 6 验证状态（2026-11-05）

本轮实际执行：

- JVM 纯模型：`hc.client.schedule.model-test` 37 tests / 166 assertions，0 failures
  （含缺失 `:schedule` 回执释放提交态、助手/会话消息规范化、跟随助手模型、
  立即运行确认与重试 `:attempt`、启用摘要、未知平台回退）。
- CLJS React 渲染：`hc.client.schedule.ui-test` 19 tests / 96 assertions，0 failures
  （真实 `react-dom/server`，含 iOS `value="enable"` radio 具体禁用、助手选择、
  继续/重做目标、重做消息选择与详情问题正文、立即运行确认面板、失败运行重试、
  工具范围/结果去向）。
- CLJS 时区：`hc.client.schedule.time-test` 11 tests / 38 assertions，本轮未改动
  `time.cljs`，未重新执行；上次结果仍为 0 failures。

已实测的浏览器/服务器路径（早于本轮最后几处编辑）：

- headless Chrome 桌面 `#/settings` 点击“定时任务”显示真实面板，0 个
  `Runtime.exceptionThrown`；iOS UA 下显示“仅支持前台手动运行”，启用 radio
  `disabled`，默认“保存为暂停”；修改时间后预览从 08:30 即时变为 07:15。
- 真实服务器（临时 workspace，Sente 回执）：创建保存为暂停、保存并启用（固定模型
  快照）、暂停、恢复、立即运行、删除均走真实 `:scheduler/*` 命令；无模型手动运行
  返回 `needs-attention` 而非假成功；`Europe/Paris` 设备时区变化后跟随设备任务改为
  新时区，固定 `Asia/Shanghai` 任务保留原时刻并显示设备换算；通知/回执后列表刷新。

未实测/被阻塞：

- 真实模型生成期间的停止：临时服务器 `/healthz` 报告 `models configured? false`，
  没有产生 running run；`:stop-run` 载荷、拒绝回执与未知回执由模型测试覆盖。需要
  服务端模型配置/端口修复后再跑 UI 停止闭环。该阻塞已由服务端处理：当前
  `model_adapter/config` 的 `config-keys` 已接受 `:attachment-loader`/`:open-blob`/
  `:credential-resolver`；`hc.server.scheduler` 已将 `:connections` 与每个连接的
  `:models` 统一规范化为 id-keyed map（sequence 形状也有测试）。本轮禁止 shell，
  因此没有重新启动临时服务器验证 `/healthz` 与 due-run→running→UI stop；需要
  devops 的 scheduler-test 绿灯后再执行。
- 真实睡眠/关机后的错过补跑：服务端 `core` 测试覆盖，客户端只渲染事实；本轮未
  重新跑服务器重启用例。

## 7 0.0.1 审核修复

- 保存回执只在服务器返回 `:schedule` 后关闭表单；没有回执或字段缺失时保留草稿并
  请求刷新，不把未知当成功。
- 命令 epoch 只取 `:sync :command-epoch`；AG-UI feed epoch 不授权命令。同步横幅也
  不再用 feed epoch 伪装可提交。
- 保存被拒绝时保留“连接未同步/命令待确认”的原因，不再被“正在保存…”覆盖。
- 跟随设备时区回执立即更新已确认任务的 `:timezone :zone` 与 `:next-run`，固定
  时区任务保持原时刻；随后再以快照确认。
- 表单草稿在创建/更新回执后从本地存储移除；失败、未知结果保留。
- 快照刷新请求合并：刷新期间的第二次请求在首个回执后补发，避免通知丢失。
- 模型目录兼容 map/vector 两种 `:settings/models` 形状，不把 map entry 当模型。
- 非法日历日期（如 `2026-02-31`）在客户端预览与校验阶段被拒绝。
- 重做方式在表单中显式选择：`:branch` 目标为用户问题，`:append-version` 目标为
  助手回复；两者都要求源会话与消息标识，并显示目标消息类型。
- 连接中断/断开时，待确认命令标记为 `:outcome-unknown`，清除“正在提交”和待办
  时区/刷新标记；表单保留，恢复后不会自动重发。
- 同步横幅区分“已同步”与“已同步，等待命令基线”，后者不宣称命令可提交。
- 服务器回执 `:refused`/`:rejected` 不再被当作成功；`:unknown` 单独显示“结果
  未知”，命令状态为 `:outcome-unknown`，不自动重发。
- 编辑表单隐藏“保存并启用/暂停”选择，按钮改为“保存修改”；启停只经详情页的
  暂停/恢复/启用动作。
- 运行记录显示具体原因与可展开的结果文件，不再只重复状态词或只给文件数量。
- 通知标签按状态/原因各做一次词表映射：命中时显示中文，未知值显示服务器原名，
  缺失状态回退“状态更新”，非名称型原因回退“未知原因”；不再用四参数 `get`
  导致 CLJS release 告警与运行期 invalid arity。
- 创建/更新回执缺少 `:schedule` 时显式清除 `:ui :form-submitting?`，保留表单并
  请求快照刷新；不再留下永远“正在提交…”的按钮。
- 助手来自 catalog `:assistants`，表单选择写入顶层与快照 `:assistant-id`；
  列表/详情显示名称或 id，catalog 缺失时表单显示“未提供助手列表”。
- 新建对话默认 `:model-mode :follow-assistant`，不再在客户端冻结主模型；
  只有显式固定模型才写 `:snapshot :model`，详情对跟随模式显示“跟随助手默认”。
- “立即运行”改为只读确认：确认前零命令，确认后才发 `:scheduler/manual-run`；
  失败运行的重试携带递增 `:attempt`，不会自动重发外部写入。
- 继续会话选项显示标题、助手与最后消息；重做在 catalog 有 `:messages` 时改用
  消息选择器，详情与表单显示所选问题正文。
- 表单预览增加工具范围与结果去向；未知工具范围写“未声明”，不猜测。
- 平台策略只保留 `model/platform-policies` 一份；未知平台回退 `:unknown`，
  iOS 测试直接断言 `value="enable"` 的 radio 被禁用。

## 8 限制

- 客户端预览是显示辅助；保存后以服务器 `:next-run` 与运行事实为准。
- 不实现服务器调度、模型调用、通知发送或存储。
- 不伪造已启用、已完成、已通知或后台唤醒。
- `:redo-question` 支持两种服务端模式：`:redo-mode :branch`（默认，目标为源会话
  的用户问题，创建独立分支）与 `:redo-mode :append-version`（目标为源会话的助手
  回复，在同一消息上追加新版本）。继续/重做可继承目标会话模型；新建对话默认
  跟随助手默认模型，客户端不伪造固定模型。服务端会重新核对消息类型、来源
  Owner 与活动运行。
- 跟随助手默认模型最终由服务器在运行开始时解析。若服务端尚未支持该模式，
  “保存并启用”会按真实回执失败处理并保留表单，客户端不会伪造成已启用。
- 助手列表、会话消息与最后消息来自 catalog 投影；缺少时表单退回文本输入或
  “未提供助手列表”，不从名称相近的实体猜测替代。
- 重试只创建新的运行尝试；可能已完成的外部写入仍需用户先核对，客户端不会
  自动重放。
