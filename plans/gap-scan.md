# Herdchestra 静态缺口扫描

> 扫描日期：2026-11-05。只读扫描：未修改源码、未运行构建、未执行 git 操作。
> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > 包级 `AGENTS.md` > 本文件。
> 本文件是修复任务的输入清单，不是验收报告。所有结论来自静态阅读；运行态结论标 `[未验证]`。

## 0 范围、方法与图例

### 0.1 扫描范围

| 范围 | 内容 |
|---|---|
| 源码 | `src/`，469 个 `.clj/.cljs/.cljc` |
| 测试 | `test/`，333 个 `.clj/.cljs/.cljc` |
| 资源 | `resources/`（`index.html`、CSS、pair-hint 语言包；`resources/public/js` 为构建产物，仅查引用） |
| 构建 | `deps.edn`、`shadow-cljs.edn`、`pixi.toml`、`package.json` |
| 排除 | `target/`、`out-*`、`.cpcache/`、`node_modules/`、`.shadow-cljs/`、`.pixi/`、`.git/` |

方法：`rg` 全量正则检索；`find -mmin -1440` 取 24 小时修改；按路径推导命名空间后统计 `test/` 文本引用；逐条阅读命中上下文后判定。未运行任何测试，未用编译器验证。

### 0.2 归属修复任务

| 标签 | 范围 |
|---|---|
| `core-audit` | `src/hc/core/*` |
| `compose-audit` | `src/hc/compose/*` |
| `server-audit` | `src/hc/server/*` |
| `client-audit` | `src/hc/client/*` |
| `docs` | 根/包级 `AGENTS.md`、`HACK.md`、`plans/` |
| `release` | 构建配置、全量验证账本、交付门禁 |

### 0.3 分类

| 分类 | 含义 | 处理 |
|---|---|---|
| **R** | 规格要求但缺失：规格写明应有，代码/文档/测试没有或不完整 | 必须修 |
| **N** | 规格声明的非目标：规格明确不做，或已声明“未实现且拒绝” | 不实现；只保证拒绝诚实、文档一致 |
| **D** | 死代码 / 未接线：无人引用、被取代未删、实现存在但生产路径不调用 | 删除或接线，二选一 |
| **X** | 已判定无缺口：保留判断证据，防止后续误报 | 不改；必要时加锁死测试 |

### 0.4 统计

| 分类 | 条目数 |
|---|---|
| R 规格要求但缺失 | 47 |
| N 规格声明的非目标 | 5 |
| D 死代码 / 未接线 | 9 |
| X 已判定无缺口（基线） | 44 |
| **合计** | **105** |

可处理条目 = R + D = 56。X 类保留全部证据，不计入修复工作量。

---

## 1 标记扫描

### 1.1 TODO / FIXME / XXX / HACK

代码注释中 **0 处**。全仓 7 处命中全部是对 `HACK.md` 的引用，不是待办。

| 编号 | 位置 | 命中文本 | 分类 | 归属 | 动作 |
|---|---|---|---|---|---|
| G-001 | `src/AGENTS.md:5` | “根规格更新后，先同步本文件，再同步相关包文档与 `HACK.md`” | X | docs | 无 |
| G-002 | `src/AGENTS.md:107` | “外部库、平台与运行时限制……写入根 `HACK.md`” | X | docs | 无 |
| G-003 | `src/hc/server/AGENTS.md:106` | “原生库与事务可见性的实测结果见根 `HACK.md` §5.1” | X | docs | 无 |
| G-004 | `src/hc/server/documents/AGENTS.md:36` | “升级任一版本时同时更新 `version` 常量、缓存目录名与 `HACK.md` 实测记录” | X | docs | 无 |
| G-005 | `src/hc/server/storage/AGENTS.md:40` | “Measured on this host (`HACK.md` records the details)” | X | docs | 无 |
| G-006 | `deps.edn:40` | “(root AGENTS.md §7.1; measured limits in HACK.md)” | X | docs | 无 |
| G-007 | `pixi.toml:6` | “osx-arm64 omitted: conda-forge clojure has no arm build (HACK.md §4.2)” | X | release | 无 |

`test/`、`resources/` 内 0 处。

### 1.2 not implemented / unimplemented / placeholder / stub / coming soon / 暂未 / 未实现 / 待实现

#### 1.2.1 真正表示未完成的条目

| 编号 | 位置 | 证据 | 分类 | 归属 | 动作 |
|---|---|---|---|---|---|
| G-008 | `src/hc/core/swarm_stm/commands.clj:1311` | `;; stub actions (phase one: no file or process side effects)`。内核动作只记账；真实文件/Shell 执行在 `hc.compose.workspace`。`swarm_stm/AGENTS.md:382` 把“`:shell` 替身由真实 Shell 适配器替换”列为阶段四 | N | core-audit + docs | 分层有意，不实现执行；把注释改为“本包无副作用，执行由组合层负责”，与 §10 阶段叙述对齐 |
| G-009 | `src/hc/client/miniapps/smoke.cljs:2-4` | 自述 “Temporary browser harness … It is not part of the production `:app` entry and is deleted after devops verifies the sandbox.” 无 shadow-cljs build、无 `test/js` 脚本引用 | D | client-audit | 删除该文件；若仍需浏览器烟测，补可复现 build + 脚本（`miniapps/AGENTS.md:93-96` 目前要求临时注入 `:smoke` build） |
| G-010 | `src/hc/server/files.clj:367` | docstring “Content search is not implemented and is rejected rather than imitated.”；拒绝分支在 `:372`（`:UNSUPPORTED_OPERATION`）。`files/AGENTS.md:111` 与 `:44` 已声明内容搜索未实现 | N | server-audit | 不实现；保持拒绝文案与 HTTP 501 映射一致（`files/AGENTS.md:71`） |
| G-011 | `src/hc/server/search/config.clj:343` | “service type is not implemented”，未知 `:kind` 返回 `:SEARCH_SERVICE_KIND_UNKNOWN` 草稿 | N | server-audit | 不实现；`search/AGENTS.md:64` 只允许 `:searxng/:brave/:tavily`，保持现状 |

#### 1.2.2 误报清单（保留，避免后续重复误判）

| 编号 | 类别 | 代表位置 | 分类 | 归属 |
|---|---|---|---|---|
| G-012 | HTML `:placeholder` 输入框属性 | `src/hc/client/settings/view.cljc:184,218,418,458,467,502`；`src/hc/client/extensions/view.cljc:223,293,345,359,450,465`；`src/hc/client/conversation/view.cljs:416,1032,1169` | X | client-audit |
| G-013 | “草稿暂未保存”运行时文案（规格要求） | `src/hc/client/conversation/draft.cljc:381-382`；`surface.cljc:433,474`；`workbench/model.cljc:834-835`；`workbench/main.cljs:100`；`workbench/settings_bridge.cljs:111`；`schedule/view.cljs:789`；`settings/view.cljc:967`；`attachments/surface.cljs:136` | X | client-audit |
| G-014 | “暂不可用 / 暂未听到”状态文案 | `src/hc/client/preview/view.cljs:229,233,237,272`；`workbench/model.cljc:118,145`；`speech/ui.cljs:388` | X | client-audit |
| G-015 | backup `:placeholders` 领域概念（缺失助手占位） | `src/hc/server/backup/merge.clj:419,449,451`；`client/backup/model.cljc:1065`；`client/backup/view.cljc:764-765` | X | server-audit |
| G-016 | 契约措辞中的 placeholder | `src/hc/client/conversation/attachments.cljc:547`；`src/hc/client/conversation/composer.cljc:117` | X | client-audit |

### 1.3 显式拒绝（`:unsupported` / `:not-supported` / `:UNSUPPORTED_*`）

全仓 101 处文本命中（51 文件），另有 38 处 `err/fail!` / `err/failure :UNSUPPORTED_OPERATION`。逐条判断后：未发现借 `:unsupported` 掩盖未完成功能的分支。

#### 1.3.1 规格内拒绝（实现完成，不改）

| 编号 | 位置 | 内容 | 分类 | 归属 |
|---|---|---|---|---|
| G-017 | `src/hc/server/attachments/model.clj:510` | 附件与模型能力不匹配 → `:route :unsupported` | X | server-audit |
| G-018 | `src/hc/server/attachments/model.clj:534` | 同上（文本附件） | X | server-audit |
| G-019 | `src/hc/server/attachments/model.clj:544` | 同上（原生附件） | X | server-audit |
| G-020 | `src/hc/client/settings/catalog.cljc:44` | 能力声明归一为 `:unsupported` | X | client-audit |
| G-021 | `src/hc/client/settings/catalog.cljc:51` | 显示文案“不支持” | X | client-audit |
| G-022 | `src/hc/client/settings/catalog.cljc:105` | `:unsupported` → `[cap false]` | X | client-audit |
| G-023 | `src/hc/server/model_adapter/port.clj:878` | `:unsupported-capability`（零 provider 支持） | X | server-audit |
| G-024 | `src/hc/server/documents/source.clj:257` | zip 容器 → `:kind :unsupported` | X | server-audit |
| G-025 | `src/hc/server/documents/source.clj:259` | OLE 容器 → `:kind :unsupported` | X | server-audit |
| G-026 | `src/hc/server/documents/source.clj:264` | 未知二进制 → `:kind :unsupported` | X | server-audit |
| G-027 | `src/hc/server/gateway/http.clj:268` | 路由不支持该 HTTP 方法 | X | server-audit |
| G-028 | `src/hc/client/sync/object.cljc:122` | “object encoding is not supported” | X | client-audit |
| G-029 | `src/hc/core/ufss/reader.clj:620` | namespaced map 不支持 | X | core-audit |
| G-030 | `src/hc/core/ufss/reader.clj:624` | reader conditional 不支持 | X | core-audit |
| G-031 | `src/hc/server/attachments/media.clj:283` | `:unsupported-image-format` | X | server-audit |
| G-032 | `src/hc/server/attachments/ingest.clj:255` | `:UNSUPPORTED_MIME` | X | server-audit |
| G-033 | `src/hc/server/attachments/http.clj:173` | 415 `:UNSUPPORTED_MIME` | X | server-audit |
| G-034 | `src/hc/core/pair_hint/language.clj:51` | 非 en/zh-CN 抛 `:PAIR_HINT_LANGUAGE_INVALID`；`resources/hc/core/pair_hint/` 只有 `en.md`、`zh-CN.md` | X | core-audit |
| G-035 | `src/hc/core/workspace_gate/applier.clj:84` | 文件系统不支持原子替换 → `:HARD_FAILURE :atomic-replace-unsupported`，不降级 | X | core-audit |

#### 1.3.2 防御性 default（不是缺口，保留）

| 编号 | 位置 | 内容 | 分类 | 归属 |
|---|---|---|---|---|
| G-036 | `src/hc/compose/workspace.clj:817-818` | 未知 workspace 操作 → `:unsupported-operation` | X | compose-audit |
| G-037 | `src/hc/server/scheduler.clj:620-621` | 未知执行模式兜底；`:615-618` 已覆盖 `:new-chat/:continue-chat/:redo-question` | X | server-audit |
| G-038 | `src/hc/server/app/system.clj:898` | 非 workspace bridge 拥有的控制原语 | X | server-audit |
| G-039 | `src/hc/server/app/system.clj:1238` | 未知取消资源类型；已知 `:tool/:shell/:workspace/:devops/:model` 全覆盖 | X | server-audit |
| G-040 | `src/hc/server/app/system.clj:2644` | `:UNSUPPORTED_FLEET_EFFECT`“no real handler is wired”；`fleet/supervisor.clj:82-110` 只发 `:model/start` 与 `:model/cancel`，当前覆盖完整 | X | server-audit |
| G-041 | `src/hc/server/program/runtime.clj:473` | 缺 workspace session 的装配失败 | X | server-audit |
| G-042 | `src/hc/server/program/runtime.clj:481` | 缺 workspace port 的装配失败 | X | server-audit |
| G-043 | `src/hc/server/program/runtime.clj:973` | 缺 shell call port 的装配失败 | X | server-audit |
| G-044 | `src/hc/server/shell/session.clj:130` | `:env-refs` 无 secret resolver 时拒绝 | X | server-audit |
| G-045 | `src/hc/server/shell/session.clj:135` | secret resolver 抛错 | X | server-audit |
| G-046 | `src/hc/server/shell/session.clj:140` | resolver 返回 nil | X | server-audit |
| G-047 | `src/hc/compose/owner/guards.clj:51-52` | 未知守卫操作 | X | compose-audit |

#### 1.3.3 需复核的边界

| 编号 | 位置 | 证据 | 分类 | 归属 | 动作 |
|---|---|---|---|---|---|
| G-048 | `src/hc/client/sync/adapter.cljc:460` | 未知事件类型 → `:ignored` + `:unsupported-event`。规格只定义 `STATE_SNAPSHOT/STATE_DELTA/MESSAGES_SNAPSHOT`（`sync/AGENTS.md:87-89`），服务器也只发这三类（`gateway.clj:683-685`） | R（测试） | client-audit | 加一个锁死事件集合的测试：新增服务器事件时必须显式失败，而不是静默丢弃 |
| G-049 | `src/hc/server/documents/selection.clj:78` | 内容类型不支持 → `:unsupported` | N | server-audit | 不实现；保持拒绝 |
| G-050 | `src/hc/server/search/retrieve.clj:196` | 非文本文档 → `:unsupported`，随后 `:SEARCH_FETCH_UNSUPPORTED` | N | server-audit | 不实现；保持拒绝 |

### 1.4 空 `(comment ...)` 与返回 nil 的占位函数

| 编号 | 结论 | 证据 | 分类 | 归属 |
|---|---|---|---|---|
| G-051 | `(comment ...)` 0 处；`#_(...)` 0 处；注释掉的代码 0 处；可疑 nil 占位函数 0 处 | 检索到的 `(fn [] nil)` / `(fn [_] nil)` 均为回调 no-op：`attachments/surface.cljs:196,352`；`speech/translation.cljs:188`；`speech/ui.cljs:296,802`；`core/swarm_stm/kernel.clj:31`；`server/app/restore_supervisor.clj:1123`；`sessions/secrets.clj:212`。另有 229 处 `(catch Throwable _ nil)`，多为清理/探针路径，未发现把失败伪装成成功 | X | 各层 |

---

## 2 最近 24 小时修改（观测数据，不编号）

`src/` + `test/` 共 **440** 个文件在 24 小时内被改动，其中 **368** 个是 `.clj/.cljs/.cljc/.edn`。整个工作树基本是同一批并行施工的快照，因此“最近修改”区分度低。

### 2.1 最近 25 个源码文件（mtime 倒序）

| # | 文件 | 时间 |
|---|---|---|
| 1 | `src/hc/server/app/system.clj` | 07:21 |
| 2 | `src/hc/compose/owner/handlers.clj` | 07:21 |
| 3 | `src/hc/compose/owner/guards.clj` | 07:20 |
| 4 | `src/hc/server/model_adapter/port.clj` | 07:19 |
| 5 | `src/hc/client/workbench/sync_bridge.cljs` | 06:58 |
| 6 | `src/hc/server/gateway/sync_object.clj` | 06:52 |
| 7 | `src/hc/client/sync/object.cljc` | 06:52 |
| 8 | `src/hc/server/gateway/http.clj` | 06:51 |
| 9 | `src/hc/server/gateway.clj` | 06:45 |
| 10 | `src/hc/client/sync/adapter.cljc` | 06:45 |
| 11 | `src/hc/client/workbench/model.cljc` | 06:38 |
| 12 | `src/hc/client/sync.cljc` | 06:38 |
| 13 | `src/hc/server/app/config.clj` | 06:37 |
| 14 | `src/hc/server/gateway/feed.clj` | 06:35 |
| 15 | `src/hc/server/gateway/diff.clj` | 06:18 |
| 16 | `src/hc/client/backup/bridge.cljs` | 06:18 |
| 17 | `src/hc/client/conversation/timeline.cljc` | 06:01 |
| 18 | `src/hc/client/conversation/view.cljs` | 05:59 |
| 19 | `src/hc/server/app/settings.clj` | 05:58 |
| 20 | `src/hc/client/workbench/views/session.cljs` | 05:41 |
| 21 | `src/hc/client/settings/catalog.cljc` | 05:41 |
| 22 | `src/hc/client/settings/view.cljc` | 05:22 |
| 23 | `src/hc/client/workbench/db.cljs` | 05:21 |
| 24 | `src/hc/client/settings/connection.cljc` | 05:21 |
| 25 | `src/hc/client/workbench/views/shell.cljs` | 05:20 |

### 2.2 上述文件中仍含真实标记的

| 文件:行 | 标记性质 |
|---|---|
| `src/hc/server/app/system.clj:898,1238,2644` | `:UNSUPPORTED_*` 兜底（G-038/039/040） |
| `src/hc/compose/owner/guards.clj:51` | `:UNSUPPORTED_OPERATION` 兜底（G-047） |
| `src/hc/server/model_adapter/port.clj:878` | `:unsupported-capability`，规格内（G-023） |
| `src/hc/client/sync/adapter.cljc:460` | `:unsupported-event`，需测试锁死（G-048） |
| `src/hc/client/sync/object.cljc:122` | 编码不支持（G-028） |
| `src/hc/server/gateway/http.clj:268` | 方法不支持（G-027） |
| `src/hc/compose/workspace.clj:817` | 未知操作兜底（G-036，mtime 02:53） |
| `src/hc/client/settings/model.cljc:1748` | “主模型不支持视觉”文案（X） |
| `src/hc/core/swarm_stm/commands.clj:1311` | stub actions 注释（G-008，mtime 02:20） |

### 2.3 含标记但不在 24 小时内

- `src/hc/server/files.clj:367`（内容搜索未实现，G-010）
- `src/hc/server/search/config.clj:343`（未知服务类型，G-011）

---

## 3 包级 `AGENTS.md` 缺失

规则：目录内有 `.clj/.cljs/.cljc` 源码但本目录无 `AGENTS.md`。共 11 个目录。

| 编号 | 目录 | 源文件数 | 上层文档覆盖情况 | 分类 | 归属 | 严重度 |
|---|---|---|---|---|---|---|
| G-052 | `src/hc` | 1（`version.cljc`） | `src/AGENTS.md` 覆盖 | R | docs | 低 |
| G-053 | `src/hc/client/appearance` | 2（`model.cljc`、`view.cljc`） | `src/hc/client/AGENTS.md` 与 `src/AGENTS.md` 均未提及 appearance | R | docs + client-audit | **高** |
| G-054 | `src/hc/client/conversation/storage` | 1（`storage/browser.cljs`） | `conversation/AGENTS.md:38-39` 有逐文件表 | R | docs | 低 |
| G-055 | `src/hc/client/sessions/controls` | 1（`controls/ui.cljs`） | `sessions/AGENTS.md:18-19` 有逐文件表 | R | docs | 低 |
| G-056 | `src/hc/client/workbench/views` | 8 | `workbench/AGENTS.md:39-44` 列出 6 个（session/board/settings/assistants/materials/shell）；`views/browser.cljs` 与 `views/common.cljs` 无表项（common 仅在 `:240` 的 harness 说明出现） | R | docs + client-audit | **中** |
| G-057 | `src/hc/server/documents/ocr` | 1（`tesseract.clj`） | `documents/AGENTS.md:46-49` 引用 OCR 入口 | R | docs | 低 |
| G-058 | `src/hc/server/extensions/mcp` | 9 | `extensions/AGENTS.md` 讨论 MCP 行为（`:158-165`、`:225-227`），无逐文件清单 | R | docs | 中 |
| G-059 | `src/hc/server/extensions/skill` | 4 | `extensions/AGENTS.md` 讨论技能语义，无逐文件清单 | R | docs | 中 |
| G-060 | `src/hc/server/model_adapter/providers` | 2（`openai.clj`、`ollama.clj`） | `model_adapter/AGENTS.md:347-348` 有逐文件表 | R | docs | 低 |
| G-061 | `src/hc/server/search/providers` | 5 | `search/AGENTS.md:26-32` 有完整逐文件表 | R | docs | 低 |
| G-062 | `src/hc/server/storage/engine` | 1（`datalevin.clj`） | `storage/AGENTS.md:244` 说明唯一引擎 | R | docs | 低 |

> 注：G-052～G-062 的编号顺序按目录列出；G-053 与 G-056 是仅有的两个“上层文档也未覆盖”的目录，优先处理。

---

## 4 测试覆盖缺口

一级模块全部有对应测试目录：`test/hc/core`（21 文件）、`test/hc/compose`（66）、`test/hc/server`（176）、`test/hc/client`（69）。缺口在二级模块与具体文件。

方法：源文件命名空间名在 `test/` 全文零出现 = **无直接命名空间引用**；不等于完全无间接覆盖。全部 123 个无直接引用文件可按此方法复现。

### 4.1 无专用测试文件/目录的模块

| 编号 | 模块 | 源文件 | 现有覆盖 | 分类 | 归属 | 动作 |
|---|---|---|---|---|---|---|
| G-063 | `src/hc/client/browser` | 3 | 仅 `test/hc/client/browser_model_test.clj` 覆盖 `model` + `view`；`react.cljs` 无直接引用 | R | client-audit | 为 `react.cljs` 补渲染测试或明确其由 DOM harness 覆盖 |

### 4.2 模块级缺口（有测试目录，但存在无直接引用的源文件）

| 编号 | 模块 | 源 | 测试 | 无直接引用 | 分类 | 归属 | 备注 |
|---|---|---|---|---|---|---|---|
| G-064 | `client/workbench` | 24 | 6 | 15 | R | client-audit | `views/shell`、`views/session` 由 `test/js` DOM 脚本覆盖；`views/board`、`views/browser`、`views/materials`、`views/assistants`、`views/settings` 无对应脚本 |
| G-065 | `server/extensions` | 22 | 11 | 11 | R | server-audit | 见 G-081、G-082 |
| G-066 | `server/speech` | 11 | 4 | 7 | R | server-audit | 见 G-083 |
| G-067 | `server/search` | 12 | 10 | 7 | R | server-audit | 见 G-084 |
| G-068 | `server/storage` | 9 | 4 | 6 | R | server-audit | 见 G-085 |
| G-069 | `server/backup` | 26 | 12 | 6 | R | server-audit | 备份面最大，集成测试多但单元覆盖稀 |
| G-070 | `server/shell` | 6 | 1 | 4 | R | server-audit | 见 G-086 |
| G-071 | `client/conversation` | 17 | 5 | 4 | R | client-audit | |
| G-072 | `client/miniapps` | 5 | 1 | 4 | R | client-audit | 见 G-087 |
| G-073 | `client/files` | 6 | 3 | 3 | R | client-audit | |
| G-074 | `client/speech` | 5 | 1 | 3 | R | client-audit | |
| G-075 | `client/sync` | 6 | 3 | 3 | R | client-audit | |
| G-076 | `server/documents` | 10 | 10 | 3 | R | server-audit | |
| G-077 | `server/materials` | 10 | 9 | 3 | R | server-audit | |
| G-078 | `server/app` | 19 | 28 | 3 | R | server-audit | |
| G-079 | `core/pair_hint` | 7 | 2 | 3 | R | core-audit | |
| G-080 | `core/workspace_gate` | 8 | 3 | 3 | R | core-audit | |

### 4.3 具体无直接测试的文件组

| 编号 | 文件 | 分类 | 归属 | 备注 |
|---|---|---|---|---|
| G-081 | `server/extensions/mcp/{transport,stdio,probe,http}.clj` | R | server-audit | `mcp_connection_test` / `mcp_integration_test` 间接覆盖子系统 |
| G-082 | `server/extensions/skill/{archive,update,preview}.clj` | R | server-audit | `store.clj` 已被引用 |
| G-083 | `server/speech/{stt,tts,translation,usage,files}.clj` | R | server-audit | `error.clj`、`http.clj` 为辅助 |
| G-084 | `server/search/providers/{searxng,brave,tavily,common,protocol}.clj` | R | server-audit | provider 仅经上层间接覆盖 |
| G-085 | `server/storage/{error,export,integrity,lock,migration,view}.clj` | R | server-audit | `storage_test` 覆盖主路径 |
| G-086 | `server/shell/{error,output,read_only,session}.clj` | R | server-audit | `shell_test` / `shell_owner_port_test` 间接覆盖 |
| G-087 | `client/miniapps/{api,smoke,store,view}.cljs` | R | client-audit | `smoke.cljs` 另见 G-009（建议删除） |
| G-088 | `client/workbench/views/{assistants,board,browser,common,materials,session}.cljs` | R | client-audit | `session` 有 `verify_workbench_session_commands.sh`；`board/browser/materials/assistants` 无脚本；`common` 为共享辅助 |
| G-089 | `client/conversation/storage/browser.cljs` 与 `storage.cljc` | R | client-audit | 另见 G-093：该模块生产未接线，先接线再补测试 |

---

## 5 四类之外的硬线索

### 5.1 死代码 / 未接线

| 编号 | 位置 | 证据 | 分类 | 归属 | 动作 |
|---|---|---|---|---|---|
| G-090 | `src/hc/server/app/maintenance.clj`（80 行） | 全仓仅自身 `ns` 行；`app.clj:36-46` 使用 `restore-supervisor`，整个“停机→离线恢复→重启”实现已被取代 | D | server-audit | 删除 |
| G-091 | `src/hc/server/backup/domain.clj`（7 行） | 无任何 `require`；仅 `backup/AGENTS.md:281` 提及 | D | server-audit | 删除，或改为 `backup.clj` 公开门面的一部分 |
| G-092 | `src/hc/server/backup/target.clj`（19 行） | 无任何 `require` | D | server-audit | 删除，或让 `backup.clj` 改用它 |
| G-093 | `src/hc/server/backup/recovery_store.clj`（7 行） | 无任何 `require` | D | server-audit | 删除，或让 `backup.clj` 改用它 |
| G-094 | `src/hc/client/conversation/storage/browser.cljs`（73 行）+ `storage.cljc`（68 行） | browser 适配器无生产代码 `require`；`storage.cljc` 仅被 `test/hc/client/conversation_storage_test.clj` 引用 | D | client-audit | 二选一：接入会话面，或删除并同步 `conversation/AGENTS.md:38-39,202-207` |
| G-095 | 草稿持久化双实现 | 生产走 `client/workbench/storage.cljs`（JSON、key `hc.workbench.v1`）；conversation 一套是 EDN、key `hc.client.conversation.drafts.v1`，仅测试使用 | D | client-audit | 统一为一套；若保留两套，必须在文档写明各自适用面 |
| G-096 | `src/hc/server/artifacts.clj:40-41` / `integration.clj:57-58` | `register-shell-output!` 只有定义与透传，**无生产调用方**（仅 test/ 调用）；`server/artifacts/AGENTS.md:270-272` 已自述“调用方未接入” | D | server-audit | 在 Owner 工具结果/Shell 结果路径接线，或删除该端口并下调文档承诺 |
| G-097 | `src/hc/client/artifacts/view.cljs` | 只有 open/download，无删除；`server/artifacts.clj:42` 有 `retract!`；`client/artifacts/AGENTS.md:132` 自述“删除/撤回未接 UI” | R | client-audit | 接删除/撤回确认流程；若根规格不要求，改文档标非目标 |
| G-098 | `src/hc/compose/owner/AGENTS.md:855-857` | `:calls/:conversation/:receipts` 随会话增长，“compaction policy is not implemented yet”；`server/history` 有可撤销压缩，Owner 侧无策略 | R | compose-audit | 设计并实现 Owner 状态压缩，或把上限与拒绝行为写成显式契约 |

### 5.2 文档漂移

| 编号 | 位置 | 现状 | 分类 | 归属 | 动作 |
|---|---|---|---|---|---|
| G-099 | `HACK.md:106` | “骨架交付时全量 `clojure -X:test` 未完成” | R | docs | 改为历史记录，并指向最新验证账本 |
| G-100 | `HACK.md:279` | `clojure -X:test` 仍标 `[NOT RUN]` | R | docs | 重跑后记录测试数/断言数/seed；未跑则保留 NOT RUN |
| G-101 | `HACK.md:281` | E2E 01–22、F01–F15、V01–V08、T01–T13 全 `[NOT RUN]` | R | docs | 按实际执行情况逐项更新 |
| G-102 | `HACK.md` §8.4 第 2 条 | 称“当前源码没有 `-main` 或系统装配入口”、gateway/owner “处于计划态”；实际 `server/app.clj` 有 `-main`、`system.clj` 有完整装配、`pixi.toml` 有 `app`/`app-check` | R | docs | 删除过期结论，改为当前入口与冒烟命令 |
| G-103 | `src/hc/server/artifacts/AGENTS.md:267-269` 与 `:270` | “应用装配已通过冒烟”与“调用方未接入”并存，易误读 | R | docs | 明确区分“HTTP 组件已冒烟”与“业务调用方未接线” |

### 5.3 构建与交付

| 编号 | 位置 | 现状 | 分类 | 归属 | 动作 |
|---|---|---|---|---|---|
| G-104 | `deps.edn:10-12` | `:dev {:extra-paths ["dev" "test"]}` 指向不存在的 `dev/`；`pixi.toml` 的 `repl = "clojure -M:dev"` 因此少一个源码根 | D | release | 删除 `"dev"` 或补目录；二者只能留一个 |
| G-105 | `HACK.md:279-281` | 仓库没有当前“全量测试已通过”的记录；NOT RUN 条目未被后续覆盖 | R | release | 合并后单 JVM 全量重跑，记录测试数/断言数/failures/errors/退出码 |

---

## 6 建议处理顺序

原则：先删死代码与修文档漂移，再接线，再补测试。理由：死代码会误导后续读者与测试；过期文档会让修复者按错误前提工作；接线前补测试会锁死错误形态。

### 阶段 1：删除死代码与临时脚手架（1–2 天）

| 顺序 | 条目 | 出口条件 |
|---|---|---|
| 1.1 | G-090、G-091、G-092、G-093、G-009 | 删除后 `rg` 无残留引用；`deps.edn`/`shadow-cljs.edn` 不含对应入口 |
| 1.2 | G-094、G-095（二选一） | 草稿持久化只剩一套格式与一个 key；`conversation/AGENTS.md` 同步 |
| 1.3 | G-104 | `clojure -M:dev` 的 classpath 不再包含不存在路径 |

### 阶段 2：修文档漂移（1 天，可与阶段 1 并行）

| 顺序 | 条目 | 出口条件 |
|---|---|---|
| 2.1 | G-099、G-100、G-101、G-102、G-105 | `HACK.md` 不再声称“没有 `-main`”；NOT RUN 与实测分开记录 |
| 2.2 | G-103 | artifacts 文档区分“组件冒烟”与“调用方接线” |
| 2.3 | G-052～G-062，优先 G-053、G-056 | 每个有源码的包目录有 `AGENTS.md`，或上层文档有逐文件表 |
| 2.4 | G-008 | `swarm_stm/commands.clj:1311` 注释与 `AGENTS.md` §10 阶段叙述一致 |

### 阶段 3：接线（3–5 天）

| 顺序 | 条目 | 出口条件 |
|---|---|---|
| 3.1 | G-096 | Owner/Shell 结果路径真实调用 `register-shell-output!`，或删除端口并下调文档 |
| 3.2 | G-097 | 产物删除/撤回有确认流程；或文档标非目标 |
| 3.3 | G-098 | Owner 状态压缩有上限、触发条件与拒绝行为 |
| 3.4 | G-095（若阶段 1 选择保留两套） | 两套格式的适用面在文档中写清 |

### 阶段 4：补测试（5–8 天）

| 顺序 | 条目 | 出口条件 |
|---|---|---|
| 4.1 | G-048 | 同步事件集合锁死测试：未知事件必须显式失败 |
| 4.2 | G-063、G-064、G-088 | workbench 未覆盖 view 有 DOM 脚本；browser/react 有渲染测试 |
| 4.3 | G-065～G-070、G-081～G-086 | extensions/speech/search/storage/shell 的传输、provider、迁移、导出有直接测试 |
| 4.4 | G-071～G-080、G-087、G-089 | 其余模块补齐；先接线再测 G-089 |
| 4.5 | G-040 | 给 fleet effect 集合加断言/测试，防止新增 effect 静默落到 `:UNSUPPORTED_FLEET_EFFECT` |

### 不建议做（N 类）

| 条目 | 理由 |
|---|---|
| G-010（内容搜索） | 内容搜索是 `files/AGENTS.md` 明示的非目标；保持 501 拒绝 |
| G-011（搜索服务类型） | 只实现三种搜索服务是 `search/AGENTS.md:64` 的规格 |
| G-049（文档容器） | DOCX/PPTX/EPUB 等容器是 `documents/AGENTS.md:243-245` 的非目标 |
| G-050（非文本文档抓取） | 非文本文档抓取是 `retrieve` 的显式拒绝 |
| G-008（内核动作替身） | 内核不执行文件/Shell 副作用是分层决定；执行在 `hc.compose.workspace` |

---

## 7 条目索引

| 区段 | 编号范围 | 条数 |
|---|---|---|
| 1.1 TODO 引用 | G-001～G-007 | 7 |
| 1.2 占位词 | G-008～G-016 | 9 |
| 1.3 显式拒绝 | G-017～G-050 | 34 |
| 1.4 comment/nil | G-051 | 1 |
| 3 包文档缺失 | G-052～G-062 | 11 |
| 4 测试覆盖 | G-063～G-089 | 27 |
| 5 硬线索 | G-090～G-105 | 16 |
| **合计** | | **105** |

> 核对：1.3 为 34 条（19 规格内 + 12 防御性 + 3 需复核），1.2 为 9 条，1.1 为 7 条，1.4 为 1 条，合计 51；第 3 节 11 条，第 4 节 27 条，第 5 节 16 条，总计 **105** 条。分类统计：R 47、N 5、D 9、X 44。

### 7.1 分类统计复核

| 分类 | 条目 |
|---|---|
| R | G-048（1）、G-052～G-089（11 + 27）、G-097、G-098、G-099～G-103（5）、G-105 = 47 |
| N | G-008、G-010、G-011、G-049、G-050 = 5 |
| D | G-009、G-090～G-096（7）、G-104 = 9 |
| X | G-001～G-007（7）、G-012～G-047（36）、G-051 = 44 |
| **合计** | **105** |
