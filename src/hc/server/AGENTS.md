# hc.server AGENTS.md — 服务端适配层

> 权威顺序：根 `AGENTS.md` > `src/AGENTS.md` > 本文件。
> 本文件定义服务端适配边界；协议语义以根 §5、§7.1、§7.2、§7.3 为准。

## 1 责任

服务端把领域状态与外部世界连接起来，但不替 Owner 做业务决策：

1. **存储适配器**：用窄接口封装嵌入式数据库。完整责任是 `read-session-view`、`commit-migration!`、`list-open-obligations`、`manage-blob-refs!`、`open-close-store!`。提交一次领域迁移后返回可用于后续守卫判断的一致状态视图。
2. **Blob 区**：长输出、大附件与二进制产物先写入受管临时位置，完成后生成带大小与摘要的稳定内容 ID，再在数据库事务中建立引用。清理器只回收超过安全窗口且无当前引用的对象。
3. **模型端口**：只使用 LangChain4j 低层 `ChatModel` / `StreamingChatModel` / `ToolSpecification`。对外只暴露 `(start-request! [port request receiver])` 与 `(cancel-request! [port handle])`。
4. **Clojure 沙箱执行器**：`hc.server.program.*` 执行 `defprogram`、角色能力面板、批内内存视图、`park` / `unpark`、`where` / `whereAll`、期限、call-id 幂等与受保护提交。文件预演与 `stamp` 比较通过 `:workspace` 端口调用 `hc.core.workspace-gate`，不在适配器里重写；Shell 与看板原语通过 `:call` / `:control` 端口交给组合层。
5. **Sente / AG-UI 网关**：发送 `RUN_STARTED`、文本流、工具调用流、`STATE_SNAPSHOT`、`STATE_DELTA`、`MESSAGES_SNAPSHOT` 与项目 `CUSTOM` 事件；为每个新订阅提供原子的“基线快照 + 同步 epoch/序号”边界。
6. **配置、恢复、备份与外部适配**：解析供应商连接、模型角色与能力范围；启动时扫描未结记录；提供备份、恢复与未知结果核对入口；连接 Ollama、浏览器、桌面应用等外部服务。
   进程级安全恢复由 `hc.server.app.restore-supervisor` 负责：实时预览并在
   `:confirm? true` 后暂存备份、关闭命令门、排空在途 handler、确认全部
   writer 停止、运行 offline restore、重启系统，并把持久 journal 暴露给
   重连客户端。停止未确认时绝不恢复；恢复计划 fingerprint 变化时在写入前
   拒绝；进程在恢复中重启时不自动重跑。详见 `src/hc/server/app/AGENTS.md`。
7. **受管浏览器**：`hc.server.browser.*` 通过 `BrowserPort` 执行导航、观察、动作与截图；服务持有页面、权限范围、页面卡与对象级接管状态；`cdp` 适配器只连接或启动 CDP，不关闭操作员共享的浏览器。敏感字段、过期目标和未知结果按 §7.4 旅程 6.4（开浏览器） 与 §6.4 处理。
8. **只读工作区文件 API**：`hc.server.files.*` 通过 `hc.core.workspace-gate` 提供列表、`stat`、文本读取与名称搜索；浏览器路径限制在工作区根内，stamp 变化返回 `:FILE_CONFLICT`，不写文件。接口与错误码见 `src/hc/server/files/AGENTS.md`。
9. **联网搜索与全文读取**：`hc.server.search.*` 只连接本轮显式选中的独立搜索服务，把真实返回结果交给 `hc.server.materials.search` 做 URL 合并、发布日期与抓取时间分离、引用编号；全文读取只针对已在引用集合中的 URL，且不向目标网页发送搜索凭据。接口与边界见 `src/hc/server/search/AGENTS.md`。
10. **文档提取与 OCR**：`hc.server.documents.*` 只按显式 `:native` / `:text` / `:ocr` 路线工作；文本严格解码，PDF 保留原页码与加密/权限边界，图像应用 EXIF 方向与长边限制，OCR 使用本地 CPU Tesseract 与内置语言数据。空页、空 OCR、缺语言与不可用引擎都是带证据的拒绝，不自动换路线，不伪造文本。接口、错误码与限制见 `src/hc/server/documents/AGENTS.md`。

## 2 不得

1. 不启用 LangChain4j 高层 `AiServices` 自动工具循环，不用其 `ChatMemory` 替内核管理历史。
2. 不把线程句柄、HTTP 连接、取消函数等进程内临时资源写入持久领域状态。
3. 不在供应商回调线程内直接写库或启动子任务；回调只复制数据、附上调用身份并投递到 Owner 或有界流合并层。
4. 不用 `Thread/interrupt` 打断数据库事务提交或 `FileChannel` 写入线程。
5. 不在存储打开失败时静默新建同名空库；必须停止派发副作用工作并提供恢复入口。
6. 不关闭用户共享的 Ollama、浏览器或桌面服务；连接不等于拥有生命周期。
7. 不把未知用量记为 `0`，不把供应商未返回的字段伪造成已确认值，不在日志中写入密钥或认证头。
8. 不把 Sente 实时通道当作大对象通道。单条消息遵守 1 MiB 边界；长日志、大附件与导出包走独立 HTTP/Blob 通道，只传内容 ID、状态与就绪通知。超限的 `STATE_SNAPSHOT` / `STATE_DELTA` 序列化为 EDN 大对象，经 `/sync-objects/<token>` 传输；Sente 只发引用，不提高上限。
9. 不在流式工具参数尚未完整时执行工具；参数完整且基本校验通过后，才向 Owner 发布完整工具调用请求。
10. 不在搜索失败时改用另一个未选服务，不把空结果或失败结果标成“已联网”，不把抓取时间冒充发布日期，不把搜索密钥或认证头写进错误、日志、诊断包或公开配置视图。

## 3 关键边界

### 3.1 存储

- 主状态走嵌入式库；人工迁移与跨库升级走带显式数据格式版本号的 EDN/JSON 导出包。
- 删除区分当前业务删除、应用保留清除与介质级法医擦除；前两者不宣称已抹除外部分布式副本。
- 可重建派生索引命中后必须回主库核对实体当前版本仍存在且有效。

### 3.2 模型调用

- 每次请求固定不可变输入快照；重试保留 `call-id`、更换 `attempt-id`。
- 应用模型网关在出站副本上注入 Reviewer/Swarm 角色系统提示与
  `clj-review`/`clj-worker` 程序工具 schema；`hc.server.app.prompts`
  只做纯装饰，程序运行时仍是能力边界的唯一执行者。Plan 顶层与 Plan
  dedicated worker 使用各自的面板，不在此处装饰。
- 严格区分业务继续轮次与上游故障重试；故障重试进入显式 `:retry-wait`，记录 `:retry-at` 与已用次数。
- 供应商不返回全局唯一 `call-id` 时，由适配器分配项目级稳定 `call-id`，并维护与供应商 ID、`attempt-id`、输出索引的映射。
- 用量区分输入、输出、缓存与未知；流式部分统计与最终完整统计按覆盖规则归并，防重复累加。

### 3.3 网关

- 客户端重连后先进入 `:connected-awaiting-sync`，取最新 `STATE_SNAPSHOT` 与同步序号，再按原 `command-id` 查询未决回执。
- 旧连接或旧订阅的迟到增量按订阅代号丢弃；慢客户端积压过多时退回“等待新快照”，不反向阻塞会话执行。
- 永久折叠或删除时，用显式项目扩展字段或会话同步 epoch 重建来清除已移除的 `activity` / `reasoning` 显示记录，不依赖 `MESSAGES_SNAPSHOT` 的缺省语义。

### 3.4 有界输出

- `call` 内联输出默认 1 MiB；完整日志写入 artifact，单会话上限 64 MiB，超出截断并标 `:output-truncated`。
- LLM 写的报告全文内联，不截断、不摘要、不转 artifact。

### 3.5 搜索与读取

- `:mode :required` 至少尝试一次选中服务；没有任何成功服务响应时返回 `:must-search-failed? true`，调用方必须如实报错，不得用模型内部知识补成“已联网”。
- 空结果 `:status :empty` 是成功尝试，不是网络失败；两者在界面与日志中分别命名。
- 搜索服务类型必须显式给出，显示名不参与选择；请求未给出 `:service-id` 时返回 `:SEARCH_SERVICE_NOT_SELECTED`，绝不从已启用服务中挑默认值。
- 多密钥只在 `:rotation :on-auth-failure` 明确开启时逐个尝试一次；同一密钥不重试，缺第一个密钥不会静默改用第二个。
- 全文读取默认不跟随重定向，默认拒绝私有/回环地址；URL 必须已在引用集合中。读取被拒、截断或失败时保留已有标题与摘要。
- 发布到浏览器的配置只含凭据别名、环境变量名与是否可用；内联密钥不进入快照、错误或测试输出。

### 3.6 启动、默认会话与恢复投影

- **默认会话必须带 `:config`**：空库启动时
  `registry/create-session!` 的首个 “New session” 必须传
  `:config (live-session-config)`，使模型 binding 非空；省略 config 会让首屏
  新建会话显示未绑定模型，即使设置里已有模型。提交失败不创建 Owner/registry
  条目，启动返回 `:DEFAULT_SESSION_FAILED`，不静默留下半初始化会话。
- **恢复后连接重新投影**：备份恢复写回的是设置文档形状，不是进程内连接句柄。
  启动/重连后由 `settings` 投影重新构造连接与模型绑定；旧的 `:launch-connection-ids`/
  `:removed-connection-ids` 不能被备份携带，否则目标实例会把恢复连接误判为已撤销。
  恢复后连接默认未验证，调度器/模型端口只使用重新投影后的配置。
- **定时任务冻结模型**：创建/恢复 run 时把当时的 `:model` binding、
  `:assistant-id` 与 capability scope 冻结进 run；恢复后不读取“当前设置”替换
  既有 run 的模型。备份恢复的 schedule 默认 `:paused`，不自动补跑。
- **备份 CLI**：默认加密 `:aes-gcm`，`--encrypt true|false` 显式覆盖；
  `--include-deleted true|false` 控制已删除会话；无密码且未显式关闭加密时报
  `:BACKUP_PASSWORD_REQUIRED`。全新目录 restore 由 CLI 自带
  `:no-prior-app-commands? true`；重复 replace 返回 `:already-imported`。
  细节见 `src/hc/server/backup/AGENTS.md`。
- **merge restart 回执**：只有 `:complete`/`:no-changes`/`:already-imported`/
  `:applied` 且无 `:needs-attention?`/`:needs-recovery?` 才算成功；
  restart 失败不得报 complete，重复 merge 返回 `:already-imported`。
  详见 `src/hc/server/backup/AGENTS.md`。

复检：

```bash
clojure -X:test:server :nses '[hc.server.app.system-test
                               hc.server.backup.maintenance-test
                               hc.server.scheduler-test]'
```

失败模式：默认会话无 `:config` 会在首屏显示未绑定；恢复连接若沿用旧
`:launch-connection-ids` 会显示已撤销；调度器读取当前设置会替换既有 run 的
冻结模型；把 `:restart-failed` 映射为 `:complete` 会伪造成功。

## 4 命名空间

当前：`hc.server.blob-store.*`、`hc.server.browser.*`、`hc.server.commands.*`、
`hc.server.artifacts.*`、`hc.server.documents.*`、`hc.server.gateway.*`、`hc.server.history.*`、`hc.server.model-port.*`、
`hc.server.model-auth.*`、
`hc.server.program.*`、`hc.server.search.*`、`hc.server.speech.*`、`hc.server.storage.*`、
`hc.server.recovery.*`、`hc.server.backup.*`、`hc.server.scheduler.*`、
`hc.server.history.context`、`hc.server.materials.memory-suggest`。

`hc.server.artifacts` 负责产物四步提交中的完整性检查、元数据登记与
打开/下载投影；字节仍属于 `hc.server.blob-store`，引用仍走
`manage-blob-refs!`。文档见 `src/hc/server/artifacts/AGENTS.md`。

`hc.server.history` 负责消息版本、编辑、分支复制语义、上下文边界与可撤销
压缩。它是纯领域层，不启动 run、不重放工具调用、不删除附件；命令通过
`sessions/session-port` 的 `:delegate` 进入，文档见
`src/hc/server/history/AGENTS.md`。

`hc.server.model-auth` 负责第三方模型账号的 OAuth/设备码登录、回环或应用
回调、令牌刷新与加密凭据存储。浏览器命令只看到流程状态和账号标识；访问
令牌、刷新令牌、设备码与客户端密钥不进入回执、快照、日志或错误。文档见
`src/hc/server/model_auth/AGENTS.md`。

存储引擎已选定为 Datalevin 1.2.10 KV，放在 `deps.edn` 的 `:server` 别名（恰好一个候选）。
适配器接口、键布局、原子提交与读屏障、非破坏性打开规则见
`src/hc/server/storage/AGENTS.md`；恢复的六个窗口见
`src/hc/server/recovery/AGENTS.md`；语音输入与朗读的服务边界见
`src/hc/server/speech/AGENTS.md`；原生库与事务可见性的实测结果见根 `HACK.md` §5.1。

`hc.server.app.owner-store` 在每次 Owner 状态提交的同一 `commit-migration!`
中，把仍未确认的子报告镜像为 `:report-pending` 未结记录（`report/<report-id>`），
确认提交则以 `:obligations {:remove [...]}` 原子移除。启动恢复因此能在 Owner
启动前看到未结报告；应用装配的 `hc.compose.owner/report-bridge` 负责跨 Owner
投递与确认回程，找不到父 Owner 时保持记录打开并按指数退避重试，绝不标记失败。
持久化报告路径的验收见
`test/hc/server/app/owner_report_restart_test.clj`。

## 5 测试

1. 存储契约测试：同一组迁移在重开存储后得到一致视图；提交返回读屏障后的最新状态；关闭顺序不丢未结责任。
2. 模型端口测试：用假端口注入流式片段、重复工具调用、丢响应、429/5xx、超时与取消；验证 `attempt-id` 隔离、工具参数完整性与用量归并。
3. 网关测试：重连、旧订阅增量、快照替换、永久折叠、1 MiB 边界与迟到回包。
4. 沙箱测试：`stamp` 冲突、dry-run 拒绝、`:applying` 期间取消、记账重试不重放文件写入；只用测试自建临时工作区。
5. 安全测试：日志与诊断包不出现密钥；恢复失败不初始化空库；外部服务连接失败给出可读原因。
6. 文档测试：用真实生成的文件跑文本/PDF/图像/OCR；页码保持原文档身份，扫描页必须显式 `:no-text`，OCR 空结果与缺语言不得伪造文本；密码不进入结果或日志。命令为 `clojure -M:test:server -d test/hc/server/documents`。
7. 受管浏览器测试：纯模型覆盖接管、敏感字段、页面卡上限与诚实报告；假端口覆盖导航、观察、动作、暂停、恢复重读、工作台 `command!` 适配器与未知结果；真实 Chrome 冒烟覆盖 CDP 导航、观察、输入、敏感拒绝、显式接管、恢复重读、页面状态保留、HTTP 错误分类与关页；应用集成测试启动真实 `hc.server.app.system`，验证 `[:browser :endpoint]`、会话浏览器服务与命令适配器。无 Chrome 时只跳过真实测试，不以假端口替代。
8. 搜索测试：回环 HTTP 假服务覆盖 SearXNG、Brave、Tavily 请求形状与结果归一；必搜失败、空结果、部分成功、取消、密钥轮换、无回退、URL 合并、发布日期未知、全文读取截断/受阻、私有地址拒绝与秘密不泄漏。真实外部搜索服务不作为测试前提。

服务端测试使用 `clojure -X:test:server` 或 `clojure -M:test:server -n <namespace>`。需要模型或数据库的测试用显式假端口或临时目录，不连接真实用户数据。
