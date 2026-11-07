# pair_hint

层：`hc/core` 独立核心。无宿主 SDK、存储、网络或其他 `hc/core` 模块依赖。公开入口 `hc.core.pair-hint`；实现分在 `canon`、`prefix`、`resources`、`language`、`observation`、`hint` 六个命名空间。

## AXIOM

1. **语言只绑定一次。** 会话创建时按「显式环境变量 → 系统配置 → IDE / 系统 locale → 默认 `:en` 并记录」定下 `:en` 或 `:zh-CN`。非法显式值立即失败，绝不回退到另一种语言；`resume-session` 只信持久化的语言字段，不重跑阶梯。子会话与蜂群槽位只能继承父语言，不能自行改语言。
2. **资源必须成对。** `resources/hc/core/pair_hint/en.md` 与 `zh-CN.md` 同时存在、非空、占位符集合相同。缺一半或占位符不一致都抛错；`zh-CN.md` 首句固定为「思考过程与全部对外输出，一律用简体中文」，运行时加载也校验这一前缀，不能只靠测试。
3. **同 Epoch 前缀律。** 后一次渲染消息序列必须以前一次为语义前缀。身份只由正向白名单定义：角色、每个内容块的类型及该类型的语义字段；usage、流式占位、时间戳、索引等未列入白名单的字段不参与。链只存逐消息 digest 与链根，不存完整请求副本；验证时重算 digest 并核对链根。
4. **合成提示成对且空工具名。** 每次注入一条 `:assistant` tool-call 与一条 `:tool` result，工具名均为 `""`；两种消息都带 `:meta`（`:pair-hint`、`:ephemeral`、`:format`），flat 与 blocks 形状语义等价。记录型 hint 必须有非 nil 的 `:placement`，否则抛 `:PAIR_HINT_POSITION_INVALID`；`project-request` 可从 `:call-id`/`:attempt-id` 派生。同一 placement 重复处理只重放原 pair，不新增；`ephemeral` 侧回合只预热缓存，不记账、不移动链位点。**声明不支持 `:tools` 能力的模型是唯一例外**：提示内容照常送达，但以 `:format :notes` 渲染成一条普通 `:assistant` 消息，绝不向无法使用工具调用的模型发送 `tool_calls`/`tool` 信封。该降级由调用方给出的能力事实（`wrap-model-gateway` 的 `:tools-supported?`）显式选择并记录在投影里，不是静默丢弃；请求本身若真的需要工具，仍按 `:unsupported-capability` 明确失败。
5. **看板经 Pair Hint 推送。** 写者收到 full snapshot clean-break，旧 delta 删除并开新前缀边界；读者追加 delta，超过条数或版本跨度阈值后请求 clean-break。阈值 clean-break 只接受与当前 observation 版本一致的快照；旧快照不得冒充当前全量，缺快照时返回 `:needs-snapshot true` 并保留 delta。同版本但 changes/rows 不同是协议错误，不得静默 replay。Steward fleet 视图同构，只保留有界字段与 `:result-queued` 事件（事件字段也做白名单，delta 渲染只取最后 `:max-events` 条），不暴露单任务详情；`:joined` 行的 `:report` 是完整的 slice 报告，逐字保留、不截断、不摘要，非 joined 行不保留该字段。
6. **身份只记 provider 真正收到的内容。** flat user 消息的 `:turn-card-ids`、`:instruction-values`、`:draft`、`:turn-command-id` 等回合记账字段只由当轮 material-injection 从最新用户消息读出，绝不进入旧消息的语义身份；文本相同即同一消息。quote 只取渲染文本；citation 只取渲染胜出字段（`:text` → `:label` → `:url` 的第一项），被遮蔽的字段不参与。附件按 id + content digest + media 精确比对；文件名、状态、预览 URL 等展示/传输字段不参与。真实文本、quote 文本、citation 渲染值、附件 id/digest 的改写在同 Epoch 内仍是 `:PREFIX_DRIFT`。

## GATE

1. **边界只能由持久事实开出。** `allowed-boundary-kinds` 只收第 6.1 节列出的五种。任何 API 都不得因「看起来像重试」「看起来像 stop」「看起来像规则变更」而在投影失败后补开边界；那种反应式边界把未授权的状态变更伪装成合法前缀，并把真实的漂移悄悄改成新 Epoch。
2. **投影失败即失败。** `project-request` 返回 `{:ok? false ...}` 时原 session 不变、原 request 不变，调用方必须把错误交给上层；不得重试一次、不得降级、不得吞掉 `:PREFIX_DRIFT`。
3. **身份不读字符串形状。** 不得用 `:message-id` 前缀、文本内容或其他显示字符串推断消息身份；身份只取白名单字段的取值本身。
4. **不兼容的旧链明确失败。** 链不带规则版本，也没有「规则不符就换 Epoch」的退路。root/count 自相矛盾或 prior boundary 非法时报 `:PAIR_HINT_CHAIN_INVALID`；内部自洽但身份规则不同的旧链会表现为 `:PREFIX_DRIFT`/`:PREFIX_TRUNCATED`，同样不提交状态。允许丢弃旧链重开会合（pre-1.0 无兼容义务），但**不得**由代码伪造一个合法前缀边界来代替迁移。

## LEMMA

1. [AXIOM 3] `verify-prefix` 先校验链根与 count，再逐条比对；截断报 `:PREFIX_TRUNCATED`，首处不一致报 `:PREFIX_DRIFT` 并给出下标，不静默重算成功。
2. [AXIOM 1] `resolve-language` 的每个显式来源都做严格归一化；`:EN`、`:zh`、`:zh_CN`、`:fr` 都是非法值。locale 只按主语言标签匹配，`C`/`POSIX` 落入默认并记 `:recorded? true`。
3. [AXIOM 2] `load-pair` 用 `:resources`、`:loader` 或 classpath 读取；任何一半空白都算缺失；占位符集合不一致或 zh-CN 首句缺失都抛 `:PAIR_HINT_RESOURCE_MISMATCH`。`render` 缺占位值时报错，不用空串冒充。
4. [AXIOM 4] placement 的 call id 由 placement 与文本的 canonical digest 派生；同一 placement 且无新边界时原样重放。记录型 hint 缺 placement 直接失败；显式边界优先于旧 placement，开启新 epoch。epoch 内保留全部 placements，只在边界清空，不按条数截断。
5. [AXIOM 5] 阈值判定用「超过」而非「达到」：delta 条数或版本跨度 `>` 阈值才 clean-break。只有版本匹配的快照才可用于阈值 clean-break；旧快照、低版本 snapshot 与同版本不同内容都明确失败或返回 `:needs-snapshot`，不伪造全量。`project-request` 在 projection 中透出 `:needs-snapshot`。
6. [AXIOM 6] `message-identity` 对 flat user 只投影 text、quotes 渲染文本、citations 渲染胜出字段与附件（id + digest + media）；同文本但回合记账不同的两轮请求 digest 相同。附件缺 id 与 digest 时失败关闭。已进入链的 assistant 消息文本变化同样是身份变化，照实报 `:PREFIX_DRIFT` 且不提交状态；Pair Hint 不得自行开边界吸收它，第 6.1 节的边界清单里没有 `:assistant-rewrite`。

## SPEC

- 公开 API：`create-session`、`resume-session`、`child-session`、`resolve-language`、`inherit-language`、`load-resources`、`build-hint`、`append-hint`、`record-request`、`reanchor`、`apply-board-observation`、`apply-fleet-observation`、`observe-board`、`observe-fleet`、`chain-of`、`verify-prefix`、`message-identity`、`message-digest`。
- `append-hint` 的记录型调用必须有 `:placement`（缺失抛 `:PAIR_HINT_POSITION_INVALID`）；`:insert-at` 指定持久插入位置（默认尾插）；`:anchor` 指定 `{:after-message-id id}` / `{:after-call-id id}` / `{:after-index n}` / `{:tail true}`。越界抛 `:PAIR_HINT_POSITION_INVALID`；anchor 不在请求中抛 `:PAIR_HINT_ANCHOR_MISSING`。`project-request` 缺 placement 时从 `:call-id`/`:attempt-id` 派生，避免重试重复注入。
- `project-request` 在无新边界时按 `:placements` 的 anchor 重放旧合成对，再投影当前对；同一业务 call 重试只重放，不新增。epoch 内保留全部 placements，只在显式边界清空；`record-request` 开边界时同样清空。显式边界（relay/scope-reset/scope-remove/compaction/board clean-break）丢弃旧 placements，开启新链。
- 同一 anchor 上已有合成对时，新对必须排在旧对之后（`insertion-index` 跳过连续的 `:pair-hint` 消息），否则旧链不再是前缀。未变化的 base messages 连续两次投影由行为测试覆盖。
- 重放只在**同一 placement key** 下发生；每次更换 placement key 就是新的合成对，旧对仍需按其 anchor 回填。若观察到的基础消息缺少旧对的 anchor（例如把新一轮的 assistant 回复当成 base 传给下一次投影），重放失败并报 `:PAIR_HINT_ANCHOR_MISSING`；那是调用方少传了上一轮请求里的合成对，不是链损坏。
- `:nudge-id`、`:snapshot :ephemeral?` 或 `:snapshot :attribution "agent"` 自动视为 ephemeral：只注入当前对，不重放旧对、不记账、不移动链。`:nudge-id` 是内核继续回合的显式例外（Owner 的 `open-successor-run-guard` 也把它视为不入链），不是 §6.2 列举的标题/预测类侧回合；该例外必须在规格层保持可见。`wrap-model-gateway` 默认执行此判定；普通主请求仍走完整 anchor 重放。
- flat user 身份白名单：`:text`、`:quotes`（只取 `:text`/`:content`）、`:citations`（只取渲染胜出字段）、`:attachments`（id + digest + media；展示字段不参与；缺 id 与 digest 失败关闭）。`:turn-card-ids`、`:instruction-values`、`:draft`、`:turn-command-id`、`:at`、`:streaming?` 等一律不参与。
- 允许的新前缀边界：`:relay`、`:scope-reset`、`:scope-remove`、`:compaction`、`:board-clean-break`。其它 kind（含 `:assistant-rewrite`）抛 `:PAIR_HINT_BOUNDARY_INVALID`。`:relay`/`:compaction` 必须有非空 `:id`；Scope 边界的 `:raw` 会保留在链边界里；显式边界与 pending 边界同时存在时，后者记录为 `:supersedes`，不静默丢弃。
- 主要错误码：`:PAIR_HINT_LANGUAGE_INVALID`、`:PAIR_HINT_LANGUAGE_INHERITANCE_CONFLICT`、`:PAIR_HINT_RESOURCE_MISSING`、`:PAIR_HINT_RESOURCE_MISMATCH`、`:PAIR_HINT_TEMPLATE_INVALID`、`:PAIR_HINT_TEMPLATE_VALUE_MISSING`、`:PAIR_HINT_MESSAGE_INVALID`、`:PAIR_HINT_MESSAGES_INVALID`、`:PAIR_HINT_CONTENT_INVALID`、`:PAIR_HINT_CONTENT_TYPE_UNSUPPORTED`、`:PAIR_HINT_BOUNDARY_INVALID`、`:PAIR_HINT_POSITION_INVALID`、`:PAIR_HINT_ANCHOR_MISSING`、`:PAIR_HINT_CHAIN_INVALID`、`:PAIR_HINT_RENDERER_REQUIRED`、`:PAIR_HINT_RENDERER_INVALID`、`:PREFIX_DRIFT`、`:PREFIX_TRUNCATED`、`:PAIR_HINT_OBSERVATION_INVALID`、`:PAIR_HINT_BOARD_SNAPSHOT_REQUIRED`、`:PAIR_HINT_BOARD_STALE`、`:PAIR_HINT_FLEET_STALE`。
- 看板/fleet：`observe-board` 的 writer/`kind :snapshot` 路径也做 stale 检查；阈值 clean-break 只接受版本匹配的快照，否则 `:needs-snapshot true`；同版本不同 changes/rows 报 `:PAIR_HINT_OBSERVATION_INVALID`。`project-request` 的 projection 透出 `:needs-snapshot`。`fleet-event-fields` 是事件白名单，非 `:result-queued` 事件与事件中的额外详情字段都会被丢弃；fleet delta 的渲染、`:last-delta` 与状态事件都只保留最后 `:max-events` 条。`normalize-fleet-row` 对 `:joined` 行在 `select-keys` 之后逐字补回 `:report`。
- 测试：`test/hc/core/pair_hint_test.clj`。含 `clojure.test.check` 属性：canonical 与插入顺序无关、envelope 噪声不移动链、文本变化必变 digest、随机消息扩展恒为前缀；另有语言阶梯、资源配对、placement 重放、ephemeral、board/fleet clean-break 的确定性用例。`flat-user-envelope-does-not-move-the-prefix` 钉住 AXIOM 6 的白名单边界（记账字段与 transport id 不移动 digest；citation 只有渲染胜出字段才算身份）；`recorded-assistant-change-is-reported-as-drift` 钉住「已入链的 assistant 文本改写照实报 `:PREFIX_DRIFT` 且不提交状态」；`an-incompatible-chain-fails-instead-of-inventing-a-boundary` 钉住「旧链不兼容时明确失败，绝不伪造合法边界」。
- 新增确定性测试：`placement-history-keeps-every-pair-in-the-epoch`（10 个 hint 全部回填）、`placement-is-required-for-recorded-hints`、`record-request-with-boundary-clears-placements`、`board-threshold-never-reuses-an-old-snapshot`、`board-delta-append-replay-and-stale`（同版本不同 changes）、`fleet-summary-is-bounded-and-merged`（事件白名单）、`fleet-joined-report-is-kept-verbatim`（joined 报告逐字保留）、`fleet-delta-events-are-bounded`（delta 事件上限）、`reanchor-preserves-the-raw-scope-fact`、`explicit-boundary-supersedes-a-pending-board-fact`、`project-request-exposes-a-required-snapshot`、`project-request-reports-an-invalid-current-pair-base`。
- 集成：`test/hc/core/pair_hint_ufss_integration_test.clj` 显式 require `hc.core.ufss`，用 `write-str` 验证 board/fleet/parked-handles 的 EDN+UFSS 往返与零转义；`src/hc/core/pair_hint` 自身不 require 兄弟 core 模块。
- 运行时装配：`hc.core.pair-hint/wrap-model-gateway` 包装真实 `{:start! :cancel!}` gateway；`:load-session` / `:persist-session` 提供重启后的持久链，`:observations` / `:boundary` 接收看板与 Scope 事实。Owner 侧已用真实 `:send-message` 冒烟：两轮请求链 3→7，第二请求 `verify-prefix` 为 true。
- App 装配：`hc.server.app.system/session-owner-factory` 分别包装主 `gateway` 与 `devops-gateway`（每 Owner 一个 atom，`:render hc.core.ufss/write-str`）；`sanitize-message` 保留顶层 `:pair-hint`，`model-adapter.request` 只对该标记放行空工具名。主链存 `[:mission :extensions :pair-hint]`，devops 链存 `[:mission :extensions :pair-hint-devops]`；wrapper 的 `:persist-session` 通过通用 Owner 消息 `{:type :mission/extensions-put ...}` 在同一 CAS 提交，Owner 启动后从 snapshot 的 mission extension 重新 seed。重启后续链，不新开 Epoch。
- Scope 边界：主 wrapper 与 devops wrapper 都用 `scope-runtime/pending-boundary` 读取 Owner `(:scope state)`，把原始 Scope fact 与各自 `pair-session :scope-boundary` 游标比较；`pair-hint/scope-boundary` 只做翻译并保留 `:raw`。`project-request` 成功后把原始 fact 写回 `:scope-boundary`，因此 relay/board clean-break 不会让旧 Scope 边界看起来是新的。看板主链以 writer full-snapshot clean-break 注入。
- 压缩边界：`hc.server.app.system/pair-request-boundary` 把 Scope fact、applied 压缩集合签名（`[:state :session :context :compressions]` 中 `:state :applied` 的记录）与 pending board clean-break 经 `:supersedes` 串成一条边界链；applied 集合变化时发 `:kind :compaction :id <digest>`。`project-request` 沿 `:supersedes` 链扫描，把 compaction 边界的 `:id` 写回 `pair-session :compaction-cursor`（ephemeral 请求不消费游标）；签名未变则不重复开边界。压缩 apply/undo 改写出站前缀，没有这个边界会得到 `PAIR_HINT_ANCHOR_MISSING`/`PREFIX_DRIFT`，模型请求在 dispatch 前失败。
- 看板行为：writer snapshot 版本未变且快照相同才 replay，不切 Epoch；版本变化时清空旧 delta/placements，开新 `:board-clean-break` 边界。低版本 snapshot 报 `:PAIR_HINT_BOARD_STALE`；阈值 clean-break 不使用旧快照。新 Epoch 的后续请求继续按新链 anchor 重放；行为测试覆盖 1→1→2→2 的完整序列。
- 复检：`clojure -M:test:compose:server:client -n hc.core.pair-hint-test -n hc.core.pair-hint-ufss-integration-test`（`:paths` 已含 `src`、`resources`；测试在 `test`；`request_test` 还需 `:server` 提供 Jackson）。
- App 冒烟：完整 `hc.server.app.system/start!` + 真实 session create/send + fake model port；捕获请求 roles `[:user :assistant :tool]`、空工具名、pair markers `[nil true true]`。Owner 冒烟：两轮真实 `:send-message`，第二请求 `verify-prefix` true，链 3→7/8，`errors 0`。

## 边界

- 不做自然语言输出种类检测；运行时只核验语言合法、资源成对、链不漂移。
- 不实现 provider 适配、持久化、Scope/Relay 状态机或看板 STM；只消费它们给出的事实与边界。
- `observation`、`parked-handles`、以及直接传入的 `:board-observation` / `:fleet-summary` 结构化值**必须**由调用方注入 `:render`（组合层传 `hc.core.ufss/write-str`）；pair_hint 不 require 兄弟 core 模块，也**不再**提供 `pr-str` 回退。缺 renderer 抛 `:PAIR_HINT_RENDERER_REQUIRED`，renderer 返回非字符串抛 `:PAIR_HINT_RENDERER_INVALID`。
- 真实模型适配器只允许带顶层 `:pair-hint true`（或 `:meta {:pair-hint true}`）的合成消息使用空工具名；普通空名仍拒 `:blank-tool-name`。app `sanitize-message` 必须保留 `:pair-hint` 标记。
- 链只证明语义前缀，不承诺缓存 100% 命中；缓存收益仍是 best-effort。
