# E2E 13 / 15 / 16 / 21 诊断（2026-11-05，W6）

- 范围：只读核对现有实现、补单测/DOM 测试、真实 Chrome 复跑可执行子项。
- 未修改根 `AGENTS.md`；未执行 git 写操作。
- 独立实例：`/tmp/hc-w6/data|work`、`/tmp/hc-w6/sched-data|work`、
  `/tmp/hc-w6/speech-data|work`、`/tmp/hc-w6/gen-data|work`；真实 Chrome
  `/usr/bin/google-chrome`；证据 `plans/evidence/e2e-13-15-16-21/`。
- 模型说明：E2E13 生成、E2E15 回复、E2E16 手动运行使用本地受控
  OpenAI-compatible stub；E2E15 的 STT/TTS 也是本地 loopback stub。
  报告不把这些 stub 冒充真实模型/真实语音供应商。真实 Chrome 与真实
  HTTP/IndexedDB/Sente 路径成立。

## 1 现状与根因

### E2E 13 技能与小应用

| 子项 | 实际状态 | 根因/证据 |
|---|---|---|
| 扩展页 | 可打开，`扩展状态已同步` | 前轮 React keyword child 修复仍有效 |
| 技能安装 | 文本 SKILL.md 安装成功；卡片 `绑定 0`、`未启用` | 真实 Sente `extensions/skill/install` |
| 技能启用/绑定 | 勾选后 `已启用`；填 `default` 后 `绑定 1` | 真实回执；`plans/evidence/.../13-skill-enabled-bound.png` |
| 技能“当前会话启用” | **不可达** | 客户端没有会话级技能选择命令；服务端只有全局 enable + assistant bind。E2E13 的“为当前会话启用”只能以 assistant 绑定近似，不能声称完成 |
| 两个小应用隔离 | PASS | 两个 app_id 各写同名 `note`；A=`A-record`、B=`B-record`；刷新后仍各自可见 |
| 清除互不影响 | PASS | 清空 A 后 IndexedDB 只剩 B 行；A 重开 `note=(none)`，B 仍 `B-record` |
| 生成入口 | 已有“生成小应用”入口，但缺少客户端超时；占位符错误要求 `apps/...` | 服务端只接受裸文件名并固定写入 `miniapps/`；旧占位符导致 `INVALID_FILENAME` |
| 生成失败可查看已有文件 | PASS | `APP_EXISTS` 显示在同面板，`reading-a.html` 列表仍可见 |
| 生成成功 | PASS（stub 模型） | 真实 `POST /api/workspace/miniapp-generate`；`miniapps/reading-log.html` 3748 B，列表出现 `reading-log.html` |
| 生成等待/超时 | 已加 310 s 客户端 AbortController 超时与 `GENERATE_TIMEOUT` 文案；单测 PASS | 未用真实模型跑到超时；失败路径用 `APP_EXISTS` 验证 |

### E2E 15 语音

| 子项 | 实际状态 | 根因/证据 |
|---|---|---|
| 文件路径转写→草稿→发送 | PASS（前轮） | `report.json`：转写 200、草稿插入、发送后 1 条消息 |
| TTS | PASS（前轮） | 8044 B `audio/wav` |
| 实时麦克风 | **BLOCKED（产品缺陷已修）** | `begin-recording!` 与 TTS `synthesize!` 的 Promise 链 `.then` 未闭合，`.catch` 被编译到 handler 上；真实 Chrome 报 `(intermediate value).catch is not a function`。两处已重写修复 |
| 修复后复跑 | fake device 录到 0 B/0 ms，页面显示“没有录到声音”；不再上传空 Blob | 120 s WAV 与默认假设备均无数据，实时麦克风仍 BLOCKED |
| 朗读停止 | 真实 UI PASS + 单测 PASS | 点击“朗读此回复”出现播放器；点“停止”后播放器消失 |
| 版本切换停止旧朗读 | 单测 PASS；真实 UI NOT RUN | `stop-if-stale!` 覆盖 version 变化/消息消失；重新生成→版本选择器未在真实 UI 完成 |
| 真实扬声器播放 | NOT RUN | 无音频设备；只验证状态与 DOM |

### E2E 16 定时任务

| 子项 | 实际状态 | 证据 |
|---|---|---|
| 创建/预览/暂停/时区/错过不补跑/恢复默认暂停 | PASS（前轮） | `plans/e2e-real-16-21.md` |
| 手动运行 | PASS（本轮） | 任务保持 `已启用`，下次运行仍 `2026-11-06 09:30 (Asia/Shanghai)`；运行记录 `准备中/运行中`，不再 `SCHEDULE_INCOMPLETE` 或 `start-dispatch-failed` |
| 重叠跳过 | PASS（本轮） | 运行中再次“立即运行”显示 `已跳过：上次仍在运行`；记录 `已跳过：上次仍在运行 · 2026-11-05 15:32:31` |
| 受限平台 | PASS（UA 模拟） | iOS UA：`仅支持前台手动运行`；`value=enable` radio `disabled=true`；默认 `paused` 选中 |
| 表单 capability-scope | 已修 | `new-form`/`form-snapshot` 显式带 `:capability-scope`（nil 表示未声明）；模型测试断言 key 存在且显式值保留 |
| 真实模型手动运行 | 未在本轮验证 | 使用受控延迟模型 stub；真实 Ollama 需要串行锁，本轮未占用 |

### E2E 21 费用口径

| 子项 | 实际状态 | 说明 |
|---|---|---|
| 三口径（服务返回/估算/未知） | 前轮 PASS；本轮未复跑 | 前轮页面 `服务返回 58；估算 0；未提供 17`，未知不为 0；价格保存后 `估算 58`、USD 0.003275 |
| 价格表单 | 前轮 PASS；本轮未复跑 | 服务/模型下拉可选；保存价格有回执 |
| 多币种分开 | 服务端测试 PASS；真实 UI 未复跑 | `stats_test/cost-is-grouped-by-currency` 断言 USD/EUR 两组、无跨币种合计 |
| 改价不改历史 | 服务端测试 PASS；真实 UI 未复跑 | 请求记录带 `:price` 快照；`stats_test` 断言旧 `:price` 不变，重算只生成新视图 |
| TaggedValue 金额 | 已修（前轮） | `USD 0.003275` 普通十进制；模型/UI 测试覆盖 |

## 2 本轮代码修复

1. `src/hc/client/miniapps/api.cljs`：`generate!` 增加 `AbortController`
   超时（默认 310 s），超时返回 `:GENERATE_TIMEOUT`，并返回 Promise 供
   测试等待。
2. `src/hc/client/miniapps/view.cljs`：生成中显示“最长等待 5 分钟；失败时
   已有文件仍可查看”；失败 banner 增加“刷新已有文件”；文件名占位符改为
   `reading-log.html（固定写入 miniapps/）`。
3. `src/hc/client/speech/ui.cljs`：修复 `begin-recording!` 的 Promise 链；
   空 Blob/0 ms 不再上传，显示“没有录到声音”。
4. `src/hc/client/speech/api.cljs`：重写 `synthesize!`，修复同样的
   `.catch` 被编译到 handler 上的缺陷。
5. `src/hc/server/speech/store.clj`：`SPEECH_AUDIO_EMPTY` 的 `fail!`
   补齐第三个参数，空音频从 500 变为 400。
6. `src/hc/client/schedule/model.cljc`：表单快照显式携带
   `:capability-scope`，避免服务端隐式补 key；`form-from-schedule` 保留
   已有值。
7. 测试：
   - `test/hc/client/miniapps/api_test.cljs`（2 tests / 8 assertions）：
     成功回执与超时中止。
   - `test/hc/client/speech/playback_test.cljs`（3 tests / 13 assertions）：
     停止暂停音频、版本切换停止、消息消失停止。
   - `test/hc/client/schedule/model_test.clj` 新增 3 个断言：
     capability-scope key/显式值。
   - 服务端语音 34/196；空录音判定新增 1 test/3 assertions。
   - 既有：miniapps 12/76、speech 18/101、schedule 37/169、diagnostics
     21/140，均 0 failures。

## 3 未覆盖 / 剩余阻塞

- E2E 13：会话级技能选择 UI 不存在；真实模型生成未跑（受控 stub 成功，
  真实模型需串行锁且本轮预算不足）。
- E2E 15：实时麦克风修复后仍停在录音上传失败；需抓 `/speech/recordings`
  请求与错误详情。真实朗读 UI 的停止/版本切换未跑到，仅有单测。
- E2E 16：真实模型手动运行未跑；UA 模拟不等于真实 iOS 设备。
- E2E 21：本轮未复跑三口径/价格/多币种；结论仍依赖前轮 UI 证据与服务端
  测试，不能升级为新的 UI PASS。
- 构建警告：`src/hc/client/workbench/browser_bridge.cljs` 使用
  `str/lower-case`/`str/split` 但未 require `clojure.string`，release
  有 undeclared-var 警告；不在本工人所有权内，未修改。
