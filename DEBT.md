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
