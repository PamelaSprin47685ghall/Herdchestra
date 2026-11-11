# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

编号不重排、不复用：已关闭条目（如 D-01、D-02、D-03、D-04、D-05、D-06、D-08–D-12、D-13–D-20、D-22、D-23、D-32）从本文件删除；历史状态见 git 历史与 `plans/debt-status-0.0.1.md`。新增条目从 D-38 起连续编号（D-38–D-46 已占用；D-32、D-38 已关闭但编号不复用）。

## D-31 MSYS2/UCRT64 子环境绑定未实现

- **条款**：`AGENTS.md` §7.2（环境供应：Pixi 与 MSYS2 的职责分工）。
- **缺口**：`src/hc/server/shell/process.clj:49-55` 的 Windows 分支只把字符串命令交给 `cmd.exe /c`，没有绑定 MSYS2/UCRT64 子环境，也没有把项目声明的 Unix 工具链注入 `PATH`、`MSYSTEM`、`CHERE_INVOKING` 等变量。`src/hc/server/artifacts/AGENTS.md:294-295` 的 Windows 项仍标 `NOT RUN`。
- **影响**：Windows 上的 holder/Plan dedicated worker 无法按 spec 使用项目的 bash 工具链；路径分隔符、权限与信号语义与 Unix 不一致，跨平台验收缺少可重复环境。
- **建议**：显式探测 MSYS2/UCRT64 的 `bash.exe`，按会话构造环境（`MSYSTEM=UCRT64`、工作区路径转换），保留 `cmd.exe /c` 作为无 bash 时的诚实回退；补 Windows 冒烟并把运行范围写入证据。

## D-33 单会话 64 MiB artifact 累计上限在捕获阶段未知剩余预算

- **条款**：`AGENTS.md` §7.2（artifact 64 MiB 上限）与附录 §8.6 产物提交流程。
- **缺口**：`src/hc/server/artifacts/registry.clj:505-555` 的 `ensure-session-capacity!` 只在登记时检查会话累计字节；`src/hc/server/artifacts/integration.clj:115-120` 把 `:max-bytes` 与 `:session-limit-bytes` 都设为同一个上限；`src/hc/server/shell/output.clj:21-23,63-80` 的捕获器只接收单 artifact 的 `:artifact-limit`，不知道本会话已用掉多少。捕获仍可能写出一份必然在登记时被 `:ARTIFACT_SESSION_LIMIT` 拒绝的文件。
- **影响**：用户付出等待后拿不到产物；临时文件与诊断更嘈杂，且截断/拒绝发生得过晚。
- **建议**：把会话剩余预算传入捕获器（或由捕获器先查询现有字节），在写入前截断/拒绝并给明确理由；登记仍保留最终权威检查。

## D-34 artifact 登记失败被静默吞掉

- **条款**：`AGENTS.md` §7.2 四步产物提交流程与附录 §8.6。
- **缺口**：`src/hc/server/app/system.clj:2472-2482` 的执行器终态回调用 `(try ... (catch Throwable _ nil))` 包住 `artifacts-integration/register-executor-result!`；登记或宣告抛出时没有诊断、没有重试、没有用户可见回执。
- **影响**：产物可能已写入工作区却永远不登记、不宣告；用户看不到卡片，排障也找不到原因。
- **建议**：捕获后记录结构化诊断（至少 `:code`、`:tool-call-id`、`:path`、异常消息），把登记失败放入可重试队列或 Owner outbox，并向用户给“产物未登记”的未交付理由。

## D-35 artifact 第 1 步未校验文件属于任务工作区

- **条款**：`AGENTS.md` §7.2（“任务工作区生成 → 检查 → 登记 → 宣告”）与 §4.2 受保护提交边界。
- **缺口**：`src/hc/server/artifacts/registry.clj:527-556` 的 `check-source!` 只校验文件存在、非空、大小与可选 size 声明；`:workspace-root` 与 `:relative-path` 仅被记录到元数据（`registry.clj:177-179,427-430`），未强制 `:file`/`:source` 的真实路径位于该工作区内。调用方可以登记任意绝对路径。
- **影响**：产物来源可以撒谎；越界文件被当作任务产物宣告，破坏“任务工作区生成”的边界与审计可信度。
- **建议**：登记前规范化真实路径并验证位于 `:workspace-root` 内（符号链接按真实路径判断）；显式操作员导出可走单独入口，不复用产物登记。

## D-36 announcement 返回 nil 被当成成功

- **条款**：`AGENTS.md` §7.2 第 4 步“向用户宣告可用”与附录 §8.6。
- **缺口**：`src/hc/server/artifacts/integration.clj:35-47` 的 `register-and-announce!` 只在 `(false? published)` 时抛 `:ARTIFACT_ANNOUNCEMENT_FAILED`；`publish!` 返回 `nil`（例如没有通道或回调漏返回）会被当作成功，随后 `mark-announced!` 把产物标成已宣告。
- **影响**：产物被持久标为“已宣告”，但用户从未收到事件；重试路径也不会再补发。
- **建议**：把“接受”定义为明确的 truthy 结果（或要求 `{:ok? true}` 形状）；nil 视为未接受并保留未宣告状态；没有 publisher 时显式区分“未配置宣告通道”。

## D-37 Owner chunk 未合并，`:calls`/`:conversation`/`:receipts` 无按条数保留策略

- **条款**：`AGENTS.md` §5.1（单 Owner 状态迁移）、§6.1（流式阅读）与 §8.4 变更检查表。
- **缺口**：`src/hc/compose/owner/AGENTS.md:1016-1026` 仍记录两项缺口：每个流式 chunk 逐次提交（未在 `deliver!` 前合并相邻 chunk，`src/hc/compose/owner/handlers.clj:511-515`、`state.clj:874-878` 仍是单 chunk 追加）；`:calls`、`:conversation`、`:receipts` 随会话增长，没有按条数/世代的保留或折叠策略。新增的 `src/hc/compose/owner/compact.clj` 与 `runtime.clj:369-380` 只在大状态超过阈值时压缩长字符串/回执载荷，不限制条目数量，也不改变 chunk 提交粒度。
- **影响**：长流式回合放大持久层写放大；长会话的 Owner 整态 CAS 会越来越慢，最终逼近文档大小/提交成本边界。
- **建议**：在 dispatch 的末端按时间窗或字节阈值合并相邻 chunk，一次提交一个合并块；为 `:calls`/`:conversation`/`:receipts` 定义显式保留/折叠策略（保留权威回执与未结调用，折叠已结算的细粒度记录），并保留现有按大小的 payload 压缩作为独立层。

## D-39 高对比主题未进入机器对比度校验（静态 CSS 仅 color-scheme）

- **条款**：`AGENTS.md` §7.4 旅程 1.1（正文对比度 ≥ 4.5:1、大字/关键图形 ≥ 3:1）。
- **缺口**：`resources/public/css/workbench.css:69-71` 的 `html[data-contrast="high"]` 只声明 `color-scheme: light`，没有颜色变量；`test/hc/client/workbench/contrast_test.clj:62-66,392-410` 以 `root + light + high` 解析高对比主题，因此 high 的可计算颜色等于 light。运行时的 `src/hc/client/appearance.cljs:15-23` 另注入一份高对比变量（黑字、白底、深 accent/danger/warn/ok、无阴影），但对比度测试不读取该注入样式；E2E 19 的 `collectContrast` 也只覆盖 light/dark。
- **影响**：产品声明“高对比”时，机器证据实际只证明 light；注入样式与静态 CSS 可能继续漂移，且 high 主题的 muted 文本、边框与状态色未纳入阈值校验。
- **建议**：把高对比 palette 收敛进可解析的 `workbench.css`（或让 contrast 测试解析 `appearance.cljs` 的注入文本）；为 high 主题增加独立的正文、muted、边框、状态色探针，并在 E2E 19 加 high 采样。
- **证据**：`resources/public/css/workbench.css:69-71`；`test/hc/client/workbench/contrast_test.clj:62-66,392-410`；`src/hc/client/appearance.cljs:15-23`；`test/e2e/e2e-19-ime-a11y.spec.mjs:354-367` 只采样 light/dark。

## D-40 banner 提示背景使用 color-mix，无法机器计算对比度

- **条款**：`AGENTS.md` §7.4 旅程 1.1（对比度门槛）。
- **缺口**：`src/hc/client/conversation/view.cljs:161-163` 的 `.hc-banner-error/-warning/-info` 使用 `color-mix(in srgb, var(--wb-…) N%, var(--wb-surface))` 作为背景；`test/hc/client/workbench/contrast_test.clj` 无法解析 `color-mix`，其探针也没有 banner 项，E2E 对比度采样同样未覆盖 banner。
- **影响**：三类用户可见的告警/状态 banner 的正文对比度没有机器证据；主题或 token 改变后可能低于 4.5:1 而不被发现。
- **建议**：优先改用可解析的实色 token 或预先计算的双层 fallback，并让 contrast 测试解析 `color-mix`；同时为 `.hc-banner-*` 增加 light/dark/high 探针。
- **证据**：`src/hc/client/conversation/view.cljs:161-163`；`test/hc/client/workbench/contrast_test.clj` 的探针没有 banner；E2E 对比度采样同样没有 banner 项。

## D-41 `/healthz` 的 `session-count` 仍为 O(N) 索引扫描

- **条款**：`RUNNING.md`「健康检查」（`GET /healthz`）与 `AGENTS.md` §8.5 启动健康要求。
- **缺口**：`src/hc/server/app/system.clj:5566-5580` 与 `5789-5801` 的 `:session-count` 调用 `session-ports/list-session-ids`，返回包含全部会话 ID 的索引文档后取 `count`；这不是常量时间计数。已有测量为 1000 会话约 1.6 ms。
- **影响**：当前成本可接受，但 `/healthz` 随会话数线性增长；轮询、启动探针与大库会放大读取与内存分配。
- **建议**：在 `sessions` 分区维护与索引更新同事务的 committed session count，健康端点读取该计数；若继续接受 O(N)，在 `HACK.md` 记录预算、测量口径与超过阈值时的降级为 `:unknown`。
- **证据**：`src/hc/server/app/system.clj:5566-5580,5789-5801`；`src/hc/server/app/session_persistence.clj:443-445` 返回完整 `:ids`；已有测量为 1000 会话约 1.6 ms。

## D-42 CLJS runner 的失败退出码缺少回归守卫

- **条款**：`src/AGENTS.md` §5（测试必须真实失败）与 `AGENTS.md` §8.4（验证矩阵）。
- **已修复部分**：`test/js/run_*.cjs` 曾出现 cljs.test 打印失败但进程仍 `exit 0` 的假绿；现 7 个 runner 捕获 `console` 输出里的 `N failures, M errors` 摘要，并在 `beforeExit` 强制把 `process.exitCode` 置 1，同时保留显式设置/解析 `process.exitCode`。合成探针已验证旧 runner exit 0、新 runner exit 1。
- **剩余缺口**：没有一个**故意失败**的持久化回归测试证明 `scripts/test-cljs-gate.sh` 会在失败时返回非零；合成探针目前只是一次性证据。
- **影响**：未来重构 runner、替换编译入口或改变 `beforeExit` 时序时，退出码传播可能再次退化为假绿，而正常全绿门禁无法发现。
- **建议**：把合成探针固化为仅在 CI/显式开关下启用的必失败 fixture（或注入一个 failing test namespace），断言 Node/harness 返回非零、日志包含失败摘要，且门禁将其计入 failed；正常门禁不运行该 fixture。
- **证据**：上述 runner 的 `console` 摘要捕获与 `beforeExit` 处理；`scripts/test-cljs-gate.sh` 按 `timeout ... bash "$script"` 的退出码统计 PASS/FAIL；`HACK.md` §17.21 的合成探针；仓库仍无持久化负向退出码测试。

## D-43 0.0.2 tag 曾发布 0.0.1 版本号（已用 fixup 修正，保留为流程记录）

- **条款**：发布流程（版本 manifest、tag 与源码树一致性）。
- **事故**：`v0.0.2` 原树的四个 manifest 版本号仍全是 `0.0.1`，推送后成为「自称 0.0.1 的 0.0.2」。涉及 `src/hc/version.cljc`、`pixi.toml`、`package.json`、`package-lock.json`。
- **修正**：现 `v0.0.2` 指向 fixup 提交 `721c1b2`（annotated tag object `cb87d76`），其父为 `1623d4e`（原 0.0.2 source release，freeze tree `956136d0`、bundle `fd5e1f90`）。`git diff --name-status v0.0.2^{commit}^ v0.0.2^{commit}` 恰好 4 行、全部 `M`；`4 files changed, 5 insertions(+), 5 deletions(-)`；`package-lock.json` 第 13 行起逐字节相同（58302 B = 58302 B），依赖树零改动。
- **远端**：`refs/heads/master = 721c1b2…`（原 `379b021`）；`refs/tags/v0.0.1^{} = 40d01c8…`；`refs/tags/v0.0.2^{} = 721c1b2…`；无其它 `v0.0.*` 标签。本地保底分支 `release-0.0.2-source`（指向 `1623d4e`）未推送。
- **状态**：已修正并推送；本条是已关闭事故的保留记录，不进入待办队列。临时 index 的复现命令骨架见 `HACK.md` §17.20。
- **建议**：发布前用 `scripts/release-gate.sh` 的六处版本断言；tag 推送前核对 `v0.0.x^{commit}` 的四个 manifest 与源码/freeze tree；修正 tag 时用 `GIT_INDEX_FILE` 临时 index，禁止 `reset --hard` 或重建源码提交。
- **证据**：`HACK.md` §17.20；`plans/0.0.3-release-notes.md` 「0.0.2 标签修正」。

## D-44 新建会话回执到达前立即发送可能落入上一个会话

- **条款**：客户端会话创建/发送时序（E2E 稳定性要求：同类竞态必须在 UI 侧门控）。
- **缺口**：用户点「新建会话」后，在 `:session/create` 回执到达前立刻发送，客户端尚未切换 selected session，消息可能落进上一个会话。E2E 侧已用 `newSession` 回执门控规避；产品侧未加门控。
- **影响**：消息错投、草稿错位；用户看到会话归属错误，且后续上下文可能挂到错误会话。
- **建议**：在 `:session/create` 回执前禁用发送，或把发送排队到 selected-session 更新之后；也可让命令携带 expected session id 并在回执校验，不匹配则拒绝而不是落盘。
- **证据**：本轮 E2E 新建会话用例使用 helper 等待 `:session/create` 回执；产品侧无门控。
- **补充证据（2026-11-11）**：E2E 19 在全新 data-dir 上稳定复现了同一竞态更隐蔽的一面。服务端启动时会预建一个空默认会话，因此 `newSession` 的「当前会话已有消息」判据恒为假，helper 点击后立即返回、不等回执；紧随其后的 Enter 命中本地门控 `:connection-not-ready`，消息被静默丢弃（草稿保留、无气泡），页面看似「连接中断」实为 teardown 后的遗照。测试侧已改为先等发送按钮可用再按 Enter；**产品侧门控仍未实现**，即本条缺口依旧成立。

## D-45 浏览器录音（WebM）无法使用云端语音识别

- **条款**：云端 STT 必须能验证录音容器时长（安全边界）。
- **位置**：`src/hc/server/speech.clj` 的 `:processing :cloud` 分支要求容器时长可验证；`src/hc/server/speech/audio.clj` 的 `duration-ms` 仅解析 WAV。
- **缺口/现象**：客户端 `POST /speech/recordings` 返回 400 `SPEECH_DURATION_UNVERIFIABLE`，provider 从未被调用；UI 显示「the recording duration cannot be verified; a cloud service will not receive it」，录音保留但无法云端转写。本地（device）STT 不受影响。
- **影响**：用户在浏览器里录音后选择云端 STT 必然失败；本地 STT 路径仍可用。
- **现有取证**：`test/hc/server/speech/loopback_test.clj` 已把该拒绝固化为设计行为（安全性正确），因此这是**能力缺口**而非回归。
- **建议处理**：三选一——(a) 客户端录制后转 WAV 再上传；(b) 服务端实现可验证的 WebM 时长解析（EBML Duration 元素）；(c) 在 UI 明确禁用「浏览器录音 + 云端 STT」组合并给出替代路径。任选其一都需同时更新该回归测试与 E2E 15。
- **优先级**：中；不阻塞本次发布（本地 STT 路径可用，且拒绝行为安全）。

## D-46 E2E 21 的证据哈希口径与标准公式不可直接比较

- **条款**：E2E 证据必须使用统一、可比较的 `sourceTreeSha256` 口径。
- **缺口/现象**：E2E 21 使用自定义 `sha256(git HEAD tree + spec bytes)` 证据哈希，不等同于标准 `find src test` 公式。E2E 17 曾自带只 walk `src` + `test/e2e` 的私有实现（记录 `74c18e18…`），已在测试侧加固时移除；E2E 11/17 现在使用标准树。
- **影响**：跨 spec/门禁比较源树哈希会误报；E2E 21 的哈希不能直接与标准 tree 哈希对齐。
- **建议**：所有 spec 必须从 `test/e2e/support/evidence.mjs` 导入 `sourceTreeSha256`，禁止本地重写；确需自定义口径时必须在证据中显式标记，并在门禁比较时排除。
- **状态**：已知限制，不视为失败；已在 `HACK.md` §17.29 记录。

## D-47 devops worker 内部的 shell 动作不进 Owner 调用账本

- **条款**：`AGENTS.md` §5.3（未结责任与 `:outcome-unknown` 恢复窗口）、§4.4（`.call` 会话边界）。
- **位置**：`src/hc/server/app/devops.clj:121-126` 与 `src/hc/server/app/system.clj:2381-2395` 的 `call` helper 同步执行 shell；`src/hc/compose/owner/state.clj:1631-1660` 的 `recover-state` 只把 Owner 账本里 `:dispatching`/`:running` 的 call 标为 `:outcome-unknown`；`src/hc/server/app/snapshot.clj:308-327` 只发布这些 call。
- **缺口**：Plan dedicated worker 通过 `call` 工具启动的 shell 会话不写入 Owner 的 `:calls`。JVM 被 SIGKILL 时，worker 的 shell 确实在途，但重启后没有任何记录可标 `:outcome-unknown`，「结果待确认」面板不会出现。
- **影响**：worker 侧的外部副作用在硬崩溃后失去可核对入口，与 §5.3「可能产生外部副作用的 Shell 结果未知时优先核对」不一致。E2E 10 因此只能靠 Plan 顶层 `clj-plan` 的 call 记录来间接覆盖。
- **建议**：让 devops worker 的 `call` 也登记为 Owner 可恢复的动作（或让 worker 会话把在途 shell 事实汇报给父 Owner），使 `recover-state` 能覆盖它；同时补一条针对该路径的验收。
- **优先级**：中；当前 E2E 10 已用顶层 call 覆盖同类语义，但 worker 路径本身仍是盲区。

## D-48 `stop-tree!` 的 `:settle-ms` 未随 `:stop-grace-ms` 一起收敛

- **条款**：`AGENTS.md` §4.4（会话边界与停止语义）、§8.3 F11/F12。
- **位置**：`src/hc/server/shell/process.clj` 的 `stop-tree!`：`:settle-ms` 默认 150，`:stop-grace-ms` 由 `runner :limits` 传入（默认 2000，测试可设 300）。
- **缺口**：`stop-tree!` 在发第一轮信号前固定等待 `:settle-ms`，让 `sh` 完成 fork；该值不来自 `runner :limits`，调用点也无法按会话覆盖。
- **影响**：每次停止至少多等 150 ms；若某个会话把 `:stop-grace-ms` 设得比 150 ms 更短（测试里就是 300），实际停止时长由 `:settle-ms` 而非 `:stop-grace-ms` 主导，参数语义不一致。
- **建议**：把 `:settle-ms` 纳入 `runner :limits`（例如 `:stop-settle-ms`），并让 `session.clj` 的四个 `stop-tree!` 调用点统一透传；或按 `min(settle-ms, grace-ms)` 收敛。
- **优先级**：低；不影响正确性，只影响停止延迟与参数一致性。

## D-49 通知（notice）永不过期，可长期占据右上角

- **条款**：`AGENTS.md` §7.4-4（过程区与错误状态）、§7.4-8（未交付理由必须可读且可关闭）。
- **位置**：`src/hc/client/workbench/mutations.cljs:407-420` 只有 `notify` 与 `dismiss-notice`（需用户点 ×）；`src/hc/client/workbench/views/shell.cljs:472-476` 只渲染 `(take-last 3 notices)`。没有任何自动过期或去重。
- **缺口**：`src/hc/client/workbench/events.cljc:1048-1058` 在服务端报告本轮中断时加入一条「服务器报告本轮未完成」通知。它会一直留到用户手动关闭；同类事件也不会合并。
- **影响**：曾导致 `#wb-aux-toggle` 与 `#wb-aux-close` 在 1047 次命中测试中被通知的关闭按钮遮挡（见提交 `83ee20a` 的 CSS 修复，已把通知栈移出顶部栏、并把抽屉层级提到通知之上）。遮挡已修，但「通知永驻」本身仍会让界面越用越挤，且用户没有除逐条关闭之外的清理方式。
- **建议**：给非 `:danger` 通知加自动过期（例如 8–15 秒，鼠标悬停时暂停）；同 key 通知去重并计数；提供「全部关闭」。`danger` 保留手动关闭。
- **优先级**：低到中；遮挡症状已消除，剩下的是体验与可维护性。

## D-50 `#wb-aux-close` 与其他右上角控件缺少遮挡防护

- **条款**：`AGENTS.md` §7.4-1（关键操作必须在各视口可达）。
- **位置**：`src/hc/client/workbench/views/shell.cljs:419`（`#wb-aux-close`）；`resources/public/css/workbench.css:937-947`（toast-stack）；`:1015`、`:998`（抽屉 z-index）。
- **缺口**：右上角是 topbar、通知栈与抽屉关闭按钮的公共落点，三者此前同处或相近层级，任一浮层出现即可遮住另一个控件。当前修复把通知栈移出顶部栏并把抽屉提到通知之上，层级不再冲突，但没有任何测试或结构约束防止将来再次引入同层浮层。
- **影响**：历史上已发生两次（`#wb-aux-toggle` 与 `#wb-aux-close` 各一次）。表现是「按钮看得见但点不动」，且 Playwright 会重试到整个测试预算耗尽，真正原因不出现在报错里。
- **建议**：给右上角定义一个明确的层级序列（抽屉 > 通知 > topbar）并写成 CSS 变量注释；E2E 用 `clickUncovered`（已在 `e2e-19` 内）覆盖所有右上角控件；新增浮层时必须说明它在序列中的位置。
- **优先级**：低；已有测试侧防护。

## D-51 发送被拒时只有草稿留在框里，界面不说明原因

- **条款**：`AGENTS.md` §7.4-2（发送必须可解释）、§7.4-8（未交付必须给理由）。
- **位置**：`src/hc/client/conversation/view.cljs:1749` 的 `request-send!` 分支：
  `(contains? #{:disabled :duplicate :wait :select-model} intent) nil` 直接返回 `nil`。
  `src/hc/client/conversation.cljc:216` 的 `submit-send` 在 blocker 命中时返回
  `:outcome :send :blocked`，但 UI 只在 `:needs-decision`、`:fix` 与 `:blocked`
  的部分 intent 上渲染提示。
- **缺口**：`request-send!` 的前置短路在**渲染层**丢弃了按键，`submit-send` 的
  `:blocked` 结果根本没有机会产生。于是 `:duplicate`（上一轮的 send 回执还没到）、
  `:wait`（正在停止）、`:unattended`（无人值守运行中）这三种情况下，按 Enter 或点发送
  都没有任何可见反馈，草稿原地不动。
- **影响**：E2E 11 连续三次在「第 4 次发送」处失败，每次失败点都表现为
  「mainCalls 少一次」，而不是「发送被拒」。定位成本极高：它伪装成竞态。
  2026-11-11 的修法（`00d8b5a`）只覆盖了 `:disabled`/`:select-model`，因为那一次
  的复现恰好是「输入法刚填完、view-model 还慢一帧」。同类问题在 `:duplicate`
  上仍然存在。
- **建议**：`request-send!` 在**任何**短路分支上都应产生一条可见结果。若 `submit-send`
  能按 DOM 快照重新裁决（它已经有 `captured?` 分支会主动清掉 `command-pending?`），
  就让 `:duplicate` 也走快照路径，由 `submit-send` 决定是入队还是明确拒绝；不能让
  渲染层的 intent 单方面决定「什么都不做」。或者退一步：短路时发一条 `notify`，
  写明「上一轮提交尚未确认，请稍候」。
- **优先级**：中。它不损坏数据，但会持续把「拒绝」伪装成「竞态」，吞掉定位时间。

## D-52 `:duplicate` 与 `captured?` 的语义冲突缺少文档与测试

- **条款**：`AGENTS.md` §5.1（`command-id` 去重）、§7.4-2（不允许静默重复提交）。
- **位置**：`src/hc/client/conversation.cljc:194-215`。`captured?` 为真时无条件
  设置 `:command-pending? false` 与 `:run-active? false`，即「快照路径绕过一切阻塞」。
- **缺口**：`send-decision` 的注释说「`:duplicate` 描述在途命令，不允许静默重复提交」，
  而 `submit-send` 在 `captured?` 路径上恰好解除了 `:command-pending?` 的拦截。两条
  规则在代码里同时成立，靠调用方（`request-send!` 的 intent 白名单）决定谁生效。
  这个耦合没有任何测试固定，也没有注释解释为什么快照路径可以越过去重。
- **影响**：D-51 的修复若扩大白名单，可能引入真正的重复提交；若保持不变，
  `:duplicate` 继续静默。两条路都需要先把这个语义说清楚。
- **建议**：把「快照路径的语义」写成一条独立断言：快照代表操作员**此刻**的明确提交，
  因此它只解除 `:run-active?`（那是上一轮的自动续跑），不解除 `:command-pending?`
  （那是同一份内容的在途重复）。二者混为一谈是当前歧义的根源。
- **优先级**：中。它是 D-51 修复前必须先定的契约。

## D-53 材料页不显示它当前服务哪个会话，且会话切换后不重新请求

- **条款**：`AGENTS.md` §7.4-4（任务/资料视图必须能回答「这是谁的」）、§5.1（客户端与服务端对选中会话的认知必须可核对）。
- **位置**：`src/hc/client/materials/view.cljc:227`（只渲染 ready/未就绪徽标）；
  `src/hc/client/materials/model.cljc:336-344`（`cross-session?` 丢弃快照）；
  `src/hc/client/workbench/materials_bridge.cljs:507-544`（刷新条件）。
- **缺口**：服务端只回答「它当前会话」的材料快照；客户端选中会话与这个「当前会话」
  之间隔着一次命令往返。两者的 id 不一致时，`apply-snapshot` 正确地丢弃快照，但**没有
  任何一方重新发起对齐**：客户端不因为「丢弃了跨会话快照」而再次请求，服务端也不知道
  客户端想要哪个会话。结果是页面停在「材料库已就绪」+ 0 行，而服务端其实持有别的会话
  的数据。界面上没有任何字段能让操作员发现「这块面板描述的不是我选中的会话」。
- **实证**（2026-11-11，E2E 11）：
  `{"ready":true,"revision":16,"sessionId":"hc-session-509ab5ac…","memoryCount":0,`
  `"ui":{"ready":false,"revision":null,"selectedSessionId":"hc-session-e6df4b65…"}}`
  —— 服务端说会话 A 有 16 号 revision，客户端投影停在被清空的会话 B 上。
- **建议**：
  1. 材料视图显示它对应的 `session-id`（截断即可）与会话标题，让不一致可被看见。
  2. `apply-snapshot` 丢弃跨会话快照时，桥接层应记录「待对齐」并在下一次可用时机重新
     请求当前选中会话的快照，而不是等用户手动刷新页面。
  3. `openMaterialsMemory` 这类「等就绪」的判据应由产品提供：就绪必须同时意味着
     「描述的是当前会话」。
- **优先级**：中高。它不影响数据正确性（快照被正确丢弃），但会让操作员看到错的空白面板，
  并且是 E2E 11 反复失败的直接原因。

## D-54 `cross-session?` 的丢弃是静默的，没有诊断出口

- **条款**：`AGENTS.md` §3.10-7（普通拒绝必须含类别与可重读入口）。
- **位置**：`src/hc/client/materials/model.cljc:343`：`(if (or stale? cross-session?) state`。
- **缺口**：`state` 原样返回，既不记 `:error`，也不记 `:last-rejected-snapshot`。事后无法
  从 `:ui/materials` 判断「上一次快照是因为跨会话被丢弃」还是「根本没有快照到达」。
  这与 §3.10-7 要求的可解释性相反。
- **影响**：D-53 因此无法被自诊断；E2E 11 的定位只能靠额外加探针。
- **建议**：在 `state` 上保留 `:last-rejected {:reason :cross-session :snapshot-session-id …}`
  （短字段，不含正文），供视图与探针读取。丢弃仍然是丢弃，但理由要留下。
- **优先级**：中。它是 D-53 修复的配套。
