# Herdchestra E2E 10 / 13 实跑报告（2026-11-05）

> 真实 app + 真实 Chrome。未修改产品源码；未执行 git 写操作。
> 结论：10 BLOCKED（产品缺口：无用户可见 holder shell 入口）；
> 13 PARTIAL（小应用 PASS；技能安装/启用/绑定/越界/更新保留本地 PASS；绑定选择器仍缺）。

## 1 E2E 10 工具中断后的未知结果

### UI 入口核查

在客户端搜索 holder shell / call / 运行命令 / 终端相关控件：

- `src/hc/client/swarm/model.cljc:247,694-913` 只有看板上的
  “W held — managed shell running”等**状态投影**，没有发起 holder shell
  动作的按钮或表单。
- `src/hc/client/workbench/views/shell.cljs` 是应用外壳，不是命令入口。
- `src/hc/client/extensions/view.cljc:300` 明确“程序与参数分开，不经过
  shell”，且那是 MCP 配置，不是 holder 动作。
- 未发现 `holder`/`:shell` 的用户可见提交控件；holder shell 只在
  execution tenure 内部由 Owner/actor 运行时发起。

**判定：产品缺口。** 当前工作台没有用户可见的 holder shell 入口，无法从
UI 制造“在途 shell + 宿主死亡”。这对应 AGENTS.md §7.4 旅程 3.5（蜂群施工）要求的
`$W$`/Shell 视图与人工可操作入口尚未落地。

### 组件级证据（不是 UI）

- `/tmp/hc-e2e/20261105-0811-exec/E2E-10/recovery.log`：
  `hc.server.app.owner-report-restart-test`、
  `hc.server.app.restart-smoke-test`、
  `hc.server.recovery.restart-smoke-test`、`hc.server.recovery-test`
  共 25 tests / 155 assertions，0 failures / 0 errors。
- 该组测试覆盖重启后的 pending/outcome-unknown 与恢复扫描；但测试输出
  没有逐条打印 `:outcome-unknown` UI 回执，不能冒充真实 UI 证据。

### 结论

**BLOCKED（产品缺口 + 组件证据）**。没有 UI holder shell 入口，真实
“在途 shell → 杀宿主 → 重启 → UI 核对 → 结清”未执行；组件级 25/155
通过只作为相邻证据。

## 2 E2E 13 技能与小应用

### 服务端路由

`POST /api/workspace/miniapp-generate` 已可用：

- `application/edn` → 415；
- 空 body → `INVALID_PROMPT "prompt 必须是字符串"`；
- 文件名含 `/` → `INVALID_FILENAME "文件名不能包含 /；小应用固定写入
  miniapps/ 目录"`；
- 合法 `prompt` + `filename=reading.html` → **HTTP 200**：
  `{:ok? true :app {:path "miniapps/reading.html" :app-id "app-..."}}`。
- 生成文件 `/tmp/hc-e2e-harness/e2e13/work/miniapps/reading.html` 存在。

### 面板按钮/控件

真实 Chrome `#/materials → 小应用`：

- 页面有 `details.hc-miniapp-generate`，summary 文本“生成小应用”；
  展开后有：
  - textarea `aria-label="小应用生成提示词"`；
  - input `aria-label="小应用文件名"`；
  - button “生成小应用”。
- 因此“面板缺生成按钮”不成立；按钮存在但**默认折叠在 details 内**。
- 打开 `miniapps/reading.html` 后，工作台崩溃：
  `ReferenceError: react is not defined`（`main.js:4723`），页面显示
  “工作台启动失败”。应用卡本身显示 `reading.html / 文件可用 / 数据 0 B`，
  但无法继续输入三条记录、重开、第二个小应用隔离或 IndexedDB 键清单。

### 生成文件质量问题

`reading.html` 的保存脚本包含无效 JS：

```js
document.getElementById('title').value trim ? ...
```

`value trim ?` 是语法错误；即使面板不崩溃，保存按钮也会报错。该文件使用
`localStorage`，不是 IndexedDB；隔离断言无法按 IndexedDB 键完成。

### 历史结论（2026-11-05 早先复跑）

**BLOCKED（历史）**。当时路由已 200 并生成文件；面板生成控件存在
（折叠），但打开小应用即 `react is not defined` 崩溃；生成 HTML 还有语法
错误。技能导入/启用、三条记录、重开、第二个小应用隔离未执行。

### 证据

- `/tmp/hc-e2e-harness/e2e13/gen4.json`（200）
- `/tmp/hc-e2e-harness/e2e13/route*.json`（415/400）
- `/tmp/hc-e2e-harness/e2e13/ui-probe.json`（生成控件存在）
- `/tmp/hc-e2e-harness/e2e13/open-app.json`（react is not defined）
- `/tmp/hc-e2e-harness/e2e13/work/miniapps/reading.html`
- `/tmp/hc-e2e-harness/e2e13/reading-open.png`

### 2026-11-05 复跑（真实 Chrome，小应用 UI 闭环）

客户端 bundle 已可加载：`curl` 首页 200，Playwright 加载后
`pageerror` 为空，`.wb-topbar` 存在。使用全新 app/data/workspace，通过真实
UI 操作，不用 curl 代生成。

- 材料与扩展 → 小应用 → 展开“生成小应用”。
- A：提示词 `记录读书条目：书名输入框和列表，数据存本地` → app
  `app-4aa9d520-0a8a-4b5e-ae45-e8d7d7894b4f`，
  `miniapps/reading-ui-a.html`。
- B：提示词 `记录观影条目：电影名输入框和列表，数据存本地` → app
  `app-9d7162ef-7449-411d-9d64-89df441d7c26`，
  `miniapps/movie-ui-b.html`。
- A 写入 `A书`；关闭后重开 A，`A书` 仍在。
- B 写入 `B书`；B 看不到 `A书`，A 看不到 `B书`。
- A 的 iframe 存储前缀
  `miniapp:app-4aa9d520-...:`，键
  `miniapp:app-4aa9d520-...:myBooks`，值 `["A书"]`；B 的前缀/键为
  `miniapp:app-9d7162ef-...:` / `...:myMovies`，值 `["B书"]`。
- A 执行“数据 → 清空数据 → 确认清空”后，A 的 iframe 存储与文本为空；
  B 仍保留 `B书`。最终 IndexedDB `kv` 只剩 B 的带前缀键，两个 `apps`
  记录都在。
- 负向提示词 `不要生成 HTML，只输出纯文本：这不是一个 HTML 文档`：
  HTTP 422；UI 显示红色 banner
  `INVALID_HTML · 模型输出不是完整的 HTML 文档（需要 <html> 和 </html>）`；
  列表仍为两行；工作区没有新增文件。
- `pageErrors` 为空；控制台只有 favicon 404 和负向请求的预期 422。

**小应用部分结论：PASS。** 技能导入/启用部分未在本轮复跑，因此 E2E 13
整体标记为 **PARTIAL**。

证据：

- `/tmp/hc-miniapp-ui/evidence/ui-e2e.json`
- `/tmp/hc-miniapp-ui/evidence/key-lists.json`
- `/tmp/hc-miniapp-ui/evidence/generate-reading-ui-a.edn`
- `/tmp/hc-miniapp-ui/evidence/generate-movie-ui-b.edn`
- `/tmp/hc-miniapp-ui/evidence/generate-not-html.edn`
- `/tmp/hc-miniapp-ui/evidence/01-miniapps-before.png` …
  `09-negative.png`
- `/tmp/hc-miniapp-ui/evidence/ui-e2e.log`
- `/tmp/hc-miniapp-ui/work/miniapps/reading-ui-a.html`、
  `movie-ui-b.html`

### 技能部分（同一真实 app/Chrome）

使用 `e2e-skill.zip`（`SKILL.md` + `notes.txt` + `templates/report.md`）
通过“材料与扩展 → 扩展 → 技能 → 压缩包”安装。

- 安装后卡片显示 `未启用`、`绑定 0`，说明与三个文件清单可见
  （`SKILL.md` 193 B、`notes.txt` 16 B、`templates/report.md` 16 B）。
  技能表单没有单独的“预览”按钮；这里的预览是安装后的技能卡。若要求
  安装前预览，这是 UI 缺口。
- 勾选启用并绑定 `default`（内置“通用助手”）后，卡片显示 `已启用`、
  `绑定 1`，checkbox 为选中；服务端 catalog 记录 `:enabled? true`、
  `:bindings #{"default"}`。
- 恶意压缩包（`SKILL.md` + `../../evil.txt`）安装被拒，UI 错误为
  `path must not contain '..'`；技能卡仍只有 1 个，原技能内容不变。
- 更新冲突：把已安装 `SKILL.md` 在磁盘追加 `LOCAL EDIT`，上游改为
  `UPSTREAM V2` 后比较，UI 显示 `冲突 1`，`SKILL.md` 行为 `冲突`；
  `notes.txt` / `templates/report.md` 为 `上游删除`。点击
  `应用并保留本地` 后 UI 显示 `已应用，待复核`，磁盘仍是 `LOCAL EDIT`，
  未被 `UPSTREAM V2` 覆盖。
- 客户端早先的 archive select / apply disabled 两个缺口已由 client-audit
  修复并在本轮真实 UI 复跑通过；本轮没有使用 DOM workaround。
- **仍缺**：世界书/技能绑定控件是“助手 ID”文本输入，不是展示
  “通用助手”的选择器。服务端已通过 `:settings/assistants` 暴露
  `default / 通用助手 / revision 1`；client 需要接选择器。真实世界书
  冒烟用文本输入 `default` 绑定成功。

**默认助手（服务端）**：

- 全新 data-dir 启动后，默认会话 `:assistant-id` 为 `default`，材料页
  显示 `助手范围 default`。
- 新建世界书后绑定 `default`，卡片显示 `绑定 default`。
- `settings/snapshot` 暴露 `:settings/assistants` 的
  `default`（名称 `通用助手`，revision 1）；`session-summary` 发布
  `:assistant-id`，outbound context 发布
  `:assistant {:id :name :revision}`。
- 测试：`hc.server.app.assistant-default-test` 5 tests / 26 assertions；
  与 settings/outbound/sessions 合并 86 tests / 504 assertions，0 failures。

**技能部分结论：PASS（功能）；绑定选择器仍为 client 缺口。** 因此 E2E 13
整体仍标记 **PARTIAL**。

证据：

- `/tmp/hc-default-assistant/evidence/skills-e2e.json`
- `/tmp/hc-default-assistant/evidence/skills-fixed.log`
- `/tmp/hc-default-assistant/evidence/10-skill-installed.png` …
  `14-skill-update-kept-local.png`
- `/tmp/hc-default-assistant/evidence/worldbook-bound.png`
- `/tmp/hc-default-assistant/evidence/worldbook-binding.txt`
- `/tmp/hc-default-assistant/evidence/tests.log`
- `/tmp/hc-default-assistant/data/extensions/catalog.edn` 与技能目录
- `/tmp/hc-miniapp-ui/skills/e2e-skill.zip`、`evil.zip`

## 3 总结

| E2E | 结论 | 缺口 |
|---|---|---|
| 10 | BLOCKED | 无用户可见 holder shell 入口；只有组件级 25/155 |
| 13 | PARTIAL | 小应用 PASS；技能安装/启用/绑定/越界/更新保留本地 PASS；世界书/技能绑定仍是文本输入，缺“通用助手”选择器 |
