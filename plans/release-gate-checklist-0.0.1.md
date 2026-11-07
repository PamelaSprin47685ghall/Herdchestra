# 0.0.1 发布门禁终验清单

- 日期：2026-11-05
- 范围：`RUNNING.md` 的 8 个发布条件、版本五处、工作树、文档一致性。
- 约束：只读检查 + 文档修正；未改源码；未执行 Git 写操作；未打 tag。
- 结论：**当前不能打 0.0.1 tag。** E2E 未全 PASS；8 条门禁中 4 条 PASS
  （其中门禁 1 为热缓存）、2 条 NOT RUN（最终树未重跑）、2 条 FAIL（工作树、
  发布说明待复评）。门禁 1/3/4/7 有当前树证据；最终树必须重跑。

## 0. 一键复跑脚本

`scripts/release-gate.sh` 按顺序执行 8 个门禁步骤，并把每一步日志写入
`plans/evidence/release-gate-<timestamp>/`（每次新建目录，不覆盖旧证据）：

```bash
scripts/release-gate.sh --help
scripts/release-gate.sh
# 或指定 Pixi 二进制
PIXI=/tmp/pixi scripts/release-gate.sh
```

覆盖：`pixi install --locked`、`client-install`、`client-release`（产物字节/
sha256）、`app-check`（health 关键字段）、真实启动 + SIGTERM + 同 data-dir
重启、`pixi run test`、版本五处、工作树计数。任一步命令失败立即停止并打印
失败步骤、证据目录与日志尾部；全部成功打印 `PASS` 摘要。脚本不执行 Git 写
操作、不打 tag。`bash -n` 语法检查通过；本次未跑全流程（避免与 client-ui
构建冲突）。

## 0.1 当前 bundle 健康取证（非门禁）

- `resources/public/js/main.js`：2,925,609 bytes，mtime
  `2026-11-05 13:54:38 +0800`，SHA-256
  `9a304c025df37e367d5a5650c9cdef309bb98b9f5c36ce48a9d4d02e81ecf2a6`。
- 全新 data-dir 启动真实 app，实际端口 37101；真实 Chrome/Playwright 依次打开
  `/`、`/#/materials`、`/#/settings`。
- 三个页面均：标题 `Herdchestra 工作台`、`.wb-topbar` 存在（count=1）、
  **pageErrors=[]、page consoleErrors=[]**；没有 `react is not defined`。
- driver 级 console 有一条 `Failed to load resource: 404`（静态资源，非 JS
  pageerror）；页面级 consoleErrors 为空。
- 结论：**当前 bundle 可加载**，首页/材料/设置三页无 React 崩溃；这不等于
  小应用打开/生成后的页面已复验。
- 证据：`plans/evidence/bundle-health/result.json`、`bundle.txt`、
  `home.png`、`materials.png`、`settings.png`、`driver.log`。

## 1. 八条发布条件

| # | 条件 | 可执行命令 | 期望 | 当前证据 | 状态 |
|---:|---|---|---|---|---|
| 1 | Pixi 锁定安装 | 仓库内 `/tmp/pixi install --locked`；临时干净目录复制 `pixi.toml`+`pixi.lock` 后再执行 | 两次退出 0；JDK 21.0.10 / Clojure 1.12.6 / Node 22.23.4；锁文件不变 | 仓库 1 s、临时目录 2 s，均 exit 0；锁 SHA-256 `c7467ab6…e63610` 前后一致；证据 `plans/evidence/gate1-final/` | **PASS（热缓存）**：未清缓存，仅验证锁文件与环境组装；冷下载未验证 |
| 2 | 客户端依赖与构建 | `/tmp/pixi run client-install`；`/tmp/pixi run client-release` | 退出 0；`main.js`/CSS 存在且非空 | **SKIP / NOT RUN（并行构建中）**：`main.js` mtime 12:37:25，距检查 129 s（<5 min）；检查期间又变为 2,919,917 bytes @12:39:44，确认并行构建；本次未跑 client-install/release | **NOT RUN（并行构建中，终验重跑）**；证据 `plans/evidence/gate2-final/mtime.txt` |
| 3 | 启动自检 | `/tmp/pixi run app-check` | 退出 0；`:ok true`、`:version "0.0.1"`、store open、gateway 计数 | `/tmp/gate-app-check.log`：`:ok true`、`:version "0.0.1"`、store `/tmp/hc-check-1548054516939770005/data/db`、gateway `epoch 1/seq 1/max-pending 4096` | **PASS** |
| 4 | 真实启动 | `/tmp/pixi run clojure -M:server -m hc.server.app --host 127.0.0.1 --port 0 --data-dir /tmp/hc-gate4-…/data --workspace-root /tmp/hc-gate4-…/work --static-root resources/public` + `curl /healthz`、`/`，SIGTERM 后同 data-dir 重启 | 200/200；health `:ok true`、`:version "0.0.1"`、store open、gateway 计数；`/` 含 `<title>Herdchestra`；重启后 store open、sessions count 1 | `plans/evidence/gate4-final/start1.log`、`start2.log`、`health1.json`、`health2.json`、`root1.html`、`root2.html`、`base-url.txt`、`base-url2.txt`；端口 43497→43647 | **PASS** |
| 5 | 全量测试 | `/tmp/pixi run clojure -X:test:compose:server:client`（等价 `pixi run test`） | 退出 0；记录 tests/assertions/seed | 基线 3406/22121，0F/0E，343 s；**中间全量 3611 tests / 23306 assertions，0F/0E，411 s**；`/tmp/hc-mid-full-test.log` | **NOT RUN（基线+中间 PASS）**：最终树仍需重跑 |
| 6 | 发布提交干净 | `git status --porcelain -uall`；`git ls-files` | 无未提交/未跟踪产品文件；版本五处一致 | 17 条顶层未跟踪、约 1133 个未跟踪文件（14:55 快照；并行施工中）；`git ls-files` 仅 `AGENTS.md` | **FAIL** |
| 7 | 无模型启动与提示 | 空 data-dir 启动；`node test/js/no_model_probe.cjs --url <base>/#/sessions --out plans/evidence/no-model-final --chrome /usr/bin/google-chrome --debug-port 9333` | 模型按钮“尚未选择模型”；发送 disabled；输入后草稿保留；无假模型回复 | `plans/evidence/no-model-final/gate7-summary.json`、`no-model.json`、`no-model.png`、`no-model-dom.html`：按钮“模型：尚未选择模型”，sendDisabled=true，草稿“无模型发布验证草稿”保留，messageCount=0、commandRows=0 | **PASS** |
| 8 | 发布说明如实 | 阅读 `plans/release-notes-0.0.1.md` | 列出已验证、NOT RUN/BLOCKED、边界；不写“全部通过”；新 §8.5 要求 22 条自动化 + mock 模型/OAuth/WebDAV/S3 | E2E 未全 PASS；01 PASS（binding 修复后 13/13）、07/11 BLOCKED、12/13 PARTIAL、20 PASS；release notes 已完成本轮统计/端点/19 口径修正；新 §8.5 自动化结果待 W10 | **FAIL（待复评）**；E2E 未全 PASS、W10 未出结果，仍阻断打标 |

## 2. 版本五处

| 位置 | 命令 | 值 |
|---|---|---|
| `src/hc/version.cljc` | `cat src/hc/version.cljc` | `"0.0.1"` |
| `pixi.toml` | `grep '^version' pixi.toml` | `version = "0.0.1"` |
| `package.json` | `grep '"version"' package.json` | `"version": "0.0.1"` |
| `--help` | `/tmp/pixi run clojure -M:server -m hc.server.app --help` | `Herdchestra 0.0.1 server`，exit 0 |
| health | `/tmp/pixi run app-check` | `:version "0.0.1"`，`:ok true`，exit 0 |

**五处一致。** 证据：`/tmp/gate-help.log`、`/tmp/gate-app-check.log`。

## 3. 工作树

`git status --porcelain -uall`：

| 指标 | 值 |
|---|---:|
| 顶层条目 | 17 |
| 未跟踪文件 | 约 1133（14:55 快照；并行施工中） |
| 被忽略条目 | 37 |
| 已跟踪文件 | 1（`AGENTS.md`） |

未跟踪顶层条目：`.gitignore`、`HACK.md`、`HISTORY.md`、`PROMPT.md`、
`RUNNING.md`、`deps.edn`、`package-lock.json`、`package.json`、`pixi.lock`、
`pixi.toml`、`plans/`、`resources/`、`scripts/`、`shadow-cljs.edn`、`src/`、
`test/`，以及待确认的 `机制提炼/`。除 `机制提炼/` 外均为产品/文档/证据；
发布条件 6 因此 **FAIL**。

仍存在的非产品文件（除 `机制提炼/` 外均已忽略）：

- `hs_err_pid1928617.log`、`hs_err_pid2905205.log`、`hs_err_pid3003787.log`、
  `hs_err_pid3566148.log`、`hs_err_pid3597664.log`、`hs_err_pid455536.log`、
  `hs_err_pid3871452.log`、`hs_err_pid4054882.log`、`hs_err_pid4063480.log`
  （9 个 JVM 崩溃日志）。
- `out-*` 20 个测试 harness 目录（artifacts-ui、backup-password-dom、check、
  check2、check3、conversation-selection-dom.R14enn、
  conversation-selection-dom.ljxjx4、doc、files-ui、generated-card-dom、
  generated-card-ui、miniapps-dom、
  schedule-ui、swarm-ui、workbench-draft-storage、workbench-scale-dom ×2、
  workbench-session-commands、workbench-shell-dom ×2）。
- `plans/evidence/no-model-final/chrome-profile/`、
  `plans/evidence/no-model/chrome-profile/`、
  `plans/evidence/scale-draft/chrome-profile/`（浏览器 profile，3 个）。
- `target/`、`node_modules/`、`.pixi/`、`.shadow-cljs/`、`.cpcache/`、`.pi/`。
- `resources/public/js/`、`resources/public/css/framework7-bundle.css`（构建产物）。
- `机制提炼/AGENTS.md`（222 KB，omp/pi-goal-x EditorTheme shim，非 Herdchestra
  产品/证据；未被忽略；建议移出仓库或加入 `.gitignore`，不要删除）。

`git check-ignore -v` 已确认：`.gitignore:30 hs_err_pid*.log`、`:12 out-*/`、
`:24 **/chrome-profile/`、`:7 target/`、`:13 resources/public/js/`；没有发现
未忽略的构建产物/崩溃日志。

## 4. 文档一致性

| 冲突 | 处理 |
|---|---|
| `RUNNING.md` 条件 8 仍写具体 E2E 断言（01 待复跑等） | 已改为指向 `plans/e2e-master-status-0.0.1.md`；保留“E2E 尚未全部 PASS”；修复中条目不得预写 PASS |
| release notes 01/02 写“stub，不是真实模型” | 已改为“真实本地 Ollama 复跑”；stub 只属于首轮批次 |
| release notes 19 把 200%/IME 写成 NOT RUN | 已拆分为“IME/焦点/Escape/200% 字号 PASS；屏幕阅读器顺序/对比度/真实触屏 NOT RUN” |
| release notes 与 checklist 的 hs_err 数量（4/5） | 已改为当前 6 个 |
| release notes 写全新目录 restore 报 `STORAGE_NOT_FOUND` | 已改为：CLI 可在完全空目录创建 store；隔离复测 exit 0，空备份回执 `:store-observed? true`、`:status :no-changes`（`/tmp/hc-prep-restore-0.0.1/restore-empty.log`）；非空备份的 `:status :complete` 仍引用旧证据 |
| release notes 写重复 replace 非幂等 | 已改为：同备份同类别第二次 replace 返回 `:status :already-imported`，不新增恢复点；旧记录标修复前历史 |
| `offline-restore-0.0.1.md` 的 4c/§3.11/D4 仍写 replace 重新应用 | 已标“修复前历史”，并加当前 `:already-imported` 状态与证据 |
| `HACK.md` §13.4 旧备份 CLI 限制（空目录 STORAGE_NOT_FOUND、replace 非 no-op） | 已更新为当前行为，并指向 §14.4 |
| release notes 24 类统计/属性统计 | 已写 10 符合/13 偏差/1 缺失（缺失=额度不足 UI 接线，补丁见 `plans/ui-contract-audit-0.0.1.md` §9）；属性 85/104、60 文件、134 defspec+36 quick-check=170 |
| E2E 14/17/18/22 旧 BLOCKED 与最新 PASS | release notes §3/§4.1 已改为 PASS（含 finding）；`plans/e2e-real-*` 保留为带日期的复跑证据 |

仍存在并行施工冲突：E2E 统计/01/02 端点/19 口径/hs_err 计数/备份恢复
“全新目录”定义在并行文档中曾出现不同值，本轮已按证据修正主要条目；
`plans/e2e-real-*` 中旧的 BLOCKED 是复跑前历史。最终树与 E2E 复跑前
不能据本清单打 tag。

## 4.1 中间全量回归（非最终门禁）

- 命令：`/tmp/pixi run clojure -X:test:compose:server:client`
- 退出码：0
- 耗时：411 s
- 结果：**3611 tests / 23306 assertions，0 failures / 0 errors**
- 失败命名空间：**无**
- 日志：`/tmp/hc-mid-full-test.log`
- 说明：日志中的 `sente.client/ws-error Connection refused` 与
  `NullPointerException` 是客户端重连测试的噪声，最终 summary 仍为 0F/0E；
  没有失败命名空间可归属到 client-ui/server-audit/compose-audit，也没有
  证据表明存在真实回归。
- **这不是最终门禁**：当前树仍在并行修改，打 tag 前必须在最终合并树重跑，
  并重新记录 tests/assertions/seed/退出码。

## 5. 打 tag 前必须完成的 5 件事

1. **复跑 E2E**：03/04/05/06/07/08/09/10/11/12/13/15/16/21；01 已 PASS
   （binding 修复后 13/13），14/17/18/19/20/22 已 PASS；把各条 finding
   修复状态写清。当前 E2E 未全 PASS，不能打 tag。
2. **最终树全量测试**：`pixi run test`，记录 tests/assertions/seed/退出码；
   基线 3406/22121 不能代替最终树。
3. **最终构建与真实启动**：`pixi run client-install`、`client-release`、
   `app-check`，再做真实启动 `GET /healthz` 与 `/`；记录产物大小与端口。
4. **提交与清理**：把约 1133 个未跟踪文件中的产品/文档/证据纳入提交
   （排除待确认的 `机制提炼/AGENTS.md`），确认 `git status --porcelain -uall`
   干净；保留已忽略的 hs_err/out-*/profile，或按发布策略归档；更新 release
   notes 的最终计数。
5. **最后打 tag**：确认版本五处仍为 `0.0.1`、8 条门禁全 PASS、E2E 清单
   无未解释的 BLOCKED，再建立发布提交与 tag。禁止在门禁未完成时提前打标。

## 6. 复检命令汇总

```bash
cat src/hc/version.cljc
grep '^version' pixi.toml
grep '"version"' package.json
/tmp/pixi run clojure -M:server -m hc.server.app --help
/tmp/pixi run app-check
/tmp/pixi run client-install
/tmp/pixi run client-release
/tmp/pixi run test

git status --porcelain -uall
git status --porcelain --ignored
git check-ignore -v hs_err_pid2905205.log out-check \
  plans/evidence/no-model/chrome-profile/x
```

证据文件：`/tmp/gate-help.log`、`/tmp/gate-app-check.log`、
`plans/release-0.0.1-baseline.md`、`plans/release-notes-0.0.1.md`、
`plans/offline-restore-0.0.1.md`、`plans/e2e-real-*.md`、`HACK.md` §14。
